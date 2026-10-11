package com.example.bluetoothtest

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Promoted call and message features (2026-10-11, user request to bring the experimental features
 * into the normal app). Each one has exactly one default below and one user switch in Settings >
 * Features; neither depends on the "Manual settings" toggle or on BuildConfig.DEBUG.
 *
 * Shipping a feature ON is a one-line change here, made only after the phone test named beside it
 * has passed (README register). A user who has touched a switch keeps that explicit choice; an
 * untouched switch follows the constant, so flipping it reaches everyone who never decided.
 *
 * DEBUG benchmark launches (any `test_` extra) are unchanged: they read only their own extras,
 * default off, so case IDs and measured behaviour stay valid. The old `test_*` extras are the
 * off/on switches of those runs.
 */
internal object CallFeatureDefaults {
    /** The app's settings preferences file (MainActivity.APP_PREFERENCES); the switches' explicit choices live in it. */
    const val APP_SETTINGS_PREFERENCES = "bluetooth_video_settings"

    /** Voice calls with per-direction video upgrade (phone test T1). */
    const val VOICE_CALLS = false

    /** Ringing tone for the caller while the callee rings (T1). */
    const val RINGBACK = false

    /** Pause the outgoing camera while the phone is held at the ear (T1). */
    const val EAR_CAMERA_PAUSE = false

    /** The call screen over the lock screen, and the screen turning on for an incoming call (T2). */
    const val LOCK_SCREEN_CALLS = false

    /** Answer / Decline / Hang up from call notifications (CallStyle) (T2). */
    const val CALL_NOTIFICATIONS = false

    /** Store-and-forward Messages, direct neighbours only (T3). */
    const val MESSAGES = false

    /** Start a message exchange by itself when a contact comes into range (T4). Needs Messages. */
    const val AUTO_SYNC = false

    /** Optional names, call subjects and stream titles (T5). */
    const val NAMES_AND_TITLES = false
}

internal enum class CallFeature(
    /** SharedPreferences key of the user's explicit choice (absent = [defaultOn]). */
    val prefKey: String,
    /** DEBUG launch extra of benchmark runs (default off). */
    val benchmarkExtra: String,
    val defaultOn: Boolean,
) {
    VOICE_CALLS(IntercomFeatureSettings.PREF_VOICE_CALLS, IntercomFeatureSettings.EXTRA_VOICE_CALLS, CallFeatureDefaults.VOICE_CALLS),
    RINGBACK("feature_call_ringback", CallUxSettings.EXTRA_RINGBACK, CallFeatureDefaults.RINGBACK),
    EAR_CAMERA_PAUSE(
        "feature_call_ear_camera_pause", CallUxSettings.EXTRA_PROXIMITY_CAMERA_PAUSE, CallFeatureDefaults.EAR_CAMERA_PAUSE,
    ),
    LOCK_SCREEN_CALLS("feature_call_lock_screen", CallUxSettings.EXTRA_LOCK_SCREEN, CallFeatureDefaults.LOCK_SCREEN_CALLS),
    CALL_NOTIFICATIONS("feature_call_notifications", CallUxSettings.EXTRA_CALL_STYLE, CallFeatureDefaults.CALL_NOTIFICATIONS),
    MESSAGES(StoreForwardFeature.PREF, StoreForwardFeature.EXTRA, CallFeatureDefaults.MESSAGES),
    AUTO_SYNC(StoreForwardAutoSyncFeature.PREF, StoreForwardAutoSyncFeature.EXTRA, CallFeatureDefaults.AUTO_SYNC),
    NAMES_AND_TITLES(PeerLabelFeature.PREF, PeerLabelFeature.EXTRA, CallFeatureDefaults.NAMES_AND_TITLES),
}

/** The effective value of every promoted feature for this launch. */
internal data class CallFeatureSet(
    val voiceCalls: Boolean = false,
    val ringback: Boolean = false,
    val earCameraPause: Boolean = false,
    val lockScreenCalls: Boolean = false,
    val callNotifications: Boolean = false,
    val messages: Boolean = false,
    val autoSync: Boolean = false,
    val namesAndTitles: Boolean = false,
)

internal object CallFeatureResolver {
    /**
     * One feature. A benchmark launch reads only [CallFeature.benchmarkExtra] (default off, so a stored
     * choice never leaks into a measured case). Any other launch uses the user's explicit choice when
     * [stored] has one, otherwise the feature's default.
     */
    fun enabled(
        feature: CallFeature,
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): Boolean = if (benchmarkLaunch) extra(feature.benchmarkExtra) else stored(feature.prefKey) ?: feature.defaultOn

    fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): CallFeatureSet {
        fun on(feature: CallFeature) = enabled(feature, benchmarkLaunch, extra, stored)
        val messages = on(CallFeature.MESSAGES)
        return CallFeatureSet(
            voiceCalls = on(CallFeature.VOICE_CALLS),
            ringback = on(CallFeature.RINGBACK),
            earCameraPause = on(CallFeature.EAR_CAMERA_PAUSE),
            lockScreenCalls = on(CallFeature.LOCK_SCREEN_CALLS),
            callNotifications = on(CallFeature.CALL_NOTIFICATIONS),
            messages = messages,
            // Automatic sync is a sub-option of Messages: never on without it.
            autoSync = messages && on(CallFeature.AUTO_SYNC),
            namesAndTitles = on(CallFeature.NAMES_AND_TITLES),
        )
    }

    /** Reads an explicit choice: null when the user never touched the switch. */
    fun storedChoice(prefs: SharedPreferences, key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null
}

/**
 * Android 14+ full-screen call alerts need the "full screen notifications" special access
 * (NotificationManager.canUseFullScreenIntent, USE_FULL_SCREEN_INTENT). Without it the incoming-call
 * notification only heads-up pops and the call screen does not appear over the lock screen.
 */
internal object FullScreenIntentAccess {
    /** True when Settings should offer the access link: the feature is on and Android has it off. */
    fun needsPrompt(sdkInt: Int, featureOn: Boolean, canUse: Boolean): Boolean =
        featureOn && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canUse

    fun canUse(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
    }

    /** Opens the system page for this app's access, else the app's notification settings. */
    fun openSettings(context: Context) {
        val direct = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
}

/** What the Settings > Features calls block needs from the activity. */
internal interface CallFeatureHost {
    /** True in a DEBUG benchmark launch: the switches then show the extras' values and cannot be changed. */
    val benchmarkLaunch: Boolean

    /** A switch changed: re-resolve the features and refresh dependent screens. */
    fun onCallFeaturesChanged()

    /** Android 13+: ask for POST_NOTIFICATIONS, because call alerts are notifications. */
    fun requestNotificationPermission()
}

/**
 * Binds `settings_features_calls.xml` (the include in the Settings screen) to the preferences. The
 * switches write explicit choices; [refresh] re-reads them (also after the Android settings page for
 * full-screen alerts returns).
 */
internal class CallFeatureSettingsBinder(
    private val root: View,
    private val prefs: SharedPreferences,
    private val host: CallFeatureHost,
    private val current: () -> CallFeatureSet,
) {
    private class Row(val feature: CallFeature?, val switchId: Int, val get: (CallFeatureSet) -> Boolean)

    private val rows = listOf(
        Row(CallFeature.VOICE_CALLS, R.id.featureVoiceCallsSwitch) { it.voiceCalls },
        Row(CallFeature.RINGBACK, R.id.featureRingbackSwitch) { it.ringback },
        Row(CallFeature.EAR_CAMERA_PAUSE, R.id.featureEarCameraPauseSwitch) { it.earCameraPause },
        Row(CallFeature.LOCK_SCREEN_CALLS, R.id.featureLockScreenCallsSwitch) { it.lockScreenCalls },
        Row(CallFeature.CALL_NOTIFICATIONS, R.id.featureCallNotificationsSwitch) { it.callNotifications },
        Row(CallFeature.MESSAGES, R.id.featureMessagesSwitch) { it.messages },
        Row(CallFeature.AUTO_SYNC, R.id.featureAutoSyncSwitch) { it.autoSync },
        Row(CallFeature.NAMES_AND_TITLES, R.id.featureNamesSwitch) { it.namesAndTitles },
        Row(null, R.id.featureLockScreenNamesSwitch) { false },
    )
    private var binding = false

    fun bind() {
        val context = root.context
        rows.forEach { row ->
            val switch = root.findViewById<MaterialSwitch>(row.switchId)
            switch.setOnCheckedChangeListener { _, enabled ->
                if (binding || host.benchmarkLaunch) return@setOnCheckedChangeListener
                if (row.feature == null) {
                    NotificationPrivacy.setShowPeerNames(context, enabled)
                } else {
                    prefs.edit().putBoolean(row.feature.prefKey, enabled).apply()
                    if (enabled && row.feature.needsNotifications) host.requestNotificationPermission()
                }
                host.onCallFeaturesChanged()
                refresh()
            }
        }
        root.findViewById<View>(R.id.featureFullScreenButton).setOnClickListener {
            runCatching { FullScreenIntentAccess.openSettings(context) }
        }
        refresh()
    }

    fun refresh() {
        val context = root.context
        val now = current()
        binding = true
        try {
            rows.forEach { row ->
                val switch = root.findViewById<MaterialSwitch>(row.switchId)
                switch.isChecked = if (row.feature == null) NotificationPrivacy.showPeerNames(context) else row.get(now)
                switch.isEnabled = !host.benchmarkLaunch
            }
            // Automatic sync is a sub-option of Messages.
            root.findViewById<MaterialSwitch>(R.id.featureAutoSyncSwitch).isEnabled = !host.benchmarkLaunch && now.messages
            val needsPrompt = FullScreenIntentAccess.needsPrompt(
                Build.VERSION.SDK_INT,
                featureOn = now.lockScreenCalls,
                canUse = FullScreenIntentAccess.canUse(context),
            )
            val visible = if (needsPrompt) View.VISIBLE else View.GONE
            root.findViewById<View>(R.id.featureFullScreenButton).visibility = visible
            root.findViewById<TextView>(R.id.featureFullScreenText).visibility = visible
            root.findViewById<View>(R.id.featureBenchmarkNote).visibility =
                if (host.benchmarkLaunch) View.VISIBLE else View.GONE
        } finally {
            binding = false
        }
    }

    private val CallFeature.needsNotifications: Boolean
        get() = this == CallFeature.LOCK_SCREEN_CALLS || this == CallFeature.CALL_NOTIFICATIONS ||
            this == CallFeature.MESSAGES
}
