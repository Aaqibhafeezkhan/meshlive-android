package com.example.bluetoothtest

/**
 * Scan-stall recovery for a visible scanner (the Nearby call list and the Live list). A scan the platform accepted can
 * still deliver nothing: AOSP drops a start that breaks its scan quota without any callback (`isScanningTooFrequently`,
 * le-scan-start-quota), and a stack may apply a stricter quota or background rule of its own. Phone runs
 * userflow2-b57d0eaf-20261019 (Redmi 12, HyperOS Android 15): Nearby call scanners delivered raw=0 for 28-90 s, with no
 * onScanFailed, while five phones advertised in the room and the previous scanner had heard them seconds earlier.
 *
 * When such a scanner has heard nothing at all (not even a result it refuses) for [requiredSilenceMs], its owner restarts
 * the system scan through the scan-start gate (never past the platform quota). Each restart that is followed by more
 * silence doubles the wait, up to [MAX_SILENCE_MS], so an empty room costs at most one start every 80 s; any result
 * resets it. Pure bookkeeping (main thread), unit-tested; the restart itself is the owner's.
 */
internal class ScanStallWatch(
    private val stallMs: Long = STALL_MS,
    private val maxSilenceMs: Long = MAX_SILENCE_MS,
) {
    private var lastHeardMs = 0L
    private var restarts = 0

    /** The system scan started (first start, renewal or a stall restart): silence is counted from now. */
    fun onStarted(nowMs: Long) {
        lastHeardMs = nowMs
    }

    /** Any scan result reached the app: the scan works. */
    fun onResult(nowMs: Long) {
        lastHeardMs = nowMs
        restarts = 0
    }

    fun silentMs(nowMs: Long): Long = (nowMs - lastHeardMs).coerceAtLeast(0L)

    /** The silence that triggers the next restart: [stallMs], doubled for each restart that changed nothing. */
    fun requiredSilenceMs(): Long {
        var wait = stallMs
        repeat(restarts) { wait = (wait * 2).coerceAtMost(maxSilenceMs) }
        return wait
    }

    fun due(nowMs: Long): Boolean = silentMs(nowMs) >= requiredSilenceMs()

    /** A stall restart was issued now. */
    fun onRestarted(nowMs: Long) {
        restarts++
        lastHeardMs = nowMs
    }

    companion object {
        const val STALL_MS = 10_000L
        const val MAX_SILENCE_MS = 80_000L
        const val CHECK_INTERVAL_MS = 2_000L

        /** `scan_stall: scanner | silentMs | restart | startsIn30s` (DEBUG evidence; the restart itself runs in release too). */
        fun line(scanner: String, silentMs: Long, restart: String, startsIn30s: Int): String =
            "scan_stall: scanner=$scanner | silentMs=$silentMs | restart=$restart | startsIn30s=$startsIn30s"
    }
}
