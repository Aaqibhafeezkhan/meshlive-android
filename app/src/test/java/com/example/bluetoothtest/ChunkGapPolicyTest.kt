package com.example.bluetoothtest

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** win-userflow-20261019 redmi-stranger-3/-5/-12: one notification lost in the receiving host's fragmenter. */
class ChunkGapPolicyTest {
    private var nowNs = 1_000_000_000L
    private val failures = mutableListOf<String>()

    private fun pipe(fastFail: Boolean = true) = GattBytePipe(
        capacity = 8_192,
        gapFastFail = { fastFail },
        nanoTime = { nowNs },
        onFailure = failures::add,
    )

    private fun packets(count: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val writer = object : ChunkedGattOutputStream() {
            override val payloadBytes = 1
            override fun sendPacket(value: ByteArray) {
                out += value
            }
        }
        writer.write(ByteArray(count) { it.toByte() })
        return out
    }

    @Test
    fun aGapHeldForOneSecondEndsTheStreamWithItsReason() {
        val pipe = pipe()
        val sent = packets(4)
        assertTrue(handleIncoming(pipe, sent[0]))
        assertEquals(0, pipe.read())
        // Chunk 1 is lost in the host; chunk 2 parks behind it.
        assertTrue(handleIncoming(pipe, sent[2]))
        nowNs += 999_000_000L
        assertTrue(handleIncoming(pipe, sent[3].copyOf().also { it[4] = 3 }))
        nowNs += 1_000_000L
        val later = sent[3].copyOf().also { it[4] = 4 }

        assertFalse(handleIncoming(pipe, later))
        assertEquals(listOf("GATT chunk 1 was lost (3 later chunks waited 1000 ms)"), failures)
        try {
            pipe.read()
            fail("expected the lost-chunk reason")
        } catch (error: IOException) {
            assertEquals("GATT chunk 1 was lost (3 later chunks waited 1000 ms)", error.message)
        }
    }

    @Test
    fun aGapHoldingTheChunkLimitEndsTheStreamBeforeTheTimeLimit() {
        val pipe = pipe()
        val sent = packets(ChunkGapPolicy.GAP_CHUNKS + 1)
        for (index in 1 until ChunkGapPolicy.GAP_CHUNKS) assertTrue(handleIncoming(pipe, sent[index]))

        assertFalse(handleIncoming(pipe, sent[ChunkGapPolicy.GAP_CHUNKS]))
        assertEquals(listOf("GATT chunk 0 was lost (${ChunkGapPolicy.GAP_CHUNKS} later chunks waited 0 ms)"), failures)
    }

    @Test
    fun aBriefReorderStillDeliversEveryByteAndResetsTheGapClock() {
        val pipe = pipe()
        val sent = packets(6)
        assertTrue(handleIncoming(pipe, sent[1]))
        nowNs += 900_000_000L
        assertTrue(handleIncoming(pipe, sent[0]))
        // A second gap later starts its own clock instead of inheriting the first one's 900 ms.
        assertTrue(handleIncoming(pipe, sent[3]))
        nowNs += 900_000_000L
        assertTrue(handleIncoming(pipe, sent[4]))
        assertTrue(handleIncoming(pipe, sent[2]))
        assertTrue(handleIncoming(pipe, sent[5]))

        val received = ByteArray(6)
        for (index in received.indices) received[index] = pipe.read().toByte()
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), received)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aGapLeftBehindAFilledOneStartsItsOwnClock() {
        val pipe = pipe()
        val sent = packets(6)
        assertTrue(handleIncoming(pipe, sent[1]))
        assertTrue(handleIncoming(pipe, sent[3]))
        nowNs += 900_000_000L
        assertTrue(handleIncoming(pipe, sent[0]))
        nowNs += 900_000_000L
        assertTrue(handleIncoming(pipe, sent[4]))
        assertTrue(handleIncoming(pipe, sent[2]))
        assertTrue(handleIncoming(pipe, sent[5]))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun theControlArmKeepsWaitingForTheMissingChunk() {
        val pipe = pipe(fastFail = false)
        val sent = packets(ChunkGapPolicy.GAP_CHUNKS + 2)
        assertTrue(handleIncoming(pipe, sent[1]))
        nowNs += 10_000_000_000L
        for (index in 2..ChunkGapPolicy.GAP_CHUNKS + 1) assertTrue(handleIncoming(pipe, sent[index]))

        assertTrue(failures.isEmpty())
        assertEquals(0, pipe.available())
    }
}
