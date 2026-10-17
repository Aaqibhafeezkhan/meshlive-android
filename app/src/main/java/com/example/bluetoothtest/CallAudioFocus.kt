package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
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
 *
 * Refusal handling (2026-10-14, batch U, registry entry [CALL_FOCUS_ENTRY_ID]): a refused request used to be
 * taken for a cellular call and held the call. A failed request never produces a later callback, so a wrong
 * hold lasted for the whole call. Since Android 15 (targetSdk 35+) the platform also refuses focus to an app
 * that is neither the top app nor holding a foreground service
 * (developer.android.com/about/versions/15/behavior-changes-15#audio-focus), which is not a call. The refusal is
 * now classified by [CallFocusRefusalPolicy]: only a call audio mode or a non-idle telephony state holds; any
 * other refusal is logged as `focus=failed_not_foreground`, does not hold, and is retried once when the call's
 * foreground service holds ([CallAudioFocus.onForegroundHeld]) or after [CallFocusArbiter.RETRY_DELAY_MS].
 * A delayed request ([AudioManager.AUDIOFOCUS_REQUEST_DELAYED]) is accepted and waits for the gain callback.
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
    private val context: Context,
    private val onAction: (FocusAction) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val action = CallAudioFocusPolicy.actionFor(change)
        log("change", "change=${CallAudioFocusPolicy.label(change)} | action=${action.name}")
        onAction(action)
    }
    private val classify = AudioSwitches.policyActive(CALL_FOCUS_ENTRY_ID)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Delayed gain: while a call locks focus the request waits and the gain callback releases the hold.
        // Off with the registry entry (the former FAILED-and-hold behaviour).
        .setAcceptsDelayedFocusGain(classify)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener, handler)
        .build()
    private var requested = false

    private val api = object : CallFocusApi {
        override fun requestFocus(): Int? = runCatching { audioManager?.requestAudioFocus(request) }.getOrNull()

        override fun audioMode(): Int? = runCatching { audioManager?.mode }.getOrNull()

        @Suppress("DEPRECATION")
        override fun telephonyCallState(): Int? = runCatching {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                null
            } else {
                context.getSystemService(TelephonyManager::class.java)?.callState
            }
        }.getOrNull()

        override fun foregroundServiceHeld(): Boolean = ActiveVideoSessionService.heldTypes != 0

        override fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit {
            val task = Runnable(block)
            handler.postDelayed(task, delayMs)
            return { handler.removeCallbacks(task) }
        }

        override fun log(event: String, detail: String) = this@CallAudioFocus.log(event, detail)
    }
    private val arbiter = CallFocusArbiter(api, classify, onAction)

    /**
     * Returns true when focus was granted. A refusal holds only when it is a cellular call; any other refusal is
     * retried once, and [onForegroundHeld] retries it as soon as the call's foreground service holds.
     */
    fun request(): Boolean {
        requested = true
        return arbiter.request()
    }

    /** Root hook: call when [ActiveVideoSessionService.heldTypes] becomes non-zero while the call is open. */
    fun onForegroundHeld() = arbiter.onForegroundHeld()

    fun abandon() {
        arbiter.cancel()
        if (!requested) return
        requested = false
        val result = runCatching { audioManager?.abandonAudioFocusRequest(request) }.getOrNull()
        log("abandon", "result=${result ?: "error"}")
    }

    private fun log(event: String, detail: String) {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_audio_focus: event=$event | $detail")
    }
}
