package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DozePolicyTest {
    private val awake = DozeSnapshot(deviceIdle = false, lightIdle = false, lowPowerStandby = false, powerSave = false)

    @Test
    fun deepSleepOutranksStandbyAndLightDoze() {
        assertEquals(DeviceSleepState.AWAKE, DozePolicy.state(awake))
        assertEquals(DeviceSleepState.LIGHT_IDLE, DozePolicy.state(awake.copy(lightIdle = true)))
        assertEquals(DeviceSleepState.LOW_POWER_STANDBY, DozePolicy.state(awake.copy(lightIdle = true, lowPowerStandby = true)))
        assertEquals(DeviceSleepState.DEEP_IDLE, DozePolicy.state(awake.copy(deviceIdle = true, lightIdle = true, lowPowerStandby = true)))
        // Battery saver alone is logged, not a sleep state.
        assertEquals(DeviceSleepState.AWAKE, DozePolicy.state(awake.copy(powerSave = true)))
    }

    @Test
    fun unavailableReadingsCountAsNotActive() {
        val oldApi = DozeSnapshot(deviceIdle = false, lightIdle = null, lowPowerStandby = null, powerSave = false)
        assertEquals(DeviceSleepState.AWAKE, DozePolicy.state(oldApi))
        assertTrue(DozePolicy.line(oldApi, "attach").contains("lightIdle=unavailable | lowPowerStandby=unavailable"))
    }

    @Test
    fun messagesExistOnlyWhileAsleep() {
        assertNull(DozePolicy.message(DeviceSleepState.AWAKE))
        assertEquals("Phone is in deep sleep; calls may be delayed.", DozePolicy.message(DeviceSleepState.DEEP_IDLE))
        assertTrue(DozePolicy.message(DeviceSleepState.LIGHT_IDLE)!!.contains("delayed"))
        assertTrue(DozePolicy.message(DeviceSleepState.LOW_POWER_STANDBY)!!.contains("standby"))
    }

    @Test
    fun onlyChangesAreReported() {
        assertTrue(DozePolicy.changed(null, awake))
        assertFalse(DozePolicy.changed(awake, awake.copy()))
        assertTrue(DozePolicy.changed(awake, awake.copy(powerSave = true)))
    }

    @Test
    fun logLineKeepsTheBenchmarkShape() {
        assertEquals(
            "doze_state: state=deep_idle | deviceIdle=true | lightIdle=false | lowPowerStandby=false | " +
                "powerSave=false | trigger=DEVICE_IDLE_MODE_CHANGED",
            DozePolicy.line(awake.copy(deviceIdle = true), "DEVICE_IDLE_MODE_CHANGED"),
        )
    }
}
