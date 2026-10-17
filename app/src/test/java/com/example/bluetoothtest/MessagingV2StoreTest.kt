package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.util.BitSet
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persistent records of Messaging v2: thread messages, transfers and keys, message IDs and the receipt outbox. */
class MessagingV2StoreTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val dirs = ArrayList<File>()
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 5 }, "AES") }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun dir(): File = Files.createTempDirectory("v2store-test").toFile().also(dirs::add)

    private fun message(
        id: Int,
        direction: MessageDirection = MessageDirection.IN,
        peer: String = "aa".repeat(16),
        created: Long = 1_790_000_000_000L,
        arrival: Long = 1_790_000_000_000L,
        attachment: AttachmentInfo? = null,
    ) = ThreadMessage(
        direction, peer, "%032x".format(id), if (attachment != null) V2Kind.ATTACHMENT else V2Kind.TEXT, created, arrival, 1,
        "body $id".toByteArray(), attachment, 0, null, mutableListOf("%064x".format(id)),
    )

    @Test
    fun threadMessagesSurviveARestartIncludingFlagsAndFileFields() {
        val root = dir()
        val store = ThreadStore(root, cipher, 100, 1_000_000)
        val info = AttachmentInfo(
            "ab".repeat(16), Presentation.PICTURE, 123_456, ByteArray(32) { 2 }, "pic.jpg", "image/jpeg", 640, 480, 0,
            ByteArray(40) { 7 }, 3, FileState.DOWNLOADING, "cd".repeat(16),
        )
        val sent = message(1, MessageDirection.OUT, attachment = info)
        sent.deliveredAtMs = 11
        sent.downloadedAtMs = 22
        sent.readAtMs = 33
        sent.notSent = true
        sent.disappearAtMs = 44
        store.save(sent)
        store.save(message(2))
        val reopened = ThreadStore(root, cipher, 100, 1_000_000)
        assertEquals(2, reopened.count())
        val back = reopened.get(sent.id)!!
        assertEquals(MessageDirection.OUT, back.direction)
        assertEquals(listOf("%064x".format(1)), back.bundleIds)
        assertTrue(back.delivered && back.downloaded && back.read && back.notSent)
        assertEquals(44, back.disappearAtMs)
        val a = back.attachment!!
        assertEquals("pic.jpg", a.name)
        assertEquals(FileState.DOWNLOADING, a.state)
        assertEquals("cd".repeat(16), a.vaultId)
        assertArrayEquals(ByteArray(40) { 7 }, a.thumbnail)
        assertNotNull(reopened.findByBundle("%064x".format(1)))
        assertNotNull(reopened.find(MessageDirection.IN, "aa".repeat(16), "%032x".format(2)))
    }

    @Test
    fun theStoreIsBoundedByRowsAndBytesAndAnUpdateNeverCountsTwice() {
        val store = ThreadStore(dir(), cipher, 2, 1_000_000)
        store.save(message(1))
        store.save(message(2))
        assertThrows(IllegalArgumentException::class.java) { store.save(message(3)) }
        store.save(message(1))
        assertEquals(2, store.count())
        val tiny = ThreadStore(dir(), cipher, 100, 100)
        assertThrows(IllegalArgumentException::class.java) { tiny.save(message(1)) }
        assertEquals(0, tiny.count())
    }

    @Test
    fun aDamagedOrForeignRecordIsDroppedAtStartAndNeverShown() {
        val root = dir()
        val store = ThreadStore(root, cipher, 100, 1_000_000)
        store.save(message(1))
        File(root, "garbage.rec").writeBytes(ByteArray(200) { it.toByte() })
        File(root, "stale.rec.tmp").writeBytes(ByteArray(10))
        val good = root.listFiles { f -> f.name.startsWith("in-") }!!.single()
        val other = File(root, "in-wrongname.rec")
        good.copyTo(other)
        val reopened = ThreadStore(root, cipher, 100, 1_000_000)
        assertEquals(1, reopened.count())
        assertFalse(File(root, "garbage.rec").exists())
        assertFalse(other.exists())
        assertFalse(File(root, "stale.rec.tmp").exists())
    }

    @Test
    fun theDisplayTimeIsClampedToTheWindowBeforeArrival() {
        val arrival = 1_790_000_000_000L
        assertEquals(arrival, message(1, created = arrival + 86_400_000, arrival = arrival).displayMs)
        assertEquals(arrival - 14L * 86_400_000, message(1, created = 1, arrival = arrival).displayMs)
        assertEquals(arrival - 5_000, message(1, created = arrival - 5_000, arrival = arrival).displayMs)
        val store = ThreadStore(dir(), cipher, 100, 1_000_000)
        store.save(message(1, created = arrival + 99_999_999, arrival = arrival))
        store.save(message(2, created = arrival - 60_000, arrival = arrival))
        assertEquals(listOf("%032x".format(2), "%032x".format(1)), store.forPeer("aa".repeat(16)).map { it.messageIdHex })
        assertEquals(0, store.waitingFor("aa".repeat(16)))
    }

    @Test
    fun waitingCountsOnlyUndeliveredOwnMessagesAndExpiredTimersAreListed() {
        val store = ThreadStore(dir(), cipher, 100, 1_000_000)
        val out1 = message(1, MessageDirection.OUT)
        val out2 = message(2, MessageDirection.OUT).also { it.deliveredAtMs = 5 }
        val out3 = message(3, MessageDirection.OUT).also { it.notSent = true }
        val timed = message(4).also { it.disappearAtMs = 1000 }
        listOf(out1, out2, out3, timed).forEach(store::save)
        assertEquals(1, store.waitingFor("aa".repeat(16)))
        assertEquals(listOf(timed.id), store.expired(1000).map { it.id })
        assertEquals(0, store.expired(999).size)
        assertNotNull(store.delete(timed.id))
        assertNull(store.delete(timed.id))
    }

    private fun transfer(id: String = "t1", peer: String = "bb".repeat(16)) = TransferRecord(
        id, MessageDirection.IN, peer, "%032x".format(9), "cc".repeat(16), "%064x".format(9), 2_500_000, ByteArray(32) { 1 },
        mutableListOf("%064x".format(1), "%064x".format(2)), mutableListOf(128, 119), 777_777, TransferState.ACTIVE,
        BitSet().apply { set(1) }, "dd".repeat(16), BitSet().apply { set(0) }, 2,
    )

    @Test
    fun transferRecordsAndFileKeysPersistSeparatelyAndKeysCanBeErased() {
        val root = dir()
        val book = TransferBook(File(root, "transfers"), cipher)
        val keys = TransferKeys(File(root, "keys"), cipher)
        book.save(transfer())
        keys.put("t1", ByteArray(32) { 9 })
        val reopenedBook = TransferBook(File(root, "transfers"), cipher)
        val reopenedKeys = TransferKeys(File(root, "keys"), cipher)
        val record = reopenedBook.get("t1")!!
        assertEquals(2, record.segmentCount)
        assertEquals(listOf(128, 119), record.segmentChunks)
        assertTrue(record.doneSegments.get(1) && !record.doneSegments.get(0))
        assertTrue(record.sealedSegments.get(0))
        assertEquals(2, record.failures)
        assertEquals("dd".repeat(16), record.vaultId)
        assertArrayEquals(ByteArray(32) { 9 }, reopenedKeys.get("t1"))
        // Erasing the key leaves the transfer record but no key anywhere.
        reopenedKeys.erase("t1")
        assertNull(TransferKeys(File(root, "keys"), cipher).get("t1"))
        assertEquals(0, TransferKeys(File(root, "keys"), cipher).count())
        assertEquals("t1", reopenedBook.forSegment(MessageDirection.IN, "%064x".format(2))!!.first.id)
        assertEquals(1, reopenedBook.forSegment(MessageDirection.IN, "%064x".format(2))!!.second)
        assertNull(reopenedBook.forSegment(MessageDirection.OUT, "%064x".format(2)))
        assertNull(reopenedBook.forFile(MessageDirection.IN, "other", "cc".repeat(16)))
        assertNotNull(reopenedBook.forFile(MessageDirection.IN, "bb".repeat(16), "cc".repeat(16)))
        reopenedBook.remove("t1")
        assertEquals(0, TransferBook(File(root, "transfers"), cipher).all().size)
    }

    @Test
    fun messageIdsAreBoundedAndExpireWithTheirTombstone() {
        val file = File(dir(), "seen")
        val seen = SeenMessageIds(file, cipher, 3)
        assertTrue(seen.add("peer", "m1", 100))
        assertTrue(seen.add("peer", "m2", 200))
        assertTrue(seen.add("other", "m1", 300))
        assertFalse("full: a new ID is refused", seen.add("peer", "m3", 400))
        assertTrue("an existing ID can be extended", seen.add("peer", "m1", 150))
        assertTrue(seen.contains("peer", "m1"))
        assertFalse(seen.contains("peer", "m3"))
        assertTrue("different senders have independent IDs", seen.contains("other", "m1"))
        seen.sweep(150)
        assertFalse(seen.contains("peer", "m1"))
        assertTrue(seen.contains("peer", "m2"))
        assertEquals(2, SeenMessageIds(file, cipher, 3).size())
    }

    @Test
    fun theReceiptOutboxDeduplicatesPersistsAndOrdersByAge() {
        val file = File(dir(), "outbox")
        val outbox = ReceiptOutbox(file, cipher)
        assertTrue(outbox.queue("p", ReceiptStatus.READ, "b1", 10))
        assertFalse("deduplicated by (peer, status, bundle)", outbox.queue("p", ReceiptStatus.READ, "b1", 99))
        assertTrue("another status is a separate fact", outbox.queue("p", ReceiptStatus.DOWNLOADED, "b1", 5))
        assertTrue(outbox.queue("q", ReceiptStatus.READ, "b2", 7))
        val reopened = ReceiptOutbox(file, cipher)
        assertEquals(3, reopened.size())
        assertEquals(listOf("b1", "b2", "b1"), reopened.pending().map { it.bundleIdHex })
        assertEquals(2, reopened.pending("p").size)
        reopened.remove(reopened.pending("p"))
        assertEquals(listOf("q"), ReceiptOutbox(file, cipher).pending().map { it.peer })
    }

    @Test
    fun sendTextEnforcesItsLengthAndTheContactRules() {
        val rig = SfRig()
        try {
            val alice = rig.Phone()
            val bob = rig.Phone()
            val stranger = rig.Phone()
            rig.contacts(alice, bob)
            assertThrows(IllegalArgumentException::class.java) { alice.engine.sendText(bob.nodeId, "") }
            assertThrows(IllegalArgumentException::class.java) { alice.engine.sendText(bob.nodeId, "x".repeat(8001)) }
            val max = alice.engine.sendText(bob.nodeId, "x".repeat(8000))
            assertEquals(8000, max.body.size)
            assertThrows("not a contact", IllegalArgumentException::class.java) { alice.engine.sendText(stranger.nodeId, "hi") }
            assertEquals(1, alice.engine.threads.count())
            // A pending (unaccepted) card is not a contact either.
            alice.node.contacts.offerFromLink(stranger.node.ownCard, stranger.nodeId)
            assertThrows(IllegalArgumentException::class.java) { alice.engine.sendText(stranger.nodeId, "hi") }
            assertTrue(alice.engine.isContact(bob.nodeId))
            assertFalse(alice.engine.isContact(stranger.nodeId))
            // An 8000-byte text sealed with the 70-byte header and padding stays inside the 256-chunk bundle limit.
            val sealed = alice.node.store.get(max.bundleIds.single())!!
            assertTrue(sealed.chunkCount in 1..2)
        } finally {
            rig.cleanUp()
        }
    }
}
