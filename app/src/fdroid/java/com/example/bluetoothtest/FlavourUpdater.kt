package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The F-Droid flavour's "updater": none. It makes no request, installs nothing and declares no install
 * permission; F-Droid updates this build. Only the nearby notice's explanation lives here.
 */
internal object FlavourUpdater : UpdaterBackend {
    override val available: Boolean = false

    override fun applyCheckSetting(context: Context, enabled: Boolean) = Unit

    override fun maybeCheckNow(context: Context, onDone: () -> Unit) = Unit

    override fun open(activity: Activity, nearbyRelease: Int) {
        val prefs = activity.getSharedPreferences(UpdateSettings.PREFERENCES_FILE, Context.MODE_PRIVATE)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_fdroid_title)
            .setMessage(R.string.update_fdroid_message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.update_fdroid_hide) { _, _ -> UpdatesHome.dismissNearby(activity, prefs) }
            .show()
    }

    override fun askOnce(activity: Activity, onAnswered: (Boolean) -> Unit) = Unit
}
