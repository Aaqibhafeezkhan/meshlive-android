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
