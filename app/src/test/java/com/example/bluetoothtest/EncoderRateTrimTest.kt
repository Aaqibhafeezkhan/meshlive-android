package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderRateTrimTest {
    @Test
    fun aSustainedOvershootIsCorrectedOnItsAverage() {
        // S24 c2.exynos.vp9 with temporal layers: ~430 kbps for a 300 kbps request.
        val trim = EncoderRateTrim()
        assertNull(trim.onWindow(300_000, 430_000.0))
        assertNull(trim.onWindow(300_000, 430_000.0))
        assertEquals(209_302, trim.onWindow(300_000, 430_000.0))
    }

    @Test
    fun aSingleBurstWindowDoesNotTrim() {
        // A 55 KB keyframe adds ~88 kbps to one 5 s window at 300 kbps.
        val trim = EncoderRateTrim()
        assertNull(trim.onWindow(300_000, 388_000.0))
        assertNull(trim.onWindow(300_000, 300_000.0))
        assertNull(trim.onWindow(300_000, 300_000.0))
        assertEquals(1.0, trim.factor, 0.0)
    }

    @Test
    fun burstyContentIsTrimmedOnItsAverageNotConsecutiveWindows() {
        // kfshape-a99f1ef7: M31 OMX VP9 at a 120 kbps request after a link cut.
        val trim = EncoderRateTrim()
        trim.onRequestChanged()
        assertNull(trim.onWindow(120_000, 248_000.0))
        assertNull(trim.onWindow(120_000, 174_000.0))
        assertNull(trim.onWindow(120_000, 247_000.0))
        assertEquals(85_207, trim.onWindow(120_000, 86_000.0))
    }

    @Test
    fun undershootGivesTheTrimBackButNeverExceedsTheRequest() {
        val trim = EncoderRateTrim()
        repeat(2) { trim.onWindow(300_000, 600_000.0) }
        assertEquals(150_000, trim.onWindow(300_000, 600_000.0))
        assertNull(trim.onWindow(300_000, 187_500.0))
        repeat(2) { trim.onWindow(300_000, 187_500.0) }
        assertEquals(240_000, trim.onWindow(300_000, 187_500.0))
        assertNull(trim.onWindow(300_000, 150_000.0))
        repeat(2) { trim.onWindow(300_000, 150_000.0) }
        assertEquals(300_000, trim.onWindow(300_000, 150_000.0))
        assertNull(trim.onWindow(300_000, 150_000.0))
        repeat(3) { assertNull(trim.onWindow(300_000, 150_000.0)) }
    }

    @Test
    fun windowsRightAfterARequestChangeAreNotJudged() {
        val trim = EncoderRateTrim()
        trim.onRequestChanged()
        assertNull(trim.onWindow(240_000, 792_000.0))
        assertNull(trim.onWindow(240_000, 792_000.0))
        assertNull(trim.onWindow(240_000, 300_000.0))
        assertEquals(1.0, trim.factor, 0.0)
    }
}
