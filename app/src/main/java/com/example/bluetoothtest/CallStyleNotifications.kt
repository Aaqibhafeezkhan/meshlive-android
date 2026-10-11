package com.example.bluetoothtest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.security.MessageDigest
import java.security.SecureRandom

/** What a notification action asks the call to do in its current phase. */
internal enum class CallNotificationCommand { ACCEPT, DECLINE, CANCEL, HANG_UP, IGNORE }

/**
 * CallStyle call notifications (Settings > Features "Answer and decline from notifications", promoted
 * 2026-10-11, shipped default [CallFeatureDefaults.CALL_NOTIFICATIONS]; DEBUG
 * `test_call_style_notification`, benchmark tunable `call_style_notification`).
 *
 * Before: an incoming call posted at most the experimental CallLockScreen alert (a plain
 * CATEGORY_CALL notification without buttons), and the ongoing call had only the low-importance
 * foreground-service notice. On: the incoming alert is NotificationCompat.CallStyle.forIncomingCall
 * with Decline and Answer, and the call's foreground-service notification (outgoing and active) is
 * CallStyle.forOngoingCall with Hang up; Android 12+ ranks and styles them as calls
 * (developer.android.com/reference/androidx/core/app/NotificationCompat.CallStyle). Answer opens
 * the call screen with an answer extra, so the camera/microphone service still starts while the
 * activity is visible (Android 11+ while-in-use rules). Decline and Hang up are broadcasts to a
 * receiver the activity registers as not exported, so they work without bringing the app forward.
 *
 * MainActivity is exported, so every action carries a per-process random token; an intent from
 * another app without it cannot answer (open the microphone) or end a call.
 *
 * Privacy (NotificationPrivacy, Q16-A): CallStyle needs a Person with a name. The incoming alert's full
 * version uses the caller's name (shown on an unlocked phone only); its public version is name-free.
 * The ongoing notification never names the peer.
 */
internal object CallNotificationActions {
    const val EXTRA_ACTION = "com.example.bluetoothtest.extra.CALL_ACTION"
    const val EXTRA_TOKEN = "com.example.bluetoothtest.extra.CALL_ACTION_TOKEN"
    const val BROADCAST_ACTION = "com.example.bluetoothtest.action.CALL_NOTIFICATION"
    const val ANSWER = "answer"
    const val DECLINE = "decline"
    const val HANG_UP = "hang_up"
    const val NEUTRAL_PERSON = "Encrypted call"

    private val actions = setOf(ANSWER, DECLINE, HANG_UP)

    /** Random per process; never persisted or logged. */
    val token: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /** True only for one of our actions carrying this process's token. */
    fun isAuthentic(action: String?, token: String?, expected: String = this.token): Boolean =
        action in actions && token != null &&
            MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))

    fun commandFor(action: String?, phase: PrivateCallPhase?): CallNotificationCommand = when {
        action == ANSWER && phase == PrivateCallPhase.INCOMING_RINGING -> CallNotificationCommand.ACCEPT
        action == DECLINE && phase == PrivateCallPhase.INCOMING_RINGING -> CallNotificationCommand.DECLINE
        action == HANG_UP && (phase == PrivateCallPhase.OUTGOING_INVITE || phase == PrivateCallPhase.OUTGOING_RINGING) ->
            CallNotificationCommand.CANCEL
        action == HANG_UP && (phase == PrivateCallPhase.NEGOTIATING_MEDIA || phase == PrivateCallPhase.ACTIVE) ->
            CallNotificationCommand.HANG_UP
        else -> CallNotificationCommand.IGNORE
    }

    /**
     * The CallStyle person's name. CallStyle needs one; it is part of the full notification, which
     * Android shows only on an unlocked phone (a secure lock screen shows the name-free public
     * version, unless the user allowed names there).
     */
    fun personName(peerLabel: String?): String =
        peerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: NEUTRAL_PERSON

    fun answerIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_ANSWER,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ACTION, ANSWER)
            .putExtra(EXTRA_TOKEN, token),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun broadcastIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == DECLINE) REQUEST_DECLINE else REQUEST_HANG_UP,
        Intent(BROADCAST_ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_TOKEN, token),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun incomingStyle(context: Context, personName: String): NotificationCompat.CallStyle =
        NotificationCompat.CallStyle.forIncomingCall(
            Person.Builder().setName(personName).setImportant(true).build(),
            broadcastIntent(context, DECLINE),
            answerIntent(context),
        )

    fun ongoingStyle(context: Context, personName: String): NotificationCompat.CallStyle =
        NotificationCompat.CallStyle.forOngoingCall(
            Person.Builder().setName(personName).setImportant(true).build(),
            broadcastIntent(context, HANG_UP),
        )

    private const val REQUEST_ANSWER = 11
    private const val REQUEST_DECLINE = 12
    private const val REQUEST_HANG_UP = 13
}
