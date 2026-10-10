package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEncoderSpeedGovernorTest {
    private fun NativeEncoderSpeedGovernor.feed(frames: Int, ms: Double, nowNs: Long = 0L): Int? {
        var last: Int? = null
        repeat(frames) { record(ms, nowNs)?.let { last = it } }
        return last
    }

    @Test
    fun headroomTriesASlowerPresetAndKeepsItWhenItFits() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 15)
        // ASUS measured 26 ms p95 of a 66.7 ms budget at cpu-used 7.
        assertNull(governor.feed(30, 26.0))
        assertEquals(6, governor.feed(30, 26.0))
        assertNull(governor.feed(15, 50.0))
        assertEquals(6, governor.cpuUsed)
    }

    @Test
    fun aSlowerPresetThatOverrunsIsRevertedAndBlocked() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 15)
        governor.feed(60, 20.0)
        assertEquals(6, governor.cpuUsed)
        assertEquals(7, governor.feed(15, 70.0, nowNs = 1L))
        // Still calm at 7, but 6 stays blocked inside its block window.
        assertNull(governor.feed(90, 20.0, nowNs = 5_000_000_000L))
        assertEquals(7, governor.cpuUsed)
        assertEquals(6, governor.feed(60, 20.0, nowNs = 30_000_000_000L))
    }

    @Test
    fun overloadSpeedsUpAndHotPhonesDoNotTrySlowerPresets() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 30)
        assertEquals(8, governor.feed(30, 30.0))
        repeat(90) { governor.record(5.0, 0L, mayUseMoreCpu = false) }
        assertEquals(8, governor.cpuUsed)
    }

    @Test
    fun thermalFloorRaisesThePresetAndStopsSlowerTrialsUntilCleared() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 15)
        assertEquals(9, governor.applyThermalFloor(9))
        assertEquals(9, governor.cpuUsed)
        // Calm windows at the floor never try 8 while the floor is set.
        assertNull(governor.feed(300, 10.0, nowNs = 100_000_000_000L))
        assertEquals(9, governor.cpuUsed)
        assertNull(governor.applyThermalFloor(0))
        assertEquals(8, governor.feed(120, 10.0, nowNs = 200_000_000_000L))
    }

    @Test
    fun thermalFloorBelowTheCurrentPresetChangesNothing() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 9, fps = 15)
        assertNull(governor.applyThermalFloor(8))
        assertEquals(9, governor.cpuUsed)
        assertEquals(8, governor.thermalFloor)
    }
}
