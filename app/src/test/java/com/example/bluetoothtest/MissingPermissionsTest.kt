package com.example.bluetoothtest

import android.Manifest
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test-suite report 2026-10-21: after the permission prompt the status always said "Camera, microphone, and Nearby
 * devices permissions are required." whatever was refused; Export diagnostics' fallback dropped the last crash report.
 */
class MissingPermissionsTest {
    @Test
    fun onlyTheRefusedGroupsAreNamedOnceEachInAFixedOrder() {
        assertEquals(listOf(MissingPermissions.Group.CAMERA), MissingPermissions.groups(listOf(Manifest.permission.CAMERA)))
        assertEquals(
            listOf(MissingPermissions.Group.NEARBY_DEVICES, MissingPermissions.Group.MICROPHONE),
            MissingPermissions.groups(
                listOf(
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
                ),
            ),
        )
        assertEquals(listOf(MissingPermissions.Group.LOCATION), MissingPermissions.groups(listOf(Manifest.permission.ACCESS_FINE_LOCATION)))
        assertEquals(emptyList<MissingPermissions.Group>(), MissingPermissions.groups(listOf("android.permission.POST_NOTIFICATIONS")))
    }

    @Test
    fun namesAreJoinedLikeASentence() {
        assertEquals("", MissingPermissions.join(emptyList(), ", ", " and "))
        assertEquals("the camera", MissingPermissions.join(listOf("the camera"), ", ", " and "))
        assertEquals("the camera and the microphone", MissingPermissions.join(listOf("the camera", "the microphone"), ", ", " and "))
        assertEquals(
            "Nearby devices, the camera and the microphone",
            MissingPermissions.join(listOf("Nearby devices", "the camera", "the microphone"), ", ", " and "),
        )
    }

    @Test
    fun theDiagnosticsFallbackKeepsTheLastCrashReport() {
        val dir = Files.createTempDirectory("diag-fallback").toFile()
        try {
            assertEquals("Diagnostics failed: IllegalStateException", CrashReports.diagnosticsFallback(IllegalStateException(), dir))
            CrashReports.writePending(dir, "crash_report: v1\nthread: main\n")
            val text = CrashReports.diagnosticsFallback(SecurityException("no"), dir)
            assertTrue(text.startsWith("Diagnostics failed: SecurityException\n\nlast_crash:\n"))
            assertTrue(text.endsWith("crash_report: v1\nthread: main\n"))
            // A report already shown at the prompt is still included for 7 days.
            CrashReports.markShown(dir)
            assertTrue(CrashReports.diagnosticsFallback(SecurityException(), dir).contains("crash_report: v1"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
