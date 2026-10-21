package com.example.bluetoothtest

/**
 * A failed live join (radio or video) that never became active (2026-10-20, a15s-asus radio join): the viewer hid
 * "Connecting to stream" and went back to the list with only a status line, and never tried again; to the user the tap
 * did nothing. Now the viewer says "Couldn't join <title>." with Try again, and joins once more by itself when the same
 * stream is listed again after the route cooldown. Default on; DEBUG control arm `test_stream_join_retry=false`.
 *
 * At most one automatic retry per stream until a join to it succeeds ([onJoined]); a user's Try again always queues
 * one. A pending join waits at most [WINDOW_MS] for the stream to be listed again, and any other join cancels it.
 */
internal class StreamJoinRetry<T>(private val enabled: () -> Boolean = { DebugTunables.streamJoinRetry }) {
    data class Pending<T>(val target: T, val notBeforeMs: Long, val expiresMs: Long, val auto: Boolean)

    var pending: Pending<T>? = null
        private set
    private val autoUsed = HashSet<T>()

    /** A join to [target] failed before it became active: the automatic retry, or null when none is due. */
    fun onJoinFailed(target: T, nowMs: Long): Pending<T>? {
        if (!enabled() || target in autoUsed) return null
        autoUsed += target
        return Pending(target, nowMs, nowMs + WINDOW_MS, auto = true).also { pending = it }
    }

    /** The user pressed Try again. */
    fun onTryAgain(target: T, nowMs: Long): Pending<T> =
        Pending(target, nowMs, nowMs + WINDOW_MS, auto = false).also { pending = it }

    /** A join to [target] became active: its automatic retry is available again for a later failure. */
    fun onJoined(target: T) {
        autoUsed -= target
    }

    fun cancel() {
        pending = null
    }

    /** The listed peer to join now for the pending join, if one is due; the pending join is used up. */
    fun <P> take(nowMs: Long, listed: List<P>, matches: (T, P) -> Boolean): P? {
        val due = pending ?: return null
        if (nowMs > due.expiresMs) {
            pending = null
            return null
        }
        if (nowMs < due.notBeforeMs) return null
        val peer = listed.firstOrNull { matches(due.target, it) } ?: return null
        pending = null
        return peer
    }

    companion object {
        /** Longer than the 8 s failed-route cooldown plus a few offer intervals. */
        const val WINDOW_MS = 30_000L

        fun failureMessage(title: String?): String =
            if (title.isNullOrBlank()) "Couldn't join the stream." else "Couldn't join $title."
    }
}
