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
 * Experimental (`test_relay_upstream_dck`, default off): the connection priority a central requests on the
 * link to a child that can relay (`maxChildren > 0`). A relay is peripheral to this link and central to its
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
