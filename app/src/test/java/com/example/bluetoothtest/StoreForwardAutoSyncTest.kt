package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental automatic store-and-forward sync: scheduler, backoff, gate and outbox states. */
class StoreForwardAutoSyncTest {
    private val roots = ArrayList<File>()
    private val clockMs = AtomicLong(1_790_000_000_000L)
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 3 }, "AES") }

    @After
    fun cleanUp() = roots.forEach { it.deleteRecursively() }

    private val policy = AutoSyncPolicy(jitter = 0.0)
    private fun scheduler(local: String = "000000000000", p: AutoSyncPolicy = policy, random: () -> Double = { 0.5 }) =
        StoreForwardAutoSyncScheduler(p, local, random)

    private val peer = "aaaaaaaaaaaa"

    @Test
    fun featureNeedsBenchmarkExtraAndStoreForward() {
        val none = { _: String -> null as Boolean? }
        // A benchmark launch: only its own extra, and only with store-and-forward on.
        assertFalse(StoreForwardAutoSyncFeature.resolve(true, { true }, none, storeForward = false))
        assertFalse(StoreForwardAutoSyncFeature.resolve(true, { false }, { true }, storeForward = true))
        assertTrue(StoreForwardAutoSyncFeature.resolve(true, { it == StoreForwardAutoSyncFeature.EXTRA }, none, storeForward = true))
        // A user session: the explicit Settings choice (a visible switch), else the default; never without Messages.
        val chosen = { key: String -> if (key == StoreForwardAutoSyncFeature.PREF) true else null }
        assertTrue(StoreForwardAutoSyncFeature.resolve(false, { false }, chosen, storeForward = true))
        assertFalse(StoreForwardAutoSyncFeature.resolve(false, { false }, chosen, storeForward = false))
        assertEquals(CallFeatureDefaults.AUTO_SYNC, StoreForwardAutoSyncFeature.resolve(false, { true }, none, storeForward = true))
        assertEquals("test_sf_auto_sync", StoreForwardAutoSyncFeature.EXTRA)
    }

    @Test
    fun backoffGrowsByBaseAndStopsAtCap() {
        val s = scheduler()
        assertEquals(65_000L, s.intervalMs(AutoSyncReason.OUTBOX, 0))
        assertEquals(97_500L, s.intervalMs(AutoSyncReason.OUTBOX, 1))
        assertEquals(146_250L, s.intervalMs(AutoSyncReason.OUTBOX, 2))
        assertEquals(600_000L, s.intervalMs(AutoSyncReason.OUTBOX, 6))
        assertEquals(600_000L, s.intervalMs(AutoSyncReason.OUTBOX, 1_000))
        assertEquals(600_000L, s.intervalMs(AutoSyncReason.PULL, 0))
        assertEquals(1_800_000L, s.intervalMs(AutoSyncReason.PULL, 10))
        // Never below the responder's 60 s per-neighbour cooldown.
        assertTrue(AutoSyncPolicy().outboxMinMs > 60_000)
    }

    @Test
    fun firstSightingStartsAtOnceThenWaitsForTheBackoff() {
        val s = scheduler()
        assertEquals(AutoSyncDecision.Start(AutoSyncReason.OUTBOX), s.decide(peer, 0, AutoSyncReason.OUTBOX, null))
        s.begin(peer, 0)
        assertEquals(AutoSyncDecision.Skip("in_flight"), s.decide("bbbbbbbbbbbb", 1, AutoSyncReason.OUTBOX, null))
        s.onConnectFailed(peer, 1_000)
        assertEquals(1, s.failures(peer))
        assertEquals(AutoSyncDecision.Wait(1_000 + 97_500, AutoSyncReason.OUTBOX), s.decide(peer, 2_000, AutoSyncReason.OUTBOX, null))
        assertEquals(AutoSyncDecision.Start(AutoSyncReason.OUTBOX), s.decide(peer, 98_500, AutoSyncReason.OUTBOX, null))
    }

    @Test
    fun progressResetsBackoffAndEmptyExchangesGrowIt() {
        val s = scheduler()
        repeat(3) { s.begin(peer, 0); s.onResult(peer, 0, StoreForwardSyncOutcome.INTERRUPTED, progressed = false) }
        assertEquals(3, s.failures(peer))
        s.onResult(peer, 0, StoreForwardSyncOutcome.COMPLETE, progressed = false)
        assertEquals(4, s.failures(peer))
        s.onResult(peer, 0, StoreForwardSyncOutcome.RATE_LIMITED, progressed = false)
        assertEquals(5, s.failures(peer))
        s.onResult(peer, 0, StoreForwardSyncOutcome.INTERRUPTED, progressed = true)
        assertEquals(0, s.failures(peer))
    }

    @Test
    fun mediaSuspensionKeepsBackoffButRestartsTheWait() {
        val s = scheduler()
        s.onConnectFailed(peer, 0) // not in flight: ignored
        assertEquals(0, s.failures(peer))
        s.begin(peer, 0)
        s.onConnectFailed(peer, 0)
        s.begin(peer, 100_000)
        s.onResult(peer, 100_000, StoreForwardSyncOutcome.SUSPENDED_LOCAL, progressed = false)
        assertEquals(1, s.failures(peer))
        s.onResult(peer, 100_000, StoreForwardSyncOutcome.SUSPENDED_BY_PEER, progressed = false)
        assertEquals(1, s.failures(peer))
        assertNull(s.inFlight())
    }

    @Test
    fun responderSideResultCountsSoPhonesDoNotTakeTurns() {
        val s = scheduler()
        s.decide(peer, 0, AutoSyncReason.PULL, null)
        // The peer dialled us and the exchange moved data: our own pull now waits a full interval.
        s.onResult(peer, 5_000, StoreForwardSyncOutcome.COMPLETE, progressed = true)
        assertEquals(AutoSyncDecision.Wait(605_000, AutoSyncReason.PULL), s.decide(peer, 6_000, AutoSyncReason.PULL, null))
        // Something new to send uses the shorter outbox interval.
        assertEquals(AutoSyncDecision.Start(AutoSyncReason.OUTBOX), s.decide(peer, 70_000, AutoSyncReason.OUTBOX, null))
    }

    @Test
    fun higherPrefixWaitsTheTieBreakDelay() {
        val high = scheduler(local = "ffffffffffff")
        assertEquals(AutoSyncDecision.Wait(10_000, AutoSyncReason.OUTBOX), high.decide(peer, 0, AutoSyncReason.OUTBOX, null))
        assertEquals(AutoSyncDecision.Start(AutoSyncReason.OUTBOX), high.decide(peer, 10_000, AutoSyncReason.OUTBOX, null))
        val low = scheduler(local = "000000000001")
        assertEquals(AutoSyncDecision.Start(AutoSyncReason.OUTBOX), low.decide(peer, 0, AutoSyncReason.OUTBOX, null))
    }

    @Test
    fun blockedOrNothingToSyncNeverStarts() {
        val s = scheduler()
        assertEquals(AutoSyncDecision.Skip("call"), s.decide(peer, 0, AutoSyncReason.OUTBOX, "call"))
        assertEquals(AutoSyncDecision.Skip("nothing_to_sync"), s.decide(peer, 0, null, null))
    }

    @Test
    fun cancelReleasesTheSlotWithoutPenalty() {
        val s = scheduler()
        s.begin(peer, 0)
        s.cancel(peer)
        assertNull(s.inFlight())
        assertEquals(0, s.failures(peer))
        assertEquals(AutoSyncDecision.Start(AutoSyncReason.OUTBOX), s.decide(peer, 1, AutoSyncReason.OUTBOX, null))
    }

    @Test
    fun lostAttemptExpiresAfterTimeout() {
        val s = scheduler()
        s.begin(peer, 0)
        assertEquals(AutoSyncDecision.Skip("in_flight"), s.decide("bbbbbbbbbbbb", 179_999, AutoSyncReason.OUTBOX, null))
        s.decide("bbbbbbbbbbbb", 180_000, AutoSyncReason.OUTBOX, null)
        assertNull(s.inFlight())
        assertEquals(1, s.failures(peer))
    }

    @Test
    fun jitterStaysWithinBounds() {
        val p = AutoSyncPolicy(jitter = 0.15)
        val low = scheduler(p = p, random = { 0.0 })
        low.onResult(peer, 0, StoreForwardSyncOutcome.COMPLETE, progressed = true)
        assertEquals(AutoSyncDecision.Wait(55_250, AutoSyncReason.OUTBOX), low.decide(peer, 1, AutoSyncReason.OUTBOX, null))
        val high = scheduler(p = p, random = { 1.0 })
        high.onResult(peer, 0, StoreForwardSyncOutcome.COMPLETE, progressed = true)
        assertEquals(AutoSyncDecision.Wait(74_750, AutoSyncReason.OUTBOX), high.decide(peer, 1, AutoSyncReason.OUTBOX, null))
    }

    @Test
    fun gateLetsMediaWin() {
        val idle = AutoSyncGate(true, true, false, false, false, false, false)
        assertNull(idle.blockedBy())
        assertEquals("call", idle.copy(callActive = true, liveMedia = true).blockedBy())
        assertEquals("media", idle.copy(liveMedia = true).blockedBy())
        assertEquals("exchange_running", idle.copy(exchangeRunning = true).blockedBy())
        assertEquals("user_selection", idle.copy(userSelectionPending = true).blockedBy())
        assertEquals("screen", idle.copy(onCallScreen = false).blockedBy())
        assertEquals("endpoint_busy", idle.copy(endpointIdle = false).blockedBy())
        assertEquals("thermal", idle.copy(thermalBlocked = true).blockedBy())
    }

    @Test
    fun outboxStateMachine() {
        val max = 8
        assertEquals(OutboxStatus.QUEUED, OutboxStatusRules.status(BundleState.QUEUED, 0, false, max))
        assertEquals(OutboxStatus.QUEUED, OutboxStatusRules.status(BundleState.QUEUED, 7, false, max))
        assertEquals(OutboxStatus.FAILED, OutboxStatusRules.status(BundleState.QUEUED, 8, false, max))
        assertEquals(OutboxStatus.CARRIED, OutboxStatusRules.status(BundleState.QUEUED, 8, true, max))
        assertEquals(OutboxStatus.DELIVERED, OutboxStatusRules.status(BundleState.HANDED, 9, true, max))
        assertEquals(OutboxStatus.DELIVERED, OutboxStatusRules.status(BundleState.DELIVERED, 0, false, max))
        assertEquals(OutboxStatus.FAILED, OutboxStatusRules.status(BundleState.FAILED, 0, false, max))
        assertNull(OutboxStatusRules.status(BundleState.OPENED, 0, false, max))
        assertNull(OutboxStatusRules.status(BundleState.STORED, 0, false, max))
    }

    @Test
    fun attemptCountingIgnoresSuspendRateLimitAndProgress() {
        assertTrue(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.COMPLETE, 5))
        assertTrue(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.INTERRUPTED, 0))
        assertTrue(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.TIMEOUT, 0))
        assertFalse(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.INTERRUPTED, 3))
        assertFalse(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.SUSPENDED_LOCAL, 0))
        assertFalse(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.SUSPENDED_BY_PEER, 0))
        assertFalse(OutboxStatusRules.countsAsAttempt(StoreForwardSyncOutcome.RATE_LIMITED, 0))
    }

    @Test
    fun ledgerPersistsSealedAndPrunes() {
        val dir = Files.createTempDirectory("sf-auto").toFile().also(roots::add)
        val file = File(dir, "outbox-attempts.properties")
        val ledger = OutboxAttemptLedger(file, cipher)
        assertEquals(mapOf("a" to 1, "b" to 1), ledger.recordAttempt(listOf("a", "b")))
        assertEquals(mapOf("a" to 2), ledger.recordAttempt(listOf("a")))
        ledger.markCarried("b")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("attempts"))
        val reopened = OutboxAttemptLedger(file, cipher)
        assertEquals(2, reopened.attempts("a"))
        assertTrue(reopened.carried("b"))
        reopened.retain(setOf("b"))
        assertEquals(0, OutboxAttemptLedger(file, cipher).attempts("a"))
        assertTrue(OutboxAttemptLedger(file, cipher).carried("b"))
    }

    private fun node(): StoreForwardNode {
        val dir = Files.createTempDirectory("sf-auto-node").toFile().also(roots::add)
        return StoreForwardNode.open(
            dir, KeyPairSigningIdentity(generateEcKeyPair()), SoftwareMailboxKey.generate(), cipher,
            StoreForwardLimits(), { clockMs.get() },
        )
    }

    private fun result(peer: NodeId, outcome: StoreForwardSyncOutcome, chunksSent: Int = 0) = StoreForwardSyncResult(
        peer, outcome, chunksSent, 0, 0, 0, 0, 0, emptyList(), null, emptyMap(), null,
    )

    @Test
    fun plannerFindsQueuedMessagesAndFailsAfterEightAttempts() {
        val alice = node()
        val bob = node()
        val carol = node()
        alice.contacts.addAccepted(bob.ownCard, "bob")
        alice.contacts.addAccepted(carol.ownCard, "carol")
        val ledger = OutboxAttemptLedger(null, null)
        val bobContact = StoreForwardAutoSyncPlanner.contactFor(alice, bob.nodeId.toByteArray().copyOf(6))!!
        assertEquals(bob.nodeId, bobContact.nodeId)
        assertNull(StoreForwardAutoSyncPlanner.contactFor(alice, ByteArray(6)))
        // Nothing queued: only a pull.
        assertEquals(AutoSyncReason.PULL, StoreForwardAutoSyncPlanner.need(alice, ledger, bobContact, policy).reason(true))
        assertNull(StoreForwardAutoSyncPlanner.need(alice, ledger, bobContact, policy).reason(false))
        assertNull(StoreForwardAutoSyncPlanner.need(alice, ledger, null, policy).reason(true))

        val sent = alice.send(bob.nodeId, BundleMessage(MessageKind.TEXT, clockMs.get(), ByteArray(16), null, "text/plain", "hi".toByteArray()))
        alice.send(carol.nodeId, BundleMessage(MessageKind.TEXT, clockMs.get(), ByteArray(16), null, "text/plain", "yo".toByteArray()))
        val need = StoreForwardAutoSyncPlanner.need(alice, ledger, bobContact, policy)
        assertEquals(1, need.queued)
        assertEquals(AutoSyncReason.OUTBOX, need.reason(true))
        assertEquals(0, need.carried)

        // Suspended and rate-limited exchanges are not attempts.
        assertTrue(StoreForwardAutoSyncPlanner.recordExchange(alice, ledger, result(bob.nodeId, StoreForwardSyncOutcome.SUSPENDED_LOCAL)).isEmpty())
        assertTrue(StoreForwardAutoSyncPlanner.recordExchange(alice, ledger, result(bob.nodeId, StoreForwardSyncOutcome.RATE_LIMITED)).isEmpty())
        repeat(7) {
            assertEquals(mapOf(sent.idHex to it + 1),
                StoreForwardAutoSyncPlanner.recordExchange(alice, ledger, result(bob.nodeId, StoreForwardSyncOutcome.COMPLETE)))
        }
        val stored = alice.store.get(sent.idHex)!!
        assertEquals(OutboxStatus.QUEUED, StoreForwardAutoSyncPlanner.status(ledger, stored, policy))
        StoreForwardAutoSyncPlanner.recordExchange(alice, ledger, result(bob.nodeId, StoreForwardSyncOutcome.COMPLETE))
        assertEquals(OutboxStatus.FAILED, StoreForwardAutoSyncPlanner.status(ledger, stored, policy))
        // A failed message no longer triggers automatic sync, but is still offered (manual Exchange).
        val after = StoreForwardAutoSyncPlanner.need(alice, ledger, bobContact, policy)
        assertEquals(0, after.queued)
        assertEquals(1, after.failed)
        assertEquals(AutoSyncReason.PULL, after.reason(true))
        assertTrue(alice.offers(bob.nodeId, false).any { it.idHex == sent.idHex })
        // Carol's message was never charged by Bob's exchanges.
        val carolId = alice.store.all().single { it.peerNode == carol.nodeId.toString() }.idHex
        assertEquals(0, ledger.attempts(carolId))

        // Delivery (DELIVERED_LOCAL) beats the failed mark.
        assertTrue(alice.onDeliveredLocal(sent.header.bundleId, bob.nodeId))
        assertEquals(OutboxStatus.DELIVERED, StoreForwardAutoSyncPlanner.status(ledger, alice.store.get(sent.idHex)!!, policy))
    }

    @Test
    fun keyOfMatchesDiscoveryPrefix() {
        val n = node()
        assertEquals(n.nodeId.toByteArray().copyOf(6).joinToString("") { "%02x".format(it) }, StoreForwardAutoSyncScheduler.keyOf(n.nodeId))
    }
}
