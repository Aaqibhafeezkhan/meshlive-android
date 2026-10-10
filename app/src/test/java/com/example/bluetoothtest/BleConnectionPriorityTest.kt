package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class BleConnectionPriorityTest {
    @Test
    fun usesDckAtTheConfiguredDirectLinkThreshold() {
        assertEquals(BleConnectionPriority.HIGH, gattVideoPriority(499))
        assertEquals(BleConnectionPriority.DCK, gattVideoPriority(500))
    }

    @Test
    fun debugChildPriorityOverrideSupportsMatchedHighAndDckTrials() {
        assertEquals(
            BleConnectionPriority.HIGH,
            forwarderGattPriority(1_000, BleConnectionPriority.HIGH, isDebugBuild = true),
        )
        assertEquals(
            BleConnectionPriority.DCK,
            forwarderGattPriority(150, BleConnectionPriority.DCK, isDebugBuild = true),
        )
    }

    @Test
    fun absentOverrideAndReleaseRequestsKeepProductionPriority() {
        assertEquals(
            BleConnectionPriority.DCK,
            forwarderGattPriority(1_000, null, isDebugBuild = true),
        )
        assertEquals(
            BleConnectionPriority.DCK,
            forwarderGattPriority(1_000, BleConnectionPriority.HIGH, isDebugBuild = false),
        )
        assertEquals(
            BleConnectionPriority.HIGH,
            forwarderGattPriority(150, BleConnectionPriority.DCK, isDebugBuild = false),
        )
    }
}
