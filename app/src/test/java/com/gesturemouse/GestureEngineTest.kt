package com.gesturemouse

import com.gesturemouse.GestureEngine.Landmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs

/**
 * The gesture rules, checked the same way they were checked in the prototype:
 * synthetic hand poses in, intents out.
 *
 * The tricky cases are the ones where two gestures share a hand shape and are
 * told apart by timing — a quick index dip is a click, the same curl held is a
 * lift-off — so those get the most attention here.
 */
class GestureEngineTest {

    /** Records what the engine asked for, so a test can assert on the sequence. */
    private class Recorder : GestureEngine.Output {
        val events = mutableListOf<String>()
        var dx = 0f
        var dy = 0f
        var scrolled = 0
        var label = ""

        override fun move(dx: Float, dy: Float) {
            this.dx += dx; this.dy += dy; events += "move"
        }
        override fun click() { events += "click" }
        override fun rightClick() { events += "rclick" }
        override fun buttonDown() { events += "down" }
        override fun buttonUp() { events += "up" }
        override fun scroll(notches: Int) { scrolled += notches; events += "scroll" }
        override fun state(label: String, hint: String) { this.label = label }

        fun reset() { events.clear(); dx = 0f; dy = 0f; scrolled = 0 }
        fun count(k: String) = events.count { it == k }
    }

    /**
     * Builds a plausible 21-point hand.
     *
     * @param handX  where the hand sits horizontally (drives cursor movement)
     * @param idxUp  index extended (fingertip above its PIP joint)
     * @param others how many of middle/ring/pinky are extended
     * @param pinch  thumb-to-index distance, already relative to hand size
     */
    private fun hand(
        handX: Float = 0.5f,
        handY: Float = 0.5f,
        idxUp: Boolean = true,
        others: Int = 0,
        pinch: Float = 1.0f,
        thumbToMiddle: Float = 1.0f
    ): List<Landmark> {
        val lm = MutableList(21) { Landmark(handX, handY) }
        // wrist sits a fixed distance below the middle knuckle: this pair is
        // the hand-size scale everything else is normalized against
        lm[GestureEngine.MID_MCP] = Landmark(handX, handY)
        lm[GestureEngine.WRIST] = Landmark(handX, handY + 0.10f)

        fun finger(tip: Int, pip: Int, up: Boolean, dx: Float) {
            val pipY = handY - 0.03f
            lm[pip] = Landmark(handX + dx, pipY)
            lm[tip] = Landmark(handX + dx, if (up) pipY - 0.05f else pipY + 0.05f)
        }
        finger(GestureEngine.IDX, GestureEngine.IDX_PIP, idxUp, -0.02f)
        finger(GestureEngine.MID, GestureEngine.MID_PIP, others >= 1, 0.00f)
        finger(GestureEngine.RING, GestureEngine.RING_PIP, others >= 2, 0.02f)
        finger(GestureEngine.PINK, GestureEngine.PINK_PIP, others >= 3, 0.04f)

        // thumb placed to produce the requested normalized distances
        val scale = 0.10f
        lm[GestureEngine.THUMB] = Landmark(
            lm[GestureEngine.IDX].x + pinch * scale,
            lm[GestureEngine.IDX].y
        )
        if (thumbToMiddle < 0.9f) {
            lm[GestureEngine.THUMB] = Landmark(
                lm[GestureEngine.MID].x + thumbToMiddle * scale,
                lm[GestureEngine.MID].y
            )
        }
        return lm
    }

    private class Clock { var t = 1000L }

    private fun feed(e: GestureEngine, c: Clock, lm: List<Landmark>, ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) { c.t += 16; e.update(lm, c.t); elapsed += 16 }
    }

    // ---- clutch ---------------------------------------------------------------

    @Test fun pointingIndexIsOnThePad() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 100)
        assertTrue(e.onPad)
    }

    @Test fun openPalmIsOnThePadAndSweeps() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true, others = 3), 100)
        assertTrue(e.onPad)
        assertTrue(e.sweeping)
    }

    @Test fun fistLiftsOffThePad() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 100)
        feed(e, c, hand(idxUp = false), 900)
        assertFalse(e.onPad)
    }

    @Test fun movingWhileLiftedSendsNothing() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(handX = 0.5f), 100)
        feed(e, c, hand(handX = 0.5f, idxUp = false), 900)
        r.reset()
        for (i in 0 until 20) { c.t += 16; e.update(hand(handX = 0.1f + i * 0.04f, idxUp = false), c.t) }
        assertEquals("lifted hand must not move the cursor", 0, r.count("move"))
    }

    @Test fun touchingBackDownDoesNotJump() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(handX = 0.2f), 100)
        feed(e, c, hand(handX = 0.2f, idxUp = false), 900)
        r.reset()
        c.t += 16; e.update(hand(handX = 0.9f), c.t)   // reappear far away
        assertEquals("re-touch must not emit a delta", 0, r.count("move"))
    }

    // ---- click vs lift --------------------------------------------------------

    @Test fun quickDipClicks() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 200)
        feed(e, c, hand(idxUp = false), 150)
        feed(e, c, hand(idxUp = true), 100)
        assertEquals(1, r.count("click"))
    }

    @Test fun deliberateSlowPressStillClicks() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 200)
        feed(e, c, hand(idxUp = false), 450)
        feed(e, c, hand(idxUp = true), 100)
        assertEquals("a 450ms press is still a click", 1, r.count("click"))
    }

    @Test fun heldCurlLiftsAndNeverClicks() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 200)
        feed(e, c, hand(idxUp = false), 1000)
        feed(e, c, hand(idxUp = true), 100)
        assertEquals("a held curl is a lift, not a click", 0, r.count("click"))
    }

    @Test fun cursorIsFrozenMidClick() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(handX = 0.5f), 200)
        r.reset()
        for (i in 0 until 8) { c.t += 16; e.update(hand(handX = 0.5f + i * 0.03f, idxUp = false), c.t) }
        assertEquals("a click must not shove the cursor", 0, r.count("move"))
    }

    @Test fun rapidDipsDebounceToOneClick() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 200)
        feed(e, c, hand(idxUp = false), 48); feed(e, c, hand(idxUp = true), 32)
        feed(e, c, hand(idxUp = false), 48); feed(e, c, hand(idxUp = true), 32)
        assertEquals(1, r.count("click"))
    }

    /**
     * The other side of [rapidDipsDebounceToOneClick]: a deliberate
     * double-click has to survive the debounce. Recorded sessions showed real
     * repeats at 125–201ms apart being rejected outright — 4 of 13 dips in one
     * burst — because CLICK_GAP was set well above human double-click spacing.
     */
    @Test fun aDeliberateDoubleClickRegistersTwice() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true), 200)
        feed(e, c, hand(idxUp = false), 64); feed(e, c, hand(idxUp = true), 48)
        feed(e, c, hand(idxUp = true), 96)     // brief dwell, as between real clicks
        feed(e, c, hand(idxUp = false), 64); feed(e, c, hand(idxUp = true), 48)
        assertEquals("double-clicking at human speed must produce two clicks", 2, r.count("click"))
    }

    @Test fun pinchDoesNotFireAStrayClick() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(idxUp = true, pinch = 1.0f), 200)
        feed(e, c, hand(idxUp = false, pinch = 0.25f), 400)
        feed(e, c, hand(idxUp = true, pinch = 1.0f), 200)
        assertEquals("pinching bends the index; that is not a click", 0, r.count("click"))
    }

    @Test fun indexFlickerDuringSweepFiresNoClick() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // warm up at the same position the sweep starts from, or the first
        // frame reads as one big jump backwards and swamps the measurement
        feed(e, c, hand(handX = 0.3f, idxUp = true, others = 3), 200)
        r.reset()
        for (i in 1..20) {
            c.t += 16
            e.update(hand(handX = 0.3f + 0.004f * i, idxUp = i % 4 != 0, others = 3), c.t)
        }
        assertEquals("tracking flicker mid-sweep must not click", 0, r.count("click"))
        assertTrue("and must not stall the cursor", r.dx > 100f)
    }

    // ---- gears ----------------------------------------------------------------

    private fun travel(palm: Boolean): Float {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(handX = 0.2f, idxUp = true, others = if (palm) 3 else 0), 200)
        r.reset()
        for (i in 1..40) {
            c.t += 16
            e.update(hand(handX = 0.2f + 0.25f * i / 40f, idxUp = true, others = if (palm) 3 else 0), c.t)
        }
        return r.dx
    }

    @Test fun sweepGearTravelsFurtherThanPrecise() {
        val precise = travel(false)
        val sweep = travel(true)
        assertTrue("sweep should be far faster (got $precise vs $sweep)", sweep > precise * 3f)
    }

    @Test fun gearRatioMatchesConfiguration() {
        val ratio = travel(true) / travel(false)
        val want = GestureEngine.SWEEP_GAIN / GestureEngine.PRECISE_GAIN
        assertTrue("ratio $ratio should be about $want", abs(ratio - want) < 0.2f)
    }

    @Test fun preciseGearIsFineEnoughForSmallTargets() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(handX = 0.5f), 200)
        r.reset()
        for (i in 1..10) { c.t += 16; e.update(hand(handX = 0.5f + 0.002f * i), c.t) }
        assertTrue("2% of frame should be a small nudge, got ${r.dx}", r.dx < 45f)
    }

    // ---- drag -----------------------------------------------------------------

    @Test fun pinchAndHoldGrabsAndKeepsTracking() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(handX = 0.5f, pinch = 1.0f), 100)
        feed(e, c, hand(handX = 0.5f, idxUp = false, pinch = 0.25f), 400)
        assertTrue("pinch held should grab", e.dragging)
        assertEquals(1, r.count("down"))
        assertTrue("pad stays down while dragging", e.onPad)

        r.reset()
        for (i in 1..10) { c.t += 16; e.update(hand(handX = 0.5f + 0.01f * i, idxUp = false, pinch = 0.25f), c.t) }
        assertTrue("a drag has to be able to move things", r.count("move") > 0)
    }

    @Test fun releasingAPinchDropsTheDrag() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(pinch = 1.0f), 100)
        feed(e, c, hand(idxUp = false, pinch = 0.25f), 400)
        feed(e, c, hand(idxUp = true, pinch = 1.0f), 100)
        assertFalse(e.dragging)
        assertEquals(1, r.count("up"))
    }

    @Test fun losingTheHandReleasesAHeldButton() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(pinch = 1.0f), 100)
        feed(e, c, hand(idxUp = false, pinch = 0.25f), 400)
        assertTrue(e.dragging)
        r.reset()
        e.release()
        assertEquals("a lost hand must not leave the button stuck down", 1, r.count("up"))
        assertFalse(e.dragging)
    }

    // ---- scroll ---------------------------------------------------------------

    @Test fun twoFingersScrollAndDoNotMoveTheCursor() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // index + middle up, ring and pinky down
        feed(e, c, hand(handY = 0.5f, idxUp = true, others = 1), 100)
        r.reset()
        for (i in 1..15) {
            c.t += 16
            e.update(hand(handY = 0.5f - 0.01f * i, idxUp = true, others = 1), c.t)
        }
        assertTrue("moving the hand up should scroll up, got ${r.scrolled}", r.scrolled > 0)
        assertEquals("scrolling must not also move the cursor", 0, r.count("move"))
    }

    @Test fun scrollingWhileDraggingReleasesTheButton() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(pinch = 1.0f), 100)
        feed(e, c, hand(idxUp = false, pinch = 0.25f), 400)
        assertTrue(e.dragging)
        r.reset()
        feed(e, c, hand(idxUp = true, others = 1), 100)
        assertEquals("switching to scroll must not strand a held button", 1, r.count("up"))
        assertFalse(e.dragging)
    }

    // ---- right click ----------------------------------------------------------

    @Test fun thumbToMiddleRightClicksOnceUntilReleased() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feed(e, c, hand(), 100)
        feed(e, c, hand(thumbToMiddle = 0.25f), 300)
        assertEquals("edge-triggered: one right-click per contact", 1, r.count("rclick"))
        feed(e, c, hand(thumbToMiddle = 1.0f), 100)
        feed(e, c, hand(thumbToMiddle = 0.25f), 100)
        assertEquals(2, r.count("rclick"))
    }

    // Helpers for the sleep/wake tests below, which go through updateHands()
    // because that is where the sleep gate lives.

    private fun feedHands(
        e: GestureEngine, c: Clock, hands: List<List<Landmark>>, ms: Long
    ) {
        var elapsed = 0L
        while (elapsed < ms) { c.t += 16; e.updateHands(hands, c.t); elapsed += 16 }
    }

    private fun oneHand(landmarks: List<Landmark>) = listOf(landmarks)


    // ---- sleep / wake -----------------------------------------------------
    // The camera reacts to whatever passes in front of it, not just
    // deliberate use — every false positive fixed above came from incidental
    // movement accidentally forming a real gesture shape. Going blind after a
    // period of no genuine output, and requiring a deliberate held-palm
    // gesture to resume, is the backstop for that.
    //
    // This only runs through updateHands() — handleSleep lives there, not in
    // update() — so these tests use updateHands()/oneHand() throughout rather
    // than the feed()/update() helpers the rest of the file uses.

    @Test fun freshEngineStartsAwake() {
        val e = GestureEngine(Recorder())
        assertFalse(e.asleep)
    }

    @Test fun noActivityForSleepAfterFallsAsleep() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // present but producing no real output: stationary, not moving
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        assertTrue("no real output for SLEEP_AFTER should go blind", e.asleep)
    }

    @Test fun genuineActivityResetsTheSleepClock() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.3f)), 100)
        for (i in 1..10) { c.t += 16; e.updateHands(oneHand(hand(handX = 0.3f + 0.02f * i)), c.t) }
        // idle again afterward, but for less than SLEEP_AFTER since that movement
        feedHands(e, c, oneHand(hand(handX = 0.9f)), GestureEngine.SLEEP_AFTER - 1000)
        assertFalse("movement should reset the clock, not just the very first frame", e.asleep)
    }

    @Test fun sleepingIgnoresOrdinaryPointing() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        assertTrue(e.asleep)
        r.reset()
        for (i in 1..10) { c.t += 16; e.updateHands(oneHand(hand(handX = 0.5f + 0.02f * i)), c.t) }
        assertEquals("a sleeping engine must not move the cursor", 0, r.count("move"))
    }

    @Test fun holdingAnOpenPalmWakesUpAfterWakeHold() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        assertTrue(e.asleep)
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = true, others = 3)), GestureEngine.WAKE_HOLD + 200)
        assertFalse("a full held-palm wake gesture should resume normal use", e.asleep)
    }

    // A real false negative this app hit: measured device data showed several
    // wake attempts accumulating well over half the required hold (one
    // reached 2239 of 3000ms) before one dropped tracking frame reset it to
    // zero — repeatedly, since single-hand "is this open" tracking isn't
    // perfectly stable frame to frame. WAKE_GRACE bridges that; these two
    // tests pin down both sides of it.

    @Test fun aBriefBreakInTheHoldIsBridgedByGrace() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        assertTrue(e.asleep)
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = true, others = 3)), 1500)
        // a single dropped-tracking frame, well under WAKE_GRACE
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = false, others = 0)), 100)
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = true, others = 3)), 1600)
        assertFalse("a brief tracking dropout must not restart the wake hold", e.asleep)
    }

    @Test fun aLongBreakInTheHoldResetsIt() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        assertTrue(e.asleep)
        // short enough that even bridging the full WAKE_GRACE window can't
        // reach WAKE_HOLD on its own — the way aBriefBreak...'s 1500ms segment
        // could if this one used a value close to WAKE_HOLD instead
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = true, others = 3)), 2000)
        // a genuine gap, longer than WAKE_GRACE
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = false, others = 0)), GestureEngine.WAKE_GRACE + 200)
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = true, others = 3)), 2000)
        assertTrue("a real gap has to restart the hold, not just paper over lost frames", e.asleep)
    }

    @Test fun wakingUpResumesNormalPointing() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        feedHands(e, c, oneHand(hand(handX = 0.5f, idxUp = true, others = 3)), GestureEngine.WAKE_HOLD + 200)
        assertFalse(e.asleep)
        r.reset()
        for (i in 1..10) { c.t += 16; e.updateHands(oneHand(hand(handX = 0.5f + 0.02f * i)), c.t) }
        assertTrue("once awake, real movement should work again", r.count("move") > 0)
    }
}
