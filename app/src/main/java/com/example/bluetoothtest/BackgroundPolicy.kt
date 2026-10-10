package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * Background policy (OEM background restrictions: diagnostics and user guidance only). Owner: Bluetooth workstream.
 *
 * Entries here join DevicePolicyRegistry.entries (one registry). Use DevicePolicyRegistry matchers
 * and constants freely, but never DevicePolicyRegistry.entries (initialisation order).
 *
 * Nothing here keeps the app alive by force: no wake-lock loops, no restarting services, no
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS prompt. The entries only (1) detect Android's documented
 * restriction states and (2) tell the user, per phone maker, which settings keep a call or relay
 * running with the screen off. [BackgroundReliability] turns the active entries into UI issues.
 * Phone-maker steps are condensed from Don't Kill My App (community-maintained, dontkillmyapp.com,
 * repo commit [DKMA_COMMIT]); they are upstream-documented, not locally tested. Off-switch for each
 * entry: DEBUG `test_device_policy_off=<id>`.
 */
internal object BackgroundPolicy {
    private const val REVIEWED = "2026-10-10"
    const val DKMA_COMMIT = "3616bc8b3b6b3910ac204e107ff0dfd6064f877c"
    private const val DKMA_BLOB = "https://github.com/urbandroid-team/dont-kill-my-app/blob/$DKMA_COMMIT/_vendors"
    private val DOZE_DOC = PolicySource(
        PLATFORM_DOC, "https://developer.android.com/training/monitoring-device-state/doze-standby",
        "Doze and App Standby; apps may send users to ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
    )

    private fun check(
        id: String,
        title: String,
        kind: BackgroundCheckKind,
        matcher: DeviceMatcher,
        behaviour: String,
        sources: List<PolicySource>,
        confidence: PolicyConfidence,
        fallback: String,
        evidence: String = "none: diagnostics only; upstream-documented, not locally tested",
    ) = DevicePolicyEntry(
        id = id,
        title = title,
        matcher = matcher,
        effects = listOf(BackgroundCheck(kind)),
        behaviour = behaviour,
        sources = sources,
        sourceStatus = OFFICIAL,
        evidence = listOf(evidence),
        confidence = confidence,
        reviewed = REVIEWED,
        origin = "Background/OEM audit 2026-10-10: the app never told the user why it stopped in the background.",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = fallback,
    )

    val BATTERY_OPTIMIZATION = check(
        id = "bg-battery-optimization",
        title = "Battery optimisation is on for this app",
        kind = BackgroundCheckKind.BATTERY_OPTIMIZATION,
        matcher = DeviceMatcher(),
        behaviour = "Reports PowerManager.isIgnoringBatteryOptimizations()==false and links the battery optimisation list.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)",
                "true when the app is on the device-idle allow list"),
            DOZE_DOC,
        ),
        confidence = HIGH,
        fallback = "No battery-optimisation notice.",
    )

    val BACKGROUND_RESTRICTED = check(
        id = "bg-background-restricted",
        title = "The user restricted this app's background activity",
        kind = BackgroundCheckKind.BACKGROUND_RESTRICTED,
        matcher = DeviceMatcher(),
        behaviour = "Reports ActivityManager.isBackgroundRestricted() and links the app's settings page.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted()",
                "true when the user restricted background activity; background services and jobs are stopped"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/topic/performance/background-optimization",
                "Background restrictions"),
        ),
        confidence = HIGH,
        fallback = "No background-restriction notice.",
    )

    val STANDBY_BUCKET = check(
        id = "bg-standby-bucket",
        title = "App standby bucket is rare or restricted",
        kind = BackgroundCheckKind.STANDBY_BUCKET,
        matcher = DeviceMatcher(),
        behaviour = "Reports UsageStatsManager.getAppStandbyBucket() at RARE (40) or RESTRICTED (45, API 30+).",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/app/usage/UsageStatsManager#getAppStandbyBucket()",
                "STANDBY_BUCKET_RARE = 40, STANDBY_BUCKET_RESTRICTED = 45"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/topic/performance/appstandby", "App Standby Buckets"),
        ),
        confidence = HIGH,
        fallback = "No standby-bucket notice.",
    )

    val UNUSED_APP_RESTRICTIONS = check(
        id = "bg-unused-app-restrictions",
        title = "Unused-app restrictions may remove permissions",
        kind = BackgroundCheckKind.UNUSED_APP_RESTRICTIONS,
        matcher = DeviceMatcher(sdkMin = 30),
        behaviour = "Reports PackageManager.isAutoRevokeWhitelisted()==false: permissions (Nearby devices, camera, " +
            "microphone) are removed and the app hibernates after months unused. Links the app's settings.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/topic/performance/app-hibernation",
                "App hibernation / unused app restrictions; IntentCompat.createManageUnusedAppRestrictionsIntent"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/content/pm/PackageManager#isAutoRevokeWhitelisted()",
                "true when the app is exempt from permission auto-revocation"),
        ),
        confidence = MEDIUM,
        fallback = "No unused-app notice.",
    )

    val POWER_SAVE_MODE = check(
        id = "bg-power-save-mode",
        title = "Battery Saver is on",
        kind = BackgroundCheckKind.POWER_SAVE_MODE,
        matcher = DeviceMatcher(),
        behaviour = "Reports PowerManager.isPowerSaveMode() as information and links Battery Saver settings.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/os/PowerManager#isPowerSaveMode()",
                "Battery Saver restricts background work and may apply Doze-like limits"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/topic/performance/power/power-details", "Battery Saver"),
        ),
        confidence = MEDIUM,
        fallback = "No Battery Saver notice.",
    )

    val FGS_START_FALLBACK = DevicePolicyEntry(
        id = "bg-fgs-start-fallback",
        title = "Foreground service start failures are caught and reported",
        matcher = DeviceMatcher(sdkMin = 31),
        effects = listOf(ForegroundServiceStartFallback),
        behaviour = "Only Android's two refusals are handled: a background start (ForegroundServiceStartNotAllowedException) " +
            "or a while-in-use camera/microphone type refusal (SecurityException '... foreground only permission'). " +
            "A refused type falls back to connectedDevice; a refused fallback stops the service. Every start logs " +
            "BENCHMARK foreground_service with requested, effective and refused types; any difference fails a " +
            "benchmark session (score_device_run) and shows the user a notice (e.g. camera and microphone unavailable " +
            "in the background; open the app). Manifest bugs (Invalid/MissingForegroundServiceType, other " +
            "SecurityException) still propagate.",
        sources = listOf(
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start",
                "Android 12+: startForegroundService from the background throws ForegroundServiceStartNotAllowedException"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/develop/background-work/services/fgs/service-types",
                "Android 14+: camera/microphone types need their runtime permission and a while-in-use start"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/app/Service#startForeground(int,%20android.app.Notification,%20int)",
                "startForeground throws SecurityException/ForegroundServiceStartNotAllowedException when the type is not allowed"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no FGS start failure in saved runs (all sessions start from the visible activity)"),
        confidence = MEDIUM,
        confidenceNote = "Risky paths: an incoming call or re-armed stream while the app is in the background.",
        reviewed = REVIEWED,
        origin = "Background/OEM audit 2026-10-10: ActiveVideoSessionService.start/startForeground were unguarded.",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The exception propagates as before (app crash).",
    )

    val SONY_STAMINA = check(
        id = "bg-sony-stamina",
        title = "Sony STAMINA mode",
        kind = BackgroundCheckKind.SONY_STAMINA,
        matcher = DeviceMatcher(manufacturers = setOf("sony")),
        behaviour = "Reads Settings.Secure \"somc.stamina_mode\" where readable (older Xperia builds) and reports it on.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$DKMA_BLOB/sony.md",
                "Don't Kill My App (community-maintained): STAMINA mode detectable via Settings.Secure somc.stamina_mode on older builds"),
        ),
        confidence = LOW,
        fallback = "No STAMINA notice.",
    )

    private fun oem(
        key: String,
        vendor: String,
        manufacturers: Set<String>,
        dkmaFile: String,
        dkmaAward: Int,
        osCovered: String,
        steps: List<String>,
        note: String,
        confidence: PolicyConfidence,
        killerPackages: List<String>,
        vendorIntents: List<SettingsIntentSpec>,
    ) = DevicePolicyEntry(
        id = "bg-oem-$key",
        title = "$vendor background restrictions (Don't Kill My App)",
        matcher = DeviceMatcher(manufacturers = manufacturers),
        effects = listOf(
            OemBackgroundGuidance(
                vendorKey = key,
                vendor = vendor,
                steps = steps,
                note = note,
                osCovered = osCovered,
                severityScore = dkmaAward,
                killerPackages = killerPackages,
                settings = vendorIntents,
            ),
        ),
        behaviour = "Shows $vendor's settings steps (DKMA severity $dkmaAward/5) in the background-reliability " +
            "status" + (if (killerPackages.isNotEmpty()) " and reports installed killer apps $killerPackages" else "") +
            ". Guidance only; nothing is changed on the phone.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$DKMA_BLOB/$dkmaFile.md",
                "Don't Kill My App (community-maintained, not the vendor): $osCovered"),
            DOZE_DOC,
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: guidance only; upstream-documented, not locally tested"),
        confidence = confidence,
        confidenceNote = "DKMA reports from users; menu paths change between OS versions ($osCovered).",
        reviewed = REVIEWED,
        origin = "Background/OEM audit 2026-10-10 (Don't Kill My App per-vendor data).",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Generic Android guidance (battery optimisation and app settings).",
    )

    /** Phone makers with a Don't Kill My App page, matched by Build.MANUFACTURER (case-insensitive). */
    val OEM_GUIDANCE: List<DevicePolicyEntry> = listOf(
    oem(
        key = "samsung",
        vendor = "Samsung",
        manufacturers = setOf("samsung"),
        dkmaFile = "samsung",
        dkmaAward = 5,
        osCovered = "One UI / Android 11-14 (page covers Android 14, 13, 11, Pie/10, Oreo and older)",
        steps = listOf(
            "Settings > Apps > (your app) > Battery > set Unrestricted, or Battery optimization > All apps > (your app) > Don't optimize.",
            "Settings > Battery > Background usage limits: turn off Put unused apps to sleep and Auto-disable unused apps; remove the app from Sleeping/Deep sleeping apps.",
            "Add the app to Never sleeping apps (Battery > Background usage limits) so it is not put back to sleep after about 3 days.",
            "Optionally turn off Adaptive battery (Battery > More battery settings) and lock the app in Recent apps (long-press its icon > Keep open).",
            "Re-check these settings after firmware updates; Samsung may re-add apps to the sleeping lists.",
        ),
        note = "Since Android 11 Samsung blocks wake locks in foreground services and sleeps unused apps after about 3 days; settings can be reset by updates. Samsung says that from One UI 6.0 foreground services of apps targeting Android 14 are guaranteed per Android's FGS policy; the page has no developer-side solution.",
        confidence = MEDIUM,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "xiaomi",
        vendor = "Xiaomi",
        manufacturers = setOf("xiaomi", "redmi", "poco"),
        dkmaFile = "xiaomi",
        dkmaAward = 5,
        osCovered = "MIUI 10-14 (no HyperOS section on the page)",
        steps = listOf(
            "Settings > Apps > Manage apps > (your app) > Autostart: enable it (MIUI 14: App permissions > Background autostart).",
            "Settings > Apps > (your app) > Battery saver (Security > Battery > App battery saver) > No restrictions.",
            "In recent apps drag the app card down or tap the padlock to lock it; or Security > Boost speed > Settings > Lock apps.",
            "If available, turn off Battery saver / Ultra battery saver and set Keep running after screen off for the app.",
        ),
        note = "MIUI has no documented APIs for autostart or background limits; default settings break background work. The page links an open-source MIUI-autostart library that can read whether Autostart is enabled (tested on MIUI 10-14); HyperOS is not covered by the page text.",
        confidence = MEDIUM,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "huawei",
        vendor = "Huawei",
        manufacturers = setOf("huawei"),
        dkmaFile = "huawei",
        dkmaAward = 5,
        osCovered = "EMUI 4-10 (no HarmonyOS section)",
        steps = listOf(
            "Settings > Battery > App launch > (your app) > turn off Manage automatically and enable Auto-launch, Secondary launch and Run in background.",
            "Settings > Apps > Startup manager (or All > Startup manager) > allow the app.",
            "Settings > Battery > Battery optimization > All apps > (your app) > Don't allow (do not optimize).",
            "In System Manager, turn off Smart tune-up; on EMUI 6 and older set Protected apps and Keep running after screen off.",
            "If PowerGenie is present it kills non-whitelisted apps and can only be removed over ADB (see documented packages).",
        ),
        note = "EMUI 4 killed apps holding a wake lock over 60 minutes (HwPFWService) unless the tag was on a hard-coded whitelist; EMUI 9+ PowerGenie kills everything not whitelisted. Page shows a Huawei-on-Android-6 wake lock tag workaround only.",
        confidence = MEDIUM,
        killerPackages = listOf("com.huawei.powergenie", "com.huawei.android.hwaps"),
        vendorIntents = listOf(),
    ),
    oem(
        key = "oneplus",
        vendor = "OnePlus",
        manufacturers = setOf("oneplus"),
        dkmaFile = "oneplus",
        dkmaAward = 5,
        osCovered = "OxygenOS (version not specified; OnePlus 6+ and older covered)",
        steps = listOf(
            "Settings > Battery > Battery optimization > All apps > (your app) > Don't optimize.",
            "Settings > Battery > Battery optimization > three dots > Advanced optimization: turn off Deep optimization and Sleep standby optimization.",
            "Open Recent apps, long-press the app card or tap its menu, and tap Lock so the setting is not reverted.",
            "Disable App Auto-Launch restrictions for the app and check the Recent-apps clear behaviour so swiping does not kill it.",
            "Re-check after firmware updates; OnePlus resets these settings.",
        ),
        note = "OnePlus introduced one of the severest limits since the 5/6 and resets battery-optimization exemptions after firmware updates or randomly unless the app is locked in Recent apps. The page has no developer-side solution.",
        confidence = MEDIUM,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "meizu",
        vendor = "Meizu",
        manufacturers = setOf("meizu"),
        dkmaFile = "meizu",
        dkmaAward = 4,
        osCovered = "Flyme (version not specified)",
        steps = listOf(
            "Settings > Advanced settings > Battery manager > Power plan: Performance.",
            "Settings > Advanced settings > Battery manager > Protected apps: mark the app Protected.",
            "Settings > Apps > (your app) > Battery: enable Power-intensive prompt and Keep running after screen off.",
            "Security > Permissions > Background processes: allow the app to run in the background.",
        ),
        note = "Considered on par with Huawei and Xiaomi in background limits; no developer-side solution known.",
        confidence = MEDIUM,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "asus",
        vendor = "Asus",
        manufacturers = setOf("asus"),
        dkmaFile = "asus",
        dkmaAward = 4,
        osCovered = "ZenUI Power Master (version not specified)",
        steps = listOf(
            "Open Mobile Manager > PowerMaster > Settings (or Battery-saving options).",
            "Uncheck Clean up in suspend.",
            "Uncheck Auto-deny apps from auto starting.",
            "Confirm the app still runs after the screen turns off.",
        ),
        note = "Pre-installed Power Master blocks app starts and kills background tasks when the screen turns off by default. No developer solution on the page.",
        confidence = MEDIUM,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "oppo",
        vendor = "Oppo",
        manufacturers = setOf("oppo"),
        dkmaFile = "oppo",
        dkmaAward = 4,
        osCovered = "ColorOS 6 / Oppo F1S",
        steps = listOf(
            "Pin/lock the app in the recent apps screen.",
            "In the Security app, enable the app in Startup manager and the Floating app list; on ColorOS 6 also App info > Allow Auto Start-up.",
            "App info > Battery usage > Run in background (or choose a Power Saver mode that allows background).",
            "Turn off battery optimizations and give the service a persistent foreground notification; all four steps are needed.",
        ),
        note = "Only the Oppo F1S is documented: background and accessibility services are killed every time the screen turns off. No developer-side fix beyond a foreground notification.",
        confidence = MEDIUM,
        killerPackages = listOf(),
        vendorIntents = listOf(SettingsIntentSpec(label = "Open the startup manager (DKMA, unverified)", packageName = "com.coloros.safecenter", className = "com.coloros.safecenter.permission.Permission")),
    ),
    oem(
        key = "vivo",
        vendor = "Vivo",
        manufacturers = setOf("vivo"),
        dkmaFile = "vivo",
        dkmaAward = 3,
        osCovered = "Funtouch OS (Android 13 layout mentioned); no OriginOS section",
        steps = listOf(
            "Settings > More settings > Applications > Autostart: turn the app on (Funtouch OS 2.6 and older: i Manager > App manager > Autostart manager).",
            "Settings > Battery > Background power consumption management (or High background power consumption): enable the app.",
            "App info > Battery > Battery optimization: set the app to Not optimized (or Unrestricted battery usage).",
            "In Recent apps lock the app (swipe it down or use its menu > Lock/Lockdown).",
        ),
        note = "System restrictions on Vivo are not fully uncovered; apps started from the background need the Autostart permission. No developer-side solution known.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "realme",
        vendor = "realme",
        manufacturers = setOf("realme"),
        dkmaFile = "realme",
        dkmaAward = 3,
        osCovered = "realme UI (version not specified)",
        steps = listOf(
            "Settings > Battery > Power saving settings > App battery management > (your app).",
            "Enable Allow auto-launch, Allow foreground activity and Allow background activity; do not use Optimize battery use.",
            "Settings > Apps > Special access > Display pop-up windows while running in background: allow for alarm-type apps.",
            "Avoid Super power saving, Smart Performance Mode and App quick freeze for this app.",
        ),
        note = "System restrictions on realme are not fully uncovered; App quick freeze stops notifications and background running. No developer-side solution known.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "motorola",
        vendor = "Motorola",
        manufacturers = setOf("motorola"),
        dkmaFile = "motorola",
        dkmaAward = 3,
        osCovered = "Motorola Android 10+ (version not specified)",
        steps = listOf(
            "Settings > Apps > (your app) > App battery usage > tap Allow background usage > set Unrestricted.",
            "Settings > Battery > turn off Adaptive battery, and turn off Improve battery while inactive.",
            "App info > Battery > Background restriction/limits: if it says Background activity restricted, tap Remove.",
            "Search settings for Managing background apps and enable the app.",
        ),
        note = "Battery care (com.motorola.batterycare) can keep killing apps even with Improve battery while inactive disabled; the page only offers an adb uninstall. No developer solution.",
        confidence = LOW,
        killerPackages = listOf("com.motorola.batterycare"),
        vendorIntents = listOf(),
    ),
    oem(
        key = "lenovo",
        vendor = "Lenovo",
        manufacturers = setOf("lenovo"),
        dkmaFile = "lenovo",
        dkmaAward = 3,
        osCovered = "Lenovo P2 (version not specified)",
        steps = listOf(
            "In the overview of running apps, tap the padlock icon at the top right of the app card to lock it.",
            "Do not swipe the app away; swiping kills its background process.",
            "Battery/power optimization in Settings > Apps > (your app) > Battery makes no difference.",
        ),
        note = "Only the Lenovo P2 is documented; the padlock is required for the app to stay a foreground-priority process. No developer solution known.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "blackview",
        vendor = "Blackview",
        manufacturers = setOf("blackview"),
        dkmaFile = "blackview",
        dkmaAward = 3,
        osCovered = "Android 10-12 Blackview ROMs",
        steps = listOf(
            "Settings > Apps & notifications > (your app) > Advanced > Battery > Battery optimization > All apps > Don't optimize.",
            "Settings > Smart Assist > DuraSpeed: enable the app in the list, or disable DuraSpeed entirely.",
            "Basic Service app > App Manager > App auto start: enable the app; and disable Intelligent power saving in sleep mode.",
            "System Manager > App Management > Permission Manager > Auto Clean: add the app to the protected list; lock it in Recent apps.",
        ),
        note = "Restrictions are not fully discovered; guide confirmed on BV6300Pro (Android 10) and BV9200 (Android 12). No developer-side solution.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "tecno",
        vendor = "Tecno",
        manufacturers = setOf("tecno", "tecno mobile limited"),
        dkmaFile = "tecno",
        dkmaAward = 3,
        osCovered = "HiOS (version not specified)",
        steps = listOf(
            "Settings > Battery Lab > Battery Saving Settings > disable Power Saving Management For Apps.",
            "Phone Master > Toolbox > Auto-start management: enable the app and allow it to run in the background.",
            "Phone Master > Power Marathon: disable Power Boost; add the app to App Booster (max 4 apps).",
            "Lock the app in Recent apps; turn off Sleep Mode Optimization and Smart Scenes > Short Video Optimization.",
        ),
        note = "The aggressive battery setting freezes timers, services and foreground services of the app until it is opened manually. No developer-side solution.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "ulefone",
        vendor = "Ulefone and RugOne",
        manufacturers = setOf("ulefone"),
        dkmaFile = "ulefone",
        dkmaAward = 4,
        osCovered = "Ulefone/RugOne Android (version not specified)",
        steps = listOf(
            "Settings > Battery (or Battery Manager) > App launch / Manage apps automatically: turn off the master toggle, set the app to Manage manually.",
            "Enable Auto-launch, Secondary launch and Run in background for the app.",
            "Settings > Apps > (your app) > Battery > Unrestricted.",
            "Settings > DuraSpeed: turn it off; it may re-enable itself and Screen Off Killer may reset the exemptions.",
        ),
        note = "MediaTek DuraSpeed and a hidden Screen Off Killer silently turn themselves back on and reset Unrestricted to Optimized. The page's removal route is Shizuku + Canta with --user 0.",
        confidence = MEDIUM,
        killerPackages = listOf("com.mediatek.duraspeed", "com.pri.screenoff.killer"),
        vendorIntents = listOf(),
    ),
    oem(
        key = "wiko",
        vendor = "Wiko",
        manufacturers = setOf("wiko"),
        dkmaFile = "wiko",
        dkmaAward = 3,
        osCovered = "Wiko UI (version not specified)",
        steps = listOf(
            "Phone Assistant/Manager > Battery > turn Eco Mode OFF and disable Optimized mode (or whitelist the app).",
            "Battery > Apps optimization > (your app): App optimized off; Standby wakeup/sleep/network data set to No optimize.",
            "Battery > Auto launch management: enable App auto-launch and App secondary launch; disable Close apps after screen lock.",
            "Power saver > Advanced settings: add the app to the background process whitelist.",
        ),
        note = "Wiko devices are problematic and the list may not be exhaustive; a dialer code unchecks Lowram and Background-enable but resets after reboot. No developer-side solution.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "sony",
        vendor = "Sony",
        manufacturers = setOf("sony"),
        dkmaFile = "sony",
        dkmaAward = 2,
        osCovered = "Android 8-11+ Xperia (STAMINA mode)",
        steps = listOf(
            "Settings > Battery > STAMINA mode: turn it off, or avoid the STAMINA level Prefer battery time.",
            "Settings > Apps & notifications > Advanced > Special app access > Power saving feature: set the app to Excepted.",
            "Settings > Battery > three dots > Battery optimisation > Apps > (your app) > Don't optimize.",
            "Check Intelligence Engine and Adaptive battery, which can also break background tasks.",
        ),
        note = "On newer Android STAMINA mode is the rebranded Battery Saver; Prefer battery time cannot be detected but may show as missing notifications. Older devices expose Settings.Secure \"somc.stamina_mode\" to detect STAMINA.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "unihertz",
        vendor = "Unihertz",
        manufacturers = setOf("unihertz"),
        dkmaFile = "unihertz",
        dkmaAward = 2,
        osCovered = "Unihertz Android (version not specified)",
        steps = listOf(
            "Enable 'ignore optimizations' (battery optimization exemption) for the app first.",
            "Settings > Intelligent assistance > App blocker (or search settings for App blocker, e.g. Jelly Star).",
            "Open your app in the list, or disable App blocker completely.",
            "Uncheck Boot blocker, Start blocker, Background blocker and Background cleanup for the app.",
        ),
        note = "A third-party background blocker on Atom L/XL and Jelly 2/Star combines with default Android optimizations. No developer solution.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "nokia",
        vendor = "Nokia (HMD Global)",
        manufacturers = setOf("nokia", "hmd global"),
        dkmaFile = "nokia",
        dkmaAward = 0,
        osCovered = "Android 8-9 era (HMD says disabled Pie+)",
        steps = listOf(
            "Settings > Apps > See all apps > menu > Show system > Power saver > Force stop (only on older Evenwell builds; it restarts later).",
            "Android Pie and newer: HMD says Evenwell power saving apps are disabled since 8/2019; use standard battery optimization exemptions.",
            "Mediatek models (Nokia 3.1, 5.1): DuraSpeed has no user setting and needs the adb/WRITE_SECURE_SETTINGS workaround; it does not survive reboot.",
        ),
        note = "Evenwell Power saver killed all processes after about 20 minutes with the screen off, even whitelisted ones, and blocked foreground services started from background. Workaround is keeping the screen on or waking it every under 20 minutes.",
        confidence = LOW,
        killerPackages = listOf("com.evenwell.powersaving.g3", "com.evenwell.emm", "com.mediatek.duraspeed"),
        vendorIntents = listOf(),
    ),
    oem(
        key = "stock-android",
        vendor = "AOSP",
        manufacturers = setOf("google"),
        dkmaFile = "stock_android",
        dkmaAward = 0,
        osCovered = "Android 6-9+ stock/Pixel",
        steps = listOf(
            "Settings > Apps > (your app) > Battery (Advanced > Battery): choose Unrestricted or Not optimized.",
            "Check that Background restrictions / limits is not enabled for the app (Android 8+).",
            "If all else fails, Settings > Developer options can turn Doze off on old versions.",
        ),
        note = "Doze (Android 6+) and the Android 9 Background restriction option can break background work; sensor batching is limited by the 9 minute Doze alarm limit, workaround is a partial wake lock or setAlarmClock(). The page suggests asking users to exempt the app from battery optimization.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    oem(
        key = "htc",
        vendor = "HTC",
        manufacturers = setOf("htc"),
        dkmaFile = "htc",
        dkmaAward = 0,
        osCovered = "HTC Sense, Android 6+",
        steps = listOf(
            "Settings > Power > Battery optimization.",
            "Tap Not optimized > All apps.",
            "Tap your app > Don't optimize > Done.",
        ),
        note = "No non-standard optimizations found on HTC; standard AOSP Doze applies from Android 6.",
        confidence = LOW,
        killerPackages = listOf(),
        vendorIntents = listOf(),
    ),
    )

    val entries: List<DevicePolicyEntry> = listOf(
        BATTERY_OPTIMIZATION,
        BACKGROUND_RESTRICTED,
        STANDBY_BUCKET,
        UNUSED_APP_RESTRICTIONS,
        POWER_SAVE_MODE,
        FGS_START_FALLBACK,
        SONY_STAMINA,
    ) + OEM_GUIDANCE

    /**
     * Shown when no phone-maker entry matches (Honor, Infinix, itel, ZTE and others have no DKMA page),
     * condensed from DKMA's general page.
     */
    val GENERIC_STEPS: List<String> = listOf(
        "Settings > Apps > (this app) > Battery: choose Unrestricted or Not optimized.",
        "Check that Background restriction is not enabled for the app (Android 9+).",
        "Look for a phone-maker battery saver, auto-start or app-freezer setting and allow this app there.",
    )
}

/** Which Android-documented restriction state an entry checks. */
internal enum class BackgroundCheckKind {
    BATTERY_OPTIMIZATION,
    BACKGROUND_RESTRICTED,
    STANDBY_BUCKET,
    UNUSED_APP_RESTRICTIONS,
    POWER_SAVE_MODE,
    SONY_STAMINA,
}

internal data class BackgroundCheck(val kind: BackgroundCheckKind) : DevicePolicyEffect {
    override fun describe(): String = "report background state ${kind.name.lowercase()}"
}

internal data object ForegroundServiceStartFallback : DevicePolicyEffect {
    override fun describe(): String =
        "catch a refused foreground-service start; fall back to the connectedDevice type and report it"
}

/**
 * A settings screen to open. Either an action (with the app's package URI when [withPackageUri]) or
 * an explicit component; the UI tries the specs in order and falls back to the app's details page.
 */
data class SettingsIntentSpec(
    val label: String,
    val action: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val withPackageUri: Boolean = false,
)

internal data class OemBackgroundGuidance(
    val vendorKey: String,
    val vendor: String,
    val steps: List<String>,
    val note: String,
    val osCovered: String,
    /** Don't Kill My App severity (0 none .. 5 worst). */
    val severityScore: Int,
    val killerPackages: List<String>,
    val settings: List<SettingsIntentSpec>,
) : DevicePolicyEffect {
    override fun describe(): String = "$vendor background guidance (${steps.size} steps, DKMA severity $severityScore)"
}
