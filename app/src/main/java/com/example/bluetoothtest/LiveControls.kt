package com.example.bluetoothtest

/** The round controls of a live (broadcast) screen, left to right. */
internal enum class LiveSlot { MUTE, CAMERA, END, FLIP, PAUSE }

/**
 * Which controls a live screen shows and when they work (JVM-tested). One row, an odd number of equal
 * columns, End in the middle: video live is Mute, Camera, End, Flip, Pause; audio-only live is Mute, End,
 * Pause. Before going live the row holds only Flip (when the phone has two cameras) and the wide Go live
 * button. A slot that has nothing to do keeps its place (hidden), so End never moves off the centre.
 */
internal object LiveControlSlots {
    fun slots(audioOnly: Boolean): List<LiveSlot> =
        if (audioOnly) listOf(LiveSlot.MUTE, LiveSlot.END, LiveSlot.PAUSE)
        else listOf(LiveSlot.MUTE, LiveSlot.CAMERA, LiveSlot.END, LiveSlot.FLIP, LiveSlot.PAUSE)

    /** End is exactly in the middle of an odd-length row. */
    fun endIsCentred(slots: List<LiveSlot>): Boolean =
        slots.size % 2 == 1 && slots.indexOf(LiveSlot.END) == slots.size / 2

    /** Whether the slot's button is shown. */
    fun shown(
        slot: LiveSlot,
        pauseAvailable: Boolean,
        cameraCount: Int,
        rawInput: Boolean,
    ): Boolean = when (slot) {
        LiveSlot.MUTE, LiveSlot.CAMERA, LiveSlot.END -> true
        LiveSlot.FLIP -> cameraCount > 1 && !rawInput
        LiveSlot.PAUSE -> pauseAvailable
    }

    /** Whether the shown button can be used right now. While the broadcast is paused nothing is captured. */
    fun enabled(slot: LiveSlot, paused: Boolean, cameraOff: Boolean): Boolean = when (slot) {
        LiveSlot.MUTE, LiveSlot.CAMERA -> !paused
        LiveSlot.FLIP -> !paused && !cameraOff
        LiveSlot.END, LiveSlot.PAUSE -> true
    }
}

/**
 * What a live broadcaster has switched off while still live: the microphone (audio stops, video goes on)
 * and/or the camera (video stops, audio goes on). Viewers are told with one state record
 * ([StreamLifecycleState.live]) when the Settings switch `live_media_state` is on; nothing is added to media packets.
 */
internal class LiveMediaState {
    var micMuted = false
        private set
    var cameraOff = false
        private set

    /** The record that describes the current state, to send after every change and to every late joiner. */
    val record: StreamLifecycleState get() = StreamLifecycleState.live(micMuted, cameraOff)

    /** A record is only needed while something is off; a plain LIVE already means "everything on". */
    val differsFromLive: Boolean get() = micMuted || cameraOff

    fun toggleMic(): StreamLifecycleState {
        micMuted = !micMuted
        return record
    }

    fun toggleCamera(): StreamLifecycleState {
        cameraOff = !cameraOff
        return record
    }

    /** A pause stops the camera and the microphone anyway; resuming starts the camera again. The mute is kept. */
    fun onPaused() {
        cameraOff = false
    }

    fun reset() {
        micMuted = false
        cameraOff = false
    }

    /**
     * Audio the microphone encoder produced: dropped while muted, except the codec configuration, which
     * must still reach every listener so audio decodes at once after Unmute.
     */
    fun passesAudio(flags: Int): Boolean = !micMuted || flags and StreamProtocol.FLAG_CODEC_CONFIG != 0
}

/** What a viewer shows for a state record. */
internal data class ViewerMediaNotice(val micMuted: Boolean, val cameraOff: Boolean) {
    val any: Boolean get() = micMuted || cameraOff

    companion object {
        val NONE = ViewerMediaNotice(false, false)

        /** PAUSED and ENDED clear the notice (their own overlay says what happened). */
        fun of(state: StreamLifecycleState): ViewerMediaNotice = when (state) {
            StreamLifecycleState.PAUSED, StreamLifecycleState.ENDED, StreamLifecycleState.LIVE -> NONE
            else -> ViewerMediaNotice(state.micMuted, state.cameraOff)
        }
    }
}
