package com.example.bluetoothtest

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Measurement of one store-and-forward exchange (DECISIONS Design M phase 0): the `BENCHMARK
 * message_transfer` lines carry bytes, chunks and timings per exchange, per transport and per bundle,
 * plus the app state, so a phone lane can compute goodput and background reception from the log alone.
 * Nothing here touches the wire or any decision; with the log sink unused it only keeps a few counters.
 *
 * All times are milliseconds since the exchange started (monotonic clock). `setupMs` is the time the
 * caller spent opening and authenticating the link before the exchange began (-1 when unknown).
 */
class TransferTimeline(private val nowNs: () -> Long = System::nanoTime) {
    /** First and last CHUNK record in one direction. */
    class Direction {
        @Volatile var firstNs = 0L
        @Volatile var lastNs = 0L
        val chunks = java.util.concurrent.atomic.AtomicInteger()
        val bytes = AtomicLong()
        /** Time and bytes of the stretches in which chunks followed each other less than [ACTIVE_GAP_NS] apart. */
        @Volatile var activeNs = 0L
            private set
        @Volatile var activeBytes = 0L
            private set

        fun record(now: Long, ciphertextBytes: Int) {
            if (chunks.getAndIncrement() == 0) {
                firstNs = now
            } else if (now - lastNs <= ACTIVE_GAP_NS) {
                activeNs += now - lastNs
                activeBytes += ciphertextBytes
            }
            lastNs = now
            bytes.addAndGet(ciphertextBytes.toLong())
        }
    }

    /** Per bundle, per direction. */
    class BundleTiming(val idHex: String, val priorityClass: Int, val chunkCount: Int, val incoming: Boolean) {
        var firstNs = 0L
        var lastNs = 0L
        var chunks = 0
        var bytes = 0L
        var complete = false
    }

    companion object {
        /** Chunks further apart than this are not one burst: the session was idle (a chat) between them. */
        const val ACTIVE_GAP_NS = 2_000_000_000L
    }

    val startNs: Long = nowNs()
    val outgoing = Direction()
    val incoming = Direction()
    private val wireOut = AtomicLong()
    private val wireIn = AtomicLong()
    @Volatile private var helloReceivedNs = 0L
    @Volatile var resumedChunks = 0
    private val bundles = LinkedHashMap<String, BundleTiming>()

    fun onWireOut(bytes: Int) {
        wireOut.addAndGet(bytes.toLong())
    }

    fun onWireIn(bytes: Int) {
        wireIn.addAndGet(bytes.toLong())
    }

    fun onHelloReceived() {
        if (helloReceivedNs == 0L) helloReceivedNs = nowNs()
    }

    fun onChunk(incomingDirection: Boolean, idHex: String, priorityClass: Int, chunkCount: Int, ciphertextBytes: Int) {
        val now = nowNs()
        (if (incomingDirection) incoming else outgoing).record(now, ciphertextBytes)
        synchronized(bundles) {
            val key = (if (incomingDirection) "in:" else "out:") + idHex
            val timing = bundles.getOrPut(key) { BundleTiming(idHex, priorityClass, chunkCount, incomingDirection) }
            if (timing.chunks == 0) timing.firstNs = now
            timing.lastNs = now
            timing.chunks++
            timing.bytes += ciphertextBytes
        }
    }

    fun onBundleComplete(incomingDirection: Boolean, idHex: String) {
        synchronized(bundles) { bundles[(if (incomingDirection) "in:" else "out:") + idHex]?.complete = true }
    }

    private fun ms(ns: Long): Long = (ns - startNs) / 1_000_000

    /**
     * Rate while chunks were actually moving. The first-to-last rate of a long chat session averages a file transfer with the
     * idle minutes around it: a 1 MB file that took 28 s inside a 266 s span of one text every 3 s read as "32 kbps".
     */
    private fun activeKbps(direction: Direction): String =
        if (direction.activeBytes <= 0 || direction.activeNs <= 0) "0"
        else String.format(Locale.US, "%.0f", direction.activeBytes * 8 / (direction.activeNs / 1e9) / 1000.0)

    private fun kbps(bytes: Long, firstNs: Long, lastNs: Long): String {
        val seconds = (lastNs - firstNs) / 1e9
        return if (bytes <= 0 || seconds <= 0.0) "0" else String.format(Locale.US, "%.0f", bytes * 8 / seconds / 1000.0)
    }

    /** One line per exchange. [fields] is the caller's `key=value ...` text (peer, outcome, protocol...). */
    fun sessionLine(fields: String, setupMs: Long, transport: String, appState: String): String {
        val end = nowNs()
        val durationMs = (end - startNs) / 1_000_000
        val out = outgoing
        val inn = incoming
        val outFirst = if (out.chunks.get() > 0) ms(out.firstNs) else -1
        val outLast = if (out.chunks.get() > 0) ms(out.lastNs) else -1
        val inFirst = if (inn.chunks.get() > 0) ms(inn.firstNs) else -1
        val inLast = if (inn.chunks.get() > 0) ms(inn.lastNs) else -1
        return "BENCHMARK message_transfer session $fields transport=${transport.ifBlank { "unknown" }} " +
            "setupMs=$setupMs helloMs=${if (helloReceivedNs == 0L) -1 else ms(helloReceivedNs)} durationMs=$durationMs " +
            "chunksOut=${out.chunks.get()} bytesOut=${out.bytes.get()} chunksIn=${inn.chunks.get()} bytesIn=${inn.bytes.get()} " +
            "wireOut=${wireOut.get()} wireIn=${wireIn.get()} resumedChunks=$resumedChunks " +
            "firstOutMs=$outFirst lastOutMs=$outLast firstInMs=$inFirst lastInMs=$inLast " +
            "goodputOutKbps=${kbps(out.bytes.get(), out.firstNs, out.lastNs)} goodputInKbps=${kbps(inn.bytes.get(), inn.firstNs, inn.lastNs)} " +
            "sessionOutKbps=${kbps(out.bytes.get(), startNs, end)} sessionInKbps=${kbps(inn.bytes.get(), startNs, end)} " +
            "activeOutKbps=${activeKbps(out)} activeInKbps=${activeKbps(inn)} bundles=${synchronized(bundles) { bundles.size }} " +
            "appState=${appState.ifBlank { "unknown" }}"
    }

    /**
     * One line per bundle that moved chunks in this exchange (at most [limit]). Multi-chunk bundles (files) come first: a chat
     * session of many one-chunk texts used to fill the limit and drop the file's line, the one that says how fast it moved.
     */
    fun bundleLines(transport: String, limit: Int = 32): List<String> = synchronized(bundles) {
        val all = bundles.values.toList()
        val chosen = if (all.size <= limit) all else (all.filter { it.chunks > 1 } + all.filter { it.chunks <= 1 }).take(limit)
        chosen.map { timing ->
            "BENCHMARK message_transfer bundle dir=${if (timing.incoming) "in" else "out"} id=${timing.idHex.take(16)} " +
                "class=${timing.priorityClass} chunks=${timing.chunks}/${timing.chunkCount} chunkBytes=${timing.bytes} " +
                "firstMs=${ms(timing.firstNs)} lastMs=${ms(timing.lastNs)} complete=${timing.complete} " +
                "transport=${transport.ifBlank { "unknown" }} goodputKbps=${kbps(timing.bytes, timing.firstNs, timing.lastNs)}"
        }
    }
}

/** App state at an exchange (background-receive evidence for T-M8). One short token string. */
object MessageAppStateFormat {
    /** [importance] is `RunningAppProcessInfo.importance`. */
    fun format(importance: Int, interactive: Boolean?, deviceIdle: Boolean?, powerSave: Boolean?): String {
        val name = when {
            importance <= 100 -> "foreground"
            importance <= 125 -> "foreground_service"
            importance <= 200 -> "visible"
            importance <= 230 -> "perceptible"
            importance <= 300 -> "service"
            importance < 400 -> "cached_service"
            else -> "cached"
        }
        fun flag(value: Boolean?) = when (value) {
            true -> "1"
            false -> "0"
            null -> "?"
        }
        return "importance=$name($importance),interactive=${flag(interactive)},doze=${flag(deviceIdle)},powersave=${flag(powerSave)}"
    }
}

/**
 * DEBUG-only helpers behind the `sf2_*` controls (Messaging v2, switch `test_messaging_v2`): a phone lane can send text and
 * files, approve downloads, mark messages read and read the state back without a screen. The sender's declared SHA-256 and
 * the receiver's vault SHA-256 are both logged, so an end-to-end integrity check is a text comparison of two log lines.
 */
object MessagingV2Debug {
    /** Deterministic bytes without holding the file in memory. */
    class PatternStream(private var remaining: Long, seed: Long) : java.io.InputStream() {
        private val random = java.util.Random(seed)
        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return random.nextInt(256)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(len.toLong(), remaining).toInt()
            val chunk = ByteArray(count).also(random::nextBytes)
            System.arraycopy(chunk, 0, b, off, count)
            remaining -= count
            return count
        }
    }

    /** Up to 8000 bytes is a text message of letters, anything larger a generated file. Returns the queued messages. */
    fun send(engine: MessagingV2Engine, contact: NodeId, size: Int, count: Int, seed: Long = System.nanoTime()): List<ThreadMessage> =
        List(count) { index ->
            if (size <= MessagingV2Format.TEXT_MAX_BYTES) {
                engine.sendText(contact, String(CharArray(size) { ('a'.code + (it + index) % 26).toChar() }))
            } else {
                engine.sendFile(contact, { PatternStream(size.toLong(), seed + index) }, size.toLong(), Presentation.FILE, "debug-$size.bin")
            }
        }

    fun approveAll(engine: MessagingV2Engine): Int = engine.threads.all()
        .filter { it.attachment?.state == FileState.AWAITING_APPROVAL }
        .count { runCatching { engine.approveDownload(it.id) }.isSuccess }

    fun readAll(engine: MessagingV2Engine): Int = engine.threads.all()
        .filter { it.direction == MessageDirection.IN && it.readAtMs == 0L }
        .onEach { engine.markRead(it.id) }.size

    fun statusLines(node: StoreForwardNode, limit: Int = 30): List<String> {
        val engine = node.v2 ?: return listOf("BENCHMARK message_v2 status enabled=false")
        val all = engine.threads.all()
        fun files(state: FileState) = all.count { it.attachment?.state == state }
        val summary = "BENCHMARK message_v2 status threads=${all.size} out=${all.count { it.direction == MessageDirection.OUT }} " +
            "in=${all.count { it.direction == MessageDirection.IN }} outDelivered=${all.count { it.direction == MessageDirection.OUT && it.delivered }} " +
            "outDownloaded=${all.count { it.direction == MessageDirection.OUT && it.downloaded }} " +
            "outRead=${all.count { it.direction == MessageDirection.OUT && it.read }} " +
            "awaiting=${files(FileState.AWAITING_APPROVAL)} downloading=${files(FileState.DOWNLOADING)} complete=${files(FileState.COMPLETE)} " +
            "damaged=${files(FileState.DAMAGED)} unavailable=${files(FileState.UNAVAILABLE)} transfers=${engine.transfers.all().size} " +
            "keys=${engine.keys.count()} vaultFiles=${engine.vault.ids().size} vaultBytes=${engine.vault.usedBytes()} " +
            "receiptsPending=${engine.receiptOutbox.size()} reservations=${engine.reservationCount()}"
        val lines = all.sortedByDescending { it.arrivalMs }.take(limit).map { m ->
            val a = m.attachment
            val actual = if (a?.vaultId != null && engine.vault.info(a.vaultId!!)?.complete == true) {
                runCatching { engine.vault.sha256(a.vaultId!!).sfHex() }.getOrDefault("-")
            } else "-"
            "BENCHMARK message_v2 message dir=${m.direction.name.lowercase()} id=${m.messageIdHex.take(16)} peer=${m.peerNode.take(12)} " +
                "kind=${if (a != null) "file" else "text"} bytes=${a?.totalSize ?: m.body.size} file=${a?.state?.name?.lowercase() ?: "-"} " +
                "sha256=${a?.sha256?.sfHex() ?: "-"} actual=$actual delivered=${m.delivered} downloaded=${m.downloaded} read=${m.readAtMs > 0} " +
                // T-M7: queue-to-delivered time on the sender's own clock (the DELIVERED_LOCAL round trip is included).
                "createdMs=${m.createdMs} arrivalMs=${m.arrivalMs} deliverLatencyMs=${if (m.direction == MessageDirection.OUT && m.delivered) m.deliveredAtMs - m.createdMs else -1}"
        }
        return listOf(summary) + lines
    }
}

/** DEBUG-only helpers behind the `sf_status` and `sf_clear` controls: no taps needed on the phone lane. */
object StoreForwardDebug {
    /** `BENCHMARK store_forward status ...`: counts a lane can compare before and after an exchange. */
    fun statusLine(node: StoreForwardNode, appState: String = ""): String {
        val bundles = node.store.all()
        fun count(role: BundleRole, state: BundleState? = null) =
            bundles.count { it.role == role && (state == null || it.state == state) }
        val contacts = node.contacts.all()
        return "BENCHMARK store_forward status contacts=${contacts.size} accepted=${contacts.count { it.state == ContactState.ACCEPTED }} " +
            "pending=${contacts.count { it.state == ContactState.PENDING }} " +
            "ownQueued=${count(BundleRole.OWN, BundleState.QUEUED)} ownHanded=${count(BundleRole.OWN, BundleState.HANDED)} " +
            "ownDelivered=${count(BundleRole.OWN, BundleState.DELIVERED)} ownFailed=${count(BundleRole.OWN, BundleState.FAILED)} " +
            "inboxBundles=${count(BundleRole.INBOX)} foreign=${count(BundleRole.FOREIGN)} " +
            "archiveIn=${node.archive.count(MessageDirection.IN)} archiveOut=${node.archive.count(MessageDirection.OUT)} " +
            "archiveInBytes=${node.archive.bytes(MessageDirection.IN)} " +
            "appState=${appState.ifBlank { "unknown" }}"
    }

    /** The newest [limit] received messages, one line each, so an arrival can be timed from the log. */
    fun inboxLines(node: StoreForwardNode, limit: Int = 20): List<String> =
        node.archive.page(0, limit, MessageDirection.IN).map {
            "BENCHMARK store_forward inbox id=${it.bundleIdHex.take(16)} kind=${it.kind.label} bytes=${it.bodyBytes} " +
                "from=${it.peerNode.take(12)} receivedMs=${it.receivedMs}"
        }

    /** Removes every own, inbox and archived message so the next measurement starts empty. Contacts stay. */
    fun clearMessages(node: StoreForwardNode): Int {
        var removed = 0
        node.store.all().filter { it.role == BundleRole.OWN || it.role == BundleRole.INBOX }.forEach {
            node.store.remove(it.idHex, null)
            removed++
        }
        val archived = node.archive.page(0, Int.MAX_VALUE, MessageDirection.IN) +
            node.archive.page(0, Int.MAX_VALUE, MessageDirection.OUT)
        archived.forEach {
            node.archive.delete(it.bundleIdHex)
            removed++
        }
        return removed
    }
}
