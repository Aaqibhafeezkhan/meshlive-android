package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * What decides whether Android lets this app see BLE advertisements right now (batch S-01).
 *
 * Fixed interface (docs: BACKLOG section 8): [ScanEnvironment.snapshot] with the fields below, consumed
 * by the foreground-service batch (the `location` service type is only started with Location on), the
 * reliability banner ("Turn on Location so Android lets this phone find nearby phones") and the scan
 * watchdog. [blockedReason] is null when nothing known blocks scanning.
 */
internal data class ScanEnvSnapshot(
    val sdk: Int,
    /** The user-facing Location switch (Settings > Location). */
    val locationEnabled: Boolean,
    val fineLocationGranted: Boolean,
    /** BLUETOOTH_SCAN declared with `neverForLocation` (Android 12+): the location gate does not apply. */
    val neverForLocation: Boolean,
    val blockedReason: String?,
)

internal object ScanEnvironment {
    const val REASON_LOCATION_OFF = "location_off"
    const val REASON_FINE_LOCATION_MISSING = "fine_location_not_granted"

    /** android.location.LocationManager.MODE_CHANGED_ACTION; a literal keeps the receiver independent of the SDK stub. */
    const val MODE_CHANGED_ACTION = "android.location.MODE_CHANGED"

    /** Settings.ACTION_LOCATION_SOURCE_SETTINGS, the screen the guidance opens. */
    const val LOCATION_SETTINGS_ACTION = "android.settings.LOCATION_SOURCE_SETTINGS"

    /** PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION (API 31). */
    private const val REQUESTED_PERMISSION_NEVER_FOR_LOCATION = 0x00010000

    /**
     * Android 10 and 11 (AOSP Utils.checkCallerHasFineLocation, android10-release Utils.java:368-385)
     * drop every scan result when the Location switch is off or the app lacks location permission, and
     * report nothing. Android 12+ with `neverForLocation` has no such gate. Pure truth table.
     */
    fun blockedReason(sdk: Int, locationOn: Boolean, hasFine: Boolean): String? = when {
        sdk >= Build.VERSION_CODES.S -> null
        !locationOn -> REASON_LOCATION_OFF
        !hasFine -> REASON_FINE_LOCATION_MISSING
        else -> null
    }

    /** The newest snapshot taken by [refresh]; null before the first one. */
    @Volatile var last: ScanEnvSnapshot? = null
        private set

    fun snapshot(context: Context): ScanEnvSnapshot {
        val app = context.applicationContext ?: context
        val sdk = Build.VERSION.SDK_INT
        val locationOn = runCatching {
            LocationManagerCompat.isLocationEnabled(app.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
        }.getOrDefault(true)
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return ScanEnvSnapshot(
            sdk = sdk,
            locationEnabled = locationOn,
            fineLocationGranted = fine,
            neverForLocation = neverForLocation(app, sdk),
            blockedReason = blockedReason(sdk, locationOn, fine),
        )
    }

    /** [snapshot], remembered for [last] and the synchronous scan-start log. */
    fun refresh(context: Context): ScanEnvSnapshot = snapshot(context).also { last = it }

    private fun neverForLocation(context: Context, sdk: Int): Boolean {
        if (sdk < Build.VERSION_CODES.S) return false
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val names = info.requestedPermissions ?: return@runCatching true
            val flags = info.requestedPermissionsFlags ?: return@runCatching true
            val index = names.indexOf(Manifest.permission.BLUETOOTH_SCAN)
            index < 0 || (flags[index] and REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0
        }.getOrDefault(true)
    }

    /** One line for the pre-flight log: `scan_env: locationOn=… sdk=…`. */
    fun line(snapshot: ScanEnvSnapshot?): String =
        if (snapshot == null) {
            "scan_env: locationOn=unknown | sdk=${Build.VERSION.SDK_INT} | fineLocation=unknown | neverForLocation=unknown | blocked=unknown"
        } else {
            "scan_env: locationOn=${snapshot.locationEnabled} | sdk=${snapshot.sdk} | " +
                "fineLocation=${snapshot.fineLocationGranted} | neverForLocation=${snapshot.neverForLocation} | " +
                "blocked=${snapshot.blockedReason ?: "none"}"
        }

    /** The registry entry that gates the guidance and the pre-flight log (off: `test_device_policy_off=<id>`). */
    const val POLICY_ID = "android10-11-location-switch-scan-gate"

    /** Whether the diagnostics/guidance run on this phone: SDK 29-30 and the entry not switched off. */
    fun guidanceActive(sdk: Int, switchedOff: Set<String>): Boolean =
        sdk <= Build.VERSION_CODES.R && !ScanPolicy.isOff(POLICY_ID, switchedOff)
}

/**
 * Re-checks the environment on a Location mode change (`MODE_CHANGED_ACTION`) and fires [onUnblocked]
 * when scanning was blocked and no longer is. The restart it asks for goes through the scan-start gate
 * (see ScanRenewal.restartAfterEnvironmentChange); this class only decides.
 */
internal class ScanEnvWatcher(
    private val provider: () -> ScanEnvSnapshot,
    private val onUnblocked: (ScanEnvSnapshot) -> Unit,
    private val onChanged: (ScanEnvSnapshot) -> Unit = {},
) {
    private var previous: ScanEnvSnapshot? = null

    /** Seeds the state without firing anything (the receiver is registered with the first snapshot). */
    fun seed(snapshot: ScanEnvSnapshot) {
        previous = snapshot
    }

    fun onModeChanged() {
        val now = provider()
        val before = previous
        previous = now
        if (before != null && before.blockedReason == now.blockedReason) return
        onChanged(now)
        if (before?.blockedReason != null && now.blockedReason == null) onUnblocked(now)
    }
}
