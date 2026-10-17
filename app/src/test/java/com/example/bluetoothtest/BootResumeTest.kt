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
        assertEquals(4, BootResume.ACTIONS.size)
        assertTrue("android.intent.action.BOOT_COMPLETED" in BootResume.ACTIONS)
    }

    @Test
    fun theSwitchIsOffByDefault() {
        assertFalse(SwitchDefaults.BOOT_RESUME)
        assertFalse(ServiceSwitches.bootResume)
    }
}
