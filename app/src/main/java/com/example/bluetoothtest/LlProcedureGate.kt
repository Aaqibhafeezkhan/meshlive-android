package com.example.bluetoothtest

import java.util.ArrayDeque
import java.util.EnumMap

/** The requests that start (or lock out) a link-layer procedure on one LE link, in the order the app makes them. */
internal enum class LlProcedure(val label: String, val changeKind: LinkChangeKind?) {
    /** discoverServices: the stack disables connection-parameter updates for the whole discovery (bta_gattc_act.cc). */
    SERVICE_DISCOVERY("discovery", null),

    /** requestMtu: the stack follows it with a Data Length Update (BTM_SetBleDataLength). */
    MTU("mtu", LinkChangeKind.MTU),

    /** setPreferredPhy: LL_PHY_REQ, an instant-based procedure. */
    PHY("phy", LinkChangeKind.PHY),

    /** requestConnectionPriority: LL_CONNECTION_UPDATE_IND or the L2CAP update, instant based. */
    CONNECTION_PARAMS("conn", LinkChangeKind.PRIORITY),
}

internal enum class GateAdmission {
    /** The gate is off: the request was started at once, as without a gate, and the stack accepted it. */
    IMMEDIATE_OK,

    /** The gate is off: the request was started at once and the stack refused it. */
    IMMEDIATE_FAILED,

    /** Waiting for its turn. */
    QUEUED,

    /** Not queued: the same procedure is stuck (its callback is missing), so a second one is not stacked on it. */
    DROPPED_STUCK,

    /** Not queued: the link is closed. */
    DROPPED_CLOSED,
}

internal enum class GateOutcome {
    COMPLETED,

    /** The stack reported a failure status for it. */
    FAILED_STATUS,

    /** The stack refused to queue it (start() returned false or threw). */
    NOT_QUEUED,

    /** No callback within the bound. The slot is released; the procedure is treated as stuck. */
    TIMED_OUT,

    /** Replaced by a newer request of the same kind before it started. */
    SUPERSEDED,

    /** Dropped because the same procedure got stuck before this one started. */
    DROPPED_STUCK,

    /** The link closed first. */
    CLOSED,
}

/**
 * Timing of [LlProcedureGate]. Every number is a starting point from the evidence in
 * [LlProcedureGate], not a measured optimum: the gate is Experimental until the phone A/B.
 */
internal data class LlGateTiming(
    val mtuTimeoutMs: Long = 3_000L,
    val phyTimeoutMs: Long = 2_500L,
    val connTimeoutMs: Long = 1_500L,
    val discoveryTimeoutMs: Long = 6_000L,
    /** After an MTU result the stack starts the Data Length Update (about 300-500 ms, bluetooth.md Part K). */
    val settleAfterMtuMs: Long = 400L,
    /** "Leave a fixed gap (seconds) between PHY and any connection-priority change" (controllers.md C-02). */
    val settleAfterPhyMs: Long = 1_500L,
    val settleAfterConnMs: Long = 750L,
    val settleAfterDiscoveryMs: Long = 200L,
    /** Nothing was started: only a short breath before the next request. */
    val settleAfterRefusalMs: Long = 100L,
    /** One retry after 0x23/0x2A, 0.5-1 s later (bluetooth.md Part K phy-update-collisions). */
    val retryDelayMs: Long = 750L,
    /** A peripheral sends no PHY request later than this after connecting even if the central never updated. */
    val peripheralPhyMaxWaitMs: Long = 4_000L,
    val peripheralIntervalsAfterUpdate: Int = 3,
    val peripheralMinHoldoffMs: Long = 100L,
    /** A stuck procedure blocks another of its kind for this long unless its late callback arrives. */
    val stuckTtlMs: Long = 10_000L,
)

/**
 * LlProcedureGate (BACKLOG P-01, fixed interface of section 8): one link-layer procedure in flight per LE link.
 *
 * Why. Android issues MTU/DLE, PHY and connection-update requests as independent HCI commands and does not
 * serialise them (bluetooth.md Part K phy-update-collisions-with-connection-update; AOSP serialises
 * connection updates only from Android 16, da2a7e6233). Core v5.4 Vol 6 Part B 5.3 forbids two
 * instant-based procedures at once; the loser is rejected with 0x23 (LL procedure collision) or 0x2A
 * (different transaction collision) and some controllers tear the link down: Galaxy S21 (Broadcom, Nordic
 * DevAcademy thread 436083 and 430910: the phone answers LL_PHY_REQ before the update instant), Galaxy S9+
 * (0x2A after the PHY update), Samsung S8 (Zephyr #13396, 2M before discovery fails with status 8), Lenovo
 * M10 and a Tizen phone (Zephyr #15971, PHY_UPDATE_IND instant 9 then CONN_UPDATE_REQ instant 12; Zephyr
 * 94d7669). The app saw "BLE PHY unavailable (status 42)" on M31, ASUS and A15s when both ends requested a
 * PHY (commit 2cbdafb1).
 *
 * What it does (only when [enabled]; otherwise [submit] starts the request at once and nothing changes):
 *  - one request in flight per link; the next waits for the matching callback (onMtuChanged,
 *    onServicesDiscovered, onPhyUpdate or an onPhyRead of the requested PHY, onConnectionUpdated);
 *  - a settle gap after each completion ([LlGateTiming]) so the DLE after an MTU, and the PHY instant, are over;
 *  - a missing callback is bounded: the slot is released after the timeout, MTU and PHY are then treated as
 *    STUCK and a second request of that kind is not stacked on them (a late callback clears it). A
 *    connection update that is never reported (stacks without the hidden callback) is not stuck;
 *  - a peripheral sends no PHY request before the central's first connection update plus 3 intervals and
 *    its service discovery are seen, at most [LlGateTiming.peripheralPhyMaxWaitMs] after connecting (C-20);
 *  - a PHY step answered with 0x23/0x2A is retried once after 750 ms, on 1M when a fallback was given;
 *  - a link lost with a fault status after a recent change is classified and reported to
 *    [LinkChangeRollbacks] (BACKLOG P-01 "stay on 1M for that peer", P-02).
 *
 * Connection-priority retries stay with [LeConnectionPriorityKeeper] (it retries after a reported failure);
 * its requests enter this gate, so the order is the gate's.
 *
 * Thread-safe; [schedule] and the callbacks may come from any thread. Request lambdas run outside the lock.
 *
 * Root hooks (BleL2capControl.kt, GattVideoTransport.kt; batch S wires the former): see the hand-back.
 */
internal class LlProcedureGate(
    private val link: String,
    private val role: LeLinkRole,
    private val nowMs: () -> Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val enabled: () -> Boolean = { PhySwitches.llGate },
    private val timing: LlGateTiming = LlGateTiming(),
    private val peerKey: () -> String? = { null },
    /** How long nothing was heard from the peer when the link dropped (null: unknown). */
    private val silentForMs: () -> Long? = { null },
    /** The supervision timeout the stack last reported for this link, ms (null: unknown). */
    private val supervisionMs: () -> Int? = { null },
    private val onLinkLost: (peer: String?, changes: List<RecordedChange>, lostAtMs: Long, status: Int) -> Unit =
        { peer, changes, lostAtMs, status ->
            LinkChangeRollbacks.onLinkLost(peer, changes, lostAtMs, status, System.currentTimeMillis())
        },
) {
    private class Step(
        val kind: LlProcedure,
        val label: String,
        var expectedTx: Int?,
        val fallback: (() -> Boolean)?,
        val timeoutMs: Long?,
        val onDone: ((GateOutcome) -> Unit)?,
        val start: () -> Boolean,
    ) {
        var token = 0L
        var retried = false
        var useFallback = false
        var queuedAtMs = 0L
    }

    private val lock = Any()
    private val queue = ArrayDeque<Step>()
    private var current: Step? = null
    private var tokenCounter = 0L
    private var startedAtMs = 0L
    private var freeAtMs = 0L
    private var wakeAtMs = 0L
    private var closed = false
    private val createdAtMs = nowMs()
    private val stuckUntilMs = EnumMap<LlProcedure, Long>(LlProcedure::class.java)
    private val changes = ArrayList<RecordedChange>()
    private var peerUpdateAtMs = -1L
    private var peerIntervalMs = 0.0
    private var peerDiscoveryDone = false
    private var peripheralWaitLogged = false

    /** Kinds whose callback is public and always delivered: a missing one means stuck. */
    private val stuckKinds = setOf(LlProcedure.MTU, LlProcedure.PHY)

    /**
     * Asks to run [start] (the GATT call; true when the stack queued it) as procedure [kind], when the link is free.
     * [expectedTx] is the PHY value a PHY request wants (an onPhyRead of it also completes the step);
     * [fallback] is the 1M request used for the one retry after 0x23/0x2A; [timeoutMs] overrides the default bound.
     */
    fun submit(
        kind: LlProcedure,
        label: String,
        expectedTx: Int? = null,
        fallback: (() -> Boolean)? = null,
        timeoutMs: Long? = null,
        onDone: ((GateOutcome) -> Unit)? = null,
        start: () -> Boolean,
    ): GateAdmission {
        if (!enabled()) {
            record(kind)
            val ok = runCatching(start).getOrDefault(false)
            return if (ok) GateAdmission.IMMEDIATE_OK else GateAdmission.IMMEDIATE_FAILED
        }
        val superseded = ArrayList<Step>()
        var admission = GateAdmission.QUEUED
        synchronized(lock) {
            val now = nowMs()
            when {
                closed -> admission = GateAdmission.DROPPED_CLOSED
                isStuck(kind, now) -> admission = GateAdmission.DROPPED_STUCK
                else -> {
                    if (kind == LlProcedure.CONNECTION_PARAMS) {
                        // A later priority request replaces an earlier one that has not started (the stack keeps one slot).
                        val it = queue.iterator()
                        while (it.hasNext()) {
                            val queued = it.next()
                            if (queued.kind == kind) {
                                it.remove()
                                superseded += queued
                            }
                        }
                    }
                    queue.addLast(Step(kind, label, expectedTx, fallback, timeoutMs, onDone, start).also { it.queuedAtMs = now })
                }
            }
        }
        if (admission == GateAdmission.QUEUED) {
            emit("queued", kind, label, "depth=${depth()}")
        } else {
            emit("refused", kind, label, "reason=${admission.name.lowercase()}")
        }
        superseded.forEach { finishNotStarted(it, GateOutcome.SUPERSEDED) }
        if (admission == GateAdmission.QUEUED) pump()
        return admission
    }

    /** onMtuChanged for this link (any status). */
    fun onMtuChanged(status: Int) = onCallback(LlProcedure.MTU, status)

    /** onServicesDiscovered for this link (any status). */
    fun onServicesDiscovered(status: Int) = onCallback(LlProcedure.SERVICE_DISCOVERY, status)

    /** onPhyUpdate; also fed by the central's onPhyRead through [onPhyRead] when it reports the requested PHY. */
    fun onPhyUpdate(tx: Int, status: Int) {
        onCallback(LlProcedure.PHY, status, tx)
    }

    /** onPhyRead: completes the current PHY step only when it shows the requested PHY (the read is a backstop for a missing onPhyUpdate). */
    fun onPhyRead(tx: Int, status: Int) {
        if (status != 0) return
        var matched = false
        synchronized(lock) {
            val step = current
            matched = step != null && step.kind == LlProcedure.PHY && step.expectedTx != null && step.expectedTx == tx
        }
        if (matched) onCallback(LlProcedure.PHY, 0, tx) else clearStuck(LlProcedure.PHY)
    }

    /**
     * The stack reported an LE connection update (the hidden onConnectionUpdated, either role). On the
     * peripheral side the first successful one with its interval arms the PHY holdoff.
     */
    fun onConnectionUpdated(status: Int, intervalUnits: Int) {
        if (status == 0 && intervalUnits > 0) {
            synchronized(lock) {
                if (peerUpdateAtMs < 0) {
                    peerUpdateAtMs = nowMs()
                    peerIntervalMs = intervalUnits * 1.25
                }
            }
        }
        onCallback(LlProcedure.CONNECTION_PARAMS, status)
        if (role == LeLinkRole.PERIPHERAL) pump()
    }

    /** Peripheral side: the central finished its service discovery (its first characteristic read, descriptor write or MTU request after it). */
    fun onPeerServicesDiscovered() {
        synchronized(lock) { peerDiscoveryDone = true }
        pump()
    }

    /** The link was lost with [status]; classifies the loss, tells the rollback memory, drops queued requests. */
    fun onLinkClosed(status: Int) {
        val dropped = ArrayList<Step>()
        val snapshot: List<RecordedChange>
        val lostAt = nowMs()
        synchronized(lock) {
            if (closed) return
            closed = true
            current?.let { dropped += it }
            current = null
            dropped += queue
            queue.clear()
            snapshot = changes.toList()
        }
        val ageMs = lostAt - createdAtMs
        val cls = classifyLinkDrop(
            status, ageMs, snapshot, lostAt,
            silentMs = runCatching(silentForMs).getOrNull(),
            supervisionMs = runCatching(supervisionMs).getOrNull(),
        )
        emit("link_lost", null, "", "status=$status | class=${cls.label} | ageMs=$ageMs | recentChanges=${recentSummary(snapshot, lostAt)}")
        dropped.forEach { finishNotStarted(it, GateOutcome.CLOSED) }
        runCatching { onLinkLost(peerKey(), snapshot, lostAt, status) }
    }

    /** True when nothing is in flight or waiting (tests, teardown). */
    fun isIdle(): Boolean = synchronized(lock) { current == null && queue.isEmpty() }

    fun queueDepth(): Int = depth()

    /** The changes requested so far (monotonic ms), for diagnostics. */
    fun recentChanges(): List<RecordedChange> = synchronized(lock) { changes.toList() }

    // -------------------------------------------------------------------------------------------

    private fun depth(): Int = synchronized(lock) { queue.size + if (current != null) 1 else 0 }

    private fun isStuck(kind: LlProcedure, now: Long): Boolean = (stuckUntilMs[kind] ?: 0L) > now

    private fun clearStuck(kind: LlProcedure) {
        var cleared = false
        synchronized(lock) { cleared = stuckUntilMs.remove(kind) != null }
        if (cleared) emit("late_callback", kind, "", "stuck=cleared")
    }

    private fun record(kind: LlProcedure) {
        val changeKind = kind.changeKind ?: return
        synchronized(lock) {
            changes += RecordedChange(changeKind, nowMs())
            if (changes.size > 16) changes.removeAt(0)
        }
    }

    private fun settleFor(kind: LlProcedure, outcome: GateOutcome): Long = when {
        outcome == GateOutcome.NOT_QUEUED -> timing.settleAfterRefusalMs
        kind == LlProcedure.MTU -> timing.settleAfterMtuMs
        kind == LlProcedure.PHY -> timing.settleAfterPhyMs
        kind == LlProcedure.CONNECTION_PARAMS -> timing.settleAfterConnMs
        else -> timing.settleAfterDiscoveryMs
    }

    private fun timeoutFor(step: Step): Long = step.timeoutMs ?: when (step.kind) {
        LlProcedure.MTU -> timing.mtuTimeoutMs
        LlProcedure.PHY -> timing.phyTimeoutMs
        LlProcedure.CONNECTION_PARAMS -> timing.connTimeoutMs
        LlProcedure.SERVICE_DISCOVERY -> timing.discoveryTimeoutMs
    }

    /** Milliseconds the head of the queue must still wait: settle gap, then the peripheral's PHY holdoff. */
    private fun waitFor(step: Step, now: Long): Long {
        var wait = (freeAtMs - now).coerceAtLeast(0L)
        if (role == LeLinkRole.PERIPHERAL && step.kind == LlProcedure.PHY) {
            wait = maxOf(wait, peripheralPhyWait(now))
        }
        return wait
    }

    private fun peripheralPhyWait(now: Long): Long {
        val hardRemain = createdAtMs + timing.peripheralPhyMaxWaitMs - now
        if (hardRemain <= 0) return 0L
        val ready = peerUpdateAtMs >= 0 && peerDiscoveryDone
        val remain = if (ready) {
            val holdoff = maxOf(timing.peripheralMinHoldoffMs, (timing.peripheralIntervalsAfterUpdate * peerIntervalMs).toLong())
            (peerUpdateAtMs + holdoff - now).coerceAtLeast(0L)
        } else {
            hardRemain
        }
        return minOf(remain, hardRemain)
    }

    private fun pump() {
        var started: Step? = null
        var waitLog: String? = null
        synchronized(lock) {
            if (closed || current != null) return
            val head = queue.peekFirst() ?: return
            val now = nowMs()
            val wait = waitFor(head, now)
            if (wait > 0) {
                armWake(now, wait)
                if (role == LeLinkRole.PERIPHERAL && head.kind == LlProcedure.PHY && !peripheralWaitLogged) {
                    peripheralWaitLogged = true
                    waitLog = "peerUpdated=${peerUpdateAtMs >= 0} | peerDiscovered=$peerDiscoveryDone | waitMs=$wait"
                }
            } else {
                queue.removeFirst()
                tokenCounter++
                head.token = tokenCounter
                current = head
                startedAtMs = now
                started = head
            }
        }
        waitLog?.let { emit("peripheral_wait", LlProcedure.PHY, "", it) }
        started?.let { dispatch(it, it.token) }
    }

    private fun armWake(now: Long, waitMs: Long) {
        val target = now + waitMs
        if (wakeAtMs > now && wakeAtMs <= target) return
        wakeAtMs = target
        schedule(waitMs) {
            synchronized(lock) { if (wakeAtMs <= nowMs()) wakeAtMs = 0L }
            pump()
        }
    }

    private fun dispatch(step: Step, token: Long) {
        record(step.kind)
        val request = if (step.useFallback) step.fallback ?: step.start else step.start
        val queued = runCatching(request).getOrDefault(false)
        emit(
            "start", step.kind, step.label,
            "queued=$queued | waitedMs=${nowMs() - step.queuedAtMs}${if (step.useFallback) " | retry=1M" else ""}",
        )
        if (!queued) {
            complete(token, GateOutcome.NOT_QUEUED)
            return
        }
        schedule(timeoutFor(step)) { onTimeout(token) }
    }

    private fun onTimeout(token: Long) {
        val step = synchronized(lock) { current?.takeIf { it.token == token } } ?: return
        emit("timeout", step.kind, step.label, "boundMs=${timeoutFor(step)}")
        complete(token, GateOutcome.TIMED_OUT)
    }

    private fun onCallback(kind: LlProcedure, status: Int, tx: Int? = null) {
        var wasStuck = false
        val step: Step? = synchronized(lock) {
            val c = current
            if (c != null && c.kind == kind) {
                c
            } else {
                // Not the awaited kind: a late callback of a stuck kind clears the stuck mark.
                wasStuck = stuckUntilMs.remove(kind) != null
                null
            }
        }
        if (step == null) {
            if (wasStuck) emit("late_callback", kind, "", "stuck=cleared")
            return
        }
        if (kind == LlProcedure.PHY && status != 0 && (status == 35 || status == 42) && !step.retried) {
            retry(step, status)
            return
        }
        emit(
            "callback", kind, step.label,
            "status=$status${tx?.let { " | tx=${lePhyLabel(it)}" } ?: ""} | inFlightMs=${nowMs() - startedAtMs}",
        )
        complete(step.token, if (status == 0) GateOutcome.COMPLETED else GateOutcome.FAILED_STATUS)
    }

    private fun retry(step: Step, status: Int) {
        synchronized(lock) {
            if (current !== step) return
            step.retried = true
            if (step.fallback != null) {
                step.useFallback = true
                step.expectedTx = LePhy.ONE_M
            }
            current = null
            queue.addFirst(step)
            freeAtMs = nowMs() + timing.retryDelayMs
        }
        emit("retry", step.kind, step.label, "status=$status | delayMs=${timing.retryDelayMs} | on1M=${step.fallback != null}")
        pump()
    }

    private fun complete(token: Long, outcome: GateOutcome) {
        val step: Step
        val dropped = ArrayList<Step>()
        synchronized(lock) {
            val c = current ?: return
            if (c.token != token) return
            step = c
            current = null
            val now = nowMs()
            freeAtMs = now + settleFor(step.kind, outcome)
            if (outcome == GateOutcome.TIMED_OUT && step.kind in stuckKinds) {
                stuckUntilMs[step.kind] = now + timing.stuckTtlMs
                val it = queue.iterator()
                while (it.hasNext()) {
                    val queued = it.next()
                    if (queued.kind == step.kind) {
                        it.remove()
                        dropped += queued
                    }
                }
            }
        }
        emit("done", step.kind, step.label, "outcome=${outcome.name.lowercase()}")
        runCatching { step.onDone?.invoke(outcome) }
        dropped.forEach { finishNotStarted(it, GateOutcome.DROPPED_STUCK) }
        pump()
    }

    private fun finishNotStarted(step: Step, outcome: GateOutcome) {
        emit("dropped", step.kind, step.label, "outcome=${outcome.name.lowercase()}")
        runCatching { step.onDone?.invoke(outcome) }
    }

    private fun recentSummary(snapshot: List<RecordedChange>, lostAt: Long): String =
        snapshot.takeLast(4).joinToString(",") { "${it.kind.key}@-${lostAt - it.atMs}ms" }.ifEmpty { "none" }

    private fun emit(event: String, kind: LlProcedure?, label: String, extra: String) {
        log(
            "BENCHMARK ll_gate: link=$link | role=${role.label} | event=$event" +
                (kind?.let { " | proc=${it.label}" } ?: "") +
                (if (label.isNotEmpty()) " | label=$label" else "") +
                (if (extra.isNotEmpty()) " | $extra" else ""),
        )
    }
}
