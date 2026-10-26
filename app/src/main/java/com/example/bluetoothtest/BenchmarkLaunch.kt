package com.example.bluetoothtest

/**
 * Whether a launch is a runner's benchmark launch: any `test_` extra makes it one, and then features read only their
 * extras, onboarding is skipped and the one-time "Check for updates when online?" question is never asked.
 *
 * DEBUG `test_user_launch=true` keeps the extras (tunables, `test_update_*` stand-ins) but makes the launch behave as a
 * person's (emulator crawl 2026-10-21: `test_update_pin_own_signer` already made a launch a benchmark launch, so the real
 * ask -> Yes path could not be driven on a debug build). Release builds ignore every `test_` extra, this one included.
 */
internal object BenchmarkLaunch {
    const val EXTRA_USER_LAUNCH = "test_user_launch"

    /** DEBUG offline benchmark (MainActivity): automation too, so onboarding is skipped, but features keep their settings. */
    const val EXTRA_OFFLINE_BENCHMARK = "offline_benchmark"

    /**
     * The one benchmark-launch predicate. [debug] is inside it (switch audit 2026-10-27): MainActivity is an exported
     * launcher activity, and before this a release build treated any `test_` extra from another app as a benchmark launch
     * (onboarding, the go-live disclosure and the End-live confirmation skipped, relay battery pause and Recent calls off).
     * Release builds (`debug=false`) never see a benchmark launch, whatever the extras.
     */
    fun fromExtras(keys: Set<String>, userLaunch: Boolean, debug: Boolean = BuildConfig.DEBUG): Boolean =
        debug && !userLaunch && keys.any { it.startsWith("test_") }

    /** A launch that must not show onboarding later: a benchmark launch, or the DEBUG offline benchmark. */
    fun skipsOnboarding(keys: Set<String>, userLaunch: Boolean, debug: Boolean = BuildConfig.DEBUG): Boolean =
        fromExtras(keys, userLaunch, debug) || (debug && EXTRA_OFFLINE_BENCHMARK in keys)
}
