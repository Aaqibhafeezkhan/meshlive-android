package com.example.bluetoothtest

/**
 * Call playout decisions that are not family-specific, kept in one place.
 *
 * Speech-led call playout (on by default for direct BLE GATT calls whose video fragments were negotiated, see
 * [CallHeadOfLine]; DEBUG arm `test_call_speech_led_playout`):
 * calls share one audio/video playout clock, so a large late video frame grows the delay of
 * speech as well. In calls-noloss-m31asus-51cc8028 and calls-noloss-s24a15s-51cc8028 every GATT call
 * direction pinned the call delay at its 600 ms maximum (`BENCHMARK media_age role=call_rx
 * playoutDelayMs`), while ITU-T G.114 treats more than 400 ms one-way as unacceptable for most
 * conversation. With the experiment on, late video may grow the shared delay by at most
 * [SPEECH_LED_VIDEO_GROWTH_LIMIT_MS] (enough for ordinary frame jitter); late audio still uses the
 * whole call range. Video later than that is shown late (a short freeze, then catch-up; nothing is
 * dropped) and its lateness is still reported to the sender's rate control.
 */
internal object CallPlayoutPolicy {
    const val SPEECH_LED_VIDEO_GROWTH_LIMIT_MS = 150L

    /** Growth limit for late call video on the shared clock; null leaves behaviour unchanged. */
    fun videoGrowthLimitMs(speechLed: Boolean): Long? = if (speechLed) SPEECH_LED_VIDEO_GROWTH_LIMIT_MS else null
}
