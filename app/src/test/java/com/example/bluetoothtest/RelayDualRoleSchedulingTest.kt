package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay dual-role scheduling (relayfix-0725a213 diagM1/diagP1/diagP2, mesh4ab-52681dbf-loop14b-1 HCI
 * snoops): a relay's child link at the parent's 15 ms interval can get no events at all.
 */
class RelayDualRoleSchedulingTest {
    @Test
    fun aSilentChildLinkIsStalledOnlyAfterTheWindow() {
        val guard = RelayChildStallGuard(startedAtMs = 10_000L)
        assertNull(guard.stalledFor(10_000L))
        assertNull(guard.stalledFor(11_999L))
        assertEquals(2_000L, guard.stalledFor(12_000L))
        assertEquals(2_750L, guard.stalledFor(12_750L))
        assertTrue(guard.isOpen())
    }

    @Test
    fun inboundBytesRestartTheWindowAndTheLongestGapIsKept() {
        val guard = RelayChildStallGuard(startedAtMs = 0L)
        guard.onProgress(120L) // a healthy hello reply: 59-214 ms in 30 measured links
        assertNull(guard.stalledFor(2_100L))
        guard.onProgress(1_300L)
        assertNull(guard.stalledFor(3_200L))
        assertEquals(2_000L, guard.stalledFor(3_300L))
        assertEquals(1_180L, guard.maxSilentMs())
    }

    @Test
    fun anOlderProgressStampNeverMovesTheWindowBack() {
        val guard = RelayChildStallGuard(startedAtMs = 0L)
        guard.onProgress(1_000L)
        guard.onProgress(400L) // a late reader thread
        assertNull(guard.stalledFor(2_900L))
        assertEquals(2_000L, guard.stalledFor(3_000L))
    }

    @Test
    fun aCompletedHandshakeStopsTheGuardForGood() {
        val guard = RelayChildStallGuard(startedAtMs = 5_000L)
        guard.onProgress(5_150L)
        guard.complete(5_400L)
        assertFalse(guard.isOpen())
        assertNull(guard.stalledFor(60_000L))
        assertEquals(400L, guard.handshakeMs())
        guard.complete(9_000L) // a second call changes nothing
        assertEquals(400L, guard.handshakeMs())
    }

    @Test
    fun anOpenGuardReportsNoHandshakeTime() {
        assertNull(RelayChildStallGuard(startedAtMs = 0L).handshakeMs())
    }

    @Test
    fun theProgressStreamReportsEveryReadThatDeliversBytes() {
        var reports = 0
        val stream = ProgressInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))) { reports++ }
        assertEquals(1, stream.read())
        assertEquals(1, reports)
        val buffer = ByteArray(8)
        assertEquals(4, stream.read(buffer, 0, 8))
        assertEquals(2, reports)
        assertEquals(-1, stream.read()) // end of stream is not progress
        assertEquals(-1, stream.read(buffer, 0, 8))
        assertEquals(2, reports)
    }

    @Test
    fun aStallBeforeHopAuthenticationCountsAsAnEstablishmentFailure() {
        assertTrue(isRelayStallEstablishmentFailure(stalled = true, hopAuthenticated = false))
        // After hop authentication the existing rule (no count) stands.
        assertFalse(isRelayStallEstablishmentFailure(stalled = true, hopAuthenticated = true))
        assertFalse(isRelayStallEstablishmentFailure(stalled = false, hopAuthenticated = false))
    }

    @Test
    fun theSupervisionTimeoutMessageAloneIsNotAnEstablishmentFailure() {
        // Why the guard flags the failure itself: the stack reports the 5 s death as status 8, which the
        // message rule has never matched (it covers 133/62/"establish").
        assertFalse(isRelayEstablishmentFailure(false, "GATT video receiver disconnected (status 8)"))
        assertTrue(isRelayEstablishmentFailure(false, "GATT video connection failed (status 62)"))
    }

    @Test
    fun theGuardWindowSitsBetweenTheMeasuredReplyAndTheSupervisionTimeout() {
        val slowestHealthyReplyMs = 214L
        val supervisionTimeoutMs = 5_000L
        assertTrue(RelayChildStallGuard.DEFAULT_STALL_MS >= 9 * slowestHealthyReplyMs)
        assertTrue(RelayChildStallGuard.DEFAULT_STALL_MS < supervisionTimeoutMs / 2)
    }

    @Test
    fun upstreamDckNeedsTheSwitchARelayCapableChildAndAndroid14() {
        val high = BleConnectionPriority.HIGH
        val api34 = 34
        assertEquals(BleConnectionPriority.DCK, relayUpstreamPriority(high, childCanRelay = true, sdkInt = api34, enabled = true))
        assertEquals(BleConnectionPriority.DCK, relayUpstreamPriority(high, childCanRelay = true, sdkInt = 36, enabled = true))
        // Control arm and every other case keep what was planned.
        assertEquals(high, relayUpstreamPriority(high, childCanRelay = true, sdkInt = api34, enabled = false))
        assertEquals(high, relayUpstreamPriority(high, childCanRelay = false, sdkInt = api34, enabled = true))
        assertEquals(high, relayUpstreamPriority(high, childCanRelay = true, sdkInt = 33, enabled = true))
        assertEquals(
            BleConnectionPriority.BALANCED,
            relayUpstreamPriority(BleConnectionPriority.BALANCED, childCanRelay = true, sdkInt = 31, enabled = true),
        )
    }

    @Test
    fun theSwitchesDefaultToTheDocumentedArms() {
        assertTrue(DebugTunables.relayChildStallGuard)
        assertFalse(DebugTunables.relayUpstreamDck)
    }

    @Test
    fun theExynosFamilyEntryIsAnInertProposalThatMatchesOnlyThatController() {
        val entry = BluetoothTransportPolicy.EXYNOS9611_DUAL_ROLE_EQUAL_INTERVAL
        assertTrue(entry.proposed)
        assertTrue(entry.effects.isEmpty())
        assertEquals(PolicyConfidence.LOW, entry.confidence)
        assertTrue(entry.sources.isNotEmpty())
        assertTrue(entry.evidence.all { it.startsWith("benchmarks/runs/") || it.startsWith("the ASUS") })
        assertTrue(entry.matcher.matches(DeviceTraits("exynos9611", "universal9611", 31, manufacturer = "samsung")))
        assertFalse(entry.matcher.matches(DeviceTraits("qcom", "msmnile", 30, manufacturer = "asus")))
        assertFalse(entry.matcher.matches(DeviceTraits("s5e9945", "erd9945", 36, manufacturer = "samsung")))
        assertTrue(entry in DevicePolicyRegistry.entries)
    }
}
