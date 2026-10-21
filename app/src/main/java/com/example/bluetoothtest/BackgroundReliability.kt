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
    /** Vendor ROM version properties that returned a value (names UNVERIFIED, DeviceTraitsProbe); picks MIUI/HyperOS text. */
    val romProperties: Map<String, String> = emptyMap(),
    /** An always-on role (relaying, background availability) is on: [BackgroundReliability.alwaysOnRole]. */
    val alwaysOnRole: Boolean = false,
    /** Newest exit that was not the app closing itself (ProcessExitReport): [ExitCause] name, plain text, session flag, age. */
    val lastExitCause: String? = null,
    val lastExitText: String? = null,
    val lastExitDuringSession: Boolean = false,
    val lastExitAgeMs: Long = 0L,
    /** [ScanEnvSnapshot.blockedReason] (Android 10-11 Location switch / FINE permission) or null. */
    val locationBlockedReason: String? = null,
    /** [BluetoothEpoch.crashLoopSuspected]: the Bluetooth service restarted several times in a short while. */
    val bluetoothCrashLoop: Boolean = false,
    /** The scan watchdog's suspect (`oem_pause`, `doze`) while no scan result has arrived since; null otherwise. */
    val scanPauseSuspect: String? = null,
    /** The phone's build or patch changed within the last week / it restarted within the last two days (PhoneUpdateTracker). */
    val phoneUpdatedRecently: Boolean = false,
    val restartedRecently: Boolean = false,
    /** `heads_up_only` when an incoming-call full-screen intent did not bring the call screen up in time. */
    val incomingUi: String? = null,
    /** Xiaomi's background pop-up permission from the Experimental hidden-AppOps probe; null = unknown (default). */
    val popupPermissionGranted: Boolean? = null,
    /** Package of the system Bluetooth app, for its settings page. */
    val bluetoothPackage: String = "com.android.bluetooth",
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
 * keep-alive hacks. The one system action it points at is the battery-optimisation exemption prompt (user
 * decision Q38), as the first action of the `battery-optimization` issue.
 *
 * ```
 * val status = BackgroundReliability.status(context)       // cheap; call from the main thread
 * status.issues.forEach { issue -> show(issue.title, issue.detail, issue.steps) }
 * BackgroundReliability.openSettings(activity, issue)       // opens the best settings screen
 * val card = BackgroundReliability.cardIssues(context)      // the Settings "Connection reliability" card
 * ```
 *
 * Root hooks (see the batch B2 hand-back): wire [alwaysOnRole], call [logStatus] at app start (already done),
 * call [noteScanStale] from the scan tick, [recordIncomingUi] from the incoming-call path, and let
 * CoreReliabilityUi return [cardIssues].
 */
object BackgroundReliability {
    const val STANDBY_BUCKET_RARE = 40
    const val STANDBY_BUCKET_RESTRICTED = 45

    /** A kill reason older than this is history, not a banner (ProcessExitReport keeps only exits since the last start anyway). */
    const val RECENT_EXIT_MS = 3L * 24 * 60 * 60 * 1000

    @Volatile private var lastFgsFailure: String? = null
    @Volatile private var lastScanSuspect: String? = null
    @Volatile private var incomingUiResult: String? = null
    private val startRecorded = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * True while an always-on role (relaying, background availability) is turned on. Root hook: the settings owner sets
     * this once (the default is off, so the battery issue stays ADVISORY until it is wired).
     */
    @Volatile var alwaysOnRole: () -> Boolean = { false }

    /** Root hook (scan tick): the scan watchdog found a stale scan; [suspect] is [ScanSuspect.label]. */
    fun noteScanStale(suspect: String) {
        lastScanSuspect = suspect
    }

    /** Root hook (incoming-call path, see [IncomingUiWatch]): the call screen did (false) or did not (true) appear in time. */
    fun recordIncomingUi(headsUpOnly: Boolean) {
        incomingUiResult = if (headsUpOnly) "heads_up_only" else null
    }

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
        // Once per process, before the first status: remembers the build/patch/boot count for "phone updated" (B-13).
        if (startRecorded.compareAndSet(false, true)) runCatching { PhoneUpdateTracker.recordAtStart(context.applicationContext) }
        try { Log.i("BluetoothVideo", statusLine(status(context))) } catch (_: RuntimeException) {} // Release: stripped.
    }

    internal fun statusLine(status: BackgroundReliabilityStatus): String {
        val s = status.snapshot
        return "Background reliability: sdk=${s.sdkInt} | manufacturer=${s.manufacturer} | " +
            "ignoringBatteryOpt=${s.ignoringBatteryOptimizations.flag()} | backgroundRestricted=${s.backgroundRestricted.flag()} | " +
            "standbyBucket=${s.standbyBucket ?: "?"} | powerSave=${s.powerSaveMode.flag()} | " +
            "autoRevokeExempt=${s.autoRevokeExempt.flag()} | killers=${s.installedKillerPackages.sorted().joinToString(",").ifEmpty { "none" }} | " +
            "issues=${status.issues.joinToString(",") { "${it.id}:${it.severity.name.lowercase()}" }.ifEmpty { "none" }}" +
            (s.lastExitCause?.let { " | lastExit=$it" } ?: "")
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
        // By installed package, whatever the manufacturer string says (B-11): the whole catalogue plus any entry-specific names.
        val killers = (BackgroundOem.QUERY_PACKAGES + resolved.effects<OemBackgroundGuidance>().flatMap { it.killerPackages })
            .distinct().filter { packageVisible(context.packageManager, it) }.toSet()
        val exit = runCatching { ProcessExitReport.lastUnexpected() }.getOrNull()
        val env = runCatching { ScanEnvironment.snapshot(context) }.getOrNull()
        val unresolvedStale = runCatching { ScanRuntime.health.currentThresholdMs > ScanHealth.DEFAULT_STALE_MS }.getOrDefault(false)
        val maker = Build.MANUFACTURER.orEmpty().trim().lowercase()
        val btPackage = resolved.traits.runtime?.bluetoothPackageVersions?.keys?.firstOrNull() ?: "com.android.bluetooth"
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
            romProperties = resolved.traits.runtime?.romProperties.orEmpty(),
            alwaysOnRole = runCatching { alwaysOnRole() }.getOrDefault(false),
            lastExitCause = exit?.cause?.name,
            lastExitText = exit?.userText(),
            lastExitDuringSession = exit?.sessionActive == true,
            lastExitAgeMs = exit?.let { (System.currentTimeMillis() - it.timestampMs).coerceAtLeast(0L) } ?: 0L,
            locationBlockedReason = env?.blockedReason,
            bluetoothCrashLoop = runCatching { BluetoothEpoch.core.crashLoopSuspected }.getOrDefault(false),
            scanPauseSuspect = lastScanSuspect.takeIf { unresolvedStale },
            phoneUpdatedRecently = PhoneUpdateTracker.updatedRecently(context),
            restartedRecently = PhoneUpdateTracker.restartedRecently(context),
            incomingUi = incomingUiResult,
            popupPermissionGranted = if (BackgroundSwitches.miuiAppOpsProbe && maker in BackgroundOem.RESETS_AFTER_RESTART_MAKERS) {
                MiuiAppOpsProbe.backgroundPopupGranted(context)
            } else {
                null
            },
            bluetoothPackage = btPackage,
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
                id = "battery-optimization", policyId = entry.id,
                // Q38: ACTION_NEEDED while an always-on role is on; otherwise a quiet advisory.
                severity = if (snapshot.alwaysOnRole) ReliabilitySeverity.ACTION_NEEDED else ReliabilitySeverity.ADVISORY,
                title = if (snapshot.alwaysOnRole) "Allow this app to keep running in the background" else "Battery optimisation is on for this app",
                detail = if (snapshot.alwaysOnRole) {
                    "Relaying or background availability is on, but Android may still pause the app with the screen off, " +
                        "so calls and shared video can stop. It uses more battery while a call, a live video or a relay is on."
                } else {
                    "With the screen off Android may delay or stop relaying and incoming calls."
                },
                steps = if (!FlavourBattery.directRequest) emptyList() else listOf(
                    "Tap the button and choose Allow in Android's prompt.",
                    "Or: battery optimisation list > All apps > (this app) > Don't optimise (or Unrestricted).",
                ),
                actions = listOfNotNull(
                    if (FlavourBattery.directRequest) SettingsIntentSpec("Allow background running", action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackageUri = true) else null,
                    SettingsIntentSpec("Open battery optimisation", action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    APP_DETAILS,
                ),
                validation = validationLabel(entry),
            )
        }
        issues += scanEnvironmentIssues(snapshot, resolved)
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
        // Phone-maker guidance: the entry matched by manufacturer string, else (re-branded phones) by an installed marker package.
        val oemEntries = resolved.active.filter { entry -> entry.effects.any { it is OemBackgroundGuidance } }
            .ifEmpty { listOfNotNull(markerOemEntry(snapshot, resolved)) }
        for (entry in oemEntries) {
            val guidance = entry.effects.filterIsInstance<OemBackgroundGuidance>().first()
            val killers = BackgroundOem.installed(snapshot.installedKillerPackages)
                .filter { it.packageName in guidance.killerPackages || (it.blocksApps && it.verified && it.familyKey == guidance.vendorKey) }
                .map { it.packageName }
            val variant = BackgroundOem.variantFor(guidance.vendorKey, snapshot.sdkInt, snapshot.romProperties)
            val community = entry.sources.firstOrNull()?.reference?.contains("dont-kill-my-app") == true
            issues += ReliabilityIssue(
                id = "oem-${guidance.vendorKey}", policyId = entry.id,
                severity = if (killers.isNotEmpty()) ReliabilitySeverity.ACTION_NEEDED else ReliabilitySeverity.ADVISORY,
                title = "${guidance.vendor} phones can stop background apps",
                detail = (if (killers.isNotEmpty()) "Installed background killer: ${killers.joinToString()}. " else "") +
                    "These ${guidance.vendor} settings usually keep calls and relaying alive with the screen off " +
                    "(${if (community) "Don't Kill My App" else "the maker's support page and community guides"}; written for " +
                    "${variant?.osCovered ?: guidance.osCovered}; menu names vary by version)." +
                    (if (variant != null && variant.label.startsWith("HyperOS")) " This phone looks like HyperOS." else ""),
                steps = variant?.steps ?: guidance.steps,
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
        issues += killerPackageIssues(snapshot, resolved, oemEntries)
        issues += exitIssues(snapshot, resolved)
        issues += scanPauseIssues(snapshot, resolved)
        issues += updateAndBluetoothAppIssues(snapshot, resolved)
        issues += incomingPopupIssues(snapshot, resolved)
        return issues
    }

    private fun ResolvedDevicePolicy.switchedOffIds(): Set<String> = disabled + traits.policyOff

    private fun ResolvedDevicePolicy.enabled(id: String): Boolean = !ScanPolicy.isOff(id, switchedOffIds())

    private fun ResolvedDevicePolicy.guidanceEntry(topic: GuidanceTopic): DevicePolicyEntry? =
        active.firstOrNull { entry -> entry.effects.any { it is ReliabilityGuidance && it.topic == topic } }

    /** Re-branded phones: the entry of the guidance family implied by an installed marker package, when it is not switched off. */
    private fun markerOemEntry(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): DevicePolicyEntry? {
        val family = BackgroundOem.familyFromPackages(snapshot.installedKillerPackages) ?: return null
        val entry = BackgroundPolicy.entries.firstOrNull { it.id == "bg-oem-$family" } ?: return null
        return entry.takeIf { resolved.enabled(it.id) }
    }

    /** Android 10-11 Location switch (S-01) and a restarting Bluetooth service (S-08), from the scan batch's state. */
    private fun scanEnvironmentIssues(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): List<ReliabilityIssue> {
        val issues = mutableListOf<ReliabilityIssue>()
        val reason = snapshot.locationBlockedReason
        if (reason != null && ScanEnvironment.guidanceActive(snapshot.sdkInt, resolved.switchedOffIds())) {
            issues += if (reason == ScanEnvironment.REASON_FINE_LOCATION_MISSING) {
                ReliabilityIssue(
                    id = "location-permission", policyId = ScanEnvironment.POLICY_ID, severity = ReliabilitySeverity.ACTION_NEEDED,
                    title = "Allow location permission so Android lets this phone find nearby phones",
                    detail = "On Android 10 and 11 Bluetooth search needs the location permission. This app does not read or store where you are.",
                    steps = listOf("Settings > Apps > (this app) > Permissions > Location: allow."),
                    actions = listOf(APP_DETAILS), validation = "upstream-documented",
                )
            } else {
                ReliabilityIssue(
                    id = "location-off", policyId = ScanEnvironment.POLICY_ID, severity = ReliabilitySeverity.ACTION_NEEDED,
                    title = "Turn on Location so Android lets this phone find nearby phones",
                    detail = "On Android 10 and 11 the Location switch also controls Bluetooth search; with it off no nearby phone is found. " +
                        "This app does not read or store where you are.",
                    steps = listOf("Settings > Location: turn it on."),
                    actions = listOf(SettingsIntentSpec("Open Location settings", action = ScanEnvironment.LOCATION_SETTINGS_ACTION), APP_DETAILS),
                    validation = "upstream-documented",
                )
            }
        }
        if (snapshot.bluetoothCrashLoop && resolved.enabled("bluetooth-process-restart-recovery")) {
            issues += ReliabilityIssue(
                id = "bluetooth-crash-loop", policyId = "bluetooth-process-restart-recovery", severity = ReliabilitySeverity.ADVISORY,
                title = "Bluetooth keeps restarting on this phone",
                detail = "Android's Bluetooth service stopped and started again several times in a short while, so connections drop. " +
                    "Switching Bluetooth off and on, or restarting the phone, usually settles it.",
                steps = listOf("Open Bluetooth settings, switch Bluetooth off, wait a few seconds and switch it on.", "If it comes back, restart the phone."),
                actions = listOf(SettingsIntentSpec("Open Bluetooth settings", action = Settings.ACTION_BLUETOOTH_SETTINGS)),
                validation = "upstream-documented",
            )
        }
        return issues
    }

    /** Installed task killers by package (B-11), not repeated when the maker's own entry already names them. */
    private fun killerPackageIssues(
        snapshot: BackgroundSnapshot,
        resolved: ResolvedDevicePolicy,
        oemEntries: List<DevicePolicyEntry>,
    ): List<ReliabilityIssue> {
        val entry = resolved.guidanceEntry(GuidanceTopic.KILLER_PACKAGE_SCAN) ?: return emptyList()
        val covered = oemEntries.flatMap { it.effects.filterIsInstance<OemBackgroundGuidance>() }.flatMap { it.killerPackages }.toSet()
        return BackgroundOem.installed(snapshot.installedKillerPackages)
            .filter { it.blocksApps && it.packageName !in covered }
            .map { killer ->
                ReliabilityIssue(
                    id = "killer-${killer.packageName}", policyId = entry.id,
                    severity = if (killer.verified) ReliabilitySeverity.ACTION_NEEDED else ReliabilitySeverity.ADVISORY,
                    title = "${killer.label} is installed",
                    detail = "This phone's ${killer.label} closes background apps that are not on its list, so calls and relaying can stop with the screen off." +
                        (if (killer.verified) "" else " (The package name is not confirmed by a source, so this is only a hint.)"),
                    steps = killer.steps.ifEmpty { BackgroundPolicy.GENERIC_STEPS },
                    actions = listOf(APP_DETAILS), validation = validationLabel(entry),
                )
            }
    }

    /** The newest measured stop reason (B-03 via [ProcessExitReport]), only when it was the phone and not the user or the app. */
    private fun exitIssues(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): List<ReliabilityIssue> {
        val cause = snapshot.lastExitCause ?: return emptyList()
        val text = snapshot.lastExitText ?: return emptyList()
        if (snapshot.lastExitAgeMs > RECENT_EXIT_MS) return emptyList()
        val entry = resolved.guidanceEntry(GuidanceTopic.PROCESS_EXIT) ?: return emptyList()
        val phone = cause == ExitCause.SYSTEM_POLICY.name || cause == ExitCause.MEMORY_OR_RESOURCES.name
        val user = cause == ExitCause.USER.name && snapshot.lastExitDuringSession
        val fault = cause == ExitCause.APP_FAULT.name
        if (!phone && !user && !fault) return emptyList()
        return listOf(
            ReliabilityIssue(
                id = "last-exit", policyId = entry.id,
                severity = if (phone) ReliabilitySeverity.ADVISORY else ReliabilitySeverity.INFO,
                title = when {
                    phone -> "Android stopped the app earlier"
                    user -> "The app was stopped while a call or stream was running"
                    else -> "The app stopped unexpectedly earlier"
                },
                detail = when {
                    phone -> "$text If this keeps happening, the battery settings for this app and for your phone maker can prevent it."
                    fault -> "The app stopped unexpectedly earlier."
                    else -> text
                },
                steps = if (phone) listOf("Settings > Apps > (this app) > Battery: Unrestricted.", "See the phone-maker steps below.") else emptyList(),
                actions = if (phone) listOf(APP_DETAILS) else emptyList(), validation = validationLabel(entry),
            ),
        )
    }

    /** B-15: the scan watchdog's verdict, shown only while no result has arrived since. */
    private fun scanPauseIssues(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): List<ReliabilityIssue> {
        val suspect = snapshot.scanPauseSuspect ?: return emptyList()
        val entry = resolved.guidanceEntry(GuidanceTopic.SCAN_PAUSED_BY_MAKER) ?: return emptyList()
        return when (suspect) {
            ScanSuspect.OEM_PAUSE.label -> listOf(
                ReliabilityIssue(
                    id = "scan-paused-by-maker", policyId = entry.id, severity = ReliabilitySeverity.ADVISORY,
                    title = "Your phone's battery manager may be pausing Bluetooth search",
                    detail = "With the screen off, no nearby phone has been found for a while although one was expected. Some phone makers pause " +
                        "Bluetooth search for background apps; the settings listed for your phone usually stop that.",
                    steps = listOf("Follow the steps for your phone maker below, then try again with the screen off."),
                    actions = listOf(APP_DETAILS), validation = validationLabel(entry),
                ),
            )
            ScanSuspect.DOZE.label -> listOf(
                ReliabilityIssue(
                    id = "scan-paused-by-doze", policyId = entry.id, severity = ReliabilitySeverity.INFO,
                    title = "The phone was resting, so Bluetooth search paused",
                    detail = "Android pauses background searching while the phone is idle. It resumes when the phone wakes up.",
                    steps = emptyList(), actions = emptyList(), validation = validationLabel(entry),
                ),
            )
            else -> emptyList()
        }
    }

    /** B-13: "your phone updated" and the system Bluetooth app's own battery setting. */
    private fun updateAndBluetoothAppIssues(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): List<ReliabilityIssue> {
        val issues = mutableListOf<ReliabilityIssue>()
        val maker = snapshot.manufacturer.trim().lowercase()
        val resets = maker in BackgroundOem.RESETS_AFTER_RESTART_MAKERS
        resolved.guidanceEntry(GuidanceTopic.PHONE_UPDATED)?.let { entry ->
            if (snapshot.phoneUpdatedRecently || (resets && snapshot.restartedRecently)) issues += ReliabilityIssue(
                id = "phone-updated", policyId = entry.id, severity = ReliabilitySeverity.INFO,
                title = if (snapshot.phoneUpdatedRecently) "Your phone was updated" else "Your phone restarted",
                detail = "Battery settings can go back to their defaults after " +
                    (if (snapshot.phoneUpdatedRecently) "a phone update" else "a restart on this phone maker") +
                    ". It is worth a quick look that this app and the Bluetooth app are still set to run in the background.",
                steps = listOf("Check this app's battery setting and the steps for your phone maker."),
                actions = listOf(APP_DETAILS), validation = validationLabel(entry),
            )
        }
        resolved.guidanceEntry(GuidanceTopic.SYSTEM_BLUETOOTH_APP)?.let { entry ->
            val relevant = snapshot.alwaysOnRole || snapshot.phoneUpdatedRecently || (resets && snapshot.restartedRecently)
            issues += ReliabilityIssue(
                id = "system-bluetooth-app", policyId = entry.id,
                severity = if (relevant) ReliabilitySeverity.ADVISORY else ReliabilitySeverity.INFO,
                title = "Android's Bluetooth app has its own battery setting",
                detail = "On newer Android versions the system Bluetooth app can be limited too. If connections drop with the screen off " +
                    "although this app is unrestricted, check it as well. This comes from community guides (AndroidAPS, xDrip+), " +
                    "not from Google, and this app cannot read or change that setting.",
                steps = buildList {
                    add("Settings > Apps > (menu) Show system apps > Bluetooth (and Legacy Bluetooth if listed) > Battery > Unrestricted.")
                    if (snapshot.sdkInt >= 35) add("On Android 15 and newer, open Allow background usage first, then choose Unrestricted.")
                    if (resets) add("Xiaomi phones may switch it back after a restart; look again after restarting.")
                },
                actions = listOf(
                    SettingsIntentSpec(
                        "Open the Bluetooth app's settings", action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        withPackageUri = true, targetPackage = snapshot.bluetoothPackage,
                    ),
                    SettingsIntentSpec("Open all apps", action = Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
                ),
                validation = validationLabel(entry),
            )
        }
        return issues
    }

    /** B-14: the incoming-call screen needs a background pop-up permission on Xiaomi, vivo/iQOO, OPPO, realme and OnePlus. */
    private fun incomingPopupIssues(snapshot: BackgroundSnapshot, resolved: ResolvedDevicePolicy): List<ReliabilityIssue> {
        val entry = resolved.guidanceEntry(GuidanceTopic.INCOMING_CALL_POPUP) ?: return emptyList()
        val blocked = snapshot.popupPermissionGranted == false || snapshot.incomingUi == "heads_up_only"
        return listOf(
            ReliabilityIssue(
                id = "incoming-call-popup", policyId = entry.id,
                severity = if (blocked) ReliabilitySeverity.ADVISORY else ReliabilitySeverity.INFO,
                title = if (blocked) "An incoming call did not open the call screen" else "Incoming calls may only show as a notification",
                detail = "On this phone maker's software the call screen can be blocked while the app is in the background unless the pop-up " +
                    "permission is allowed. Answering from the notification still works.",
                steps = listOf(
                    "Open the app settings and look under Permissions (or Other permissions) for Display pop-up windows while running in the background and Show on lock screen: allow them.",
                    "Redmi phones may also need Display over other apps.",
                ),
                actions = listOf(APP_DETAILS), validation = validationLabel(entry),
            ),
        )
    }

    /**
     * The Settings "Connection reliability" card: the issues worth a user's attention, most urgent first. Informational
     * items stay out. The phone-maker advice appears when something already points at trouble (an always-on role, a
     * measured kill, an installed killer, a pause), so a healthy phone shows a calm card. The battery issue reuses
     * [batteryExemptionIssue] (system prompt button), everything else opens the first screen that resolves.
     */
    internal fun cardIssues(context: Context, status: BackgroundReliabilityStatus = status(context)): List<ReliabilityCardIssue> =
        selectForCard(status).mapNotNull { issue ->
            if (issue.id == "battery-optimization") {
                batteryExemptionIssue(context)
            } else {
                ReliabilityCardIssue(
                    title = issue.title,
                    guidance = (listOf(issue.detail) + issue.steps.map { "- $it" }).joinToString("\n"),
                    settingsIntent = firstStandardIntent(context, issue),
                )
            }
        }

    /** JVM-testable selection and order for [cardIssues]. */
    internal fun selectForCard(status: BackgroundReliabilityStatus): List<ReliabilityIssue> {
        val s = status.snapshot
        val trouble = s.alwaysOnRole || s.lastExitCause in setOf(ExitCause.SYSTEM_POLICY.name, ExitCause.MEMORY_OR_RESOURCES.name) ||
            s.scanPauseSuspect == ScanSuspect.OEM_PAUSE.label || s.incomingUi == "heads_up_only" || s.phoneUpdatedRecently ||
            status.issues.any { it.severity == ReliabilitySeverity.ACTION_NEEDED }
        return status.issues
            .filter { it.severity != ReliabilitySeverity.INFO }
            .filter { trouble || !(it.id.startsWith("oem-") || it.id == "system-bluetooth-app") }
            .sortedBy { it.severity.ordinal }
    }

    /**
     * The card button starts one intent and has no fallback, so it takes the first standard Android settings action
     * (package visibility makes resolveActivity unreliable for them); vendor-private screens and deep links are left
     * to [openSettings], which falls back on an exception. The app's own details page always works.
     */
    private fun firstStandardIntent(context: Context, issue: ReliabilityIssue): Intent? {
        for (spec in issue.actions) {
            if (spec.className != null || spec.packageName != null) continue
            intentFor(context, spec)?.let { return it }
        }
        return intentFor(context, APP_DETAILS)
    }

    /** Android intents for [spec], or null when the spec cannot be built. */
    fun intentFor(context: Context, spec: SettingsIntentSpec): Intent? = runCatching {
        val spec = if (!FlavourBattery.directRequest && spec.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            spec.copy(action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, withPackageUri = false)
        } else spec
        val intent = when {
            spec.className != null && spec.packageName != null -> Intent().setClassName(spec.packageName, spec.className)
            // A documented deep link names the package that owns it (setPackage keeps the intent inside that app).
            spec.action != null -> Intent(spec.action).also { if (spec.packageName != null) it.setPackage(spec.packageName) }
            else -> return null
        }
        spec.intExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        if (spec.withPackageUri) intent.data = Uri.fromParts("package", spec.targetPackage ?: context.packageName, null)
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
            if (spec.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) FlavourBattery.explain(context)
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
data class ForegroundServiceOutcome(
    val requestedTypes: Int,
    val effectiveTypes: Int,
    val refused: String?,
    /** Camera/microphone types not asked for because their runtime permission is off (Android 14+ pre-check). */
    val permissionOffTypes: Int = 0,
) {
    val mismatch: Boolean get() = refused != null || requestedTypes != effectiveTypes

    /** Scorer contract (tools/score_device_run.py): `refused=` other than none is an unexpected event. */
    fun benchmarkLine(): String =
        "BENCHMARK foreground_service: requested=${foregroundTypeLabels(requestedTypes)} | " +
            "effective=${foregroundTypeLabels(effectiveTypes)} | refused=${refused?.replace('|', '/') ?: "none"}"

    fun summary(): String =
        "requested ${foregroundTypeLabels(requestedTypes)}, holds ${foregroundTypeLabels(effectiveTypes)}: ${refused ?: "types differ"}"

    /** Plain-language notice for the visible activity. */
    fun userNotice(): String = when {
        permissionOffTypes and FGS_CAMERA != 0 && permissionOffTypes and FGS_MICROPHONE != 0 ->
            "Camera and microphone permissions are off. Turn them on in Settings > Apps > MeshLive > Permissions."
        permissionOffTypes and FGS_CAMERA != 0 ->
            "Camera permission is off. Turn it on in Settings > Apps > MeshLive > Permissions to use the camera."
        permissionOffTypes and FGS_MICROPHONE != 0 ->
            "Microphone permission is off. Turn it on in Settings > Apps > MeshLive > Permissions to be heard."
        effectiveTypes == 0 ->
            "Android did not let this session keep running in the background. Open the app and start again."
        requestedTypes and (FGS_CAMERA or FGS_MICROPHONE) != effectiveTypes and (FGS_CAMERA or FGS_MICROPHONE) ->
            "Camera and microphone are unavailable while the app is in the background. Open the app to use them."
        requestedTypes and FGS_MEDIA_PLAYBACK != effectiveTypes and FGS_MEDIA_PLAYBACK ->
            "Listening may stop when the screen is off or the app is in the background. Keep the app open."
        // Only the Android 10-11 location type was dropped: the session runs, but search may pause with the screen off.
        (requestedTypes xor effectiveTypes) == FGS_LOCATION ->
            "Nearby phones may not be found while the screen is off. Keep the app open."
        else -> "The background session is limited. Open the app."
    }
}

private const val FGS_CONNECTED_DEVICE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
private const val FGS_CAMERA = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
private const val FGS_MICROPHONE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
private const val FGS_MEDIA_PLAYBACK = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
private const val FGS_LOCATION = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

internal fun foregroundTypeLabels(types: Int): String {
    if (types == 0) return "none"
    val labels = listOfNotNull(
        "connectedDevice".takeIf { types and FGS_CONNECTED_DEVICE != 0 },
        "camera".takeIf { types and FGS_CAMERA != 0 },
        "microphone".takeIf { types and FGS_MICROPHONE != 0 },
        "mediaPlayback".takeIf { types and FGS_MEDIA_PLAYBACK != 0 },
        // The Android 10-11 scan-delivery type; it used to print as 0x8 in the BENCHMARK foreground_service line.
        "location".takeIf { types and FGS_LOCATION != 0 },
    )
    val rest = types and (FGS_CONNECTED_DEVICE or FGS_CAMERA or FGS_MICROPHONE or FGS_MEDIA_PLAYBACK or FGS_LOCATION).inv()
    return (labels + listOfNotNull(rest.takeIf { it != 0 }?.let { "0x" + Integer.toHexString(it) })).joinToString("+")
}

/**
 * Only Android's two documented refusals qualify for the fallback (AOSP android14-release
 * ActiveServices.java 2297 and 2601-2611): ForegroundServiceStartNotAllowedException (background
 * start, API 31+) and the while-in-use type SecurityException ("... eligible state/exemptions to
 * access the foreground only permission"). Manifest bugs (Invalid/MissingForegroundServiceType,
 * any other SecurityException) propagate.
 */
/**
 * Android 14+ (AOSP ForegroundServiceTypePolicy): a foreground-service type whose permission is missing is refused with
 * SecurityException "Starting FGS with type <type> ... requires permissions: ...". Seen as a runtime permission that is
 * off (camera or microphone, "Ask every time" or an expired one-time grant); treated as a refusal of that type, never a
 * crash ([ForegroundStartSequence]).
 */
internal fun isForegroundTypePermissionMissing(error: Throwable): Boolean =
    error is SecurityException && error.message?.let { it.contains("Starting FGS with type") && it.contains("requires permissions") } == true

internal fun isForegroundServiceRefusal(error: Throwable): Boolean =
    // By simple name: the class is API 31+ and cannot be referenced on older phones.
    error.javaClass.simpleName == "ForegroundServiceStartNotAllowedException" ||
        (error is SecurityException && error.message?.contains("foreground only permission") == true)
