package com.example.bluetoothtest

/**
 * "Bluetooth needs a restart to find nearby people" on the Nearby list (default on; DEBUG control arm
 * `test_scan_restart_hint=false`, which keeps the ordinary "No one found yet" empty state).
 *
 * 2026-10-20 (win-userflow qw-packaged redmi-failfast-3, Redmi Note 9S, Android 11, MIUI): 31 of the stack's 32 GATT app
 * registrations (`GATT_MAX_APPS 32`, shared by every app's scanners, GATT clients and GATT servers; a scanner registers
 * through `BTA_GATTC_AppRegister` -> `GATT_Register`) were held outside this process, most likely leaked by earlier
 * processes (61 unclean exits counted). This app's call-listener GATT server and its Nearby scanner then took turns with
 * the one free slot: the scanner's registration failed with `MAX client reached: 32`, `onScannerRegistered` status 133
 * and `onScanFailed` code 2, on every retry. The list stayed on "No one found yet ... The list updates by itself", which
 * was untrue. Nothing an app may call frees another registration (an app targeting API 33+ cannot switch Bluetooth
 * off and on either), so after [PoolExhaustionPolicy.REGISTRATION_THRESHOLD] scanner back-off steps in a row (about 7 s)
 * the empty state says so plainly and opens the Bluetooth settings. The reliability card's "Bluetooth may be stuck"
 * stays as before.
 */
internal object ScanRestartHint {
    /** Default on; only a DEBUG launch can turn it off (`test_scan_restart_hint=false`). */
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** True while the Nearby scanner has been refused a registration often enough, recently, with no success since. */
    fun due(policy: PoolExhaustionPolicy = GattPoolBackoff.policy, on: Boolean = enabled): Boolean =
        on && policy.registrationGuidanceDue(PoolSource.SCANNER)
}
