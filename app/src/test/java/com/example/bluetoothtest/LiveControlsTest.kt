package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live screen's controls, Mute and Camera off while live, and the state records viewers see. */
class LiveControlsTest {
    @Test
    fun slotsAreOddWithEndInTheMiddle() {
        val video = LiveControlSlots.slots(audioOnly = false)
        assertEquals(listOf(LiveSlot.MUTE, LiveSlot.CAMERA, LiveSlot.END, LiveSlot.FLIP, LiveSlot.PAUSE), video)
        assertTrue(LiveControlSlots.endIsCentred(video))
        val audio = LiveControlSlots.slots(audioOnly = true)
        assertEquals(listOf(LiveSlot.MUTE, LiveSlot.END, LiveSlot.PAUSE), audio)
        assertTrue(LiveControlSlots.endIsCentred(audio))
        // An even row could not centre End.
        assertFalse(LiveControlSlots.endIsCentred(listOf(LiveSlot.MUTE, LiveSlot.END, LiveSlot.PAUSE, LiveSlot.FLIP)))
    }

    @Test
    fun buttonVisibilityRules() {
        fun shown(slot: LiveSlot, pause: Boolean = true, cameras: Int = 2, raw: Boolean = false) =
            LiveControlSlots.shown(slot, pause, cameras, raw)
        assertTrue(shown(LiveSlot.MUTE))
        assertTrue(shown(LiveSlot.CAMERA))
        assertTrue(shown(LiveSlot.END))
        // Flip needs a second camera and a real camera.
        assertFalse(shown(LiveSlot.FLIP, cameras = 1))
        assertFalse(shown(LiveSlot.FLIP, raw = true))
        assertTrue(shown(LiveSlot.FLIP))
        // Pause shows once the broadcast can be paused.
        assertFalse(shown(LiveSlot.PAUSE, pause = false))
        assertTrue(shown(LiveSlot.PAUSE))
    }

    @Test
    fun buttonsStopWhileThePausedBroadcastCapturesNothing() {
        assertTrue(LiveControlSlots.enabled(LiveSlot.MUTE, paused = false, cameraOff = false))
        assertFalse(LiveControlSlots.enabled(LiveSlot.MUTE, paused = true, cameraOff = false))
        assertFalse(LiveControlSlots.enabled(LiveSlot.CAMERA, paused = true, cameraOff = false))
        // Flip needs the camera on.
        assertTrue(LiveControlSlots.enabled(LiveSlot.FLIP, paused = false, cameraOff = false))
        assertFalse(LiveControlSlots.enabled(LiveSlot.FLIP, paused = false, cameraOff = true))
        // End and Pause always work (Pause is also Resume).
        assertTrue(LiveControlSlots.enabled(LiveSlot.END, paused = true, cameraOff = true))
        assertTrue(LiveControlSlots.enabled(LiveSlot.PAUSE, paused = true, cameraOff = true))
    }

    @Test
    fun muteAndCameraOffAreIndependentRecords() {
        val state = LiveMediaState()
        assertEquals(StreamLifecycleState.LIVE, state.record)
        assertFalse(state.differsFromLive)
        assertEquals(StreamLifecycleState.LIVE_MUTED, state.toggleMic())
        assertEquals(StreamLifecycleState.LIVE_MUTED_CAMERA_OFF, state.toggleCamera())
        assertTrue(state.differsFromLive)
        assertEquals(StreamLifecycleState.LIVE_CAMERA_OFF, state.toggleMic())
        assertEquals(StreamLifecycleState.LIVE, state.toggleCamera())
        assertFalse(state.differsFromLive)
    }

    @Test
    fun aPauseTurnsTheCameraBackOnButKeepsTheMute() {
        val state = LiveMediaState()
        state.toggleMic()
        state.toggleCamera()
        state.onPaused()
        assertTrue(state.micMuted)
        assertFalse(state.cameraOff)
        assertEquals(StreamLifecycleState.LIVE_MUTED, state.record)
        state.reset()
        assertEquals(StreamLifecycleState.LIVE, state.record)
    }

    @Test
    fun mutedAudioIsDroppedButTheCodecConfigurationAlwaysPasses() {
        val state = LiveMediaState()
        assertTrue(state.passesAudio(StreamProtocol.FLAG_AUDIO))
        state.toggleMic()
        assertFalse(state.passesAudio(StreamProtocol.FLAG_AUDIO))
        assertTrue(state.passesAudio(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG))
        state.toggleMic()
        assertTrue(state.passesAudio(StreamProtocol.FLAG_AUDIO))
    }

    @Test
    fun theNewRecordsHaveTheirOwnWireValuesAndTheOldOnesAreUntouched() {
        assertEquals(1, StreamLifecycleState.LIVE.wireValue)
        assertEquals(2, StreamLifecycleState.PAUSED.wireValue)
        assertEquals(3, StreamLifecycleState.ENDED.wireValue)
        assertEquals(4, StreamLifecycleState.LIVE_MUTED.wireValue)
        assertEquals(5, StreamLifecycleState.LIVE_CAMERA_OFF.wireValue)
        assertEquals(6, StreamLifecycleState.LIVE_MUTED_CAMERA_OFF.wireValue)
        StreamLifecycleState.entries.forEach { state ->
            assertArrayEquals(byteArrayOf(state.wireValue.toByte()), state.encode())
            assertEquals(state, StreamLifecycleState.decode(state.encode()))
        }
        assertTrue(runCatching { StreamLifecycleState.decode(byteArrayOf(7)) }.isFailure)
        assertTrue(StreamLifecycleState.LIVE_MUTED.isLiveFamily)
        assertFalse(StreamLifecycleState.PAUSED.isLiveFamily)
        assertFalse(StreamLifecycleState.ENDED.isLiveFamily)
    }

    @Test
    fun viewersShowMutedAndCameraOffOnlyWhileLive() {
        assertEquals(ViewerMediaNotice(micMuted = true, cameraOff = false), ViewerMediaNotice.of(StreamLifecycleState.LIVE_MUTED))
        assertEquals(ViewerMediaNotice(micMuted = false, cameraOff = true), ViewerMediaNotice.of(StreamLifecycleState.LIVE_CAMERA_OFF))
        assertEquals(ViewerMediaNotice(micMuted = true, cameraOff = true), ViewerMediaNotice.of(StreamLifecycleState.LIVE_MUTED_CAMERA_OFF))
        // Plain LIVE, a pause and an end all clear the notice (the pause and end have their own overlay).
        listOf(StreamLifecycleState.LIVE, StreamLifecycleState.PAUSED, StreamLifecycleState.ENDED).forEach {
            assertEquals(ViewerMediaNotice.NONE, ViewerMediaNotice.of(it))
        }
        assertFalse(ViewerMediaNotice.NONE.any)
    }

    @Test
    fun theSwitchIsOffByDefault() {
        assertFalse(ViewerFeatures.liveMediaState.defaultEnabled)
        assertFalse(CallBluetoothOutputFeature.spec.defaultEnabled)
        assertTrue(ViewerFeatures.all.contains(ViewerFeatures.liveMediaState))
        assertTrue(ViewerFeatures.all.contains(CallBluetoothOutputFeature.spec))
    }
}
