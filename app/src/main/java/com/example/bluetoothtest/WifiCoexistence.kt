package com.example.bluetoothtest

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 2.4 GHz Wi-Fi shares the antenna/band with Bluetooth and the chip time-slices between them. On
 * the M31 (Wi-Fi at 2472 MHz) GATT measured 0.39-0.46 Mbps and CoC 0.56 Mbps; with Wi-Fi off the
 * same links measured 0.89 and 0.95 Mbps. Apps cannot change the Wi-Fi band, so the phone detects
 * it, tells the user and keeps its learned capacity separate for that radio condition.
 */
internal fun wifiSharesBluetoothBand(frequencyMhz: Int?): Boolean = frequencyMhz != null && frequencyMhz in 2_400..2_500

object WifiCoexistence {
    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    @Suppress("DEPRECATION")
    fun connectedFrequencyMhz(): Int? = runCatching {
        val manager = appContext?.getSystemService(WifiManager::class.java) ?: return null
        if (!manager.isWifiEnabled) null else manager.connectionInfo?.frequency?.takeIf { it > 0 }
    }.getOrNull()

    fun sharesBluetoothBand(): Boolean = wifiSharesBluetoothBand(connectedFrequencyMhz())

    /** Plain-language advice when Wi-Fi is slowing Bluetooth, or null. */
    fun advice(): String? = if (sharesBluetoothBand()) {
        "Wi-Fi is on 2.4 GHz and shares the radio with Bluetooth, roughly halving Bluetooth speed. " +
            "Switch Wi-Fi to a 5 GHz network or turn it off for smoother, sharper video."
    } else {
        null
    }
}
