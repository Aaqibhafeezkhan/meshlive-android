package com.example.bluetoothtest

/**
 * The scan duty cycle the stack actually applies per Android release (AOSP ScanManager, aosp-bt-versions SCAN-06):
 * the window/interval of each mode changed in Android 9 and again in 13, screen-off remaps filtered scans on 13+, and a
 * filtered scan that outlives the timeout is downgraded to LOW_POWER (13 QPR1+/14+). This is a log aid so discovery
 * benchmarks from different releases are not compared as if equal; it never changes a request. Values are AOSP defaults;
 * an OEM can override them (Android 9-12 through hidden Settings.Global keys), hence "expected".
 */
internal object ScanDutyCycle {
    /** Window and interval in milliseconds. */
    data class Duty(val windowMs: Int, val intervalMs: Int) {
        val percent: Int get() = (windowMs * 100) / intervalMs
        fun label() = "$windowMs/$intervalMs"
    }

    // android.bluetooth.le.ScanSettings modes.
    private const val LOW_POWER = 0
    private const val BALANCED = 1

    fun duty(sdk: Int, scanMode: Int, screenOn: Boolean): Duty = when {
        sdk >= 33 -> when {
            scanMode == LOW_POWER && !screenOn -> Duty(512, 10_240) // SCREEN_OFF
            scanMode == BALANCED && !screenOn -> Duty(183, 730) // SCREEN_OFF_BALANCED
            scanMode == LOW_POWER -> Duty(140, 1_400)
            scanMode == BALANCED -> Duty(183, 730)
            else -> Duty(100, 100) // LOW_LATENCY (also unchanged with the screen off)
        }
        sdk >= 28 -> when (scanMode) {
            LOW_POWER -> Duty(512, 5_120)
            BALANCED -> Duty(1_024, 4_096)
            else -> Duty(4_096, 4_096)
        }
        else -> when (scanMode) {
            LOW_POWER -> Duty(500, 5_000)
            BALANCED -> Duty(2_000, 5_000)
            else -> Duty(5_000, 5_000)
        }
    }

    /** What a filtered scan runs at once the timeout downgrade applied (13 QPR1+/14+; 13 base keeps its mode). */
    fun afterTimeoutDuty(sdk: Int, scanMode: Int, screenOn: Boolean): Duty =
        if (sdk >= 33) duty(sdk, LOW_POWER, screenOn) else duty(sdk, scanMode, screenOn)

    fun line(sdk: Int, scanMode: Int, screenOn: Boolean, scanner: String): String {
        val now = duty(sdk, scanMode, screenOn)
        val later = afterTimeoutDuty(sdk, scanMode, screenOn)
        return "scan_duty: scanner=$scanner | sdk=$sdk | mode=$scanMode | screenOn=$screenOn | " +
            "expectedWindowIntervalMs=${now.label()} | dutyPercent=${now.percent} | " +
            "timeoutWindowMs=${ScanRenewalPolicy.windowMs(sdk)} | afterTimeoutWindowIntervalMs=${later.label()}"
    }
}
