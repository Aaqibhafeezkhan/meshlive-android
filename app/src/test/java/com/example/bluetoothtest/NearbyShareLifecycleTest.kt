package com.example.bluetoothtest

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NearbyShareLifecycleTest {
    @Test fun anOwnerFailureBlocksRadioWorkAndUpdatesConservatively() {
        val previous = OfflineAppShareGate.liveSessionCheck
        try {
            OfflineAppShareGate.liveSessionCheck = { true }
            assertTrue(OfflineAppShareGate.liveSessionActive())
            OfflineAppShareGate.liveSessionCheck = { error("owner unavailable") }
            assertTrue(OfflineAppShareGate.liveSessionActive())
            OfflineAppShareGate.liveSessionCheck = { false }
            assertFalse(OfflineAppShareGate.liveSessionActive())
        } finally { OfflineAppShareGate.liveSessionCheck = previous }
    }

    @Test fun stoppingDuringCreationRemovesTheLateGroupBeforeClosingItsChannel() {
        val flow = NearbyGroupLifecycle()
        flow.creating()
        assertEquals(NearbyGroupLifecycle.Cleanup.NONE, flow.stop())
        assertEquals(NearbyGroupLifecycle.Cleanup.REMOVE_GROUP, flow.created())
        assertEquals(NearbyGroupLifecycle.Cleanup.NONE, flow.stop())
    }
    @Test fun failureAfterStopClosesTheChannelWithoutRemovingAnotherAppsGroup() {
        val flow = NearbyGroupLifecycle()
        flow.creating()
        flow.stop()
        assertEquals(NearbyGroupLifecycle.Cleanup.CLOSE_CHANNEL, flow.failed())
        val neverCreated = NearbyGroupLifecycle()
        assertEquals(NearbyGroupLifecycle.Cleanup.CLOSE_CHANNEL, neverCreated.stop())
    }
    @Test fun retriesOwnOnlyTheirSuccessfulGroupAndStopIsIdempotent() {
        val flow = NearbyGroupLifecycle()
        flow.creating()
        assertEquals(NearbyGroupLifecycle.Cleanup.NONE, flow.failed())
        flow.creating()
        assertEquals(NearbyGroupLifecycle.Cleanup.NONE, flow.created())
        assertEquals(NearbyGroupLifecycle.Cleanup.REMOVE_GROUP, flow.stop())
        assertEquals(NearbyGroupLifecycle.Cleanup.NONE, flow.stop())
    }
    @Test fun occupiedPortDoesNotStealItsListenerAndCloseStopsOpenConnections() {
        val file = Files.createTempFile("nearby-lifecycle", ".apk").toFile()
        val host = InetAddress.getLoopbackAddress()
        try {
            ServerSocket(0, 8, host).use { occupied ->
                val server = OfflineAppShareServer(host, "abcdefghjkmnpqrstuvw", file, "app.apk", "Android only", requestedPort = occupied.localPort)
                try {
                    try { server.start(); fail("port is busy") } catch (_: java.io.IOException) {}
                    assertFalse(occupied.isClosed)
                } finally { server.close() }
            }
            val server = OfflineAppShareServer(host, "abcdefghjkmnpqrstuvw", file, "app.apk", "Android only")
            try {
                val port = server.start()
                Socket(host, port).use { client ->
                    client.soTimeout = 2000
                    client.getOutputStream().write("GET /unfinished".toByteArray())
                    server.close()
                    val read = runCatching { client.getInputStream().read() }
                    assertTrue(read.isFailure || read.getOrNull() == -1)
                }
                assertTrue(runCatching { Socket(host, port).use { } }.isFailure)
            } finally { server.close() }
        } finally { file.delete() }
    }
    @Test fun installedSplitsAreRefusedAndTheReceiverGetsAnAndroidOnlyVerificationPage() {
        assertNotNull(AppSharePolicy.refusal(true, 2, false, listOf("CN=Release")))
        val page = OfflineAppSharePolicy.pageHtml(OfflineAppSharePolicy.PageInfo(
            "MeshLive", "0.4.0", 42, 100, "app.apk", "a".repeat(64), listOf("AB:CD"), false, false,
        ))
        assertTrue(page.contains("Android only"))
        assertTrue(page.contains("iPhone"))
        assertTrue(page.contains("0.4.0 (42)"))
        assertTrue(page.contains("a".repeat(64)))
        assertTrue(page.contains("AB:CD"))
    }
}
