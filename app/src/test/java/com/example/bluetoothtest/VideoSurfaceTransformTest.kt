package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSurfaceTransformTest {
    @Test
    fun restoresPersistedScaleModeAndFallsBackToFill() {
        assertEquals(VideoScaleMode.FILL, VideoScaleMode.fromStoredValue("FILL"))
        assertEquals(VideoScaleMode.FIT, VideoScaleMode.fromStoredValue("fit"))
        assertEquals(VideoScaleMode.FILL, VideoScaleMode.fromStoredValue("removed-mode"))
        assertEquals(VideoScaleMode.FILL, VideoScaleMode.fromStoredValue(null))
    }

    @Test
    fun unifiedFramingMigrationKeepsFullFrameIfEitherOldRoleRequestedIt() {
        assertEquals(
            VideoScaleMode.FIT,
            VideoScaleMode.fromUnifiedOrLegacyValues(null, "FILL", "FIT"),
        )
        assertEquals(
            VideoScaleMode.FIT,
            VideoScaleMode.fromUnifiedOrLegacyValues(null, "FIT", "FILL"),
        )
        assertEquals(
            VideoScaleMode.FILL,
            VideoScaleMode.fromUnifiedOrLegacyValues("FILL", "FIT", "FIT"),
        )
    }

    @Test
    fun fitsRotatedPortraitVideoWithoutStretching() {
        val values = VideoSurfaceTransform.calculateMatrixValues(
            viewWidth = 1_000,
            viewHeight = 2_000,
            encodedWidth = 640,
            encodedHeight = 360,
            rotationDegrees = 90,
            scaleMode = VideoScaleMode.FIT,
        )

        assertEquals(0f, values[0], 0.001f)
        assertEquals(-0.5f, values[1], 0.001f)
        assertEquals(1_000f, values[2], 0.001f)
        assertEquals(1.7778f, values[3], 0.001f)
        assertEquals(111.111f, values[5], 0.01f)
    }

    @Test
    fun fitsLandscapeVideoWithoutStretching() {
        val values = VideoSurfaceTransform.calculateMatrixValues(
            viewWidth = 1_000,
            viewHeight = 2_000,
            encodedWidth = 640,
            encodedHeight = 360,
            rotationDegrees = 0,
            scaleMode = VideoScaleMode.FIT,
        )

        assertEquals(1f, values[0], 0.001f)
        assertEquals(0.28125f, values[4], 0.001f)
        assertEquals(718.75f, values[5], 0.001f)
    }

    @Test
    fun fillsPortraitScreenByCroppingWithoutStretching() {
        val values = VideoSurfaceTransform.calculateMatrixValues(
            viewWidth = 1_000,
            viewHeight = 2_000,
            encodedWidth = 640,
            encodedHeight = 360,
            rotationDegrees = 90,
            scaleMode = VideoScaleMode.FILL,
        )

        assertEquals(0f, values[0], 0.001f)
        assertEquals(-0.5625f, values[1], 0.001f)
        assertEquals(1_062.5f, values[2], 0.001f)
        assertEquals(2f, values[3], 0.001f)
        assertEquals(0f, values[5], 0.001f)
    }

}
