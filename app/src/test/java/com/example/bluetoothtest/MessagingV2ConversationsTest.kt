package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1d: the data behind the Contacts rows as conversation rows, and the move from v1 Messages data (DECISIONS M1, Q-M1 B). */
class MessagingV2ConversationsTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()

    @After
    fun cleanUp() = rig.cleanUp()

    private fun v1Text(from: SfRig.Phone, to: SfRig.Phone, text: String) = from.node.send(
        to.nodeId,
        BundleMessage(MessageKind.TEXT, rig.clock(), ByteArray(16), null, "text/plain", text.toByteArray()),
    )

    private fun v1File(from: SfRig.Phone, to: SfRig.Phone, kind: MessageKind, mime: String, size: Int) = from.node.send(
        to.nodeId,
        BundleMessage(kind, rig.clock(), ByteArray(16), null, mime, rig.bytes(size, size)),
    )

    private fun queuedOwn(phone: SfRig.Phone) = phone.node.store.all().filter { it.role == BundleRole.OWN }

    // ---- migration ---------------------------------------------------------------------------------------------------------

    @Test
    fun aQueuedV1TextBecomesAThreadMessageOnceAndTheV1BundleIsWithdrawn() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val written = rig.clock()
        v1Text(alice, bob, "written with the old app")
        rig.clockMs.addAndGet(3_000_000)
        val report = MessagingV2Migration.run(alice.node)
        assertEquals(1, report.resealed)
        assertEquals(0, report.skipped)
        val thread = alice.engine.threads.all().single()
        assertEquals("written with the old app", thread.text)
        assertEquals("the thread shows the time it was written", written, thread.createdMs)
        assertEquals(StoreForwardFormat.BUNDLE_VERSION_V3, queuedOwn(alice).single().header.version)
        assertEquals(0, alice.node.archive.count(MessageDirection.OUT))
        // A second run changes nothing.
        val again = MessagingV2Migration.run(alice.node)
        assertEquals(0, again.resealed)
        assertEquals(1, alice.engine.threads.count())
        // And the receiver gets it once, as a v2 message.
        val received = rig.sync(alice, bob).responder
        assertEquals(listOf("written with the old app"), received.v2Messages.map { it.text })
        assertEquals(0, received.opened.size)
    }

    @Test
    fun photosAndVoiceNotesMoveAsInlineAttachmentsAndWhatDoesNotFitStaysV1() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        v1File(alice, bob, MessageKind.PHOTO, "image/jpeg", 30_000)
        v1File(alice, bob, MessageKind.VOICE, "audio/mp4", 12_000)
        v1File(alice, bob, MessageKind.VIDEO, "video/mp4", 1_500_000)
        val report = MessagingV2Migration.run(alice.node)
        assertEquals(2, report.resealed)
        assertEquals("the video is over the inline limit and stays a v1 bundle", 1, report.skipped)
        val kinds = alice.engine.threads.all().map { it.attachment!!.presentation }.toSet()
        assertEquals(setOf(Presentation.PICTURE, Presentation.VOICE), kinds)
        assertEquals(1, queuedOwn(alice).count { it.header.version == StoreForwardFormat.BUNDLE_VERSION })
        assertEquals(1, alice.node.archive.count(MessageDirection.OUT))
        // The migration is not marked finished while something was left, and a rerun does not duplicate what moved.
        assertEquals(0, MessagingV2Migration.run(alice.node).resealed)
        assertEquals(2, alice.engine.threads.count())
    }

    @Test
    fun aMessageForSomeoneNoLongerAcceptedWaitsAndMovesWhenTheyAre() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        v1Text(alice, bob, "for Bob")
        alice.node.contacts.accept(bob.nodeId, "Bob")
        alice.node.contacts.remove(bob.nodeId)
        assertEquals(1, MessagingV2Migration.run(alice.node).skipped)
        assertEquals(0, alice.engine.threads.count())
        alice.node.contacts.addAccepted(bob.node.ownCard, "Bob")
        assertEquals(1, MessagingV2Migration.run(alice.node).resealed)
    }

    @Test
    fun receiptsAndDeliveredBundlesAreLeftAlone() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.node.send(bob.nodeId, BundleMessage.receipt(ByteArray(32), 1, rig.clock()))
        val delivered = v1Text(alice, bob, "already handed over")
        alice.node.store.update(delivered.idHex, BundleState.HANDED)
        val report = MessagingV2Migration.run(alice.node)
        assertEquals(0, report.resealed)
        assertEquals(0, report.skipped)
        assertEquals(2, queuedOwn(alice).size)
        assertEquals(0, alice.engine.threads.count())
    }

    @Test
    fun aRunThatCrashedAfterRecordingTheMoveOnlyFinishesTheRemoval() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val old = v1Text(alice, bob, "moved before the crash")
        // The map entry was written, the v1 bundle not yet removed.
        val file = java.io.File(alice.engine.directory, MessagingV2Migration.FILE)
        val ledger = readSealedProperties(file, alice.engine.cipher)
        ledger.setProperty("bundle.${old.idHex}", "00".repeat(16))
        writeSealedProperties(file, alice.engine.cipher, ledger)
        val report = MessagingV2Migration.run(alice.node)
        assertEquals(0, report.resealed)
        assertEquals(1, report.finishedRemovals)
        assertEquals("nothing is sealed a second time", 0, alice.engine.threads.count())
        assertTrue(queuedOwn(alice).isEmpty())
    }

    // ---- the conversation rows ----------------------------------------------------------------------------------------------

    @Test
    fun oneThreadMergesV1AndV2MessagesByTheirDisplayedTime() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        v1Text(bob, alice, "old app, first")
        rig.sync(bob, alice)
        rig.clockMs.addAndGet(60_000)
        alice.engine.sendText(bob.nodeId, "new app, second")
        rig.clockMs.addAndGet(60_000)
        bob.engine.sendText(alice.nodeId, "new app, third")
        rig.sync(alice, bob)
        val entries = ConversationIndex(alice.node).entries(bob.nodeId)
        assertEquals(3, entries.size)
        assertTrue(entries[0] is ConversationEntry.Legacy)
        assertEquals(listOf("old app, first"), entries.filterIsInstance<ConversationEntry.Legacy>().map { it.summary.preview })
        assertEquals(listOf("new app, second", "new app, third"), entries.filterIsInstance<ConversationEntry.V2>().map { it.message.text })
        assertEquals(entries.map { it.sortMs }.sorted(), entries.map { it.sortMs })
    }

    @Test
    fun contactsRowsCarryPreviewUnreadWaitingAndSortActiveFirstThenByName() {
        val me = rig.Phone()
        val busy = rig.Phone()
        val older = rig.Phone()
        val quiet = rig.Phone()
        val unnamed = rig.Phone()
        listOf(busy to "Zed", older to "Mia", quiet to "Abe").forEach { (p, name) -> me.node.contacts.addAccepted(p.node.ownCard, name); p.knows(me) }
        me.node.contacts.addAccepted(unnamed.node.ownCard, "")
        unnamed.knows(me)
        older.engine.sendText(me.nodeId, "older hello")
        rig.sync(older, me)
        rig.clockMs.addAndGet(120_000)
        busy.engine.sendText(me.nodeId, "latest news")
        rig.sync(busy, me)
        me.engine.sendText(quiet.nodeId, "waiting for Abe")
        val index = ConversationIndex(me.node)
        val rows = index.summaries()
        // Activity first, newest first; Abe's own message and Zed's arrived at the same instant, so the name decides.
        assertEquals(listOf("Abe", "Zed", "Mia"), rows.take(3).map { it.name })
        val byName = rows.associateBy { it.name }
        assertEquals("latest news", byName["Zed"]!!.previewText)
        assertEquals(1, byName["Zed"]!!.unread)
        assertTrue(byName["Zed"]!!.lastIncoming)
        assertEquals(1, byName["Abe"]!!.waiting)
        assertEquals(OwnMessageState.WAITING, byName["Abe"]!!.lastOwnState)
        assertFalse(byName["Abe"]!!.lastIncoming)
        // Without activity: after every active row, A to Z (the unnamed contact shows its handle).
        val inactive = rows.filter { !it.hasActivity }
        assertEquals(1, inactive.size)
        assertEquals(rows.last(), inactive.single())
        // The preview can be switched off.
        assertEquals("", index.summaries(showPreview = false).first { it.name == "Zed" }.previewText)
        // Reading clears unread.
        me.engine.threads.all().filter { it.direction == MessageDirection.IN }.forEach { me.engine.markRead(it.id) }
        assertEquals(0, index.unread(busy.nodeId))
    }

    @Test
    fun theLinkStateNamesWhatBlocksSending() {
        val me = rig.Phone()
        val peer = rig.Phone()
        assertEquals(ConversationLinkState.NOT_A_CONTACT, me.engine.linkState(peer.nodeId))
        me.node.contacts.offerFromLinkV2(peer.node.ownCard, peer.nodeId, null)
        assertEquals(ConversationLinkState.CARD_PENDING, me.engine.linkState(peer.nodeId))
        me.node.contacts.accept(peer.nodeId, "Peer")
        assertEquals(ConversationLinkState.READY, me.engine.linkState(peer.nodeId))
        // "Their app is older": the peer answered a v2 HELLO with DONE(error); an hour later it is tried again.
        me.engine.markLegacy(peer.nodeId)
        assertEquals(ConversationLinkState.PEER_APP_OLDER, me.engine.linkState(peer.nodeId))
        rig.clockMs.addAndGet(3_600_001)
        assertEquals(ConversationLinkState.READY, me.engine.linkState(peer.nodeId))
        rig.clockMs.addAndGet(1_000)
        me.node.contacts.offerFromLinkV2(ContactCard.create(peer.identity, SoftwareMailboxKey.generate(), rig.clock()), peer.nodeId, null)
        assertEquals(ConversationLinkState.KEYS_CHANGED, me.engine.linkState(peer.nodeId))
    }

    @Test
    fun theHighestAppliedFlagWinsAndALateReceiptStillCountsAfterNotSent() {
        fun message(delivered: Long = 0, downloaded: Long = 0, read: Long = 0, notSent: Boolean = false) = ThreadMessage(
            MessageDirection.OUT, "00".repeat(16), "11".repeat(16), V2Kind.TEXT, 1, 1, 0, ByteArray(1), null, 0, null, mutableListOf(),
            delivered, downloaded, read, notSent,
        )
        assertEquals(OwnMessageState.WAITING, ConversationRules.ownState(message(), false))
        assertEquals(OwnMessageState.SENDING, ConversationRules.ownState(message(), true))
        assertEquals(OwnMessageState.NOT_SENT, ConversationRules.ownState(message(notSent = true), true))
        assertEquals(OwnMessageState.DELIVERED, ConversationRules.ownState(message(delivered = 5), false))
        assertEquals("a late delivered receipt after not sent", OwnMessageState.DELIVERED, ConversationRules.ownState(message(delivered = 5, notSent = true), false))
        assertEquals(OwnMessageState.DOWNLOADED, ConversationRules.ownState(message(delivered = 5, downloaded = 6), false))
        assertEquals(OwnMessageState.READ, ConversationRules.ownState(message(delivered = 5, downloaded = 6, read = 7), false))
        assertNotNull(OwnMessageState.valueOf("READ"))
    }
}
