package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallPlayoutPolicyTest {
    private val ms = 1_000_000L

    private fun callClock() = PlaybackDelayProfile.CALL.newPlayoutClock()

    @Test
    fun offLeavesVideoGrowthUnlimited() {
        assertNull(CallPlayoutPolicy.videoGrowthLimitMs(speechLed = false))
        val clock = callClock()
        clock.timing(0L, 1_000 * ms)
        // A scene-cut frame 700 ms late grows the shared call delay to its 600 ms maximum (unchanged).
        val frame = StreamProtocol.Packet(0, 1_000_000L, byteArrayOf(1))
        videoPacketPlayoutTiming(frame, clock, 2_950 * ms)
        assertEquals(600L, clock.currentDelayMs)
    }

    @Test
    fun speechLedLimitsWhatLateVideoAddsToTheSharedDelay() {
        val limit = CallPlayoutPolicy.videoGrowthLimitMs(speechLed = true)
        assertEquals(150L, limit)
        val clock = callClock()
        clock.timing(0L, 1_000 * ms) // audio anchors: target 1.25 s for PTS 0
        val frame = StreamProtocol.Packet(0, 1_000_000L, byteArrayOf(1))
        // PTS 1.0 s was due at 2.25 s; it arrives at 2.95 s (700 ms late).
        val timing = requireNotNull(videoPacketPlayoutTiming(frame, clock, 2_950 * ms, limit))
        assertEquals(400L, clock.currentDelayMs) // 250 + at most 150
        assertEquals(550L, timing.latenessMs) // still reported (and played late)
        // Ordinary small video jitter is absorbed within the limit.
        val clock2 = callClock()
        clock2.timing(0L, 1_000 * ms)
        videoPacketPlayoutTiming(frame, clock2, 2_330 * ms, limit) // 80 ms late
        assertEquals(380L, clock2.currentDelayMs) // 250 + 80 + 50 margin
    }

    @Test
    fun lateAudioStillUsesTheWholeCallRange() {
        val clock = callClock()
        clock.timing(0L, 1_000 * ms)
        // Speech itself 500 ms late: audio may grow the delay to the 600 ms call maximum.
        clock.timing(1_000_000L, 2_750 * ms)
        assertEquals(600L, clock.currentDelayMs)
    }

    @Test
    fun lateVideoAboveTheLimitDoesNotHoldOffShrinkingDelayThatAudioAdded() {
        val limit = CallPlayoutPolicy.videoGrowthLimitMs(speechLed = true)
        val clock = callClock()
        clock.timing(0L, 1_000 * ms)
        clock.timing(1_000_000L, 2_750 * ms) // audio burst: delay 600
        assertEquals(600L, clock.currentDelayMs)
        // Video keeps arriving late, but the delay is above what video may add: it must not keep the
        // audio-added delay from shrinking after the 10 s call calm period.
        var nowMs = 2_750L
        repeat(150) {
            nowMs += 100
            val audioPtsUs = (nowMs - 1_500) * 1_000 // due 100 ms from now: on time
            clock.timing(audioPtsUs, nowMs * ms)
            videoPacketPlayoutTiming(
                StreamProtocol.Packet(0, audioPtsUs - 700_000, byteArrayOf(1)), // 600 ms late
                clock,
                nowMs * ms,
                limit,
            )
        }
        assertEquals(550L, clock.currentDelayMs) // 5 s of shrinking after the 10 s calm period
    }
}
