package com.example.bluetoothtest

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Experimental (`test_coc_echo_verify=true`, default off; BACKLOG C-05): an advertised CoC capability
 * is not proof that the channel carries data, so a new CoC socket must round-trip a 1 KB echo before
 * it is used. Berty's Android driver does the same (a 1024-byte echo before it marks L2CAP usable,
 * GATT otherwise).
 *
 * Wire (only between phones that both run the switch; it is the first thing on the socket, before
 * the BMH2 hop handshake): `"MLEC"` (4 bytes) + length (u16, big endian, always 1024) + the payload.
 * The client sends a random block, the listener answers with the same frame, the client compares.
 * The frame carries no key, no identity and no state; trust still comes only from the handshake that
 * follows. A phone without the switch simply fails the handshake of a switch-on peer, so the attempt
 * fails and the existing retry / GATT fallback applies, as for any CoC connect failure.
 *
 * A deadline closes the socket if the peer never answers, so a silent peer costs at most [TIMEOUT_MS].
 */
internal object CocEchoVerify {
    const val ECHO_BYTES = 1024
    const val TIMEOUT_MS = 4_000L
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
    private const val HEADER_BYTES = 6

    data class Result(val ok: Boolean, val reason: String, val elapsedMs: Long)

    fun enabled(
        switchOn: Boolean = CocSwitches.echoVerify,
        policy: () -> ResolvedDevicePolicy = CocPolicy::current,
    ): Boolean = switchOn && policy().has<CocEchoVerification>()

    /** The connecting side: sends the block and checks the echo. [closer] is closed if the deadline passes. */
    fun initiate(
        input: InputStream,
        output: OutputStream,
        closer: Closeable,
        owner: String,
        log: (String) -> Unit,
        timeoutMs: Long = TIMEOUT_MS,
        nowMs: () -> Long = System::currentTimeMillis,
        fill: (ByteArray) -> Unit = { Random.Default.nextBytes(it) },
    ): Result {
        val startedMs = nowMs()
        val deadline = CocDeadline("CocEchoVerify", timeoutMs) { runCatching { closer.close() } }
        val result = try {
            val payload = ByteArray(ECHO_BYTES).also(fill)
            output.write(frame(payload))
            output.flush()
            val echoed = readFrame(DataInputStream(input))
            if (echoed.contentEquals(payload)) {
                Result(true, "ok", nowMs() - startedMs)
            } else {
                Result(false, "payload_mismatch", nowMs() - startedMs)
            }
        } catch (error: IOException) {
            val reason = if (deadline.expired) "timeout" else "io_${error.javaClass.simpleName}"
            Result(false, reason, nowMs() - startedMs)
        } finally {
            deadline.cancel()
        }
        log(logLine("initiator", owner, result))
        return result
    }

    /** The listening side: reads one frame and sends it back unchanged. */
    fun respond(
        input: InputStream,
        output: OutputStream,
        closer: Closeable,
        owner: String,
        log: (String) -> Unit,
        timeoutMs: Long = TIMEOUT_MS,
        nowMs: () -> Long = System::currentTimeMillis,
    ): Result {
        val startedMs = nowMs()
        val deadline = CocDeadline("CocEchoRespond", timeoutMs) { runCatching { closer.close() } }
        val result = try {
            val payload = readFrame(DataInputStream(input))
            output.write(frame(payload))
            output.flush()
            Result(true, "ok", nowMs() - startedMs)
        } catch (error: IOException) {
            val reason = if (deadline.expired) "timeout" else "io_${error.javaClass.simpleName}"
            Result(false, reason, nowMs() - startedMs)
        } finally {
            deadline.cancel()
        }
        log(logLine("responder", owner, result))
        return result
    }

    internal fun frame(payload: ByteArray): ByteArray {
        require(payload.size == ECHO_BYTES) { "echo payload must be $ECHO_BYTES bytes" }
        return MAGIC + byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
    }

    /** Reads one frame; a wrong magic or length is refused before the payload is read (no allocation by the peer). */
    private fun readFrame(input: DataInputStream): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        try {
            input.readFully(header)
        } catch (eof: EOFException) {
            throw IOException("echo: socket closed before the header", eof)
        }
        if (!header.copyOf(MAGIC.size).contentEquals(MAGIC)) throw IOException("echo: bad magic")
        val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        if (length != ECHO_BYTES) throw IOException("echo: bad length $length")
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (eof: EOFException) {
            throw IOException("echo: socket closed inside the payload", eof)
        }
        return payload
    }

    private fun logLine(side: String, owner: String, result: Result): String =
        "BENCHMARK coc_echo: side=$side | owner=$owner | ok=${result.ok} | reason=${result.reason} | " +
            "elapsedMs=${result.elapsedMs} | bytes=$ECHO_BYTES"

    /** The failure the client throws after closing the socket; the callers' fallback treats it as a failed connect. */
    fun failure(result: Result): IOException =
        IOException("LE CoC echo verification failed (${result.reason}) after ${result.elapsedMs} ms")
}

/**
 * Listener side of the echo: every accepted socket answers the echo before the session sees it; one
 * that fails is closed and the next connection is awaited. Applied by [CocListeners.listen] only while
 * the switch is on.
 */
internal class EchoRespondingLease<S : Closeable>(
    private val inner: CocListenLease<S>,
    private val respond: (S) -> Boolean,
) : CocListenLease<S> {
    override val psm: Int get() = inner.psm
    override val shared: Boolean get() = inner.shared

    override fun accept(): S {
        while (true) {
            val socket = inner.accept()
            if (verified(socket)) return socket
        }
    }

    override fun accept(timeoutMs: Long): S {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
            if (remainingMs <= 0L) throw IOException("LE CoC accept timed out")
            val socket = inner.accept(remainingMs)
            if (verified(socket)) return socket
        }
    }

    override fun close() = inner.close()

    private fun verified(socket: S): Boolean {
        if (runCatching { respond(socket) }.getOrDefault(false)) return true
        runCatching { socket.close() }
        return false
    }

    companion object {
        /** The platform wiring: the echo is answered on the socket's own streams. */
        fun forSockets(
            inner: CocListenLease<BluetoothSocket>,
            owner: String,
            log: (String) -> Unit,
        ): CocListenLease<BluetoothSocket> = EchoRespondingLease(inner) { socket ->
            CocEchoVerify.respond(socket.inputStream, socket.outputStream, socket, owner, log).ok
        }
    }
}
