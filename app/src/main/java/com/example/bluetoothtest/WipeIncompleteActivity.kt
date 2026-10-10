package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shown instead of the app when an interrupted emergency wipe still cannot complete at launch
 * (for example the Keystore keeps failing or storage is read-only). It starts no transport, key or
 * store: it only explains, retries the wipe, or opens App info where clearing storage finishes it.
 */
class WipeIncompleteActivity : Activity() {
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        message = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(android.R.string.unknownName)
            textSize = 16f
        }
        val retry = Button(this).apply {
            text = "Retry"
            setOnClickListener { retry() }
        }
        val appInfo = Button(this).apply {
            text = "App info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(
                    TextView(context).apply {
                        text = "Wipe incomplete"
                        textSize = 22f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                )
                addView(message)
                addView(retry)
                addView(appInfo)
            },
        )
    }

    private fun retry() {
        if (!EmergencyWipe.isPending(this)) {
            openApp()
            return
        }
        val report = EmergencyWipe.resumePendingWipe(this)
        if (report.complete) openApp() else message.text = EmergencyWipe.incompleteMessage(report)
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        private const val EXTRA_MESSAGE = "message"

        fun intent(context: Context, report: WipeReport): Intent =
            Intent(context, WipeIncompleteActivity::class.java)
                .putExtra(EXTRA_MESSAGE, EmergencyWipe.incompleteMessage(report))
    }
}
