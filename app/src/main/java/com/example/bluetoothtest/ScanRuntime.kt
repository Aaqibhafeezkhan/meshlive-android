package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android wiring of batch S: one [attach] call (root hook, next to `ScanStartGate.shared.attach`) starts the
 * Location-mode receiver (Android 10-11), the Bluetooth state receiver, the unclean-exit marker, and a 30 s
 * tick that runs the app-wide renewal and the silence watchdog while at least one scanner is registered.
 * Nothing here scans by itself; it only stops and restarts what [ScanRegistry] holds, through the gate.
 */
internal object ScanRuntime {
    private val attached = AtomicBoolean(false)
    private val timer = HandlerScanTimer()
    private val clock: () -> Long = SystemClock::elapsedRealtime

    @Volatile private var appContext: Context? = null
    @Volatile private var cancelTick: (() -> Unit)? = null
    @Volatile private var lastLoggedStaleAction: String? = null
    @Volatile private var probeRunning = false
    @Volatile private var markerTracker: ScanMarkerTracker? = null

    val health = ScanHealth()

    /** Set by the call layer: a private call is dialling or ringing (renewal waits). Root hook, optional. */
    @Volatile var dialInProgress: () -> Boolean = { false }

    /** Set by the owner of presence/reach data: a known peer should be in range (optional root hook). */
    @Volatile var peerExpectedExtra: () -> Boolean = {
        (RelayedCallsRuntime.table?.size ?: 0) > 0 || (RelayedCallsRuntime.rotatingTable?.size ?: 0) > 0
    }

    private fun budget(): LeScanStartBudget? = runCatching { BluetoothTransportPolicy.scanBudget() }.getOrNull()

    val renewal: ScanRenewal by lazy {
        ScanRenewal(
            registry = ScanRegistry.shared,
            gate = ScanStartGate.shared,
            budget = ::budget,
            timer = timer,
            clock = clock,
            sdk = Build.VERSION.SDK_INT,
            dialInProgress = { dialInProgress() },
            log = ::scanLog,
        )
    }

    /** Screen state at the last tick; the scan-duty log line reads it (screen off changes the duty cycle on 13+). */
    @Volatile var screenOnHint: Boolean = true
        private set

    /** The count of unclean exits this session (SDK 29-30 only; 0 elsewhere). */
    @Volatile var uncleanExits: Int = 0
        private set

    /** Scan result seen by any app scanner: the watchdog's heartbeat. */
    fun onScanResult() = health.onResult(clock())

    /** A scan (re)started anywhere in the app. */
    fun onScanStart() = health.onScanStart(clock())

    const val CONSUMER_SCAN_REGISTRY = "scan-registry"
    const val CONSUMER_ADVERTISING_BUDGET = "advertising-budget"
    const val CONSUMER_LINK_CHANGE_ROLLBACK = "link-change-rollback"

    /** The Bluetooth-epoch consumers [attach] registers: the scanner rebuild only while its registry entry is on. */
    fun epochConsumers(switchedOff: Set<String>): List<String> = listOfNotNull(
        CONSUMER_SCAN_REGISTRY.takeUnless { ScanPolicy.isOff(ScanPolicy.BLUETOOTH_PROCESS_RESTART_RECOVERY.id, switchedOff) },
        CONSUMER_ADVERTISING_BUDGET,
        CONSUMER_LINK_CHANGE_ROLLBACK,
    )

    fun attach(context: Context) {
        if (!attached.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        appContext = app
        val snapshot = runCatching { ScanEnvironment.refresh(app) }.getOrNull()
        val switchedOff = DebugTunables.devicePolicyOff
        val sdk = Build.VERSION.SDK_INT

        if (ScanEnvironment.guidanceActive(sdk, switchedOff)) attachLocationWatcher(app, snapshot)

        // The epoch (the only ACTION_STATE_CHANGED receiver) is always attached: calls, receivers, the stream screen,
        // advertising and link-change marks all register on it. The registry entry's off-switch turns off only its own
        // consumer, the scanner rebuild (switch audit 2026-10-27, A5: it used to detach every Bluetooth-ON consumer).
        BluetoothEpoch.attach(app)
        epochConsumers(switchedOff).forEach { name ->
            when (name) {
                CONSUMER_SCAN_REGISTRY -> BluetoothEpoch.register(
                    name,
                    onOff = { ScanRegistry.shared.pause(ScanRegistry.shared.running()) },
                    onOn = {
                        health.reset()
                        markerTracker?.onBluetoothRestarted()
                        uncleanExits = 0
                        restartScannersAfterBluetoothOn()
                    },
                )
                // One epoch source for the other batches (integration): a Bluetooth restart voids what advertising
                // learned about its instance budget (batch A) and every remembered link-change mark (batch P).
                CONSUMER_ADVERTISING_BUDGET -> BluetoothEpoch.register(
                    name,
                    onOff = { AdvertisingBudget.shared.onAdapterRestarted() },
                    onOn = {},
                )
                CONSUMER_LINK_CHANGE_ROLLBACK -> BluetoothEpoch.register(
                    name,
                    onOff = { LinkChangeRollbacks.onBluetoothRestarted() },
                    onOn = {},
                )
            }
        }

        if ((sdk == 29 || sdk == 30) && !ScanPolicy.isOff("android10-scanner-leak-on-process-death", switchedOff)) {
            val bootCount = runCatching {
                android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
            }.getOrNull()
            val tracker = ScanMarkerTracker(FileScanMarkerStore(app.filesDir, bootCount))
            markerTracker = tracker
            uncleanExits = tracker.onProcessStart()
            scanLog("scan_leak: event=process_start | sdk=$sdk | uncleanExits=$uncleanExits")
        }

        ScanRegistry.shared.onOccupancyChanged = { occupied ->
            if (occupied) {
                markerTracker?.onScannerRegistered()
                scheduleTick()
            } else {
                markerTracker?.onAllScannersGone()
                cancelTick?.invoke()
                cancelTick = null
            }
        }
        if (ScanRegistry.shared.size > 0) {
            markerTracker?.onScannerRegistered()
            scheduleTick()
        }
    }

    /**
     * Bluetooth ON. Control arm (`test_bluetooth_on_recovery=false`): resume what the off callback paused, as before.
     * Recovery: a scanner started while the adapter was not yet ON (a viewer search restarted by the link loss during the
     * toggle, or BLE-only mode) is still RUNNING in the registry but holds a dead stack registration (raw=0 for good,
     * lane15 a55m31-edge3-d2). Stop it first; the resume then restarts it through its own gated start path.
     */
    internal fun restartScannersAfterBluetoothOn() {
        if (!BluetoothOnRecovery.enabled) {
            ScanRegistry.shared.resume()
            return
        }
        ScanRegistry.shared.recoverAfterBluetoothOn(BluetoothEpoch.core.lastStateChangeMs) { name, decision ->
            BluetoothOnRecovery.log(
                BluetoothOnRecovery.COMPONENT_SCANNER,
                if (decision.restart) "restarted" else "skipped",
                decision.reason,
                "name=$name",
            )
        }
    }

    private fun attachLocationWatcher(app: Context, first: ScanEnvSnapshot?) {
        val watcher = ScanEnvWatcher(
            provider = { ScanEnvironment.refresh(app) },
            onUnblocked = {
                scanLog("scan_env: event=unblocked | restart=through_scan_start_gate")
                renewal.restartAfterEnvironmentChange()
            },
            onChanged = { scanLog(ScanEnvironment.line(it)) },
        )
        first?.let(watcher::seed)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ScanEnvironment.MODE_CHANGED_ACTION) watcher.onModeChanged()
            }
        }
        runCatching { app.registerReceiver(receiver, IntentFilter(ScanEnvironment.MODE_CHANGED_ACTION)) }
    }

    private fun scheduleTick() {
        cancelTick?.invoke()
        cancelTick = timer.schedule(ScanHealth.CHECK_INTERVAL_MS) {
            runCatching { tick() }
            if (ScanRegistry.shared.size > 0) scheduleTick() else cancelTick = null
        }
    }

    /** Every 30 s while a scanner is registered. Main thread. */
    internal fun tick() {
        val app = appContext ?: return
        val now = clock()
        val switchedOff = DebugTunables.devicePolicyOff
        val env = runCatching { ScanEnvironment.refresh(app) }.getOrNull()
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = power?.isInteractive ?: true
        screenOnHint = screenOn
        val idle = power?.isDeviceIdleMode ?: false

        renewal.tick()

        // Stay on the filtered scan when the screen goes off (S-06).
        if (ScanFilterFallback.softwareFiltering() && !screenOn) {
            ScanFilterFallback.setStage(ScanFilterStage.FILTERED)
            scanLog("scan_filter: event=reverted | why=screen_off")
            renewal.renewNow("filter_stage", force = true)
        }

        if (ScanPolicy.isOff("scan-silence-watchdog", switchedOff)) return
        val budget = budget()
        val inputs = ScanHealthInputs(
            nowMs = now,
            scanning = ScanRegistry.shared.running(renewableOnly = true).isNotEmpty(),
            scanAgeMs = ScanRegistry.shared.oldestRunningAgeMs(),
            screenOn = screenOn,
            deviceIdle = idle,
            blockedReason = env?.blockedReason,
            sdk = Build.VERSION.SDK_INT,
            peerExpected = health.sawPeerRecently(now) || peerExpectedExtra(),
            oemAggressive = ScanHealth.oemAggressive(ScanHealth.manufacturerNow()),
            renewalPending = renewal.pending,
            gateHasSlot = ScanStartGate.shared.available(budget),
            restartEnabled = ScanSwitches.staleRestart,
            quotaWindowFull = !ScanStartGate.shared.available(budget),
        )
        val decision = health.evaluate(inputs)
        if (decision !is ScanHealthDecision.Stale) {
            lastLoggedStaleAction = null
            return
        }
        BackgroundReliability.noteScanStale(decision.suspect.label) // the Settings reliability card names a phone-maker pause
        // A deferred verdict repeats every 30 s; log it once per distinct action.
        if (decision.action != lastLoggedStaleAction || decision.action == "requested" || decision.action == "logged_only") {
            scanLog(ScanHealth.line(inputs, decision))
        }
        lastLoggedStaleAction = decision.action
        if (decision.restart) renewal.renewNow("stale", force = true)
        if (ScanSwitches.softwareFilterFallback) maybeProbe(app, inputs, decision)
    }

    @SuppressLint("MissingPermission")
    private fun maybeProbe(app: Context, inputs: ScanHealthInputs, decision: ScanHealthDecision.Stale) {
        if (probeRunning) return
        val step = ScanFilterFallback.nextStep(
            enabled = true,
            stage = if (ScanFilterFallback.softwareFiltering()) ScanFilterStage.SOFTWARE else ScanFilterStage.FILTERED,
            screenOn = inputs.screenOn,
            staleWithUnexplainedSilence = decision.suspect == ScanSuspect.NONE,
            probe = null,
        )
        if (step != ScanFilterFallback.Step.PROBE) return
        val scanner = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return
        val callbackHolder = arrayOfNulls<ScanCallback>(1)
        val probe = UnfilteredScanProbe(
            gate = ScanStartGate.shared,
            budget = ::budget,
            timer = timer,
            start = { onResult ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) = onResult()
                }
                callbackHolder[0] = callback
                scanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
                true
            },
            stop = { callbackHolder[0]?.let { runCatching { scanner.stopScan(it) } } },
        )
        probeRunning = true
        probe.run { sawAdverts ->
            probeRunning = false
            scanLog("scan_filter: event=probe | sawAdverts=$sawAdverts")
            val next = ScanFilterFallback.nextStep(
                enabled = true,
                stage = ScanFilterStage.FILTERED,
                screenOn = true,
                staleWithUnexplainedSilence = true,
                probe = sawAdverts,
            )
            if (next == ScanFilterFallback.Step.SWITCH_TO_SOFTWARE) {
                ScanFilterFallback.setStage(ScanFilterStage.SOFTWARE)
                scanLog("scan_filter: event=software_filter | restart=through_scan_start_gate")
                renewal.renewNow("filter_stage", force = true)
            }
        }
    }
}
