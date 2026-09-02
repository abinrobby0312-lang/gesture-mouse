package com.gesturemouse

import android.content.Context
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Draws the tracked skeleton so you can see what the model actually sees. */
class HandOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bones = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    private var hands: List<List<GestureEngine.Landmark>> = emptyList()
    private var onPad = false
    private var sweeping = false

    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ContextCompat.getColor(context, R.color.track)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.overlay_halo)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val edgeOn = ContextCompat.getColor(context, R.color.overlay_edge_on)
    private val edgeOff = ContextCompat.getColor(context, R.color.overlay_edge_off)
    private val handOn = ContextCompat.getColor(context, R.color.overlay_hand_on)
    private val handOff = ContextCompat.getColor(context, R.color.overlay_hand_off)

    fun setHands(hands: List<List<GestureEngine.Landmark>>, onPad: Boolean, sweeping: Boolean) {
        this.hands = hands
        this.onPad = onPad
        this.sweeping = sweeping
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        // the whole frame is the pad; the edge glows while a hand is on it
        edge.color = if (onPad) edgeOn else edgeOff
        canvas.drawRect(3f, 3f, width - 3f, height - 3f, edge)

        val drawable = hands.filter { it.size >= 21 }
        if (drawable.isEmpty()) return

        val active = if (onPad) handOn else handOff
        bone.color = active
        joint.color = active

        for (lm in drawable) {
            for ((a, b) in bones) {
                canvas.drawLine(
                    lm[a].x * width, lm[a].y * height,
                    lm[b].x * width, lm[b].y * height, bone
                )
            }
            for (i in intArrayOf(4, 8, 12, 16, 20)) {
                canvas.drawCircle(lm[i].x * width, lm[i].y * height, 8f, joint)
            }
        }

        // the tracking point, and a halo when the sweep gear is engaged. only
        // the first hand steers the cursor, so only it gets the ring.
        val lead = drawable[0]
        val cx = lead[GestureEngine.MID_MCP].x * width
        val cy = lead[GestureEngine.MID_MCP].y * height
        canvas.drawCircle(cx, cy, if (sweeping) 34f else 17f, ring)
        if (sweeping) canvas.drawCircle(cx, cy, 52f, halo)
    }
}
