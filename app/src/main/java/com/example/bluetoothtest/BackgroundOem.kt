package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/*
 * Phone-maker guidance beyond the Don't Kill My App pages, and the manufacturer-independent killer-package
 * scan (device-family backlog B-11..B-16). Guidance only: nothing here changes a setting on the phone, and no
 * hidden API is used outside the DEBUG-only [MiuiAppOpsProbe].
 *
 * Menu names are folklore-grade and vary by version; every text says "usually" or names the version it was
 * written for. Package names are marked verified (named by a retrievable source) or unverified; an unverified
 * name only ever raises an ADVISORY, never ACTION_NEEDED.
 */

/** The topic of a plain-text guidance entry (its issue is built by [BackgroundReliability.evaluate]). */
internal enum class GuidanceTopic {
    SYSTEM_BLUETOOTH_APP,
    PHONE_UPDATED,
    INCOMING_CALL_POPUP,
    KILLER_PACKAGE_SCAN,
    PROCESS_EXIT,
    SCAN_PAUSED_BY_MAKER,
}

internal data class ReliabilityGuidance(val topic: GuidanceTopic) : DevicePolicyEffect {
    override fun describe(): String = "reliability guidance: ${topic.name.lowercase().replace('_', ' ')}"
}

/**
 * One package whose presence says something about the phone's background rules, whatever the manufacturer string
 * says (re-branded and white-label phones keep the vendor's task killer).
 *
 * @property blocksApps true: the app closes or freezes background apps (ACTION_NEEDED when [verified]).
 *   false: it is only the place where the maker's settings live (a marker for the guidance family).
 * @property verified the package name is named by a retrievable source ([provenance]); false = from memory.
 * @property familyKey the [OemBackgroundGuidance.vendorKey] of the guidance to show when no maker entry matches.
 */
internal data class KillerPackage(
    val packageName: String,
    val label: String,
    val blocksApps: Boolean,
    val verified: Boolean,
    val provenance: String,
    val steps: List<String>,
    val familyKey: String? = null,
)

/** A guidance text that replaces an entry's default steps on a specific ROM generation. */
internal data class OemGuidanceVariant(val label: String, val osCovered: String, val steps: List<String>)

internal object BackgroundOem {
    private const val REVIEWED = "2026-10-14"
    private const val DKMA = "https://github.com/urbandroid-team/dont-kill-my-app/blob/${BackgroundPolicy.DKMA_COMMIT}/_vendors"
    private val DOZE_DOC = PolicySource(
        PLATFORM_DOC, "https://developer.android.com/training/monitoring-device-state/doze-standby",
        "Doze and App Standby; phone-maker managers sit on top of Android's own rules",
    )

    /** ROM version property (UNVERIFIED name, see DeviceTraitsProbe.ROM_PROPERTIES) used to tell HyperOS from MIUI. */
    const val PROP_HYPEROS = "ro.mi.os.version.name"

    // ---- Manufacturer-independent package scan -------------------------------------------------------------

    private val DURASPEED_STEPS = listOf(
        "Settings > Smart Assist > DuraSpeed (the name varies): allow this app in the list, or turn DuraSpeed off.",
        "DuraSpeed may turn itself back on; look again after a restart.",
    )
    private val HUAWEI_STEPS = listOf(
        "Settings > Battery > App launch > (this app): turn off Manage automatically; allow Auto-launch, Secondary launch and Run in background.",
        "Settings > Battery > Battery optimization > All apps > (this app) > Don't allow (do not optimize).",
    )
    private val TRANSSION_STEPS = listOf(
        "Phone Master > Toolbox > Auto-start management: allow this app to start and run in the background.",
        "Settings > Battery Lab > Battery Saving Settings: turn off Power Saving Management For Apps; lock the app in Recent apps.",
    )

    /** Package names with their provenance; the same list is the `<queries>` hook for the manifest (B1 owns it). */
    val KILLERS: List<KillerPackage> = listOf(
        KillerPackage("com.mediatek.duraspeed", "MediaTek DuraSpeed", true, true,
            "named on the Don't Kill My App Ulefone, Nokia and Blackview pages", DURASPEED_STEPS),
        KillerPackage("com.huawei.powergenie", "Huawei PowerGenie", true, true,
            "named on the Don't Kill My App Huawei page", HUAWEI_STEPS, familyKey = "huawei"),
        KillerPackage("com.huawei.android.hwaps", "Huawei power manager (hwaps)", true, true,
            "named on the Don't Kill My App Huawei page", HUAWEI_STEPS, familyKey = "huawei"),
        KillerPackage("com.evenwell.powersaving.g3", "Evenwell power saver", true, true,
            "named on the Don't Kill My App Nokia page", listOf("Settings > Apps > See all apps > Show system > Power saver: it can only be stopped, and it comes back; use the standard battery optimization exemption.")),
        KillerPackage("com.evenwell.emm", "Evenwell power manager", true, true,
            "named on the Don't Kill My App Nokia page", listOf("Use the standard battery optimization exemption; this manager has no user setting.")),
        KillerPackage("com.pri.screenoff.killer", "Screen Off Killer", true, true,
            "named on the Don't Kill My App Ulefone page", listOf("Settings > Battery: turn off the screen-off cleaning, or allow this app in it.")),
        KillerPackage("com.motorola.batterycare", "Motorola Battery care", true, true,
            "named on the Don't Kill My App Motorola page", listOf("Settings > Battery: turn off Adaptive battery and Improve battery while inactive; set this app to Unrestricted.")),
        KillerPackage("com.hihonor.powergenie", "Honor power manager", true, false,
            "NAME FROM MEMORY: Honor derives from Huawei's PowerGenie; no retrievable source confirms this package", HUAWEI_STEPS, familyKey = "honor"),
        KillerPackage("com.transsion.phonemaster", "Transsion Phone Master", false, true,
            "published on Google Play by Transsion Holdings; its Auto-start management screen is named on the Don't Kill My App Tecno page",
            TRANSSION_STEPS, familyKey = "infinix-itel"),
        KillerPackage("com.hihonor.bluetooth", "Honor system Bluetooth app", false, true,
            "named in an AndroidAPS issue report as Honor's system Bluetooth package", emptyList(), familyKey = "honor"),
    )

    /** Every package the scan can ask about: the `<queries>` entries the manifest needs (Android 11+ package visibility). */
    val QUERY_PACKAGES: List<String> = KILLERS.map { it.packageName }

    /**
     * Query packages this batch needs that AndroidManifest.xml does not declare yet (none since the integration added
     * them). [BackgroundOemTest] fails if the manifest lacks one that is not listed.
     */
    val PENDING_MANIFEST_QUERIES: Set<String> = emptySet()

    fun killerFor(packageName: String): KillerPackage? = KILLERS.firstOrNull { it.packageName == packageName }

    /** Installed catalogue packages, most severe first: verified task killers, then unverified ones, then markers. */
    fun installed(installedPackages: Set<String>): List<KillerPackage> =
        KILLERS.filter { it.packageName in installedPackages }
            .sortedBy { if (it.blocksApps && it.verified) 0 else if (it.blocksApps) 1 else 2 }

    /** The guidance family a package implies when no maker entry matched the manufacturer string, or null. */
    fun familyFromPackages(installedPackages: Set<String>): String? =
        installed(installedPackages).firstNotNullOfOrNull { it.familyKey }

    // ---- ROM generation -----------------------------------------------------------------------------------

    /**
     * Xiaomi's HyperOS replaced MIUI on Android 14 (and on a few Android 13 builds). The property name is unverified,
     * so without it the Android version decides, and the text only says "looks like".
     */
    fun looksLikeHyperOs(sdk: Int, romProperties: Map<String, String>): Boolean =
        PROP_HYPEROS in romProperties || sdk >= 34

    private val HYPEROS = OemGuidanceVariant(
        label = "HyperOS",
        osCovered = "HyperOS (Android 14 and newer, some Android 13 builds); written from Xiaomi's HyperOS developer pages",
        steps = listOf(
            "Settings > Apps > Manage apps > (your app) > Autostart: turn it on (it is off by default).",
            "Settings > Battery > App smart power saving (or the app's Battery saver) > (your app) > No restrictions.",
            "Open Recent apps and lock this app's card (swipe it down or press and hold, then tap the padlock); swiping it away usually stops it.",
            "If incoming calls do not appear on a locked or busy phone, allow Display pop-up windows while running in the background in the app's permissions; Redmi phones may also need Display over other apps.",
            "Xiaomi may switch these back after a restart or an update; check them again.",
        ),
    )

    private val COLOROS_NEW = OemGuidanceVariant(
        label = "ColorOS 12+ / OxygenOS 12+",
        osCovered = "ColorOS 12 and newer, OxygenOS 12 and newer (Don't Kill My App covers ColorOS 6 and old OnePlus only; menu names below are not confirmed)",
        steps = listOf(
            "Settings > Battery > (your app), or Settings > Apps > App management > (your app) > Battery usage: allow Background activity.",
            "On the same screen allow Auto-launch (also called Allow auto-launch or Startup manager).",
            "Settings > Battery > More battery settings: do not optimise this app; turn off Sleep standby optimization and Deep optimization if present.",
            "Lock the app in Recent apps (press its card, then Lock); swiping a card usually stops it.",
            "Re-check after system updates; these makers are reported to reset the settings.",
        ),
    )

    /** The steps variant for this ROM generation, or null to keep the entry's own steps. */
    fun variantFor(vendorKey: String, sdk: Int, romProperties: Map<String, String>): OemGuidanceVariant? = when (vendorKey) {
        "xiaomi" -> if (looksLikeHyperOs(sdk, romProperties)) HYPEROS else null
        "oppo", "oneplus" -> if (sdk >= 31) COLOROS_NEW else null
        else -> null
    }

    /** Makers where the "incoming call screen needs a background pop-up permission" guidance applies (B-14). */
    val POPUP_PERMISSION_MAKERS: Set<String> =
        setOf("xiaomi", "redmi", "poco", "vivo", "iqoo", "oppo", "realme", "oneplus")

    /** Makers whose settings are reported to change back after a restart (Xiaomi family, AT-04). */
    val RESETS_AFTER_RESTART_MAKERS: Set<String> = setOf("xiaomi", "redmi", "poco")

    // ---- Registry entries ---------------------------------------------------------------------------------

    private fun oemEntry(
        key: String,
        vendor: String,
        manufacturers: Set<String>,
        steps: List<String>,
        note: String,
        severity: Int,
        osCovered: String,
        sources: List<PolicySource>,
        confidenceNote: String,
    ) = DevicePolicyEntry(
        id = "bg-oem-$key",
        title = "$vendor background restrictions",
        matcher = DeviceMatcher(manufacturers = manufacturers),
        effects = listOf(
            OemBackgroundGuidance(
                vendorKey = key, vendor = vendor, steps = steps, note = note, osCovered = osCovered,
                severityScore = severity, killerPackages = emptyList(), settings = emptyList(),
            ),
        ),
        behaviour = "Shows $vendor's settings steps in the background-reliability status. Guidance only; nothing is " +
            "changed on the phone. Not on Don't Kill My App: the steps come from the sources below and are written as 'usually'.",
        sources = sources,
        sourceStatus = PARTIAL,
        evidence = listOf("none: guidance only; upstream-documented, not locally tested"),
        confidence = LOW,
        confidenceNote = confidenceNote,
        reviewed = REVIEWED,
        origin = "Backlog B-11 (oem-bt.md B6, codec-background.md B8): these makers had only the generic text.",
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Generic Android guidance (battery optimisation and app settings).",
    )

    val HONOR = oemEntry(
        key = "honor",
        vendor = "Honor",
        manufacturers = setOf("honor"),
        steps = listOf(
            "Settings > Launch (or Phone Manager > App launch) > (your app): turn off Manage automatically, then allow Auto-launch, Secondary launch and Run in background.",
            "Settings > Battery optimization > All apps > (your app) > Don't allow (do not optimise).",
            "Settings > Battery: turn off Power saving mode and Ultra power saving mode.",
            "Open Recent apps and lock this app's card (swipe it down or press and hold, then tap the padlock).",
        ),
        note = "Honor phones run MagicOS (Honor left Huawei in 2020) and have no Don't Kill My App page. Honor's own support page names battery " +
            "optimisation, Android's sleep mechanism and third-party management apps as reasons for closed background apps and says it is still working on it. " +
            "One AndroidAPS report (Honor, Android 16 / MagicOS 10) could not restore Bluetooth by whitelisting the system Bluetooth app, so the effect on Bluetooth is unproven.",
        severity = 3,
        osCovered = "MagicOS (version not specified)",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://www.honor.com/global/support/content/en-us00428704/",
                "Honor: apps closed in the background; Launch > Run in background, battery optimization, power saving"),
            PolicySource(VENDOR_DOC, "https://docs.sportstracklive.com/android-battery-saving/honor",
                "third-party guide (not the vendor): App launch settings, Battery optimisation > Don't allow, lock in Recents"),
            DOZE_DOC,
        ),
        confidenceNote = "One vendor support page plus one third-party guide; menu paths change between MagicOS versions; Bluetooth effect unmeasured.",
    )

    val INFINIX_ITEL = oemEntry(
        key = "infinix-itel",
        vendor = "Infinix and itel",
        manufacturers = setOf("infinix", "infinix mobility limited", "itel", "itel mobile limited", "transsion holdings"),
        steps = listOf(
            "Settings > Battery Lab > Battery Saving Settings > turn off Power Saving Management For Apps.",
            "Phone Master > Toolbox > Auto-start management: enable the app and allow it to run in the background.",
            "Phone Master > Power Marathon: turn off Power Boost; add the app to App Booster (at most 4 apps).",
            "Lock the app in Recent apps; turn off Sleep Mode Optimization and Smart Scenes > Short Video Optimization.",
        ),
        note = "Infinix and itel are Transsion brands like Tecno; Don't Kill My App covers Tecno only, so these steps are the Tecno ones, assumed to " +
            "carry over (inference). Sleep Mode Optimization turns airplane mode on at night, which would cut every Bluetooth link: a possible cause of " +
            "unexplained overnight link loss, not confirmed on a phone.",
        severity = 3,
        osCovered = "XOS / itel OS (Transsion, version not specified); Tecno HiOS page",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$DKMA/tecno.md",
                "Don't Kill My App (community-maintained, Tecno only): Phone Master auto-start, Power Marathon, Sleep Mode Optimization airplane mode"),
            PolicySource(VENDOR_DOC, "https://en.wikipedia.org/wiki/Infinix_Mobile",
                "Infinix and itel are brands of Transsion Holdings, the owner of Tecno (basis for sharing the Tecno steps)"),
            DOZE_DOC,
        ),
        confidenceNote = "The Infinix/itel part is an inference from shared ownership; menu names are the Tecno page's and unconfirmed on these brands.",
    )

    private fun guidance(
        id: String,
        title: String,
        topic: GuidanceTopic,
        matcher: DeviceMatcher = DeviceMatcher(),
        behaviour: String,
        sources: List<PolicySource>,
        sourceStatus: PolicySourceStatus = PARTIAL,
        confidence: PolicyConfidence,
        confidenceNote: String,
        origin: String,
        fallback: String,
    ) = DevicePolicyEntry(
        id = id,
        title = title,
        matcher = matcher,
        effects = listOf(ReliabilityGuidance(topic)),
        behaviour = behaviour,
        sources = sources,
        sourceStatus = sourceStatus,
        evidence = listOf("none: guidance only; upstream-documented, not locally tested"),
        confidence = confidence,
        confidenceNote = confidenceNote,
        reviewed = REVIEWED,
        origin = origin,
        area = PolicyArea.BACKGROUND,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = fallback,
    )

    val SYSTEM_BLUETOOTH_APP = guidance(
        id = "bg-system-bluetooth-app",
        title = "The system Bluetooth app has its own battery setting",
        topic = GuidanceTopic.SYSTEM_BLUETOOTH_APP,
        behaviour = "Tells the user that the system 'Bluetooth' app (and 'Legacy Bluetooth' where listed) may need Unrestricted too, with the exact " +
            "path, and opens that app's settings page. Information at first, advisory while an always-on role is on, after a phone update, or after a " +
            "restart on Xiaomi. The app cannot read or change that setting.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://androidaps.readthedocs.io/en/latest/SettingUpAaps/SetupWizard.html",
                "AndroidAPS setup: newer Android applies battery optimisation to the system Bluetooth app too; set Bluetooth and Legacy Bluetooth to Unrestricted (community project, not Google)"),
            PolicySource(VENDOR_DOC, "https://navid200.github.io/xDrip/docs/BluetoothBatteryOpt.html",
                "xDrip+ guidance: Bluetooth system app battery optimisation; Xiaomi may re-enable it after every restart (community project)"),
            DOZE_DOC,
        ),
        confidence = MEDIUM,
        confidenceNote = "Repeated by two large community projects with success reports and also failures; no AOSP source; Honor attempt did not help.",
        origin = "Backlog B-13 (app-trackers AT-04, oem-bt S2-10).",
        fallback = "No system Bluetooth app notice.",
    )

    val PHONE_UPDATED = guidance(
        id = "bg-phone-updated",
        title = "The phone was updated or restarted since the app last ran",
        topic = GuidanceTopic.PHONE_UPDATED,
        behaviour = "Remembers Build.FINGERPRINT, the security patch and the boot count at each app start; for a week after they change it says " +
            "'your phone updated, battery settings may have reset', and on Xiaomi after a restart that its settings may have reset. Stores three short values locally.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://navid200.github.io/xDrip/docs/FAQ/AndroidUpdate.html",
                "xDrip+ FAQ: after an Android update some settings are set back to their defaults (community project)"),
            PolicySource(VENDOR_DOC, "$DKMA/samsung.md",
                "Don't Kill My App (community-maintained): Samsung may re-add apps to the sleeping lists after an update"),
            PolicySource(VENDOR_DOC, "$DKMA/oneplus.md",
                "Don't Kill My App (community-maintained): OnePlus resets battery optimisation after firmware updates"),
        ),
        confidence = MEDIUM,
        confidenceNote = "The reset is reported by several makers' users, not measured here; the banner only suggests a re-check, it never claims a reset happened.",
        origin = "Backlog B-13 (app-trackers AT-03/AT-04).",
        fallback = "No update notice.",
    )

    val INCOMING_CALL_POPUP = guidance(
        id = "bg-incoming-call-popup",
        title = "Incoming-call screen may need a background pop-up permission",
        topic = GuidanceTopic.INCOMING_CALL_POPUP,
        matcher = DeviceMatcher(manufacturers = POPUP_PERMISSION_MAKERS),
        behaviour = "On Xiaomi, vivo/iQOO, OPPO, realme and OnePlus phones, tells the user that the incoming-call screen can be blocked while the app is " +
            "in the background unless 'Display pop-up windows while running in the background' is allowed, and opens the app's settings page. Text only; " +
            "the hidden AppOps check is Experimental and off (test_miui_appops_probe).",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625",
                "Xiaomi HyperOS: starting a page from the background is denied by default"),
            PolicySource(VENDOR_DOC, "https://trtc.io/document/51022",
                "Tencent TUICallKit FAQ: Xiaomi needs 'Background pop-ups', Redmi also 'Display over other applications', vivo needs 'Background pop-ups' (SDK vendor, not the phone maker)"),
            PolicySource(VENDOR_DOC, "https://help.airdroid.com/hc/en-us/articles/38026717416859",
                "AirDroid: OPPO background pop-up note (third-party)"),
        ),
        confidence = LOW,
        confidenceNote = "One phone-maker page (Xiaomi) and SDK/third-party pages for the rest; permission names differ by version.",
        origin = "Backlog B-14 (codec-background B17).",
        fallback = "No pop-up permission text.",
    )

    val KILLER_PACKAGE_SCAN = guidance(
        id = "bg-killer-package-scan",
        title = "Task-killer apps installed on this phone",
        topic = GuidanceTopic.KILLER_PACKAGE_SCAN,
        behaviour = "Looks for installed packages known to close or freeze background apps (MediaTek DuraSpeed, Huawei PowerGenie, Motorola Battery care, " +
            "Evenwell power savers, Screen Off Killer, and the Transsion Phone Master / Honor markers) whatever the manufacturer string says. A verified " +
            "task killer makes its issue ACTION_NEEDED; an unverified package name or a marker only gives an advisory. Needs <queries> entries in the manifest.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$DKMA/ulefone.md", "Don't Kill My App: MediaTek DuraSpeed and Screen Off Killer reset the exemptions"),
            PolicySource(VENDOR_DOC, "$DKMA/huawei.md", "Don't Kill My App: PowerGenie kills apps that are not on its list"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/training/package-visibility",
                "Android 11+ hides other packages unless declared in <queries>"),
        ),
        confidence = MEDIUM,
        confidenceNote = "Package names come from community pages; presence is certain, the effect on this app is not measured. Honor's PowerGenie name is unverified.",
        origin = "Backlog B-11 (oem-bt B7, cpu-power section 9): the scan ran only for Ulefone and Nokia entries.",
        fallback = "Only the manufacturer's own entry reports its killer apps.",
    )

    val PROCESS_EXIT = guidance(
        id = "bg-process-exit",
        title = "Why Android last stopped the app",
        topic = GuidanceTopic.PROCESS_EXIT,
        matcher = DeviceMatcher(sdkMin = 30),
        behaviour = "Shows the newest ApplicationExitInfo record (read at app start by ProcessExitReport) in plain words when it was the phone, not the " +
            "user or the app: memory, a battery freeze, or the maker's own killer. Android 11+; read-only.",
        sources = listOf(
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)",
                "per-app history of why processes ended"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/app/ApplicationExitInfo",
                "exit reasons: LOW_MEMORY, FREEZER, USER_REQUESTED, OTHER (with a description)"),
        ),
        sourceStatus = OFFICIAL,
        confidence = MEDIUM,
        confidenceNote = "The API is documented; what a phone maker puts in REASON_OTHER varies and is shown as reported.",
        origin = "Backlog B-03/B-11 (cpu-power CP-24): the banner had no measured kill reason.",
        fallback = "No kill-reason line.",
    )

    val SCAN_PAUSED_BY_MAKER = guidance(
        id = "bg-scan-paused-by-maker",
        title = "The phone maker may be pausing Bluetooth search",
        topic = GuidanceTopic.SCAN_PAUSED_BY_MAKER,
        behaviour = "Shown only after the scan watchdog (ScanHealth) found a stale scan with the screen off or the phone idle on a maker whose power " +
            "manager is documented to pause background Bluetooth search, and no result has arrived since. Points to the maker's steps. No restart is made here.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://github.com/dotintent/react-native-ble-plx/issues/1172",
                "community issue report (not the vendor): vivo/Xiaomi/OPPO scan paused with the screen off; Background power consumption > Unrestricted fixed vivo"),
            PolicySource(VENDOR_DOC, "$DKMA/vivo.md", "Don't Kill My App (community-maintained): vivo background restrictions"),
            PolicySource(VENDOR_DOC, "$DKMA/xiaomi.md", "Don't Kill My App (community-maintained): Xiaomi background restrictions"),
        ),
        confidence = LOW,
        confidenceNote = "One issue report plus community pages; the watchdog names a suspect, it does not prove the cause.",
        origin = "Backlog B-15 (oem-bt A2/A3/S2-04, S-04 cause code).",
        fallback = "No scan-pause notice (the scan_stale log line still names the suspect).",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        HONOR, INFINIX_ITEL, SYSTEM_BLUETOOTH_APP, PHONE_UPDATED, INCOMING_CALL_POPUP, KILLER_PACKAGE_SCAN, PROCESS_EXIT, SCAN_PAUSED_BY_MAKER,
    )
}
