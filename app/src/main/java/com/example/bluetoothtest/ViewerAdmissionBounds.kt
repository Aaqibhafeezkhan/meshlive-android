package com.example.bluetoothtest

/*
 * Design QV review 1, R3 (2026-10-20): a source's viewer-request admission must survive a crowd or a flood of fake
 * requests. Each version 5 request is keyed by its announced ID and advertising address, so fresh RDI/address pairs
 * were always new keys: they skipped every per-key cooldown, took pre-authentication fan-out slots until they failed
 * or timed out (12 s), and the per-key rejection maps only lost an entry when the same key was looked up again, so a
 * stream of fresh keys grew them without limit.
 */

/**
 * A map whose entries expire, with a hard size cap. A write at the cap first sweeps expired entries, then evicts the
 * entry that would expire soonest; reads drop an expired entry. Not thread-safe by itself: owners synchronize.
 */
internal class BoundedExpiringMap<V>(private val maxEntries: Int) {
    private class Entry<V>(val deadlineNs: Long, val value: V)

    private val entries = LinkedHashMap<String, Entry<V>>()

    val size: Int get() = entries.size

    fun put(key: String, value: V, deadlineNs: Long, nowNs: Long) {
        entries.remove(key)
        if (entries.size >= maxEntries) sweep(nowNs)
        while (entries.size >= maxEntries) {
            val soonest = entries.entries.minByOrNull { it.value.deadlineNs }?.key ?: break
            entries.remove(soonest)
        }
        entries[key] = Entry(deadlineNs, value)
    }

    /** The value while its deadline has not passed (an expired entry is removed on the way). */
    fun get(key: String, nowNs: Long): V? {
        val entry = entries[key] ?: return null
        if (nowNs >= entry.deadlineNs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun deadline(key: String): Long? = entries[key]?.deadlineNs

    /** The value whether or not its deadline passed (callers that decide expiry themselves). */
    fun peek(key: String): V? = entries[key]?.value

    fun remove(key: String) {
        entries.remove(key)
    }

    fun sweep(nowNs: Long) {
        entries.entries.removeAll { nowNs >= it.value.deadlineNs }
    }

    fun clear() = entries.clear()
}

/**
 * Pre-authentication admission of viewer requests (default on; DEBUG control arm `test_viewer_admission_bounds=false`).
 *
 * - At most [attemptsPerWindow] new pre-authentication connection attempts start per [WINDOW_NS], whatever the number of
 *   free fan-out slots; requests over that are deferred (asked again on the next scan), not refused with the 30 s
 *   capacity cooldown. A recovery request (its previous parent named) is not counted: those are already limited to one
 *   probe per round.
 * - Requests are tried oldest first by when this source first saw their key. A real viewer keeps requesting with the
 *   same key for minutes; fake requests with fresh keys drop out of the caller's list within its freshness window, so
 *   the real viewer is soon the oldest key in the list.
 * - A key whose attempt failed before authentication sorts after untried keys for [FAILURE_PENALTY_NS], but is never
 *   excluded: a real viewer whose first connect failed (0x3E) is tried again.
 * - Both tables are capped ([MAX_TRACKED]) and expire ([SEEN_TTL_NS], [FAILURE_PENALTY_NS]).
 */
internal class PreAuthAdmissionGate(private val enabled: () -> Boolean = { DebugTunables.viewerAdmissionBounds }) {
    class Selection<P>(val chosen: List<P>, val deferred: List<P>)

    private val firstSeen = BoundedExpiringMap<Long>(MAX_TRACKED)
    private val failures = BoundedExpiringMap<Unit>(MAX_TRACKED)
    private val attempts = ArrayDeque<Long>()

    val trackedKeys: Int @Synchronized get() = firstSeen.size + failures.size

    /** How many new pre-authentication attempts may start now. */
    @Synchronized
    fun allowance(nowNs: Long, fanOut: Int): Int {
        while (attempts.isNotEmpty() && nowNs - attempts.first() >= WINDOW_NS) attempts.removeFirst()
        return (attemptsPerWindow(fanOut) - attempts.size).coerceAtLeast(0)
    }

    /**
     * Orders [candidates] (recovery requests first, then oldest key first, recently failed keys last) and keeps
     * recovery requests plus at most [allowance] others; the rest are deferred. Off: everything is chosen, in order.
     */
    @Synchronized
    fun <P> select(
        candidates: List<P>,
        keyOf: (P) -> String,
        isRecovery: (P) -> Boolean,
        fanOut: Int,
        nowNs: Long,
    ): Selection<P> {
        if (!enabled()) return Selection(candidates, emptyList())
        candidates.forEach { candidate ->
            val key = keyOf(candidate)
            val since = firstSeen.peek(key) ?: nowNs
            firstSeen.put(key, since, nowNs + SEEN_TTL_NS, nowNs)
        }
        failures.sweep(nowNs)
        val ordered = candidates.sortedWith(
            compareBy<P> { if (isRecovery(it)) 0 else 1 }
                .thenBy { if (failures.get(keyOf(it), nowNs) != null) 1 else 0 }
                .thenBy { firstSeen.peek(keyOf(it)) ?: nowNs },
        )
        var left = allowance(nowNs, fanOut)
        val chosen = mutableListOf<P>()
        val deferred = mutableListOf<P>()
        ordered.forEach { candidate ->
            when {
                isRecovery(candidate) -> chosen += candidate
                left > 0 -> {
                    chosen += candidate
                    left--
                }
                else -> deferred += candidate
            }
        }
        return Selection(chosen, deferred)
    }

    /** A connection attempt to [key] started (not for recovery requests). */
    @Synchronized
    fun noteAttempt(key: String, nowNs: Long) {
        if (!enabled()) return
        attempts.addLast(nowNs)
    }

    /** The attempt to [key] ended before the link authenticated. */
    @Synchronized
    fun onPreAuthFailure(key: String, nowNs: Long) {
        if (!enabled()) return
        failures.put(key, Unit, nowNs + FAILURE_PENALTY_NS, nowNs)
    }

    @Synchronized
    fun clear() {
        firstSeen.clear()
        failures.clear()
        attempts.clear()
    }

    companion object {
        const val WINDOW_NS = 10_000_000_000L
        const val MIN_ATTEMPTS_PER_WINDOW = 4
        const val SEEN_TTL_NS = 30_000_000_000L
        const val FAILURE_PENALTY_NS = 10_000_000_000L
        const val MAX_TRACKED = 512

        /** A source admitting a whole group at once still dials every free slot in one window. */
        fun attemptsPerWindow(fanOut: Int): Int = maxOf(MIN_ATTEMPTS_PER_WINDOW, fanOut)
    }
}
