package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MeshSecurityTest {
    private val capabilities = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        maxBitrateKbps = 1_100,
        maxChildren = 2,
    )

    @Test
    fun authenticatedHandshakeDerivesOppositeDirectionKeys() {
        val initiator = MeshHandshake.start(
            KeyPairSigningIdentity(generateEcKeyPair()),
            HandshakeRole.INITIATOR,
            capabilities,
        )
        val responder = MeshHandshake.start(
            KeyPairSigningIdentity(generateEcKeyPair()),
            HandshakeRole.RESPONDER,
            capabilities,
        )
        val initiatorRemote = HandshakeHelloCodec.decode(HandshakeHelloCodec.encode(responder.hello))
        val responderRemote = HandshakeHelloCodec.decode(HandshakeHelloCodec.encode(initiator.hello))

        val initiatorKeys = MeshHandshake.finish(initiator, initiatorRemote)
        val responderKeys = MeshHandshake.finish(responder, responderRemote)

        assertEquals(responder.hello.nodeId, initiatorKeys.peerNodeId)
        assertEquals(initiator.hello.nodeId, responderKeys.peerNodeId)
        assertArrayEquals(initiatorKeys.sendKey, responderKeys.receiveKey)
        assertArrayEquals(initiatorKeys.receiveKey, responderKeys.sendKey)

        val associatedData = "stream/sequence/ttl".toByteArray()
        val plaintext = "encoded VP9 access unit".toByteArray()
        val ciphertext = MeshAead.encrypt(initiatorKeys.sendKey, 7, plaintext, associatedData)
        assertArrayEquals(
            plaintext,
            MeshAead.decrypt(responderKeys.receiveKey, 7, ciphertext, associatedData),
        )
    }

    @Test
    fun modifiedSignedHelloIsRejected() {
        val pending = MeshHandshake.start(
            KeyPairSigningIdentity(generateEcKeyPair()),
            HandshakeRole.INITIATOR,
            capabilities,
        )
        val modified = pending.hello.copy(
            capabilities = pending.hello.capabilities.copy(maxChildren = 9),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MeshHandshake.verify(modified)
        }
    }

    @Test
    fun linkHelloCannotBeReusedAsEndToEndHello() {
        val pending = MeshHandshake.start(
            KeyPairSigningIdentity(generateEcKeyPair()),
            HandshakeRole.INITIATOR,
            capabilities,
            purpose = HandshakePurpose.LINK,
        )

        assertThrows(IllegalArgumentException::class.java) {
            MeshHandshake.verify(pending.hello, HandshakePurpose.END_TO_END)
        }
    }

    @Test
    fun modifiedCiphertextOrAssociatedDataIsRejected() {
        val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
        val ciphertext = MeshAead.encrypt(key, 1, "frame".toByteArray(), "header".toByteArray())
        assertThrows(Exception::class.java) {
            MeshAead.decrypt(key, 1, ciphertext, "modified-header".toByteArray())
        }
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()

        assertThrows(Exception::class.java) {
            MeshAead.decrypt(key, 1, ciphertext, "header".toByteArray())
        }
    }

    @Test
    fun aesGcmMatchesWindowsInteroperabilityVector() {
        val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
        val ciphertext = MeshAead.encrypt(
            key,
            7,
            "cross-platform".toByteArray(Charsets.US_ASCII),
            "BMS1-test-aad".toByteArray(Charsets.US_ASCII),
        )

        assertEquals(
            "831f7c6c643a2e96a52b8940413960b7e55624a70b6b69866e3df1d44810",
            ciphertext.joinToString("") { "%02x".format(it) },
        )
    }
}
