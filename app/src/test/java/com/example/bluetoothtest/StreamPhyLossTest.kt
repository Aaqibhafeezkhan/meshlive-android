package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A15s (GATT central, 2M) -> ASUS stream source, build d8c7589c (lane4-asus-a15s-m31-d8c7589c-20261013/b6-a15s-source-diag): every
 * 2M link ended with a connection timeout (GATT status 8, supervision 5000 ms) 5.4-17.7 s after hop authentication, i.e. the
 * peer -> source direction had been silent since 0.4-12.7 s. The numbers below are those attempts.
 */
class StreamPhyLossTest {
    private val authAt = 100_000L
    private val supervisionMs = 5_000L

    private fun verdict(
        status: Int = StreamPhyLoss.GATT_CONN_TIMEOUT,
        failure: Throwable? = EOFException(),
        lostAfterAuthMs: Long,
        lastHeartbeatAfterAuthMs: Long = 0L,
        enabled: Boolean = true,
        endedLocally: Boolean = false,
        gatt: Boolean = true,
        wasTwoM: Boolean = true,
        sendLinks: Int = 1,
        peerRelays: Boolean = false,
    ): StreamPhyLoss.Verdict {
        val cause = StreamPhyLoss.cause(status, failure)
        val lostAt = authAt + lostAfterAuthMs
        val silent = StreamPhyLoss.silentSinceMs(
            cause,
            lostAt,
            supervisionMs,
            if (lastHeartbeatAfterAuthMs > 0) authAt + lastHeartbeatAfterAuthMs else 0L,
        )
        return StreamPhyLoss.verdict(enabled, endedLocally, gatt, cause, authAt, silent, wasTwoM, sendLinks, peerRelays)
    }

    @Test
    fun everyMeasuredA15sSourceAttemptIsRemembered() {
        // stream-cold-1 (7.45 s after the PHY update, 6.08, 17.65, 7.86), stream-cold-2 (8.59, 10.81, 8.60), stream-control-1 (7.78, 5.38).
        listOf(7_450L, 6_080L, 17_650L, 7_860L, 8_590L, 10_810L, 8_600L, 7_780L, 5_380L).forEach { lostAfter ->
            val result = verdict(lostAfterAuthMs = lostAfter)
            assertTrue("lost $lostAfter ms after authentication: ${result.reason}", result.record)
        }
    }

    @Test
    fun theWindowCountsFromTheLastReceptionNotFromTheTimeoutReport() {
        // Reported 20 s after authentication, silent since 15 s: still early. Reported at 20.001 s: silent since 15.001 s, not early.
        assertTrue(verdict(lostAfterAuthMs = 20_000L).record)
        assertEquals("not_early", verdict(lostAfterAuthMs = 20_001L).reason)
    }

    @Test
    fun aHeartbeatTimeoutCountsFromTheLastHeartbeat() {
        val timeout = IOException(StreamPhyLoss.HEARTBEAT_TIMED_OUT)
        // Link up 1 s, heartbeat stopped, the sender gave up 6 s later (receiverHeartbeatTimeoutNs for GATT).
        val early = verdict(status = 0, failure = timeout, lostAfterAuthMs = 7_000L, lastHeartbeatAfterAuthMs = 1_000L)
        assertTrue(early.reason, early.record)
        // A healthy stream whose heartbeat stopped long after authentication is a mid-session loss, not a setup problem.
        val late = verdict(status = 0, failure = timeout, lostAfterAuthMs = 60_000L, lastHeartbeatAfterAuthMs = 54_000L)
        assertEquals("not_early", late.reason)
    }

    @Test
    fun aPeerDepartureALocalCloseOrAStackErrorIsNeverLinkEvidence() {
        // 19 = remote user terminated (a viewer leaving), 22 = local host terminated, 133 = generic stack error, 62 = 0x3E.
        listOf(19, 22, 133, 62, 0, -1).forEach { status ->
            val result = verdict(status = status, lostAfterAuthMs = 6_000L)
            assertFalse("status $status", result.record)
            assertEquals("not_link_loss", result.reason)
        }
        assertEquals("ended_locally", verdict(lostAfterAuthMs = 6_000L, endedLocally = true).reason)
    }

    @Test
    fun onlyASingleLinkGattStreamOnTwoMToAPeerThatDoesNotRelayCounts() {
        assertEquals("fan_out", verdict(lostAfterAuthMs = 6_000L, sendLinks = 2).reason)
        assertEquals("hop_relays", verdict(lostAfterAuthMs = 6_000L, peerRelays = true).reason)
        assertEquals("not_two_m", verdict(lostAfterAuthMs = 6_000L, wasTwoM = false).reason)
        assertEquals("not_gatt", verdict(lostAfterAuthMs = 6_000L, gatt = false).reason)
        assertEquals("disabled", verdict(lostAfterAuthMs = 6_000L, enabled = false).reason)
    }

    @Test
    fun aLinkThatNeverOpenedHasNoWindow() {
        val cause = StreamPhyLoss.cause(StreamPhyLoss.GATT_CONN_TIMEOUT, null)
        val result = StreamPhyLoss.verdict(true, false, true, cause, 0L, 0L, true, 1, false)
        assertEquals("not_started", result.reason)
    }

    @Test
    fun silenceNeverPredatesTheClock() {
        // A supervision timeout longer than the link's life must not go negative.
        assertEquals(0L, StreamPhyLoss.silentSinceMs(StreamPhyLoss.Cause.SUPERVISION_TIMEOUT, 3_000L, 5_000L, 0L))
        // No supervision parameters seen: the report time is the best known silence start.
        assertEquals(9_000L, StreamPhyLoss.silentSinceMs(StreamPhyLoss.Cause.SUPERVISION_TIMEOUT, 9_000L, 0L, 0L))
        // A heartbeat time after the loss (clock mix-up) is ignored.
        assertEquals(9_000L, StreamPhyLoss.silentSinceMs(StreamPhyLoss.Cause.HEARTBEAT_TIMEOUT, 9_000L, 5_000L, 9_500L))
    }

    @Test
    fun theFirstDisconnectStatusDecidesTheCause() {
        assertEquals(StreamPhyLoss.Cause.SUPERVISION_TIMEOUT, StreamPhyLoss.cause(8, IOException("anything")))
        assertEquals(StreamPhyLoss.Cause.HEARTBEAT_TIMEOUT, StreamPhyLoss.cause(-1, IOException(StreamPhyLoss.HEARTBEAT_TIMED_OUT)))
        assertEquals(StreamPhyLoss.Cause.OTHER, StreamPhyLoss.cause(-1, EOFException()))
        assertEquals(StreamPhyLoss.Cause.OTHER, StreamPhyLoss.cause(0, null))
    }

    // --- b8 B1 (build 326aed2c): 8 attempts, none recorded; the failures were in the setup, before the stream connected ---

    private val openedAt = 50_000L

    private fun setupVerdict(
        status: Int = -1,
        failure: Throwable? = null,
        connected: Boolean = false,
        lostAfterOpenMs: Long,
        ending: Boolean = false,
        peerKnown: Boolean = true,
    ): StreamPhyLoss.Verdict {
        val cause = StreamPhyLoss.cause(status, failure, connected, lostAfterOpenMs)
        val lostAt = openedAt + lostAfterOpenMs
        val silent = StreamPhyLoss.silentSinceMs(cause, lostAt, supervisionMs, 0L)
        return StreamPhyLoss.verdict(
            true, ending && cause != StreamPhyLoss.Cause.SETUP_TIMEOUT, true, cause, openedAt, silent, true, 0, false, peerKnown,
        )
    }

    @Test
    fun aConnectionTimeoutDuringTheHopHandshakeCountsFromTheLinkOpening() {
        // 16:32:51.3 connect, link open 52.56, status 8 at 59.16 (6.6 s), then 5.1 s and 6.0 s: all in "Authenticating the BLE GATT link".
        listOf(6_600L, 5_150L, 5_130L).forEach { lostAfter ->
            val result = setupVerdict(status = StreamPhyLoss.GATT_CONN_TIMEOUT, failure = EOFException(), lostAfterOpenMs = lostAfter)
            assertTrue("lost $lostAfter ms after opening: ${result.reason}", result.record)
        }
        // A timeout reported earlier than the supervision time cannot put the silence before the link existed.
        assertTrue(setupVerdict(status = StreamPhyLoss.GATT_CONN_TIMEOUT, lostAfterOpenMs = 2_000L).record)
    }

    @Test
    fun theBroadcastersConnectionTimeoutOnAStalledSetupCounts() {
        // The hop handshake took 2.5 to 6.9 s (healthy: 1 s) and the final-receiver handshake never finished; the owner closed at +9.6 to +11 s.
        listOf(10_890L, 11_010L, 9_560L).forEach { lostAfter ->
            val result = setupVerdict(failure = InterruptedException("GATT input interrupted"), lostAfterOpenMs = lostAfter, ending = true)
            assertTrue("owner timeout at $lostAfter ms: ${result.reason}", result.record)
        }
    }

    @Test
    fun aQuickUserCancelOrAConnectedStreamIsNotASetupStall() {
        assertEquals("not_link_loss", setupVerdict(lostAfterOpenMs = StreamPhyLoss.SETUP_STALL_MS - 1).reason)
        assertEquals("not_link_loss", setupVerdict(connected = true, lostAfterOpenMs = 30_000L).reason)
        // A setup that stalled longer than the early-loss window is not recorded either.
        assertEquals("not_early", setupVerdict(lostAfterOpenMs = 15_001L).reason)
    }

    @Test
    fun aRotatingPseudonymThatNeverAuthenticatedNamesNoPeer() {
        assertEquals("unknown_peer", setupVerdict(lostAfterOpenMs = 11_000L, peerKnown = false).reason)
    }

    // --- test_phy_after_auth ---

    @Test
    fun phyAfterAuthAsksForTwoMUnlessThePeerIsRememberedBad() {
        assertEquals(BlePhyPreference.TWO_M, phyAfterAuth(BlePhyPreference.TWO_M, peerBadOnTwoM = false))
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, phyAfterAuth(BlePhyPreference.TWO_M, peerBadOnTwoM = true))
        // The connect-time lookup already chose 1M (an entry under the advertised prefix): the authenticated node never raises it.
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, phyAfterAuth(BlePhyPreference.ONE_M_ADAPTIVE, peerBadOnTwoM = false))
    }

    @Test
    fun aPhyAfterAuthLinkLostBeforeItsTwoMRequestIsNotA2MLoss() {
        // The stack never reported a PHY (empty summary): only what was requested can say 2M.
        val beforeRequest = streamRequestedTwoM(BlePhyPreference.TWO_M, deferPhy = true, deferredRequested = false)
        assertEquals("not_two_m", verdict(lostAfterAuthMs = 6_000L, wasTwoM = linkWasTwoM("", beforeRequest)).reason)
        // Remembered bad after authentication: 1M was kept, nothing to mark again.
        val keptOneM = phyAfterAuth(BlePhyPreference.TWO_M, peerBadOnTwoM = true) == BlePhyPreference.TWO_M
        val rememberedBad = streamRequestedTwoM(BlePhyPreference.TWO_M, deferPhy = true, deferredRequested = keptOneM)
        assertEquals("not_two_m", verdict(lostAfterAuthMs = 6_000L, wasTwoM = linkWasTwoM("", rememberedBad)).reason)
        // Once 2M was asked for after authentication, an early loss counts as before; and without the arm nothing changes.
        val requested = streamRequestedTwoM(BlePhyPreference.TWO_M, deferPhy = true, deferredRequested = true)
        assertTrue(verdict(lostAfterAuthMs = 6_000L, wasTwoM = linkWasTwoM("", requested)).record)
        assertTrue(streamRequestedTwoM(BlePhyPreference.TWO_M, deferPhy = false, deferredRequested = false))
    }

    // --- call stall memory (b2-weaklink-ab call-a15s-asus-control-r1) ---

    @Test
    fun aStalledDirectCallSetupIsRememberedUnderTheAdvertisedPrefix() {
        val prefix = byteArrayOf(0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f)
        assertEquals(
            "5a5b5c5d5e5f",
            AdaptivePhyMemory.advertisedPeerToRemember(true, stalled = true, direct = true, rotating = false, nodePrefix = prefix),
        )
        // The same key phyFor reads back before the next dial.
        assertEquals(AdaptivePhyMemory.prefixLabel(prefix), "5a5b5c5d5e5f")
    }

    @Test
    fun otherSetupFailuresRelayedOffersAndPseudonymsAreNotRemembered() {
        val prefix = ByteArray(6) { 1 }
        assertNull(AdaptivePhyMemory.advertisedPeerToRemember(false, true, true, false, prefix))
        assertNull(AdaptivePhyMemory.advertisedPeerToRemember(true, false, true, false, prefix))
        assertNull(AdaptivePhyMemory.advertisedPeerToRemember(true, true, false, false, prefix))
        assertNull(AdaptivePhyMemory.advertisedPeerToRemember(true, true, true, true, prefix))
        assertNull(AdaptivePhyMemory.advertisedPeerToRemember(true, true, true, false, ByteArray(0)))
        assertNotNull(AdaptivePhyMemory.advertisedPeerToRemember(true, true, true, false, prefix))
    }

    // --- experimental per-direction PHY request (test_gatt_phy_split) ---

    private val m1 = BluetoothDevice.PHY_LE_1M_MASK
    private val m2 = BluetoothDevice.PHY_LE_2M_MASK

    @Test
    fun noSplitKeepsTheSamePhyBothWays() {
        val masks = phyRequestMasks(BlePhyPreference.TWO_M, null, null)
        assertEquals(m2, masks.txMask)
        assertEquals(m2, masks.rxMask)
        val adaptive = phyRequestMasks(BlePhyPreference.ONE_M_ADAPTIVE, null, null)
        assertEquals(m1, adaptive.txMask)
        assertEquals(m1, adaptive.rxMask)
    }

    @Test
    fun aSplitAsksForADifferentPhyPerDirectionOnTheTwoMDefaultOnly() {
        val txOnly = phyRequestMasks(BlePhyPreference.TWO_M, GattPhySplit.TX_2M_RX_1M, null)
        assertEquals(m2, txOnly.txMask)
        assertEquals(m1, txOnly.rxMask)
        val rxOnly = phyRequestMasks(BlePhyPreference.TWO_M, GattPhySplit.TX_1M_RX_2M, null)
        assertEquals(m1, rxOnly.txMask)
        assertEquals(m2, rxOnly.rxMask)
        // A peer remembered bad on 2M stays on 1M both ways; so does a test_ble_phy arm.
        val remembered = phyRequestMasks(BlePhyPreference.ONE_M_ADAPTIVE, GattPhySplit.TX_2M_RX_1M, null)
        assertEquals(m1, remembered.txMask)
        assertEquals(m1, remembered.rxMask)
        val armed = phyRequestMasks(BlePhyPreference.TWO_M, GattPhySplit.TX_2M_RX_1M, ExperimentalBlePhy.LE_1M)
        assertEquals(armed.txMask, armed.rxMask)
    }

    @Test
    fun theSplitParsesItsLaunchValue() {
        assertEquals(GattPhySplit.TX_2M_RX_1M, GattPhySplit.fromDebugValue("tx2m-rx1m"))
        assertEquals(GattPhySplit.TX_1M_RX_2M, GattPhySplit.fromDebugValue(" TX1M-RX2M "))
        assertNull(GattPhySplit.fromDebugValue("2m"))
        assertNull(GattPhySplit.fromDebugValue(null))
    }
}
