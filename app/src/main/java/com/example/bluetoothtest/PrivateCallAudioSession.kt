package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.Closeable

/** Where call audio plays. Bluetooth headsets are deliberately absent (see [callOutputFor]). */
internal enum class CallOutput(val label: String) {
    SPEAKER("Speaker"),
    EARPIECE("Earpiece"),
    WIRED_HEADSET("Headset"),
}

/** Wired or USB headsets/headphones: they use no radio, so a call may always prefer them. */
internal val WIRED_CALL_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

/**
 * The one call-output decision (JVM-tested). A plugged wired/USB headset replaces the earpiece, and
 * also the loudspeaker unless the user explicitly asked for the loudspeaker: on Android 10/11
 * setSpeakerphoneOn(false) already leaves the choice to the platform, which picks the headset, but on
 * Android 12+ setCommunicationDevice(earpiece) overrode a plugged headset. Bluetooth SCO/LE Audio
 * headsets are never chosen: they take airtime from the same radio that carries the call's own
 * Bluetooth media link (PLAN "Bluetooth headsets").
 */
internal fun callOutputFor(
    speakerRequested: Boolean,
    explicitSpeakerChoice: Boolean,
    wiredHeadsetPresent: Boolean,
    earpiecePresent: Boolean,
): CallOutput = when {
    wiredHeadsetPresent && !(speakerRequested && explicitSpeakerChoice) -> CallOutput.WIRED_HEADSET
    speakerRequested || !earpiecePresent -> CallOutput.SPEAKER
    else -> CallOutput.EARPIECE
}

/**
 * Call audio mode and route. [startOnSpeaker] false starts on the handset earpiece when the phone has
 * one (experimental voice calls); video calls keep the loudspeaker default. A plugged wired headset
 * wins at start and when it is plugged in during the call ([callOutputFor]).
 *
 * Needs MODIFY_AUDIO_SETTINGS: without it AOSP AudioService.setMode() and setSpeakerphoneOn() return
 * silently (checkAudioSettingsPermission, Android 10 through current), so before 2026-10-09 every
 * call ran in MODE_NORMAL and Android 10/11 phones (A15s, ASUS) could not leave the earpiece. The
 * `BENCHMARK call_audio_route` line records the mode the platform actually reports.
 */
internal class PrivateCallAudioSession(
    context: Context,
    private val startOnSpeaker: Boolean = true,
    private val onRouteChanged: (() -> Unit)? = null,
    /** Non-null: the call holds audio focus while open ([CallAudioFocus]); null: no focus request (before 2026-10-10). */
    onFocusAction: ((FocusAction) -> Unit)? = null,
) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val previousMode = audioManager.mode
    @Suppress("DEPRECATION")
    private val previousSpeakerphone = audioManager.isSpeakerphoneOn

    /** True while call audio plays from the loudspeaker (the default), false for earpiece/headset. */
    var speakerOn = true
        private set

    var output = CallOutput.SPEAKER
        private set

    private var explicitSpeakerChoice = false

    /** Phones without a handset earpiece (for example tablets) keep the loudspeaker. */
    val earpieceAvailable: Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

    /** Whether the route button can leave the loudspeaker (earpiece or a plugged headset). */
    val quietRouteAvailable: Boolean
        get() = earpieceAvailable || wiredHeadsetPresent()

    /**
     * Earpiece calls only: while call audio plays from the earpiece the phone is at the ear, so the
     * proximity sensor turns the screen off and a cheek cannot press End or push-to-talk. Held for
     * exactly as long as the earpiece route is active; released on speaker/headset and in [close].
     */
    private val proximity = ProximityScreenOff(PowerManagerProximityLock.create(context))

    private val audioFocus = onFocusAction?.let { CallAudioFocus(context, it) }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (output != CallOutput.WIRED_HEADSET &&
                addedDevices.any { it.isSink && it.type in WIRED_CALL_OUTPUT_TYPES }
            ) {
                explicitSpeakerChoice = false
                applyRoute(speakerRequested = false, explicit = false, event = "headset_added")
                onRouteChanged?.invoke()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (output == CallOutput.WIRED_HEADSET &&
                removedDevices.any { it.isSink && it.type in WIRED_CALL_OUTPUT_TYPES } &&
                !wiredHeadsetPresent()
            ) {
                applyRoute(speakerRequested = startOnSpeaker, explicit = false, event = "headset_removed")
                onRouteChanged?.invoke()
            }
        }
    }

    init {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioFocus?.request()
        applyRoute(speakerRequested = startOnSpeaker, explicit = false, event = "start")
        // The callback's initial report lists devices already routed above; it changes nothing then.
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, null) }
    }

    private fun wiredHeadsetPresent(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in WIRED_CALL_OUTPUT_TYPES }

    /**
     * Routes call audio to the loudspeaker, or to the quiet route (a plugged headset, else the
     * handset earpiece). The earpiece is the quiet choice: Android's lowest voice-call volume step is
     * still loud on the speaker. [explicit] marks the user's own choice (a layout change is not).
     * Returns true while the loudspeaker is in use.
     */
    fun setSpeakerOn(speaker: Boolean, explicit: Boolean = true): Boolean {
        if (!speaker && !quietRouteAvailable) return speakerOn
        applyRoute(speaker, explicit, if (explicit) "user" else "layout")
        return speakerOn
    }

    @SuppressLint("MissingPermission", "NewApi")
    @Suppress("DEPRECATION")
    private fun applyRoute(speakerRequested: Boolean, explicit: Boolean, event: String) {
        if (explicit) explicitSpeakerChoice = speakerRequested
        val wanted = callOutputFor(
            speakerRequested,
            explicitSpeakerChoice,
            wiredHeadsetPresent(),
            earpieceAvailable,
        )
        val applied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val target = when (wanted) {
                CallOutput.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                CallOutput.EARPIECE -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                CallOutput.WIRED_HEADSET -> devices.firstOrNull { it.type in WIRED_CALL_OUTPUT_TYPES }
            }
            if (target != null && audioManager.setCommunicationDevice(target)) wanted else currentOutputS()
        } else {
            // FORCE_SPEAKER, or FORCE_NONE, where the platform prefers a wired headset to the earpiece.
            audioManager.isSpeakerphoneOn = wanted == CallOutput.SPEAKER
            when {
                audioManager.isSpeakerphoneOn -> CallOutput.SPEAKER
                wanted == CallOutput.SPEAKER -> if (wiredHeadsetPresent()) CallOutput.WIRED_HEADSET else CallOutput.EARPIECE
                else -> wanted
            }
        }
        output = applied
        speakerOn = applied == CallOutput.SPEAKER
        proximity.onSpeakerChanged(applied != CallOutput.EARPIECE)
        logRoute(event, wanted)
    }

    @SuppressLint("NewApi")
    private fun currentOutputS(): CallOutput {
        val type = audioManager.communicationDevice?.type ?: return CallOutput.SPEAKER
        return when {
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallOutput.EARPIECE
            type in WIRED_CALL_OUTPUT_TYPES -> CallOutput.WIRED_HEADSET
            else -> CallOutput.SPEAKER
        }
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun logRoute(event: String, requested: CallOutput) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type?.toString() ?: "none"
        } else {
            "n/a"
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK call_audio_route: event=$event | requested=${requested.name} | output=${output.name} | " +
                "mode=${audioManager.mode} | modeInCommunication=${audioManager.mode == AudioManager.MODE_IN_COMMUNICATION} | " +
                "communicationDevice=$device | speakerphone=${audioManager.isSpeakerphoneOn} | sdk=${Build.VERSION.SDK_INT}",
        )
    }

    @Suppress("DEPRECATION")
    override fun close() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        audioFocus?.abandon()
        proximity.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        audioManager.isSpeakerphoneOn = previousSpeakerphone
        audioManager.mode = previousMode
    }
}

/** A proximity wake lock the call can hold and release; separated so the policy is JVM-testable. */
internal interface ProximityLock {
    val held: Boolean
    fun acquire()
    fun release()
}

/**
 * Holds the proximity lock while call audio is on the earpiece and nowhere else. A null lock (the
 * phone has no proximity sensor, or the platform refuses the lock) leaves the screen behaviour
 * exactly as before.
 */
internal class ProximityScreenOff(private val lock: ProximityLock?) {
    /** True while the screen-off lock is held. */
    val active: Boolean get() = lock?.held == true

    @Synchronized
    fun onSpeakerChanged(speakerOn: Boolean) {
        val lock = lock ?: return
        if (speakerOn) {
            if (lock.held) lock.release()
        } else if (!lock.held) {
            lock.acquire()
        }
    }

    @Synchronized
    fun close() {
        val lock = lock ?: return
        if (lock.held) lock.release()
    }
}

/** PROXIMITY_SCREEN_OFF_WAKE_LOCK, only where this phone supports it. */
internal class PowerManagerProximityLock private constructor(
    private val wakeLock: PowerManager.WakeLock,
) : ProximityLock {
    override val held: Boolean get() = wakeLock.isHeld

    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        runCatching {
            wakeLock.acquire()
            Log.i("BluetoothVideo", "Call proximity screen-off lock: held")
        }.onFailure { Log.w("BluetoothVideo", "Call proximity screen-off lock could not be held", it) }
    }

    override fun release() {
        runCatching {
            wakeLock.release()
            Log.i("BluetoothVideo", "Call proximity screen-off lock: released")
        }
    }

    companion object {
        fun create(context: Context): ProximityLock? {
            val power = context.getSystemService(PowerManager::class.java) ?: return null
            if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return null
            val wakeLock = power.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "BluetoothTest:CallProximity",
            ).apply { setReferenceCounted(false) }
            return PowerManagerProximityLock(wakeLock)
        }
    }
}
