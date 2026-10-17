package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The screens' side of call blocking (CallBlocking.kt holds the policy and the store). Everything here is a small
 * helper the existing screens call in one line: the Settings card, the Block confirmation, "Decline and block" and
 * the Recent rows. Store access stays off the main thread.
 */
internal object CallBlockingUi {
    /** What a list calls this person: your label for a verified contact, else the handle. */
    fun nameFor(prefixHex: String, contactNames: Map<String, String>): String =
        contactNames[prefixHex]?.takeIf { it.isNotBlank() } ?: IdentityHandle.fromPrefixHex(prefixHex)

    /** Settings card: the "Block unknown callers" switch and the "Blocked people" entry. */
    fun bindSettings(activity: Activity) {
        val prefs = activity.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        activity.findViewById<MaterialSwitch>(R.id.blockUnknownCallersSwitch).apply {
            isChecked = CallBlocking.blockUnknown(activity)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(CallBlocking.PREF_BLOCK_UNKNOWN, enabled).apply()
            }
        }
        activity.findViewById<View>(R.id.blockedPeopleButton).setOnClickListener {
            activity.startActivity(Intent(activity, BlockedPeopleActivity::class.java))
        }
    }

    /** Asks, then blocks [fingerprint]. [name] is what the person is called on screen. */
    fun confirmBlock(activity: Activity, fingerprint: IdentityFingerprint, name: String, onDone: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_warning)
            .setTitle(activity.getString(R.string.block_title, name))
            .setMessage(R.string.block_message)
            .setPositiveButton(R.string.block_action) { _, _ -> block(activity, fingerprint, name, onDone) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Blocks [fingerprint] now and says what happened. */
    fun block(activity: Activity, fingerprint: IdentityFingerprint, name: String, onDone: () -> Unit = {}) {
        val context = activity.applicationContext
        Thread {
            val result = BlockedPeople.block(context, fingerprint)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val message = when (result) {
                    BlockResult.BLOCKED -> activity.getString(R.string.block_done, name)
                    BlockResult.ALREADY_BLOCKED -> activity.getString(R.string.block_already, name)
                    BlockResult.FULL -> activity.getString(R.string.block_full)
                    BlockResult.STORE_UNREADABLE, BlockResult.UNAVAILABLE -> activity.getString(R.string.block_failed)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                onDone()
            }
        }.start()
    }

    /** Long press on a Recent row: block that person when their full identity was recorded. */
    fun blockFromRecent(activity: Activity, call: RecentCall, name: String) {
        val fingerprint = call.fingerprintHex.takeIf { it.length == IdentityFingerprint.BYTE_COUNT * 2 }
            ?.let { runCatching { IdentityFingerprint.fromHex(it) }.getOrNull() }
        if (fingerprint == null) {
            Toast.makeText(activity, R.string.block_failed, Toast.LENGTH_LONG).show()
            return
        }
        confirmBlock(activity, fingerprint, name)
    }

    /**
     * After "Decline and block" declined the call: blocks a caller that showed its real identity; for a caller that
     * is still a throwaway key (nothing to block) says so and offers "Block unknown callers".
     */
    fun afterDeclineAndBlock(activity: Activity, caller: IncomingCaller?) {
        val fingerprint = caller?.fingerprint
        if (caller == null || caller.pseudonymous || fingerprint == null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.block_not_possible_title)
                .setMessage(R.string.block_not_possible_message)
                .setPositiveButton(R.string.block_turn_on_unknown) { _, _ ->
                    activity.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putBoolean(CallBlocking.PREF_BLOCK_UNKNOWN, true).apply()
                    Toast.makeText(activity, R.string.block_unknown_turned_on, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(R.string.action_close, null)
                .show()
            return
        }
        val context = activity.applicationContext
        Thread {
            val names = runCatching { SecurityUiBackend.current.contactNames(context) }.getOrDefault(emptyMap())
            val name = nameFor(fingerprint.nodePrefixHex, names)
            val result = BlockedPeople.block(context, fingerprint)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val message = when (result) {
                    BlockResult.BLOCKED, BlockResult.ALREADY_BLOCKED -> activity.getString(R.string.block_declined_and_blocked, name)
                    BlockResult.FULL -> activity.getString(R.string.block_full)
                    BlockResult.STORE_UNREADABLE, BlockResult.UNAVAILABLE -> activity.getString(R.string.block_failed)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** The Recent row of a call declined by "Block unknown callers": nobody is named, nothing to call back. */
    fun blockedRecentRow(activity: Activity, parent: ViewGroup, call: RecentCall): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_person_row, parent, false)
        val time = android.text.format.DateUtils.getRelativeTimeSpanString(
            call.atMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        val title = activity.getString(R.string.recent_blocked_call)
        row.findViewById<TextView>(R.id.personAvatar).apply {
            text = "!"
            backgroundTintList = ContextCompat.getColorStateList(activity, R.color.surface_variant)
        }
        row.findViewById<TextView>(R.id.personName).text = title
        row.findViewById<TextView>(R.id.personReach).apply {
            text = activity.getString(R.string.recent_blocked_call_detail, time)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
        row.findViewById<View>(R.id.personVoiceButton).visibility = View.GONE
        row.findViewById<View>(R.id.personVideoButton).visibility = View.GONE
        row.contentDescription = activity.getString(R.string.recent_blocked_call_detail, time).let { "$title, $it" }
        return row
    }
}
