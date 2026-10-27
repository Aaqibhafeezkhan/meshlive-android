package com.example.bluetoothtest

/**
 * One resolver for a call endpoint's head-of-line measures (switch audit 2026-10-27, F5): call video fragments
 * ([CallVideoFragments]) and speech-led playout ([CallPlayoutPolicy]). Before, three independent switches decided them
 * (`callVideoFragments`, `callSpeechLedPlayout` and the promoted `callGattSpeechDefaults`), and nothing logged which
 * source a call used.
 *
 * Sources, in order:
 * - a DEBUG benchmark launch with `test_call_head_of_line=off|gatt_default|on` ([Mode]): `off` is the control arm, `gatt_default`
 *   measures exactly what a user launch runs, `on` turns both on for every transport;
 * - a DEBUG benchmark launch without it: the separate Experimental extras `test_call_video_fragments` and
 *   `test_call_speech_led_playout` (default off, each arm still switchable alone);
 * - a user launch: the promoted policy (design decision 2026-10-11, DECISIONS "Call head-of-line A/B"): both on for direct calls
 *   whose endpoint transport is BLE GATT (release builds never see the extras).
 *
 * Speech-led playout of the promoted policy and of `on` applies only to a call whose fragments were negotiated (B5): only
 * fragments plus speech-led was measured, and speech-led alone lets late video play behind the audio it was paired with
 * (relayed calls and peers without the fragment bit). The separate speech-led extra keeps its ungated arm.
 */
object CallHeadOfLine {
    const val EXTRA = "test_call_head_of_line"

    enum class Mode(val value: String) {
        OFF("off"),
        GATT_DEFAULT("gatt_default"),
        ON("on"),
        ;

        companion object {
            fun fromValue(value: String?): Mode? = entries.firstOrNull { it.value.equals(value?.trim(), ignoreCase = true) }
        }
    }

    data class Policy(
        val videoFragments: Boolean,
        /** Speech-led playout asked for; see [speechLed]. */
        val speechLedRequested: Boolean,
        /** Speech-led playout only once fragments were negotiated (false for the separate Experimental speech-led arm). */
        val speechLedNeedsFragments: Boolean,
        /** Which source decided: `user_default`, `mode_<value>` or `extras`. */
        val source: String,
    ) {
        /** Speech-led playout for a call whose fragment negotiation ended with [fragmentsNegotiated]. */
        fun speechLed(fragmentsNegotiated: Boolean): Boolean =
            speechLedRequested && (fragmentsNegotiated || !speechLedNeedsFragments)

        fun label(fragmentsNegotiated: Boolean): String =
            "source=$source | videoFragments=$videoFragments | fragmentsNegotiated=$fragmentsNegotiated | " +
                "speechLed=${speechLed(fragmentsNegotiated)}"
    }

    fun resolve(
        benchmarkLaunch: Boolean,
        gattEndpoint: Boolean,
        mode: Mode?,
        fragmentsExtra: Boolean,
        speechLedExtra: Boolean,
    ): Policy = when {
        // A user launch (a DEBUG `test_user_launch` may still add the separate arms, as before this resolver).
        !benchmarkLaunch -> Policy(
            videoFragments = gattEndpoint || fragmentsExtra,
            speechLedRequested = gattEndpoint || speechLedExtra,
            speechLedNeedsFragments = !speechLedExtra,
            source = "user_default",
        )
        mode == Mode.OFF -> Policy(false, false, true, "mode_off")
        mode == Mode.GATT_DEFAULT -> gattDefault(gattEndpoint, "mode_gatt_default")
        mode == Mode.ON -> Policy(true, true, true, "mode_on")
        else -> Policy(fragmentsExtra, speechLedExtra, speechLedNeedsFragments = false, source = "extras")
    }

    private fun gattDefault(gattEndpoint: Boolean, source: String) = Policy(gattEndpoint, gattEndpoint, true, source)

    /** The policy of a call endpoint created now, from this launch's [DebugTunables]. */
    fun current(endpointTransport: BluetoothTransportOption): Policy = resolve(
        benchmarkLaunch = DebugTunables.benchmarkLaunch,
        gattEndpoint = endpointTransport == BluetoothTransportOption.BLE_GATT,
        mode = DebugTunables.callHeadOfLineMode,
        fragmentsExtra = DebugTunables.callVideoFragments,
        speechLedExtra = DebugTunables.callSpeechLedPlayout,
    )
}
