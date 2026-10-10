package com.example.bluetoothtest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Call audio focus (bug-fix level, 2026-10-10; on for user sessions, benchmark extra
 * `test_call_audio_focus`).
 *
 * Before: the call never requested audio focus, so music, podcasts and navigation kept playing
 * over the call, and a cellular call that took the microphone left the peer hearing silence with
 * no explanation. Now the call requests AUDIOFOCUS_GAIN_TRANSIENT for USAGE_VOICE_COMMUNICATION
 * (developer.android.com/media/optimize/audio-focus) while its audio session is open. A transient
 * loss (Telecom takes focus for a ringing or active cellular call) holds the call: the microphone is
 * muted on the existing mute path, so the peer gets the ordinary Microphone muted signal (9), and
 * the regained focus restores the user's own mute choice (signal 10 when unmuted). A permanent loss
 * (the user started other audio) and duckable losses are only logged.
 */
internal enum class FocusAction { HOLD, RESUME, NONE }

internal object CallAudioFocusPolicy {
    fun actionFor(focusChange: Int): FocusAction = when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusAction.HOLD
        AudioManager.AUDIOFOCUS_GAIN,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> FocusAction.RESUME
        else -> FocusAction.NONE
    }

    fun label(focusChange: Int): String = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "gain_transient"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "gain_transient_exclusive"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "gain_transient_may_duck"
        AudioManager.AUDIOFOCUS_LOSS -> "loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_transient_can_duck"
        else -> "unknown_$focusChange"
    }
}

/**
 * Combines the user's mute with the focus hold. The microphone sends while neither is set.
 * [effectiveMuted] is what the call's mute path must carry.
 */
internal class CallFocusHold {
    var held = false
        private set
    var userMuted = false
        private set

    val effectiveMuted: Boolean get() = held || userMuted

    /** Applies a focus action; returns the new effective mute when it changed, otherwise null. */
    fun onAction(action: FocusAction): Boolean? {
        val before = effectiveMuted
        when (action) {
            FocusAction.HOLD -> held = true
            FocusAction.RESUME -> held = false
            FocusAction.NONE -> return null
        }
        return effectiveMuted.takeIf { it != before }
    }

    /** The user's own mute/unmute (button or push-to-talk); returns the new effective mute. */
    fun onUserMuted(muted: Boolean): Boolean {
        userMuted = muted
        return effectiveMuted
    }
}

/** Requests and abandons the call's audio focus; [onAction] runs on the main thread. */
internal class CallAudioFocus(
    context: Context,
    private val onAction: (FocusAction) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val action = CallAudioFocusPolicy.actionFor(change)
        log("change", "change=${CallAudioFocusPolicy.label(change)} | action=${action.name}")
        onAction(action)
    }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        .build()
    private var requested = false

    /** Returns true when focus was granted. A refusal (the phone is in a cellular call) holds. */
    fun request(): Boolean {
        val result = runCatching { audioManager?.requestAudioFocus(request) }.getOrNull()
        requested = true
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("request", "result=${result ?: "error"} | granted=$granted")
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) onAction(FocusAction.HOLD)
        return granted
    }

    fun abandon() {
        if (!requested) return
        requested = false
        val result = runCatching { audioManager?.abandonAudioFocusRequest(request) }.getOrNull()
        log("abandon", "result=${result ?: "error"}")
    }

    private fun log(event: String, detail: String) {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_audio_focus: event=$event | $detail")
    }
}
