package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.os.Build

enum class BlePhyPreference(
    private val baseLabel: String,
    private val baseMask: Int,
    private val baseOption: Int,
) {
    TWO_M("2M", BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED),

    /** 1M for one peer that [AdaptivePhyMemory] remembers bad on 2M (default-on adaptive PHY). */
    ONE_M_ADAPTIVE("1M (adaptive)", BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED),
    ;

    /** 2M; another PHY only under the DEBUG experiment `test_ble_phy` ([DebugTunables.experimentalBlePhy]). */
    val mask: Int
        get() = DebugTunables.experimentalBlePhy?.mask ?: baseMask

    val option: Int
        get() = DebugTunables.experimentalBlePhy?.option ?: baseOption

    val label: String
        get() = DebugTunables.experimentalBlePhy?.label ?: baseLabel
}

/**
 * Experimental LE PHYs (DEBUG `test_ble_phy`, default off: 2M). LE 1M is the ASUS <-> A15s control.
 * LE Coded trades rate for range (Core Vol 6 Part A 1: 500 kb/s with S=2, 125 kb/s with S=8);
 * measured GATT 154/47 kbps and CoC 81/40 kbps on S24 -> S22 (DECISIONS "Bounded payload"), enough
 * for a voice call (Opus 32 kbps). Both phones must support it (BluetoothAdapter.isLeCodedPhySupported:
 * the M31 does not); otherwise the PHY update keeps the current PHY. Connections are still set up
 * on LE 1M (legacy advertising), so discovery range does not change, only the range of an
 * established link. Learned link capacity is stored apart from 2M ([phyCapacityKey]).
 */
enum class ExperimentalBlePhy(
    val debugValue: String,
    val mask: Int,
    val option: Int,
    val label: String,
) {
    LE_1M("1m", BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED, "1M (experimental)"),
    CODED_S2("coded-s2", BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S2, "Coded S2 (experimental)"),
    CODED_S8("coded-s8", BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S8, "Coded S8 (experimental)"),
    ;

    val coded: Boolean get() = mask == BluetoothDevice.PHY_LE_CODED_MASK

    /** Conservative single-link seed for a child link pacer on this PHY (measured S24 -> S22 maxima). */
    fun pacerPriorKbps(transport: BluetoothTransportOption): Int? = when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> null
        BluetoothTransportOption.BLE_GATT -> when (this) {
            LE_1M -> 250
            CODED_S2 -> 120
            CODED_S8 -> 40
        }
        BluetoothTransportOption.BLE_L2CAP -> when (this) {
            LE_1M -> 200
            CODED_S2 -> 70
            CODED_S8 -> 35
        }
    }

    companion object {
        fun fromDebugValue(value: String?): ExperimentalBlePhy? =
            entries.firstOrNull { it.debugValue.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Experimental (DEBUG `test_gatt_phy_split`, default off): a GATT client that asks for a different PHY per direction. Android's
 * `setPreferredPhy(txPhy, rxPhy, options)` takes both masks, and the link layer may settle on an asymmetric pair (Core Vol 6 Part B
 * 5.1.10 PHY Update Procedure). Why: with the A15s as GATT central to the ASUS, the peripheral -> central direction went silent
 * on 2M (central -> peripheral kept delivering until the central gave up) while 1M was healthy
 * (lane4-asus-a15s-m31-d8c7589c-20261013/b6-a15s-source-diag; DECISIONS "A15s as sender"). `tx2m-rx1m` is the arm that keeps the
 * central's media direction on 2M; `tx1m-rx2m` is the mirror, so a pair of runs names the failing direction. Applies only to
 * the 2M default preference: a remembered-bad peer ([BlePhyPreference.ONE_M_ADAPTIVE]) and a `test_ble_phy` arm keep their PHY.
 */
enum class GattPhySplit(val debugValue: String, val txMask: Int, val rxMask: Int) {
    TX_2M_RX_1M("tx2m-rx1m", BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_1M_MASK),
    TX_1M_RX_2M("tx1m-rx2m", BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_LE_2M_MASK),
    ;

    companion object {
        fun fromDebugValue(value: String?): GattPhySplit? =
            entries.firstOrNull { it.debugValue.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * `test_phy_after_auth`: the PHY to ask for once the hop peer is authenticated. The connect-time choice [connectPhy] already
 * reflects a memory entry found under the advertised prefix; the authenticated node adds the entry a rotating pseudonym could
 * never be matched to.
 */
internal fun phyAfterAuth(connectPhy: BlePhyPreference, peerBadOnTwoM: Boolean): BlePhyPreference =
    if (connectPhy != BlePhyPreference.TWO_M || peerBadOnTwoM) BlePhyPreference.ONE_M_ADAPTIVE else BlePhyPreference.TWO_M

/**
 * Whether a stream link asked the stack for 2M, which the early-loss rule ([linkWasTwoM]) reads when the stack never reported a
 * PHY. With `test_phy_after_auth` the link opens on 1M and 2M is requested only after hop authentication ([deferredRequested]);
 * a link lost before that never ran on 2M (switch audit 2026-10-27, A6: it counted as a 2M loss and marked the peer bad on 2M).
 */
internal fun streamRequestedTwoM(linkPhy: BlePhyPreference, deferPhy: Boolean, deferredRequested: Boolean): Boolean =
    if (deferPhy) deferredRequested else linkPhy == BlePhyPreference.TWO_M

/** The masks and option a GATT client passes to `setPreferredPhy`. */
internal data class PhyRequestMasks(val txMask: Int, val rxMask: Int, val option: Int)

internal fun phyRequestMasks(
    preference: BlePhyPreference,
    split: GattPhySplit?,
    experimentalPhy: ExperimentalBlePhy? = DebugTunables.experimentalBlePhy,
): PhyRequestMasks =
    if (split != null && preference == BlePhyPreference.TWO_M && experimentalPhy == null) {
        PhyRequestMasks(split.txMask, split.rxMask, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
    } else {
        PhyRequestMasks(preference.mask, preference.mask, preference.option)
    }

/**
 * Experimental adaptive split (DEBUG `test_adaptive_phy_split`, default off): when the adaptive PHY decision would move a GATT link
 * from 2M to 1M (`one_pdu_per_event` or `stalled_one_way`), the central first asks for [arm] instead: only the peripheral -> central
 * direction goes to 1M and the central's own transmit direction (the measured, media-carrying writer) keeps 2M. The split is
 * tried once per link; a link that decides again after it moves to 1M and records the peer as before. Returns the split to
 * request, or null for the existing full move to 1M, which is used when the switch is off, on any link but GATT ("gatt"; the
 * CoC control link is untested), when the link already started split (`test_gatt_phy_split`) or on an experimental PHY arm
 * (`test_ble_phy`), and when the split was already tried on this link.
 */
internal fun adaptiveSplitFor(
    arm: GattPhySplit?,
    link: String,
    staticSplit: GattPhySplit?,
    experimentalPhy: ExperimentalBlePhy?,
    alreadySplit: Boolean,
): GattPhySplit? =
    if (arm != null && link == "gatt" && staticSplit == null && experimentalPhy == null && !alreadySplit) arm else null

/** The `to=` value of the adaptive_phy line for a split move, e.g. `split-tx2m-rx1m`. */
internal fun adaptiveSplitLabel(split: GattPhySplit): String = "split-${split.debugValue}"

/**
 * Whether the central's link still counts as a 2M link for the adaptive decision. After a split the transmit PHY is the one the
 * split kept ([GattPhySplit.TX_2M_RX_1M]: 2M, so measuring continues) or the mirror, whose transmit PHY is 1M but whose receive
 * direction is still 2M (a second failure escalates to full 1M, which changes that direction too).
 */
internal fun adaptiveLinkOnTwoM(txPhy: Int, splitApplied: GattPhySplit?): Boolean =
    txPhy == LePhy.TWO_M || (splitApplied == GattPhySplit.TX_1M_RX_2M && txPhy == LePhy.ONE_M)

/** Suffix that keeps capacity learned on an experimental LE PHY apart from the 2M default. */
internal fun phyCapacityKey(transport: BluetoothTransportOption, phy: ExperimentalBlePhy?): String =
    if (phy == null || transport == BluetoothTransportOption.CLASSIC_RFCOMM) "" else ".phy-${phy.debugValue}"

enum class BleConnectionPriority(
    val label: String,
    private val value: Int,
) {
    HIGH("high", BluetoothGatt.CONNECTION_PRIORITY_HIGH),
    BALANCED("balanced", BluetoothGatt.CONNECTION_PRIORITY_BALANCED),
    DCK("DCK", BluetoothGatt.CONNECTION_PRIORITY_DCK),

    /** DEBUG experiments only (relay child-link interleaving): Android LOW_POWER, 100-125 ms. */
    LOW("low", BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);

    val requestValue: Int
        get() = if (this == DCK && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            BluetoothGatt.CONNECTION_PRIORITY_HIGH
        } else {
            value
        }

    companion object {
        fun fromDebugValue(value: String?): BleConnectionPriority = when (value?.lowercase()) {
            "dck" -> DCK
            "balanced" -> BALANCED
            "low" -> LOW
            else -> HIGH
        }
    }
}

internal fun gattVideoPriority(videoBitrateKbps: Int): BleConnectionPriority {
    require(videoBitrateKbps >= 0)
    return if (videoBitrateKbps >= 500) {
        BleConnectionPriority.DCK
    } else {
        BleConnectionPriority.HIGH
    }
}

internal fun forwarderGattPriority(
    videoBitrateKbps: Int,
    debugOverride: BleConnectionPriority?,
    isDebugBuild: Boolean,
): BleConnectionPriority {
    val productionPriority = gattVideoPriority(videoBitrateKbps)
    return if (isDebugBuild) debugOverride ?: productionPriority else productionPriority
}

/**
 * Experimental, default off (`test_relay_upstream_dck=true`; DebugTunables.relayUpstreamDck): the connection priority a central
 * requests on the link to a child that can relay (`maxChildren > 0`). It was worse with a child on phones, so it stays off. A relay is peripheral to this link and central to its
 * own child link, and both settle at HIGH (11.25-15 ms, 15 ms on every controller seen). Two links at the
 * same interval keep a fixed relative anchor phase for the whole connection, and on the Exynos 9611
 * controller (M31) a child anchor in the wrong part of the parent's cycle starves the child link
 * completely (relayfix-0725a213 diagM1/diagP2: 9 of 55 attempts dead, 22 never established). With the
 * parent at DCK (30 ms, `gatt_dck_priority_*_interval` = 24 units in AOSP) the relay's 15 ms child link
 * has two events per parent event, so one of them always falls outside any parent window shorter than
 * 15 ms minus one child exchange. DCK exists from Android 14 (API 34); older centrals keep [planned].
 * Hypothesis under A/B, not a measured fix; Core Spec Vol 6 Part B 4.5 leaves overlapping events of two
 * connections to the controller.
 */
internal fun relayUpstreamPriority(
    planned: BleConnectionPriority,
    childCanRelay: Boolean,
    sdkInt: Int,
    enabled: Boolean,
): BleConnectionPriority =
    if (enabled && childCanRelay && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) BleConnectionPriority.DCK else planned
