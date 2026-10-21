package com.example.bluetoothtest

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Design SR revision 2 (Experimental, `test_call_link_renewal`): wire, keys, opening, scope and both state machines. */
class CallLinkRenewalTest {
    private var now = 100_000L
    private val clock = { now }
    private val callId = UUID.fromString("0b6f3a52-6a39-4d5b-9b55-3c1e7a1c9d01")
    private val ticket = 0x1122334455667788L
    private val k1 = ByteArray(32) { (it * 7 + 1).toByte() }
    private val k2 = ByteArray(32) { (200 - it).toByte() }
    private val key = CallRenewalCrypto.renewalKey(k1, k2)
    private val direct = CallRenewalScope.Facts(
        gattOnBothEnds = true, relayHops = 0, relayedRoute = false, linkPeerIsEndpointPeer = true, verificationSession = false,
    )

    // ----- wire -----

    @Test
    fun recordsHaveTheirExactLayouts() {
        val offer = CallRenewalWire.encode(CallRenewalWire.Offer(ticket, 1))
        assertEquals(10, offer.size)
        assertEquals(1, offer[0].toInt())
        assertEquals(CallRenewalWire.Offer(ticket, 1), CallRenewalWire.decode(offer))
        assertEquals(9, CallRenewalWire.encode(CallRenewalWire.Armed(ticket)).size)
        val refused = CallRenewalWire.Refused(ticket, CallRenewalWire.Reason.ABANDONED)
        assertEquals(refused, CallRenewalWire.decode(CallRenewalWire.encode(refused)))
        val bind = CallRenewalWire.Bind(ticket, 42L)
        val encoded = CallRenewalWire.encode(bind)
        assertEquals(17, encoded.size)
        assertEquals(bind, CallRenewalWire.decode(encoded))
    }

    @Test
    fun malformedRecordsFailClosed() {
        val bad = listOf(
            byteArrayOf(),
            byteArrayOf(9) + ByteArray(8) { 1 },
            CallRenewalWire.encode(CallRenewalWire.Armed(ticket)) + byteArrayOf(0),
            byteArrayOf(2) + ByteArray(8),
            CallRenewalWire.encode(CallRenewalWire.Offer(ticket, 0)).also { it[9] = 2 },
            CallRenewalWire.encode(CallRenewalWire.Refused(ticket, CallRenewalWire.Reason.LIMIT)).also { it[9] = 8 },
            CallRenewalWire.encode(CallRenewalWire.Bind(ticket, 1L)).copyOf(16),
        )
        bad.forEach { payload ->
            try {
                CallRenewalWire.decode(payload)
                fail("accepted ${payload.size} bytes")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun theCapabilityIsEndToEndBitFourAndBothSidesMustSignIt() {
        val base = NodeCapabilities(
            transportMask = NodeCapabilities.TRANSPORT_BLE_GATT or PeerLabelFeature.CAPABILITY_BIT,
            encodeCodecMask = 1, decodeCodecMask = 1, maxBitrateKbps = 600, maxChildren = 0,
        )
        assertEquals(0x10, CallRenewalWire.CAPABILITY_BIT)
        val signed = CallRenewalWire.advertise(base, enabled = true)
        assertEquals(base.transportMask or 0x10, signed.transportMask)
        assertEquals(base, CallRenewalWire.advertise(base, enabled = false))
        assertTrue(CallRenewalWire.negotiated(true, signed))
        assertFalse(CallRenewalWire.negotiated(true, base))
        assertFalse(CallRenewalWire.negotiated(false, signed))
        // Bit 4 is not one of the end-to-end bits already in use (3 BDX1, 5 LABELS, 7 fragments).
        listOf(CallEndpointOpening.HELLO_BIT, PeerLabelFeature.CAPABILITY_BIT, CallVideoFragments.CAPABILITY_BIT).forEach {
            assertEquals(0, it and CallRenewalWire.CAPABILITY_BIT)
        }
    }

    @Test
    fun anOpenerAcceptsTypeTwelveOnlyAfterItsEndpointSignedTheBit() {
        val sealer = EndToEndContentSealer(k1)
        val opener = EndToEndContentOpener(k1)
        val record = sealer.seal(EndToEndContentType.RENEW, 0, CallRenewalWire.encode(CallRenewalWire.Armed(ticket)))
        try {
            opener.open(record)
            fail("type 12 opened without the bit")
        } catch (_: IllegalStateException) {
        }
        opener.acceptRenewal = true
        val opened = opener.open(record)
        assertEquals(EndToEndContentType.RENEW, opened.type)
        assertEquals(0L, opened.sequence)
    }

    @Test
    fun bindNamesTheSequenceAfterItsOwnRecord() {
        val sealer = EndToEndContentSealer(k1)
        val opener = EndToEndContentOpener(k1).apply { acceptRenewal = true }
        repeat(3) { opener.open(sealer.seal(EndToEndContentType.KEY_FRAME_REQUEST, 0, byteArrayOf())) }
        val sealed = sealer.sealNext(EndToEndContentType.RENEW, 0) { seq -> CallRenewalWire.encode(CallRenewalWire.Bind(ticket, seq + 1)) }
        val content = opener.open(sealed)
        val bind = CallRenewalWire.decode(content.payload) as CallRenewalWire.Bind
        assertEquals(3L, content.sequence)
        assertEquals(content.sequence + 1, bind.nextSequence)
    }

    // ----- keys and opening -----

    @Test
    fun bothEndpointsDeriveTheSameRenewalKeyWithoutARole() {
        assertArrayEquals(key, CallRenewalCrypto.renewalKey(k2, k1))
        assertEquals(32, key.size)
        assertFalse(key.contentEquals(k1) || key.contentEquals(k2))
    }

    @Test
    fun theAdvertUuidIsAVersionFourUuidNewForEveryTicket() {
        val uuid = CallRenewalCrypto.advertUuid(key, callId, ticket)
        assertEquals(4, uuid.version())
        assertEquals(2, uuid.variant())
        assertNotEquals(uuid, CallRenewalCrypto.advertUuid(key, callId, ticket + 1))
        assertNotEquals(uuid, CallRenewalCrypto.advertUuid(CallRenewalCrypto.renewalKey(k1, k1), callId, ticket))
    }

    @Test
    fun theProductionOpeningProvesTheKeyBothWaysAndBothSidesGetTheSameHopKeys() {
        val run = opening(armedKey = key)
        val offerer = requireNotNull(run.offerer)
        val armed = requireNotNull(run.armed)
        assertArrayEquals("offerer to armed", offerer.first, armed.first)
        assertArrayEquals("armed to offerer", offerer.second, armed.second)
        assertFalse(offerer.first.contentEquals(offerer.second))
        assertEquals(listOf(ticket to true), run.admitted)
    }

    @Test
    fun anAdvertCopiedFromTheAirCannotConfirm() {
        // A squatter that copied the UUID answers with a key it does not have: the offerer refuses its confirmation.
        val run = opening(armedKey = CallRenewalCrypto.renewalKey(k1, k1))
        assertNull(run.offerer)
        assertEquals("the squatter's own check refuses the real proof", listOf(ticket to false), run.admitted)
    }

    @Test
    fun aProofForAnotherCallOrTicketIsRefused() {
        val challenge = ByteArray(16) { 5 }
        val nonce = ByteArray(16) { 9 }
        val good = proof(ticket, challenge, nonce, callId)
        assertTrue(CallRenewalOpening.proofValid(key, callId, ticket, challenge, good))
        val otherCall = proof(ticket, challenge, nonce, UUID.randomUUID())
        assertFalse(CallRenewalOpening.proofValid(key, callId, ticket, challenge, otherCall))
        val otherTicket = proof(ticket + 1, challenge, nonce, callId)
        assertFalse(CallRenewalOpening.proofValid(key, callId, ticket, challenge, otherTicket))
        val otherChallenge = proof(ticket, ByteArray(16) { 6 }, nonce, callId)
        assertFalse(CallRenewalOpening.proofValid(key, callId, ticket, challenge, otherChallenge))
    }

    @Test
    fun aConfirmationReflectedAsAProofIsRefused() {
        val confirm = CallRenewalFrames.confirm(CallRenewalCrypto.armedMac(key, callId, ticket, ByteArray(16), ByteArray(16)))
        for (frame in listOf(confirm, confirm + ByteArray(CallRenewalFrames.PROOF_BYTES - confirm.size))) {
            try {
                CallRenewalFrames.parseProof(frame)
                fail("a confirmation parsed as a proof")
            } catch (_: IllegalArgumentException) {
            }
        }
        // The armed MAC over the same inputs is never a valid offer MAC (separate labels).
        val challenge = ByteArray(16) { 1 }
        val nonce = ByteArray(16) { 2 }
        val reflected = CallRenewalFrames.Proof(ticket, nonce, CallRenewalCrypto.armedMac(key, callId, ticket, challenge, nonce))
        assertFalse(CallRenewalOpening.proofValid(key, callId, ticket, challenge, reflected))
        try {
            CallRenewalFrames.parseProof(CallRenewalFrames.proof(ticket, nonce, ByteArray(32)).also { it.fill(0, 4, 12) })
            fail("a zero ticket parsed")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun hopKeysDependOnTicketChallengeAndNonce() {
        val challenge = ByteArray(16) { 1 }
        val nonce = ByteArray(16) { 2 }
        val base = CallRenewalCrypto.hopKeys(key, callId, ticket, challenge, nonce)
        val variants = listOf(
            CallRenewalCrypto.hopKeys(key, callId, ticket + 1, challenge, nonce),
            CallRenewalCrypto.hopKeys(key, callId, ticket, ByteArray(16) { 3 }, nonce),
            CallRenewalCrypto.hopKeys(key, callId, ticket, challenge, ByteArray(16) { 4 }),
            CallRenewalCrypto.hopKeys(key, UUID.randomUUID(), ticket, challenge, nonce),
        )
        variants.forEach { variant ->
            assertFalse(variant.first.contentEquals(base.first))
            assertFalse(variant.second.contentEquals(base.second))
        }
    }

    @Test
    fun onlyAWellFormedBindNamingItsOwnSequencePlusOneIsAccepted() {
        val sealer = EndToEndContentSealer(k1)
        val opener = EndToEndContentOpener(k1).apply { acceptRenewal = true }
        repeat(2) { opener.open(sealer.seal(EndToEndContentType.KEY_FRAME_REQUEST, 0, byteArrayOf())) }
        val wrongFloor = opener.open(sealer.seal(EndToEndContentType.RENEW, 0, CallRenewalWire.encode(CallRenewalWire.Bind(ticket, 7))))
        assertNull("nextSequence != sequence + 1", CallRenewalOpening.bindFrom(wrongFloor))
        val notBind = opener.open(sealer.seal(EndToEndContentType.RENEW, 0, CallRenewalWire.encode(CallRenewalWire.Armed(ticket))))
        assertNull(CallRenewalOpening.bindFrom(notBind))
        val otherType = opener.open(sealer.seal(EndToEndContentType.KEY_FRAME_REQUEST, 0, byteArrayOf()))
        assertNull(CallRenewalOpening.bindFrom(otherType))
        assertNull(CallRenewalOpening.bindFrom(null))
        val good = opener.open(sealer.sealNext(EndToEndContentType.RENEW, 0) { CallRenewalWire.encode(CallRenewalWire.Bind(ticket, it + 1)) })
        assertEquals(CallRenewalWire.Bind(ticket, good.sequence + 1), CallRenewalOpening.bindFrom(good))
    }

    @Test
    fun anOldLinkRecordOpenedAfterBindIsRefusedNotProcessed() {
        // The shared opener requires increasing sequences: an old-link record sealed before BIND cannot be opened after it.
        val sealer = EndToEndContentSealer(k1)
        val opener = EndToEndContentOpener(k1).apply { acceptRenewal = true }
        val oldLinkRecord = sealer.seal(EndToEndContentType.KEY_FRAME_REQUEST, 0, byteArrayOf())
        val bind = sealer.sealNext(EndToEndContentType.RENEW, 0) { CallRenewalWire.encode(CallRenewalWire.Bind(ticket, it + 1)) }
        assertTrue(CallRenewalOpening.bindFrom(opener.open(bind)) != null)
        try {
            opener.open(oldLinkRecord)
            fail("an old-link record opened after BIND")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ----- scope -----

    @Test
    fun noRenewalOnRelayedOrOtherCalls() {
        val notDirect = listOf(
            direct.copy(relayHops = 1),
            direct.copy(relayedRoute = true),
            direct.copy(linkPeerIsEndpointPeer = false),
            direct.copy(gattOnBothEnds = false),
            direct.copy(verificationSession = true),
        )
        notDirect.forEach { facts ->
            assertEquals(facts.toString(), CallRenewalWire.Reason.NOT_DIRECT, refusal(facts))
        }
        assertNull(refusal(direct))
    }

    @Test
    fun limitsAndSwitchesAreChecked() {
        assertEquals(CallRenewalWire.Reason.DISABLED, refusal(direct, enabled = false))
        assertEquals(CallRenewalWire.Reason.DISABLED, refusal(direct, negotiated = false))
        assertEquals(CallRenewalWire.Reason.LIMIT, refusal(direct, phaseActive = false))
        assertEquals(CallRenewalWire.Reason.LIMIT, refusal(direct, activeForMs = 9_999))
        assertEquals(CallRenewalWire.Reason.LIMIT, refusal(direct, attempts = 2))
        assertEquals(CallRenewalWire.Reason.LIMIT, refusal(direct, sinceLast = 59_999))
        assertNull(refusal(direct, attempts = 1, sinceLast = 60_000))
    }

    // ----- offerer -----

    @Test
    fun theOffererWaitsOneSecondOfSoftStateThenScansAndBreaksOnlyAfterFound() {
        val offerer = RenewalOfferer(clock)
        assertFalse(offerer.shouldTrigger(null))
        assertFalse(offerer.shouldTrigger(now - 999))
        assertTrue(offerer.shouldTrigger(now - 1_000))
        offerer.offer(ticket)
        assertTrue(offerer.ownsLiveness())
        now += 2_500
        assertEquals(RenewalOfferer.Tick.NONE, offerer.tick())
        assertTrue(offerer.onAdvertFound())
        assertEquals(RenewalOfferer.Phase.CONNECTING, offerer.phase)
        assertFalse("a second sighting does not break again", offerer.onAdvertFound())
        now += 1_000
        assertTrue(offerer.onBound())
        assertEquals(RenewalOfferer.Phase.BOUND, offerer.phase)
        assertTrue("ownership holds until the switch reset the timers", offerer.ownsLiveness())
        offerer.reset()
        assertFalse(offerer.ownsLiveness())
    }

    @Test
    fun anOffererThatFindsNothingKeepsTheOldLinkUnderTheNormalRules() {
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        now += RenewalOfferer.SCAN_MS
        assertEquals(RenewalOfferer.Tick.STOP_SCAN, offerer.tick())
        assertEquals(RenewalOfferer.Phase.WAITING_OLD_LINK, offerer.phase)
        assertFalse(offerer.ownsLiveness())
        assertFalse("too late to break the old link", offerer.onAdvertFound())
        now += 10_000
        assertEquals(RenewalOfferer.Tick.NONE, offerer.tick())
        assertTrue("the peer heard again abandons the renewal", offerer.onPeerHeard())
    }

    @Test
    fun hearingThePeerBeforeTheBreakAbandonsTheRenewal() {
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        now += 1_000
        assertTrue(offerer.onPeerHeard())
        assertEquals(RenewalOfferer.Phase.ABANDONED, offerer.phase)
        assertFalse(offerer.onAdvertFound())
        offerer.reset()
        assertEquals(RenewalOfferer.Phase.IDLE, offerer.phase)
    }

    @Test
    fun aBrokenOldLinkEndsTheCallTwelveSecondsAfterTheOffer() {
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        now += 3_000
        assertTrue(offerer.onAdvertFound())
        assertFalse("after the break the old link cannot be heard", offerer.onPeerHeard())
        now += 7_999
        assertTrue(offerer.mayRetry())
        now += 1
        assertFalse("no new attempt after 11 s", offerer.mayRetry())
        now += 999
        assertEquals(RenewalOfferer.Tick.NONE, offerer.tick())
        now += 1
        assertEquals(RenewalOfferer.Tick.END_CALL, offerer.tick())
        assertFalse(offerer.onBound())
    }

    @Test
    fun aCollisionKeepsTheCallersOffer() {
        assertTrue(RenewalCollision.keepOwn(0))
        assertFalse(RenewalCollision.keepOwn(1))
        val callee = RenewalOfferer(clock)
        callee.offer(ticket)
        assertTrue(callee.yieldToPeer())
        assertFalse(callee.ownsLiveness())
    }

    // ----- armed phone -----

    @Test
    fun anOutOfScopeOfferIsNotArmed() {
        val armed = RenewalArmed(clock)
        assertFalse(armed.onOffer(ticket, CallRenewalWire.Reason.NOT_DIRECT))
        assertEquals(RenewalArmed.Phase.IDLE, armed.phase)
        assertFalse(armed.suspendsLiveness())
    }

    @Test
    fun aRegistrationSlowerThanThreeSecondsIsRefused() {
        val armed = RenewalArmed(clock)
        assertTrue(armed.onOffer(ticket, null))
        assertTrue(armed.suspendsLiveness())
        now += RenewalArmed.REGISTRATION_MS
        assertEquals(RenewalArmed.Tick.REFUSE_REGISTRATION, armed.tick(oldLinkAlive = true))
        assertFalse("a late server is never adopted", armed.onServerReady())
        assertFalse(armed.suspendsLiveness())
    }

    @Test
    fun aSquatterIsDroppedAfterOneSecondAndTheRealOffererStillBinds() {
        val armed = armedAndReady()
        val squatter = (armed.onConnection(1) as RenewalArmed.Admission.Challenge).connection
        now += 999
        assertFalse(armed.proofOverdue(squatter))
        now += 1
        assertTrue(armed.proofOverdue(squatter))
        armed.onConnectionClosed(squatter)
        val real = (armed.onConnection(2) as RenewalArmed.Admission.Challenge).connection
        now += 300
        assertEquals(RenewalArmed.ProofResult.CONFIRM, armed.onProof(real, ticket, valid = true))
        assertFalse(armed.proofOverdue(real))
        assertEquals(RenewalArmed.BindResult.ACCEPT, armed.onBind(real, ticket, firstRecord = true))
        assertEquals(RenewalArmed.Phase.BOUND, armed.phase)
    }

    @Test
    fun aWrongProofIsDroppedAndTheTicketIsUsedOnce() {
        val armed = armedAndReady()
        armed.onConnection(1)
        assertEquals(RenewalArmed.ProofResult.DROP, armed.onProof(1, ticket, valid = false))
        armed.onConnection(2)
        assertEquals(RenewalArmed.ProofResult.DROP, armed.onProof(2, ticket + 1, valid = true))
        armed.onConnection(3)
        assertEquals(RenewalArmed.ProofResult.CONFIRM, armed.onProof(3, ticket, valid = true))
        assertEquals("only one proved connection", RenewalArmed.Admission.Drop, armed.onConnection(4))
    }

    @Test
    fun bindIsRefusedOnTheWrongLinkOrOutOfOrderAndAcceptedOnce() {
        val armed = armedAndReady()
        armed.onConnection(1)
        armed.onConnection(2)
        assertEquals(RenewalArmed.ProofResult.CONFIRM, armed.onProof(2, ticket, valid = true))
        assertEquals("old link", RenewalArmed.BindResult.IGNORE, armed.onBind(null, ticket, firstRecord = true))
        assertEquals("unproved link", RenewalArmed.BindResult.IGNORE, armed.onBind(1, ticket, firstRecord = true))
        assertEquals("not the first record", RenewalArmed.BindResult.IGNORE, armed.onBind(2, ticket, firstRecord = false))
        assertEquals("another ticket", RenewalArmed.BindResult.IGNORE, armed.onBind(2, ticket + 1, firstRecord = true))
        assertEquals(RenewalArmed.BindResult.ACCEPT, armed.onBind(2, ticket, firstRecord = true))
        assertEquals("once", RenewalArmed.BindResult.IGNORE, armed.onBind(2, ticket, firstRecord = true))
    }

    @Test
    fun connectionsPerAttemptAreBounded() {
        val armed = armedAndReady()
        repeat(RenewalArmed.MAX_CONNECTIONS) { assertTrue(armed.onConnection(it.toLong()) is RenewalArmed.Admission.Challenge) }
        assertEquals(RenewalArmed.Admission.Drop, armed.onConnection(99))
    }

    @Test
    fun theAdvertStopsAtSevenSecondsAndTheBoundEndsOnlyACallWhoseOldLinkIsGone() {
        val gone = armedAndReady()
        now += RenewalArmed.ADVERT_MS
        assertEquals(RenewalArmed.Tick.STOP_ADVERT, gone.tick(oldLinkAlive = false))
        assertEquals(RenewalArmed.Tick.NONE, gone.tick(oldLinkAlive = false))
        now += RenewalArmed.HARD_BOUND_MS - RenewalArmed.ADVERT_MS
        assertEquals(RenewalArmed.Tick.END_CALL, gone.tick(oldLinkAlive = false))

        val alive = armedAndReady()
        now += RenewalArmed.HARD_BOUND_MS
        assertEquals(RenewalArmed.Tick.DISARM, alive.tick(oldLinkAlive = true))
        assertFalse(alive.suspendsLiveness())
    }

    @Test
    fun anAbandonedOfferDisarmsAtOnce() {
        val armed = armedAndReady()
        assertFalse(armed.onAbandoned(ticket + 1))
        assertTrue(armed.onAbandoned(ticket))
        assertFalse(armed.suspendsLiveness())
        assertEquals(RenewalArmed.Admission.Drop, armed.onConnection(1))
    }

    @Test
    fun attemptsAreCountedPerCall() {
        val attempts = RenewalAttempts()
        assertNull(attempts.sinceLast(now))
        attempts.record(now)
        now += 5_000
        assertEquals(1, attempts.count)
        assertEquals(5_000L, attempts.sinceLast(now))
    }

    // ----- review fixes: liveness, servers, connect budget, collisions -----

    @Test
    fun aDeadLinkNobodyOwnsEndsTheCall() {
        // The zombie call: the old link died quietly during a renewal that then ended without binding.
        assertEquals(RenewalLiveness.Decision.NONE, RenewalLiveness.decide(linkDead = true, renewalOwnsLiveness = true, silenceEndSinceMs = null, nowMs = now))
        assertEquals(RenewalLiveness.Decision.END_DEAD_LINK, RenewalLiveness.decide(linkDead = true, renewalOwnsLiveness = false, silenceEndSinceMs = null, nowMs = now))
        assertEquals(RenewalLiveness.Decision.NONE, RenewalLiveness.decide(linkDead = false, renewalOwnsLiveness = false, silenceEndSinceMs = null, nowMs = now))
    }

    @Test
    fun aSilenceTheWriterDidNotEndIsEndedAfterTheGrace() {
        val since = now
        assertEquals(RenewalLiveness.Decision.NONE, RenewalLiveness.decide(false, false, since, since + RenewalLiveness.GRACE_MS - 1))
        assertEquals(RenewalLiveness.Decision.END_SILENCE, RenewalLiveness.decide(false, false, since, since + RenewalLiveness.GRACE_MS))
        assertEquals(RenewalLiveness.Decision.NONE, RenewalLiveness.decide(false, true, since, since + 60_000))
    }

    @Test
    fun scanFailureAndRegistrationRefusalHandLivenessBack() {
        // After these the renewal no longer owns liveness, so a dead link is ended by the supervisor.
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        now += RenewalOfferer.SCAN_MS
        offerer.tick()
        assertFalse(offerer.ownsLiveness())
        val armed = RenewalArmed(clock)
        armed.onOffer(ticket, null)
        now += RenewalArmed.REGISTRATION_MS
        armed.tick(oldLinkAlive = false)
        assertFalse(armed.suspendsLiveness())
        assertEquals(RenewalLiveness.Decision.END_DEAD_LINK,
            RenewalLiveness.decide(true, offerer.ownsLiveness() || armed.suspendsLiveness(), null, now))
    }

    @Test
    fun anEndVerdictWhileConnectingNeverEndsARenewalThatBinds() {
        // Re-review N1: the offerer's verdict is END by connect time; BOUND keeps ownership until the switch.
        val ticker = RenewalLivenessTicker()
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        now += 3_000
        offerer.onAdvertFound()
        repeat(20) {
            now += 250
            assertEquals(RenewalLiveness.Decision.NONE, ticker.tick(true, false, offerer.ownsLiveness(), now))
        }
        assertTrue(offerer.onBound())
        now += 250
        assertEquals("bound, timers not reset yet", RenewalLiveness.Decision.NONE, ticker.tick(true, false, offerer.ownsLiveness(), now))
        offerer.reset() // after the switch reset lastHeartbeatNs
        now += 250
        assertEquals(RenewalLiveness.Decision.NONE, ticker.tick(false, false, offerer.ownsLiveness(), now))
    }

    @Test
    fun aHandbackGetsTheFullGrace() {
        val ticker = RenewalLivenessTicker()
        now += 250
        assertEquals(RenewalLiveness.Decision.NONE, ticker.tick(true, false, true, now))
        now += 5_000
        assertEquals("owned: never ended", RenewalLiveness.Decision.NONE, ticker.tick(true, false, true, now))
        now += 250
        assertEquals("handed back: the grace starts now", RenewalLiveness.Decision.NONE, ticker.tick(true, false, false, now))
        now += RenewalLiveness.GRACE_MS - 1
        assertEquals(RenewalLiveness.Decision.NONE, ticker.tick(true, false, false, now))
        now += 1
        assertEquals(RenewalLiveness.Decision.END_SILENCE, ticker.tick(true, false, false, now))
    }

    @Test
    fun armedOwnershipHoldsThroughBindUntilTheSwitch() {
        val armed = armedAndReady()
        armed.onConnection(1)
        armed.onProof(1, ticket, valid = true)
        assertEquals(RenewalArmed.BindResult.ACCEPT, armed.onBind(1, ticket, firstRecord = true))
        assertTrue(armed.suspendsLiveness())
        armed.reset()
        assertFalse(armed.suspendsLiveness())
    }

    @Test
    fun requeuedSignalsSkipThoseTheReassertionResends() {
        val id = UUID.randomUUID()
        val taken = listOf(
            PrivateCallSignalMessage(id, PrivateCallSignal.MICROPHONE_MUTED, PrivateCallReason.NONE),
            PrivateCallSignalMessage(id, PrivateCallSignal.HANG_UP, PrivateCallReason.NORMAL),
            PrivateCallSignalMessage(id, PrivateCallSignal.CAMERA_OFF, PrivateCallReason.NONE),
        )
        assertEquals(listOf(PrivateCallSignal.HANG_UP), RenewalRequeue.signalsToRequeue(taken).map { it.signal })
    }

    @Test
    fun requeuedControlsGoAheadOfNewerOnes() {
        val queue = BoundedControlQueue<String>(capacity = 8, name = "test", onRefused = {})
        queue.offerMust("newer")
        queue.offerMustFirst(listOf("old-1", "old-2"))
        assertEquals(listOf("old-1", "old-2", "newer"), listOf(queue.poll(), queue.poll(), queue.poll()))
    }

    private class FakeServer(val name: String) : java.io.Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun aSecondRenewalNeverClosesTheLiveLink() {
        val servers = RenewalServers<FakeServer>()
        val first = FakeServer("first")
        assertTrue(servers.startAttempt(first))
        assertNull(servers.promote())
        assertEquals(first, servers.bound())
        val second = FakeServer("second")
        assertTrue(servers.startAttempt(second))
        servers.abandonAttempt()
        assertTrue(second.closed)
        assertFalse("the live link's server stays", first.closed)
        val third = FakeServer("third")
        servers.startAttempt(third)
        assertEquals("the older live server is handed back to close after the switch", first, servers.promote())
        assertFalse(third.closed)
    }

    @Test
    fun teardownClosesEveryServerAndRefusesLateAttempts() {
        val servers = RenewalServers<FakeServer>()
        val live = FakeServer("live")
        servers.startAttempt(live)
        servers.promote()
        val attempt = FakeServer("attempt")
        servers.startAttempt(attempt)
        servers.closeAll()
        assertTrue(live.closed && attempt.closed)
        val late = FakeServer("late")
        assertFalse("adopted after teardown: the caller closes it", servers.startAttempt(late))
    }

    @Test
    fun theConnectBudgetNeverPassesTheLastConnectMoment() {
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        assertEquals("no budget before the break", 0L, offerer.connectBudgetMs())
        now += 2_000
        assertTrue(offerer.onAdvertFound())
        assertEquals(RenewalOfferer.LAST_CONNECT_MS - 2_000, offerer.connectBudgetMs())
        now += 8_999
        assertEquals(1L, offerer.connectBudgetMs())
        now += 1
        assertEquals(0L, offerer.connectBudgetMs())
        assertFalse(offerer.mayRetry())
    }

    @Test
    fun onlyAScanningOfferIsAPendingCollision() {
        val offerer = RenewalOfferer(clock)
        offerer.offer(ticket)
        assertTrue(offerer.ownOfferPending())
        now += RenewalOfferer.SCAN_MS
        offerer.tick()
        assertFalse("a finished scan is not a pending offer", offerer.ownOfferPending())
        assertFalse(offerer.yieldToPeer())
    }

    @Test
    fun onlyCallSessionsWithTheBitOnBothSidesDeriveTheKey() {
        val base = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, 1, 1, 600, 0)
        val signed = CallRenewalWire.advertise(base, enabled = true)
        assertTrue(CallRenewalWire.bothSigned(signed, signed))
        assertFalse(CallRenewalWire.bothSigned(signed, base))
        assertFalse(CallRenewalWire.bothSigned(base, signed))
    }

    @Test
    fun addressKindsAreReadFromTheTopBits() {
        assertEquals("rpa", CallRenewalAndroid.addressKind("4F:12:34:56:78:9A"))
        assertEquals("static", CallRenewalAndroid.addressKind("C3:12:34:56:78:9A"))
        assertEquals("other", CallRenewalAndroid.addressKind("12:12:34:56:78:9A"))
        assertEquals("unknown", CallRenewalAndroid.addressKind(null))
    }

    // ----- helpers -----

    private fun armedAndReady(): RenewalArmed = RenewalArmed(clock).also {
        assertTrue(it.onOffer(ticket, null))
        now += 500
        assertTrue(it.onServerReady())
    }

    private fun refusal(
        facts: CallRenewalScope.Facts,
        enabled: Boolean = true,
        negotiated: Boolean = true,
        phaseActive: Boolean = true,
        activeForMs: Long = 30_000,
        attempts: Int = 0,
        sinceLast: Long? = null,
    ) = CallRenewalScope.refusal(enabled, negotiated, facts, phaseActive, activeForMs, attempts, sinceLast)

    private class OpeningRun(
        val offerer: Pair<ByteArray, ByteArray>?,
        val armed: Pair<ByteArray, ByteArray>?,
        val admitted: List<Pair<Long, Boolean>>,
    )

    /** Both production halves (CallRenewalOpening) over pipes; [armedKey] differs from the offerer's for a squatter. */
    private fun opening(armedKey: ByteArray): OpeningRun {
        val toArmed = PipedOutputStream()
        val armedIn = PipedInputStream(toArmed, 4_096)
        val toOfferer = PipedOutputStream()
        val offererIn = PipedInputStream(toOfferer, 4_096)
        val admitted = mutableListOf<Pair<Long, Boolean>>()
        var armedResult: Pair<ByteArray, ByteArray>? = null
        val armedThread = thread {
            armedResult = runCatching {
                CallRenewalOpening.armed(armedIn, toOfferer, armedKey, callId, ticket, ByteArray(16) { (it + 3).toByte() }) { proofTicket, valid ->
                    synchronized(admitted) { admitted += proofTicket to valid }
                    // A squatter would confirm anyway, with its own (wrong) key: that is what the offerer must refuse.
                    true
                }
            }.getOrNull()
        }
        val offererResult = runCatching {
            CallRenewalOpening.offerer(offererIn, toArmed, key, callId, ticket, ByteArray(16) { (it + 11).toByte() })
        }.getOrNull()
        armedThread.join(5_000)
        return OpeningRun(offererResult, armedResult, synchronized(admitted) { admitted.toList() })
    }

    private fun proof(proofTicket: Long, challenge: ByteArray, nonce: ByteArray, forCall: UUID): CallRenewalFrames.Proof =
        CallRenewalFrames.parseProof(
            CallRenewalFrames.proof(proofTicket, nonce, CallRenewalCrypto.offerMac(key, forCall, proofTicket, challenge, nonce)),
        )
}
