package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayForwardCountersTest {
    @Test
    fun aRecordReadButNeverWrittenShowsInTheCloseLine() {
        val counters = RelayForwardCounters()
        val content = SecureRecord(SecureRecordType.END_TO_END_CONTENT, ByteArray(881))
        val heartbeat = SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
        repeat(3) {
            counters.onRead()
            counters.onForwarded(content)
        }
        counters.onRead()
        counters.onForwarded(heartbeat)
        // The next record was read, then its write to the child failed.
        counters.onRead()

        assertEquals(5, counters.read)
        assertEquals(4, counters.forwarded)
        assertEquals(3L * 881, counters.contentBytes)
        assertEquals(1, counters.heartbeats)
        val line = counters.line("caller", stage = "write", ended = java.io.IOException("broken pipe"), sourceRead = 5, destinationWritten = 4)
        assertTrue(line, line.contains(
            "relay_forwarded: direction=caller | read=5 | forwarded=4 | contentBytes=2643 | heartbeats=1 | " +
                "sourceRecordsRead=5 | destinationRecordsWritten=4 | stage=write | ended=IOException",
        ))
    }
}
