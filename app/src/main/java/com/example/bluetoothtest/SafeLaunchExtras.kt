package com.example.bluetoothtest

import android.content.Intent
import android.os.Bundle

/**
 * MainActivity is exported (launcher), so any app can start it with extras. An extra of a Parcelable class MeshLive
 * does not have makes the first extras read throw (BadParcelableException, a RuntimeException) and would crash the
 * app on demand (platform audit P1, 2026-10-21). Unparcel once up front; on failure the launch continues with no
 * extras, which is a plain launch.
 */
internal object SafeLaunchExtras {
    /** True when reading the extras throws; [probe] unparcels them (production: `intent.extras?.keySet()`). */
    fun unreadable(probe: () -> Unit): Boolean = try {
        probe()
        false
    } catch (e: RuntimeException) {
        true
    }

    /** The intent to use: [intent] itself, or a copy without extras when they cannot be read. */
    fun sanitize(intent: Intent): Intent =
        if (unreadable { intent.extras?.keySet() }) Intent(intent).replaceExtras(null as Bundle?) else intent
}
