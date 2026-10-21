package com.example.bluetoothtest

import android.content.pm.ServiceInfo

/**
 * Foreground-service type decisions of [ActiveVideoSessionService], kept free of Android calls so they run in JVM
 * tests (device-family backlog B-01 and B-05).
 */
internal object ServiceTypePolicy {
    const val LOCATION: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private const val CONNECTED_DEVICE: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    /**
     * K B19: on Android 14+ the connectedDevice type's runtime prerequisite is met by a manifest-declared
     * CHANGE_WIFI_STATE (or by a Bluetooth runtime grant). The foreground-service fallback below always ends in
     * connectedDevice, so removing this permission (for example with the Wi-Fi Direct share feature) would make
     * the fallback depend on Bluetooth grants. ServiceManifestTest pins the declaration.
     */
    const val CONNECTED_DEVICE_PREREQUISITE_PERMISSION = "android.permission.CHANGE_WIFI_STATE"

    /**
     * Q37 (Android 10-11 only): a background BLE scan returns results only while the app counts as "in use" for
     * location. A foreground service of type `location` makes it count (Android background-location documentation);
     * no background-location permission is requested. The type is requested only when the phone is on API 29-30,
     * the family policy entry is on, the Location switch is on and FINE location is granted, and the start happens
     * with the app visible or the service already holds the type (a later notification update from the background
     * must not drop it). A start from the background never asks for it: Meshtastic dropped it from background starts.
     * [distributionAllows] is false in the Google Play flavour, whose manifest has no location type
     * (BuildConfig.LOCATION_FGS_AVAILABLE; asking for an undeclared type throws).
     */
    fun locationTypeWanted(
        sdk: Int,
        policyActive: Boolean,
        locationEnabled: Boolean,
        fineLocationGranted: Boolean,
        appVisible: Boolean,
        alreadyHeld: Boolean,
        distributionAllows: Boolean = BuildConfig.LOCATION_FGS_AVAILABLE,
    ): Boolean = distributionAllows && policyActive && sdk in 29..30 && locationEnabled && fineLocationGranted &&
        (appVisible || alreadyHeld)

    /**
     * RunningAppProcessInfo.IMPORTANCE_FOREGROUND is 100 (an activity of the app is on screen); a process that only runs
     * a foreground service reports IMPORTANCE_FOREGROUND_SERVICE (125) and is not "visible" for the location rule.
     */
    fun isForegroundImportance(importance: Int): Boolean = importance <= 100

    private const val CAMERA: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    private const val MICROPHONE: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    /**
     * Android 14+ (targetSdk 34+) refuses a camera or microphone type whose runtime permission is not granted, with a plain
     * SecurityException ("Starting FGS with type camera ... requires permissions: ...") thrown inside the service's own
     * start, which closed the whole app. 2026-10-21 Android 16 / HyperOS desk check, risk #1: HyperOS "Ask every time"
     * and one-time grants make it reachable. Such a type is not asked for at all; the rest of the session keeps running.
     * Returns the types to ask for and the dropped ones. Below Android 14 nothing is dropped (no such prerequisite).
     */
    fun withoutUngrantedTypes(sdk: Int, types: Int, cameraGranted: Boolean, microphoneGranted: Boolean): Pair<Int, Int> {
        if (sdk < 34) return types to 0
        var dropped = 0
        if (types and CAMERA != 0 && !cameraGranted) dropped = dropped or CAMERA
        if (types and MICROPHONE != 0 && !microphoneGranted) dropped = dropped or MICROPHONE
        return (types and dropped.inv()) to dropped
    }

    /** `BENCHMARK fgs_type_refused` line: types the session could not hold, and why. */
    fun refusedLine(types: Int, reason: String, stage: String): String =
        "fgs_type_refused: types=${foregroundTypeLabelsWithLocation(types)} | reason=$reason | stage=$stage"

    fun hasLocation(types: Int): Boolean = types and LOCATION != 0

    fun withoutLocation(types: Int): Int = types and LOCATION.inv()

    /** What the session means for the wake lock and the logs. */
    enum class SessionKind { IDLE_AVAILABILITY, ACTIVE }

    /**
     * Reachability ("Available for private Bluetooth calls") and the incoming-call ring are idle: statusText set and
     * none of the active-session extras. Everything that carries media (camera, microphone, call style, relay
     * hop, audio live) or has no status text (stream receiver, relay source) is active.
     */
    fun sessionKind(
        usesCamera: Boolean,
        usesMicrophone: Boolean,
        ongoingCall: Boolean,
        relayStopAction: Boolean,
        audioLive: Boolean,
        hasStatusText: Boolean,
    ): SessionKind = if (!usesCamera && !usesMicrophone && !ongoingCall && !relayStopAction && !audioLive && hasStatusText) {
        SessionKind.IDLE_AVAILABILITY
    } else {
        SessionKind.ACTIVE
    }

    /** `BENCHMARK fgs_start` per-start line: types and the permission set that qualified them (K B12/B19, T AT-09). */
    fun startLine(types: Int, latencyMs: Long?, permissions: List<Pair<String, Boolean>>): String =
        "fgs_start: types=${foregroundTypeLabelsWithLocation(types)} | latency_ms=${latencyMs ?: "unknown"} | " +
            "permissions=" + permissions.joinToString(",") { (name, granted) -> "$name:${if (granted) "Y" else "N"}" }

    /** Labels of [foregroundTypeLabels] plus the `location` bit (kept for callers that hold the raw type mask). */
    fun foregroundTypeLabelsWithLocation(types: Int): String {
        val base = foregroundTypeLabels(types and LOCATION.inv())
        if (!hasLocation(types)) return base
        return if (base == "none") "location" else "$base+location"
    }

    /** `onTimeout` log line; the service stops cleanly (no current type uses the 6 h limit, CP-03). */
    fun timeoutLine(startId: Int, fgsType: Int?): String =
        "fgs_timeout: startId=$startId | type=${fgsType?.let(::foregroundTypeLabelsWithLocation) ?: "none"} | action=stop"
}

/**
 * The startForeground attempts of one service start. Behaviour of the existing fallback is unchanged
 * (`bg-fgs-start-fallback`): a refused camera/microphone type retries with connectedDevice, a refused retry stops the
 * service, any other exception propagates. New: the optional `location` type (API 29-30) is dropped on ANY
 * exception first, because it was added on top of a working set and must never cost the session; the other types
 * are tried again without it. The drop is reported in [ForegroundServiceOutcome.refused] so a scorer sees it.
 */
internal class ForegroundStartSequence(
    private val start: (Int) -> Unit,
    private val fallbackEnabled: () -> Boolean,
    private val isRefusal: (Throwable) -> Boolean,
    /** A type whose permission is missing (see [isForegroundTypePermissionMissing]): never a crash, whatever the policy says. */
    private val isMissingPermission: (Throwable) -> Boolean = ::isForegroundTypePermissionMissing,
    /** Called with the types of an attempt Android refused for a missing permission (the service logs `fgs_type_refused`). */
    private val onTypeRefused: (Int) -> Unit = {},
) {
    fun run(requested: Int): ForegroundServiceOutcome {
        var attempt = requested
        var locationNote: String? = null
        if (ServiceTypePolicy.hasLocation(requested)) {
            try {
                start(requested)
                return ForegroundServiceOutcome(requested, requested, null)
            } catch (error: RuntimeException) {
                locationNote = "location type dropped, ${describe(error)}"
                attempt = ServiceTypePolicy.withoutLocation(requested)
            }
        }
        val refusal = try {
            start(attempt)
            return ForegroundServiceOutcome(requested, attempt, locationNote)
        } catch (error: RuntimeException) {
            val missing = isMissingPermission(error)
            if (!missing && (!fallbackEnabled() || !isRefusal(error))) throw error
            if (missing) onTypeRefused(attempt)
            listOfNotNull(locationNote, describe(error)).joinToString("; ")
        }
        val fallbackType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (attempt == fallbackType) return ForegroundServiceOutcome(requested, 0, refusal)
        return try {
            start(fallbackType)
            ForegroundServiceOutcome(requested, fallbackType, refusal)
        } catch (error: RuntimeException) {
            if (!isRefusal(error) && !isMissingPermission(error)) throw error
            ForegroundServiceOutcome(requested, 0, "$refusal; fallback ${describe(error)}")
        }
    }

    private fun describe(error: Throwable) = "${error.javaClass.simpleName}: ${error.message}"
}

/**
 * Stop-scans-on-exit (S-09, Android 10 scanner leak AOSP 8471db4566): when the session service ends or its task is
 * removed, the app's BLE scanners are stopped together so a dead process leaves none registered. Batch S owns
 * `ScanRegistry.stopAll()`, which is the default of [scanStopAll].
 */
internal object ServiceCleanup {
    enum class Reason { SERVICE_DESTROYED, TASK_REMOVED }

    @Volatile var scanStopAll: () -> Unit = { ScanRegistry.stopAll() }

    /**
     * Task removal always stops them (the activity that owned them is gone). A service that ends while the app is
     * still visible keeps them: the visible activity owns its own scanners and stops them itself.
     */
    fun shouldStopScans(enabled: Boolean, reason: Reason, appVisible: Boolean): Boolean = enabled && when (reason) {
        Reason.TASK_REMOVED -> true
        Reason.SERVICE_DESTROYED -> !appVisible
    }

    fun stopScans(enabled: Boolean, reason: Reason, appVisible: Boolean): Boolean {
        if (!shouldStopScans(enabled, reason, appVisible)) return false
        runCatching { scanStopAll() }
        return true
    }
}
