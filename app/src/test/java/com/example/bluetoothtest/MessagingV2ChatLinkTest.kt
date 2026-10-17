package com.example.bluetoothtest

import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1d, the link state machine as the thread link needs it (DECISIONS Design M6): the responder cooldown after HELLO, cards
 * inside an exchange, the tag table refreshed mid-session, a key change that sends nothing, and hostile peers.
 */
class MessagingV2ChatLinkTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()
    private val random = SecureRandom()

    @After
    fun cleanUp() = rig.cleanUp()

    private fun waitUntil(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    // ---- the responder cooldown is applied after the HELLO (M6) ----------------------------------------------------------------

    /** A complete exchange driven by the test as [peer]; returns how the real responder session ended. */
    private fun exchange(responder: SfRig.Phone, peer: SfRig.Phone, flags: Int): StoreForwardSyncOutcome {
        val scripted = rig.scripted(responder, peer.nodeId)
        runCatching {
            scripted.send(rig.hello(2, flags))
            scripted.send(StoreForwardMessage.Summary(emptyList(), true))
            scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        }
        return scripted.finish().outcome
    }

    private val chatFlags = StoreForwardHelloFlags.FILES or StoreForwardHelloFlags.CHAT

    @Test
    fun aContactThatOffersAChatSessionGetsTheRelaxedCooldownAfterItsHello() {
        val phone = rig.Phone(keepCooldown = true)
        val contact = rig.Phone()
        rig.contacts(phone, contact)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, exchange(phone, contact, chatFlags))
        assertEquals("the same instant: inside 5 s", StoreForwardSyncOutcome.RATE_LIMITED, exchange(phone, contact, chatFlags))
        rig.clockMs.addAndGet(4_999)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, exchange(phone, contact, chatFlags))
        rig.clockMs.addAndGet(1)
        assertEquals("5 s later it is allowed again", StoreForwardSyncOutcome.COMPLETE, exchange(phone, contact, chatFlags))
    }

    @Test
    fun aContactWithoutTheChatFlagKeepsTheSixtySecondCooldown() {
        val phone = rig.Phone(keepCooldown = true)
        val contact = rig.Phone()
        rig.contacts(phone, contact)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, exchange(phone, contact, StoreForwardHelloFlags.FILES))
        rig.clockMs.addAndGet(6_000)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, exchange(phone, contact, StoreForwardHelloFlags.FILES))
        rig.clockMs.addAndGet(54_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, exchange(phone, contact, StoreForwardHelloFlags.FILES))
    }

    @Test
    fun aStrangerClaimingTheChatFlagGetsNoRelaxation() {
        val phone = rig.Phone(keepCooldown = true)
        val stranger = rig.Phone()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, exchange(phone, stranger, chatFlags))
        rig.clockMs.addAndGet(6_000)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, exchange(phone, stranger, chatFlags))
    }

    @Test
    fun theRelaxedCapIsPerHourAndBoundsAContactThatReconnectsInALoop() {
        val phone = rig.Phone(v2Limits = MessagingV2Limits(contactMaxExchangesPerHour = 3), keepCooldown = true)
        val contact = rig.Phone()
        rig.contacts(phone, contact)
        // Start 10 s into an hour so that nothing below crosses an hour boundary.
        rig.clockMs.set((rig.clock() / 3_600_000 + 1) * 3_600_000 + 10_000)
        repeat(3) {
            assertEquals(StoreForwardSyncOutcome.COMPLETE, exchange(phone, contact, chatFlags))
            rig.clockMs.addAndGet(6_000)
        }
        assertEquals("the fourth in the hour", StoreForwardSyncOutcome.RATE_LIMITED, exchange(phone, contact, chatFlags))
        rig.clockMs.addAndGet(3_600_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, exchange(phone, contact, chatFlags))
    }

    @Test
    fun aRefusedHelloLeavesNothingBehindAndIsReportedAsRateLimited() {
        val phone = rig.Phone(keepCooldown = true)
        val contact = rig.Phone()
        rig.contacts(phone, contact)
        contact.engine.sendText(phone.nodeId, "queued")
        exchange(phone, contact, StoreForwardHelloFlags.FILES)
        val scripted = rig.scripted(phone, contact.nodeId)
        runCatching { scripted.send(rig.hello(2, StoreForwardHelloFlags.FILES)) }
        val seen = scripted.receiveUntil { it is StoreForwardMessage.Done }
        assertTrue(seen.any { it is StoreForwardMessage.Done && it.reason == StoreForwardDoneReason.RATE_LIMITED })
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, result.outcome)
        assertEquals(0, result.completed.size)
    }

    // ---- a link lost before anything moved does not impose the cooldown (lane3-asus-a15s-m31-20261013) ------------------------------

    /** The real responder admits the exchange (it answers the HELLO), then the radio link dies before any chunk moved. */
    private fun lostLinkAfterHello(responder: SfRig.Phone, peer: SfRig.Phone): StoreForwardSyncResult {
        val scripted = rig.scripted(responder, peer.nodeId)
        scripted.send(rig.hello(2, StoreForwardHelloFlags.FILES))
        assertTrue("the responder answered, so it admitted the exchange", scripted.receive() is StoreForwardMessage.Hello)
        scripted.drop()
        return scripted.finish()
    }

    @Test
    fun anAdmittedExchangeCutByTheLinkBeforeAnythingMovedDoesNotMakeTheRetryWaitSixtySeconds() {
        val phone = rig.Phone(keepCooldown = true)
        val peer = rig.Phone()
        rig.contacts(phone, peer)
        val lost = lostLinkAfterHello(phone, peer)
        assertEquals(StoreForwardSyncOutcome.INTERRUPTED, lost.outcome)
        assertEquals(0, lost.chunksSent + lost.chunksReceived)
        rig.clockMs.addAndGet(1_000)
        assertEquals("the retry 1 s later is not rate limited", StoreForwardSyncOutcome.COMPLETE, exchange(phone, peer, StoreForwardHelloFlags.FILES))
        // A finished exchange still holds the spacing.
        rig.clockMs.addAndGet(1_000)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, exchange(phone, peer, StoreForwardHelloFlags.FILES))
    }

    @Test
    fun theHourlyCapStillCountsExchangesThatWereCutBeforeAnythingMoved() {
        val phone = rig.Phone(limits = StoreForwardLimits(maxExchangesPerHour = 3), keepCooldown = true)
        val peer = rig.Phone()
        rig.contacts(phone, peer)
        rig.clockMs.set((rig.clock() / 3_600_000 + 1) * 3_600_000 + 10_000)
        repeat(3) {
            assertEquals(StoreForwardSyncOutcome.INTERRUPTED, lostLinkAfterHello(phone, peer).outcome)
            rig.clockMs.addAndGet(1_000)
        }
        assertEquals(
            "a neighbour that connects and vanishes is still bounded by the hourly cap",
            StoreForwardSyncOutcome.RATE_LIMITED,
            exchange(phone, peer, StoreForwardHelloFlags.FILES),
        )
    }

    @Test
    fun aRefusedExchangeDoesNotRefundAndTheLedgerRefundIsIdempotent() {
        val phone = rig.Phone(keepCooldown = true)
        val peer = rig.Phone()
        val key = NeighborLedger.neighborKey(peer.nodeId)
        assertTrue(phone.node.ledger.admitExchange(key, 60_000, 10))
        assertFalse("inside the cooldown", phone.node.ledger.admitExchange(key, 60_000, 10))
        phone.node.ledger.refundCooldown(key)
        phone.node.ledger.refundCooldown(key)
        assertTrue("the spacing was given back once", phone.node.ledger.admitExchange(key, 60_000, 10))
        assertFalse("and the admission after it holds the spacing again", phone.node.ledger.admitExchange(key, 60_000, 10))
    }

    // ---- cards inside an exchange ------------------------------------------------------------------------------------------------

    @Test
    fun theNumberOfCardsInOneExchangeIsBounded() {
        val phone = rig.Phone()
        val contact = rig.Phone()
        rig.contacts(phone, contact)
        val scripted = rig.scripted(phone, contact.nodeId)
        runCatching {
            scripted.send(rig.hello(2))
            repeat(6) { scripted.send(StoreForwardMessage.Card(contact.node.ownCard)) }
        }
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.FAILED, result.outcome)
        assertEquals("Too many CARD records", result.detail)
    }

    @Test
    fun aCardAcceptedMidSessionLetsTheNextSummaryBeAdmitted() {
        val alice = rig.Phone(chat = true)
        val bob = rig.Phone(chat = true)
        alice.knows(bob)
        // Bob only holds Alice's card as pending: her bundles are not for him yet.
        bob.node.contacts.offerFromLinkV2(alice.node.ownCard, alice.nodeId, null)
        alice.engine.sendText(bob.nodeId, "first, before the card was accepted")
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        Thread.sleep(200)
        assertEquals(0, bob.engine.threads.count())
        // The user accepts Alice's card in the Contacts tab while the session runs.
        bob.node.contacts.accept(alice.nodeId, "Alice")
        alice.engine.sendText(bob.nodeId, "second, after")
        waitUntil { bob.engine.threads.count() == 1 }
        assertEquals("second, after", bob.engine.threads.all().single().text)
        live.sessionA.endChat()
        live.join()
        // The first text was not repeated inside that session, so it waits for the next exchange.
        val next = rig.open(alice, bob)
        waitUntil { bob.engine.threads.count() == 2 }
        next.sessionA.endChat()
        next.join()
        assertEquals(setOf("first, before the card was accepted", "second, after"), bob.engine.threads.all().map { it.text }.toSet())
    }

    @Test
    fun aDoneIdleOutsideAChatSessionIsAViolation() {
        val phone = rig.Phone()
        val peer = rig.Phone()
        rig.contacts(phone, peer)
        val scripted = rig.scripted(phone, peer.nodeId)
        runCatching {
            scripted.send(rig.hello(2, StoreForwardHelloFlags.FILES))
            scripted.send(StoreForwardMessage.Summary(emptyList(), true))
            scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.IDLE))
        }
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.FAILED, result.outcome)
        assertTrue(result.detail!!.contains("DONE(IDLE) outside a chat session"))
    }

    @Test
    fun theSummaryEntryLimitCountsEveryBundleOfferedInTheThirtyMinuteWindowAcrossRepeatedSummaries() {
        val phone = rig.Phone(chat = true)
        val peer = rig.Phone(chat = true)
        rig.contacts(phone, peer)
        val scripted = rig.scripted(phone, peer.nodeId)
        runCatching {
            scripted.send(rig.hello(2, chatFlags))
            scripted.send(StoreForwardMessage.Summary(emptyList(), true))
            // Four SUMMARY messages of 256 entries are exactly the 1,024 allowed; a fifth entry more is too many.
            repeat(5) {
                val entries = List(StoreForwardWire.MAX_SUMMARY_ENTRIES) {
                    SummaryEntry(ByteArray(32).also(random::nextBytes), ByteArray(8), 0, StoreForwardFormat.hourOf(rig.clock()) + 5, 1, 1)
                }
                scripted.send(StoreForwardMessage.Summary(entries, true))
            }
        }
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.FAILED, result.outcome)
        assertEquals("Too many SUMMARY entries", result.detail)
    }

    // ---- a key change sends nothing ---------------------------------------------------------------------------------------------------

    @Test
    fun aContactWhoseKeysChangedIsNotWrittenToUntilTheUserComparesAgain() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val waiting = alice.engine.sendText(bob.nodeId, "written before the change")
        // Bob reinstalls the app but keeps his identity: a new mailbox key, a newer signed card.
        rig.clockMs.addAndGet(10_000)
        val bobAgain = rig.Phone(identityKeys = bob.keys, mailboxKey = SoftwareMailboxKey.generate())
        bobAgain.knows(alice)
        val first = rig.sync(alice, bobAgain)
        assertEquals(CardOffer.KEY_CHANGED, first.initiator.cardOffer)
        assertEquals("nothing reached the contact with the new keys", 0, bobAgain.engine.threads.count())
        assertFalse(alice.engine.threads.get(waiting.id)!!.delivered)
        val contact = alice.node.contacts.get(bob.nodeId)!!
        assertTrue(contact.keysChanged)
        assertEquals(ConversationLinkState.KEYS_CHANGED, alice.engine.linkState(bob.nodeId))
        assertTrue("new text is refused", runCatching { alice.engine.sendText(bob.nodeId, "more") }.exceptionOrNull() is MessagingV2KeysChangedException)
        assertTrue(
            "files are refused too",
            runCatching {
                alice.engine.sendFile(bob.nodeId, { java.io.ByteArrayInputStream(ByteArray(10)) }, 10)
            }.exceptionOrNull() is MessagingV2KeysChangedException,
        )
        assertTrue("v1 sends are refused", runCatching {
            alice.node.send(bob.nodeId, BundleMessage(MessageKind.TEXT, rig.clock(), ByteArray(16), null, "text/plain", ByteArray(1)))
        }.exceptionOrNull() is MessagingV2KeysChangedException)
        assertTrue("nothing is offered", alice.node.offers(bob.nodeId, false, true).isEmpty())
        assertNotEquals(contact.card.safetyNumber(), contact.pendingKeyChange!!.safetyNumber())
        // A second exchange still sends nothing.
        assertEquals(0, rig.sync(alice, bobAgain).responder.v2Messages.size)
        // The user compared the new safety number and accepts it: the waiting text is sealed again under the same message ID.
        val adopted = alice.engine.acceptKeyChange(bob.nodeId)
        assertTrue(adopted.adopted)
        assertEquals(1, adopted.resealedTexts)
        assertEquals(0, adopted.notSent)
        assertEquals(ConversationLinkState.READY, alice.engine.linkState(bob.nodeId))
        val second = rig.sync(alice, bobAgain)
        val arrived = second.responder.v2Messages.single()
        assertEquals("written before the change", arrived.text)
        assertEquals(waiting.messageIdHex, arrived.messageIdHex)
        assertTrue(alice.engine.threads.get(waiting.id)!!.delivered)
        alice.engine.sendText(bob.nodeId, "writing again works")
    }

    @Test
    fun filesAndEverythingElseThatWaitedBecomeNotSentWhenTheNewKeysAreAccepted() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val inline = alice.engine.sendFile(bob.nodeId, { java.io.ByteArrayInputStream(rig.bytes(20_000, 3)) }, 20_000, Presentation.PICTURE, "p.jpg", "image/jpeg")
        val big = alice.engine.sendFile(bob.nodeId, { java.io.ByteArrayInputStream(rig.bytes(1_100_000, 4)) }, 1_100_000, Presentation.FILE, "big.bin")
        val text = alice.engine.sendText(bob.nodeId, "and a text")
        rig.clockMs.addAndGet(10_000)
        val bobAgain = rig.Phone(identityKeys = bob.keys, mailboxKey = SoftwareMailboxKey.generate())
        bobAgain.knows(alice)
        rig.sync(alice, bobAgain)
        val adopted = alice.engine.acceptKeyChange(bob.nodeId)
        assertEquals(1, adopted.resealedTexts)
        assertEquals(2, adopted.notSent)
        assertTrue(alice.engine.threads.get(inline.id)!!.notSent)
        assertTrue(alice.engine.threads.get(big.id)!!.notSent)
        assertFalse(alice.engine.threads.get(text.id)!!.notSent)
        // The sealed segments of the big file are gone with its key, and only the re-sealed text remains queued for Bob.
        val queued = alice.node.store.all().filter { it.role == BundleRole.OWN }
        assertEquals(1, queued.size)
        assertEquals(1, rig.sync(alice, bobAgain).responder.v2Messages.size)
    }

    @Test
    fun aWantForSomethingOfferedBeforeTheNewerCardArrivedIsRefused() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.engine.sendText(bob.nodeId, "offered, then the keys change")
        val bundle = alice.node.store.all().single()
        rig.clockMs.addAndGet(10_000)
        val bobAgain = rig.Phone(identityKeys = bob.keys, mailboxKey = SoftwareMailboxKey.generate())
        val scripted = rig.scripted(alice, bob.nodeId)
        runCatching {
            scripted.send(rig.hello(2))
            scripted.send(StoreForwardMessage.Card(bobAgain.node.ownCard))
            scripted.send(StoreForwardMessage.Summary(emptyList(), true))
            scripted.send(StoreForwardMessage.Want(bundle.header.bundleId, true, bundle.chunkCount, ChunkBitmap.full(bundle.chunkCount)))
        }
        val seen = scripted.receiveUntil { it is StoreForwardMessage.Reply }
        val reply = seen.last() as StoreForwardMessage.Reply
        assertEquals(StoreForwardRefusal.UNKNOWN, reply.reason)
        assertFalse("no header and no chunk was served", seen.any { it is StoreForwardMessage.Header || it is StoreForwardMessage.Chunk })
        runCatching { scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL)) }
        scripted.finish()
        assertTrue(alice.node.contacts.get(bob.nodeId)!!.keysChanged)
    }

    @Test
    fun aMessageQueuedAfterTheSessionWasToldToEndWaitsForTheNextExchange() {
        val alice = rig.Phone(chat = true)
        val bob = rig.Phone(chat = true)
        rig.contacts(alice, bob)
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        live.sessionA.endChat()
        alice.engine.sendText(bob.nodeId, "too late for this session")
        val pair = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, pair.initiator.outcome)
        assertEquals(0, bob.engine.threads.count())
        val next = rig.open(alice, bob)
        waitUntil { bob.engine.threads.count() == 1 }
        next.sessionA.endChat()
        next.join()
    }
}
