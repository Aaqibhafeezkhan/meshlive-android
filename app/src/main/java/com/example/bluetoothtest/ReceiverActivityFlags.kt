package com.example.bluetoothtest

/**
 * Review B2 (2026-10-27): whether a stream join became active, kept per attempt and per playback session.
 *
 * Before, one flag served both. It was set when any receiver went active and reset only by a join that did not preserve
 * playback, so during recovery (every reconnect preserves playback) it stayed true: a reconnect that never became active
 * still counted as active. Its failed parent was then recorded as a success (the "avoid a failing parent" fallback reset
 * itself), the failed route got the short unexpected-loss cooldown instead of the failed-route one, and the waiting overlay
 * could hide on a frame of the old session.
 *
 * [attempt] is reset when a receiver starts; [session] (only for "keep the playback screen while reconnecting") is reset
 * when a join starts a new playback session.
 */
internal class ReceiverActivityFlags {
    var attempt = false
    var session = false
        private set

    fun startAttempt() {
        attempt = false
    }

    fun onActive() {
        attempt = true
        session = true
    }

    fun newSession() {
        attempt = false
        session = false
    }
}
