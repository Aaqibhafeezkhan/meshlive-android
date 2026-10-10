package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEstablishFallbackTest {
    private val minute = 60_000L

    @Test
    fun twoEstablishmentFailuresWithinTheWindowMarkThePeerForFiveMinutes() {
        // mesh4ab-52681dbf-loop14b-1 0006: M31 relay -> ASUS failed 0x3E (status 133) ten times.
        val tracker = EstablishmentFailureTracker()
        assertFalse(tracker.onFailure("a1b2c3d4e5f6", 0))
        assertFalse(tracker.isMarked("a1b2c3d4e5f6", 1))
        assertTrue(tracker.onFailure("a1b2c3d4e5f6", minute))
        assertTrue(tracker.isMarked("a1b2c3d4e5f6", minute + 1))
        assertTrue(tracker.isMarked("a1b2c3d4e5f6", 6 * minute - 1))
        assertFalse(tracker.isMarked("a1b2c3d4e5f6", 6 * minute))
        // Other peers are unaffected (hop-local, per peer).
        assertFalse(tracker.isMarked("9c8d7e6f5a40", minute + 1))
    }

    @Test
    fun spacedFailuresOrASuccessfulLinkDoNotMark() {
        val tracker = EstablishmentFailureTracker()
        assertFalse(tracker.onFailure("a", 0))
        assertFalse(tracker.onFailure("a", 3 * minute))
        tracker.onSuccess("a")
        assertFalse(tracker.onFailure("a", 3 * minute + 1))
        assertTrue(tracker.onFailure("a", 3 * minute + 2))
        tracker.onSuccess("a")
        assertFalse(tracker.isMarked("a", 3 * minute + 3))
    }

    @Test
    fun onlyLinkEstablishmentFailuresCount() {
        assertTrue(isRelayEstablishmentFailure(false, "BLE control connection failed (status 133)"))
        assertTrue(isRelayEstablishmentFailure(false, "GATT connect status 62"))
        assertFalse(isRelayEstablishmentFailure(true, "BLE control connection failed (status 133)"))
        assertFalse(isRelayEstablishmentFailure(false, "Downstream child cannot decode this stream"))
        assertTrue(isChildEstablishmentFailure(false, "LINK|java.io.IOException:LE CoC accept timed out"))
        assertFalse(isChildEstablishmentFailure(true, "LINK|java.io.IOException:LE CoC accept timed out"))
        assertFalse(isChildEstablishmentFailure(false, "LINK|java.io.IOException:socket closed"))
    }

    @Test
    fun aMarkedParentRanksAfterEveryOtherRouteUnlessTheControlArmIsOn() {
        assertEquals(1, establishmentRank(marked = true, enabled = true))
        assertEquals(0, establishmentRank(marked = false, enabled = true))
        assertEquals(0, establishmentRank(marked = true, enabled = false))
    }
}
