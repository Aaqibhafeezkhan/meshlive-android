package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the shared `GR1` vectors (tests/vectors/gr1_v1.json, produced by the Windows implementation) on Android. */
class Gr1VectorTest {
    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Suppress("UNCHECKED_CAST")
    private val root: Map<String, Any?> by lazy {
        val path = "tests/vectors/gr1_v1.json"
        val file = listOf(File("../$path"), File(path)).firstOrNull { it.isFile } ?: error("missing $path")
        val parsed = TestJson.parse(file.readText(Charsets.UTF_8)) as Map<String, Any?>
        assertEquals("gr1_v1", parsed["format"])
        parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String): List<Map<String, Any?>> = root[key] as List<Map<String, Any?>>

    private fun int(value: Any?): Int = (value as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun ranges(value: Any?): List<Gr1.SeqRange> =
        (value as List<List<Any?>>).map { Gr1.SeqRange(int(it[0]), int(it[1])) }

    @Test
    fun theConstantsMatchExactly() {
        @Suppress("UNCHECKED_CAST")
        val constants = root["constants"] as Map<String, Any?>
        val android = mapOf(
            "historyMs" to Gr1.HISTORY_MS.toLong(),
            "historyValues" to Gr1.HISTORY_VALUES.toLong(),
            "historyBytes" to Gr1.HISTORY_BYTES.toLong(),
            "helloRepeatMs" to Gr1.HELLO_REPEAT_MS,
            "roundMs" to Gr1.ROUND_MS,
            "maxRequests" to Gr1.MAX_REQUESTS.toLong(),
            "deadlineMs" to Gr1.DEADLINE_MS,
            "readerNacksPerSecond" to Gr1.READER_NACKS_PER_SECOND.toLong(),
            "resendsPerSequence" to Gr1.RESENDS_PER_SEQUENCE.toLong(),
            "resendBudgetPercent" to Gr1.RESEND_BUDGET_PERCENT.toLong(),
            "resendFloorBytesPerSecond" to Gr1.RESEND_FLOOR_BYTES_PER_SECOND.toLong(),
            "highIdleMs" to Gr1.HIGH_IDLE_MS,
            "highPerSecond" to Gr1.HIGH_PER_SECOND.toLong(),
            "maxOutOfRangeNackRanges" to Gr1.MAX_OUT_OF_RANGE_NACK_RANGES.toLong(),
            "maxRanges" to Gr1.MAX_RANGES.toLong(),
            "maxRun" to Gr1.MAX_RUN.toLong(),
            "gapFastFailMs" to ChunkGapPolicy.GAP_MS,
            "gapFastFailChunks" to ChunkGapPolicy.GAP_CHUNKS.toLong(),
        )
        assertEquals(android.keys, constants.keys)
        for ((name, value) in android) assertEquals(name, value, (constants[name] as Number).toLong())
    }

    @Test
    fun theNackRangesFittingTableMatches() {
        @Suppress("UNCHECKED_CAST")
        val table = root["nackRangesFitting"] as List<List<Any?>>
        assertTrue(table.isNotEmpty())
        for (row in table) assertEquals("value limit ${row[0]}", int(row[1]), Gr1.nackRangesFitting(int(row[0])))
    }

    @Test
    fun everyValidVectorDecodesToItsFieldsAndReencodesToTheSameBytes() {
        val valid = list("valid")
        assertEquals(9, valid.size)
        for (vector in valid) {
            val name = vector["name"] as String
            val bytes = hex(vector["hex"] as String)
            val decoded = Gr1.decode(bytes)
            when (vector["op"]) {
                "hello" -> {
                    val expected = Gr1.Message.Hello(int(vector["historyValues"]), int(vector["historyBytes"]), int(vector["historyMs"]))
                    assertEquals(name, expected, decoded)
                    // A clamped HELLO cannot round-trip; the others re-encode exactly.
                    if (name != "hello_oversized_clamped") {
                        assertArrayEquals(name, bytes, Gr1.hello(expected.historyValues, expected.historyBytes, expected.historyMs))
                    }
                }
                "nack" -> {
                    val expected = Gr1.Message.Nack(ranges(vector["ranges"]))
                    assertEquals(name, expected, decoded)
                    assertArrayEquals(name, bytes, Gr1.nack(expected.ranges))
                }
                "gone" -> {
                    val expected = Gr1.Message.Gone(Gr1.SeqRange(int(vector["first"]), int(vector["run"])))
                    assertEquals(name, expected, decoded)
                    assertArrayEquals(name, bytes, Gr1.gone(expected.range))
                }
                "high" -> {
                    val expected = Gr1.Message.High(int(vector["sequence"]))
                    assertEquals(name, expected, decoded)
                    assertArrayEquals(name, bytes, Gr1.high(expected.sequence))
                }
                else -> throw AssertionError("$name: unknown op ${vector["op"]}")
            }
        }
    }

    @Test
    fun everyIgnoredVectorDecodesToNothingAndLeavesThePipeAlone() {
        val ignored = list("ignored")
        assertEquals(14, ignored.size)
        for (vector in ignored) {
            val name = vector["name"] as String
            val bytes = hex(vector["hex"] as String)
            assertNull(name, Gr1.decode(bytes))
            // On a link with recovery on, the value is ignored without failing it.
            val failures = mutableListOf<String>()
            val link = GattChunkRecovery("vector", log = {}, startThread = false)
            val pipe = GattBytePipe(recovery = link, dropEvery = 0, debugLog = {}, onFailure = failures::add)
            assertTrue(name, handleIncoming(pipe, bytes))
            assertNull(name, link.peerHello)
            assertTrue(name, failures.isEmpty())
            assertEquals(name, 0, pipe.available())
        }
    }
}
