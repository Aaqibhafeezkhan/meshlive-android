package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Experimental dual listening (user question Q-P3, designed 2026-10-11; PLAN "Design D", PROTOCOL
 * "Experimental dual listening"). Settings > Features "Reachable over both Bluetooth LE links",
 * default [ConnectivityFeatureDefaults.DUAL_LISTENING] (off until phone test T12), DEBUG benchmark
 * extra `test_dual_listening`. Pure Kotlin except [DualListeningFeature.enabled]: every rule here is
 * JVM-tested. With the switch off nothing in this file changes a byte on air or on a link.
 *
 * A call endpoint has one transport. A CoC endpoint with this feature also serves the GATT call
 * service on the same advertising address and says so with one bit of the announcement's
 * `capabilitySummary`; a caller whose CoC attempt fails opens the GATT service of the same device
 * inside the same attempt, and a GATT-only endpoint can list and call such a phone.
 */
object DualListeningFeature {
    const val EXTRA_ENABLED = "test_dual_listening"
    const val PREF_ENABLED = "experimental_dual_listening"

    /**
     * DEBUG launch extra (string), so the real GATT attempt can be tested: `coc_connect` fails the caller's CoC
     * attempt at once, `coc_hang` makes it block until the CoC phase deadline and then fail.
     */
    const val EXTRA_INJECT = "test_dual_listening_inject"
    const val INJECT_COC_CONNECT = "coc_connect"
    const val INJECT_COC_HANG = "coc_hang"

    enum class Injection { NONE, COC_CONNECT_FAIL, COC_HANG }

    /** The DEBUG injection value of this launch (set from the launch extra; null in a release build). */
    @Volatile var inject: String? = null

    /** The effective switch: a benchmark launch reads only its extra, any other launch the user's choice or the default. */
    val enabled: Boolean get() = FeatureCatalog.isEnabled(ConnectivityFeatures.dualListening.id)

    /** Pure form of the injection rule: only a DEBUG build, with the feature on, and an exact value. */
    internal fun injection(debugBuild: Boolean, enabled: Boolean, value: String?): Injection = when {
        !debugBuild || !enabled -> Injection.NONE
        value == INJECT_COC_CONNECT -> Injection.COC_CONNECT_FAIL
        value == INJECT_COC_HANG -> Injection.COC_HANG
        else -> Injection.NONE
    }
}

/** The announcement hint and the rules for who may set and read it (PROTOCOL "Announcement hint"). */
internal object DualListeningWire {
    /** `capabilitySummary` bit 3 of a call offer: "also reachable over BLE GATT at this address". */
    const val SUMMARY_BIT = 1 shl 3

    /**
     * The one shape that may carry the hint: a private-call offer (not a stream offer) at route cost 0 whose
     * transport flags are clear (LE CoC primary) and that is not a relay-helper offer. Everything else
     * (GATT, Classic, stream offers, viewer requests, relayed offers, helpers) never carries it and ignores it.
     */
    fun mayCarry(offer: DiscoveryAnnouncement): Boolean =
        offer.isPrivateCallOffer() && offer.routeCost == 0 &&
            offer.transportOption() == BluetoothTransportOption.BLE_L2CAP &&
            offer.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE == 0

    /** True only for an offer of the carrying shape whose summary has the bit; elsewhere the bit means nothing. */
    fun hasHint(offer: DiscoveryAnnouncement): Boolean =
        mayCarry(offer) && offer.capabilitySummary and SUMMARY_BIT != 0

    /**
     * The offer this endpoint advertises: [base] with the hint exactly when the feature is on, the second
     * listener really exists ([secondaryReady]) and [base] has the carrying shape. Otherwise [base] itself
     * (the same instance), so a switch-off offer is byte-identical by construction.
     */
    fun offerFor(base: DiscoveryAnnouncement, enabled: Boolean, secondaryReady: Boolean): DiscoveryAnnouncement {
        if (!enabled || !secondaryReady || !mayCarry(base)) return base
        return base.copy(capabilitySummary = base.capabilitySummary or SUMMARY_BIT)
    }

    /** [offer] without the bit (a mirrored offer must not claim a second transport). */
    fun stripped(offer: DiscoveryAnnouncement): DiscoveryAnnouncement =
        if (offer.capabilitySummary and SUMMARY_BIT == 0) offer else offer.copy(capabilitySummary = offer.capabilitySummary and SUMMARY_BIT.inv())

    /** The transports a selected offer allows a caller to use: its primary one, plus GATT when the hint is valid. */
    fun offeredTransports(offer: DiscoveryAnnouncement): Set<BluetoothTransportOption> {
        val primary = offer.transportOption()
        return if (hasHint(offer)) setOf(primary, BluetoothTransportOption.BLE_GATT) else setOf(primary)
    }
}

/** The caller's transport attempts for one call attempt (PROTOCOL "Caller"). */
internal object DualCallerPlan {
    /**
     * Ordered attempts for [offer] from an endpoint whose own transport is [endpoint]. Switch off, Classic,
     * a relayed or non-call offer: just the endpoint's own transport. A CoC endpoint with a hinted CoC offer:
     * CoC then GATT. Everything else: the endpoint's own transport (a GATT endpoint reaches a hinted CoC
     * offer over GATT with no code of its own; a GATT offer seen by a CoC endpoint keeps the safety net's
     * rebuild).
     */
    fun plan(endpoint: BluetoothTransportOption, offer: DiscoveryAnnouncement, enabled: Boolean): List<BluetoothTransportOption> {
        if (!enabled || endpoint == BluetoothTransportOption.CLASSIC_RFCOMM) return listOf(endpoint)
        if (endpoint == BluetoothTransportOption.BLE_L2CAP && DualListeningWire.hasHint(offer)) {
            return listOf(BluetoothTransportOption.BLE_L2CAP, BluetoothTransportOption.BLE_GATT)
        }
        return listOf(endpoint)
    }

    /** Whether a GATT endpoint lists [offer] only because of the hint (the call list's extra condition). */
    fun listsHintedOffer(endpoint: BluetoothTransportOption, offer: DiscoveryAnnouncement, enabled: Boolean): Boolean =
        enabled && endpoint == BluetoothTransportOption.BLE_GATT && DualListeningWire.hasHint(offer)
}

/** Whether a link on [used] may carry a call the user selected [selected] for (PROTOCOL "Identity binding"). */
internal object DualLinkRule {
    enum class Result { PASS, NOT_OFFERED, NOT_SIGNED }

    /**
     * The transport-specific half of the caller's check (the identity half, `DiscoverySelectionCheck` and the
     * end-to-end prefix check, takes no transport and runs on every link): the offer allowed [used] and the
     * peer's signed link hello carries its bit.
     */
    fun verifyCaller(selected: DiscoveryAnnouncement, used: BluetoothTransportOption, peerTransportMask: Int): Result = when {
        used !in DualListeningWire.offeredTransports(selected) -> Result.NOT_OFFERED
        peerTransportMask and used.capabilityMask == 0 -> Result.NOT_SIGNED
        else -> Result.PASS
    }

    /** The callee side: a link may arrive on the endpoint's primary transport, or on GATT while the second listener exists. */
    fun arrivalAllowed(
        primary: BluetoothTransportOption,
        secondaryOpen: Boolean,
        arrived: BluetoothTransportOption,
    ): Boolean = arrived == primary || (secondaryOpen && primary == BluetoothTransportOption.BLE_L2CAP &&
        arrived == BluetoothTransportOption.BLE_GATT)
}

/** Time budget of the caller's CoC-then-GATT sequence (PROTOCOL "Caller"). */
internal object DualSetupBudget {
    /** The CoC phase (PSM read over GATT, then the CoC connect) is cut at this deadline; the GATT attempt follows. */
    const val COC_PHASE_MS = 9_000L

    /**
     * An accepted link on a dual endpoint must finish both handshakes within this time or it is closed: the
     * second door (a GATT central enabling notifications) must not let a silent stranger hold the endpoint
     * and, on a GATT win, take the CoC listener and the advertisement with it.
     */
    const val INCOMING_HANDSHAKE_MS = 20_000L

    /** The deadline of the CoC phase, an absolute elapsedRealtime value, never later than [outerDeadlineMs] when one exists. */
    fun cocDeadline(startMs: Long, outerDeadlineMs: Long?): Long {
        val own = startMs + COC_PHASE_MS
        return if (outerDeadlineMs == null) own else minOf(own, outerDeadlineMs.coerceAtLeast(startMs))
    }
}

/** Failure of every attempt of a plan; [causes] keeps one error per transport tried, in order. */
internal class DualSetupFailedException(
    val causes: List<Pair<BluetoothTransportOption, Throwable>>,
) : IOException(
    "Call setup failed on " + causes.joinToString(" and ") { (transport, error) ->
        "${transport.shortLabel}: ${error.message ?: error.javaClass.simpleName}"
    },
    causes.lastOrNull()?.second,
)

/**
 * Runs a plan: each transport once, in order, the next only after the previous failed (the caller never
 * scans between attempts and reuses the selected device). Pure: the opener, clock, cancellation and log are
 * injected so the sequence is JVM-tested.
 */
internal class DualAttemptRunner<C : Any>(
    private val clockMs: () -> Long,
    private val cancelled: () -> Boolean,
    private val log: (String) -> Unit,
) {
    class Outcome<C : Any>(
        val link: C,
        val transport: BluetoothTransportOption,
        /** The transport that failed first when [transport] is a fallback; null for a first-attempt success. */
        val fellBackFrom: BluetoothTransportOption?,
        val firstError: Throwable?,
        val firstPhaseMs: Long,
    ) {
        val fallback: Boolean get() = fellBackFrom != null
    }

    /**
     * [open] opens one attempt on a transport; [attemptIndex] is 0 for the first. It throws on failure. An
     * [InterruptedException] or a cancelled runner stops the sequence at once (no further attempt).
     */
    fun run(
        plan: List<BluetoothTransportOption>,
        open: (BluetoothTransportOption, Int) -> C,
    ): Outcome<C> {
        require(plan.isNotEmpty() && plan.size <= MAX_ATTEMPTS && plan.toSet().size == plan.size) {
            "A plan tries each of at most $MAX_ATTEMPTS transports once"
        }
        val failures = ArrayList<Pair<BluetoothTransportOption, Throwable>>(plan.size)
        var firstPhaseMs = 0L
        for ((index, transport) in plan.withIndex()) {
            if (index > 0 && cancelled()) break
            val startedMs = clockMs()
            try {
                val link = open(transport, index)
                return Outcome(
                    link,
                    transport,
                    fellBackFrom = failures.firstOrNull()?.first,
                    firstError = failures.firstOrNull()?.second,
                    firstPhaseMs = firstPhaseMs,
                )
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (error: Exception) {
                if (index == 0) firstPhaseMs = clockMs() - startedMs
                failures += transport to error
                log(DualListeningLog.attemptFailed(transport, index, error, clockMs() - startedMs))
                if (cancelled()) break
            }
        }
        throw DualSetupFailedException(failures)
    }

    companion object {
        /** CoC then GATT: two attempts at most. */
        const val MAX_ATTEMPTS = 2
    }
}

/**
 * Waits after the last GATT call server closed before another opens (the existing GATT restart settle
 * time, `MainActivity.GATT_CALL_RESTART_DELAY_MS`), so the second server of a new availability does not
 * race the stack's cleanup of the previous one.
 */
internal class DualSettleGate(
    private val clockMs: () -> Long,
    private val settleMs: Long = SETTLE_MS,
) {
    private val lastClosedAtMs = AtomicLong(NEVER)

    fun noteClosed() = lastClosedAtMs.set(clockMs())

    /** Milliseconds still to wait before opening a server now (0 when settled or never closed). */
    fun waitMs(): Long {
        val closedAt = lastClosedAtMs.get()
        if (closedAt == NEVER) return 0L
        return (closedAt + settleMs - clockMs()).coerceIn(0L, settleMs)
    }

    companion object {
        const val SETTLE_MS = 1_500L
        private const val NEVER = Long.MIN_VALUE
    }
}

/** The platform server behind the second listener (a [GattVideoServer] without advertising on phones). */
internal interface DualSecondaryServer : Closeable {
    /** Registers the service and starts accepting; throws [IOException] when it cannot. Does not advertise. */
    fun start()
}

/**
 * Owns at most one second listener for one endpoint: [open] starts it (never throws; false means "no
 * second listener, advertise the plain CoC offer"), [close] is idempotent, and a closed host never opens
 * again. The count of servers it ever holds at once is 1 ([DualListeningBudget.SECONDARY_GATT_SERVERS]).
 */
internal class DualSecondaryHost(
    private val create: () -> DualSecondaryServer,
    private val settle: DualSettleGate,
    private val sleepMs: (Long) -> Unit,
    private val log: (String) -> Unit,
) : Closeable {
    private var server: DualSecondaryServer? = null
    private var closed = false

    /** The server is registered and running (the hint may be advertised). */
    val ready: Boolean @Synchronized get() = server != null && !closed

    val serverCount: Int @Synchronized get() = if (server != null) 1 else 0

    fun open(): Boolean {
        val wait = synchronized(this) {
            if (closed || server != null) return ready
            settle.waitMs()
        }
        if (wait > 0L) {
            try {
                sleepMs(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        val candidate = try {
            create().also(DualSecondaryServer::start)
        } catch (error: Exception) {
            log(DualListeningLog.secondary("failed", 0, error.message ?: error.javaClass.simpleName))
            return false
        }
        val keep = synchronized(this) {
            if (closed || server != null) {
                false
            } else {
                server = candidate
                true
            }
        }
        if (!keep) {
            runCatching { candidate.close() }
            settle.noteClosed()
            return ready
        }
        log(DualListeningLog.secondary("started", 1, null))
        return true
    }

    override fun close() {
        val old = synchronized(this) {
            closed = true
            server.also { server = null }
        } ?: return
        runCatching { old.close() }
        settle.noteClosed()
        log(DualListeningLog.secondary("closed", 0, null))
    }
}

/** The resources a dual endpoint holds (PLAN "What it costs"), pinned so a regression shows in a JVM test. */
internal object DualListeningBudget {
    /** `BluetoothGattServer` registrations the second listener adds to a CoC endpoint's one control server. */
    const val SECONDARY_GATT_SERVERS = 1

    /** Advertising sets the second listener adds: it shares the control server's one connectable set. */
    const val SECONDARY_ADVERTISING_SETS = 0

    /** Scan filters the feature adds: the scan filter is the unchanged service UUID. */
    const val SECONDARY_SCAN_FILTERS = 0

    /** CoC registrations it adds: the CoC lease per role is the same. */
    const val SECONDARY_COC_REGISTRATIONS = 0

    /** Seconds-of-air bytes it adds to the scan response: one bit of an existing byte. */
    const val SECONDARY_ANNOUNCEMENT_BYTES = 0

    /** GATT servers an endpoint holds while available: the control server plus the second listener. */
    fun gattServers(secondaryOpen: Boolean): Int = 1 + if (secondaryOpen) SECONDARY_GATT_SERVERS else 0

    fun advertisingSets(secondaryOpen: Boolean): Int = 1 + if (secondaryOpen) SECONDARY_ADVERTISING_SETS else 0
}

/** Log lines of the feature (BENCHMARK prefix is added by the caller in DEBUG builds). */
internal object DualListeningLog {
    fun offer(hint: Boolean, summary: Int, secondary: String, reason: String? = null): String =
        "dual_listening: event=offer | hint=$hint | summary=0x${"%02x".format(summary)} | secondary=$secondary" +
            (reason?.let { " | reason=$it" } ?: "")

    fun secondary(state: String, secondaryServers: Int, detail: String?): String =
        "dual_listening: event=secondary | state=$state | " +
            "gattServers=${DualListeningBudget.gattServers(secondaryServers > 0)} | " +
            "adSets=${DualListeningBudget.advertisingSets(secondaryServers > 0)}" +
            (detail?.let { " | detail=${it.replace('\n', ' ').take(80)}" } ?: "")

    fun link(accepted: BluetoothTransportOption, primary: BluetoothTransportOption): String =
        "dual_listening: event=link | accepted=${accepted.name} | primary=${primary.name}"

    fun plan(endpoint: BluetoothTransportOption, attempts: List<BluetoothTransportOption>): String =
        "dual_listening: event=plan | endpoint=${endpoint.name} | attempts=${attempts.joinToString(">") { it.name }}"

    fun attemptFailed(transport: BluetoothTransportOption, index: Int, error: Throwable, elapsedMs: Long): String =
        "dual_listening: event=attempt_failed | transport=${transport.name} | attempt=${index + 1} | " +
            "error=${error.javaClass.simpleName} | elapsedMs=$elapsedMs"

    /** The requested-versus-effective record of a fallback, like the safety net's `call transport:` lines. */
    fun fallback(requested: BluetoothTransportOption, effective: BluetoothTransportOption, cocError: Throwable?, phaseMs: Long): String =
        "call transport: event=dual_fallback | requested=${requested.name} | effective=${effective.name} | " +
            "automatic=true | reason=dual_coc_failed | cocError=${cocError?.javaClass?.simpleName ?: "none"} | cocPhaseMs=$phaseMs"

    /** The visible status line of a fallback. */
    const val FALLBACK_NOTICE = "Using BLE GATT for this call: L2CAP did not connect"
}
