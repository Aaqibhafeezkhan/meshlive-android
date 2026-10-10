package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallSessionTest {
    @Test
    fun outgoingAttemptDoesNotReplaceAnIncomingOrActiveCall() {
        assertTrue(canStartOutgoingPrivateCall(running = true, state = null, callRunning = false))
        assertFalse(
            canStartOutgoingPrivateCall(
                running = true,
                state = PrivateCallState.incoming(UUID.randomUUID()),
                callRunning = true,
            ),
        )
        assertFalse(canStartOutgoingPrivateCall(running = true, state = null, callRunning = true))
        assertFalse(canStartOutgoingPrivateCall(running = false, state = null, callRunning = false))
    }

    @Test
    fun remotePreviewStaysHiddenUntilCallVideoStarts() {
        assertFalse(shouldRevealPrivateCallRemotePreview(isCallScreen = true, remoteVideoStarted = false))
        assertFalse(shouldRevealPrivateCallRemotePreview(isCallScreen = false, remoteVideoStarted = true))
        assertTrue(shouldRevealPrivateCallRemotePreview(isCallScreen = true, remoteVideoStarted = true))
    }

    @Test
    fun privateCallPeerSurvivesBalancedBleGapsThenExpires() {
        val lastSeenMs = 1_000L

        assertTrue(privateCallPeerIsFresh(lastSeenMs, lastSeenMs + 6_000L))
        assertTrue(privateCallPeerIsFresh(lastSeenMs, lastSeenMs + PRIVATE_CALL_PEER_STALE_MS))
        assertFalse(privateCallPeerIsFresh(lastSeenMs, lastSeenMs + PRIVATE_CALL_PEER_STALE_MS + 1L))
    }

    @Test
    fun selectedPrivateCallPeerRequiresANewAddressObservation() {
        assertFalse(privateCallPeerWasRefreshed(observedAtMs = 999L, selectedAtMs = 1_000L))
        assertTrue(privateCallPeerWasRefreshed(observedAtMs = 1_000L, selectedAtMs = 1_000L))
        assertTrue(privateCallPeerWasRefreshed(observedAtMs = 1_001L, selectedAtMs = 1_000L))
    }

    @Test
    fun classicConnectionFailureExplainsThePairingFreeCallFallback() {
        val classic = privateCallConnectionFailureMessage(
            BluetoothTransportOption.CLASSIC_RFCOMM,
            "read failed",
        )

        assertTrue(classic.contains("Pair the phones"))
        assertTrue(classic.contains("BLE GATT"))
        assertEquals(
            "Call could not connect: unavailable",
            privateCallConnectionFailureMessage(BluetoothTransportOption.BLE_GATT, "unavailable"),
        )
    }

    @Test
    fun callSignalsRoundTripAndRejectInvalidReasons() {
        val callId = UUID.fromString("f2af30d1-5858-43f6-bdbf-a38efdc64002")
        val messages = listOf(
            PrivateCallSignalMessage(callId, PrivateCallSignal.INVITE, relayHops = 2),
            PrivateCallSignalMessage(callId, PrivateCallSignal.RINGING),
            PrivateCallSignalMessage(callId, PrivateCallSignal.ACCEPT),
            PrivateCallSignalMessage(callId, PrivateCallSignal.DECLINE, PrivateCallReason.BUSY),
            PrivateCallSignalMessage(callId, PrivateCallSignal.CANCEL),
            PrivateCallSignalMessage(callId, PrivateCallSignal.HANG_UP, PrivateCallReason.NORMAL),
        )

        messages.forEach { message ->
            assertEquals(message, PrivateCallSignalCodec.decode(PrivateCallSignalCodec.encode(message)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSignalMessage(callId, PrivateCallSignal.ACCEPT, PrivateCallReason.NORMAL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSignalMessage(callId, PrivateCallSignal.DECLINE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSignalMessage(UUID(0L, 0L), PrivateCallSignal.INVITE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSignalMessage(callId, PrivateCallSignal.ACCEPT, relayHops = 1)
        }

        val invalidMagic = PrivateCallSignalCodec.encode(messages.first()).also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSignalCodec.decode(invalidMagic)
        }
        val unknownSignal = PrivateCallSignalCodec.encode(messages.first()).also { it[4] = 99 }
        assertThrows(IllegalStateException::class.java) {
            PrivateCallSignalCodec.decode(unknownSignal)
        }
    }

    @Test
    fun callerAndCalleeReachActiveThenEnd() {
        val callId = UUID.fromString("f2af30d1-5858-43f6-bdbf-a38efdc64003")
        val caller = PrivateCallState.outgoing(callId)
            .transition(PrivateCallEvent.REMOTE_RINGING)
            .transition(PrivateCallEvent.REMOTE_ACCEPT)
            .transition(PrivateCallEvent.MEDIA_READY)
        val callee = PrivateCallState.incoming(callId)
            .transition(PrivateCallEvent.LOCAL_ACCEPT)
            .transition(PrivateCallEvent.MEDIA_READY)

        assertEquals(PrivateCallPhase.ACTIVE, caller.phase)
        assertEquals(PrivateCallPhase.ACTIVE, callee.phase)
        assertEquals(PrivateCallPhase.ENDED, caller.transition(PrivateCallEvent.LOCAL_HANG_UP).phase)
        assertEquals(PrivateCallPhase.ENDED, callee.transition(PrivateCallEvent.REMOTE_HANG_UP).phase)
    }

    @Test
    fun invalidCallTransitionsFailClosed() {
        val outgoing = PrivateCallState.outgoing()
        assertThrows(IllegalStateException::class.java) {
            outgoing.transition(PrivateCallEvent.LOCAL_ACCEPT)
        }
        val ended = outgoing.transition(PrivateCallEvent.LOCAL_CANCEL)
        assertThrows(IllegalStateException::class.java) {
            ended.transition(PrivateCallEvent.REMOTE_RINGING)
        }
    }

    @Test
    fun privateCallDiscoveryDoesNotLookLikeABroadcast() {
        val callOffer = DiscoveryAnnouncement(
            nodeIdPrefix = byteArrayOf(1, 2, 3, 4, 5, 6),
            flags = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = 123,
            routeCost = 0,
            sequence = 0,
        )

        assertTrue(callOffer.isPrivateCallOffer())
        assertEquals(false, callOffer.isStreamOffer())
        assertTrue(
            DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(callOffer))
                .isPrivateCallOffer(),
        )
    }

    @Test
    fun sendFailureAfterThePeersHangUpIsNotACallFailure() {
        assertTrue(privateCallAlreadyEnded(PrivateCallPhase.ENDED))
        assertFalse(privateCallAlreadyEnded(PrivateCallPhase.ACTIVE))
        assertFalse(privateCallAlreadyEnded(null))
    }
}
