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

    @Test
    fun encodeDecodeRoundTripAndRejectsJunk() {
        val calls = listOf(RecentCall(a, true, 5), RecentCall(b, false, 6))
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("not a call"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("xyz,v,1"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(null))
    }
}
