package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/** Diagnostic or guidance effect of a scan entry: the registry names it, the code lives in the scan files. */
internal data class ScanBehaviourEffect(val text: String) : DevicePolicyEffect {
    override fun describe(): String = text
}

/**
 * Registry entries of batch S (BLE scanning and adapter state). Exposed as [entries] for the one-line
 * concatenation in DevicePolicyRegistry.entries; this file never reads that registry. Entries that
 * ship ON are active; Experimental arms (default off, a `test_scan_*` launch extra) are listed as
 * `proposed` so the registry never reports them active, and their switch is in [ScanSwitches].
 * Runtime code checks [isOff] directly, so an entry's off-switch works whether or not the root has
 * added this list to the aggregate yet.
 */
internal object ScanPolicy {
    private const val REVIEWED = "2026-10-14"
    private const val A10_BT = "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android10-release"
    private const val A11_BT = "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android11-release"
    private const val A13_BT = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-release"
    private const val A14_BT = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release"

    /**
     * Takes a scan-start slot on a worker thread, sleeping through the gate's wait (probes). False when the wait would
     * exceed [maxWaitMs] (nothing recorded). A null budget (entry switched off) takes no slot.
     */
    fun reserveBlocking(
        gate: ScanStartGate,
        budget: LeScanStartBudget?,
        maxWaitMs: Long = 35_000L,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Boolean {
        var waited = 0L
        while (true) {
            val wait = gate.reserve(budget)
            if (wait <= 0L) return true
            if (waited + wait > maxWaitMs) return false
            sleep(wait)
            waited += wait
        }
    }

    /** True when the entry [id] (or "all") is in the DEBUG `test_device_policy_off` set. */
    fun isOff(id: String, switchedOff: Set<String>): Boolean = "all" in switchedOff || id in switchedOff

    /** Runtime form of [isOff] against the launch extra. */
    fun enabled(id: String): Boolean = !isOff(id, DebugTunables.devicePolicyOff)

    val LOCATION_SWITCH_GATE = DevicePolicyEntry(
        id = ScanEnvironment.POLICY_ID,
        title = "Android 10-11: scan results need the Location switch on",
        matcher = DeviceMatcher(sdkMin = 29, sdkMax = 30),
        effects = listOf(
            ScanBehaviourEffect(
                "log scan_env (location switch, permission, SDK) when a discovery scan starts; report the " +
                    "blocked state to the reliability banner; restart discovery through the scan-start gate when the switch turns on",
            ),
        ),
        behaviour = "Android 10 and 11 drop every BLE scan result when the Location switch is off, without any " +
            "error (the scan 'starts' and stays empty). The app checks the switch (runtime, not a model list), logs " +
            "`scan_env`, tells the reliability banner to say 'Turn on Location so Android lets this phone find " +
            "nearby phones (location is not recorded)' with a link to Location settings, and when the switch " +
            "turns on restarts discovery through the scan-start gate (5 starts per 30 s). Android 12+ is not " +
            "affected: BLUETOOTH_SCAN is declared neverForLocation. No background-location permission is requested.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A10_BT/src/com/android/bluetooth/Utils.java#368",
                "checkCallerHasFineLocation: blockedByLocationOff() ('Permission denial: Location is off') is the first test"),
            PolicySource(AOSP_SOURCE, "$A10_BT/src/com/android/bluetooth/gatt/ScanManager.java#325",
                "Cannot start unfiltered scan in location-off; MODE_CHANGED_ACTION receiver restarts suspended scans"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/connectivity/bluetooth/bt-permissions",
                "On Android 11 and lower a Bluetooth scan could be used to gather the user's location; Android 12 adds neverForLocation"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no saved run switched Location off on an Android 10-11 phone"),
        confidence = HIGH,
        confidenceNote = "AOSP code read for Android 10; Android 11 keeps the check. OEM changes unknown. Not phone-tested: " +
            "A15s (Android 10) and ASUS (Android 11) with Location off should show the banner and zero results.",
        reviewed = REVIEWED,
        origin = "Backlog S-01 (device-family research 2026-10-14): only OfflineAppShareActivity checked the Location switch.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Discovery scans run unchanged; with Location off they return nothing silently, as before.",
    )

    val SCAN_SILENCE_WATCHDOG = DevicePolicyEntry(
        id = "scan-silence-watchdog",
        title = "Log a scan that goes silent while a peer is expected",
        matcher = DeviceMatcher(),
        effects = listOf(
            ScanBehaviourEffect(
                "every 30 s: if a continuous scan has been silent for 120 s (doubling to 10 min) and a peer was seen " +
                    "within the last hour, log scan_stale with the suspected cause",
            ),
        ),
        behaviour = "Android can stop delivering scan results without any onScanFailed: Doze, background throttling, " +
            "the scan-timeout downgrade, Location off on Android 10-11, a phone maker's power manager. The watchdog " +
            "(bitchat's model: 30 s check, 120 s stale) logs `BENCHMARK scan_stale` with the suspected cause " +
            "(location_off, doze, timeout_window, oem_pause, background, none). A restart is a separate Experimental " +
            "arm (`test_scan_stale_restart`): one restart through the scan-start gate, thresholds doubling to 10 min. The stack drops a " +
            "scan start over its 5-per-30-s quota WITHOUT any onScanFailed (aosp-bt-versions SCAN-01), so a silent scan with a full " +
            "start budget is named `scan_quota_silent`. The address rotation every 7-15 min (Android 11+) pauses scanning for milliseconds " +
            "only and the heartbeat is any matching result whatever its address, so it cannot trip the 120 s threshold.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/gatt/ScanManager.java#1341",
                "regularScanTimeout: a filtered scan client is moved to a downgraded (LOW_POWER) scan, an unfiltered one to opportunistic"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/os/PowerManager#isDeviceIdleMode()",
                "isDeviceIdleMode: Doze; the cause classification reads it"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/training/monitoring-device-state/doze-standby",
                "Doze and App Standby defer background work and network access"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no saved run recorded a silent scan with a peer in range"),
        confidence = MEDIUM,
        confidenceNote = "The causes are documented; the thresholds are bitchat's (30 s / 120 s), not measured limits. " +
            "Whether a restart helps is unproven, hence the separate default-off arm.",
        reviewed = REVIEWED,
        origin = "Backlog S-04: restarts happened only on a schedule (R19) and on onScanFailed, never on result silence.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "No scan_stale line; scans restart only on schedule and failure, as before.",
    )

    val BLUETOOTH_PROCESS_RESTART_RECOVERY = DevicePolicyEntry(
        id = "bluetooth-process-restart-recovery",
        title = "Rebuild BLE scanners after Bluetooth off/on or a Bluetooth process restart",
        matcher = DeviceMatcher(),
        effects = listOf(
            ScanBehaviourEffect(
                "track the adapter state (hidden BLE_ON states tolerated), pause scanners when it goes off, restart them " +
                    "through the scan-start gate 500 ms+ after STATE_ON with jitter and back-off, count an epoch",
            ),
        ),
        behaviour = "When the adapter turns off or the Bluetooth process restarts, every native scanner, advertising set, " +
            "GATT server handle and L2CAP PSM is gone while the Java wrappers stay alive and silent. The app counts a " +
            "Bluetooth epoch (one per off -> on episode), pauses its scanners at off and restarts them after on, " +
            "through the scan-start gate; other owners register their own rebuild. Ships ON because a stopped scan is already " +
            "dead. A crashed Bluetooth process is restarted by the system after 400 ms x (retry + 1), at most 6 times in a row, then stays " +
            "off; three off episodes in a minute are reported as a suspected crash loop. This entry documents the source; the code is " +
            "generic and its off-switch is this id.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/framework/java/android/bluetooth/le/BluetoothLeUtils.java#170",
                "checkAdapterStateOn throws IllegalStateException unless the adapter is ON or BLE_ON"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#ACTION_STATE_CHANGED",
                "ACTION_STATE_CHANGED: STATE_OFF, TURNING_ON, ON, TURNING_OFF"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no saved run toggled Bluetooth during an active scan"),
        confidence = MEDIUM,
        confidenceNote = "State machine is documented; the exact broadcast order for BLE_ON varies by Android version and is tolerated, " +
            "not relied on. Not phone-tested.",
        reviewed = REVIEWED,
        origin = "Backlog S-08: CocAutoGatt reset only CoC registration counts on adapter state; scanners were never rebuilt.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "A scan stopped by the adapter turning off stays stopped until its owner restarts it.",
    )

    val ANDROID10_SCANNER_LEAK = DevicePolicyEntry(
        id = "android10-scanner-leak-on-process-death",
        title = "Android 10-11: stop scans before the process exits; count unclean exits",
        matcher = DeviceMatcher(sdkMin = 29, sdkMax = 30),
        effects = listOf(
            ScanBehaviourEffect(
                "write a 'scanner registered' marker while any app scanner is registered, count markers found at next start, " +
                    "classify scan error 2 / GATT 257 as the Android 10 scanner leak",
            ),
        ),
        behaviour = "On stock Android 10 a scanner whose app process dies is never unregistered (the death recipient " +
            "marks a copy of the client), draining the shared 32-slot pool until `startScan` fails with code 2 and " +
            "connects report 257 for every app. The app stops all scanners in the service teardown (every SDK), " +
            "keeps a marker file while a scanner is registered, counts unclean exits at the next start, and names " +
            "scan error 2 / GATT 257 on SDK 29-30 as this leak in diagnostics (SDK 30 measured on the ASUS, see evidence). A one-tap Bluetooth restart is a design decision (Q-N1), not part of " +
            "this entry, and BluetoothAdapter.enable()/disable() always fail for apps targeting API 33+ (this app targets 36), so " +
            "a restart can only be a user toggle in Settings.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/8471db45665399101279b23a827b1681bb52a810",
                "Fix scanner leak when the app dies (b/143588356), Android 11"),
            PolicySource(AOSP_SOURCE, "$A10_BT/src/com/android/bluetooth/gatt/GattService.java#330",
                "ScannerDeathRecipient.binderDied stops a new ScanClient copy; the registered client's appDied stays false"),
            PolicySource(AOSP_SOURCE, "$A11_BT/src/com/android/bluetooth/gatt/GattService.java#350",
                "android11-release looks the real client up (getScanClient)"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf(
            "lane11 ASUS (Android 11, SDK 30, patch 2021-06-01): `dumpsys bluetooth_manager` GATT Scanner Map lists 25 'Ongoing scans' for " +
                "io.github.meshlive.app (1583 started / 1558 stopped) back to 10-12 and the 32-slot pool full (`MAX client reached: 32`); the three " +
                "ongoing starts inside the saved logs match, to the second, the first scan of a process the lane driver force-killed " +
                "(benchmarks/runs/lane11-stats-20261015/m-asus-btmgr). Intermittent: 3 of about 77 force-killed processes in the saved lane11 ASUS logs leaked.",
        ),
        confidence = HIGH,
        confidenceNote = "AOSP code read for stock Android 10 (fixed in the Android 11 source); measured on an Android 11 ASUS build whose stack still " +
            "leaked scanner registrations of force-killed processes, so the scope covers SDK 29 and 30. OEM backports unknown. Intermittent, " +
            "not every kill leaks.",
        reviewed = REVIEWED,
        origin = "Backlog S-09: no onTaskRemoved, scans were not stopped in service teardown.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No marker, no classification; scans are still stopped by their owners' close().",
    )

    val SCAN_TIMEOUT_RENEWAL = DevicePolicyEntry(
        id = "le-scan-timeout-renewal",
        title = "Stop all app scanners together before the stack's scan-timeout downgrade (Experimental)",
        matcher = DeviceMatcher(),
        effects = listOf(
            ScanBehaviourEffect(
                "every 8 min (Android 14+) / 25 min (Android 10-13): stop every registered scanner for >= 1 s, then restart " +
                    "them through the scan-start gate; deferred while a call discovery or dial is in flight",
            ),
        ),
        behaviour = "AOSP measures a scan as 'too long' from the app's first running scan (AppScanStats.mScanStartTime resets " +
            "only when the app had no scan), so restarting one scanner does not reset the clock while another runs. " +
            "Renewal stops all of them together. Experimental, default off: `test_scan_renewal_all=true`.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/gatt/AppScanStats.java#265",
                "if (!isScanning()) mScanStartTime = startTime: the per-app clock restarts only after no scan"),
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/btservice/AdapterService.java#6777",
                "default scan timeout 10 min on Android 14+"),
            PolicySource(AOSP_SOURCE, "$A13_BT/android/app/src/com/android/bluetooth/btservice/AdapterService.java#5288",
                "default scan timeout 30 min on Android 13"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no saved run measured peer sightings per minute past minute 10 of a continuous scan"),
        confidence = LOW,
        confidenceNote = "High on the mechanism (AOSP code), low on the effect size: no phone has shown the rate drop yet.",
        reviewed = REVIEWED,
        origin = "Backlog S-03: BleL2capDiscovery, the probes and the stream scanners each own a scan; nothing renewed them together.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Only the relay helper's own scanner restarts (SDK-aware interval); other scanners run unchanged.",
    )

    val SCAN_HARDWARE_FILTER_FALLBACK = DevicePolicyEntry(
        id = "scan-hardware-filter-fallback",
        title = "Filtered scan yields nothing: software-filter fallback while the screen is on (Experimental)",
        matcher = DeviceMatcher(),
        effects = listOf(
            ScanBehaviourEffect(
                "after a stale verdict with the screen on and no other cause, an unfiltered 3 s probe; if advertisements " +
                    "arrive, discovery scanners use an empty filter and match the service UUID in software until the screen turns off",
            ),
        ),
        behaviour = "Some controllers accept an offloaded filter and never report a match. The fallback is runtime-triggered " +
            "(never by model) and reverts to the filtered scan when the screen turns off. Experimental, default off: " +
            "`test_scan_sw_filter_fallback=true`. The platform has no public 'hardware filtering off' setting.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isOffloadedFilteringSupported()",
                "isOffloadedFilteringSupported: a hint only; support is reported, not proven to deliver"),
            PolicySource(AOSP_SOURCE, "$A10_BT/src/com/android/bluetooth/gatt/ScanManager.java#1054",
                "shouldUseAllPassFilter: more filters than free hardware slots makes the stack scan all-pass"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no owned phone showed a filtered scan returning nothing"),
        confidence = MEDIUM,
        confidenceNote = "Field reports (Scanner-Compat #79 Samsung A10/A50, #16 Huawei P8 Lite) are library-tracker evidence; no official statement.",
        reviewed = REVIEWED,
        origin = "Backlog S-06: ScanFailurePolicy handles failures, not a scan that runs and reports nothing.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "The filtered scan keeps running (silently empty if the controller is broken).",
    )

    val SCAN_PAUSE_DURING_CONNECT = DevicePolicyEntry(
        id = "scan-pause-during-connect",
        title = "Pause discovery scans while the app dials a link (Experimental)",
        matcher = DeviceMatcher(),
        effects = listOf(
            ScanBehaviourEffect("ScanGate.pauseFor(ms) stops continuous scanners for the dial window and restarts them through the gate"),
        ),
        behaviour = "Scanning while a connection is established competes for the radio; Android 14+ already downgrades " +
            "LOW_LATENCY to BALANCED for about 6 s during connects. The diagnostic half is always on: discovery statistics windows " +
            "that overlap an own connect attempt are tagged. The pause itself is Experimental, default off: `test_scan_pause_during_connect=true`.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/gatt/ScanManager.java#1341",
                "the stack downgrades scan intensity itself during connection establishment"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no A/B of child establish rate with and without a concurrent scan"),
        confidence = LOW,
        confidenceNote = "RxAndroidBle FAQ and controller scan-window reasoning are old and mixed; the A14+ stack already mitigates.",
        reviewed = REVIEWED,
        origin = "Backlog S-07: discovery kept scanning during dials.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Discovery keeps scanning during dials.",
    )

    val HYPEROS_DEEPDOZE_SCAN_BLOCK = DevicePolicyEntry(
        id = "hyperos-deepdoze-scan-block",
        title = "Xiaomi/Redmi/POCO: background scans may deliver nothing in Deep Doze (diagnostic)",
        matcher = DeviceMatcher(manufacturers = setOf("xiaomi", "redmi", "poco")),
        effects = listOf(ScanBehaviourEffect("no effect: the scan_stale line names oem_pause or doze; the banner text belongs to the reliability batch")),
        behaviour = "A MIUI/HyperOS ScanManager logs 'is Deep Doze, non-system software won't start scan!'; a scan from a " +
            "foreground service then delivers zero results. Detect, do not assume: screen off + device idle + zero results " +
            "-> the watchdog's suspected cause. No behaviour change.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/training/monitoring-device-state/doze-standby",
                "Doze defers background work; vendor Deep Doze is a stricter, undocumented variant"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: Nordic DevZone thread 129338 reports it for a Xiaomi phone; no local run"),
        confidence = LOW,
        confidenceNote = "One forum report; cause unresolved even there. Never claim relay survival in Deep Doze on Xiaomi.",
        reviewed = REVIEWED,
        origin = "Backlog S-04 (oem-bt.md A2).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Nothing changes.",
    )

    val OEM_SCREENOFF_SCAN_PAUSE = DevicePolicyEntry(
        id = "oem-screenoff-scan-pause-vivo-poco-oppo",
        title = "vivo/iQOO/OPPO/Xiaomi: scan may pause with the screen off (diagnostic)",
        matcher = DeviceMatcher(manufacturers = setOf("vivo", "iqoo", "oppo", "xiaomi", "poco")),
        effects = listOf(ScanBehaviourEffect("no effect: the scan_stale line names oem_pause; guidance text belongs to the reliability batch")),
        behaviour = "Reports (react-native-ble-plx #1172, Android 15) say scanning pauses with the screen off and resumes at " +
            "screen on even with a foreground service and battery optimisation off; vivo's 'Background power consumption' " +
            "setting fixed it. Diagnostic only here.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/training/monitoring-device-state/doze-standby",
                "vendor power managers sit above Android's own background rules"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: single issue-tracker report; no local run"),
        confidence = LOW,
        confidenceNote = "One report, consistent with Don't Kill My App (community) for vivo; unverified.",
        reviewed = REVIEWED,
        origin = "Backlog S-04 (oem-bt.md S2-04).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Nothing changes.",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        LOCATION_SWITCH_GATE,
        SCAN_SILENCE_WATCHDOG,
        BLUETOOTH_PROCESS_RESTART_RECOVERY,
        ANDROID10_SCANNER_LEAK,
        SCAN_TIMEOUT_RENEWAL,
        SCAN_HARDWARE_FILTER_FALLBACK,
        SCAN_PAUSE_DURING_CONNECT,
        HYPEROS_DEEPDOZE_SCAN_BLOCK,
        OEM_SCREENOFF_SCAN_PAUSE,
    )
}
