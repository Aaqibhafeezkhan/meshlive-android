package com.example.bluetoothtest

/**
 * Chooses the slowest (highest-quality) libaom real-time preset this phone can sustain, from its
 * own measured encode times instead of a fixed resolution table. A slower preset is tried for one
 * short window; if it exceeds the frame budget the governor steps back and blocks that preset for
 * an exponentially growing time. Overload at the current preset steps to a faster one.
 */
internal class NativeEncoderSpeedGovernor(
    initialCpuUsed: Int,
    fps: Int,
    private val minCpuUsed: Int = MIN_REALTIME_CPU_USED,
    private val maxCpuUsed: Int = MAX_REALTIME_CPU_USED,
) {
    private val budgetMs = 1_000.0 / fps.coerceAtLeast(1)
    private val window = DoubleArray(WINDOW_FRAMES)
    private var count = 0
    private var calmWindows = 0
    private var trialFrom: Int? = null
    private val blockedUntilNs = mutableMapOf<Int, Long>()
    private val blockDurationNs = mutableMapOf<Int, Long>()

    var cpuUsed = initialCpuUsed.coerceIn(minCpuUsed, maxCpuUsed)
        private set

    /** Completed windows since the preset last changed (read-only observation for persistence). */
    var stableWindows = 0
        private set

    /** Consecutive completed windows that overran the budget while already at the fastest preset. */
    var overloadedAtFastestWindows = 0
        private set

    val trialInProgress: Boolean get() = trialFrom != null

    /**
     * Thermal speed floor (MemoryThermalPolicy, experimental): no preset slower than this is used or
     * tried while it is set; 0 clears it. Returns the new preset when the current one had to be raised.
     */
    var thermalFloor = 0
        private set

    fun applyThermalFloor(floor: Int): Int? {
        val bounded = floor.coerceIn(0, maxCpuUsed)
        if (bounded == thermalFloor) return null
        thermalFloor = bounded
        if (bounded > cpuUsed) {
            trialFrom = null
            calmWindows = 0
            count = 0
            return change(bounded)
        }
        return null
    }

    /** Records one frame's encode time; returns a new preset when the governor changes it. */
    fun record(encodeMs: Double, nowNs: Long, mayUseMoreCpu: Boolean = true): Int? {
        window[count++] = encodeMs
        val trial = trialFrom
        val needed = if (trial != null) TRIAL_FRAMES else WINDOW_FRAMES
        if (count < needed) return null
        val p95 = window.copyOf(count).sorted()[((count - 1) * 95) / 100]
        count = 0
        val overloaded = p95 > budgetMs * OVERLOAD_FRACTION
        overloadedAtFastestWindows = if (overloaded && trial == null && cpuUsed >= maxCpuUsed) {
            overloadedAtFastestWindows + 1
        } else {
            0
        }
        val changed = decide(p95, trial, nowNs, mayUseMoreCpu)
        stableWindows = if (changed != null) 0 else stableWindows + 1
        return changed
    }

    private fun decide(p95: Double, trial: Int?, nowNs: Long, mayUseMoreCpu: Boolean): Int? {
        return when {
            p95 > budgetMs * OVERLOAD_FRACTION && trial != null -> {
                // The slower preset did not fit: return and block it for longer each time.
                val duration = (blockDurationNs[cpuUsed] ?: INITIAL_BLOCK_NS / 2) * 2
                blockDurationNs[cpuUsed] = duration.coerceAtMost(MAX_BLOCK_NS)
                blockedUntilNs[cpuUsed] = nowNs + duration
                trialFrom = null
                calmWindows = 0
                change(trial)
            }
            p95 > budgetMs * OVERLOAD_FRACTION && cpuUsed < maxCpuUsed -> {
                calmWindows = 0
                change(cpuUsed + 1)
            }
            trial != null -> {
                trialFrom = null
                null
            }
            p95 < budgetMs * HEADROOM_FRACTION && mayUseMoreCpu && cpuUsed > maxOf(minCpuUsed, thermalFloor) -> {
                calmWindows++
                val slower = cpuUsed - 1
                if (calmWindows >= CALM_WINDOWS_BEFORE_TRIAL && nowNs >= (blockedUntilNs[slower] ?: 0L)) {
                    calmWindows = 0
                    trialFrom = cpuUsed
                    change(slower)
                } else {
                    null
                }
            }
            else -> {
                calmWindows = 0
                null
            }
        }
    }

    private fun change(next: Int): Int? {
        val bounded = next.coerceIn(minCpuUsed, maxCpuUsed)
        if (bounded == cpuUsed) return null
        cpuUsed = bounded
        return bounded
    }

    companion object {
        /** libaom's real-time mode accepts speeds 5 and faster. */
        const val MIN_REALTIME_CPU_USED = 5
        const val MAX_REALTIME_CPU_USED = 10
        const val WINDOW_FRAMES = 30
        const val TRIAL_FRAMES = 15
        const val CALM_WINDOWS_BEFORE_TRIAL = 2
        const val HEADROOM_FRACTION = 0.45
        const val OVERLOAD_FRACTION = 0.85
        const val INITIAL_BLOCK_NS = 20_000_000_000L
        const val MAX_BLOCK_NS = 320_000_000_000L
    }
}
