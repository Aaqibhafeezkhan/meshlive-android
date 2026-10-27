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
        reachableWhileBrowsing: Boolean = true,
    ) = ReachabilityInputs(
        featureEnabled, screen, foreground, foregroundServiceHeld, bluetoothReady, batteryLow, batterySaver,
        reachableWhileBrowsing,
    )

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
        val spec = CallReachabilityFeature.spec
        assertTrue(FeatureCatalog.resolve(spec, benchmarkLaunch = false, extra = { false }, stored = { null }))
        assertFalse(FeatureCatalog.resolve(spec, benchmarkLaunch = false, extra = { false }, stored = { false }))
        assertFalse(FeatureCatalog.resolve(spec, benchmarkLaunch = true, extra = { false }, stored = { true }))
        assertTrue(FeatureCatalog.resolve(spec, benchmarkLaunch = true, extra = { it == CallReachabilityFeature.EXTRA }, stored = { null }))
        assertEquals("test_always_reachable", CallReachabilityFeature.EXTRA)
    }

    // ---- reachable while browsing (2026-10-12) ----

    @Test
    fun browsingTheLiveTabKeepsTheEndpointWithoutScanning() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING))
        assertEquals(AdvertisingRole.CALL_AVAILABLE, plan.role)
        assertTrue(plan.endpoint)
        assertTrue(plan.reachable)
        // The endpoint adds no scan start (scan quota); the browser's own stream scan is not this plan's.
        assertFalse(plan.discoveryScan)
        assertEquals("live_browsing", plan.reason)
    }

    @Test
    fun browsingWithTheSwitchOffIsExactlyWatching() {
        val off = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING, reachableWhileBrowsing = false))
        assertEquals(CallReachability.plan(inputs(ReachabilityScreen.WATCH)), off)
        assertFalse(off.endpoint)
        assertFalse(off.reachable)
        assertEquals(AdvertisingRole.WATCHING, off.role)
    }

    @Test
    fun browsingFollowsTheSameGatesAsHome() {
        // Reachability master flag off, no Bluetooth or permissions: no endpoint, same reasons as Home.
        val flagOff = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING, featureEnabled = false))
        assertFalse(flagOff.endpoint)
        assertEquals("flag_off", flagOff.reason)
        val noBluetooth = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING, bluetoothReady = false))
        assertFalse(noBluetooth.endpoint)
        assertEquals("bluetooth_or_permissions", noBluetooth.reason)
    }

    @Test
    fun browsingInTheBackgroundNeedsTheConnectedDeviceService() {
        val withService = CallReachability.plan(
            inputs(ReachabilityScreen.LIVE_BROWSING, foreground = false, foregroundServiceHeld = true),
        )
        assertTrue(withService.endpoint)
        assertTrue(withService.reachable)
        assertEquals("live_browsing_background_service", withService.reason)
        // Android 12+: without the service the frozen process could not answer, so the endpoint stops.
        val without = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING, foreground = false))
        assertFalse(without.endpoint)
        assertFalse(without.reachable)
        assertEquals("background_without_foreground_service", without.reason)
    }

    @Test
    fun browsingMatchesHomeInEveryInputCombination() {
        for (feature in listOf(true, false)) for (fg in listOf(true, false)) for (service in listOf(true, false)) {
            for (ready in listOf(true, false)) for (low in listOf(true, false)) for (saver in listOf(true, false)) {
                val home = CallReachability.plan(inputs(ReachabilityScreen.HOME, feature, fg, service, ready, low, saver))
                val browsing = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING, feature, fg, service, ready, low, saver))
                val label = "feature=$feature fg=$fg service=$service ready=$ready low=$low saver=$saver"
                assertEquals(label, home.role, browsing.role)
                assertEquals(label, home.endpoint, browsing.endpoint)
                assertEquals(label, home.reachable, browsing.reachable)
                assertEquals(label, home.discoveryScan, browsing.discoveryScan)
            }
        }
    }

    @Test
    fun onlyTheCallScreenInTheForegroundScansForPeople() {
        for (screen in ReachabilityScreen.values()) for (fg in listOf(true, false)) {
            val plan = CallReachability.plan(inputs(screen, foreground = fg, foregroundServiceHeld = true))
            assertEquals("$screen fg=$fg", screen == ReachabilityScreen.CALL_BROWSER && fg, plan.discoveryScan)
        }
    }

    @Test
    fun theLiveTabIsBrowsingUntilAStreamIsJoinedOrRejoined() {
        assertEquals(ReachabilityScreen.LIVE_BROWSING, liveTabReachabilityScreen(true, streamJoinedOrRejoining = false))
        // Join (connectToStream), rejoin after a loss, or relaying: the viewer owns the radio.
        assertEquals(ReachabilityScreen.WATCH, liveTabReachabilityScreen(true, streamJoinedOrRejoining = true))
        // Switch off: the old Watch rules from the first frame.
        assertEquals(ReachabilityScreen.WATCH, liveTabReachabilityScreen(false, streamJoinedOrRejoining = false))
        assertEquals(ReachabilityScreen.WATCH, liveTabReachabilityScreen(false, streamJoinedOrRejoining = true))
    }

    @Test
    fun theEndpointFollowsTheUserThroughEveryLiveTabTransition() {
        fun endpoint(screen: ReachabilityScreen) = CallReachability.plan(inputs(screen, foregroundServiceHeld = true)).endpoint
        // Open the Live tab: browsing, endpoint stays (was closed here before).
        assertTrue(endpoint(ReachabilityScreen.LIVE_BROWSING))
        // Join a stream: closed, as today.
        assertFalse(endpoint(ReachabilityScreen.WATCH))
        // Leave the stream back to browsing: restored.
        assertTrue(endpoint(ReachabilityScreen.LIVE_BROWSING))
        // Go live: closed, as today; back to browsing: restored.
        assertFalse(endpoint(ReachabilityScreen.LIVE))
        assertTrue(endpoint(ReachabilityScreen.LIVE_BROWSING))
        // An incoming call while browsing: the call keeps the endpoint and the people scan stays off.
        val inCall = CallReachability.plan(inputs(ReachabilityScreen.CALL))
        assertTrue(inCall.endpoint)
        assertFalse(inCall.discoveryScan)
        assertEquals(AdvertisingRole.IN_CALL, inCall.role)
        // The call ends back on browsing: the Home-style idle plan again.
        assertEquals(AdvertisingRole.CALL_AVAILABLE, CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING)).role)
        // Calls tab and Home are unchanged by the new state.
        assertTrue(endpoint(ReachabilityScreen.CALL_BROWSER))
        assertTrue(endpoint(ReachabilityScreen.HOME))
    }

    @Test
    fun browsingStatusLineIsTrueOnlyWhenTheEndpointAdvertises() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING))
        assertEquals(HomeReachability.REACHABLE, homeReachability(plan, endpointAvailable = true, endpointStarting = false))
        assertEquals(HomeReachability.STARTING, homeReachability(plan, endpointAvailable = false, endpointStarting = true))
        assertEquals(HomeReachability.NOT_REACHABLE, homeReachability(plan, endpointAvailable = false, endpointStarting = false))
        val off = CallReachability.plan(inputs(ReachabilityScreen.LIVE_BROWSING, reachableWhileBrowsing = false))
        assertEquals(HomeReachability.NOT_REACHABLE, homeReachability(off, endpointAvailable = false, endpointStarting = false))
    }

    @Test
    fun browsingSwitchDefaultsOnForUsersAndOffForBenchmarkLaunches() {
        val spec = ReachableWhileBrowsingFeature.spec
        val absent = { _: String -> null as Boolean? }
        assertTrue(FeatureCatalog.resolve(spec, false, absent, { null }))
        // A DEBUG benchmark launch keeps the old behaviour (existing cases do not change) unless asked.
        assertFalse(FeatureCatalog.resolve(spec, true, absent, { null }))
        assertTrue(FeatureCatalog.resolve(spec, true, { true }, { null }))
        // The control arm, in a benchmark launch and in a DEBUG person's launch; a release build never reads it.
        assertFalse(FeatureCatalog.resolve(spec, true, { false }, { null }))
        assertFalse(FeatureCatalog.resolve(spec, false, { false }, { null }, debugBuild = true))
        assertTrue(FeatureCatalog.resolve(spec, false, { false }, { null }, debugBuild = false))
        assertEquals("test_reachable_while_browsing", ReachableWhileBrowsingFeature.EXTRA)
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
