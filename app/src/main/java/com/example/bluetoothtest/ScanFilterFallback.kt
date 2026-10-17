package com.example.bluetoothtest

import java.util.UUID

/** Which filter the discovery scanners use. */
internal enum class ScanFilterStage { FILTERED, SOFTWARE }

/**
 * Filtered scan that yields nothing (batch S-06, Experimental, default off: `test_scan_sw_filter_fallback`).
 *
 * Some controllers accept an offloaded service-UUID filter and then never report a match (Scanner-Compat
 * #79: Samsung Galaxy A10/A50 with controller firmware that "supports" offloaded filtering; Scanner-Compat
 * #16: Huawei P8 Lite). The watchdog's stale verdict is the trigger: a peer is expected, the screen is on and
 * nothing else explains the silence. A short UNFILTERED probe then asks whether any advertisement reaches
 * the app at all. If it does, the discovery scanners restart with an empty hardware filter and the service
 * UUID is matched in software while the screen is on. The platform ScanSettings has no public
 * "hardware filtering off" switch (that is Nordic's Scanner-Compat API, not the framework's), so the
 * software filter is the portable step. With the screen off the filtered, non-empty scan comes back: an
 * unfiltered scan is paused by the stack and costs power (BACKLOG S-05, O S3).
 */
internal object ScanFilterFallback {
    @Volatile var stage: ScanFilterStage = ScanFilterStage.FILTERED
        private set

    /** Whether the discovery scanners should hand an empty filter to the stack and match in software. */
    fun softwareFiltering(): Boolean = stage == ScanFilterStage.SOFTWARE

    fun setStage(value: ScanFilterStage) {
        stage = value
    }

    /** Pure match of an advertisement's service UUIDs against the app's, for the software stage. */
    fun matchesService(serviceUuids: List<UUID>?, wanted: UUID, compact: UUID? = null): Boolean {
        if (serviceUuids == null) return false
        return serviceUuids.any { it == wanted || (compact != null && it == compact) }
    }

    enum class Step { NONE, PROBE, SWITCH_TO_SOFTWARE, REVERT_TO_FILTERED }

    /**
     * The next step. [probe]: null = no probe has run for this verdict, true = it saw advertisements,
     * false = it saw none (the silence is not a filter problem).
     */
    fun nextStep(
        enabled: Boolean,
        stage: ScanFilterStage,
        screenOn: Boolean,
        staleWithUnexplainedSilence: Boolean,
        probe: Boolean?,
    ): Step = when {
        !enabled -> Step.NONE
        stage == ScanFilterStage.SOFTWARE && !screenOn -> Step.REVERT_TO_FILTERED
        stage == ScanFilterStage.SOFTWARE -> Step.NONE
        !screenOn || !staleWithUnexplainedSilence -> Step.NONE
        probe == null -> Step.PROBE
        probe -> Step.SWITCH_TO_SOFTWARE
        else -> Step.NONE
    }
}

/**
 * One short unfiltered scan: did any advertisement arrive? [start] begins the scan and calls its argument
 * for every result (returns false when it could not start); [stop] ends it. The scan start takes a slot
 * from the scan-start gate; without one the probe is inconclusive (null) rather than queued.
 */
internal class UnfilteredScanProbe(
    private val gate: ScanStartGate,
    private val budget: () -> LeScanStartBudget?,
    private val timer: ScanTimer,
    private val start: (onResult: () -> Unit) -> Boolean,
    private val stop: () -> Unit,
    private val durationMs: Long = DURATION_MS,
) {
    @Volatile var running: Boolean = false
        private set

    /** Starts the probe; [onDone] gets true / false, or null when it could not run. False when already running. */
    fun run(onDone: (Boolean?) -> Unit): Boolean {
        if (running) return false
        if (!gate.tryReserve(budget())) {
            onDone(null)
            return true
        }
        var seen = false
        running = true
        val started = runCatching { start { seen = true } }.getOrDefault(false)
        if (!started) {
            running = false
            onDone(null)
            return true
        }
        timer.schedule(durationMs) {
            runCatching(stop)
            running = false
            onDone(seen)
        }
        return true
    }

    companion object {
        const val DURATION_MS = 3_000L
    }
}
