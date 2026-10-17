package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.BLUETOOTH_SPEC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/** After an unconfirmed `setPreferredPhy`, read the PHY at [plan]'s offsets (readPhy starts no LL procedure). */
internal data class PhyUpdateCallbackBackstop(val plan: PhyWaitPlan) : DevicePolicyEffect {
    override fun describe(): String =
        "poll readPhy from ${plan.firstPollMs} ms every ${plan.pollIntervalMs} ms until ${plan.deadlineMs} ms after a PHY request"
}

/** The dual-role probe logs `BENCHMARK dual_role` and adds `dual_role=<result>` to the device_policy line. */
internal object DualRoleProbeLog : DevicePolicyEffect {
    override fun describe(): String = "log whether a child link carried data while the parent link was up"
}

/** Every connection-priority request logs the interval range, latency and supervision timeout it asks for. */
internal object PriorityRequestSupervisionLog : DevicePolicyEffect {
    override fun describe(): String = "log the supervision timeout a connection-priority request sets"
}

/**
 * Device-family policy of batch P (PHY and link-layer procedures). The entries live in one list that the
 * registry aggregate concatenates (`PhyLinkPolicy.entries`, one line in DevicePolicyRegistry.areaEntryLists).
 * Behaviour that ships on is limited to diagnostics and an after-failure backstop (the Android 13 readPhy
 * poll, the priority-request log, the dual-role probe log); every behaviour change is a proposed (inert)
 * entry plus a default-off Experimental switch in [PhySwitches]. Gates stay identical for every phone.
 */
internal object PhyLinkPolicy {
    private const val REVIEWED = "2026-10-14"
    private const val BT_MODULE = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+"
    private const val NORDIC_BLE = "https://github.com/NordicSemiconductor/Android-BLE-Library"

    const val ANDROID13_BACKSTOP_ID = "android13-phy-update-callback-backstop"
    const val DUAL_ROLE_PROBE_ID = "dual-role-probe-log"
    const val PRIORITY_LOG_ID = "le-priority-request-supervision-log"

    private fun active(id: String, disabled: Set<String>) = "all" !in disabled && id !in disabled

    /** The Android 13 readPhy backstop applies (SDK 33, entry not switched off). Independent of registry membership. */
    fun phyCallbackBackstopActive(sdkInt: Int, disabled: Set<String>): Boolean =
        sdkInt == 33 && active(ANDROID13_BACKSTOP_ID, disabled)

    fun dualRoleProbeLogActive(disabled: Set<String>): Boolean = active(DUAL_ROLE_PROBE_ID, disabled)

    fun prioritySupervisionLogActive(disabled: Set<String>): Boolean = active(PRIORITY_LOG_ID, disabled)

    val ANDROID13_PHY_CALLBACK_BACKSTOP = DevicePolicyEntry(
        id = ANDROID13_BACKSTOP_ID,
        title = "Android 13: poll readPhy when onPhyUpdate does not arrive",
        matcher = DeviceMatcher(sdkMin = 33, sdkMax = 33),
        effects = listOf(PhyUpdateCallbackBackstop(PhyWaitPlan.ANDROID_13)),
        behaviour = "After a setPreferredPhy on Android 13 the app reads the PHY (readPhy) 200 ms later and every 400 ms " +
            "until 2 s, and takes the first read that shows the requested PHY as the answer, so a missing onPhyUpdate no " +
            "longer leaves the PHY unknown (adaptive 1M move, deferred PHY request, LL procedure gate). Other releases keep " +
            "Nordic's single read 1 s after the request. The read only observes; it starts no link-layer procedure. A " +
            "PHY that never shows is reported as unconfirmed, never assumed.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT_MODULE/e0fd13ef08",
                "\"Pass PHY change event up to GATT\" (2022-10-06, Bug 245969911): the GD ACL shim did not forward " +
                    "LE PHY update to GATT before android13-qpr1; derived from the diff, so the base release is a " +
                    "suspect, not a measured failure (short hash)"),
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/414",
                "Nordic Android-BLE-Library #414: on Android 13 setPreferredPhy completes but onPhyUpdate is not " +
                    "delivered; the library calls readPhy after 1 s"),
            PolicySource(VENDOR_DOC, "https://github.com/weliem/blessed-android/blob/master/blessed/src/main/java/com/welie/blessed/BluetoothPeripheral.java",
                "Blessed completes its PHY command after a 200 ms delay on Android 13 \"in order not to block the queue\" " +
                    "(author reproduced on a Pixel 5)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no owned phone runs Android 13 base; the existing readPhy fallback (CocPhyAfterConnect) is Nordic's 1 s rule"),
        confidence = MEDIUM,
        confidenceNote = "Two independent libraries report the missing callback on Android 13; the AOSP cause is derived " +
            "from a diff and the Bluetooth module can be updated past the fix by Play system updates, so every SDK 33 phone gets " +
            "the cheap read-only poll and no behaviour depends on it.",
        reviewed = REVIEWED,
        origin = "BACKLOG CONN-09 (aosp-bt-versions.md) and ble-library-trackers N2/B2: AdaptivePhy and CocPhyAfterConnect relied on onPhyUpdate or one late read.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "One readPhy 1 s after the request, as before.",
    )

    val PRIORITY_REQUEST_SUPERVISION_LOG = DevicePolicyEntry(
        id = PRIORITY_LOG_ID,
        title = "Log the supervision timeout every connection-priority request sets",
        matcher = DeviceMatcher(),
        effects = listOf(PriorityRequestSupervisionLog),
        behaviour = "Diagnostics only: each requestConnectionPriority logs the interval range, latency and supervision " +
            "timeout it asks for (5 s on Android 10+, 20 s on Android 8-9) so a link lost after 5 s of silence is " +
            "classified as the priority request's timeout, not a stack timer. Nothing is requested differently.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT_MODULE/d32b2d461",
                "\"Lower the BLE connection supervision timeout to 5s\" (2017-03-21): GattService.connectionParameterUpdate sends " +
                    "timeout 500 (5 s) on Android 10 and later, 2000 (20 s) on Android 8-9 (short hash)"),
            PolicySource(AOSP_SOURCE, "$BT_MODULE/745ee92b87ed/android/app/res/values/config.xml",
                "gatt_high_priority 9-12, gatt_balanced 24-40, gatt_low_power 80-100 latency 2, gatt_dck_priority 24-24 units"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestConnectionPriority(int)",
                "the public API only offers BALANCED, HIGH, LOW_POWER and (API 34) DCK; its Javadoc quotes older numbers"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: AOSP source reading; the Exynos 9611 entry records 0x08 after 5.0-5.1 s of silence that this timeout explains"),
        confidence = MEDIUM,
        confidenceNote = "The mechanism is read from AOSP release branches; the log only restates it per request. That a given " +
            "0x08 is this timeout still needs the silence length from the link-lost line.",
        reviewed = REVIEWED,
        origin = "BACKLOG CONN-01/P-05: LeConnectionPriorityKeeper logged retries but not the parameters of the request itself.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The link line already reports the stack's supervision timeout after the first update.",
    )

    val DUAL_ROLE_PROBE_LOG = DevicePolicyEntry(
        id = DUAL_ROLE_PROBE_ID,
        title = "Dual-role probe: log whether a child link ran while the parent link was up",
        matcher = DeviceMatcher(),
        effects = listOf(DualRoleProbeLog),
        behaviour = "Diagnostics only: a relay logs `BENCHMARK dual_role: result=pass|fail|unproven` from what actually " +
            "happened (parent up, child connected, child data with the peer answering, parent still active), and the " +
            "device_policy line carries `dual_role=<result>`. A pass is never credited unless both links carried data in " +
            "the same window. A learned child-link limit is kept as advice for admission; nothing is enforced.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://github.com/torvalds/linux/blob/master/drivers/bluetooth/hci_qca.c",
                "qca_soc_data: QCA_CAP_VALID_LE_STATES for QCA2066, WCN3991, WCN6750, WCN6855, WCN7850; WCN3990, WCN3998, " +
                    "WCN3950, WCN3988 and QCA6390 get HCI_QUIRK_BROKEN_LE_STATES (commit 547801380e, 2020-09-30)"),
            PolicySource(VENDOR_DOC, "https://github.com/torvalds/linux/blob/master/include/net/bluetooth/hci.h",
                "HCI_QUIRK_BROKEN_LE_STATES: many controllers had trouble initiating a connectable advertisement " +
                    "despite reporting the state combination as supported"),
            PolicySource(BLUETOOTH_SPEC, "Bluetooth Core Specification v5.4 Vol 6 Part B 1.1 and Vol 4 Part E 7.8.27 (LE Read Supported States)",
                "supported states and state combinations are controller capabilities the host cannot assume; Android exposes no API for them"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: S24, S22, M31 and ASUS relay lanes already run dual role; the probe adds the explicit per-phone answer"),
        confidence = LOW,
        confidenceNote = "The kernel table says which chips a maintainer validated, not which are broken, and describes Linux " +
            "host behaviour; the probe exists to replace the inference with a per-phone observation.",
        reviewed = REVIEWED,
        origin = "BACKLOG P-03 (controllers.md C-08, C-19): dual role was assumed from relay runs; no result was recorded per phone.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No dual_role field and no probe line; relay behaviour is unchanged either way.",
    )

    val QUALCOMM_WCN399X_DUAL_ROLE_UNVALIDATED = DevicePolicyEntry(
        id = "qualcomm-wcn399x-dual-role-unvalidated",
        title = "Qualcomm WCN3990/WCN3998 generation: central+peripheral operation was never validated upstream",
        matcher = DeviceMatcher(
            hardware = setOf("qcom"),
            boards = setOf("msmnile", "sdm845"),
            boardPrefixes = setOf("sm8150", "sdm845"),
        ),
        effects = emptyList(),
        behaviour = "Proposal (diagnostic, no effect): phones with the SM8150/SDM845 platform (WCN3998 / WCN3990 combo " +
            "chips, which Linux does NOT list as validated for simultaneous central and peripheral roles) deserve the " +
            "dual-role probe first. The board to chip mapping is indirect. No behaviour changes; the existing qcom-legacy " +
            "and msmnile protections stand on their own local evidence.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://github.com/torvalds/linux/blob/master/drivers/bluetooth/hci_qca.c",
                "WCN3990 and WCN3998 are in the group without QCA_CAP_VALID_LE_STATES (HCI_QUIRK_BROKEN_LE_STATES); " +
                    "WCN3991, WCN6750, WCN6855, WCN7850 and QCA2066 are validated (commit 547801380e, 5d192b697c)"),
            PolicySource(VENDOR_DOC, "https://github.com/torvalds/linux/commit/aae6b81260",
                "2024-08-12: the quirk became opt-out (\"by default we assume the controllers would report valid states\"); " +
                    "omission of a chip means nobody validated it, not that its firmware is broken"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: correlation with the ASUS msmnile limits is not proof"),
        confidence = LOW,
        confidenceNote = "Facts are kernel commits; the board-to-chip map and any inference about an Android phone are indirect, " +
            "and Qualcomm Android firmware can differ from the Linux-loaded rampatch.",
        reviewed = REVIEWED,
        origin = "BACKLOG P-03 (controllers.md C-19).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Nothing changes; the dual-role probe runs on every phone.",
    )

    val LL_PROCEDURE_COLLISION_SERIALISE = DevicePolicyEntry(
        id = "ll-procedure-collision-serialise",
        title = "One link-layer procedure in flight per LE link (MTU, PHY, connection update)",
        matcher = DeviceMatcher(controller = ControllerMatcher(le2mPhy = true)),
        effects = emptyList(),
        behaviour = "Proposal (Experimental, test_ll_gate=true): requestMtu, discoverServices, setPreferredPhy and " +
            "requestConnectionPriority on one link are queued through LlProcedureGate: one in flight, a settle gap after " +
            "each (400 ms after MTU, 1.5 s after a PHY update, 750 ms after a connection update), a peripheral sends no " +
            "PHY request before the central's first connection update plus three intervals and its service discovery, a " +
            "PHY answered with 0x23/0x2A is retried once on 1M, a missing callback is bounded and not stacked on, and a " +
            "link lost within 5 s of a PHY update stays on 1M for that peer. Costs a few seconds before 2M. Off: every " +
            "request goes straight to the stack, as it does today.",
        sources = listOf(
            PolicySource(BLUETOOTH_SPEC, "Bluetooth Core Specification v5.4 Vol 6 Part B 5.3 (procedure collisions)",
                "two instant-based procedures (connection update, PHY update) may not be pending together; the second is " +
                    "rejected with 0x23 LL procedure collision or 0x2A different transaction collision"),
            PolicySource(VENDOR_DOC, "https://github.com/zephyrproject-rtos/zephyr/commit/94d7669cf321dc17fa9a59467506467ee0a65e8e",
                "Zephyr: \"certain phones in the market perform Connection Update Procedure and do not correctly handle " +
                    "remote initiated colliding PHY update procedures\" (2018-08-17)"),
            PolicySource(VENDOR_DOC, "https://devzone.nordicsemi.com/f/nordic-q-a/100349/devacademy-bluetooth-le-connection-problems/436083",
                "Nordic engineer: Galaxy S21 answers LL_PHY_REQ immediately instead of waiting for LL_CONNECTION_UPDATE_IND; " +
                    "Broadcom fixed the controller bug, Samsung had not shipped it (a forum claim, no official source)"),
            PolicySource(VENDOR_DOC, "https://devzone.nordicsemi.com/f/nordic-q-a/107610/specific-android-tablet-model-isn-t-able-to-reliably-connect/465457",
                "Lenovo M10: disconnect 0x2A within 5 s of the 2M update; fixed by disabling the auto PHY update or delaying it"),
            PolicySource(AOSP_SOURCE, "$BT_MODULE/745ee92b87ed/system/stack/l2cap/l2c_ble_conn_params.cc",
                "the stack locks 7.5 ms parameters while service discovery runs and applies the app's request afterwards; " +
                    "AOSP does not serialise the app's PHY, MTU/DLE and priority requests (serialised only in Android 16)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: status 42 \"BLE PHY unavailable\" collisions seen on M31, ASUS and A15s in 2026-10-07 runs led to commit 2cbdafb1; no retained run isolates the gate"),
        confidence = MEDIUM,
        confidenceNote = "The spec rule and the error codes are primary; the Broadcom/Samsung attribution is a Nordic engineer's " +
            "forum statement, and the default behaviour is already measured on six phones, so the gate stays an A/B arm.",
        reviewed = REVIEWED,
        origin = "BACKLOG P-01 (controllers.md C-02/C-03/C-20, bluetooth.md Part J/K).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Requests are issued as today; LeConnectionPriorityKeeper retries a failed connection update.",
    )

    val LINK_LOST_AFTER_CHANGE_ROLLBACK = DevicePolicyEntry(
        id = "link-lost-after-change-rollback",
        title = "Roll back the last MTU, PHY or priority change after a link is lost within 10 s of it",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "Proposal (Experimental, test_link_change_rollback=true): a link lost with a fault status (0x08, 0x22, " +
            "0x23, 0x2A, 0x3E, 133, 147) within 10 s of an MTU, PHY or priority change remembers that change as bad for the " +
            "peer; the next connection asks for MTU 185, stays on 1M, or asks for BALANCED. The memory expires on a " +
            "Bluetooth restart, a security-patch change or after 7 days. Off: no memory, the existing 2M->1M adaptive " +
            "memory is unchanged.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/258",
                "link dies within seconds of a large MTU (OPPO A5s MT6765, Android 8.1: MTU <= 246 worked) and GATT 8 three " +
                    "to four seconds after connecting on Redmi/OPPO (19 of 20 worked after bonding first)"),
            PolicySource(VENDOR_DOC, "https://devzone.nordicsemi.com/f/nordic-q-a/68646/early-disconnect-when-nrf_sdh_ble_gatt_max_mtu_size-247",
                "disconnect 0x22 soon after MTU 247 / DLE 251 while 27/23 stays connected (single, unanswered report)"),
            PolicySource(VENDOR_DOC, "https://devzone.nordicsemi.com/f/nordic-q-a/107610/specific-android-tablet-model-isn-t-able-to-reliably-connect/465457",
                "disconnect 0x2A shortly after a PHY change on a Lenovo M10"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: single reports on other phones; the A15s (MediaTek, Android 10) is the lane for the phone test"),
        confidence = LOW,
        confidenceNote = "Each source is one report without a root cause; the rule is generic (any change followed by a fault " +
            "status) and cheap to undo, which is why it is an A/B arm and not a default.",
        reviewed = REVIEWED,
        origin = "BACKLOG P-02 (bluetooth.md Part J mtk-mt6765-mtu-over-246-disconnect, early-disconnect-redmi-oppo-gatt8).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "The link is retried with the same requests; AdaptivePhyMemory still handles 2M->1M.",
    )

    val LE_PRIORITY_INTERVAL_REASSERT = DevicePolicyEntry(
        id = "le-priority-interval-reassert",
        title = "Request the connection priority again when the interval reverts to the default",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "Proposal (Experimental, test_priority_reassert=true): when onConnectionUpdated reports an interval above " +
            "the requested priority's maximum (HIGH asks 11.25-15 ms and the link falls back to 45 ms), the same priority is " +
            "requested again after 1 s and 3 s, at most twice, and not when the second report shows the same interval (a " +
            "floor such as the 20 ms hearing-aid minimum). Off: the drift is only logged.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/537",
                "Android uses 7.5 ms for service discovery and then drops to its default (36 units = 45 ms on a Tab S6 Lite); " +
                    "the maintainer: request the priority again"),
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/558",
                "the interval flips back to 30 ms after CONNECTION_PRIORITY_HIGH was requested"),
            PolicySource(AOSP_SOURCE, "$BT_MODULE/745ee92b87ed/android/app/src/com/android/bluetooth/gatt/GattService.java",
                "a request issued while another update is pending is dropped (one slot, the later request replaces the earlier); " +
                    "the stack applies the queued request when service discovery ends"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: LeConnectionPriorityKeeper logs show failed updates (0x23/0x3B), not silent reverts"),
        confidence = MEDIUM,
        confidenceNote = "Three independent reports and AOSP behaviour support the revert; whether MeshLive links revert after " +
            "its own request (it already requests after discovery) is unmeasured, and the DCK/BALANCED experiments change " +
            "the interval on purpose.",
        reviewed = REVIEWED,
        origin = "ble-library-trackers N11/R9/P6 and aosp-bt-versions CONN-02: only a reported failure re-issued the request.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Only a reported failed update (0x23, 0x3B...) re-issues the request, as today.",
    )

    val CODED_PHY_CONFIRMED_CALLBACK_ONLY = DevicePolicyEntry(
        id = "coded-phy-confirmed-callback-only",
        title = "Treat Coded PHY as active only when the stack reported it",
        matcher = DeviceMatcher(controller = ControllerMatcher(leCodedPhy = true)),
        effects = emptyList(),
        behaviour = "Proposal (needs the LinkCapacity/LinkSelfMeasurement call sites to pass trustedExperimentalPhy): the Coded " +
            "pacer prior and capacity key apply only after a PHY callback with status 0 reported Coded; an unconfirmed " +
            "Coded request is treated as the PHY the link really runs on. Connections stay on LE 1M first and Coded is " +
            "requested after connect; Coded-primary scanning is not used (impossible before Android 15), and 1M is always " +
            "in the connectGatt mask.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/166",
                "isLeCodedPhySupported is a feature bit: Pixel 3/4 advertise Coded but scan only 1M primary; LG K31 reports " +
                    "YES and cannot see a Coded advertisement"),
            PolicySource(AOSP_SOURCE, "$BT_MODULE/refs/heads/android14-release/system/gd/hci/le_scanning_manager.cc",
                "configure_scan hardcodes phys_in_use = 1 (1M) before Android 15; phy_to_native plumbing arrives in 15 behind a flag"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothGatt#setPreferredPhy(int,%20int,%20int)",
                "setPreferredPhy requests a PHY; onPhyUpdate reports the result and the stack may keep the current PHY"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: Coded was measured GATT 154/47 kbps on S24 -> S22 only (DECISIONS Bounded payload)"),
        confidence = MEDIUM,
        confidenceNote = "The flag/scan gap is documented by two libraries and AOSP; which phones report Coded without running " +
            "it on a link is unmeasured, and the proposal is inert until the capacity call sites are wired.",
        reviewed = REVIEWED,
        origin = "BACKLOG P-04 residue (bluetooth.md coded-phy-flag-not-reliable, controllers.md C-22).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The requested Coded arm sets the pacer prior, as today (DEBUG test_ble_phy only).",
    )

    internal val entries: List<DevicePolicyEntry> = listOf(
        ANDROID13_PHY_CALLBACK_BACKSTOP,
        PRIORITY_REQUEST_SUPERVISION_LOG,
        DUAL_ROLE_PROBE_LOG,
        QUALCOMM_WCN399X_DUAL_ROLE_UNVALIDATED,
        LL_PROCEDURE_COLLISION_SERIALISE,
        LINK_LOST_AFTER_CHANGE_ROLLBACK,
        LE_PRIORITY_INTERVAL_REASSERT,
        CODED_PHY_CONFIRMED_CALLBACK_ONLY,
    )
}
