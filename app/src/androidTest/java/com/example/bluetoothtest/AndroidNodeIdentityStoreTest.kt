package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AndroidNodeIdentityStoreTest {
    @Test
    fun keystoreIdentityPersistsAndCompletesAuthenticatedEncryptedHandshake() {
        val store = AndroidNodeIdentityStore("bluetooth-video-mesh-instrumentation-test")
        store.delete()
        try {
            val firstIdentity = store.getOrCreate()
            val secondLoad = store.getOrCreate()
            assertEquals(firstIdentity.nodeId, secondLoad.nodeId)
            assertArrayEquals(firstIdentity.publicKeyEncoded, secondLoad.publicKeyEncoded)

            val capabilities = NodeCapabilities(
                transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
                encodeCodecMask = NodeCapabilities.CODEC_VP9,
                decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
                maxBitrateKbps = 1_100,
                maxChildren = 2,
            )
            val initiator = MeshHandshake.start(firstIdentity, HandshakeRole.INITIATOR, capabilities)
            val responder = MeshHandshake.start(
                KeyPairSigningIdentity(generateEcKeyPair()),
                HandshakeRole.RESPONDER,
                capabilities,
            )
            val initiatorKeys = MeshHandshake.finish(
                initiator,
                HandshakeHelloCodec.decode(HandshakeHelloCodec.encode(responder.hello)),
            )
            val responderKeys = MeshHandshake.finish(
                responder,
                HandshakeHelloCodec.decode(HandshakeHelloCodec.encode(initiator.hello)),
            )
            val plaintext = "Samsung Android Keystore mesh test".toByteArray()
            val aad = "test-link".toByteArray()

            assertArrayEquals(
                plaintext,
                MeshAead.decrypt(
                    responderKeys.receiveKey,
                    0,
                    MeshAead.encrypt(initiatorKeys.sendKey, 0, plaintext, aad),
                    aad,
                ),
            )
        } finally {
            store.delete()
        }
    }
}
