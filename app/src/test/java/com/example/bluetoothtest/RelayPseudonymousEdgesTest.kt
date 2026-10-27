package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DECISIONS Design C "Amendment CP" with review CP-1: which identity each relayed-call edge presents under the BMH3 pseudonym
 * defaults, over real BMH3 handshakes on in-memory pipes, plus the helper's dispatch, limiter and FAIL rules and the
 * target's decision before it rings.
 */
class RelayPseudonymousEdgesTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val caps = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP or RotatingDiscoveryIds.LINK_HELLO_BIT or RelayedCalls.LINK_HELLO_SETUP_BIT,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        maxBitrateKbps = 1_100,
        maxChildren = 0,
    )
    private val helperCaps = caps.copy(maxChildren = 1)

    private class CountingIdentity(private val inner: SigningIdentity) : SigningIdentity {
        val signed = AtomicInteger()
        override val nodeId: NodeId get() = inner.nodeId
        override val publicKeyEncoded: ByteArray get() = inner.publicKeyEncoded
        override fun sign(message: ByteArray): ByteArray {
            signed.incrementAndGet()
            return inner.sign(message)
        }
    }

    private fun newIdentity() = CountingIdentity(KeyPairSigningIdentity(generateEcKeyPair()))

    private class QueuePipe {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile private var closed = false
        val output = object : OutputStream() {
            override fun write(b: Int) {
                if (closed) throw java.io.IOException("closed")
                queue.put(b and 0xff)
            }
        }
        val input = object : InputStream() {
            override fun read(): Int {
                val deadline = System.nanoTime() + 60_000_000_000L
                while (true) {
                    val value = queue.poll(20, TimeUnit.MILLISECONDS)
                    if (value != null) return value
                    if (closed && queue.isEmpty()) return -1
                    if (System.nanoTime() > deadline) throw java.io.IOException("test pipe read timed out")
                }
            }
        }
        fun close() {
            closed = true
        }
    }

    private class Duplex {
        val aToB = QueuePipe()
        val bToA = QueuePipe()
        fun close() {
            aToB.close()
            bToA.close()
        }
    }

    private class Both<T>(val initiator: Result<T>, val responder: Result<T>)

    private fun <T> both(initiator: () -> T, responder: () -> T, onFailure: () -> Unit): Both<T> {
        val responderResult = AtomicReference<Result<T>>()
        val worker = thread(name = "responder", isDaemon = true) {
            responderResult.set(runCatching(responder).onFailure { onFailure() })
        }
        val initiatorResult = runCatching(initiator).onFailure { onFailure() }
        worker.join(60_000)
        return Both(initiatorResult, responderResult.get() ?: Result.failure(IllegalStateException("responder timed out")))
    }

    /** One link handshake: [initiatorReal] dials with [initiatorOptions]; the answerer uses [responderOptions]. */
    private fun link(
        initiatorReal: SigningIdentity,
        initiatorOptions: Bmh3InitiatorOptions,
        responderReal: SigningIdentity,
        responderOptions: Bmh3ResponderOptions,
    ): Both<SecureBluetoothSession> {
        val pipes = Duplex()
        return both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, initiatorReal, caps, bmh3 = initiatorOptions) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, responderReal, helperCaps, bmh3 = responderOptions) },
            onFailure = { pipes.close() },
        )
    }

    private fun prefixOf(identity: SigningIdentity) = identity.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)

    /** The routed row a caller dials: the target at cost 1, advertised (in the caller's table) through its helper. */
    private fun routed(target: ByteArray) = RelayedCalls.routedAnnouncement(target, 1, RelayedCalls.helperOfferFor(ByteArray(6) { 9 }))

    private fun pseudonymHelper() = Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false)

    // ------------------------------------------------------------------ the caller's first edge

    @Test
    fun theControlArmFailsAtTheFirstHopExactlyAsOnThePhones() {
        // relay-showcase contact-c12: the undeclared throwaway toward a relay meets the helper's pseudonym and is refused.
        val control = Bmh3Policy.initiatorOptions(routed(ByteArray(6) { 1 }), Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true)
        val run = link(newIdentity(), control, newIdentity(), pseudonymHelper())
        val failure = run.initiator.exceptionOrNull()
        assertTrue(failure is Bmh3Failure)
        assertEquals("pseudonymous answerer to a real caller", (failure as Bmh3Failure).reason)
    }

    @Test
    fun aDeclaredThrowawayReachesAPseudonymousHelperAndNoRealKeySigns() {
        val callerReal = newIdentity()
        val helperReal = newIdentity()
        val options = Bmh3Policy.initiatorOptions(
            routed(ByteArray(6) { 1 }), Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true,
            relayEdges = true, relayHop = RelayedCalls.hopOfferFor(prefixOf(helperReal), rotating = false),
        )
        assertNull("no tag toward a relay", options.contactTag)
        assertNull(options.identityOverride)
        val run = link(callerReal, options, helperReal, pseudonymHelper())
        val callerLink = run.initiator.getOrThrow()
        val helperLink = run.responder.getOrThrow()
        assertTrue(callerLink.ownPseudonymous && callerLink.peerPseudonymous)
        assertTrue(helperLink.ownPseudonymous && helperLink.peerPseudonymous)
        assertNotEquals(callerReal.nodeId, helperLink.peerNodeId)
        assertNotEquals(helperReal.nodeId, callerLink.peerNodeId)
        assertEquals("the caller's real key never signs toward a relay", 0, callerReal.signed.get())
        assertEquals(0, helperReal.signed.get())
        // A pseudonymous helper sends no BDO1 and the caller expects none.
        assertTrue(RelayIdentityPolicy.skipsLinkOpening(initiator = true, callerLink.ownPseudonymous, callerLink.peerPseudonymous))
        assertTrue(RelayIdentityPolicy.skipsLinkOpening(initiator = false, helperLink.ownPseudonymous, helperLink.peerPseudonymous))
        // The helper's dispatch admits exactly a SETUP (and a direct call's handshake), never an exchange.
        assertFalse(
            RelayIdentityPolicy.refusesFirstRecord(
                SecureRecordType.RELAY_SETUP, helperLink.ownPseudonymous, helperLink.peerPseudonymous, helperLink.qrTagMatched,
                pseudonymousEdges = true, helperPresent = true,
            ),
        )
        assertTrue(
            RelayIdentityPolicy.refusesFirstRecord(
                SecureRecordType.STORE_FORWARD, helperLink.ownPseudonymous, helperLink.peerPseudonymous, helperLink.qrTagMatched,
                pseudonymousEdges = true, helperPresent = true,
            ),
        )
    }

    @Test
    fun aHelperWithPseudonymsOffAnswersRealIsBoundAndAnImpostorWithARealKeyIsRefused() {
        val helperReal = newIdentity()
        val selectedHop = RelayedCalls.hopOfferFor(prefixOf(helperReal), rotating = false)
        fun options(hop: DiscoveryAnnouncement) = Bmh3Policy.initiatorOptions(
            routed(ByteArray(6) { 1 }), Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true, relayEdges = true, relayHop = hop,
        )
        // Mixed modes: the helper presents its real key to the throwaway caller (pseudonyms off on the helper).
        val ok = link(newIdentity(), options(selectedHop), helperReal, Bmh3ResponderOptions())
        val callerLink = ok.initiator.getOrThrow()
        assertFalse(callerLink.peerPseudonymous)
        assertEquals(helperReal.nodeId, callerLink.peerNodeId)
        // Both sides then run the BDO1 exchange (the responder used to skip it when its peer was a throwaway).
        assertFalse(RelayIdentityPolicy.skipsLinkOpening(initiator = true, callerLink.ownPseudonymous, callerLink.peerPseudonymous))
        val helperLink = ok.responder.getOrThrow()
        assertFalse(RelayIdentityPolicy.skipsLinkOpening(initiator = false, helperLink.ownPseudonymous, helperLink.peerPseudonymous))
        // A phone answering at the helper's address with another real key is not the selected helper.
        val impostor = link(newIdentity(), options(selectedHop), newIdentity(), Bmh3ResponderOptions())
        assertNotNull(impostor.initiator.exceptionOrNull())
    }

    @Test
    fun aHelpersOnwardEdgeBindsARealTargetAndRefusesAnImpostorButNotAThrowaway() {
        val targetReal = newIdentity()
        val targetOffer = RelayedCalls.hopOfferFor(prefixOf(targetReal), rotating = false)
        val real = link(newIdentity(), Bmh3Policy.relayHopOptions(targetOffer), targetReal, Bmh3ResponderOptions())
        assertEquals(targetReal.nodeId, real.initiator.getOrThrow().peerNodeId)
        val impostor = link(newIdentity(), Bmh3Policy.relayHopOptions(targetOffer), newIdentity(), Bmh3ResponderOptions())
        assertNotNull("a real key that is not the target fails the hop", impostor.initiator.exceptionOrNull())
        // A target in pseudonym mode answers with its connection throwaway: not bound here, the caller's end-to-end check
        // authenticates it (review CP-1: its only powers are those of a relay).
        val signedBefore = targetReal.signed.get()
        val pseudonymTarget = link(newIdentity(), Bmh3Policy.relayHopOptions(targetOffer), targetReal, pseudonymHelper())
        assertTrue(pseudonymTarget.initiator.getOrThrow().peerPseudonymous)
        assertEquals("a pseudonym-mode target's real key signs nothing for an untagged relay", signedBefore, targetReal.signed.get())
    }

    @Test
    fun everyRelayConnectionGetsItsOwnThrowawayNeverTheEndToEndAttemptKey() {
        val attempt = newIdentity()
        val keys = (1..4).map {
            Bmh3Policy.initiatorOptions(
                routed(ByteArray(6) { 1 }), Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true, strangerIdentity = attempt,
                relayEdges = true,
            ).strangerIdentity!!.publicKeyEncoded.toList()
        } + (1..4).map { Bmh3Policy.relayHopOptions(null).strangerIdentity!!.publicKeyEncoded.toList() }
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(keys.contains(attempt.publicKeyEncoded.toList()))
        // The end-to-end layer keeps the attempt pseudonym (it is not a relay edge).
        val endToEnd = Bmh3Policy.initiatorOptions(
            routed(ByteArray(6) { 1 }), Bmh3Policy.Layer.ENDPOINT, log = {}, pseudonyms = true, strangerIdentity = attempt, relayEdges = true,
        )
        assertEquals(attempt, endToEnd.strangerIdentity)
    }

    @Test
    fun noTagTowardARelayEvenWithTheDebugTestLease() {
        try {
            Bmh3Feature.configureDebug(only = false, contactsOnly = false, testLeaseHex = "07".repeat(32))
            val toRelay = routed(ByteArray(6) { 1 })
            assertNull(Bmh3ContactTags.initiatorTag(toRelay, 100L, toAdvertOwner = false))
            assertNull(Bmh3Policy.initiatorOptions(toRelay, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true).contactTag)
            assertNull(Bmh3Policy.initiatorOptions(toRelay, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true, relayEdges = true).contactTag)
            assertNull(Bmh3Policy.relayHopOptions(RelayedCalls.hopOfferFor(ByteArray(6) { 2 }, false)).contactTag)
            // Toward the advert's owner the test lease still applies (unchanged).
            assertNotNull(Bmh3ContactTags.initiatorTag(RelayedCalls.hopOfferFor(ByteArray(6) { 2 }, false), 100L))
        } finally {
            Bmh3Feature.configureDebug(only = false, contactsOnly = false, testLeaseHex = null)
        }
    }

    @Test
    fun theContactHelperArmRunsRealToRealAndFailsTheCandidateOnAThrowaway() {
        try {
            Bmh3Feature.configureDebug(only = false, contactsOnly = false, testLeaseHex = "05".repeat(32))
            val epoch = 1_990_000L
            val callerReal = newIdentity()
            val helperReal = newIdentity()
            val helperOffer = RelayedCalls.hopOfferFor(prefixOf(helperReal), rotating = false)
            val tagCheck = requireNotNull(Bmh3ContactTags.initiatorTag(helperOffer, epoch))
            fun options() = Bmh3Policy.relayLinkOptions(helperOffer, helperOffer, epoch, leaseSource = null)
            val contact = link(callerReal, options(), helperReal, Bmh3ResponderOptions(tagChecks = listOf(tagCheck), strangerIdentity = newIdentity()))
            val callerLink = contact.initiator.getOrThrow()
            assertFalse(callerLink.ownPseudonymous || callerLink.peerPseudonymous)
            assertEquals(helperReal.nodeId, callerLink.peerNodeId)
            // The same helper answering with a throwaway (no matching lease): this candidate fails, no anonymous retry inside it.
            val throwaway = link(newIdentity(), options(), helperReal, pseudonymHelper())
            assertNotNull(throwaway.initiator.exceptionOrNull())
        } finally {
            Bmh3Feature.configureDebug(only = false, contactsOnly = false, testLeaseHex = null)
        }
    }

    @Test
    fun aRelayEdgeNeverSpeaksBmh2InPseudonymMode() {
        // A version 2 offer without a BMH3 hint and without a pin: only pseudonym mode off lets BMH2 through.
        val v2 = RelayedCalls.hopOfferFor(ByteArray(6) { 3 }, rotating = false).copy(capabilitySummary = NodeCapabilities.CODEC_VP9)
        assertTrue(Bmh3Policy.relayHopUsesBmh3(bmh3Enabled = true, only = false, pseudonymMode = true, hop = v2, pinned = false))
        assertTrue(Bmh3Policy.relayHopUsesBmh3(bmh3Enabled = true, only = true, pseudonymMode = false, hop = v2, pinned = false))
        assertEquals(
            Bmh3Choice.decide(true, false, v2, false).bmh3,
            Bmh3Policy.relayHopUsesBmh3(bmh3Enabled = true, only = false, pseudonymMode = false, hop = v2, pinned = false),
        )
        assertFalse(Bmh3Policy.relayHopUsesBmh3(bmh3Enabled = false, only = false, pseudonymMode = false, hop = v2, pinned = false))
        // A rotating helper refuses a fixed-prefix SETUP (message 1): its target would need a BMH2 hop.
        assertTrue(RelayIdentityPolicy.refusesSetupMessage(pseudonymousEdges = true, helperRotating = true, setupRotating = false))
        assertFalse(RelayIdentityPolicy.refusesSetupMessage(pseudonymousEdges = true, helperRotating = true, setupRotating = true))
        assertFalse(RelayIdentityPolicy.refusesSetupMessage(pseudonymousEdges = false, helperRotating = true, setupRotating = false))
    }

    // ------------------------------------------------------------------ a relay in the middle of a stranger call

    @Test
    fun aRelayPosingAsTheCalleeCannotGetTheCallerToRevealFirst() {
        // The relay terminates both legs of a stranger call: caller <-> relay (the relay answers as the callee) and
        // relay <-> callee (the relay dials as the caller). The real callee's reveal is bound to the relay's leg.
        val callerReal = newIdentity()
        val calleeReal = newIdentity()
        val relayAsCallee = newIdentity()
        val relayAsCaller = newIdentity()
        val callerLeg = endToEnd(callerReal, relayAsCallee)
        val calleeLeg = endToEnd(relayAsCaller, calleeReal)
        val entry = OpeningEntry(ByteArray(6) { 1 }, 1_990_656L, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 3 })
        val calleeReveal = DiscoveryExchange.decode(requireNotNull(calleeLeg.second.reveal).sendOwn(calleeReal, listOf(entry)))
            as DiscoveryExchange.IdentityReveal
        // Forwarded into the caller's leg it does not verify, so the caller never sends its own reveal.
        assertThrows(Bmh3Failure::class.java) { requireNotNull(callerLeg.first.reveal).onPeerReveal(calleeReveal) }
        assertEquals("the caller's real key signed nothing", 0, callerReal.signed.get())
        // The callee's side of that leg is what review CP-1 states plainly: if the callee accepts, its reveal goes to
        // whoever holds the call, here the relay (hence the "via a phone nearby" ring and the stranger-call switch).
        assertNotNull(calleeReveal)
    }

    /** Link plus end-to-end handshake of a stranger call between [caller] and [callee] (both present throwaways). */
    private fun endToEnd(caller: SigningIdentity, callee: SigningIdentity): Pair<EndToEndVideoSession, EndToEndVideoSession> {
        val callerOptions = Bmh3InitiatorOptions(strangerIdentity = newIdentity())
        val calleeOptions = Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false)
        val pipes = Duplex()
        val links = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, caller, caps, bmh3 = callerOptions) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, callee, helperCaps, bmh3 = calleeOptions) },
            onFailure = { pipes.close() },
        )
        val ends = both(
            { EndToEndVideoHandshake.initiate(links.initiator.getOrThrow(), caller, caps, bmh3 = callerOptions) },
            { EndToEndVideoHandshake.respond(links.responder.getOrThrow(), callee, helperCaps, bmh3 = calleeOptions) },
            onFailure = { pipes.close() },
        )
        return ends.initiator.getOrThrow() to ends.responder.getOrThrow()
    }

    // ------------------------------------------------------------------ helper dispatch, limits and FAIL codes

    @Test
    fun theFirstRecordDispatchMatchesReviewCp1() {
        fun refuses(type: SecureRecordType, own: Boolean, peer: Boolean, qr: Boolean = false, edges: Boolean = true, helper: Boolean = true) =
            RelayIdentityPolicy.refusesFirstRecord(type, own, peer, qr, edges, helper)
        // A direct stranger call's first record is its end-to-end handshake: it always goes on to the call.
        assertFalse(refuses(SecureRecordType.END_TO_END_HANDSHAKE, own = true, peer = true))
        // Store-and-forward needs real keys on both ends.
        assertTrue(refuses(SecureRecordType.STORE_FORWARD, own = true, peer = true))
        assertTrue(refuses(SecureRecordType.STORE_FORWARD, own = false, peer = true))
        assertFalse(refuses(SecureRecordType.STORE_FORWARD, own = false, peer = false))
        // SETUP: real/real as before; a throwaway on either side only with the edges on and a helper present.
        assertFalse(refuses(SecureRecordType.RELAY_SETUP, own = false, peer = false))
        assertFalse(refuses(SecureRecordType.RELAY_SETUP, own = true, peer = true))
        assertFalse(refuses(SecureRecordType.RELAY_SETUP, own = false, peer = true))
        assertTrue(refuses(SecureRecordType.RELAY_SETUP, own = true, peer = true, edges = false))
        assertTrue(refuses(SecureRecordType.RELAY_SETUP, own = true, peer = true, helper = false))
        // A verification-code session never carries SETUP.
        assertTrue(refuses(SecureRecordType.RELAY_SETUP, own = false, peer = false, qr = true))
    }

    @Test
    fun manyThrowawaysFromOneAddressShareItsLimitAndTheGlobalCapHolds() {
        var now = 0L
        val limiter = RelaySetupRateLimiter({ now })
        val sameAddress = (1..20).map { RelayIdentityPolicy.limiterKey(pseudonymousParent = true, remoteAddress = "aa:bb:cc:dd:ee:01")!! }
        val admitted = sameAddress.count { limiter.tryAdmit(it) }
        assertEquals("fresh throwaways do not buy fresh quota", 3, admitted)
        // An unknown address is one shared bucket.
        now += 61_000
        val unknown = (1..5).count { limiter.tryAdmit(RelayIdentityPolicy.limiterKey(true, null)!!) }
        assertEquals(3, unknown)
        assertEquals(RelayIdentityPolicy.UNKNOWN_ADDRESS_BUCKET, RelayIdentityPolicy.limiterKey(true, ""))
        // Many addresses: the overall cap of 10 per minute.
        now += 61_000
        val spread = (1..30).count { limiter.tryAdmit(RelayIdentityPolicy.limiterKey(true, "aa:bb:cc:dd:%02x:01".format(it))!!) }
        assertEquals(10, spread)
        // A real parent keeps its own key (null: the caller passes its prefix).
        assertNull(RelayIdentityPolicy.limiterKey(pseudonymousParent = false, remoteAddress = "aa:bb:cc:dd:ee:01"))
    }

    @Test
    fun aClosureBeforeReadyIsATimeoutNotNoRoute() {
        fun failure(edges: Boolean, stage: BluetoothPrivateCallRelay.OnDemandStage, error: Exception) =
            BluetoothPrivateCallRelay(onStatus = {}, onStopped = {}).onDemandFailure(stage, error, pseudonymousEdges = edges)
        val connecting = BluetoothPrivateCallRelay.OnDemandStage.CONNECTING
        val authenticating = BluetoothPrivateCallRelay.OnDemandStage.AUTHENTICATING
        assertEquals(RelaySetupFailure.TIMEOUT, failure(true, connecting, java.io.IOException("read failed")))
        assertEquals(RelaySetupFailure.TIMEOUT, failure(true, authenticating, java.io.EOFException()))
        assertEquals(RelaySetupFailure.CHILD_AUTHENTICATION, failure(true, authenticating, IllegalArgumentException("not the target")))
        assertEquals(RelaySetupFailure.NO_ROUTE, failure(false, connecting, java.io.IOException("read failed")))
    }

    // ------------------------------------------------------------------ the target's decision

    @Test
    fun theTargetRingsARelayedCallOnlyWithRelayingAndConsentOnAndStrangersByTheirSwitch() {
        fun decide(via: Boolean, relayed: Boolean = true, consent: Boolean = true, stranger: Boolean = false, strangerOn: Boolean = true) =
            RelayIdentityPolicy.incoming(via, relayed, consent, stranger, strangerOn)
        assertEquals(RelayIdentityPolicy.Incoming.RING, decide(via = false, relayed = false, consent = false))
        assertEquals(RelayIdentityPolicy.Incoming.RING, decide(via = true))
        // A dishonest helper ignores the consent bit: the target enforces it, and its experiment being off.
        assertEquals(RelayIdentityPolicy.Incoming.DECLINE_RELAYING_OFF, decide(via = true, relayed = false))
        assertEquals(RelayIdentityPolicy.Incoming.DECLINE_RELAYING_OFF, decide(via = true, consent = false))
        assertEquals(RelayIdentityPolicy.Incoming.RING, decide(via = true, stranger = true))
        assertEquals(RelayIdentityPolicy.Incoming.DECLINE_STRANGER, decide(via = true, stranger = true, strangerOn = false))
        assertTrue(RelayIdentityPolicy.strangerRingStatus(viaRelay = true).contains("via a phone nearby"))
        assertTrue(RelayIdentityPolicy.strangerRingStatus(viaRelay = true).contains("which may be a relaying phone"))
        assertFalse(RelayIdentityPolicy.strangerRingStatus(viaRelay = false).contains("relaying"))
    }
}
