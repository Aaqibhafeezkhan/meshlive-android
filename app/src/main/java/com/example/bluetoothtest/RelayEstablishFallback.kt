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

    /** Records one establishment failure; true when this failure newly marks [key]. */
    @Synchronized
    fun onFailure(key: String, nowMs: Long): Boolean {
        val recent = failures.getOrPut(key) { mutableListOf() }
        recent.removeAll { nowMs - it !in 0 until windowMs }
        recent += nowMs
        if (recent.size < threshold || isMarked(key, nowMs)) return false
        marks[key] = nowMs + markMs
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

    @Synchronized
    fun onSuccess(key: String) {
        failures.remove(key)
        marks.remove(key)
    }

    companion object {
        const val THRESHOLD = 2
        const val WINDOW_MS = 120_000L
        const val MARK_MS = 5L * 60 * 1_000
    }
}

/** Child side: the parent never connected (CoC accept timed out before the link was ever active). */
internal fun isChildEstablishmentFailure(wasActive: Boolean, stopSignature: String?): Boolean =
    !wasActive && stopSignature?.contains("accept timed out", ignoreCase = true) == true

/**
 * Relay side: the child connection failed before hop authentication at link establishment (GATT
 * status 133/62, the stack's report of HCI 0x3E, or an establishment timeout).
 */
internal fun isRelayEstablishmentFailure(hopAuthenticated: Boolean, message: String?): Boolean {
    if (hopAuthenticated || message == null) return false
    val text = message.lowercase()
    return "status 133" in text || "status 62" in text || "establish" in text
}

/** Selection rank: 1 for a marked (establishment-failing) parent, so it sorts after every other route. */
internal fun establishmentRank(marked: Boolean, enabled: Boolean): Int = if (enabled && marked) 1 else 0

/** The relay's tracker, kept across forwarders so a re-started forwarder remembers its failing child. */
internal object RelayChildEstablishment {
    val tracker = EstablishmentFailureTracker()
}
