package com.example.bluetoothtest

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link opening `BDO1` over a real BMH2 link: negotiation by hello bit 3 and record order. */
class RotatingDiscoveryLinkTest {
    private val base = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_000,
        maxChildren = 0,
    )
    private val withBit = base.copy(transportMask = base.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
    private val presenceKey = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun bothBitsExchangeOpeningsAndTheCallerVerifiesTheSelectedId() {
        val result = runPair(callerCaps = withBit, calleeCaps = withBit)
        val (calleeNode, entry) = requireNotNull(result.calleeAdvertised)
        val opening = requireNotNull(result.callerReceived)
        assertEquals(1, opening.size)
        assertArrayEquals(entry.rdi, opening[0].rdi)
        val selected = DiscoveryAnnouncement(
            nodeIdPrefix = entry.rdi,
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            capabilitySummary = 1,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        )
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            DiscoverySelectionCheck.check(selected, 0, result.callerSawNode!!.toByteArray(), opening),
        )
        assertEquals(calleeNode, result.callerSawNode)
        assertTrue(requireNotNull(result.calleeReceived).isEmpty())
        // The next record after the opening is ordinary traffic, in order, both ways.
        assertEquals("after", result.callerNextPayload)
    }

    @Test
    fun oneSidedBitSendsNothingAndTheSelectedVersion5Fails() {
        val result = runPair(callerCaps = withBit, calleeCaps = base)
        assertNull(result.callerReceived)
        assertNull(result.calleeReceived)
        assertEquals("after", result.callerNextPayload)
        val entry = requireNotNull(result.calleeAdvertised).second
        val selected = DiscoveryAnnouncement(
            nodeIdPrefix = entry.rdi,
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            capabilitySummary = 1,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        )
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            DiscoverySelectionCheck.check(selected, 0, result.callerSawNode!!.toByteArray(), result.callerReceived),
        )
    }

    @Test
    fun anUnexpectedOpeningIsAnUnknownRecordType() {
        // A reader that did not negotiate bit 3 rejects hop type 13 exactly like any unknown type.
        val pipes = Pipes()
        val calleeIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val error = AtomicReference<Throwable?>()
        val callee = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(pipes.responderInput, pipes.responderOutput, calleeIdentity, withBit)
                link.writer.write(SecureRecordType.DISCOVERY_OPENING, DiscoveryOpeningCodec.encode(emptyList()))
                link.writer.flush()
            }.onFailure(error::set)
        }
        val link = SecureBluetoothHandshake.initiate(pipes.initiatorInput, pipes.initiatorOutput, KeyPairSigningIdentity(generateEcKeyPair()), base)
        val thrown = assertThrows(IllegalStateException::class.java) { link.reader.read() }
        assertTrue(thrown.message!!.contains("Unknown secure record type 13"))
        callee.join(5_000)
        assertNull(error.get())
    }

    private data class Result(
        val calleeAdvertised: Pair<NodeId, OpeningEntry>?,
        val callerReceived: List<OpeningEntry>?,
        val calleeReceived: List<OpeningEntry>?,
        val callerSawNode: NodeId?,
        val callerNextPayload: String?,
        val tarpitRanBeforeHello: Boolean,
    )

    private fun runPair(callerCaps: NodeCapabilities, calleeCaps: NodeCapabilities): Result {
        val pipes = Pipes()
        val calleeIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeNode = NodeId.fromPublicKey(calleeIdentity.publicKeyEncoded)
        val entry = RotatingDiscoveryIds.entry(presenceKey, calleeNode.toByteArray(), 1_990_656L, 0)
        val calleeReceived = AtomicReference<List<OpeningEntry>?>()
        val failure = AtomicReference<Throwable?>()
        val tarpitSaw = AtomicReference<NodeId?>()
        val callee = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(pipes.responderInput, pipes.responderOutput, calleeIdentity, calleeCaps) {
                    tarpitSaw.set(it)
                }
                calleeReceived.set(LinkOpeningExchange.exchange(link, calleeCaps, listOf(entry)))
                link.writer.write(SecureRecordType.LINK_HEARTBEAT, "after".toByteArray())
                link.writer.flush()
            }.onFailure(failure::set)
        }
        val callerIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val link = SecureBluetoothHandshake.initiate(pipes.initiatorInput, pipes.initiatorOutput, callerIdentity, callerCaps)
        val received = LinkOpeningExchange.exchange(link, callerCaps, emptyList())
        val next = link.reader.read()
        callee.join(5_000)
        failure.get()?.let { throw AssertionError("callee failed", it) }
        assertEquals(NodeId.fromPublicKey(callerIdentity.publicKeyEncoded), tarpitSaw.get())
        return Result(
            calleeAdvertised = calleeNode to entry,
            callerReceived = received,
            calleeReceived = calleeReceived.get(),
            callerSawNode = link.peerNodeId,
            callerNextPayload = String(next.payload),
            tarpitRanBeforeHello = tarpitSaw.get() != null,
        )
    }

    private class Pipes {
        val initiatorInput = PipedInputStream(64 * 1024)
        val responderOutput = PipedOutputStream(initiatorInput)
        val responderInput = PipedInputStream(64 * 1024)
        val initiatorOutput = PipedOutputStream(responderInput)
    }
}
