package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootResumeTest {
    @Test
    fun knownRestartBroadcastsStartTheServiceOnlyWithTheOptInAndTheEntry() {
        val start = BootResume.Decision.START_CONNECTED_DEVICE
        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        ).forEach { action ->
            assertEquals(action, start, BootResume.decide(action, optIn = true, entryActive = true))
            assertEquals(action, BootResume.Decision.IGNORE, BootResume.decide(action, optIn = false, entryActive = true))
            assertEquals(action, BootResume.Decision.IGNORE, BootResume.decide(action, optIn = true, entryActive = false))
        }
    }

    @Test
    fun anythingElseIsIgnored() {
        assertEquals(BootResume.Decision.IGNORE, BootResume.decide(null, true, true))
        assertEquals(BootResume.Decision.IGNORE, BootResume.decide("android.intent.action.LOCKED_BOOT_COMPLETED", true, true))
        assertEquals(BootResume.Decision.IGNORE, BootResume.decide("com.example.evil.BOOT", true, true))
    }

    @Test
    fun theManifestActionsAndTheCodeActionsAgree() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map { java.io.File(it) }
            .first { it.isFile }.readText()
        val receiver = Regex("<receiver[^>]*BootResumeReceiver.*?</receiver>", RegexOption.DOT_MATCHES_ALL).find(manifest)
        assertTrue("the boot receiver is declared", receiver != null)
        val declared = Regex("<action\\s+android:name=\"([^\"]+)\"").findAll(receiver!!.value).map { it.groupValues[1] }.toSet()
        // A restart broadcast the manifest delivers but the code ignores (or the other way round) would silently do nothing.
        assertEquals(BootResume.ACTIONS, declared)
    }

    @Test
    fun theSwitchIsOffByDefault() {
        assertFalse(SwitchDefaults.BOOT_RESUME)
        assertFalse(ServiceSwitches.bootResume)
    }
}
