package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of batch T (thermal, memory, native). Every one is an Experimental arm, DEBUG-only (a launch
 * extra from another app must not change a release build) and default off, except
 * [forecastLegacy10sCadence], which only selects the old arm of an already-experimental path. The forecast
 * itself stays `MemoryThermalPolicy.headroomForecast` (`test_thermal_forecast=true`). Register rows:
 * docs/dev/switches.md "Experimental, alternative and optional behaviour" (added by the root).
 */
internal object ThermalSwitches {
    /**
     * `test_thermal_forecast_legacy_10s` (Experimental, default off): keeps the original forecast sampling arm
     * (one call per 10 s, no warm-up discard) so the corrected 2 s arm can be A/B-compared against it.
     * Only meaningful together with `test_thermal_forecast=true`. Before the T-01 fix every forecast run used
     * this cadence, which the AOSP watcher cannot turn into a forecast (see [ThermalHeadroomSampler]).
     */
    @Volatile var forecastLegacy10sCadence: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_thermal_battery_fallback` (Experimental, default off): battery temperature when no skin thresholds (T-02). */
    @Volatile var batteryFallback: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_memory_tier` (Experimental, default off): tier-based sender queue and lag-frame caps (T-03). The tier log is always on. */
    @Volatile var memoryTierSizing: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_native_simd_safe_mode` (Experimental, default off): AOM_SIMD_CAPS_MASK relaunch after a native crash at encoder start (T-05). */
    @Volatile var simdSafeMode: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_userspace32_tier` (Experimental, default off): software-AV1 thread cap and MediaCodec preference on 32-bit userspace (T-08). */
    @Volatile var userspace32Tier: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    fun applyLaunchExtras(intent: Intent) = applyExtras { key, default -> intent.getBooleanExtra(key, default) }

    fun applyExtras(boolExtra: (String, Boolean) -> Boolean) {
        forecastLegacy10sCadence = boolExtra("test_thermal_forecast_legacy_10s", false)
        batteryFallback = boolExtra("test_thermal_battery_fallback", false)
        memoryTierSizing = boolExtra("test_memory_tier", false)
        simdSafeMode = boolExtra("test_native_simd_safe_mode", false)
        userspace32Tier = boolExtra("test_userspace32_tier", false)
    }

    /** Sampler configuration for the active forecast arm: the corrected 2 s cadence, or the legacy 10 s one. */
    fun samplerConfig(): ThermalHeadroomSampler.Config =
        if (forecastLegacy10sCadence) {
            ThermalHeadroomSampler.Config(
                sampleIntervalMs = 10_000L,
                warmupMs = 0L,
                decisionIntervalMs = 10_000L,
                unsupportedNanStreak = Int.MAX_VALUE,
                noSkinThresholdsNanStreak = Int.MAX_VALUE,
            )
        } else {
            ThermalHeadroomSampler.Config()
        }

    /** One launch line recording every batch-T arm, so a run's evidence states which arms were on. */
    fun benchmarkLine(): String =
        "BENCHMARK thermal_switches: forecast=${MemoryThermalPolicy.headroomForecast} | " +
            "forecastLegacy10s=$forecastLegacy10sCadence | batteryFallback=$batteryFallback | " +
            "memoryTierSizing=$memoryTierSizing | simdSafeMode=$simdSafeMode | " +
            "userspace32Tier=$userspace32Tier"
}
