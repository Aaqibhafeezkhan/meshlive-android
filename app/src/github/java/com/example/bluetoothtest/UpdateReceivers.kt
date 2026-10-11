package com.example.bluetoothtest

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

/**
 * Receives PackageInstaller's answer for the update session (github flavour). It is not exported: only
 * the PendingIntent that [UpdateEngine.install] hands to the session can reach it. The confirmation
 * screen Android shows is the user's consent; nothing here approves anything.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK update_install: status=$status | message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                // Kept only when Android's background-start rules blocked it: the update screen reopens it (review F12).
                val launched = confirm != null &&
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.w("BluetoothVideo", "Update confirmation could not open: ${it.message}") }
                        .isSuccess
                pendingConfirm = if (launched) null else confirm
            }
            else -> {
                pendingConfirm = null
                // Success replaces this process before the broadcast is seen; a failure is shown the next time
                // the update screen opens.
                context.getSharedPreferences(UpdateSettings.PREFERENCES_FILE, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_LAST_STATUS, status)
                    .putString(PREF_LAST_MESSAGE, message.take(200))
                    .apply()
            }
        }
    }

    companion object {
        const val PREF_LAST_STATUS = "update_last_install_status"
        const val PREF_LAST_MESSAGE = "update_last_install_message"

        /** The installer's confirmation screen, kept so the update screen can reopen it if the app was in the background. */
        @Volatile
        var pendingConfirm: Intent? = null
    }
}

/**
 * The daily background check (github flavour). Armed only while "Check for updates" is on and the
 * installed copy carries the release key; it asks the schedule in [UpdateEngine.check] first, so the
 * app, this job and a manual tap together make at most one request a day.
 */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "update-check") {
            runCatching {
                // UpdateEngine.check itself refuses without the user's yes (UpdateConsent).
                UpdateEngine.check(applicationContext, manual = false)
            }.onFailure { Log.w("BluetoothVideo", "Update job failed: ${it.javaClass.simpleName}") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 0x4D4C01

        fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setPeriodic(UpdateSchedule.DAY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(false)
                .build()
            scheduler.schedule(job)
        }
    }
}
