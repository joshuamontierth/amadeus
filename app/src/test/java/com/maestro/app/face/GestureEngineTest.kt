package com.maestro.app.face

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the gesture state machine. This is the safety-critical core
 * (accidental page turns are the whole risk), so the dwell / cooldown / re-arm
 * behaviour, the debounced blink tracking and the false-alarm cases (glancing at the
 * keys, a quick blink, nodding, a face coming and going) are pinned down here.
 * No Android dependencies => runs on the JVM.
 */
class GestureEngineTest {

    private val cfg = GestureConfig() // next = HEAD_RIGHT, prev = HEAD_LEFT, dwell 350, cooldown 1200, balanced

    /** A scripted camera: feeds frames to one engine on a clock and records every page turn. */
    private class Session(val config: GestureConfig) {
        val engine = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        var t = 0L
        var status = EngineStatus.NO_FACE

        fun frame(signals: FaceSignals): GestureOutput {
            val out = engine.onFrame(signals, config, t)
            status = out.status
            out.fired?.let { fired.add(t to it) }
            return out
        }

        /** Feeds [signals] for [ms] at ~30 fps. */
        fun hold(ms: Long, signals: FaceSignals, step: Long = 33): Session = play(ms, step) { signals }

        /** Feeds whatever [signalsAt] returns for each frame, given the time in ms since this call began. */
        fun play(ms: Long, step: Long = 33, signalsAt: (Long) -> FaceSignals): Session {
            val start = t
            while (t < start + ms) {
                frame(signalsAt(t - start))
                t += step
            }
            return this
        }

        fun rest(ms: Long = 800L) = hold(ms, FaceSignals(present = true, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f))
        fun noFace(ms: Long) = hold(ms, FaceSignals(present = false))

        /** Time since [since] at which the nth page turn happened. */
        fun firedAfter(since: Long, n: Int = 0) = fired[n].first - since
    }

    private fun session(c: GestureConfig = cfg) = Session(c)

    private fun face(
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        l: Float = 0.9f,
        r: Float = 0.9f,
        smiling: Float = -1f
    ) = FaceSignals(present = true, yaw = yaw, pitch = pitch, roll = roll, leftEyeOpen = l, rightEyeOpen = r, smiling = smiling)

    private fun headRight() = face(yaw = 30f)
    private fun headLeft() = face(yaw = -30f)
    private fun open() = face(l = 0.95f, r = 0.95f)
    private fun closed() = face(l = 0.05f, r = 0.05f)

    // ------------------------------------------------------------------ poses

    @Test
    fun noFace_reportsNoFace_andNeverFires() {
        val s = session()
        val out = s.frame(FaceSignals(present = false))
        assertEquals(EngineStatus.NO_FACE, out.status)
        assertNull(out.fired)
    }

    @Test
    fun neutralFace_waitsToSettle_thenIsReady_andNeverFires() {
        val s = session()
        assertEquals(EngineStatus.COOLDOWN, s.frame(face()).status) // just appeared: not trusted yet
        s.hold(1000L, face())
        assertEquals(EngineStatus.READY, s.status)
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun holdingHeadRightPastDwell_firesNextExactlyOnce() {
        val s = session().rest()
        val start = s.t
        s.hold(3000L, headRight())
        assertEquals(1, s.fired.size)
        assertEquals(PageAction.NEXT, s.fired[0].second)
        assertTrue("fired after ${s.firedAfter(start)}", s.firedAfter(start) in 350L..450L)
    }

    @Test
    fun briefHeadTurn_shorterThanDwell_doesNotFire() {
        val s = session().rest()
        s.hold(250L, headRight())
        s.hold(2000L, face())
        assertTrue(s.fired.isEmpty())
        assertEquals(EngineStatus.READY, s.status)
    }

    @Test
    fun graceWindow_keepsDwellAliveThroughBriefDropout() {
        val s = session().rest()
        val start = s.t
        s.hold(200L, headRight())
        s.hold(66L, face())            // two frames read neutral
        s.hold(500L, headRight())
        assertEquals(1, s.fired.size)
        assertTrue("fired after ${s.firedAfter(start)}", s.firedAfter(start) <= 600L)
    }

    @Test
    fun heldGestureDoesNotRepeat_untilNeutral() {
        val s = session().rest()
        s.hold(1000L, headRight())
        assertEquals(1, s.fired.size)
        s.hold(3000L, headRight()) // still holding well past the cooldown: no second turn
        assertEquals(1, s.fired.size)
        s.rest()
        s.hold(800L, headRight())
        assertEquals(2, s.fired.size)
    }

    @Test
    fun gestureDuringCooldown_isBlocked_evenIfReleasedAndRepeated() {
        val s = session().rest()
        s.hold(400L, headRight())
        assertEquals(1, s.fired.size)
        s.rest(500L)
        val out = s.hold(400L, headRight()).frame(headRight()) // still inside the 1200 ms cooldown
        assertEquals(EngineStatus.COOLDOWN, out.status)
        assertEquals(1, s.fired.size)
    }

    @Test
    fun headLeft_firesPrevious() {
        val s = session().rest().hold(600L, headLeft())
        assertEquals(listOf(PageAction.PREVIOUS), s.fired.map { it.second })
    }

    @Test
    fun yawThreshold_isRespected() {
        val s = session().rest().hold(2000L, face(yaw = 21f))
        assertTrue(s.fired.isEmpty())
        assertEquals(EngineStatus.READY, s.status)
    }

    @Test
    fun winkMapping_firesConfiguredAction() {
        val s = session(GestureConfig(nextGesture = GestureType.WINK_LEFT, prevGesture = GestureType.WINK_RIGHT)).rest()
        s.hold(600L, face(l = 0.1f, r = 0.9f))
        assertEquals(listOf(PageAction.NEXT), s.fired.map { it.second })
    }

    @Test
    fun bothEyesClosed_isNotMistakenForWink() {
        val s = session(GestureConfig(nextGesture = GestureType.WINK_LEFT, prevGesture = GestureType.WINK_RIGHT)).rest()
        s.hold(600L, closed())
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun unknownEyeProbabilities_doNotTriggerEyeGestures() {
        val s = session(GestureConfig(nextGesture = GestureType.WINK_LEFT)).rest()
        s.hold(800L, face(l = -1f, r = -1f))
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun ambiguousMapping_sameGestureBothDirections_firesNothing() {
        val s = session(GestureConfig(nextGesture = GestureType.HEAD_RIGHT, prevGesture = GestureType.HEAD_RIGHT)).rest()
        s.hold(800L, headRight())
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun noneGesture_isNeverActive() {
        val s = session(GestureConfig(nextGesture = GestureType.NONE, prevGesture = GestureType.NONE)).rest()
        s.hold(800L, headRight())
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun swappedMapping_reversesDirection() {
        val s = session(GestureConfig(nextGesture = GestureType.HEAD_LEFT, prevGesture = GestureType.HEAD_RIGHT)).rest()
        s.hold(600L, headRight())
        assertEquals(listOf(PageAction.PREVIOUS), s.fired.map { it.second })
    }

    @Test
    fun changingDirectionMidHold_restartsDwellTimer() {
        val s = session().rest()
        s.hold(300L, headRight())
        val switched = s.t
        s.hold(600L, headLeft())
        assertEquals(listOf(PageAction.PREVIOUS), s.fired.map { it.second })
        assertTrue("fired after ${s.firedAfter(switched)}", s.firedAfter(switched) >= 350L)
    }

    @Test
    fun smile_firesAfterDwell() {
        val s = session(GestureConfig(nextGesture = GestureType.SMILE, prevGesture = GestureType.NONE)).rest()
        s.hold(600L, face(smiling = 0.9f))
        assertEquals(listOf(PageAction.NEXT), s.fired.map { it.second })
    }

    @Test
    fun smile_belowThreshold_doesNotFire() {
        val s = session(GestureConfig(nextGesture = GestureType.SMILE, prevGesture = GestureType.NONE)).rest()
        s.hold(1000L, face(smiling = 0.5f))
        assertTrue(s.fired.isEmpty())
    }

    // ------------------------------------------------- real gestures, imperfect

    @Test
    fun wobbleAroundTheThreshold_stillCountsAsOneHold() {
        val s = session().rest()
        // Alternates 24° / 20°: the dips are under the entry threshold but above the stay threshold.
        s.play(1500L) { ms -> face(yaw = if ((ms / 33) % 2 == 0L) 24f else 20f) }
        assertEquals(1, s.fired.size)
    }

    @Test
    fun aDroppedFrameMidGesture_doesNotLoseTheGesture() {
        val s = session().rest()
        s.hold(200L, headRight())
        s.frame(FaceSignals(present = false)); s.t += 33
        s.hold(600L, headRight())
        assertEquals(1, s.fired.size)
    }

    @Test
    fun aGestureFromAnOffCenterRestingPose_stillFires() {
        val s = session().hold(30_000L, face(yaw = 14f)) // tablet off to one side
        assertTrue(s.fired.isEmpty())
        s.hold(800L, face(yaw = 38f))
        assertEquals(listOf(PageAction.NEXT), s.fired.map { it.second })
    }

    @Test
    fun relaxedProtection_acceptsAGestureWithTheHeadTiltedMore() {
        val tilted = face(yaw = 30f, pitch = 22f)
        assertTrue(session(cfg.copy(protection = MisfireProtection.BALANCED)).rest().hold(1500L, tilted).fired.isEmpty())
        assertEquals(1, session(cfg.copy(protection = MisfireProtection.RELAXED)).rest().hold(1500L, tilted).fired.size)
    }

    @Test
    fun strictProtection_ignoresAGestureWithTheHeadTiltedLess() {
        val tilted = face(yaw = 30f, pitch = 15f)
        assertEquals(1, session(cfg.copy(protection = MisfireProtection.BALANCED)).rest().hold(1500L, tilted).fired.size)
        assertTrue(session(cfg.copy(protection = MisfireProtection.STRICT)).rest(1000L).hold(1500L, tilted).fired.isEmpty())
    }

    // ------------------------------------------------------ false alarms: head

    @Test
    fun glancingDownAtTheKeys_doesNotTurnThePage() {
        val s = session().rest()
        // Head drops 30° and the yaw estimate drifts to 26° while looking at the instrument.
        s.play(300L) { ms -> face(yaw = 26f * ms / 300f, pitch = -30f * ms / 300f) }
        s.hold(1500L, face(yaw = 26f, pitch = -30f))
        s.play(300L) { ms -> face(yaw = 26f * (1 - ms / 300f), pitch = -30f * (1 - ms / 300f)) }
        s.rest()
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun glancingDownAtTheMusic_fromAFaceThatIsNeverLost_doesNotTurnThePageEvenIfItIsSlow() {
        val s = session().rest()
        s.play(2000L) { ms -> face(yaw = 25f * ms / 2000f, pitch = -25f * ms / 2000f) }
        s.hold(1500L, face(yaw = 25f, pitch = -25f))
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun nodAlongToTheMusic_doesNotTurnThePage() {
        val s = session().rest()
        // 2 Hz nod, ±9° of pitch, with the yaw estimate wobbling along with it up to 23°.
        s.play(4000L) { ms ->
            val phase = 2 * PI * 2.0 * ms / 1000.0
            face(yaw = 15f + 8f * sin(phase).toFloat(), pitch = 9f * sin(phase + 0.7).toFloat())
        }
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun nodsWhereTheYawEstimateFollowsThePitch_doNotTurnThePage() {
        val s = session().rest()
        // Each nod drops the head 14° for 400 ms, and the yaw estimate reads 25° while it is down.
        s.play(6000L) { ms -> if ((ms / 400) % 2 == 0L) face(yaw = 25f, pitch = -14f) else face() }
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun swayingSideToSideWithTheMusic_doesNotTurnThePage() {
        val s = session().rest()
        // 1 Hz sway, ±25° (past the threshold at the peaks, but never held for the dwell).
        s.play(5000L) { ms -> face(yaw = 25f * sin(2 * PI * ms / 1000.0).toFloat()) }
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun headTippedToAShoulder_doesNotTurnThePage() {
        val s = session().rest().hold(1500L, face(yaw = 28f, roll = 35f))
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun restingOffCenter_withOccasionalWobblePastTheThreshold_doesNotTurnThePage() {
        val s = session().hold(25_000L, face(yaw = 9f))
        s.hold(800L, face(yaw = 24f)) // absolute yaw is past 22°, but only 15° from where the head rests
        s.hold(800L, face(yaw = 9f))
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun stalledFrames_cannotFakeADwell() {
        val s = session().rest()
        // The analyzer falls behind: frames arrive 400 ms apart.
        s.hold(1000L, headRight(), step = 400L)
        assertTrue(s.fired.isEmpty())
    }

    // ------------------------------------------ false alarms: face lost, returned

    @Test
    fun faceAppearingAlreadyTurned_doesNotTurnThePage() {
        val s = session().hold(2000L, headRight())
        assertTrue(s.fired.isEmpty())
        s.rest()
        s.hold(600L, headRight())
        assertEquals(1, s.fired.size)
    }

    @Test
    fun faceReturningTurned_afterBeingLost_doesNotTurnThePage() {
        val s = session().rest()
        s.noFace(600L)
        s.hold(2000L, headRight())
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
        s.rest()
        s.hold(600L, headRight())
        assertEquals(1, s.fired.size)
    }

    @Test
    fun faceLostMidGesture_cancelsIt() {
        val s = session().rest()
        s.hold(200L, headRight())
        s.noFace(400L)
        s.hold(1500L, headRight())
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun unusableFrames_countAsNoFace() {
        val s = session().rest()
        val crowded = headRight().copy(usable = false)
        val out = s.hold(1500L, crowded).frame(crowded)
        assertEquals(EngineStatus.NO_FACE, out.status)
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun noFaceForALongTime_thenAGestureFromNeutral_works() {
        val s = session().rest()
        s.noFace(30_000L)
        s.rest()
        s.hold(600L, headLeft())
        assertEquals(listOf(PageAction.PREVIOUS), s.fired.map { it.second })
    }

    // ----------------------------------------------- false alarms: after a turn

    @Test
    fun headSwingingBackPastCenter_doesNotTurnTheOtherWay() {
        val s = session().rest()
        s.hold(1600L, headRight())           // fires, then keeps holding through the cooldown
        assertEquals(1, s.fired.size)
        // Swings back through center in 300 ms and hangs at -25°.
        s.play(300L) { ms -> face(yaw = 30f - 55f * ms / 300f) }
        s.hold(1500L, face(yaw = -25f))
        assertEquals("fired: ${s.fired}", 1, s.fired.size)
    }

    @Test
    fun comingBackToCenter_thenTurningTheOtherWay_works() {
        val s = session().rest()
        s.hold(1600L, headRight())
        s.rest(600L)
        s.hold(600L, headLeft())
        assertEquals(listOf(PageAction.NEXT, PageAction.PREVIOUS), s.fired.map { it.second })
    }

    // ------------------------------------------------------- long blink events

    private val longCfg = GestureConfig(nextGesture = GestureType.BLINK_BOTH, prevGesture = GestureType.NONE)

    @Test
    fun naturalQuickBlink_neverFiresLongBlink() {
        val s = session(longCfg).rest()
        s.hold(180L, closed())   // a normal blink
        s.hold(1500L, open())
        assertTrue("unexpected fires: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun longBlink_firesAtConfiguredDuration_onceOnly_thenAgainAfterReopen() {
        val s = session(longCfg).rest()
        val closeAt = s.t
        s.hold(500L, closed())
        assertTrue(s.fired.isEmpty())
        assertEquals(EngineStatus.ARMING, s.status) // closed, long blink mapped
        // Crosses the 700 ms threshold at ~closure start + 700 -> exactly one fire, while still closed.
        s.hold(900L, closed())
        assertEquals(1, s.fired.size)
        assertEquals(PageAction.NEXT, s.fired[0].second)
        assertTrue("fired at ${s.firedAfter(closeAt)}", s.firedAfter(closeAt) in 700L..850L)
        // Keep holding closed long past the cooldown: no repeat.
        s.hold(2000L, closed())
        assertEquals(1, s.fired.size)
        // Reopen (re-arms), close again -> second fire ~700 ms into the new closure.
        s.hold(800L, open())
        val second = s.t
        s.hold(1200L, closed())
        assertEquals(2, s.fired.size)
        assertTrue("2nd fire at ${s.firedAfter(second, 1)}", s.firedAfter(second, 1) in 700L..850L)
    }

    @Test
    fun longBlinkSlider_isRespected() {
        val slow = longCfg.copy(longBlinkMs = 1000L)
        val s = session(slow).rest()
        // 800 ms closure: longer than a blink, shorter than the configured 1000 ms -> nothing.
        s.hold(800L, closed())
        s.hold(800L, open())
        assertTrue(s.fired.isEmpty())
        val start = s.t
        s.hold(1300L, closed())
        assertEquals(1, s.fired.size)
        assertTrue("fired at ${s.firedAfter(start)}", s.firedAfter(start) in 1000L..1150L)
    }

    @Test
    fun mediumClosure_betweenQuickAndLong_firesNothing() {
        val s = session(longCfg).rest()
        s.hold(550L, closed())   // too long for quick, short of 700
        s.hold(1500L, open())
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun eyelidsDroopingAsTheGazeDropsToTheKeys_isNotALongBlink() {
        val s = session(longCfg).rest()
        // Eye-open probability slides 0.95 -> 0.05 over 600 ms (no head movement), then stays low.
        s.play(600L) { ms -> face(l = 0.95f - 0.9f * ms / 600f, r = 0.95f - 0.9f * ms / 600f) }
        s.hold(2000L, closed())
        assertTrue("fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun eyesFirstSeenClosed_isNotALongBlink() {
        val s = session(longCfg)
        s.hold(2000L, closed())
        assertTrue(s.fired.isEmpty())
    }

    // ----------------------------------------------------- double blink events

    private val dblCfg = GestureConfig(nextGesture = GestureType.DOUBLE_BLINK, prevGesture = GestureType.NONE)

    @Test
    fun deliberateDoubleBlink_fires() {
        val s = session(dblCfg).rest()
        s.hold(200L, closed())   // blink 1
        s.hold(200L, open())     // clean reopen
        s.hold(200L, closed())   // blink 2
        s.hold(500L, open())
        assertEquals(listOf(PageAction.NEXT), s.fired.map { it.second })
    }

    @Test
    fun blinksTooFarApart_doNotPair() {
        val s = session(dblCfg).rest()
        s.hold(200L, closed())
        s.hold(600L, open())     // 600 ms gap > 450 ms limit
        s.hold(200L, closed())
        s.hold(600L, open())
        assertTrue("unexpected fires: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun rapidBlinkFlutter_isRejectedByPreOpenRequirement() {
        val s = session(dblCfg).rest(120L)
        repeat(4) {
            s.hold(120L, closed())
            s.hold(120L, open())
        }
        s.hold(800L, open())
        assertTrue("flutter fired: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun doubleBlinkCompletedDuringCooldown_isDiscarded() {
        val s = session(dblCfg).rest()
        // Pair 1 fires.
        s.hold(200L, closed()); s.hold(200L, open()); s.hold(200L, closed()); s.hold(200L, open())
        assertEquals(1, s.fired.size)
        // Pair 2 entirely inside the cooldown window -> discarded.
        s.hold(200L, closed()); s.hold(200L, open()); s.hold(200L, closed()); s.hold(200L, open())
        assertEquals("pair inside cooldown must not fire", 1, s.fired.size)
        // Pair 3 after the cooldown and a rest -> fires.
        s.hold(1200L, open())
        s.hold(200L, closed()); s.hold(200L, open()); s.hold(200L, closed()); s.hold(500L, open())
        assertEquals(2, s.fired.size)
    }

    // ------------------------------------------------- noise-robustness cases

    @Test
    fun singleNoisyClosedFrame_doesNotCountAsBlink() {
        val s = session(dblCfg).rest()
        s.play(1500L) { ms -> if (ms in 495L..527L) closed() else open() }
        assertTrue(s.fired.isEmpty())
    }

    @Test
    fun singleNoisyOpenFrame_doesNotSplitALongBlink() {
        val s = session(longCfg).rest()
        val start = s.t
        s.play(1500L) { ms -> if (ms in 396L..428L) open() else closed() } // one bogus frame mid-closure
        assertEquals(1, s.fired.size)
        assertTrue("fired at ${s.firedAfter(start)}", s.firedAfter(start) in 700L..850L)
    }

    @Test
    fun wobbleInsideHysteresisBand_staysOneBlink_noPhantomDouble() {
        val s = session(dblCfg).rest()
        s.hold(180L, closed())
        s.hold(66L, face(l = 0.35f, r = 0.35f)) // between closed(0.25) and open(0.55) thresholds: wobble mid-blink
        s.hold(120L, closed())
        s.hold(800L, open())
        // With naive single-threshold logic this reads as two blinks -> double-blink.
        assertTrue("wobble produced a phantom double blink: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun headTurnedAway_suppressesEyeGestures() {
        // Eyes read closed while the head is turned 30° (profile view = junk eye data).
        val s = session(longCfg).rest()
        s.hold(1500L, face(l = 0.05f, r = 0.05f, yaw = 30f))
        assertTrue("fired despite head turn: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun pitchedDown_suppressesEyeGestures() {
        // Looking down (e.g. at the keys): lids droop, eye probabilities crater.
        val s = session(longCfg).rest()
        s.hold(1500L, face(l = 0.05f, r = 0.05f, pitch = -30f))
        assertTrue("fired despite downward pitch: ${s.fired}", s.fired.isEmpty())
    }

    @Test
    fun pitchedDown_suppressesWinkAndSmilePoses() {
        val s = session(GestureConfig(nextGesture = GestureType.SMILE, prevGesture = GestureType.WINK_LEFT)).rest()
        s.hold(1000L, face(pitch = -30f, smiling = 0.95f))
        assertTrue(s.fired.isEmpty())
    }
}
