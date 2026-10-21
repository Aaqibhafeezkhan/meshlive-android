package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** prewarm-ab-20261020a-d: the S22's camera delivered 4 exactly blank buffers before its first real frame. */
class LeadingBlankFramesTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun theS22SequenceLosesExactlyItsFourBlankBuffers() {
        val gate = LeadingBlankFrames { true }
        val luma = listOf(0.0, 0.0, 0.0, 0.0, 50.7, 53.0, 54.1)
        val dropped = luma.mapIndexed { i, y -> gate.drop(y, 128.0, 128.0, nowMs = i * 67L) }
        assertEquals(listOf(true, true, true, true, false, false, false), dropped)
        assertEquals(4, gate.dropped)
        assertFalse("the check ends at the first real frame", gate.checking)
        assertFalse("never mid-stream", gate.drop(0.0, 128.0, 128.0, 1_000L))
    }

    @Test
    fun aDarkRealSceneIsNotDroppedAndTheDropIsBounded() {
        assertFalse("sensor noise in the dark", LeadingBlankFrames.isBlank(2.0, 127.0, 129.0))
        assertFalse("tinted, not neutral", LeadingBlankFrames.isBlank(0.0, 120.0, 128.0))
        assertTrue(LeadingBlankFrames.isBlank(0.0, 128.0, 128.0))

        val frames = LeadingBlankFrames { true }
        val byCount = (0 until 20).count { frames.drop(0.0, 128.0, 128.0, it * 10L) }
        assertEquals(LeadingBlankFrames.MAX_FRAMES, byCount)

        val time = LeadingBlankFrames { true }
        assertTrue(time.drop(0.0, 128.0, 128.0, 0L))
        assertFalse("1 s after the first frame the stream starts anyway", time.drop(0.0, 128.0, 128.0, 1_000L))

        assertFalse("off by default (Experimental)", LeadingBlankFrames { false }.drop(0.0, 128.0, 128.0, 0L))
        assertFalse(DebugTunables.dropLeadingBlankFrames)
    }

    @Test
    fun bothImageInputPathsAskBeforeTheEncoderSeesTheFrame() {
        val encoder = source("CameraVideoEncoder.kt")
        val listener = encoder.substringAfter("reader.setOnImageAvailableListener({ availableReader ->").substringBefore("}, worker)")
        assertTrue(listener.contains("if (!dropLeadingBlank(image)) queueNativeFrame(image)"))
        assertTrue(listener.indexOf("dropLeadingBlank(image)") < listener.indexOf("pendingImage = image"))
    }
}
