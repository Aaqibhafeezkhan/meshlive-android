package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** vab-20261020b/c: the first key frame came 995 ms (median) after the viewer authenticated, because media started then. */
class SourceMediaPrewarmTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun prewarmOnlyForCameraStreamsWithoutTheAutoLadderOrReplay() {
        assertTrue(SourceMediaPrewarm.applies(enabled = true, audioOnly = false, autoLadder = false, replay = false))
        assertFalse("the off arm does not pre-warm", SourceMediaPrewarm.applies(false, false, false, false))
        assertFalse("radio: no camera", SourceMediaPrewarm.applies(true, true, false, false))
        assertFalse("the Auto ladder's start rate needs the connected peers", SourceMediaPrewarm.applies(true, false, true, false))
        assertFalse("replay", SourceMediaPrewarm.applies(true, false, false, true))
        assertTrue("on by default (design decision 2026-10-21)", DebugTunables.sourceMediaPrewarm)
    }

    @Test
    fun theFirstViewerUsesThePrewarmedEncoderAndOnlyThenOpensTheMicrophone() {
        assertEquals(SourceMediaPrewarm.FirstViewer.KEY_FRAME_AND_MICROPHONE, SourceMediaPrewarm.onFirstViewer(prewarmed = true, encoderRunning = true))
        assertEquals("the pre-warm failed: start as before", SourceMediaPrewarm.FirstViewer.START_MEDIA, SourceMediaPrewarm.onFirstViewer(true, false))
        assertEquals(SourceMediaPrewarm.FirstViewer.START_MEDIA, SourceMediaPrewarm.onFirstViewer(false, false))
        assertTrue("no authenticated viewer yet", SourceMediaPrewarm.holdMicrophone(prewarmed = true, connectedViewers = 0))
        assertFalse(SourceMediaPrewarm.holdMicrophone(prewarmed = true, connectedViewers = 1))
        assertFalse("off: as before", SourceMediaPrewarm.holdMicrophone(prewarmed = false, connectedViewers = 0))
    }

}
