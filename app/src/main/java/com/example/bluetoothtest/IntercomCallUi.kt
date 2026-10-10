package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.Closeable

/**
 * Presentation of the experimental intercom call features (IntercomCall.kt): the voice-call panel
 * (peer label, call type, timer) and the push-to-talk button with its cue. It never touches
 * capture, codecs or transport; the activity applies the returned state to the call endpoint.
 * With every feature off (the default) it stays hidden and the call screen is unchanged.
 */
internal class IntercomCallUi(
    private val voicePanel: View,
    private val voicePeerText: TextView,
    private val voiceTypeText: TextView,
    private val voiceTimer: Chronometer,
    private val pushToTalkButton: Button,
    /** pressed, source ("touch", "accessibility" or "debug"). Returns true when applied. */
    private val onPushToTalk: (Boolean, String) -> Boolean,
) : Closeable {
    private val context = voicePanel.context
    private var peerLabel = ""
    private var layoutState: CallLayoutState? = null
    private var timerRunning = false
    private var cue: ToneGenerator? = null

    /** Speaker/earpiece policy of the current call (voice calls feature only). */
    var routePolicy: CallAudioRoutePolicy? = null
        private set

    /** Push-to-talk state of the current call (push-to-talk feature only). */
    var pushToTalk: PushToTalkState? = null
        private set

    val layout: CallLayout? get() = layoutState?.layout
    val localVideo: Boolean get() = layoutState?.localVideo ?: true
    val remoteVideo: Boolean get() = layoutState?.remoteVideo ?: true

    init {
        bindPushToTalkButton()
    }

    /** A new call screen: nothing intercom-specific is visible. */
    fun reset() {
        peerLabel = ""
        layoutState = null
        routePolicy = null
        pushToTalk = null
        stopTimer()
        voicePanel.visibility = View.GONE
        pushToTalkButton.visibility = View.GONE
        updatePushToTalkButton()
    }

    fun setPeerLabel(label: String) {
        peerLabel = label
        voicePeerText.text = label
    }

    /** Ringing or calling as a voice call: the panel replaces the black video area. */
    fun showPendingVoice(incoming: Boolean) {
        voiceTypeText.text = if (incoming) "Incoming voice call" else "Calling…"
        voiceTypeText.visibility = View.VISIBLE
        voicePanel.visibility = View.VISIBLE
    }

    /** The ringing call turned out not to be a voice offer. */
    fun hidePendingVoice() {
        if (layoutState == null) voicePanel.visibility = View.GONE
    }

    /**
     * Local media is starting with [callType] (the call is connected). Returns the initial muted
     * state (PTT mode starts muted) and arms the layout, route, timer and push-to-talk button.
     */
    fun startMedia(callType: CallType, features: IntercomFeatureSettings): Boolean {
        val voiceFeature = features.voiceCalls || callType == CallType.VOICE
        layoutState = if (voiceFeature) CallLayoutState(callType) else null
        routePolicy = if (voiceFeature) CallAudioRoutePolicy(callType) else null
        pushToTalk = if (features.pushToTalkButton) PushToTalkState(startMuted = features.pttMode) else null
        pushToTalkButton.visibility = if (pushToTalk != null) View.VISIBLE else View.GONE
        if (layoutState != null && !timerRunning) {
            voiceTimer.base = SystemClock.elapsedRealtime()
            voiceTimer.start()
            timerRunning = true
        }
        applyLayout()
        updatePushToTalkButton()
        return pushToTalk?.muted ?: false
    }

    /** Each returns true when the layout changed. */
    fun onLocalVideo(on: Boolean): Boolean = layoutState?.onLocalVideo(on)?.also { if (it) applyLayout() } ?: false

    fun onRemoteCamera(enabled: Boolean): Boolean =
        layoutState?.onRemoteCameraSignal(enabled)?.also { if (it) applyLayout() } ?: false

    fun onRemoteFrame(): Boolean = layoutState?.onRemoteFrame()?.also { if (it) applyLayout() } ?: false

    private fun applyLayout() {
        val voice = layoutState?.layout == CallLayout.VOICE
        voicePanel.visibility = if (voice) View.VISIBLE else View.GONE
        if (voice) {
            // Connected: the timer is the state line, so the type line steps aside.
            voiceTypeText.visibility = View.GONE
        }
    }

    /** Short haptic feedback always; a quiet local tone unless an automated run set the speaker gain. */
    fun playCue(pressed: Boolean) {
        pushToTalkButton.performHapticFeedback(
            if (pressed) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,
        )
        if (DebugTunables.playbackGain != null) return
        val generator = cue ?: runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, CUE_VOLUME) }
            .getOrNull()?.also { cue = it } ?: return
        generator.startTone(
            if (pressed) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK,
            if (pressed) PRESS_CUE_MS else RELEASE_CUE_MS,
        )
    }

    fun updatePushToTalkButton() {
        val state = pushToTalk
        val held = state?.held == true
        pushToTalkButton.text = when {
            held -> "Talking… release to mute"
            state?.muted == false -> "Hold to talk (microphone open)"
            else -> "Hold to talk"
        }
        pushToTalkButton.contentDescription = if (held) {
            "Talking. Release to mute your microphone."
        } else {
            "Push to talk. Touch and hold to talk; your microphone is muted when you let go."
        }
        pushToTalkButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (held) R.color.record_red else R.color.accent),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindPushToTalkButton() {
        pushToTalkButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    onPushToTalk(true, "touch")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    onPushToTalk(false, "touch")
                }
            }
            true
        }
        // Screen readers cannot always hold: a double tap toggles talking on and off instead.
        pushToTalkButton.setOnClickListener {
            onPushToTalk(pushToTalk?.held != true, "accessibility")
        }
    }

    private fun stopTimer() {
        voiceTimer.stop()
        voiceTimer.base = SystemClock.elapsedRealtime()
        timerRunning = false
    }

    override fun close() {
        reset()
        cue?.release()
        cue = null
    }

    private companion object {
        const val CUE_VOLUME = 35
        const val PRESS_CUE_MS = 60
        const val RELEASE_CUE_MS = 90
    }
}
