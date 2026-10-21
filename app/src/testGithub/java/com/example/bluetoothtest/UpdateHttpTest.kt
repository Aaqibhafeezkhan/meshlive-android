package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class UpdateHttpTest {
    private class Connection(val body: InputStream, val status: Int = 200) : HttpURLConnection(URL("https://example.test")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun getErrorStream() = body
    }

    private fun expectTransport(block: () -> Unit) {
        try { block(); fail("expected network failure") } catch (_: UpdateHttp.TransportException) {}
    }

    @Test fun bodyTimeoutsAndDnsFailuresUseTheTransportErrorPathAndDisconnect() {
        val c = Connection(object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("stalled")
        })
        expectTransport { UpdateHttp.get(UpdateEndpoints.LATEST_URL, UpdateSource.GITHUB, 100, connect = { c }) }
        assertTrue(c.disconnected)
        expectTransport { UpdateHttp.get(UpdateEndpoints.LATEST_URL, UpdateSource.GITHUB, 100, connect = { throw java.net.UnknownHostException() }) }
    }

    @Test fun aTrickleHasATotalBudgetAndCancellationStopsBeforeWriting() {
        var nanos = 0L
        val slow = object : ByteArrayInputStream(ByteArray(10)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                nanos += 61_000_000_000
                return super.read(b, off, len)
            }
        }
        val sink = ByteArrayOutputStream()
        expectTransport { HashedCopy.copy(UpdateHttp.guarded(slow, { false }, 60_000) { nanos }, sink, 100) }
        assertEquals(0, sink.size())
        expectTransport { HashedCopy.copy(UpdateHttp.guarded(ByteArrayInputStream(ByteArray(10)), { true }, 60_000), sink, 100) }
        assertEquals(0, sink.size())
    }

    @Test fun truncatedDownloadsFailAndDiskFailuresRemainStorageErrors() {
        val c = Connection(ByteArrayInputStream(byteArrayOf(1)))
        expectTransport {
            UpdateHttp.download("https://example.test", UpdateSource.GITHUB, ByteArrayOutputStream(), 100, 2, { false }, connect = { c }, onProgress = {})
        }
        assertTrue(c.disconnected)
        val disk = object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }
        try {
            UpdateHttp.download("https://example.test", UpdateSource.GITHUB, disk, 100, 1, { false }, connect = { Connection(ByteArrayInputStream(byteArrayOf(1))) }, onProgress = {})
            fail("expected storage failure")
        } catch (e: IOException) {
            assertFalse(e is UpdateHttp.TransportException)
            assertEquals("disk full", e.message)
        }
    }

    @Test fun rateLimitedResponsesRemainAnswers() {
        for (status in listOf(403, 429)) {
            val answer = UpdateHttp.get(UpdateEndpoints.LATEST_URL, UpdateSource.GITHUB, 100, 1_800_000_000_000, { Connection(ByteArrayInputStream(byteArrayOf()), status) })
            assertEquals(status, answer.status)
            assertEquals(1_800_003_600_000L, answer.rateLimitedUntilMs)
        }
    }
}
