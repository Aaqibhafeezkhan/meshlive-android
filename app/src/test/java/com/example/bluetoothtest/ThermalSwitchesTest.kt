package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalSwitchesTest {
    @After
    fun restore() {
        ThermalSwitches.applyExtras { _, default -> default }
    }

    @Test
    fun everyBatchSwitchDefaultsOff() {
        ThermalSwitches.applyExtras { _, default -> default }
        assertFalse(ThermalSwitches.forecastLegacy10sCadence)
        assertFalse(ThermalSwitches.batteryFallback)
        assertFalse(ThermalSwitches.memoryTierSizing)
        assertFalse(ThermalSwitches.simdSafeMode)
        assertFalse(ThermalSwitches.userspace32Tier)
    }

    @Test
    fun launchExtrasSetEachArmByItsDocumentedName() {
        val on = setOf(
            "test_thermal_forecast_legacy_10s",
            "test_thermal_battery_fallback",
            "test_memory_tier",
            "test_native_simd_safe_mode",
            "test_userspace32_tier",
        )
        ThermalSwitches.applyExtras { key, _ -> key in on }
        // BuildConfig.DEBUG is true for the debug unit-test variant; release builds ignore every extra.
        assertEquals(BuildConfig.DEBUG, ThermalSwitches.forecastLegacy10sCadence)
        assertEquals(BuildConfig.DEBUG, ThermalSwitches.batteryFallback)
        assertEquals(BuildConfig.DEBUG, ThermalSwitches.memoryTierSizing)
        assertEquals(BuildConfig.DEBUG, ThermalSwitches.simdSafeMode)
        assertEquals(BuildConfig.DEBUG, ThermalSwitches.userspace32Tier)
        // One extra turns on one arm only.
        ThermalSwitches.applyExtras { key, _ -> key == "test_native_simd_safe_mode" }
        assertEquals(BuildConfig.DEBUG, ThermalSwitches.simdSafeMode)
        assertFalse(ThermalSwitches.memoryTierSizing)
    }

    @Test
    fun samplerConfigIsTheCorrectedArmUnlessTheLegacySwitchIsOn() {
        assertEquals(ThermalHeadroomSampler.Config(), ThermalSwitches.samplerConfig())
        assertEquals(2_000L, ThermalSwitches.samplerConfig().sampleIntervalMs)
    }

    @Test
    fun benchmarkLineListsEveryArm() {
        val line = ThermalSwitches.benchmarkLine()
        assertTrue(line, line.startsWith("BENCHMARK thermal_switches: forecast="))
        listOf("forecastLegacy10s=", "batteryFallback=", "memoryTierSizing=", "simdSafeMode=", "userspace32Tier=")
            .forEach { assertTrue("$it in $line", it in line) }
    }

    @Test
    fun userspace32TierCapsThreadsOnlyOnA32BitProcessWithTheSwitch() {
        assertEquals(2, Userspace32Tier.nativeThreadCap(4, switchOn = true, process64 = false))
        assertEquals(1, Userspace32Tier.nativeThreadCap(1, true, false))
        assertEquals(4, Userspace32Tier.nativeThreadCap(4, switchOn = true, process64 = true))
        assertEquals(4, Userspace32Tier.nativeThreadCap(4, switchOn = false, process64 = false))
    }

    @Test
    fun userspaceLogLineStatesTheProcessAbi() {
        assertEquals(
            "BENCHMARK userspace: process64=false | supportedAbis=armeabi-v7a | tierSwitch=true | tierActive=true | nativeThreadCap=2",
            Userspace32Tier.logLine(true, false, listOf("armeabi-v7a")),
        )
        assertEquals(
            "BENCHMARK userspace: process64=true | supportedAbis=arm64-v8a,armeabi-v7a | tierSwitch=false | tierActive=false | nativeThreadCap=none",
            Userspace32Tier.logLine(false, true, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }
}
