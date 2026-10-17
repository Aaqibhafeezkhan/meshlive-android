package com.example.bluetoothtest

import android.media.AudioDeviceInfo

/** An audio sink the platform reports; a plain stand-in for [AudioDeviceInfo] so the rules are JVM-tested. */
internal data class AudioSink(val type: Int, val name: String, val id: Int)

/** One row of the audio output sheet. */
internal data class CallOutputOption(val output: CallOutput, val name: String, val deviceId: Int = NO_DEVICE) {
    companion object {
        const val NO_DEVICE = -1
    }
}

/** Bluetooth call audio (SCO, LE Audio headset, hearing aid). Offered only behind [CallBluetoothOutputFeature]. */
internal val BLUETOOTH_CALL_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
)

/**
 * What the call screen's Speaker button does, from the outputs that are actually available (JVM-tested).
 *
 * The button always sits in the control row of a voice and of a video call. With only the phone's own
 * speaker and earpiece a tap toggles between them (earpiece for voice, loudspeaker for video by default).
 * As soon as a wired headset (or, behind the Bluetooth switch, a Bluetooth audio device) is present, a tap
 * opens the output sheet instead: Phone speaker / Earpiece / the device by name.
 */
internal object CallOutputChoices {
    /** Speaker first, then the earpiece if the phone has one, then each external device once. */
    fun options(sinks: List<AudioSink>, bluetoothAllowed: Boolean): List<CallOutputOption> {
        val result = ArrayList<CallOutputOption>()
        result += CallOutputOption(CallOutput.SPEAKER, "Phone speaker")
        if (sinks.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) {
            result += CallOutputOption(CallOutput.EARPIECE, "Earpiece")
        }
        sinks.filter { it.type in WIRED_CALL_OUTPUT_TYPES }.forEach { sink ->
            result += CallOutputOption(CallOutput.WIRED_HEADSET, sink.name.ifBlank { "Headset" }, sink.id)
        }
        if (bluetoothAllowed) {
            sinks.filter { it.type in BLUETOOTH_CALL_OUTPUT_TYPES }.forEach { sink ->
                result += CallOutputOption(CallOutput.BLUETOOTH, sink.name.ifBlank { "Bluetooth headset" }, sink.id)
            }
        }
        return result.distinctBy { it.output to it.name }
    }

    /** A headset (wired, or Bluetooth when allowed) is present: the Speaker button opens the sheet. */
    fun opensSheet(options: List<CallOutputOption>): Boolean =
        options.any { it.output == CallOutput.WIRED_HEADSET || it.output == CallOutput.BLUETOOTH }

    /** The button has something to do: more than one output exists. A speaker-only tablet leaves it off. */
    fun buttonEnabled(options: List<CallOutputOption>): Boolean = options.size > 1

    /** The sheet row that matches the route in use (the first of its kind), or null. */
    fun selected(options: List<CallOutputOption>, current: CallOutput): CallOutputOption? =
        options.firstOrNull { it.output == current }
}

/**
 * Experimental (default OFF, `call_bluetooth_output`): lists Bluetooth audio devices in the output sheet and
 * lets the user pick one. Off keeps the long-standing rule that call audio never goes to a Bluetooth
 * headset, because SCO/LE Audio shares airtime with the Bluetooth link that carries the call itself
 * (PLAN "Bluetooth headsets"). Read when a call starts.
 */
internal object CallBluetoothOutputFeature {
    const val EXTRA_ENABLED = "test_call_bluetooth_output"
    const val PREF_ENABLED = "feature_call_bluetooth_output"

    val spec = FeatureSpec(
        id = "call_bluetooth_output",
        prefKey = PREF_ENABLED,
        defaultEnabled = false,
        benchmarkExtra = EXTRA_ENABLED,
        titleRes = R.string.feat_call_bluetooth_output,
        descriptionRes = R.string.feat_call_bluetooth_output_description,
        iconRes = R.drawable.ic_headphones,
        group = ViewerFeatures.GROUP,
        phoneTest = "T-OUT1",
    )

    val enabled: Boolean get() = FeatureCatalog.isEnabled(spec.id)
}
