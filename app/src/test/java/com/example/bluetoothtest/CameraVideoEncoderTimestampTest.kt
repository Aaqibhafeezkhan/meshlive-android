package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraVideoEncoderTimestampTest {
    @Test
    fun keepsCodecTimestampAlreadyUsingElapsedRealtimeEpoch() {
        assertEquals(
            0L,
            codecPresentationTimeEpochOffsetUs(
                presentationTimeUs = 30_000_000L,
                elapsedRealtimeUs = 30_020_000L,
                monotonicTimeUs = 30_020_000L,
            ),
        )
    }

    @Test
    fun mapsNearZeroCodecEpochOntoElapsedRealtimeEpoch() {
        assertEquals(
            30_000_000L,
            codecPresentationTimeEpochOffsetUs(
                presentationTimeUs = 20_000L,
                elapsedRealtimeUs = 30_020_000L,
                monotonicTimeUs = 10_020_000L,
            ),
        )
    }

    @Test
    fun mappingMonotonicCaptureEpochDoesNotEraseEncoderDelay() {
        val captureTimeUs = 9_850_000L
        val offsetUs = codecPresentationTimeEpochOffsetUs(
            presentationTimeUs = captureTimeUs,
            elapsedRealtimeUs = 30_000_000L,
            monotonicTimeUs = 10_000_000L,
        )
        assertEquals(20_000_000L, offsetUs)
        assertEquals(29_850_000L, captureTimeUs + offsetUs)
    }
}
