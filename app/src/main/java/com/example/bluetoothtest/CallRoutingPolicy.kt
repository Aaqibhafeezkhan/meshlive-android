package com.example.bluetoothtest

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Pure call-routing rules (batch U-06; Signal FullSignalAudioManagerApi31 and Twilio AudioDeviceManager).
 * [PrivateCallAudioSession] gathers the platform facts and applies the decisions.
 *
 * The sequence the session follows is Signal's: (1) request audio focus, (2) set MODE_IN_COMMUNICATION,
 * (3) on API 31+ optionally wait for OnModeChangedListener (Experimental), (4) setCommunicationDevice and check its
 * boolean result, re-picking another candidate on false, (5) register AudioDeviceCallback and
 * OnCommunicationDeviceChangedListener to re-assert the choice (rate limited), (6) never offer a watch as a call
 * device, (7) on stop clearCommunicationDevice and restore the former mode and speakerphone state, then abandon focus.
 */
internal object CallRoutingPolicy {
    /** How long the Experimental mode-change wait blocks the caller. */
    const val MODE_CHANGE_WAIT_MS = 500L

    /** A smartwatch is listed as a Bluetooth device but is no call headset (Signal ignores " Watch" product names). */
    fun isWatchName(productName: String?): Boolean = productName?.contains(" watch", ignoreCase = true) == true

    /** Output-sheet rows without watches; wired and built-in outputs are never filtered. */
    fun withoutWatches(sinks: List<AudioSink>): List<AudioSink> =
        sinks.filterNot { it.type in BLUETOOTH_CALL_OUTPUT_TYPES && isWatchName(it.name) }

    /** A communication-device candidate: the platform's type, product name and id. */
    data class Candidate(val type: Int, val name: String, val id: Int)

    fun withoutWatchCandidates(candidates: List<Candidate>): List<Candidate> =
        candidates.filterNot { it.type in BLUETOOTH_CALL_OUTPUT_TYPES && isWatchName(it.name) }

    /**
     * Candidates for [wanted], best first: the explicitly chosen Bluetooth device (when any), then every device
     * of the output's types in the platform's order. Watches are removed.
     */
    fun candidatesFor(wanted: CallOutput, devices: List<Candidate>, explicitDeviceId: Int): List<Candidate> {
        val eligible = withoutWatchCandidates(devices)
        val matching = eligible.filter { it.type in typesFor(wanted) }
        if (wanted != CallOutput.BLUETOOTH || explicitDeviceId == CallOutputOption.NO_DEVICE) return matching
        return matching.sortedByDescending { it.id == explicitDeviceId }
    }

    fun typesFor(output: CallOutput): Set<Int> = when (output) {
        CallOutput.SPEAKER -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        CallOutput.EARPIECE -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        CallOutput.WIRED_HEADSET -> WIRED_CALL_OUTPUT_TYPES
        CallOutput.BLUETOOTH -> BLUETOOTH_CALL_OUTPUT_TYPES
    }

    /**
     * Tries [candidates] in order until [set] accepts one (setCommunicationDevice returns false when the device is
     * not available). Returns the accepted candidate and the number of rejected tries, or null when none worked.
     */
    fun <T> pickFirstAccepted(candidates: List<T>, set: (T) -> Boolean): Pair<T, Int>? {
        var rejected = 0
        for (candidate in candidates) {
            if (set(candidate)) return candidate to rejected
            rejected++
        }
        return null
    }

    /** True when the platform's current communication device type is not one of [expected]'s types (or is cleared). */
    fun needsReassert(currentType: Int?, expected: CallOutput): Boolean =
        currentType == null || currentType !in typesFor(expected)

    /**
     * The call's audio mode. Experimental (off): One UI with a USB headset plugged in uses MODE_NORMAL, the
     * Twilio workaround for Galaxy S9/S21 families whose USB headsets stayed silent in MODE_IN_COMMUNICATION
     * (Twilio applies it when the speaker is not wanted).
     * Matched by skin and the runtime USB headset, never by model; unverified on hardware.
     */
    fun callMode(usbHeadsetPresent: Boolean, skin: OemSkin, experimentOn: Boolean, speakerWanted: Boolean): Int =
        if (experimentOn && usbHeadsetPresent && !speakerWanted && skin == OemSkin.ONE_UI) AudioManager.MODE_NORMAL
        else AudioManager.MODE_IN_COMMUNICATION
}

/** At most [maxPerWindow] re-asserts per [windowMs], so the app never fights the platform in a loop. */
internal class RouteReassertLimiter(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
) {
    private val times = ArrayDeque<Long>()

    fun allow(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        if (times.size >= maxPerWindow) return false
        times.addLast(nowMs)
        return true
    }

    companion object {
        const val MAX_PER_WINDOW = 3
        const val WINDOW_MS = 10_000L
    }
}
