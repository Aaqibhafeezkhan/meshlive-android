package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCallsTest {
    private val a = "8a1b2c3d4e5f"
    private val b = "9c8d7e6f5a40"

    @Test
    fun newestFirstOnePerPersonCapped() {
        var calls = emptyList<RecentCall>()
        calls = RecentCalls.add(calls, RecentCall(a, video = true, atMs = 1))
        calls = RecentCalls.add(calls, RecentCall(b, video = false, atMs = 2))
        calls = RecentCalls.add(calls, RecentCall(a, video = false, atMs = 3))
        assertEquals(listOf(a, b), calls.map { it.nodePrefixHex })
        assertEquals(false, calls.first().video)
        repeat(10) { calls = RecentCalls.add(calls, RecentCall("%012x".format(it), true, 10L + it)) }
        assertEquals(RecentCalls.MAX_ENTRIES, calls.size)
    }

    private val fingerprint = a + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".substring(12)

    @Test
    fun theFullIdentityRoundTripsAndOldThreeFieldLinesStillLoad() {
        val calls = listOf(RecentCall(a, true, 5, fingerprintHex = fingerprint), RecentCall(b, false, 6))
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        assertEquals(listOf(RecentCall(a, true, 9)), RecentCalls.decode("$a,v,9"))
        // A fingerprint that does not start with the row's prefix, or is not 64 hex digits, is junk.
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$b,v,9,$fingerprint"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$a,v,9,abc"))
    }

    @Test
    fun aBlockedCallIsOneAnonymousRowAndNeverReplacesAPerson() {
        var calls = listOf(RecentCall(a, true, 1, fingerprintHex = fingerprint))
        calls = RecentCalls.add(calls, RecentCall("", false, 2, blocked = true))
        calls = RecentCalls.add(calls, RecentCall("", false, 3, blocked = true))
        assertEquals(listOf(true, false), calls.map { it.blocked })
        assertEquals(3L, calls.first().atMs)
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        // A person calling again does not remove the blocked row either.
        calls = RecentCalls.add(calls, RecentCall(a, false, 4, fingerprintHex = fingerprint))
        assertEquals(listOf(false, true), calls.map { it.blocked })
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$a,v,9,,b"))
    }

    @Test
    fun encodeDecodeRoundTripAndRejectsJunk() {
        val calls = listOf(RecentCall(a, true, 5), RecentCall(b, false, 6))
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("not a call"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("xyz,v,1"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(null))
    }

    @Test
    fun aStrangerIsRememberedByTheGeneratedNameAndTheTimeOnly() {
        val stranger = RecentCall("", true, 7, strangerName = "gold fox")
        val encoded = RecentCalls.encode(listOf(stranger))
        assertEquals(",v,7,,s,gold fox", encoded)
        assertEquals(listOf(stranger), RecentCalls.decode(encoded))
        // No keys, prefix or fingerprint can ride along, and junk names are dropped.
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$a,v,7,,s,gold fox"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(",v,7,$fingerprint,s,gold fox"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(",v,7,,s,Gold Fox"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(",v,7,,s,gold fox,x"))
        assertEquals(listOf("gold fox 42-7a7d"), RecentCalls.decode(",a,7,,s,gold fox 42-7a7d").map { it.strangerName })
    }

    @Test
    fun strangersDedupeByNameAndNeverReplacePeopleOrBlockedRows() {
        var calls = listOf(RecentCall(a, true, 1, fingerprintHex = fingerprint), RecentCall("", false, 2, blocked = true))
        calls = RecentCalls.add(calls, RecentCall("", true, 3, strangerName = "gold fox"))
        calls = RecentCalls.add(calls, RecentCall("", true, 4, strangerName = "blue otter"))
        calls = RecentCalls.add(calls, RecentCall("", false, 5, strangerName = "gold fox"))
        assertEquals(listOf("gold fox", "blue otter", "", ""), calls.map { it.strangerName })
        assertEquals(listOf(5L, 4L, 1L, 2L), calls.map { it.atMs })
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
    }
}
