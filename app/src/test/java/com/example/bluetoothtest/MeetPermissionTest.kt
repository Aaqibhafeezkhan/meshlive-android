package com.example.bluetoothtest

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class MeetPermissionTest {
    @Test fun notificationDenialWarnsButDoesNotPreventContactVerification() {
        for (sdk in listOf(33, 34, 36)) {
            val granted = RuntimePermissionPlan.onboarding(sdk).filter { it != Manifest.permission.POST_NOTIFICATIONS }.toSet()
            assertTrue(RuntimePermissionPlan.endpoint(sdk).all { it in granted })
            assertFalse(RuntimePermissionPlan.readiness(sdk).all { it in granted })
        }
    }

    @Test fun eachBluetoothPermissionRemainsRequired() {
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE)) {
            val granted = RuntimePermissionPlan.onboarding(33).filter { it != permission }.toSet()
            assertFalse(RuntimePermissionPlan.endpoint(33).all { it in granted })
        }
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in RuntimePermissionPlan.endpoint(30))
    }
}
