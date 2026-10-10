package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDelayProfileTest {
    @Test
    fun autoIsTheDefaultAndEveryStreamingChoiceIsAnAdaptiveRange() {
        assertEquals(PlaybackDelayProfile.AUTO, PlaybackDelayProfile.fromStoredValue(null))
        assertEquals(PlaybackDelayProfile.AUTO, PlaybackDelayProfile.fromStoredValue("removed"))
        assertEquals(PlaybackDelayProfile.AUTO, PlaybackDelayProfile.fromStoredValue("CALL"))
        assertEquals(PlaybackDelayProfile.BALANCED, PlaybackDelayProfile.fromStoredValue("BALANCED"))
        assertEquals(250L, PlaybackDelayProfile.AUTO.delayMs)
        assertEquals(2_000L, PlaybackDelayProfile.AUTO.adaptiveMaxDelayMs)
        assertEquals(600L, PlaybackDelayProfile.LOW.adaptiveMaxDelayMs)
        assertEquals(1_200L, PlaybackDelayProfile.BALANCED.adaptiveMaxDelayMs)
        assertEquals(2_500L, PlaybackDelayProfile.STABLE.adaptiveMaxDelayMs)
        assertEquals(500, PlaybackDelayProfile.STABLE.audioPrebufferMs)
    }

    @Test
    fun queuesHoldTheWholeMaximumDelaySoNothingIsDropped() {
        // 15 FPS x (2.0 s maximum + 0.5 s decoder headroom) + 3 frames.
        assertEquals(41, PlaybackDelayProfile.AUTO.videoQueueCapacity(15))
        assertEquals(78, PlaybackDelayProfile.AUTO.videoQueueCapacity(30))
        // 50 audio packets/s over 2.5 s.
        assertEquals(125, PlaybackDelayProfile.AUTO.audioQueueCapacity())
    }

    @Test
    fun callsAdaptWithinAConversationalBound() {
        assertEquals(PlaybackDelayProfile.CALL, PlaybackDelayProfile.AUTO.forCall(constrainedLink = false))
        assertEquals(250L, PlaybackDelayProfile.CALL.delayMs)
        assertEquals(600L, PlaybackDelayProfile.CALL.adaptiveMaxDelayMs)
        assertEquals(PlaybackDelayProfile.CALL_STABLE, PlaybackDelayProfile.AUTO.forCall(constrainedLink = true))
        assertEquals(800L, PlaybackDelayProfile.CALL_STABLE.adaptiveMaxDelayMs)
        val low = PlaybackDelayProfile.LOW.forCall(constrainedLink = false)
        assertEquals(30L, low.delayMs)
        assertEquals(200L, low.adaptiveMaxDelayMs)
        // Every call profile adapts, and none exceeds the 800 ms conversational ceiling.
        PlaybackDelayProfile.entries.filter { it.name.startsWith("CALL") }.forEach {
            assertEquals(true, it.adaptive)
            assertEquals(true, it.adaptiveMaxDelayMs <= 800L)
        }
    }

    @Test
    fun sliderStopsMapDirectlyToTheThreeProfiles() {
        assertEquals(PlaybackDelayProfile.LOW, PlaybackDelayProfile.fromSliderValue(0f))
        assertEquals(PlaybackDelayProfile.BALANCED, PlaybackDelayProfile.fromSliderValue(1f))
        assertEquals(PlaybackDelayProfile.STABLE, PlaybackDelayProfile.fromSliderValue(2f))
        assertEquals("Medium", PlaybackDelayProfile.BALANCED.stabilityLabel)
        assertEquals(1f, PlaybackDelayProfile.AUTO.sliderValue)
    }
}
