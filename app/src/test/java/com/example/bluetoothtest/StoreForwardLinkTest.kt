package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory byte pipe: reads block until data or close; close fails both ends like a dropped link. */
private class TestPipe(private val capacity: Int = Int.MAX_VALUE) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val buffer = java.util.ArrayDeque<Byte>()
    @Volatile var closed = false

    val input = object : InputStream() {
        override fun read(): Int = lock.withLock {
            while (buffer.isEmpty()) {
                if (closed) throw IOException("Pipe closed")
                available.await()
            }
            buffer.removeFirst().toInt().and(0xff).also { available.signalAll() }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = lock.withLock {
            if (len == 0) return 0
            while (buffer.isEmpty()) {
                if (closed) throw IOException("Pipe closed")
                available.await()
            }
            var count = 0
            while (count < len && buffer.isNotEmpty()) b[off + count++] = buffer.removeFirst()
            available.signalAll()
            count
        }
    }

    val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = lock.withLock {
            // A bounded pipe blocks the writer like a radio buffer the peer stopped draining.
            while (!closed && buffer.size + len > capacity && buffer.isNotEmpty()) available.await()
            if (closed) throw IOException("Pipe closed")
            for (i in off until off + len) buffer.addLast(b[i])
            available.signalAll()
        }
    }

    /** Like a socket close: data already written can still be read, then reads fail. */
    fun close() = lock.withLock {
        closed = true
        available.signalAll()
    }
}

class StoreForwardLinkTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val roots = ArrayList<File>()
    private val clockMs = AtomicLong(1_790_000_000_000L)
    private val clock: () -> Long = { clockMs.get() }

    @After
    fun cleanUp() = roots.forEach { it.deleteRecursively() }

    private inner class Phone(
        limits: StoreForwardLimits = StoreForwardLimits(),
        root: File? = null,
        identityKeys: java.security.KeyPair? = null,
        mailboxKey: SoftwareMailboxKey? = null,
        val cipher: AtRestCipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") },
        /** Most tests exchange back to back; the per-neighbour exchange limits are tested on their own. */
        exchangeLimits: Boolean = false,
    ) {
        val dir: File = root ?: Files.createTempDirectory("sf-test").toFile().also(roots::add)
        val keys = identityKeys ?: generateEcKeyPair()
        val identity = KeyPairSigningIdentity(keys)
        val mailbox = mailboxKey ?: SoftwareMailboxKey.generate()
        val node = StoreForwardNode.open(
            dir, identity, mailbox,
            cipher,
            (if (exchangeLimits) limits else limits.copy(exchangeCooldownMs = 0, maxExchangesPerHour = Int.MAX_VALUE)).withLoadHeadroom(),
            clock,
        )
        val nodeId get() = identity.nodeId

        fun reopen(limits: StoreForwardLimits = node.limits) = Phone(limits, dir, keys, mailbox, cipher, exchangeLimits = true)

        fun knows(other: Phone) = node.contacts.addAccepted(other.node.ownCard, "peer")

        fun sendText(to: Phone, text: String, ttl: Int = 72) = node.send(
            to.nodeId,
            BundleMessage(MessageKind.TEXT, clock(), ByteArray(16), null, "text/plain", text.toByteArray()),
            ttl,
        )

        fun sendBytes(to: Phone, size: Int, kind: MessageKind = MessageKind.PHOTO) = node.send(
            to.nodeId,
            BundleMessage(kind, clock(), ByteArray(16), null, "application/octet-stream",
                ByteArray(size).also(SecureRandom()::nextBytes)),
        )
    }

    private class SyncPair(val initiator: StoreForwardSyncResult, val responder: StoreForwardSyncResult)

    /**
     * Runs one exchange over a real BMS1 record layer (fresh directional keys) and in-memory pipes.
     * [onInitiatorChunk] / [onResponderChunk] run on the writer after each CHUNK record is written.
     */
    private fun sync(
        a: Phone,
        b: Phone,
        onInitiatorChunk: (StoreForwardLinkSession, Int, () -> Unit) -> Unit = { _, _, _ -> },
        onResponderChunk: (StoreForwardLinkSession, Int, () -> Unit) -> Unit = { _, _, _ -> },
        /**
         * Replays the interleaving a loaded host produces by chance: the responder finishes (and closes the
         * link) first, and the initiator's reader only runs after its own writer has failed on the closed
         * link. Only for a responder that refuses at once (rate limit); otherwise it would wait forever.
         */
        initiatorReadsAfterItsWriteFails: Boolean = false,
        /** Collects both sessions' log lines (prefixed A:/B:) for the measurement tests. */
        logs: MutableList<String>? = null,
    ): SyncPair {
        val aToB = TestPipe()
        val bToA = TestPipe()
        val close = { aToB.close(); bToA.close() }
        val keyAB = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyBA = ByteArray(32).also(SecureRandom()::nextBytes)
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_CLASSIC, 0, 0, 0, 0)
        val writeFailed = java.util.concurrent.CountDownLatch(1)
        val aOut = if (!initiatorReadsAfterItsWriteFails) aToB.output else object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = try {
                aToB.output.write(b, off, len)
            } catch (error: IOException) {
                writeFailed.countDown()
                throw error
            }
        }
        val aIn = if (!initiatorReadsAfterItsWriteFails) bToA.input else object : InputStream() {
            private var gated = true
            private fun gate() {
                if (gated) {
                    gated = false
                    assertTrue("initiator writer never failed", writeFailed.await(120, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            override fun read(): Int = gate().let { bToA.input.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = gate().let { bToA.input.read(b, off, len) }
        }
        val linkA = SecureBluetoothSession(b.nodeId, caps, SecureRecordReader(aIn, keyBA), SecureRecordWriter(aOut, keyAB))
        val linkB = SecureBluetoothSession(a.nodeId, caps, SecureRecordReader(aToB.input, keyAB), SecureRecordWriter(bToA.output, keyBA))
        linkA.reader.acceptStoreForward = true
        linkB.reader.acceptStoreForward = true
        lateinit var sessionA: StoreForwardLinkSession
        lateinit var sessionB: StoreForwardLinkSession
        sessionA = StoreForwardLinkSession(linkA, a.node, initiator = true, closeLink = close, log = { println("A: $it"); logs?.let { sink -> synchronized(sink) { sink += "A: $it" } } },
            afterChunkWritten = { onInitiatorChunk(sessionA, it, close) })
        sessionB = StoreForwardLinkSession(linkB, b.node, initiator = false, closeLink = close, log = { println("B: $it"); logs?.let { sink -> synchronized(sink) { sink += "B: $it" } } },
            afterChunkWritten = { onResponderChunk(sessionB, it, close) })
        var resultB: StoreForwardSyncResult? = null
        var responderError: Throwable? = null
        val responder = thread {
            try {
                resultB = sessionB.run()
            } catch (error: Throwable) {
                responderError = error
            }
        }
        if (initiatorReadsAfterItsWriteFails) responder.join(120_000)
        val resultA = sessionA.run()
        responder.join(120_000)
        close()
        responderError?.let { throw AssertionError("Responder failed", it) }
        return SyncPair(resultA, requireNotNull(resultB) { "Responder did not finish" })
    }

    @Test
    fun hopTypeElevenIsUnknownUnlessTheReaderOptsIn() {
        val key = ByteArray(32) { 3 }
        val bytes = ByteArrayOutputStream()
        SecureRecordWriter(bytes, key).apply {
            write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL)))
            flush()
        }
        val error = assertThrows(IllegalStateException::class.java) {
            SecureRecordReader(ByteArrayInputStream(bytes.toByteArray()), key).read()
        }
        assertEquals("Unknown secure record type 11", error.message)
        val optedIn = SecureRecordReader(ByteArrayInputStream(bytes.toByteArray()), key).apply { acceptStoreForward = true }
        assertEquals(SecureRecordType.STORE_FORWARD, optedIn.read().type)
    }

    @Test
    fun wireMessagesRoundTripAndRejectMalformedInput() {
        val id = ByteArray(32) { it.toByte() }
        val bits = java.util.BitSet().apply { set(0); set(9) }
        val messages = listOf(
            StoreForwardMessage.Hello(1, 123, 456, ByteArray(8) { 1 }),
            StoreForwardMessage.Summary(listOf(SummaryEntry(id, ByteArray(8) { 2 }, 1, 999, 10, 7)), last = true),
            StoreForwardMessage.Want(id, true, 10, bits),
            StoreForwardMessage.Chunk(id, 3, ByteArray(100) { 4 }),
            StoreForwardMessage.Reply(id, StoreForwardRefusal.QUOTA),
            StoreForwardMessage.DeliveredLocal(id),
            StoreForwardMessage.Done(StoreForwardDoneReason.SUSPEND),
        )
        messages.forEach { message ->
            val encoded = StoreForwardWire.encode(message)
            assertArrayEquals(encoded, StoreForwardWire.encode(StoreForwardWire.decode(encoded)))
            assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(encoded + 0) }
        }
        val want = StoreForwardWire.decode(StoreForwardWire.encode(messages[2])) as StoreForwardMessage.Want
        assertEquals(bits, want.missing)
        val unknownType = StoreForwardWire.encode(messages[6]).also { it[4] = 42 }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(unknownType) }
        val badBitmap = StoreForwardWire.encode(StoreForwardMessage.Want(id, false, 10, bits)).also {
            it[it.size - 1] = 0xff.toByte()
        }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(badBitmap) }
    }

    @Test
    fun textReachesTheRecipientAndAReceiptReturns() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "meet at the north gate")
        val first = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, first.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, first.responder.outcome)
        val received = first.responder.opened.single()
        assertEquals("meet at the north gate", String(received.message.body))
        assertEquals(alice.nodeId.toString(), received.peerNode)
        assertEquals(BundleState.HANDED, alice.node.store.get(sent.idHex)!!.state)
        assertEquals(1, bob.node.archive.count())
        // Bob queued an end-to-end receipt; the next exchange carries it back.
        val second = sync(bob, alice)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        assertTrue(second.responder.completed.single() is StoreForwardCompletion.ReceiptOpened)
        assertEquals(BundleState.DELIVERED, alice.node.store.get(sent.idHex)!!.state)
    }

    @Test
    fun exchangeLogsMessageTransferLinesWithBytesChunksAndTimings() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val bundle = alice.sendBytes(bob, 120_000)
        val logs = ArrayList<String>()
        sync(alice, bob, logs = logs)
        val session = logs.single { it.startsWith("A: BENCHMARK message_transfer session") }
        fun field(line: String, key: String) = Regex("$key=([^ ]+)").find(line)!!.groupValues[1]
        assertEquals(bundle.chunkCount.toString(), field(session, "chunksOut"))
        assertEquals("0", field(session, "chunksIn"))
        assertTrue(field(session, "bytesOut").toLong() > 120_000)
        assertEquals("COMPLETE", field(session, "outcome"))
        assertEquals("true", field(session, "initiator"))
        assertEquals("unknown", field(session, "transport"))
        assertTrue(field(session, "durationMs").toLong() >= field(session, "lastOutMs").toLong())
        assertTrue(field(session, "wireOut").toLong() >= field(session, "bytesOut").toLong())
        val receiver = logs.single { it.startsWith("B: BENCHMARK message_transfer session") }
        assertEquals(bundle.chunkCount.toString(), field(receiver, "chunksIn"))
        assertEquals(field(session, "bytesOut"), field(receiver, "bytesIn"))
        val out = logs.single { it.startsWith("A: BENCHMARK message_transfer bundle") }
        val inn = logs.single { it.startsWith("B: BENCHMARK message_transfer bundle") }
        assertEquals("out", field(out, "dir"))
        assertEquals("in", field(inn, "dir"))
        assertEquals("true", field(out, "complete"))
        assertEquals("true", field(inn, "complete"))
        assertEquals("${bundle.chunkCount}/${bundle.chunkCount}", field(inn, "chunks"))
    }

    @Test
    fun resumedChunksAreCountedInTheMeasurementLine() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val bundle = alice.sendBytes(bob, 300_000)
        sync(alice, bob, onInitiatorChunk = { _, sent, close -> if (sent == 10) close() })
        val logs = ArrayList<String>()
        sync(bob, alice, logs = logs)
        val line = logs.single { it.startsWith("A: BENCHMARK message_transfer session") }
        assertEquals("10", Regex("resumedChunks=([^ ]+)").find(line)!!.groupValues[1])
        assertEquals((bundle.chunkCount - 10).toString(), Regex("chunksIn=([^ ]+)").find(line)!!.groupValues[1])
    }

    @Test
    fun statusAndClearHelpersReportAndEmptyTheStores() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        alice.sendText(bob, "hello")
        assertTrue(StoreForwardDebug.statusLine(alice.node, "x").contains("ownQueued=1"))
        sync(alice, bob)
        val line = StoreForwardDebug.statusLine(bob.node)
        assertTrue(line, line.contains("archiveIn=1"))
        assertEquals(1, StoreForwardDebug.inboxLines(bob.node).size)
        assertTrue(StoreForwardDebug.clearMessages(bob.node) >= 1)
        assertTrue(StoreForwardDebug.statusLine(bob.node).contains("archiveIn=0"))
        assertEquals(1, bob.node.contacts.all().size)
        assertEquals(0, StoreForwardDebug.clearMessages(bob.node))
    }

    @Test
    fun appStateFormatNamesTheProcessImportance() {
        assertEquals("importance=foreground(100),interactive=1,doze=0,powersave=0", MessageAppStateFormat.format(100, true, false, false))
        assertEquals("importance=cached(400),interactive=0,doze=1,powersave=?", MessageAppStateFormat.format(400, false, true, null))
        assertTrue(MessageAppStateFormat.format(300, null, null, null).startsWith("importance=service(300)"))
    }

    @Test
    fun cardsExchangeOverTheLinkAsPendingUntilAccepted() {
        val alice = Phone()
        val bob = Phone()
        val result = sync(alice, bob)
        assertEquals(CardOffer.ADDED_PENDING, result.initiator.cardOffer)
        assertEquals(CardOffer.ADDED_PENDING, result.responder.cardOffer)
        assertEquals(ContactState.PENDING, alice.node.contacts.get(bob.nodeId)!!.state)
        assertThrows(IllegalArgumentException::class.java) { alice.sendText(bob, "not yet") }
        alice.node.contacts.accept(bob.nodeId, "Bob")
        bob.node.contacts.accept(alice.nodeId, "Alice")
        alice.sendText(bob, "now")
        assertEquals("now", String(sync(alice, bob).responder.opened.single().message.body))
        // A repeated card from the same link is recognised, not duplicated.
        assertEquals(CardOffer.ALREADY_KNOWN, sync(alice, bob).initiator.cardOffer)
    }

    @Test
    fun aCardMustMatchTheAuthenticatedLinkIdentity() {
        val alice = Phone()
        val bob = Phone()
        val mallory = Phone()
        assertEquals(CardOffer.IDENTITY_MISMATCH, alice.node.contacts.offerFromLink(mallory.node.ownCard, bob.nodeId))
        assertNull(alice.node.contacts.get(mallory.nodeId))
    }

    @Test
    fun interruptedTransferResumesWithoutResendingChunks() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val bundle = alice.sendBytes(bob, 300_000)
        val total = bundle.chunkCount
        assertTrue(total > 30)
        val cut = 10
        val first = sync(alice, bob, onInitiatorChunk = { _, sent, close -> if (sent == cut) close() })
        assertEquals(StoreForwardSyncOutcome.INTERRUPTED, first.responder.outcome)
        val partial = bob.node.store.get(bundle.idHex)!!
        assertEquals(BundleState.RECEIVING, partial.state)
        val held = partial.presentCount
        assertEquals(cut, held)
        // Persisted across a restart of the recipient's store.
        val bobAgain = bob.reopen()
        assertEquals(held, bobAgain.node.store.get(bundle.idHex)!!.presentCount)
        val second = sync(bobAgain, alice)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        assertEquals(total - held, second.responder.chunksSent)
        assertEquals(total - held, second.initiator.chunksReceived)
        assertEquals(300_000, second.initiator.opened.single().message.body.size)
    }

    @Test
    fun liveMediaPreemptsWithinOneChunkAndTheTransferResumes() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val bundle = alice.sendBytes(bob, 400_000, MessageKind.VIDEO)
        val suspendAfter = 5
        var chunksAtRequest = -1
        val first = sync(alice, bob, onInitiatorChunk = { session, sent, _ ->
            if (sent == suspendAfter) {
                chunksAtRequest = sent
                session.requestSuspend()
            }
        })
        assertEquals(StoreForwardSyncOutcome.SUSPENDED_LOCAL, first.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.SUSPENDED_BY_PEER, first.responder.outcome)
        // The request came after chunk 5; no further chunk was written ("within one chunk").
        assertEquals(chunksAtRequest, first.initiator.chunksSent)
        assertEquals(suspendAfter, first.responder.chunksReceived)
        val second = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        assertEquals(bundle.chunkCount - suspendAfter, second.initiator.chunksSent)
        assertEquals(1, second.responder.opened.size)
    }

    @Test
    fun deliveredBundlesAreNotTransferredAgain() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "once")
        sync(alice, bob)
        // Alice's phone forgets it was handed (simulating a lost DELIVERED_LOCAL) and re-offers it.
        alice.node.store.update(sent.idHex, BundleState.QUEUED)
        val again = sync(alice, bob)
        assertEquals(0, again.initiator.chunksSent)
        assertEquals(0, again.responder.opened.size)
        assertEquals(1, bob.node.archive.count(MessageDirection.IN))
        // Bob answered the re-offer with DELIVERED_LOCAL (and his receipt arrived), so Alice stops offering it.
        assertTrue(alice.node.store.get(sent.idHex)!!.state in setOf(BundleState.HANDED, BundleState.DELIVERED))
        // Store-level idempotence: a duplicate chunk is recognised, a poisoned one rejected.
        val big = alice.sendBytes(bob, 20_000)
        val header = big.header
        val carrier = Phone(StoreForwardLimits(carrierEnabled = true))
        carrier.node.store.beginReceive(header, BundleRole.FOREIGN, alice.nodeId.toString())
        val chunk0 = alice.node.store.readChunk(big.idHex, 0)!!
        assertEquals(ChunkWriteResult.ACCEPTED, carrier.node.store.writeChunk(big.idHex, 0, chunk0))
        assertEquals(ChunkWriteResult.DUPLICATE, carrier.node.store.writeChunk(big.idHex, 0, chunk0))
        val poisoned = alice.node.store.readChunk(big.idHex, 1)!!.also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(ChunkWriteResult.POISONED, carrier.node.store.writeChunk(big.idHex, 1, poisoned))
        assertFalse(carrier.node.store.get(big.idHex)!!.has(1))
    }

    @Test
    fun expiredBundlesAreNeitherOfferedNorKept() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "short lived", ttl = 1)
        clockMs.addAndGet(2 * 3_600_000L)
        val result = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(0, result.responder.opened.size)
        assertNull(alice.node.store.get(sent.idHex))
        // Acceptance-side check: an entry past its expiry beyond the skew allowance is refused.
        val entry = SummaryEntry(ByteArray(32) { 9 }, ByteArray(8), 0, StoreForwardFormat.hourOf(clock()) - 3, 1, 1)
        assertEquals(StoreForwardAdmission.Skip(StoreForwardRefusal.EXPIRED), bob.node.decide(entry, alice.nodeId, 0, emptyMap()))
        val withinSkew = SummaryEntry(ByteArray(32) { 8 }, ByteArray(8), 0, StoreForwardFormat.hourOf(clock()) - 1, 1, 1)
        assertEquals(StoreForwardAdmission.Skip(StoreForwardRefusal.NOT_FOR_ME), bob.node.decide(withinSkew, alice.nodeId, 0, emptyMap()))
    }

    @Test
    fun perNeighbourDailyAndSessionQuotasRefuseExcess() {
        val limits = StoreForwardLimits(neighborDailyBytes = 60_000, sessionBytes = 50_000)
        val alice = Phone()
        val bob = Phone(limits)
        alice.knows(bob)
        bob.knows(alice)
        alice.sendText(bob, "small 1")
        alice.sendText(bob, "small 2")
        alice.sendBytes(bob, 30_000)
        alice.sendBytes(bob, 30_000)
        val first = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, first.initiator.outcome)
        // Two texts (8 KiB reserved each) and one 30 KB photo (4 chunks reserved) fit the 50 KB session budget.
        assertEquals(3, first.responder.opened.size)
        assertEquals(1, first.responder.refusals[StoreForwardRefusal.QUOTA])
        // Next session the same day: the 60 KB daily allowance for this neighbour is exhausted.
        val second = sync(alice, bob)
        assertEquals(0, second.responder.opened.size)
        assertEquals(1, second.responder.refusals[StoreForwardRefusal.RATE])
        // A new UTC day resets it.
        clockMs.addAndGet(24 * 3_600_000L)
        assertEquals(1, sync(alice, bob).responder.opened.size)
    }

    @Test
    fun perSenderDailyBundleLimitApplies() {
        val alice = Phone()
        val bob = Phone(StoreForwardLimits(senderDailyBundles = 2))
        alice.knows(bob)
        bob.knows(alice)
        repeat(3) { alice.sendText(bob, "m$it") }
        val result = sync(alice, bob)
        assertEquals(2, result.responder.opened.size)
        assertEquals(1, result.responder.refusals[StoreForwardRefusal.RATE])
    }

    @Test
    fun nonRecipientRefusesWhenNotCarrying() {
        val alice = Phone()
        val bob = Phone()
        val carol = Phone()
        alice.knows(carol)
        carol.knows(alice)
        alice.knows(bob)
        bob.knows(alice)
        alice.sendText(carol, "for carol")
        val result = sync(alice, bob)
        // Bob is not a carrier (v1 default) and Alice offers Carol's bundle only to Carol.
        assertEquals(0, result.responder.chunksReceived)
        assertTrue(bob.node.store.all().isEmpty())
    }

    @Test
    fun aCarrierStoresVerifiesAndForwardsWithoutOpening() {
        // Carrying is hard-off in v1; the sender must itself run the (v2) carrier role to hand a
        // bundle to a non-recipient.
        val alice = Phone(StoreForwardLimits(carrierEnabled = true))
        val carrier = Phone(StoreForwardLimits(carrierEnabled = true))
        val carol = Phone()
        alice.knows(carol)
        carol.knows(alice)
        val sent = alice.sendText(carol, "via a carrier")
        val first = sync(alice, carrier)
        assertTrue(first.responder.completed.single() is StoreForwardCompletion.Carried)
        val carried = carrier.node.store.get(sent.idHex)!!
        assertEquals(BundleRole.FOREIGN, carried.role)
        assertEquals(BundleState.STORED, carried.state)
        // The carrier holds every chunk, verified against the hash list, but cannot open it.
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(carried.header, carrier.node.store.chunks(sent.idHex), carrier.mailbox, carrier.nodeId) { _, _ -> true }
        }
        val second = sync(carrier, carol)
        assertEquals("via a carrier", String(second.responder.opened.single().message.body))
        // Foreign quota: 25% per source neighbour (16 KB) refuses the 20 KB photo and 10% per tag
        // (6.4 KB) refuses even the one-chunk text.
        val tight = Phone(StoreForwardLimits(carrierEnabled = true, foreignQuotaBytes = 64_000))
        alice.sendBytes(carol, 20_000)
        val refused = sync(alice, tight).responder
        assertEquals(0, refused.completed.size)
        assertEquals(2, refused.refusals[StoreForwardRefusal.QUOTA])
    }

    @Test
    fun foreignEvictionPrefersLargeClassesAndNeverTouchesOwnData() {
        val limits = StoreForwardLimits(carrierEnabled = true, foreignQuotaBytes = 100_000,
            foreignPerNeighborFraction = 1.0, foreignPerTagFraction = 1.0)
        val alice = Phone(StoreForwardLimits(carrierEnabled = true))
        val carol = Phone()
        alice.knows(carol)
        carol.knows(alice)
        val carrier = Phone(limits)
        carrier.knows(carol)
        carol.knows(carrier)
        val own = carrier.sendText(carol, "carrier's own")
        val video = alice.sendBytes(carol, 40_000, MessageKind.VIDEO)
        val voice = alice.sendBytes(carol, 40_000, MessageKind.VOICE)
        sync(alice, carrier)
        assertNotNull(carrier.node.store.get(video.idHex))
        assertNotNull(carrier.node.store.get(voice.idHex))
        assertTrue(carrier.node.store.makeRoomForForeign(50_000, limits))
        assertNull(carrier.node.store.get(video.idHex))
        assertNotNull(carrier.node.store.get(voice.idHex))
        assertNotNull(carrier.node.store.get(own.idHex))
        assertFalse(carrier.node.store.makeRoomForForeign(200_000, limits))
        assertNotNull(carrier.node.store.get(own.idHex))
    }

    @Test
    fun pendingContactMessagesWaitWithTheSenderUntilAccepted() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        // Bob holds Alice's card only as a pending link card: nothing of hers is stored yet (N1).
        assertEquals(CardOffer.ADDED_PENDING, bob.node.contacts.offerFromLink(alice.node.ownCard, alice.nodeId))
        val sent = alice.sendText(bob, "hello stranger")
        val result = sync(alice, bob)
        assertEquals(0, result.responder.chunksReceived)
        assertEquals(1, result.responder.refusals[StoreForwardRefusal.NOT_FOR_ME])
        assertTrue(bob.node.store.all().isEmpty())
        assertEquals(BundleState.QUEUED, alice.node.store.get(sent.idHex)!!.state)
        bob.node.contacts.accept(alice.nodeId, "Alice")
        assertEquals("hello stranger", String(sync(alice, bob).responder.opened.single().message.body))
    }

    @Test
    fun archiveIsEncryptedAtRest() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        alice.sendText(bob, "secret words here")
        sync(alice, bob)
        val files = File(bob.dir, "messages").listFiles()!!.filter { it.name.endsWith(".body") || it.name.endsWith(".meta") }
        assertEquals(2, files.size)
        files.forEach { assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("secret words")) }
        val reopened = bob.reopen().node.archive
        val id = reopened.page(0, 10).single().bundleIdHex
        assertEquals("secret words here", String(reopened.load(id)!!.message.body))
    }

    private class RawLinks(val a: SecureBluetoothSession, val b: SecureBluetoothSession, val close: () -> Unit)

    private fun rawLinks(aNode: NodeId, bNode: NodeId, aToBCapacity: Int = Int.MAX_VALUE): RawLinks {
        val aToB = TestPipe(aToBCapacity)
        val bToA = TestPipe()
        val keyAB = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyBA = ByteArray(32).also(SecureRandom()::nextBytes)
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_CLASSIC, 0, 0, 0, 0)
        return RawLinks(
            SecureBluetoothSession(bNode, caps, SecureRecordReader(bToA.input, keyBA), SecureRecordWriter(aToB.output, keyAB)),
            SecureBluetoothSession(aNode, caps, SecureRecordReader(aToB.input, keyAB), SecureRecordWriter(bToA.output, keyBA)),
        ) { aToB.close(); bToA.close() }
    }

    @Test
    fun featureIsOffUnlessExplicitlyEnabled() {
        assertFalse(StoreForwardFeature.enabled)
        // A user session follows the explicit Settings choice, else the shipped default; no Manual settings toggle.
        assertTrue(StoreForwardFeature.resolve(false, { false }, stored = { if (it == StoreForwardFeature.PREF) true else null }))
        assertFalse(StoreForwardFeature.resolve(false, { true }, stored = { if (it == StoreForwardFeature.PREF) false else null }))
        assertEquals(CallFeatureDefaults.MESSAGES, StoreForwardFeature.resolve(false, { true }, stored = { null }))
        // A benchmark launch uses only its own extra, never a stored UI choice.
        assertFalse(StoreForwardFeature.resolve(true, { false }, stored = { true }))
        assertTrue(StoreForwardFeature.resolve(true, { it == StoreForwardFeature.EXTRA }, stored = { false }))
        assertFalse(StoreForwardLimits().carrierEnabled)
    }

    @Test
    fun responderDispatchHandsACallHandshakeOnUnchanged() {
        val initiatorInput = java.io.PipedInputStream(16 * 1024)
        val responderOutput = java.io.PipedOutputStream(initiatorInput)
        val responderInput = java.io.PipedInputStream(16 * 1024)
        val initiatorOutput = java.io.PipedOutputStream(responderInput)
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_CLASSIC, 0, 0, 0, 0)
        val initiatorIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val responderIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        var responderPeer: NodeId? = null
        var lateRecordError: Throwable? = null
        val responder = thread {
            val link = SecureBluetoothHandshake.respond(responderInput, responderOutput, responderIdentity, caps)
            val first = readFirstRecordForStoreForwardDispatch(link)
            assertEquals(SecureRecordType.END_TO_END_HANDSHAKE, first.type)
            responderPeer = EndToEndVideoHandshake.respond(link, responderIdentity, caps, first).peerNodeId
            // After the dispatch read, a call link rejects type 11 exactly as before.
            lateRecordError = runCatching { link.reader.read() }.exceptionOrNull()
        }
        val link = SecureBluetoothHandshake.initiate(initiatorInput, initiatorOutput, initiatorIdentity, caps)
        val endpoint = EndToEndVideoHandshake.initiate(link, initiatorIdentity, caps)
        link.writer.write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL)))
        link.writer.flush()
        responder.join(120_000)
        assertEquals(responderIdentity.nodeId, endpoint.peerNodeId)
        assertEquals(initiatorIdentity.nodeId, responderPeer)
        assertEquals("Unknown secure record type 11", lateRecordError?.message)
    }

    @Test
    fun aProtocolViolationFailsClosed() {
        val alice = Phone()
        val bob = Phone()
        val links = rawLinks(alice.nodeId, bob.nodeId)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        // Bob's side is a hostile raw peer: SUMMARY before HELLO.
        links.b.writer.write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(StoreForwardMessage.Summary(emptyList(), true)))
        links.b.writer.flush()
        val result = StoreForwardLinkSession(links.a, alice.node, initiator = true, closeLink = links.close).run()
        assertEquals(StoreForwardSyncOutcome.FAILED, result.outcome)
        // The hostile peer is told DONE(ERROR) before the link closes.
        val received = generateSequence { runCatching { StoreForwardWire.decode(links.b.reader.read().payload) }.getOrNull() }.toList()
        assertTrue(received.contains(StoreForwardMessage.Done(StoreForwardDoneReason.ERROR)))
    }

    @Test
    fun aSilentPeerTimesOut() {
        // The watchdog runs on System.nanoTime (N7): the frozen test wall clock cannot stop it.
        val alice = Phone(StoreForwardLimits(progressTimeoutMs = 400))
        val bob = Phone()
        val links = rawLinks(alice.nodeId, bob.nodeId)
        links.a.reader.acceptStoreForward = true
        val startNs = System.nanoTime()
        val result = StoreForwardLinkSession(links.a, alice.node, initiator = true, closeLink = links.close).run()
        assertEquals(StoreForwardSyncOutcome.TIMEOUT, result.outcome)
        // Well below the 120 s session limit (so the 400 ms progress watchdog did the work) and far above scheduling noise.
        assertTrue((System.nanoTime() - startNs) / 1_000_000 < 60_000)
    }

    @Test
    fun aPoisonedChunkIsRejectedAndTheBundleCompletesLater() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val bundle = alice.sendBytes(bob, 40_000)
        // A peer that does not verify what it serves: Alice's side is driven raw and poisons chunk 2.
        val links = rawLinks(alice.nodeId, bob.nodeId)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        var first: StoreForwardSyncResult? = null
        val bobSession = thread { first = StoreForwardLinkSession(links.b, bob.node, initiator = false, closeLink = links.close).run() }
        rawHandshake(links.a)
        writeMessage(links.a, StoreForwardMessage.Summary(listOf(alice.node.summaryEntry(bundle)), last = true))
        while (readMessage(links.a) !is StoreForwardMessage.Want) Unit
        writeMessage(links.a, StoreForwardMessage.Header(bundle.header, 0))
        for (index in 0 until bundle.chunkCount) {
            val chunk = alice.node.store.readChunk(bundle.idHex, index)!!
            if (index == 2) chunk[100] = (chunk[100].toInt() xor 1).toByte()
            writeMessage(links.a, StoreForwardMessage.Chunk(bundle.header.bundleId, index, chunk))
        }
        writeMessage(links.a, StoreForwardMessage.Reply(bundle.header.bundleId, StoreForwardRefusal.SENT))
        writeMessage(links.a, StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        bobSession.join(120_000)
        links.close()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, first!!.outcome)
        assertEquals(1, first!!.poisonedChunks)
        val partial = bob.node.store.get(bundle.idHex)!!
        assertFalse(partial.has(2))
        assertEquals(bundle.chunkCount - 1, partial.presentCount)
        assertTrue(first!!.opened.isEmpty())
        // A good copy (the real sender) supplies only the missing chunk.
        val second = sync(alice, bob)
        assertEquals(1, second.initiator.chunksSent)
        assertEquals(40_000, second.responder.opened.single().message.body.size)
    }

    // ---- Security review fixes (findings 1-10) ----

    private fun readMessage(link: SecureBluetoothSession): StoreForwardMessage =
        StoreForwardWire.decode(link.reader.read().payload)

    private fun writeMessage(link: SecureBluetoothSession, message: StoreForwardMessage) {
        link.writer.write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(message))
        link.writer.flush()
    }

    /** Raw peer side: sends HELLO, then reads the session's messages until its final SUMMARY. */
    private fun rawHandshake(link: SecureBluetoothSession): List<SummaryEntry> {
        writeMessage(link, StoreForwardMessage.Hello(StoreForwardWire.VERSION, 0, 0, ByteArray(8)))
        val entries = ArrayList<SummaryEntry>()
        while (true) {
            val message = readMessage(link)
            if (message is StoreForwardMessage.Summary) {
                entries += message.entries
                if (message.last) return entries
            }
        }
    }

    private class FlakyCipher(private val inner: AtRestCipher) : AtRestCipher {
        @Volatile var failSeal = false
        override fun seal(plaintext: ByteArray): ByteArray {
            // Only opened-message bodies (BSM1) fail; metadata files keep working.
            if (failSeal && plaintext.size >= 4 && String(plaintext, 0, 4, Charsets.US_ASCII) == "BSM1") {
                throw java.io.IOException("Simulated storage failure")
            }
            return inner.seal(plaintext)
        }
        override fun open(sealed: ByteArray): ByteArray = inner.open(sealed)
    }

    @Test
    fun finding1_aSelfDeclaredCarrierGetsNoBundlesForOthers() {
        val alice = Phone()
        val carol = Phone()
        alice.knows(carol)
        carol.knows(alice)
        alice.sendText(carol, "only for carol")
        // Mallory advertises carrier quota in HELLO; Alice does not carry, so nothing is offered.
        val mallory = Phone(StoreForwardLimits(carrierEnabled = true))
        val stranger = sync(mallory, alice)
        assertEquals(0, stranger.initiator.chunksReceived)
        assertTrue(mallory.node.store.all().isEmpty())
        assertEquals("only for carol", String(sync(alice, carol).responder.opened.single().message.body))
    }

    @Test
    fun finding2_aRepeatedWantFailsClosed() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        alice.sendBytes(bob, 30_000)
        val links = rawLinks(alice.nodeId, bob.nodeId)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        var result: StoreForwardSyncResult? = null
        val session = thread { result = StoreForwardLinkSession(links.a, alice.node, initiator = false, closeLink = links.close).run() }
        val entry = rawHandshake(links.b).single()
        val all = java.util.BitSet().apply { set(0, entry.chunkCount) }
        repeat(2) { writeMessage(links.b, StoreForwardMessage.Want(entry.bundleId, true, entry.chunkCount, all)) }
        session.join(120_000)
        assertEquals(StoreForwardSyncOutcome.FAILED, result!!.outcome)
        assertEquals("Repeated WANT", result!!.detail)
    }

    @Test
    fun finding2_tooManySummaryEntriesFailClosed() {
        val alice = Phone(StoreForwardLimits(maxSummaryEntries = 300))
        val bob = Phone()
        val links = rawLinks(alice.nodeId, bob.nodeId)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        var result: StoreForwardSyncResult? = null
        val session = thread { result = StoreForwardLinkSession(links.a, alice.node, initiator = false, closeLink = links.close).run() }
        rawHandshake(links.b)
        val random = SecureRandom()
        repeat(2) {
            val entries = List(StoreForwardWire.MAX_SUMMARY_ENTRIES) {
                SummaryEntry(ByteArray(32).also(random::nextBytes), ByteArray(8), 0, StoreForwardFormat.hourOf(clock()) + 5, 1, 1)
            }
            runCatching { writeMessage(links.b, StoreForwardMessage.Summary(entries, last = false)) }
        }
        session.join(120_000)
        assertEquals(StoreForwardSyncOutcome.FAILED, result!!.outcome)
        assertEquals("Too many SUMMARY entries", result!!.detail)
    }

    @Test
    fun finding2_outgoingQuotasStopServingPerExchangeAndPerDay() {
        val chunk = StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES.toLong()
        val alice = Phone(StoreForwardLimits(sessionBytesOut = 3 * chunk, neighborDailyBytesOut = 5 * chunk))
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val bundle = alice.sendBytes(bob, 100_000)
        val first = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, first.initiator.outcome)
        assertEquals(3, first.initiator.chunksSent)
        assertEquals(1, first.responder.refusals[StoreForwardRefusal.RATE])
        assertEquals(2, sync(alice, bob).initiator.chunksSent)
        assertEquals(0, sync(alice, bob).initiator.chunksSent)
        assertEquals(5, bob.node.store.get(bundle.idHex)!!.presentCount)
        clockMs.addAndGet(24 * 3_600_000L)
        assertEquals(3, sync(alice, bob).initiator.chunksSent)
    }

    @Test
    fun finding2_watchdogEndsASuspendEvenWhileTheWriterIsBlocked() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        alice.sendBytes(bob, 200_000)
        // Alice -> Bob holds about 2 chunks; the raw Bob stops reading after its WANT.
        val links = rawLinks(alice.nodeId, bob.nodeId, aToBCapacity = 20_000)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        val started = java.util.concurrent.CountDownLatch(1)
        val session = StoreForwardLinkSession(links.a, alice.node, initiator = false, closeLink = links.close,
            afterChunkWritten = { started.countDown() })
        var result: StoreForwardSyncResult? = null
        val runner = thread { result = session.run() }
        val entry = rawHandshake(links.b).single()
        val all = java.util.BitSet().apply { set(0, entry.chunkCount) }
        writeMessage(links.b, StoreForwardMessage.Want(entry.bundleId, true, entry.chunkCount, all))
        assertTrue(started.await(120, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(300)
        val requestedNs = System.nanoTime()
        session.requestSuspend()
        runner.join(120_000)
        val elapsedMs = (System.nanoTime() - requestedNs) / 1_000_000
        assertEquals(StoreForwardSyncOutcome.SUSPENDED_LOCAL, result!!.outcome)
        assertTrue("suspend took $elapsedMs ms", elapsedMs < 4_000)
        assertTrue(result!!.chunksSent < entry.chunkCount)
    }

    @Test
    fun finding2_watchdogTimesOutAWriterBlockedByAPeerThatStoppedReading() {
        val alice = Phone(StoreForwardLimits(progressTimeoutMs = 400))
        val bob = Phone()
        alice.knows(bob)
        alice.sendBytes(bob, 200_000)
        val links = rawLinks(alice.nodeId, bob.nodeId, aToBCapacity = 20_000)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        var result: StoreForwardSyncResult? = null
        val runner = thread { result = StoreForwardLinkSession(links.a, alice.node, initiator = false, closeLink = links.close).run() }
        val entry = rawHandshake(links.b).single()
        writeMessage(links.b, StoreForwardMessage.Want(entry.bundleId, true, entry.chunkCount,
            java.util.BitSet().apply { set(0, entry.chunkCount) }))
        runner.join(120_000)
        assertEquals(StoreForwardSyncOutcome.TIMEOUT, result!!.outcome)
    }

    @Test
    fun finding3_aStrangerCannotSpendAContactsDailyAllowance() {
        val alice = Phone()
        val bob = Phone(StoreForwardLimits(senderDailyBundles = 2))
        alice.knows(bob)
        bob.knows(alice)
        val mallory = Phone()
        // Mallory learned today's Alice-Bob tag and offers fake IDs carrying it, then leaves.
        val tag = StoreForwardTags.tag(bob.node.pairSecret(bob.node.contacts.get(alice.nodeId)!!),
            StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(clock())))
        val links = rawLinks(mallory.nodeId, bob.nodeId)
        links.a.reader.acceptStoreForward = true
        links.b.reader.acceptStoreForward = true
        var bobResult: StoreForwardSyncResult? = null
        val bobSession = thread { bobResult = StoreForwardLinkSession(links.b, bob.node, initiator = false, closeLink = links.close).run() }
        rawHandshake(links.a)
        val random = SecureRandom()
        val fakes = List(5) { SummaryEntry(ByteArray(32).also(random::nextBytes), tag, 0, StoreForwardFormat.hourOf(clock()) + 10, 1, 1) }
        writeMessage(links.a, StoreForwardMessage.Summary(fakes, last = true))
        var wants = 0
        while (wants < 5) if (readMessage(links.a) is StoreForwardMessage.Want) wants++
        links.close()
        bobSession.join(120_000)
        assertEquals(StoreForwardSyncOutcome.INTERRUPTED, bobResult!!.outcome)
        // Alice's real messages still fit her full allowance of 2.
        alice.sendText(bob, "one")
        alice.sendText(bob, "two")
        assertEquals(2, sync(alice, bob).responder.opened.size)
    }

    @Test
    fun finding3_aHeaderMustAuthenticateTheTagsOwnerBeforeAnyChunk() {
        val alice = Phone()
        val bob = Phone()
        val mallory = Phone()
        alice.knows(bob)
        bob.knows(alice)
        bob.knows(mallory)
        mallory.knows(bob)
        // Mallory (an accepted contact) seals to Bob but stamps the Alice-Bob tag on the bundle.
        val aliceBob = bob.node.pairSecret(bob.node.contacts.get(alice.nodeId)!!)
        val forged = StoreForwardSeal.seal(
            BundleMessage(MessageKind.TEXT, clock(), ByteArray(16), null, "text/plain", "spoof".toByteArray()),
            mallory.mailbox, mallory.nodeId, bob.mailbox.publicKey, bob.nodeId, aliceBob, clock(),
        )
        mallory.node.store.putOwn(forged, bob.nodeId, mallory.node.limits)
        val result = sync(mallory, bob)
        assertEquals(1, result.responder.refusals[StoreForwardRefusal.INVALID])
        assertEquals(0, result.responder.chunksReceived)
        assertEquals(0, bob.node.archive.count())
        assertEquals(0, bob.node.ledger.today(NeighborLedger.senderKey(alice.nodeId.toString())).bundlesIn)
    }

    @Test
    fun finding5_aFailedArchiveWriteKeepsTheCiphertextAndIsNotAcknowledged() {
        val alice = Phone()
        val flaky = FlakyCipher(AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") })
        val bob = Phone(cipher = flaky)
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "must not be lost")
        flaky.failSeal = true
        val first = sync(alice, bob)
        assertTrue(first.responder.completed.single() is StoreForwardCompletion.Deferred)
        assertEquals(BundleState.UNOPENED, bob.node.store.get(sent.idHex)!!.state)
        assertEquals(BundleState.QUEUED, alice.node.store.get(sent.idHex)!!.state)
        assertFalse(bob.node.store.isSeen(sent.idHex))
        // Storage recovers: the next exchange first reopens it, then acknowledges the re-offer.
        flaky.failSeal = false
        sync(alice, bob)
        assertEquals("must not be lost", String(bob.node.archive.load(sent.idHex)!!.message.body))
        assertNull(bob.node.store.get(sent.idHex))
        assertTrue(alice.node.store.get(sent.idHex)!!.state != BundleState.QUEUED)
        assertEquals(1, bob.node.store.all().count { it.role == BundleRole.OWN })
    }

    @Test
    fun finding5_anOpenInterruptedAfterArchivingIsCompletedOnceWithOneReceipt() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "crash window")
        // Simulate a kill after the last chunk was stored but before the open finished.
        val header = sent.header
        val received = bob.node.store.beginReceive(header, BundleRole.INBOX, alice.nodeId.toString())
        alice.node.store.chunks(sent.idHex).forEachIndexed { i, c -> bob.node.store.writeChunk(received.idHex, i, c) }
        val restarted = bob.reopen()
        val first = restarted.node.retryUnopened()
        assertTrue(first.single() is StoreForwardCompletion.Opened)
        assertTrue(restarted.node.retryUnopened().isEmpty())
        assertEquals(1, restarted.node.archive.count())
        assertEquals(1, restarted.node.store.all().count { it.role == BundleRole.OWN })
    }

    @Test
    fun finding6_openedMessagesExpireCountAgainstAQuotaAndArePaged() {
        val alice = Phone()
        val bob = Phone(StoreForwardLimits(archiveQuotaBytes = 30_000))
        alice.knows(bob)
        bob.knows(alice)
        alice.sendBytes(bob, 20_000)
        alice.sendBytes(bob, 20_000)
        val result = sync(alice, bob)
        assertEquals(1, result.responder.opened.size)
        assertEquals(1, result.responder.completed.count { it is StoreForwardCompletion.Deferred })
        assertTrue(bob.node.archive.bytes() <= 30_000)
        // Expiry: a 1-hour message disappears from the archive on both phones.
        val carol = Phone()
        carol.knows(alice)
        alice.knows(carol)
        val short = alice.sendText(carol, "brief", ttl = 1)
        sync(alice, carol)
        assertEquals(1, carol.node.archive.count())
        clockMs.addAndGet(2 * 3_600_000L)
        carol.node.sweepArchive()
        alice.node.sweepArchive()
        assertEquals(0, carol.node.archive.count())
        assertNull(alice.node.archive.load(short.idHex))
        // Paging: listing records only, newest first.
        repeat(5) { i ->
            clockMs.addAndGet(1_000)
            alice.sendText(carol, "m$i")
        }
        sync(alice, carol)
        val page = carol.node.archive.page(0, 2)
        assertEquals(listOf("m4", "m3"), page.map { it.preview })
        assertEquals(1, carol.node.archive.page(4, 2).size)
        assertEquals("m4", String(carol.node.archive.load(page.first().bundleIdHex)!!.message.body))
    }

    @Test
    fun finding7_aCardReusingAMailboxKeyCannotShadowTheRealSender() {
        val alice = Phone()
        val bob = Phone()
        val mallory = Phone()
        // Mallory's own signed card carrying Alice's public mailbox key reaches Bob first.
        val squat = ContactCard.create(mallory.identity, alice.mailbox, clock())
        assertEquals(CardOffer.ADDED_PENDING, bob.node.contacts.offerFromLink(squat, mallory.nodeId))
        bob.knows(alice)
        alice.knows(bob)
        alice.sendText(bob, "it is really me")
        val opened = sync(alice, bob).responder.opened.single()
        assertEquals(alice.nodeId.toString(), opened.peerNode)
        // Accepting Alice dropped the pending squatter (N2); a new squatting card is refused.
        assertNull(bob.node.contacts.get(mallory.nodeId))
        val second = Phone()
        val squat2 = ContactCard.create(second.identity, alice.mailbox, clock())
        assertEquals(CardOffer.MAILBOX_IN_USE, bob.node.contacts.offerFromLink(squat2, second.nodeId))
        assertFalse(bob.node.contacts.isKnownSender(mallory.nodeId, bob.mailbox.compressedPublic))
        assertTrue(bob.node.contacts.isKnownSender(alice.nodeId, alice.mailbox.compressedPublic))
    }

    @Test
    fun finding8_playbackReadsFromMemoryAndLeftoversAreDeleted() {
        val source = ByteArray(10) { it.toByte() }
        val buffer = ByteArray(8)
        assertEquals(4, StoreForwardPlaintextFiles.readAt(source, 6, buffer, 2, 8))
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), buffer.copyOfRange(2, 6))
        assertEquals(-1, StoreForwardPlaintextFiles.readAt(source, 10, buffer, 0, 1))
        assertEquals(-1, StoreForwardPlaintextFiles.readAt(source, -1, buffer, 0, 1))
        val cache = Files.createTempDirectory("sf-cache").toFile().also(roots::add)
        File(cache, StoreForwardPlaintextFiles.RECORDING).writeBytes(byteArrayOf(1))
        File(cache, StoreForwardPlaintextFiles.LEGACY_PLAYBACK).writeBytes(byteArrayOf(2))
        File(cache, "unrelated.bin").writeBytes(byteArrayOf(3))
        assertEquals(2, StoreForwardPlaintextFiles.cleanLeftovers(cache))
        assertEquals(listOf("unrelated.bin"), cache.list()!!.toList())
    }

    @Test
    fun finding9_deliveredLocalIsAnsweredOnlyToTheTagOwner() {
        val alice = Phone()
        val bob = Phone()
        val mallory = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "private")
        sync(alice, bob)
        val tags = bob.node.tagTable()
        val realTag = sent.header.tags.first()
        fun entry(tag: ByteArray) = SummaryEntry(sent.header.bundleId, tag, 0, sent.header.expiryHour, 1, 1)
        val skip = StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
        assertEquals(skip, bob.node.decide(entry(realTag), mallory.nodeId, 0, tags))
        assertEquals(skip, bob.node.decide(entry(ByteArray(8) { 1 }), alice.nodeId, 0, tags))
        assertTrue(bob.node.decide(entry(realTag), alice.nodeId, 0, tags) is StoreForwardAdmission.AlreadyDelivered)
    }

    @Test
    fun finding10_tombstonesOutliveTheAcceptanceGraceAndDeletedMessagesStayDeleted() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val sent = alice.sendText(bob, "delete me", ttl = 1)
        sync(alice, bob)
        bob.node.deleteMessage(sent.idHex)
        assertEquals(0, bob.node.archive.count())
        val entry = SummaryEntry(sent.header.bundleId, sent.header.tags.first(), 0, sent.header.expiryHour, 1, 1)
        // Every hour in which a replay could still be accepted (until expiry + 2 h skew).
        for (hour in 0..3) {
            clockMs.set(sent.header.expiryHour.toLong() * 3_600_000L + hour * 3_600_000L - 1)
            bob.node.sweep()
            val decision = bob.node.decide(entry, alice.nodeId, 0, bob.node.tagTable())
            assertFalse("hour $hour: $decision", decision is StoreForwardAdmission.Want)
        }
        assertEquals(0, bob.node.archive.count())
        assertEquals(sent.header.expiryHour + 3, bob.node.limits.tombstoneUntilHour(sent.header.expiryHour))
    }

    // ---- Second security review (N1-N7) ----

    @Test
    fun n1_pendingSendersCannotFillTheInboxAndIgnoreRemovesTheirBundles() {
        val bob = Phone()
        val throwaways = List(3) { Phone() }
        throwaways.forEach { stranger ->
            stranger.knows(bob)
            assertEquals(CardOffer.ADDED_PENDING, bob.node.contacts.offerFromLink(stranger.node.ownCard, stranger.nodeId))
            stranger.sendBytes(bob, 200_000)
            val result = sync(stranger, bob)
            assertEquals(0, result.responder.chunksReceived)
        }
        assertEquals(0L, bob.node.store.usage(BundleRole.INBOX))
        assertTrue(bob.node.tagTable().values.flatten().isEmpty())
        // A bundle held for a pending card (older builds) goes when the card is ignored.
        val legacy = throwaways.first()
        val sealed = legacy.node.store.all().single { it.role == BundleRole.OWN }
        val held = bob.node.store.beginReceive(sealed.header, BundleRole.INBOX, legacy.nodeId.toString())
        legacy.node.store.chunks(sealed.idHex).forEachIndexed { i, c -> bob.node.store.writeChunk(held.idHex, i, c) }
        bob.node.store.update(held.idHex, BundleState.PENDING_CONTACT, legacy.nodeId.toString())
        assertTrue(bob.node.removeContact(legacy.nodeId))
        assertNull(bob.node.store.get(held.idHex))
        // Unaccepted cards are pruned after 14 days.
        clockMs.addAndGet(15L * 24 * 3_600_000)
        bob.node.sweep()
        assertTrue(bob.node.contacts.all().isEmpty())
    }

    @Test
    fun n2_identityIsBoundIntoThePairSecretTagsAndSlot() {
        val alice = Phone()
        val bob = Phone()
        val mallory = Phone()
        // The same mailbox key under two identities gives different pair secrets (and tags).
        val asAlice = StoreForwardTags.pairSecret(bob.mailbox, bob.nodeId, alice.mailbox.publicKey, alice.nodeId)
        val asMallory = StoreForwardTags.pairSecret(bob.mailbox, bob.nodeId, alice.mailbox.publicKey, mallory.nodeId)
        assertFalse(asAlice.contentEquals(asMallory))
        assertArrayEquals(asAlice, StoreForwardTags.pairSecret(alice.mailbox, alice.nodeId, bob.mailbox.publicKey, bob.nodeId))
        // A slot sealed for Bob's identity does not open under another identity with Bob's key.
        val sealed = StoreForwardSeal.seal(
            BundleMessage(MessageKind.TEXT, clock(), ByteArray(16), null, "text/plain", "x".toByteArray()),
            alice.mailbox, alice.nodeId, bob.mailbox.publicKey, bob.nodeId, asAlice, clock(),
        )
        val known = { node: NodeId, key: ByteArray -> node == alice.nodeId && key.contentEquals(alice.mailbox.compressedPublic) }
        assertEquals("x", String(StoreForwardSeal.open(sealed.header, sealed.chunks, bob.mailbox, bob.nodeId, known).message.body))
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(sealed.header, sealed.chunks, bob.mailbox, mallory.nodeId, known)
        }
        // Version 2 headers only: a version-1 header is rejected.
        val v1 = sealed.header.encoded.copyOf().also { it[4] = 1 }
        assertEquals("Unsupported bundle version", assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(v1) }.message)
        assertEquals(2, StoreForwardFormat.BUNDLE_VERSION)
    }

    @Test
    fun n2_duplicateMailboxKeysAreRefusedAcrossPendingAndAcceptedCards() {
        val alice = Phone()
        val bob = Phone()
        val mallory = Phone()
        val other = Phone()
        assertEquals(CardOffer.ADDED_PENDING, bob.node.contacts.offerFromLink(ContactCard.create(mallory.identity, alice.mailbox, clock()), mallory.nodeId))
        // A second pending card with the same mailbox key is refused even though neither is accepted.
        assertEquals(CardOffer.MAILBOX_IN_USE, bob.node.contacts.offerFromLink(ContactCard.create(other.identity, alice.mailbox, clock()), other.nodeId))
        assertEquals(CardOffer.MAILBOX_IN_USE, bob.node.contacts.offerFromLink(alice.node.ownCard, alice.nodeId))
        // The user-verified (typed) card wins and removes the pending squatter.
        bob.knows(alice)
        assertNull(bob.node.contacts.get(mallory.nodeId))
        val tags = bob.node.tagTable()
        assertTrue(tags.values.all { it.size == 1 && alice.nodeId in it })
    }

    @Test
    fun n3_responderLimitsExchangesPerNeighbour() {
        val alice = Phone()
        val bob = Phone(StoreForwardLimits(exchangeCooldownMs = 60_000, maxExchangesPerHour = 2), exchangeLimits = true)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, sync(alice, bob).responder.outcome)
        val tooSoon = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, tooSoon.responder.outcome)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, tooSoon.initiator.outcome)
        clockMs.addAndGet(61_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, sync(alice, bob).responder.outcome)
        clockMs.addAndGet(61_000)
        // Third exchange in the same hour: refused by the hourly cap.
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, sync(alice, bob).responder.outcome)
        // Another neighbour is unaffected.
        assertEquals(StoreForwardSyncOutcome.COMPLETE, sync(Phone(), bob).responder.outcome)
    }

    @Test
    fun n3_rateLimitReplyBeatsTheInitiatorsFailedWrite() {
        val alice = Phone()
        val bob = Phone(StoreForwardLimits(exchangeCooldownMs = 60_000, maxExchangesPerHour = 2), exchangeLimits = true)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, sync(alice, bob).responder.outcome)
        // Bob refuses and closes the link before Alice's writer has put HELLO on the wire, and Alice's reader
        // only runs after that write failed. Alice must still report Bob's explicit DONE(RATE_LIMITED), not a generic interruption.
        val tooSoon = sync(alice, bob, initiatorReadsAfterItsWriteFails = true)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, tooSoon.responder.outcome)
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, tooSoon.initiator.outcome)
    }

    @Test
    fun n4_metadataFilesAreEncryptedAtRestAndNeighbourRecordsArePruned() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        alice.sendText(bob, "meta")
        alice.sendBytes(bob, 30_000)
        sync(alice, bob)
        val aliceHex = alice.nodeId.toString()
        val files = bob.dir.walkTopDown().filter { it.isFile && it.name.endsWith(".properties") }.toList()
        assertTrue(files.map { it.name }.containsAll(listOf("contacts.properties", "neighbors.properties", "seen.properties")))
        files.forEach { file ->
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse("${file.name} leaks a node ID", text.contains(aliceHex))
            assertFalse("${file.name} is plaintext", text.contains("bytesIn") || text.contains(".state"))
            // A random IV starts with '#' one time in 256, so a bare startsWith("#") check is flaky; sealed means
            // the at-rest cipher opens it (and the sealed text is then different from the opened plaintext).
            val opened = bob.cipher.open(file.readBytes())
            assertFalse("${file.name} is stored unsealed", opened.contentEquals(file.readBytes()))
        }
        val aliceMeta = alice.dir.walkTopDown().first { it.name == "meta.properties" }
        assertFalse(String(aliceMeta.readBytes(), Charsets.ISO_8859_1).contains("role"))
        val reopened = bob.reopen()
        assertEquals(ContactState.ACCEPTED, reopened.node.contacts.get(alice.nodeId)!!.state)
        // Ledger cap and idle pruning.
        val ledgerFile = Files.createTempFile("ledger", ".properties").toFile().also(roots::add)
        val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 5 }, "AES") }
        val ledger = NeighborLedger(ledgerFile, clock, cipher, maxRecords = 50)
        repeat(80) { ledger.add("neighbor.$it", bytesIn = 1) }
        ledger.prune()
        assertEquals(50, ledger.recordCount())
        clockMs.addAndGet(31L * 24 * 3_600_000)
        ledger.prune()
        assertEquals(0, ledger.recordCount())
    }

    @Test
    fun n4_batchedChunkSyncNeverClaimsUnsyncedChunks() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        val bundle = alice.sendBytes(bob, 200_000)
        assertTrue(bundle.chunkCount > 16)
        val held = bob.node.store.beginReceive(bundle.header, BundleRole.INBOX, alice.nodeId.toString())
        repeat(5) { bob.node.store.writeChunk(held.idHex, it, alice.node.store.readChunk(bundle.idHex, it)!!) }
        // A "crash" now (no flush): the saved bitmap still claims nothing, so all 5 are re-requested.
        assertEquals(0, bob.reopen().node.store.get(held.idHex)!!.presentCount)
        bob.node.store.flushAll()
        assertEquals(5, bob.reopen().node.store.get(held.idHex)!!.presentCount)
        // Every 16 unsynced chunks the batch is persisted without an explicit flush.
        (5 until 20).forEach { bob.node.store.writeChunk(held.idHex, it, alice.node.store.readChunk(bundle.idHex, it)!!) }
        assertEquals(5, bob.reopen().node.store.get(held.idHex)!!.presentCount)
        bob.node.store.writeChunk(held.idHex, 20, alice.node.store.readChunk(bundle.idHex, 20)!!)
        assertEquals(21, bob.reopen().node.store.get(held.idHex)!!.presentCount)
    }

    @Test
    fun n5_aDamagedStoredBundleIsSkippedAndShownFailedWithoutAbortingTheExchange() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val damaged = alice.sendBytes(bob, 40_000)
        val healthy = alice.sendText(bob, "still arrives")
        val chunkFile = File(alice.dir, "store/bundles/${damaged.idHex}/chunks.bin")
        chunkFile.writeBytes(chunkFile.readBytes().also { val at = 2 * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES + 7; it[at] = (it[at].toInt() xor 0x55).toByte() })
        val result = sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(1, result.responder.refusals[StoreForwardRefusal.INVALID])
        assertEquals("still arrives", String(result.responder.opened.single().message.body))
        assertEquals(BundleState.FAILED, alice.node.store.get(damaged.idHex)!!.state)
        assertTrue(damaged.idHex in alice.node.store.failed())
        assertTrue(alice.node.offers(bob.nodeId, false).none { it.idHex == damaged.idHex })
        assertNotNull(healthy)
    }

    @Test
    fun n5_aTornHeaderLeavesAVisibleFailedEntryInsteadOfSilentDeletion() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        val queued = alice.sendText(bob, "torn")
        val dir = File(alice.dir, "store/bundles/${queued.idHex}")
        File(dir, "header.bin").writeBytes(ByteArray(10))
        val restarted = alice.reopen()
        assertNull(restarted.node.store.get(queued.idHex))
        assertTrue(queued.idHex in restarted.node.store.failed())
        assertTrue(dir.exists())
        assertNotNull(restarted.node.archive.load(queued.idHex))
    }

    @Test
    fun n6_deletingAQueuedMessageWithdrawsIt() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        val queued = alice.sendText(bob, "never mind")
        alice.node.deleteMessage(queued.idHex)
        assertNull(alice.node.store.get(queued.idHex))
        assertEquals(0, alice.node.archive.count())
        assertEquals(0, sync(alice, bob).responder.chunksReceived)
    }

    @Test
    fun n6_safetyNumberIsStableShortAndKeyBound() {
        val alice = Phone()
        val card = alice.node.ownCard
        val number = card.safetyNumber()
        assertTrue(number, Regex("""\d{5} \d{5} \d{5} \d{5}""").matches(number))
        assertEquals(number, ContactCard.fromText(card.toText()).safetyNumber())
        val otherKey = ContactCard.create(alice.identity, SoftwareMailboxKey.generate(), clock())
        assertFalse(number == otherKey.safetyNumber())
        assertFalse(number == Phone().node.ownCard.safetyNumber())
    }

    @Test
    fun n7_aMissingMailboxKeyIsAVisibleResetNotASilentNewKey() {
        assertEquals(MailboxKeyAction.CREATE, MailboxKeyPlanner.plan(null, false, false))
        assertEquals(MailboxKeyAction.USE_KEYSTORE, MailboxKeyPlanner.plan("keystore", true, false))
        assertEquals(MailboxKeyAction.USE_SOFTWARE, MailboxKeyPlanner.plan("software", false, true))
        assertEquals(MailboxKeyAction.RESET_AND_CREATE, MailboxKeyPlanner.plan("keystore", false, false))
        assertEquals(MailboxKeyAction.RESET_AND_CREATE, MailboxKeyPlanner.plan("software", false, false))
        assertEquals(MailboxKeyAction.RESET_AND_CREATE, MailboxKeyPlanner.plan("unknown", true, true))
        val dir = Files.createTempDirectory("sf-key").toFile().also(roots::add)
        assertNull(MailboxKeyResetNotice.pendingSinceMs(dir))
        MailboxKeyResetNotice.record(dir, 1234L)
        assertEquals(1234L, MailboxKeyResetNotice.pendingSinceMs(dir))
        MailboxKeyResetNotice.dismiss(dir)
        assertNull(MailboxKeyResetNotice.pendingSinceMs(dir))
    }
}
