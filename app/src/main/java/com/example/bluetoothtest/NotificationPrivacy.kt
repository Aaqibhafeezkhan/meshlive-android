package com.example.bluetoothtest

import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Lock-screen notification privacy (research catalog P10b, 2026-10-10).
 *
 * Before: the session notification (`ActiveVideoSessionService`) and the experimental incoming-call
 * alert (`CallLockScreen`) set no visibility and no public version, so a lock screen set to show all
 * notification content displayed the activity ("Encrypted private video call") and the alert displayed
 * the caller's name. Now every call/stream notification is VISIBILITY_PRIVATE with a public version
 * that names only the app, and the caller's name is left out of the notification entirely unless the
 * user opts in ([showPeerNames]: Advanced setting, preference [KEY_PEER_NAMES]; DEBUG
 * `test_lock_screen_peer_names`). Opted in, the alert is VISIBILITY_PUBLIC and carries the name.
 * Message content (store-and-forward, none posted today) would be VISIBILITY_SECRET.
 */
internal enum class NotificationKind { SESSION, INCOMING_CALL, MESSAGE }

internal object NotificationPrivacy {
    const val PREFS = "notification_privacy"
    const val KEY_PEER_NAMES = "lock_screen_peer_names"
    const val PUBLIC_TITLE = "Bluetooth Video"
    const val PUBLIC_SESSION_TEXT = "Active"
    const val PUBLIC_INCOMING_CALL_TEXT = "Incoming call"
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
        NotificationKind.SESSION -> NotificationCompat.VISIBILITY_PRIVATE
        NotificationKind.INCOMING_CALL ->
            if (peerNamesOptIn) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE
        NotificationKind.MESSAGE ->
            if (peerNamesOptIn) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET
    }

    /** The alert's text: the caller's name only when the user opted in. */
    fun incomingCallText(peerLabel: String, peerNamesOptIn: Boolean): String =
        if (peerNamesOptIn) peerLabel else HIDDEN_PEER_TEXT

    fun publicText(kind: NotificationKind): String = when (kind) {
        NotificationKind.SESSION -> PUBLIC_SESSION_TEXT
        NotificationKind.INCOMING_CALL -> PUBLIC_INCOMING_CALL_TEXT
        NotificationKind.MESSAGE -> "New message"
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
