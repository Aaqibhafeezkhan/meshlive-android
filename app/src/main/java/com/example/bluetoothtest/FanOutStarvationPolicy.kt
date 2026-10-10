package com.example.bluetoothtest

/**
 * Detects a fan-out child the source cannot serve. In the M31 GATT fan-out (two 300 kbps viewers)
 * the S24 child played 14.3-14.9 FPS while the ASUS child got 0.9-4.7 FPS for the whole session:
 * its link cycled through large-frame overflow and recovery-keyframe waits. Strict per-second
 * starvation never held for three consecutive windows, and an earlier strict rule withdrew a viewer
 * that was only waiting for one keyframe after a single burst.
 *
 * Rule: a child that received less than half of what it was offered, or whose queue was at least
 * [POOR_BACKLOG_MS] behind live, in [POOR_REQUIRED] of the last [HORIZON_WINDOWS] one-second
 * windows, while a sibling was healthy within 2 s, is released to find another route, and the source
 * learns the aggregate it actually carried as its fan-out ceiling. Nothing is dropped any more, so a
 * starved child shows as a backlog rather than a delivery ratio: the M31 GATT fan-out ASUS child sat
 * 2.0-3.7 s behind (7 FPS, 9 freezes) at the rate floor while the S24 child's queue was empty
 * (kfshape-a99f1ef7).
 *
 * Early release (mesh4-57484598): the 24-of-30 s backlog rule held every viewer at about 100 kbps for
 * about 20 s before releasing the A15s. When the encoder is already at its rate floor (rate control
 * has nothing left to cut), the child's backlog has kept growing for [EARLY_WINDOWS] consecutive
 * windows and a sibling is healthy, waiting longer only starves the sibling too. The protections of
 * the slow rule stay: a window with a keyframe or large-frame drain ([LinkRateSample.largeFrameGrace])
 * restarts the streak, a draining backlog restarts it, and it needs growth, not just a high backlog.
 */
internal class FanOutStarvationPolicy(
    private val horizonWindows: Int = HORIZON_WINDOWS,
    private val poorRequired: Int = POOR_REQUIRED,
    private val earlyRelease: () -> Boolean = { DebugTunables.fanOutEarlyRelease },
) {
    data class Withdrawal(val child: Any, val carriedAggregateKbps: Int)

    private class ChildState(val firstSeenNs: Long) {
        var lastHealthyNs = Long.MIN_VALUE
        var lastSampleNs = 0L
        val poor = ArrayDeque<Boolean>()
        val behind = ArrayDeque<Boolean>()
        val carriedKbps = ArrayDeque<Int>()
        /** Backlog of consecutive windows that were all growing behind at the rate floor (early release). */
        val floorBacklogs = ArrayDeque<Int>()

        fun averageCarriedKbps(): Int = if (carriedKbps.isEmpty()) 0 else carriedKbps.sum() / carriedKbps.size
    }

    private val children = mutableMapOf<Any, ChildState>()

    @Synchronized
    fun onSample(child: Any, sample: LinkRateSample, nowNs: Long, encoderAtFloor: Boolean = false): Withdrawal? {
        val state = children.getOrPut(child) { ChildState(nowNs) }
        state.lastSampleNs = nowNs
        state.carriedKbps.addLast(
            if (sample.windowNs > 0) (sample.sentBytes * 8 * 1_000_000L / sample.windowNs).toInt() else 0,
        )
        state.poor.addLast(sample.offeredBytes > 0 && sample.sentBytes * 2 < sample.offeredBytes)
        state.behind.addLast(sample.backlogAgeMs >= POOR_BACKLOG_MS)
        while (state.carriedKbps.size > horizonWindows) state.carriedKbps.removeFirst()
        while (state.poor.size > horizonWindows) state.poor.removeFirst()
        while (state.behind.size > BEHIND_HORIZON_WINDOWS) state.behind.removeFirst()
        val healthy = !sample.childLate && sample.backlogAgeMs < LinkRateController.CONGESTED_BACKLOG_MS &&
            sample.sentBytes * 10 >= sample.offeredBytes * 8
        if (healthy) state.lastHealthyNs = nowNs
        trackFloorBacklog(state, sample, encoderAtFloor)
        children.values.removeAll { nowNs - it.lastSampleNs > STALE_NS }
        if (!starved(state) && !growingAtFloor(state)) return null
        val anotherHealthy = children.any { (key, other) ->
            key != child && other.lastHealthyNs != Long.MIN_VALUE && nowNs - other.lastHealthyNs <= HEALTHY_RECENT_NS
        }
        val aggregate = children.values.sumOf { it.averageCarriedKbps() }
        if (!anotherHealthy) {
            // Shared overload: every child is starved together, so the radio cannot carry this many
            // links. Undo the newest admission (it reroutes) instead of degrading everyone; the
            // carried aggregate becomes the fan-out ceiling. Nothing is dropped either way.
            val allPoor = children.size >= 2 && children.values.all(::starved)
            if (!allPoor) return null
            val newest = children.maxBy { it.value.firstSeenNs }.key
            children.remove(newest)
            return Withdrawal(newest, aggregate)
        }
        children.remove(child)
        return Withdrawal(child, aggregate)
    }

    /**
     * Starved: less than half delivered in [poorRequired] of [horizonWindows] windows, or at least
     * [POOR_BACKLOG_MS] behind in [BEHIND_REQUIRED] of [BEHIND_HORIZON_WINDOWS]. Being behind gets the
     * longer horizon because rate control and the encoder trim (15-20 s of averages) correct an
     * overshooting encoder first; releasing after 10 s left the M31 fan-out's ASUS child with no
     * route at all (callrate-454cf485), worse than a slow picture.
     */
    private fun starved(state: ChildState): Boolean =
        state.poor.size >= horizonWindows && state.poor.count { it } >= poorRequired ||
            state.behind.size >= BEHIND_HORIZON_WINDOWS && state.behind.count { it } >= BEHIND_REQUIRED

    /**
     * Extends or restarts the streak of windows where this child was behind with the encoder at its
     * floor. Anything that makes a high backlog expected (a keyframe/large-frame drain), a backlog
     * that fell clearly, or an encoder above its floor ends the streak.
     */
    private fun trackFloorBacklog(state: ChildState, sample: LinkRateSample, encoderAtFloor: Boolean) {
        val queue = state.floorBacklogs
        val behind = encoderAtFloor && !sample.largeFrameGrace && sample.backlogAgeMs >= EARLY_BACKLOG_MS &&
            sample.sentBytes < sample.offeredBytes
        val draining = queue.isNotEmpty() && sample.backlogAgeMs + EARLY_DRAIN_TOLERANCE_MS < queue.last()
        if (!behind || draining) queue.clear()
        if (behind) queue.addLast(sample.backlogAgeMs)
        while (queue.size > EARLY_WINDOWS) queue.removeFirst()
    }

    /** The backlog grew by at least [EARLY_GROWTH_MS] over [EARLY_WINDOWS] windows, all at the floor. */
    private fun growingAtFloor(state: ChildState): Boolean =
        earlyRelease() && state.floorBacklogs.size >= EARLY_WINDOWS &&
            state.floorBacklogs.last() >= state.floorBacklogs.first() + EARLY_GROWTH_MS

    @Synchronized
    fun remove(child: Any) {
        children.remove(child)
    }

    companion object {
        const val HORIZON_WINDOWS = 10
        const val POOR_REQUIRED = 8
        /** Beyond the Automatic playout maximum (2 s): the child shows stalls. */
        const val POOR_BACKLOG_MS = 2_000
        const val BEHIND_HORIZON_WINDOWS = 30
        const val BEHIND_REQUIRED = 24
        /** Early release (encoder at its floor): 10 s behind and growing, instead of 24 of 30 s. */
        const val EARLY_WINDOWS = 10
        const val EARLY_BACKLOG_MS = 1_500
        const val EARLY_GROWTH_MS = 500
        const val EARLY_DRAIN_TOLERANCE_MS = 300
        const val HEALTHY_RECENT_NS = 2_000_000_000L
        const val STALE_NS = 5_000_000_000L
    }
}
