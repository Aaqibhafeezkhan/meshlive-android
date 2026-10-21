package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/** The github flavour's updater (the F-Droid flavour has a stub with the same name that does nothing). */
internal object FlavourUpdater : UpdaterBackend {
    override val available: Boolean = true

    @Volatile
    private var swept = false

    override fun applyCheckSetting(context: Context, enabled: Boolean) {
        UpdateJobService.schedule(context, enabled)
        // Once per process start: an installed update's file, a refused file and an abandoned download are not kept
        // (nothing downloads while the process is being created).
        if (!swept) {
            swept = true
            UpdateEngine.sweep(context)
        }
    }

    override fun maybeCheckNow(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        thread(name = "update-check") {
            runCatching { UpdateEngine.check(app, manual = false) }
            onDone()
        }
    }

    override fun open(activity: Activity, nearbyRelease: Int) {
        if (OfflineAppShareGate.liveSessionActive()) {
            android.widget.Toast.makeText(activity, R.string.update_busy_media, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        activity.startActivity(UpdateActivity.intent(activity, nearbyRelease))
    }

    override fun askOnce(activity: Activity, onAnswered: (Boolean) -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_ask_title)
            .setMessage(R.string.update_ask_message)
            .setCancelable(false)
            .setPositiveButton(R.string.update_ask_yes) { _, _ -> onAnswered(true) }
            .setNegativeButton(R.string.update_ask_no) { _, _ -> onAnswered(false) }
            .show()
    }
}
