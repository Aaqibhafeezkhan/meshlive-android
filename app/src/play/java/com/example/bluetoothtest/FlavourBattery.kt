package com.example.bluetoothtest

import android.content.Context
import android.widget.Toast

/** Play never asks for a direct exemption, including in DEBUG control launches. */
internal object FlavourBattery {
    const val directRequest = false
    fun requestSpec(packageName: String) = BatteryExemptionPrompt.listSpec
    fun guidanceText(fallback: Int): Int = if (DebugTunables.playBatteryGuidance) R.string.play_battery_guidance else fallback
    fun explain(context: Context) {
        if (DebugTunables.playBatteryGuidance) Toast.makeText(context, R.string.play_battery_guidance, Toast.LENGTH_LONG).show()
    }
}
