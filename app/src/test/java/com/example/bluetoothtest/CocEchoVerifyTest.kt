package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-05 (Experimental, default off): 1 KB echo before a CoC socket is used. */
class CocEchoVerifyTest {
    private val lines = ArrayList<String>()
    private val noCloser = Closeable {}

    private fun payload(seed: Int) = ByteArray(CocEchoVerify.ECHO_BYTES) { (it * 7 + seed).toByte() }

    /** An input stream whose read blocks until closed, like a silent socket. */
    private class SilentStream : InputStream(), Closeable {
        private val closed = CountDownLatch(1)
        override fun read(): Int {
            closed.await(30, TimeUnit.SECONDS)
            throw IOException("closed")
        }
        override fun close() = closed.countDown()
    }

    @Test
    fun theFrameCarriesMagicAndLengthThenThePayload() {
        val frame = CocEchoVerify.frame(payload(1))
        assertEquals(4 + 2 + CocEchoVerify.ECHO_BYTES, frame.size)
        assertEquals("MLEC", String(frame, 0, 4, Charsets.US_ASCII))
        assertEquals(CocEchoVerify.ECHO_BYTES, ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF))
        assertThrows(IllegalArgumentException::class.java) { CocEchoVerify.frame(ByteArray(10)) }
    }

    @Test
    fun anEchoOfTheSameBytesVerifies() {
        val sent = payload(3)
        val out = ByteArrayOutputStream()
        val result = CocEchoVerify.initiate(
            ByteArrayInputStream(CocEchoVerify.frame(sent)), out, noCloser, "o", { lines += it }, fill = { sent.copyInto(it) },
        )
        assertTrue(result.reason, result.ok)
        assertArrayEquals("what went out is the same frame", CocEchoVerify.frame(sent), out.toByteArray())
        assertTrue(lines.single(), "side=initiator" in lines.single() && "ok=true" in lines.single())
    }

    @Test
    fun aDifferentEchoIsAMismatch() {
        val result = CocEchoVerify.initiate(
            ByteArrayInputStream(CocEchoVerify.frame(payload(9))), ByteArrayOutputStream(), noCloser, "o", { lines += it },
            fill = { payload(3).copyInto(it) },
        )
        assertFalse(result.ok)
        assertEquals("payload_mismatch", result.reason)
        assertFalse(CocEchoVerify.failure(result).message.isNullOrBlank())
    }

    @Test
    fun aPeerThatSendsSomethingElseIsRefusedBeforeAnyPayloadIsRead() {
        val badMagic = ByteArray(6 + CocEchoVerify.ECHO_BYTES).also { it[0] = 'X'.code.toByte() }
        assertEquals(false, CocEchoVerify.initiate(ByteArrayInputStream(badMagic), ByteArrayOutputStream(), noCloser, "o", {}).ok)
        // Right magic, wrong length: a peer cannot make the listener allocate or wait for more.
        val badLength = byteArrayOf('M'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val result = CocEchoVerify.respond(ByteArrayInputStream(badLength), ByteArrayOutputStream(), noCloser, "o", { lines += it })
        assertFalse(result.ok)
        assertTrue(result.reason, "io_" in result.reason)
        assertFalse("a short stream is a failure, not a hang", CocEchoVerify.respond(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream(), noCloser, "o", {}).ok)
    }

    @Test
    fun aSilentPeerCostsAtMostTheTimeoutAndTheSocketIsClosed() {
        val silent = SilentStream()
        val started = System.nanoTime()
        val result = CocEchoVerify.initiate(silent, ByteArrayOutputStream(), silent, "o", { lines += it }, timeoutMs = 120L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertFalse(result.ok)
        assertEquals("timeout", result.reason)
        assertTrue("elapsed $elapsedMs", elapsedMs in 100L..5_000L)
    }

    @Test
    fun theResponderEchoesTheRequestUnchanged() {
        val request = payload(5)
        val out = ByteArrayOutputStream()
        val result = CocEchoVerify.respond(ByteArrayInputStream(CocEchoVerify.frame(request)), out, noCloser, "o", { lines += it })
        assertTrue(result.reason, result.ok)
        assertArrayEquals(CocEchoVerify.frame(request), out.toByteArray())
    }

    @Test
    fun aRealRoundTripOverTwoPipesVerifiesAndTheRandomBlocksDiffer() {
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer, 4096)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient, 4096)
        val serverResult = AtomicReference<CocEchoVerify.Result>()
        val server = thread { serverResult.set(CocEchoVerify.respond(serverIn, serverToClient, noCloser, "srv", {})) }
        val client = CocEchoVerify.initiate(clientIn, clientToServer, noCloser, "cli", {})
        server.join(5_000)
        assertTrue(client.reason, client.ok)
        assertTrue(serverResult.get().ok)
        val a = ByteArray(CocEchoVerify.ECHO_BYTES).also { kotlin.random.Random.Default.nextBytes(it) }
        val b = ByteArray(CocEchoVerify.ECHO_BYTES).also { kotlin.random.Random.Default.nextBytes(it) }
        assertFalse("the default fill is random per connection", a.contentEquals(b))
    }

    @Test
    fun theSwitchAndTheRegistryEntryBothGateTheEcho() {
        val on = { CocPolicy.resolve(DeviceTraits("qcom", "x", 33), emptySet()) }
        assertFalse(CocEchoVerify.enabled(switchOn = false, policy = on))
        assertTrue(CocEchoVerify.enabled(switchOn = true, policy = on))
        assertFalse(CocEchoVerify.enabled(switchOn = true, policy = { CocPolicy.resolve(DeviceTraits("qcom", "x", 33), setOf("coc-echo-verification")) }))
    }

    /** A lease over a queue of fake sockets. */
    private class FakeLease(private val sockets: ArrayDeque<FakeSocket>) : CocListenLease<FakeSocket> {
        override val psm = 133
        override val shared = false
        var closed = false
        override fun accept(): FakeSocket = sockets.removeFirstOrNull() ?: throw IOException("lease empty")
        override fun accept(timeoutMs: Long): FakeSocket = sockets.removeFirstOrNull() ?: throw IOException("accept timed out")
        override fun close() { closed = true }
    }

    private class FakeSocket(val answers: Boolean) : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() = closed.set(true)
    }

    @Test
    fun theListenerClosesAConnectionThatDoesNotEchoAndHandsOverTheNextOne() {
        val bad = FakeSocket(answers = false)
        val good = FakeSocket(answers = true)
        val lease = EchoRespondingLease(FakeLease(ArrayDeque(listOf(bad, good)))) { it.answers }
        assertSame(good, lease.accept())
        assertTrue("the silent connection is closed", bad.closed.get())
        assertFalse(good.closed.get())
        assertEquals(133, lease.psm)
    }

    @Test
    fun theTimedAcceptKeepsItsDeadlineAcrossRefusedConnections() {
        val bad = FakeSocket(answers = false)
        val lease = EchoRespondingLease(FakeLease(ArrayDeque(listOf(bad)))) { it.answers }
        val error = assertThrows(IOException::class.java) { lease.accept(500L) }
        assertTrue(error.message, "timed out" in error.message.orEmpty())
        assertTrue(bad.closed.get())
        val good = FakeSocket(true)
        assertSame(good, EchoRespondingLease(FakeLease(ArrayDeque(listOf(good)))) { it.answers }.accept(500L))
    }

    @Test
    fun aRespondThatThrowsRefusesTheConnectionInsteadOfEscaping() {
        val socket = FakeSocket(true)
        val lease = EchoRespondingLease(FakeLease(ArrayDeque(listOf(socket)))) { error("stream broke") }
        assertThrows(IOException::class.java) { lease.accept() }
        assertTrue(socket.closed.get())
    }
}
