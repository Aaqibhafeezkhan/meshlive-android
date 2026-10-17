package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1d, one contact system (DECISIONS Design M1): cards from verified identities are accepted without a second comparison,
 * a changed mailbox key is held back and sends nothing, a replayed older card cannot roll a key back, and the verified-contact
 * store stays the source of truth.
 */
class MessagingV2ContactsTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()
    private val aes = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        rig.cleanUp()
        dirs.forEach { it.deleteRecursively() }
    }

    /** A verified-contact store stand-in: identity key (hex) to the label the user gave. */
    private class FakeDirectory(var readableNow: Boolean = true) : VerifiedIdentityDirectory {
        val verified = HashMap<String, String>()
        override fun verifiedLabel(identityKey: ByteArray): String? = verified[identityKey.sfHex()]
        override fun isVerifiedNode(node: NodeId): Boolean = verified.keys.any { NodeId.fromPublicKey(hexToBytes(it)) == node }
        override val readable: Boolean get() = readableNow
        fun verify(identity: SigningIdentity, label: String = "") {
            verified[identity.publicKeyEncoded.sfHex()] = label
        }
    }

    private class Person(val mailbox: SoftwareMailboxKey = SoftwareMailboxKey.generate()) {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        val nodeId get() = identity.nodeId
        fun card(ms: Long, with: SoftwareMailboxKey = mailbox) = ContactCard.create(identity, with, ms)
    }

    private fun book(clock: () -> Long = { 1_000L }, file: File? = null): ContactBook {
        val target = file ?: File(Files.createTempDirectory("contactbook").toFile().also(dirs::add), "contacts.properties")
        return ContactBook(target, clock, 20, aes)
    }

    // ---- card handling -----------------------------------------------------------------------------------------------------

    @Test
    fun aCardFromAVerifiedIdentityIsAcceptedWithoutASecondComparison() {
        val directory = FakeDirectory()
        val bob = Person().also { directory.verify(it.identity, "Bob") }
        val contacts = book()
        assertEquals(CardOffer.AUTO_ACCEPTED, contacts.offerFromLinkV2(bob.card(10), bob.nodeId, directory))
        val contact = contacts.get(bob.nodeId)!!
        assertEquals(ContactState.ACCEPTED, contact.state)
        assertEquals(ContactSource.VERIFIED, contact.source)
        assertEquals("Bob", contact.name)
        assertTrue(contacts.isKnownSender(bob.nodeId, bob.mailbox.compressedPublic))
    }

    @Test
    fun aCardFromAnUnverifiedIdentityStaysPendingBehindTheSafetyNumber() {
        val directory = FakeDirectory()
        val carol = Person()
        val contacts = book()
        assertEquals(CardOffer.ADDED_PENDING, contacts.offerFromLinkV2(carol.card(10), carol.nodeId, directory))
        assertEquals(ContactState.PENDING, contacts.get(carol.nodeId)!!.state)
        assertFalse(contacts.isKnownSender(carol.nodeId, carol.mailbox.compressedPublic))
        // No directory at all behaves the same.
        val other = Person()
        assertEquals(CardOffer.ADDED_PENDING, book().offerFromLinkV2(other.card(10), other.nodeId, null))
    }

    @Test
    fun aCardMustComeFromItsOwnLinkPeer() {
        val directory = FakeDirectory()
        val bob = Person().also { directory.verify(it.identity) }
        val mallory = Person()
        assertEquals(CardOffer.IDENTITY_MISMATCH, book().offerFromLinkV2(bob.card(10), mallory.nodeId, directory))
    }

    @Test
    fun aPendingCardBecomesAcceptedWhenItsIdentityIsVerifiedLater() {
        val directory = FakeDirectory()
        val dave = Person()
        val contacts = book()
        contacts.offerFromLinkV2(dave.card(10), dave.nodeId, directory)
        assertEquals(ContactState.PENDING, contacts.get(dave.nodeId)!!.state)
        directory.verify(dave.identity, "Dave")
        // The same card again, now from a verified identity.
        assertEquals(CardOffer.AUTO_ACCEPTED, contacts.offerFromLinkV2(dave.card(10), dave.nodeId, directory))
        assertEquals(ContactSource.VERIFIED, contacts.get(dave.nodeId)!!.source)
    }

    @Test
    fun aNewerCardWithTheSameKeysOnlyRefreshesTheFreshnessAnchor() {
        val bob = Person()
        val contacts = book()
        contacts.addAccepted(bob.card(100), "Bob")
        assertEquals(CardOffer.ALREADY_KNOWN, contacts.offerFromLinkV2(bob.card(500), bob.nodeId, null))
        assertEquals(500L, contacts.get(bob.nodeId)!!.card.createdMs)
        // An older copy of the same card does not move it back.
        assertEquals(CardOffer.ALREADY_KNOWN, contacts.offerFromLinkV2(bob.card(200), bob.nodeId, null))
        assertEquals(500L, contacts.get(bob.nodeId)!!.card.createdMs)
        assertFalse(contacts.get(bob.nodeId)!!.keysChanged)
    }

    @Test
    fun aNewerCardWithOtherKeysIsHeldBackAndTheOldKeyStaysUntilTheUserCompares() {
        val directory = FakeDirectory()
        val bob = Person().also { directory.verify(it.identity, "Bob") }
        val contacts = book()
        contacts.offerFromLinkV2(bob.card(100), bob.nodeId, directory)
        val newMailbox = SoftwareMailboxKey.generate()
        val rotated = bob.card(200, newMailbox)
        assertEquals(CardOffer.KEY_CHANGED, contacts.offerFromLinkV2(rotated, bob.nodeId, directory))
        val contact = contacts.get(bob.nodeId)!!
        assertTrue(contact.keysChanged)
        assertTrue("the old key is still the stored card", contact.card.mailboxPublic.contentEquals(bob.mailbox.compressedPublic))
        assertTrue(contacts.isKnownSender(bob.nodeId, bob.mailbox.compressedPublic))
        assertFalse("the new key is not trusted yet", contacts.isKnownSender(bob.nodeId, newMailbox.compressedPublic))
        // The same held card again is idempotent; the safety numbers differ so the user can compare.
        assertEquals(CardOffer.KEY_CHANGED, contacts.offerFromLinkV2(rotated, bob.nodeId, directory))
        assertTrue(contact.card.safetyNumber() != contacts.pendingKeyChange(bob.nodeId)!!.safetyNumber())
        // The user compared: the new key replaces the old one, the name and the source stay.
        val replaced = contacts.acceptKeyChange(bob.nodeId)
        assertNotNull(replaced)
        val after = contacts.get(bob.nodeId)!!
        assertFalse(after.keysChanged)
        assertEquals("Bob", after.name)
        assertEquals(ContactSource.VERIFIED, after.source)
        assertTrue(contacts.isKnownSender(bob.nodeId, newMailbox.compressedPublic))
        assertFalse(contacts.isKnownSender(bob.nodeId, bob.mailbox.compressedPublic))
    }

    @Test
    fun aReplayedOlderSignedCardCannotRollAKeyBack() {
        val bob = Person()
        val contacts = book()
        val oldCard = bob.card(100)
        contacts.addAccepted(oldCard, "Bob")
        val rotatedMailbox = SoftwareMailboxKey.generate()
        assertEquals(CardOffer.KEY_CHANGED, contacts.offerFromLinkV2(bob.card(300, rotatedMailbox), bob.nodeId, null))
        assertTrue(contacts.acceptKeyChange(bob.nodeId) != null)
        // An attacker who captured the old signed card replays it: the stored card is newer, so it is ignored.
        assertEquals(CardOffer.KEY_CHANGED_STALE, contacts.offerFromLinkV2(oldCard, bob.nodeId, null))
        assertFalse(contacts.get(bob.nodeId)!!.keysChanged)
        assertTrue(contacts.isKnownSender(bob.nodeId, rotatedMailbox.compressedPublic))
        // A card with the very same creation time but other keys is also not newer.
        assertEquals(CardOffer.KEY_CHANGED_STALE, contacts.offerFromLinkV2(bob.card(300, SoftwareMailboxKey.generate()), bob.nodeId, null))
    }

    @Test
    fun aHeldKeyChangeIsReplacedByAStillNewerCardAndCanBeDismissed() {
        val bob = Person()
        val contacts = book()
        contacts.addAccepted(bob.card(100), "Bob")
        val second = SoftwareMailboxKey.generate()
        val third = SoftwareMailboxKey.generate()
        contacts.offerFromLinkV2(bob.card(200, second), bob.nodeId, null)
        assertEquals(CardOffer.KEY_CHANGED, contacts.offerFromLinkV2(bob.card(300, third), bob.nodeId, null))
        assertTrue(contacts.pendingKeyChange(bob.nodeId)!!.mailboxPublic.contentEquals(third.compressedPublic))
        assertTrue(contacts.dismissKeyChange(bob.nodeId))
        assertFalse(contacts.get(bob.nodeId)!!.keysChanged)
        assertTrue(contacts.isKnownSender(bob.nodeId, bob.mailbox.compressedPublic))
        assertFalse(contacts.dismissKeyChange(bob.nodeId))
        assertNull(contacts.acceptKeyChange(bob.nodeId))
    }

    @Test
    fun aKeyChangeForAPendingCardIsIgnoredAndAMailboxKeyOfAnotherContactIsRefused() {
        val bob = Person()
        val carol = Person()
        val contacts = book()
        contacts.offerFromLinkV2(bob.card(100), bob.nodeId, null)
        assertEquals(CardOffer.KEY_CHANGED_IGNORED, contacts.offerFromLinkV2(bob.card(200, SoftwareMailboxKey.generate()), bob.nodeId, null))
        assertFalse(contacts.get(bob.nodeId)!!.keysChanged)
        // Carol tries to take Bob's mailbox key.
        contacts.addAccepted(carol.card(100), "Carol")
        assertEquals(CardOffer.MAILBOX_IN_USE, contacts.offerFromLinkV2(carol.card(300, bob.mailbox), carol.nodeId, null))
    }

    @Test
    fun contactSourceAndHeldKeysSurviveARestart() {
        val directory = FakeDirectory()
        val bob = Person().also { directory.verify(it.identity, "Bob") }
        val carol = Person()
        val file = File(Files.createTempDirectory("contactbook").toFile().also(dirs::add), "contacts.properties")
        val first = book(file = file)
        first.offerFromLinkV2(bob.card(100), bob.nodeId, directory)
        first.addAccepted(carol.card(100), "Carol")
        val rotated = SoftwareMailboxKey.generate()
        first.offerFromLinkV2(bob.card(200, rotated), bob.nodeId, directory)
        val second = book(file = file)
        assertEquals(ContactSource.VERIFIED, second.get(bob.nodeId)!!.source)
        assertEquals(ContactSource.MANUAL, second.get(carol.nodeId)!!.source)
        assertTrue(second.get(bob.nodeId)!!.keysChanged)
        assertTrue(second.pendingKeyChange(bob.nodeId)!!.mailboxPublic.contentEquals(rotated.compressedPublic))
    }

    @Test
    fun theGenerationMovesWhenAContactIsAcceptedChangedOrRemoved() {
        val bob = Person()
        val contacts = book()
        val start = contacts.generation
        contacts.addAccepted(bob.card(100), "Bob")
        assertTrue(contacts.generation > start)
        val afterAdd = contacts.generation
        contacts.offerFromLinkV2(bob.card(100), bob.nodeId, null)
        assertEquals("an unchanged card changes nothing", afterAdd, contacts.generation)
        contacts.remove(bob.nodeId)
        assertTrue(contacts.generation > afterAdd)
    }

    // ---- the verified-contact store stays the source of truth ---------------------------------------------------------------

    @Test
    fun reconcilePromotesAPendingCardAndOnlyRemovesContactsThatWereAcceptedBecauseTheyWereVerified() {
        val directory = FakeDirectory()
        val verifiedBob = Person().also { directory.verify(it.identity, "Bob") }
        val manualCarol = Person()
        val pendingDave = Person()
        val contacts = book()
        contacts.offerFromLinkV2(verifiedBob.card(100), verifiedBob.nodeId, directory)
        contacts.addAccepted(manualCarol.card(100), "Carol")
        contacts.offerFromLinkV2(pendingDave.card(100), pendingDave.nodeId, directory)
        // Dave is verified in person afterwards; Bob's verification is forgotten.
        directory.verify(pendingDave.identity, "Dave")
        directory.verified.remove(verifiedBob.identity.publicKeyEncoded.sfHex())
        val changed = contacts.reconcileVerified(directory)
        assertEquals(listOf(pendingDave.nodeId), changed.promoted)
        assertEquals(listOf(verifiedBob.nodeId), changed.demoted)
        assertEquals(ContactState.ACCEPTED, contacts.get(pendingDave.nodeId)!!.state)
        assertEquals("Dave", contacts.get(pendingDave.nodeId)!!.name)
        assertNull(contacts.get(verifiedBob.nodeId))
        assertNotNull("a contact the user accepted by hand is never touched", contacts.get(manualCarol.nodeId))
    }

    @Test
    fun anUnreadableVerifiedStoreDemotesNobody() {
        val directory = FakeDirectory()
        val bob = Person().also { directory.verify(it.identity, "Bob") }
        val contacts = book()
        contacts.offerFromLinkV2(bob.card(100), bob.nodeId, directory)
        directory.verified.clear()
        directory.readableNow = false
        assertTrue(contacts.reconcileVerified(directory).demoted.isEmpty())
        assertNotNull(contacts.get(bob.nodeId))
        directory.readableNow = true
        assertEquals(listOf(bob.nodeId), contacts.reconcileVerified(directory).demoted)
    }

    @Test
    fun theDirectoryReadsTheRealVerifiedContactStore() {
        val dir = Files.createTempDirectory("verified").toFile().also(dirs::add)
        val store = VerifiedContactStore(File(dir, "verified-contacts.bin"), aes)
        val bob = Person()
        val carol = Person()
        val fingerprint = IdentityFingerprint.fromPublicKey(bob.identity.publicKeyEncoded)
        assertEquals(
            MarkVerifiedResult.VERIFIED,
            store.markVerified(fingerprint, fingerprint, "Bob", VerificationMethod.SAFETY_CODE_CONFIRMED),
        )
        val directory = VerifiedStoreDirectory(store)
        assertEquals("Bob", directory.verifiedLabel(bob.identity.publicKeyEncoded))
        assertNull(directory.verifiedLabel(carol.identity.publicKeyEncoded))
        assertTrue(directory.isVerifiedNode(bob.nodeId))
        assertFalse(directory.isVerifiedNode(carol.nodeId))
        assertTrue(directory.readable)
        // A damaged store is reported unreadable instead of verifying or demoting anybody.
        File(dir, "verified-contacts.bin").writeBytes(ByteArray(64) { 1 })
        store.dropCache()
        val damaged = VerifiedStoreDirectory(store)
        assertFalse(damaged.readable)
        assertNull(damaged.verifiedLabel(bob.identity.publicKeyEncoded))
    }

    // ---- inside an exchange ------------------------------------------------------------------------------------------------

    @Test
    fun aVerifiedPeersMessageIsOpenedInTheSameExchangeThatBringsItsCard() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        // Alice has Bob's card (she scanned it), Bob has never seen Alice's card, but verified her identity in person.
        alice.knows(bob)
        val directory = FakeDirectory().also { it.verify(alice.identity, "Alice") }
        bob.node.verifiedDirectory = directory
        alice.engine.sendText(bob.nodeId, "hello Bob")
        val result = rig.sync(alice, bob)
        assertEquals(CardOffer.AUTO_ACCEPTED, result.responder.cardOffer)
        val contact = bob.node.contacts.get(alice.nodeId)!!
        assertEquals(ContactState.ACCEPTED, contact.state)
        assertEquals(ContactSource.VERIFIED, contact.source)
        assertEquals("Alice", contact.name)
        // The tag table was rebuilt after the card, so the SUMMARY that followed it was admitted and opened.
        assertEquals(listOf("hello Bob"), result.responder.v2Messages.map { it.text })
        assertTrue(alice.engine.threads.all().single().delivered)
    }

    @Test
    fun withoutVerificationTheSameExchangeLeavesTheCardPendingAndStoresNothing() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        alice.knows(bob)
        alice.engine.sendText(bob.nodeId, "hello Bob")
        val result = rig.sync(alice, bob)
        assertEquals(CardOffer.ADDED_PENDING, result.responder.cardOffer)
        assertEquals(0, bob.engine.threads.count())
        assertEquals(ContactState.PENDING, bob.node.contacts.get(alice.nodeId)!!.state)
    }

    @Test
    fun aVerifiedPeerGetsTheContactTierAndTheDeliveryFlowsBack() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        alice.knows(bob)
        val directory = FakeDirectory().also { it.verify(alice.identity, "Alice") }
        bob.node.verifiedDirectory = directory
        assertTrue(bob.node.isTrustedPeer(alice.nodeId))
        assertFalse(bob.node.isTrustedPeer(rig.Phone().nodeId))
        val reply = bob.node.tierFor(alice.nodeId, true)
        assertFalse("not a contact yet: the card has not arrived", reply.contact)
        rig.sync(alice, bob)
        assertTrue(bob.node.tierFor(alice.nodeId, true).contact)
    }
}
