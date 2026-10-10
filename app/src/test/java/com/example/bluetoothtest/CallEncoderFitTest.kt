package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class CallEncoderFitTest {
    private val vga = VideoResolution(640, 480, "480p")
    private val q360 = VideoResolution(480, 360, "360p")
    private val cif = VideoResolution(352, 288, "CIF")
    private val modes = listOf(
        DeviceVideoMode(vga, listOf(15, 30), null),
        DeviceVideoMode(q360, listOf(15, 30), null),
        DeviceVideoMode(cif, listOf(15), null),
        DeviceVideoMode(VideoResolution(320, 240, "240p"), listOf(30), null),
    )
    private val requested = VideoSettings(vga, fps = 15, bitRate = 600_000, latencyMs = 500)

    @Test
    fun keepsTheRequestedGeometryUnlessThisPhoneRecordedAnOverload() {
        assertEquals(requested, CallEncoderFit.fitted(requested, modes) { _, _, _ -> false })
    }

    @Test
    fun stepsToTheLargestSmallerModeAtTheSameFrameRateThatIsNotOverloaded() {
        val overloaded = setOf("640x480@15")
        val fitted = CallEncoderFit.fitted(requested, modes) { w, h, fps -> nativeEncoderSpeedKey(w, h, fps) in overloaded }
        assertEquals(q360, fitted.resolution)
        assertEquals(15, fitted.fps)
        assertEquals(600_000, fitted.bitRate)
        // 480x360 also overloaded earlier: 352x288. 320x240 offers no 15 FPS and is never chosen.
        val both = setOf("640x480@15", "480x360@15")
        assertEquals(cif, CallEncoderFit.fitted(requested, modes) { w, h, fps -> nativeEncoderSpeedKey(w, h, fps) in both }.resolution)
    }

    @Test
    fun noSmallerOfferedModeKeepsTheRequest() {
        val all = setOf("640x480@15", "480x360@15", "352x288@15")
        assertEquals(requested, CallEncoderFit.fitted(requested, modes) { w, h, fps -> nativeEncoderSpeedKey(w, h, fps) in all })
        assertEquals(requested, CallEncoderFit.fitted(requested, emptyList()) { _, _, _ -> true })
    }
}
