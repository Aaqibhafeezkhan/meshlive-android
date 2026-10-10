package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiTest {
    @Test
    fun readinessOrder() {
        assertEquals(HomeReadiness.UNSUPPORTED, homeReadiness(hasAdapter = false, permissionsGranted = true, bluetoothOn = true))
        assertEquals(HomeReadiness.PERMISSIONS_NEEDED, homeReadiness(true, permissionsGranted = false, bluetoothOn = false))
        assertEquals(HomeReadiness.BLUETOOTH_OFF, homeReadiness(true, permissionsGranted = true, bluetoothOn = false))
        assertEquals(HomeReadiness.READY, homeReadiness(true, permissionsGranted = true, bluetoothOn = true))
    }

    @Test
    fun relayCountFromRouteCost() {
        assertEquals(0, advertisedRelayCount(0))
        assertEquals(3, advertisedRelayCount(3))
        assertNull(advertisedRelayCount(-1))
        assertNull(advertisedRelayCount(200))
    }

    @Test
    fun callRouteDirectWhenAdjacentIsEndpoint() {
        val peer = "0123456789abcdef0123456789abcdef"
        assertEquals(CallRoute.Direct, callRoute("0123456789ab", peer, null))
        assertEquals(CallRoute.Relayed(2), callRoute("ffffffffffff", peer, 2))
        assertEquals(CallRoute.Relayed(null), callRoute("ffffffffffff", peer, null))
        assertEquals(CallRoute.Relayed(null), callRoute("ffffffffffff", peer, 0))
        assertNull(callRoute("", peer, null))
        assertNull(callRoute("0123", null, null))
    }

    @Test
    fun liveChip() {
        assertEquals(LiveChipState.NOT_LIVE, liveChipState("SEND", streaming = false, paused = false, connectedCount = 0))
        assertEquals(LiveChipState.LIVE, liveChipState("SEND", streaming = true, paused = false, connectedCount = 0))
        assertEquals(LiveChipState.PAUSED, liveChipState("SEND", streaming = true, paused = true, connectedCount = 2))
        assertEquals(LiveChipState.WATCH_WAITING, liveChipState("RECEIVE", false, false, 0))
        assertEquals(LiveChipState.WATCHING, liveChipState("RECEIVE", false, false, 1))
        assertEquals(LiveChipState.RELAYING, liveChipState("RELAY", false, false, 1))
    }

    @Test
    fun tripleTapWithinWindow() {
        val detector = MultiTapDetector(count = 3, windowMs = 600)
        assertFalse(detector.onTap(0))
        assertFalse(detector.onTap(500))
        assertTrue(detector.onTap(1000))
        // Reset after firing; a slow tap restarts the sequence.
        assertFalse(detector.onTap(1100))
        assertFalse(detector.onTap(2000))
        assertFalse(detector.onTap(2300))
        assertTrue(detector.onTap(2600))
    }

    @Test
    fun onboardingOnlyForPlainFirstLaunch() {
        assertTrue(shouldShowOnboarding(onboardingDone = false, launchHasExtras = false, recreated = false))
        assertFalse(shouldShowOnboarding(onboardingDone = true, launchHasExtras = false, recreated = false))
        assertFalse(shouldShowOnboarding(onboardingDone = false, launchHasExtras = true, recreated = false))
        assertFalse(shouldShowOnboarding(onboardingDone = false, launchHasExtras = false, recreated = true))
    }

    @Test
    fun identityColourIsStableAndOpaque() {
        val a = identityColor("8a1b2c3d4e5f")
        assertEquals(a, identityColor("8A1B2C3D4E5F"))
        assertEquals(0xFF, a ushr 24)
        assertTrue(identityColor("000000000001") != identityColor("9c8d7e6f5a40"))
    }

    @Test
    fun technicalStatusStaysInTheLog() {
        assertNull(displayStatus("BLE PHY TX 1M / RX 1M"))
        assertNull(displayStatus("Encrypted private-call link to 9c8d7e6f5a40 over BLE GATT"))
        assertNull(displayStatus("Call rate 400 kbps"))
        assertEquals("Peer microphone muted", displayStatus("Peer microphone muted"))
        assertEquals("Call ended. Available for another call.", displayStatus("Call ended. Available for another call."))
        assertNull(displayStatus("  "))
    }

    @Test
    fun identityFormatting() {
        val hex = "0123456789abcdef0123456789abcdef"
        assertEquals("0123 4567 89AB CDEF\n0123 4567 89AB CDEF", IdentityFormat.groupedHex(hex))
        assertEquals("0123 4567", IdentityFormat.shortHex(hex))
        assertEquals("12345 67890 12345 67890", IdentityFormat.groupedDigits("12345678901234567890"))
        assertEquals("ab-12", IdentityFormat.groupedDigits("ab-12"))
        val sixty = (1..60).joinToString("") { (it % 10).toString() }
        assertEquals(3, IdentityFormat.groupedDigits(sixty).lines().size)
        assertEquals("12345 67890 12345 67890", IdentityFormat.groupedDigits(sixty).lines()[0])
    }

    @Test
    fun relayDefaultsAndAutoPause() {
        assertTrue(relayDefaultOn(benchmarkLaunch = false))
        assertFalse(relayDefaultOn(benchmarkLaunch = true))
        assertTrue(relayAutoPaused(batteryPercent = 15, charging = false, batterySaver = false))
        assertFalse(relayAutoPaused(batteryPercent = 15, charging = true, batterySaver = false))
        assertTrue(relayAutoPaused(batteryPercent = 80, charging = true, batterySaver = true))
        assertFalse(relayAutoPaused(batteryPercent = -1, charging = false, batterySaver = false))
    }

    @Test
    fun videoHealthNoticeOnlyWhenRelevant() {
        assertEquals(R.string.video_notice_hot, videoHealthNotice(true, 80, false))
        assertEquals(R.string.video_notice_battery, videoHealthNotice(false, 20, false))
        assertNull(videoHealthNotice(false, 20, true))
        assertNull(videoHealthNotice(false, 50, false))
    }
}
