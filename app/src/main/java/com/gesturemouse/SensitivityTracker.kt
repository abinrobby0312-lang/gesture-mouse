package com.gesturemouse

import kotlin.math.hypot

/**
 * Watches real pointer use and says whether the sensitivity suits the person.
 *
 * Two patterns are telling, and they point in opposite directions:
 *
 * - **Overshoot** — a long, purposeful move that then reverses without the
 *   finger or hand lifting: the cursor sailed past what it was aimed at and
 *   had to be dragged back. Frequent overshoot means it's too fast.
 * - **Clutching** — a long move, a lift, and straight away another long move
 *   the same way: the pointer ran out of room before reaching the target and
 *   the finger had to be repositioned. Frequent clutching means it's too slow.
 *
 * Only long moves ("aims", at least [AIM_PX] of pointer travel) are judged —
 * small adjustments say nothing either way. The verdict is over the last
 * [WINDOW] aims, so it follows the person rather than their first minute.
 *
 * Thread-safe: the Air tab feeds it from the camera thread while the settings
 * sheet reads it on the UI thread.
 *
 * Pure logic with explicit timestamps, so it's unit-tested like
 * [GestureEngine]. All distances are in pointer units, i.e. after the
 * sensitivity multiplier, which is what the person is actually steering.
 */
class SensitivityTracker {

    companion object {
        /** Pointer travel that makes a move count as aiming somewhere. */
        const val AIM_PX = 140f
        /** A reversal within this much of the last movement is a correction. */
        const val CORRECTION_MS = 450L
        /** A re-stroke this soon after a lift is a clutch, not a new intention. */
        const val CLUTCH_MS = 700L
        /** Directions within this cosine count as "the same way" (~45°). */
        const val SAME_WAY_COS = 0.7f
        /** Directions below this cosine count as "back again" (~120°). */
        const val REVERSE_COS = -0.5f
        const val WINDOW = 30
        /** Aims needed before offering any verdict. */
        const val MIN_AIMS = 10
        /** Share of aims showing a pattern before it counts as a verdict. */
        const val VERDICT_RATE = 0.3f
        /** How far a suggestion moves the setting. */
        const val STEP = 0.15f
    }

    enum class Verdict { LEARNING, TOO_FAST, TOO_SLOW, GOOD }

    data class Report(
        val verdict: Verdict,
        val aims: Int,
        val overshoots: Int,
        val clutches: Int
    ) {
        /** Multiply the current setting by this to act on the verdict. */
        val factor: Float get() = when (verdict) {
            Verdict.TOO_FAST -> 1f - STEP
            Verdict.TOO_SLOW -> 1f + STEP
            else -> 1f
        }
    }

    private enum class Mark { PLAIN, OVERSHOOT, CLUTCH }

    /** The last [WINDOW] aims and what each showed. */
    private val history = ArrayDeque<Mark>()

    // the stroke segment currently being travelled
    private var runX = 0f
    private var runY = 0f
    private var lastMoveAt = 0L

    // the previous finished aim, for clutch detection
    private var lastAimX = 0f
    private var lastAimY = 0f
    private var lastAimEndedAt = Long.MIN_VALUE / 2

    @Synchronized fun move(dx: Float, dy: Float, now: Long) {
        if (dx == 0f && dy == 0f) return
        val len = hypot(runX, runY)
        if (len > 0f) {
            val cos = (runX * dx + runY * dy) / (len * hypot(dx, dy))
            if (cos < REVERSE_COS) {
                // turned back on itself: close the segment, and if it was an
                // aim followed closely by this, it overshot
                val corrected = now - lastMoveAt <= CORRECTION_MS
                endRun(if (corrected) Mark.OVERSHOOT else Mark.PLAIN)
            }
        }
        runX += dx
        runY += dy
        lastMoveAt = now
    }

    /** Finger or hand came off. */
    @Synchronized fun lift() = endRun(Mark.PLAIN)

    private fun endRun(markIfAim: Mark) {
        val len = hypot(runX, runY)
        if (len >= AIM_PX) {
            var mark = markIfAim
            if (mark == Mark.PLAIN && isClutch(len)) mark = Mark.CLUTCH
            record(mark)
            lastAimX = runX; lastAimY = runY
            lastAimEndedAt = lastMoveAt
        }
        runX = 0f; runY = 0f
    }

    /** When the current stroke began; see [begin]. */
    private var strokeStartedAt = 0L

    /**
     * This aim began soon after the previous aim ended, in a new stroke, and
     * carried on the same way.
     */
    private fun isClutch(len: Float): Boolean {
        val prevLen = hypot(lastAimX, lastAimY)
        if (prevLen == 0f) return false
        val cos = (runX * lastAimX + runY * lastAimY) / (len * prevLen)
        return cos > SAME_WAY_COS && strokeStartedAt - lastAimEndedAt in 0..CLUTCH_MS
    }

    /** Call when a new stroke begins (finger down / hand onto the pad). */
    @Synchronized fun begin(now: Long) {
        runX = 0f; runY = 0f
        strokeStartedAt = now
        lastMoveAt = now
    }

    private fun record(mark: Mark) {
        history.addLast(mark)
        while (history.size > WINDOW) history.removeFirst()
    }

    /** Start over — the data describes a sensitivity that no longer applies. */
    @Synchronized fun reset() {
        history.clear()
        runX = 0f; runY = 0f
        lastAimX = 0f; lastAimY = 0f
        lastAimEndedAt = Long.MIN_VALUE / 2
    }

    @Synchronized fun report(): Report {
        val aims = history.size
        val over = history.count { it == Mark.OVERSHOOT }
        val clutch = history.count { it == Mark.CLUTCH }
        val verdict = when {
            aims < MIN_AIMS -> Verdict.LEARNING
            over >= clutch && over >= aims * VERDICT_RATE -> Verdict.TOO_FAST
            clutch > over && clutch >= aims * VERDICT_RATE -> Verdict.TOO_SLOW
            else -> Verdict.GOOD
        }
        return Report(verdict, aims, over, clutch)
    }
}
