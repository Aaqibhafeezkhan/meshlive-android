package com.example.bluetoothtest

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Rotating discovery IDs, Phase 1b/2 at the call endpoint: bit 3, the BDX1 OPENING, the guard, the presence rules. */
class RotatingEndpointTest {
    private val base = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_000,
        maxChildren = 0,
    )
    private val withBit = CallEndpointOpening.advertise(base, true)
    private val presenceKey = ByteArray(32) { (it * 3).toByte() }
    private val epoch = 1_990_656L

    @Before
    fun fixTheClockOfTheEpochWindow() {
        DiscoverySelectionCheck.unixSeconds = { 1_791_590_400L + 100L }
    }

    @After
    fun restoreTheClock() {
        DiscoverySelectionCheck.unixSeconds = { System.currentTimeMillis() / 1000L }
    }

    private class Pipes {
        val initiatorInput = PipedInputStream(64 * 1024)
        val responderOutput = PipedOutputStream(initiatorInput)
        val responderInput = PipedInputStream(64 * 1024)
        val initiatorOutput = PipedOutputStream(responderInput)
    }

    private class Session(val link: SecureBluetoothSession, val endpoint: EndToEndVideoSession)

    private class Pair2(val caller: Session, val callee: Session, val calleeIdentity: SigningIdentity)

    /** A real link + end-to-end handshake pair; the callee signs [calleeEndpoint], the caller [callerEndpoint]. */
    private fun connect(callerEndpoint: NodeCapabilities, calleeEndpoint: NodeCapabilities): Pair2 {
        val pipes = Pipes()
        val calleeIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val callerIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeSide = AtomicReference<Session>()
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(pipes.responderInput, pipes.responderOutput, calleeIdentity, base)
                calleeSide.set(Session(link, EndToEndVideoHandshake.respond(link, calleeIdentity, calleeEndpoint)))
            }.onFailure(failure::set)
        }
        val link = SecureBluetoothHandshake.initiate(pipes.initiatorInput, pipes.initiatorOutput, callerIdentity, base)
        val callerSide = Session(link, EndToEndVideoHandshake.initiate(link, callerIdentity, callerEndpoint))
        worker.join(10_000)
        failure.get()?.let { throw AssertionError("callee failed", it) }
        return Pair2(callerSide, requireNotNull(calleeSide.get()), calleeIdentity)
    }

    private fun selectedOffer(entry: OpeningEntry, cost: Int = 0) = DiscoveryAnnouncement(
        nodeIdPrefix = entry.rdi,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        capabilitySummary = 1,
        streamToken = 0,
        routeCost = cost,
        sequence = 0,
        version = DiscoveryAnnouncement.ROTATING_VERSION,
    )

    @Test
    fun bit3IsNegotiatedOnlyWhenBothEndpointHellosCarryIt() {
        assertTrue(CallEndpointOpening.negotiated(true, withBit))
        assertFalse(CallEndpointOpening.negotiated(false, withBit))
        assertFalse(CallEndpointOpening.negotiated(true, base))
        assertEquals(base, CallEndpointOpening.advertise(base, false))
    }

    /** What the callee sends right before its first answer, and what the caller reads: one sealed BDX1 record. */
    private fun transfer(pair: Pair2, entries: List<OpeningEntry>): List<OpeningEntry> {
        val sealed = pair.callee.endpoint.sealer.seal(
            EndToEndContentType.DISCOVERY, 0, DiscoveryExchange.encode(DiscoveryExchange.Opening(entries)),
        )
        pair.callee.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealed)
        pair.callee.link.writer.flush()
        val record = pair.caller.link.reader.read()
        assertEquals(SecureRecordType.END_TO_END_CONTENT, record.type)
        pair.caller.endpoint.opener.acceptDiscovery = true
        val content = pair.caller.endpoint.opener.open(record.payload)
        assertEquals(EndToEndContentType.DISCOVERY, content.type)
        return (DiscoveryExchange.decode(content.payload) as DiscoveryExchange.Opening).entries
    }

    @Test
    fun theCalleesOpeningReachesTheCallerAndVerifiesEndToEnd() {
        val pair = connect(withBit, withBit)
        assertTrue(CallEndpointOpening.negotiated(true, pair.caller.endpoint.peerCapabilities))
        val calleeEntry = RotatingDiscoveryIds.entry(presenceKey, pair.calleeIdentity.nodeId.toByteArray(), epoch, 0)
        val opening = transfer(pair, listOf(calleeEntry))
        assertEquals(1, opening.size)
        assertArrayEquals(calleeEntry.rdi, opening[0].rdi)
        val peer = pair.caller.endpoint.peerNodeId
        // Direct: link node equals endpoint node.
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            CallEndpointOpening.check(selectedOffer(calleeEntry), peer, pair.caller.link.peerNodeId, true, opening),
        )
        // Relayed: the link peer is a relay, the end-to-end opening still passes.
        val relay = NodeId.fromBytes(ByteArray(16) { 1 })
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            CallEndpointOpening.check(selectedOffer(calleeEntry, cost = 1), peer, relay, false, opening),
        )
        // A direct call whose link node is not the endpoint node fails even with a valid opening.
        assertEquals(
            DiscoverySelectionCheck.Result.PREFIX_MISMATCH,
            CallEndpointOpening.check(selectedOffer(calleeEntry), peer, relay, true, opening),
        )
        // RD4: an entry from far outside the verifier's epoch window is stale although it recomputes.
        val stale = RotatingDiscoveryIds.entry(presenceKey, peer.toByteArray(), epoch + 500, 0)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_STALE,
            CallEndpointOpening.check(selectedOffer(stale), peer, peer, true, listOf(stale)),
        )
    }

    @Test
    fun anOpeningForAnotherNodeOrNoOpeningFailsTheSelectedVersion5Offer() {
        val pair = connect(withBit, withBit)
        val peer = pair.caller.endpoint.peerNodeId
        val impostor = NodeId.fromBytes(ByteArray(16) { 8 })
        val forged = RotatingDiscoveryIds.entry(presenceKey, impostor.toByteArray(), epoch, 0)
        val selected = selectedOffer(forged, cost = 1)
        // A relay answering the endpoint handshake itself carries its own node, whose ID cannot open the callee's RDI.
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            CallEndpointOpening.check(selected, peer, peer, false, listOf(forged)),
        )
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            CallEndpointOpening.check(selected, impostor, peer, false, null),
        )
        // A role-1 entry never opens a call offer.
        val wrongRole = RotatingDiscoveryIds.entry(presenceKey, impostor.toByteArray(), epoch, 1)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            CallEndpointOpening.check(selected, impostor, peer, false, listOf(wrongRole)),
        )
    }

    @Test
    fun aVersion2OfferKeepsTheEndToEndPrefixCheckAndNeedsNoOpening() {
        val node = NodeId.fromBytes(ByteArray(16) { 4 })
        val selected = selectedOffer(RotatingDiscoveryIds.entry(presenceKey, node.toByteArray(), epoch, 0)).copy(
            nodeIdPrefix = node.toByteArray().copyOf(6),
            version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        )
        assertEquals(DiscoverySelectionCheck.Result.PASS, CallEndpointOpening.check(selected, node, node, true, null))
        assertEquals(
            DiscoverySelectionCheck.Result.PREFIX_MISMATCH,
            CallEndpointOpening.check(selected, NodeId.fromBytes(ByteArray(16) { 6 }), node, true, null),
        )
    }

    @Test
    fun type11IsAnUnknownContentTypeUntilTheOpenerOptsIn() {
        val pair = connect(withBit, withBit)
        val sealed = pair.callee.endpoint.sealer.seal(
            EndToEndContentType.DISCOVERY, 0, DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer),
        )
        val error = assertThrows(IllegalStateException::class.java) { pair.caller.endpoint.opener.open(sealed) }
        assertTrue(error.message!!.contains("Unknown end-to-end content type 11"))
        pair.caller.endpoint.opener.acceptDiscovery = true
        // The sequence moved on, so a fresh record under the opted-in opener decodes.
        val next = pair.callee.endpoint.sealer.seal(
            EndToEndContentType.DISCOVERY, 0, DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer),
        )
        val record = pair.caller.endpoint.opener.open(next)
        assertEquals(EndToEndContentType.DISCOVERY, record.type)
        assertEquals(DiscoveryExchange.RecognitionOffer, DiscoveryExchange.decode(record.payload))
    }

    @Test
    fun theFirstRecordMustBeAnOpeningThatOpensTheSelectedOffer() {
        val node = NodeId.fromBytes(ByteArray(16) { 4 })
        val entry = RotatingDiscoveryIds.entry(presenceKey, node.toByteArray(), epoch, 0)
        val selected = selectedOffer(entry)
        fun record(type: EndToEndContentType, payload: ByteArray) = EndToEndContentRecord(type, 0, payload)
        fun verify(first: EndToEndContentRecord) = CallEndpointOpening.verifyFirstRecord(selected, node, node, true, first)
        val good = record(EndToEndContentType.DISCOVERY, DiscoveryExchange.encode(DiscoveryExchange.Opening(listOf(entry))))
        assertEquals(DiscoverySelectionCheck.Result.PASS, verify(good))
        // Any other first record (an answer without the opening), an offer or garbage is a missing opening.
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            verify(record(EndToEndContentType.PRIVATE_CALL_SIGNAL, ByteArray(23))),
        )
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            verify(record(EndToEndContentType.DISCOVERY, DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer))),
        )
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            verify(record(EndToEndContentType.DISCOVERY, byteArrayOf(1, 2, 3))),
        )
        // An empty opening, or one for another node, does not open the selected ID.
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            verify(record(EndToEndContentType.DISCOVERY, DiscoveryExchange.encode(DiscoveryExchange.Opening(emptyList())))),
        )
        val other = RotatingDiscoveryIds.entry(presenceKey, ByteArray(16) { 5 }, epoch, 0)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            verify(record(EndToEndContentType.DISCOVERY, DiscoveryExchange.encode(DiscoveryExchange.Opening(listOf(other))))),
        )
    }

    // ----- relay guard -----

    @Test
    fun theUnchangedGuardCountsTheCalleesFirstRecordAfterTheInvite() {
        // RD6: the callee's OPENING is sent right before its first answer to the Invite, so the guard needs no
        // exception: the first end-to-end content record from the callee is still an answer, and the caller's
        // own content never is.
        val guard = RelayChainGuard(readyNs = 0L)
        guard.onRecord(fromCaller = true, SecureRecordType.END_TO_END_HANDSHAKE)
        guard.onRecord(fromCaller = false, SecureRecordType.END_TO_END_HANDSHAKE)
        guard.onRecord(fromCaller = true, SecureRecordType.END_TO_END_CONTENT)
        assertFalse(guard.satisfied)
        assertTrue(guard.violation(21_000_000_000L)!!.contains("No answer from the callee"))
        guard.onRecord(fromCaller = false, SecureRecordType.END_TO_END_CONTENT)
        assertTrue(guard.satisfied)
    }

    // ----- presence session rules -----

    private val lease = DiscoveryExchange.PresenceLease(1, epoch, 2962, listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }))

    @Test
    fun anEligibleSideOffersFirstAndLeasesOnlyAfterBothOffered() {
        val session = PresenceSession(localEligible = true)
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer), session.start())
        // The peer's lease before its offer is dropped.
        val early = session.onRecord(lease, peerVerifiedNow = true)
        assertTrue(early.single() is PresenceSession.Action.Reject)
        val afterOffer = session.onRecord(DiscoveryExchange.RecognitionOffer, peerVerifiedNow = true)
        assertEquals(listOf<Any>(PresenceSession.Action.SendLease), afterOffer)
        val stored = session.onRecord(lease, peerVerifiedNow = true)
        assertTrue(stored.single() is PresenceSession.Action.StoreLease)
        // One lease, one offer per session.
        assertTrue(session.onRecord(lease, true).single() is PresenceSession.Action.Reject)
        assertTrue(session.onRecord(DiscoveryExchange.RecognitionOffer, true).single() is PresenceSession.Action.Reject)
    }

    @Test
    fun anOfferThatArrivesBeforeThisSidesStartIsNotLost() {
        // The peer answered first and offered at once; this side starts a moment later and sends both records.
        val session = PresenceSession(localEligible = true)
        assertTrue(session.onRecord(DiscoveryExchange.RecognitionOffer, true).isEmpty())
        assertEquals(
            listOf<Any>(PresenceSession.Action.SendOffer, PresenceSession.Action.SendLease),
            session.start(),
        )
        // Starting twice sends nothing more, and the peer's lease is then stored as usual.
        assertTrue(session.start().isEmpty())
        assertTrue(session.onRecord(lease, true).single() is PresenceSession.Action.StoreLease)
    }

    @Test
    fun anOfferThatWasWithheldMeansNoLeaseIsSentOrStored() {
        val session = PresenceSession(localEligible = true)
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer), session.start())
        session.offerWithheld()
        // The peer's OFFER arrives, but this side never offered: no lease goes out ...
        assertTrue(session.onRecord(DiscoveryExchange.RecognitionOffer, true).isEmpty())
        // ... and the peer's lease is not stored.
        val rejected = session.onRecord(lease, true).single()
        assertTrue(rejected is PresenceSession.Action.Reject)
        // A repeated start sends nothing.
        assertTrue(session.start().isEmpty())
    }

    @Test
    fun anIneligibleSideNeverOffersLeasesOrStores() {
        val session = PresenceSession(localEligible = false)
        assertTrue(session.start().isEmpty())
        assertTrue(session.onRecord(DiscoveryExchange.RecognitionOffer, true).isEmpty())
        assertTrue(session.onRecord(lease, true).single() is PresenceSession.Action.Reject)
    }

    @Test
    fun aLeaseFromAPeerThatIsNoLongerVerifiedIsRejected() {
        val session = PresenceSession(localEligible = true)
        session.start()
        session.onRecord(DiscoveryExchange.RecognitionOffer, true)
        val result = session.onRecord(lease, peerVerifiedNow = false)
        assertTrue(result.single() is PresenceSession.Action.Reject)
        // A repeated opening in the session is also dropped.
        assertTrue(session.onRecord(DiscoveryExchange.Opening(emptyList()), true).single() is PresenceSession.Action.Reject)
    }
}
