package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.BLUETOOTH_SPEC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/** Every LE CoC client connect is closed from a watchdog after [defaultBoundMs]; the retry/auto-GATT path takes over. */
internal data class CocConnectBoundEffect(val defaultBoundMs: Long) : DevicePolicyEffect {
    override fun describe(): String = "LE CoC client connect bounded to ${defaultBoundMs / 1_000} s (socket closed by a watchdog)"
}

/** Failed CoC/Classic connects log the BluetoothSocketException code (API 34+) and its class. */
internal data object CocSocketErrorLogging : DevicePolicyEffect {
    override fun describe(): String = "log BluetoothSocketException error codes and their failure class"
}

/** Android 10: a CoC connect must follow a GATT connect or scan sighting of the same address; logs a violation. */
internal data object CocAddressTypePrecondition : DevicePolicyEffect {
    override fun describe(): String = "assert (log only) that a GATT connect preceded the CoC connect (Android 10 address type)"
}

/** A RuntimeException thrown by a Classic socket call is a failed attempt, not a crash. */
internal data object ClassicConnectRuntimeGuard : DevicePolicyEffect {
    override fun describe(): String = "Classic RFCOMM connect: RuntimeException/NPE becomes a failed attempt"
}

/** ACTION_PAIRING_REQUEST and ACTION_BOND_STATE_CHANGED are logged during Classic connects (receivers only). */
internal data object ClassicPairingRequestLogging : DevicePolicyEffect {
    override fun describe(): String = "log pairing requests and bond changes during Classic connects"
}

/** At most [maxPeers] distinct RFCOMM peers per phone; a further new peer is refused as capacity. */
internal data class ClassicRfcommPeerCap(val maxPeers: Int) : DevicePolicyEffect {
    override fun describe(): String = "Classic RFCOMM admission: at most $maxPeers distinct peers"
}

/** A CoC writer with zero acknowledged bytes from the start is classified as a receiver credit stall. */
internal data object CocCreditStallClassification : DevicePolicyEffect {
    override fun describe(): String = "classify a CoC writer with no acknowledged progress as a credit stall"
}

/** Experimental: one GATT MTU exchange of [mtu] on the fresh CoC ACL before CoC traffic (raises LE Data Length). */
internal data class CocMtuExchangeAfterConnect(val mtu: Int) : DevicePolicyEffect {
    override fun describe(): String = "one GATT MTU exchange ($mtu) on the CoC ACL before CoC traffic"
}

/** Experimental: a 1 KB echo over the new CoC socket must round-trip before the socket is used. */
internal data object CocEchoVerification : DevicePolicyEffect {
    override fun describe(): String = "1 KB echo over the CoC socket before use (falls back to GATT on failure)"
}

/**
 * Policy entries of the CoC and Classic batch (family batch C). The entries are listed in
 * [DevicePolicyRegistry] by one line (`CocPolicy.entries,`); until that line exists the batch resolves
 * its own entries through [resolve], which applies the same matcher and the same
 * `test_device_policy_off` switches, so behaviour does not depend on the aggregate.
 *
 * Related entries owned elsewhere: `le-coc-server-leak`, `le-coc-eatt-stranded-channels`,
 * `android-10-11-le-coc-security-id-budget`, `android-11-coc-small-sdu-coalescing` and
 * `pre-android-13-coc-congested-write-loss` already cover the listener, EATT and write-loss families
 * (BACKLOG C-07). `android15-qcom-coc-connect-stall` (BluetoothTransportPolicy.kt) is superseded by
 * [COC_CONNECT_BOUNDED]; its 30 s premise is not an AOSP timer.
 */
internal object CocPolicy {
    private const val REVIEWED = "2026-10-14"
    private const val ORIGIN_BATCH_C = "Family batch C (BACKLOG C-01..C-07, aosp-bt-versions MTU-02/COC-01/COC-04/CONN-08)."

    private const val AOSP = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+"

    val COC_CONNECT_BOUNDED = DevicePolicyEntry(
        id = "coc-connect-bounded",
        title = "Every LE CoC client connect is bounded (12 s) on every Android version",
        matcher = DeviceMatcher(),
        effects = listOf(CocConnectBoundEffect(CocSwitches.DEFAULT_CONNECT_BOUND_MS)),
        behaviour = "BluetoothSocket.connect() for an LE CoC channel blocks until the stack answers; the stack's own " +
            "channel-connect timer is 60 s. A watchdog closes the socket after 12 s (close() from another thread " +
            "aborts a blocking connect) and the attempt fails like any CoC connect failure, so the existing retry " +
            "and automatic GATT fallback take over. A connect that normally takes well under 3 s is unaffected. " +
            "Off: the connect waits for the stack (test_coc_connect_bound_ms=0 also turns the bound off).",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothSocket#connect()",
                "connect() blocks until the connection succeeds or fails; close() from another thread aborts it"),
            PolicySource(AOSP_COMMIT, "$AOSP/323f73de8584685774cf84b5556ec97e4feca9a0",
                "Android 13 QPR2 fix: a synchronous LE CoC failure used to call back before the connect request returned, so an earlier stack could leave the Java socket waiting"),
            PolicySource(AOSP_SOURCE, "$AOSP/refs/heads/android14-release/system/stack/l2cap/l2c_int.h",
                "L2CAP_CHNL_CONNECT_TIMEOUT_MS is 60 s, the stack's own bound; no 30 s CoC timer exists (corrects the 30 s premise of android15-qcom-coc-connect-stall)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no long CoC connect is measured; healthy connects take well under the bound"),
        confidence = MEDIUM,
        confidenceNote = "The blocking connect and the abort by close() are documented; whether a given stack hangs " +
            "for the full 60 s is unmeasured, and the 12 s value is ours (conservative, tune by data).",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C + " Replaces the proposal android15-qcom-coc-connect-stall (unbounded BleCocClient.connectChannel).",
        legacySwitches = listOf("test_coc_connect_bound_ms=<ms> (0 = unbounded)"),
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The CoC connect waits for the stack's own timeout (up to 60 s).",
    )

    val COC_SOCKET_ERROR_CODES = DevicePolicyEntry(
        id = "coc-socket-error-codes",
        title = "Failed socket connects log the BluetoothSocketException code (Android 14+)",
        matcher = DeviceMatcher(sdkMin = 34),
        effects = listOf(CocSocketErrorLogging),
        behaviour = "A failed LE CoC or Classic connect logs errorCode and its class (stale PSM 13, no resources 12, " +
            "timeout 14, security 3-7, ACL failure 2, local socket 15-21). A stale-PSM failure is never repeated on " +
            "the same PSM: callers read the PSM again before the next attempt. Logging only; no connect is changed.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothSocketException",
                "getErrorCode() and the L2CAP_* / SOCKET_* constants (API 34)"),
            PolicySource(AOSP_SOURCE, "$AOSP/refs/heads/android16-release/framework/java/android/bluetooth/BluetoothSocketException.java",
                "Error code definitions as shipped; commits a23c9d40dd and 8134e953f2 introduced them in Android 14"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no phone run logged a code yet; the class-to-action table is unit tested"),
        confidence = HIGH,
        confidenceNote = "The API and constants are documented; which code a given OEM stack reports for a given " +
            "failure is unmeasured, so classes are diagnostics, not model rules.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Failures log the exception text only.",
    )

    val COC_ANDROID10_ADDRESS_PRECONDITION = DevicePolicyEntry(
        id = "coc-android10-address-type-precondition",
        title = "Android 10: a CoC connect must follow a GATT connect or scan sighting of the same address",
        matcher = DeviceMatcher(sdkMin = 29, sdkMax = 29),
        effects = listOf(CocAddressTypePrecondition),
        behaviour = "Android 10 does not copy the LE address type into the stack record for a CoC client connect, so " +
            "a CoC connect to an address the stack did not learn in this Bluetooth session never establishes. " +
            "MeshLive reads the PSM over GATT first, which creates the record. The client logs " +
            "BENCHMARK coc_address_precondition and flags a violation so a later refactor cannot break the order. " +
            "Logging only.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$AOSP/7ead9c5d288eeec8dbf63bbe34af12007aa37193",
                "Android 11 fix 'Fix CoC not being able to establish first connection' (b/133444088) adds BTA_DmAddBleDevice before the CoC connect"),
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/system/bt/+/refs/heads/android10-release/btif/src/btif_sock.cc",
                "android10-release btif_sock.cc lacks the address-type registration"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: assertion only; every current CoC connect follows a GATT PSM read"),
        confidence = MEDIUM,
        confidenceNote = "The AOSP defect is documented for stock Android 10; OEM backports of the fix are unknown.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No assertion line.",
    )

    val CLASSIC_CONNECT_RUNTIME_GUARD = DevicePolicyEntry(
        id = "classic-connect-runtime-guard",
        title = "A RuntimeException inside a Classic RFCOMM connect is a failed attempt",
        matcher = DeviceMatcher(),
        effects = listOf(ClassicConnectRuntimeGuard),
        behaviour = "BluetoothSocket.connect() can throw a NullPointerException from inside the framework on some " +
            "devices (Briar: Huawei, Android 9). The attempt is recorded as failed with the exception class and the " +
            "connector moves to the next candidate instead of crashing the session.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothSocket#connect()",
                "connect() is documented to throw IOException only; a RuntimeException is a framework fault"),
            PolicySource(VENDOR_DOC, "https://code.briarproject.org/briar/briar/-/issues/1899",
                "Briar: NPE inside the Android Bluetooth service on BluetoothSocket.connect() (Huawei ANE-LX1, Android 9); fix catches RuntimeException"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no phone of ours threw it"),
        confidence = LOW,
        confidenceNote = "One third-party report; the guard changes nothing on a healthy connect.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "A RuntimeException ends the attempt through the candidate loop's generic catch.",
    )

    val CLASSIC_PAIRING_REQUEST_LOGGING = DevicePolicyEntry(
        id = "classic-pairing-request-logging",
        title = "Pairing requests and bond changes are logged during Classic connects",
        matcher = DeviceMatcher(),
        effects = listOf(ClassicPairingRequestLogging),
        behaviour = "While a Classic connect runs, receivers log ACTION_PAIRING_REQUEST (variant) and " +
            "ACTION_BOND_STATE_CHANGED so an unexpected consent dialog on an unknown phone is attributed (the remote " +
            "stack set the bonding bit). The receivers never abort a broadcast, confirm a pairing or call createBond.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothDevice#ACTION_PAIRING_REQUEST",
                "Protected broadcast carrying EXTRA_PAIRING_VARIANT"),
            PolicySource(AOSP_SOURCE, "$AOSP/refs/heads/android16-release/system/btif/src/btif_dm.cc",
                "btif_dm_get_pairing_type: Just Works auto-accept only when neither side sets a bonding bit and the pairing was not API-initiated"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no consent dialog seen on a bond-free Classic run"),
        confidence = HIGH,
        confidenceNote = "Diagnostic only; which OEM stacks set the bonding bit for an insecure connect is undocumented.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No pairing lines in the log.",
    )

    val CLASSIC_RFCOMM_PEER_CAP = DevicePolicyEntry(
        id = "classic-rfcomm-peer-cap",
        title = "Classic RFCOMM peer count: log when a seventh distinct peer would be refused (refusal at 6 is the Experimental arm)",
        matcher = DeviceMatcher(),
        effects = listOf(ClassicRfcommPeerCap(CocSwitches.DEFAULT_CLASSIC_RFCOMM_CAP)),
        behaviour = "The stack allows 7 remote devices with RFCOMM multiplexers and 30 ports per phone, shared with the " +
            "user's headset, car and other apps. By default (root review) nothing is refused: a Classic connect to a seventh " +
            "distinct peer only writes a log-only classic_rfcomm_cap line. The Experimental arm test_classic_rfcomm_cap=6 refuses it " +
            "as capacity (a clear error) instead of exhausting slots; an already connected peer can reconnect. " +
            "Off: no line (test_device_policy_off=classic-rfcomm-peer-cap).",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$AOSP/refs/heads/android14-release/system/btif/src/btif_sock_rfc.cc",
                "MAX_RFC_SESSION 7 remote devices and 30 RFCOMM slots shared by every app and profile"),
            PolicySource(AOSP_COMMIT, "$AOSP/12194ed93dece9558ea7b8cb199f74873a9a62c4",
                "Android 14 security fix: with all slots in use a new listening socket could get slot 0 and crash the Bluetooth process"),
            PolicySource(BLUETOOTH_SPEC, "Bluetooth Core Specification 5.4, Vol 1, Part A (a BR/EDR piconet has at most seven active peripherals)",
                "Upper bound on simultaneous Classic links per central"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no run reached six Classic peers"),
        confidence = MEDIUM,
        confidenceNote = "The limits are AOSP constants; 6 (one slot left for the user's own device) is our choice. Refusal is off by default until a phone run shows it is safe; incoming peers are undercounted until the accept paths call ClassicRfcommCap.note.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        legacySwitches = listOf("test_classic_rfcomm_cap=<n> (Experimental refusal; 0 = log only)"),
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No log line and no admission cap; the stack refuses or fails a seventh session itself.",
    )

    val COC_CREDIT_STALL_CLASSIFICATION = DevicePolicyEntry(
        id = "coc-credit-stall-classification",
        title = "A CoC writer with no acknowledged progress is classified as a receiver credit stall",
        matcher = DeviceMatcher(sdkMin = 29, sdkMax = 33),
        effects = listOf(CocCreditStallClassification),
        behaviour = "Before Android 13 QPR3 the L2CAP credit counter started at zero and wrapped on the first receive, so " +
            "a build with a low credit default stalls the sender after the first burst. A CoC writer that has handed " +
            "bytes to the socket but sees none acknowledged by the peer's application within the window is logged as " +
            "a credit stall and, with automatic GATT fallback on, moves the session to GATT instead of retrying CoC.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$AOSP/7e7d5ae3d5a18dc95c90216fdf3b8db6a39b45f1",
                "Initialize remote_credit_count when L2CAP on connection request (b/263323082; android13-qpr3 and later)"),
            PolicySource(AOSP_COMMIT, "$AOSP/4f221c977f4c2a4329cd0679e23541c3b672a6f0",
                "Make L2CAP credit config modifiable: OEMs can lower the default credits"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no stalled CoC writer logged"),
        confidence = LOW,
        confidenceNote = "The defect is documented; which OEM builds lowered the credit default is unknown, so this is " +
            "behavioural classification with no model list.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "A stalled CoC writer ends through the existing write watchdogs.",
    )

    val COC_MTU_EXCHANGE_BEFORE_TRAFFIC = DevicePolicyEntry(
        id = "coc-mtu-exchange-before-traffic",
        title = "One GATT MTU exchange on the fresh CoC ACL before CoC traffic (raises LE Data Length); Android 12-13 fresh-ACL centrals",
        matcher = DeviceMatcher(sdkMin = 31, sdkMax = 33),
        effects = listOf(CocMtuExchangeAfterConnect(517)),
        behaviour = "The stack raises the LE link-layer data length (up to 251 bytes) only after an ATT MTU exchange; " +
            "an L2CAP CoC channel alone does not, until Android 16 QPR1 sets the data length on a CoC connect. A CoC-only " +
            "link therefore keeps the controller default (27-byte payloads). On the fresh-ACL CoC client (the GATT " +
            "client is closed before the CoC connect) the arm opens one short GATT client on the new ACL right after " +
            "the CoC connect, requests MTU 517, waits for onMtuChanged, closes it and logs BENCHMARK coc_mtu_prime " +
            "(requested and effective MTU, inferred data length) before the normal GATT attach and any CoC traffic. " +
            "Default ON on the stacks where this client path runs (Android 12-13, entry le-coc-eatt-stranded-channels); " +
            "elsewhere the control GATT client stays open and already exchanged the MTU, and a skipped line with " +
            "reason=control-link-alive is logged. DEBUG test_coc_mtu_prime=false is the control arm. Never fails the CoC connect.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$AOSP/refs/heads/android14-release/system/stack/gatt/gatt_api.cc",
                "GATTC_UpdateUserAttMtuIfNeeded: BTM_SetBleDataLength runs from the ATT MTU handlers, with the user's requested MTU"),
            PolicySource(AOSP_COMMIT, "$AOSP/4f54ed0aa4",
                "Android 16 QPR1 'Set LE Data length on LE CoC connection' (flag set_max_data_length_for_lecoc): before it no path raises the data length for a dynamic CoC channel"),
            PolicySource(AOSP_COMMIT, "$AOSP/545bc4e427",
                "CONN-08: the ACL idles out about 1 s after the last GATT client or dynamic channel closes, which is why the exchange runs while the CoC channel keeps the ACL up"),
            PolicySource(BLUETOOTH_SPEC, "Bluetooth Core Specification 5.4, Vol 6, Part B, section 5.1.9 (Data Length Update procedure)",
                "The data length update is a link-layer procedure the host must start"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "benchmarks/runs/lane10-newbuild-checks-20261015/ (b2b-coc-mtu-prime-vbr, b2l: M31 Android 12 central, ASUS peer " +
                "632 kbps on 5/5 runs with the prime vs about 325 kbps on 5/5 without)",
            "benchmarks/runs/lane10-newbuild-checks-20261015/ (b2k: S24 peer, neutral)",
        ),
        confidence = MEDIUM,
        confidenceNote = "The AOSP code path is read and one central phone (M31) measured 2x with an ASUS peer; an S24 peer was " +
            "neutral. Android does not expose the effective data length, so the benefit is inferred from throughput. " +
            "Lane 11 breadth runs cover more peers; only the M31 central is measured for the stack family.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C + " Root cause candidate for the 17-29 kbps CoC stages. Default on since Q49 (2026-10-15).",
        legacySwitches = listOf("test_coc_mtu_prime=false (control arm)"),
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        fallback = "The CoC link runs with whatever data length the controller negotiated by itself.",
    )

    val COC_ECHO_VERIFICATION = DevicePolicyEntry(
        id = "coc-echo-verification",
        title = "Experimental: a 1 KB echo must round-trip over a new CoC socket before it is used",
        matcher = DeviceMatcher(),
        effects = listOf(CocEchoVerification),
        behaviour = "An advertised CoC capability is not proof that the channel carries data. After the connect the client " +
            "sends a 1024-byte random block and the listener echoes it before anything else; a mismatch or a timeout " +
            "closes the socket and fails the attempt, which the existing fallback turns into GATT. Costs one round " +
            "trip at setup. Both phones need the switch; no key or identity is exchanged in the echo. Default off; " +
            "DEBUG test_coc_echo_verify=true.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://github.com/berty/berty/issues/901",
                "Berty (BLE plus L2CAP mesh): PeerDevice.java verifies L2CAP with a 1024-byte echo before marking it usable and falls back to GATT; this issue is its write-timeout discussion"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: not run on phones"),
        confidence = LOW,
        confidenceNote = "One mesh app uses the pattern; MeshLive has not measured how often an established CoC socket is unusable.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C,
        legacySwitches = listOf("test_coc_echo_verify=true"),
        arm = PolicyArm("test_coc_echo_verify=true") { CocSwitches.echoVerify },
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "A CoC socket is used as soon as connect() returns.",
    )

    /** COC-01 (design record needed before any use): documented here so the capability is not forgotten. */
    val ANDROID16_BLUETOOTH_SOCKET_SETTINGS = DevicePolicyEntry(
        id = "android16-bluetooth-socket-settings-coc",
        title = "Android 16 BluetoothSocketSettings: LE CoC without encryption on a fixed PSM",
        matcher = DeviceMatcher(sdkMin = 36),
        effects = emptyList(),
        behaviour = "Proposal only, not implemented: API 36 adds BluetoothSocketSettings, which can open an LE CoC server " +
            "and client without link encryption or authentication on a PSM in a fixed range (128-255). That would remove " +
            "the PSM read over GATT and the fresh-ACL dance for peers that both run Android 16, at the cost of a second " +
            "PSM-discovery rule and a wire/discovery change. Needs a DECISIONS design record and review before any code.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothSocketSettings",
                "Android 16 (API 36) socket settings for L2CAP channels"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no Android 16 pair has used it"),
        confidence = LOW,
        confidenceNote = "Details (PSM range, encryption flags) come from the research notes of the AOSP read; the API " +
            "page is the authority and must be re-read when the design record is written.",
        reviewed = REVIEWED,
        origin = ORIGIN_BATCH_C + " COC-01.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The PSM is read over the GATT control service as today.",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        COC_CONNECT_BOUNDED,
        COC_SOCKET_ERROR_CODES,
        COC_ANDROID10_ADDRESS_PRECONDITION,
        CLASSIC_CONNECT_RUNTIME_GUARD,
        CLASSIC_PAIRING_REQUEST_LOGGING,
        CLASSIC_RFCOMM_PEER_CAP,
        COC_CREDIT_STALL_CLASSIFICATION,
        COC_MTU_EXCHANGE_BEFORE_TRAFFIC,
        COC_ECHO_VERIFICATION,
        ANDROID16_BLUETOOTH_SOCKET_SETTINGS,
    )

    /**
     * This batch's entries as seen by [traits]: same matcher and `test_device_policy_off` rules as
     * [DeviceFamilyPolicy.resolve], independent of whether the aggregate lists the batch yet.
     */
    fun resolve(
        traits: DeviceTraits,
        disabled: Set<String> = DeviceFamilyPolicy.switchedOff,
    ): ResolvedDevicePolicy = DevicePolicyOff.resolve(entries, traits, disabled)

    fun current(): ResolvedDevicePolicy = resolve(DeviceTraits.current())
}
