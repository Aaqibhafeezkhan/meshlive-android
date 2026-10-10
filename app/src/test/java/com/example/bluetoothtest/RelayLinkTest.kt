package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayLinkTest {
    @Test
    fun aStarvedRelayChildIsAtMostAboutOnePduPerEvent() {
        val oneEvent = AdaptivePhyPolicy.oneEventKbps(15.0)
        // relaylink-gattdef-7a6c401c: 11 kbps starved, 296 kbps healthy, both at 15 ms.
        assertTrue(relayChildStarved(11, oneEvent))
        assertTrue(relayChildStarved(160, oneEvent))
        assertFalse(relayChildStarved(296, oneEvent))
        assertFalse(relayChildStarved(11, 0))
    }

    @Test
    fun relayUpstreamCountsNestedRelaysAndNeverGoesNegative() {
        assertFalse(RelayUpstream.active)
        RelayUpstream.enter(); RelayUpstream.enter()
        RelayUpstream.exit()
        assertTrue(RelayUpstream.active)
        RelayUpstream.exit(); RelayUpstream.exit()
        assertFalse(RelayUpstream.active)
    }

    @Test
    fun memoryFormat2KeysIgnoreFormat1Entries() {
        assertEquals("bad2m2.BLE_L2CAP.9c8d7e6f5a40", AdaptivePhyMemory.key(BluetoothTransportOption.BLE_L2CAP, "9c8d7e6f5a40"))
        assertFalse(AdaptivePhyMemory.key(BluetoothTransportOption.BLE_L2CAP, "x").startsWith("bad2m."))
    }

    @Test
    fun relayDebugPrioritiesParse() {
        assertEquals(BleConnectionPriority.LOW, BleConnectionPriority.fromDebugValue("low"))
        assertEquals(BleConnectionPriority.BALANCED, BleConnectionPriority.fromDebugValue("balanced"))
    }
}
