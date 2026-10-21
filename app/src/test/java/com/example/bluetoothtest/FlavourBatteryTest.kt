package com.example.bluetoothtest

import org.junit.Assert.*
import org.junit.Test

class FlavourBatteryTest {
    @Test fun eachDistributionOpensItsAllowedBatteryScreenWithOnlyTheRequiredData() {
        val play = BuildConfig.DISTRIBUTION == "play"
        val request = BatteryExemptionPrompt.requestSpec("test.mesh")
        assertEquals(if (play) "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS" else "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", request.action)
        assertEquals(if (play) null else "package:test.mesh", request.dataUri)
    }

    @Test fun theDebugControlChangesGuidanceButCannotEnableADirectPlayRequest() {
        try {
            DebugTunables.playBatteryGuidance = false
            assertEquals(R.string.ob_bg_text, FlavourBattery.guidanceText(R.string.ob_bg_text))
            val control = BatteryExemptionPrompt.requestSpec("test.mesh")
            DebugTunables.playBatteryGuidance = true
            assertEquals(control, BatteryExemptionPrompt.requestSpec("test.mesh"))
            assertEquals(if (BuildConfig.DISTRIBUTION == "play") R.string.play_battery_guidance else R.string.ob_bg_text,
                FlavourBattery.guidanceText(R.string.ob_bg_text))
        } finally { DebugTunables.playBatteryGuidance = true }
    }
}
