package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.BLUETOOTH_SPEC
import com.example.bluetoothtest.PolicySourceKind.CDD
import com.example.bluetoothtest.PolicySourceKind.GOOGLE_OPEN_SOURCE
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/** Effects of the advertising batch (device-family backlog A-01..A-03). Descriptions only; the code reads the switches. */
internal data object AdvertisingNonThrowingStart : DevicePolicyEffect {
    override fun describe(): String = "advertising-set starts report a failure status instead of throwing"
}

internal data object AdvertisingBudgetLearning : DevicePolicyEffect {
    override fun describe(): String =
        "learn the advertising-set budget from start refusals; optional sets yield to the session-critical one"
}

internal data object AdvertisingSetRestart : DevicePolicyEffect {
    override fun describe(): String = "restart an advertising set that stopped without being asked, with back-off"
}

internal data object AdvertisingStuckReport : DevicePolicyEffect {
    override fun describe(): String = "report 'no free advertising slots although none are ours' with the Bluetooth restart remedy"
}

internal data object AdvertisingLegacyPayloadCap : DevicePolicyEffect {
    override fun describe(): String = "every MeshLive legacy advertisement fits the 28-byte connectable budget of Android 13 and below"
}

internal data object AdvertisingIgnoreMultiAdvFlag : DevicePolicyEffect {
    override fun describe(): String = "advertising is never gated on BluetoothAdapter.isMultipleAdvertisementSupported()"
}

/**
 * Advertising-set policy entries (batch A). All are universal (runtime-detected), none rests on a phone model:
 * the controller's instance count and the stack's behaviour are learned from start results. Off-switch for each:
 * DEBUG `test_device_policy_off=<id>` (also read directly by [enabled], so the behaviour follows the switch whether
 * or not the root has added [entries] to the registry aggregate).
 */
internal object AdvertisingPolicy {
    private const val REVIEWED = "2026-10-14"
    private const val A14_BT = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release"
    private const val A11_BT = "https://android.googlesource.com/platform/system/bt/+/refs/heads/android11-release"
    private const val SET_CALLBACK = "https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetCallback"
    private const val CDD_BLUETOOTH = "https://source.android.com/docs/compatibility/14/android-14-cdd#7_4_3_bluetooth"
    private const val CORE_SETS = "Bluetooth Core Specification 5.4, Vol 4, Part E, section 7.8.58 (LE Read Number of Supported Advertising Sets)"
    private const val TRACKER_NOTE = "third-party issue tracker as recorded in the family research ble-library-trackers.md; not re-fetched"

    const val ID_NON_THROWING_START = "advertising-start-non-throwing"
    const val ID_BUDGET = "advertising-set-budget-learned"
    const val ID_WATCHDOG = "advertising-set-watchdog-restart"
    const val ID_STUCK = "advertising-slots-stuck-report"
    const val ID_PAYLOAD = "advertising-legacy-payload-28-bytes"
    const val ID_MULTI_ADV_FLAG = "advertising-multi-adv-flag-not-trusted"
    const val ID_A15_QUIESCE = "advertising-android15-slot-leak-quiesce"

    val NON_THROWING_START = DevicePolicyEntry(
        id = ID_NON_THROWING_START,
        title = "Starting an advertising set never throws into the app",
        matcher = DeviceMatcher(),
        effects = listOf(AdvertisingNonThrowingStart),
        behaviour = "startAdvertisingSet validates synchronously: an oversize legacy payload or an unsupported parameter " +
            "throws IllegalArgumentException, and a start while the adapter is off or turning off throws " +
            "IllegalStateException (BT Adapter is not turned ON). Every MeshLive advertising start (stable offer and " +
            "discovery sets, title and reach pages, the capacity probe) now turns those into a failure status the " +
            "callers already handle, so a delayed restart cannot end the process. One callback object serves one " +
            "set, and the stable set is started with duration 0 and no event limit so the stack re-enables it after " +
            "every connection. An adapter-off exception no longer counts as the stack refusing a hidden (non-discoverable) " +
            "advertisement. Off: the previous direct call (exceptions propagate).",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/framework/java/android/bluetooth/le/BluetoothLeAdvertiser.java",
                "startAdvertisingSet: legacy data > 31 bytes, unsupported PHY and bad duration/events throw IllegalArgumentException; the classic startAdvertising path reports DATA_TOO_LARGE instead"),
            PolicySource(AOSP_SOURCE, "$A14_BT/framework/java/android/bluetooth/le/BluetoothLeUtils.java",
                "checkAdapterStateOn throws IllegalStateException unless the adapter (or BLE mode) is on"),
            PolicySource(API_REFERENCE, SET_CALLBACK, "Public failure codes DATA_TOO_LARGE 1, TOO_MANY_ADVERTISERS 2, ALREADY_STARTED 3, INTERNAL_ERROR 4, FEATURE_UNSUPPORTED 5"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no phone threw; unit tests cover the exception mapping"),
        confidence = MEDIUM,
        confidenceNote = "The throwing behaviour is read from AOSP source (high); that any owned phone reaches it is unmeasured.",
        reviewed = REVIEWED,
        origin = "Device-family backlog A-01: StableAdvertisingSet.startOnce called startAdvertisingSet with no guard, so a " +
            "stack that throws (adapter off during a delayed restart, a rejected parameter) crashed the calling thread.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The call propagates its exception as before.",
    )

    val BUDGET_LEARNED = DevicePolicyEntry(
        id = ID_BUDGET,
        title = "The advertising-set budget is learned from start refusals; optional sets yield",
        matcher = DeviceMatcher(),
        effects = listOf(AdvertisingBudgetLearning),
        behaviour = "The number of advertising instances is whatever the controller reports (the CDD only says SHOULD 4, " +
            "a cheap controller can offer one) and every app and the system share it, so it cannot be read. MeshLive " +
            "learns it: a refusal with TOO_MANY_ADVERTISERS while it owns n sets means the budget is n now (kept five " +
            "minutes). With a short budget the reach and title sets do not start (they need one instance plus one spare), " +
            "and a refused session-critical set (the stream's own advertisement) first stops the title set, then the " +
            "reach set, waits for the stack to confirm the stop (the instance is freed asynchronously) and retries once. " +
            "Priority: stable offer > reach > title > probe. Starts also wait up to 0.7 s for stops still in flight. " +
            "No advertisement changes. Off: previous behaviour (probe-only gating, no preemption, no stop wait).",
        sources = listOf(
            PolicySource(CDD, CDD_BLUETOOTH, "7.4.3: SHOULD support multi advertisement with at least 4 slots; devices with fewer are legal"),
            PolicySource(API_REFERENCE, SET_CALLBACK, "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: no advertising instance available"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/gd/hci/le_advertising_manager.cc",
                "allocate_advertiser returns an invalid id when every instance is used -> start_advertising_fail(TOO_MANY_ADVERTISERS); the id is freed asynchronously after a stop"),
            PolicySource(AOSP_SOURCE, "$A11_BT/stack/btm/ble_advertiser_hci_interface.cc",
                "Legacy HCI interface ReadInstanceCount returns 1: a controller with neither BT5 extended advertising nor vendor multi-adv has one instance for all apps"),
            PolicySource(BLUETOOTH_SPEC, CORE_SETS, "The controller reports its own number of advertising sets; Android exposes no API for it"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no 1-slot phone is owned; the budget logic is covered by unit tests and the test_adv_budget_cap simulation switch"),
        confidence = MEDIUM,
        confidenceNote = "Mechanism and the shared, asynchronous release are read from AOSP (high); how many instances owned phones " +
            "actually leave free beside other apps is only what BENCHMARK adv_set_probe has logged; no real one-instance phone was tested.",
        reviewed = REVIEWED,
        origin = "Device-family backlog A-02 (bluetooth.md adv-set-count-is-controller-dependent, adv-legacy-single-instance; " +
            "oem-bt.md C6): TOO_MANY was handled by skipping the title set but a refused stream set had no recovery.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Optional sets are gated by the one-time probe as before; a refused stable set fails with its status.",
    )

    val WATCHDOG_RESTART = DevicePolicyEntry(
        id = ID_WATCHDOG,
        title = "A stable advertising set that stops unexpectedly is restarted",
        matcher = DeviceMatcher(),
        effects = listOf(AdvertisingSetRestart),
        behaviour = "If the stack reports the stable set stopped (onAdvertisingSetStopped) without MeshLive asking, or reports " +
            "it disabled and does not enable it again within 4 s (the stack itself disables a connectable set for an " +
            "instant to rotate its address every 7 to 15 minutes), the set is started again as a new set (new address) " +
            "with back-off 1, 2, 4, 8, 16 s and at most five restarts in ten minutes; then the owner's own lost-handling " +
            "takes over. The length of each disable gap is logged, which is the only observable trace of the address " +
            "rotation. Title and reach sets restart the same way. Off: a stopped set stays stopped until its owner " +
            "starts it again.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/system/gd/hci/le_advertising_manager.cc",
                "handle_set_terminated re-enables non-directed sets with duration 0 and no event limit; rotation disables a connectable set, sets the new address and enables it again"),
            PolicySource(AOSP_SOURCE, "$A14_BT/framework/java/android/bluetooth/le/BluetoothLeAdvertiser.java",
                "onBluetoothServiceDown clears the advertiser's sets without delivering callbacks: a dead set is silent"),
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/279e3c3a86",
                "2022-09-14, bug 246692649: rotation after a connection to a set was not re-armed before Android 14"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no unexpected stop has been captured on an owned phone"),
        confidence = MEDIUM,
        confidenceNote = "The stack's re-enable and rotation behaviour is read from source; no owned phone has shown a set that died " +
            "silently, so the restart is defensive engineering (a dead advertisement is worth restarting) rather than a measured fix.",
        reviewed = REVIEWED,
        origin = "Device-family backlog A-03 (oem-bt.md C7, bluetooth.md adv-set-terminated-on-connection-and-reenable): " +
            "StableAdvertisingSet ignored onAdvertisingSetStopped and never checked onAdvertisingEnabled(false).",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No restart; the owner's onLost path handles only failures of its own fallback.",
    )

    val STUCK_REPORT = DevicePolicyEntry(
        id = ID_STUCK,
        title = "'No free advertising slots although none are ours' is detected and reported",
        matcher = DeviceMatcher(),
        effects = listOf(AdvertisingStuckReport),
        behaviour = "Two TOO_MANY_ADVERTISERS refusals while MeshLive owns no advertising set mean other apps, or a leaked " +
            "stack, hold every instance. Reports on Redmi, Mi 11 Lite, Galaxy A54 and Oppo phones say this persists until " +
            "the phone restarts, and Android 15's advertising manager leaks a slot when an address update meets a set " +
            "removal. MeshLive says so in plain words and names the remedy (turn Bluetooth off and on; restart the phone) " +
            "through the status line and BENCHMARK adv_budget stuck, and exposes the state to the reliability banner. " +
            "It does not toggle Bluetooth. Off: the start fails with its status only.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/e28bb6de3f",
                "2025-01-09, bugs 380772123 / 388615378: 'Fix advertising set slot becoming unusable'; in android16-release, not in android15 branches; leaves every slot unusable until Bluetooth restarts"),
            PolicySource(GOOGLE_OPEN_SOURCE, "https://github.com/simondankelmann/Bluetooth-LE-Spam/issues/140",
                "Redmi Note 9, Mi 11 Lite, Galaxy A54, Oppo Find X3 Lite: TOO_MANY_ADVERTISERS until the phone restarts ($TRACKER_NOTE)"),
            PolicySource(API_REFERENCE, SET_CALLBACK, "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: not seen on an owned phone"),
        confidence = LOW,
        confidenceNote = "The slot leak is a documented AOSP bug (fixed in 16); the persistent-until-reboot reports are six mixed " +
            "user comments on one third-party issue tracker. The detection itself is a count of refusals and cannot misreport other states.",
        reviewed = REVIEWED,
        origin = "Device-family backlog A-02 / ble-library-trackers.md V1 (advertiser-slot leak): TOO_MANY was only logged.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No report; the start failure reaches the user as 'advertising failed (code 2)'.",
    )

    val LEGACY_PAYLOAD = DevicePolicyEntry(
        id = ID_PAYLOAD,
        title = "Every legacy advertisement fits the 28-byte connectable budget of Android 13 and below",
        matcher = DeviceMatcher(),
        effects = listOf(AdvertisingLegacyPayloadCap),
        behaviour = "Android 13 and below charge a connectable legacy advertisement the 3-byte Flags field, leaving 28 bytes " +
            "of primary data; Android 14 charges it only when the set is also discoverable (31 bytes for the hidden-name " +
            "sets). The same payload that starts on Android 14 fails with DATA_TOO_LARGE on Android 12/13. MeshLive's " +
            "connectable primary data is one 128-bit service UUID (18 bytes) and every manufacturer-data page is in " +
            "the scan response (31 bytes, never charged flags); a unit test checks the largest announcement, title, " +
            "reach and name page against the smaller budget, and a DEBUG line logs the sizes at each start. " +
            "No advertisement changed.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14_BT/framework/java/android/bluetooth/le/BluetoothLeAdvertiser.java",
                "totalBytes(): 2 header bytes per AD structure; Flags charged when connectable (Android 13) or connectable and discoverable (Android 14); scan response never"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder#setDiscoverable(boolean)",
                "setDiscoverable is API 34; a non-discoverable connectable set carries no Flags field"),
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/6b5504173e",
                "2024-03-08, 'Add data size check on legacy advertising': Android 15's stack checks the length itself"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: guard and sizes are checked by unit tests only"),
        confidence = MEDIUM,
        confidenceNote = "The charging rule is read from AOSP (high); it has no runtime effect while every payload fits, " +
            "and no owned phone runs Android 13 or below with a larger payload.",
        reviewed = REVIEWED,
        origin = "Device-family backlog A-01 / aosp-bt-versions.md ADV-01: the per-SDK difference is invisible in same-model benchmarks.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No guard; an oversize payload would be reported as DATA_TOO_LARGE (or thrown) by the stack.",
    )

    val MULTI_ADV_FLAG = DevicePolicyEntry(
        id = ID_MULTI_ADV_FLAG,
        title = "Advertising is not gated on isMultipleAdvertisementSupported()",
        matcher = DeviceMatcher(),
        effects = listOf(AdvertisingIgnoreMultiAdvFlag),
        behaviour = "BluetoothAdapter.isMultipleAdvertisementSupported() returns false on phones where advertising works " +
            "(Galaxy S20 Exynos), and is true only above a controller-reported instance threshold. MeshLive reads it for " +
            "logging only (adv_set_probe, support bundle) and decides from actual start results: the budget is learned by " +
            "starting sets. A source test keeps the flag out of the advertising code's decisions.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isMultipleAdvertisementSupported()",
                "Whether the chipset supports more than one concurrent advertisement; CDD 7.4.3 requires it to be reported truthfully from Android 11"),
            PolicySource(GOOGLE_OPEN_SOURCE, "https://github.com/theheraldproject/herald-for-android/issues/111",
                "SM-G980F (Exynos, Android 10): the flag is false yet advertising works ($TRACKER_NOTE)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none"),
        confidence = MEDIUM,
        confidenceNote = "Two independent projects (Herald, AltBeacon) report the false negative; MeshLive never gated on the flag, " +
            "so this entry records a decision and its guard rather than a behaviour change.",
        reviewed = REVIEWED,
        origin = "Device-family backlog (ble-library-trackers.md A11): do not gate advertising on the flag.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "n/a: the flag is not consulted.",
    )

    /**
     * Proposal: the Android 15 advertising-slot leak mitigation exists only as the Experimental switch
     * `test_adv_quiesce_stop` (default off). It becomes an effect here when an A/B on an Android 15 phone supports it.
     */
    val A15_QUIESCE = DevicePolicyEntry(
        id = ID_A15_QUIESCE,
        title = "Android 15: disable an advertising set and let that confirm before stopping it",
        matcher = DeviceMatcher(sdkMin = 35, sdkMax = 35),
        effects = emptyList(),
        behaviour = "Hypothesis (Experimental, switch test_adv_quiesce_stop, default off): Android 15's advertising manager leaks " +
            "an instance when the address-update completion arrives right after the set was removed, until Bluetooth restarts. " +
            "Rotation does nothing for a set that is not enabled, so disabling a set and waiting for onAdvertisingEnabled(false) " +
            "before stopAdvertisingSet shrinks the window. The fix is in Android 16 and may already reach Android 15 phones " +
            "through a Bluetooth module update, so the security-patch window of the affected builds is unknown. " +
            "Promotion needs an A/B on an Android 15 phone that shows the slot count after repeated rotation boundaries.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/e28bb6de3f",
                "2025-01-09, bugs 380772123 / 388615378: set random address completion right after removal leaves the slot unusable"),
            PolicySource(AOSP_SOURCE, "$A14_BT/system/gd/hci/le_advertising_manager.cc",
                "set_advertising_set_random_address_on_timer: rotation of a set that is not enabled is skipped"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no Android 15 phone is owned for this"),
        confidence = LOW,
        confidenceNote = "The leak is a documented AOSP bug; that disabling first avoids it is inferred from the rotation code and untested.",
        reviewed = REVIEWED,
        origin = "Device-family backlog A-02/A-03 root addendum (aosp-bt-versions.md ADV-03).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Sets are stopped directly, as before.",
    )

    /** The batch's entries; the root adds one concatenation line to the registry aggregate. */
    val entries: List<DevicePolicyEntry> =
        listOf(NON_THROWING_START, BUDGET_LEARNED, WATCHDOG_RESTART, STUCK_REPORT, LEGACY_PAYLOAD, MULTI_ADV_FLAG, A15_QUIESCE)

    /** True unless `test_device_policy_off=<id>` (or `all`) switched the entry off on this run. */
    fun enabled(id: String): Boolean = !DevicePolicyOff.isOff(id, DeviceFamilyPolicy.switchedOff)

    fun nonThrowingStart(): Boolean = enabled(ID_NON_THROWING_START)

    fun budgetOn(): Boolean = enabled(ID_BUDGET)

    fun preemptOn(): Boolean = budgetOn() && AdvertisingSwitches.budgetPreempt

    fun stopSettleOn(): Boolean = budgetOn() && AdvertisingSwitches.stopSettle

    fun watchdogOn(): Boolean = enabled(ID_WATCHDOG) && AdvertisingSwitches.watchdogRestart

    fun stuckReportOn(): Boolean = enabled(ID_STUCK)
}
