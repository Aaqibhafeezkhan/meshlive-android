package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayJoinKeyFrameTest {
    @Test
    fun switchOffNeverForcesSoEveryRequestTakesTheBackoff() {
        val tracker = RelayJoinKeyFrame(startedAtMs = 0L)
        assertFalse(tracker.onRequest(5_000L, enabled = false, childRelays = true))
        assertFalse(tracker.onRequest(60_000L, enabled = false, childRelays = true))
    }

    @Test
    fun aRelayChildsFirstRequestAfterACalmGapIsForced() {
        val tracker = RelayJoinKeyFrame(startedAtMs = 0L)
        assertTrue(tracker.onRequest(3_500L, enabled = true, childRelays = true))
    }

    @Test
    fun theWindowStartsAtAuthenticationBecauseThePeerReadyKeyframeIsAlreadyForced() {
        val tracker = RelayJoinKeyFrame(startedAtMs = 10_000L)
        assertFalse(tracker.onRequest(11_000L, enabled = true, childRelays = true))
    }

    @Test
    fun aPlainViewerNeverBypassesTheBackoff() {
        val tracker = RelayJoinKeyFrame(startedAtMs = 0L)
        assertFalse(tracker.onRequest(30_000L, enabled = true, childRelays = false))
    }

    @Test
    fun aChildThatKeepsAskingForcesAtMostOncePerCalmWindow() {
        val tracker = RelayJoinKeyFrame(startedAtMs = 0L)
        // Recovery requests every 1.5 s: after the first forced one, none is forced again.
        val forced = (1..20).map { i -> tracker.onRequest(5_000L + i * 1_500L, enabled = true, childRelays = true) }
        assertEquals(listOf(true) + List(19) { false }, forced)
    }

    @Test
    fun requestsSpacedByTheCalmGapAreForcedOnePerGap() {
        val tracker = RelayJoinKeyFrame(startedAtMs = 0L)
        val forced = (1..5).map { i -> tracker.onRequest(i * 2_000L, enabled = true, childRelays = true) }
        assertEquals(List(5) { true }, forced)
    }
}
