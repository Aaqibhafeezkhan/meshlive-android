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

    /**
     * The alert's own text, shown only to an unlocked phone (or, with the opt-in, also on the lock
     * screen): the caller's name, else a prompt to open the app.
     */
    fun incomingCallText(peerLabel: String?): String =
        peerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: HIDDEN_PEER_TEXT

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

/** What the call screen may show while a secure lock screen is up (Q16-A). */
internal object LockScreenNames {
    /** True when the caller's name must be replaced by a neutral label until the phone is unlocked. */
    fun masked(keyguardLocked: Boolean, keyguardSecure: Boolean, namesAllowed: Boolean): Boolean =
        keyguardLocked && keyguardSecure && !namesAllowed
}
