package com.example.bluetoothtest

/**
 * Decides when a relay child's link cannot carry the stream. Nothing is dropped for a slow child
 * (user direction 2026-10-07), so the evidence is how far behind live its queue falls. A burst or
 * scene cut is absorbed by latency; a link that stays behind by more than a viewer's playout buffer
 * can absorb is withdrawn so the child can reroute, because a relay may only handle its own
 * children and cannot slow the source (hop-local rule). The previous policy counted overflow drops.
 */
class RelayChildHealthPolicy(
    /**
     * Relay-child skip-to-keyframe is on for this child queue: stale video is shed at about the
     * 1.75-2 s stream budget, so "nothing dropped" no longer holds and the growth-trend rule (1.5 s)
     * would withdraw the child before the shed can act. mesh4-041da09f 0001/0004: a join keyframe
     * drained 1.5-1.6 s behind live and the child was withdrawn 4 s after joining, then rerouted to
     * the source (requested-parent FAIL). The sustained and hard rules still withdraw a child whose
     * link cannot carry what is never shed (audio, keyframes).
     */
    private val sheddingActive: Boolean = false,
) {
    private var behindSinceNs: Long? = null
    /** (time, backlog) samples; the first is the newest one at least [TREND_WINDOW_NS] old. */
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Called by the child writer after each write and by its stall timer (ReceiverStreamForwarder). */
    @Synchronized
    fun shouldWithdraw(backlogAgeMs: Long, nowNs: Long): Boolean {
        if (backlogAgeMs >= HARD_BACKLOG_MS) return true
        samples.addLast(nowNs to backlogAgeMs)
        while (samples.size >= 2 && nowNs - samples[1].first >= TREND_WINDOW_NS) samples.removeFirst()
        val (thenNs, thenBacklogMs) = samples.first()
        // Still falling behind well past a keyframe's drain: with nothing dropped, this link carries
        // less than the stream and can never catch up (chain S24->M31->ASUS: 1.5 -> 4 -> 5 s behind
        // over 15 s before the sustained rule fired, gradient-9c76a1cc).
        // Draining (below the window's peak) is a burst being absorbed, not a short link.
        if (!sheddingActive && nowNs - thenNs >= TREND_WINDOW_NS && backlogAgeMs >= TREND_MIN_BACKLOG_MS &&
            backlogAgeMs - thenBacklogMs >= TREND_GROWTH_MS &&
            backlogAgeMs >= samples.maxOf { it.second }
        ) {
            return true
        }
        if (backlogAgeMs < WITHDRAW_BACKLOG_MS) {
            behindSinceNs = null
            return false
        }
        val since = behindSinceNs ?: nowNs.also { behindSinceNs = it }
        return nowNs - since >= SUSTAINED_NS
    }

    companion object {
        /** Beyond the 2 s Automatic playout maximum: the child would show stalls. */
        const val WITHDRAW_BACKLOG_MS = 2_500L
        const val SUSTAINED_NS = 3_000_000_000L
        const val HARD_BACKLOG_MS = 6_000L
        /** A 55 KB join keyframe drains in ~2 s on a 250 kbps child link; growth beyond that is a short link. */
        const val TREND_WINDOW_NS = 3_000_000_000L
        const val TREND_MIN_BACKLOG_MS = 1_500L
        /** Carried less than ~83% of the stream over the window. */
        const val TREND_GROWTH_MS = 500L
    }
}
