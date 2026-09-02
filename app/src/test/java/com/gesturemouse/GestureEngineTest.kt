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
        override fun launchUrl() { events += "launch" }

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

    // ---- crossed palms --------------------------------------------------------
    // This one opens a window on someone else's screen, so the tests lean on the
    // cases where it must *not* fire at least as hard as the case where it must.
    //
    // This is deliberately a single-instant trigger, not a held pose. Two
    // earlier designs (a continuous hold; a hold with a memory of a momentary
    // cross) both failed against real device data: at the exact moment wrists
    // cross, MediaPipe's read is unreliable across the board — not just hand
    // *count*, but "open" and "raised" too, both measured false at the actual
    // crossing instants a real session caught. What's gated on now is a tight
    // wristGap plus a genuine handedness order-swap — not openness, not height
    // — and [looksLikeTwoHands], added after a real false fire turned out to be
    // one physical hand MediaPipe reported as two overlapping detections.

    private fun feedHands(
        e: GestureEngine, c: Clock, hands: List<GestureEngine.Hand>, ms: Long
    ) {
        var elapsed = 0L
        while (elapsed < ms) { c.t += 16; e.updateHands(hands, c.t); elapsed += 16 }
    }

    private fun oneHand(landmarks: List<Landmark>, isRight: Boolean = true) =
        listOf(GestureEngine.Hand(landmarks, isRight))

    private fun mirroredAroundX(landmarks: List<Landmark>, centerX: Float) =
        landmarks.map { Landmark(2 * centerX - it.x, it.y) }

    /**
     * A right hand at [rightX] and an anatomically mirrored left hand at
     * [leftX]. Mirrored, not just translated, because a duplicate detection
     * of one physical hand produces two *identically shaped* landmark sets —
     * exactly what [GestureEngine.EGG_MIN_SPREAD] exists to reject — so a
     * fixture meant to look like two real hands has to actually differ in
     * shape, the way an anatomical left and right hand naturally do.
     */
    private fun twoHands(
        rightX: Float, leftX: Float, handY: Float = 0.25f, idxUp: Boolean = true, others: Int = 3
    ) = listOf(
        GestureEngine.Hand(hand(handX = rightX, handY = handY, idxUp = idxUp, others = others), isRight = true),
        GestureEngine.Hand(
            mirroredAroundX(hand(handX = leftX, handY = handY, idxUp = idxUp, others = others), leftX),
            isRight = false
        )
    )

    /**
     * Two hands, genuinely crossed and close: the right hand sits at the
     * *smaller* x (swapped to screen-left) and the left hand at the larger x —
     * see [GestureEngine.checkEggPose]. Open/raised by default since that's
     * the intended pose, but neither is required to fire — see [crossedFistsAtAnyHeightStillLaunch].
     */
    private fun crossedPalms(gap: Float = 0.02f, handY: Float = 0.25f) =
        twoHands(rightX = 0.5f - gap / 2f, leftX = 0.5f + gap / 2f, handY = handY)

    /** Same shape as [crossedPalms] but each hand on its own, uncrossed side. */
    private fun sideBySidePalms(gap: Float = 0.10f, handY: Float = 0.25f) =
        twoHands(rightX = 0.5f + gap / 2f, leftX = 0.5f - gap / 2f, handY = handY)

    /**
     * The false-positive shape this app actually hit: one physical hand's
     * landmarks, copied unchanged and offset by [gap] in each direction. This
     * is what MediaPipe's own duplicate-detection artifact looks like — every
     * landmark equally close, not just the wrist — as opposed to [twoHands],
     * where mirroring makes the fingers splay apart from the crossing point.
     */
    private fun duplicateHandGhost(gap: Float = 0.02f, handY: Float = 0.25f): List<GestureEngine.Hand> {
        val base = hand(handX = 0.5f, handY = handY, idxUp = true, others = 3)
        return listOf(
            GestureEngine.Hand(base.map { Landmark(it.x - gap / 2f, it.y) }, isRight = true),
            GestureEngine.Hand(base.map { Landmark(it.x + gap / 2f, it.y) }, isRight = false)
        )
    }

    @Test fun crossedPalmsLaunchTheUrlImmediately() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        c.t += 16
        e.updateHands(crossedPalms(), c.t)
        assertEquals("a single genuine crossing frame is enough — no hold required", 1, r.count("launch"))
        assertTrue(e.eggPosed)
    }

    @Test fun sameHandsUncrossedNeverLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // identical positions and gap to crossedPalmsLaunchTheUrlImmediately —
        // the only difference is which hand is on which side
        feedHands(e, c, sideBySidePalms(), 1500)
        assertEquals("two hands held together is not the same as crossed", 0, r.count("launch"))
        assertFalse(e.eggPosed)
    }

    @Test fun bothHandsSeenAsTheSameSideDoNotLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // a plausible tracker misread: two hands, both classified "Right"
        val hands = listOf(
            GestureEngine.Hand(hand(handX = 0.45f, handY = 0.25f, idxUp = true, others = 3), isRight = true),
            GestureEngine.Hand(hand(handX = 0.55f, handY = 0.25f, idxUp = true, others = 3), isRight = true)
        )
        feedHands(e, c, hands, 1500)
        assertEquals("without a left hand there is nothing to check crossing against", 0, r.count("launch"))
    }

    // The trade-off this design deliberately makes: real crossing instants
    // measured false for "open" and "raised" too, so requiring either would
    // mean never firing on a genuine cross. Crossed fists at any height now
    // fire — that's intentional, not a regression. wristGap + handedness
    // order-swap alone is the selectivity, and it held up empirically.

    @Test fun crossedFistsAtAnyHeightStillLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        val fists = twoHands(rightX = 0.49f, leftX = 0.51f, handY = 0.8f, idxUp = false, others = 0)
        c.t += 16
        e.updateHands(fists, c.t)
        assertEquals(
            "openness and height are not gated on — a real crossing instant reads false on both",
            1, r.count("launch")
        )
    }

    // A real false fire: one hand, dragged normally, occasionally reported by
    // MediaPipe as two overlapping detections with one misclassified as the
    // opposite hand — a fake crossed pair with a razor-thin wristGap. Gap
    // alone can't reject this; looksLikeTwoHands can, because a duplicate
    // stays just as close everywhere, not just at the wrist.

    @Test fun aDuplicateDetectionOfOneHandDoesNotLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        c.t += 16
        e.updateHands(duplicateHandGhost(), c.t)
        assertEquals(
            "the same hand measured against itself is not a crossing",
            0, r.count("launch")
        )
    }

    /**
     * Every landmark except the wrist offset by a distinct, generous amount —
     * unlike [twoHands] (which reuses [hand]'s handful of finger offsets and
     * can coincidentally lose spread at specific gaps), avgGap here always
     * clears [GestureEngine.EGG_MIN_SPREAD] by a wide margin regardless of
     * wristGap. For isolating the wristGap boundary alone.
     *
     * [RICH_SCALE] is this fixture's wrist-to-knuckle distance — the yardstick
     * the engine divides by, since [GestureEngine.EGG_TIGHT_GAP] is a multiple
     * of hand size, not a raw frame fraction.
     */
    private fun richHands(rightX: Float, leftX: Float, handY: Float = 0.25f): List<GestureEngine.Hand> {
        fun build(baseX: Float, sign: Float) = List(21) { i ->
            if (i == GestureEngine.WRIST) Landmark(baseX, handY)
            else Landmark(baseX + sign * (0.02f + i * 0.01f), handY)
        }
        return listOf(
            GestureEngine.Hand(build(rightX, 1f), isRight = true),
            GestureEngine.Hand(build(leftX, -1f), isRight = false)
        )
    }

    /** [richHands]' MID_MCP sits 0.02 + 9*0.01 from its wrist. */
    private val RICH_SCALE = 0.11f

    private fun richHandsAtScaledGap(scaledGap: Float): List<GestureEngine.Hand> {
        val raw = scaledGap * RICH_SCALE
        return richHands(rightX = 0.5f - raw / 2f, leftX = 0.5f + raw / 2f)
    }

    @Test fun justWithinTheTightGapLaunches() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        c.t += 16
        e.updateHands(richHandsAtScaledGap(GestureEngine.EGG_TIGHT_GAP - 0.1f), c.t)
        assertEquals(1, r.count("launch"))
    }

    @Test fun justOutsideTheTightGapDoesNotLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        c.t += 16
        e.updateHands(richHandsAtScaledGap(GestureEngine.EGG_TIGHT_GAP + 0.1f), c.t)
        assertEquals("crossed but not tight enough to trust outright", 0, r.count("launch"))
    }

    /**
     * The distance bug that prompted hand-scaling: rubbing both eyes about
     * four feet from the camera fired the easter egg, because everything
     * shrinks in frame at range and an ordinary two-handed movement landed
     * inside a raw threshold tuned at arm's length. Same pose, same raw
     * geometry, hands a quarter the size — must still be rejected.
     */
    @Test fun aDistantSmallHandedPoseDoesNotLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        val far = crossedPalms().map { hd ->
            hd.copy(landmarks = hd.landmarks.map { Landmark(0.5f + (it.x - 0.5f) / 4f, 0.5f + (it.y - 0.5f) / 4f) })
        }
        // shrink the *pose* but not the separation: hands stay as far apart in
        // raw frame terms as an up-close cross, which is exactly what a distant
        // non-crossing movement looks like
        val spread = far.mapIndexed { i, hd ->
            val push = if (hd.isRight) -0.03f else 0.03f
            hd.copy(landmarks = hd.landmarks.map { Landmark(it.x + push, it.y) })
        }
        feedHands(e, c, spread, 1500)
        assertEquals("thresholds must hold at any distance from the camera", 0, r.count("launch"))
    }

    @Test fun aCrossThatIsNotLevelDoesNotLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // tight in x, but one hand well above the other — hands passing each
        // other rather than wrists crossing
        val notLevel = listOf(
            GestureEngine.Hand(hand(handX = 0.49f, handY = 0.15f, idxUp = true, others = 3), isRight = true),
            GestureEngine.Hand(
                mirroredAroundX(hand(handX = 0.51f, handY = 0.55f, idxUp = true, others = 3), 0.51f),
                isRight = false
            )
        )
        feedHands(e, c, notLevel, 1500)
        assertEquals("a real cross is level, not one hand above the other", 0, r.count("launch"))
    }

    @Test fun handsHeldTooFarApartDoNotLaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, crossedPalms(gap = 0.5f), 1500)
        assertEquals("hands apart is not the tight cross this trusts outright", 0, r.count("launch"))
    }

    @Test fun repeatedCrossedFramesLaunchOnlyOnceDueToCooldown() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, crossedPalms(), 3000)
        assertEquals("a cooldown, not a hold, is what limits repeat firing", 1, r.count("launch"))
    }

    @Test fun reposingInsideTheCooldownDoesNotRelaunch() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, crossedPalms(), 1000)
        assertEquals(1, r.count("launch"))
        feedHands(e, c, oneHand(hand(idxUp = true)), 200)   // hands drop
        feedHands(e, c, crossedPalms(), 1000)               // and cross again
        assertEquals("the cooldown has to survive the hands dropping in between", 1, r.count("launch"))
    }

    @Test fun theCursorHoldsStillWhileHandsAreCrossed() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), 200)
        r.reset()
        for (i in 1..40) {
            c.t += 16
            // both hands drifting sideways while still crossed
            e.updateHands(crossedPalms().map { hd ->
                hd.copy(landmarks = hd.landmarks.map { Landmark(it.x + 0.01f * i, it.y) })
            }, c.t)
        }
        assertEquals("a crossing must not also steer the cursor", 0, r.count("move"))
        assertFalse(e.onPad)
    }

    @Test fun oneHandStillDrivesTheCursorThroughUpdateHands() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.3f)), 200)
        assertTrue("a single hand must behave exactly as before", e.onPad)
        r.reset()
        for (i in 1..10) { c.t += 16; e.updateHands(oneHand(hand(handX = 0.3f + 0.01f * i)), c.t) }
        assertTrue("and still move the cursor", r.count("move") > 0)
        assertEquals(0, r.count("launch"))
    }

    @Test fun aSecondHandDoesNotDisturbNormalPointing() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        // pointing with one hand while the other rests far away — the
        // handedness order happens to read as "crossed" here (whichever hand
        // is labeled Right just happens to sit at the smaller x), but the
        // wristGap is nowhere near tight enough to trust
        val pair = listOf(
            GestureEngine.Hand(hand(handX = 0.3f), isRight = true),
            GestureEngine.Hand(hand(handX = 0.8f, handY = 0.8f, idxUp = false), isRight = false)
        )
        feedHands(e, c, pair, 200)
        assertTrue(e.onPad)
        assertEquals(0, r.count("launch"))
    }

    @Test fun losingTheHandsClearsThePose() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        c.t += 16
        e.updateHands(crossedPalms(), c.t)
        assertTrue(e.eggPosed)
        e.release()
        assertFalse(e.eggPosed)
    }

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

    @Test fun sleepingIgnoresTheEasterEggToo() {
        val r = Recorder(); val e = GestureEngine(r); val c = Clock()
        feedHands(e, c, oneHand(hand(handX = 0.5f)), GestureEngine.SLEEP_AFTER + 500)
        assertTrue(e.asleep)
        r.reset()
        c.t += 16
        e.updateHands(crossedPalms(), c.t)
        assertEquals("sleeping means blind to every gesture, not just pointing", 0, r.count("launch"))
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
