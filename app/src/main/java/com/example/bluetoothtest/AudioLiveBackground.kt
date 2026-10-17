package com.example.bluetoothtest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Background listening and broadcasting of an audio-only live stream (Design AL, slice 4): the notification
 * actions, the framework media session and the reminder rule. The foreground service itself is
 * [ActiveVideoSessionService] (types `connectedDevice | mediaPlayback` for a listener, `connectedDevice |
 * microphone` for a broadcaster).
 */

/** What the session service shows and which foreground-service type it asks for. */
enum class AudioLiveServiceMode {
    NONE,
    /** Broadcasting audio: microphone type, notification "Broadcasting audio" with Mute and Stop. */
    BROADCAST,
    /** Listening: media-playback type, media-style notification, neutral lock-screen text. */
    LISTEN;

    companion object {
        fun from(value: String?): AudioLiveServiceMode = entries.firstOrNull { it.name == value } ?: NONE
    }
}

/** Notification actions of the audio-only sessions; authenticated like the call actions (the package-private token). */
internal object AudioLiveNotification {
    const val ACTION_MUTE = "com.example.bluetoothtest.action.AUDIO_LIVE_MUTE"
    const val ACTION_STOP = "com.example.bluetoothtest.action.AUDIO_LIVE_STOP"
    const val EXTRA_TOKEN = "com.example.bluetoothtest.extra.AUDIO_LIVE_TOKEN"
    private const val REQUEST_MUTE = 31
    private const val REQUEST_STOP = 32
    const val REMINDER_ID = 1004
    const val REMINDER_CHANNEL = "audio_live_reminder"

    fun muteIntent(context: Context): PendingIntent = broadcast(context, REQUEST_MUTE, ACTION_MUTE)

    fun stopIntent(context: Context): PendingIntent = broadcast(context, REQUEST_STOP, ACTION_STOP)

    private fun broadcast(context: Context, request: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        request,
        Intent(action).setPackage(context.packageName).putExtra(EXTRA_TOKEN, CallNotificationActions.token),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun isAuthentic(token: String?): Boolean = token != null && java.security.MessageDigest.isEqual(
        token.toByteArray(Charsets.US_ASCII),
        CallNotificationActions.token.toByteArray(Charsets.US_ASCII),
    )
}

/**
 * The framework media session of a listening session (no new library). It carries no title or metadata:
 * lock-screen media controls, the quick-settings carousel, car and watch displays ignore a notification's
 * public version, so anything set here could show a name on a locked phone. The headset play/pause button
 * toggles the listener's own mute. Deactivated and released when listening ends.
 */
internal object AudioLiveMediaSession {
    @Volatile private var session: MediaSession? = null

    /** The token for the notification's media style, or null when no session is open. */
    val token: MediaSession.Token? get() = session?.sessionToken

    @Synchronized
    fun open(context: Context, onToggleMute: () -> Unit) {
        if (session != null) return
        val created = MediaSession(context.applicationContext, "MeshLiveRadio")
        created.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in TOGGLE_KEYS) {
                    onToggleMute()
                }
                return true
            }

            override fun onPlay() = onToggleMute()

            override fun onPause() = onToggleMute()
        })
        created.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        created.isActive = true
        session = created
    }

    @Synchronized
    fun close() {
        val open = session ?: return
        session = null
        runCatching { open.isActive = false }
        runCatching { open.release() }
    }

    private val TOGGLE_KEYS = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    )
}

/**
 * "Still broadcasting" (Design AL, AL-8): a reminder after [NO_LISTENER_MS] without a listener or [TOTAL_MS]
 * on the air, and the broadcast stops [UNANSWERED_STOP_MS] after a reminder nobody answered. The user answers
 * by opening the app. A broadcast is never silently left running on an empty channel.
 */
class AudioLiveReminderPolicy(startMs: Long) {
    enum class Action { NONE, REMIND, STOP }

    private var lastAnsweredMs = startMs
    private var emptySinceMs: Long? = startMs
    private var remindedAtMs: Long? = null

    @Synchronized
    fun onListeners(count: Int, nowMs: Long) {
        if (count > 0) {
            emptySinceMs = null
        } else if (emptySinceMs == null) {
            emptySinceMs = nowMs
        }
    }

    /** The user opened the app (or answered the reminder): the clocks start again. */
    @Synchronized
    fun onUserPresent(nowMs: Long) {
        lastAnsweredMs = nowMs
        remindedAtMs = null
        if (emptySinceMs != null) emptySinceMs = nowMs
    }

    @Synchronized
    fun decide(nowMs: Long): Action {
        val reminded = remindedAtMs
        if (reminded != null) return if (nowMs - reminded >= UNANSWERED_STOP_MS) Action.STOP else Action.NONE
        val empty = emptySinceMs
        val due = nowMs - lastAnsweredMs >= TOTAL_MS || (empty != null && nowMs - empty >= NO_LISTENER_MS)
        if (!due) return Action.NONE
        remindedAtMs = nowMs
        return Action.REMIND
    }

    companion object {
        const val NO_LISTENER_MS = 30L * 60_000
        const val TOTAL_MS = 2L * 60 * 60_000
        const val UNANSWERED_STOP_MS = 5L * 60_000
    }
}
