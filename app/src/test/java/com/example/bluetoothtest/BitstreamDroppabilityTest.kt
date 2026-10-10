package com.example.bluetoothtest

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitstreamDroppabilityTest {
    // frame_marker 10, profile 0, show_existing 0, inter, shown, error resilient, then refresh flags.
    private fun resilientInter(refresh: Int) = byteArrayOf(0x87.toByte(), refresh.toByte(), 0x55)

    // Same without error resilience: two reset_frame_context bits precede refresh_frame_flags.
    private val nonResilientInter = byteArrayOf(0x86.toByte(), 0x00, 0x00, 0x55)

    @Test
    fun vp9HeaderFieldsAreRead() {
        val header = BitstreamDroppability.vp9Header(resilientInter(0x01))!!
        assertFalse(header.keyFrame)
        assertTrue(header.errorResilient)
        assertEquals(0x01, header.refreshFrameFlags)
        assertTrue(BitstreamDroppability.vp9Header(byteArrayOf(0x83.toByte(), 0, 0))!!.keyFrame)
    }

    @Test
    fun onlyErrorResilientNonRefreshingVp9FramesAreDroppable() {
        val analyzer = BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_VP9)
        assertTrue(analyzer.isDroppable(resilientInter(0), keyFrame = false))
        assertFalse(analyzer.isDroppable(resilientInter(0x01), keyFrame = false))
        assertFalse(analyzer.isDroppable(resilientInter(0), keyFrame = true))
        assertEquals(1L, analyzer.droppableFrames)
    }

    @Test
    fun oneNonResilientFrameDisablesDroppingForTheSession() {
        // The next frame would inherit motion vectors from a dropped frame.
        val analyzer = BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_VP9)
        assertFalse(analyzer.isDroppable(nonResilientInter, keyFrame = false))
        assertFalse(analyzer.isDroppable(resilientInter(0), keyFrame = false))
    }

    @Test
    fun superframesAndOtherCodecsAreNeverDroppable() {
        val superframe = byteArrayOf(0x87.toByte(), 0x00, 0xc0.toByte(), 0x05, 0xc0.toByte())
        assertFalse(BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_VP9).isDroppable(superframe, keyFrame = false))
        assertFalse(BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_AV1).isDroppable(resilientInter(0), keyFrame = false))
        assertFalse(BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_VP9).isDroppable(byteArrayOf(), keyFrame = false))
    }
}
