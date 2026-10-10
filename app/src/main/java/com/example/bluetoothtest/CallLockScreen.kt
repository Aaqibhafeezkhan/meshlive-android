package com.example.bluetoothtest

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Experimental lock-screen call presentation (default off; DEBUG `test_call_lock_screen`, benchmark
 * tunable `call_lock_screen`).
 *
 * Before: an incoming call while the phone was locked or the app was in the background only rang;
 * its only notification was the low-importance foreground-service notice ("Incoming encrypted call.
 * Tap to answer.", IMPORTANCE_LOW, CATEGORY_SERVICE), so the caller's name and the answer screen
 * appeared only after unlocking. No call run measured lock or background behaviour.
 *
 * On: while a call rings or runs, the call screen may show over the keyguard
 * (`Activity.setShowWhenLocked`, API 27: the activity "will remain in the resumed state visible
 * on-top of the lock screen"), and an incoming call turns the screen on (`setTurnScreenOn`). An
 * incoming call also posts a CATEGORY_CALL notification on an IMPORTANCE_HIGH channel with a
 * full-screen intent back to this activity, which Android documents for incoming calls on a locked
 * device (developer.android.com/develop/connectivity/telecom/voip-app/notifications; Android 10+
 * blocks background activity starts, and a full-screen intent needs an IMPORTANCE_HIGH channel).
 * Without the full-screen permission (Android 14+ special access) or POST_NOTIFICATIONS (13+)
 * Android shows a heads-up notification or nothing; the call itself is unchanged.
 * Answering still happens on the call screen, after which the camera/microphone service starts
 * while the activity is visible, as Android 11+ requires.
 */
internal object CallLockScreenPolicy {
    private val showPhases = setOf(
        PrivateCallPhase.OUTGOING_INVITE,
        PrivateCallPhase.OUTGOING_RINGING,
        PrivateCallPhase.INCOMING_RINGING,
        PrivateCallPhase.NEGOTIATING_MEDIA,
        PrivateCallPhase.ACTIVE,
    )

    /** The call screen may show over the keyguard in these phases. */
    fun showOverLockScreen(enabled: Boolean, phase: PrivateCallPhase?): Boolean = enabled && phase in showPhases

    /** Only a ringing incoming call turns the screen on. */
    fun turnScreenOn(enabled: Boolean, phase: PrivateCallPhase?): Boolean =
        enabled && phase == PrivateCallPhase.INCOMING_RINGING
}

internal class CallLockScreen(private val activity: Activity) {
    private val enabled = DebugTunables.callLockScreen
    private val callStyle = CallUx.settings.callStyleNotifications
    private var appliedShow = false
    private var appliedTurnOn = false
    private var alertPosted = false

    /** Applies the window flags for [phase] (null: no call). No effect while the experiment is off. */
    fun apply(phase: PrivateCallPhase?) {
        if (!enabled) return
        val show = CallLockScreenPolicy.showOverLockScreen(enabled, phase)
        val turnOn = CallLockScreenPolicy.turnScreenOn(enabled, phase)
        if (show != appliedShow) {
            activity.setShowWhenLocked(show)
            appliedShow = show
        }
        if (turnOn != appliedTurnOn) {
            activity.setTurnScreenOn(turnOn)
            appliedTurnOn = turnOn
        }
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_lock_screen: phase=${phase?.name ?: "none"} | showWhenLocked=$show | turnScreenOn=$turnOn",
        )
    }

    /**
     * Posts the incoming-call alert (heads-up, or full screen while locked when Android allows it).
     * With the CallStyle experiment it carries Decline/Answer (CallNotificationActions); showing the
     * call screen itself over the keyguard still needs this class's own switch.
     */
    fun showIncomingAlert(peerLabel: String) {
        if (!enabled && !callStyle) return
        val manager = activity.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming encrypted Bluetooth calls"
                setSound(null, null) // The app already plays the ringtone and vibration.
            },
        )
        val intent = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            activity,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fullScreenAllowed = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        // The caller's name stays out of the notification unless the user opted in (NotificationPrivacy).
        val peerNames = NotificationPrivacy.showPeerNames(activity)
        val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle("Incoming encrypted call")
            .setContentText(NotificationPrivacy.incomingCallText(peerLabel, peerNames))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .let { builder ->
                if (!callStyle) return@let builder
                builder.setStyle(
                    CallNotificationActions.incomingStyle(activity, CallNotificationActions.personName(peerLabel, peerNames)),
                )
            }
            .let { builder ->
                NotificationPrivacy.apply(
                    builder,
                    activity,
                    CHANNEL_ID,
                    NotificationKind.INCOMING_CALL,
                    R.drawable.ic_stat_meshlive,
                    peerNames,
                )
            }
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onSuccess { alertPosted = true }
            .onFailure { Log.w("BluetoothVideo", "Incoming call alert could not be posted", it) }
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_lock_screen: alert=posted | callStyle=$callStyle | fullScreenAllowed=$fullScreenAllowed | " +
                "notificationsEnabled=${manager.areNotificationsEnabled()}",
        )
    }

    fun cancelIncomingAlert() {
        if (!alertPosted) return
        alertPosted = false
        activity.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "incoming_private_calls"
        const val NOTIFICATION_ID = 1002
        const val REQUEST_CODE = 2
    }
}

/** POST_NOTIFICATIONS is needed for the alert on Android 13+; asked only while the experiment is on. */
internal fun callLockScreenNeedsNotificationPermission(enabled: Boolean, sdkInt: Int, granted: Boolean): Boolean =
    enabled && sdkInt >= 33 && !granted
