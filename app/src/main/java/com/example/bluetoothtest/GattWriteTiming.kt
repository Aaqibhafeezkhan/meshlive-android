package com.example.bluetoothtest

internal data class GattWriteIntervals(
    val apiNs: Long?,
    val callbackAfterApiNs: Long?,
    val wakeupNs: Long?,
    val totalNs: Long,
    val callbackDuringApi: Boolean,
)

internal fun gattWriteIntervals(
    startedNs: Long,
    apiReturnedNs: Long?,
    callbackNs: Long?,
    finishedNs: Long,
): GattWriteIntervals {
    val returned = apiReturnedNs?.takeIf { it in startedNs..finishedNs }
    val callback = callbackNs?.takeIf { it in startedNs..finishedNs }
    return GattWriteIntervals(
        apiNs = returned?.minus(startedNs),
        callbackAfterApiNs = if (returned != null && callback != null) (callback - returned).coerceAtLeast(0) else null,
        wakeupNs = if (returned != null && callback != null) finishedNs - maxOf(returned, callback) else null,
        totalNs = finishedNs - startedNs,
        callbackDuringApi = returned != null && callback != null && callback < returned,
    )
}

internal class GattWriteTiming(private val report: (String) -> Unit) {
    private val api = RollingLatencyStats()
    private val callbackAfterApi = RollingLatencyStats()
    private val wakeup = RollingLatencyStats()
    private val total = RollingLatencyStats()
    private var attempts = 0L
    private var attemptBytes = 0L
    private var rejected = 0L
    private var timedOut = 0L
    private var failed = 0L
    private var missingCallback = 0L
    private var callbackDuringApi = 0L
    private var lastReportNs: Long? = null

    fun record(bytes: Int, startedNs: Long, apiReturnedNs: Long?, callbackNs: Long?, finishedNs: Long, outcome: String) {
        val intervals = gattWriteIntervals(startedNs, apiReturnedNs, callbackNs, finishedNs)
        attempts++
        attemptBytes += bytes
        if (outcome == "rejected") rejected++
        if (outcome == "timeout") timedOut++
        if (outcome != "success") failed++
        if (intervals.callbackAfterApiNs == null) missingCallback++
        if (intervals.callbackDuringApi) callbackDuringApi++
        intervals.apiNs?.let(api::recordNanos)
        intervals.callbackAfterApiNs?.let(callbackAfterApi::recordNanos)
        intervals.wakeupNs?.let(wakeup::recordNanos)
        total.recordNanos(intervals.totalNs)
        val previous = lastReportNs ?: startedNs.also { lastReportNs = it }
        if (finishedNs - previous < 5_000_000_000L) return
        lastReportNs = finishedNs
        fun timing(name: String, stats: RollingLatencyStats): String {
            val snapshot = stats.snapshot()
            if (snapshot.samples == 0) return "$name UNMEASURED (0 samples)"
            return java.lang.String.format(java.util.Locale.ROOT,
                "%s p50/p95/max %.3f/%.3f/%.3f ms (%d rolling samples)",
                name, snapshot.p50Ms, snapshot.p95Ms, snapshot.maxMs, snapshot.samples)
        }
        report("BENCHMARK GATT write timing: attempts=$attempts attemptValueBytes=$attemptBytes " +
            "rejected=$rejected timedOut=$timedOut failed=$failed missingCallback=$missingCallback " +
            "callbackDuringApi=$callbackDuringApi | " + listOf(timing("api", api),
                timing("callback-after-api", callbackAfterApi), timing("await-wakeup", wakeup),
                timing("total", total)).joinToString(" | "))
    }
}
