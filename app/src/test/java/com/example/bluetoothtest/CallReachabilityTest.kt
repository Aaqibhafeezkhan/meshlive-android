package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReachabilityTest {
    private fun inputs(
        screen: ReachabilityScreen,
        featureEnabled: Boolean = true,
        foreground: Boolean = true,
        foregroundServiceHeld: Boolean = false,
        bluetoothReady: Boolean = true,
        batteryLow: Boolean = false,
        batterySaver: Boolean = false,
    ) = ReachabilityInputs(featureEnabled, screen, foreground, foregroundServiceHeld, bluetoothReady, batteryLow, batterySaver)

    @Test
    fun homeInForegroundAdvertisesAndListensWithoutScanning() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.HOME))
        assertEquals(AdvertisingRole.CALL_AVAILABLE, plan.role)
        assertTrue(plan.endpoint)
        assertTrue(plan.reachable)
        // No scan start: the scan quota (5 starts / 30 s) is untouched on Home.
        assertFalse(plan.discoveryScan)
    }

    @Test
    fun homeWithFlagOffKeepsTheOldBehaviour() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.HOME, featureEnabled = false))
        assertEquals(AdvertisingRole.NONE, plan.role)
        assertFalse(plan.endpoint)
        assertFalse(plan.reachable)
        assertEquals("flag_off", plan.reason)
    }

    @Test
    fun homeNeedsBluetoothAndPermissions() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.HOME, bluetoothReady = false))
        assertFalse(plan.endpoint)
        assertEquals("bluetooth_or_permissions", plan.reason)
    }

    @Test
    fun callScreenAdvertisesAndScansInForegroundRegardlessOfFlag() {
        for (flag in listOf(true, false)) {
            val plan = CallReachability.plan(inputs(ReachabilityScreen.CALL_BROWSER, featureEnabled = flag))
            assertEquals(AdvertisingRole.CALL_AVAILABLE, plan.role)
            assertTrue(plan.endpoint)
            assertTrue(plan.discoveryScan)
        }
        // In the background the Call screen keeps its endpoint (unchanged) but stops scanning (onPause).
        val background = CallReachability.plan(inputs(ReachabilityScreen.CALL_BROWSER, foreground = false))
        assertTrue(background.endpoint)
        assertFalse(background.discoveryScan)
    }

    @Test
    fun inCallKeepsTheEndpointAndNeverScans() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.CALL, foreground = false))
        assertEquals(AdvertisingRole.IN_CALL, plan.role)
        assertTrue(plan.endpoint)
        assertFalse(plan.discoveryScan)
        assertFalse(plan.reachable)
    }

    @Test
    fun goingLiveHandsTheAdvertisingSetsToTheStream() {
        for (service in listOf(true, false)) {
            val plan = CallReachability.plan(inputs(ReachabilityScreen.LIVE, foregroundServiceHeld = service))
            assertEquals(AdvertisingRole.LIVE_STREAM, plan.role)
            assertFalse(plan.endpoint)
            assertFalse(plan.discoveryScan)
        }
    }

    @Test
    fun watchingHandsTheAdvertisingSetsToTheViewer() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.WATCH))
        assertEquals(AdvertisingRole.WATCHING, plan.role)
        assertFalse(plan.endpoint)
        assertFalse(plan.reachable)
    }

    @Test
    fun backgroundHomeNeedsTheConnectedDeviceService() {
        val withService = CallReachability.plan(
            inputs(ReachabilityScreen.HOME, foreground = false, foregroundServiceHeld = true),
        )
        assertTrue(withService.endpoint)
        assertTrue(withService.reachable)
        assertEquals("home_background_service", withService.reason)
        // Android 12+ refused or never started the service: the frozen process could not answer.
        val withoutService = CallReachability.plan(inputs(ReachabilityScreen.HOME, foreground = false))
        assertFalse(withoutService.endpoint)
        assertFalse(withoutService.reachable)
        assertEquals("background_without_foreground_service", withoutService.reason)
    }

    @Test
    fun lowBatteryAndBatterySaverNeverTurnReachabilityOff() {
        for (low in listOf(false, true)) for (saver in listOf(false, true)) for (foreground in listOf(false, true)) {
            for (screen in ReachabilityScreen.values()) {
                val normal = CallReachability.plan(
                    inputs(screen, foreground = foreground, foregroundServiceHeld = true),
                )
                val constrained = CallReachability.plan(
                    inputs(screen, foreground = foreground, foregroundServiceHeld = true, batteryLow = low, batterySaver = saver),
                )
                assertEquals("$screen low=$low saver=$saver", normal.endpoint, constrained.endpoint)
                assertEquals("$screen low=$low saver=$saver", normal.role, constrained.role)
                assertEquals("$screen low=$low saver=$saver", normal.reachable, constrained.reachable)
            }
        }
        val low = CallReachability.plan(inputs(ReachabilityScreen.HOME, batteryLow = true))
        assertTrue(low.endpoint)
        assertTrue(low.reason.contains("relay helping pauses"))
    }

    @Test
    fun homeStatusLineIsTrueOnlyWhenTheEndpointAdvertises() {
        val home = CallReachability.plan(inputs(ReachabilityScreen.HOME))
        assertEquals(HomeReachability.REACHABLE, homeReachability(home, endpointAvailable = true, endpointStarting = false))
        assertEquals(HomeReachability.STARTING, homeReachability(home, endpointAvailable = false, endpointStarting = true))
        // Start failed (e.g. too many advertisers): not claimed as visible.
        assertEquals(HomeReachability.NOT_REACHABLE, homeReachability(home, endpointAvailable = false, endpointStarting = false))
        val off = CallReachability.plan(inputs(ReachabilityScreen.HOME, featureEnabled = false))
        assertEquals(HomeReachability.NOT_REACHABLE, homeReachability(off, endpointAvailable = false, endpointStarting = false))
    }

    @Test
    fun normalLaunchesAreReachableAndBenchmarkLaunchesNeedTheExplicitExtra() {
        assertTrue(CallReachabilityFeature.resolve(benchmarkLaunch = false) { false })
        assertFalse(CallReachabilityFeature.resolve(benchmarkLaunch = true) { false })
        assertTrue(CallReachabilityFeature.resolve(benchmarkLaunch = true) { it == CallReachabilityFeature.EXTRA })
        assertEquals("test_always_reachable", CallReachabilityFeature.EXTRA)
    }

    @Test
    fun heldTypesListenerFiresOnlyOnChange() {
        var calls = 0
        ActiveVideoSessionService.heldTypesListener = { calls++ }
        try {
            ActiveVideoSessionService.updateHeldTypes(0)
            val connected = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ActiveVideoSessionService.updateHeldTypes(connected)
            ActiveVideoSessionService.updateHeldTypes(connected)
            assertTrue(ActiveVideoSessionService.holdsConnectedDevice())
            ActiveVideoSessionService.updateHeldTypes(0) // refused in onStartCommand, or the service ended
            assertEquals(2, calls)
            assertFalse(ActiveVideoSessionService.holdsConnectedDevice())
        } finally {
            ActiveVideoSessionService.heldTypesListener = null
            ActiveVideoSessionService.updateHeldTypes(0)
        }
    }
}
