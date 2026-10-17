package com.example.bluetoothtest

import java.io.IOException

/**
 * Automatic GATT fallback for streams (design decision Q50, DECISIONS "Design SG"). Experimental and OFF:
 * every stage needs `coc_auto_gatt` AND the sub-switch [streamsSwitch] (DEBUG extra `test_coc_auto_gatt_streams`,
 * shipped constant [ConnectivityFeatureDefaults.COC_AUTO_GATT_STREAMS], default off). With either off nothing
 * here changes a decision, a scan filter, an admission or a byte on a link.
 *
 * - Stage 0 (receiver notice and `stream_listen_failed` line): text and log only, shown whenever an LE CoC
 *   stream receiver's listen fails, because it changes no behaviour.
 * - Stage 1: a receiver whose LE CoC listen failed restarts on BLE GATT (once per adapter session, the same
 *   ledger as calls). It only reacts to its own failed listen, never to a received advertisement.
 * - Stage 2: an LE CoC source with the sub-switch on also takes GATT viewer requests into a separate GATT
 *   child pool with its own admission budget (mixed fan-out). L2CAP children are never moved, counted into the
 *   pool or evicted; a GATT or Classic source never takes children of another transport. A GATT child that
 *   cannot carry the stream's rate is refused visibly, never given a different stream.
 *
 * Pure decision functions only; the activity, receiver and broadcaster call them.
 */
internal object StreamGattFallback {
    const val EXTRA_STREAMS = "test_coc_auto_gatt_streams"

    /** Initial size of the separate GATT child pool (DECISIONS Design SG, open point: a conservative guess until a mixed run is measured). */
    const val GATT_POOL_CAPACITY = 1

    /** The raw sub-switch of this launch (see [resolveSwitch]); only meaningful together with `coc_auto_gatt`. */
    @Volatile var streamsSwitch: Boolean = ConnectivityFeatureDefaults.COC_AUTO_GATT_STREAMS

    /**
     * A DEBUG build's benchmark launch uses only its own extra; every other launch (and every release build, whatever
     * extras another app sends) the shipped constant.
     */
    fun resolveSwitch(
        debugBuild: Boolean,
        benchmarkLaunch: Boolean,
        extra: Boolean,
        shipped: Boolean = ConnectivityFeatureDefaults.COC_AUTO_GATT_STREAMS,
    ): Boolean = if (debugBuild && benchmarkLaunch) extra else shipped

    /** Whether the stream stages may act: `coc_auto_gatt` on and the sub-switch on. */
    fun active(auto: CocAutoGattSettings = CocAutoGatt.current, streams: Boolean = streamsSwitch): Boolean =
        auto.enabled && streams

    // ---- Stage 0 / 1: the receiver ------------------------------------------------------------------

    enum class ReceiverRestart(val label: String, val restart: Boolean) {
        RESTART_ON_GATT("restart_on_gatt", true),
        OFF("off", false),
        NOT_L2CAP("not_l2cap", false),
        NOT_LISTEN_FAILURE("not_listen_failure", false),
        WAS_ACTIVE("was_active", false),
        STREAM_ENDED("stream_ended", false),
        ALREADY_FALLEN_BACK("already_fallen_back", false),
    }

    /**
     * Whether a receiver that stopped should be restarted on GATT. Only a failed LE CoC listen (the receiver's
     * own STARTUP failure) on an L2CAP receiver that never became active, with both switches on and the adapter
     * session's ledger not already forced. A link loss, a local media failure or a withdrawn route never does.
     */
    fun receiverRestart(
        active: Boolean,
        receiverTransport: BluetoothTransportOption,
        stopKind: ReceiverStopKind?,
        listenFailed: Boolean,
        wasActive: Boolean,
        ended: Boolean,
        ledgerAlreadyForced: Boolean,
    ): ReceiverRestart = when {
        !active -> ReceiverRestart.OFF
        receiverTransport != BluetoothTransportOption.BLE_L2CAP -> ReceiverRestart.NOT_L2CAP
        stopKind != ReceiverStopKind.STARTUP || !listenFailed -> ReceiverRestart.NOT_LISTEN_FAILURE
        wasActive -> ReceiverRestart.WAS_ACTIVE
        ended -> ReceiverRestart.STREAM_ENDED
        ledgerAlreadyForced -> ReceiverRestart.ALREADY_FALLEN_BACK
        else -> ReceiverRestart.RESTART_ON_GATT
    }

    /**
     * The transport a stream receiver starts on: the offer's, except that an L2CAP offer is joined over GATT
     * when this adapter session already forced GATT ([forced], from [CocAutoGattSession.forcedFor]) and the
     * stages are active. Recovery by Bluetooth off/on resets the ledger, so the next join is L2CAP again.
     */
    fun receiverTransport(
        offered: BluetoothTransportOption,
        active: Boolean,
        forced: CocTransportChoice?,
    ): BluetoothTransportOption =
        if (active && offered == BluetoothTransportOption.BLE_L2CAP && forced != null) {
            BluetoothTransportOption.BLE_GATT
        } else {
            offered
        }

    /** The visible receiver notice for a failed LE CoC listen (stage 0). */
    fun listenFailedNotice(detail: String, fallbackActive: Boolean): String =
        "This phone's Bluetooth could not listen for L2CAP streams ($detail). " +
            if (fallbackActive) {
                "Trying BLE GATT. Turning Bluetooth off and on restores L2CAP."
            } else {
                "Turn Bluetooth off and on to restore it."
            }

    /** The notice when the receiver restarted on GATT; it names the requested and the effective transport. */
    fun restartNotice(choice: CocTransportChoice): String =
        "Using BLE GATT for this stream: L2CAP unavailable on this phone " +
            "(requested ${choice.requested.shortLabel}, using ${choice.effective.shortLabel}). " +
            "Turning Bluetooth off and on restores L2CAP."

    /** The `stream_listen_failed` line body (stage 0). The caller adds the `BENCHMARK ` prefix in DEBUG builds. */
    fun listenFailedLine(
        transport: BluetoothTransportOption,
        streamToken: Int,
        cause: String,
        registrations: Int,
        auto: CocAutoGattSettings,
        streamsSwitch: Boolean,
        injected: Boolean,
    ): String =
        "stream_listen_failed: transport=${transport.name} | token=$streamToken | cause=$cause | " +
            "cocRegistrations=$registrations | threshold=${auto.threshold} | coc_auto_gatt=${auto.enabled} | " +
            "streams_gatt=$streamsSwitch | injected=$injected"

    /** The restart / refusal line body for the receiver ledger. */
    fun restartLine(verdict: ReceiverRestart, choice: CocTransportChoice?, streamToken: Int): String =
        "stream_gatt_fallback: side=receiver | event=${verdict.label} | token=$streamToken" +
            (choice?.let { " | ${it.logLine()}" } ?: "")

    // ---- Stage 2: the source ------------------------------------------------------------------------

    /**
     * The viewer-request source filter (transport part). Off: exactly the session transport, as before. On with
     * an LE CoC source: also a GATT request. Never the reverse (GATT and Classic sources take only their own).
     */
    fun acceptsViewerRequest(
        request: BluetoothTransportOption,
        session: BluetoothTransportOption,
        active: Boolean,
    ): Boolean = request == session ||
        (active && session == BluetoothTransportOption.BLE_L2CAP && request == BluetoothTransportOption.BLE_GATT)

    enum class ChildPool { PRIMARY, GATT_POOL, NOT_ACCEPTED }

    /** Which pool a viewer request belongs to on a source whose session transport is [session]. */
    fun poolFor(request: BluetoothTransportOption, session: BluetoothTransportOption, active: Boolean): ChildPool = when {
        request == session -> ChildPool.PRIMARY
        acceptsViewerRequest(request, session, active) -> ChildPool.GATT_POOL
        else -> ChildPool.NOT_ACCEPTED
    }

    enum class GattChildVerdict(val label: String, val admit: Boolean) {
        ADMIT("admit", true),
        REFUSE_POOL_FULL("gatt_pool_full", false),
        REFUSE_RATE("gatt_rate", false),
        REFUSE_AGGREGATE("aggregate_budget", false),
    }

    /** Everything the GATT child admission needs, measured by the broadcaster (kbps are wire kbps incl. margin). */
    data class GattChildInput(
        val gattChildren: Int,
        val poolCapacity: Int,
        /** The planned rate the existing GATT children are already charged. */
        val gattChargedKbps: Double,
        /** L2CAP children already connected or connecting, at their planned per-link rate. */
        val l2capCommittedKbps: Double,
        /** The source's aggregate fan-out budget for its session transport. */
        val aggregateBudgetKbps: Double,
        /** What this child needs: the stream's planned per-link rate (times the base-layer share when the stream is layered). */
        val needKbps: Double,
        /** What one GATT link may carry (its measured/prior budget). */
        val gattLinkCapacityKbps: Double,
    )

    data class GattChildAdmission(val verdict: GattChildVerdict, val chargeKbps: Double)

    /**
     * Admission of one GATT child into the separate pool: the pool must have room, the child must be able to carry
     * the stream's rate (otherwise it is refused, never given a different stream), and the aggregate charge
     * (L2CAP children plus GATT children plus this one) must fit the source's budget. Existing L2CAP children are
     * never evicted by this decision; a later L2CAP admission sees the charge through [fanOutBudgetKbps].
     */
    fun admitGattChild(input: GattChildInput): GattChildAdmission = when {
        input.gattChildren >= input.poolCapacity -> GattChildAdmission(GattChildVerdict.REFUSE_POOL_FULL, 0.0)
        input.needKbps > input.gattLinkCapacityKbps -> GattChildAdmission(GattChildVerdict.REFUSE_RATE, 0.0)
        input.l2capCommittedKbps + input.gattChargedKbps + input.needKbps > input.aggregateBudgetKbps ->
            GattChildAdmission(GattChildVerdict.REFUSE_AGGREGATE, 0.0)
        else -> GattChildAdmission(GattChildVerdict.ADMIT, input.needKbps)
    }

    /** The budget left for L2CAP fan-out after the GATT children's airtime is charged (never negative). */
    fun fanOutBudgetKbps(aggregateBudgetKbps: Double, gattChargedKbps: Double): Double =
        (aggregateBudgetKbps - gattChargedKbps).coerceAtLeast(0.0)

    /** The `stream_gatt_pool` line body for an admission decision. */
    fun poolLine(input: GattChildInput, admission: GattChildAdmission, l2capChildren: Int): String =
        "stream_gatt_pool: event=${if (admission.verdict.admit) "admit" else "refuse"} | reason=${admission.verdict.label} | " +
            "gattChildren=${input.gattChildren}/${input.poolCapacity} | l2capChildren=$l2capChildren | " +
            "needKbps=${input.needKbps.toInt()} | gattLinkCapacityKbps=${input.gattLinkCapacityKbps.toInt()} | " +
            "chargedKbps=${admission.chargeKbps.toInt()} | gattChargedKbps=${input.gattChargedKbps.toInt()} | " +
            "l2capCommittedKbps=${input.l2capCommittedKbps.toInt()} | budgetKbps=${input.aggregateBudgetKbps.toInt()}"

    /** The visible source-side status for a refused GATT child. */
    fun refusalStatus(verdict: GattChildVerdict): String = when (verdict) {
        GattChildVerdict.REFUSE_POOL_FULL -> "A receiver that can only use BLE GATT was not admitted: the GATT slot is in use."
        GattChildVerdict.REFUSE_RATE -> "A receiver that can only use BLE GATT was not admitted: this stream is too fast for a GATT link."
        GattChildVerdict.REFUSE_AGGREGATE -> "A receiver that can only use BLE GATT was not admitted: it would take airtime from the other viewers."
        GattChildVerdict.ADMIT -> ""
    }
}

/** Design SG stage 2: the source's separate GATT child pool (capacity) and the I/O configuration of its GATT links. */
class GattChildPoolConfig(val capacity: Int, val ioConfig: VideoStreamIoConfig)

/** A failed LE CoC listen of a stream receiver: the failure signature, and whether the DEBUG injection caused it. */
internal data class StreamListenFailure(val signature: String, val injected: Boolean)

/**
 * DEBUG listen-failure injection (`test_coc_listen_inject=stream`, off by default): the LE CoC listen of a stream
 * receiver throws, so a healthy phone can test the fallback that a leaking stack (the A15s) triggers for real. Only a
 * DEBUG build reads the extra; the value must match exactly.
 */
internal object CocListenInject {
    const val EXTRA = "test_coc_listen_inject"
    const val STREAM = "stream"

    /** The DEBUG injection value of this launch (null in a release build). */
    @Volatile var value: String? = null

    /** What an injected listen failure throws, so the receiver can tell an injected failure in its log. */
    class InjectedListenFailure(role: String) : IOException("injected LE CoC listen failure ($EXTRA=$role)")

    /** Pure form of the rule. */
    fun shouldFail(debugBuild: Boolean, configured: String?, role: String): Boolean =
        debugBuild && configured == role

    /** Throws [InjectedListenFailure] when this launch asked for a failed listen of [role]. */
    fun failIfRequested(role: String) {
        if (shouldFail(BuildConfig.DEBUG, value, role)) throw InjectedListenFailure(role)
    }
}
