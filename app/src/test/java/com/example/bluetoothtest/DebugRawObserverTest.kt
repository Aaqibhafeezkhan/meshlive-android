package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRawObserverTest {
    // A service-data AD structure (length 7: type 0x16 plus 6 data bytes) carrying fa21b92f.
    private val section = "0716" + "0aaa" + "fa21b92f"
    private val raw = "020106" + section + "03094d" + "00"

    @Test
    fun hexIsLowerCasePairs() {
        assertEquals("00ff1a", RawObserverCore.hex(byteArrayOf(0, -1, 0x1a)))
    }

    @Test
    fun matchIsNormalizedAndRejectsNonHex() {
        assertEquals("fa21b92f", RawObserverCore.normalizeMatch("FA:21 b9 2F"))
        assertNull(RawObserverCore.normalizeMatch(""))
        assertNull(RawObserverCore.normalizeMatch(null))
        assertNull(RawObserverCore.normalizeMatch("xyz"))
    }

    @Test
    fun nonMatchingResultIsIgnoredAndLeavesNoSummary() {
        val core = RawObserverCore("fa21b92f")
        assertNull(core.onResult("AA", "0201060303", 0))
        assertTrue(core.summaryLine(1).contains("payload=none"))
        assertTrue(core.summaryLine(1).contains("addresses=[]"))
    }

    @Test
    fun matchingResultIsLoggedOncePerAddressPerSecond() {
        val core = RawObserverCore("fa21b92f")
        assertNotNull(core.onResult("AA", raw.uppercase(), 1000))
        assertNull(core.onResult("AA", raw, 1500))
        assertNotNull(core.onResult("BB", raw, 1500)) // another address is independent
        assertNull(core.onResult("AA", raw, 1999))
        assertNotNull(core.onResult("AA", raw, 2000))
    }

    @Test
    fun summaryAggregatesAllMatchesEvenWhenRateLimited() {
        val core = RawObserverCore("fa21b92f")
        core.onResult("AA", raw, 1000)
        core.onResult("AA", raw, 1200) // suppressed but still counted
        core.onResult("BB", raw, 61_000) // rotated address later
        val s = core.summaryLine(62_000)
        assertTrue(s, s.contains("payload=$section"))
        assertTrue(s, s.contains("AA(first=1000,last=1200,count=2)"))
        assertTrue(s, s.contains("BB(first=61000,last=61000,count=1)"))
    }

    @Test
    fun sectionExtractionHandlesMalformedData() {
        assertEquals(section, RawObserverCore.extractSection(raw, "fa21b92f"))
        assertNull(RawObserverCore.extractSection("0816fa", "fa21"))
        assertNull(RawObserverCore.extractSection("0000fa21", "fa21"))
        // A match spanning two structures is not inside one section.
        assertNull(RawObserverCore.extractSection("0116" + "01fa", "1601"))
    }

    @Test
    fun createIsNullInReleaseOrWithoutExtra() {
        assertNull(DebugRawObserver.create(false, null, "fa21b92f", null))
        assertNull(DebugRawObserver.create(true, null, null, null))
        assertNotNull(DebugRawObserver.create(true, null, "fa21b92f", null))
    }
}
