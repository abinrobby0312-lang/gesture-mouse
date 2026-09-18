package com.gesturemouse

import com.gesturemouse.SensitivityTracker.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic strokes in, verdicts out. Each helper drives a whole stroke the way
 * a finger does: down, a run of ~16 ms moves, up.
 */
class SensitivityTrackerTest {

    private val t = SensitivityTracker()
    private var now = 0L

    /** One stroke of [steps] moves of (dx, dy), then optionally a reversal. */
    private fun stroke(dx: Float, dy: Float, steps: Int = 20, backSteps: Int = 0) {
        t.begin(now)
        repeat(steps) { now += 16; t.move(dx, dy, now) }
        repeat(backSteps) { now += 16; t.move(-dx / 2, -dy / 2, now) }
        t.lift()
    }

    private fun pause(ms: Long) { now += ms }

    @Test fun learnsBeforeJudging() {
        repeat(SensitivityTracker.MIN_AIMS - 1) { stroke(10f, 0f); pause(2000) }
        assertEquals(Verdict.LEARNING, t.report().verdict)
    }

    @Test fun smallAdjustmentsAreIgnored() {
        repeat(40) { stroke(2f, 0f, steps = 10); pause(300) }   // 20px moves
        assertEquals(0, t.report().aims)
    }

    @Test fun longMovesThatSailPastAndComeBackAreOvershoot() {
        repeat(12) { i ->
            // alternate directions so no two aims look like a clutch
            val s = if (i % 2 == 0) 1f else -1f
            stroke(10f * s, 0f, backSteps = 5)
            pause(2000)
        }
        val r = t.report()
        assertEquals(Verdict.TOO_FAST, r.verdict)
        assertEquals(12, r.overshoots)
        assertEquals(1f - SensitivityTracker.STEP, r.factor, 1e-6f)
    }

    @Test fun reStrokingTheSameWayIsClutching() {
        // pairs of quick same-direction strokes: the first reached nowhere
        repeat(6) { i ->
            val s = if (i % 2 == 0) 1f else -1f
            stroke(0f, 10f * s); pause(200)
            stroke(0f, 10f * s); pause(2000)
        }
        val r = t.report()
        assertEquals(6, r.clutches)
        assertEquals(Verdict.TOO_SLOW, r.verdict)
        assertEquals(1f + SensitivityTracker.STEP, r.factor, 1e-6f)
    }

    @Test fun cleanAimsAreGood() {
        repeat(15) { i ->
            val s = if (i % 2 == 0) 1f else -1f
            stroke(10f * s, 5f); pause(1500)
        }
        assertEquals(Verdict.GOOD, t.report().verdict)
    }

    @Test fun aSlowDeliberateTurnIsNotAnOvershoot() {
        repeat(12) {
            t.begin(now)
            repeat(20) { now += 16; t.move(10f, 0f, now) }
            now += 1000                       // stopped, looked, then went back
            repeat(20) { now += 16; t.move(-10f, 0f, now) }
            t.lift(); pause(2000)
        }
        assertEquals(0, t.report().overshoots)
    }

    @Test fun resetForgetsEverything() {
        repeat(12) { stroke(10f, 0f, backSteps = 5); pause(2000) }
        t.reset()
        assertEquals(0, t.report().aims)
        assertEquals(Verdict.LEARNING, t.report().verdict)
    }
}
