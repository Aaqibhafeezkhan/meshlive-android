package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Messaging v2 link state machine: negotiation, v1 interop, chat sessions, CANCEL/PING, budgets and hostile peers. */
class MessagingV2LinkTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    /** A hung exchange fails the test instead of the build. */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()
    private val random = SecureRandom()

    @After
    fun cleanUp() = rig.cleanUp()

    private fun v1Text(from: SfRig.Phone, to: SfRig.Phone, text: String) = from.node.send(
        to.nodeId,
        BundleMessage(MessageKind.TEXT, rig.clock(), ByteArray(16), null, "text/plain", text.toByteArray()),
    )

    // ---- negotiation and interop ---------------------------------------------------------------------------------------

    @Test
    fun aV2PhoneDialingAV1PhoneIsRefusedOnceThenFallsBackToV1ForAnHour() {
        val v2 = rig.Phone()
        val v1 = rig.Phone(v2 = false)
        rig.contacts(v2, v1)
        v1Text(v1, v2, "from the old app")
        val first = rig.sync(v2, v1)
        // The v1 reader rejects the 19-byte HELLO and answers DONE(error); the dialer learns the peer is older.
        assertEquals(StoreForwardSyncOutcome.FAILED, first.responder.outcome)
        assertEquals(StoreForwardSyncOutcome.INTERRUPTED, first.initiator.outcome)
        assertTrue(first.initiator.detail!!.contains("Messaging v2"))
        assertTrue(v2.engine.isLegacy(v1.nodeId))
        // Within the hour the exchange is a plain v1 exchange in both directions.
        v1Text(v2, v1, "to the old app")
        val second = rig.sync(v2, v1)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.responder.outcome)
        assertEquals("from the old app", String(second.initiator.opened.single().message.body))
        assertEquals("to the old app", String(second.responder.opened.single().message.body))
        // An hour later it tries version 2 again.
        rig.clockMs.addAndGet(3_700_000)
        assertFalse(v2.engine.isLegacy(v1.nodeId))
    }

    @Test
    fun aV2MessageIsNeverOfferedToAV1Peer() {
        val v2 = rig.Phone()
        val v1 = rig.Phone(v2 = false)
        rig.contacts(v2, v1)
        v2.engine.sendText(v1.nodeId, "a version 3 bundle")
        rig.sync(v2, v1)
        val second = rig.sync(v2, v1)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        assertEquals(0, second.responder.completed.size)
        assertEquals(0, v1.node.archive.count())
        assertEquals(0, v1.node.store.all().size)
        assertEquals(1, v2.node.store.all().count { it.state == BundleState.QUEUED })
    }

    @Test
    fun aV1PhoneDialingAV2PhoneGetsAFullV1Exchange() {
        val v2 = rig.Phone()
        val v1 = rig.Phone(v2 = false)
        rig.contacts(v2, v1)
        v1Text(v1, v2, "hello v2")
        v1Text(v2, v1, "hello v1")
        v2.engine.sendText(v1.nodeId, "version 3 stays home")
        val logs = ArrayList<String>()
        val result = rig.sync(v1, v2, logs = logs)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
        assertEquals("hello v1", String(result.initiator.opened.single().message.body))
        assertEquals("hello v2", String(result.responder.opened.single().message.body))
        assertTrue(logs.any { it.startsWith("B:") && it.contains("proto=1") })
        assertEquals(0, v1.node.store.all().count { it.header.version == 3 })
    }

    @Test
    fun withTheSwitchOffTheBytesOnAirAreVersion1() {
        val v1 = rig.Phone(v2 = false)
        val peer = rig.Phone(v2 = false)
        rig.contacts(v1, peer)
        v1Text(v1, peer, "plain")
        val scripted = rig.scripted(v1, peer.nodeId)
        scripted.send(StoreForwardMessage.Hello(1, 0, 0, ByteArray(8)))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        val seen = ArrayList<ByteArray>()
        while (true) {
            val record = scripted.receiveRecord() ?: break
            seen += record
            val message = StoreForwardWire.decode(record, v2 = false)
            if (message is StoreForwardMessage.Summary) {
                scripted.send(
                    StoreForwardMessage.Want(message.entries[0].bundleId, true, message.entries[0].chunkCount, ChunkBitmap.full(message.entries[0].chunkCount)),
                )
            }
            if (message is StoreForwardMessage.Reply) break
        }
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        scripted.finish()
        // Every record the switch-off phone wrote parses with the v1 reader, HELLO is 17 bytes and bundles are version 2.
        assertTrue(seen.isNotEmpty())
        val hello = StoreForwardWire.decode(seen.first(), v2 = false) as StoreForwardMessage.Hello
        assertEquals(1, hello.version)
        assertEquals(9 + 17, seen.first().size)
        val headers = seen.map { StoreForwardWire.decode(it, v2 = false) }.filterIsInstance<StoreForwardMessage.Header>()
        assertEquals(2, headers.single().header.version)
    }

    @Test
    fun aV2PhoneAnswersAV1HelloWithAV1Hello() {
        val v2 = rig.Phone()
        val peer = rig.Phone(v2 = false)
        rig.contacts(v2, peer)
        val scripted = rig.scripted(v2, peer.nodeId)
        scripted.send(StoreForwardMessage.Hello(1, 0, 0, ByteArray(8)))
        val record = scripted.receiveRecord()!!
        val hello = StoreForwardWire.decode(record, v2 = false) as StoreForwardMessage.Hello
        assertEquals(1, hello.version)
        assertEquals(17, record.size - 9)
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        assertEquals(StoreForwardSyncOutcome.COMPLETE, scripted.finish().outcome)
    }

    @Test
    fun aV1HelloVersion3HeaderIsAProtocolViolation() {
        val v2 = rig.Phone()
        val peer = rig.Phone()
        rig.contacts(v2, peer)
        peer.engine.sendText(v2.nodeId, "v3")
        val header = peer.node.store.all().single().header
        val scripted = rig.scripted(v2, peer.nodeId)
        scripted.send(StoreForwardMessage.Hello(1, 0, 0, ByteArray(8)))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Header(header, 0))
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.FAILED, result.outcome)
    }

    // ---- chat session ------------------------------------------------------------------------------------------------------

    private fun chatPair(limits: MessagingV2Limits = MessagingV2Limits()): Pair<SfRig.Phone, SfRig.Phone> {
        val a = rig.Phone(v2Limits = limits, chat = true)
        val b = rig.Phone(v2Limits = limits, chat = true)
        rig.contacts(a, b)
        return a to b
    }

    private fun waitUntil(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    @Test
    fun aChatSessionStaysOpenAndDeliversTextWithoutANewExchange() {
        val (alice, bob) = chatPair()
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        // The session is idle but open: nothing has ended it.
        Thread.sleep(300)
        val sent = alice.engine.sendText(bob.nodeId, "arrives within the same session")
        waitUntil { bob.engine.threads.count() == 1 }
        waitUntil { alice.engine.threads.get(sent.id)!!.delivered }
        val reply = bob.engine.sendText(alice.nodeId, "and back again")
        waitUntil { alice.engine.threads.count() == 2 }
        assertEquals("and back again", alice.engine.threads.all().first { it.direction == MessageDirection.IN }.text)
        live.sessionA.endChat()
        val result = live.join()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
        assertTrue(reply.id.isNotEmpty())
    }

    @Test
    fun repeatedSummariesAreAcceptedInAChatSessionButNotOutsideOne() {
        val (alice, bob) = chatPair()
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat }
        repeat(5) {
            alice.engine.sendText(bob.nodeId, "burst $it")
            waitUntil { bob.engine.threads.count() == it + 1 }
        }
        live.sessionB.endChat()
        val result = live.join()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(5, result.responder.v2Messages.size)
        // Without the CHAT flag on both sides a second SUMMARY is the v1 violation.
        val noChat = rig.Phone()
        val peer = rig.Phone()
        rig.contacts(noChat, peer)
        val scripted = rig.scripted(noChat, peer.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        assertEquals(StoreForwardSyncOutcome.FAILED, scripted.finish().outcome)
    }

    @Test
    fun doneIsHeldInAChatSessionAndIdleEndsItCleanlyOnBothSides() {
        val (alice, bob) = chatPair()
        val live = rig.open(alice, bob)
        waitUntil { live.sessionA.isChat && live.sessionB.isChat }
        Thread.sleep(400)
        assertTrue("both sessions are still running", live.sessionA.isChat)
        live.sessionB.endChat()
        val result = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
    }

    @Test
    fun aSummaryAfterDoneIdleIsIgnoredAndItsBundlesWaitForTheNextExchange() {
        val (alice, _) = chatPair()
        val peer = rig.Phone(chat = true)
        rig.contacts(alice, peer)
        val scripted = rig.scripted(alice, peer.nodeId)
        scripted.send(rig.hello(2, StoreForwardHelloFlags.FILES or StoreForwardHelloFlags.CHAT))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.IDLE))
        // The session answers with its own DONE(IDLE) and ends; a late SUMMARY changes nothing.
        val seen = scripted.receiveUntil { it is StoreForwardMessage.Done }
        assertTrue(seen.any { it is StoreForwardMessage.Done && it.reason == StoreForwardDoneReason.IDLE })
        runCatching {
            scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        }
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.outcome)
    }

    @Test
    fun aPingKeepsTheSessionAliveAndMustNotComeMoreOftenThanEveryTenSeconds() {
        val limits = MessagingV2Limits(pingIntervalMs = 100, pingMinGapMs = 10_000)
        val (alice, _) = chatPair(limits)
        val peer = rig.Phone(chat = true)
        rig.contacts(alice, peer)
        val scripted = rig.scripted(alice, peer.nodeId)
        scripted.send(rig.hello(2, StoreForwardHelloFlags.FILES or StoreForwardHelloFlags.CHAT))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        // The session under test writes its own PING after a quiet interval.
        val seen = scripted.receiveUntil { it === StoreForwardMessage.Ping }
        assertTrue(seen.last() === StoreForwardMessage.Ping)
        scripted.send(StoreForwardMessage.Ping)
        scripted.send(StoreForwardMessage.Ping)
        val result = scripted.finish()
        assertEquals("two PINGs in 10 s are a violation", StoreForwardSyncOutcome.FAILED, result.outcome)
    }

    @Test
    fun aPingOutsideAChatSessionIsAViolation() {
        val v2 = rig.Phone()
        val peer = rig.Phone()
        rig.contacts(v2, peer)
        val scripted = rig.scripted(v2, peer.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Ping)
        assertEquals(StoreForwardSyncOutcome.FAILED, scripted.finish().outcome)
    }

    @Test
    fun aCallPreemptsAChatSessionWithinOneChunk() {
        val (alice, bob) = chatPair()
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(900_000, 9)) }, 900_000, Presentation.VIDEO, "v.mp4", "video/mp4")
        val result = rig.sync(alice, bob, onAChunk = { session, sent, _ -> if (sent == 7) session.requestSuspend() })
        assertEquals(StoreForwardSyncOutcome.SUSPENDED_LOCAL, result.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.SUSPENDED_BY_PEER, result.responder.outcome)
        assertEquals(7, result.initiator.chunksSent)
    }

    @Test
    fun aTextJumpsAheadOfTheNextChunkOfASegmentInAChatSession() {
        val (alice, bob) = chatPair(MessagingV2Limits(autoDownloadMaxBytes = 64_000_000))
        val bytes = rig.bytes(2_300_000, 61)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, "big.bin", "application/octet-stream")
        var sentText = false
        var wantsBefore = 0
        val live = rig.open(alice, bob, onAChunk = { session, sent, _ ->
            if (sent == 60 && !sentText) {
                sentText = true
                // The text is created while the first segment is streaming; the writer serves the smaller class first.
                wantsBefore = session.wantsServed
                alice.engine.sendText(bob.nodeId, "urgent")
            } else if (sent == 61) {
                // The writer wrote the text's SUMMARY before chunk 61. Hold it here until the receiver's WANT reached the serve
                // queue (an event, not a delay), so the order is decided by the priority rule and not by machine speed.
                val end = System.nanoTime() + 60_000_000_000L
                while (session.wantsServed == wantsBefore && System.nanoTime() < end) Thread.sleep(1)
            }
        })
        waitUntil(120_000) { bob.engine.threads.all().any { it.attachment?.state == FileState.COMPLETE } }
        live.sessionA.endChat()
        val result = live.join(120_000)
        val order = result.responder.completed
        val urgent = order.indexOfFirst { it is StoreForwardCompletion.V2Message && it.message.text == "urgent" }
        val firstSegment = order.indexOfFirst { it is StoreForwardCompletion.V2Segment }
        assertTrue("urgent text completed ($urgent) before the first segment ($firstSegment)", urgent in 0 until firstSegment)
    }

    @Test
    fun aDownloadCancelledAndApprovedAgainInTheSameSessionResumesWithoutANewExchange() {
        val (alice, bob) = chatPair(MessagingV2Limits(autoDownloadMaxBytes = 0))
        val bytes = rig.bytes(1_500_000, 62)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, "again.bin", "application/octet-stream")
        var cancelled = false
        val live = rig.open(alice, bob, onAChunk = { _, sent, _ ->
            if (sent == 30 && !cancelled) {
                cancelled = true
                bob.engine.cancelDownload(bob.engine.threads.all().first { it.attachment != null }.id)
            }
        })
        waitUntil { bob.engine.threads.all().any { it.attachment != null } }
        val card = bob.engine.threads.all().first { it.attachment != null }
        bob.engine.approveDownload(card.id)
        waitUntil(120_000) { cancelled }
        waitUntil { bob.engine.threads.get(card.id)!!.attachment!!.state == FileState.AWAITING_APPROVAL }
        // The tap again: the segment is requested again within the same session and the file completes.
        bob.engine.approveDownload(card.id)
        waitUntil(120_000) { bob.engine.threads.get(card.id)!!.attachment!!.state == FileState.COMPLETE }
        val info = bob.engine.threads.get(card.id)!!.attachment!!
        assertEquals(bytes.size.toLong(), info.totalSize)
        assertTrue(java.security.MessageDigest.isEqual(sfSha256(bytes), bob.engine.vault.sha256(info.vaultId!!)))
        live.sessionA.endChat()
        val result = live.join(120_000)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(0, result.responder.poisonedChunks)
    }

    // ---- unlisted WANT and CANCEL --------------------------------------------------------------------------------------

    @Test
    fun anUnlistedWantIsHonouredOnlyForAnOwnBundleAddressedToThatPeer() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        val carol = rig.Phone()
        rig.contacts(alice, bob)
        rig.contacts(alice, carol)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(1_200_000, 71)) }, 1_200_000, name = "f")
        alice.engine.sendFile(carol.nodeId, { ByteArrayInputStream(rig.bytes(1_200_000, 72)) }, 1_200_000, name = "g")
        val record = alice.engine.transfers.all().first { it.peer == bob.nodeId.toString() }
        val forCarol = alice.engine.transfers.all().first { it.peer == carol.nodeId.toString() }
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        // A segment is never listed in SUMMARY, yet it is served to its addressee on request...
        val chunks = record.segmentChunks[0]
        scripted.send(StoreForwardMessage.Want(hexToBytes(record.segmentIds[0]), true, chunks, ChunkBitmap.full(chunks)))
        val served = scripted.receiveUntil { it is StoreForwardMessage.Reply && it.reason == StoreForwardRefusal.SENT }
        assertTrue(served.any { it is StoreForwardMessage.Header && it.header.isBulk })
        assertEquals(chunks, served.count { it is StoreForwardMessage.Chunk })
        // ...but not to anyone else, whatever its ID: that is an unknown WANT.
        val otherChunks = forCarol.segmentChunks[0]
        scripted.send(StoreForwardMessage.Want(hexToBytes(forCarol.segmentIds[0]), true, otherChunks, ChunkBitmap.full(otherChunks)))
        val refused = scripted.receiveUntil { it is StoreForwardMessage.Reply }
        assertEquals(StoreForwardRefusal.UNKNOWN, (refused.last() as StoreForwardMessage.Reply).reason)
        assertTrue(refused.none { it is StoreForwardMessage.Chunk })
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        scripted.finish()
    }

    @Test
    fun anUnlistedWantIsAnUnknownWantInAV1Exchange() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(1_200_000, 73)) }, 1_200_000, name = "f")
        val record = alice.engine.transfers.all().single()
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(StoreForwardMessage.Hello(1, 0, 0, ByteArray(8)))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        val chunks = record.segmentChunks[0]
        scripted.send(StoreForwardMessage.Want(hexToBytes(record.segmentIds[0]), true, chunks, ChunkBitmap.full(chunks)))
        val seen = scripted.receiveUntil { it is StoreForwardMessage.Reply }
        assertEquals(StoreForwardRefusal.UNKNOWN, (seen.last() as StoreForwardMessage.Reply).reason)
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        scripted.finish()
    }

    @Test
    fun aBundleCanBeWantedAgainAfterItsServeFinishedButNotWhileItRuns() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(1_200_000, 74)) }, 1_200_000, name = "f")
        val record = alice.engine.transfers.all().single()
        val chunks = record.segmentChunks[0]
        val id = hexToBytes(record.segmentIds[0])
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Want(id, true, chunks, ChunkBitmap.full(chunks)))
        scripted.receiveUntil { it is StoreForwardMessage.Reply && it.reason == StoreForwardRefusal.SENT }
        scripted.send(StoreForwardMessage.Want(id, false, chunks, ChunkBitmap.full(chunks)))
        val again = scripted.receiveUntil { it is StoreForwardMessage.Reply }
        assertEquals(chunks, again.count { it is StoreForwardMessage.Chunk })
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        assertEquals(StoreForwardSyncOutcome.COMPLETE, scripted.finish().outcome)
        // While the first serve is still running (the peer is not reading), a second WANT is a protocol violation.
        val stalled = rig.scripted(alice, bob.nodeId, capacity = 16_000)
        stalled.send(rig.hello(2))
        stalled.send(StoreForwardMessage.Summary(emptyList(), true))
        stalled.send(StoreForwardMessage.Want(id, true, chunks, ChunkBitmap.full(chunks)))
        Thread.sleep(200)
        stalled.send(StoreForwardMessage.Want(id, true, chunks, ChunkBitmap.full(chunks)))
        val drain = Thread {
            // Drain so the blocked writer can finish and the session can report.
            while (stalled.receive() != null) Unit
        }.apply { isDaemon = true; start() }
        assertEquals(StoreForwardSyncOutcome.FAILED, stalled.finish().outcome)
        drain.join(2_000)
    }

    @Test
    fun aCancelStopsAServedBundleBeforeItsNextChunkAndNothingIsAnswered() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(1_200_000, 75)) }, 1_200_000, name = "f")
        val record = alice.engine.transfers.all().single()
        val chunks = record.segmentChunks[0]
        val id = hexToBytes(record.segmentIds[0])
        val scripted = rig.scripted(alice, bob.nodeId, capacity = 20_000)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Want(id, true, chunks, ChunkBitmap.full(chunks)))
        var got = 0
        val seen = scripted.receiveUntil { if (it is StoreForwardMessage.Chunk) got++; got >= 3 }
        scripted.send(StoreForwardMessage.Cancel(id))
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        scripted.finish()
        // Everything the session wrote after the CANCEL is still in the pipe: drain it.
        val rest = ArrayList<StoreForwardMessage>()
        while (true) rest += scripted.receive() ?: break
        val chunksAfter = rest.count { it is StoreForwardMessage.Chunk }
        assertTrue("stopped well before $chunks chunks (got ${got + chunksAfter})", got + chunksAfter < chunks)
        assertFalse("a cancelled serve answers nothing", rest.any { it is StoreForwardMessage.Reply })
        assertTrue(seen.isNotEmpty())
    }

    // ---- hostile peers -----------------------------------------------------------------------------------------------

    @Test
    fun aSummaryListingABulkClassIsSkippedAndNeverWanted() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(listOf(SummaryEntry(ByteArray(32) { 1 }, ByteArray(8), 4, 999_999, 10, 0)), true))
        scripted.send(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
        val result = scripted.finish()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.outcome)
        assertEquals(1, result.refusals[StoreForwardRefusal.INVALID])
    }

    @Test
    fun floodingControlRecordsEndsTheExchangeWithRateLimited() {
        val limits = MessagingV2Limits(controlBurst = 20)
        val alice = rig.Phone(v2Limits = limits)
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        repeat(60) {
            runCatching { scripted.send(StoreForwardMessage.Want(ByteArray(32) { b -> (it + b).toByte() }, true, 1, ChunkBitmap.full(1))) }
        }
        val seen = scripted.receiveUntil { it is StoreForwardMessage.Done && it.reason == StoreForwardDoneReason.RATE_LIMITED }
        assertTrue(seen.any { it is StoreForwardMessage.Done && it.reason == StoreForwardDoneReason.RATE_LIMITED })
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, scripted.finish().outcome)
    }

    @Test
    fun cancelFloodsAndRepeatedWantsForOneBundleAreRateLimited() {
        val limits = MessagingV2Limits(cancelPerMinute = 5, wantPerBundlePerHour = 3)
        val alice = rig.Phone(v2Limits = limits)
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        repeat(8) { runCatching { scripted.send(StoreForwardMessage.Cancel(ByteArray(32) { b -> (it + b).toByte() })) } }
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, scripted.finish().outcome)
        val sent = alice.engine.sendText(bob.nodeId, "wanted over and over")
        val id = hexToBytes(sent.bundleIds.single())
        val chunks = alice.node.store.get(sent.bundleIds.single())!!.chunkCount
        val second = rig.scripted(alice, bob.nodeId)
        second.send(rig.hello(2))
        second.send(StoreForwardMessage.Summary(emptyList(), true))
        repeat(3) {
            second.send(StoreForwardMessage.Want(id, true, chunks, ChunkBitmap.full(chunks)))
            second.receiveUntil { m -> m is StoreForwardMessage.Reply && m.reason == StoreForwardRefusal.SENT }
        }
        runCatching { second.send(StoreForwardMessage.Want(id, true, chunks, ChunkBitmap.full(chunks))) }
        assertEquals(StoreForwardSyncOutcome.RATE_LIMITED, second.finish().outcome)
    }

    @Test
    fun theControlBudgetCountsABurstAllowanceAndTheTenMinuteCap() {
        val now = AtomicLong(0)
        val budget = ControlBudget(MessagingV2Limits(controlPerSecond = 20, controlBurst = 50, controlPerTenMinutes = 120), now::get)
        repeat(50) { assertTrue(budget.control()) }
        assertFalse("burst used", budget.control())
        now.addAndGet(1_000)
        repeat(20) { assertTrue(budget.control()) }
        assertFalse(budget.control())
        // Refilling continues at the sustained rate until the ten-minute cap.
        var allowed = 70
        for (second in 1..40) {
            now.addAndGet(1_000)
            while (budget.control()) allowed++
        }
        assertEquals(120, allowed)
        now.addAndGet(601_000)
        assertTrue(budget.control())
        // CANCEL and per-bundle WANT limits, and the asymmetric-operation budget for non-contacts.
        val small = ControlBudget(MessagingV2Limits(cancelPerMinute = 2, wantPerBundlePerHour = 2, asymmetricOpsPerExchange = 3), now::get)
        assertTrue(small.cancel()); assertTrue(small.cancel()); assertFalse(small.cancel())
        now.addAndGet(61_000)
        assertTrue(small.cancel())
        assertTrue(small.want("a")); assertTrue(small.want("a")); assertFalse(small.want("a")); assertTrue(small.want("b"))
        assertTrue(small.asymmetric(false)); assertTrue(small.asymmetric(false, 2)); assertFalse(small.asymmetric(false))
        assertTrue("a contact is not limited", small.asymmetric(true, 100))
    }

    @Test
    fun aPingCanNeverKeepAStalledTransferAliveButLivenessNeedsAnyRecordInAChatSession() {
        val now = AtomicLong(1_000)
        val limits = StoreForwardLimits()
        val v2 = MessagingV2Limits()
        val chat = SessionDeadlines(true, limits, v2, now::get)
        chat.setTransferActive(true)
        // Pings arrive every 9 s for a minute but no chunk moves: the 10 s progress deadline still fires.
        var expired: DeadlineReason? = null
        for (i in 1..8) {
            now.addAndGet(9_000)
            chat.inbound()
            expired = chat.expired()
            if (expired != null) break
        }
        assertEquals(DeadlineReason.PROGRESS, expired)
        // Without a transfer, an idle chat session lives while records (PINGs) arrive and dies after 60 s of silence.
        val idle = SessionDeadlines(true, limits, v2, now::get)
        repeat(10) { now.addAndGet(20_000); idle.inbound(); assertNull(idle.expired()) }
        now.addAndGet(60_001)
        assertEquals(DeadlineReason.LIVENESS, idle.expired())
        // Our own writes do not prove the peer is alive in a chat session.
        val writes = SessionDeadlines(true, limits, v2, now::get)
        now.addAndGet(30_000); writes.outbound()
        now.addAndGet(31_000); writes.outbound()
        assertEquals(DeadlineReason.LIVENESS, writes.expired())
    }

    @Test
    fun aNonChatExchangeKeepsTheV1TimersAndATotalLimit() {
        val now = AtomicLong(0)
        val limits = StoreForwardLimits()
        val plain = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        now.addAndGet(9_000); plain.outbound()
        assertNull("either direction counts as a record", plain.expired())
        now.addAndGet(10_001)
        assertEquals(DeadlineReason.LIVENESS, plain.expired())
        val busy = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        repeat(13) { now.addAndGet(9_500); busy.inbound(); busy.chunk() }
        assertEquals(DeadlineReason.TOTAL, busy.expired())
        val window = WindowCounter(30 * 60_000, now::get)
        window.add(1000)
        now.addAndGet(29 * 60_000)
        window.add(24)
        assertEquals(1024, window.total())
        now.addAndGet(2 * 60_000)
        assertEquals(24, window.total())
    }

    @Test
    fun theTotalLimitIsExtendedWhileChunksKeepFlowingButNeverPastTheHardCap() {
        val now = AtomicLong(0)
        val limits = StoreForwardLimits()
        val flowing = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        flowing.setTransferActive(true)
        // A slow path (about 89 kbps: one 8 KB chunk per 0.7 s) is past 120 s but still moving: no TOTAL.
        var elapsed = 0L
        while (elapsed < 600_000) {
            now.addAndGet(700); elapsed += 700
            flowing.inbound(); flowing.chunk()
            assertNull("flowing at ${elapsed}ms", flowing.expired())
        }
        // The hard cap still ends a session that never stops moving.
        while (elapsed <= limits.sessionHardCapMs) {
            now.addAndGet(700); elapsed += 700
            flowing.inbound(); flowing.chunk()
        }
        assertEquals(DeadlineReason.TOTAL, flowing.expired())
    }

    @Test
    fun aSenderWithNothingLeftToSendKeepsTheExtendedSessionOneProgressWindowForThePeersCompletion() {
        // lane3 build 33b0b869: the sender wrote all 616 chunks, its jobs finished, and the 120 s total cut it 1.5 s before the
        // receiver completed.
        val now = AtomicLong(0)
        val limits = StoreForwardLimits()
        val sender = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        sender.setTransferActive(true)
        repeat(600) { now.addAndGet(700); sender.inbound(); sender.chunk() }
        assertNull(sender.expired())
        sender.setTransferActive(false) // every job finished; waiting for the receiver's final REPLY and DONE
        now.addAndGet(1_500); sender.inbound()
        assertNull("1.5 s after the last chunk the session is still alive", sender.expired())
        now.addAndGet(6_000); sender.inbound()
        assertNull("still inside one progress window", sender.expired())
        now.addAndGet(4_000); sender.inbound()
        assertEquals("a peer that never completes is cut by the total after the window", DeadlineReason.TOTAL, sender.expired())
    }

    @Test
    fun aTransferThatStopsMovingStillEndsOnProgressAtOnceAndATrickleGetsNoExtension() {
        val now = AtomicLong(0)
        val limits = StoreForwardLimits()
        val stopped = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        stopped.setTransferActive(true)
        repeat(300) { now.addAndGet(700); stopped.inbound(); stopped.chunk() }
        assertNull(stopped.expired())
        // Records still arrive (pings, summaries) but no chunk moves: the strict 10 s progress deadline fires, not the cap.
        now.addAndGet(9_000); stopped.inbound()
        assertNull(stopped.expired())
        now.addAndGet(1_100); stopped.inbound()
        assertEquals(DeadlineReason.PROGRESS, stopped.expired())

        // One chunk every 9 s passes the progress and liveness rules but is not flowing: cut at the 120 s total.
        val trickle = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        trickle.setTransferActive(true)
        var reason: DeadlineReason? = null
        var age = 0L
        while (reason == null && age < 300_000) {
            now.addAndGet(9_000); age += 9_000
            trickle.inbound(); trickle.chunk()
            reason = trickle.expired()
        }
        assertEquals(DeadlineReason.TOTAL, reason)
        assertTrue("cut at the 120 s total, not later (age $age)", age <= 129_000)

        // No transfer running (only control records): the plain 120 s limit applies even after earlier chunks.
        val quiet = SessionDeadlines(false, limits, MessagingV2Limits(), now::get)
        quiet.setTransferActive(true)
        repeat(20) { now.addAndGet(500); quiet.inbound(); quiet.chunk() }
        quiet.setTransferActive(false)
        repeat(13) { now.addAndGet(9_500); quiet.inbound() }
        assertEquals(DeadlineReason.TOTAL, quiet.expired())
    }

    @Test
    fun aStalledTransferInARealSessionTimesOutEvenWhilePingsKeepArriving() {
        val clock = AtomicLong(0)
        // No ping-gap rule here (the gap is 0): the session's reader may take several pings after the test has moved the virtual
        // clock on, and then two pings read the same instant; the gap rule has its own test.
        val limits = MessagingV2Limits(pingMinGapMs = 0)
        val alice = rig.Phone(v2Limits = limits, chat = true, loadHeadroom = false)
        val bob = rig.Phone(chat = true)
        rig.contacts(alice, bob)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(1_200_000, 76)) }, 1_200_000, name = "f")
        val record = alice.engine.transfers.all().single()
        val scripted = rig.scripted(alice, bob.nodeId, monotonicMs = clock::get, capacity = 20_000)
        scripted.send(rig.hello(2, StoreForwardHelloFlags.FILES or StoreForwardHelloFlags.CHAT))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        val chunks = record.segmentChunks[0]
        scripted.send(StoreForwardMessage.Want(hexToBytes(record.segmentIds[0]), true, chunks, ChunkBitmap.full(chunks)))
        // The peer never reads again: the writer stalls mid-segment. Time passes and pings keep the link "alive".
        repeat(12) {
            clock.addAndGet(2_000)
            runCatching { scripted.send(StoreForwardMessage.Ping) }
            Thread.sleep(80)
        }
        val result = scripted.finish()
        assertEquals("detail=${result.detail}", StoreForwardSyncOutcome.TIMEOUT, result.outcome)
    }

    @Test
    fun helloIsRequiredBeforeAnythingElseAndRepeatedHelloIsRefused() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val first = rig.scripted(alice, bob.nodeId)
        first.send(StoreForwardMessage.Summary(emptyList(), true))
        assertEquals(StoreForwardSyncOutcome.FAILED, first.finish().outcome)
        val second = rig.scripted(alice, bob.nodeId)
        second.send(rig.hello(2))
        second.send(rig.hello(2))
        assertEquals(StoreForwardSyncOutcome.FAILED, second.finish().outcome)
        val third = rig.scripted(alice, bob.nodeId)
        third.send(StoreForwardMessage.Hello(7, 0, 0, ByteArray(8)))
        assertEquals(StoreForwardSyncOutcome.FAILED, third.finish().outcome)
    }

    @Test
    fun garbageAndUnknownTypesInAV2ExchangeAreContained() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.sendRaw(ByteArray(50) { random.nextInt().toByte() })
        assertEquals(StoreForwardSyncOutcome.FAILED, scripted.finish().outcome)
        val other = rig.scripted(alice, bob.nodeId)
        other.send(rig.hello(2))
        other.sendRaw(StoreForwardWire.encode(StoreForwardMessage.Ping).also { it[4] = 77 })
        assertEquals(StoreForwardSyncOutcome.FAILED, other.finish().outcome)
    }

    @Test
    fun aV2PeerSendingAnUnrequestedChunkOrHeaderIsContainedOrFailed() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        bob.engine.sendText(alice.nodeId, "stray")
        val stray = bob.node.store.all().single()
        val scripted = rig.scripted(alice, bob.nodeId)
        scripted.send(rig.hello(2))
        scripted.send(StoreForwardMessage.Summary(emptyList(), true))
        scripted.send(StoreForwardMessage.Header(stray.header, 0))
        assertEquals(StoreForwardSyncOutcome.FAILED, scripted.finish().outcome)
        assertEquals(0, alice.node.store.all().size)
    }

    @Test
    fun aV1CardAndV2SessionTogetherStillLandPendingUntilAccepted() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        val result = rig.sync(alice, bob)
        assertEquals(CardOffer.ADDED_PENDING, result.initiator.cardOffer)
        assertEquals(CardOffer.ADDED_PENDING, result.responder.cardOffer)
        alice.node.contacts.accept(bob.nodeId, "Bob")
        bob.node.contacts.accept(alice.nodeId, "Alice")
        alice.engine.sendText(bob.nodeId, "now that we are contacts")
        assertEquals(1, rig.sync(alice, bob).responder.v2Messages.size)
    }

    @Test
    fun strangersCannotFileV2MessagesAndNothingIsStoredForThem() {
        val alice = rig.Phone()
        val mallory = rig.Phone()
        mallory.node.contacts.addAccepted(alice.node.ownCard, "alice")
        // Alice does not know Mallory: Mallory's v2 bundle is not for her (contacts only).
        mallory.engine.sendText(alice.nodeId, "hi stranger")
        val result = rig.sync(mallory, alice)
        assertEquals(0, result.responder.v2Messages.size)
        assertEquals(0, alice.engine.threads.count())
        assertEquals(0, alice.node.store.all().size)
    }

    @Test
    fun theDeliveredFlagArrivesFromTheDirectLinkEvenIfTheReceiptOutboxIsEmpty() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val sent = alice.engine.sendText(bob.nodeId, "ack me")
        rig.sync(alice, bob)
        assertTrue(alice.engine.threads.get(sent.id)!!.delivered)
        assertEquals(0, bob.engine.receiptOutbox.size())
        assertNull(bob.node.store.all().firstOrNull { it.role == BundleRole.OWN })
    }
}
