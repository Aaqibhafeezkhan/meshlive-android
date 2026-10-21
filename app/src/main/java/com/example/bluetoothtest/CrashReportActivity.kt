package com.example.bluetoothtest

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The next-launch prompt of [CrashReports]: "MeshLive closed unexpectedly. Share a crash report?". MainActivity hands
 * over here before it opens any radio, camera or service, so a crash during MainActivity's own start cannot hide the
 * prompt. Every answer marks the report as shown and continues to MainActivity; Share opens the chooser with Export
 * diagnostics (which includes the report) on top of it.
 */
class CrashReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = CrashReports.dir(this)
        if (CrashReports.pending(dir) == null) {
            continueToApp(null)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_prompt_title)
            .setMessage(R.string.crash_prompt_text)
            .setCancelable(false)
            .setPositiveButton(R.string.crash_prompt_share) { _, _ ->
                val report = runCatching { DiagnosticsUiBackend.current.build(applicationContext) }
                    .getOrElse { CrashReports.diagnosticsFallback(it, dir) }
                CrashReports.markShown(dir)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.help_export_subject))
                    .putExtra(Intent.EXTRA_TEXT, report)
                continueToApp(Intent.createChooser(send, getString(R.string.help_export)))
            }
            .setNegativeButton(R.string.crash_prompt_later) { _, _ ->
                CrashReports.markShown(dir)
                continueToApp(null)
            }
            .setNeutralButton(R.string.crash_prompt_never) { _, _ ->
                CrashReports.disable(this)
                continueToApp(null)
            }
            .show()
    }

    private fun continueToApp(chooser: Intent?) {
        val main = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { if (chooser != null) startActivities(arrayOf(main, chooser)) else startActivity(main) }
            .onFailure { runCatching { startActivity(main) } }
        finish()
    }

    companion object {
        fun intent(context: android.content.Context): Intent = Intent(context, CrashReportActivity::class.java)
    }
}
