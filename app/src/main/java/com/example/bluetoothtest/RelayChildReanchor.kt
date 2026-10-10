package com.example.bluetoothtest

/**
 * Relay child re-anchoring (default on; DEBUG launch extra `test_relay_child_reanchor=false` is the
 * control arm). A relay is peripheral to its parent and central to its child, and both links run at
 * 15 ms with a fixed relative phase. HCI snoop (benchmarks/runs/relaylink-*) showed that when the
 * child's anchor falls inside the parent's 7-10 ms multi-packet window the child link carries 4-37
 * kbps for the whole connection (3 of 9 default runs), otherwise 349-378 kbps. Interval and priority
 * changes did not help (BALANCED gave 45 ms; requestLeConnectionUpdate is a blocked hidden API on
 * Android 11), but a new connection draws a new anchor: the v6 stall release recovered one starved
 * run to 378 kbps. So a relay that sees its child starved closes that child's connection (no wire
 * signal, no withdrawal record); the child's existing link-loss recovery reconnects, preferring the
 * same parent ([preferredRecoveryRank]), and gets a new anchor. At most [RelayReanchorBudget.maxAttempts]
 * per child per minute, after which the withdrawal policy decides as before.
 */
internal class RelayChildStarvation(
    private val windowNs: Long = WINDOW_NS,
    private val thresholdKbps: Int = THRESHOLD_KBPS,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /**
     * One timer tick: [deliveredBytes] is everything the writer has handed the child link so far,
     * [backlogAgeMs] how far the child's queue is behind. Returns the delivered rate when the child
     * has been starved for a whole window with media waiting the entire time, else null.
     */
    @Synchronized
    fun onTick(nowNs: Long, deliveredBytes: Long, backlogAgeMs: Long): Int? {
        if (backlogAgeMs <= 0L) {
            // Nothing waiting: a quiet link is not a starved one.
            samples.clear()
            return null
        }
        samples.addLast(nowNs to deliveredBytes)
        while (samples.size >= 2 && nowNs - samples[1].first >= windowNs) samples.removeFirst()
        val (startNs, startBytes) = samples.first()
        val spanNs = nowNs - startNs
        if (spanNs < windowNs) return null
        val kbps = ((deliveredBytes - startBytes) * 8 * 1_000_000L / spanNs).toInt()
        return kbps.takeIf { it < thresholdKbps }
    }

    @Synchronized
    fun reset() = samples.clear()

    companion object {
        const val WINDOW_NS = 2_000_000_000L
        /** Far below one 251-octet PDU per 15 ms event (131 kbps); starved runs measured 4-37 kbps. */
        const val THRESHOLD_KBPS = 50
    }
}

/** Re-anchor attempts per child: at most [maxAttempts] inside any [windowNs]. */
internal class RelayReanchorBudget(
    private val maxAttempts: Int = 2,
    private val windowNs: Long = 60_000_000_000L,
) {
    private val attempts = HashMap<String, ArrayDeque<Long>>()

    /** Returns this attempt's number for [child], or null when the budget is spent. */
    @Synchronized
    fun tryAttempt(child: String, nowNs: Long): Int? {
        val times = attempts.getOrPut(child) { ArrayDeque() }
        while (times.isNotEmpty() && nowNs - times.first() >= windowNs) times.removeFirst()
        if (times.size >= maxAttempts) return null
        times.addLast(nowNs)
        return times.size
    }
}

/**
 * Child side: after a plain link loss (not a withdrawal) of an active route through a relay, try that
 * same parent first, so a relay's re-anchor reconnects the same child instead of rerouting it to a
 * cheaper route. 0 sorts first. Null [preferredParent] keeps today's ranking.
 */
internal fun preferredRecoveryRank(nodeIdPrefix: ByteArray, preferredParent: ByteArray?): Int =
    if (preferredParent != null && preferredParent.contentEquals(nodeIdPrefix)) 0 else 1

/** The child prefers its previous relay parent only after a link loss of an active relayed route. */
internal fun recoveryParentPreference(
    wasActive: Boolean,
    linkLoss: Boolean,
    parentRouteCost: Int?,
    parentPrefix: ByteArray?,
    enabled: Boolean,
): ByteArray? = parentPrefix?.copyOf()?.takeIf {
    enabled && wasActive && linkLoss && (parentRouteCost ?: 0) > 0
}

/**
 * How long a child waits for its previous relay's offer before reconnecting elsewhere. In the logged
 * recoveries the first offer of the same stream arrived 106-147 ms after the loss (A15s 2026-10-10
 * 09:52:10.619 -> 10.766, 10:04:39.357 -> 39.463, 10:04:48.176 -> 48.291), so 2 s covers several
 * advertising rounds and bounds the extra wait when that relay is really gone.
 */
const val PREFERRED_PARENT_GRACE_MS = 2_000L

/** End of the wait for [preferredParent], or 0 when there is none (or the grace is switched off). */
internal fun preferredParentGraceDeadline(preferredParent: ByteArray?, nowMs: Long, enabled: Boolean): Long =
    if (enabled && preferredParent != null) nowMs + PREFERRED_PARENT_GRACE_MS else 0L

/**
 * Child side, during the grace after a re-anchor-eligible link loss: reconnection used to take the
 * first matching offer heard. On 2026-10-10 the A15s heard its source S24 (hops 0) 147 ms after the
 * ASUS relay re-anchored it, chose it over the absent ASUS, and waited 15 s on a source whose only
 * GATT slot the ASUS held ("BLE GATT source slots full; viewer discovery stopped"). So while the
 * grace runs and the preferred parent's offer is not among [candidates], nothing is chosen yet; once
 * it is present the usual ordering (with [preferredRecoveryRank]) decides. After the grace, or with
 * no preferred parent, every candidate is allowed.
 */
internal fun <T> recoveryCandidatesDuringGrace(
    candidates: List<T>,
    prefixOf: (T) -> ByteArray,
    preferredParent: ByteArray?,
    graceUntilMs: Long,
    nowMs: Long,
): List<T> {
    if (preferredParent == null || nowMs >= graceUntilMs) return candidates
    return if (candidates.any { prefixOf(it).contentEquals(preferredParent) }) candidates else emptyList()
}
