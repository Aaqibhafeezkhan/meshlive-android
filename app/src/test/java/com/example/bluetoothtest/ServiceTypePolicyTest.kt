package com.example.bluetoothtest

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTypePolicyTest {
    private val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private fun wanted(
        sdk: Int = 30,
        policy: Boolean = true,
        locationOn: Boolean = true,
        fine: Boolean = true,
        visible: Boolean = true,
        held: Boolean = false,
        distribution: Boolean = true,
    ) = ServiceTypePolicy.locationTypeWanted(sdk, policy, locationOn, fine, visible, held, distribution)

    @Test
    fun aBuildWithoutTheLocationTypeNeverAsksForIt() {
        // The Google Play flavour declares no location type; asking for an undeclared type throws in startForeground.
        assertFalse(wanted(sdk = 29, distribution = false))
        assertFalse(wanted(sdk = 30, held = true, distribution = false))
    }

    @Test
    fun locationTypeIsWantedOnlyOnAndroid10And11() {
        assertFalse(wanted(sdk = 28))
        assertTrue(wanted(sdk = 29))
        assertTrue(wanted(sdk = 30))
        assertFalse(wanted(sdk = 31))
        assertFalse(wanted(sdk = 34))
        assertFalse(wanted(sdk = 36))
    }

    @Test
    fun locationTypeNeedsPolicyLocationSwitchAndFineGrant() {
        assertFalse(wanted(policy = false))
        assertFalse(wanted(locationOn = false))
        assertFalse(wanted(fine = false))
    }

    @Test
    fun locationTypeIsNotAskedFromTheBackgroundUnlessAlreadyHeld() {
        assertFalse(wanted(visible = false, held = false))
        // A later notification update from the background must keep the type the service already holds.
        assertTrue(wanted(visible = false, held = true))
    }

    @Test
    fun requestedTypesAddLocationOnlyWhenAsked() {
        assertEquals(connected, ActiveVideoSessionService.requestedTypes(false, false))
        assertEquals(connected or location, ActiveVideoSessionService.requestedTypes(false, false, location = true))
        assertEquals(
            connected or camera or microphone or location,
            ActiveVideoSessionService.requestedTypes(true, true, location = true),
        )
        // The pre-existing combinations are unchanged.
        assertEquals(connected or camera or microphone, ActiveVideoSessionService.requestedTypes(true, true))
        assertEquals(connected or microphone, ActiveVideoSessionService.requestedTypes(false, true))
        assertEquals(
            connected or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ActiveVideoSessionService.requestedTypes(false, false, mediaPlayback = true),
        )
    }

    @Test
    fun withoutLocationKeepsEveryOtherType() {
        val all = connected or camera or microphone or location
        assertEquals(connected or camera or microphone, ServiceTypePolicy.withoutLocation(all))
        assertEquals(connected, ServiceTypePolicy.withoutLocation(connected))
        assertTrue(ServiceTypePolicy.hasLocation(all))
        assertFalse(ServiceTypePolicy.hasLocation(connected))
    }

    @Test
    fun labelsNameTheLocationType() {
        assertEquals("connectedDevice+location", ServiceTypePolicy.foregroundTypeLabelsWithLocation(connected or location))
        assertEquals("connectedDevice+camera+microphone", ServiceTypePolicy.foregroundTypeLabelsWithLocation(connected or camera or microphone))
        assertEquals("location", ServiceTypePolicy.foregroundTypeLabelsWithLocation(location))
        assertEquals("none", ServiceTypePolicy.foregroundTypeLabelsWithLocation(0))
    }

    @Test
    fun visibleMeansAnActivityIsOnScreenNotMerelyARunningService() {
        assertTrue(ServiceTypePolicy.isForegroundImportance(100))
        assertFalse(ServiceTypePolicy.isForegroundImportance(125)) // IMPORTANCE_FOREGROUND_SERVICE
        assertFalse(ServiceTypePolicy.isForegroundImportance(400)) // IMPORTANCE_CACHED
    }

    @Test
    fun idleAvailabilityIsNotAnActiveHop() {
        // Reachability and the incoming ring: status text only.
        assertEquals(
            ServiceTypePolicy.SessionKind.IDLE_AVAILABILITY,
            ServiceTypePolicy.sessionKind(false, false, false, false, false, hasStatusText = true),
        )
        // Stream receiver / relay source: no status text, no media extras.
        assertEquals(
            ServiceTypePolicy.SessionKind.ACTIVE,
            ServiceTypePolicy.sessionKind(false, false, false, false, false, hasStatusText = false),
        )
        assertEquals(ServiceTypePolicy.SessionKind.ACTIVE, ServiceTypePolicy.sessionKind(true, true, false, false, false, true))
        assertEquals(ServiceTypePolicy.SessionKind.ACTIVE, ServiceTypePolicy.sessionKind(false, true, true, false, false, true))
        assertEquals(ServiceTypePolicy.SessionKind.ACTIVE, ServiceTypePolicy.sessionKind(false, false, false, true, false, true))
        assertEquals(ServiceTypePolicy.SessionKind.ACTIVE, ServiceTypePolicy.sessionKind(false, false, false, false, true, true))
    }

    @Test
    fun startLineReportsTypesLatencyAndPermissions() {
        val line = ServiceTypePolicy.startLine(connected or location, 42L, listOf("camera" to true, "btScan" to false))
        assertEquals("fgs_start: types=connectedDevice+location | latency_ms=42 | permissions=camera:Y,btScan:N", line)
        assertTrue(ServiceTypePolicy.startLine(connected, null, emptyList()).contains("latency_ms=unknown"))
    }

    @Test
    fun timeoutLineNamesTheTypeAndTheAction() {
        assertEquals("fgs_timeout: startId=3 | type=connectedDevice | action=stop", ServiceTypePolicy.timeoutLine(3, connected))
        assertEquals("fgs_timeout: startId=3 | type=none | action=stop", ServiceTypePolicy.timeoutLine(3, null))
    }
}

class ServiceCleanupTest {
    @Test
    fun taskRemovalAlwaysStopsTheScannersWhenEnabled() {
        assertTrue(ServiceCleanup.shouldStopScans(true, ServiceCleanup.Reason.TASK_REMOVED, appVisible = false))
        assertTrue(ServiceCleanup.shouldStopScans(true, ServiceCleanup.Reason.TASK_REMOVED, appVisible = true))
        assertFalse(ServiceCleanup.shouldStopScans(false, ServiceCleanup.Reason.TASK_REMOVED, appVisible = false))
    }

    @Test
    fun aServiceThatEndsWhileTheAppIsVisibleLeavesTheScannersToTheActivity() {
        assertTrue(ServiceCleanup.shouldStopScans(true, ServiceCleanup.Reason.SERVICE_DESTROYED, appVisible = false))
        assertFalse(ServiceCleanup.shouldStopScans(true, ServiceCleanup.Reason.SERVICE_DESTROYED, appVisible = true))
        assertFalse(ServiceCleanup.shouldStopScans(false, ServiceCleanup.Reason.SERVICE_DESTROYED, appVisible = false))
    }

    @Test
    fun stopScansCallsTheSeamOnceAndSurvivesAFailure() {
        val saved = ServiceCleanup.scanStopAll
        try {
            var calls = 0
            ServiceCleanup.scanStopAll = { calls++ }
            assertTrue(ServiceCleanup.stopScans(true, ServiceCleanup.Reason.TASK_REMOVED, appVisible = false))
            assertEquals(1, calls)
            assertFalse(ServiceCleanup.stopScans(true, ServiceCleanup.Reason.SERVICE_DESTROYED, appVisible = true))
            assertEquals(1, calls)
            ServiceCleanup.scanStopAll = { throw IllegalStateException("scanner already gone") }
            assertTrue(ServiceCleanup.stopScans(true, ServiceCleanup.Reason.TASK_REMOVED, appVisible = false))
        } finally {
            ServiceCleanup.scanStopAll = saved
        }
    }
}
