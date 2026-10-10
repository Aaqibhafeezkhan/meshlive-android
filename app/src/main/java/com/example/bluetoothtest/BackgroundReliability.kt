package com.example.bluetoothtest

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** How urgently the UI should surface an issue. */
enum class ReliabilitySeverity { ACTION_NEEDED, ADVISORY, INFO }

/**
 * One thing that can stop calls, streams or relaying in the background, with what to do about it.
 * [actions] are tried in order by [BackgroundReliability.openSettings]; the app's details page is
 * always the last fallback.
 */
data class ReliabilityIssue(
    val id: String,
    /** The DevicePolicyRegistry entry that produced it (its off switch is test_device_policy_off=<id>). */
    val policyId: String,
    val severity: ReliabilitySeverity,
    val title: String,
    val detail: String,
    val steps: List<String>,
    val actions: List<SettingsIntentSpec>,
    /** "upstream-documented" / "locally reproduced" / "experimental". */
    val validation: String,
)

/** What the app could read about its own restriction state (null: not readable on this phone). */
data class BackgroundSnapshot(
    val sdkInt: Int,
    val manufacturer: String,
    val ignoringBatteryOptimizations: Boolean?,
    val backgroundRestricted: Boolean?,
    val standbyBucket: Int?,
    val powerSaveMode: Boolean?,
    val autoRevokeExempt: Boolean?,
    val staminaMode: Boolean?,
    val installedKillerPackages: Set<String>,
    val lastForegroundServiceFailure: String?,
    /** A BLE search currently waiting for Android's scan quota (le-scan-start-quota), in ms; 0 when none. */
    val scanDeferredMs: Long = 0L,
)

data class BackgroundReliabilityStatus(
    val snapshot: BackgroundSnapshot,
    val issues: List<ReliabilityIssue>,
) {
    val needsAction: Boolean get() = issues.any { it.severity == ReliabilitySeverity.ACTION_NEEDED }
    val hasWarnings: Boolean get() = issues.any { it.severity != ReliabilitySeverity.INFO }
}

/**
 * Background reliability for the UI banner (audit 2026-10-10). Diagnostics and guidance only: no
 * keep-alive hacks, no exemption prompt.
 *
 * ```
 * val status = BackgroundReliability.status(context)       // cheap; call from the main thread
 * status.issues.forEach { issue -> show(issue.title, issue.detail, issue.steps) }
 * BackgroundReliability.openSettings(activity, issue)       // opens the best settings screen
 * ```
 */
object BackgroundReliability {
    const val STANDBY_BUCKET_RARE = 40
    const val STANDBY_BUCKET_RESTRICTED = 45

    @Volatile private var lastFgsFailure: String? = null

    /** Called by ActiveVideoSessionService when Android refused a foreground-service start or type. */
    fun recordForegroundServiceFailure(reason: String) {
        lastFgsFailure = reason
        runCatching { Log.w("BluetoothVideo", "Background reliability: foreground service refused: $reason") }
    }

    fun status(context: Context): BackgroundReliabilityStatus {
        val resolved = DeviceFamilyPolicy.current()
        val snapshot = snapshot(context.applicationContext, resolved)
        return BackgroundReliabilityStatus(snapshot, evaluate(snapshot, resolved))
    }

    /** Logs one diagnostic line (not a BENCHMARK contract line) with the state and issue ids. */
    fun logStatus(context: Context) {
        try { Log.i("BluetoothVideo", statusLine(status(context))) } catch (_: RuntimeException) {} // Release: stripped.
    }

    internal fun statusLine(status: BackgroundReliabilityStatus): String {
        val s = status.snapshot
        return "Background reliability: sdk=${s.sdkInt} | manufacturer=${s.manufacturer} | " +
            "ignoringBatteryOpt=${s.ignoringBatteryOptimizations.flag()} | backgroundRestricted=${s.backgroundRestricted.flag()} | " +
            "standbyBucket=${s.standbyBucket ?: "?"} | powerSave=${s.powerSaveMode.flag()} | " +
            "autoRevokeExempt=${s.autoRevokeExempt.flag()} | killers=${s.installedKillerPackages.sorted().joinToString(",").ifEmpty { "none" }} | " +
            "issues=${status.issues.joinToString(",") { "${it.id}:${it.severity.name.lowercase()}" }.ifEmpty { "none" }}"
    }

    private fun Boolean?.flag(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    private fun snapshot(context: Context, resolved: ResolvedDevicePolicy): BackgroundSnapshot {
        val packageName = context.packageName
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val killers = resolved.effects<OemBackgroundGuidance>().flatMap { it.killerPackages }
            .filter { packageVisible(context.packageManager, it) }.toSet()
        return BackgroundSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            ignoringBatteryOptimizations = runCatching { power?.isIgnoringBatteryOptimizations(packageName) }.getOrNull(),
            backgroundRestricted = runCatching { activity?.isBackgroundRestricted }.getOrNull(),
            standbyBucket = runCatching { usage?.appStandbyBucket }.getOrNull(),
            powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull(),
            autoRevokeExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { context.packageManager.isAutoRevokeWhitelisted }.getOrNull()
            } else {
                null
            },
            // Readable only where the OEM key is not restricted (older Xperia builds); null otherwise.
            staminaMode = if (resolved.effects<BackgroundCheck>().any { it.kind == BackgroundCheckKind.SONY_STAMINA }) {
                runCatching { Settings.Secure.getInt(context.contentResolver, "somc.stamina_mode", 0) > 0 }.getOrNull()
            } else {
                null
            },
            installedKillerPackages = killers,
            lastForegroundServiceFailure = lastFgsFailure,
            scanDeferredMs = ScanStartGate.shared.deferredMs(),
        )
    }

    private fun validationLabel(entry: DevicePolicyEntry?): String = when (entry?.validation) {
        PolicyValidation.LOCALLY_REPRODUCED -> "locally reproduced"
        PolicyValidation.EXPERIMENTAL -> "experimental"
        else -> "upstream-documented"
    }

    private val APP_DETAILS = SettingsIntentSpec(
        label = "Open app settings",
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        withPackageUri = true,
    )

    /** JVM-testable: which issues the active entries raise for [snapshot]. */
    internal fun evaluate(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): List<ReliabilityIssue> {
        val issues = mutableListOf<ReliabilityIssue>()
        fun entryFor(kind: BackgroundCheckKind): DevicePolicyEntry? =
            resolved.active.firstOrNull { entry -> entry.effects.any { it is BackgroundCheck && it.kind == kind } }

        entryFor(BackgroundCheckKind.BACKGROUND_RESTRICTED)?.let { entry ->
            if (snapshot.backgroundRestricted == true) issues += ReliabilityIssue(
                id = "background-restricted", policyId = entry.id, severity = ReliabilitySeverity.ACTION_NEEDED,
                title = "Background activity is restricted",
                detail = "Android stops this app's work when it is not on screen, so calls and relaying stop when you leave it.",
                steps = listOf("Settings > Apps > (this app) > Battery: choose Unrestricted (or turn off Background restriction)."),
                actions = listOf(APP_DETAILS), validation = validationLabel(entry),
            )
        }
        entryFor(BackgroundCheckKind.STANDBY_BUCKET)?.let { entry ->
            val bucket = snapshot.standbyBucket
            if (bucket != null && bucket >= STANDBY_BUCKET_RARE) issues += ReliabilityIssue(
                id = "standby-bucket", policyId = entry.id,
                severity = if (bucket >= STANDBY_BUCKET_RESTRICTED) ReliabilitySeverity.ACTION_NEEDED else ReliabilitySeverity.ADVISORY,
                title = if (bucket >= STANDBY_BUCKET_RESTRICTED) "Android put this app in the restricted group" else "Android rarely lets this app run in the background",
                detail = "App standby bucket $bucket. Opening the app moves it up; an unrestricted battery setting keeps it there.",
                steps = listOf("Open the app before you need it, and set its battery use to Unrestricted."),
                actions = listOf(APP_DETAILS), validation = validationLabel(entry),
            )
        }
        snapshot.lastForegroundServiceFailure?.let { reason ->
            resolved.active.firstOrNull { entry -> entry.effects.any { it is ForegroundServiceStartFallback } }?.let { entry ->
                issues += ReliabilityIssue(
                    id = "foreground-service-refused", policyId = entry.id, severity = ReliabilitySeverity.ACTION_NEEDED,
                    title = "Android refused to keep the session running in the background",
                    detail = "Reason: $reason. Start calls and streams with the app on screen; allow unrestricted battery use.",
                    steps = listOf("Keep the app open when you start a call or stream.", "Settings > Apps > (this app) > Battery: Unrestricted."),
                    actions = listOf(APP_DETAILS), validation = validationLabel(entry),
                )
            }
        }
        entryFor(BackgroundCheckKind.BATTERY_OPTIMIZATION)?.let { entry ->
            if (snapshot.ignoringBatteryOptimizations == false) issues += ReliabilityIssue(
                id = "battery-optimization", policyId = entry.id, severity = ReliabilitySeverity.ADVISORY,
                title = "Battery optimisation is on for this app",
                detail = "With the screen off Android may delay or stop relaying and incoming calls.",
                steps = listOf("Battery optimisation list > All apps > (this app) > Don't optimise (or Unrestricted)."),
                actions = listOf(
                    SettingsIntentSpec("Open battery optimisation", action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    APP_DETAILS,
                ),
                validation = validationLabel(entry),
            )
        }
        entryFor(BackgroundCheckKind.UNUSED_APP_RESTRICTIONS)?.let { entry ->
            if (snapshot.autoRevokeExempt == false) issues += ReliabilityIssue(
                id = "unused-app-restrictions", policyId = entry.id, severity = ReliabilitySeverity.ADVISORY,
                title = "Android may remove permissions if the app is unused",
                detail = "After a few months without use, Nearby devices, camera and microphone permissions are removed, " +
                    "so the app could not answer when you need it off-grid.",
                steps = listOf("Settings > Apps > (this app): turn off \"Pause app activity if unused\" (or \"Remove permissions if app isn't used\")."),
                actions = listOfNotNull(
                    if (snapshot.sdkInt == 30) SettingsIntentSpec("Open unused-app settings", action = Intent.ACTION_AUTO_REVOKE_PERMISSIONS, withPackageUri = true) else null,
                    APP_DETAILS,
                ),
                validation = validationLabel(entry),
            )
        }
        entryFor(BackgroundCheckKind.POWER_SAVE_MODE)?.let { entry ->
            if (snapshot.powerSaveMode == true) issues += ReliabilityIssue(
                id = "power-save-mode", policyId = entry.id, severity = ReliabilitySeverity.INFO,
                title = "Battery Saver is on",
                detail = "Battery Saver can delay background work while the screen is off.",
                steps = listOf("Turn Battery Saver off during calls or relaying if they stop."),
                actions = listOf(SettingsIntentSpec("Open Battery Saver", action = Settings.ACTION_BATTERY_SAVER_SETTINGS)),
                validation = validationLabel(entry),
            )
        }
        entryFor(BackgroundCheckKind.SONY_STAMINA)?.let { entry ->
            if (snapshot.staminaMode == true) issues += ReliabilityIssue(
                id = "sony-stamina", policyId = entry.id, severity = ReliabilitySeverity.ADVISORY,
                title = "STAMINA mode is on",
                detail = "Sony STAMINA mode limits background apps.",
                steps = listOf("Settings > Battery > STAMINA mode: turn it off, or except this app."),
                actions = listOf(SettingsIntentSpec("Open Battery Saver", action = Settings.ACTION_BATTERY_SAVER_SETTINGS), APP_DETAILS),
                validation = validationLabel(entry),
            )
        }
        if (snapshot.scanDeferredMs > 0L) {
            resolved.active.firstOrNull { entry -> entry.effects.any { it is LeScanStartBudget } }?.let { entry ->
                issues += ReliabilityIssue(
                    id = "scan-quota-deferred", policyId = entry.id, severity = ReliabilitySeverity.INFO,
                    title = "Bluetooth search is waiting",
                    detail = "Android allows 5 Bluetooth searches per 30 s per app; the next search starts in " +
                        "${(snapshot.scanDeferredMs + 999) / 1_000} s.",
                    steps = listOf("Wait a moment; the search resumes by itself."),
                    actions = emptyList(), validation = validationLabel(entry),
                )
            }
        }
        val oemEntries = resolved.active.filter { entry -> entry.effects.any { it is OemBackgroundGuidance } }
        for (entry in oemEntries) {
            val guidance = entry.effects.filterIsInstance<OemBackgroundGuidance>().first()
            val killers = guidance.killerPackages.filter { it in snapshot.installedKillerPackages }
            issues += ReliabilityIssue(
                id = "oem-${guidance.vendorKey}", policyId = entry.id,
                severity = if (killers.isNotEmpty()) ReliabilitySeverity.ACTION_NEEDED else ReliabilitySeverity.ADVISORY,
                title = "${guidance.vendor} phones can stop background apps",
                detail = (if (killers.isNotEmpty()) "Installed background killer: ${killers.joinToString()}. " else "") +
                    "These ${guidance.vendor} settings keep calls and relaying alive with the screen off " +
                    "(Don't Kill My App; written for ${guidance.osCovered}; menu names vary by version).",
                steps = guidance.steps,
                actions = guidance.settings + APP_DETAILS, validation = validationLabel(entry),
            )
        }
        if (oemEntries.isEmpty() && entryFor(BackgroundCheckKind.BATTERY_OPTIMIZATION) != null) {
            issues += ReliabilityIssue(
                id = "oem-generic", policyId = BackgroundPolicy.BATTERY_OPTIMIZATION.id, severity = ReliabilitySeverity.INFO,
                title = "Phone-maker battery settings",
                detail = "Some phone makers add their own app killers. If calls or relaying stop with the screen off, check these settings.",
                steps = BackgroundPolicy.GENERIC_STEPS,
                actions = listOf(APP_DETAILS), validation = "upstream-documented",
            )
        }
        return issues
    }

    /** Android intents for [spec], or null when the spec cannot be built. */
    fun intentFor(context: Context, spec: SettingsIntentSpec): Intent? = runCatching {
        val intent = when {
            spec.className != null && spec.packageName != null -> Intent().setClassName(spec.packageName, spec.className)
            spec.action != null -> Intent(spec.action)
            else -> return null
        }
        if (spec.withPackageUri) intent.data = Uri.fromParts("package", context.packageName, null)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent
    }.getOrNull()

    /**
     * Opens the first settings screen of [issue] that Android accepts, then the app's details page.
     * startActivity is used directly (package visibility does not filter explicit starts); a vendor
     * screen that is missing or not exported throws and the next one is tried.
     */
    fun openSettings(context: Context, issue: ReliabilityIssue): Boolean {
        for (spec in issue.actions + APP_DETAILS) {
            val intent = intentFor(context, spec) ?: continue
            val opened = try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            if (opened) return true
        }
        return false
    }

    internal fun packageVisible(packageManager: PackageManager, packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0); true }.getOrDefault(false)
}

/** The immediate answer of ActiveVideoSessionService.start: [refused] when Android refused the start itself. */
data class ForegroundStartRequest(val requestedTypes: Int, val refused: String?)

/**
 * What the session service asked for and what it holds. Any difference (or a refusal) means the
 * session runs without some of what it requested, e.g. no camera/microphone while backgrounded.
 */
data class ForegroundServiceOutcome(val requestedTypes: Int, val effectiveTypes: Int, val refused: String?) {
    val mismatch: Boolean get() = refused != null || requestedTypes != effectiveTypes

    /** Scorer contract (tools/score_device_run.py): `refused=` other than none is an unexpected event. */
    fun benchmarkLine(): String =
        "BENCHMARK foreground_service: requested=${foregroundTypeLabels(requestedTypes)} | " +
            "effective=${foregroundTypeLabels(effectiveTypes)} | refused=${refused?.replace('|', '/') ?: "none"}"

    fun summary(): String =
        "requested ${foregroundTypeLabels(requestedTypes)}, holds ${foregroundTypeLabels(effectiveTypes)}: ${refused ?: "types differ"}"

    /** Plain-language notice for the visible activity. */
    fun userNotice(): String = when {
        effectiveTypes == 0 ->
            "Android did not let this session keep running in the background. Open the app and start again."
        requestedTypes and (FGS_CAMERA or FGS_MICROPHONE) != effectiveTypes and (FGS_CAMERA or FGS_MICROPHONE) ->
            "Camera and microphone are unavailable while the app is in the background. Open the app to use them."
        else -> "The background session is limited. Open the app."
    }
}

private const val FGS_CONNECTED_DEVICE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
private const val FGS_CAMERA = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
private const val FGS_MICROPHONE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

internal fun foregroundTypeLabels(types: Int): String {
    if (types == 0) return "none"
    val labels = listOfNotNull(
        "connectedDevice".takeIf { types and FGS_CONNECTED_DEVICE != 0 },
        "camera".takeIf { types and FGS_CAMERA != 0 },
        "microphone".takeIf { types and FGS_MICROPHONE != 0 },
    )
    val rest = types and (FGS_CONNECTED_DEVICE or FGS_CAMERA or FGS_MICROPHONE).inv()
    return (labels + listOfNotNull(rest.takeIf { it != 0 }?.let { "0x" + Integer.toHexString(it) })).joinToString("+")
}

/**
 * Only Android's two documented refusals qualify for the fallback (AOSP android14-release
 * ActiveServices.java 2297 and 2601-2611): ForegroundServiceStartNotAllowedException (background
 * start, API 31+) and the while-in-use type SecurityException ("... eligible state/exemptions to
 * access the foreground only permission"). Manifest bugs (Invalid/MissingForegroundServiceType,
 * any other SecurityException) propagate.
 */
internal fun isForegroundServiceRefusal(error: Throwable): Boolean =
    // By simple name: the class is API 31+ and cannot be referenced on older phones.
    error.javaClass.simpleName == "ForegroundServiceStartNotAllowedException" ||
        (error is SecurityException && error.message?.contains("foreground only permission") == true)
