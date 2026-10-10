package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRelayHopTest {
    private val s = 1_000_000_000L

    @Test
    fun aHopLostSoonAfterForwardingStartsOn2MIsEarly() {
        // relaycall-659efc38 0003: the ASUS -> A15s hop reset within seconds of forwarding.
        assertTrue(CallRelayHopLoss.isEarly(100 * s, 106 * s, "PHY TX 2M / RX 2M", requestedTwoM = true, sendLinks = 0))
        // Unreported PHY on a hop the relay asked 2M for still counts (Android 10 GATT clients).
        assertTrue(CallRelayHopLoss.isEarly(100 * s, 106 * s, "PHY pending", requestedTwoM = true, sendLinks = 1))
    }

    @Test
    fun lateLossesOneMegLinksAndUnstartedForwardingAreNotEarly() {
        assertFalse(CallRelayHopLoss.isEarly(100 * s, 116 * s, "PHY TX 2M / RX 2M", requestedTwoM = true, sendLinks = 0))
        assertFalse(CallRelayHopLoss.isEarly(100 * s, 106 * s, "PHY TX 1M / RX 1M", requestedTwoM = true, sendLinks = 0))
        assertFalse(CallRelayHopLoss.isEarly(100 * s, 106 * s, "", requestedTwoM = false, sendLinks = 0))
        assertFalse(CallRelayHopLoss.isEarly(0, 6 * s, "PHY TX 2M / RX 2M", requestedTwoM = true, sendLinks = 0))
        // Never while sending on two or more links of the transport.
        assertFalse(CallRelayHopLoss.isEarly(100 * s, 106 * s, "PHY TX 2M / RX 2M", requestedTwoM = true, sendLinks = 2))
    }

    @Test
    fun aStallBeforeForwardingStartedCountsFromTheStart() {
        assertTrue(CallRelayHopLoss.isEarly(100 * s, 90 * s, "PHY TX 2M / RX 2M", requestedTwoM = true, sendLinks = 0))
    }

    @Test
    fun relayedOffersAreRememberedUnderTheAdvertisedPrefix() {
        assertEquals("8a1b2c3d4e5f", AdaptivePhyMemory.prefixLabel(byteArrayOf(0x8a.toByte(), 0x1b, 0x2c, 0x3d, 0x4e, 0x5f)))
        assertEquals("bad2m2via.BLE_GATT.8a1b2c3d4e5f", AdaptivePhyMemory.viaKey(BluetoothTransportOption.BLE_GATT, "8a1b2c3d4e5f"))
        // Distinct from a hop node entry, so a direct peer is never confused with a relayed offer.
        assertFalse(AdaptivePhyMemory.viaKey(BluetoothTransportOption.BLE_GATT, "x") == AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, "x"))
    }
}
