package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** What the activity knows that decides whether this phone helps (read on the main thread, cached for admission). */
internal data class HelperDeviceState(
    val bluetoothReady: Boolean,
    /** The call endpoint exists and is available for calls (it may be serving a request right now). */
    val callReady: Boolean,
    /** The call endpoint's transport, null while there is none. */
    val transport: BluetoothTransportOption?,
    val batteryPercent: Int,
    val charging: Boolean,
    val batterySaver: Boolean,
    val thermalSevere: Boolean,
    /** The user's own call, ringing call, stream, viewing or message exchange. */
    val ownActivity: Boolean,
    val screenOn: Boolean,
    val rotatingIdsOn: Boolean,
)

/**
 * The runtime of experimental relayed calls (DECISIONS Design C) for one activity: the reach table, the idle
 * scan, the reach pages, SETUP admission and the helper state shown in Settings. It exists only while the
 * experiment is on; the activity creates it in applyRelayedCalls and drops it when the switch goes off.
 * All radio work is bounded by [RelayHelperPolicy]; a relay already running is never cut by a pause.
 */
internal class RelayedCallsController(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val deviceState: () -> HelperDeviceState,
    /** The foreground notification text: relaying (with the relay count) or back to ready. */
    private val onRelayNotification: (relaying: Boolean, relays: Int) -> Unit,
    /** The helper state or table changed in a way Settings or the call list shows. */
    private val onChanged: () -> Unit,
    /** The call offer on air no longer matches the helper/consent state: restart the idle endpoint. */
    private val onOfferStale: () -> Unit,
) : RelayHelperHost {
    private val handler = Handler(Looper.getMainLooper())
    private val localNodeId = AndroidNodeIdentityStore().getOrCreate().nodeId
    private val localPrefix = localNodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)

    val table = ReachTable<BleL2capPeer>(
        localPrefix,
        ReachConfig(maxRelays = RelayedCallsFeature.maxRelays),
        clock = RelayedCallsRuntime::nowMs,
        directSightings = ::directSightings,
    )
    /**
     * Rotating IDs + relayed calls (PLAN C4, reach pages version 6): the version 5 population has its own table,
     * flushed at every boundary together with the reach pages' set (RV6).
     */
    val rotatingTable = ReachTable<BleL2capPeer>(
        ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
        ReachConfig(maxRelays = RelayedCallsFeature.maxRelays),
        clock = RelayedCallsRuntime::nowMs,
        rotating = true,
        ownIds = { RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER) },
        directSightings = ::directSightings,
    )
    private fun directSightings(): Boolean = !RelayedCallsFeature.noDirectSightings
    private var boundaryListening = false
    private val boundaryListener: (Long) -> Unit = { _ -> onBoundary() }
    @Volatile private var advertiserHoldUntilMs = 0L
    private var advertiserRotating = false
    private val limiter = RelaySetupRateLimiter(RelayedCallsRuntime::nowMs)

    @Volatile private var snapshot: HelperDeviceState? = null
    @Volatile var plan: RelayHelperPlan = RelayHelperPolicy.plan(inputsFor(null))
        private set
    @Volatile private var relaying = false

    /** The bits of the call offer currently on air (helping, consent); null when none is known. */
    @Volatile private var advertised: Pair<Boolean, Boolean>? = null
    private var scanner: BleL2capDiscovery? = null
    private var scannerMode: HelperScan? = null
    private var scannerStartedMs = 0L
    private var scannerRetryAtMs = 0L
    private var advertiser: ReachAdvertiser? = null
    private var advertiserTransport: BluetoothTransportOption? = null
    private var lastStateLine: String? = null
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        RelayedCallsRuntime.table = table
        RelayedCallsRuntime.rotatingTable = rotatingTable
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        stopScanner()
        stopAdvertiser()
        if (boundaryListening) {
            boundaryListening = false
            RotatingDiscovery.removeBoundaryListener(boundaryListener)
        }
        RelayedCallsRuntime.table = null
        RelayedCallsRuntime.rotatingTable = null
        table.clear()
        rotatingTable.clear()
        relaying = false
    }

    /**
     * Rotating IDs (RV6): a boundary flushes the version 5 table (its entries name last epoch's IDs) and stops
     * the version 6 pages for the usual 1-8 s silence; the next refresh starts them on a new set with the new
     * RDI. Learned entries need their sightings and five seconds again, so relayed reach is briefly empty.
     */
    private fun onBoundary() {
        rotatingTable.clear()
        stopAdvertiser()
        advertiserHoldUntilMs = SystemClock.elapsedRealtime() + RotatingDiscovery.boundarySilenceMs()
        handler.postDelayed({ refresh() }, advertiserHoldUntilMs - SystemClock.elapsedRealtime() + 100L)
    }

    /** Re-evaluates the helper policy and starts or stops the scan and the reach pages (main thread). */
    fun refresh() {
        val state = deviceState()
        snapshot = state
        val previous = plan
        val rotatingNow = RelayedCallsFeature.rotatingActive(state.rotatingIdsOn)
        if (rotatingNow != boundaryListening) {
            boundaryListening = rotatingNow
            if (rotatingNow) {
                RotatingDiscovery.addBoundaryListener(boundaryListener)
            } else {
                RotatingDiscovery.removeBoundaryListener(boundaryListener)
                rotatingTable.clear()
            }
        }
        plan = RelayHelperPolicy.plan(inputsFor(state))
        val line = "state=${if (plan.helping) "RUNNING" else "PAUSED"} | reason=${plan.pause.name} | scan=${plan.scan.name} | relaying=$relaying"
        if (line != lastStateLine) {
            lastStateLine = line
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_helper: $line")
        }
        // The offer on air carries the helper flag and the consent bit from when it opened. If the policy or the
        // user's choice changed since, a restart makes callers see the truth (an idle endpoint only).
        val onAir = advertised
        if (onAir != null && state.callReady && !relaying &&
            (onAir.first != liveHelping(state) || onAir.second != consentOn())
        ) {
            advertised = null
            onOfferStale()
        }
        if (plan.helping) {
            ensureScanner(plan.scan)
            if (!relaying) ensureAdvertiser(state.transport) else stopAdvertiser()
        } else {
            stopScanner()
            stopAdvertiser()
        }
        if (BuildConfig.DEBUG && ++ticks % TABLE_LOG_TICKS == 0) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK relayed_reach_table: entries=${table.size} | rotating_entries=${rotatingTable.size} | " +
                    "pages=${RelayedCallsRuntime.pagesSeen} | offers=${RelayedCallsRuntime.offersSeen} | " +
                    "costs=${table.costSummary()} | rotating_costs=${rotatingTable.costSummary()}",
            )
        }
        if (previous.pause != plan.pause) onChanged()
    }

    fun statusLine(): String = RelayHelperPolicy.statusLine(plan, snapshot?.batteryPercent ?: -1)

    private fun inputsFor(state: HelperDeviceState?): RelayHelperInputs = RelayHelperInputs(
        featureOn = RelayedCallsFeature.enabled,
        helpOn = RelayedCallsFeature.helpEnabled,
        rotatingIdsOn = state?.rotatingIdsOn ?: RotatingDiscoveryFeature.enabled,
        rotatingCompat = RelayedCallsFeature.rotatingCompat,
        leTransport = state?.transport?.let { it != BluetoothTransportOption.CLASSIC_RFCOMM } ?: false,
        bluetoothReady = state?.bluetoothReady ?: false,
        callReady = state?.callReady ?: false,
        batteryPercent = state?.batteryPercent ?: -1,
        charging = state?.charging ?: false,
        batterySaver = state?.batterySaver ?: false,
        thermalSevere = state?.thermalSevere ?: false,
        ownActivity = state?.ownActivity ?: false,
        screenOn = state?.screenOn ?: true,
    )

    // ----- scan and reach pages -----

    private fun ensureScanner(mode: HelperScan) {
        val now = SystemClock.elapsedRealtime()
        val running = scanner
        // R19: AOSP downgrades a filtered scan after 30 minutes, so the scan is restarted before that
        // (inside the start quota) and whenever the screen/charging state changes the mode.
        if (running != null && scannerMode == mode && now - scannerStartedMs < SCAN_RESTART_MS) return
        if (running == null && now < scannerRetryAtMs) return
        stopScanner()
        val adapter = adapterProvider() ?: return
        scannerMode = mode
        scannerStartedMs = now
        scanner = BleL2capDiscovery(
            adapter,
            onPeer = {},
            onFinished = {},
            onError = { message ->
                handler.post {
                    Log.w("BluetoothVideo", "relayed_helper: idle scan stopped: $message")
                    stopScanner()
                    scannerRetryAtMs = SystemClock.elapsedRealtime() + SCAN_RETRY_MS
                }
            },
            acceptAnnouncement = { it.isPrivateCallOffer() },
            scanDurationMs = 0L,
            startLowLatency = false,
            lowPower = mode == HelperScan.LOW_POWER,
            acceptRotating = RelayedCallsFeature.rotatingActive(RotatingDiscoveryFeature.enabled),
        ).also { it.start() }
    }

    private fun stopScanner() {
        scanner?.close()
        scanner = null
        scannerMode = null
    }

    private fun ensureAdvertiser(transport: BluetoothTransportOption?) {
        val adapter = adapterProvider()
        if (adapter == null || transport == null || transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            stopAdvertiser()
            return
        }
        val rotatingPages = RelayedCallsFeature.rotatingActive(RotatingDiscoveryFeature.enabled)
        if (rotatingPages && (RotatingDiscovery.visibleByHandle() || SystemClock.elapsedRealtime() < advertiserHoldUntilMs)) {
            // Inside a Visible-by-handle window the phone advertises fixed-prefix offers, and across a boundary
            // the old set stays silent: no version 6 page names an ID that no offer carries.
            stopAdvertiser()
            return
        }
        if (advertiser != null && advertiserTransport == transport && advertiserRotating == rotatingPages) return
        stopAdvertiser()
        advertiserTransport = transport
        advertiserRotating = rotatingPages
        advertiser = if (rotatingPages) {
            ReachAdvertiser(
                adapter,
                { RotatingDiscovery.currentId(RotatingDiscoveryIds.ROLE_CALL_OFFER) },
                transport,
                rotatingTable::advertisable,
                rotating = true,
            )
        } else {
            ReachAdvertiser(adapter, { localPrefix }, transport, table::advertisable)
        }.also { it.start() }
    }

    private fun stopAdvertiser() {
        advertiser?.close()
        advertiser = null
        advertiserTransport = null
    }

    // ----- RelayHelperHost -----

    override fun consentOn(): Boolean = RelayedCallsFeature.consentAllowed(snapshot?.rotatingIdsOn ?: RotatingDiscoveryFeature.enabled)

    /**
     * Asked by a live endpoint (opening its offer, signing a hello), so the endpoint's own existence counts as
     * call-ready here: the periodic plan only turns "running" once the endpoint is available, which is too late
     * for the first offer.
     */
    override fun helperActive(): Boolean = !relaying && liveHelping(snapshot)

    private fun liveHelping(state: HelperDeviceState?): Boolean =
        RelayHelperPolicy.plan(inputsFor(state).copy(callReady = true)).helping

    override fun offerOpened(helperActive: Boolean, consent: Boolean) {
        advertised = helperActive to consent
    }

    override fun admit(
        parentNodeId: NodeId,
        target: ByteArray,
        budget: Int,
        transport: BluetoothTransportOption,
        rotating: Boolean,
    ): RelayAdmission {
        val activeTable = if (rotating) rotatingTable else table
        val state = snapshot ?: deviceState()
        val live = RelayHelperPolicy.plan(inputsFor(state))
        val featureOn = RelayedCallsFeature.enabled
        val allowed = RelayedCallsFeature.helperAllowed(state.rotatingIdsOn)
        val paused = live.paused
        val busy = relaying || state.ownActivity
        // The rate limit counts only requests that could have been served, so a paused phone costs nothing.
        val limited = if (!featureOn || !allowed || paused || busy) {
            false
        } else {
            !limiter.tryAdmit(parentNodeId.toByteArray().prefixHex())
        }
        val accept = { offer: DiscoveryAnnouncement -> offer.transportOption() == transport }
        val route = if (featureOn && !paused && budget in 1..RelayedCallsFeature.maxRelays) {
            activeTable.setupRoute(target, budget, accept)
        } else {
            null
        }
        val parent = parentNodeId.toByteArray()
        val refusal = RelaySetupAdmission.refusal(
            RelaySetupInputs(
                featureOn = featureOn,
                helperAllowed = allowed,
                paused = paused,
                busy = busy,
                rateLimited = limited,
                parentIsSelf = parent.contentEquals(localNodeId.toByteArray()),
                // A rotating target is an RDI: the parent equal to the target is caught by parentProblem after the
                // child authenticates (the parent cannot be its own child).
                parentIsTarget = !rotating && parent.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(target),
                targetIsSelf = if (rotating) {
                    RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER).any { it.contentEquals(target) }
                } else {
                    target.contentEquals(localPrefix)
                },
                budget = budget,
                maxRelays = RelayedCallsFeature.maxRelays,
                routeAvailable = route != null,
            ),
        )
        if (refusal != null) return RelayAdmission.Refuse(refusal)
        val chosen = requireNotNull(route)
        val nextHopPrefix = chosen.nextHop
        if (nextHopPrefix == null) {
            return RelayAdmission.Accept(chosen.hop.peer, nextBudget = 0, hopPrefix = null, rotating = rotating)
        }
        val next = BleL2capPeer(
            device = chosen.hop.peer.device,
            label = chosen.hop.peer.label,
            announcement = RelayedCalls.routedAnnouncement(target, budget - 1, chosen.hop.announcement),
        )
        return RelayAdmission.Accept(next, nextBudget = budget - 1, hopPrefix = nextHopPrefix, rotating = rotating)
    }

    override fun relayStarted(relays: Int) {
        relaying = true
        handler.post {
            stopAdvertiser()
            onRelayNotification(true, relays)
            onChanged()
        }
    }

    override fun relayEnded() {
        relaying = false
        handler.post {
            onRelayNotification(false, 0)
            refresh()
            onChanged()
        }
    }

    override fun routeOutcome(route: RelayRoute, ready: Boolean, reason: RelaySetupFailure?) {
        val routeTable = if (route.rotating) rotatingTable else table
        if (ready) {
            routeTable.markUsed(route.helperPrefix, route.target)
        } else {
            routeTable.markFailed(route.helperPrefix, route.target, noRoute = reason == RelaySetupFailure.NO_ROUTE)
        }
        handler.post { onChanged() }
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val TABLE_LOG_TICKS = 6
        const val SCAN_RESTART_MS = 20 * 60_000L
        const val SCAN_RETRY_MS = 30_000L
    }
}
