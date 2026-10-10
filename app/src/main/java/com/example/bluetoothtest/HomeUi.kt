package com.example.bluetoothtest

/** What the home screen's readiness card asks of the user. */
internal enum class HomeReadiness { UNSUPPORTED, PERMISSIONS_NEEDED, BLUETOOTH_OFF, READY }

/**
 * Readiness for the home card. Permissions come first because Android 12+ cannot even report or
 * request Bluetooth state usefully without Nearby devices access.
 */
internal fun homeReadiness(hasAdapter: Boolean, permissionsGranted: Boolean, bluetoothOn: Boolean): HomeReadiness =
    when {
        !hasAdapter -> HomeReadiness.UNSUPPORTED
        !permissionsGranted -> HomeReadiness.PERMISSIONS_NEEDED
        !bluetoothOn -> HomeReadiness.BLUETOOTH_OFF
        else -> HomeReadiness.READY
    }

/**
 * Number of relaying phones between this phone and an advertised peer or stream, from the
 * advertisement's route cost (0 = in direct radio range). Null for an out-of-range value.
 */
internal fun advertisedRelayCount(routeCost: Int): Int? = routeCost.takeIf { it in 0..MAX_DISPLAYED_ROUTE_COST }

private const val MAX_DISPLAYED_ROUTE_COST = 16

/** How a call reached the other person, for the in-call security chip. */
internal sealed class CallRoute {
    object Direct : CallRoute()
    /** Relayed; [relays] is null when this phone does not know how many (an incoming call). */
    data class Relayed(val relays: Int?) : CallRoute()
}

/**
 * Route of an authenticated call: relayed when the adjacent link's peer is not the end-to-end peer.
 * [outgoingRelays] is the route cost of the advertisement this phone dialled, when it dialled.
 */
internal fun callRoute(adjacentPeerHex: String?, endToEndPeerHex: String?, outgoingRelays: Int?): CallRoute? {
    if (adjacentPeerHex.isNullOrEmpty() || endToEndPeerHex.isNullOrEmpty()) return null
    val relayed = !endToEndPeerHex.startsWith(adjacentPeerHex, ignoreCase = true) &&
        !adjacentPeerHex.startsWith(endToEndPeerHex, ignoreCase = true)
    return if (!relayed) CallRoute.Direct else CallRoute.Relayed(outgoingRelays?.takeIf { it > 0 })
}

/** The live/watch status chip's state on the shared video screen. */
internal enum class LiveChipState { NOT_LIVE, LIVE, PAUSED, WATCH_WAITING, WATCHING, RELAY_WAITING, RELAYING }

internal fun liveChipState(
    role: String,
    streaming: Boolean,
    paused: Boolean,
    connectedCount: Int,
): LiveChipState = when (role) {
    "SEND" -> when {
        !streaming -> LiveChipState.NOT_LIVE
        paused -> LiveChipState.PAUSED
        else -> LiveChipState.LIVE
    }
    "RECEIVE" -> if (connectedCount > 0) LiveChipState.WATCHING else LiveChipState.WATCH_WAITING
    else -> if (connectedCount > 0) LiveChipState.RELAYING else LiveChipState.RELAY_WAITING
}

/** Panic gesture: [count] taps, each within [windowMs] of the previous one. */
internal class MultiTapDetector(private val count: Int = 3, private val windowMs: Long = 600L) {
    private var taps = 0
    private var lastMs = Long.MIN_VALUE

    /** Records a tap at [nowMs]; true when this tap completes the sequence (and resets it). */
    fun onTap(nowMs: Long): Boolean {
        taps = if (lastMs != Long.MIN_VALUE && nowMs - lastMs <= windowMs) taps + 1 else 1
        lastMs = nowMs
        if (taps >= count) {
            taps = 0
            lastMs = Long.MIN_VALUE
            return true
        }
        return false
    }
}

/**
 * First-run onboarding is shown only for a plain user launch: never when the launch carries any
 * extras (benchmark and automation intents always do) or when the activity is being recreated.
 */
internal fun shouldShowOnboarding(onboardingDone: Boolean, launchHasExtras: Boolean, recreated: Boolean): Boolean =
    !onboardingDone && !launchHasExtras && !recreated

/**
 * A stable colour per identity (from its node-ID prefix), so the same person keeps the same
 * avatar colour in every list. Returns an opaque ARGB colour with fixed saturation/brightness.
 */
internal fun identityColor(nodePrefixHex: String): Int {
    val hash = nodePrefixHex.lowercase().fold(17) { acc, c -> acc * 31 + c.code }
    val hue = Math.floorMod(hash, 360).toFloat()
    return hsvToArgb(hue, 0.55f, 0.80f)
}

private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = v - c
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(value: Float) = ((value + m) * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

private val TECHNICAL_STATUS = Regex(
    "\\b(PHY|kbps|GATT|CoC|L2CAP|RFCOMM|MTU|PSM|BLE|ACL|HCI|SDU|codec)\\b|link to ",
    RegexOption.IGNORE_CASE,
)

/**
 * Status text as shown on screen: the same message the app logs, or null when it is a technical
 * diagnostic (radio, transport, codec) that belongs in the log and Link health, not the main UI.
 * The log line itself is never changed.
 */
internal fun displayStatus(message: String): String? =
    message.trim().takeIf { it.isNotEmpty() && !TECHNICAL_STATUS.containsMatchIn(it) }

/** The ACTIVE call status duplicates the security chip, so its bubble is hidden. */
internal const val ACTIVE_CALL_STATUS = "Encrypted private call"

/** Settings preference (app preferences file): the home-title triple-tap starts an emergency wipe. */
internal const val PREF_PANIC_GESTURE = "panic_wipe_gesture"

/** What the user types to confirm an emergency wipe from Settings (EmergencyWipe.CONFIRMATION_TEXT). */
internal const val WIPE_CONFIRMATION_TEXT = "WIPE"

/** "Help relay" default (design decision): on for normal use, off for benchmark launches. */
internal fun relayDefaultOn(benchmarkLaunch: Boolean): Boolean = !benchmarkLaunch

/** Relaying pauses below 20 % battery when not charging, or in Battery Saver. Unknown level never pauses. */
internal fun relayAutoPaused(batteryPercent: Int, charging: Boolean, batterySaver: Boolean): Boolean =
    batterySaver || (!charging && batteryPercent in 0 until RELAY_MIN_BATTERY_PERCENT)

internal const val RELAY_MIN_BATTERY_PERCENT = 20

/** Settings: "Reachable for calls" (always-reachable listener), on by default; app preferences. */
internal const val PREF_REACHABLE_FOR_CALLS = "reachable_for_calls"

/** UI preferences: the "hold Mute to talk" tip was shown once. */
internal const val PREF_HOLD_MUTE_TIP_SHOWN = "tip_hold_mute_shown"

/** The single device-health line for video screens, or null when nothing needs saying. */
internal fun videoHealthNotice(thermalReductionActive: Boolean, batteryPercent: Int, charging: Boolean): Int? = when {
    thermalReductionActive -> R.string.video_notice_hot
    !charging && batteryPercent in 0..RELAY_MIN_BATTERY_PERCENT -> R.string.video_notice_battery
    else -> null
}

/** Recent rows shown on Home (all actions must stay above the fold; RecentCalls keeps 5). */
internal const val HOME_RECENT_ROWS = 3

/** How long a call back waits for the person to appear nearby. */
internal const val CALL_BACK_WAIT_MS = 20_000L

/** Technical status lines kept per call for Link health. */
internal const val LINK_DETAIL_LINES = 6

/** Undo window before the panic gesture wipes. */
internal const val PANIC_WIPE_DELAY_MS = 3_000L
