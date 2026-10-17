package com.example.bluetoothtest

import android.os.PowerManager

/**
 * Experimental battery-temperature fallback (batch T, item T-02; `test_thermal_battery_fallback=true`,
 * DEBUG only, default off). It replaces the former SoC-family matcher (Unisoc/MediaTek) of the registry
 * entry `thermal-battery-temperature-fallback` with runtime detection.
 *
 * Why runtime: PowerManager.getThermalHeadroom uses only TYPE_SKIN sensors that have a SEVERE
 * hot-throttling threshold (ThermalManagerService.TemperatureWatcher.updateSevereThresholds; getForecast
 * returns NaN with "No temperature thresholds found"), while getCurrentThermalStatus uses all sensor
 * types. So a phone can report statuses and still return NaN headroom, and a phone with no thermal HAL
 * can return NaN and a status that never leaves NONE. Which SoC families lack skin thresholds is not
 * documented, so no family is named; the signal is the NaN streak of [ThermalHeadroomSampler] (3 calls
 * about 1 s or more apart) or, on API 35+, an empty getThermalHeadroomThresholds() map.
 *
 * Only a fallback source is added: the reported status still wins when higher, the result never reaches
 * CRITICAL (stopping a stream stays tied to the platform's own status), and the battery temperature
 * thresholds are our values (the battery is a poor proxy for skin temperature), so the entry stays
 * Experimental until a phone without skin thresholds is measured.
 */
internal object ThermalBatteryFallback {
    /** Battery temperature (degrees Celsius) at which the fallback reports LIGHT / MODERATE / SEVERE. */
    const val LIGHT_C = 40f
    const val MODERATE_C = 43f
    const val SEVERE_C = 46f

    /** Status implied by [temperatureC]; NONE for null or a non-finite value; never above SEVERE. */
    fun statusFromBatteryTemperature(temperatureC: Float?): Int = when {
        temperatureC == null || !temperatureC.isFinite() -> PowerManager.THERMAL_STATUS_NONE
        temperatureC >= SEVERE_C -> PowerManager.THERMAL_STATUS_SEVERE
        temperatureC >= MODERATE_C -> PowerManager.THERMAL_STATUS_MODERATE
        temperatureC >= LIGHT_C -> PowerManager.THERMAL_STATUS_LIGHT
        else -> PowerManager.THERMAL_STATUS_NONE
    }

    /** ACTION_BATTERY_CHANGED EXTRA_TEMPERATURE is in tenths of a degree Celsius. */
    fun celsiusFromTenths(tenths: Int): Float? = if (tenths == Int.MIN_VALUE) null else tenths / 10f

    /** Why the fallback is or is not driving the ladder; logged so a run shows which signal was used. */
    enum class Source(val label: String) {
        PLATFORM_STATUS("platform_status"),
        FALLBACK_OFF("fallback_off"),
        HEADROOM_FINE("headroom_ok"),
        BATTERY_TEMPERATURE("battery_temperature"),
    }

    /**
     * [switchOn]: the experiment; [noSkinThresholds]: the sampler's latched verdict. The platform status
     * keeps driving the ladder when it is higher than the battery-derived one.
     */
    fun source(switchOn: Boolean, noSkinThresholds: Boolean): Source = when {
        !switchOn -> Source.FALLBACK_OFF
        noSkinThresholds -> Source.BATTERY_TEMPERATURE
        else -> Source.HEADROOM_FINE
    }

    fun effectiveStatus(reported: Int, batteryTemperatureC: Float?, switchOn: Boolean, noSkinThresholds: Boolean): Int =
        if (source(switchOn, noSkinThresholds) == Source.BATTERY_TEMPERATURE) {
            maxOf(reported, statusFromBatteryTemperature(batteryTemperatureC))
        } else {
            reported
        }

    /**
     * True when the platform status is silent while the battery is hot: the reported status is NONE but the
     * battery temperature maps to MODERATE or higher. Evidence for "status never fires", independent of
     * whether the experiment is on.
     */
    fun statusLooksSilent(reported: Int, batteryTemperatureC: Float?): Boolean =
        reported <= PowerManager.THERMAL_STATUS_NONE &&
            statusFromBatteryTemperature(batteryTemperatureC) >= PowerManager.THERMAL_STATUS_MODERATE

    fun benchmarkLine(
        reported: Int,
        batteryTemperatureC: Float?,
        switchOn: Boolean,
        sampler: ThermalHeadroomSampler?,
    ): String =
        "BENCHMARK thermal_fallback: source=${source(switchOn, sampler?.noSkinThresholds == true).label} | " +
            "reported=${MemoryThermalPolicy.statusName(reported)} | batteryC=" +
            (batteryTemperatureC?.takeIf { it.isFinite() }?.let { "%.1f".format(java.util.Locale.US, it) } ?: "na") +
            " | batteryStatus=${MemoryThermalPolicy.statusName(statusFromBatteryTemperature(batteryTemperatureC))} | " +
            "statusSilent=${statusLooksSilent(reported, batteryTemperatureC)} | " +
            "samplerCalls=${sampler?.calls ?: 0} | samplerNan=${sampler?.nanReadings ?: 0}"
}
