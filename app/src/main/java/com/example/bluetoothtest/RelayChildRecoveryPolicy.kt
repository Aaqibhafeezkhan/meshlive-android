package com.example.bluetoothtest

/**
 * One relay-child recovery policy (switch audit 2026-10-27, B1/B2, proposal F3).
 *
 * Before, about ten switches each read [DebugTunables] at their own call site in ReceiverStreamForwarder, AndroidVideoReceiver
 * and MainActivity, and several of them acted on the same failed child link with different follow-ups. This file gathers
 * them into one effective configuration ([RelayRecoveryConfig], logged once per launch as `BENCHMARK relay_recovery_config`)
 * and one policy per side:
 * - relay side ([RelayChildRecoveryPolicy]): whether a sighting of a child may be dialled ([dialDecision]), what a failed
 *   attempt is (classified once, [onAttemptFailed]: the establishment tracker, the hold-offs and the PHY after the failure
 *   follow from that one classification), and the starved-link close ([RelayRecoveryConfig.starvationClose]);
 * - child side: the parent-link watch (silence and trickle close, [RelayRecoveryConfig.parentWatch] and
 *   [RelayRecoveryConfig.parentTrickle]) and the same-parent preference with its grace ([RelayRecoveryConfig.preferParent],
 *   [RelayRecoveryConfig.recoveryGrace]).
 *
 * Every existing switch keeps its meaning and its default; the defaults behave exactly as the scattered code did
 * (RelayChildRecoveryPolicyEquivalenceTest replays the earlier call-site logic against this policy for every switch
 * combination). Two things changed on purpose, both outside the defaults:
 * - `relay_child_reanchor` used to gate four behaviours. The child-side watch and the same-parent preference now have their own
 *   DEBUG switches (`test_relay_parent_watch`, `test_relay_prefer_parent`). The watch, absent, follows `relay_child_reanchor`,
 *   so `test_relay_child_reanchor=false` alone still turns it off, and `relay_parent_trickle` can now be A/B-tested with or
 *   without the relay-side close. The same-parent preference is off by default since build 4 (measured: middle phone killed
 *   and relaunched, 5/5 re-anchored without it vs 4/5 with it); `test_relay_prefer_parent=true` brings it and, with it,
 *   `relay_recovery_grace` back.
 * - The establishment budget and keep-2M both decided the PHY after a failure before any link existed. They now share one rule
 *   ([setupPhyAfterFailure]): such a failure keeps 2M, because establishment runs on the advertising PHY. The PHY the budget arm
 *   gets is unchanged (it never pinned 1M); it now also logs that decision as `link_phy ... reason=establishment_failed`.
 */
internal data class RelayRecoveryConfig(
    /** `test_relay_child_stall_guard` (default on): close a silent LE child link before the end-to-end handshake. */
    val stallGuard: Boolean,
    /** `test_relay_stall_separate` (default on): a guard stall counts apart from refusals ([EstablishmentFailureTracker.onStall]). */
    val stallSeparate: Boolean,
    /** `test_relay_stall_holdoff` (default on): no dial to a child within [RelayStallHoldoff.HOLDOFF_MS] of a stall close. */
    val stallHoldoff: Boolean,
    /** `test_relay_refusal_escalation` (default on): escalating refusal marks and a hold-off after each refusal round. */
    val refusalEscalation: Boolean,
    /** `test_relay_establish_fallback` (default on): the establishment tracker (marks, withdrawn offers, parent ranking). */
    val establishFallback: Boolean,
    /** `test_relay_establish_budget` (Experimental, off): in-place retries of fast establishment failures inside 12 s. */
    val establishBudget: Boolean,
    /** `test_relay_hold_in_place` (default on): a sighting the relay will not dial leaves the offer and scan running. */
    val holdInPlace: Boolean,
    /** `test_relay_establish_keep_2m` (Experimental, off): a failure before any link existed does not pin 1M. */
    val keep2M: Boolean,
    /** `test_phy_setup_stall` (default on): a 2M child setup that failed before hop authentication retries on 1M. */
    val phySetupStall: Boolean,
    /** `test_relay_child_reanchor` (default on), relay side: close a starved child link so it reconnects on a new anchor. */
    val starvationClose: Boolean,
    /** Child side: watch a relayed parent link and close it when silent (`test_relay_parent_watch`, follows `relay_child_reanchor`). */
    val parentWatch: Boolean,
    /** `test_relay_parent_trickle` (default on): inside the watch, also close a parent link that trickles under bitrate/8. */
    val parentTrickleSwitch: Boolean,
    /** Child side: after a link loss, prefer the same relay parent (`test_relay_prefer_parent`, off by default since build 4). */
    val preferParent: Boolean,
    /** `test_relay_recovery_grace` (default on): wait up to 2 s for the preferred parent's offer. */
    val recoveryGraceSwitch: Boolean,
    /** `test_relay_cold_start` (default on): admission, logged here because it decides whether a fresh relay takes a child. */
    val coldStart: Boolean,
    /** `test_relay_reanchor` (Experimental, off): the AdaptivePhy connection-update re-anchor (not the close above). */
    val reanchorUpdate: Boolean,
) {
    /** Whether any hold-off is read at all (the dial gate's `holdoffOn`). */
    val anyHoldoff: Boolean get() = stallHoldoff || refusalEscalation

    /**
     * Refusal marks escalate 30/60/120/300 s instead of a flat 5 minutes. Both the refusal escalation and the establishment
     * budget ask for it (the budget's description says so too), so the budget's flat-mark "control" exists only with refusal
     * escalation off (B1).
     */
    val escalatingRefusalMarks: Boolean get() = establishBudget || refusalEscalation

    /** The trickle close is judged only inside the parent-link watch. */
    val parentTrickle: Boolean get() = parentWatch && parentTrickleSwitch

    /** The grace waits for a preferred parent, which exists only with [preferParent]. */
    val recoveryGrace: Boolean get() = preferParent && recoveryGraceSwitch

    /** One line per launch: the raw switches and what is in effect. */
    fun line(): String =
        "BENCHMARK relay_recovery_config: stallGuard=$stallGuard | stallSeparate=$stallSeparate | stallHoldoff=$stallHoldoff | " +
            "refusalEscalation=$refusalEscalation | establishFallback=$establishFallback | establishBudget=$establishBudget | " +
            "holdInPlace=$holdInPlace | keep2M=$keep2M | phySetupStall=$phySetupStall | starvationClose=$starvationClose | " +
            "parentWatch=$parentWatch | parentTrickle=$parentTrickleSwitch(effective=$parentTrickle) | " +
            "preferParent=$preferParent | recoveryGrace=$recoveryGraceSwitch(effective=$recoveryGrace) | " +
            "coldStart=$coldStart | reanchorUpdate=$reanchorUpdate | anyHoldoff=$anyHoldoff | " +
            "escalatingRefusalMarks=$escalatingRefusalMarks"

    companion object {
        /** The shipped configuration: what [current] returns with every switch at its default. */
        val DEFAULTS = RelayRecoveryConfig(
            stallGuard = true,
            stallSeparate = true,
            stallHoldoff = true,
            refusalEscalation = true,
            establishFallback = true,
            establishBudget = false,
            holdInPlace = true,
            keep2M = false,
            phySetupStall = true,
            starvationClose = true,
            parentWatch = true,
            parentTrickleSwitch = true,
            preferParent = false,
            recoveryGraceSwitch = true,
            coldStart = true,
            reanchorUpdate = false,
        )

        /** The configuration in effect now (the switches are set at launch; read at every decision, as the call sites did). */
        fun current(): RelayRecoveryConfig = RelayRecoveryConfig(
            stallGuard = DebugTunables.relayChildStallGuard,
            stallSeparate = DebugTunables.relayStallSeparate,
            stallHoldoff = DebugTunables.relayStallHoldoff,
            refusalEscalation = DebugTunables.relayRefusalEscalation,
            establishFallback = DebugTunables.relayEstablishFallback,
            establishBudget = DebugTunables.relayEstablishBudget,
            holdInPlace = DebugTunables.relayHoldInPlace,
            keep2M = DebugTunables.relayEstablishKeep2M,
            phySetupStall = DebugTunables.phySetupStallGuard,
            starvationClose = DebugTunables.relayChildReanchor,
            parentWatch = DebugTunables.relayParentWatch ?: DebugTunables.relayChildReanchor,
            parentTrickleSwitch = DebugTunables.relayParentTrickle,
            preferParent = DebugTunables.relayPreferParent,
            recoveryGraceSwitch = DebugTunables.relayRecoveryGrace,
            coldStart = DebugTunables.relayColdStart,
            reanchorUpdate = DebugTunables.relayReanchor,
        )
    }
}

/**
 * The relay side of [RelayRecoveryConfig]: one dial decision for a child and one classification per failed attempt. The
 * stores are injected so the policy can be replayed in JVM tests; [live] uses the relay's process-wide ones.
 */
internal class RelayChildRecoveryPolicy(
    val config: RelayRecoveryConfig,
    private val tracker: EstablishmentFailureTracker = RelayChildEstablishment.tracker,
    private val stallHoldoff: RelayStallHoldoff = RelayChildStall.holdoff,
    private val refusalHoldoff: RelayStallHoldoff = RelayChildStall.refusalHoldoff,
) {
    /** Whether to dial [verdict], and the hold-off left (for the log line). */
    data class DialDecision(val verdict: RelayChildDialGate.Verdict, val heldMs: Long)

    /** Milliseconds left on [key]'s hold-offs whose switch is on (0 when none). */
    fun holdoffRemainingMs(key: String, nowMs: Long): Long = maxOf(
        if (config.stallHoldoff) stallHoldoff.remainingMs(key, nowMs) else 0L,
        if (config.refusalEscalation) refusalHoldoff.remainingMs(key, nowMs) else 0L,
    )

    /**
     * May the relay dial [key] now? A child marked by the establishment tracker is deferred first, then one inside a stall or
     * refusal hold-off. Used both in the discovery callback (hold in place) and at the start of the dial.
     */
    fun dialDecision(key: String, nowMs: Long): DialDecision {
        val heldMs = holdoffRemainingMs(key, nowMs)
        val verdict = RelayChildDialGate.verdict(
            fallbackOn = config.establishFallback,
            marked = config.establishFallback && tracker.isMarked(key, nowMs),
            holdoffOn = config.anyHoldoff,
            heldMs = heldMs,
        )
        return DialDecision(verdict, heldMs)
    }

    /** The stall guard closed [key]'s link (the hold-off is always written; it is read only while its switch is on). */
    fun onStallClosed(key: String, nowMs: Long) = stallHoldoff.onStall(key, nowMs)

    /** The child authenticated on the hop: its establishment record ends. */
    fun onHopAuthenticated(key: String) = tracker.onSuccess(key)

    /** The end-to-end handshake finished on a guarded link: both hold-offs end. */
    fun onHandshakeComplete(key: String) {
        stallHoldoff.onSuccess(key)
        refusalHoldoff.onSuccess(key)
    }

    /** What one failed attempt was and what it did. */
    data class AttemptOutcome(
        val kind: RelayChildFailureKind,
        /** The attempt reached the establishment tracker (fallback on and an establishment failure). */
        val counted: Boolean,
        /** This attempt newly marked the child (its offer is withdrawn for [markMs]). */
        val marked: Boolean,
        val markMs: Long,
        /** What the failure does to the child's PHY for the rest of the forwarding session. */
        val setupPhy: RelayChildSetupPhy,
    )

    /**
     * Classifies a failed attempt to [key] once and applies it: a guard stall or a refusal is recorded by the tracker (with the
     * refusal hold-off when refusal escalation is on), and the PHY after the failure follows [setupPhyAfterFailure].
     * [budgetExhausted] is an [EstablishmentBudgetExhausted] while the budget is on; [reachedConnection] says a link existed.
     */
    fun onAttemptFailed(
        key: String,
        nowMs: Long,
        hopAuthenticated: Boolean,
        message: String?,
        stalled: Boolean,
        budgetExhausted: Boolean,
        attemptPhy: BlePhyPreference?,
        reachedConnection: Boolean,
    ): AttemptOutcome {
        val kind = classifyRelayChildFailure(hopAuthenticated, message, stalled, config.stallSeparate)
        val counted = config.establishFallback && kind != RelayChildFailureKind.NONE
        var marked = false
        if (counted) {
            marked = if (kind == RelayChildFailureKind.STALL) {
                tracker.onStall(key, nowMs)
            } else {
                // A refusal round also holds the next dial to this child off (it is rediscovered every second while it
                // advertises in burst mode, and used to spend both failures in 3 s).
                if (config.refusalEscalation) refusalHoldoff.onStall(key, nowMs)
                tracker.onFailure(key, nowMs, config.escalatingRefusalMarks)
            }
        }
        val markMs = if (marked) tracker.remainingMs(key, nowMs) else 0L
        return AttemptOutcome(
            kind,
            counted,
            marked,
            markMs,
            setupPhyAfterFailure(config, attemptPhy, hopAuthenticated, budgetExhausted, reachedConnection),
        )
    }

    companion object {
        /** The policy with the configuration in effect now and the relay's process-wide stores. */
        fun live(): RelayChildRecoveryPolicy = RelayChildRecoveryPolicy(RelayRecoveryConfig.current())

        /**
         * One rule for the PHY after a failed child setup (B1). A failure before any link existed (an exhausted establishment
         * budget, or no connection with keep-2M on) keeps 2M: establishment runs on the advertising PHY, so 1M would not change
         * it. A failure after the link existed is the protected case and pins 1M ([relayChildSetupPhyDecision]). With both
         * Experimental switches off this is exactly the earlier rule.
         */
        fun setupPhyAfterFailure(
            config: RelayRecoveryConfig,
            attemptPhy: BlePhyPreference?,
            hopAuthenticated: Boolean,
            budgetExhausted: Boolean,
            reachedConnection: Boolean,
        ): RelayChildSetupPhy {
            val exhausted = config.establishBudget && budgetExhausted
            return relayChildSetupPhyDecision(
                config.phySetupStall,
                attemptPhy,
                hopAuthenticated,
                keepOnEstablishment = config.keep2M || exhausted,
                reachedConnection = reachedConnection && !exhausted,
            )
        }
    }
}
