package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEarlyLossTest {
    private val s = 1_000_000_000L

    @Test
    fun theWindowStartsAtMediaStartWhenTheCallReachedActive() {
        // A15s caller (xpair-asusa15s-351baa8e-fb2257/0003): authenticated at 0 s, ACTIVE at 16.4 s,
        // link stalled about 7 s later, write timeout reported at 23.3 s.
        val start = callEarlyLossWindowStartNs(authenticatedAtNs = 100 * s, activeAtNs = 116 * s + s * 4 / 10)
        val stalled = callLinkStalledAtNs(lastReadNs = 123 * s, lastSendNs = 122 * s, nowNs = 123 * s + s * 3 / 10)
        assertTrue(AdaptivePhyMemory.isEarlyLoss(start / 1_000_000, stalled / 1_000_000, wasTwoM = true, sendLinks = 1))
        // Measured from authentication to detection, as before, it was outside the 15 s window.
        assertFalse(AdaptivePhyMemory.isEarlyLoss(100_000, 123_300, wasTwoM = true, sendLinks = 1))
    }

    @Test
    fun beforeActiveTheWindowStartsAtAuthentication() {
        assertEquals(100 * s, callEarlyLossWindowStartNs(100 * s, 0))
    }

    @Test
    fun theStallIsTheEarlierDirectionToStopAndNeverAfterNow() {
        assertEquals(5 * s, callLinkStalledAtNs(lastReadNs = 7 * s, lastSendNs = 5 * s, nowNs = 9 * s))
        assertEquals(9 * s, callLinkStalledAtNs(lastReadNs = 0, lastSendNs = 0, nowNs = 9 * s))
        assertEquals(9 * s, callLinkStalledAtNs(lastReadNs = 12 * s, lastSendNs = 0, nowNs = 9 * s))
    }

    @Test
    fun anUnconfirmedPhyCountsAs2MOnlyWhen2MWasRequested() {
        // The A15s (Android 10) GATT client may never report a PHY update ("PHY pending").
        assertTrue(linkWasTwoM("PHY pending", requestedTwoM = true))
        assertTrue(linkWasTwoM("", requestedTwoM = true))
        assertTrue(linkWasTwoM("PHY TX 2M / RX 2M", requestedTwoM = false))
        assertFalse(linkWasTwoM("PHY TX 1M / RX 1M", requestedTwoM = true))
        assertFalse(linkWasTwoM("PHY pending", requestedTwoM = false))
    }
}
