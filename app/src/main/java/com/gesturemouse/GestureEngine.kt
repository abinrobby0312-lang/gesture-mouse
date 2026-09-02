package com.gesturemouse

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a stream of hand landmarks into mouse intents.
 *
 * Pure logic — no Android, no camera, no Bluetooth — so it can be reasoned
 * about and unit-tested on its own. [Output] is the only way it talks to the
 * world.
 *
 * The model, in short:
 *  - Movement is relative, like a trackpad, and the hand is tracked by the
 *    middle knuckle rather than a fingertip so flexing a finger doesn't drag
 *    the cursor.
 *  - Two gears picked by hand shape: open palm sweeps, pointing index is precise.
 *  - A fist lifts off the pad so you can reposition for free.
 *  - Dipping the index clicks if it springs back quickly, or lifts off if held.
 */
class GestureEngine(private val realOut: Output) {

    interface Output {
        fun move(dx: Float, dy: Float)
        fun click()
        fun rightClick()
        fun buttonDown()
        fun buttonUp()
        fun scroll(notches: Int)
        fun state(label: String, hint: String)

        /** Both palms raised and crossed — open the configured link on the host. */
        fun launchUrl()
    }

    companion object {
        const val PINCH_CLOSE = 0.38f
        const val PINCH_OPEN = 0.52f
        const val DRAG_HOLD = 120L
        /**
         * A dip back up faster than this is a click; slower and it counted as
         * lifting off the pad instead. The single knob balancing click against
         * clutch — see the README.
         *
         * Measured clicks land far below it: nine real ones across recorded
         * sessions ran 52–312ms, so 600 already carries ~290ms of headroom.
         * The one recorded [click_slow] near-miss was a 716ms hold, which is
         * much closer to a deliberate fist-clutch than to any observed click,
         * so it's left as a lift-off rather than stretching TAP_MAX past it
         * and making every clutch that much slower to engage.
         */
        const val TAP_MAX = 600L

        /**
         * Minimum spacing between clicks, to stop one physical dip registering
         * twice when index tracking flickers.
         *
         * Was 300ms, which measured data showed was swallowing deliberate
         * double-clicks: a recorded burst produced nine dips of which four
         * were rejected, at gaps of 125/159/191/201ms — squarely human
         * double-click spacing, a 31% loss rate. Tracking flicker repeats far
         * faster than that (the synthetic case in
         * [GestureEngineTest.rapidDipsDebounceToOneClick] cycles in ~80ms), so
         * this sits between the two clusters: high enough to still absorb a
         * flicker, low enough to let a real double-click through.
         */
        const val CLICK_GAP = 100L
        const val DELTA_SMOOTH = 0.5f
        const val DEAD_ZONE = 0.15f

        /** Below this the thumb is clearly reaching for a pinch, so an
         *  approach that never closes is worth recording as a near-miss. */
        const val PINCH_WATCH = 0.75f
        const val SCROLL_DIVISOR = 0.035f
        const val PRECISE_GAIN = 0.55f
        const val SWEEP_GAIN = 2.0f

        /**
         * The two-hand pose: wrists genuinely crossed, checked with handedness
         * rather than raw position — the subject's right hand has swapped over
         * to the screen-left side and the left hand to screen-right, see
         * [checkEggPose]. Two hands merely held up side by side, uncrossed,
         * does not satisfy this even if they're touching.
         *
         * This fires on a single instant, not a held pose — two earlier
         * designs (a continuous hold, then a hold with a memory of a momentary
         * cross) both failed against real tracking data. At the exact moment
         * wrists cross, they occlude each other and MediaPipe's read is
         * unreliable across the board: not just hand *count* (dropping to one
         * hand or losing both), but "open" and "raised" too, which measured
         * device sessions showed reading false at the very instants a genuine
         * crossing was caught. Tracking often didn't recover to a clean
         * two-hand read for a second or more afterward, so waiting for things
         * to "settle" before firing meant never firing at all.
         *
         * What's trusted is the crossing instant itself: [checkEggPose] only
         * computes it when both a Right and a Left hand are confidently found,
         * combined with a tight [EGG_TIGHT_GAP]. That combination is gated
         * only by [EGG_COOLDOWN] so the same crossing can't retrigger while
         * your hands are still up.
         *
         * [EGG_TIGHT_GAP] itself was set from measured device data, not a
         * guess, because a loose value turned this into a false-positive
         * source during ordinary one-handed mouse use — a second hand (e.g.
         * the one holding the phone) drifting into frame for a single frame
         * would occasionally read as a swapped L/R pair. The two clusters
         * were cleanly separated: deliberate crossings measured wristGap
         * 0.01–0.02; incidental single-frame two-hand reads during normal use
         * measured 0.08–0.12. The threshold sits between them.
         */
        const val EGG_COOLDOWN = 5000L
        const val EGG_HEIGHT = 0.5f      // wrists must be above this (0 = top of frame) — logged only, not gated on
        const val EGG_SPAN = 0.3f        // logged only, not gated on — see EGG_TIGHT_GAP for the real gate

        /**
         * Max wrist separation to trust a crossing outright, **as a multiple
         * of hand size** rather than a raw fraction of the frame.
         *
         * Scaling matters as much as the value. A raw frame distance means
         * something completely different depending on how far away you are:
         * at arm's length your hands are large in frame and a genuine
         * crossing leaves a comparatively wide raw gap, while from across the
         * room everything shrinks, so an ordinary two-handed movement — one
         * measured false fire came from rubbing both eyes about four feet
         * out — lands inside a raw threshold tuned up close. Dividing by hand
         * scale is the same normalization [PINCH_CLOSE] and [PINCH_OPEN]
         * already use so pinch detection holds at any distance.
         */
        const val EGG_TIGHT_GAP = 0.6f

        /**
         * A third false-positive source, distinct from the loose-gap one
         * above: MediaPipe occasionally reports one physical hand as two
         * overlapping detections (visible in device logs as a "L,L" hand
         * count), and on rare frames classifies one of the duplicates as the
         * opposite hand — producing a fake crossed pair with a razor-thin
         * wristGap, indistinguishable from a real cross by gap alone, because
         * it's literally the same hand measured against itself. One measured
         * false fire this way: wristGap 0.0199, mid one-handed drag.
         *
         * [EggCheck.avgGap] (every landmark, not just the wrist) is the
         * differentiator: a duplicate stays just as close everywhere, since
         * it's the same hand; two real crossed hands splay their fingers
         * apart from the crossing point, so avgGap reads meaningfully above
         * wristGap. Hand-scaled for the same reason as [EGG_TIGHT_GAP].
         */
        const val EGG_MIN_SPREAD = 0.12f

        /**
         * Both hands must be within this multiple of hand size of each other
         * *vertically* too. A genuine wrist cross puts them at nearly the
         * same height; two hands that merely pass each other in x — one high,
         * one low, as when reaching across your face — do not. Raw
         * [wristGap] alone can't tell those apart, since it only measures
         * horizontal separation.
         */
        const val EGG_MAX_Y_GAP = 0.8f

        /**
         * How long without any real output — move, click, scroll, drag, or
         * the easter egg — before the engine stops reacting to hands at all.
         *
         * The camera sees whatever passes in front of it, not just deliberate
         * trackpad use, and every false-positive fixed above came from
         * incidental hand movement accidentally forming a real gesture shape.
         * Going blind after a period of no genuine engagement means stray
         * movement near an otherwise-idle phone can't do anything, at the
         * cost of a deliberate gesture — [WAKE_HOLD] — being needed to resume.
         */
        const val SLEEP_AFTER = 5_000L

        /** How long the wake gesture — one open palm — must be held. */
        const val WAKE_HOLD = 3_000L

        /**
         * How long a tracking dropout during the wake hold is bridged without
         * resetting it — the single-hand equivalent of [EGG_GRACE]. Measured
         * device data showed this was necessary, not just theoretically
         * possible: several real attempts accumulated well over half the
         * required hold (one reached 2239 of 3000ms) before a single dropped
         * frame reset it to zero, over and over. Single-hand "is this an open
         * palm" tracking is more stable than the two-hand crossing case
         * [EGG_GRACE] was written for, but not stable enough to demand a
         * literally unbroken 3 full seconds.
         */
        const val WAKE_GRACE = 400L

        const val WRIST = 0
        const val THUMB = 4
        const val IDX_PIP = 6
        const val IDX = 8
        const val MID_MCP = 9
        const val MID_PIP = 10
        const val MID = 12
        const val RING_PIP = 14
        const val RING = 16
        const val PINK_PIP = 18
        const val PINK = 20
    }

    /** Master sensitivity; both gears multiply this. */
    var speed = 1800f

    /**
     * Optional sink for telemetry. Records what fired *and what nearly fired* —
     * a gesture that misses a threshold by a hair leaves no trace otherwise, so
     * without this there's no way to distinguish "didn't try" from "tried and
     * the threshold is wrong".
     */
    var logger: ((String, Map<String, Any>) -> Unit)? = null

    private fun logEv(k: String, d: Map<String, Any> = emptyMap()) = logger?.invoke(k, d)

    var onPad = false; private set
    var sweeping = false; private set
    var dragging = false; private set

    /** True while the crossed-palms pose is being held. */
    var eggPosed = false; private set

    private var eggCooldownUntil = Long.MIN_VALUE

    /** True once [SLEEP_AFTER] has elapsed with no real output; see [updateHands]. */
    var asleep = false; private set

    private var lastActiveAt = Long.MIN_VALUE
    private var wakeHoldStart = 0L
    private var wakeLastOpenAt = 0L

    /** `now` from whichever of [update]/[updateHands] is currently running, so [out] can stamp activity. */
    private var currentNow = 0L

    /**
     * Wraps [realOut] so every real action marks [lastActiveAt] automatically,
     * without threading a timestamp through every call site in [update]. Only
     * actions count — [Output.state] is just a UI label and doesn't reset the
     * sleep timer, or a phone just sitting there with the readout on screen
     * would never sleep.
     */
    private val out = object : Output {
        override fun move(dx: Float, dy: Float) { lastActiveAt = currentNow; realOut.move(dx, dy) }
        override fun click() { lastActiveAt = currentNow; realOut.click() }
        override fun rightClick() { lastActiveAt = currentNow; realOut.rightClick() }
        override fun buttonDown() { lastActiveAt = currentNow; realOut.buttonDown() }
        override fun buttonUp() { lastActiveAt = currentNow; realOut.buttonUp() }
        override fun scroll(notches: Int) { lastActiveAt = currentNow; realOut.scroll(notches) }
        override fun launchUrl() { lastActiveAt = currentNow; realOut.launchUrl() }
        override fun state(label: String, hint: String) = realOut.state(label, hint)
    }

    private var pinching = false
    private var pinchStart = 0L
    private var rPinching = false
    private var idxWasUp = false
    private var dipStart = 0L
    private var lastClick = -99999L
    private var lastHandX = 0f
    private var lastHandY = 0f
    private var hasLastHand = false
    private var vx = 0f
    private var vy = 0f
    private var scrollAnchor = Float.NaN
    private var scrollAccum = 0f

    // --- telemetry state -----------------------------------------------------
    private var pinchMin = Float.NaN     // closest approach of an unfinished pinch
    private var dragPinchMin = Float.NaN // pinchD range seen while a drag was held,
    private var dragPinchMax = Float.NaN // to show how near it came to releasing
    private var dragStart = 0L
    private var padSince = 0L
    private var wasOnPad = false
    private var wasSweeping = false
    private var runSign = 0f             // direction of the current horizontal run
    private var runPx = 0f               // distance travelled in that direction
    private var lastReversal = 0L

    private fun dist(a: Landmark, b: Landmark) = hypot(a.x - b.x, a.y - b.y)
    private fun isUp(lm: List<Landmark>, tip: Int, pip: Int) = lm[tip].y < lm[pip].y

    /**
     * Watches for a long push followed by a short push back the other way.
     *
     * That shape means the cursor sailed past the target and had to be dragged
     * back — the clearest evidence that sensitivity is too high for the gear in
     * use. A plain change of direction is not interesting; it's the long-then-
     * short-correction pattern that matters.
     */
    private fun trackReversal(v: Float, now: Long) {
        val s = if (v > 0f) 1f else -1f
        if (s == runSign) {
            runPx += abs(v)
            return
        }
        val previousRun = runPx
        if (runSign != 0f && previousRun > 60f && now - lastReversal > 200L) {
            logEv("overshoot", mapOf(
                "runPx" to previousRun.toInt(),
                "gear" to if (sweeping) "sweep" else "precise"
            ))
            lastReversal = now
        }
        runSign = s
        runPx = abs(v)
    }

    /**
     * Drop all pointer state and let go of anything held.
     *
     * Split out of [release] because the crossed-palms pose needs to stop the
     * cursor dead without also clearing the pose's own timers or overwriting
     * the label with "no hand".
     */
    private fun resetPointer() {
        if (dragging) {
            out.buttonUp()
            dragging = false
        }
        if (wasOnPad) {
            logEv("pad_up", mapOf("ms" to 0, "reason" to "lost"))
            wasOnPad = false
        }
        pinching = false
        rPinching = false
        onPad = false
        sweeping = false
        wasSweeping = false
        hasLastHand = false
        vx = 0f; vy = 0f
        dipStart = 0L
        idxWasUp = false
        scrollAnchor = Float.NaN
        scrollAccum = 0f
        pinchMin = Float.NaN
        dragPinchMin = Float.NaN; dragPinchMax = Float.NaN
        runSign = 0f; runPx = 0f
    }

    /** Called when the hand leaves frame, the tab is hidden, or tracking stops. */
    fun release() {
        resetPointer()
        eggPosed = false
        out.state("no hand", "show your hand to the camera")
    }

    private fun palmOpenOf(lm: List<Landmark>) = listOf(
        isUp(lm, IDX, IDX_PIP), isUp(lm, MID, MID_PIP),
        isUp(lm, RING, RING_PIP), isUp(lm, PINK, PINK_PIP)
    ).count { it } >= 3

    private var lastEggDiagAt = 0L

    /**
     * Every condition around the crossed-palms pose, kept separate so a
     * failed attempt can be logged with *which* signal looked wrong, and so
     * [updateHands] can gate on [crossed] and [wristGap] alone without
     * requiring [bothOpen] or [bothHigh] — see the trade-off note on
     * [EGG_TIGHT_GAP].
     */
    /**
     * All gaps are in multiples of hand size, not raw frame fractions, so
     * every threshold holds at any distance from the camera — see
     * [EGG_TIGHT_GAP].
     */
    private data class EggCheck(
        val bothHandsFound: Boolean,
        val bothOpen: Boolean = false,
        val bothHigh: Boolean = false,
        val wristGap: Float = Float.NaN,
        val wristYGap: Float = Float.NaN,
        val avgGap: Float = Float.NaN,
        val crossed: Boolean = false
    ) {
        /**
         * True only when the two hands look like genuinely different hands,
         * not one physical hand MediaPipe reported as two overlapping
         * detections. A duplicate has every landmark — not just the wrist —
         * sitting about as close together as [wristGap]; two real hands
         * crossing splay their fingers apart from the crossing point, so
         * [avgGap] reads meaningfully larger. See [EGG_MIN_SPREAD].
         */
        val looksLikeTwoHands get() = bothHandsFound && avgGap - wristGap >= EGG_MIN_SPREAD

        /** A real cross is tight in x *and* level in y — see [EGG_MAX_Y_GAP]. */
        val tightAndLevel get() =
            wristGap <= EGG_TIGHT_GAP && wristYGap <= EGG_MAX_Y_GAP
    }

    /**
     * True crossing, using handedness rather than raw position.
     *
     * Landmarks alone can't tell "crossed" from "held close together side by
     * side" — both put two hands near each other. What makes it a cross is
     * that each hand ends up on the *other* side of the body: the subject's
     * right hand swaps over to screen-left, the left hand to screen-right.
     *
     * The landmarks the app feeds in are already mirror-corrected for the
     * front camera (see [AirFragment]'s capture step, done so that moving a
     * hand right moves the cursor right) — which is also the convention
     * MediaPipe's own handedness classifier assumes, so "screen-right" here
     * lines up with the subject's actual right side. On the back camera,
     * which isn't mirrored, handedness comes out flipped and this check will
     * be backwards; the pose is meant to be used facing the phone.
     */
    private fun checkEggPose(hands: List<Hand>): EggCheck {
        val right = hands.firstOrNull { it.isRight }?.landmarks
        val left = hands.firstOrNull { !it.isRight }?.landmarks
        if (right == null || left == null || right.size < 21 || left.size < 21) {
            return EggCheck(bothHandsFound = false)
        }
        var gapSum = 0f
        for (i in 0 until 21) gapSum += dist(right[i], left[i])
        // wrist-to-knuckle on each hand, averaged: the same hand-size yardstick
        // pinch detection uses, so these thresholds hold at any camera distance
        val scale = max(
            (dist(right[WRIST], right[MID_MCP]) + dist(left[WRIST], left[MID_MCP])) / 2f,
            1e-6f
        )
        return EggCheck(
            bothHandsFound = true,
            bothOpen = palmOpenOf(right) && palmOpenOf(left),
            bothHigh = right[WRIST].y <= EGG_HEIGHT && left[WRIST].y <= EGG_HEIGHT,
            wristGap = abs(right[WRIST].x - left[WRIST].x) / scale,
            wristYGap = abs(right[WRIST].y - left[WRIST].y) / scale,
            avgGap = (gapSum / 21) / scale,
            // uncrossed, the right hand sits at the larger x (screen-right);
            // crossed, it has swapped past the left hand
            crossed = right[WRIST].x < left[WRIST].x
        )
    }

    /**
     * Blind until woken. Checked first, ahead of even the crossed-palms pose,
     * so a sleeping phone reacts to nothing an incidental hand shape could
     * form — that's the whole point of [SLEEP_AFTER]. Returns true if this
     * call was consumed by sleep/wake handling and the caller should stop.
     */
    private fun handleSleep(hands: List<Hand>, now: Long): Boolean {
        if (!asleep && now - lastActiveAt >= SLEEP_AFTER) {
            asleep = true
            resetPointer()
            eggPosed = false
            wakeHoldStart = 0L
            logEv("sleep")
            out.state("sleeping", "hold an open palm to wake")
        }
        if (!asleep) return false

        val lm = hands.firstOrNull()?.landmarks
        val palmOpen = lm != null && lm.size >= 21 && palmOpenOf(lm)
        if (palmOpen) wakeLastOpenAt = now

        // [WAKE_GRACE]: a hold in progress survives a brief dropout — only a
        // gap longer than the grace window actually breaks it
        val holding = palmOpen || (wakeHoldStart != 0L && now - wakeLastOpenAt <= WAKE_GRACE)

        if (holding) {
            if (wakeHoldStart == 0L) wakeHoldStart = now
            val held = now - wakeHoldStart
            if (held >= WAKE_HOLD) {
                asleep = false
                lastActiveAt = now
                wakeHoldStart = 0L
                logEv("wake")
                out.state("awake", "ready")
            } else {
                val secondsLeft = (WAKE_HOLD - held) / 1000L + 1
                out.state("waking…", "hold still — ${secondsLeft}s")
            }
        } else {
            if (wakeHoldStart != 0L) logEv("wake_broken", mapOf("heldMs" to (now - wakeHoldStart)))
            wakeHoldStart = 0L
            out.state("sleeping", "hold an open palm to wake")
        }
        return true
    }

    /**
     * Entry point when the tracker can see more than one hand.
     *
     * A genuine crossing fires immediately — see the class-level note on
     * [EGG_TIGHT_GAP] for why this is a single-instant trigger rather than a
     * held pose, and why openness/height aren't part of the gate. Otherwise
     * this is ordinary single-hand tracking on [hands]`[0]`.
     *
     * Named separately from [update] rather than overloading it — both would
     * erase to the same JVM signature.
     */
    fun updateHands(hands: List<Hand>, now: Long) {
        currentNow = now
        if (lastActiveAt == Long.MIN_VALUE) lastActiveAt = now
        if (handleSleep(hands, now)) return

        val check = checkEggPose(hands)
        if (check.bothHandsFound && now - lastEggDiagAt > 300L) {
            lastEggDiagAt = now
            logEv("egg_diag", mapOf(
                "bothOpen" to check.bothOpen, "bothHigh" to check.bothHigh,
                "wristGap" to check.wristGap, "wristYGap" to check.wristYGap,
                "avgGap" to check.avgGap, "crossed" to check.crossed
            ))
        }

        if (check.crossed && check.tightAndLevel && check.looksLikeTwoHands) {
            eggPosed = true
            resetPointer()
            if (now >= eggCooldownUntil) {
                eggCooldownUntil = now + EGG_COOLDOWN
                out.launchUrl()
                logEv("launch_url", mapOf(
                    "wristGap" to check.wristGap, "wristYGap" to check.wristYGap,
                    "avgGap" to check.avgGap
                ))
                out.state("link sent", "typing it on the host")
            } else {
                out.state("crossed palms", "cooling down…")
            }
            return
        }

        eggPosed = false

        val first = hands.firstOrNull()?.landmarks
        if (first == null || first.size < 21) release() else update(first, now)
    }

    /**
     * @param lm 21 hand landmarks, normalized 0..1, already un-mirrored so that
     *           moving your hand right moves the cursor right.
     * @param now monotonic milliseconds.
     */
    fun update(lm: List<Landmark>, now: Long) {
        currentNow = now
        if (lm.size < 21) {
            release()
            return
        }

        val scale = max(dist(lm[WRIST], lm[MID_MCP]), 1e-6f)
        val idxUp = isUp(lm, IDX, IDX_PIP)
        val midUp = isUp(lm, MID, MID_PIP)
        val ringUp = isUp(lm, RING, RING_PIP)
        val pinkUp = isUp(lm, PINK, PINK_PIP)
        val pinchD = dist(lm[THUMB], lm[IDX]) / scale
        val rightD = dist(lm[THUMB], lm[MID]) / scale
        val palmOpen = listOf(idxUp, midUp, ringUp, pinkUp).count { it } >= 3

        // ---- scroll: index and middle up, ring and pinky down ----
        if (idxUp && midUp && !ringUp && !pinkUp) {
            if (dragging) { out.buttonUp(); dragging = false }
            pinching = false
            rPinching = false
            hasLastHand = false
            onPad = false
            sweeping = false
            val y = lm[IDX].y
            if (scrollAnchor.isNaN()) scrollAnchor = y
            scrollAccum += (scrollAnchor - y)
            scrollAnchor = y
            val notches = (scrollAccum / SCROLL_DIVISOR).toInt()
            if (notches != 0) {
                out.scroll(notches)
                scrollAccum -= notches * SCROLL_DIVISOR
                logEv("scroll", mapOf("notches" to notches))
            }
            out.state("scroll", "move hand up or down")
            return
        }
        scrollAnchor = Float.NaN
        scrollAccum = 0f

        // ---- index dip: quick down-and-up clicks, a held curl lifts off ----
        // suppressed while the thumb is closed in (pinching bends the index too)
        // and during an open-palm sweep (a tracking flicker mid-sweep would
        // otherwise stall the cursor and fire a click nobody asked for)
        val thumbClear = pinchD > PINCH_OPEN && !dragging && !palmOpen
        if (!thumbClear) {
            // Which gate swallowed a dip, logged on the up-edge — the point a
            // click would have fired. Recorded sessions showed click attempts
            // producing no click event of any kind (not even a near-miss),
            // because they never got past this gate at all; without naming the
            // specific gate there's no way to tell which of the three did it.
            if (idxUp && !idxWasUp) {
                logEv("click_blocked", mapOf(
                    "pinchD" to pinchD,
                    "byPinch" to (pinchD <= PINCH_OPEN),
                    "byDrag" to dragging,
                    "byPalm" to palmOpen
                ))
            }
            dipStart = 0L
            idxWasUp = idxUp
        } else if (idxUp != idxWasUp) {
            if (!idxUp) {
                dipStart = now
            } else {
                if (dipStart > 0L) {
                    val held = now - dipStart
                    when {
                        // held past the cutoff, so it counted as lifting off the
                        // pad. if it only just overshot, a click was probably
                        // what was meant — this is the number TAP_MAX is tuned by
                        held >= TAP_MAX -> logEv(
                            "click_slow",
                            mapOf("ms" to held, "overBy" to (held - TAP_MAX))
                        )
                        now - lastClick <= CLICK_GAP -> logEv(
                            "click_debounced",
                            mapOf("ms" to held, "gap" to (now - lastClick))
                        )
                        else -> {
                            out.click()
                            lastClick = now
                            logEv("click", mapOf("ms" to held, "margin" to (TAP_MAX - held)))
                        }
                    }
                }
                dipStart = 0L
            }
            idxWasUp = idxUp
        }
        val pendingTap = dipStart > 0L && now - dipStart < TAP_MAX

        // ---- pad contact ----
        onPad = dragging || pinching || ((idxUp || palmOpen) && !pendingTap)
        sweeping = onPad && palmOpen

        // clutch rhythm: frequent short strokes mean speed is too low for the
        // distances being covered, so the hand keeps running out of room
        if (onPad != wasOnPad) {
            if (onPad) {
                padSince = now
                logEv("pad_down", mapOf("gear" to if (sweeping) "sweep" else "precise"))
            } else {
                logEv("pad_up", mapOf("ms" to (now - padSince)))
            }
            wasOnPad = onPad
        }
        if (onPad && sweeping != wasSweeping) {
            logEv("gear", mapOf("to" to if (sweeping) "sweep" else "precise"))
            wasSweeping = sweeping
        }

        // ---- relative move, tracked from the middle knuckle ----
        val hx = lm[MID_MCP].x
        val hy = lm[MID_MCP].y
        if (!onPad) {
            hasLastHand = false
            vx = 0f; vy = 0f
        } else if (!hasLastHand) {
            lastHandX = hx; lastHandY = hy
            hasLastHand = true
        } else {
            val gain = speed * (if (palmOpen) SWEEP_GAIN else PRECISE_GAIN)
            val dx = (hx - lastHandX) * gain
            val dy = (hy - lastHandY) * gain
            lastHandX = hx; lastHandY = hy
            vx += (dx - vx) * DELTA_SMOOTH
            vy += (dy - vy) * DELTA_SMOOTH
            if (abs(vx) > DEAD_ZONE || abs(vy) > DEAD_ZONE) {
                out.move(vx, vy)
                trackReversal(vx, now)
            }
        }

        // ---- pinch and hold: grab and drag ----
        // While a drag is held, track how close the hand gets to releasing.
        // Recorded sessions showed drags stuck down for 6–7 seconds during
        // what were meant to be clicks, which happens if the hand settles
        // inside the PINCH_CLOSE..PINCH_OPEN hysteresis band and never reaches
        // the release threshold — this records how far short it stayed.
        if (dragging) {
            dragPinchMin = if (dragPinchMin.isNaN()) pinchD else minOf(dragPinchMin, pinchD)
            dragPinchMax = if (dragPinchMax.isNaN()) pinchD else maxOf(dragPinchMax, pinchD)
        }
        if (!pinching && pinchD < PINCH_CLOSE) {
            pinching = true
            pinchStart = now
        } else if (pinching && pinchD > PINCH_OPEN) {
            pinching = false
            if (dragging) {
                out.buttonUp(); dragging = false
                logEv("drop", mapOf(
                    "ms" to (now - dragStart),
                    "minPinchD" to dragPinchMin, "maxPinchD" to dragPinchMax
                ))
                dragPinchMin = Float.NaN; dragPinchMax = Float.NaN
            } else {
                // closed far enough to count, but let go before it became a grab
                logEv("pinch_short", mapOf("ms" to (now - pinchStart)))
            }
        }
        if (pinching && !dragging && now - pinchStart > DRAG_HOLD) {
            out.buttonDown()
            dragging = true
            dragStart = now
            // pinchD at grab time shows how deep the closing motion actually
            // went: a deliberate pinch should sit well under PINCH_CLOSE, while
            // an index dip that only grazes it is a click being misread
            logEv("grab", mapOf("pinchD" to pinchD, "idxUp" to idxUp, "palmOpen" to palmOpen))
        }

        // a pinch that reached for it but never closed. if these cluster just
        // above PINCH_CLOSE, the threshold is too tight for this hand.
        if (!pinching) {
            if (pinchD < PINCH_WATCH) {
                pinchMin = if (pinchMin.isNaN()) pinchD else minOf(pinchMin, pinchD)
            } else if (!pinchMin.isNaN()) {
                if (pinchMin >= PINCH_CLOSE) {
                    logEv("pinch_near", mapOf(
                        "min" to pinchMin, "shortBy" to (pinchMin - PINCH_CLOSE)
                    ))
                }
                pinchMin = Float.NaN
            }
        } else {
            pinchMin = Float.NaN
        }

        // ---- right click: thumb to middle fingertip ----
        if (!rPinching && !pinching && rightD < PINCH_CLOSE) {
            rPinching = true
            out.rightClick()
            logEv("rclick")
        } else if (rPinching && rightD > PINCH_OPEN) {
            rPinching = false
        }

        out.state(
            when {
                dragging -> "drag"
                pendingTap -> "click"
                !onPad -> "lifted"
                palmOpen -> "sweep"
                else -> "precise"
            },
            when {
                dragging -> "release to drop"
                !onPad -> "open your hand to touch down"
                palmOpen -> "open palm — fast across the screen"
                else -> "index — fine control, dip to click"
            }
        )
    }

    data class Landmark(val x: Float, val y: Float)

    /**
     * One tracked hand, tagged with which physical hand it is.
     *
     * [isRight] only has to be trustworthy for [checkEggPose] — single-hand
     * pointing, clicking, dragging etc. never look at it.
     */
    data class Hand(val landmarks: List<Landmark>, val isRight: Boolean)
}
