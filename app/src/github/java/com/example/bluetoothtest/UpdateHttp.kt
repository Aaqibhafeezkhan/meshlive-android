package com.example.bluetoothtest

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The only network code of the updater (github flavour). It sends a fixed, minimal request: no cookies,
 * no account, no device or app version, only a generic User-Agent (GitHub's API refuses requests
 * without one). Redirects are followed by hand and only to the hosts the [UpdateSource] allows, over
 * HTTPS; a response is size-limited before it is read.
 */
internal object UpdateHttp {
    const val USER_AGENT = "MeshLive-updater"
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    class Answer(
        val status: Int,
        val body: ByteArray,
        /** When a 403/429 says the request budget is spent: the time to wait until, ms since the epoch. */
        val rateLimitedUntilMs: Long?,
    )

    /** The request never produced an HTTP answer (offline, DNS, TLS, timeout) or an answer broke a rule. */
    class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** GET [url] (an API or small text file) and return the status and at most [maxBytes] of the body. */
    fun get(url: String, source: UpdateSource, maxBytes: Int, nowMs: Long = System.currentTimeMillis()): Answer {
        val connection = open(url, source, accept = "application/vnd.github+json, text/plain, */*")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            val reset = UpdateSchedule.rateLimitReset(
                status,
                connection.getHeaderField("Retry-After")?.toLongOrNull(),
                connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull(),
                connection.getHeaderField("X-RateLimit-Remaining")?.toLongOrNull(),
                nowMs,
            )
            return Answer(status, body, reset)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [url] into [out] while hashing it ([HashedCopy]). [expectedSize] (from the release listing)
     * must match the bytes received; the limit and a cancel flag stop an oversized or abandoned download.
     */
    fun download(
        url: String,
        source: UpdateSource,
        out: OutputStream,
        maxBytes: Long,
        expectedSize: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): HashedResult {
        val connection = open(url, source, accept = "application/octet-stream")
        try {
            val status = connection.responseCode
            if (status != 200) throw TransportException("The download answered HTTP $status")
            val announced = connection.contentLengthLong
            if (announced >= 0 && announced != expectedSize) throw TransportException("The download size differs from the release listing")
            val guarded = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (isCancelled()) throw TransportException("Cancelled")
                    return super.read(b, off, len)
                }
            }
            val result = HashedCopy.copy(guarded, out, minOf(maxBytes, expectedSize), onProgress)
            if (result.bytes != expectedSize) throw TransportException("The download ended early")
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw TransportException("The answer is larger than $maxBytes bytes")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Opens [url] and follows allowed redirects; the returned connection has its status line read. */
    private fun open(url: String, source: UpdateSource, accept: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (current != url && !source.isAllowedRedirect(current)) {
                throw TransportException("Redirect to a host the updater does not use")
            }
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw TransportException("Cannot open the connection", e)
            } catch (e: ClassCastException) {
                throw TransportException("Not an HTTP URL", e)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw TransportException("Redirect without a target")
                    connection.disconnect()
                    current = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                        ?: throw TransportException("Unreadable redirect target")
                    return@repeat
                }
                return connection
            } catch (e: TransportException) {
                connection.disconnect()
                throw e
            } catch (e: IOException) {
                connection.disconnect()
                throw TransportException(e.javaClass.simpleName + ": " + (e.message ?: "network error"), e)
            }
        }
        throw TransportException("Too many redirects")
    }
}
