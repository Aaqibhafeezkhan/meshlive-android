package com.example.bluetoothtest

import android.os.Build
import android.os.Handler
import android.os.PowerManager

/**
 * Experimental thermal-headroom sampler for the forecast path (batch T, item T-01). It exists because
 * the original sampling ran once per 10 s, which can never produce a real forecast.
 *
 * What AOSP does (ThermalManagerService.TemperatureWatcher, android-13.0.0_r1 and main): the server
 * samples the skin temperature once per second only while forecast calls keep arriving within
 * INACTIVITY_THRESHOLD_MILLIS (10 s); after longer silence it clears all samples. A forecast needs
 * MINIMUM_SAMPLE_COUNT (3) samples; with fewer, the service returns the current normalised value, not a
 * forecast. The client (PowerManager.getThermalHeadroom) returns NaN for calls closer than
 * MINIMUM_HEADROOM_TIME_MILLIS (500 ms), and for every call when the device has no skin thresholds.
 * A call every 10 s therefore finds the watcher idle or just cleared each time: a current-value reading
 * labelled as a 10 s forecast.
 *
 * Rules implemented here (all pure, driven by an injected clock and reader, so they are host-tested):
 *  - sample every [Config.sampleIntervalMs] (2 s, never below the 500 ms client limit);
 *  - discard values read in the first [Config.warmupMs] (3 s) after a window starts or restarts (a gap
 *    longer than the 10 s inactivity threshold restarts it, because the server cleared its samples);
 *  - hand a decision value to the consumer only every [Config.decisionIntervalMs] (10 s), so the workload
 *    ladder does not flap on a 2 s signal;
 *  - NaN twice in a row = forecast unsupported (the decision value is null); NaN three times in a row,
 *    or an empty threshold map on API 35+ = "no skin thresholds" (latched; sampling stops; the battery
 *    temperature fallback may take over).
 * It is used only while the forecast experiment is on (`test_thermal_forecast=true`); the default status
 * ladder never calls it.
 */
internal class ThermalHeadroomSampler(
    private val readHeadroom: (forecastSeconds: Int) -> Float,
    private val nowMs: () -> Long,
    val config: Config = Config(),
    private val thresholdsEmpty: () -> Boolean? = { null },
) {
    data class Config(
        val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
        val warmupMs: Long = DEFAULT_WARMUP_MS,
        val decisionIntervalMs: Long = DEFAULT_DECISION_INTERVAL_MS,
        /** The server clears its samples after this much silence between forecast calls. */
        val inactivityResetMs: Long = SERVER_INACTIVITY_THRESHOLD_MS,
        val forecastSeconds: Int = FORECAST_SECONDS,
        val unsupportedNanStreak: Int = 2,
        val noSkinThresholdsNanStreak: Int = 3,
    ) {
        /** Never below the 500 ms client limit. */
        val effectiveSampleIntervalMs: Long get() = maxOf(sampleIntervalMs, MIN_CALL_INTERVAL_MS)
    }

    /** One [tick]: whether a call was made and whether a decision value is due for the consumer. */
    data class TickResult(
        val called: Boolean,
        val decisionDue: Boolean,
        /** Valid only when [decisionDue]; null means "no forecast" (warm-up, NaN or unsupported). */
        val decisionHeadroom: Float?,
    )

    var calls = 0
        private set
    var finiteReadings = 0
        private set
    var nanReadings = 0
        private set
    var warmupDiscarded = 0
        private set
    var consecutiveNan = 0
        private set

    /** Two NaN in a row: the forecast is ignored until a finite value arrives. */
    val forecastUnsupported: Boolean get() = consecutiveNan >= config.unsupportedNanStreak

    /** Latched: no skin thresholds (three NaN in a row, or an empty API-35 threshold map). */
    var noSkinThresholds = false
        private set

    /** Latest post-warm-up finite value, or null. */
    var latestHeadroom: Float? = null
        private set

    private var lastCallMs: Long? = null
    private var windowStartMs = 0L
    private var lastDecisionMs: Long? = null
    private var thresholdsChecked = false

    /** Sampling stops for good once no skin thresholds are established (until [reset]). */
    val stopped: Boolean get() = noSkinThresholds

    fun reset() {
        calls = 0
        finiteReadings = 0
        nanReadings = 0
        warmupDiscarded = 0
        consecutiveNan = 0
        noSkinThresholds = false
        latestHeadroom = null
        lastCallMs = null
        lastDecisionMs = null
        thresholdsChecked = false
    }

    fun tick(): TickResult {
        if (noSkinThresholds) return TickResult(false, false, null)
        val now = nowMs()
        val last = lastCallMs
        if (last != null && now - last < config.effectiveSampleIntervalMs) {
            return TickResult(false, decisionDue = false, decisionHeadroom = null)
        }
        if (!thresholdsChecked) {
            thresholdsChecked = true
            if (runCatching { thresholdsEmpty() }.getOrNull() == true) {
                noSkinThresholds = true
                return TickResult(false, decisionDue = true, decisionHeadroom = null)
            }
        }
        if (last == null || now - last > config.inactivityResetMs) {
            // The server dropped its samples (or this is the first call): a new warm-up window.
            windowStartMs = now
            latestHeadroom = null
        }
        val value = readHeadroom(config.forecastSeconds)
        val wasLatched = noSkinThresholds
        lastCallMs = now
        calls++
        if (value.isNaN()) {
            nanReadings++
            consecutiveNan++
            latestHeadroom = null
            if (consecutiveNan >= config.noSkinThresholdsNanStreak) noSkinThresholds = true
        } else {
            consecutiveNan = 0
            finiteReadings++
            if (now - windowStartMs < config.warmupMs) {
                warmupDiscarded++
                latestHeadroom = null
            } else {
                latestHeadroom = value
            }
        }
        val previousDecision = lastDecisionMs
        // Latching "no skin thresholds" always tells the consumer at once: sampling stops, so no later decision comes.
        val due = (noSkinThresholds && !wasLatched) || if (previousDecision == null) {
            latestHeadroom != null || forecastUnsupported
        } else {
            now - previousDecision >= config.decisionIntervalMs
        }
        if (!due) return TickResult(true, decisionDue = false, decisionHeadroom = null)
        lastDecisionMs = now
        return TickResult(true, decisionDue = true, decisionHeadroom = if (forecastUnsupported) null else latestHeadroom)
    }

    /** `BENCHMARK thermal_sampler:` evidence line, so a forecast run proves the forecast was actually active. */
    fun benchmarkLine(): String =
        "BENCHMARK thermal_sampler: sampleMs=${config.effectiveSampleIntervalMs} | warmupMs=${config.warmupMs} | " +
            "decisionMs=${config.decisionIntervalMs} | forecastSeconds=${config.forecastSeconds} | calls=$calls | " +
            "finite=$finiteReadings | nan=$nanReadings | warmupDiscarded=$warmupDiscarded | " +
            "latest=${latestHeadroom?.let { "%.2f".format(java.util.Locale.US, it) } ?: "na"} | " +
            "unsupported=$forecastUnsupported | noSkinThresholds=$noSkinThresholds"

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS = 2_000L
        const val DEFAULT_WARMUP_MS = 3_000L
        const val DEFAULT_DECISION_INTERVAL_MS = 10_000L
        const val FORECAST_SECONDS = 10

        /** PowerManager.MINIMUM_HEADROOM_TIME_MILLIS: calls closer than this return NaN. */
        const val MIN_CALL_INTERVAL_MS = 500L

        /** ThermalManagerService.TemperatureWatcher.INACTIVITY_THRESHOLD_MILLIS. */
        const val SERVER_INACTIVITY_THRESHOLD_MS = 10_000L

        /** The sampler wired to the real PowerManager (API 30+; null below). */
        fun forPowerManager(powerManager: PowerManager, nowMs: () -> Long, config: Config = Config()): ThermalHeadroomSampler? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return ThermalHeadroomSampler(
                readHeadroom = { seconds -> runCatching { powerManager.getThermalHeadroom(seconds) }.getOrDefault(Float.NaN) },
                nowMs = nowMs,
                config = config,
                thresholdsEmpty = {
                    if (Build.VERSION.SDK_INT >= 35) {
                        runCatching { powerManager.thermalHeadroomThresholds.isEmpty() }.getOrNull()
                    } else {
                        null
                    }
                },
            )
        }
    }
}

/** Posts a task after a delay. The production implementation wraps a Handler; tests pass a fake. */
internal fun interface TickScheduler {
    fun postDelayed(delayMs: Long, task: Runnable)
}

/**
 * Keeps a [ThermalHeadroomSampler] ticking while [isActive] is true. [ensureRunning] is idempotent and cheap,
 * so the existing 5 s badge refresh can call it; the loop then runs at the sampler's own cadence, also with
 * the screen off. It stops by itself when [isActive] turns false or no skin thresholds are established, and
 * resets the sampler so the next session starts a fresh warm-up window.
 */
internal class ThermalHeadroomSamplerDriver(
    private val sampler: ThermalHeadroomSampler,
    private val scheduler: TickScheduler,
    private val isActive: () -> Boolean,
    private val onDecision: (Float?, ThermalHeadroomSampler) -> Unit,
) {
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    private val loop = object : Runnable {
        override fun run() {
            if (!isActive()) {
                running = false
                sampler.reset()
                return
            }
            val result = sampler.tick()
            if (result.decisionDue) onDecision(result.decisionHeadroom, sampler)
            if (sampler.stopped) {
                running = false
                return
            }
            scheduler.postDelayed(sampler.config.effectiveSampleIntervalMs, this)
        }
    }

    fun ensureRunning() {
        if (running || sampler.stopped || !isActive()) return
        running = true
        scheduler.postDelayed(0L, loop)
    }

    companion object {
        fun handlerScheduler(handler: Handler): TickScheduler = TickScheduler { delay, task -> handler.postDelayed(task, delay) }
    }
}
