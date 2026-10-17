package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Visible by name (Design G, G5; PROTOCOL "Visible by name: window offer, name pages and the role 3 ID"; `visible_by_name`,
 * off). Pure Kotlin: the page codec, the scanner's bounded reassembly and the claim rules, the exact-ten-minute window and
 * its exclusion with Visible-by-handle, the window offer and the link-opening entries.
 *
 * Trust, plainly (independent review findings 3 and 4): a page carries no signature. Anyone can copy a window ID and advertise
 * their own offer hash and pages, and receiving something recently is not freshness. So a name read from pages is only ever
 * an unconfirmed claim of whoever advertises that ID ("~Rahul, calls itself"), shown only while exactly one offer hash is
 * heard for the ID, and replaced by the signed label of the actual key once a call authenticates the peer.
 */

/** One decoded name page: `version 7 | RDI (6) | SHA-256(name)[0:4] | index << 4 | count | 1..10 name bytes`. */
internal class NamePage(rdi: ByteArray, hash: ByteArray, val index: Int, val count: Int, chunk: ByteArray) {
    val rdi: ByteArray = rdi.copyOf()
    val hash: ByteArray = hash.copyOf()
    val chunk: ByteArray = chunk.copyOf()

    init {
        require(rdi.size == RotatingDiscoveryIds.ID_BYTES && hash.size == NamePageCodec.HASH_BYTES)
        require(count in 1..NamePageCodec.MAX_PAGES && index in 0 until count)
        require(chunk.size in 1..NamePageCodec.MAX_CHUNK_BYTES)
    }
}

internal object NamePageCodec {
    const val VERSION = 7
    const val HASH_BYTES = 4
    const val MAX_PAGES = 4
    const val MAX_CHUNK_BYTES = 10
    const val HEADER_BYTES = 1 + RotatingDiscoveryIds.ID_BYTES + HASH_BYTES + 1
    const val MAX_NAME_BYTES = 32

    /** `SHA-256(UTF-8 name)[0:4]`: the commitment carried by the window offer's route-token field. */
    fun nameHash(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(HASH_BYTES)

    fun hashToToken(hash: ByteArray): Int = ByteBuffer.wrap(hash).int

    /**
     * The fewest pages of whole code points (at most [MAX_CHUNK_BYTES] bytes each) that carry [name], or null when it needs
     * more than [MAX_PAGES]. A greedy split can strand 7-byte pages, so this is an exact minimum.
     */
    fun split(name: String): List<ByteArray>? {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_NAME_BYTES) return null
        val sizes = name.codePoints().toArray().map { String(Character.toChars(it)).toByteArray(Charsets.UTF_8).size }
        val n = sizes.size
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val take = IntArray(n + 1)
        best[n] = 0
        for (i in n - 1 downTo 0) {
            var total = 0
            var j = i
            while (j < n && total + sizes[j] <= MAX_CHUNK_BYTES) {
                total += sizes[j]
                j++
                if (best[j] != Int.MAX_VALUE && best[j] + 1 < best[i]) {
                    best[i] = best[j] + 1
                    take[i] = j - i
                }
            }
        }
        if (best[0] == Int.MAX_VALUE || best[0] > MAX_PAGES) return null
        val out = ArrayList<ByteArray>()
        var offset = 0
        var cp = 0
        while (cp < n) {
            val k = take[cp]
            val size = sizes.subList(cp, cp + k).sum()
            out += bytes.copyOfRange(offset, offset + size)
            offset += size
            cp += k
        }
        return out
    }

    /** True when [name] needs at most [MAX_PAGES] pages. */
    fun fits(name: String): Boolean = split(name) != null

    /** The wire pages for [name] on window ID [rdi]; null when [name] does not fit. */
    fun pages(rdi: ByteArray, name: String): List<ByteArray>? {
        require(rdi.size == RotatingDiscoveryIds.ID_BYTES)
        val chunks = split(name) ?: return null
        val hash = nameHash(name)
        return chunks.mapIndexed { index, chunk ->
            ByteBuffer.allocate(HEADER_BYTES + chunk.size).apply {
                put(VERSION.toByte())
                put(rdi)
                put(hash)
                put(((index shl 4) or chunks.size).toByte())
                put(chunk)
            }.array()
        }
    }

    fun isNamePage(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size in (HEADER_BYTES + 1)..(HEADER_BYTES + MAX_CHUNK_BYTES) && bytes[0].toInt() == VERSION

    /** Strict decode: null for anything not exactly a well-formed page. */
    fun decode(bytes: ByteArray): NamePage? {
        if (!isNamePage(bytes)) return null
        val control = bytes[HEADER_BYTES - 1].toInt() and 0xff
        val index = control ushr 4
        val count = control and 0x0f
        if (count !in 1..MAX_PAGES || index >= count) return null
        return NamePage(
            bytes.copyOfRange(1, 1 + RotatingDiscoveryIds.ID_BYTES),
            bytes.copyOfRange(1 + RotatingDiscoveryIds.ID_BYTES, 1 + RotatingDiscoveryIds.ID_BYTES + HASH_BYTES),
            index,
            count,
            bytes.copyOfRange(HEADER_BYTES, bytes.size),
        )
    }

    /** Whole-text UTF-8 decode that refuses malformed input. */
    internal fun strictUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
}

/** The window offer: an announcement of version 8 (the version 5 layout) on the role 3 ID, the name hash in the token field. */
internal object WindowOffer {
    fun announcement(rdi: ByteArray, nameHash: ByteArray, transportFlags: Int = 0, capabilitySummary: Int = 3): DiscoveryAnnouncement {
        require(nameHash.size == NamePageCodec.HASH_BYTES)
        val token = NamePageCodec.hashToToken(nameHash)
        require(token != 0) { "A zero hash means no commitment" }
        return DiscoveryAnnouncement(
            nodeIdPrefix = rdi,
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or transportFlags,
            capabilitySummary = capabilitySummary,
            streamToken = token,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.WINDOW_VERSION,
        )
    }

    /** The 4-byte commitment in [offer]'s token field, or null for a non-window offer or token 0. */
    fun commitment(offer: DiscoveryAnnouncement): ByteArray? =
        if (offer.isWindowOffer && offer.streamToken != 0) ByteBuffer.allocate(4).putInt(offer.streamToken).array() else null
}

/**
 * The scanner's reassembly, with global bounds (finding 10). It admits a page only for an RDI whose window offer it heard
 * within [OFFER_FRESH_MS], and only when the page's hash equals that offer's commitment, so a flood of pages for unheard
 * or other-hash IDs costs a lookup and no memory. A name is shown only while exactly ONE commitment is heard for the ID
 * (two means a copy of the ID: no name, and the caller's duplicate-ID warning applies) and only as a claim.
 */
internal class NamePageAssembler(
    private val limits: Limits = Limits(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class Limits(
        val maxAssemblies: Int = 16,
        val maxAssembliesPerRdi: Int = 4,
        val maxBufferedBytes: Int = 640,
        val maxPagesPerSecond: Int = 32,
        val maxRows: Int = 24,
        val assemblyMs: Long = 30_000L,
    )

    private class OfferState(var lastSeenMs: Long) {
        /** commitment hex -> last time heard */
        val commitments = LinkedHashMap<String, Long>()
    }

    private class Assembly(val rdiHex: String, val hashHex: String, val count: Int, val startedMs: Long) {
        val chunks = arrayOfNulls<ByteArray>(count)
        val bytes: Int get() = chunks.sumOf { it?.size ?: 0 }
    }

    private val offers = LinkedHashMap<String, OfferState>(16, 0.75f, true)
    private val assemblies = LinkedHashMap<String, Assembly>()
    private val named = HashMap<String, String>() // rdi hex -> verified claimed name (kept while its offer is fresh)
    private var windowStartMs = 0L
    private var windowPages = 0

    /** A window offer was heard (version 8, non-zero commitment). Bounded to [Limits.maxRows] rows, oldest dropped. */
    @Synchronized
    fun onOffer(rdi: ByteArray, commitment: ByteArray) {
        val now = clockMs()
        val key = hex(rdi)
        val state = offers.getOrPut(key) { OfferState(now) }
        state.lastSeenMs = now
        state.commitments[hex(commitment)] = now
        pruneCommitments(state, now)
        while (offers.size > limits.maxRows) {
            val eldest = offers.keys.first()
            dropRow(eldest)
        }
    }

    /** A page was heard. Returns true when it was admitted (counted against the bounds). */
    @Synchronized
    fun onPage(page: NamePage): Boolean {
        val now = clockMs()
        if (now - windowStartMs >= 1_000L) {
            windowStartMs = now
            windowPages = 0
        }
        if (++windowPages > limits.maxPagesPerSecond) return false
        expire(now)
        val rdiKey = hex(page.rdi)
        val state = offers[rdiKey] ?: return false
        if (now - state.lastSeenMs > OFFER_FRESH_MS) return false
        if (hex(page.hash) !in state.commitments) return false
        val key = rdiKey + "/" + hex(page.hash) + "/" + page.count
        var assembly = assemblies[key]
        if (assembly == null) {
            if (assemblies.values.count { it.rdiHex == rdiKey } >= limits.maxAssembliesPerRdi) return false
            while (assemblies.size >= limits.maxAssemblies || bufferedBytes() + page.chunk.size > limits.maxBufferedBytes) {
                if (assemblies.isEmpty()) return false
                assemblies.remove(assemblies.keys.first())
            }
            assembly = Assembly(rdiKey, hex(page.hash), page.count, now).also { assemblies[key] = it }
        }
        if (assembly.chunks[page.index] == null) assembly.chunks[page.index] = page.chunk.copyOf()
        if (assembly.chunks.all { it != null }) {
            assemblies.remove(key)
            val joined = assembly.chunks.fold(ByteArray(0)) { acc, c -> acc + requireNotNull(c) }
            val text = NamePageCodec.strictUtf8(joined)
            if (text != null && hex(NamePageCodec.nameHash(text)) == assembly.hashHex &&
                LabelText.isCanonical(LabelKind.PERSON_NAME, text)
            ) {
                named[rdiKey] = text
            }
        }
        return true
    }

    /**
     * The claimed name of [rdi], or null. Requires a fresh offer, exactly one commitment heard for the ID, and a name whose
     * hash equals it. It is a claim, never an identity.
     */
    @Synchronized
    fun claimedName(rdi: ByteArray): String? {
        val now = clockMs()
        val key = hex(rdi)
        val state = offers[key] ?: return null
        if (now - state.lastSeenMs > OFFER_FRESH_MS) {
            dropRow(key)
            return null
        }
        pruneCommitments(state, now)
        if (state.commitments.size != 1) return null
        val name = named[key] ?: return null
        return name.takeIf { hex(NamePageCodec.nameHash(it)) == state.commitments.keys.first() }
    }

    /** True while two commitments are heard for [rdi] (a copy of the ID: show the duplicate-ID warning, no name). */
    @Synchronized
    fun conflicting(rdi: ByteArray): Boolean {
        val state = offers[hex(rdi)] ?: return false
        pruneCommitments(state, clockMs())
        return state.commitments.size > 1
    }

    @Synchronized
    fun sizes(): Triple<Int, Int, Int> = Triple(offers.size, assemblies.size, bufferedBytes())

    @Synchronized
    fun clear() {
        offers.clear()
        assemblies.clear()
        named.clear()
    }

    private fun bufferedBytes() = assemblies.values.sumOf { it.bytes }

    private fun expire(now: Long) {
        assemblies.values.removeAll { now - it.startedMs > limits.assemblyMs }
    }

    private fun pruneCommitments(state: OfferState, now: Long) {
        state.commitments.values.removeAll { now - it > OFFER_FRESH_MS }
    }

    private fun dropRow(key: String) {
        offers.remove(key)
        named.remove(key)
        assemblies.values.removeAll { it.rdiHex == key }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** A window offer older than this is no longer a row: its name is not shown (display hygiene, not authentication). */
        const val OFFER_FRESH_MS = 60_000L
    }
}

/** The process-wide scanner board of window rows (fed by the scan callback, read by the list). */
internal object NameWindowBoard {
    val assembler = NamePageAssembler()

    fun onOffer(offer: DiscoveryAnnouncement) {
        WindowOffer.commitment(offer)?.let { assembler.onOffer(offer.nodeIdPrefix, it) }
    }

    fun onPage(page: NamePage) {
        assembler.onPage(page)
    }

    fun claimedName(offer: DiscoveryAnnouncement): String? = assembler.claimedName(offer.nodeIdPrefix)
}

/**
 * The 10-minute window (G-Q4): exactly [DURATION_MS] of elapsed time from the tap, never longer whatever the wall clock does,
 * ended earlier by Stop, an app restart (in memory only) or Bluetooth off. It shares one state with Visible-by-handle
 * (mode ID or NAME, never both) and never advances the monotonic epoch of the ordinary IDs.
 */
internal class VisibleNameWindow(private val elapsedMs: () -> Long, private val wallMs: () -> Long) {
    enum class Result { STARTED, NAME_INVALID, NAME_TOO_LONG_FOR_PAGES, NAME_HASH_ZERO, NAME_SAME_AS_CALL_NAME_UNCONFIRMED }

    private var startedElapsed = Long.MIN_VALUE
    private var current: String? = null

    /** The name being shown, while the window runs. */
    @Synchronized
    fun name(): String? = current.takeIf { active() }

    @Synchronized
    fun active(): Boolean {
        val name = current ?: return false
        if (elapsedMs() - startedElapsed >= DURATION_MS) {
            current = null
            return false
        }
        return name.isNotEmpty()
    }

    /** The commitment of the running window's name, or null. */
    @Synchronized
    fun hash(): ByteArray? = name()?.let(NamePageCodec::nameHash)

    /**
     * Starts the window for [rawName]. A real name in a public window is the risky default (review F16), so a name equal to
     * the one used in calls needs [confirmedSameAsCallName] (the screen asks first). Returns the result; on STARTED the
     * wall time the window ends at is [endsAtWallMs].
     */
    @Synchronized
    fun start(rawName: String, callName: String?, confirmedSameAsCallName: Boolean): Result {
        val name = runCatching { LabelText.normalize(LabelKind.PERSON_NAME, rawName) }.getOrNull() ?: return Result.NAME_INVALID
        if (!NamePageCodec.fits(name)) return Result.NAME_TOO_LONG_FOR_PAGES
        if (NamePageCodec.hashToToken(NamePageCodec.nameHash(name)) == 0) return Result.NAME_HASH_ZERO
        if (callName != null && callName == name && !confirmedSameAsCallName) return Result.NAME_SAME_AS_CALL_NAME_UNCONFIRMED
        current = name
        startedElapsed = elapsedMs()
        return Result.STARTED
    }

    val endsAtWallMs: Long get() = wallMs() + (DURATION_MS - (elapsedMs() - startedElapsed)).coerceAtLeast(0L)

    @Synchronized
    fun stop() {
        current = null
    }

    companion object {
        const val DURATION_MS = 600_000L
    }
}

/**
 * Visible-by-handle and Visible-by-name share one window: starting one ends the other (a name next to a fixed prefix would
 * give observers a permanent name-to-key table).
 */
internal class VisibleWindows(
    private val nameWindow: VisibleNameWindow,
    private val startHandle: () -> Unit,
    private val stopHandle: () -> Unit,
) {
    enum class Mode { NONE, ID, NAME }

    private var idRunning = false

    fun mode(isHandleActive: Boolean): Mode = when {
        nameWindow.active() -> Mode.NAME
        isHandleActive -> Mode.ID
        else -> Mode.NONE
    }

    fun startId() {
        nameWindow.stop()
        idRunning = true
        startHandle()
    }

    fun startName(rawName: String, callName: String?, confirmed: Boolean): VisibleNameWindow.Result {
        if (idRunning) {
            stopHandle()
            idRunning = false
        }
        val result = nameWindow.start(rawName, callName, confirmed)
        return result
    }

    fun stopAll() {
        nameWindow.stop()
        if (idRunning) stopHandle()
        idRunning = false
    }
}

/**
 * While a window is open the link opening holds at most two entries (a 0.2.0-beta decoder rejects more): the current role 0
 * entry and the current role 3 entry. The previous-epoch entries are dropped for the duration, so a call that selected the
 * role 0 ID of the epoch that just ended in the first minute after a boundary must retry.
 */
internal object WindowOpening {
    fun entries(role0: List<OpeningEntry>, role3: List<OpeningEntry>, windowOpen: Boolean): List<OpeningEntry> =
        if (!windowOpen) {
            role0
        } else {
            (role0.take(1) + role3.take(1)).take(DiscoveryOpeningCodec.MAX_ENTRIES)
        }
}
