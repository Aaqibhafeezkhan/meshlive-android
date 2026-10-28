package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.GeneralSecurityException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A failed hop record still ends the link exactly as before; the `hop_integrity` line says what failed and what came
 * next, so a byte changed in place (next header intact) can be told from an SDU lost below the app (stream shifted).
 * The user-visible message carries no record type, sequence or size.
 */
class HopIntegrityLogTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
    private val lines = mutableListOf<String>()
    private lateinit var previousSink: (String) -> Unit

    @Before
    fun captureLog() {
        previousSink = HopIntegrityLog.sink
        HopIntegrityLog.sink = { synchronized(lines) { lines += it } }
    }

    @After
    fun restoreLog() {
        HopIntegrityLog.sink = previousSink
    }

    /** Three 900-byte records as a CoC sender writes them: each record flushed, cut into 245-byte SDUs. */
    private fun sdusOfThreeRecords(): List<List<ByteArray>> {
        val sdus = mutableListOf<ByteArray>()
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) = error("single-byte write")
            override fun write(b: ByteArray, off: Int, len: Int) {
                sdus += b.copyOfRange(off, off + len)
            }
        }
        val writer = SecureRecordWriter(ChunkedOutputStream(sink, COC_SINGLE_PDU_SDU_BYTES), key)
        return (0 until 3).map { index ->
            sdus.clear()
            writer.write(SecureRecordType.END_TO_END_CONTENT, ByteArray(900) { (it + index).toByte() })
            sdus.toList()
        }
    }

    private fun bytes(records: List<List<ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { out -> records.flatten().forEach(out::write) }.toByteArray()

    private fun onlyLine(): String = synchronized(lines) { lines.single() }

    @Test
    fun aByteChangedInPlaceLeavesTheNextHeaderIntact() {
        val records = sdusOfThreeRecords()
        val wire = bytes(records)
        wire[100] = (wire[100].toInt() xor 0x01).toByte()
        val reader = SecureRecordReader(ByteArrayInputStream(wire), key)

        val failure = assertThrows(GeneralSecurityException::class.java) { reader.read() }

        assertEquals("Secure link decrypt failed", failure.message)
        val line = onlyLine()
        assertTrue(line, line.contains("hop_integrity: kind=tag | type=END_TO_END_CONTENT | sequence=0 | ciphertextBytes=916"))
        assertTrue(line, line.contains("next_header=valid_n1 | next_sequence=1"))
    }

    @Test
    fun aLostMiddleSduShiftsTheStream() {
        val records = sdusOfThreeRecords()
        val lossy = listOf(records[0].filterIndexed { index, _ -> index != 1 }, records[1], records[2])
        val reader = SecureRecordReader(ByteArrayInputStream(bytes(lossy)), key)

        assertThrows(GeneralSecurityException::class.java) { reader.read() }

        val line = onlyLine()
        assertTrue(line, line.contains("kind=tag | type=END_TO_END_CONTENT | sequence=0"))
        assertTrue(line, line.contains("next_header=invalid"))
    }

    @Test
    fun aWholeLostRecordIsASequenceGapWithANumberFreeMessage() {
        val records = sdusOfThreeRecords()
        val reader = SecureRecordReader(ByteArrayInputStream(bytes(listOf(records[0], records[2]))), key)
        assertEquals(900, reader.read().payload.size)

        val failure = assertThrows(IllegalArgumentException::class.java) { reader.read() }

        assertEquals("Unexpected secure record sequence", failure.message)
        assertFalse(failure.message.orEmpty().any(Char::isDigit))
        assertTrue(onlyLine(), onlyLine().contains("kind=sequence | type=END_TO_END_CONTENT | sequence=2 | expected=1 | gap=1"))
    }

    @Test
    fun aDamagedHeaderIsLoggedWithItsRawFields() {
        val wire = bytes(sdusOfThreeRecords())
        wire[0] = 'X'.code.toByte()
        val reader = SecureRecordReader(ByteArrayInputStream(wire), key)

        val failure = assertThrows(IllegalArgumentException::class.java) { reader.read() }

        assertEquals("Invalid secure record magic", failure.message)
        val line = onlyLine()
        assertTrue(line, line.contains("kind=header | reason=Invalid secure record magic | magic=bad | rawType=5 | rawSequence=0"))
        assertTrue(line, line.contains("expected=0"))
    }

    @Test
    fun thePeekNeverWaitsForBytesThatHaveNotArrived() {
        val wire = bytes(sdusOfThreeRecords()).also { it[100] = (it[100].toInt() xor 0x01).toByte() }
        // The next records are "on the way": available() reports nothing beyond the failed record, so a read of
        // them would block. The reader must log `unavailable` instead of reading.
        val firstRecordBytes = 17 + 916
        val holdsBack = object : FilterInputStream(ByteArrayInputStream(wire)) {
            var position = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val allowed = minOf(len, firstRecordBytes - position)
                check(allowed > 0) { "read past the failed record: this would block on a socket" }
                return super.read(b, off, allowed).also { if (it > 0) position += it }
            }
            override fun read(): Int = error("single-byte read")
            override fun available(): Int = maxOf(0, firstRecordBytes - position)
        }
        val reader = SecureRecordReader(holdsBack as InputStream, key)

        assertThrows(GeneralSecurityException::class.java) { reader.read() }

        assertTrue(onlyLine(), onlyLine().contains("next_header=unavailable | available=0"))
    }
}
