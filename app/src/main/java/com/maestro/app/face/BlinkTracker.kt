package com.maestro.app.face

/** Discrete blink events produced by [BlinkTracker] (at most one per closure). */
data class BlinkEvents(val doubleBlink: Boolean, val longBlink: Boolean)

/**
 * Debounced both-eyes blink tracker. Raw per-frame eye probabilities are noisy
 * (single-frame dropouts, wobble around thresholds), so the eyes-closed state is
 * derived with three layers of filtering before any event is emitted:
 *
 *  - **Hysteresis:** the state flips to CLOSED only when *both* eyes drop below the
 *    closed threshold, and back to OPEN only when *either* eye rises above the open
 *    threshold. Readings between the two thresholds never flip the state, so wobble
 *    around a single cutoff can't split one blink into two.
 *  - **Debounce:** a state change must persist for [STATE_DEBOUNCE_MS] *and*
 *    [MIN_STATE_SAMPLES] consecutive frames before it is accepted; a lone
 *    misclassified frame can neither start, end, nor split a closure.
 *  - **Duration classes:** a completed closure counts as a *quick blink* only at
 *    80–400 ms (natural blink territory ends ~400 ms; shorter is noise). Eyes held
 *    closed for the user-set long-blink duration fire a *long blink* the moment the
 *    threshold is crossed. Anything in between is discarded entirely.
 *
 * Only a closure that starts the way a blink does counts toward any event: the eyes go from
 * clearly open to closed within [MAX_ONSET_MS]. Eyelids drooping as the gaze drops to the keys
 * or the lap pass through the in-between readings for much longer and are ignored.
 *
 * A double blink requires two quick blinks with a clean debounced reopen between
 * them, at most [DOUBLE_BLINK_GAP_MS] apart, and the eyes must have been open for
 * [PRE_OPEN_MS] before the first — so bursts of rapid natural blinking (dry eyes,
 * concentration flutter) never chain into a trigger. A long blink needs the same
 * open period first, so eyes that were never seen open can't fire it.
 */
class BlinkTracker {
    companion object {
        const val STATE_DEBOUNCE_MS = 70L
        const val MIN_STATE_SAMPLES = 2
        const val MIN_BLINK_MS = 80L
        const val MAX_QUICK_BLINK_MS = 400L
        const val DOUBLE_BLINK_GAP_MS = 450L
        const val PRE_OPEN_MS = 200L
        const val MAX_ONSET_MS = 200L
        private const val NEVER = Long.MIN_VALUE / 2
    }

    private enum class State { UNKNOWN, OPEN, CLOSED }

    private var confirmed = State.UNKNOWN
    private var pending: State? = null
    private var pendingSince = 0L
    private var pendingSamples = 0
    private var pendingOnsetMs = Long.MAX_VALUE // how long the eyes took to get from open to the pending closure

    private var lastOpenReadingAt = NEVER  // most recent frame with an eye clearly open
    private var openSince = NEVER          // start of the current confirmed OPEN period
    private var closedStart = NEVER        // start of the current confirmed CLOSED period
    private var openBeforeClosureMs = -1L  // how long the eyes were open before this closure
    private var closureOnsetMs = Long.MAX_VALUE
    private var longConsumed = false       // long blink already emitted for this closure
    private var firstQuickBlinkEnd = NEVER // end time of a valid first blink of a pair

    private var doubleBlinkReady = false
    private var longBlinkReady = false

    val isClosed: Boolean get() = confirmed == State.CLOSED

    fun reset() {
        confirmed = State.UNKNOWN
        pending = null
        pendingSamples = 0
        pendingOnsetMs = Long.MAX_VALUE
        lastOpenReadingAt = NEVER
        openSince = NEVER
        closedStart = NEVER
        openBeforeClosureMs = -1L
        closureOnsetMs = Long.MAX_VALUE
        longConsumed = false
        firstQuickBlinkEnd = NEVER
        doubleBlinkReady = false
        longBlinkReady = false
    }

    /** Returns pending events exactly once; the caller decides whether to act on them. */
    fun poll(): BlinkEvents {
        val events = BlinkEvents(doubleBlinkReady, longBlinkReady)
        doubleBlinkReady = false
        longBlinkReady = false
        return events
    }

    fun update(leftOpen: Float, rightOpen: Float, config: GestureConfig, now: Long) {
        if (leftOpen < 0f || rightOpen < 0f) {
            reset()
            return
        }
        val raw = when {
            leftOpen < config.eyeClosedThreshold && rightOpen < config.eyeClosedThreshold -> State.CLOSED
            leftOpen > config.eyeOpenThreshold || rightOpen > config.eyeOpenThreshold -> State.OPEN
            else -> null // inside the hysteresis band: keep the current state
        }
        if (raw == State.OPEN) lastOpenReadingAt = now
        if (raw != null) advance(raw, now)

        // Fire the long blink as soon as the closure crosses the configured duration,
        // while the eyes are still closed — responsive, and it can't re-fire because
        // the closure is marked consumed.
        if (confirmed == State.CLOSED && !longConsumed && closureIsBlinkLike() && now - closedStart >= config.longBlinkMs) {
            longBlinkReady = true
            longConsumed = true
            firstQuickBlinkEnd = NEVER
        }
    }

    private fun closureIsBlinkLike() = closureOnsetMs <= MAX_ONSET_MS && openBeforeClosureMs >= PRE_OPEN_MS

    private fun advance(raw: State, now: Long) {
        if (raw == confirmed) {
            pending = null
            pendingSamples = 0
            return
        }
        if (raw == pending) {
            pendingSamples++
        } else {
            // A different reading interrupts an in-flight transition. Under
            // sample-and-hold the pending state persisted until this frame, so commit
            // it retroactively if it already met the debounce bar; otherwise it was
            // chatter and is dropped.
            val p = pending
            if (p != null && pendingSamples >= MIN_STATE_SAMPLES && now - pendingSince >= STATE_DEBOUNCE_MS) {
                commit(p, pendingSince)
            }
            pending = raw
            pendingSince = now
            pendingSamples = 1
            if (raw == State.CLOSED) {
                pendingOnsetMs = if (lastOpenReadingAt == NEVER) Long.MAX_VALUE else now - lastOpenReadingAt
            }
        }
        if (pendingSamples >= MIN_STATE_SAMPLES && now - pendingSince >= STATE_DEBOUNCE_MS) {
            commit(pending ?: return, pendingSince)
            pending = null
            pendingSamples = 0
        }
    }

    private fun commit(state: State, at: Long) {
        val previous = confirmed
        confirmed = state
        when (state) {
            State.CLOSED -> {
                closedStart = at
                longConsumed = false
                closureOnsetMs = pendingOnsetMs
                openBeforeClosureMs =
                    if (previous == State.OPEN && openSince != NEVER) at - openSince else -1L
            }
            State.OPEN -> {
                if (previous == State.CLOSED && closedStart != NEVER) classifyClosure(closedStart, at)
                openSince = at
                closedStart = NEVER
            }
            State.UNKNOWN -> Unit
        }
    }

    private fun classifyClosure(start: Long, end: Long) {
        if (longConsumed || closureOnsetMs > MAX_ONSET_MS) {
            // This closure already fired a long blink (so it can't also count as a quick
            // one), or it drooped shut rather than blinked.
            firstQuickBlinkEnd = NEVER
            return
        }
        val duration = end - start
        if (duration in MIN_BLINK_MS..MAX_QUICK_BLINK_MS) {
            if (firstQuickBlinkEnd != NEVER && start - firstQuickBlinkEnd <= DOUBLE_BLINK_GAP_MS) {
                doubleBlinkReady = true
                firstQuickBlinkEnd = NEVER
            } else {
                // Only a blink preceded by comfortably-open eyes may start a pair,
                // so flutter bursts can't chain into a double blink.
                firstQuickBlinkEnd = if (openBeforeClosureMs >= PRE_OPEN_MS) end else NEVER
            }
        } else {
            // Medium-length or sub-threshold closures are neither quick nor long:
            // discard them and break any pair in progress.
            firstQuickBlinkEnd = NEVER
        }
    }
}
