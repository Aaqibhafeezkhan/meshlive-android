package com.example.bluetoothtest

/**
 * A DEBUG dial (`test_control` voice_call/call/invite) that arrives while the target is briefly absent from the
 * discovery list (a lifecycle scan restart, an endpoint that is still starting) waits a bounded time for the next
 * sighting instead of being dropped without a trace. lane10 b1b-calls-a15s-s24 audio-a15s-s24-r1: the dial was
 * accepted 80 ms after a scan restart, waited the fixed 3 s for a fresh sighting, the first sighting of the new
 * scanner came 4.5 s after its start, and the expiry only changed a status text, so no invite and no log line followed.
 */
internal object DebugDialWait {
    // User taps use this same bounded wait (lane 11 b2x-ab-a15s-asus-audio calls 03/04/08: after the onResume scanner
    // restart the first sighting took 3.7-5.8 s, so the old silent 3 s selection expired with only a status text).

    /** Longest wait for a fresh sighting of the dialled phone. */
    const val WAIT_MS = 10_000L

    /** The short refresh wait of an automatic (not user-started) message exchange; user and DEBUG dials use [WAIT_MS]. */
    const val AUTO_REFRESH_MS = 3_000L

    /** How long a "not found nearby" notice stays on the Call screen when later sightings refresh the status line. */
    const val NOTICE_MS = 20_000L

    fun refreshTimeoutMs(auto: Boolean): Long = if (auto) AUTO_REFRESH_MS else WAIT_MS

    fun noticeVisible(shownAtMs: Long, nowMs: Long): Boolean = nowMs - shownAtMs in 0 until NOTICE_MS

    /** Visible status while the dial waits for the next sighting of the person. */
    fun lookingStatus(name: String): String = "Looking for $name…"

    /** Visible status when the wait ran out; names the reason (the scanner's registration failure, else not seen). */
    fun notFoundStatus(name: String, scannerFailureCode: Int?): String =
        if (scannerFailureCode != null) {
            "$name was not found nearby: this phone's Bluetooth scanner is unavailable " +
                "(${ScanFailurePolicy.name(scannerFailureCode)}). Try again in a moment."
        } else {
            "$name was not found nearby: their phone was not seen in ${WAIT_MS / 1000} s. " +
                "Make sure the app is open on their phone and close by, then try again."
        }

    /** One accepted dial that still waits for its target. */
    data class Pending(
        val prefix: String,
        val callType: CallType,
        val messageSync: Boolean,
        val startedAtMs: Long,
        val waitMs: Long = WAIT_MS,
        /** Shown name of a user-started dial; null for a DEBUG dial. */
        val displayName: String? = null,
    ) {
        fun matches(key: String): Boolean = key.equals(prefix, ignoreCase = true)
        fun expired(nowMs: Long): Boolean = nowMs - startedAtMs >= waitMs
        fun remainingMs(nowMs: Long): Long = (startedAtMs + waitMs - nowMs).coerceAtLeast(0L)
        fun waitedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)
    }

    /** Reason of a dial that ran out of time: the scanner's registration failure (pool exhausted) explains a missing sighting. */
    fun expiryReason(scannerFailureCode: Int?): String =
        if (scannerFailureCode != null) "scanner_unavailable" else "peer_not_seen"

    /** Extra `k=v` details of an expiry: the last scan failure code, only when the scanner is the explanation. */
    fun expiryDetails(scannerFailureCode: Int?, waitedMs: Long): Array<Pair<String, Any?>> =
        if (scannerFailureCode != null) {
            arrayOf("waitedMs" to waitedMs, "lastScanFailureCode" to scannerFailureCode, "scanFailureName" to ScanFailurePolicy.name(scannerFailureCode))
        } else {
            arrayOf("waitedMs" to waitedMs)
        }

    /** `BENCHMARK call_invite: <event> | node=... | k=v`: every dial ends in a started line or a failure line. */
    fun line(event: String, node: String, vararg details: Pair<String, Any?>): String =
        "BENCHMARK call_invite: $event | node=$node" + details.joinToString("") { " | ${it.first}=${it.second}" }
}
