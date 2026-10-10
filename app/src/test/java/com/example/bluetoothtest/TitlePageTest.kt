package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePageTest {
    private val token = 0x81234567.toInt()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun shortTitleIsOnePageWithTheProtocolLayout() {
        val pages = TitlePageCodec.pages(token, "Hi")
        assertEquals(1, pages.size)
        val digest = TitlePageCodec.digest("Hi".toByteArray())
        assertEquals("03" + "81234567" + "%04x".format(digest) + "01" + "4869", hex(pages[0]))
        // The 22-byte announcement decoder rejects a title page (old scanners ignore it).
        assertTrue(runCatching { DiscoveryAnnouncementCodec.decode(pages[0]) }.isFailure)
    }

    @Test
    fun multiByteTitleNeverSplitsACodePointAndReassemblesOutOfOrder() {
        // 18 ASCII bytes, then 3-byte characters that straddle the 19-byte page boundary.
        val title = "a".repeat(18) + "\u20AC".repeat(10)
        assertEquals(48, title.toByteArray(Charsets.UTF_8).size)
        val pages = TitlePageCodec.pages(token, title)
        assertEquals(3, pages.size)
        pages.forEach { page ->
            val fragment = TitlePageCodec.decode(page).fragment
            // Each fragment is valid UTF-8 on its own.
            assertEquals(fragment.size, String(fragment, Charsets.UTF_8).toByteArray(Charsets.UTF_8).size)
            assertTrue(page.size <= 27)
        }
        val board = TitleBoard { 1_000L }
        assertNull(board.add(TitlePageCodec.decode(pages[2])))
        assertNull(board.add(TitlePageCodec.decode(pages[0])))
        assertEquals(title, board.add(TitlePageCodec.decode(pages[1])))
        assertEquals(title, board.title(token))
    }

    @Test
    fun digestMismatchAndConflictingTitlesShowNoTitle() {
        var now = 0L
        val board = TitleBoard { now }
        val good = TitlePageCodec.decode(TitlePageCodec.pages(token, "Market")[0])
        val tampered = good.copy(fragment = "Marker".toByteArray())
        assertNull(board.add(tampered))
        assertNull(board.title(token))
        // A second, different title for the same token within 60 s: "conflicting titles nearby".
        now += 1_000
        assertNull(board.add(TitlePageCodec.decode(TitlePageCodec.pages(token, "Other")[0])))
        assertNull(board.title(token))
        // After the conflict window, one consistent title shows again.
        now += 61_000
        assertEquals("Market", board.add(good))
    }

    @Test
    fun malformedPagesAreRejected() {
        val page = TitlePageCodec.pages(token, "Hi")[0]
        val badIndex = page.copyOf().also { it[7] = 0x11 } // index 1 of count 1
        assertTrue(runCatching { TitlePageCodec.decode(badIndex) }.isFailure)
        val zeroCount = page.copyOf().also { it[7] = 0x00 }
        assertTrue(runCatching { TitlePageCodec.decode(zeroCount) }.isFailure)
        assertTrue(runCatching { TitlePageCodec.decode(page.copyOf(8)) }.isFailure) // empty fragment
        assertTrue(runCatching { TitlePageCodec.pages(token, "a".repeat(49)) }.isFailure)
    }

    @Test
    fun nonCanonicalAdvertisedTitleIsNotShown() {
        val board = TitleBoard { 0L }
        val raw = " padded".toByteArray()
        val page = TitlePage(token, TitlePageCodec.digest(raw), 0, 1, raw)
        assertNull(board.add(page))
    }

    @Test
    fun floodOfRandomTokensUsesBoundedMemory() {
        var now = 0L
        val board = TitleBoard { now }
        val raw = "Hi".toByteArray()
        // Incomplete two-page titles from MAX_TOKENS random tokens fill the board...
        (1..TitleBoard.MAX_TOKENS).forEach { token ->
            assertNull(board.add(TitlePage(token, TitlePageCodec.digest(raw), 0, 2, raw)))
        }
        // ...so a new token is dropped while it is full,
        val fresh = TitlePageCodec.decode(TitlePageCodec.pages(0x7000_0001, "Hi").single())
        assertNull(board.add(fresh))
        // and accepted again once the flood's entries age out (no permanent per-token state).
        now += TitleBoard.CONFLICT_WINDOW_MS + 1
        assertEquals("Hi", board.add(fresh))
    }
}
