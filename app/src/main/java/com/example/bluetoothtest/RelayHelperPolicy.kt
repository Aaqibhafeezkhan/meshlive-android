package com.example.bluetoothtest

/** Why the relay-helper is not helping right now (shown as the Settings status line). */
internal enum class HelperPause(val label: String) {
    NONE("running"),
    FEATURE_OFF("experiment off"),
    HELP_OFF("helping switched off"),
    ROTATING_IDS("rotating IDs on"),
    CLASSIC_TRANSPORT("Classic calls"),
    BLUETOOTH("Bluetooth or permissions"),
    NOT_CALL_READY("not reachable for calls"),
    BATTERY("battery low"),
    BATTERY_SAVER("battery saver"),
    THERMAL("phone hot"),
    OWN_ACTIVITY("own call or stream"),
}

internal enum class HelperScan { BALANCED, LOW_POWER }

internal data class RelayHelperInputs(
    val featureOn: Boolean,
    val helpOn: Boolean,
    val rotatingIdsOn: Boolean,
    /** Rotating IDs + relayed calls work together (reach pages version 6); false restores the old exclusion. */
    val rotatingCompat: Boolean = false,
    /** The call transport is LE (CoC or GATT); Classic is not relayed on demand. */
    val leTransport: Boolean,
    val bluetoothReady: Boolean,
    /** The call endpoint is available (reachable for calls) and not in a call, ringing or exchange. */
    val callReady: Boolean,
    /** -1 when unknown (never pauses). */
    val batteryPercent: Int,
    val charging: Boolean,
    val batterySaver: Boolean,
    val thermalSevere: Boolean,
    /** The user's own call, ringing call, stream, viewing or message exchange. */
    val ownActivity: Boolean,
    val screenOn: Boolean,
)

internal data class RelayHelperPlan(
    val pause: HelperPause,
    /** The table scan, the reach pages and acceptance of a new SETUP run. */
    val helping: Boolean,
    val scan: HelperScan,
) {
    val paused: Boolean get() = pause != HelperPause.NONE
}

/**
 * When a phone helps with other people's calls (DECISIONS Design C, C5; design decision 2: on by default while
 * the app runs, paused below 20 % battery or in Battery Saver). Pure, so every combination is tested.
 * A relayed call already running is not cut by a pause; only [preempts] ends it.
 */
internal object RelayHelperPolicy {
    fun plan(inputs: RelayHelperInputs): RelayHelperPlan {
        val pause = when {
            !inputs.featureOn -> HelperPause.FEATURE_OFF
            !inputs.helpOn -> HelperPause.HELP_OFF
            inputs.rotatingIdsOn && !inputs.rotatingCompat -> HelperPause.ROTATING_IDS
            !inputs.leTransport -> HelperPause.CLASSIC_TRANSPORT
            !inputs.bluetoothReady -> HelperPause.BLUETOOTH
            inputs.ownActivity -> HelperPause.OWN_ACTIVITY
            !inputs.callReady -> HelperPause.NOT_CALL_READY
            inputs.batterySaver -> HelperPause.BATTERY_SAVER
            relayAutoPaused(inputs.batteryPercent, inputs.charging, batterySaver = false) -> HelperPause.BATTERY
            inputs.thermalSevere -> HelperPause.THERMAL
            else -> HelperPause.NONE
        }
        val scan = if (inputs.screenOn || inputs.charging) HelperScan.BALANCED else HelperScan.LOW_POWER
        return RelayHelperPlan(pause, helping = pause == HelperPause.NONE, scan = scan)
    }

    /** The user's own activity ends a relay in progress at once (PLAN C5). */
    fun preempts(relaying: Boolean, ownActivityStarting: Boolean): Boolean = relaying && ownActivityStarting

    /** Settings status line. */
    fun statusLine(plan: RelayHelperPlan, batteryPercent: Int): String = when (plan.pause) {
        HelperPause.NONE -> "Helping: on"
        HelperPause.BATTERY -> "Helping: paused, battery $batteryPercent %"
        HelperPause.FEATURE_OFF, HelperPause.HELP_OFF -> "Helping: off"
        else -> "Helping: paused (${plan.pause.label})"
    }
}
