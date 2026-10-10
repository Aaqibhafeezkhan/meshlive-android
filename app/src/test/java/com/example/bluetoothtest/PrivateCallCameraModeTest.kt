package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateCallCameraModeTest {
    private val nativeModes = listOf(
        DeviceVideoMode(VideoResolution(320, 240, "240p"), listOf(15, 30), null),
        DeviceVideoMode(VideoResolution(640, 360, "360p"), listOf(15, 30), null),
    )

    @Test
    fun unsupportedNegotiatedSizeUsesAnActualCameraAndEncoderMode() {
        val requested = VideoSettings.DEFAULT.copy(
            resolution = VideoResolution(426, 240, "240p"), fps = 15, bitRate = 110_000,
        )

        val selected = privateCallCameraVideoSettings(requested, nativeModes)

        assertEquals(nativeModes[0].resolution, selected.resolution)
        assertEquals(15, selected.fps)
        assertEquals(110_000, selected.bitRate)
    }

    @Test
    fun compatibleNativeProfileIsUnchanged() {
        val requested = VideoSettings.DEFAULT.copy(resolution = nativeModes[1].resolution, fps = 30)

        assertEquals(requested, privateCallCameraVideoSettings(requested, nativeModes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedFpsFailsBeforeSendingAHeaderOrStartingTheCamera() {
        privateCallCameraVideoSettings(VideoSettings.DEFAULT.copy(fps = 10), nativeModes)
    }
}
