package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayDutySupportTest {
    @Test
    fun theParentStreamsOfAnOnDemandRelayAreEmptyAndDiscarding() {
        val input = RelayParentStreams.emptyInput()
        val buffer = ByteArray(8)
        assertEquals(-1, input.read())
        assertEquals(-1, input.read(buffer))
        assertEquals(-1, input.read(buffer, 2, 4))
        assertEquals(0, input.read(buffer, 0, 0))
        assertEquals(0, input.available())
        input.close()

        val output = RelayParentStreams.discardingOutput()
        output.write(7)
        output.write(buffer)
        output.write(buffer, 4, 4)
        output.flush()
        output.close()
        assertArrayEquals(ByteArray(8), buffer)
    }

    @Test
    fun theParentStreamsKeepTheStreamContractForBadRanges() {
        val buffer = ByteArray(4)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 3 to 2, 5 to 0)) {
            try {
                RelayParentStreams.emptyInput().read(buffer, offset, length)
                fail("read $offset/$length")
            } catch (_: IndexOutOfBoundsException) {
                // expected
            }
            try {
                RelayParentStreams.discardingOutput().write(buffer, offset, length)
                fail("write $offset/$length")
            } catch (_: IndexOutOfBoundsException) {
                // expected
            }
        }
    }

    @Test
    fun anErrorIsContainedAsAnExceptionAndAnExceptionPassesThrough() {
        val io = java.io.IOException("link lost")
        assertSame(io, io.asRelayDutyException())

        val missing = NoSuchMethodError("java.io.InputStream.nullInputStream()")
        val contained = missing.asRelayDutyException()
        assertTrue(contained is RelayDutyError)
        assertSame(missing, contained!!.cause)
        assertTrue("the status line still names the Error", contained.message!!.contains("NoSuchMethodError"))
        assertTrue(contained.message!!.contains("nullInputStream"))

        assertTrue(AssertionError("x").asRelayDutyException() is RelayDutyError)
        assertTrue(NoClassDefFoundError("x").asRelayDutyException() is RelayDutyError)
    }

    @Test
    fun jvmLevelFailuresAreNotMasked() {
        assertNull(OutOfMemoryError().asRelayDutyException())
        assertNull(StackOverflowError().asRelayDutyException())
        assertNull(InternalError().asRelayDutyException())
    }
}
