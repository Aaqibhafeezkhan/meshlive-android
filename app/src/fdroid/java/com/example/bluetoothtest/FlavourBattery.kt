package com.example.bluetoothtest

import android.content.Context

/** Sideload distributions retain the system's direct exemption prompt. */
internal object FlavourBattery {
    const val directRequest = true
    fun requestSpec(packageName: String) = BatteryExemptionPrompt.directSpec(packageName)
    fun guidanceText(fallback: Int): Int = fallback
    fun explain(context: Context) = Unit
}
