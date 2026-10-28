package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom

class OfflineAppShareTest {
    private val token = "abcdefghjkmnpqrstuvw"
    private val apk = "BluetoothVideo-1.0-1.apk"

    // --- Routing ------------------------------------------------------------------------------------

    @Test
    fun onlyThePageAndTheApkUnderTheTokenAreServed() {
        assertEquals(OfflineHttp.Route.Page, OfflineHttp.route("GET", "/$token/", token, apk))
        assertEquals(OfflineHttp.Route.PageRedirect, OfflineHttp.route("GET", "/$token", token, apk))
        assertEquals(OfflineHttp.Route.Apk, OfflineHttp.route("GET", "/$token/$apk", token, apk))
        assertEquals(OfflineHttp.Route.Apk, OfflineHttp.route("HEAD", "/$token/$apk", token, apk))
        listOf(
            "/", "/index.html", "/$apk", "/$token/other.apk", "/$token/$apk/", "/$token//$apk",
            "/$token/../$apk", "/$token/$apk?x=1", "/$token/?", "/$token/%2e%2e/", "/$token/a\\b",
            "/$token/$apk#frag", "/${token}x/", "/${token.dropLast(1)}/", "/${token.uppercase()}/",
        ).forEach { target ->
            assertEquals(target, OfflineHttp.Route.NotFound, OfflineHttp.route("GET", target, token, apk))
        }
    }

    @Test
    fun otherMethodsAndMalformedTargetsAreRefused() {
        listOf("POST", "PUT", "DELETE", "OPTIONS", "get").forEach {
            assertEquals(OfflineHttp.Route.MethodNotAllowed, OfflineHttp.route(it, "/$token/", token, apk))
        }
        assertEquals(OfflineHttp.Route.BadRequest, OfflineHttp.route("GET", "http://x/$token/", token, apk))
        assertEquals(OfflineHttp.Route.BadRequest, OfflineHttp.route("GET", "*", token, apk))
    }

    @Test
    fun tokenComparisonIsExact() {
        assertTrue(OfflineHttp.constantTimeEquals(token, token))
        assertFalse(OfflineHttp.constantTimeEquals(token, token.dropLast(1)))
        assertFalse(OfflineHttp.constantTimeEquals("", token))
    }

    // --- Range ----------------------------------------------------------------------------------------

    @Test
    fun singleByteRangesAreParsed() {
        assertEquals(OfflineHttp.RangeResult.Full, OfflineHttp.parseRange(null, 100))
        assertEquals(OfflineHttp.RangeResult.Partial(0, 9), OfflineHttp.parseRange("bytes=0-9", 100))
        assertEquals(OfflineHttp.RangeResult.Partial(90, 99), OfflineHttp.parseRange("bytes=90-", 100))
        assertEquals(OfflineHttp.RangeResult.Partial(90, 99), OfflineHttp.parseRange("bytes=90-500", 100))
        assertEquals(OfflineHttp.RangeResult.Partial(80, 99), OfflineHttp.parseRange("bytes=-20", 100))
        assertEquals(OfflineHttp.RangeResult.Partial(0, 99), OfflineHttp.parseRange("bytes=-500", 100))
        assertEquals(OfflineHttp.RangeResult.Unsatisfiable, OfflineHttp.parseRange("bytes=100-", 100))
        assertEquals(OfflineHttp.RangeResult.Unsatisfiable, OfflineHttp.parseRange("bytes=-0", 100))
    }

    @Test
    fun unusableRangesFallBackToTheWholeFile() {
        listOf("bytes=0-1,5-6", "items=0-1", "bytes=9-1", "bytes=a-b", "bytes=-", "bytes=1", "bytes=99999999999999999999-")
            .forEach { assertEquals(it, OfflineHttp.RangeResult.Full, OfflineHttp.parseRange(it, 100)) }
    }

    @Test
    fun requestHeadIsReadWithLimits() {
        val request = OfflineHttp.readRequest(
            ByteArrayInputStream("GET /$token/ HTTP/1.1\r\nHost: x\r\nRange: bytes=0-1\r\n\r\n".toByteArray()),
        )
        assertEquals("GET", request?.method)
        assertEquals("/$token/", request?.target)
        assertEquals("bytes=0-1", request?.headers?.get("range"))
        val longLine = "GET /" + "a".repeat(OfflineHttp.MAX_REQUEST_LINE) + " HTTP/1.1\r\n\r\n"
        assertEquals(null, OfflineHttp.readRequest(ByteArrayInputStream(longLine.toByteArray())))
        val manyHeaders = "GET / HTTP/1.1\r\n" + (1..100).joinToString("") { "X-$it: y\r\n" } + "\r\n"
        assertEquals(null, OfflineHttp.readRequest(ByteArrayInputStream(manyHeaders.toByteArray())))
        assertEquals(null, OfflineHttp.readRequest(ByteArrayInputStream("garbage\r\n\r\n".toByteArray())))
    }

    // --- QR payloads, names and policy ----------------------------------------------------------------

    @Test
    fun wifiQrPayloadUsesTheStandardFormatAndEscapes() {
        assertEquals(
            "WIFI:T:WPA;S:DIRECT-ab-xyz;P:Secret123abc;;",
            OfflineAppSharePolicy.wifiQrPayload("DIRECT-ab-xyz", "Secret123abc"),
        )
        assertEquals(
            "WIFI:T:WPA;S:a\\;b\\,c\\:d\\\\e\\\"f;P:p\\;q;;",
            OfflineAppSharePolicy.wifiQrPayload("a;b,c:d\\e\"f", "p;q"),
        )
    }

    @Test
    fun pageUrlCarriesTheTokenWithATrailingSlash() {
        assertEquals("http://192.168.49.1:8080/$token/", OfflineAppSharePolicy.pageUrl("192.168.49.1", 8080, token))
        assertEquals("http://[fe80::1]:80/$token/", OfflineAppSharePolicy.pageUrl("fe80::1%p2p0", 80, token))
    }

    @Test
    fun generatedCredentialsAreValidAndRandom() {
        val random = SecureRandom()
        repeat(50) {
            val name = OfflineAppSharePolicy.randomNetworkName(random)
            assertTrue(name, OfflineAppSharePolicy.validNetworkName(name))
            assertFalse(name, name.contains("Video", ignoreCase = true))
            val pass = OfflineAppSharePolicy.randomPassphrase(random)
            assertTrue(pass, OfflineAppSharePolicy.validPassphrase(pass))
            assertEquals(OfflineAppSharePolicy.TOKEN_LENGTH, OfflineAppSharePolicy.randomToken(random).length)
        }
        assertFalse(OfflineAppSharePolicy.validNetworkName("BluetoothVideo"))
        assertFalse(OfflineAppSharePolicy.validNetworkName("DIRECT-" + "x".repeat(30)))
        assertFalse(OfflineAppSharePolicy.validPassphrase("short"))
        val tokens = (1..100).map { OfflineAppSharePolicy.randomToken(random) }.toSet()
        assertEquals(100, tokens.size)
    }

    @Test
    fun fiveGigahertzIsTriedFirstOnlyWhenSupported() {
        assertEquals(
            listOf(GroupBandAttempt.BAND_5GHZ, GroupBandAttempt.BAND_AUTO),
            OfflineAppSharePolicy.bandAttempts(supports5Ghz = true),
        )
        assertEquals(
            listOf(GroupBandAttempt.BAND_AUTO),
            OfflineAppSharePolicy.bandAttempts(supports5Ghz = false),
        )
    }

    @Test
    fun noAttemptIsEverAPersistentGroup() {
        // Every attempt kind is a WifiP2pConfig band attempt (temporary group); the no-config createGroup that
        // creates a persistent group, and with it a fixed Wi-Fi Direct MAC address, has no enum value to select.
        assertEquals(
            setOf("BAND_5GHZ", "BAND_AUTO"),
            GroupBandAttempt.values().map { it.name }.toSet(),
        )
        for (supports5 in listOf(true, false)) {
            val attempts = OfflineAppSharePolicy.bandAttempts(supports5)
            assertTrue(attempts.isNotEmpty())
            assertTrue(attempts.all { it == GroupBandAttempt.BAND_5GHZ || it == GroupBandAttempt.BAND_AUTO })
        }
    }

    @Test
    fun createGroupFailuresRetryBusyThenFallBack() {
        val busy = OfflineAppSharePolicy.P2P_BUSY
        assertEquals(OfflineAppSharePolicy.FailureStep.Retry(1_000L), OfflineAppSharePolicy.onCreateGroupFailure(busy, 0, 3, 0))
        assertEquals(OfflineAppSharePolicy.FailureStep.Retry(8_000L), OfflineAppSharePolicy.onCreateGroupFailure(busy, 0, 3, 3))
        assertEquals(OfflineAppSharePolicy.FailureStep.NextAttempt, OfflineAppSharePolicy.onCreateGroupFailure(busy, 0, 3, 4))
        assertEquals(
            OfflineAppSharePolicy.FailureStep.NextAttempt,
            OfflineAppSharePolicy.onCreateGroupFailure(OfflineAppSharePolicy.P2P_ERROR, 0, 3, 0),
        )
        assertTrue(OfflineAppSharePolicy.onCreateGroupFailure(OfflineAppSharePolicy.P2P_ERROR, 2, 3, 0) is OfflineAppSharePolicy.FailureStep.GiveUp)
        assertTrue(OfflineAppSharePolicy.onCreateGroupFailure(OfflineAppSharePolicy.P2P_UNSUPPORTED, 0, 3, 0) is OfflineAppSharePolicy.FailureStep.GiveUp)
    }

    @Test
    fun bandLabelsAndCountdown() {
        assertEquals("2.4 GHz", OfflineAppSharePolicy.bandLabel(2437))
        assertEquals("5 GHz", OfflineAppSharePolicy.bandLabel(5180))
        assertEquals("unknown band", OfflineAppSharePolicy.bandLabel(0))
        assertEquals("10:00", OfflineAppSharePolicy.minutesSecondsLeft(OfflineAppSharePolicy.SHARE_TIMEOUT_MS))
        assertEquals("0:01", OfflineAppSharePolicy.minutesSecondsLeft(1))
        assertEquals("0:00", OfflineAppSharePolicy.minutesSecondsLeft(-5))
    }

    @Test
    fun subnetCheckAcceptsOnlyGroupPeers() {
        val local = InetAddress.getByName("192.168.49.1")
        assertTrue(OfflineAppSharePolicy.sameSubnet(local, 24, InetAddress.getByName("192.168.49.77")))
        assertFalse(OfflineAppSharePolicy.sameSubnet(local, 24, InetAddress.getByName("192.168.50.77")))
        assertFalse(OfflineAppSharePolicy.sameSubnet(local, 24, InetAddress.getByName("::1")))
        assertTrue(OfflineAppSharePolicy.sameSubnet(local, 20, InetAddress.getByName("192.168.63.2")))
        assertFalse(OfflineAppSharePolicy.sameSubnet(local, 33, InetAddress.getByName("192.168.49.1")))
    }

    @Test
    fun featureFollowsTheSharedLaunchRule() {
        assertTrue(OfflineAppShareFeature.resolve(true, { it == OfflineAppShareFeature.EXTRA }, { false }, available = true))
        assertFalse(OfflineAppShareFeature.resolve(true, { false }, { true }, available = true))
        // Normal launch: the user's explicit choice, no Manual-settings toggle; extras are ignored.
        assertTrue(OfflineAppShareFeature.resolve(false, { false }, { if (it == OfflineAppShareFeature.PREF) true else null }, available = true))
        assertFalse(OfflineAppShareFeature.resolve(false, { true }, { false }, available = true))
        // Never decided: the shipped default (the Help entry stays hidden until phone test T9).
        assertEquals(
            ConnectivityFeatureDefaults.SHARE_APP_NEARBY,
            OfflineAppShareFeature.resolve(false, { true }, { null }, available = true),
        )
    }

    @Test
    fun aBuildWithoutAppShareNeverTurnsItOn() {
        // An unavailable build: neither a benchmark extra nor a stored choice turns it on.
        assertFalse(OfflineAppShareFeature.resolve(true, { true }, { true }, available = false))
        assertFalse(OfflineAppShareFeature.resolve(false, { true }, { true }, available = false))
    }

    @Test
    fun pageEscapesValuesAndShowsVerificationData() {
        val html = OfflineAppSharePolicy.pageHtml(pageInfo(versionName = "1.0<script>"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("1.0&lt;script&gt;"))
        assertTrue(html.contains("ab".repeat(32)))
        assertTrue(html.contains("2E:EF"))
        assertTrue(html.contains("href=\"$apk\""))
        assertFalse(html.contains("http://"))
    }

    // --- Live server on loopback -------------------------------------------------------------------

    @Test
    fun serverServesOnlyTheAllowlistOverRealSockets() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val file = Files.createTempFile("offline-share", ".apk").toFile()
        file.writeBytes(bytes)
        val page = OfflineAppSharePolicy.pageHtml(pageInfo())
        val server = OfflineAppShareServer(InetAddress.getLoopbackAddress(), token, file, apk, page)
        try {
            val port = server.start()
            val pageResponse = request(port, "GET /$token/ HTTP/1.1")
            assertEquals(200, pageResponse.code)
            assertEquals(page, String(pageResponse.body, Charsets.UTF_8))
            assertTrue(pageResponse.head.contains("Content-Security-Policy: default-src 'none'"))

            val full = request(port, "GET /$token/$apk HTTP/1.1")
            assertEquals(200, full.code)
            assertArrayEquals(bytes, full.body)
            assertTrue(full.head.contains("Content-Type: application/vnd.android.package-archive"))
            assertTrue(full.head.contains("Accept-Ranges: bytes"))

            val partial = request(port, "GET /$token/$apk HTTP/1.1", "Range: bytes=100-199")
            assertEquals(206, partial.code)
            assertArrayEquals(bytes.copyOfRange(100, 200), partial.body)
            assertTrue(partial.head.contains("Content-Range: bytes 100-199/200000"))

            assertEquals(416, request(port, "GET /$token/$apk HTTP/1.1", "Range: bytes=200000-").code)
            val head = request(port, "HEAD /$token/$apk HTTP/1.1")
            assertEquals(200, head.code)
            assertEquals(0, head.body.size)
            assertTrue(head.head.contains("Content-Length: 200000"))

            assertEquals(308, request(port, "GET /$token HTTP/1.1").code)
            assertEquals(404, request(port, "GET / HTTP/1.1").code)
            assertEquals(404, request(port, "GET /wrongtokenwrongtoken/$apk HTTP/1.1").code)
            assertEquals(404, request(port, "GET /$token/../../etc/passwd HTTP/1.1").code)
            assertEquals(405, request(port, "POST /$token/ HTTP/1.1").code)
            assertEquals(400, request(port, "nonsense").code)
            assertEquals(4, server.apkRequests.get()) // full, partial, 416 and HEAD
            assertEquals(1, server.pageRequests.get())
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun serverRejectsPeersOutsideTheGroupAndStopsOnClose() {
        val file = Files.createTempFile("offline-share", ".apk").toFile()
        file.writeBytes(ByteArray(10))
        val server = OfflineAppShareServer(
            InetAddress.getLoopbackAddress(), token, file, apk, "page", allowRemote = { false },
        )
        try {
            val port = server.start()
            val response = request(port, "GET /$token/ HTTP/1.1")
            assertEquals(-1, response.code) // Closed without a response.
            server.close()
            val refused = runCatching { Socket(InetAddress.getLoopbackAddress(), port).use { it.getInputStream().read() } }
            assertTrue(refused.isFailure || refused.getOrNull() == -1)
        } finally {
            server.close()
            file.delete()
        }
    }

    private class Response(val code: Int, val head: String, val body: ByteArray)

    private fun request(port: Int, requestLine: String, vararg headers: String): Response =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            val text = buildString {
                append(requestLine).append("\r\nHost: localhost\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            runCatching { socket.getOutputStream().apply { write(text.toByteArray(Charsets.US_ASCII)); flush() } }
            val all = ByteArrayOutputStream()
            runCatching { socket.getInputStream().copyTo(all) }
            val raw = all.toByteArray()
            val split = indexOf(raw, "\r\n\r\n".toByteArray())
            if (split < 0) return Response(-1, "", raw)
            val head = String(raw, 0, split, Charsets.US_ASCII)
            val code = head.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: -1
            Response(code, head, raw.copyOfRange(split + 4, raw.size))
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun pageInfo(versionName: String = "1.0") = OfflineAppSharePolicy.PageInfo(
        appName = "Bluetooth Video",
        versionName = versionName,
        versionCode = 1,
        sizeBytes = 10_700_000,
        apkFileName = apk,
        apkSha256 = "ab".repeat(32),
        signingCertSha256 = listOf(AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256),
        matchesPublishedReleaseKey = true,
        debugBuild = false,
    )
}
