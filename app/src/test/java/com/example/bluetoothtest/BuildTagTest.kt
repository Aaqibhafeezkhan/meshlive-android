package com.example.bluetoothtest

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTagTest {
    private val capabilities = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP or RotatingDiscoveryIds.LINK_HELLO_BIT,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        maxBitrateKbps = 1_100,
        maxChildren = 0,
    )

    @After
    fun resetHooks() {
        LinkBuildTags.local = null
        LinkBuildTags.sink = null
    }

    private fun identity() = KeyPairSigningIdentity(generateEcKeyPair())

    /** A fixed-output random source, so nonce bytes 2..31 can be compared with the untagged case. */
    private class FixedRandom(private val fill: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(fill.toByte())
        }
    }

    @Test
    fun tagEncodesReleaseNumberAndKeyBitAndRoundTrips() {
        assertEquals(0x0001, BuildTag(1, false).encode())
        assertEquals(0x8001, BuildTag(1, true).encode())
        assertEquals(0x7FFF, BuildTag(ReleaseNumber.MAX, false).encode())
        for (value in listOf(1, 2, 12, 300, 0x7FFF, 0x8001, 0xFFFF)) {
            assertEquals(value, BuildTag.decode(value)!!.encode())
        }
        assertNull("release number 0 is not a tag", BuildTag.decode(0))
        assertNull(BuildTag.decode(0x8000))
        assertNull(BuildTag.decode(0x1_0001))
        assertThrows(IllegalArgumentException::class.java) { BuildTag(0, false) }
        assertThrows(IllegalArgumentException::class.java) { BuildTag(ReleaseNumber.MAX + 1, false) }
    }

    @Test
    fun forBuildNeedsASplitReleaseAndTheSwitch() {
        assertEquals(BuildTag(1, true), BuildTag.forBuild(12, signedWithReleaseKey = true, enabled = true))
        assertEquals(BuildTag(2, false), BuildTag.forBuild(20, signedWithReleaseKey = false, enabled = true))
        assertNull("switch off", BuildTag.forBuild(12, true, enabled = false))
        assertNull("non-split debug build", BuildTag.forBuild(1, true, enabled = true))
        assertNull(BuildTag.forBuild(9, true, enabled = true))
    }

    @Test
    fun theTagNeverRevealsTheCpuType() {
        val tags = listOf(10L, 11L, 12L).map { BuildTag.forBuild(it, true, true)!!.encode() }
        assertEquals(1, tags.toSet().size)
    }

    @Test
    fun aTaggedLinkHelloKeepsItsLengthAndOnlyChangesBitSevenAndTwoNonceBytes() {
        val id = identity()
        val plain = MeshHandshake.start(id, HandshakeRole.INITIATOR, capabilities, FixedRandom(0x5A))
        val tagged = MeshHandshake.start(
            id,
            HandshakeRole.INITIATOR,
            capabilities,
            FixedRandom(0x5A),
            buildTag = BuildTag(7, true),
        )
        assertEquals(MeshHandshake.NONCE_BYTES, tagged.hello.nonce.size)
        assertEquals(capabilities.transportMask or BuildTag.LINK_HELLO_BIT, tagged.hello.capabilities.transportMask)
        assertEquals(capabilities.transportMask, plain.hello.capabilities.transportMask)
        assertEquals(0x80, tagged.hello.nonce[0].toInt() and 0xff)
        assertEquals(0x07, tagged.hello.nonce[1].toInt())
        assertArrayEquals(
            plain.hello.nonce.copyOfRange(2, 32),
            tagged.hello.nonce.copyOfRange(2, 32),
        )
        // The unchanged (old-build) codec encodes and decodes it, and the signature verifies.
        val bytes = HandshakeHelloCodec.encode(tagged.hello)
        // The signed part has the same length; only the ECDSA signature's DER length varies from run to run.
        assertEquals(
            HandshakeHelloCodec.encodeUnsigned(plain.hello).size,
            HandshakeHelloCodec.encodeUnsigned(tagged.hello).size,
        )
        val decoded = HandshakeHelloCodec.decode(bytes)
        MeshHandshake.verify(decoded)
        assertEquals(BuildTag(7, true), BuildTag.readFrom(decoded))
        assertNull(BuildTag.readFrom(plain.hello))
    }

    @Test
    fun withoutATagTheHelloIsExactlyAsBefore() {
        val id = identity()
        val a = MeshHandshake.start(id, HandshakeRole.INITIATOR, capabilities, FixedRandom(9))
        val b = MeshHandshake.start(id, HandshakeRole.INITIATOR, capabilities, FixedRandom(9), buildTag = null)
        assertArrayEquals(a.hello.nonce, b.hello.nonce)
        assertEquals(a.hello.capabilities, b.hello.capabilities)
        assertEquals(0, a.hello.capabilities.transportMask and BuildTag.LINK_HELLO_BIT)
    }

    @Test
    fun aTagIsNeverPutInAnEndToEndHelloOrOverAnotherMeaningOfBitSeven() {
        val endToEnd = MeshHandshake.start(
            identity(),
            HandshakeRole.INITIATOR,
            capabilities,
            FixedRandom(1),
            purpose = HandshakePurpose.END_TO_END,
            buildTag = BuildTag(3, true),
        )
        assertEquals(0, endToEnd.hello.capabilities.transportMask and BuildTag.LINK_HELLO_BIT)
        assertNull(BuildTag.readFrom(endToEnd.hello))
        // A caller whose mask already uses bit 7 keeps its meaning and gets no tag.
        val busy = capabilities.copy(transportMask = capabilities.transportMask or BuildTag.LINK_HELLO_BIT)
        val link = MeshHandshake.start(identity(), HandshakeRole.INITIATOR, busy, FixedRandom(1), buildTag = BuildTag(3, true))
        assertEquals(0x01, link.hello.nonce[0].toInt())
        assertEquals(busy.transportMask, link.hello.capabilities.transportMask)
    }

    @Test
    fun theTagIsSignedSoStrippingOrChangingItBreaksTheHandshake() {
        val hello = MeshHandshake.start(
            identity(),
            HandshakeRole.RESPONDER,
            capabilities,
            buildTag = BuildTag(9, false),
        ).hello
        MeshHandshake.verify(hello)
        val newerNonce = hello.nonce.copyOf().also { it[1] = 99 }
        assertThrows(IllegalArgumentException::class.java) { MeshHandshake.verify(hello.copy(nonce = newerNonce)) }
        val stripped = hello.copy(
            capabilities = hello.capabilities.copy(transportMask = hello.capabilities.transportMask and BuildTag.LINK_HELLO_BIT.inv()),
        )
        assertThrows(IllegalArgumentException::class.java) { MeshHandshake.verify(stripped) }
    }

    private class Pair(val initiator: SecureBluetoothSession, val responder: SecureBluetoothSession)

    private fun handshake(
        initiatorTag: BuildTag?,
        responderTag: BuildTag?,
        initiatorCaps: NodeCapabilities = capabilities,
        responderCaps: NodeCapabilities = capabilities,
    ): Pair {
        val initiatorInput = PipedInputStream(16 * 1024)
        val responderOutput = PipedOutputStream(initiatorInput)
        val responderInput = PipedInputStream(16 * 1024)
        val initiatorOutput = PipedOutputStream(responderInput)
        val failure = AtomicReference<Throwable?>()
        val responderSession = AtomicReference<SecureBluetoothSession>()
        val worker = thread {
            runCatching {
                responderSession.set(
                    SecureBluetoothHandshake.respond(
                        responderInput,
                        responderOutput,
                        identity(),
                        responderCaps,
                        buildTag = responderTag,
                    ),
                )
            }.onFailure(failure::set)
        }
        val initiator = SecureBluetoothHandshake.initiate(
            initiatorInput,
            initiatorOutput,
            identity(),
            initiatorCaps,
            buildTag = initiatorTag,
        )
        worker.join(5_000)
        failure.get()?.let { throw it }
        return Pair(initiator, responderSession.get())
    }

    @Test
    fun bothRolesLearnTheOthersTagAndNoOneElseSeesBitSeven() {
        val seen = ArrayList<BuildTag>()
        LinkBuildTags.sink = { synchronized(seen) { seen += it } }
        val pair = handshake(BuildTag(4, true), BuildTag(6, false))
        assertEquals(BuildTag(6, false), pair.initiator.peerBuildTag)
        assertEquals(BuildTag(4, true), pair.responder.peerBuildTag)
        assertEquals(0, pair.initiator.peerCapabilities.transportMask and BuildTag.LINK_HELLO_BIT)
        assertEquals(capabilities.transportMask, pair.initiator.peerCapabilities.transportMask)
        assertEquals(capabilities.transportMask, pair.responder.peerCapabilities.transportMask)
        // Both sessions carry the peer's tag, but only the phone that opened the link reports it to the notice.
        assertEquals(listOf(BuildTag(6, false)), synchronized(seen) { seen.toList() })
        pair.initiator.close()
        pair.responder.close()
    }

    @Test
    fun anOlderPhoneWithoutATagInteroperatesBothWays() {
        val seen = ArrayList<BuildTag>()
        LinkBuildTags.sink = { synchronized(seen) { seen += it } }
        val newInitiator = handshake(BuildTag(5, true), null)
        assertNull(newInitiator.initiator.peerBuildTag)
        assertEquals(BuildTag(5, true), newInitiator.responder.peerBuildTag)
        val newResponder = handshake(null, BuildTag(5, true))
        assertEquals(BuildTag(5, true), newResponder.initiator.peerBuildTag)
        assertNull(newResponder.responder.peerBuildTag)
        assertEquals("only the initiator's side of the second handshake reports", listOf(BuildTag(5, true)), synchronized(seen) { seen.toList() })
        listOf(newInitiator, newResponder).forEach {
            it.initiator.close()
            it.responder.close()
        }
    }

    @Test
    fun aStrangerWhoConnectsToUsCannotPlantAClaim() {
        val seen = ArrayList<BuildTag>()
        LinkBuildTags.sink = { synchronized(seen) { seen += it } }
        // The attacker is the initiator and we are the responder: its tag is parsed but never reported.
        val pair = handshake(BuildTag(ReleaseNumber.MAX, true), null)
        assertEquals(BuildTag(ReleaseNumber.MAX, true), pair.responder.peerBuildTag)
        assertTrue(synchronized(seen) { seen.isEmpty() })
        pair.initiator.close()
        pair.responder.close()
    }

    @Test
    fun theDefaultTagComesFromTheProcessHookAndIsOffByDefault() {
        assertNull(LinkBuildTags.local)
        val untagged = handshake(LinkBuildTags.local, LinkBuildTags.local)
        assertNull(untagged.initiator.peerBuildTag)
        LinkBuildTags.local = BuildTag(8, false)
        val tagged = handshake(LinkBuildTags.local, LinkBuildTags.local)
        assertNotNull(tagged.initiator.peerBuildTag)
        assertTrue(tagged.responder.peerBuildTag!!.releaseNumber == 8)
        assertFalse(tagged.responder.peerBuildTag!!.releaseKey)
        listOf(untagged, tagged).forEach {
            it.initiator.close()
            it.responder.close()
        }
    }

    @Test
    fun aThrowingSinkCannotBreakTheHandshake() {
        LinkBuildTags.sink = { error("sink failure") }
        val pair = handshake(BuildTag(2, false), BuildTag(2, false))
        assertEquals(BuildTag(2, false), pair.initiator.peerBuildTag)
        pair.initiator.close()
        pair.responder.close()
    }
}
