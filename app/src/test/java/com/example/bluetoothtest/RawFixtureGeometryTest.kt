package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFixtureGeometryTest {
    /** 300 frames of 640x360 I420: the fixture of the ASUS to S24 dark-scene A/B. */
    private val fixtureBytes = 103_680_000L

    @Test
    fun theFixtureIsAnExactMultipleOfTwoGeometries() {
        // The hole: read as 640x480 it is a whole 225 frames, so a size check alone cannot see the error.
        assertEquals(300L, fixtureBytes / RawFixtureGeometry.frameBytes(640, 360)!!)
        assertEquals(225L, fixtureBytes / RawFixtureGeometry.frameBytes(640, 480)!!)
        assertEquals(0L, fixtureBytes % RawFixtureGeometry.frameBytes(640, 480)!!)
    }

    @Test
    fun anEncoderAtAnotherSizeIsRefusedNotReinterpreted() {
        val check = RawFixtureGeometry.check(fixtureBytes, 640, 360, 640, 480)
        assertFalse(check.ok)
        assertEquals(300L, check.frames) // counted at the declared size, never the encoder's
        assertTrue(check.reason, check.reason.contains("640x360") && check.reason.contains("640x480"))
        assertTrue(check.reason.contains("no reinterpretation"))
    }

    @Test
    fun theDeclaredSizeAtTheEncoderSizeIsAccepted() {
        val check = RawFixtureGeometry.check(fixtureBytes, 640, 360, 640, 360)
        assertTrue(check.ok)
        assertEquals(300L, check.frames)
    }

    @Test
    fun aFileThatIsNotWholeFramesOfTheDeclaredSizeIsRefused() {
        assertFalse(RawFixtureGeometry.check(fixtureBytes + 1, 640, 360, 640, 360).ok)
        assertFalse(RawFixtureGeometry.check(0, 640, 360, 640, 360).ok)
        assertFalse(RawFixtureGeometry.check(345_599, 640, 360, 640, 360).ok)
        // A file of 225 frames of 640x480 plus a byte is not whole frames of any declared size here.
        assertFalse(RawFixtureGeometry.check(225L * 460_800 + 1, 640, 360, 640, 360).ok)
    }

    @Test
    fun oddOrEmptyGeometryIsNotI420() {
        assertNull(RawFixtureGeometry.frameBytes(639, 360))
        assertNull(RawFixtureGeometry.frameBytes(640, 0))
        assertFalse(RawFixtureGeometry.check(1_000, 639, 360, 639, 360).ok)
    }

    @Test
    fun theDeclaredGeometryComesFromTheRunnerThenFromTheRequestNeverFromTheEncoder() {
        assertEquals(640 to 360, RawFixtureGeometry.declared(640, 360, 640, 480))
        assertEquals(640 to 360, RawFixtureGeometry.declared(0, 0, 640, 360))
        assertEquals(640 to 360, RawFixtureGeometry.declared(640, 0, 640, 360))
        assertNull(RawFixtureGeometry.declared(0, 0, 0, 0))
    }

    @Test
    fun theBenchmarkLineNamesTheFileTheDeclaredAndTheEncoderGeometry() {
        val refused = RawFixtureGeometry.check(fixtureBytes, 640, 360, 640, 480)
        val line = RawFixtureGeometry.line("benchmark-raw-86e9.yuv", fixtureBytes, 640 to 360, 640, 480, refused)
        assertTrue(line, line.startsWith("BENCHMARK raw_source: status=REFUSED | file=benchmark-raw-86e9.yuv"))
        assertTrue(line.contains("declared=640x360 | encoder=640x480 | frames=300"))
        val ok = RawFixtureGeometry.line("f.yuv", fixtureBytes, null, 640, 360, RawFixtureGeometry.check(fixtureBytes, 640, 360, 640, 360))
        assertTrue(ok.contains("status=OK") && ok.contains("declared=none"))
    }

    @Test
    fun pickersNeverChangeTheSettingsWhileARawFixtureIsActive() {
        assertFalse(RawFixtureGeometry.pickersMayChangeSettings(rawInputActive = true))
        assertTrue(RawFixtureGeometry.pickersMayChangeSettings(rawInputActive = false))
    }

    private fun mode(width: Int, height: Int, vararg fps: Int) =
        DeviceVideoMode(VideoResolution(width, height, "${height}p"), fps.toList(), null)

    private fun settings(width: Int, height: Int, fps: Int) =
        VideoSettings(VideoResolution(width, height, "${height}p"), fps, 500_000, 100)

    private val modes = listOf(mode(176, 144, 15, 30), mode(640, 480, 15, 30), mode(1280, 720, 30))

    @Test
    fun theRawStartGateNeedsNoCameraModeWhateverTheSpinnerSelects() {
        // Lane 11: on the raw path the spinner stays at position 0 (the smallest camera mode).
        val spinnerAtZero = modes[0]
        for ((w, h) in listOf(640 to 360, 640 to 480, 176 to 144, 1280 to 720)) {
            assertTrue("raw ${w}x$h", RawFixtureGeometry.startGateModeOk(true, spinnerAtZero, settings(w, h, 30)))
        }
        assertTrue(RawFixtureGeometry.startGateModeOk(true, null, settings(640, 360, 30)))
    }

    @Test
    fun theCameraStartGateStillRequiresTheSelectedModeToMatchResolutionAndFps() {
        assertTrue(RawFixtureGeometry.startGateModeOk(false, modes[1], settings(640, 480, 30)))
        // Size mismatch (the spinner is on 176x144, the settings ask for 640x480).
        assertFalse(RawFixtureGeometry.startGateModeOk(false, modes[0], settings(640, 480, 30)))
        // No camera mode for 640x360 at all.
        assertFalse(RawFixtureGeometry.startGateModeOk(false, modes[1], settings(640, 360, 30)))
        // Right size, a rate the mode does not offer.
        assertFalse(RawFixtureGeometry.startGateModeOk(false, modes[2], settings(1280, 720, 15)))
        assertFalse(RawFixtureGeometry.startGateModeOk(false, null, settings(640, 480, 30)))
    }

}
