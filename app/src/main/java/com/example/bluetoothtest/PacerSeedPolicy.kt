package com.example.bluetoothtest

/**
 * One pacer seed policy and one per-child queue policy (switch audit 2026-10-27, B3/B12, proposal F4).
 *
 * The sender and the relay each held their own copy of the on-time seed hold/raise and read the pacing, seed, drain-guard,
 * lead-cap and time-queue switches at their own call sites. [PacerQueueConfig] is the one effective configuration (logged
 * once per launch as `BENCHMARK pacer_queue_config`), [PacerSeedPolicy.onTimeReport] the one seed rule both writers apply,
 * and [RelayChildQueuePolicy] what a relay's child queue and writer use for one child.
 *
 * Thresholds, in one place (they are not one number; each guards a different question):
 * - 250 ms [RELAY_PACE_LEAD_CAP_NS]: link time the relay may run ahead of a late child (Experimental lead cap).
 * - 400 ms [BoundedMediaAge.MIN_STREAM_QUEUE_MS] = [LinkRateController.CONGESTED_BACKLOG_MS]: the smallest stream shed budget
 *   and the backlog the rate controller treats as congestion; also [TimeBasedSizing.DEFAULT_FLOOR_MS].
 * - 500 ms [LinkPacer.SEED_RAISE_BACKLOG_MS]: the sender's own queue that marks the pace (not the link) as the limit, for the
 *   on-time raise and for a join-probe burst counted as link-bound.
 * - 600 ms (relay child thinning `behindMs`): a child queue that is behind.
 * A relay child whose shed budget sits at the 400 ms floor (a child reporting a large added playout delay) sheds before its
 * queue reaches 500 ms, so the on-time raise rarely fires there; that is deliberate (the shed, not a faster pace, answers a
 * late child), and the drain guard no longer reads a shed as draining ([LinkPacer.holdSeed], B3).
 */
internal data class PacerQueueConfig(
    /** `test_link_pacing` (default on). */
    val linkPacing: Boolean,
    /** `test_link_pacer_seed` (default on). */
    val seed: Boolean,
    /** `test_link_pacer_peer_seed` (Experimental, off). */
    val peerSeed: Boolean,
    /** `test_join_probe_seed` (Experimental, off). */
    val joinProbeSeed: Boolean,
    /** `test_on_time_reports` (default on). */
    val onTimeReports: Boolean,
    /** `test_on_time_seed_raise` (default on). */
    val onTimeSeedRaise: Boolean,
    /** `test_seed_raise_drain_guard` (Experimental, off). */
    val drainGuard: Boolean,
    /** `test_relay_pace_head` (default on). */
    val relayPaceHeadOnly: Boolean,
    /** `test_relay_pace_lead_cap` (Experimental, off). */
    val relayPaceLeadCap: Boolean,
    /** Relay-child media age or the global bounded media age: the relay child queue sheds. */
    val relayChildShed: Boolean,
    /** `test_relay_time_queue` (Experimental, off). */
    val relayTimeQueue: Boolean,
    /** `test_relay_drain_sustained` (Experimental, off). */
    val relayDrainSustained: Boolean,
    /** `test_relay_child_thinning` (Experimental, off). */
    val relayChildThinning: Boolean,
    /** `test_playout_time_target` (Experimental, off). */
    val playoutTimeTarget: Boolean,
    /** Relay queue latency budget (`test_time_queue_budget_ms`). */
    val relayQueueBudgetMs: Long,
    /** Viewer playout budget (`test_playout_time_budget_ms`, else the relay's). */
    val playoutBudgetMs: Long,
    /** `test_time_queue_floor_ms`. */
    val queueFloorMs: Long,
) {
    /** The seed is applied at all (pacing and the seed both on). */
    val seeded: Boolean get() = linkPacing && seed

    /** The drain guard can act: it judges only the on-time raise. */
    val drainGuardEffective: Boolean get() = seeded && onTimeReports && onTimeSeedRaise && drainGuard

    fun line(): String =
        "BENCHMARK pacer_queue_config: linkPacing=$linkPacing | seed=$seed | peerSeed=$peerSeed | joinProbeSeed=$joinProbeSeed | " +
            "onTimeReports=$onTimeReports | onTimeSeedRaise=$onTimeSeedRaise | drainGuard=$drainGuard(effective=$drainGuardEffective) | " +
            "relayPaceHeadOnly=$relayPaceHeadOnly | relayPaceLeadCap=$relayPaceLeadCap | relayChildShed=$relayChildShed | " +
            "relayTimeQueue=$relayTimeQueue | relayDrainSustained=$relayDrainSustained | relayChildThinning=$relayChildThinning | " +
            "playoutTimeTarget=$playoutTimeTarget | relayQueueBudgetMs=$relayQueueBudgetMs | playoutBudgetMs=$playoutBudgetMs | " +
            "queueFloorMs=$queueFloorMs | seedRaiseBacklogMs=${LinkPacer.SEED_RAISE_BACKLOG_MS} | " +
            "shedFloorMs=${BoundedMediaAge.MIN_STREAM_QUEUE_MS} | leadCapMs=${RELAY_PACE_LEAD_CAP_NS / 1_000_000L}"

    companion object {
        fun current(): PacerQueueConfig = PacerQueueConfig(
            linkPacing = DebugTunables.linkPacing,
            seed = DebugTunables.linkPacerSeed,
            peerSeed = DebugTunables.experimentalPeerLinkSeed,
            joinProbeSeed = DebugTunables.joinProbeSeed,
            onTimeReports = DebugTunables.onTimeReports,
            onTimeSeedRaise = DebugTunables.onTimeSeedRaise,
            drainGuard = DebugTunables.seedRaiseDrainGuard,
            relayPaceHeadOnly = DebugTunables.relayPaceHeadOnly,
            relayPaceLeadCap = DebugTunables.relayPaceLeadCap,
            relayChildShed = BoundedMediaAge.relayChildEnabled(),
            relayTimeQueue = DebugTunables.relayTimeQueue,
            relayDrainSustained = DebugTunables.relayDrainSustained,
            relayChildThinning = DebugTunables.relayChildThinning,
            playoutTimeTarget = DebugTunables.playoutTimeTarget,
            relayQueueBudgetMs = TimeBasedSizing.relayQueueBudgetMs(),
            playoutBudgetMs = TimeBasedSizing.playoutBudgetMs(),
            queueFloorMs = DebugTunables.timeQueueFloorMs,
        )
    }
}

/** The one on-time seed rule of a stream writer (direct sender or relay child writer). */
internal object PacerSeedPolicy {
    /** What one on-time report did to the seed, for the writer's log lines. */
    data class OnTimeHold(val fromKbps: Int, val toKbps: Int, val backlogMs: Long, val guardBlock: DrainGuardBlock?) {
        val raised: Boolean get() = toKbps > fromKbps

        fun raiseLine(role: String, peer: String?): String =
            "BENCHMARK pacer_seed_raise: role=$role | " + (peer?.let { "peer=$it | " } ?: "") +
                "fromKbps=$fromKbps | toKbps=$toKbps | backlogMs=$backlogMs"
    }

    /**
     * An unmeasured child reported media on time: hold the seeded pace and, with the on-time raise, raise it while the
     * writer's own queue ([backlogMs], read only when the raise is on) shows the pace is the limit. [droppedMedia] is the
     * queue's running drop count, read only for the drain guard (B3). Null when the pacer has nothing to hold.
     */
    fun onTimeReport(
        pacer: LinkPacer,
        config: PacerQueueConfig,
        backlogMs: () -> Long,
        droppedMedia: () -> Long,
    ): OnTimeHold? {
        val before = pacer.seedKbps
        val backlog = if (config.onTimeSeedRaise) backlogMs() else -1L
        val dropped = if (config.drainGuard) droppedMedia() else -1L
        val held = pacer.holdSeed(backlog, config.drainGuard, dropped)
        val block = pacer.lastDrainGuardBlock
        if (!held && block == null) return null
        return OnTimeHold(before, if (held) pacer.seedKbps else before, backlog, block)
    }
}

/** What a relay's child queue and child writer use for one child; fixed when the child connects. */
internal data class RelayChildQueuePolicy(
    val paceHeadOnly: Boolean,
    val leadCap: Boolean,
    /** Time-based shed target (null: the fixed budget, the control arm). Video streams only. */
    val timeQueue: RelayTimeQueueConfig?,
    val drainSustained: Boolean,
) {
    /** The lead cap's in-flight limit for the child's latest lateness report ([LinkPacer.setLeadCapNs]). */
    fun leadCapNs(childLate: Boolean): Long = relayLeadCapNs(leadCap, childLate)

    companion object {
        fun of(config: PacerQueueConfig, audioOnly: Boolean): RelayChildQueuePolicy = RelayChildQueuePolicy(
            paceHeadOnly = config.relayPaceHeadOnly,
            leadCap = config.relayPaceLeadCap,
            timeQueue = if (config.relayTimeQueue && !audioOnly) {
                RelayTimeQueueConfig(latencyBudgetMs = config.relayQueueBudgetMs, floorMs = config.queueFloorMs)
            } else {
                null
            },
            drainSustained = config.relayDrainSustained,
        )
    }
}
