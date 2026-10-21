package com.example.bluetoothtest

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The startForeground attempts of ActiveVideoSessionService, with the location fallback of Q37 (B-01). */
class ForegroundStartSequenceTest {
    private val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private class Refusal(message: String) : SecurityException(message)

    private fun run(
        requested: Int,
        fallbackEnabled: Boolean = true,
        failWhen: (Int) -> RuntimeException? = { null },
        attempts: MutableList<Int> = mutableListOf(),
    ): ForegroundServiceOutcome = ForegroundStartSequence(
        start = { types ->
            attempts += types
            failWhen(types)?.let { throw it }
        },
        fallbackEnabled = { fallbackEnabled },
        isRefusal = { it is Refusal },
    ).run(requested)

    @Test
    fun anAcceptedStartReportsTheRequestedTypes() {
        val attempts = mutableListOf<Int>()
        val outcome = run(connected or camera or microphone, attempts = attempts)
        assertEquals(listOf(connected or camera or microphone), attempts)
        assertEquals(connected or camera or microphone, outcome.effectiveTypes)
        assertNull(outcome.refused)
        assertFalse(outcome.mismatch)
    }

    @Test
    fun aRefusedCameraAndMicrophoneTypeFallsBackToConnectedDevice() {
        val attempts = mutableListOf<Int>()
        val outcome = run(
            connected or camera or microphone,
            failWhen = { types -> if (types and camera != 0) Refusal("needs the foreground only permission") else null },
            attempts = attempts,
        )
        assertEquals(listOf(connected or camera or microphone, connected), attempts)
        assertEquals(connected, outcome.effectiveTypes)
        assertTrue(outcome.refused!!.startsWith("Refusal: "))
        assertTrue(outcome.mismatch)
    }

    @Test
    fun aRefusedFallbackStopsTheService() {
        val outcome = run(connected or microphone, failWhen = { Refusal("no") })
        assertEquals(0, outcome.effectiveTypes)
        assertEquals("Refusal: no; fallback Refusal: no", outcome.refused)
    }

    @Test
    fun aRefusedConnectedDeviceOnlyStartIsNotRetried() {
        val attempts = mutableListOf<Int>()
        val outcome = run(connected, failWhen = { Refusal("background") }, attempts = attempts)
        assertEquals(listOf(connected), attempts)
        assertEquals(0, outcome.effectiveTypes)
        assertEquals("Refusal: background", outcome.refused)
    }

    @Test
    fun otherExceptionsPropagateAsBefore() {
        try {
            run(connected or microphone, failWhen = { IllegalStateException("manifest bug") })
            fail("expected the exception")
        } catch (expected: IllegalStateException) {
            assertEquals("manifest bug", expected.message)
        }
    }

    @Test
    fun aDisabledFallbackLetsARefusalPropagate() {
        try {
            run(connected or microphone, fallbackEnabled = false, failWhen = { Refusal("no") })
            fail("expected the refusal")
        } catch (expected: Refusal) {
            assertEquals("no", expected.message)
        }
    }

    @Test
    fun aFailingFallbackWithAnotherExceptionPropagates() {
        try {
            run(connected or microphone, failWhen = { types -> if (types == connected) IllegalStateException("late") else Refusal("first") })
            fail("expected the exception")
        } catch (expected: IllegalStateException) {
            assertEquals("late", expected.message)
        }
    }

    @Test
    fun locationAcceptedIsReportedWithoutARefusal() {
        val attempts = mutableListOf<Int>()
        val outcome = run(connected or location, attempts = attempts)
        assertEquals(listOf(connected or location), attempts)
        assertEquals(connected or location, outcome.effectiveTypes)
        assertNull(outcome.refused)
    }

    @Test
    fun locationIsDroppedOnAnyExceptionAndTheRestStarts() {
        val attempts = mutableListOf<Int>()
        // Not a documented refusal on Android 10-11: any failure of the add-on type must not cost the session.
        val outcome = run(
            connected or location,
            failWhen = { types -> if (ServiceTypePolicy.hasLocation(types)) IllegalStateException("location not allowed") else null },
            attempts = attempts,
        )
        assertEquals(listOf(connected or location, connected), attempts)
        assertEquals(connected, outcome.effectiveTypes)
        assertEquals("location type dropped, IllegalStateException: location not allowed", outcome.refused)
        assertTrue(outcome.mismatch) // visible to the scorer and the user notice
    }

    @Test
    fun droppingLocationKeepsCameraAndMicrophoneWhenTheyStillStart() {
        val attempts = mutableListOf<Int>()
        val outcome = run(
            connected or camera or microphone or location,
            failWhen = { types -> if (ServiceTypePolicy.hasLocation(types)) Refusal("background start") else null },
            attempts = attempts,
        )
        assertEquals(listOf(connected or camera or microphone or location, connected or camera or microphone), attempts)
        assertEquals(connected or camera or microphone, outcome.effectiveTypes)
    }

    @Test
    fun locationDropThenCameraRefusalStillFallsBackToConnectedDevice() {
        val attempts = mutableListOf<Int>()
        val outcome = run(
            connected or camera or microphone or location,
            failWhen = { types -> if (types != connected) Refusal("started from the background") else null },
            attempts = attempts,
        )
        assertEquals(
            listOf(connected or camera or microphone or location, connected or camera or microphone, connected),
            attempts,
        )
        assertEquals(connected, outcome.effectiveTypes)
        assertTrue(outcome.refused!!.startsWith("location type dropped, Refusal: started from the background; Refusal: "))
    }

    @Test
    fun locationDropThenRefusedFallbackStopsTheService() {
        val outcome = run(connected or location, failWhen = { Refusal("no") })
        assertEquals(0, outcome.effectiveTypes)
        assertEquals("location type dropped, Refusal: no; Refusal: no", outcome.refused)
    }

    /** AOSP ForegroundServiceTypePolicy's message for a type whose permission is missing (Android 14+). */
    private val missingCamera = SecurityException(
        "Starting FGS with type camera callerApp=ProcessRecord{1 2:io.github.meshlive.app/u0a1} targetSDK=36 requires " +
            "permissions: all of the permissions allOf=true [android.permission.FOREGROUND_SERVICE_CAMERA] any of the " +
            "permissions allOf=false [android.permission.CAMERA, android.permission.SYSTEM_CAMERA]",
    )

    @Test
    fun aMissingCameraPermissionNeverClosesTheAppEvenWithTheFallbackPolicyOff() {
        val attempts = mutableListOf<Int>()
        val refused = mutableListOf<Int>()
        val outcome = ForegroundStartSequence(
            start = { types ->
                attempts += types
                if (types and camera != 0) throw missingCamera
            },
            fallbackEnabled = { false },
            isRefusal = { false },
            onTypeRefused = { refused += it },
        ).run(connected or camera or microphone)
        assertEquals(listOf(connected or camera or microphone, connected), attempts)
        assertEquals(connected, outcome.effectiveTypes)
        assertEquals(listOf(connected or camera or microphone), refused)
        assertTrue(outcome.refused!!.startsWith("SecurityException: Starting FGS with type camera"))
    }

    @Test
    fun theMissingPermissionMessageIsRecognisedAndOtherSecurityExceptionsAreNot() {
        assertTrue(isForegroundTypePermissionMissing(missingCamera))
        assertFalse(isForegroundTypePermissionMissing(SecurityException("Permission Denial: not allowed")))
        assertFalse(isForegroundTypePermissionMissing(IllegalStateException(missingCamera.message)))
    }

    @Test
    fun android14AndLaterDoNotAskForATypeWhosePermissionIsOff() {
        val all = connected or camera or microphone
        assertEquals((connected or microphone) to camera, ServiceTypePolicy.withoutUngrantedTypes(36, all, false, true))
        assertEquals(connected to (camera or microphone), ServiceTypePolicy.withoutUngrantedTypes(34, all, false, false))
        assertEquals(all to 0, ServiceTypePolicy.withoutUngrantedTypes(36, all, true, true))
        assertEquals("Android 13 and older have no such prerequisite", all to 0, ServiceTypePolicy.withoutUngrantedTypes(33, all, false, false))
        assertEquals(connected to 0, ServiceTypePolicy.withoutUngrantedTypes(36, connected, false, false))
        assertEquals(
            "fgs_type_refused: types=camera | reason=permission_off | stage=precheck",
            ServiceTypePolicy.refusedLine(camera, "permission_off", "precheck"),
        )
    }

    @Test
    fun theUserIsToldWhichPermissionIsOff() {
        fun notice(off: Int) = ForegroundServiceOutcome(connected or camera or microphone, connected, "permission off", off).userNotice()
        assertTrue(notice(camera).startsWith("Camera permission is off."))
        assertTrue(notice(microphone).startsWith("Microphone permission is off."))
        assertTrue(notice(camera or microphone).startsWith("Camera and microphone permissions are off."))
        assertEquals(
            "Camera and microphone are unavailable while the app is in the background. Open the app to use them.",
            notice(0),
        )
    }

    @Test
    fun aNativeLibraryThatCannotLoadOnlyRemovesTheSoftwareEncoder() {
        // On the JVM the Android library is normally absent: touching the bridge must not throw.
        val version = NativeAomBridge.versionOrNull()
        assertEquals(NativeAomBridge.loadError == null, NativeAomBridge.available)
        if (!NativeAomBridge.available) assertNull(version)
    }

    @Test
    fun locationDropThenAnUnrelatedExceptionPropagates() {
        try {
            run(connected or location, failWhen = { types -> if (ServiceTypePolicy.hasLocation(types)) Refusal("x") else IllegalStateException("manifest bug") })
            fail("expected the exception")
        } catch (expected: IllegalStateException) {
            assertEquals("manifest bug", expected.message)
        }
    }
}
