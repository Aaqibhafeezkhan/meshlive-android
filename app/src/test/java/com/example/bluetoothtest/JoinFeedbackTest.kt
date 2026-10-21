package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-20 a15s-asus radio join: a redundant priority request, then a silent failed join. */
class JoinFeedbackTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    private fun keeper(skip: Boolean, requests: MutableList<BleConnectionPriority>, lines: MutableList<String>) =
        LeConnectionPriorityKeeper(
            link = "gatt-video",
            sendRequest = { requests += it; true },
            schedule = { _, _ -> },
            active = { true },
            log = { lines += it },
            enabled = { true },
            requestLogEnabled = { false },
            skipRedundant = { skip },
            sdkInt = 29,
        )

    @Test
    fun aRequestForTheIntervalTheLinkAlreadyHasIsSkippedOnlyWhenTheExperimentIsOn() {
        // The A15s: HIGH requested, the link settled at 15 ms (12 units), then HIGH again after the handshake.
        assertTrue(LeConnectionPriorityKeeper.isRedundant(BleConnectionPriority.HIGH, BleConnectionPriority.HIGH, 12, 29))
        assertFalse("not yet updated", LeConnectionPriorityKeeper.isRedundant(BleConnectionPriority.HIGH, BleConnectionPriority.HIGH, -1, 29))
        assertFalse("45 ms creation interval", LeConnectionPriorityKeeper.isRedundant(BleConnectionPriority.HIGH, BleConnectionPriority.HIGH, 36, 29))
        assertFalse("a different priority was asked for", LeConnectionPriorityKeeper.isRedundant(BleConnectionPriority.HIGH, BleConnectionPriority.BALANCED, 12, 29))

        val requests = mutableListOf<BleConnectionPriority>()
        val lines = mutableListOf<String>()
        val on = keeper(skip = true, requests, lines)
        assertTrue(on.request(BleConnectionPriority.HIGH))
        on.onConnectionUpdated(0, 12)
        assertTrue(on.request(BleConnectionPriority.HIGH))
        assertEquals("the second request is not sent", 1, requests.size)
        assertTrue(lines.any { it.startsWith("BENCHMARK prio_request: skipped") && it.contains("intervalMs=15.00") && it.contains("window=11.25-15.00") })

        val offRequests = mutableListOf<BleConnectionPriority>()
        val off = keeper(skip = false, offRequests, mutableListOf())
        off.request(BleConnectionPriority.HIGH)
        off.onConnectionUpdated(0, 12)
        off.request(BleConnectionPriority.HIGH)
        assertEquals("default: sent as before", 2, offRequests.size)
        assertFalse(DebugTunables.skipRedundantPriority)
    }

    @Test
    fun aFailedJoinRetriesOnceWhenTheStreamIsListedAgain() {
        val retry = StreamJoinRetry<String> { true }
        val due = retry.onJoinFailed("radio", nowMs = 1_000L)
        assertNotNull(due)
        assertNull("not listed yet", retry.take(2_000L, listOf("other")) { t, p -> t == p })
        assertEquals("radio", retry.take(9_500L, listOf("other", "radio")) { t, p -> t == p })
        assertNull("used up", retry.pending)
        assertNull("one automatic retry until a join succeeds", retry.onJoinFailed("radio", 20_000L))
        retry.onJoined("radio")
        assertNotNull(retry.onJoinFailed("radio", 30_000L))
        assertNull("expired", retry.take(30_000L + StreamJoinRetry.WINDOW_MS + 1, listOf("radio")) { t, p -> t == p })

        // Try again always queues one; any other join cancels it.
        retry.onTryAgain("radio", 100_000L)
        retry.cancel()
        assertNull(retry.take(100_001L, listOf("radio")) { t, p -> t == p })
        assertNull("control arm", StreamJoinRetry<String> { false }.onJoinFailed("radio", 0L))

        assertEquals("Couldn't join QLRadio.", StreamJoinRetry.failureMessage("QLRadio"))
        assertEquals("Couldn't join the stream.", StreamJoinRetry.failureMessage(null))
    }

    @Test
    fun theFailedJoinPathShowsTheMessageAndConsumesThePendingJoin() {
        val main = source("MainActivity.kt")
        val back = main.substringAfter("private fun returnToStreamBrowser(").substringBefore("private fun onStreamJoinFailed(")
        assertTrue(back.contains("if (peer != null && !localMediaFailure) onStreamJoinFailed(peer)"))
        val failed = main.substringAfter("private fun onStreamJoinFailed(").substringBefore("private fun updateConnectionDot(")
        assertTrue(failed.contains("StreamJoinRetry.failureMessage(title)") && failed.contains("R.string.stream_join_try_again"))
        val refresh = main.substringAfter("private fun refreshNearbyStreamPeers()").substringBefore("debugAutoSelectStream &&")
        assertTrue(refresh.contains("streamJoinRetry.take(nowMs, nearbyStreamPeers)"))
        val connect = main.substringAfter("private fun connectToStream(peer: BleL2capPeer)").substringBefore("Selected route token")
        assertTrue(connect.contains("streamJoinRetry.cancel()"))
        assertTrue(DebugTunables.streamJoinRetry)
    }
}
