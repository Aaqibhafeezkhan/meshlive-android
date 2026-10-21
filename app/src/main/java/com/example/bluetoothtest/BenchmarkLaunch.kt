package com.example.bluetoothtest

/**
 * Whether a launch is a runner's benchmark launch: any `test_` extra makes it one, and then features read only their
 * extras, onboarding is skipped and the one-time "Check for updates when online?" question is never asked.
 *
 * DEBUG `test_user_launch=true` keeps the extras (tunables, `test_update_*` stand-ins) but makes the launch behave as a
 * person's (emulator crawl 2026-10-21: `test_update_pin_own_signer` already made a launch a benchmark launch, so the real
 * ask -> Yes path could not be driven on a debug build). Release builds ignore it.
 */
internal object BenchmarkLaunch {
    const val EXTRA_USER_LAUNCH = "test_user_launch"

    fun fromExtras(keys: Set<String>, userLaunch: Boolean): Boolean = !userLaunch && keys.any { it.startsWith("test_") }
}
