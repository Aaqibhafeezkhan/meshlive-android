package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/** Android 10-11: the session service also declares the `location` foreground-service type. */
internal data object LocationForegroundServiceType : DevicePolicyEffect {
    override fun describe(): String =
        "request the location foreground-service type with the session service while the app is visible (API 29-30, Location on, FINE granted)"
}

/** Experimental: a renewable partial wake lock while a screen-off session forwards media. */
internal data object SessionWakeLockEffect : DevicePolicyEffect {
    override fun describe(): String = "hold a renewable partial wake lock (meshlive:session) during a screen-off active session"
}

/** Experimental, opt-in: start the connectedDevice foreground service after a restart or an app update. */
internal data object BootResumeEffect : DevicePolicyEffect {
    override fun describe(): String = "start only the connectedDevice foreground service after BOOT_COMPLETED / MY_PACKAGE_REPLACED"
}

/**
 * Registry entries of the foreground-service batch (B1). The root adds `ServiceFamilyPolicy.entries` to the aggregate list
 * in DevicePolicyRegistry (one concatenation line). The code asks this object directly ([locationTypeActive],
 * [sessionWakeLockActive], [bootResumeActive]) so the off-switch `test_device_policy_off=<id>` works even before that line
 * lands. Owner: Bluetooth workstream. Matchers never use Build.MODEL.
 */
internal object ServiceFamilyPolicy {
    private const val REVIEWED = "2026-10-14"

    /** Same upstream snapshot BackgroundPolicy cites (kept local so the two files stay independent). */
    private const val DKMA_COMMIT = "3616bc8b3b6b3910ac204e107ff0dfd6064f877c"

    private val BT_PERMISSIONS_DOC = PolicySource(
        PLATFORM_DOC, "https://developer.android.com/develop/connectivity/bluetooth/bt-permissions",
        "Android 10/11: an app that runs a service must also declare ACCESS_BACKGROUND_LOCATION to discover Bluetooth devices",
    )

    const val LOCATION_FGS_ID = "android10-11-background-scan-needs-location-fgs"
    const val SESSION_WAKE_LOCK_ID = "fgs-session-wake-lock"
    const val BOOT_RESUME_ID = "boot-resume-after-restart"

    val LOCATION_FGS = DevicePolicyEntry(
        id = LOCATION_FGS_ID,
        title = "Android 10-11 background scan results need a location-type foreground service",
        matcher = DeviceMatcher(sdkMin = 29, sdkMax = 30),
        effects = listOf(LocationForegroundServiceType),
        behaviour = "The session service adds the location foreground-service type on Android 10-11, only when the service is " +
            "started with the app visible, the Location switch is on and FINE location is granted. If Android refuses the " +
            "combination the location type is dropped and the rest of the session starts as before (reported as refused= in " +
            "BENCHMARK foreground_service). No ACCESS_BACKGROUND_LOCATION permission is requested. The user is told the Location " +
            "switch must be on (batch S).",
        sources = listOf(
            BT_PERMISSIONS_DOC,
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/tags/android-11.0.0_r1/src/com/android/bluetooth/gatt/GattService.java",
                "scanClient.hasLocationPermission is decided when the scan registers; results are delivered only if it holds and Location is not off"),
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/tags/android-11.0.0_r1/src/com/android/bluetooth/Utils.java",
                "checkCallerHasFineLocation: AppOps FINE_LOCATION check (while-in-use for a background app without a location foreground service)"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/sensors-and-location/location/background",
                "a foreground service of type location counts as in-use for location access"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/background-work/services/fgs/service-types#location",
                "location type: the user must have location services on and FINE or COARSE granted"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: not yet measured on an Android 10 or 11 phone (lock the phone, bring a second phone in range, count scan results)"),
        confidence = MEDIUM,
        confidenceNote = "HIGH that the requirement is documented and that the stack code gates on it; MEDIUM that a connectedDevice-only " +
            "service fails on real Android 10/11 OEM builds and that the location type fixes it: unmeasured. Meshtastic ships " +
            "connectedDevice|location without background location. Play needs a location foreground-service declaration (demo video).",
        reviewed = REVIEWED,
        origin = "Device-family backlog B-01 / design decision Q37 (2026-10-14): background scans on Android 10-11 returned nothing.",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The session service declares camera, microphone, connectedDevice and mediaPlayback only, as before.",
    )

    val SESSION_WAKE_LOCK = DevicePolicyEntry(
        id = SESSION_WAKE_LOCK_ID,
        title = "Partial wake lock during a screen-off active session (Experimental)",
        matcher = DeviceMatcher(sdkMin = 29),
        effects = listOf(SessionWakeLockEffect),
        behaviour = "Only with the Experimental switch test_session_wake_lock=true: a renewable PARTIAL_WAKE_LOCK (meshlive:session, " +
            "90 s safety timeout renewed every minute) is held after startForeground succeeded, while the screen is off and the " +
            "session is an active hop (not idle availability), up to a daily budget; released when the service ends. " +
            "Default off: no lock is taken.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/background-work/background-tasks/awake",
                "a foreground service keeps the process alive, not the CPU; hold a partial wake lock only for the work that needs it"),
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java",
                "setWakeLockDisabledStateLocked: in Doze a wake lock is disabled only for a non-allowlisted UID worse than BOUND_FOREGROUND_SERVICE; Low Power Standby disables it too"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/google/play/vitals/excessive-wakelock",
                "Play vitals: 2 h or more of screen-off partial wake lock in 24 h is excessive"),
            PolicySource(VENDOR_DOC, "https://github.com/urbandroid-team/dont-kill-my-app/blob/$DKMA_COMMIT/_vendors/samsung.md",
                "Don't Kill My App (community-maintained): Samsung One UI 3-5 blocks wake locks in foreground services"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: not measured on a screen-off relay; needs dumpsys power and timer lateness with the lock on and off"),
        confidence = MEDIUM,
        confidenceNote = "The AOSP rule is certain; whether MeshLive timers stall without the lock, and whether OEM builds honour a " +
            "foreground-service lock, is unmeasured. A held lock does not prove the CPU stays awake. Who may flip the default " +
            "(sideload build only) is user question Q-N5.",
        reviewed = REVIEWED,
        origin = "Device-family backlog B-04: no partial wake lock existed (grep: only a proximity lock in PrivateCallAudioSession).",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "No wake lock is held (the shipped behaviour).",
    )

    val BOOT_RESUME = DevicePolicyEntry(
        id = BOOT_RESUME_ID,
        title = "Start the connectedDevice foreground service after a restart or an update (Experimental, opt-in)",
        matcher = DeviceMatcher(sdkMin = 29),
        effects = listOf(BootResumeEffect),
        behaviour = "Only after the user turns on the opt-in setting (or test_boot_resume=true in a debug build): a receiver for " +
            "BOOT_COMPLETED, the OEM quick-boot broadcasts and MY_PACKAGE_REPLACED starts only the connectedDevice foreground " +
            "service (no camera, no microphone: Android 15 forbids those from BOOT_COMPLETED). The receiver component is disabled in " +
            "the manifest and is enabled only while the opt-in is on, so a default install runs nothing at boot.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed",
                "Android 15: BOOT_COMPLETED receivers may not start dataSync, camera, mediaPlayback, phoneCall, mediaProjection or microphone services; connectedDevice is allowed"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start",
                "ACTION_BOOT_COMPLETED and ACTION_MY_PACKAGE_REPLACED are exemptions from the Android 12 background foreground-service start rule"),
            PolicySource(VENDOR_DOC, "https://github.com/urbandroid-team/dont-kill-my-app/blob/$DKMA_COMMIT/_vendors/xiaomi.md",
                "Don't Kill My App (community-maintained): Xiaomi and some other makers also require the autostart permission"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: not run on a phone; the in-process relay host that continues after the service starts is not part of this entry"),
        confidence = LOW,
        confidenceNote = "The platform rules are official (HIGH). That a started service alone resumes relaying is not true yet: " +
            "the relay controllers live in the activity, so the end-to-end value is unproven. Autostart-permission makers may block " +
            "the broadcast. User question Q-N2 decides whether this ships.",
        reviewed = REVIEWED,
        origin = "Device-family backlog B-06: a relay phone that reboots stays off the mesh until someone opens the app.",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "Nothing starts at boot (the shipped behaviour).",
    )

    val entries: List<DevicePolicyEntry> = listOf(LOCATION_FGS, SESSION_WAKE_LOCK, BOOT_RESUME)

    /** True when [entry] applies to [traits] and neither "all" nor its id is switched off. Pure, for tests. */
    fun isActive(entry: DevicePolicyEntry, traits: DeviceTraits, disabled: Set<String>): Boolean =
        entry.matcher.matches(traits) && "all" !in disabled && entry.id !in disabled && entry.id !in traits.policyOff

    private fun activeHere(entry: DevicePolicyEntry): Boolean =
        runCatching { isActive(entry, DeviceTraits.current(), DeviceFamilyPolicy.switchedOff) }.getOrDefault(false)

    fun locationTypeActive(): Boolean = activeHere(LOCATION_FGS)

    fun sessionWakeLockActive(): Boolean = activeHere(SESSION_WAKE_LOCK)

    fun bootResumeActive(): Boolean = activeHere(BOOT_RESUME)
}
