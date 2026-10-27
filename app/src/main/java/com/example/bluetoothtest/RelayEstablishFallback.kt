package com.example.bluetoothtest

/**
 * Relay parent fallback on link-establishment failure (default on; DEBUG
 * `test_relay_establish_fallback=false` is the control arm).
 *
 * Why: in mesh4ab-52681dbf-loop14b-1 0006, the M31 relay was central to its child. The M31 HCI snoop
 * shows ten LE Connection Complete events at a 45 ms interval, each followed about 270 ms later
 * (6 connection events) by Disconnection Complete reason 0x3E (Connection Failed to be Established;
 * Core v5.4 Vol 1 Part F 2.62). The stack reported GATT status 133. 1M retries failed alike, because
 * establishment always runs on the advertising PHY. The child waited out a 15 s CoC accept timeout,
 * got an 8 s route cooldown and picked the same relay again.
 *
 * Rule: hop-local, no phone list. [threshold] establishment failures within [windowMs] mark a peer for
 * [markMs].
 * - A child ranks a marked parent after every unmarked one, so another relay or the source is chosen
 *   when one is offered and admission allows; a marked parent stays a last resort.
 * - A relay stops connecting to a marked child, which withdraws its offer for that child. The child's
 *   accept then times out and it falls back as above.
 * A successful link clears the mark.
 */
internal class EstablishmentFailureTracker(
    private val threshold: Int = THRESHOLD,
    private val windowMs: Long = WINDOW_MS,
    private val markMs: Long = MARK_MS,
) {
    private val failures = HashMap<String, MutableList<Long>>()
    private val marks = HashMap<String, Long>()
    private val levels = HashMap<String, Int>()

    /**
     * Records one establishment failure; true when this failure newly marks [key]. [decaying] (the
     * Experimental `test_relay_establish_budget`) escalates the mark 30/60/120/300 s on consecutive marks
     * of the same peer without a success in between, instead of a flat [markMs].
     */
    @Synchronized
    fun onFailure(key: String, nowMs: Long, decaying: Boolean = false): Boolean {
        val recent = failures.getOrPut(key) { mutableListOf() }
        recent.removeAll { nowMs - it !in 0 until windowMs }
        recent += nowMs
        if (recent.size < threshold || isMarked(key, nowMs)) return false
        val level = levels[key] ?: 0
        marks[key] = nowMs + if (decaying) decayingMarkMs(level) else markMs
        levels[key] = level + 1
        recent.clear()
        return true
    }

    @Synchronized
    fun isMarked(key: String, nowMs: Long): Boolean {
        val until = marks[key] ?: return false
        if (nowMs >= until) {
            marks.remove(key)
            return false
        }
        return true
    }

    /** Milliseconds left on [key]'s mark (0 when unmarked). */
    @Synchronized
    fun remainingMs(key: String, nowMs: Long): Long {
        val until = marks[key] ?: return 0L
        return (until - nowMs).coerceAtLeast(0L)
    }

    private val stalls = HashMap<String, MutableList<Long>>()
    private val stallLevels = HashMap<String, Int>()

    /**
     * Records one guard-detected hop-stage stall ([RelayChildStallGuard]: the link was silent after the connection update, the
     * dead-anchor signature) and returns true when it newly marks [key]. Stalls are not refusals: a stall says this attempt drew
     * a dead anchor phase (about 16 percent of attempts), not that the child is unreachable. They count apart from
     * [onFailure], need [stallThreshold] within [stallWindowMs], and mark only for [STALL_MARKS_MS] (30 s, 60 s, 120 s, then
     * 300 s on consecutive marks of the same peer without a success), so two dead anchors in a row never withdraw the offer for
     * the full [MARK_MS] (b14-audio-chain-a5a8f056 music r1: stalls 15.9 s apart marked the child for 5 minutes, twice).
     */
    @Synchronized
    fun onStall(key: String, nowMs: Long, stallThreshold: Int = STALL_THRESHOLD, stallWindowMs: Long = STALL_WINDOW_MS): Boolean {
        val recent = stalls.getOrPut(key) { mutableListOf() }
        recent.removeAll { nowMs - it !in 0 until stallWindowMs }
        recent += nowMs
        if (recent.size < stallThreshold || isMarked(key, nowMs)) return false
        val level = stallLevels[key] ?: 0
        marks[key] = nowMs + STALL_MARKS_MS[level.coerceIn(0, STALL_MARKS_MS.lastIndex)]
        stallLevels[key] = level + 1
        recent.clear()
        return true
    }

    @Synchronized
    fun onSuccess(key: String) {
        failures.remove(key)
        marks.remove(key)
        levels.remove(key)
        stalls.remove(key)
        stallLevels.remove(key)
    }

    companion object {
        const val THRESHOLD = 2
        const val WINDOW_MS = 120_000L
        const val MARK_MS = 5L * 60 * 1_000

        /** Guard stalls needed within [STALL_WINDOW_MS] to mark a child (refusals need [THRESHOLD]). */
        const val STALL_THRESHOLD = 3
        const val STALL_WINDOW_MS = 120_000L

        /** Marks for consecutive stall marks of the same peer: short, because a dead anchor redraws on the next link. */
        val STALL_MARKS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

        /** Escalating marks of the Experimental establishment budget: 30 s, 60 s, 120 s, then 300 s. */
        private val DECAYING_MARKS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

        fun decayingMarkMs(level: Int): Long = DECAYING_MARKS_MS[level.coerceIn(0, DECAYING_MARKS_MS.lastIndex)]
    }
}

/**
 * Child side: the parent never connected (CoC accept timed out, or a GATT join's wait ended with no connection: review B4,
 * 2026-10-27; before, the GATT join ended without a stop cause, so the fallback never counted a GATT parent).
 */
internal fun isChildEstablishmentFailure(wasActive: Boolean, stopSignature: String?): Boolean =
    !wasActive && (
        stopSignature?.contains("accept timed out", ignoreCase = true) == true ||
            stopSignature?.startsWith(ReceiverStopKind.JOIN_TIMEOUT.name + "|") == true
        )

/**
 * Relay side: the child connection failed before hop authentication at link establishment (GATT
 * status 133/62, the stack's report of HCI 0x3E, or an establishment timeout).
 */
internal fun isRelayEstablishmentFailure(hopAuthenticated: Boolean, message: String?): Boolean {
    if (hopAuthenticated || message == null) return false
    val text = message.lowercase()
    return "status 133" in text || "status 62" in text || "establish" in text
}

/** What a failed relay child attempt is to the establishment tracker. */
internal enum class RelayChildFailureKind {
    /** Not an establishment failure (the link was authenticated, or another error). */
    NONE,

    /** The stack refused or timed out the connection (status 133/62, "establish"): counted with [EstablishmentFailureTracker.onFailure]. */
    REFUSAL,

    /** The stall guard closed a link that was silent before hop authentication: counted with [EstablishmentFailureTracker.onStall]. */
    STALL,
}

/**
 * [stallSeparate] false (the control arm `test_relay_stall_separate=false`) counts a stall as an ordinary refusal, as before. A stall
 * is checked first: the error that follows a guard close is an EOF, but a stalled attempt whose message also names a 133 is still
 * the stall, not a refusal.
 */
internal fun classifyRelayChildFailure(
    hopAuthenticated: Boolean,
    message: String?,
    stalled: Boolean,
    stallSeparate: Boolean,
): RelayChildFailureKind = when {
    isRelayStallEstablishmentFailure(stalled, hopAuthenticated) ->
        if (stallSeparate) RelayChildFailureKind.STALL else RelayChildFailureKind.REFUSAL
    isRelayEstablishmentFailure(hopAuthenticated, message) -> RelayChildFailureKind.REFUSAL
    else -> RelayChildFailureKind.NONE
}

/** Selection rank: 1 for a marked (establishment-failing) parent, so it sorts after every other route. */
internal fun establishmentRank(marked: Boolean, enabled: Boolean): Int = if (enabled && marked) 1 else 0

/** The relay's tracker, kept across forwarders so a re-started forwarder remembers its failing child. */
internal object RelayChildEstablishment {
    val tracker = EstablishmentFailureTracker()
}
