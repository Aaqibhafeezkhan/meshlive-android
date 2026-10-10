package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.BitSet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * `BSF1` messages, carried as BMS1 hop type 11 (`STORE_FORWARD`) inside the existing
 * authenticated, ordered adjacent-link session: `BSF1 | type(1) | length(4) | body`.
 * Nothing here relaxes link ordering or encryption; a bundle's own integrity comes from its
 * header (ID = SHA-256(header)) and the per-chunk hash list.
 */
sealed interface StoreForwardMessage {
    data class Hello(val version: Int, val freeQuotaBytes: Long, val dailyRemainingBytes: Long, val digest: ByteArray) :
        StoreForwardMessage
    class Summary(val entries: List<SummaryEntry>, val last: Boolean) : StoreForwardMessage
    class Want(val bundleId: ByteArray, val needHeader: Boolean, val chunkCount: Int, val missing: BitSet) : StoreForwardMessage
    class Header(val header: BundleHeader, val copyTokens: Int) : StoreForwardMessage
    class Chunk(val bundleId: ByteArray, val index: Int, val ciphertext: ByteArray) : StoreForwardMessage
    class Reply(val bundleId: ByteArray, val reason: StoreForwardRefusal) : StoreForwardMessage
    class DeliveredLocal(val bundleId: ByteArray) : StoreForwardMessage
    data class Done(val reason: StoreForwardDoneReason) : StoreForwardMessage
    class Card(val card: ContactCard) : StoreForwardMessage
}

enum class StoreForwardDoneReason(val wireValue: Int) {
    NORMAL(0),
    /** Live media needs the radio: the link closes now and resumes later from bitmaps. */
    SUSPEND(1),
    TIMEOUT(2),
    ERROR(3),
    /** Responder refuses: this neighbour exchanged too recently or too often (N3). */
    RATE_LIMITED(4),
}

object StoreForwardWire {
    private val MAGIC = "BSF1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val MAX_SUMMARY_ENTRIES = 256
    private const val HELLO = 1
    private const val SUMMARY = 2
    private const val WANT = 3
    private const val HEADER = 4
    private const val CHUNK = 5
    private const val REPLY = 6
    private const val DELIVERED_LOCAL = 7
    private const val DONE = 8
    private const val CARD = 9
    private const val ID = StoreForwardFormat.BUNDLE_ID_BYTES

    fun encode(message: StoreForwardMessage): ByteArray {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        val type = when (message) {
            is StoreForwardMessage.Hello -> HELLO.also {
                require(message.digest.size == 8)
                out.writeByte(message.version)
                out.writeInt(message.freeQuotaBytes.coerceIn(0, 0xffffffffL).toInt())
                out.writeInt(message.dailyRemainingBytes.coerceIn(0, 0xffffffffL).toInt())
                out.write(message.digest)
            }
            is StoreForwardMessage.Summary -> SUMMARY.also {
                require(message.entries.size <= MAX_SUMMARY_ENTRIES)
                out.writeShort(message.entries.size)
                out.writeByte(if (message.last) 1 else 0)
                message.entries.forEach { entry ->
                    out.write(entry.bundleId)
                    out.write(entry.tag)
                    out.writeByte(entry.priorityClass)
                    out.writeInt(entry.expiryHour)
                    out.writeShort(entry.chunkCount)
                    out.writeShort(entry.chunksHeld)
                }
            }
            is StoreForwardMessage.Want -> WANT.also {
                out.write(message.bundleId)
                out.writeByte(if (message.needHeader) 1 else 0)
                out.writeShort(message.chunkCount)
                out.write(ChunkBitmap.encode(message.missing, message.chunkCount))
            }
            is StoreForwardMessage.Header -> HEADER.also {
                out.writeByte(message.copyTokens)
                out.write(message.header.encoded)
            }
            is StoreForwardMessage.Chunk -> CHUNK.also {
                out.write(message.bundleId)
                out.writeShort(message.index)
                out.write(message.ciphertext)
            }
            is StoreForwardMessage.Reply -> REPLY.also {
                out.write(message.bundleId)
                out.writeByte(message.reason.wireValue)
            }
            is StoreForwardMessage.DeliveredLocal -> DELIVERED_LOCAL.also { out.write(message.bundleId) }
            is StoreForwardMessage.Done -> DONE.also { out.writeByte(message.reason.wireValue) }
            is StoreForwardMessage.Card -> CARD.also { out.write(message.card.encode()) }
        }
        out.flush()
        val payload = body.toByteArray()
        return ByteArrayOutputStream(9 + payload.size).also { bytes ->
            DataOutputStream(bytes).apply {
                write(MAGIC)
                writeByte(type)
                writeInt(payload.size)
                write(payload)
            }
        }.toByteArray()
    }

    fun decode(record: ByteArray): StoreForwardMessage {
        require(record.size >= 9) { "Store-forward message is too short" }
        val head = DataInputStream(ByteArrayInputStream(record))
        val magic = ByteArray(4).also(head::readFully)
        require(magic.contentEquals(MAGIC)) { "Invalid store-forward magic" }
        val type = head.readUnsignedByte()
        val length = head.readInt()
        require(length == record.size - 9) { "Store-forward length mismatch" }
        val body = record.copyOfRange(9, record.size)
        val input = DataInputStream(ByteArrayInputStream(body))
        fun id() = ByteArray(ID).also(input::readFully)
        val message: StoreForwardMessage = when (type) {
            HELLO -> {
                require(body.size == 17) { "Invalid HELLO" }
                StoreForwardMessage.Hello(
                    input.readUnsignedByte(),
                    input.readInt().toLong() and 0xffffffffL,
                    input.readInt().toLong() and 0xffffffffL,
                    ByteArray(8).also(input::readFully),
                )
            }
            SUMMARY -> {
                val count = input.readUnsignedShort()
                require(count <= MAX_SUMMARY_ENTRIES && body.size == 3 + count * 49) { "Invalid SUMMARY" }
                val last = when (input.readUnsignedByte()) {
                    0 -> false
                    1 -> true
                    else -> throw IllegalArgumentException("Invalid SUMMARY flag")
                }
                StoreForwardMessage.Summary(
                    List(count) {
                        val bundleId = id()
                        val tag = ByteArray(StoreForwardFormat.TAG_BYTES).also(input::readFully)
                        val priorityClass = input.readUnsignedByte()
                        val expiry = input.readInt()
                        val chunks = input.readUnsignedShort()
                        val held = input.readUnsignedShort()
                        require(held <= chunks) { "Invalid SUMMARY chunk count" }
                        SummaryEntry(bundleId, tag, priorityClass, expiry, chunks, held)
                    },
                    last,
                )
            }
            WANT -> {
                val bundleId = id()
                val needHeader = when (input.readUnsignedByte()) {
                    0 -> false
                    1 -> true
                    else -> throw IllegalArgumentException("Invalid WANT flag")
                }
                val count = input.readUnsignedShort()
                require(count in 1..StoreForwardFormat.MAX_CHUNKS && body.size == ID + 3 + (count + 7) / 8) { "Invalid WANT" }
                StoreForwardMessage.Want(bundleId, needHeader, count, ChunkBitmap.decode(ByteArray((count + 7) / 8).also(input::readFully), count))
            }
            HEADER -> {
                require(body.size > 1) { "Invalid HEADER" }
                StoreForwardMessage.Header(BundleHeader.decode(body.copyOfRange(1, body.size)), body[0].toInt() and 0xff)
            }
            CHUNK -> {
                require(body.size in ID + 2 + 1..ID + 2 + StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES) { "Invalid CHUNK" }
                StoreForwardMessage.Chunk(id(), input.readUnsignedShort(), body.copyOfRange(ID + 2, body.size))
            }
            REPLY -> {
                require(body.size == ID + 1) { "Invalid ACCEPT/REFUSE" }
                StoreForwardMessage.Reply(id(), StoreForwardRefusal.fromWire(input.readUnsignedByte()))
            }
            DELIVERED_LOCAL -> {
                require(body.size == ID) { "Invalid DELIVERED_LOCAL" }
                StoreForwardMessage.DeliveredLocal(id())
            }
            DONE -> {
                require(body.size == 1) { "Invalid DONE" }
                val value = input.readUnsignedByte()
                StoreForwardMessage.Done(StoreForwardDoneReason.entries.firstOrNull { it.wireValue == value }
                    ?: throw IllegalArgumentException("Unknown DONE reason $value"))
            }
            CARD -> StoreForwardMessage.Card(ContactCard.decode(body))
            else -> throw IllegalArgumentException("Unknown store-forward message type $type")
        }
        return message
    }
}

enum class StoreForwardSyncOutcome {
    /** Both phones sent DONE: everything wanted and offered this time was exchanged. */
    COMPLETE,
    SUSPENDED_LOCAL,
    SUSPENDED_BY_PEER,
    TIMEOUT,
    /** The link closed or failed first; partial bundles resume from their bitmaps. */
    INTERRUPTED,
    FAILED,
    /** The responder's per-neighbour cooldown/hourly cap refused this exchange (N3). */
    RATE_LIMITED,
}

data class StoreForwardSyncResult(
    val peer: NodeId,
    val outcome: StoreForwardSyncOutcome,
    val chunksSent: Int,
    val chunksReceived: Int,
    val duplicateChunks: Int,
    val poisonedChunks: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val completed: List<StoreForwardCompletion>,
    val cardOffer: CardOffer?,
    val refusals: Map<StoreForwardRefusal, Int>,
    val detail: String?,
) {
    val opened: List<ArchivedMessage> get() = completed.filterIsInstance<StoreForwardCompletion.Opened>().map { it.message }

    fun summary(): String = "outcome=$outcome sent=$chunksSent/${bytesSent}B received=$chunksReceived/${bytesReceived}B " +
        "opened=${opened.size} completed=${completed.size} dup=$duplicateChunks poisoned=$poisonedChunks" +
        (cardOffer?.let { " card=$it" } ?: "") + (detail?.let { " detail=$it" } ?: "")
}

/**
 * One store-and-forward exchange over an already authenticated adjacent link ([link], BMH2).
 *
 * Flow: the initiator sends HELLO and CARD; the responder answers with its own. After a peer's
 * HELLO, each side sends its SUMMARY (last flag on the final message). The receiving side admits
 * entries hop-locally ([StoreForwardNode.decide]) and sends one WANT per bundle with a needed-chunk
 * bitmap; the offering side answers with HEADER (if needed), the CHUNKs it holds and ACCEPT(SENT).
 * A side sends DONE(NORMAL) once all its WANTs are answered; the session ends when both DONEs crossed.
 *
 * Bounded by construction (finding 2): at most [StoreForwardLimits.maxSummaryEntries] entries are
 * offered or accepted, a bundle is served once per exchange (a repeated WANT is a protocol
 * violation), each WANT is one lazily read job rather than per-chunk queue entries, and outgoing
 * bytes stop at the per-exchange and per-neighbour daily limits (ACCEPT/REFUSE RATE). A dedicated
 * writer drains outbound work so neither side deadlocks on a full radio buffer, and a separate
 * watchdog enforces the progress/session timeouts and a suspend even while a write is blocked.
 * [requestSuspend] is honoured before the next record (DONE(SUSPEND), then close); a writer stuck
 * in a write is force-closed after [StoreForwardLimits.suspendGraceMs].
 */
class StoreForwardLinkSession(
    private val link: SecureBluetoothSession,
    private val node: StoreForwardNode,
    private val initiator: Boolean,
    private val closeLink: () -> Unit,
    private val log: (String) -> Unit = {},
    private val firstRecord: SecureRecord? = null,
    /** Test hook: called by the writer after each CHUNK record is written. */
    private val afterChunkWritten: (Int) -> Unit = {},
) {
    /** One WANT being served: HEADER if asked, each held chunk, then ACCEPT(SENT) or REFUSE(RATE). */
    private inner class ServeJob(val bundle: StoredBundle, needHeader: Boolean, private val missing: BitSet) {
        private var headerPending = needHeader
        private var nextIndex = missing.nextSetBit(0)
        var finished = false
            private set

        /** Writes the job's next record; returns false once it has nothing more to write. */
        fun writeNext(): Boolean {
            if (finished) return false
            if (headerPending) {
                headerPending = false
                writeMessage(StoreForwardMessage.Header(bundle.header, 0))
                return true
            }
            while (nextIndex >= 0 && !bundle.has(nextIndex)) nextIndex = missing.nextSetBit(nextIndex + 1)
            if (nextIndex >= 0) {
                if (outgoingLimitReached()) {
                    finish(StoreForwardRefusal.RATE)
                    return true
                }
                val index = nextIndex
                nextIndex = missing.nextSetBit(nextIndex + 1)
                val ciphertext = runCatching { node.store.readChunk(bundle.idHex, index) }.getOrNull()
                // A damaged stored chunk fails this bundle only, never the whole exchange (N5).
                if (ciphertext == null || !bundle.header.verifyChunk(index, ciphertext)) {
                    node.store.markCorrupt(bundle.idHex, "Stored chunk $index is damaged")
                    log("Store-forward bundle ${bundle.idHex.take(16)} chunk $index is damaged; skipped")
                    finish(StoreForwardRefusal.INVALID)
                    return true
                }
                link.writer.write(
                    SecureRecordType.STORE_FORWARD,
                    StoreForwardWire.encode(StoreForwardMessage.Chunk(bundle.header.bundleId, index, ciphertext)),
                )
                link.writer.flush()
                chunksSent.incrementAndGet()
                bytesSent.addAndGet(ciphertext.size.toLong())
                node.ledger.add(NeighborLedger.neighborKey(peer), bytesOut = ciphertext.size.toLong())
                afterChunkWritten(chunksSent.get())
                return true
            }
            finish(StoreForwardRefusal.SENT)
            return true
        }

        private fun finish(reason: StoreForwardRefusal) {
            finished = true
            writeMessage(StoreForwardMessage.Reply(bundle.header.bundleId, reason))
        }
    }

    private class Wanted(
        val entry: SummaryEntry,
        val role: BundleRole,
        val candidates: Set<NodeId>,
        val outstanding: BitSet,
        var headerReceived: Boolean,
    ) {
        var answered = false
    }

    private val peer = link.peerNodeId
    private val limits = node.limits
    private val control = LinkedBlockingQueue<StoreForwardMessage>(3 * limits.maxSummaryEntries + 16)
    private val jobs = LinkedBlockingQueue<ServeJob>(limits.maxSummaryEntries)
    private val suspendRequested = AtomicBoolean(false)
    private val stopWriter = AtomicBoolean(false)
    private val drainThenStop = AtomicBoolean(false)
    private val linkClosed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    @Volatile private var forcedOutcome: StoreForwardSyncOutcome? = null
    /** Monotonic progress clock for the watchdog (N7): never the wall clock. */
    @Volatile private var lastProgressNs = System.nanoTime()
    private val startNs = System.nanoTime()

    private var helloSent = false
    private var helloReceived = false
    private var peerSummaryDone = false
    private var peerIsCarrier = false
    private var doneSent = false
    private var doneReceived = false
    private var peerDoneReason: StoreForwardDoneReason? = null
    /** The writer hit an I/O error. Applied last, so the peer's own explicit DONE reason is never lost to it. */
    @Volatile private var writerFailed = false
    private val offered = HashMap<String, StoredBundle>()
    private val served = HashSet<String>()
    private var unknownWants = 0
    private var summaryEntriesReceived = 0
    private val wants = LinkedHashMap<String, Wanted>()
    private var reservedBytes = 0L
    private var tagTable: Map<String, Set<NodeId>> = emptyMap()

    private val chunksSent = AtomicInteger()
    private val bytesSent = AtomicLong()
    private var chunksReceived = 0
    private var bytesReceived = 0L
    private var duplicates = 0
    private var poisoned = 0
    private val completed = ArrayList<StoreForwardCompletion>()
    private var cardOffer: CardOffer? = null
    private val refusals = HashMap<StoreForwardRefusal, Int>()

    /** Live media needs the radio: stop within one chunk (thread-safe, idempotent). */
    fun requestSuspend() {
        suspendRequested.set(true)
    }

    /** Runs the exchange to its end. Never throws: failures are reported in the result. */
    fun run(): StoreForwardSyncResult {
        val writer = thread(name = "StoreForwardWriter") { writerLoop() }
        val watchdog = thread(name = "StoreForwardWatchdog") { watchdogLoop() }
        var outcome: StoreForwardSyncOutcome
        var detail: String? = null
        try {
            node.sweep()
            tagTable = node.tagTable()
            if (!initiator && !node.admitExchange(peer)) {
                forcedOutcome = StoreForwardSyncOutcome.RATE_LIMITED
                failWith(StoreForwardDoneReason.RATE_LIMITED)
                throw StoreForwardProtocolException("Exchange refused: this neighbour exchanged too recently")
            }
            if (initiator) sendHello()
            firstRecord?.let(::handleRecord)
            while (!(doneSent && doneReceived) && peerDoneReason.let { it == null || it == StoreForwardDoneReason.NORMAL }) {
                val record = link.reader.read()
                handleRecord(record)
            }
            outcome = when (peerDoneReason) {
                StoreForwardDoneReason.SUSPEND -> StoreForwardSyncOutcome.SUSPENDED_BY_PEER
                StoreForwardDoneReason.RATE_LIMITED -> StoreForwardSyncOutcome.RATE_LIMITED
                StoreForwardDoneReason.TIMEOUT, StoreForwardDoneReason.ERROR -> StoreForwardSyncOutcome.INTERRUPTED
                // Both DONEs crossed, but ours may not have reached the peer.
                else -> if (writerFailed) StoreForwardSyncOutcome.INTERRUPTED else StoreForwardSyncOutcome.COMPLETE
            }
        } catch (error: Exception) {
            outcome = forcedOutcome ?: if (error is StoreForwardProtocolException && !writerFailed) {
                failWith(StoreForwardDoneReason.ERROR)
                StoreForwardSyncOutcome.FAILED
            } else {
                StoreForwardSyncOutcome.INTERRUPTED
            }
            detail = error.message ?: error.javaClass.simpleName
        }
        if (outcome == StoreForwardSyncOutcome.COMPLETE) drainThenStop.set(true) else stopWriter.set(true)
        writer.join(limits.progressTimeoutMs + 1_000)
        if (writer.isAlive) {
            // Blocked in a write the peer never drains: closing the link unblocks it.
            closeOnce()
            writer.join(1_000)
        }
        stopWriter.set(true)
        finished.set(true)
        watchdog.interrupt()
        runCatching { node.store.flushAll() }
        watchdog.join(1_000)
        if (outcome == StoreForwardSyncOutcome.COMPLETE) {
            runCatching {
                node.ledger.recordSync(NeighborLedger.neighborKey(peer), node.inventoryDigest(offered.values.toList()))
            }
        }
        val result = StoreForwardSyncResult(
            peer, forcedOutcome ?: outcome, chunksSent.get(), chunksReceived, duplicates, poisoned,
            bytesSent.get(), bytesReceived, completed.toList(), cardOffer, refusals.toMap(), detail,
        )
        log("BENCHMARK store_forward session peer=${peer.shortLabel()} initiator=$initiator ${result.summary()}")
        return result
    }

    private fun handleRecord(record: SecureRecord) {
        lastProgressNs = System.nanoTime()
        if (record.type != SecureRecordType.STORE_FORWARD) {
            throw StoreForwardProtocolException("Unexpected record ${record.type} in a store-forward session")
        }
        val message = try {
            StoreForwardWire.decode(record.payload)
        } catch (error: IllegalArgumentException) {
            throw StoreForwardProtocolException(error.message ?: "Malformed store-forward message")
        }
        when (message) {
            is StoreForwardMessage.Hello -> onHello(message)
            is StoreForwardMessage.Card -> {
                cardOffer = node.contacts.offerFromLink(message.card, peer)
                log("Store-forward card from ${peer.shortLabel()}: $cardOffer")
            }
            is StoreForwardMessage.Summary -> onSummary(message)
            is StoreForwardMessage.Want -> onWant(message)
            is StoreForwardMessage.Header -> onHeader(message)
            is StoreForwardMessage.Chunk -> onChunk(message)
            is StoreForwardMessage.Reply -> onReply(message)
            is StoreForwardMessage.DeliveredLocal -> node.onDeliveredLocal(message.bundleId, peer)
            is StoreForwardMessage.Done -> {
                doneReceived = true
                peerDoneReason = message.reason
            }
        }
        maybeSendDone()
    }

    private fun sendHello() {
        if (helloSent) return
        helloSent = true
        val offers = node.offers(peer, peerIsCarrier = false)
        enqueue(StoreForwardMessage.Hello(StoreForwardWire.VERSION, node.freeForeignBytes(), node.dailyRemainingBytes(peer),
            node.inventoryDigest(offers)))
        runCatching { node.ownCard }.onSuccess { enqueue(StoreForwardMessage.Card(it)) }
    }

    private fun onHello(hello: StoreForwardMessage.Hello) {
        if (helloReceived) throw StoreForwardProtocolException("Repeated HELLO")
        if (hello.version != StoreForwardWire.VERSION) throw StoreForwardProtocolException("Unsupported store-forward version ${hello.version}")
        helloReceived = true
        peerIsCarrier = hello.freeQuotaBytes > 0
        sendHello()
        val offers = node.offers(peer, peerIsCarrier).take(limits.maxSummaryEntries)
        offers.forEach { offered[it.idHex] = it }
        val entries = offers.map(node::summaryEntry)
        if (entries.isEmpty()) {
            enqueue(StoreForwardMessage.Summary(emptyList(), last = true))
        } else {
            entries.chunked(StoreForwardWire.MAX_SUMMARY_ENTRIES).forEachIndexed { index, part ->
                enqueue(StoreForwardMessage.Summary(part, last = (index + 1) * StoreForwardWire.MAX_SUMMARY_ENTRIES >= entries.size))
            }
        }
    }

    private fun onSummary(summary: StoreForwardMessage.Summary) {
        if (!helloReceived || peerSummaryDone) throw StoreForwardProtocolException("Unexpected SUMMARY")
        summaryEntriesReceived += summary.entries.size
        if (summaryEntriesReceived > limits.maxSummaryEntries) throw StoreForwardProtocolException("Too many SUMMARY entries")
        val ordered = summary.entries.sortedWith(compareBy({ it.priorityClass }, { it.chunkCount }, { it.expiryHour }))
        for (entry in ordered) {
            if (wants.containsKey(entry.idHex)) continue
            when (val decision = node.decide(entry, peer, reservedBytes, tagTable)) {
                is StoreForwardAdmission.Want -> {
                    reservedBytes += decision.missing.cardinality().toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES
                    wants[entry.idHex] = Wanted(entry, decision.role, decision.candidates.ifEmpty { tagTable[entry.tag.sfHex()].orEmpty() },
                        decision.missing.clone() as BitSet, headerReceived = !decision.needHeader)
                    enqueue(StoreForwardMessage.Want(entry.bundleId, decision.needHeader, entry.chunkCount, decision.missing))
                }
                is StoreForwardAdmission.AlreadyDelivered -> enqueue(StoreForwardMessage.DeliveredLocal(decision.bundleId))
                is StoreForwardAdmission.Skip -> refusals.merge(decision.reason, 1, Int::plus)
            }
        }
        if (summary.last) peerSummaryDone = true
    }

    private fun onWant(want: StoreForwardMessage.Want) {
        val idHex = want.bundleId.sfHex()
        val bundle = offered[idHex]?.let { node.store.get(idHex) }
        if (bundle == null || bundle.chunkCount != want.chunkCount) {
            if (++unknownWants > limits.maxSummaryEntries) throw StoreForwardProtocolException("Too many unknown WANTs")
            enqueue(StoreForwardMessage.Reply(want.bundleId, StoreForwardRefusal.UNKNOWN))
            return
        }
        if (!served.add(idHex)) throw StoreForwardProtocolException("Repeated WANT")
        if (!jobs.offer(ServeJob(bundle, want.needHeader, want.missing))) {
            throw StoreForwardProtocolException("Too many WANTs")
        }
    }

    private fun onHeader(message: StoreForwardMessage.Header) {
        val idHex = message.header.bundleId.sfHex()
        val wanted = wants[idHex] ?: throw StoreForwardProtocolException("Unrequested HEADER")
        if (wanted.headerReceived || wanted.answered) throw StoreForwardProtocolException("Repeated HEADER")
        try {
            node.beginReceive(message.header, wanted.entry, wanted.role, peer, wanted.candidates)
            wanted.headerReceived = true
        } catch (error: IllegalArgumentException) {
            val reason = (error as? StoreForwardRefused)?.reason ?: StoreForwardRefusal.INVALID
            log("Store-forward header refused ($reason): ${error.message}")
            wanted.answered = true
            refusals.merge(reason, 1, Int::plus)
            enqueue(StoreForwardMessage.Reply(message.header.bundleId, reason))
        }
    }

    private fun onChunk(message: StoreForwardMessage.Chunk) {
        val idHex = message.bundleId.sfHex()
        val wanted = wants[idHex]
        if (wanted == null || !wanted.headerReceived || wanted.answered || !wanted.outstanding.get(message.index)) {
            // Unrequested or after a refused header: ignore without storing, but bound abuse.
            if (++duplicates > StoreForwardFormat.MAX_CHUNKS * 4) throw StoreForwardProtocolException("Too many unrequested chunks")
            return
        }
        when (node.store.writeChunk(idHex, message.index, message.ciphertext)) {
            ChunkWriteResult.ACCEPTED -> {
                chunksReceived++
                bytesReceived += message.ciphertext.size
                node.ledger.add(NeighborLedger.neighborKey(peer), bytesIn = message.ciphertext.size.toLong())
            }
            ChunkWriteResult.DUPLICATE -> duplicates++
            ChunkWriteResult.POISONED -> {
                poisoned++
                log("Store-forward poisoned chunk ${message.index} of ${idHex.take(16)} from ${peer.shortLabel()}")
                if (poisoned > limits.maxPoisonedChunks) throw StoreForwardProtocolException("Too many poisoned chunks")
                return
            }
            ChunkWriteResult.UNKNOWN_BUNDLE -> throw StoreForwardProtocolException("Chunk for an unknown bundle")
        }
        wanted.outstanding.clear(message.index)
        val stored = node.store.get(idHex)
        if (stored != null && stored.complete && stored.state == BundleState.RECEIVING) {
            val completion = node.onComplete(stored, peer)
            completed += completion
            log("Store-forward bundle ${idHex.take(16)} complete from ${peer.shortLabel()}: $completion")
            // Deferred (not stored durably) and Rejected are never acknowledged.
            if (completion is StoreForwardCompletion.Opened || completion is StoreForwardCompletion.ReceiptOpened ||
                completion == StoreForwardCompletion.PendingContact
            ) {
                enqueue(StoreForwardMessage.DeliveredLocal(stored.header.bundleId))
            }
        }
    }

    private fun onReply(message: StoreForwardMessage.Reply) {
        val wanted = wants[message.bundleId.sfHex()] ?: return
        wanted.answered = true
        if (message.reason != StoreForwardRefusal.SENT) refusals.merge(message.reason, 1, Int::plus)
    }

    private fun maybeSendDone() {
        if (doneSent || !helloReceived || !peerSummaryDone) return
        if (wants.values.any { !it.answered }) return
        doneSent = true
        enqueue(StoreForwardMessage.Done(StoreForwardDoneReason.NORMAL))
    }

    private fun enqueue(message: StoreForwardMessage) {
        if (!control.offer(message)) throw StoreForwardProtocolException("Outbound queue full")
    }

    /** Serving-side quotas: this exchange and this neighbour's UTC day (finding 2). */
    private fun outgoingLimitReached(): Boolean =
        bytesSent.get() + StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES > limits.sessionBytesOut ||
            node.ledger.today(NeighborLedger.neighborKey(peer)).bytesOut + StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES >
            limits.neighborDailyBytesOut

    private fun writeMessage(message: StoreForwardMessage) {
        link.writer.write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(message))
    }

    private fun failWith(reason: StoreForwardDoneReason) {
        runCatching {
            writeMessage(StoreForwardMessage.Done(reason))
            link.writer.flush()
        }
        closeOnce()
    }

    private fun closeOnce() {
        if (linkClosed.compareAndSet(false, true)) runCatching(closeLink)
    }

    private fun writerLoop() {
        var dirty = false
        var current: ServeJob? = null
        try {
            while (!stopWriter.get()) {
                if (suspendRequested.get()) {
                    forcedOutcome = forcedOutcome ?: StoreForwardSyncOutcome.SUSPENDED_LOCAL
                    failWith(StoreForwardDoneReason.SUSPEND)
                    return
                }
                val message = control.poll()
                if (message != null) {
                    writeMessage(message)
                    dirty = true
                    lastProgressNs = System.nanoTime()
                    continue
                }
                if (current == null || current.finished) current = jobs.poll()
                val job = current
                if (job != null) {
                    if (job.writeNext()) {
                        dirty = true
                        lastProgressNs = System.nanoTime()
                    }
                    continue
                }
                if (dirty) {
                    link.writer.flush()
                    dirty = false
                }
                // The reader sets drainThenStop only after its last enqueue, so once it is seen the
                // queues are final: stop only if they are still empty (else drain the late item).
                if (drainThenStop.get()) {
                    if (control.isEmpty() && jobs.isEmpty()) return
                    continue
                }
                control.poll(POLL_MS, TimeUnit.MILLISECONDS)?.let { late ->
                    writeMessage(late)
                    dirty = true
                    lastProgressNs = System.nanoTime()
                }
            }
        } catch (error: Exception) {
            if (!stopWriter.get()) {
                // A refusing peer (DONE RATE_LIMITED / SUSPEND) closes right after its DONE, so a write
                // racing that close fails; the reader still reports the peer's reason (not forcedOutcome).
                writerFailed = true
                closeOnce()
            }
        }
    }

    /**
     * Runs beside the writer so a write blocked on a peer that stopped reading cannot defeat the
     * timeouts or a suspend: closing the link unblocks both the reader and the writer.
     */
    private fun watchdogLoop() {
        var suspendSeenNs = 0L
        try {
            while (!finished.get()) {
                Thread.sleep(POLL_MS)
                if (suspendRequested.get()) {
                    if (suspendSeenNs == 0L) suspendSeenNs = System.nanoTime()
                    if ((System.nanoTime() - suspendSeenNs) / 1_000_000 >= limits.suspendGraceMs) {
                        forcedOutcome = forcedOutcome ?: StoreForwardSyncOutcome.SUSPENDED_LOCAL
                        stopWriter.set(true)
                        closeOnce()
                    }
                }
                val now = System.nanoTime()
                if ((now - lastProgressNs) / 1_000_000 > limits.progressTimeoutMs ||
                    (now - startNs) / 1_000_000 > limits.sessionTimeoutMs
                ) {
                    forcedOutcome = forcedOutcome ?: StoreForwardSyncOutcome.TIMEOUT
                    stopWriter.set(true)
                    closeOnce()
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        const val POLL_MS = 50L
    }
}

class StoreForwardProtocolException(message: String) : Exception(message)

/**
 * Responder-side dispatch on a call endpoint with the experiment on: reads the first record after
 * the BMH2 link handshake, accepting hop type 11 for that one read only. A store-forward record
 * starts a message exchange; anything else is handed unchanged to the call handshake, and type 11
 * is rejected again from then on.
 */
internal fun readFirstRecordForStoreForwardDispatch(link: SecureBluetoothSession): SecureRecord {
    link.reader.acceptStoreForward = true
    try {
        return link.reader.read()
    } finally {
        link.reader.acceptStoreForward = false
    }
}
