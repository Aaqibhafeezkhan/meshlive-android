package com.example.bluetoothtest

import java.security.MessageDigest

/**
 * Public stream-title pages (naming design 3.7 / PROTOCOL "Experimental peer labels"): the
 * manufacturer `0xFFFF` scan response of a second, non-connectable legacy advertising set.
 *
 * `version 3 | stream route token (4) | SHA-256(title)[0:2] | page (index << 4 | count) | 1..19 bytes`
 *
 * Old scanners reject it (not 22 bytes). A title read from pages is always UNCONFIRMED: anyone
 * nearby can advertise one. The signed kind 2 label received after joining replaces it.
 */
internal data class TitlePage(
    val routeToken: Int,
    val digest: Int,
    val index: Int,
    val count: Int,
    val fragment: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is TitlePage && routeToken == other.routeToken &&
        digest == other.digest && index == other.index && count == other.count && fragment.contentEquals(other.fragment)

    override fun hashCode(): Int = ((routeToken * 31 + digest) * 31 + index) * 31 + fragment.contentHashCode()
}

internal object TitlePageCodec {
    const val VERSION = 3
    const val HEADER_BYTES = 8
    const val MAX_FRAGMENT_BYTES = 19
    const val MAX_PAGES = 3

    fun digest(titleBytes: ByteArray): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(titleBytes)
        return ((hash[0].toInt() and 0xff) shl 8) or (hash[1].toInt() and 0xff)
    }

    /** Splits [title] (already canonical, at most 48 UTF-8 bytes) at code-point boundaries. */
    fun pages(routeToken: Int, title: String): List<ByteArray> {
        val bytes = title.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= LabelKind.STREAM_TITLE.maxBytes) { "Title length" }
        val fragments = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            var end = minOf(bytes.size, offset + MAX_FRAGMENT_BYTES)
            // Never split a UTF-8 sequence: back off over continuation bytes.
            while (end < bytes.size && end > offset && (bytes[end].toInt() and 0xc0) == 0x80) end--
            fragments += bytes.copyOfRange(offset, end)
            offset = end
        }
        require(fragments.size <= MAX_PAGES) { "Title needs more than $MAX_PAGES pages" }
        val digest = digest(bytes)
        return fragments.mapIndexed { index, fragment ->
            java.nio.ByteBuffer.allocate(HEADER_BYTES + fragment.size)
                .put(VERSION.toByte())
                .putInt(routeToken)
                .putShort(digest.toShort())
                .put(((index shl 4) or fragments.size).toByte())
                .put(fragment)
                .array()
        }
    }

    fun isTitlePage(bytes: ByteArray?): Boolean = bytes != null && bytes.isNotEmpty() && bytes[0].toInt() == VERSION

    fun decode(bytes: ByteArray): TitlePage {
        require(bytes.size in HEADER_BYTES + 1..HEADER_BYTES + MAX_FRAGMENT_BYTES) { "Title page length" }
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        require(buffer.get().toInt() == VERSION) { "Title page version" }
        val token = buffer.int
        val digest = buffer.short.toInt() and 0xffff
        val page = buffer.get().toInt() and 0xff
        val index = page ushr 4
        val count = page and 0x0f
        require(count in 1..MAX_PAGES && index < count) { "Title page index" }
        val fragment = ByteArray(buffer.remaining()).also(buffer::get)
        return TitlePage(token, digest, index, count, fragment)
    }
}

/**
 * Reassembles advertised titles by (route token, digest, count). All pages within 30 s; the
 * concatenation must be valid UTF-8, canonical and match the digest. Two different digests for one
 * token within 60 s mean "conflicting titles nearby": no title is shown for that token.
 */
internal class TitleBoard(private val nowMs: () -> Long = System::currentTimeMillis) {
    private data class Key(val token: Int, val digest: Int, val count: Int)
    private class Partial(val firstMs: Long, val pages: Array<ByteArray?>)
    private class Seen(val digest: Int, val atMs: Long)

    private val partials = mutableMapOf<Key, Partial>()
    private val titles = mutableMapOf<Int, Pair<String, Long>>()
    private val digests = mutableMapOf<Int, MutableList<Seen>>()
    private val conflicted = mutableMapOf<Int, Long>()

    @Synchronized
    fun add(page: TitlePage): String? {
        val now = nowMs()
        prune(now)
        // Anyone nearby can send pages with fresh random tokens at the scan rate: bound every map so
        // a flood costs a fixed amount of memory (new tokens are dropped while the board is full).
        if (!digests.containsKey(page.routeToken) && digests.size >= MAX_TOKENS) return null
        if (!partials.containsKey(Key(page.routeToken, page.digest, page.count)) && partials.size >= MAX_TOKENS) return null
        val seen = digests.getOrPut(page.routeToken) { mutableListOf() }
        seen.removeAll { now - it.atMs > CONFLICT_WINDOW_MS || it.digest == page.digest }
        if (seen.isNotEmpty()) {
            conflicted[page.routeToken] = now
            titles.remove(page.routeToken)
        }
        seen += Seen(page.digest, now)
        val key = Key(page.routeToken, page.digest, page.count)
        val partial = partials.getOrPut(key) { Partial(now, arrayOfNulls(page.count)) }
        partial.pages[page.index] = page.fragment
        if (partial.pages.any { it == null }) return null
        partials.remove(key)
        val bytes = partial.pages.fold(ByteArray(0)) { acc, part -> acc + requireNotNull(part) }
        if (TitlePageCodec.digest(bytes) != page.digest) return null
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return null
        if (!LabelText.isCanonical(LabelKind.STREAM_TITLE, text)) return null
        if (conflicted[page.routeToken]?.let { now - it <= CONFLICT_WINDOW_MS } == true) return null
        titles[page.routeToken] = text to now
        return text
    }

    /** The unconfirmed advertised title for a stream offer's route token, or null. */
    @Synchronized
    fun title(routeToken: Int): String? {
        prune(nowMs())
        if (conflicted.containsKey(routeToken)) return null
        return titles[routeToken]?.first
    }

    @Synchronized
    fun clear() {
        partials.clear(); titles.clear(); digests.clear(); conflicted.clear()
    }

    private fun prune(now: Long) {
        partials.entries.removeAll { now - it.value.firstMs > PAGE_WINDOW_MS }
        titles.entries.removeAll { now - it.value.second > TITLE_STALE_MS }
        conflicted.entries.removeAll { now - it.value > CONFLICT_WINDOW_MS }
        digests.values.forEach { seen -> seen.removeAll { now - it.atMs > CONFLICT_WINDOW_MS } }
        digests.entries.removeAll { it.value.isEmpty() }
    }

    companion object {
        const val PAGE_WINDOW_MS = 30_000L
        const val CONFLICT_WINDOW_MS = 60_000L
        const val TITLE_STALE_MS = 30_000L

        /** Tokens tracked at once (about 256 nearby title advertisers within one minute). */
        const val MAX_TOKENS = 256
    }
}

/** Process-wide board fed by the scanner's title-page path (duplicates reported, before dedup). */
internal object StreamTitles {
    val board = TitleBoard()

    /** Called (scanner thread) when a token's assembled title is new or changed: the Live list redraws its rows. */
    @Volatile var onNewTitle: (() -> Unit)? = null
    private val shown = HashMap<Int, String>()

    /** True once per new or changed title of a token (bounded: cleared past the board's token limit). */
    @Synchronized
    fun isNew(token: Int, title: String): Boolean {
        if (shown[token] == title) return false
        if (shown.size >= 64) shown.clear()
        shown[token] = title
        return true
    }

    @Volatile var pagesReceived = 0L
}
