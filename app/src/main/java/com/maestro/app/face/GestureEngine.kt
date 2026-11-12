package com.maestro.app.face

import kotlin.math.abs

/**
 * Turns a stream of [FaceSignals] into discrete [PageAction]s.
 *
 * Held poses (head turn, wink, smile) must be held for [GestureConfig.dwellMs] of frames that
 * actually show the pose; a lapse of a frame or two pauses the hold instead of ending it. Once in
 * a pose the thresholds relax a little ([STAY_RATIO]), so wobble around the cutoff doesn't break a
 * real hold apart. Blink gestures are *events* from [BlinkTracker].
 *
 * Safeguards against accidental turns:
 *  - **Neutral first:** a gesture only counts after the face has been seen at rest for
 *    [MisfireProtection.settleMs] — which also covers a face that has just (re)appeared, so a head
 *    that arrives already turned, or comes back into view after looking away, never turns a page.
 *  - **Square-on gate:** nothing counts while the head is pitched away from its resting pose
 *    (glancing down at the keys), tipped sideways, or nodding. ML Kit's yaw and eye estimates
 *    are unreliable there. Eye and smile gestures additionally need the head roughly frontal.
 *  - **Adaptive rest pose:** the resting yaw and pitch follow slow drift in posture (a tablet
 *    standing off to one side), within [BASELINE_LIMIT_DEG], so gestures are measured from where
 *    the head actually rests. A deliberate turn is too fast and too large to be absorbed.
 *  - **Cooldown + re-arm:** after firing, new triggers are blocked for [GestureConfig.cooldownMs],
 *    and the face must then sit at rest again for [MisfireProtection.rearmMs] — a head swinging
 *    back past centre doesn't count as the next turn.
 *  - **Face handling:** a face missing for [FACE_GAP_MS] is gone (everything resets); a shorter
 *    gap is a dropped frame and is ridden out.
 *
 * Not thread-safe: it is only ever driven from the single camera-analysis callback.
 */
class GestureEngine {
    private companion object {
        // Sentinel far in the past so a freshly constructed engine is never "in cooldown"
        // regardless of the clock's starting value. Halved to avoid subtraction overflow.
        const val NEVER = Long.MIN_VALUE / 2
        // A held pose survives signal dropouts shorter than this without ending the hold.
        const val GRACE_MS = 150L
        // A face missing for longer than this has left; shorter is just dropped frames.
        const val FACE_GAP_MS = 250L
        // The most one frame can add to a hold, so a stalled analyzer can't fake a long dwell.
        const val MAX_FRAME_STEP_MS = 150L
        // Eye/smile gestures only count while the face is roughly camera-frontal.
        const val EYE_GATE_YAW_DEG = 20f
        // Once in a pose, it is kept down to this fraction of the entry threshold.
        const val STAY_RATIO = 0.8f
        // At rest = within this fraction of the turn threshold from the resting pose.
        const val NEUTRAL_RATIO = 0.6f
        // The resting pose follows the head while it stays within this fraction of the threshold.
        const val REST_RATIO = 0.9f
        const val BASELINE_TAU_MS = 8000f
        const val BASELINE_LIMIT_DEG = 10f
        const val BASELINE_FORGET_MS = 10_000L
        const val SWING_WINDOW_MS = 500L
    }

    private class PitchSample(val at: Long, val pitch: Float)

    private val tracker = BlinkTracker()
    private val pitchHistory = ArrayDeque<PitchSample>()

    private var candidate: PageAction? = null
    private var heldMs = 0L
    private var lastFrameAt = NEVER
    private var lastPoseActiveAt = NEVER
    private var previousActive: Set<GestureType> = emptySet()

    private var lastFire = NEVER
    private var armed = false
    private var neutralSince = NEVER
    private var neutralNeededMs = 0L

    private var lastSeenAt = NEVER
    private var visibleSince = NEVER
    private var baseYaw = 0f
    private var basePitch = 0f

    fun reset() {
        forgetFace()
        lastFire = NEVER
        lastSeenAt = NEVER
        lastFrameAt = NEVER
        baseYaw = 0f
        basePitch = 0f
    }

    fun onFrame(signals: FaceSignals, config: GestureConfig, nowMs: Long): GestureOutput {
        if (!signals.present || !signals.usable) return onNoFace(nowMs)

        val guard = config.protection
        if (visibleSince == NEVER) {
            visibleSince = nowMs
            armed = false
            neutralNeededMs = guard.settleMs
        }
        val dt = (nowMs - lastFrameAt).coerceIn(0L, MAX_FRAME_STEP_MS)
        lastFrameAt = nowMs
        lastSeenAt = nowMs

        val yaw = signals.yaw - baseYaw
        val pitch = signals.pitch - basePitch
        notePitch(signals.pitch, nowMs)

        val squareOn = abs(pitch) <= guard.maxTiltDeg &&
            abs(signals.roll) <= guard.maxRollDeg &&
            pitchSwing() <= guard.maxPitchSwingDeg
        val frontal = squareOn && abs(yaw) <= EYE_GATE_YAW_DEG

        if (squareOn && abs(yaw) <= REST_RATIO * config.yawThresholdDeg && abs(pitch) <= REST_RATIO * guard.maxTiltDeg) {
            val follow = (dt / BASELINE_TAU_MS).coerceAtMost(0.1f)
            baseYaw = (baseYaw + (signals.yaw - baseYaw) * follow).coerceIn(-BASELINE_LIMIT_DEG, BASELINE_LIMIT_DEG)
            basePitch = (basePitch + (signals.pitch - basePitch) * follow).coerceIn(-BASELINE_LIMIT_DEG, BASELINE_LIMIT_DEG)
        }

        if (frontal) {
            tracker.update(signals.leftEyeOpen, signals.rightEyeOpen, config, nowMs)
        } else {
            tracker.reset()
        }
        val events = tracker.poll()

        val active = if (squareOn) activeGestures(signals, yaw, config, frontal) else emptySet()
        previousActive = active
        val action = resolveAction(active, config)

        val neutral = squareOn && !tracker.isClosed && action == null && abs(yaw) <= NEUTRAL_RATIO * config.yawThresholdDeg
        if (!neutral) {
            neutralSince = NEVER
        } else if (neutralSince == NEVER) {
            neutralSince = nowMs
        }
        if (!armed && neutralSince != NEVER && nowMs - neutralSince >= neutralNeededMs) armed = true

        if (nowMs - lastFire < config.cooldownMs) {
            candidate = null
            return GestureOutput(EngineStatus.COOLDOWN, null) // events discarded during cooldown
        }

        if (armed) {
            val eventAction = resolveEvent(events, config)
            if (eventAction != null) return fire(eventAction, nowMs, guard)
        }

        if (action == null) {
            if (armed && candidate != null && nowMs - lastPoseActiveAt <= GRACE_MS) {
                // Brief lapse mid-hold: the hold pauses, it doesn't restart.
                return GestureOutput(EngineStatus.ARMING, null)
            }
            candidate = null
            val status = when {
                !armed -> EngineStatus.COOLDOWN
                tracker.isClosed && mapsGesture(config, GestureType.BLINK_BOTH) -> EngineStatus.ARMING
                else -> EngineStatus.READY
            }
            return GestureOutput(status, null)
        }

        lastPoseActiveAt = nowMs
        if (!armed) {
            // A pose is already being held (from the last turn, or as the face came into view);
            // wait for it to be released.
            candidate = action
            return GestureOutput(EngineStatus.COOLDOWN, null)
        }
        if (action != candidate) {
            candidate = action
            heldMs = 0L
        } else {
            heldMs += dt
        }
        return if (heldMs >= config.dwellMs) {
            fire(action, nowMs, guard)
        } else {
            GestureOutput(EngineStatus.ARMING, null)
        }
    }

    private fun onNoFace(now: Long): GestureOutput {
        if (now - lastSeenAt > FACE_GAP_MS) {
            forgetFace()
            if (now - lastSeenAt > BASELINE_FORGET_MS) {
                baseYaw = 0f
                basePitch = 0f
            }
        }
        lastFrameAt = now
        return GestureOutput(EngineStatus.NO_FACE, null)
    }

    private fun forgetFace() {
        candidate = null
        heldMs = 0L
        lastPoseActiveAt = NEVER
        previousActive = emptySet()
        tracker.reset()
        pitchHistory.clear()
        neutralSince = NEVER
        visibleSince = NEVER
        armed = false
    }

    private fun fire(action: PageAction, now: Long, guard: MisfireProtection): GestureOutput {
        lastFire = now
        armed = false
        neutralSince = NEVER
        neutralNeededMs = guard.rearmMs
        candidate = null
        heldMs = 0L
        return GestureOutput(EngineStatus.COOLDOWN, action)
    }

    private fun notePitch(pitch: Float, now: Long) {
        pitchHistory.addLast(PitchSample(now, pitch))
        while (pitchHistory.first().at < now - SWING_WINDOW_MS) pitchHistory.removeFirst()
    }

    /** How far the head's pitch has travelled over the last half second; nodding along to the music shows up here. */
    private fun pitchSwing(): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in pitchHistory) {
            if (s.pitch < lo) lo = s.pitch
            if (s.pitch > hi) hi = s.pitch
        }
        return hi - lo
    }

    private fun resolveEvent(events: BlinkEvents, config: GestureConfig): PageAction? {
        if (events.longBlink) mappingFor(config, GestureType.BLINK_BOTH)?.let { return it }
        if (events.doubleBlink) mappingFor(config, GestureType.DOUBLE_BLINK)?.let { return it }
        return null
    }

    private fun mappingFor(c: GestureConfig, gesture: GestureType): PageAction? = when {
        c.nextGesture == gesture && c.prevGesture != gesture -> PageAction.NEXT
        c.prevGesture == gesture && c.nextGesture != gesture -> PageAction.PREVIOUS
        else -> null // unmapped, or ambiguously mapped to both directions
    }

    private fun mapsGesture(c: GestureConfig, gesture: GestureType): Boolean =
        c.nextGesture == gesture || c.prevGesture == gesture

    private fun resolveAction(active: Set<GestureType>, config: GestureConfig): PageAction? {
        val wantsNext = config.nextGesture != GestureType.NONE && active.contains(config.nextGesture)
        val wantsPrev = config.prevGesture != GestureType.NONE && active.contains(config.prevGesture)
        return when {
            wantsNext && !wantsPrev -> PageAction.NEXT
            wantsPrev && !wantsNext -> PageAction.PREVIOUS
            else -> null // nothing, or ambiguous (both at once) -> ignore
        }
    }

    /** The factor for a threshold that is easier to stay past than to cross: [whenHeld] if already in [gesture], else 1. */
    private fun stay(gesture: GestureType, whenHeld: Float) = if (gesture in previousActive) whenHeld else 1f

    /** Held poses only, with [yaw] measured from the resting pose. Blink gestures are events emitted by [BlinkTracker]. */
    private fun activeGestures(s: FaceSignals, yaw: Float, c: GestureConfig, frontal: Boolean): Set<GestureType> {
        val set = mutableSetOf<GestureType>()

        if (yaw >= c.yawThresholdDeg * stay(GestureType.HEAD_RIGHT, STAY_RATIO)) set.add(GestureType.HEAD_RIGHT)
        if (yaw <= -c.yawThresholdDeg * stay(GestureType.HEAD_LEFT, STAY_RATIO)) set.add(GestureType.HEAD_LEFT)

        if (frontal) {
            val l = s.leftEyeOpen
            val r = s.rightEyeOpen
            if (l >= 0f && r >= 0f) {
                if (l < c.eyeClosedThreshold * stay(GestureType.WINK_LEFT, 1f / STAY_RATIO) &&
                    r > c.eyeOpenThreshold * stay(GestureType.WINK_LEFT, STAY_RATIO)
                ) set.add(GestureType.WINK_LEFT)
                if (r < c.eyeClosedThreshold * stay(GestureType.WINK_RIGHT, 1f / STAY_RATIO) &&
                    l > c.eyeOpenThreshold * stay(GestureType.WINK_RIGHT, STAY_RATIO)
                ) set.add(GestureType.WINK_RIGHT)
            }
            if (s.smiling >= 0f && s.smiling > c.smileThreshold * stay(GestureType.SMILE, STAY_RATIO)) set.add(GestureType.SMILE)
        }

        return set
    }
}
