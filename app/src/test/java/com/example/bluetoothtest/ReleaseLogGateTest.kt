package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLogGateTest {
    @Test
    fun aReleaseBuildNeverBuildsOrReturnsADeviceIdentifyingLine() {
        var built = false
        val line = ReleaseLogGate.debugOnly(debug = false) { built = true; "BENCHMARK device_policy: fingerprint=google/x/y" }
        assertNull(line)
        assertTrue("the line must not even be formatted in release", !built)
    }

    @Test
    fun aDebugBuildKeepsTheLineForTheBenchmarkHarness() {
        assertEquals("BENCHMARK doze_state: x", ReleaseLogGate.debugOnly(debug = true) { "BENCHMARK doze_state: x" })
    }

    @Test
    fun theDevicePolicyLineIsTheKindOfTextTheGateWithholds() {
        val resolved = DeviceFamilyPolicy.resolve(DeviceTraits("qcom", "msmnile", 30), emptySet())
        val line = ReleaseLogGate.debugOnly(debug = false) { DeviceFamilyPolicy.benchmarkLine(resolved) }
        assertNull(line)
        assertTrue(DeviceFamilyPolicy.benchmarkLine(resolved).startsWith("BENCHMARK device_policy:"))
    }
}
