package com.example.bluetoothtest

import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Lock-screen notification privacy (research catalog P10b, 2026-10-10; names rule Q16-A, 2026-10-11).
 *
 * Every call/stream notification is VISIBILITY_PRIVATE with a public version that names only the app,
 * so a secure lock screen shows "Bluetooth Video / Incoming call" and nothing else. The full version,
 * which Android shows only on an unlocked phone, carries the caller's name. The user can allow names on
 * the lock screen too ([showPeerNames]: Settings > Features, preference [KEY_PEER_NAMES]; DEBUG
 * `test_lock_screen_peer_names`): the incoming-call alert is then VISIBILITY_PUBLIC. The call screen
 * shown over the lock screen follows the same rule ([LockScreenNames]).
 * Message notifications never carry message text or sender; they are VISIBILITY_SECRET (nothing on a
 * secure lock screen) unless names are allowed, and then show a generic "New message".
 *
 * Ongoing call and stream-viewer notifications (design decision Q16, 2026-10-14) carry the peer's name in
 * their full version, so an unlocked phone shows who the call or stream is with. A named session
 * notification stays VISIBILITY_PRIVATE with its name-free public version unless the same lock-screen
 * opt-in is on; an unnamed one is always PRIVATE.
 */
internal enum class NotificationKind { SESSION, INCOMING_CALL, MESSAGE, AUDIO_LISTEN, AUDIO_BROADCAST }

internal object NotificationPrivacy {
    const val PREFS = "notification_privacy"
    const val KEY_PEER_NAMES = "lock_screen_peer_names"
    const val PUBLIC_TITLE = "Bluetooth Video"
    const val PUBLIC_SESSION_TEXT = "Active"
    const val PUBLIC_INCOMING_CALL_TEXT = "Incoming call"
    /** Audio-only live (Design AL): neutral lock-screen text, never a name or a title. */
    const val PUBLIC_AUDIO_LISTEN_TEXT = "Listening to a live broadcast"
    const val PUBLIC_AUDIO_BROADCAST_TEXT = "Broadcasting audio"
    const val HIDDEN_PEER_TEXT = "Open the app to see who is calling."

    /** DEBUG override (`test_lock_screen_peer_names`); null follows the user's preference. */
    @Volatile var debugPeerNames: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    fun showPeerNames(context: Context): Boolean = debugPeerNames
        ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PEER_NAMES, false)

    fun setShowPeerNames(context: Context, show: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PEER_NAMES, show).apply()
    }

    fun visibility(kind: NotificationKind, peerNamesOptIn: Boolean): Int = when (kind) {
        NotificationKind.AUDIO_LISTEN, NotificationKind.AUDIO_BROADCAST -> NotificationCompat.VISIBILITY_PRIVATE
        // [peerNamesOptIn] is true only when the notification carries a name and the user allowed names there.
        NotificationKind.SESSION ->
            if (peerNamesOptIn) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE
        NotificationKind.INCOMING_CALL ->
            if (peerNamesOptIn) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE
        NotificationKind.MESSAGE ->
            if (peerNamesOptIn) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET
    }

    /**
     * The alert's own text, shown only to an unlocked phone (or, with the opt-in, also on the lock
     * screen): the caller's name, else a prompt to open the app.
     */
    fun incomingCallText(peerLabel: String?): String =
        peerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: HIDDEN_PEER_TEXT

    /** Longest peer name a notification carries; longer names are cut so a hostile label cannot fill the shade. */
    const val MAX_PEER_NAME_CHARS = 40

    /**
     * The peer's name for an ongoing call or stream notification: trimmed, control characters removed,
     * cut to [MAX_PEER_NAME_CHARS], or null when there is nothing to show.
     */
    fun sessionPeerName(raw: String?): String? =
        raw?.filterNot { it.isISOControl() }?.trim()?.take(MAX_PEER_NAME_CHARS)?.trim()?.takeIf { it.isNotEmpty() }

    /** The CallStyle person of an ongoing call: the peer's name, else the status text, else a neutral label. */
    fun ongoingPersonName(statusText: String?, peerName: String?): String =
        sessionPeerName(peerName) ?: statusText?.trim()?.takeIf { it.isNotEmpty() } ?: CallNotificationActions.NEUTRAL_PERSON

    /**
     * The full (unlocked) notification's text. A CallStyle call already shows the name as its title, so its
     * text stays the status; any other session notification puts the name in front of the status.
     */
    fun sessionContentText(baseText: String, peerName: String?, ongoingCallStyle: Boolean): String {
        val name = sessionPeerName(peerName)
        return if (name == null || ongoingCallStyle) baseText else "$name \u00B7 $baseText"
    }

    fun publicText(kind: NotificationKind): String = when (kind) {
        NotificationKind.SESSION -> PUBLIC_SESSION_TEXT
        NotificationKind.INCOMING_CALL -> PUBLIC_INCOMING_CALL_TEXT
        NotificationKind.MESSAGE -> "New message"
        NotificationKind.AUDIO_LISTEN -> PUBLIC_AUDIO_LISTEN_TEXT
        NotificationKind.AUDIO_BROADCAST -> PUBLIC_AUDIO_BROADCAST_TEXT
    }

    /** Applies visibility and a name-free public version to [builder]. */
    fun apply(
        builder: NotificationCompat.Builder,
        context: Context,
        channelId: String,
        kind: NotificationKind,
        smallIcon: Int,
        peerNamesOptIn: Boolean,
    ): NotificationCompat.Builder {
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(PUBLIC_TITLE)
            .setContentText(publicText(kind))
            .build()
        return builder.setVisibility(visibility(kind, peerNamesOptIn)).setPublicVersion(publicVersion)
    }
}

/** What the call screen may show while a secure lock screen is up (Q16-A). */
internal object LockScreenNames {
    /** True when the caller's name must be replaced by a neutral label until the phone is unlocked. */
    fun masked(keyguardLocked: Boolean, keyguardSecure: Boolean, namesAllowed: Boolean): Boolean =
        keyguardLocked && keyguardSecure && !namesAllowed
}
