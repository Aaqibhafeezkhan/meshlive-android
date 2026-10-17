package com.example.bluetoothtest

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Experimental, default off (user question Q-N2; device-family backlog B-06): resume the foreground service after the phone
 * restarts or the app is updated, so a relay phone that rebooted overnight is back on the mesh without someone opening the
 * app. Register entry `boot-resume-after-restart`, switch `test_boot_resume` (debug) or the opt-in user setting.
 *
 * Default off means inert: [BootResumeReceiver] is declared `android:enabled="false"` in the manifest and
 * [setEnabled] turns the component on only while the user's opt-in is on, so a default install runs nothing at boot.
 *
 * Only the connectedDevice type is started (Android 15 forbids camera, microphone, mediaPlayback and others from
 * BOOT_COMPLETED; connectedDevice is allowed, and BOOT_COMPLETED / MY_PACKAGE_REPLACED are exemptions from the Android 12
 * background-start rule). Known gap, stated plainly: the relay controllers still live in the main activity, so a service
 * started at boot holds the process and the notification but does not itself relay. Closing that needs a headless host
 * (a root decision); [afterStart] is the single hook for it.
 */
internal object BootResume {
    private const val KEY_ENABLED = "boot_resume_enabled"

    val ACTIONS: Set<String> = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        // Vendor quick-boot broadcasts (power-off alarm / fast boot): same treatment, never trusted for more.
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
    )

    enum class Decision { IGNORE, START_CONNECTED_DEVICE }

    /** Pure rule: start only for a known action while the opt-in and the registry entry are on. */
    fun decide(action: String?, optIn: Boolean, entryActive: Boolean): Decision =
        if (action != null && action in ACTIONS && optIn && entryActive) Decision.START_CONNECTED_DEVICE else Decision.IGNORE

    /** Root hook: called after the service start was requested (for example to start a headless relay host). */
    @Volatile var afterStart: ((Context) -> Unit)? = null

    fun isEnabled(context: Context): Boolean = ServiceSwitches.bootResume || prefs(context).getBoolean(KEY_ENABLED, false)

    /** The user's opt-in (relay settings). Enables or disables the receiver component to match. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        syncComponent(context)
    }

    /** Make the manifest component match the current opt-in (also after the debug switch or an emergency wipe). */
    fun syncComponent(context: Context) {
        val state = if (isEnabled(context)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootResumeReceiver::class.java), state, PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(OnboardingActivity.PREFERENCES, Context.MODE_PRIVATE)
}

class BootResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val decision = BootResume.decide(intent?.action, BootResume.isEnabled(context), ServiceFamilyPolicy.bootResumeActive())
        if (decision == BootResume.Decision.IGNORE) {
            // Self-heal: the component should not be on without the opt-in (for example after an emergency wipe).
            if (!BootResume.isEnabled(context)) BootResume.syncComponent(context)
            return
        }
        try {
            ActiveVideoSessionService.start(
                context,
                usesCamera = false,
                statusText = context.getString(R.string.reachable_service_text),
                usesMicrophone = false,
            )
            BootResume.afterStart?.invoke(context)
        } catch (error: RuntimeException) {
            try { android.util.Log.w("BluetoothVideo", "BENCHMARK boot_resume: failed ${error.javaClass.simpleName}") } catch (_: RuntimeException) {}
        }
    }
}
