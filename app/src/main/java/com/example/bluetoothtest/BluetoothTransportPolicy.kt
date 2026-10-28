package com.example.bluetoothtest

import android.os.SystemClock
import android.util.Log
import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.BLUETOOTH_SPEC
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL
import java.util.Locale

/**
 * Bluetooth transport policy (GATT op queueing, MTU, PHY, CoC, scanning). Owner: Bluetooth workstream.
 *
 * Entries here join DevicePolicyRegistry.entries (one registry). Use DevicePolicyRegistry matchers
 * and constants freely, but never DevicePolicyRegistry.entries (initialisation order).
 *
 * Audit 2026-10-10 against Nordic Android-BLE-Library 4a86e6c6 and AOSP packages/modules/Bluetooth:
 * the GATT clients already serialise setup operations through callbacks (MTU -> discovery -> CCCD ->
 * PHY), use autoConnect=false with TRANSPORT_LE, initiate on LE 1M, close before a retry and wait
 * 500 ms (Nordic waits 200 ms), refresh the service cache only when a service is missing, and bound
 * every setup stage by the 6/12 s attempt deadline plus a 3 s MTU watchdog. The rules below cover
 * what the audit found missing. Every implemented entry is on by default; DEBUG
 * `test_device_policy_off=<id>` turns one off. Proposals are inert until a phone A/B.
 */
internal object BluetoothTransportPolicy {
    private const val REVIEWED = "2026-10-10"
    private const val NORDIC =
        "https://github.com/NordicSemiconductor/Android-BLE-Library/blob/4a86e6c6b2631a29d7de6c5789b11326733f1503"
    private const val A14_BT = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release"
    private const val A10_BT = "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android10-release"
    const val GATT_ERROR_133 = 133

    val LE_SCAN_QUOTA = DevicePolicyEntry(
        id = "le-scan-start-quota",
        title = "LE scan start quota (5 starts per 30 s)",
        matcher = DeviceMatcher(),
        effects = listOf(LeScanStartBudget(maxStarts = 5, windowMs = 30_000L)),
        behaviour = "Every BLE scan start (including the low-latency -> balanced switch) takes a slot from a " +
            "budget of 5 per rolling 30 s. A start that would be the 6th waits until the oldest slot expires " +
            "instead of being refused, and the wait is shown to the user and in the background status. A " +
            "balanced switch without a free slot keeps the low-latency scan (no queued restart), so slots stay " +
            "free for new discovery such as calls. The stack counts per app UID in the Bluetooth process, so " +
            "the count survives our process restarts: starts are persisted (elapsedRealtime) and seed the gate " +
            "at process start, keyed by Settings.Global.BOOT_COUNT so a reboot never seeds stale starts.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/gatt/GattService.java#3135",
                "startScan: isScanningTooFrequently() -> the scan is not started, silently: no onScanFailed(6) is delivered on any release (aosp-bt-versions SCAN-01)"),
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/gatt/AppScanStats.java#507",
                "isScanningTooFrequently: the last getScanQuotaCount() starts all inside getScanQuotaWindowMillis()"),
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/btservice/AdapterService.java#6777",
                "DEFAULT_SCAN_QUOTA_COUNT = 5, DEFAULT_SCAN_QUOTA_WINDOW_MILLIS = 30 s (DeviceConfig may change them)"),
            PolicySource(AOSP_SOURCE, "$A10_BT/src/com/android/bluetooth/gatt/AppScanStats.java#82",
                "Android 10: NUM_SCAN_DURATIONS_KEPT = 5, EXCESSIVE_SCANNING_PERIOD_MS = 30 s"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf(
            "benchmarks/runs/lane-a-matrix-529f36cc/0001-4b5ecaf7784b/physical/a15s-bluetooth-6297.log:147 " +
                "(\"App 'com.example.bluetoothtest' is scanning too frequently\" while an incoming-stream failure loop " +
                "restarted discovery every ~1.5 s; 7 such lines in lane-a-matrix-529f36cc on the A15s and the S24; the refusal " +
                "is a stack log line, not a scan callback)",
        ),
        confidence = HIGH,
        confidenceNote = "The refusal is reproduced on Android 10 and 14 phones; that the budget removes it is not phone-tested.",
        reviewed = REVIEWED,
        origin = "Audit 2026-10-10: BleL2capDiscovery restarted scans without counting starts.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        fallback = "Scans start immediately; a 6th start inside 30 s is dropped by the stack without any callback (no onScanFailed(6) on any release), so discovery goes quiet and only the app's own start budget protects it.",
    )

    val GATT_MTU_FIXED_517 = DevicePolicyEntry(
        id = "gatt-mtu-fixed-517",
        title = "Android 14+ requests ATT MTU 517 on the first exchange",
        matcher = DeviceMatcher(sdkMin = 34),
        effects = listOf(GattMtuStackForced(mtu = 517)),
        behaviour = "Diagnostics only: the LE link line reports the requested MTU, the 517 the stack actually sends and " +
            "the effective MTU; an experimental `test_gatt_request_mtu` below 517 is flagged as ignored by the stack.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/about/versions/14/behavior-changes-all#mtu-set-to-517",
                "Android 14 requests MTU 517 for the first requestMtu on an ACL and disregards later requests"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/stack/gatt/gatt_api.cc#722",
                "GATTC_ConfigureMTU sends GATT_MAX_MTU_SIZE whatever the app asked"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/bta/gatt/bta_gattc_act.cc#743",
                "bta_gattc_cfg_mtu: an exchange already done or in progress is reported back with the current MTU"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/stack/gatt/gatt_api.cc#820",
                "GATTC_UpdateUserAttMtuIfNeeded: LE Data Length 251 is set only after an ATT MTU exchange, from the first caller's " +
                    "requested value on Android 14+ (so a small first request caps the data length); a settle delay delays both"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: saved runs never set test_gatt_request_mtu below 517 on an Android 14+ client"),
        confidence = HIGH,
        reviewed = REVIEWED,
        origin = "Audit 2026-10-10: the requested-vs-effective MTU was not recorded.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The link line reports only the requested and effective MTU.",
    )

    val GATT_WRITE_BUSY_RETRY = DevicePolicyEntry(
        id = "gatt-write-busy-retry",
        title = "Retry a GATT write the stack reports busy",
        matcher = DeviceMatcher(sdkMin = 33),
        effects = listOf(GattWriteBusyRetry(maxWaitMs = 500L, stepMs = GattWriteBusyPolicy.LEGACY_STEP_MS)),
        behaviour = "When writeCharacteristic returns ERROR_GATT_WRITE_REQUEST_BUSY (201) the same write is retried " +
            "every 2 ms for at most 500 ms before the link fails as before. Nothing is dropped or reordered. The " +
            "Experimental arm test_gatt_busy_wedge=recover (default legacy, root review) retries every 25 ms (the framework " +
            "has already retried 5 x 10 ms inside the call) and, on Android 13-14, ends the link at once on a busy answer " +
            "while MeshLive has nothing in flight on that client (android13-14-gatt-client-wedged-after-busy).",
        sources = listOf(
            PolicySource(API_REFERENCE,
                "https://developer.android.com/reference/android/bluetooth/BluetoothStatusCodes#ERROR_GATT_WRITE_REQUEST_BUSY",
                "API 33+: writeCharacteristic returns ERROR_GATT_WRITE_REQUEST_BUSY when a write is already in flight"),
            PolicySource(AOSP_SOURCE, "$A14_BT/android/app/src/com/android/bluetooth/gatt/GattService.java#3896",
                "one write permit per remote address, shared by every client and app; released in onWriteCharacteristic"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no 'write queue rejected' line in saved runs; upstream-documented, not locally tested"),
        confidence = MEDIUM,
        confidenceNote = "Mechanism is AOSP source; it only matters when another client or app writes to the same peer. " +
            "A 25 ms step (backlog G-03; BluetoothGatt.java already retries 5 x 10 ms) is the Experimental test_gatt_busy_wedge=recover arm; the shipped step stays 2 ms.",
        reviewed = REVIEWED,
        origin = "Audit 2026-10-10: a busy return failed the whole GATT link at once.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "A rejected write closes the GATT link (\"GATT video write queue rejected a packet\").",
    )

    val LE_CONNECT_RETRY_STATUSES = DevicePolicyEntry(
        id = "le-connect-retry-link-statuses",
        title = "Retry LE connect failures beyond status 133",
        matcher = DeviceMatcher(),
        effects = listOf(LeConnectRetryStatuses(setOf(8, 34, 62, 133, 147))),
        behaviour = "A GATT video or CoC control attempt that fails at connection with a link-layer status (0x08 " +
            "supervision timeout, 0x22 LL response timeout, 0x3E failed to establish, 133 GATT_ERROR, 147 " +
            "GATT_CONNECTION_TIMEOUT) uses the existing bounded retry; the statuses other than 133 retry at most " +
            "once, and no retry starts unless a whole further attempt fits the caller's setup budget (a private " +
            "call's 15 s early-loss window, so its 2M -> 1M setup retry and bad-2M memory still happen). Peer or " +
            "local terminations (0x13, 0x16) and admission refusals are never retried.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC/ble/src/main/java/no/nordicsemi/android/ble/BleManagerHandler.java#L2264-L2267",
                "Nordic Android-BLE-Library retries any non-success connect status within 20 s of the request (close, wait, reconnect)"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/stack/include/gatt_api.h#270",
                "tGATT_DISCONN_REASON: GATT_CONN_TIMEOUT 0x08, GATT_CONN_LMP_TIMEOUT 0x22, GATT_CONN_FAILED_ESTABLISHMENT 0x3E"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/bta/gatt/bta_gattc_act.cc#358",
                "bta_gattc_open_fail reports a failed open as GATT_ERROR (133)"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothGatt#GATT_CONNECTION_TIMEOUT",
                "API 35: GATT_CONNECTION_TIMEOUT (0x93) for a connection attempt that timed out"),
            PolicySource(BLUETOOTH_SPEC, "https://www.bluetooth.com/specifications/specs/core-specification-5-4/",
                "Core v5.4 Vol 1 Part F 2.62: 0x3E Connection Failed to be Established"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "benchmarks/runs/mesh4-041da09f/0005-ca14685ab021/physical/m31-bluetooth-8990.log:6103-6117 " +
                "(M31 -> ASUS: HCI 0x3E at LE connection setup surfaces as 133; 16 attempts in 30 s before success)",
            "benchmarks/runs (all saved app logs, 2026-10-10 scan): 66 of 85 GATT and 80 of 98 CoC-control attempts after a " +
                "133 retry succeeded; one 'BLE control connection failed (status 8)' was not retried",
        ),
        confidence = MEDIUM,
        confidenceNote = "133 retries are measured; the added statuses follow Nordic and AOSP but are not phone-tested here.",
        reviewed = REVIEWED,
        origin = "Audit 2026-10-10: only the literal status 133 was retryable.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Only status 133 (and the MTU/read stall) is retried.",
    )

    // --- Proposals: sourced, inert until a phone A/B and maintainer review -------------------

    val ANDROID16_BOND_LOSS_DIALOG = DevicePolicyEntry(
        id = "android16-bond-loss-dialog",
        title = "Android 16 keeps the bond and shows a re-pair dialog on bond loss",
        matcher = DeviceMatcher(sdkMin = 36),
        effects = emptyList(),
        behaviour = "Proposal: when a secure (bonded) Classic connect fails authentication, explain that Android " +
            "shows its own re-pair dialog and offer Classic without pairing.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/about/versions/16/behavior-changes-all",
                "'Improved bond loss handling': disconnect, keep the local bond, show a system re-pair dialog"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no Android 16 phone in the required set"),
        confidence = MEDIUM,
        reviewed = REVIEWED,
        origin = "Research 2026-10-10 (Qaul/Berty/Knit/MeshChat patterns); the Classic path needs an auth-failure hook first.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The Classic connect fails with the stack's message.",
    )

    val SAMSUNG_DIRECT_CONNECT_TIMEOUT = DevicePolicyEntry(
        id = "samsung-gatt-direct-connect-5s",
        title = "Samsung direct GATT connect may give up after about 5 s (no AOSP basis: classify by duration)",
        matcher = DeviceMatcher(manufacturers = setOf("samsung")),
        effects = emptyList(),
        behaviour = "Proposal (diagnostic), reclassified 2026-10-14: AOSP has no 5 s direct-connect timer; its timers are " +
            "30 s (L2CAP_BLE_LINK_CONNECT_TIMEOUT_MS and the GD create-connection timeout, ending in 133, or 147 from " +
            "Android 14 QPR3). A failure at about 5 s on Samsung would be an OEM stack change. Every failed GATT connect " +
            "now logs its duration class (gatt_connect_failure_class: fast 0x3E, own deadline, stack 30 s, other) on every " +
            "phone, so a Samsung-only 5 s cluster shows up as data. Our 6/12 s attempt deadlines and retries own the timeout.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/3615d8fa04",
                "Add GATT_CONNECTION_TIMEOUT and change connection timeout flow (2024-01-19): the stack's direct connect ends after 30 s"),
            PolicySource(VENDOR_DOC,
                "https://github.com/A-ElMahmi/BlueMesh/blob/4cfd94a9afc4/blessed/src/main/java/com/welie/blessed/BluetoothPeripheral.kt#L1518-L1519",
                "blessed library comment (third party, not Samsung): Samsung connectGatt(autoConnect=false) times out after ~5 s"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: saved Samsung 133s are 0x3E failures 0.4-1 s after the request, not 5 s timeouts"),
        confidence = LOW,
        confidenceNote = "The only 5 s source is a third-party library comment; aosp-bt-versions CONN-04 finds no AOSP 5 s timer.",
        reviewed = REVIEWED,
        origin = "Research 2026-10-10 (Qaul/Berty/Knit/MeshChat patterns).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Every 133 is treated alike.",
    )

    val ANDROID15_COC_STALL = DevicePolicyEntry(
        id = "android15-qcom-coc-connect-stall",
        title = "Android 15 Qualcomm LE CoC connect can stall 30 s (superseded by coc-connect-bounded; the 30 s premise is wrong)",
        matcher = DeviceMatcher(sdkMin = 35, socFamilies = setOf(SocFamily.QUALCOMM)),
        effects = emptyList(),
        behaviour = "Superseded by coc-connect-bounded (implemented: the LE CoC connect is bounded at 12 s on every SDK and " +
            "falls back to the existing retry/auto-GATT path). The 30 s premise is wrong: AOSP's own L2CAP channel-connect timer is 60 s " +
            "(L2CAP_CHNL_CONNECT_TIMEOUT_MS) and no 30 s CoC timer exists (aosp-bt-versions COC-04); the bound is still sound.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothSocket#connect()",
                "connect() blocks until connected or failed; close() from another thread aborts it"),
            PolicySource(VENDOR_DOC, "https://forum.fairphone.com/t/bluetooth-problems-since-android-15-update/",
                "Fairphone 4 (Qualcomm) on Android 15: LE CoC secure channel times out at 30 s; Google issue 371713238 not readable"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no Android 15 Qualcomm phone in the required set; long CoC connects not measured yet"),
        confidence = LOW,
        reviewed = REVIEWED,
        origin = "Research 2026-10-10 plus audit finding (unbounded CoC connect).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The CoC connect waits for the stack's own timeout.",
    )

    val ANDROID12_DISCONNECT_STATUS_ZERO = DevicePolicyEntry(
        id = "android12-gatt-disconnect-status-zero",
        title = "Android 12 may report GATT disconnect status 0 for every cause",
        matcher = DeviceMatcher(sdkMin = 31, sdkMax = 31),
        effects = emptyList(),
        behaviour = "Proposal: on Android 12 classify a setup disconnect from our own state (stage, elapsed time) " +
            "instead of the callback status.",
        sources = listOf(
            PolicySource(API_REFERENCE,
                "https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt,%20int,%20int)",
                "status is the operation status; Google issues 174678306/207323675/233239417 report 0 for all causes (not readable)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: the M31 (Android 12) reports 133 and 8 in saved logs, so it does not show this"),
        confidence = LOW,
        reviewed = REVIEWED,
        origin = "Research 2026-10-10 (Qaul/Berty/Knit/MeshChat patterns).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Setup failures are classified by status.",
    )

    val EXYNOS9611_DUAL_ROLE_EQUAL_INTERVAL = DevicePolicyEntry(
        id = "exynos9611-dual-role-equal-interval-child-starvation",
        title = "Exynos 9611 relay: a central child link at the parent's interval can get no events at all",
        matcher = DeviceMatcher(hardware = setOf("exynos9611")),
        effects = emptyList(),
        behaviour = "Proposal (diagnostic): a relay on this controller (peripheral to its parent, central to its child, " +
            "both HIGH = 15 ms) loses its child link in about half of the attempts: 22 of 55 never established (HCI 0x3E), " +
            "9 went silent for 5.0-5.1 s after the update to 15 ms (0x08; in the four diag cases the last completed packet " +
            "came 77-92 ms after the update), the rest ran starved or healthy. Every M31 15 ms stage with the child anchor " +
            "11.3-13.7 ms after the parent anchor was dead or starved (8 of 8); every one at 3.7-10.3 ms was healthy " +
            "except two CoC stages without a data-length update (6.1/6.6 ms, 17-29 kbps). The generic mitigations (relay_child_stall_guard, relay_child_reanchor, " +
            "relay_establish_fallback) apply to every phone; a child that is an A15s (MediaTek MT6765, Android 10) fails " +
            "alike (11 of 23 attempts, 2026-10-12): six of nine failed attempts left the child with no connect request at all, " +
            "three left both ends connected with no first anchor, and direct M31->A15s without a parent link lost about 2 of 28. " +
            "The Experimental test_relay_establish_budget retries these fast 0x3E failures in place for 12 s. " +
            "This entry only records the family so a later A/B " +
            "(test_relay_upstream_dck, test_gatt_request_mtu) can promote a family-specific rule, for example " +
            "a 30 ms parent link or a smaller data length for a relay child on this controller.",
        sources = listOf(
            PolicySource(AOSP_SOURCE,
                "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed/system/stack/l2cap/l2c_ble_conn_params.cc",
                "the stack locks 7.5 ms connection parameters while GATT service discovery runs and applies the app's " +
                    "pending request when it ends; every central here shows 45 -> 7.5 -> 15 ms (the HIGH range 11.25-15 ms " +
                    "resolves to 15 ms on the Exynos, Qualcomm and S24 controllers seen)"),
            PolicySource(AOSP_SOURCE,
                "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed/android/app/res/values/config.xml",
                "gatt_high_priority_min/max_interval 9/12 (11.25-15 ms), gatt_balanced 24/40 (30-50 ms), " +
                    "gatt_dck_priority 24/24 (30 ms); the app cannot ask for another interval (hidden " +
                    "BluetoothGatt.requestLeConnectionUpdate throws NoSuchMethodException, relaylink-arms2r1)"),
            PolicySource(API_REFERENCE,
                "https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestConnectionPriority(int)",
                "the public API offers BALANCED, HIGH, LOW_POWER and (API 34) DCK only"),
            PolicySource(AOSP_COMMIT,
                "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/d32b2d461",
                "Lower the BLE connection supervision timeout to 5s (2017): every requestConnectionPriority sends timeout = 500 (5 s) on " +
                    "Android 10+ (20 s on 8-9), so the '0x08 after 5.0-5.1 s of silence' above is this timer expiring, " +
                    "not a stack fault (aosp-bt-versions CONN-01); the link-silence line now names it"),
            PolicySource(VENDOR_DOC,
                "https://nrfconnectdocs.nordicsemi.com/ncs/latest/nrfxlib/softdevice_controller/doc/scheduling.html",
                "Nordic's controller (a different vendor): peripheral link timing is dictated by the peer central and may " +
                    "collide with any running event; central link events are placed relative to other central links only; " +
                    "a lower-priority event is blocked and rescheduled, and a peripheral connection about to time out gets " +
                    "the highest priority. The Exynos 9611 controller shows no such rescue (0x08 after 5.0 s of silence)"),
            PolicySource(BLUETOOTH_SPEC,
                "https://www.bluetooth.com/specifications/specs/core-specification-5-4/",
                "Core Vol 6 Part B 4.5 (connection state: events, supervision timer) and 5.1.1 (connection update: transmit " +
                    "window): two connections' overlapping events are left to the controller; no Samsung or Exynos document " +
                    "describes its scheduler"),
            PolicySource(VENDOR_DOC,
                "https://devzone.nordicsemi.com/f/nordic-q-a/129232/peripheral-connection-terminated-with-0x08-connection-timeout-after-163-ms-although-the-initial-supervision-timeout-is-720-ms",
                "A Nordic answer cites Core v6.3 Vol 6 Part B 4.5.2: a connection that receives no packet from the peer in " +
                    "its first six connection events is lost (0x3E; 6 x 45 ms = the 270 ms seen). Verified only through this " +
                    "secondary quote (the primary Core text was not fetched, 2026-10-12); a connect request the peer never " +
                    "received ends the same way"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "benchmarks/runs/relayfix-0725a213-diagM1 (0001: 2 deaths at 11.35/11.51 ms phase; 0002 CoC healthy at 10.28)",
            "benchmarks/runs/relayfix-0725a213-diagP1 (healthy 10.21 and 3.71), diagP2 (0001: four 0x3E; 0003: 2 deaths at 11.38/11.63)",
            "benchmarks/runs/mesh4ab-52681dbf-loop14b-1 (0002/0011/0012 deaths and starved stages at 11.98-13.73; 0001 healthy at 9.4)",
            "the ASUS (Qualcomm SM8150) as the dual-role relay: 29 child attempts, 5 x 0x3E, no 0x08",
            "benchmarks/runs/demo-f22f533d-relay, -relay2, -relay3 and demo-cf8752d1-q5 0007/0008/0011/0012/0018: the M31 relay's A15s child, 11 of 23 attempts 0x3E (relay2: 4 of 4, offer withdrawn after 3.5 s)",
        ),
        confidence = LOW,
        reviewed = "2026-10-11",
        origin = "Root-cause analysis 2026-10-11 of the M31 relay child link (HCI snoops, M31 + ASUS); one Exynos phone.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        fallback = "Relay child links use HIGH like every other pair; the generic guards recover (0.3 s for 0x3E, about 3 s for a stall).",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        LE_SCAN_QUOTA,
        GATT_MTU_FIXED_517,
        GATT_WRITE_BUSY_RETRY,
        LE_CONNECT_RETRY_STATUSES,
        ANDROID16_BOND_LOSS_DIALOG,
        SAMSUNG_DIRECT_CONNECT_TIMEOUT,
        ANDROID15_COC_STALL,
        ANDROID12_DISCONNECT_STATUS_ZERO,
        EXYNOS9611_DUAL_ROLE_EQUAL_INTERVAL,
    ) + GattLinkPolicy.entries

    private fun policy(): ResolvedDevicePolicy = DeviceFamilyPolicy.current()

    /** The active scan budget, or null when its entry is off. */
    fun scanBudget(resolved: ResolvedDevicePolicy = policy()): LeScanStartBudget? =
        resolved.effects<LeScanStartBudget>().firstOrNull()

    fun mtuStackForced(resolved: ResolvedDevicePolicy = policy()): Int? =
        resolved.effects<GattMtuStackForced>().firstOrNull()?.mtu

    fun writeBusyRetry(resolved: ResolvedDevicePolicy = policy()): GattWriteBusyRetry? =
        resolved.effects<GattWriteBusyRetry>().firstOrNull()

    /** Statuses a setup failure may be retried for: 133 always (the pre-audit rule), more with the entry. */
    fun retryableConnectStatuses(resolved: ResolvedDevicePolicy = policy()): Set<Int> =
        setOf(GATT_ERROR_133) + resolved.effects<LeConnectRetryStatuses>().flatMap { it.statuses }
}

/** Process-wide BLE scan-start budget (AOSP scan quota). */
internal data class LeScanStartBudget(val maxStarts: Int, val windowMs: Long) : DevicePolicyEffect {
    override fun describe(): String =
        "at most $maxStarts BLE scan starts per ${windowMs / 1_000} s (a further start waits instead of being refused)"
}

/** Diagnostics: the stack sends this MTU whatever the app requests. */
internal data class GattMtuStackForced(val mtu: Int) : DevicePolicyEffect {
    override fun describe(): String = "stack requests ATT MTU $mtu on the first exchange (reported in the LE link line)"
}

internal data class GattWriteBusyRetry(val maxWaitMs: Long, val stepMs: Long) : DevicePolicyEffect {
    override fun describe(): String = "retry a busy GATT write every $stepMs ms for up to $maxWaitMs ms"
}

internal data class LeConnectRetryStatuses(val statuses: Set<Int>) : DevicePolicyEffect {
    override fun describe(): String = "retry GATT connection-stage failures with status ${statuses.sorted()}"
}

/**
 * Says whether a GATT/CoC setup failure message "<prefix> ... (status N)" may use the bounded retry.
 * Only messages starting with one of [connectionPrefixes] (connection-stage failures) qualify.
 */
internal fun isRetryableLeSetupStatus(message: String?, retryable: Set<Int>, connectionPrefixes: List<String>): Boolean {
    if (message == null || connectionPrefixes.none { message.startsWith(it) }) return false
    val status = LE_STATUS_SUFFIX.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return status in retryable
}

private val LE_STATUS_SUFFIX = Regex("\\(status (\\d+)\\)$")

/**
 * Status line for a bounded setup retry: "<link> stack returned status N; <retrying>" (the pre-audit
 * 133 wording for every status), or "<failure>; <retrying>" for a stall without a status. It never
 * says "connection failed": tools/score_device_run.py counts that as an unexpected event, and a
 * retry that recovers is not one; a final failure is still reported by the caller.
 */
internal fun leSetupRetryNotice(link: String, message: String?, retrying: String): String =
    leSetupStatus(message)?.let { "$link stack returned status $it; $retrying" }
        ?: "${message ?: "$link setup stalled"}; $retrying"

/** Status code at the end of a GATT/CoC setup failure message "... (status N)", or null. */
internal fun leSetupStatus(message: String?): Int? =
    message?.let { LE_STATUS_SUFFIX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/**
 * Bounded LE setup retry (review 2026-10-10). A retry needs: an attempt left, a retryable failure,
 * at most ONE retry for the link-layer statuses added by le-connect-retry-link-statuses (8, 0x22,
 * 0x3E, 147: a supervision timeout alone costs seconds), and enough of the caller's setup budget for
 * a whole further attempt ([nextAttemptMs]; [remainingBudgetMs] is [SetupDeadline.ordinaryRemainingMs]). A private call's 2M -> 1M setup retry and its bad-2M
 * memory only happen inside AdaptivePhyMemory.EARLY_LOSS_MS, so inner retries must not consume it.
 */
internal fun leSetupRetryAllowed(
    message: String?,
    attemptsUsed: Int,
    maxAttempts: Int,
    classifiedRetryable: Boolean,
    extendedRetriesUsed: Int,
    nextAttemptMs: Long,
    remainingBudgetMs: Long?,
): Boolean {
    if (attemptsUsed >= maxAttempts || !classifiedRetryable) return false
    val status = leSetupStatus(message)
    val extended = status != null && status != BluetoothTransportPolicy.GATT_ERROR_133
    if (extended && extendedRetriesUsed >= MAX_EXTENDED_STATUS_RETRIES) return false
    return remainingBudgetMs == null || remainingBudgetMs >= nextAttemptMs
}

internal const val MAX_EXTENDED_STATUS_RETRIES = 1

/** Persisted scan starts (elapsedRealtime), so a restarted process still sees the stack's per-UID count. */
internal interface ScanStartStore {
    fun load(): List<Long>
    fun save(starts: List<Long>)
}

/**
 * Rolling-window scan-start gate. [reserve] returns 0 and records the start when a slot is free,
 * otherwise the milliseconds to wait before asking again (nothing recorded). The stack counts per
 * app UID inside the Bluetooth process, so its count survives our process restarts: [attach] seeds
 * the gate from starts an earlier process of this boot persisted (elapsedRealtime does not reset).
 */
internal class ScanStartGate(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val starts = ArrayDeque<Long>()
    private var store: ScanStartStore? = null

    /** Until when (elapsedRealtime) the last refused start waits; 0 when none. */
    @Volatile var deferredUntilMs: Long = 0L
        private set

    @Synchronized
    fun attach(store: ScanStartStore) {
        if (this.store != null) return
        this.store = store
        val now = clock()
        // Entries later than now come from a previous boot (the clock restarted) and are dropped.
        val seeded = runCatching { store.load() }.getOrDefault(emptyList()).filter { it in 0..now }
        val merged = (seeded + starts).distinct().sorted().takeLast(MAX_TRACKED)
        starts.clear()
        starts.addAll(merged)
    }

    @Synchronized
    fun reserve(budget: LeScanStartBudget?): Long {
        if (budget == null) return 0L
        val now = clock()
        while (starts.isNotEmpty() && now - starts.first() >= budget.windowMs) starts.removeFirst()
        if (starts.size >= budget.maxStarts) {
            // Margin: the stack timestamps the start when its binder call arrives, slightly after ours.
            val waitMs = (starts.first() + budget.windowMs - now).coerceAtLeast(0L) + SCAN_QUOTA_MARGIN_MS
            deferredUntilMs = now + waitMs
            return waitMs
        }
        starts.addLast(now)
        deferredUntilMs = 0L
        store?.let { target -> runCatching { target.save(starts.toList()) } }
        return 0L
    }

    /**
     * Takes a slot only when one is free now, atomically; a refusal records nothing and is not a
     * deferral (for an optional restart such as the balanced switch, which keeps its running scan).
     */
    @Synchronized
    fun tryReserve(budget: LeScanStartBudget?): Boolean {
        if (budget == null) return true
        if (used(budget) >= budget.maxStarts) return false
        return reserve(budget) == 0L
    }

    /** Whether a start slot is free right now (reserves nothing). */
    @Synchronized
    fun available(budget: LeScanStartBudget?): Boolean = budget == null || used(budget) < budget.maxStarts

    @Synchronized
    fun used(budget: LeScanStartBudget?): Int {
        if (budget == null) return 0
        val now = clock()
        return starts.count { now - it < budget.windowMs }
    }

    /** Milliseconds a refused start still waits, or 0. */
    fun deferredMs(): Long = (deferredUntilMs - clock()).coerceAtLeast(0L)

    companion object {
        const val SCAN_QUOTA_MARGIN_MS = 250L
        private const val MAX_TRACKED = 16

        /** One gate per process: the stack counts starts per app UID. */
        val shared = ScanStartGate()

        /** Set by the visible activity: a discovery start waits [Long] ms for the scan quota. */
        @Volatile var deferralListener: ((Long) -> Unit)? = null
    }
}

/**
 * Persisted scan starts keyed by boot: "boot=<Settings.Global.BOOT_COUNT>;<t1>,<t2>,..." (elapsedRealtime).
 * Starts from another boot, a legacy unkeyed value or corrupt numbers are dropped, so a quick reboot
 * never seeds phantom starts. When the boot count is unreadable ("?") on both sides, the gate's own
 * elapsedRealtime filter is the only guard (the pre-boot-key rule).
 */
internal fun encodeScanStarts(bootCount: Int?, starts: List<Long>): String =
    "boot=${bootCount ?: "?"};" + starts.joinToString(",")

internal fun decodeScanStarts(raw: String?, bootCount: Int?): List<Long> {
    val text = raw ?: return emptyList()
    val separator = text.indexOf(';')
    if (separator < 0 || text.substring(0, separator) != "boot=${bootCount ?: "?"}") return emptyList()
    return text.substring(separator + 1).split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0L }
}

/** SharedPreferences store for [ScanStartGate.attach], keyed by the boot count. */
internal class PreferencesScanStartStore(context: android.content.Context) : ScanStartStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("le_scan_quota", android.content.Context.MODE_PRIVATE)
    private val bootCount: Int? = runCatching {
        android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    }.getOrNull()

    override fun load(): List<Long> = decodeScanStarts(preferences.getString(KEY, null), bootCount)

    override fun save(starts: List<Long>) {
        preferences.edit().putString(KEY, encodeScanStarts(bootCount, starts)).apply()
    }

    private companion object {
        const val KEY = "starts_elapsed_ms"
    }
}

/**
 * One consistent requested-vs-observed line per LE link (audit 2026-10-10): PHY, MTU, connection
 * priority and the stack-reported interval side by side. Diagnostics only; nothing here changes a
 * request. It is not a BENCHMARK contract line; `BENCHMARK le_conn_params` is unchanged.
 */
internal class LeLinkDiagnostics(
    private val link: String,
    private val peer: String?,
    private val stackForcedMtu: Int?,
) {
    @Volatile var requestedPhy: String = "?"
    @Volatile var requestedMtu: Int? = null
    @Volatile private var effectiveMtu: Int? = null
    @Volatile private var mtuStatus: Int? = null
    @Volatile private var observedPhy: String = "pending"
    @Volatile private var phyStatus: Int? = null
    @Volatile private var requestedPriority: String = "?"
    @Volatile private var priorityQueued: Boolean? = null
    @Volatile private var params: LeConnectionParameters? = null

    fun onPriority(priority: BleConnectionPriority, queued: Boolean) {
        requestedPriority = priority.label
        priorityQueued = queued
    }

    fun onMtu(effective: Int, status: Int) {
        effectiveMtu = effective
        mtuStatus = status
    }

    fun onPhy(txPhy: Int, rxPhy: Int, status: Int) {
        observedPhy = "tx${lePhyLabel(txPhy)}/rx${lePhyLabel(rxPhy)}"
        phyStatus = status
    }

    fun onParams(interval: Int, latency: Int, timeout: Int, status: Int) {
        params = LeConnectionParameters(interval, latency, timeout, status)
    }

    fun line(event: String): String {
        val p = params
        val requested = requestedMtu
        val mtuSource = when {
            stackForcedMtu == null -> "app"
            requested != null && requested != stackForcedMtu -> "stack-sends-$stackForcedMtu-request-ignored"
            else -> "stack-sends-$stackForcedMtu"
        }
        return String.format(
            Locale.ROOT,
            "LE link: link=%s | event=%s | peer=...%s | phy requested=%s observed=%s status=%s | " +
                "mtu requested=%s effective=%s status=%s source=%s | priority requested=%s queued=%s | " +
                "interval observedMs=%s latency=%s supervisionMs=%s status=%s | btAudio=%s",
            link, event, peer?.takeLast(5) ?: "unknown",
            requestedPhy, observedPhy, phyStatus?.toString() ?: "-",
            requested?.toString() ?: "-", effectiveMtu?.toString() ?: "-", mtuStatus?.toString() ?: "-", mtuSource,
            requestedPriority, priorityQueued?.toString() ?: "-",
            p?.let { String.format(Locale.ROOT, "%.2f", it.intervalMs) } ?: "-",
            p?.peripheralLatency?.toString() ?: "-", p?.supervisionTimeoutMs?.toString() ?: "-", p?.status?.toString() ?: "-",
            runCatching { BluetoothAudioCoexistence.state().describe() }.getOrDefault("?"),
        )
    }

    fun log(event: String) {
        try { Log.i("BluetoothVideo", line(event)) } catch (_: RuntimeException) {} // Release: stripped.
    }

    companion object {
        /** On a phone: the stack-forced MTU from the registry (null where the entry is off or not matched). */
        fun create(link: String, peer: String?): LeLinkDiagnostics =
            LeLinkDiagnostics(link, peer, runCatching { BluetoothTransportPolicy.mtuStackForced() }.getOrNull())
    }
}

/** BluetoothDevice.PHY_LE_1M/2M/CODED values (1/2/3), spelled without the Android class for JVM tests. */
internal fun lePhyLabel(phy: Int): String = when (phy) {
    1 -> "1M"
    2 -> "2M"
    3 -> "Coded"
    else -> "unknown($phy)"
}
