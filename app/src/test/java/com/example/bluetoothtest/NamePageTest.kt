package com.example.bluetoothtest

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Name pages, the window offer and the scanner's claim rules (Design G, G5; review findings 3, 4 and 10). */
class NamePageTest {
    private var now = 0L
    private val rdi = byteArrayOf(0x44, 0xe6.toByte(), 0x9c.toByte(), 0x0a, 0x7a, 0x7d)
    private val otherRdi = byteArrayOf(0x67, 0xab.toByte(), 0xa6.toByte(), 0x70, 0xe6.toByte(), 0x5a)

    @Before
    fun setUp() {
        now = 1_000L
    }

    private fun assembler(limits: NamePageAssembler.Limits = NamePageAssembler.Limits()) = NamePageAssembler(limits) { now }

    private fun offerFor(a: NamePageAssembler, id: ByteArray, name: String) =
        a.onOffer(id, NamePageCodec.nameHash(name))

    private fun feed(a: NamePageAssembler, id: ByteArray, name: String): Boolean {
        val pages = requireNotNull(NamePageCodec.pages(id, name)) { name }
        return pages.map { a.onPage(requireNotNull(NamePageCodec.decode(it))) }.all { it }
    }

    // --- codec ---

    @Test
    fun aPageHasTheDocumentedLayoutAndAtMostTwentyTwoBytes() {
        val pages = NamePageCodec.pages(rdi, "Rahul Kumar-Singh")!!
        assertTrue(pages.size in 1..4)
        pages.forEachIndexed { i, page ->
            assertTrue(page.size <= 22)
            assertEquals(7, page[0].toInt())
            assertArrayEquals(rdi, page.copyOfRange(1, 7))
            assertArrayEquals(NamePageCodec.nameHash("Rahul Kumar-Singh"), page.copyOfRange(7, 11))
            assertEquals((i shl 4) or pages.size, page[11].toInt() and 0xff)
        }
        assertEquals(
            "Rahul Kumar-Singh",
            String(pages.map { it.copyOfRange(12, it.size) }.fold(ByteArray(0)) { a, b -> a + b }, Charsets.UTF_8),
        )
    }

    @Test
    fun everyNameOfCodePointsUpToThreeBytesFitsInFourPagesAndAnySplitIsWholeCodePoints() {
        // A page holds at least 8 bytes of code points up to 3 bytes (a code point that does not fit moves to the next
        // page), so 32 bytes need at most 4 pages. Names mixing 3- and 4-byte code points can need a fifth: those are
        // refused by the window (the record's first claim of "any mix" was wrong; found by this test).
        val random = Random(7)
        val small = listOf("a", "é", "€", "अ", "中")
        val all = small + listOf("😀", "🇮")
        var refused = 0
        repeat(40_000) { round ->
            val pool = if (round % 2 == 0) small else all
            val sb = StringBuilder()
            while (true) {
                val next = pool[random.nextInt(pool.size)]
                if ((sb.toString() + next).toByteArray(Charsets.UTF_8).size > 32) break
                sb.append(next)
            }
            val name = sb.toString()
            val chunks = NamePageCodec.split(name)
            if (pool === small) assertNotNull("needs more than four pages: $name", chunks)
            if (chunks == null) {
                refused++
            } else {
                assertTrue(chunks.size <= 4)
                chunks.forEach { assertTrue(it.size in 1..10) }
                assertEquals(name, String(chunks.fold(ByteArray(0)) { a, b -> a + b }, Charsets.UTF_8))
                chunks.forEach { assertNotNull("a page ends inside a code point", NamePageCodec.strictUtf8(it)) }
            }
        }
        assertTrue("some mixed 3/4-byte names do need a fifth page and are refused (found $refused)", refused > 0)
    }

    @Test
    fun aFourPageNameOfThreeAndFourByteCodePointsRoundTrips() {
        val three = "€".repeat(10) // 30 bytes of 3-byte code points
        val four = "😀".repeat(8) // 32 bytes of 4-byte code points
        for (name in listOf(three, four)) {
            val pages = NamePageCodec.pages(rdi, name)
            assertNotNull(name, pages)
            assertTrue(pages!!.size <= 4)
        }
        assertNull("33 bytes", NamePageCodec.pages(rdi, "a".repeat(33)))
        assertNull(NamePageCodec.pages(rdi, ""))
    }

    @Test
    fun theDecoderIsStrict() {
        val good = NamePageCodec.pages(rdi, "Rahul")!![0]
        assertNotNull(NamePageCodec.decode(good))
        assertNull(NamePageCodec.decode(good.copyOf().also { it[0] = 6 }))
        assertNull(NamePageCodec.decode(good.copyOf().also { it[11] = 0x00 })) // count 0
        assertNull(NamePageCodec.decode(good.copyOf().also { it[11] = 0x25.toByte() })) // index 2 of count 5
        assertNull(NamePageCodec.decode(good.copyOf().also { it[11] = 0x31 })) // index 3 of count 1
        assertNull(NamePageCodec.decode(good + ByteArray(20)))
        assertNull(NamePageCodec.decode(ByteArray(12))) // header only: no chunk byte
        assertFalse(NamePageCodec.isNamePage(null))
    }

    // --- the window offer (version 8) ---

    @Test
    fun theWindowOfferIsVersion8CarriesTheHashAndIsInvisibleToOldDecoders() {
        val hash = NamePageCodec.nameHash("Rahul")
        val offer = WindowOffer.announcement(rdi, hash)
        assertEquals(8, offer.version)
        assertTrue(offer.isWindowOffer)
        assertTrue(offer.rotating)
        assertArrayEquals(hash, WindowOffer.commitment(offer))
        val bytes = DiscoveryAnnouncementCodec.encode(offer)
        assertEquals(22, bytes.size)
        assertEquals(8, bytes[0].toInt())
        // 0.2.0-beta behaviour (and any build with the switch off): "Unsupported announcement version", never a crash.
        assertThrows(IllegalArgumentException::class.java) { DiscoveryAnnouncementCodec.decode(bytes, acceptRotating = true) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryAnnouncementCodec.decode(bytes, acceptRotating = false) }
        val decoded = DiscoveryAnnouncementCodec.decode(bytes, acceptRotating = true, acceptWindow = true)
        assertEquals(offer.streamToken, decoded.streamToken)
        assertTrue(decoded.isWindowOffer)
        // Version 7 pages are not an announcement at all.
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryAnnouncementCodec.decode(NamePageCodec.pages(rdi, "Rahul")!![0], acceptRotating = true, acceptWindow = true)
        }
        assertThrows(IllegalArgumentException::class.java) { WindowOffer.announcement(rdi, ByteArray(4)) }
    }

    @Test
    fun theWindowIdIsRole3AndUnlinkableToRoles0And1ButChangesPerEpoch() {
        val node = ByteArray(16) { it.toByte() }
        val dayKey = ByteArray(32) { (it + 3).toByte() }
        val r0 = RotatingDiscoveryIds.ownEntry(dayKey, node, 100, RotatingDiscoveryIds.ROLE_CALL_OFFER).rdi
        val r1 = RotatingDiscoveryIds.ownEntry(dayKey, node, 100, RotatingDiscoveryIds.ROLE_STREAM_OFFER).rdi
        val r3 = RotatingDiscoveryIds.ownEntry(dayKey, node, 100, RotatingDiscoveryIds.ROLE_WINDOW_OFFER)
        assertEquals(3, RotatingDiscoveryIds.ROLE_WINDOW_OFFER)
        assertFalse(r3.rdi.contentEquals(r0))
        assertFalse(r3.rdi.contentEquals(r1))
        assertFalse(r3.rdi.contentEquals(RotatingDiscoveryIds.ownEntry(dayKey, node, 101, 3).rdi))
        // The opening binds the role: a role 3 entry does not open as a call offer, and vice versa.
        assertTrue(r3.opens(r3.rdi, node, expectedRole = 3))
        assertFalse(r3.opens(r3.rdi, node, expectedRole = 0))
    }

    // --- the claim rules (finding 3) ---

    @Test
    fun aNameAppearsOnlyAfterTheOfferAndAllPagesAgreeWithTheCommitment() {
        val a = assembler()
        assertFalse("pages without a heard offer are not admitted", feed(a, rdi, "Rahul"))
        assertNull(a.claimedName(rdi))
        offerFor(a, rdi, "Rahul")
        assertTrue(feed(a, rdi, "Rahul"))
        assertEquals("Rahul", a.claimedName(rdi))
        // Pages that hash to something else than the offer's commitment are not admitted.
        val b = assembler()
        offerFor(b, rdi, "Rahul")
        assertFalse(feed(b, rdi, "Police"))
        assertNull(b.claimedName(rdi))
    }

    @Test
    fun aBystanderCannotRenameAnotherIdBecauseTheOfferCarriesAnotherCommitment() {
        val a = assembler()
        offerFor(a, rdi, "Rahul")
        feed(a, rdi, "Rahul")
        assertFalse("pages for the same ID with another hash", feed(a, rdi, "Informer"))
        assertEquals("Rahul", a.claimedName(rdi))
        // A page for an ID without an offer names nothing.
        feed(a, otherRdi, "Police")
        assertNull(a.claimedName(otherRdi))
    }

    @Test
    fun aCopiedIdWithItsOwnOfferHashAndPagesShowsNoNameAndIsFlaggedAsConflicting() {
        // Finding 3: an unsigned hash does not authenticate the owner. The attacker copies the RDI and advertises its own
        // offer (own commitment) and pages. While the real owner is also heard, two commitments exist: no name at all.
        val a = assembler()
        offerFor(a, rdi, "Rahul")
        feed(a, rdi, "Rahul")
        offerFor(a, rdi, "Informer")
        feed(a, rdi, "Informer")
        assertTrue(a.conflicting(rdi))
        assertNull("no claim is shown while two phones use the ID", a.claimedName(rdi))
        // When the copy is the only one nearby it can still show its claim: the name is a claim, never a credential.
        now += NamePageAssembler.OFFER_FRESH_MS + 1
        val lonely = assembler()
        offerFor(lonely, rdi, "Informer")
        feed(lonely, rdi, "Informer")
        assertEquals("Informer", lonely.claimedName(rdi))
    }

    @Test
    fun aRecordedOfferAndPageSetReplayedLaterIsStillOnlyAClaimWhileHeardAndVanishesWhenNotHeard() {
        // Finding 4: recency is not freshness. The replay shows the same unconfirmed claim and no more; it expires.
        val a = assembler()
        offerFor(a, rdi, "Rahul")
        feed(a, rdi, "Rahul")
        assertEquals("Rahul", a.claimedName(rdi))
        now += NamePageAssembler.OFFER_FRESH_MS + 1
        assertNull(a.claimedName(rdi))
        assertEquals(0, a.sizes().first)
    }

    @Test
    fun aNameThatBreaksTheTextRulesIsNeverShown() {
        val a = assembler()
        for (bad in listOf("~Police", "Rahul - Blue Otter", "Rahul‮evil")) {
            val hash = NamePageCodec.nameHash(bad)
            val id = byteArrayOf(1, 2, 3, 4, 5, bad.length.toByte())
            a.onOffer(id, hash)
            val pages = NamePageCodec.pages(id, bad) ?: continue
            pages.forEach { a.onPage(NamePageCodec.decode(it)!!) }
            assertNull(bad, a.claimedName(id))
        }
    }

    // --- global bounds (finding 10) ---

    @Test
    fun floodsOfPagesForUnheardOrOtherHashIdsCostNoMemory() {
        val a = assembler()
        val random = Random(3)
        repeat(5_000) {
            val id = ByteArray(6).also(random::nextBytes)
            val page = NamePageCodec.decode(NamePageCodec.pages(id, "Name$it".take(10))!![0])!!
            assertFalse(a.onPage(page))
            now += 40 // stays under the page-rate bound too
        }
        assertEquals(Triple(0, 0, 0), a.sizes())
    }

    @Test
    fun rotatingDigestsAndCountsOnAHeardIdStayInsideTheAssemblyBounds() {
        val limits = NamePageAssembler.Limits(maxAssemblies = 16, maxAssembliesPerRdi = 4, maxBufferedBytes = 640, maxPagesPerSecond = 100_000)
        val a = assembler(limits)
        // The attacker's offers advertise many commitments for one ID; only one of them can ever display.
        for (i in 0 until 200) {
            val name = "Name Number $i"
            a.onOffer(rdi, NamePageCodec.nameHash(name))
            a.onPage(NamePageCodec.decode(NamePageCodec.pages(rdi, name)!![0])!!)
        }
        val (_, assemblies, bytes) = a.sizes()
        assertTrue(assemblies <= 16)
        assertTrue(bytes <= 640)
        assertTrue(a.conflicting(rdi))
        assertNull(a.claimedName(rdi))
    }

    @Test
    fun multiPageAssembliesAreCappedPerIdAndInTotal() {
        val limits = NamePageAssembler.Limits(maxPagesPerSecond = 100_000)
        val a = assembler(limits)
        val ids = (0 until 40).map { byteArrayOf(9, 9, 9, 9, 9, it.toByte()) }
        val longName = "A very long name abcdefgh" // several pages
        ids.forEach { id ->
            a.onOffer(id, NamePageCodec.nameHash(longName))
            // first page only: leaves an open assembly
            a.onPage(NamePageCodec.decode(NamePageCodec.pages(id, longName)!![0])!!)
        }
        val (rows, assemblies, bytes) = a.sizes()
        assertTrue(assemblies <= limits.maxAssemblies)
        assertTrue(bytes <= limits.maxBufferedBytes)
        assertTrue(rows <= limits.maxRows)
    }

    @Test
    fun theParseRateAndTheRowCountAreBounded() {
        val a = assembler()
        a.onOffer(rdi, NamePageCodec.nameHash("Rahul"))
        val page = NamePageCodec.decode(NamePageCodec.pages(rdi, "Rahul")!![0])!!
        var admitted = 0
        repeat(500) { if (a.onPage(page)) admitted++ } // all within the same second
        assertEquals(32, admitted)
        repeat(100) { a.onOffer(byteArrayOf(5, 5, 5, 5, 5, it.toByte()), NamePageCodec.nameHash("N$it")) }
        assertTrue(a.sizes().first <= 24)
    }

    @Test
    fun thePagesMustBeTheWholeNameInAnyOrderOrNothingShows() {
        val a = assembler()
        offerFor(a, rdi, "Rahul Kumar-Singh")
        val pages = NamePageCodec.pages(rdi, "Rahul Kumar-Singh")!!.map { NamePageCodec.decode(it)!! }
        assertTrue(pages.size > 1)
        a.onPage(pages.last())
        assertNull(a.claimedName(rdi))
        pages.dropLast(1).reversed().forEach { a.onPage(it) }
        assertEquals("Rahul Kumar-Singh", a.claimedName(rdi))
    }

    // --- opening entries while a window is open ---

    @Test
    fun theLinkOpeningHoldsAtMostTheCurrentRole0AndRole3EntriesWhileAWindowIsOpen() {
        val node = ByteArray(16) { it.toByte() }
        val dayKey = ByteArray(32) { 7 }
        fun entry(epoch: Long, role: Int) = RotatingDiscoveryIds.ownEntry(dayKey, node, epoch, role)
        val role0 = listOf(entry(11, 0), entry(10, 0))
        val role3 = listOf(entry(11, 3), entry(10, 3))
        assertEquals(role0, WindowOpening.entries(role0, role3, windowOpen = false))
        val open = WindowOpening.entries(role0, role3, windowOpen = true)
        assertEquals(listOf(role0[0], role3[0]), open)
        assertTrue(open.size <= DiscoveryOpeningCodec.MAX_ENTRIES)
        DiscoveryOpeningCodec.decode(DiscoveryOpeningCodec.encode(open)) // a 0.2.0-beta decoder accepts it
        assertEquals(listOf(role0[0]), WindowOpening.entries(role0, emptyList(), windowOpen = true))
    }
}
