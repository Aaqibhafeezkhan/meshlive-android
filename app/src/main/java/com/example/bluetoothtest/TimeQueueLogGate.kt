package com.example.bluetoothtest

/**
 * When a relay writes its `BENCHMARK relay_time_queue` line (DEBUG/benchmark logging only). The writer loop logs every
 * 5 s but only after a write returns, and a write into a slow hop blocks: lane10 b5-q31-drain-193409 (S24 -> ASUS ->
 * A15s) got 1-2 lines per 3 minutes, too few to judge `drainSource`. The line is therefore also written, from the
 * relay's stall-watch timer, on any change of `drainSource` or `overload`, and at least every [PERIODIC_NS] while the
 * child queue is non-empty. One gate per child connection serves both threads, so a line is never doubled.
 */
internal class TimeQueueLogGate(private val periodicNs: Long = PERIODIC_NS) {
    private var lastLogNs = 0L
    private var logged = false
    private var lastSource: String? = null
    private var lastOverload = false

    /** The writer loop logged this state itself (its own 5 s cadence). */
    @Synchronized
    fun noteLogged(nowNs: Long, source: String, overload: Boolean) {
        lastLogNs = nowNs
        logged = true
        lastSource = source
        lastOverload = overload
    }

    /** `change` or `periodic` when a line is due now (and records it), else null. */
    @Synchronized
    fun trigger(nowNs: Long, queueNonEmpty: Boolean, source: String, overload: Boolean): String? {
        val changed = lastSource != null && (source != lastSource || overload != lastOverload)
        lastSource = source
        lastOverload = overload
        val reason = when {
            changed -> "change"
            queueNonEmpty && (!logged || nowNs - lastLogNs >= periodicNs) -> "periodic"
            else -> null
        }
        if (reason != null) {
            lastLogNs = nowNs
            logged = true
        }
        return reason
    }

    companion object {
        const val PERIODIC_NS = 10_000_000_000L
    }
}
