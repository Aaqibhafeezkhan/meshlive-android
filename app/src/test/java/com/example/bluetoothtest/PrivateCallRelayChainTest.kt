package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallRelayChainTest {
    private val callee = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val local = ByteArray(16) { 0x40 }
    private val relayChild = ByteArray(16) { 0x22 }

    private fun offer(routeCost: Int, prefix: ByteArray = callee) = DiscoveryAnnouncement(
        nodeIdPrefix = prefix,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
            if (routeCost > 0) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0,
        capabilitySummary = NodeCapabilities.CODEC_VP9,
        streamToken = 0,
        routeCost = routeCost,
        sequence = 0,
    )

    private fun calleeLink(): ByteArray = callee + ByteArray(10) { 9 }

    @Test
    fun chainOffKeepsTheOneRelayRuleExactly() {
        // Any private-call offer is taken, as before; the child must be the announced callee.
        assertTrue(PrivateCallRelayChain.admitsOffer(offer(3), local.copyOf(6), chainEnabled = false))
        assertTrue(PrivateCallRelayChain.verifyChildLinkPrefix(offer(1), chainEnabled = false))
        assertNull(PrivateCallRelayChain.childProblem(offer(0), calleeLink(), 0, local, chainEnabled = false))
        assertEquals(
            "Callee identity changed after discovery",
            PrivateCallRelayChain.childProblem(offer(1), relayChild, 1, local, chainEnabled = false),
        )
        assertNull(PrivateCallRelayChain.parentProblem(relayChild, relayChild, callee, local, chainEnabled = false))
    }

    @Test
    fun aRelayAcceptsTheCalleeOrAnotherRelayWithinTheHopBound() {
        assertNull(PrivateCallRelayChain.childProblem(offer(0), calleeLink(), 0, local, chainEnabled = true))
        assertEquals(
            "Callee identity changed after discovery",
            PrivateCallRelayChain.childProblem(offer(0), relayChild, 1, local, chainEnabled = true),
        )
        for (cost in 1 until PrivateCallRelayChain.MAX_RELAYS) {
            assertTrue(PrivateCallRelayChain.admitsOffer(offer(cost), local.copyOf(6), chainEnabled = true))
            assertFalse(PrivateCallRelayChain.verifyChildLinkPrefix(offer(cost), chainEnabled = true))
            assertNull(PrivateCallRelayChain.childProblem(offer(cost), relayChild, 1, local, chainEnabled = true))
        }
        val tooFar = offer(PrivateCallRelayChain.MAX_RELAYS)
        assertFalse(PrivateCallRelayChain.admitsOffer(tooFar, local.copyOf(6), chainEnabled = true))
        assertNotNull(PrivateCallRelayChain.childProblem(tooFar, relayChild, 1, local, chainEnabled = true))
    }

    @Test
    fun aRelayedOfferMustBeServedByASignedRelayThatIsNeitherTheCalleeNorThisPhone() {
        assertEquals(
            "A relayed offer reached its callee directly",
            PrivateCallRelayChain.childProblem(offer(1), calleeLink(), 1, local, chainEnabled = true),
        )
        assertEquals(
            "Relay child did not authenticate as a relay",
            PrivateCallRelayChain.childProblem(offer(1), relayChild, 0, local, chainEnabled = true),
        )
        assertEquals(
            "A call relay cannot be its own child",
            PrivateCallRelayChain.childProblem(offer(1), local, 1, local, chainEnabled = true),
        )
        // Never an offer for this phone itself: it would relay a call it is already part of.
        assertFalse(PrivateCallRelayChain.admitsOffer(offer(0, local.copyOf(6)), local.copyOf(6), chainEnabled = true))
    }

    @Test
    fun aRelayNeverJoinsAParentThatClosesALoop() {
        val caller = ByteArray(16) { 0x11 }
        assertNull(PrivateCallRelayChain.parentProblem(caller, relayChild, callee, local, chainEnabled = true))
        assertNotNull(PrivateCallRelayChain.parentProblem(relayChild, relayChild, callee, local, chainEnabled = true))
        assertNotNull(PrivateCallRelayChain.parentProblem(local, relayChild, callee, local, chainEnabled = true))
        assertNotNull(PrivateCallRelayChain.parentProblem(calleeLink(), relayChild, callee, local, chainEnabled = true))
    }

    @Test
    fun theAdvertisedHopCountGrowsByOneAtEveryRelayAndStopsAtTheBound() {
        var advertised = offer(0)
        val counts = mutableListOf<Int>()
        while (PrivateCallRelayChain.admitsOffer(advertised, local.copyOf(6), chainEnabled = true)) {
            advertised = PrivateCallRelayChain.parentOffer(advertised, DiscoveryAnnouncement.FLAG_GATT_TRANSPORT)
            counts += advertised.routeCost
            assertArrayEquals(callee, advertised.nodeIdPrefix)
            assertTrue(advertised.isPrivateCallOffer())
            assertTrue(advertised.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0)
        }
        assertEquals((1..PrivateCallRelayChain.MAX_RELAYS).toList(), counts)
        // The caller's Invite carries the relay count; the wire accepts up to 15.
        assertTrue(PrivateCallRelayChain.MAX_RELAYS <= PrivateCallSignalMessage.MAX_RELAY_HOPS)
    }

    @Test
    fun relayedCallCapKeepsTheLegacyCapUnlessTheMeasuredBudgetIsOn() {
        val request = VideoSettings.DEFAULT.copy(fps = 30, bitRate = 600_000)
        val legacy = privateCallVideoSettingsForRoute(request, relayHops = 2)
        assertEquals(15, legacy.fps)
        assertEquals(110_000, legacy.bitRate)
        assertEquals(request, privateCallVideoSettingsForRoute(request, relayHops = 0, measuredBudgetBps = 50_000))
        // 900 kbps link: 900 x 0.2125 = 191 kbps for one relayed direction, less 32 kbps audio.
        val budget = relayedCallMeasuredBudgetBps(900, 32_000)
        assertEquals(159_250, budget)
        assertEquals(159_250, privateCallVideoSettingsForRoute(request, 1, budget).bitRate)
        assertEquals(15, privateCallVideoSettingsForRoute(request, 1, budget).fps)
        // A weak link never lowers the relayed call below the legacy cap; a small request stays as is.
        assertEquals(110_000, privateCallVideoSettingsForRoute(request, 1, relayedCallMeasuredBudgetBps(300, 32_000)).bitRate)
        assertEquals(90_000, privateCallVideoSettingsForRoute(request.copy(bitRate = 90_000), 1, budget).bitRate)
        assertNull(relayedCallMeasuredBudgetBps(null, 32_000))
        assertNull(relayedCallMeasuredBudgetBps(0, 32_000))
    }

    @Test
    fun endpointsAuthenticateEachOtherThroughTwoOpaqueRelaysAndRelaysCannotForgeMedia() {
        val honest = runChain(tamperAtR2 = false)
        assertNull(honest.calleeFailure.get())
        assertEquals(honest.callee.nodeId, honest.callerSawEndpoint)
        assertEquals("hello-callee", String(honest.calleeReceived.get()!!))
        assertEquals("hello-caller", String(honest.callerReceived!!))
        // Each relay saw only its adjacent link identities: never the far endpoint as a neighbour.
        assertEquals(honest.relay2.nodeId, honest.relay1Child)
        assertEquals(honest.callee.nodeId, honest.relay2Child)

        val tampered = runChain(tamperAtR2 = true)
        assertEquals(tampered.callee.nodeId, tampered.callerSawEndpoint)
        assertNotNull("A relay that alters endpoint ciphertext must fail the callee", tampered.calleeFailure.get())
        assertNull(tampered.calleeReceived.get())
    }

    @Test
    fun aRelayThatAnswersTheEndpointHandshakeItselfIsNotTheCallee() {
        // A malicious relay can terminate the caller's endpoint handshake, but only under its own
        // signed identity; the caller's discovery-prefix check (AndroidPrivateVideoCall.establish)
        // then refuses it.
        val caller = KeyPairSigningIdentity(generateEcKeyPair())
        val relay = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val (callerSide, relaySide) = linkPair()
        val relayEndpoint = AtomicReference<NodeId>()
        val worker = thread {
            val link = SecureBluetoothHandshake.respond(relaySide.first, relaySide.second, relay, RELAY_CAPS)
            relayEndpoint.set(EndToEndVideoHandshake.respond(link, relay, ENDPOINT_CAPS).peerNodeId)
        }
        val link = SecureBluetoothHandshake.initiate(callerSide.first, callerSide.second, caller, ENDPOINT_CAPS)
        val endpoint = EndToEndVideoHandshake.initiate(link, caller, ENDPOINT_CAPS)
        worker.join(10_000)
        assertEquals(relay.nodeId, endpoint.peerNodeId)
        assertFalse(
            endpoint.peerNodeId.toByteArray().copyOf(6).contentEquals(calleeIdentity.nodeId.toByteArray().copyOf(6)),
        )
        assertNotEquals(calleeIdentity.nodeId, endpoint.peerNodeId)
        assertEquals(caller.nodeId, relayEndpoint.get())
    }

    @Test
    fun aRelayedChildMustSignTheCallRelayCapabilityNotJustRelayCapacity() {
        // Forwarding receivers and stream relays sign maxChildren >= 1 too; only call relays sign the bit.
        val streamForwarderMask = NodeCapabilities.TRANSPORT_CLASSIC
        assertEquals(
            "Relay child did not authenticate as a private-call relay",
            PrivateCallRelayChain.childProblem(offer(1), relayChild, 1, local, true, streamForwarderMask),
        )
        val signed = PrivateCallRelayChain.relayCapabilities(ENDPOINT_CAPS.copy(maxChildren = 1), chainEnabled = true)
        assertNull(PrivateCallRelayChain.childProblem(offer(1), relayChild, 1, local, true, signed.transportMask))
        // The bit leaves the transport bits every reader tests unchanged, and survives the signed codec.
        assertEquals(ENDPOINT_CAPS.transportMask, signed.transportMask and NodeCapabilities.TRANSPORT_BLE_L2CAP)
        assertEquals(signed, NodeCapabilitiesCodec.decode(NodeCapabilitiesCodec.encode(signed)))
        assertEquals(0, signed.transportMask and CallVideoFragments.CAPABILITY_BIT)
        // Control arm: the wire is exactly as before, and the callee itself needs no bit.
        assertEquals(ENDPOINT_CAPS, PrivateCallRelayChain.relayCapabilities(ENDPOINT_CAPS, chainEnabled = false))
        assertNull(PrivateCallRelayChain.childProblem(offer(0), calleeLink(), 0, local, true, streamForwarderMask))
    }

    @Test
    fun aClassicRelayedChildIsOnlyTheAdvertisingDevice() {
        assertTrue(PrivateCallRelayChain.classicExactChildOnly(offer(1), chainEnabled = true))
        assertFalse(PrivateCallRelayChain.classicExactChildOnly(offer(0), chainEnabled = true))
        assertFalse(PrivateCallRelayChain.classicExactChildOnly(offer(1), chainEnabled = false))
        val plan = ClassicCandidateKind.entries.map { ClassicCandidate(it.label, it) }
        assertEquals(
            listOf("debug", "bonded-address", "bonded-name", "discovered"),
            classicExactDevicePlan(plan).map { it.device },
        )
        // A plan of guesses only is empty: nothing is paged rather than another bonded phone.
        assertTrue(classicExactDevicePlan(plan.filter { !it.kind.resolved && it.kind != ClassicCandidateKind.DISCOVERED }).isEmpty())
    }

    @Test
    fun theCallerRefusesOffersBeyondTheAppRelayLimitInsteadOfClampingThem() {
        for (cost in 0..PrivateCallRelayChain.MAX_RELAYS) assertEquals(cost, PrivateCallRelayChain.callerRelayHops(cost))
        for (cost in listOf(PrivateCallRelayChain.MAX_RELAYS + 1, PrivateCallSignalMessage.MAX_RELAY_HOPS, 255)) {
            assertNull(PrivateCallRelayChain.callerRelayHops(cost))
        }
    }

    @Test
    fun aRelayWaitsABoundedTimeForItsParent() {
        // Three outer relays (8 s scan + 30 s Classic connect each) and the caller connect fit inside.
        val worstChainBuildMs = (PrivateCallRelayChain.MAX_RELAYS - 1) * (8_000L + 30_000L) + 30_000L
        assertTrue(PrivateCallRelayChain.PARENT_WAIT_MS > worstChainBuildMs)
        assertTrue(PrivateCallRelayChain.PARENT_WAIT_MS <= 300_000L)
    }

    private class ChainResult(
        val callee: SigningIdentity,
        val relay2: SigningIdentity,
        val callerSawEndpoint: NodeId,
        val callerReceived: ByteArray?,
        val calleeReceived: AtomicReference<ByteArray?>,
        val calleeFailure: AtomicReference<Throwable?>,
        val relay1Child: NodeId,
        val relay2Child: NodeId,
    )

    /** caller -> R1 -> R2 -> callee over in-memory links with real BMH2/BMS1/BME1. */
    private fun runChain(tamperAtR2: Boolean): ChainResult {
        val caller = KeyPairSigningIdentity(generateEcKeyPair())
        val r1 = KeyPairSigningIdentity(generateEcKeyPair())
        val r2 = KeyPairSigningIdentity(generateEcKeyPair())
        val callee = KeyPairSigningIdentity(generateEcKeyPair())
        val (callerToR1, r1FromCaller) = linkPair()
        val (r1ToR2, r2FromR1) = linkPair()
        val (r2ToCallee, calleeFromR2) = linkPair()
        val calleeReceived = AtomicReference<ByteArray?>()
        val calleeFailure = AtomicReference<Throwable?>()
        val r1Child = AtomicReference<NodeId>()
        val r2Child = AtomicReference<NodeId>()
        val workers = mutableListOf<Thread>()

        workers += thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(calleeFromR2.first, calleeFromR2.second, callee, ENDPOINT_CAPS)
                val endpoint = EndToEndVideoHandshake.respond(link, callee, ENDPOINT_CAPS)
                check(endpoint.peerNodeId == caller.nodeId)
                EndToEndStreamProtocol.writePacket(link.writer, endpoint.sealer, 0, 1L, "hello-caller".toByteArray())
                link.writer.flush()
                calleeReceived.set(EndToEndStreamProtocol.readPacket(link.reader, endpoint.opener).payload)
            }.onFailure(calleeFailure::set)
        }
        fun relay(
            identity: SigningIdentity,
            downstream: Pair<InputStream, OutputStream>,
            upstream: Pair<InputStream, OutputStream>,
            childOffer: DiscoveryAnnouncement,
            child: AtomicReference<NodeId>,
            tamper: Boolean,
        ) = thread {
            val childLink = SecureBluetoothHandshake.initiate(downstream.first, downstream.second, identity, RELAY_CAPS)
            PrivateCallRelayChain.childProblem(
                childOffer, childLink.peerNodeId.toByteArray(), childLink.peerCapabilities.maxChildren,
                identity.nodeId.toByteArray(), chainEnabled = true, childLink.peerCapabilities.transportMask,
            )?.let { error(it) }
            child.set(childLink.peerNodeId)
            val parentLink = SecureBluetoothHandshake.respond(upstream.first, upstream.second, identity, RELAY_CAPS)
            PrivateCallRelayChain.parentProblem(
                parentLink.peerNodeId.toByteArray(), childLink.peerNodeId.toByteArray(), childOffer.nodeIdPrefix,
                identity.nodeId.toByteArray(), chainEnabled = true,
            )?.let { error(it) }
            val back = thread { copy(childLink, parentLink, tamper = false) }
            copy(parentLink, childLink, tamper)
            back.join(5_000)
        }
        val calleeOffer = offer(0, callee.nodeId.toByteArray().copyOf(6))
        workers += relay(r2, r2ToCallee, r2FromR1, calleeOffer, r2Child, tamperAtR2)
        workers += relay(r1, r1ToR2, r1FromCaller, PrivateCallRelayChain.parentOffer(calleeOffer, 0), r1Child, false)

        val link = SecureBluetoothHandshake.initiate(callerToR1.first, callerToR1.second, caller, ENDPOINT_CAPS)
        val endpoint = EndToEndVideoHandshake.initiate(link, caller, ENDPOINT_CAPS)
        EndToEndStreamProtocol.writePacket(link.writer, endpoint.sealer, 0, 2L, "hello-callee".toByteArray())
        link.writer.flush()
        val received = runCatching { EndToEndStreamProtocol.readPacket(link.reader, endpoint.opener).payload }.getOrNull()
        workers.first().join(10_000)
        listOf(callerToR1, r1FromCaller, r1ToR2, r2FromR1, r2ToCallee, calleeFromR2).forEach { (input, output) ->
            input.close()
            output.close()
        }
        workers.forEach { it.join(10_000) }
        return ChainResult(
            callee, r2, endpoint.peerNodeId, received, calleeReceived, calleeFailure, r1Child.get(), r2Child.get(),
        )
    }

    /** BluetoothPrivateCallRelay.copyRecords, minus Android: endpoint records copied unchanged. */
    private fun copy(source: SecureBluetoothSession, destination: SecureBluetoothSession, tamper: Boolean) {
        runCatching {
            while (true) {
                val record = source.reader.read()
                val payload = if (tamper && record.type == SecureRecordType.END_TO_END_CONTENT) {
                    record.payload.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
                } else {
                    record.payload
                }
                destination.writer.write(record.type, payload)
                destination.writer.flush()
            }
        }
    }

    /** One in-memory full-duplex link: (A side, B side), each an input and output stream. */
    private fun linkPair(): Pair<Pair<InputStream, OutputStream>, Pair<InputStream, OutputStream>> {
        val aToB = BlockingPipe()
        val bToA = BlockingPipe()
        return (bToA.input to aToB.output) to (aToB.input to bToA.output)
    }

    private class BlockingPipe {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw java.io.IOException("closed")
                if (len > 0) chunks.put(b.copyOfRange(off, off + len))
            }
            override fun close() {
                closed = true
                chunks.put(ByteArray(0))
            }
        }
        val input = object : InputStream() {
            private var current = ByteArray(0)
            private var offset = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (offset == current.size) {
                    if (closed && chunks.isEmpty()) return -1
                    val next = chunks.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (next.isEmpty()) {
                        chunks.put(next)
                        return -1
                    }
                    current = next
                    offset = 0
                }
                val count = minOf(len, current.size - offset)
                System.arraycopy(current, offset, b, off, count)
                offset += count
                return count
            }
            override fun close() {
                closed = true
                chunks.put(ByteArray(0))
            }
        }
    }

    private companion object {
        val ENDPOINT_CAPS = NodeCapabilities(
            transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
            encodeCodecMask = NodeCapabilities.CODEC_VP9,
            decodeCodecMask = NodeCapabilities.CODEC_VP9,
            maxBitrateKbps = 1_000,
            maxChildren = 0,
        )
        val RELAY_CAPS = PrivateCallRelayChain.relayCapabilities(ENDPOINT_CAPS.copy(maxChildren = 1), chainEnabled = true)
    }
}
