package com.gesturemouse

import android.content.Context
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * An ordinary touch trackpad, for when you don't want to wave your hand around.
 *
 * Deliberately mirrors laptop trackpad conventions rather than inventing new
 * ones: one finger moves, tap clicks, two fingers scroll, two-finger tap is a
 * right-click, and tap-then-hold starts a drag.
 */
class TrackpadSurface @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mouse: HidMouse? = null

    /** Pixels of cursor travel per pixel of finger travel. */
    var sensitivity = 1.2f

    /** Two-finger travel per scroll notch at scroll speed 1. */
    private val scrollDivisor = 26f

    /** Scroll speed multiplier from [Settings.scrollSpeed]. */
    var scrollGain = 1f

    /** Fed with one-finger pointer movement; see [SensitivityTracker]. */
    var tracker: SensitivityTracker? = null

    private val tapSlop = 18f          // px of movement still considered a tap
    private val tapTimeout = 260L      // ms — longer than this is a drag, not a tap
    private val dragHoldTimeout = 320L // ms window for the second tap of tap-and-hold

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private var pointerCount = 0
    private var maxPointers = 0

    private var lastTapUpAt = 0L
    private var dragging = false

    private var scrollAccum = 0f
    private var lastScrollY = 0f
    private var scrolling = false

    private var glow = 0f

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dim)
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    private val touchDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.brandAccent)
    }
    private var touches = mutableListOf<Pair<Float, Float>>()

    private val borderDragging = context.themeColor(R.attr.stateWaiting)
    private val borderGlow = context.themeColor(R.attr.brandAccent)
    private val borderIdle = ContextCompat.getColor(context, R.color.line)

    override fun onDraw(canvas: Canvas) {
        val r = RectF(2f, 2f, width - 2f, height - 2f)
        border.color = when {
            // amber, not the crimson `fault`: dragging is a live state, not an
            // error, and it has to stay distinct from the neon-red glow
            dragging -> borderDragging
            glow > 0f -> borderGlow
            else -> borderIdle
        }
        canvas.drawRoundRect(r, 10f, 10f, border)

        if (touches.isEmpty()) {
            canvas.drawText(
                if (dragging) "dragging" else "trackpad",
                width / 2f, height / 2f + 12f, label
            )
        }
        for ((x, y) in touches) canvas.drawCircle(x, y, 26f, touchDot)

        if (glow > 0f) {
            glow -= 0.08f
            postInvalidateOnAnimation()
        }
    }

    private fun flash() {
        glow = 1f
        invalidate()
    }

    private fun syncTouches(e: MotionEvent) {
        touches = MutableList(e.pointerCount) { i -> e.getX(i) to e.getY(i) }
        invalidate()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = mouse
        val now = System.currentTimeMillis()

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                downAt = now
                moved = false
                maxPointers = 1
                pointerCount = 1
                scrolling = false
                scrollAccum = 0f
                tracker?.begin(now)

                // second tap of a tap-and-hold: press and keep holding
                if (now - lastTapUpAt < dragHoldTimeout && !dragging) {
                    dragging = true
                    m?.buttonDown(HidMouse.BUTTON_LEFT)
                    flash()
                }
                syncTouches(e)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = e.pointerCount
                maxPointers = maxOf(maxPointers, pointerCount)
                if (pointerCount == 2) {
                    lastScrollY = (e.getY(0) + e.getY(1)) / 2f
                    scrollAccum = 0f
                }
                syncTouches(e)
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    // two fingers: scroll, and never move the cursor
                    scrolling = true
                    val midY = (e.getY(0) + e.getY(1)) / 2f
                    scrollAccum += (lastScrollY - midY)
                    lastScrollY = midY
                    val divisor = scrollDivisor / scrollGain
                    val notches = (scrollAccum / divisor).toInt()
                    if (notches != 0) {
                        m?.scroll(notches)
                        scrollAccum -= notches * divisor
                        moved = true
                    }
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (hypot(e.x - downX, e.y - downY) > tapSlop) moved = true
                    if (!scrolling) {
                        m?.move(dx * sensitivity, dy * sensitivity)
                        // only while actually steering: a drag held with the
                        // button down is aiming too, but scrolling isn't
                        tracker?.move(dx * sensitivity, dy * sensitivity, now)
                    }
                }
                syncTouches(e)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = e.pointerCount - 1
                syncTouches(e)
            }

            MotionEvent.ACTION_UP -> {
                tracker?.lift()
                val quick = now - downAt < tapTimeout && !moved
                if (dragging) {
                    dragging = false
                    m?.buttonUp(HidMouse.BUTTON_LEFT)
                } else if (quick) {
                    if (maxPointers >= 2) {
                        m?.click(HidMouse.BUTTON_RIGHT)
                    } else {
                        m?.click(HidMouse.BUTTON_LEFT)
                        lastTapUpAt = now      // arms tap-and-hold
                    }
                    flash()
                }
                touches.clear()
                scrolling = false
                maxPointers = 0
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    m?.buttonUp(HidMouse.BUTTON_LEFT)
                }
                touches.clear()
                invalidate()
            }
        }
        return true
    }
}
