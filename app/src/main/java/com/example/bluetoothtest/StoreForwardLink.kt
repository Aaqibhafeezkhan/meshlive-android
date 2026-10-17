package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    /** Version 1 is 17 bytes; version 2 (Messaging v2) adds [flags] and [prekeysHeld] (19 bytes). */
    data class Hello(
        val version: Int,
        val freeQuotaBytes: Long,
        val dailyRemainingBytes: Long,
        val digest: ByteArray,
        val flags: Int = 0,
        val prekeysHeld: Int = 0,
    ) : StoreForwardMessage
    class Summary(val entries: List<SummaryEntry>, val last: Boolean) : StoreForwardMessage
    class Want(val bundleId: ByteArray, val needHeader: Boolean, val chunkCount: Int, val missing: BitSet) : StoreForwardMessage
    class Header(val header: BundleHeader, val copyTokens: Int) : StoreForwardMessage
    class Chunk(val bundleId: ByteArray, val index: Int, val ciphertext: ByteArray) : StoreForwardMessage
    class Reply(val bundleId: ByteArray, val reason: StoreForwardRefusal) : StoreForwardMessage
    class DeliveredLocal(val bundleId: ByteArray) : StoreForwardMessage
    data class Done(val reason: StoreForwardDoneReason) : StoreForwardMessage
    class Card(val card: ContactCard) : StoreForwardMessage
    /** Messaging v2: the receiver tells the sender to stop serving a bundle (no reply). */
    class Cancel(val bundleId: ByteArray) : StoreForwardMessage
    /** Messaging v2: keeps a chat session alive; never counts as transfer progress. */
    data object Ping : StoreForwardMessage
}

enum class StoreForwardDoneReason(val wireValue: Int) {
    NORMAL(0),
    /** Live media needs the radio: the link closes now and resumes later from bitmaps. */
    SUSPEND(1),
    TIMEOUT(2),
    ERROR(3),
    /** Responder refuses: this neighbour exchanged too recently or too often (N3). Messaging v2: a budget was exceeded. */
    RATE_LIMITED(4),
    /** Messaging v2: the thread was left (2 minutes) or the chat session reached 30 minutes; the link closes cleanly. */
    IDLE(5),
}

/** HELLO version 2 flags (PROTOCOL "BSF1 version 2"); other bits must be zero. */
object StoreForwardHelloFlags {
    const val CHAT = 1
    const val FILES = 2
    const val PREKEYS = 4
    const val KNOWN = CHAT or FILES or PREKEYS
}

object StoreForwardWire {
    private val MAGIC = "BSF1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    /** HELLO version of a Messaging v2 exchange. */
    const val VERSION_2 = 2
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
    private const val CANCEL = 11
    private const val PING = 12
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
                if (message.version >= VERSION_2) {
                    require(message.flags and StoreForwardHelloFlags.KNOWN.inv() == 0 && message.prekeysHeld in 0..255)
                    out.writeByte(message.flags)
                    out.writeByte(message.prekeysHeld)
                } else {
                    require(message.flags == 0 && message.prekeysHeld == 0) { "HELLO version 1 has no flags" }
                }
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
            is StoreForwardMessage.Cancel -> CANCEL.also { out.write(message.bundleId) }
            StoreForwardMessage.Ping -> PING
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

    /**
     * [v2] opts in to the Messaging v2 forms: a 19-byte HELLO, CANCEL, PING, DONE(IDLE), REFUSE 9 and version 3 headers.
     * With it false (the switch off) every one of them is malformed, exactly as in v1.
     */
    fun decode(record: ByteArray, v2: Boolean = false): StoreForwardMessage {
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
                require(body.size == 17 || (v2 && body.size == 19)) { "Invalid HELLO" }
                val version = input.readUnsignedByte()
                val quota = input.readInt().toLong() and 0xffffffffL
                val daily = input.readInt().toLong() and 0xffffffffL
                val digest = ByteArray(8).also(input::readFully)
                // Version 2 is exactly the 19-byte form; a 17-byte HELLO that claims version 2 is malformed.
                if (v2) require((body.size == 19) == (version >= VERSION_2)) { "HELLO version does not match its length" }
                if (body.size == 19) {
                    val flags = input.readUnsignedByte()
                    val prekeys = input.readUnsignedByte()
                    require(flags and StoreForwardHelloFlags.KNOWN.inv() == 0) { "Unknown HELLO flags" }
                    StoreForwardMessage.Hello(version, quota, daily, digest, flags, prekeys)
                } else {
                    StoreForwardMessage.Hello(version, quota, daily, digest)
                }
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
                StoreForwardMessage.Header(BundleHeader.decode(body.copyOfRange(1, body.size), allowV3 = v2), body[0].toInt() and 0xff)
            }
            CHUNK -> {
                require(body.size in ID + 2 + 1..ID + 2 + StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES) { "Invalid CHUNK" }
                StoreForwardMessage.Chunk(id(), input.readUnsignedShort(), body.copyOfRange(ID + 2, body.size))
            }
            REPLY -> {
                require(body.size == ID + 1) { "Invalid ACCEPT/REFUSE" }
                val bundleId = id()
                val reason = StoreForwardRefusal.fromWire(input.readUnsignedByte())
                require(v2 || reason != StoreForwardRefusal.PREKEY_GONE) { "Unknown store-forward reason ${reason.wireValue}" }
                StoreForwardMessage.Reply(bundleId, reason)
            }
            DELIVERED_LOCAL -> {
                require(body.size == ID) { "Invalid DELIVERED_LOCAL" }
                StoreForwardMessage.DeliveredLocal(id())
            }
            DONE -> {
                require(body.size == 1) { "Invalid DONE" }
                val value = input.readUnsignedByte()
                StoreForwardMessage.Done(
                    StoreForwardDoneReason.entries.firstOrNull { it.wireValue == value && (v2 || it != StoreForwardDoneReason.IDLE) }
                        ?: throw IllegalArgumentException("Unknown DONE reason $value"),
                )
            }
            CARD -> StoreForwardMessage.Card(ContactCard.decode(body))
            CANCEL -> {
                require(v2) { "Unknown store-forward message type $type" }
                require(body.size == ID) { "Invalid CANCEL" }
                StoreForwardMessage.Cancel(id())
            }
            PING -> {
                require(v2) { "Unknown store-forward message type $type" }
                require(body.isEmpty()) { "Invalid PING" }
                StoreForwardMessage.Ping
            }
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
    /** The responder's per-neighbour cooldown/hourly cap refused this exchange (N3), or a control budget ended it. */
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
    /** Messaging v2: the exchange ran as a chat session (both HELLOs carried the CHAT flag). */
    val chat: Boolean = false,
) {
    val opened: List<ArchivedMessage> get() = completed.filterIsInstance<StoreForwardCompletion.Opened>().map { it.message }

    /** Messaging v2 conversation messages stored in this exchange. */
    val v2Messages: List<ThreadMessage> get() = completed.filterIsInstance<StoreForwardCompletion.V2Message>().map { it.message }

    fun summary(): String = "outcome=$outcome sent=$chunksSent/${bytesSent}B received=$chunksReceived/${bytesReceived}B " +
        "opened=${opened.size} completed=${completed.size} dup=$duplicateChunks poisoned=$poisonedChunks" +
        (cardOffer?.let { " card=$it" } ?: "") + (detail?.let { " detail=$it" } ?: "")
}

/** A control budget (M12a) was exceeded: the exchange ends with DONE(rate limited). */
class StoreForwardRateLimited(message: String) : Exception(message)

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
 *
 * Messaging v2 (only when [StoreForwardNode.v2] exists and the peer's HELLO is version 2; otherwise every line of
 * this class behaves exactly as v1): bulk segments the receiver pulls, WANT for an own bundle the SUMMARY did not
 * list, CANCEL, a smallest-first writer, contact admission tiers with physical reservations, control budgets,
 * independent liveness and transfer-progress deadlines, and, when both HELLOs carry the CHAT flag, a chat session
 * that stays open: repeated SUMMARY, PING, and DONE held until [endChat].
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
    /** Phase 0 measurement (`BENCHMARK message_transfer`): the link's transport label, e.g. "GATT". */
    private val transportLabel: String = "",
    /** Milliseconds the caller spent opening and authenticating the link; -1 when unknown. */
    private val setupMs: Long = -1,
    /** App state for the measurement lines (foreground/background, screen, Doze); "" when unknown. */
    private val appState: () -> String = { "" },
    /** Messaging v2: this phone can keep the exchange open (HELLO flag CHAT). Policy (when) is the caller's. */
    private val chatCapable: Boolean = false,
    /** Monotonic milliseconds for the v2 deadlines and budgets; tests substitute a controllable clock. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** One WANT being served: HEADER if asked, each held chunk, then ACCEPT(SENT) or REFUSE(RATE). */
    private inner class ServeJob(val bundle: StoredBundle, needHeader: Boolean, private val missing: BitSet) {
        private var headerPending = needHeader
        private var nextIndex = missing.nextSetBit(0)
        @Volatile var finished = false
            private set
        @Volatile var cancelled = false

        /** Class first, then the bytes still to send (the header counts as one record), for the smallest-first writer. */
        val priorityClass: Int get() = bundle.header.priorityClass
        val remainingChunks: Int get() = if (nextIndex < 0) 0 else missing.get(nextIndex, maxOf(nextIndex, missing.length())).cardinality()

        /** Writes the job's next record; returns false once it has nothing more to write. */
        fun writeNext(): Boolean {
            if (finished || cancelled) return false
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
                val chunkRecord = StoreForwardWire.encode(StoreForwardMessage.Chunk(bundle.header.bundleId, index, ciphertext))
                link.writer.write(SecureRecordType.STORE_FORWARD, chunkRecord)
                link.writer.flush()
                timeline.onWireOut(chunkRecord.size)
                timeline.onChunk(false, bundle.idHex, bundle.header.priorityClass, bundle.chunkCount, ciphertext.size)
                noteWritten()
                deadlines?.chunk()
                chunksSent.incrementAndGet()
                bytesSent.addAndGet(ciphertext.size.toLong())
                node.ledger.add(NeighborLedger.neighborKey(peer), bytesOut = ciphertext.size.toLong())
                afterChunkWritten(chunksSent.get())
                return true
            }
            finish(StoreForwardRefusal.SENT)
            return true
        }

        fun cancel() {
            cancelled = true
            release()
        }

        private fun finish(reason: StoreForwardRefusal) {
            finished = true
            release()
            if (reason == StoreForwardRefusal.SENT) timeline.onBundleComplete(false, bundle.idHex)
            writeMessage(StoreForwardMessage.Reply(bundle.header.bundleId, reason))
        }

        /** In v2 a finished or cancelled job lets the same bundle be WANTed again; in v1 a bundle is served once. */
        private fun release() {
            if (v2Active) served.remove(bundle.idHex)
        }
    }

    private class Wanted(
        val entry: SummaryEntry,
        val role: BundleRole,
        val candidates: Set<NodeId>,
        val outstanding: BitSet,
        var headerReceived: Boolean,
        /** A bulk segment pulled by this phone (not listed in a SUMMARY). */
        val pulled: Boolean = false,
    ) {
        @Volatile var answered = false
        @Volatile var cancelledUntilMs = 0L
    }

    private val peer = link.peerNodeId
    private val limits = node.limits
    private val control = LinkedBlockingQueue<StoreForwardMessage>(3 * limits.maxSummaryEntries + 16)
    private val jobs = CopyOnWriteArrayList<ServeJob>()
    private val suspendRequested = AtomicBoolean(false)
    private val stopWriter = AtomicBoolean(false)
    private val drainThenStop = AtomicBoolean(false)
    private val linkClosed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    @Volatile private var forcedOutcome: StoreForwardSyncOutcome? = null
    /** Monotonic progress clock for the watchdog (N7): never the wall clock. */
    @Volatile private var lastProgressNs = System.nanoTime()
    private val startNs = System.nanoTime()
    private val startMs = monotonicMs()
    private val timeline = TransferTimeline()

    // Messaging v2 negotiation (all false/null in a v1 exchange).
    private val v2Local = node.v2 != null
    private val v2Limits = node.v2?.limits ?: MessagingV2Limits()
    @Volatile private var ourHelloVersion = 1
    @Volatile private var v2Active = false
    @Volatile private var peerFlags = 0
    @Volatile private var chatActive = false
    @Volatile private var idleRequested = false
    @Volatile private var tier: AdmissionTier = node.tierFor(peer, false)
    @Volatile private var deadlines: SessionDeadlines? = null
    private val budget = ControlBudget(v2Limits, monotonicMs)
    private val summaryWindow = WindowCounter(v2Limits.summaryWindowMs, monotonicMs)
    @Volatile private var lastWriteMs = monotonicMs()
    private var lastPingReceivedMs = 0L
    private val doneLock = Any()

    /** This responder passed the cooldown/hourly admission (it holds the neighbour's cooldown until the exchange ends). */
    @Volatile private var admitted = false
    @Volatile private var helloSent = false
    @Volatile private var helloReceived = false
    @Volatile private var peerSummaryDone = false
    private var peerIsCarrier = false
    @Volatile private var doneSent = false
    @Volatile private var doneReceived = false
    @Volatile private var readerThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    /** The reader loop's result; [readOutcome] stays null when the reader never unwinds and the exchange is abandoned. */
    @Volatile private var readOutcome: StoreForwardSyncOutcome? = null
    @Volatile private var readDetail: String? = null
    @Volatile private var readFailure: Throwable? = null
    private val readerDone = java.util.concurrent.CountDownLatch(1)
    /** The watchdog gave up on a reader that ignores close and interrupt: [run] returns without it. */
    private val abandoned = AtomicBoolean(false)
    private val abandonGraceMs: Long get() = maxOf(limits.suspendGraceMs, 100L)
    @Volatile private var wakeReaderAfterDone = false
    @Volatile private var peerDoneReason: StoreForwardDoneReason? = null
    /** The writer hit an I/O error. Applied last, so the peer's own explicit DONE reason is never lost to it. */
    @Volatile private var writerFailed = false
    private val offered = ConcurrentHashMap<String, StoredBundle>()
    private val served: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var unknownWants = 0
    private var summaryEntriesReceived = 0
    private val wants = ConcurrentHashMap<String, Wanted>()
    private val reservedBytes = AtomicLong()
    private var tagTable: Map<String, Set<NodeId>> = emptyMap()
    private var tagGeneration = 0
    private var cardsReceived = 0

    private val chunksSent = AtomicInteger()
    private val bytesSent = AtomicLong()
    private var chunksReceived = 0
    private var bytesReceived = 0L
    private var duplicates = 0
    private var poisoned = 0
    private val completed = java.util.Collections.synchronizedList(ArrayList<StoreForwardCompletion>())
    private var cardOffer: CardOffer? = null
    private val refusals = ConcurrentHashMap<StoreForwardRefusal, Int>()
    private var legacyDetail: String? = null

    /** Live media needs the radio: stop within one chunk (thread-safe, idempotent). */
    fun requestSuspend() {
        suspendRequested.set(true)
    }

    /** CANCEL records acted on so far (test observation: an event to wait for instead of a delay). */
    @Volatile internal var cancelsServed = 0

    /** WANTs accepted into the serve queue so far (test observation, like [cancelsServed]). */
    @Volatile internal var wantsServed = 0

    /** One line of internal state for a test that finds an exchange hung. */
    internal fun debugState(): String =
        "initiator=$initiator hello=$helloSent/$helloReceived summaryDone=$peerSummaryDone done=$doneSent/$doneReceived " +
            "peerDone=$peerDoneReason wants=${wants.size}/unanswered=${wants.values.count { !it.answered }} deferred=${deferred.size} " +
            "jobs=${jobs.count { !it.finished && !it.cancelled }} chat=$chatActive finished=${finished.get()}"

    /** The authenticated adjacent identity of this exchange. */
    val peerId: NodeId get() = peer

    /** True when this phone dialed the exchange (false: it answered). */
    val isInitiator: Boolean get() = initiator

    /** True once both HELLOs were version 2: the exchange speaks Messaging v2. */
    val isV2: Boolean get() = v2Active

    /** True when both phones asked for a chat session and this one stays open until [endChat]. */
    val isChat: Boolean get() = chatActive

    /**
     * Ends a chat session cleanly (the thread was left, or 30 minutes passed): DONE(IDLE) goes out once this phone's WANTs
     * are answered. Thread-safe and idempotent; a no-op outside a chat session.
     */
    fun endChat() {
        if (!chatActive) return
        idleRequested = true
        maybeSendDone()
    }

    /**
     * A new own bundle was queued (a thread message): offered to the peer at once in a chat session, with a SUMMARY on top of
     * the first one (at most 1,024 bundles per 30 minutes). Outside a chat session it simply waits for the next exchange.
     */
    fun offerNewBundles() {
        if (!chatActive || doneSent || idleRequested || finished.get()) return
        val room = limits.maxSummaryEntries - summaryWindow.total()
        if (room <= 0) return
        val fresh = node.offers(peer, peerIsCarrier, v2Active).filter { !offered.containsKey(it.idHex) }.take(room)
        if (fresh.isEmpty()) return
        fresh.forEach { offered[it.idHex] = it }
        summaryWindow.add(fresh.size)
        val entries = fresh.map(node::summaryEntry)
        entries.chunked(StoreForwardWire.MAX_SUMMARY_ENTRIES).forEach { part -> enqueueQuietly(StoreForwardMessage.Summary(part, last = true)) }
    }

    /** An approved download is waiting: ask the peer for its missing segments now. */
    fun pullNow() {
        if (!helloReceived || !v2Active || doneSent || finished.get()) return
        runCatching { sendPulls() }
    }

    /** The user cancelled a download: tell the peer to stop serving [bundleId]; chunks within 5 s after are dropped quietly. */
    fun cancelBundle(bundleId: ByteArray) {
        if (!v2Active || finished.get()) return
        val idHex = bundleId.sfHex()
        val wanted = wants[idHex] ?: return
        if (wanted.answered) return
        wanted.answered = true
        node.v2?.release(idHex)
        wanted.cancelledUntilMs = monotonicMs() + v2Limits.cancelGraceMs
        enqueueQuietly(StoreForwardMessage.Cancel(bundleId))
        maybeSendDone()
    }

    /**
     * Runs the exchange to its end. Never throws for a failed exchange: failures are reported in the result.
     *
     * The protocol loop runs on a dedicated reader thread and this thread only waits for it. A link whose read ignores both
     * close and interrupt (a GATT-server link whose peer vanished) can therefore never pin this call: the watchdog force-finishes
     * the session ([forceFinish]) and this method returns, so the endpoint and the node are free for the next exchange. An
     * interrupt of the calling thread (the endpoint closing) is handled the same way, and every cleanup step below always runs.
     */
    fun run(): StoreForwardSyncResult {
        node.registerSession(this)
        val writer = thread(name = "StoreForwardWriter", isDaemon = true) { writerLoop() }
        writerThread = writer
        val watchdog = thread(name = "StoreForwardWatchdog", isDaemon = true) { watchdogLoop() }
        val appStateStart = runCatching(appState).getOrDefault("")
        var interrupted = false
        var outcome = StoreForwardSyncOutcome.INTERRUPTED
        var detail: String? = null
        try {
            thread(name = "StoreForwardReader", isDaemon = true) { readerMain() }
            interrupted = awaitReader()
            val readerOutcome = readOutcome
            if (readerOutcome != null) {
                outcome = readerOutcome
                detail = readDetail
            } else {
                // The reader never unwound (a link that ignores close and interrupt): the exchange is abandoned.
                outcome = forcedOutcome ?: StoreForwardSyncOutcome.INTERRUPTED
                detail = if (interrupted) "interrupted" else "the link ignored close"
            }
            if (detail == null) detail = legacyDetail
        } finally {
            // Always: whatever ended the exchange, the session leaves the node, releases its disk reservations and stops its helpers.
            node.unregisterSession(this)
            node.v2?.let { engine -> wants.keys.forEach(engine::release) }
        }
        val isComplete = outcome == StoreForwardSyncOutcome.COMPLETE
        try {
            if (isComplete) drainThenStop.set(true) else stopWriter.set(true)
            interrupted = joinWriter(writer, limits.progressTimeoutMs + 1_000) || interrupted
            if (writer.isAlive) {
                // Blocked in a write the peer never drains: closing the link unblocks it.
                closeOnce()
                interrupted = joinWriter(writer, 1_000) || interrupted
            }
        } finally {
            stopWriter.set(true)
            finished.set(true)
            watchdog.interrupt()
        }
        runCatching { node.store.flushAll() }
        interrupted = joinQuietly(watchdog, 1_000) || interrupted
        if (isComplete) {
            runCatching {
                node.ledger.recordSync(NeighborLedger.neighborKey(peer), node.inventoryDigest(offered.values.toList()))
            }
        }
        val result = StoreForwardSyncResult(
            peer, forcedOutcome ?: outcome, chunksSent.get(), chunksReceived, duplicates, poisoned,
            bytesSent.get(), bytesReceived, completed.toList(), cardOffer, refusals.toMap(), detail, chat = chatActive,
        )
        if (admitted && cooldownRefundable(result)) runCatching { node.refundExchangeCooldown(peer) }
        log("BENCHMARK store_forward session peer=${peer.shortLabel()} initiator=$initiator ${result.summary()}")
        runCatching {
            val appStateEnd = runCatching(appState).getOrDefault("")
            timeline.bundleLines(transportLabel).forEach(log)
            log(
                timeline.sessionLine(
                    "peer=${peer.shortLabel()} initiator=$initiator proto=${if (v2Active) 2 else 1} chat=$chatActive " +
                        "outcome=${result.outcome} opened=${result.opened.size + result.v2Messages.size} " +
                        "completed=${result.completed.size} dup=${result.duplicateChunks} poisoned=${result.poisonedChunks}",
                    setupMs, transportLabel, "$appStateStart->$appStateEnd",
                ),
            )
        }
        // The caller's thread was interrupted while this exchange wound down: pass that on now that the cleanup is done.
        if (interrupted) Thread.currentThread().interrupt()
        readFailure?.let { throw it }
        return result
    }
    /**
     * An admitted exchange that was cut by the link (INTERRUPTED or TIMEOUT) before anything moved: no bundle chunk either way,
     * nothing opened. Its retry is not the neighbour's fault, so it does not wait out the cooldown ([NeighborLedger.refundCooldown]).
     * A finished, refused, suspended or protocol-failed exchange keeps the spacing, and so does one that moved data.
     */
    private fun cooldownRefundable(result: StoreForwardSyncResult): Boolean =
        (result.outcome == StoreForwardSyncOutcome.INTERRUPTED || result.outcome == StoreForwardSyncOutcome.TIMEOUT) &&
            result.chunksSent == 0 && result.chunksReceived == 0 && result.completed.isEmpty()

    /** The protocol loop, on its own thread: reports its outcome through [readOutcome] and always releases [run]. */
    private fun readerMain() {
        readerThread = Thread.currentThread()
        try {
            val (outcome, detail) = exchangeLoop()
            readDetail = detail
            readOutcome = outcome
        } catch (error: Throwable) {
            readFailure = error
        } finally {
            readerDone.countDown()
        }
    }

    /** Reads and answers records until both DONEs crossed or the link failed; returns the outcome and its detail. */
    private fun exchangeLoop(): Pair<StoreForwardSyncOutcome, String?> {
        var outcome: StoreForwardSyncOutcome
        var detail: String? = null
        try {
            node.sweep()
            tagTable = node.tagTable()
            tagGeneration = node.contacts.generation
            // A Messaging v2 responder applies its cooldown after it has read the peer's HELLO (M6): an accepted contact that
            // offers a chat session gets 5 s and 60 per hour. Everything else, and every v1 responder, checks first.
            if (!initiator && !v2Local && !node.admitExchange(peer)) {
                forcedOutcome = StoreForwardSyncOutcome.RATE_LIMITED
                failWith(StoreForwardDoneReason.RATE_LIMITED)
                throw StoreForwardProtocolException("Exchange refused: this neighbour exchanged too recently")
            }
            if (!initiator && !v2Local) admitted = true
            if (initiator) sendHello()
            firstRecord?.let(::handleRecord)
            while (!(doneSent && doneReceived) && peerDoneReason.let {
                    it == null || it == StoreForwardDoneReason.NORMAL || it == StoreForwardDoneReason.IDLE
                }
            ) {
                val record = link.reader.read()
                // A read that finally returned after the watchdog gave up on this exchange must not act on the dead session.
                if (abandoned.get()) throw java.io.IOException("Exchange abandoned")
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
            outcome = forcedOutcome ?: if (wakeReaderAfterDone && doneSent && doneReceived && !writerFailed) {
                // Both DONEs crossed; the writer closed the link only to release the blocked reader.
                StoreForwardSyncOutcome.COMPLETE
            } else if (error is StoreForwardRateLimited && !writerFailed) {
                failWith(StoreForwardDoneReason.RATE_LIMITED)
                StoreForwardSyncOutcome.RATE_LIMITED
            } else if (error is StoreForwardProtocolException && !writerFailed) {
                failWith(StoreForwardDoneReason.ERROR)
                StoreForwardSyncOutcome.FAILED
            } else {
                StoreForwardSyncOutcome.INTERRUPTED
            }
            if (outcome != StoreForwardSyncOutcome.COMPLETE) detail = error.message ?: error.javaClass.simpleName
        }
        return outcome to detail
    }

    /**
     * Waits for the reader thread. Returns true when this thread was interrupted meanwhile (the endpoint closing): the link is
     * then closed at once and the reader gets the grace period to unwind before the exchange is abandoned.
     */
    private fun awaitReader(): Boolean {
        var interrupted = false
        var giveUpNs = 0L
        while (true) {
            try {
                if (readerDone.await(POLL_MS, TimeUnit.MILLISECONDS)) return interrupted
            } catch (_: InterruptedException) {
                if (!interrupted) {
                    interrupted = true
                    forcedOutcome = forcedOutcome ?: StoreForwardSyncOutcome.INTERRUPTED
                    stopWriter.set(true)
                    closeOnce()
                    giveUpNs = System.nanoTime() + abandonGraceMs * 1_000_000
                }
            }
            if (abandoned.get()) return interrupted
            if (giveUpNs != 0L && System.nanoTime() >= giveUpNs) {
                readerThread?.interrupt()
                abandoned.set(true)
                return interrupted
            }
        }
    }

    /** Joins without letting an interrupt skip the rest of the cleanup; true when this thread was interrupted. */
    private fun joinQuietly(thread: Thread, timeoutMs: Long): Boolean {
        try {
            thread.join(timeoutMs)
            return false
        } catch (_: InterruptedException) {
            closeOnce()
            return true
        }
    }

    /** After an abandon nothing waits for a writer that is stuck inside the link. */
    private fun joinWriter(writer: Thread, timeoutMs: Long): Boolean =
        if (abandoned.get()) false else joinQuietly(writer, timeoutMs)

    /**
     * The first deadline (or an unanswered suspend) ends the session for good: close the link, give the reader the grace
     * period to unwind, then interrupt reader and writer, and if the link still ignores both, abandon them. [run] then returns,
     * the node forgets the session and the endpoint is free for the next exchange. Runs once, on the watchdog thread.
     */
    private fun forceFinish() {
        stopWriter.set(true)
        closeOnce()
        if (readerDone.await(abandonGraceMs, TimeUnit.MILLISECONDS)) return
        readerThread?.interrupt()
        writerThread?.interrupt()
        if (readerDone.await(abandonGraceMs, TimeUnit.MILLISECONDS)) return
        runCatching { log("BENCHMARK message_v2 session abandoned peer=${peer.shortLabel()} reason=link_ignored_close") }
        abandoned.set(true)
    }

    private fun handleRecord(record: SecureRecord) {
        lastProgressNs = System.nanoTime()
        deadlines?.inbound()
        timeline.onWireIn(record.payload.size)
        if (record.type != SecureRecordType.STORE_FORWARD) {
            throw StoreForwardProtocolException("Unexpected record ${record.type} in a store-forward session")
        }
        val message = try {
            StoreForwardWire.decode(record.payload, v2Local)
        } catch (error: IllegalArgumentException) {
            throw StoreForwardProtocolException(error.message ?: "Malformed store-forward message")
        }
        if (v2Active) enforceControlBudget(message)
        when (message) {
            is StoreForwardMessage.Hello -> onHello(message)
            is StoreForwardMessage.Card -> {
                if (v2Active && ++cardsReceived > v2Limits.maxCardsPerExchange) throw StoreForwardProtocolException("Too many CARD records")
                if (v2Active && !budget.asymmetric(tier.contact)) throw StoreForwardRateLimited("Too many signature checks for a non-contact")
                val offer = node.onCardFromLink(message.card, peer)
                cardOffer = offer
                log("Store-forward card from ${peer.shortLabel()}: $offer")
                if (node.v2 != null) onCardOutcome(offer)
            }
            is StoreForwardMessage.Summary -> onSummary(message)
            is StoreForwardMessage.Want -> onWant(message)
            is StoreForwardMessage.Header -> onHeader(message)
            is StoreForwardMessage.Chunk -> onChunk(message)
            is StoreForwardMessage.Reply -> onReply(message)
            is StoreForwardMessage.DeliveredLocal -> node.onDeliveredLocal(message.bundleId, peer)
            is StoreForwardMessage.Done -> {
                // A v1 peer answers a v2 HELLO with DONE(error): it is older, and is not tried as v2 again for an hour.
                if (ourHelloVersion == 2 && !helloReceived && message.reason == StoreForwardDoneReason.ERROR) {
                    node.v2?.markLegacy(peer)
                    legacyDetail = "peer does not speak Messaging v2"
                }
                doneReceived = true
                peerDoneReason = message.reason
                if (message.reason == StoreForwardDoneReason.IDLE) {
                    if (!chatActive) throw StoreForwardProtocolException("DONE(IDLE) outside a chat session")
                    // The peer ended the session (its thread was left): this side logs its own end line, as the phone that
                    // ends it does from the chat tick, so both logs show the end. Not logged when this phone asked first.
                    if (!idleRequested) runCatching { log("BENCHMARK message_v2 chat end peer=${peer.shortLabel()} why=peer_ended") }
                    idleRequested = true
                }
            }
            is StoreForwardMessage.Cancel -> onCancel(message)
            StoreForwardMessage.Ping -> onPing()
        }
        maybeSendDone()
    }

    /** Inbound control records are budgeted (M12a); data (CHUNK, HEADER) and the handshake are not. */
    private fun enforceControlBudget(message: StoreForwardMessage) {
        val counted = when (message) {
            is StoreForwardMessage.Summary, is StoreForwardMessage.Want, is StoreForwardMessage.Cancel,
            StoreForwardMessage.Ping, is StoreForwardMessage.Reply, is StoreForwardMessage.DeliveredLocal -> true
            else -> false
        }
        if (counted && !budget.control()) throw StoreForwardRateLimited("Too many control records")
    }

    private fun sendHello() {
        if (helloSent) return
        helloSent = true
        // The initiator speaks v2 unless this peer recently answered a v2 HELLO with DONE(error); a responder follows the peer.
        val version = when {
            !initiator -> ourHelloVersion
            v2Local && node.v2?.isLegacy(peer) != true -> StoreForwardWire.VERSION_2
            else -> StoreForwardWire.VERSION
        }
        ourHelloVersion = version
        val offers = node.offers(peer, peerIsCarrier = false, v2Mode = version == 2)
        var flags = 0
        if (version == 2) {
            flags = StoreForwardHelloFlags.FILES
            if (chatCapable) flags = flags or StoreForwardHelloFlags.CHAT
        }
        enqueue(StoreForwardMessage.Hello(version, node.freeForeignBytes(), node.dailyRemainingBytes(peer),
            node.inventoryDigest(offers), flags))
        runCatching { node.ownCard }.onSuccess { enqueue(StoreForwardMessage.Card(it)) }
    }

    private fun onHello(hello: StoreForwardMessage.Hello) {
        if (helloReceived) throw StoreForwardProtocolException("Repeated HELLO")
        if (hello.version != StoreForwardWire.VERSION && !(v2Local && hello.version == StoreForwardWire.VERSION_2)) {
            throw StoreForwardProtocolException("Unsupported store-forward version ${hello.version}")
        }
        if (!initiator && v2Local) {
            // The cooldown is applied here, after the HELLO, so a contact that offers a chat session can be admitted sooner (M6).
            val contactChat = hello.version == StoreForwardWire.VERSION_2 && hello.flags and StoreForwardHelloFlags.CHAT != 0 &&
                node.v2?.isContact(peer) == true
            if (!node.admitExchangeAfterHello(peer, contactChat)) {
                forcedOutcome = StoreForwardSyncOutcome.RATE_LIMITED
                failWith(StoreForwardDoneReason.RATE_LIMITED)
                throw StoreForwardProtocolException("Exchange refused: this neighbour exchanged too recently")
            }
            admitted = true
        }
        if (!helloSent) {
            // Responder: speak the peer's version when this phone can.
            ourHelloVersion = if (v2Local && hello.version == StoreForwardWire.VERSION_2) StoreForwardWire.VERSION_2 else StoreForwardWire.VERSION
        } else if (hello.version != ourHelloVersion) {
            throw StoreForwardProtocolException("Store-forward version mismatch")
        }
        helloReceived = true
        timeline.onHelloReceived()
        peerIsCarrier = hello.freeQuotaBytes > 0
        if (hello.version == StoreForwardWire.VERSION_2) {
            v2Active = true
            peerFlags = hello.flags
            chatActive = chatCapable && hello.flags and StoreForwardHelloFlags.CHAT != 0
            tier = node.tierFor(peer, true)
            deadlines = SessionDeadlines(chatActive, limits, v2Limits, monotonicMs).also { it.inbound() }
        }
        sendHello()
        if (v2Active) {
            // Receipts waiting for this peer travel with this exchange.
            runCatching { node.v2?.flushReceipts(peer, force = true) }
        }
        val offers = node.offers(peer, peerIsCarrier, v2Active).take(limits.maxSummaryEntries)
        offers.forEach { offered[it.idHex] = it }
        if (chatActive) summaryWindow.add(offers.size)
        val entries = offers.map(node::summaryEntry)
        if (entries.isEmpty()) {
            enqueue(StoreForwardMessage.Summary(emptyList(), last = true))
        } else {
            entries.chunked(StoreForwardWire.MAX_SUMMARY_ENTRIES).forEachIndexed { index, part ->
                enqueue(StoreForwardMessage.Summary(part, last = (index + 1) * StoreForwardWire.MAX_SUMMARY_ENTRIES >= entries.size))
            }
        }
        if (v2Active) sendPulls()
    }

    private fun onSummary(summary: StoreForwardMessage.Summary) {
        if (!helloReceived) throw StoreForwardProtocolException("Unexpected SUMMARY")
        refreshContactsIfChanged()
        if (peerSummaryDone && !chatActive) throw StoreForwardProtocolException("Unexpected SUMMARY")
        // A SUMMARY that arrives after this phone sent DONE(IDLE) is ignored: its bundles wait for the next exchange.
        if (chatActive && doneSent) return
        if (chatActive) {
            // 1,024 bundles offered in any window of 30 minutes (not per exchange, which would end a chat session).
            peerSummaryWindow.add(summary.entries.size)
            if (peerSummaryWindow.total() > limits.maxSummaryEntries) throw StoreForwardProtocolException("Too many SUMMARY entries")
        } else {
            summaryEntriesReceived += summary.entries.size
            if (summaryEntriesReceived > limits.maxSummaryEntries) throw StoreForwardProtocolException("Too many SUMMARY entries")
        }
        val ordered = summary.entries.sortedWith(compareBy({ it.priorityClass }, { it.chunkCount }, { it.expiryHour }))
        for (entry in ordered) {
            if (wants.containsKey(entry.idHex)) continue
            // At most 8 bundles are in flight at once (M12a): the rest wait here and are requested as slots free up.
            if (v2Active && deferred.isNotEmpty() || v2Active && inFlightFull()) {
                deferred += entry
                continue
            }
            admit(entry)
        }
        if (summary.last) peerSummaryDone = true
    }

    /** Entries from a SUMMARY that wait for an in-flight slot (Messaging v2 only; reader thread). */
    private val deferred = ArrayList<SummaryEntry>()

    private fun inFlightFull(): Boolean {
        val engine = node.v2 ?: return false
        return engine.reservationCount() >= v2Limits.maxInFlightBundles
    }

    /**
     * A card was received over this link. An accepted one (the identity is verified in person) changes who this phone
     * recognises tags of and what this peer may do, so the tag table and the admission tier are rebuilt before the SUMMARY
     * that follows the card is read; bundles that waited for the card are opened.
     */
    private fun onCardOutcome(offer: CardOffer) {
        if (offer != CardOffer.AUTO_ACCEPTED) return
        refreshContactsIfChanged()
        completed += node.openPendingFrom(peer).filter { it is StoreForwardCompletion.V2Message || it is StoreForwardCompletion.Opened }
            .onEach { node.notifyCompletion(peer, it) }
    }

    /** Messaging v2: a contact was accepted, changed or removed mid-session (a card, the Contacts tab): rebuild what depends on it. */
    private fun refreshContactsIfChanged() {
        if (node.v2 == null) return
        val generation = node.contacts.generation
        if (generation == tagGeneration) return
        tagGeneration = generation
        tagTable = node.tagTable()
        if (v2Active) tier = node.tierFor(peer, true)
    }

    private fun admit(entry: SummaryEntry) {
        refreshContactsIfChanged()
        when (val decision = node.decide(entry, peer, reservedBytes.get(), tagTable, tier)) {
            is StoreForwardAdmission.Want -> {
                reservedBytes.addAndGet(decision.missing.cardinality().toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES)
                if (!decision.needHeader) timeline.resumedChunks += entry.chunkCount - decision.missing.cardinality()
                wants[entry.idHex] = Wanted(entry, decision.role, decision.candidates.ifEmpty { tagTable[entry.tag.sfHex()].orEmpty() },
                    decision.missing.clone() as BitSet, headerReceived = !decision.needHeader)
                enqueue(StoreForwardMessage.Want(entry.bundleId, decision.needHeader, entry.chunkCount, decision.missing))
            }
            is StoreForwardAdmission.AlreadyDelivered -> enqueue(StoreForwardMessage.DeliveredLocal(decision.bundleId))
            is StoreForwardAdmission.Skip -> refusals.merge(decision.reason, 1, Int::plus)
        }
    }

    /** A slot freed up: request the next deferred entries in order. */
    private fun drainDeferred() {
        while (deferred.isNotEmpty() && !inFlightFull()) {
            val entry = deferred.removeAt(0)
            if (!wants.containsKey(entry.idHex)) admit(entry)
        }
    }

    private val peerSummaryWindow = WindowCounter(v2Limits.summaryWindowMs, monotonicMs)

    private val pullLock = Any()

    /** Sends WANTs for the missing segments of approved downloads from this peer (receiver pull, v2 only). */
    private fun sendPulls() {
        synchronized(pullLock) { sendPullsLocked() }
    }

    private fun sendPullsLocked() {
        val engine = node.v2 ?: return
        if (peerFlags and StoreForwardHelloFlags.FILES == 0) return
        for (request in engine.pendingPulls(peer)) {
            val entry = request.entry
            val idHex = entry.idHex
            // A segment the user cancelled in this exchange is asked for again when the download is approved again;
            // any other earlier request (finished or refused) is not repeated within the exchange.
            val earlier = wants[idHex]
            if (earlier != null && !(earlier.answered && earlier.cancelledUntilMs != 0L)) continue
            when (val decision = node.decidePull(entry, peer, reservedBytes.get(), tier)) {
                is StoreForwardAdmission.Want -> {
                    reservedBytes.addAndGet(decision.missing.cardinality().toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES)
                    if (!decision.needHeader) timeline.resumedChunks += entry.chunkCount - decision.missing.cardinality()
                    wants[idHex] = Wanted(entry, decision.role, decision.candidates, decision.missing.clone() as BitSet,
                        headerReceived = !decision.needHeader, pulled = true)
                    if (!budget.want(idHex)) throw StoreForwardRateLimited("Too many WANTs for one bundle")
                    enqueue(StoreForwardMessage.Want(entry.bundleId, decision.needHeader, entry.chunkCount, decision.missing))
                }
                is StoreForwardAdmission.AlreadyDelivered -> Unit
                is StoreForwardAdmission.Skip -> refusals.merge(decision.reason, 1, Int::plus)
            }
        }
    }

    private fun onWant(want: StoreForwardMessage.Want) {
        val idHex = want.bundleId.sfHex()
        if (node.v2 != null && node.contacts.get(peer)?.keysChanged == true) {
            // M1: after a key change nothing is sent to this contact until the user compares again, not even what was offered
            // before its newer card arrived.
            enqueue(StoreForwardMessage.Reply(want.bundleId, StoreForwardRefusal.UNKNOWN))
            return
        }
        var bundle = offered[idHex]?.let { node.store.get(idHex) }
        if (bundle == null && v2Active) {
            // An unlisted WANT is honoured only for one of this phone's own bundles addressed to this very peer.
            bundle = node.store.get(idHex)?.takeIf {
                it.role == BundleRole.OWN && it.peerNode == peer.toString() && it.complete &&
                    (it.state == BundleState.QUEUED || it.state == BundleState.HANDED)
            }
        }
        if (bundle == null || bundle.chunkCount != want.chunkCount) {
            if (++unknownWants > limits.maxSummaryEntries) throw StoreForwardProtocolException("Too many unknown WANTs")
            enqueue(StoreForwardMessage.Reply(want.bundleId, StoreForwardRefusal.UNKNOWN))
            return
        }
        if (v2Active && !budget.want(idHex)) throw StoreForwardRateLimited("Too many WANTs for one bundle")
        if (!served.add(idHex)) throw StoreForwardProtocolException("Repeated WANT")
        if (jobs.count { !it.finished && !it.cancelled } >= limits.maxSummaryEntries) {
            throw StoreForwardProtocolException("Too many WANTs")
        }
        jobs.add(ServeJob(bundle, want.needHeader, want.missing))
        wantsServed++
    }

    private fun onCancel(message: StoreForwardMessage.Cancel) {
        if (!v2Active) throw StoreForwardProtocolException("Unexpected CANCEL")
        if (!budget.cancel()) throw StoreForwardRateLimited("Too many CANCELs")
        val idHex = message.bundleId.sfHex()
        // The sender abandons the job before the next chunk and answers nothing; an unknown CANCEL is a race, not an error.
        jobs.firstOrNull { it.bundle.idHex == idHex && !it.finished && !it.cancelled }?.cancel()
        cancelsServed++
    }

    private fun onPing() {
        if (!chatActive) throw StoreForwardProtocolException("Unexpected PING")
        val now = monotonicMs()
        if (lastPingReceivedMs != 0L && now - lastPingReceivedMs < v2Limits.pingMinGapMs) {
            throw StoreForwardProtocolException("PING more often than every ${v2Limits.pingMinGapMs / 1000} s")
        }
        lastPingReceivedMs = now
        log("BENCHMARK message_v2 chat ping_in peer=${peer.shortLabel()}")
    }

    private fun onHeader(message: StoreForwardMessage.Header) {
        val idHex = message.header.bundleId.sfHex()
        if (message.header.version == StoreForwardFormat.BUNDLE_VERSION_V3 && !v2Active) {
            throw StoreForwardProtocolException("Version 3 bundle in a version 1 exchange")
        }
        val wanted = wants[idHex] ?: throw StoreForwardProtocolException("Unrequested HEADER")
        if (wanted.headerReceived || wanted.answered) throw StoreForwardProtocolException("Repeated HEADER")
        try {
            if (v2Active && !message.header.isBulk) {
                // Opening a key slot is an asymmetric operation: bounded per exchange for a phone that is not a contact.
                if (!budget.asymmetric(tier.contact)) throw StoreForwardRateLimited("Too many slot opens for a non-contact")
            }
            node.beginReceive(message.header, wanted.entry, wanted.role, peer, wanted.candidates, tier)
            wanted.headerReceived = true
        } catch (error: StoreForwardRateLimited) {
            throw error
        } catch (error: IllegalArgumentException) {
            val reason = (error as? StoreForwardRefused)?.reason ?: StoreForwardRefusal.INVALID
            log("Store-forward header refused ($reason): ${error.message}")
            if (v2Active && !message.header.isBulk && !budget.asymmetric(tier.contact, 2)) {
                throw StoreForwardRateLimited("Too many failed slot opens for a non-contact")
            }
            wanted.answered = true
            node.v2?.release(idHex)
            refusals.merge(reason, 1, Int::plus)
            enqueue(StoreForwardMessage.Reply(message.header.bundleId, reason))
        }
    }

    private fun onChunk(message: StoreForwardMessage.Chunk) {
        val idHex = message.bundleId.sfHex()
        val wanted = wants[idHex]
        if (wanted != null && wanted.cancelledUntilMs != 0L && monotonicMs() < wanted.cancelledUntilMs) {
            // Chunks in flight when the CANCEL went out: dropped without counting as poisoned or duplicate.
            return
        }
        if (wanted == null || !wanted.headerReceived || wanted.answered || !wanted.outstanding.get(message.index)) {
            // Unrequested or after a refused header: ignore without storing, but bound abuse.
            if (++duplicates > StoreForwardFormat.MAX_CHUNKS * 4) throw StoreForwardProtocolException("Too many unrequested chunks")
            return
        }
        when (node.store.writeChunk(idHex, message.index, message.ciphertext)) {
            ChunkWriteResult.ACCEPTED -> {
                chunksReceived++
                timeline.onChunk(true, idHex, wanted.entry.priorityClass, wanted.entry.chunkCount, message.ciphertext.size)
                deadlines?.chunk()
                bytesReceived += message.ciphertext.size
                node.ledger.add(NeighborLedger.neighborKey(peer), bytesIn = message.ciphertext.size.toLong())
                if (v2Active) node.v2?.onBytesReceived(peer, message.ciphertext.size.toLong())
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
            timeline.onBundleComplete(true, idHex)
            val completion = node.onComplete(stored, peer)
            completed += completion
            log("Store-forward bundle ${idHex.take(16)} complete from ${peer.shortLabel()}: $completion")
            if (completion is StoreForwardCompletion.V2Message && chatActive) {
                // T-M7: arrival time of a message in a chat session, to be compared with the sender's queue time.
                log("BENCHMARK message_v2 chat_recv peer=${peer.shortLabel()} id=${completion.message.messageIdHex.take(16)} " +
                    "createdMs=${completion.message.createdMs} arrivalMs=${completion.message.arrivalMs}")
            }
            node.notifyCompletion(peer, completion)
            // Deferred (not stored durably) and Rejected are never acknowledged.
            val acknowledged = completion is StoreForwardCompletion.Opened || completion is StoreForwardCompletion.ReceiptOpened ||
                completion == StoreForwardCompletion.PendingContact || completion is StoreForwardCompletion.V2Message ||
                completion == StoreForwardCompletion.V2Duplicate || completion is StoreForwardCompletion.V2Receipts ||
                completion is StoreForwardCompletion.V2Segment
            if (acknowledged) enqueue(StoreForwardMessage.DeliveredLocal(stored.header.bundleId))
            if (v2Active && completion is StoreForwardCompletion.Rejected) {
                refusals.merge(StoreForwardRefusal.INVALID, 1, Int::plus)
                enqueue(StoreForwardMessage.Reply(stored.header.bundleId, StoreForwardRefusal.INVALID))
            }
            // A finished segment frees a slot: the next missing ones are requested within this exchange.
            if (v2Active && completion is StoreForwardCompletion.V2Segment && !completion.fileComplete) sendPulls()
            if (v2Active) drainDeferred()
        }
    }

    private fun onReply(message: StoreForwardMessage.Reply) {
        val wanted = wants[message.bundleId.sfHex()] ?: return
        wanted.answered = true
        node.v2?.release(message.bundleId.sfHex())
        if (message.reason != StoreForwardRefusal.SENT) refusals.merge(message.reason, 1, Int::plus)
        if (v2Active) drainDeferred()
        // The sender no longer has a segment this phone pulled: "No longer available".
        if (v2Active && wanted.pulled && message.reason == StoreForwardRefusal.UNKNOWN) {
            runCatching { node.v2?.onSegmentUnavailable(message.bundleId.sfHex(), peer) }
        }
    }

    private fun maybeSendDone() {
        synchronized(doneLock) {
            if (doneSent || !helloReceived || !peerSummaryDone) return
            if (wants.values.any { !it.answered } || deferred.isNotEmpty()) return
            // A chat session stays open: DONE is held until the session ends (endChat or the peer's DONE(IDLE)).
            if (chatActive && !idleRequested) return
            // Our DONE was decided on another thread (a cancel, endChat) after the peer's DONE had already been read: the reader
            // is blocked waiting for a record that will never come. The writer closes the link once our DONE is flushed.
            if (doneReceived && Thread.currentThread() !== readerThread) wakeReaderAfterDone = true
            doneSent = true
            enqueueQuietly(StoreForwardMessage.Done(if (chatActive) StoreForwardDoneReason.IDLE else StoreForwardDoneReason.NORMAL))
        }
    }

    private fun enqueue(message: StoreForwardMessage) {
        if (!control.offer(message)) throw StoreForwardProtocolException("Outbound queue full")
    }

    /** From threads other than the reader: a full queue just drops the optional message. */
    private fun enqueueQuietly(message: StoreForwardMessage) {
        control.offer(message)
    }

    /** Serving-side quotas: this exchange and this neighbour's UTC day (finding 2); the contact tier in a v2 exchange. */
    private fun outgoingLimitReached(): Boolean =
        bytesSent.get() + StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES > tier.sessionBytesOut ||
            node.ledger.today(NeighborLedger.neighborKey(peer)).bytesOut + StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES >
            tier.dailyBytesOut

    private fun noteWritten() {
        lastWriteMs = monotonicMs()
        deadlines?.outbound()
    }

    private fun writeMessage(message: StoreForwardMessage) {
        val record = StoreForwardWire.encode(message)
        link.writer.write(SecureRecordType.STORE_FORWARD, record)
        timeline.onWireOut(record.size)
        noteWritten()
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

    /**
     * The next job to serve. v1: first in, first out, one to completion. v2: re-evaluated at every chunk, smallest class
     * first and, within a class, fewest chunks left, so a text jumps ahead of the next chunk of a segment.
     */
    private fun nextJob(): ServeJob? {
        jobs.removeAll { it.finished || it.cancelled }
        if (!v2Active) return jobs.firstOrNull()
        return jobs.minWithOrNull(compareBy<ServeJob>({ it.priorityClass }, { it.remainingChunks }))
    }

    private fun writerLoop() {
        var dirty = false
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
                val job = nextJob()
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
                    if (wakeReaderAfterDone && doneSent && doneReceived) {
                        closeOnce()
                        return
                    }
                }
                // The reader sets drainThenStop only after its last enqueue, so once it is seen the
                // queues are final: stop only if they are still empty (else drain the late item).
                if (drainThenStop.get()) {
                    if (control.isEmpty() && jobs.none { !it.finished && !it.cancelled }) return
                    continue
                }
                if (chatActive && !doneSent && monotonicMs() - lastWriteMs >= v2Limits.pingIntervalMs) {
                    // Nothing written for 20 s in a chat session: a PING keeps the peer's liveness deadline from firing.
                    writeMessage(StoreForwardMessage.Ping)
                    log("BENCHMARK message_v2 chat ping_out peer=${peer.shortLabel()}")
                    dirty = true
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
     * timeouts or a suspend: closing the link unblocks both the reader and the writer. The first expiry or
     * unanswered suspend calls [forceFinish] and ends this loop.
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
                        forceFinish()
                        return
                    }
                }
                val now = System.nanoTime()
                val active = deadlines
                val expired = if (active != null) {
                    // Messaging v2: liveness (any record; a PING counts) and transfer progress (a chunk must move) are separate.
                    active.setTransferActive(wants.values.any { !it.answered } || jobs.any { !it.finished && !it.cancelled })
                    if (chatActive && !idleRequested && monotonicMs() - startMs > v2Limits.chatMaxMs) endChat()
                    active.expired() != null
                } else {
                    (now - lastProgressNs) / 1_000_000 > limits.progressTimeoutMs || (now - startNs) / 1_000_000 > limits.sessionTimeoutMs
                }
                if (expired) {
                    if (active != null) {
                        // Which deadline ended it and how long each clock had been still, so a hung session can be told apart.
                        runCatching { log("BENCHMARK message_v2 deadline peer=${peer.shortLabel()} reason=${active.expired()} ${active.describe()} state=${debugState()}") }
                    }
                    forcedOutcome = forcedOutcome ?: StoreForwardSyncOutcome.TIMEOUT
                    // The first deadline ends the session for good: logged once, then this loop is over (a link that ignores
                    // close used to leave the session unfinished and this loop re-logging the same deadline every 50 ms).
                    forceFinish()
                    return
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
