package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1d, the thread link policy (DECISIONS Design M6): who offers a chat session, who dials, when a session ends. */
class MessagingV2ChatTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()
    private val now = AtomicLong(1_000_000L)
    private val clock: () -> Long = { now.get() }

    @After
    fun cleanUp() = rig.cleanUp()

    private fun presence(policy: ChatSessionPolicy = ChatSessionPolicy()) = ChatPresence(clock, policy)

    private fun controller(
        phone: SfRig.Phone,
        presence: ChatPresence = presence(),
        policy: ChatSessionPolicy = ChatSessionPolicy(),
        enabled: () -> Boolean = { true },
    ) = ChatSessionController(phone.node, presence, policy, nowMs = clock, random = { 0.5 }, enabled = enabled)

    private fun result(peer: NodeId, outcome: StoreForwardSyncOutcome, chat: Boolean = false) = StoreForwardSyncResult(
        peer, outcome, 0, 0, 0, 0, 0, 0, emptyList(), null, emptyMap(), null, chat,
    )

    /** Two phones that know each other, ordered by node ID: the first has the lower one. */
    private fun orderedPair(): Pair<SfRig.Phone, SfRig.Phone> {
        val a = rig.Phone(chat = true)
        val b = rig.Phone(chat = true)
        rig.contacts(a, b)
        return if (a.nodeId.toString() < b.nodeId.toString()) a to b else b to a
    }

    private class Directory(val nodes: MutableSet<NodeId> = HashSet()) : VerifiedIdentityDirectory {
        override fun verifiedLabel(identityKey: ByteArray): String? = if (NodeId.fromPublicKey(identityKey) in nodes) "" else null
        override fun isVerifiedNode(node: NodeId): Boolean = node in nodes
    }

    private fun waitUntil(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    // ---- the chat flag ------------------------------------------------------------------------------------------------------

    @Test
    fun aPhoneOffersAChatSessionOnlyWhileItHasAReasonToStay() {
        val (me, peer) = orderedPair()
        val presence = presence()
        val c = controller(me, presence)
        assertFalse("no thread, app in the background", c.chatCapable(peer.nodeId))
        presence.threadOpened(peer.nodeId)
        assertTrue(c.chatCapable(peer.nodeId))
        presence.threadClosed(peer.nodeId)
        now.addAndGet(119_000)
        assertTrue("a quick look away keeps the session", c.chatCapable(peer.nodeId))
        now.addAndGet(2_000)
        assertFalse("left for more than 2 minutes", c.chatCapable(peer.nodeId))
        assertEquals(ChatInterest.NONE, presence.interest(peer.nodeId))
    }

    @Test
    fun theForegroundAppJoinsASessionTheContactOpensEvenWithoutItsOwnThread() {
        val (me, peer) = orderedPair()
        val presence = presence()
        val c = controller(me, presence)
        presence.setAppForeground(true)
        assertEquals(ChatInterest.FOREGROUND, presence.interest(peer.nodeId))
        assertTrue(c.chatCapable(peer.nodeId))
        presence.setAppForeground(false)
        assertTrue("left the app a moment ago", c.chatCapable(peer.nodeId))
        now.addAndGet(121_000)
        assertFalse(c.chatCapable(peer.nodeId))
        // The policy can require an open thread instead.
        val strict = presence(ChatSessionPolicy(joinWhenForeground = false))
        strict.setAppForeground(true)
        assertFalse(controller(me, strict, ChatSessionPolicy(joinWhenForeground = false)).chatCapable(peer.nodeId))
    }

    @Test
    fun aPhoneThatStartsAnExchangeNeedsAThreadOfItsOwnButAnAnsweringForegroundPhoneMayJoin() {
        val (me, peer) = orderedPair()
        val presence = presence()
        val c = controller(me, presence)
        presence.setAppForeground(true)
        // Two phones with the app open and a plain sync (the DEBUG sf_sync, auto sync): the exchange must end by itself.
        assertFalse("the dialler has no thread open", c.chatCapable(peer.nodeId, dialer = true))
        assertTrue("an answering phone in the foreground joins", c.chatCapable(peer.nodeId, dialer = false))
        presence.threadOpened(peer.nodeId)
        assertTrue(c.chatCapable(peer.nodeId, dialer = true))
        presence.threadClosed(peer.nodeId)
        presence.setAppForeground(false)
        assertTrue("left a moment ago: still a reason", c.chatCapable(peer.nodeId, dialer = true))
        now.addAndGet(121_000)
        assertFalse(c.chatCapable(peer.nodeId, dialer = true))
    }

    @Test
    fun twoForegroundPhonesWithNoThreadOpenSyncOnceAndEndInsteadOfHoldingTheLink() {
        val (alice, bob) = orderedPair()
        val aliceController = controller(alice, presence().also { it.setAppForeground(true) })
        val bobController = controller(bob, presence().also { it.setAppForeground(true) })
        val a = rig.Phone(chat = aliceController.chatCapable(bob.nodeId, dialer = true))
        val b = rig.Phone(chat = bobController.chatCapable(alice.nodeId, dialer = false))
        rig.contacts(a, b)
        a.engine.sendText(b.nodeId, "plain sync")
        val result = rig.sync(a, b)
        assertFalse(result.initiator.chat)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(listOf("plain sync"), result.responder.v2Messages.map { it.text })
    }

    @Test
    fun theDebugThreadControlFindsAContactInTheCacheOrReadsTheStoreWhenTheCacheMisses() {
        fun entry(fingerprint: String) = ContactEntry(fingerprint, "", 1, true)
        val s24 = entry("0096b299a99f" + "1".repeat(52))
        val other = entry("aa11bb22cc33" + "2".repeat(52))
        var storeReads = 0
        val fresh = { storeReads++; listOf(s24, other) }
        assertEquals(s24, DebugContactLookup.find("0096b299a99f", listOf(s24), fresh).entry)
        assertEquals("a cache hit reads nothing", 0, storeReads)
        val viaStore = DebugContactLookup.find("0096B299A99F", emptyList(), fresh)
        assertEquals(s24, viaStore.entry)
        assertTrue(viaStore.fromStore)
        assertEquals("not_verified", DebugContactLookup.find("ffffffffffff", emptyList(), fresh).why)
        assertEquals("no_verified_contacts", DebugContactLookup.find("0096b299a99f", emptyList()) { emptyList() }.why)
        assertEquals("bad_prefix", DebugContactLookup.find("xyz", emptyList(), fresh).why)
        assertEquals("ambiguous_prefix", DebugContactLookup.find("0096", emptyList()) { listOf(s24, entry("0096" + "3".repeat(60))) }.why)
    }

    @Test
    fun onlyATrustedPeerIsOfferedAChatSession() {
        val me = rig.Phone(chat = true)
        val stranger = rig.Phone()
        val verified = rig.Phone()
        val presence = presence()
        presence.threadOpened(stranger.nodeId)
        presence.threadOpened(verified.nodeId)
        val c = controller(me, presence)
        assertFalse("a stranger is never offered a session", c.chatCapable(stranger.nodeId))
        // A person verified in person is trusted before the card arrives.
        me.node.verifiedDirectory = Directory(mutableSetOf(verified.nodeId))
        assertTrue(c.chatCapable(verified.nodeId))
    }

    @Test
    fun noSessionIsOfferedWhenTheSwitchIsOffTheKeysChangedOrTheNodeIsV1() {
        val (me, peer) = orderedPair()
        val presence = presence().also { it.threadOpened(peer.nodeId) }
        var on = true
        val c = controller(me, presence, enabled = { on })
        assertTrue(c.chatCapable(peer.nodeId))
        on = false
        assertFalse("test_mv2_chat_session off is the control arm", c.chatCapable(peer.nodeId))
        on = true
        me.node.contacts.offerFromLinkV2(
            ContactCard.create(peer.identity, SoftwareMailboxKey.generate(), rig.clock() + 1_000), peer.nodeId, null,
        )
        assertTrue(me.node.contacts.get(peer.nodeId)!!.keysChanged)
        assertFalse("nothing is sent to a contact whose keys changed", c.chatCapable(peer.nodeId))
        val v1 = rig.Phone(v2 = false)
        v1.knows(peer)
        assertFalse(controller(v1, presence).chatCapable(peer.nodeId))
    }

    // ---- who dials and when ---------------------------------------------------------------------------------------------------

    @Test
    fun onlyAnOpenThreadOfAContactDialsAndTheGateAlwaysWins() {
        val (me, peer) = orderedPair()
        val presence = presence()
        val c = controller(me, presence)
        assertEquals(ChatDial.Skip("thread_closed"), c.dialDecision(peer.nodeId, ChatGate()))
        presence.setAppForeground(true)
        assertEquals("the foreground alone never dials", ChatDial.Skip("thread_closed"), c.dialDecision(peer.nodeId, ChatGate()))
        presence.threadOpened(peer.nodeId)
        assertEquals(ChatDial.Start, c.dialDecision(peer.nodeId, ChatGate()))
        assertEquals(ChatDial.Skip("call"), c.dialDecision(peer.nodeId, ChatGate(callActive = true)))
        assertEquals(ChatDial.Skip("media"), c.dialDecision(peer.nodeId, ChatGate(liveMedia = true)))
        assertEquals(ChatDial.Skip("exchange_running"), c.dialDecision(peer.nodeId, ChatGate(exchangeRunning = true)))
        assertEquals(ChatDial.Skip("endpoint_busy"), c.dialDecision(peer.nodeId, ChatGate(endpointBusy = true)))
        assertEquals(ChatDial.Skip("thermal"), c.dialDecision(peer.nodeId, ChatGate(thermalBlocked = true)))
        assertEquals(ChatDial.Skip("not_contact"), c.dialDecision(rig.Phone().nodeId, ChatGate()))
        assertEquals(ChatDial.Skip("disabled"), controller(me, presence, enabled = { false }).dialDecision(peer.nodeId, ChatGate()))
    }

    @Test
    fun theLowerNodeIdDialsAtOnceAndTheHigherWaitsForItsDial() {
        val (lower, higher) = orderedPair()
        val lowerPresence = presence().also { it.threadOpened(higher.nodeId) }
        val higherPresence = presence().also { it.threadOpened(lower.nodeId) }
        val lowerController = controller(lower, lowerPresence)
        val higherController = controller(higher, higherPresence)
        assertEquals(ChatDial.Start, lowerController.dialDecision(higher.nodeId, ChatGate()))
        val wait = higherController.dialDecision(lower.nodeId, ChatGate())
        assertTrue(wait is ChatDial.Wait)
        assertEquals(now.get() + 4_000, (wait as ChatDial.Wait).untilMs)
        now.addAndGet(3_999)
        assertTrue(higherController.dialDecision(lower.nodeId, ChatGate()) is ChatDial.Wait)
        now.addAndGet(1)
        assertEquals("nobody dialled: the higher ID dials itself", ChatDial.Start, higherController.dialDecision(lower.nodeId, ChatGate()))
    }

    @Test
    fun aDialInFlightBlocksASecondOneUntilItsResultOrTimeout() {
        val (me, peer) = orderedPair()
        val presence = presence().also { it.threadOpened(peer.nodeId) }
        val c = controller(me, presence)
        c.onDialStarted(peer.nodeId)
        assertEquals(ChatDial.Skip("dial_in_flight"), c.dialDecision(peer.nodeId, ChatGate()))
        now.addAndGet(59_000)
        assertEquals(ChatDial.Skip("dial_in_flight"), c.dialDecision(peer.nodeId, ChatGate()))
        now.addAndGet(2_000)
        // The dial is treated as lost: one failure, and the retry waits.
        assertTrue(c.dialDecision(peer.nodeId, ChatGate()) is ChatDial.Wait)
        assertEquals(1, c.failures(peer.nodeId))
    }

    @Test
    fun failedDialsBackOffExponentiallyUpToTheCapAndASuccessResetsThem() {
        val (me, peer) = orderedPair()
        val presence = presence().also { it.threadOpened(peer.nodeId) }
        val c = controller(me, presence)
        val delays = ArrayList<Long>()
        repeat(8) {
            c.onDialStarted(peer.nodeId)
            c.onConnectFailed(peer.nodeId)
            val decision = c.dialDecision(peer.nodeId, ChatGate()) as ChatDial.Wait
            delays += decision.untilMs - now.get()
            now.set(decision.untilMs)
        }
        assertEquals(listOf(8_250L, 12_375L, 18_562L, 27_843L, 41_765L, 60_000L, 60_000L, 60_000L), delays)
        c.onExchangeResult(result(peer.nodeId, StoreForwardSyncOutcome.COMPLETE, chat = true))
        assertEquals(0, c.failures(peer.nodeId))
    }

    @Test
    fun whatTheLastExchangeDidDecidesTheNextDial() {
        val (me, peer) = orderedPair()
        val presence = presence().also { it.threadOpened(peer.nodeId) }
        val c = controller(me, presence)
        // A session that ended (30 minutes, or cleanly) is re-opened a second later while the thread is open.
        c.onExchangeResult(result(peer.nodeId, StoreForwardSyncOutcome.COMPLETE, chat = true))
        assertEquals(5_500L, (c.dialDecision(peer.nodeId, ChatGate()) as ChatDial.Wait).untilMs - now.get())
        now.addAndGet(5_500)
        assertEquals(ChatDial.Start, c.dialDecision(peer.nodeId, ChatGate()))
        // A plain exchange this phone asked for: the contact did not offer a chat session, so its normal cooldown applies.
        c.onDialStarted(peer.nodeId)
        c.onExchangeResult(result(peer.nodeId, StoreForwardSyncOutcome.COMPLETE, chat = false))
        assertEquals(65_000L, (c.dialDecision(peer.nodeId, ChatGate()) as ChatDial.Wait).untilMs - now.get())
        now.addAndGet(65_000)
        // A call took the radio: not a failure.
        c.onExchangeResult(result(peer.nodeId, StoreForwardSyncOutcome.SUSPENDED_LOCAL, chat = true))
        assertEquals(0, c.failures(peer.nodeId))
        // The responder refused (cooldown): wait out the v1 cooldown.
        now.addAndGet(5_000)
        c.onExchangeResult(result(peer.nodeId, StoreForwardSyncOutcome.RATE_LIMITED))
        assertEquals(65_000L, (c.dialDecision(peer.nodeId, ChatGate()) as ChatDial.Wait).untilMs - now.get())
        assertEquals(1, c.failures(peer.nodeId))
    }

    @Test
    fun aRunningSessionBlocksAnotherDialToTheSameContact() {
        val (alice, bob) = orderedPair()
        val presence = presence().also { it.threadOpened(bob.nodeId) }
        val c = controller(alice, presence)
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        assertEquals(ChatDial.Skip("session_running"), c.dialDecision(bob.nodeId, ChatGate()))
        live.sessionA.endChat()
        live.join()
    }

    // ---- ending a session -----------------------------------------------------------------------------------------------------

    @Test
    fun theTickEndsASessionOnlyAfterTheThreadWasLeftForTwoMinutes() {
        val (alice, bob) = orderedPair()
        val presence = presence().also { it.threadOpened(bob.nodeId) }
        val c = controller(alice, presence)
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        assertTrue("the thread is open", c.tick().isEmpty())
        presence.threadClosed(bob.nodeId)
        now.addAndGet(119_000)
        assertTrue("still within the grace", c.tick().isEmpty())
        Thread.sleep(150)
        assertTrue(live.sessionA.isChat)
        now.addAndGet(2_000)
        assertEquals(listOf(bob.nodeId), c.tick())
        val pair = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.responder.outcome)
        assertTrue(pair.initiator.chat && pair.responder.chat)
    }

    @Test
    fun aForegroundAppDoesNotKeepTheDialersSessionAliveAfterItsThreadWasLeft() {
        // lane3-asus-a15s-m31-20261013 build 99f28d1f: the opener left the thread, its app stayed in the foreground, and the
        // session lasted 8+ minutes without "chat end ... why=thread_left".
        val (alice, bob) = orderedPair()
        val presence = presence().also { it.threadOpened(bob.nodeId); it.setAppForeground(true) }
        val c = controller(alice, presence)
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        assertTrue(live.sessionA.isInitiator && !live.sessionB.isInitiator)
        assertTrue("the thread is open", c.tick().isEmpty())
        presence.threadClosed(bob.nodeId)
        now.addAndGet(119_000)
        assertTrue("still within the grace", c.tick().isEmpty())
        now.addAndGet(2_000)
        assertEquals("the foreground screen is no reason for the dialer", listOf(bob.nodeId), c.tick())
        val pair = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.responder.outcome)
    }

    @Test
    fun theAnsweringPhoneStaysWhileItsAppIsInTheForegroundButCannotHoldTheSessionAlone() {
        val (alice, bob) = orderedPair()
        val dialerPresence = presence().also { it.threadOpened(bob.nodeId) }
        val answererPresence = presence().also { it.setAppForeground(true) }
        val dialer = controller(alice, dialerPresence)
        val answerer = controller(bob, answererPresence)
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        now.addAndGet(600_000)
        assertTrue("an answering foreground phone keeps its side", answerer.tick().isEmpty())
        assertTrue("the opener's thread is still open", dialer.tick().isEmpty())
        dialerPresence.threadClosed(bob.nodeId)
        now.addAndGet(121_000)
        assertTrue("the answerer alone does not end it", answerer.tick().isEmpty())
        assertEquals("the opener ends it about 2 minutes after leaving", listOf(bob.nodeId), dialer.tick())
        assertEquals(StoreForwardSyncOutcome.COMPLETE, live.join(120_000).responder.outcome)
    }

    @Test
    fun theAnsweringPhoneLogsItsOwnChatEndWhenTheOpenerEndsTheSession() {
        // lane3 build 33b0b869: only the opener logged "chat end ... why=thread_left"; the answerer's log showed no end.
        val (alice, bob) = orderedPair()
        val logs = ArrayList<String>()
        val live = rig.open(alice, bob, logs = logs)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        live.sessionA.endChat()
        live.join(120_000)
        val ended = synchronized(logs) { logs.filter { "chat end" in it } }
        assertEquals("only the answering side logs it here (the opener's tick logs thread_left)", 1, ended.size)
        assertTrue(
            ended.single(),
            ended.single().startsWith("B:") && "why=peer_ended" in ended.single() &&
                "peer=${alice.nodeId.shortLabel()}" in ended.single(),
        )
    }

    @Test
    fun switchingTheFeatureOffEndsRunningSessions() {
        val (alice, bob) = orderedPair()
        val presence = presence().also { it.threadOpened(bob.nodeId) }
        var on = true
        val c = controller(alice, presence, enabled = { on })
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        on = false
        assertEquals(listOf(bob.nodeId), c.tick())
        assertEquals(StoreForwardSyncOutcome.COMPLETE, live.join(120_000).initiator.outcome)
    }

    @Test
    fun aSessionEndsAtItsMaximumAndTheOpenThreadReopensIt() {
        val limits = MessagingV2Limits(chatMaxMs = 400)
        val a = rig.Phone(v2Limits = limits, chat = true)
        val b = rig.Phone(v2Limits = limits, chat = true)
        rig.contacts(a, b)
        val (alice, bob) = if (a.nodeId.toString() < b.nodeId.toString()) a to b else b to a
        val presence = presence().also { it.threadOpened(bob.nodeId) }
        val c = controller(alice, presence)
        // The sessions' own clock is virtual: the maximum (400 ms) is reached by advancing it once the chat is open, not by a
        // race between a real 400 ms and how soon a loaded machine finishes the HELLO.
        val sessionClock = AtomicLong(0)
        val live = rig.open(alice, bob, monotonicMs = sessionClock::get)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        sessionClock.addAndGet(401)
        val pair = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.initiator.outcome)
        assertTrue(pair.initiator.chat)
        c.onExchangeResult(pair.initiator)
        assertTrue(c.dialDecision(bob.nodeId, ChatGate()) is ChatDial.Wait)
        now.addAndGet(5_500)
        assertEquals("the thread is still open: a new session", ChatDial.Start, c.dialDecision(bob.nodeId, ChatGate()))
        // And the new session really opens and carries text.
        val again = rig.open(alice, bob, monotonicMs = { 0L }) // frozen: the 400 ms maximum cannot end it before the test does
        waitUntil { again.sessionA.isChat }
        again.sessionA.endChat()
        assertTrue(again.join(120_000).initiator.chat)
    }

    @Test
    fun bothPhonesEndingTheSessionAtTheSameMomentStillCloseCleanly() {
        val (alice, bob) = orderedPair()
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        val gate = java.util.concurrent.CountDownLatch(1)
        val t1 = Thread { gate.await(); live.sessionA.endChat() }
        val t2 = Thread { gate.await(); live.sessionB.endChat() }
        t1.start()
        t2.start()
        gate.countDown()
        val pair = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.responder.outcome)
    }

    @Test
    fun textArrivesInAboutASecondAndTheSenderLearnsItWasDelivered() {
        val (alice, bob) = orderedPair()
        // The sessions' clock is frozen, so delivery cannot be waiting for a ping, a batch window or any other timer: the text
        // is pushed the moment it is queued. (A wall-clock bound on "about a second" cannot hold on a loaded machine.)
        val live = rig.open(alice, bob, monotonicMs = { 0L })
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        val startedNs = System.nanoTime()
        val sent = alice.engine.sendText(bob.nodeId, "are you there?")
        waitUntil(120_000) { alice.engine.threads.get(sent.id)!!.delivered }
        val tookMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue("queue to delivered took $tookMs ms", tookMs < 30_000)
        assertEquals("are you there?", bob.engine.threads.all().single().text)
        live.sessionA.endChat()
        live.join()
    }

    @Test
    fun theCompletionListenerSeesAMessageWhileTheSessionIsStillRunning() {
        val (alice, bob) = orderedPair()
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        bob.node.addCompletionListener { peer, completion ->
            if (completion is StoreForwardCompletion.V2Message && peer == alice.nodeId) seen += completion.message.text
        }
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        alice.engine.sendText(bob.nodeId, "live")
        waitUntil { seen.isNotEmpty() }
        assertTrue("the session is still open", live.sessionB.isChat)
        assertEquals(listOf("live"), seen.toList())
        live.sessionA.endChat()
        live.join()
    }

    @Test
    fun readReceiptsLeaveAtOnceInAChatSessionAndNotBefore() {
        val limits = MessagingV2Limits(readReceipts = true)
        val a = rig.Phone(v2Limits = limits, chat = true)
        val b = rig.Phone(v2Limits = limits, chat = true)
        rig.contacts(a, b)
        val live = rig.open(a, b)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        val sent = a.engine.sendText(b.nodeId, "read me")
        waitUntil { b.engine.threads.count() == 1 }
        assertFalse(a.engine.threads.get(sent.id)!!.read)
        b.engine.markRead(b.engine.threads.all().single().id)
        waitUntil { a.engine.threads.get(sent.id)!!.read }
        assertNull("nothing waits in the outbox", b.engine.receiptOutbox.pending(null).firstOrNull())
        live.sessionA.endChat()
        live.join()
    }

    @Test
    fun withoutASessionAReadReceiptWaitsForTheBatch() {
        val limits = MessagingV2Limits(readReceipts = true)
        val a = rig.Phone(v2Limits = limits)
        val b = rig.Phone(v2Limits = limits)
        rig.contacts(a, b)
        val sent = a.engine.sendText(b.nodeId, "read me")
        rig.sync(a, b)
        b.engine.markRead(b.engine.threads.all().single().id)
        assertFalse(a.engine.threads.get(sent.id)!!.read)
        assertNotNull("queued for the next exchange", b.engine.receiptOutbox.pending(null).firstOrNull())
        rig.sync(a, b)
        assertTrue(a.engine.threads.get(sent.id)!!.read)
    }
}
