package com.example.bluetoothtest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object ContactEmailUi {
    fun open(activity: Activity, concern: Boolean = false) {
        ContactEmail.open(activity.getString(R.string.contact_email), concern, launch = { action, uri ->
            try {
                activity.startActivity(Intent(action, Uri.parse(uri)))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }, fallback = { email ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.contact_us)
                .setMessage(email)
                .setPositiveButton(R.string.action_copy) { _, _ ->
                    activity.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.contact_us), email))
                    Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.action_close, null)
                .show()
        })
    }
}
