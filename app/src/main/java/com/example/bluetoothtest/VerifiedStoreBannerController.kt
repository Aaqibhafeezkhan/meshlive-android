package com.example.bluetoothtest

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The home-screen banner for an unreadable verified-contact store (design decision Q15, 2026-10-14: fail open, tell the user, retry).
 *
 * Calls and streams are never blocked by the store; while it cannot be read nobody shows as verified. A temporary error (Keystore
 * or I/O) shows "Verified contacts couldn't be loaded" and is re-checked automatically on the store's own backoff schedule
 * ([VerifiedStoreRecovery]); the banner goes away by itself when a retry succeeds. A corrupt file is kept untouched and the
 * banner offers "Reset verified contacts" (after a confirmation), the only action that deletes it.
 *
 * All store access runs on a worker thread; the store's change listener only posts.
 */
internal class VerifiedStoreBannerController(
    private val activity: Activity,
    private val banner: View,
    private val text: TextView,
    private val reset: View,
) {
    private val security: SecurityUi get() = SecurityUiBackend.current
    private val handler = Handler(Looper.getMainLooper())
    private val checkAgain = Runnable { refresh() }
    private var worker: ExecutorService? = Executors.newSingleThreadExecutor()
    private var stopWatching: (() -> Unit)? = null
    private var running = false

    init {
        reset.setOnClickListener { confirmReset() }
    }

    /** Begins watching (called from onResume); also checks once now. */
    fun start() {
        if (running) {
            refresh()
            return
        }
        running = true
        val context = activity.applicationContext
        stopWatching = runCatching { security.watchStoreProblems(context) { handler.post(checkAgain) } }.getOrNull()
        refresh()
    }

    /** Stops watching and the scheduled retry (called from onPause). */
    fun stop() {
        running = false
        handler.removeCallbacks(checkAgain)
        stopWatching?.invoke()
        stopWatching = null
    }

    fun close() {
        stop()
        worker?.shutdownNow()
        worker = null
    }

    private fun refresh() {
        val executor = worker ?: return
        val context = activity.applicationContext
        executor.execute {
            val problem = runCatching { security.storeProblem(context) }.getOrNull()
            val dueInMs = runCatching { security.storeRetryDueInMs(context) }.getOrNull()
            handler.post { if (running) show(bannerFor(problem), dueInMs) }
        }
    }

    private fun show(state: StoreBanner, retryDueInMs: Long?) {
        banner.visibility = if (state == StoreBanner.NONE) View.GONE else View.VISIBLE
        when (state) {
            StoreBanner.NONE -> Unit
            StoreBanner.RETRYING -> text.setText(R.string.home_verified_store_temporary)
            StoreBanner.RESET_REQUIRED -> text.setText(R.string.home_verified_store_corrupt)
        }
        reset.visibility = if (state == StoreBanner.RESET_REQUIRED) View.VISIBLE else View.GONE
        handler.removeCallbacks(checkAgain)
        VerifiedStoreRecovery.nextCheckInMs(state, retryDueInMs)?.let { handler.postDelayed(checkAgain, it) }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_warning)
            .setMessage(R.string.verify_store_reset_confirm)
            .setPositiveButton(R.string.home_verified_store_reset) { _, _ ->
                val context = activity.applicationContext
                worker?.execute {
                    runCatching { security.resetVerifiedContacts(context) }
                    handler.post { refresh() }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        /** The banner state for a store problem: temporary errors retry by themselves, a corrupt file needs the user's reset. */
        internal fun bannerFor(problem: StoreProblem?): StoreBanner = when (problem) {
            null -> StoreBanner.NONE
            StoreProblem.TEMPORARY -> StoreBanner.RETRYING
            StoreProblem.CORRUPT -> StoreBanner.RESET_REQUIRED
        }
    }
}
