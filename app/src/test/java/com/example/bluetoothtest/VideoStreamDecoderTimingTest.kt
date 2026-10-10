package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStreamDecoderTimingTest {
    @Test
    fun holdsEncodedFrameUntilDecoderLeadWindow() {
        assertEquals(
            420L,
            decoderFeedDelayMs(
                targetTimeNs = 1_500_000_000L,
                nowNs = 1_000_000_000L,
            ),
        )
    }

    @Test
    fun feedsImmediatelyInsideDecoderLeadWindow() {
        assertEquals(
            0L,
            decoderFeedDelayMs(
                targetTimeNs = 1_079_000_000L,
                nowNs = 1_000_000_000L,
            ),
        )
    }

    @Test
    fun outputHoldRoundsUpWithoutChangingTheInputLeadWindow() {
        val nowNs = 1_000_000_000L
        assertEquals(2L, decoderFeedDelayMs(nowNs + 1_000_001L, nowNs, decodeAheadMs = 0L))
        assertEquals(0L, decoderFeedDelayMs(nowNs, nowNs, decodeAheadMs = 0L))
        assertEquals(0L, decoderFeedDelayMs(nowNs - 1L, nowNs, decodeAheadMs = 0L))
        assertEquals(0L, decoderFeedDelayMs(nowNs + 79_999_999L, nowNs))
        assertEquals(1L, decoderFeedDelayMs(nowNs + 80_000_001L, nowNs))
    }

    @Test
    fun plannedQueueDepthExcludesFramesHeldForSelectedStabilityDelay() {
        assertEquals(10, plannedDecoderQueueDepth(fps = 15, playbackDelayMs = 500))
        assertEquals(6, plannedDecoderQueueDepth(fps = 15, playbackDelayMs = 250))
        assertEquals(3, plannedDecoderQueueDepth(fps = 15, playbackDelayMs = 30))
    }

    @Test
    fun feedAheadStaysAtTheBaseLeadUntilTheDecoderIsMeasuredSlow() {
        assertEquals(DECODER_FEED_AHEAD_MS, adaptiveDecoderFeedAheadMs(null, 250L))
        assertEquals(DECODER_FEED_AHEAD_MS, adaptiveDecoderFeedAheadMs(30L, 250L))
        // Even a tiny playout delay never drops below the historical 80 ms lead.
        assertEquals(DECODER_FEED_AHEAD_MS, adaptiveDecoderFeedAheadMs(400L, 100L))
    }

    @Test
    fun periodicDecoderStallsGetSlackBoundedByThePlayoutDelay() {
        val window = DecoderLatencyWindow()
        // c2.exynos.av1.decoder: ~12 ms decodes with a 170 ms stall every 61 frames.
        repeat(300) { frame -> window.record(if (frame % 61 == 60) 170L else 12L) }
        assertEquals(170L, window.p99Ms())
        assertEquals(190L, adaptiveDecoderFeedAheadMs(window.p99Ms(), 250L))
        // Bounded by the current delay minus the safety margin.
        assertEquals(150L, adaptiveDecoderFeedAheadMs(window.p99Ms(), 200L))
        // Grows no further with a larger delay: it follows the decoder, not the delay.
        assertEquals(190L, adaptiveDecoderFeedAheadMs(window.p99Ms(), 2_000L))
        // The stall must be fed early enough: its feed wait is 60 ms instead of 170.
        val nowNs = 1_000_000_000L
        assertEquals(60L, decoderFeedDelayMs(nowNs + 250_000_000L, nowNs, adaptiveDecoderFeedAheadMs(170L, 250L)))
    }

    @Test
    fun feedAheadReturnsToTheBaseLeadAfterStallsAgeOut() {
        val window = DecoderLatencyWindow(capacity = 128)
        repeat(128) { frame -> window.record(if (frame % 61 == 60) 170L else 12L) }
        assertEquals(170L, window.p99Ms())
        repeat(128) { window.record(15L) }
        assertEquals(15L, window.p99Ms())
        assertEquals(DECODER_FEED_AHEAD_MS, adaptiveDecoderFeedAheadMs(window.p99Ms(), 250L))
    }

    @Test
    fun latencyWindowNeedsSamplesBeforeAdapting() {
        val window = DecoderLatencyWindow()
        repeat(7) { window.record(500L) }
        assertNull(window.p99Ms())
        window.record(500L)
        assertEquals(500L, window.p99Ms())
    }

    @Test
    fun decoderFeedUsesALookupThatNeverGrowsTheDelay() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 2_000)
        val frame = StreamProtocol.Packet(0, 1_000_000L, byteArrayOf(1))
        clock.timing(0L, 1_000_000_000L)
        // Fed 400 ms after its 2.25 s target because the decoder stalled.
        assertEquals(400L, videoPacketFeedTarget(frame, clock, 2_650_000_000L)?.latenessMs)
        assertEquals(250L, clock.currentDelayMs)
        assertNull(videoPacketFeedTarget(StreamProtocol.Packet(StreamProtocol.FLAG_CODEC_CONFIG, 0L, byteArrayOf(1)), clock, 0L))
        // The same lateness at network arrival grows it (400 ms + 50 ms margin; target moves to 2.7 s).
        assertEquals(2_700_000_000L, videoPacketPlayoutTiming(frame, clock, 2_650_000_000L)?.targetTimeNs)
        assertEquals(700L, clock.currentDelayMs)
    }
}
