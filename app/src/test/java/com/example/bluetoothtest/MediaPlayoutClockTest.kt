package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlayoutClockTest {
    @Test
    fun cameraRestartConfigurationDoesNotReanchorSharedAudioVideoClock() {
        val clock = MediaPlayoutClock(delayMs = 250)
        val config = StreamProtocol.Packet(StreamProtocol.FLAG_CODEC_CONFIG, 0L, byteArrayOf(1))
        clock.timing(20_000_000L, 2_000_000_000L)

        // The receiver and decoder encounter the same untimed configuration independently.
        assertNull(videoPacketPlayoutTiming(config, clock, 2_100_000_000L))
        assertEquals(2_270_000_000L, clock.timing(20_020_000L, 2_101_000_000L).targetTimeNs)
        assertNull(videoPacketPlayoutTiming(config, clock, 2_200_000_000L))
        assertEquals(2_290_000_000L, clock.renderTimeNs(20_040_000L, 2_210_000_000L))
    }

    @Test
    fun realVideoFrameAtTimestampZeroStillEstablishesPlayoutClock() {
        val clock = MediaPlayoutClock(delayMs = 250)
        val frame = StreamProtocol.Packet(StreamProtocol.FLAG_KEY_FRAME, 0L, byteArrayOf(1))

        assertEquals(2_250_000_000L, videoPacketPlayoutTiming(frame, clock, 2_000_000_000L)?.targetTimeNs)
        assertEquals(2_270_000_000L, clock.timing(20_000L, 2_010_000_000L).targetTimeNs)
    }

    @Test
    fun lateFirstKeyframeDoesNotBecomePermanentLatency() {
        val clock = MediaPlayoutClock(delayMs = 250)
        // First keyframe (PTS 1.000 s) arrived 800 ms late behind a startup backlog.
        assertEquals(10_250_000_000L, clock.timing(1_000_000L, 10_000_000_000L).targetTimeNs)
        // The backlog bursts in right after it: PTS 1.800 s arrives 30 ms later. Without
        // re-anchoring it would be held ~1 s (more than the decoder queue holds).
        assertEquals(10_280_000_000L, clock.timing(1_800_000L, 10_030_000_000L).targetTimeNs)
        assertEquals(1, clock.reanchors)
        // Ordinary jitter inside the slack keeps the new anchor.
        assertEquals(10_346_000_000L, clock.timing(1_866_000L, 10_100_000_000L).targetTimeNs)
        assertEquals(1, clock.reanchors)
    }

    @Test
    fun mapsAudioAndVideoOntoOneTimeline() {
        val clock = MediaPlayoutClock(delayMs = 60)

        assertEquals(1_060_000_000L, clock.timing(1_000_000, 1_000_000_000L).targetTimeNs)
        assertEquals(1_080_000_000L, clock.timing(1_020_000, 1_020_000_000L).targetTimeNs)
        assertEquals(1_093_333_000L, clock.timing(1_033_333, 1_033_333_000L).targetTimeNs)
    }

    @Test
    fun keepsOneLongRunningTimelineInsteadOfResettingEveryFiveSeconds() {
        val clock = MediaPlayoutClock(delayMs = 60)
        clock.timing(1_000_000, 1_000_000_000L)
        clock.timing(2_000_000, 2_000_000_000L)
        clock.timing(3_000_000, 3_000_000_000L)
        clock.timing(4_000_000, 4_000_000_000L)
        clock.timing(5_000_000, 5_000_000_000L)
        clock.timing(6_000_000, 6_000_000_000L)

        assertEquals(7_060_000_000L, clock.timing(7_000_000, 7_000_000_000L).targetTimeNs)
    }

    @Test
    fun acceptsSlightlyEarlierTimestampFromTheOtherMediaTrack() {
        val clock = MediaPlayoutClock(delayMs = 60)
        clock.timing(1_000_000, 1_000_000_000L)

        assertEquals(1_040_000_000L, clock.timing(980_000, 1_010_000_000L).targetTimeNs)
    }

    @Test
    fun reportsLateMediaAgainstTheSharedTarget() {
        val clock = MediaPlayoutClock(delayMs = 60)
        clock.timing(1_000_000, 1_000_000_000L)

        assertFalse(clock.isTooLate(1_020_000, maximumLateMs = 90, nowNs = 1_100_000_000L))
        assertTrue(clock.isTooLate(1_020_000, maximumLateMs = 90, nowNs = 1_171_000_000L))
    }

    @Test
    fun smallCatchUpKeepsTheSharedTimelineButAStaleAnchorIsReplaced() {
        val clock = MediaPlayoutClock(delayMs = 250)
        clock.timing(1_000_000, 1_000_000_000L)
        // 450 ms early is within the tolerance (one extra buffer delay): the timeline is kept.
        assertEquals(1_650_000_000L, clock.timing(1_400_000, 1_200_000_000L).targetTimeNs)
        assertEquals(0, clock.reanchors)
        // 700 ms early cannot be jitter on a live timeline: re-anchor to the configured delay.
        assertEquals(1_550_000_000L, clock.timing(2_000_000, 1_300_000_000L).targetTimeNs)
        assertEquals(1, clock.reanchors)
        assertFalse(clock.isTooLate(2_020_000, maximumLateMs = 90, nowNs = 1_320_000_000L))
    }

    @Test
    fun trueTimestampDiscontinuityStartsANewTimeline() {
        val clock = MediaPlayoutClock(delayMs = 60)
        clock.timing(1_000_000, 1_000_000_000L)

        assertEquals(2_060_000_000L, clock.timing(7_000_001, 2_000_000_000L).targetTimeNs)
    }

    @Test
    fun adaptiveDelayGrowsWhenMediaArrivesLateAndNeverPastItsMaximum() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 500)
        assertEquals(1_250_000_000L, clock.timing(0L, 1_000_000_000L).targetTimeNs)
        // PTS 1.000 s arrives 100 ms after its 2.25 s target: delay grows by 100 + 50 ms margin.
        assertEquals(2_400_000_000L, clock.timing(1_000_000L, 2_350_000_000L).targetTimeNs)
        assertEquals(400L, clock.currentDelayMs)
        assertEquals(false, clock.isSaturated(2_350_000_000L))
        // A 400 ms burst only grows to the 500 ms ceiling: buffering no longer absorbs the link.
        clock.timing(2_000_000L, 3_800_000_000L)
        assertEquals(500L, clock.currentDelayMs)
        assertEquals(true, clock.isSaturated(3_800_000_000L))
        assertEquals(false, MediaPlayoutClock(250).saturated)
    }

    @Test
    fun adaptiveDelayRemembersABurstForAMinuteThenShrinksSlowly() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 500)
        clock.timing(0L, 1_000_000_000L)
        clock.timing(1_000_000L, 2_450_000_000L)
        assertEquals(500L, clock.currentDelayMs)
        // 20 s later the next scene cut must still find the grown delay (10 s calm used to shrink it).
        clock.timing(4_000_000L, 5_000_000_000L)
        clock.timing(21_000_000L, 22_450_000_000L)
        assertEquals(500L, clock.currentDelayMs)
        // 2 s after a minute of calm: 20 ms shrunk (1 ms per 100 ms).
        clock.timing(61_000_000L, 64_450_000_000L)
        assertEquals(480L, clock.currentDelayMs)
    }

    @Test
    fun callsShrinkAfterTenCalmSecondsToKeepConversationDelayShort() {
        val clock = PlaybackDelayProfile.CALL.newPlayoutClock()
        clock.timing(0L, 1_000_000_000L)
        clock.timing(1_000_000L, 2_600_000_000L)
        assertEquals(600L, clock.currentDelayMs)
        // 2 s after ten calm seconds: 20 ms shrunk.
        clock.timing(13_000_000L, 14_600_000_000L)
        assertEquals(580L, clock.currentDelayMs)
    }

    @Test
    fun aPinnedDelayIsSaturatedOnlyWhileMediaIsStillLate() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 500)
        clock.timing(0L, 1_000_000_000L)
        clock.timing(1_000_000L, 2_450_000_000L)
        assertEquals(true, clock.isSaturated(2_450_000_000L))
        // Still pinned while remembering the burst, but nothing is late any more.
        clock.timing(3_000_000L, 3_000_000_000L)
        assertEquals(500L, clock.currentDelayMs)
        assertEquals(false, clock.isSaturated(5_000_000_000L))
    }

    @Test
    fun fixedProfilesDoNotAdapt() {
        val clock = MediaPlayoutClock(delayMs = 250)
        clock.timing(0L, 1_000_000_000L)
        clock.timing(1_000_000L, 2_450_000_000L)
        assertEquals(250L, clock.currentDelayMs)
    }

    @Test
    fun aRelayReserveStartsPlaybackLaterAndCalmNeverShrinksBelowIt() {
        // Automatic 250-2000 ms: a 1500 ms request is capped at 75% of the 1750 ms range (1312 ms).
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 2_000)
        clock.reserve(1_500)
        assertEquals(1_562L, clock.currentDelayMs)
        assertEquals(2_562_500_000L, clock.timing(0L, 1_000_000_000L).targetTimeNs)
        assertEquals(false, clock.saturated)
        // A 1.7 s join gap arrives 388 ms late against the reserve: growth reaches the maximum.
        clock.timing(1_000_000L, 3_950_000_000L)
        assertEquals(2_000L, clock.currentDelayMs)
        // Long calm: shrinks back to the reserve, not to 250 ms.
        clock.timing(130_000_000L, 132_000_000_000L)
        assertEquals(1_562L, clock.currentDelayMs)
    }

    @Test
    fun aReserveIsCappedSoAPinnedDelayStillMeansLateMedia() {
        // Low 100-600 ms: at most 375 ms of the 500 ms range is reserved.
        val clock = MediaPlayoutClock(delayMs = 100, adaptiveMaxDelayMs = 600)
        clock.reserve(1_500)
        assertEquals(475L, clock.currentDelayMs)
        assertEquals(false, clock.saturated)
        // A fixed-delay (call) clock ignores a reserve.
        val fixed = MediaPlayoutClock(250)
        fixed.reserve(1_500)
        assertEquals(250L, fixed.currentDelayMs)
    }

    @Test
    fun postArrivalLookupsNeverMoveTheClock() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 2_000)
        assertEquals(1_250_000_000L, clock.targetFor(0L, 1_000_000_000L).targetTimeNs)
        // Before any arrival a lookup does not anchor the timeline.
        assertEquals(2_250_000_000L, clock.timing(0L, 2_000_000_000L).targetTimeNs)
        // A frame looked up 300 ms after its target is reported late but changes nothing.
        val late = clock.targetFor(1_000_000L, 3_550_000_000L)
        assertEquals(3_250_000_000L, late.targetTimeNs)
        assertEquals(300L, late.latenessMs)
        assertEquals(3_550_000_000L, clock.renderTimeNs(1_000_000L, 3_550_000_000L))
        assertTrue(clock.isTooLate(1_000_000L, maximumLateMs = 200, nowNs = 3_550_000_000L))
        assertEquals(250L, clock.currentDelayMs)
        assertEquals(0L, clock.addedDelayMs)
        assertEquals(0, clock.reanchors)
        // A far-early lookup does not re-anchor either.
        clock.targetFor(5_000_000L, 3_560_000_000L)
        assertEquals(0, clock.reanchors)
        assertEquals(3_250_000_000L, clock.targetFor(1_000_000L, 3_600_000_000L).targetTimeNs)
    }

    @Test
    fun decoderStallsWithFlatArrivalsDoNotGrowTheDelay() {
        // 15 FPS arrivals exactly on time (each 250 ms ahead of its target) for 120 s, while the
        // decoder stalls ~170 ms every 61 frames so those frames are fed/rendered late.
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 2_000)
        val startNs = 1_000_000_000L
        val frameUs = 66_667L
        var maxFeedLatenessMs = 0L
        for (frame in 0 until 15 * 120) {
            val ptsUs = frame * frameUs
            val arrivalNs = startNs + ptsUs * 1_000L
            clock.timing(ptsUs, arrivalNs)
            val stallNs = if (frame % 61 == 60) 170_000_000L else 0L
            val feedNs = arrivalNs + 250_000_000L - 80_000_000L + stallNs
            maxFeedLatenessMs = maxOf(maxFeedLatenessMs, clock.targetFor(ptsUs, feedNs).latenessMs)
            clock.renderTimeNs(ptsUs, feedNs + 10_000_000L)
        }
        assertTrue(maxFeedLatenessMs >= 80L)
        assertEquals(250L, clock.currentDelayMs)
        assertEquals(0L, clock.addedDelayMs)
        assertFalse(clock.saturated)
    }

    @Test
    fun lateArrivalsStillGrowTheDelayAndSaturate() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 2_000)
        clock.timing(0L, 1_000_000_000L)
        // PTS 1 s arrives 300 ms after its 2.25 s target: grows by 300 + 50 ms margin.
        clock.timing(1_000_000L, 2_550_000_000L)
        assertEquals(600L, clock.currentDelayMs)
        assertEquals(350L, clock.addedDelayMs)
        // Post-arrival lookups see the grown timeline without changing it.
        assertEquals(2_600_000_000L, clock.targetFor(1_000_000L, 2_560_000_000L).targetTimeNs)
        assertEquals(600L, clock.currentDelayMs)
        // A 2 s arrival gap pins the delay at its maximum.
        clock.timing(2_000_000L, 5_600_000_000L)
        assertEquals(2_000L, clock.currentDelayMs)
        assertTrue(clock.isSaturated(5_600_000_000L))
    }
}
