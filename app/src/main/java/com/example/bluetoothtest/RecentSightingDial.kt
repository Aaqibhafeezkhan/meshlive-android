package com.example.bluetoothtest

/**
 * Experimental (default off, DEBUG `test_dial_recent_sighting`): a call to a phone heard very recently dials its last
 * sighting at once instead of waiting for the next one.
 *
 * Today every dial first waits for a sighting newer than the tap (`call_invite: refreshing_address`, then `peer_seen`),
 * so the address is fresh. Measured over the saved runs (2026-10-26, 2238 dials): that wait is median 160 ms, p90
 * 591 ms, and it sits in front of everything else in tap-to-ring. A sighting under [MAX_AGE_MS] old is as fresh as the
 * one the wait would bring, except across a rotating-ID boundary, where the old address stops advertising: no fast
 * path within [BOUNDARY_MARGIN_MS] of a local epoch boundary (both phones' clocks can differ by seconds).
 *
 * No wire change and nothing new on air: the callee sees the same connection to the same advert. A fast dial whose
 * connection fails takes the existing call failure and rediscovery path. Off: the wait as before.
 */
internal object RecentSightingDial {
    /** Oldest sighting that may be dialled at once. */
    const val MAX_AGE_MS = 1_500L

    /** No fast path this close to a rotating-ID epoch boundary (either side). */
    const val BOUNDARY_MARGIN_MS = 10_000L

    enum class Decision { DIAL_NOW, WAIT_FOR_SIGHTING }

    /**
     * [lastSeenAtMs]: elapsed-realtime of the newest sighting of this row (null: none). [nowMs]: elapsed realtime.
     * [wallMs] and [epochSeconds]: the local discovery wall clock and epoch length; [rotating]: the row is a rotating ID.
     */
    fun decide(
        enabled: Boolean,
        lastSeenAtMs: Long?,
        nowMs: Long,
        rotating: Boolean,
        wallMs: Long,
        epochSeconds: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        boundaryMarginMs: Long = BOUNDARY_MARGIN_MS,
    ): Decision {
        if (!enabled || lastSeenAtMs == null) return Decision.WAIT_FOR_SIGHTING
        val ageMs = nowMs - lastSeenAtMs
        if (ageMs < 0L || ageMs > maxAgeMs) return Decision.WAIT_FOR_SIGHTING
        if (rotating && nearEpochBoundary(wallMs, epochSeconds, boundaryMarginMs)) return Decision.WAIT_FOR_SIGHTING
        return Decision.DIAL_NOW
    }

    /** Whether [wallMs] lies within [marginMs] of a multiple of the epoch length (an epoch boundary). */
    fun nearEpochBoundary(wallMs: Long, epochSeconds: Long, marginMs: Long): Boolean {
        if (epochSeconds <= 0L) return true
        val epochMs = epochSeconds * 1_000L
        val intoEpoch = Math.floorMod(wallMs, epochMs)
        return intoEpoch < marginMs || epochMs - intoEpoch <= marginMs
    }
}
