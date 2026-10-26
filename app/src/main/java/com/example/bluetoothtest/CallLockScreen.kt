package com.example.bluetoothtest

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Call presentation over the lock screen (Settings > Features "Calls on the lock screen", promoted
 * 2026-10-11, shipped default [CallFeatureDefaults.LOCK_SCREEN_CALLS]; DEBUG `test_call_lock_screen`,
 * benchmark tunable `call_lock_screen`).
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
 * Without the full-screen permission (Android 14+ special access, which Settings > Features links
 * to) or POST_NOTIFICATIONS (13+) Android shows a heads-up notification or nothing; the call itself
 * is unchanged. Answering still happens on the call screen, after which the camera/microphone
 * service starts while the activity is visible, as Android 11+ requires.
 *
 * Names (Q16-A): a secure lock screen shows neither in the notification (public version) nor on the
 * call screen ([displayName], [LockScreenNames]) who is calling, until the phone is unlocked, unless
 * the user allowed names there.
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
    // Read at each use: the Settings switches apply to the next call without recreating the activity.
    private val enabled: Boolean get() = CallUx.settings.lockScreen
    private val callStyle: Boolean get() = CallUx.settings.callStyleNotifications
    private var appliedShow = false
    private var appliedTurnOn = false
    private var alertPosted = false
    private var unlockReceiver: BroadcastReceiver? = null

    /** Applies the window flags for [phase] (null: no call). No effect while the feature is off. */
    fun apply(phase: PrivateCallPhase?) {
        if (phase == null) stopWatchingUnlock()
        if (!enabled && !appliedShow && !appliedTurnOn) return
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

    /** True while a secure lock screen is up and the user has not allowed names on it. */
    fun namesMasked(): Boolean {
        if (!enabled) return false
        val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return false
        return LockScreenNames.masked(
            keyguardLocked = keyguard.isKeyguardLocked,
            keyguardSecure = keyguard.isKeyguardSecure,
            namesAllowed = NotificationPrivacy.showPeerNames(activity),
        )
    }

    /** The name the call screen may show now: [real], or a neutral label while [namesMasked]. */
    fun displayName(real: String): String =
        if (namesMasked()) activity.getString(R.string.call_name_hidden_locked) else real

    /**
     * Calls [onChanged] whenever the lock state may have changed (unlock: ACTION_USER_PRESENT; screen
     * on or off, after which the keyguard may be up again), so the call screen can show the real name
     * after an unlock and hide it again after a relock. Replaces an earlier watch; cancelled when the
     * call ends ([apply] with null).
     */
    fun watchLockState(onChanged: () -> Unit) {
        stopWatchingUnlock()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onChanged()
            }
        }
        unlockReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** The activity is going away: drop the lock-state receiver so it cannot leak. */
    fun release() = stopWatchingUnlock()

    private fun stopWatchingUnlock() {
        val receiver = unlockReceiver ?: return
        unlockReceiver = null
        runCatching { activity.unregisterReceiver(receiver) }
    }

    /**
     * Posts the incoming-call alert (heads-up, or full screen while locked when Android allows it).
     * With the CallStyle feature it carries Decline/Answer (CallNotificationActions); showing the
     * call screen itself over the keyguard needs this class's own switch. The alert's own text names
     * the caller, which Android shows to an unlocked phone only; its public version never does.
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
        val fullScreenAllowed = FullScreenIntentAccess.canUse(activity)
        val lockScreenNames = NotificationPrivacy.showPeerNames(activity)
        val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle("Incoming encrypted call")
            .setContentText(NotificationPrivacy.incomingCallText(peerLabel))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .let { builder ->
                if (!callStyle) return@let builder
                builder.setStyle(CallNotificationActions.incomingStyle(activity, CallNotificationActions.personName(peerLabel)))
            }
            .let { builder ->
                NotificationPrivacy.apply(
                    builder,
                    activity,
                    CHANNEL_ID,
                    NotificationKind.INCOMING_CALL,
                    R.drawable.ic_stat_meshlive,
                    lockScreenNames,
                )
            }
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                alertPosted = true
                // Is the call screen going to come up (full-screen intent)? Only meaningful when the app is not already in front.
                val inFront = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
                if (!inFront) IncomingUiWatch.shared.onFullScreenIntentPosted()
            }
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
        IncomingUiWatch.shared.cancel() // answered, declined or missed: no verdict about the screen
        activity.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "incoming_private_calls"
        const val NOTIFICATION_ID = 1002
        const val REQUEST_CODE = 2
    }
}

/** POST_NOTIFICATIONS is needed for the alert on Android 13+; asked when a call alert feature is on. */
internal fun callLockScreenNeedsNotificationPermission(enabled: Boolean, sdkInt: Int, granted: Boolean): Boolean =
    enabled && sdkInt >= 33 && !granted

/**
 * The same check from the effective call settings: the lock-screen call presentation or CallStyle notifications. Reads
 * [CallUxSettings.lockScreen], the real setting (switch audit 2026-10-27, A7: the caller read the DEBUG-only mirror
 * `DebugTunables.callLockScreen`, always false in release, so release asked only because of call notifications).
 */
internal fun callAlertNeedsNotificationPermission(settings: CallUxSettings, sdkInt: Int, granted: Boolean): Boolean =
    callLockScreenNeedsNotificationPermission(settings.lockScreen || settings.callStyleNotifications, sdkInt, granted)
