package com.example.bluetoothtest

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app asks the user for before a call, a stream, a scan, and what a refusal costs. Written from the user's side:
 * refuse the camera and the call can still be heard, refuse notifications and nothing else changes, an old phone is never
 * asked for permissions that do not exist on it.
 */
class PermissionFlowTest {
    private val android11 = 30
    private val android12 = 31
    private val android13 = 33
    private val android14 = 34

    private val camera = Manifest.permission.CAMERA
    private val microphone = Manifest.permission.RECORD_AUDIO
    private val notifications = Manifest.permission.POST_NOTIFICATIONS
    private val nearbyBluetooth = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
    private val location = Manifest.permission.ACCESS_FINE_LOCATION

    private fun call(sdk: Int) = RuntimePermissionPlan.required(sdk, capturesMedia = true, needsCamera = true, needsBleAccess = true)

    @Test
    fun aCallAsksForCameraMicrophoneAndNearbyDevicesButNeverForNotifications() {
        listOf(android12, android13, android14).forEach { sdk ->
            val asked = call(sdk)
            assertTrue("Android $sdk asks for the camera", camera in asked)
            assertTrue("Android $sdk asks for the microphone", microphone in asked)
            assertTrue("Android $sdk asks for Nearby devices", asked.containsAll(nearbyBluetooth))
            assertFalse("a refused or missing notification permission must not block a call", notifications in asked)
        }
    }

    @Test
    fun watchingAStreamNeedsOnlyNearbyDevices() {
        val asked = RuntimePermissionPlan.required(android13, capturesMedia = false, needsCamera = true, needsBleAccess = true)
        assertEquals(nearbyBluetooth, asked)
        assertFalse("no camera or microphone prompt for someone who only watches", camera in asked || microphone in asked)
    }

    @Test
    fun turningAVoiceCallIntoVideoAddsOnlyTheCameraPermission() {
        val voice = RuntimePermissionPlan.required(android14, true, false, true).toSet()
        val video = RuntimePermissionPlan.required(android14, true, true, true).toSet()
        assertEquals(setOf(camera), video - voice)
        assertTrue(voice.containsAll(nearbyBluetooth + microphone))
        assertFalse(camera in RuntimePermissionPlan.onboarding(android14))
    }

    @Test
    fun anAudioOnlyBroadcastNeverAsksForTheCamera() {
        val asked = RuntimePermissionPlan.required(android13, capturesMedia = true, needsCamera = false, needsBleAccess = true)
        assertTrue(microphone in asked)
        assertFalse(camera in asked)
    }

    @Test
    fun aPhoneBeforeAndroid12IsAskedForLocationNotForBluetoothPermissionsThatDoNotExistThere() {
        val asked = call(android11)
        assertTrue(location in asked)
        assertTrue("Android 11 has no Nearby devices permission", asked.none { it in nearbyBluetooth })
        assertTrue("and no notification permission before Android 13", notifications !in RuntimePermissionPlan.onboarding(android12))
        // Without scanning or advertising nothing about location is asked.
        val offline = RuntimePermissionPlan.required(android11, capturesMedia = false, needsCamera = true, needsBleAccess = false)
        assertTrue(offline.isEmpty())
    }

    @Test
    fun theFirstRunScreenExplainsEveryPermissionTheAppCanAskFor() {
        listOf(android11, android12, android13, android14).forEach { sdk ->
            val explained = (RuntimePermissionPlan.onboarding(sdk) + camera).toSet()
            val everyScreen = listOf(
                call(sdk),
                RuntimePermissionPlan.required(sdk, capturesMedia = true, needsCamera = false, needsBleAccess = true),
                RuntimePermissionPlan.required(sdk, capturesMedia = false, needsCamera = true, needsBleAccess = true),
                RuntimePermissionPlan.required(sdk, capturesMedia = false, needsCamera = true, needsBleAccess = false),
            ).flatten()
            assertTrue("Android $sdk: a prompt the first-run screen never explained: ${everyScreen.toSet() - explained}", explained.containsAll(everyScreen))
        }
    }

    @Test
    fun readinessRequiresCallAlertsAndMicrophoneButNotCamera() {
        listOf(android12, android13, android14).forEach { sdk ->
            val needed = RuntimePermissionPlan.readiness(sdk)
            assertEquals(sdk >= android13, notifications in needed)
            assertFalse(camera in needed)
            assertTrue(needed.containsAll(listOf(microphone) + nearbyBluetooth))
            val old = RuntimePermissionPlan.readiness(sdk, callReadiness = false)
            assertTrue(camera in old)
            assertFalse(notifications in old)
            // Each refusal on its own keeps the card from saying "ready" (homeReadiness is what the card uses).
            needed.forEach { refused ->
                val granted = needed.filter { it != refused }.toSet()
                val state = homeReadiness(hasAdapter = true, permissionsGranted = needed.all { it in granted }, bluetoothOn = true)
                assertEquals("$refused refused on Android $sdk", HomeReadiness.PERMISSIONS_NEEDED, state)
            }
            assertEquals(HomeReadiness.READY, homeReadiness(true, permissionsGranted = true, bluetoothOn = true))
        }
    }

    @Test
    fun theFirstRunListHasNoDuplicatesAndAsksForNothingBeyondCameraMicrophoneNearbyDevicesAndNotifications() {
        // OnboardingActivity.permissions is what the first-run screen and the readiness card read; it delegates to the plan.
        listOf(android11, android13).forEach { sdk -> assertEquals(RuntimePermissionPlan.onboarding(sdk).size, RuntimePermissionPlan.onboarding(sdk).toSet().size) }
        assertEquals(setOf(microphone, notifications, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE), RuntimePermissionPlan.onboarding(android13).toSet())
    }

    /** The main screen's request on a plain first launch: what onboarding leaves open, on the screen the user lands on. */
    private fun landing(sdk: Int, onCallList: Boolean, sending: Boolean, callReadiness: Boolean = true) = RuntimePermissionPlan.required(
        sdk,
        RuntimePermissionPlan.screenCapturesMedia(sending, onCallScreen = false, onCallList = onCallList, callReadiness = callReadiness, transportExperiment = false),
        needsCamera = true,
        needsBleAccess = true,
    )

    @Test
    fun aFirstLaunchAsksNothingBehindOnboardingAndAutomationNeverWaits() {
        assertTrue("the Live tab under the Welcome page must not ask for Location", RuntimePermissionPlan.mainScreenWaits(onboardingOpen = true, automationLaunch = false))
        assertFalse("after Start (or Back on Welcome) the screens ask as usual", RuntimePermissionPlan.mainScreenWaits(onboardingOpen = false, automationLaunch = false))
        assertFalse("benchmark launches never show onboarding, so never wait for it", RuntimePermissionPlan.mainScreenWaits(onboardingOpen = true, automationLaunch = true))
    }

    @Test
    fun theCallsListAfterOnboardingAsksOnlyForBluetoothAccessNeverTheCamera() {
        assertEquals(listOf(location), landing(android11, onCallList = true, sending = true))
        assertEquals(nearbyBluetooth, landing(android14, onCallList = true, sending = true))
        // Going live still asks for the camera and microphone on its own screen, and a call asks when it starts.
        assertTrue(landing(android11, onCallList = false, sending = true).containsAll(listOf(camera, microphone, location)))
        assertTrue(call(android14).containsAll(listOf(camera, microphone)))
        // The older flow (callReadiness off) still asks on the list, as before.
        assertTrue(camera in landing(android14, onCallList = true, sending = true, callReadiness = false))
    }

    @Test
    fun aPermissionRefusedOnOnboardingIsNotAskedAgainRightAfterStart() {
        OnboardingState.recordAnswers(mapOf(location to false, microphone to true))
        val refused = OnboardingState.takeRefused()
        assertEquals(emptyList<String>(), RuntimePermissionPlan.afterOnboarding(listOf(location), refused))
        // Only the first screen after onboarding skips it; later requests ask again.
        assertEquals(emptySet<String>(), OnboardingState.takeRefused())
        // "Not now" on the permission page refused nothing: the Calls list asks for what it needs.
        assertEquals(nearbyBluetooth, RuntimePermissionPlan.afterOnboarding(nearbyBluetooth, emptySet()))
    }
}
