package com.example.bluetoothtest

/**
 * Always reachable for calls (2026-10-10, first iPhone -> Android test). Before this the
 * call-availability advertisement and its GATT/CoC listener ran only while the Call (people) screen
 * was open, so on Home `dumpsys bluetooth_manager` showed "Advertisers: []" and no caller could
 * find the phone. On: the private-call endpoint (advertising + listener, no scanning) also runs on
 * Home and Settings-from-Home while the app is visible, and in the background while the session
 * foreground service (connectedDevice type) is held. Off: exactly the old behaviour.
 *
 * Normal launches: on. A DEBUG benchmark launch (any `test_*` extra) requests its role exactly,
 * so it is on only with an explicit `test_always_reachable=true`; existing cases do not change.
 */
object CallReachabilityFeature {
    const val EXTRA = "test_always_reachable"

    @Volatile var enabled = true

    /** Normal launches follow the Settings switch "Reachable for calls" ([stored], default on). */
    fun resolve(benchmarkLaunch: Boolean, stored: () -> Boolean = { true }, extra: (String) -> Boolean): Boolean =
        if (benchmarkLaunch) extra(EXTRA) else stored()
}

/** Where the activity is, as far as call reachability cares. */
internal enum class ReachabilityScreen {
    /** Home, or Settings opened from Home. */
    HOME,
    /** The Call (people) screen: advertising + listening + scanning, as before. */
    CALL_BROWSER,
    /** A call is ringing or connected. */
    CALL,
    /** Camera / Go live screen (stream sender or relay): the stream advertiser owns the radio. */
    LIVE,
    /** Watch list or playback: the viewer and its forwarder own the radio. */
    WATCH,
}

/** Which advertising role the phone holds. */
internal enum class AdvertisingRole { NONE, CALL_AVAILABLE, IN_CALL, LIVE_STREAM, WATCHING }

internal data class ReachabilityInputs(
    val featureEnabled: Boolean,
    val screen: ReachabilityScreen,
    /** The activity is started (visible); a foreground service may be started now. */
    val foreground: Boolean,
    /** The session foreground service currently holds the connectedDevice type. */
    val foregroundServiceHeld: Boolean,
    /** Bluetooth on and the call permissions granted. */
    val bluetoothReady: Boolean,
    /** Battery below [LOW_BATTERY_PERCENT]. Informational: never turns reachability off. */
    val batteryLow: Boolean = false,
    /** Battery Saver on. Informational: never turns reachability off. */
    val batterySaver: Boolean = false,
)

internal data class ReachabilityPlan(
    val role: AdvertisingRole,
    /** The private-call endpoint (call advertisement + GATT/CoC listener) should run. */
    val endpoint: Boolean,
    /** Scan for other phones (the people list). Only the Call screen in the foreground scans. */
    val discoveryScan: Boolean,
    /** This plan (not the outcome) makes the phone callable; the home line also needs success. */
    val reachable: Boolean,
    val reason: String,
) {
    fun logLine(): String = "role=${role.name} | endpoint=$endpoint | scan=$discoveryScan | reachable=$reachable | reason=$reason"
}

/**
 * The advertising state machine. Pure, so every screen/background/battery combination is unit
 * tested. Budget notes:
 * - Advertising sets: Home adds one call-availability set (legacy, as on the Call screen) and
 *   nothing on Live/Watch, where the stream offer and the peer-labels title set (which keeps one
 *   free set of headroom, TitleAdvertiser.MIN_FREE_SETS) need the controller's sets; the endpoint
 *   is closed before those start (showStreamScreen/showReceiverBrowser).
 * - Scan quota (ScanStartGate, 5 starts / 30 s): reachability never scans, so Home costs no start.
 * - iPhone compact discovery changes only scanning; Android still advertises the ordinary
 *   announcement, which iPhones read.
 * - Battery (design decision 2026-10-10): relay helping pauses below 20 % or in Battery Saver, but
 *   incoming-call reachability is core and stays on.
 * - Android 12+ background: in the background the endpoint runs only while the connectedDevice
 *   foreground service is held (started while visible); without it the process can be frozen, so
 *   the endpoint stops instead of claiming a reachability it cannot deliver.
 */
internal object CallReachability {
    const val LOW_BATTERY_PERCENT = 20

    fun plan(inputs: ReachabilityInputs): ReachabilityPlan {
        val batteryNote = when {
            inputs.batteryLow && inputs.batterySaver -> " (low battery + saver: calls stay on, relay helping pauses)"
            inputs.batteryLow -> " (low battery: calls stay on, relay helping pauses)"
            inputs.batterySaver -> " (battery saver: calls stay on, relay helping pauses)"
            else -> ""
        }
        return when (inputs.screen) {
            ReachabilityScreen.CALL -> ReachabilityPlan(AdvertisingRole.IN_CALL, true, false, false, "in_call")
            // Unchanged Call-screen behaviour, independent of the flag: the screen manages its own
            // endpoint and the foreground service it starts on success.
            ReachabilityScreen.CALL_BROWSER -> ReachabilityPlan(
                AdvertisingRole.CALL_AVAILABLE,
                endpoint = inputs.bluetoothReady,
                discoveryScan = inputs.bluetoothReady && inputs.foreground,
                reachable = inputs.bluetoothReady,
                reason = "call_screen$batteryNote",
            )
            ReachabilityScreen.LIVE -> ReachabilityPlan(AdvertisingRole.LIVE_STREAM, false, false, false, "stream_owns_advertising")
            ReachabilityScreen.WATCH -> ReachabilityPlan(AdvertisingRole.WATCHING, false, false, false, "viewer_owns_advertising")
            ReachabilityScreen.HOME -> when {
                !inputs.featureEnabled -> ReachabilityPlan(AdvertisingRole.NONE, false, false, false, "flag_off")
                !inputs.bluetoothReady -> ReachabilityPlan(AdvertisingRole.NONE, false, false, false, "bluetooth_or_permissions")
                !inputs.foreground && !inputs.foregroundServiceHeld ->
                    ReachabilityPlan(AdvertisingRole.NONE, false, false, false, "background_without_foreground_service")
                else -> ReachabilityPlan(
                    AdvertisingRole.CALL_AVAILABLE,
                    endpoint = true,
                    discoveryScan = false,
                    reachable = true,
                    reason = (if (inputs.foreground) "home" else "home_background_service") + batteryNote,
                )
            }
        }
    }
}

/** The home status line's reachability part. */
internal enum class HomeReachability { REACHABLE, STARTING, NOT_REACHABLE }

internal fun homeReachability(plan: ReachabilityPlan, endpointAvailable: Boolean, endpointStarting: Boolean): HomeReachability =
    when {
        !plan.reachable -> HomeReachability.NOT_REACHABLE
        endpointAvailable -> HomeReachability.REACHABLE
        endpointStarting -> HomeReachability.STARTING
        else -> HomeReachability.NOT_REACHABLE
    }
