package com.example.bluetoothtest

/** Why Go live (or Watch, or Relay) cannot start, when the reason is one the user can act on or must know. */
internal enum class LiveStartBlock { NONE, NO_BLUETOOTH, PERMISSIONS, BLUETOOTH_OFF }

/** The notice's button. */
internal enum class LiveStartAction { NONE, ASK, OPEN_SETTINGS, TURN_ON_BLUETOOTH }

/**
 * The visible reason on the camera screen (Android 16 emulator crawl, 2026-10-21): with the microphone or camera denied,
 * Go live was only greyed out and the "access needed" text went to the hidden status line. The camera screen now shows the
 * missing access with a way to fix it, and a Bluetooth that is off (or missing) as a separate reason. Other holds (no
 * destination yet, heat, a session still running) keep their own indicators and show no notice.
 */
internal object LiveStartGate {
    fun block(sessionActive: Boolean, transportReady: Boolean, permissionsReady: Boolean, bluetoothReady: Boolean): LiveStartBlock =
        when {
            sessionActive -> LiveStartBlock.NONE
            // A phone without Bluetooth LE cannot go live whatever access it gives.
            !transportReady -> LiveStartBlock.NO_BLUETOOTH
            !permissionsReady -> LiveStartBlock.PERMISSIONS
            !bluetoothReady -> LiveStartBlock.BLUETOOTH_OFF
            else -> LiveStartBlock.NONE
        }

    /**
     * Missing access: ask again while the system still shows its prompt (not asked yet in this process, or a rationale is
     * due after one refusal); once it no longer does (denied for good), only the app's settings page can grant it.
     */
    fun action(block: LiveStartBlock, askedThisProcess: Boolean, rationaleDue: Boolean): LiveStartAction = when (block) {
        LiveStartBlock.NONE, LiveStartBlock.NO_BLUETOOTH -> LiveStartAction.NONE
        LiveStartBlock.PERMISSIONS -> if (!askedThisProcess || rationaleDue) LiveStartAction.ASK else LiveStartAction.OPEN_SETTINGS
        LiveStartBlock.BLUETOOTH_OFF -> LiveStartAction.TURN_ON_BLUETOOTH
    }
}
