package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothTransportOptionTest {
    @Test
    fun gattIsTheConfiguredDefault() {
        assertEquals(BluetoothTransportOption.BLE_GATT, BluetoothTransportOption.DEFAULT)
    }

    @Test
    fun debugTransportOnlyOverridesWhenExplicitlyRequested() {
        assertNull(debugTransportOverride(null))
        assertEquals(
            BluetoothTransportOption.CLASSIC_RFCOMM,
            debugTransportOverride("classic"),
        )
        assertEquals(BluetoothTransportOption.BLE_L2CAP, debugTransportOverride("ble"))
        assertEquals(BluetoothTransportOption.BLE_GATT, debugTransportOverride("gatt"))
    }

    @Test
    fun transportsExposeIndependentUserBitrateCeilings() {
        assertEquals(1_500, BluetoothTransportOption.CLASSIC_RFCOMM.maxVideoBitrateKbps)
        assertEquals(1_200, BluetoothTransportOption.BLE_L2CAP.maxVideoBitrateKbps)
        assertEquals(1_100, BluetoothTransportOption.BLE_GATT.maxVideoBitrateKbps)
    }

    @Test
    fun onlyLe2mIsSelectable() {
        // 2M is the only user-facing PHY; ONE_M_ADAPTIVE is chosen internally for a peer remembered bad on 2M.
        assertEquals(listOf(BlePhyPreference.TWO_M, BlePhyPreference.ONE_M_ADAPTIVE), BlePhyPreference.entries)
    }
}
