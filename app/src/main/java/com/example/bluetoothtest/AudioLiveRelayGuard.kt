package com.example.bluetoothtest

/**
 * A relay must not pay for its child with its own parent (Design AL, T-AL3 chain ASUS -> A15s -> M31, build
 * 99f28d1f). On a GATT relay both links sit at 15 ms (the equal-interval stage of DECISIONS "Relay dual-role
 * scheduling"), and the A15s's inbound fell from about 19 to 7-8 kbps from the moment the child link was up, for
 * as long as it stayed up: not a join transient, so no grace window at the parent can absorb it. The parent shed
 * twice and withdrew its child (the relay) 10 s later; the relay needed 26 s to find a route again, and the same
 * thing happened the next time the child joined.
 *
 * Hop-local remedy, the audio counterpart of the video [ReceiverNodeHealthPolicy]: the relay measures its own
 * inbound payload rate, remembers what it was before the child joined, and when it stays below
 * [DEGRADED_RATIO] of that for [WINDOW_MS] (after [GRACE_MS] for the child's setup) it withdraws the child
 * (the existing LOCAL_CAPACITY route withdrawal; the child reroutes, usually straight to the source) and stops
 * relaying on that transport for a cooldown ([AudioLiveRelayMemory]). The parent never sees a stall long
 * enough to withdraw. Pure: bytes and clock are passed in.
 */
class AudioLiveRelayInboundGuard(
    /** Opus payload rate of the preset in kbps; the baseline when too little was seen before the child joined. */
    private val nominalKbps: Double,
) {
    enum class Verdict { OK, DROP_CHILD }

    // Whole seconds (clock / 1000) -> payload bytes received in that second.
    private val seconds = java.util.TreeMap<Long, Long>()
    private var childJoinedMs: Long? = null
    private var baselineKbpsAtJoin = 0.0
    private var degradedSinceMs: Long? = null
    private var paused = false

    /** Rate seen by the last [verdict], for the BENCHMARK line. */
    @Volatile var lastRecentKbps = 0.0
        private set

    val baselineKbps: Double get() = baselineKbpsAtJoin

    @Synchronized
    fun onBytes(bytes: Int, nowMs: Long) {
        seconds.merge(nowMs / 1_000L, bytes.toLong(), Long::plus)
        while (seconds.size > KEEP_SECONDS) seconds.pollFirstEntry()
    }

    /** The stream was paused or resumed: silence is expected, so nothing is judged across it. */
    @Synchronized
    fun onPausedChanged(isPaused: Boolean, nowMs: Long) {
        paused = isPaused
        degradedSinceMs = null
        // After a resume the grace starts again and the pre-pause seconds are not a baseline.
        if (!isPaused && childJoinedMs != null) childJoinedMs = nowMs
    }

    @Synchronized
    fun onChildCount(count: Int, nowMs: Long) {
        if (count > 0 && childJoinedMs == null) {
            childJoinedMs = nowMs
            degradedSinceMs = null
            baselineKbpsAtJoin = measuredBaselineKbps(nowMs)
        } else if (count == 0) {
            childJoinedMs = null
            degradedSinceMs = null
        }
    }

    /** True once a child has been carried for [HEALTHY_MS] without the inbound rate ever falling away. */
    @Synchronized
    fun childCarriedHealthily(nowMs: Long): Boolean =
        childJoinedMs?.let { nowMs - it >= HEALTHY_MS } == true && degradedSinceMs == null

    @Synchronized
    fun verdict(nowMs: Long): Verdict {
        val joined = childJoinedMs ?: return Verdict.OK
        if (paused || nowMs - joined < GRACE_MS + WINDOW_MS) return Verdict.OK
        val recent = recentKbps(nowMs)
        lastRecentKbps = recent
        if (recent >= baselineKbpsAtJoin * DEGRADED_RATIO) {
            degradedSinceMs = null
            return Verdict.OK
        }
        // The first low window is a candidate; it must persist for another [CONFIRM_MS], so one late burst is not read as a stall.
        val since = degradedSinceMs ?: nowMs.also { degradedSinceMs = it }
        return if (nowMs - since >= CONFIRM_MS) Verdict.DROP_CHILD else Verdict.OK
    }

    /** Mean payload rate over the [WINDOW_MS] before [nowMs] (complete seconds only: the running second is left out). */
    private fun recentKbps(nowMs: Long): Double {
        val end = nowMs / 1_000L
        val start = end - WINDOW_MS / 1_000L
        return seconds.subMap(start, end).values.sum() * 8.0 / WINDOW_MS
    }

    private fun measuredBaselineKbps(joinMs: Long): Double {
        val end = joinMs / 1_000L
        val covered = seconds.subMap(end - BASELINE_SECONDS, end)
        // Fewer than half the seconds with data (a fresh relay): the preset's rate, discounted, is the yardstick.
        if (covered.size < BASELINE_SECONDS / 2) return nominalKbps * NOMINAL_DISCOUNT
        return covered.values.sum() * 8.0 / (BASELINE_SECONDS * 1_000.0)
    }

    companion object {
        /** The child's own setup (connect, MTU, service discovery, PHY) legitimately costs airtime for about a second. */
        const val GRACE_MS = 1_000L
        const val WINDOW_MS = 3_000L
        const val CONFIRM_MS = 500L
        const val DEGRADED_RATIO = 0.65
        const val BASELINE_SECONDS = 8
        const val NOMINAL_DISCOUNT = 0.75
        const val HEALTHY_MS = 60_000L
        private const val KEEP_SECONDS = 40
    }
}

/**
 * What a relay learned about carrying a child on a transport (in memory, per process, hop-local): after
 * [AudioLiveRelayInboundGuard] dropped a child, this phone does not relay audio on that transport for a
 * cooldown that doubles with every repeat ([BASE_MS] up to [MAX_MS]); a child carried healthily resets it.
 */
object AudioLiveRelayMemory {
    const val BASE_MS = 120_000L
    const val MAX_MS = 960_000L
    private val blockedUntilMs = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()

    /** Milliseconds this transport stays closed for relaying, 0 when open. */
    @Synchronized
    fun blockedForMs(key: String, nowMs: Long): Long = ((blockedUntilMs[key] ?: 0L) - nowMs).coerceAtLeast(0L)

    /** Records a drop; returns the cooldown now in force. */
    @Synchronized
    fun onDropped(key: String, nowMs: Long): Long {
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        val cooldown = minOf(MAX_MS, BASE_MS shl (count - 1).coerceAtMost(10))
        blockedUntilMs[key] = nowMs + cooldown
        return cooldown
    }

    @Synchronized
    fun onHealthy(key: String) {
        failures.remove(key)
    }

    @Synchronized
    internal fun clearForTest() {
        blockedUntilMs.clear()
        failures.clear()
    }
}

/** Lets a repeating BENCHMARK line through when its content changed or [minIntervalMs] passed since the last one. */
class AudioLiveLogLimiter(private val minIntervalMs: Long = 30_000L) {
    private var lastKey: String? = null
    private var lastMs = 0L

    @Synchronized
    fun shouldLog(key: String, nowMs: Long): Boolean {
        if (key == lastKey && nowMs - lastMs < minIntervalMs) return false
        lastKey = key
        lastMs = nowMs
        return true
    }
}
