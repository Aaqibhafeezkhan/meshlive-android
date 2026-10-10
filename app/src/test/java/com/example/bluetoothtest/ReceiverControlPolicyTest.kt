package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverControlPolicyTest {
    @Test
    fun rejectedProbeDoesNotReplaceTheLastAdmittedParent() {
        val admitted = byteArrayOf(0x22)
        assertArrayEquals(admitted, recoveryParentAfterRouteFailure(admitted, true, byteArrayOf(0x24)))
        assertArrayEquals(byteArrayOf(0x22), recoveryParentAfterRouteFailure(null, true, admitted))
        assertNull(recoveryParentAfterRouteFailure(null, false, byteArrayOf(0x24)))
    }

    @Test
    fun capacityCooldownRefreshesOnlyOnANewGeneration() {
        assertFalse(streamCapacityGenerationAdvanced(5, 5))
        assertFalse(streamCapacityGenerationAdvanced(5, 4))
        assertTrue(streamCapacityGenerationAdvanced(5, 6))
        assertTrue(streamCapacityGenerationAdvanced(127, 0))
    }

    @Test
    fun routeWithdrawalDoesNotConfuseSourceSlotsWithRelayPlayback() {
        assertTrue(streamCapacityWithdrawalNotice(0).contains("connection slot"))
        assertFalse(streamCapacityWithdrawalNotice(0).contains("unstable"))
        assertTrue(streamCapacityWithdrawalNotice(1).contains("connection became unstable"))
        assertFalse(streamCapacityWithdrawalNotice(1).contains("full"))
        assertFalse(streamCapacityWithdrawalNotice(null).contains("overloaded"))
    }

    @Test
    fun genericReconnectDoesNotShortenCapacityWithdrawalSuppression() {
        assertEquals(31_000L, streamRouteSuppressionDeadline(31_000L, 9_000L))
    }

    @Test
    fun laterFailureCanExtendAnExpiredRouteSuppression() {
        assertEquals(40_000L, streamRouteSuppressionDeadline(31_000L, 40_000L))
        assertEquals(9_000L, streamRouteSuppressionDeadline(null, 9_000L))
    }

    @Test
    fun classicTimeoutDoesNotPretendThatPairingIsTheKnownCause() {
        val notice = receiverStartupFailureNotice(
            BluetoothTransportOption.CLASSIC_RFCOMM, 123, "No connection arrived within 15 seconds",
        )
        assertTrue(notice.contains("busy"))
        assertTrue(notice.contains("full"))
        assertTrue(notice.contains("No connection arrived"))
    }

    @Test
    fun classicStartupFailureExplainsThePairingFreeFallback() {
        val notice = receiverStartupFailureNotice(
            BluetoothTransportOption.CLASSIC_RFCOMM,
            streamToken = 123,
            detail = "Try again",
        )

        assertTrue(notice.contains("Pair the phones"))
        assertTrue(notice.contains("BLE GATT"))
        assertEquals(
            "Receiver stopped: GATT unavailable",
            receiverStartupFailureNotice(
                BluetoothTransportOption.BLE_GATT,
                streamToken = 123,
                detail = "GATT unavailable",
            ),
        )
    }

    @Test
    fun gattAvoidsPeriodicReverseTrafficWhileStreamTransportsKeepLivenessHeartbeats() {
        assertFalse(usesPeriodicReceiverHeartbeat(BluetoothTransportOption.BLE_GATT))
        assertTrue(usesPeriodicReceiverHeartbeat(BluetoothTransportOption.BLE_L2CAP))
        assertTrue(usesPeriodicReceiverHeartbeat(BluetoothTransportOption.CLASSIC_RFCOMM))
    }

    @Test
    fun directAndForwardedGattRoutesUseSparseHopLocalLiveness() {
        assertTrue(
            shouldSendPeriodicReceiverHeartbeat(
                BluetoothTransportOption.BLE_GATT,
                meshLink = false,
            ),
        )
        assertTrue(
            shouldSendPeriodicReceiverHeartbeat(
                BluetoothTransportOption.BLE_GATT,
                meshLink = true,
            ),
        )
        assertEquals(2_000L, receiverHeartbeatIntervalMs(BluetoothTransportOption.BLE_GATT))
        assertEquals(6_000_000_000L, receiverHeartbeatTimeoutNs(BluetoothTransportOption.BLE_GATT))
    }

    @Test
    fun sourceExpectsSparseGattHeartbeatFromOrdinaryAndRelayCapableChildren() {
        assertTrue(
            shouldExpectPeriodicChildHeartbeat(
                BluetoothTransportOption.BLE_GATT,
                childCanRelay = false,
            ),
        )
        assertTrue(
            shouldExpectPeriodicChildHeartbeat(
                BluetoothTransportOption.BLE_GATT,
                childCanRelay = true,
            ),
        )
    }

    @Test
    fun directGattDiagnosticChangesBothSidesWithoutChangingRelayCapability() {
        for (enabled in listOf(false, true)) {
            assertEquals(enabled, shouldSendPeriodicReceiverHeartbeat(
                BluetoothTransportOption.BLE_GATT, meshLink = false, debugDirectGattHeartbeat = enabled,
            ))
            assertEquals(enabled, shouldExpectPeriodicChildHeartbeat(
                BluetoothTransportOption.BLE_GATT, childCanRelay = false, debugDirectGattHeartbeat = enabled,
            ))
        }
        assertTrue(shouldSendPeriodicReceiverHeartbeat(
            BluetoothTransportOption.BLE_GATT, meshLink = true, debugDirectGattHeartbeat = false,
        ))
        assertTrue(shouldExpectPeriodicChildHeartbeat(
            BluetoothTransportOption.BLE_GATT, childCanRelay = true, debugDirectGattHeartbeat = false,
        ))
    }

    @Test
    fun firstUnexpectedLossRetriesTheSameRouteImmediatelyAndRepeatedLossesBackOff() {
        assertEquals(0L, unexpectedLossRetryDelayMs(1))
        assertEquals(1_000L, unexpectedLossRetryDelayMs(2))
        assertEquals(4_000L, unexpectedLossRetryDelayMs(4))
        assertEquals(8_000L, unexpectedLossRetryDelayMs(5))
        assertEquals(8_000L, unexpectedLossRetryDelayMs(50))
    }

    @Test
    fun fullSourceOfferIsFlaggedWithoutLosingTheCapacityGeneration() {
        fun offer(sequence: Int) = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES), flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
            capabilitySummary = 1, streamToken = 7, routeCost = 0, sequence = sequence,
        )
        val full = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(offer(DiscoveryAnnouncement.SEQUENCE_SOURCE_FULL or 5)))
        assertTrue(full.isFullStreamOffer())
        assertEquals(5, full.capacityGeneration())
        val open = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(offer(6)))
        assertFalse(open.isFullStreamOffer())
        assertTrue(streamCapacityGenerationAdvanced(full.capacityGeneration(), open.capacityGeneration()))
    }
}
