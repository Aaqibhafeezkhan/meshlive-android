package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permanent guard for the Android platform-behaviour class of bug (docs/dev/decisions/README.md "Platform behaviour audit
 * 2026-10-21"): a rule that Android enforces only on newer releases or only for a higher targetSdk, that lint has no
 * detector for, and that therefore ships unnoticed and fails on a user's phone. The first of the class was a
 * network-constrained JobScheduler job without ACCESS_NETWORK_STATE (SecurityException on Android 14+, targetSdk 34+,
 * crash on every start; the platform annotations carry no @RequiresPermission for JobInfo, so lint cannot see it).
 *
 * The checks read the manifests and sources as text. A failing check names the rule and the file; fix the code or, for the
 * few documented exceptions, extend the allowlist in this file with the reason.
 */
class PlatformBehaviourGuardTest {
    /** Gradle runs unit tests from the module directory; tolerate the repository root too. */
    private val appDir: File = if (File("src/main").isDirectory) File(".") else File("app")

    private fun file(path: String) = File(appDir, path)

    /** The manifest without XML comments (the comments name elements and permissions in prose). */
    private fun manifest(sourceSet: String): String =
        file("src/$sourceSet/AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val componentPattern = Regex("""<(activity|service|receiver|provider)\b([^>]*?)(/>|>(.*?)</\1>)""", RegexOption.DOT_MATCHES_ALL)

    private fun componentName(openTag: String) = Regex("android:name=\"([^\"]+)\"").find(openTag)?.groupValues?.get(1).orEmpty()

    private fun kotlinFiles(sourceSet: String): List<File> =
        file("src/$sourceSet/java").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()

    /** Source text with comment-only lines removed, so prose that names an API does not count as a use. */
    private fun code(file: File): String = file.readLines().filterNot { line ->
        val t = line.trim()
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }.joinToString("\n")

    /** The merged view of one distribution flavour: main plus that flavour's manifest and sources. */
    private data class Flavour(val name: String, val sourceSets: List<String>) {
        val manifestSets get() = sourceSets
    }

    private val flavours = listOf(
        Flavour("github", listOf("main", "github")),
        Flavour("fdroid", listOf("main", "fdroid")),
        Flavour("play", listOf("main", "play")),
    )

    private fun permissionTags(set: String, permission: String) =
        Regex("<uses-permission\\s[^>]*android:name=\"android\\.permission\\.$permission\"[^>]*>").findAll(manifest(set)).map { it.value }

    /** Declared and not removed by a flavour manifest (a flavour's tools:node="remove" wins in the merge). */
    private fun declares(flavour: Flavour, permission: String): Boolean =
        flavour.manifestSets.any { set -> permissionTags(set, permission).any { !it.contains("tools:node=\"remove\"") } } &&
            flavour.manifestSets.none { set -> permissionTags(set, permission).any { it.contains("tools:node=\"remove\"") } }

    // ---- JobScheduler / connectivity: ACCESS_NETWORK_STATE (Android 14 SecurityException; ConnectivityManager on every release)

    @Test
    fun networkConstrainedJobsAndConnectivityQueriesDeclareAccessNetworkState() {
        val uses = Regex("""setRequiredNetworkType|setRequiredNetwork\(|getActiveNetworkInfo|getNetworkCapabilities|\.activeNetwork\b|registerNetworkCallback|registerDefaultNetworkCallback|ConnectivityManager::class""")
        for (flavour in flavours) {
            val users = flavour.sourceSets.flatMap(::kotlinFiles).filter { uses.containsMatchIn(code(it)) }
            if (users.isNotEmpty()) {
                assertTrue(
                    "${flavour.name}: ${users.map { it.name }} use a network-constrained job or ConnectivityManager, so the manifest must declare " +
                        "ACCESS_NETWORK_STATE (Android 14 JobScheduler.schedule throws SecurityException without it)",
                    declares(flavour, "ACCESS_NETWORK_STATE"),
                )
            }
        }
    }

    @Test
    fun everyJobServiceIsProtectedByBindJobService() {
        for (set in listOf("main", "github", "fdroid", "play", "debug")) {
            componentPattern.findAll(manifest(set)).filter { it.groupValues[1] == "service" }.forEach { service ->
                val name = componentName(service.groupValues[2])
                val isJob = kotlinFiles(set).any { f -> Regex("""class\s+${Regex.escape(name.removePrefix("."))}\s*:\s*JobService""").containsMatchIn(code(f)) }
                if (isJob) assertTrue("$name must require android.permission.BIND_JOB_SERVICE", service.value.contains("BIND_JOB_SERVICE"))
            }
        }
    }

    // ---- Components: explicit android:exported (mandatory with an intent filter from Android 12 / targetSdk 31)

    /** Third-party components that declare neither an intent filter nor android:exported (implicitly not exported). */
    private val libraryComponentsWithoutExported = setOf("com.journeyapps.barcodescanner.CaptureActivity")

    @Test
    fun everyManifestComponentDeclaresExported() {
        for (set in listOf("main", "github", "fdroid", "play", "debug")) {
            componentPattern.findAll(manifest(set)).forEach {
                val name = componentName(it.groupValues[2])
                if (name in libraryComponentsWithoutExported && !it.value.contains("<intent-filter")) return@forEach
                if (it.groupValues[2].contains("tools:node=\"remove\"")) return@forEach // a flavour removing a main component

                assertTrue("$set manifest: $name has no android:exported (Android 12 install failure with an intent filter)", it.groupValues[2].contains("android:exported="))
            }
        }
    }

    @Test
    fun exportedComponentsAreOnlyTheLauncherAndTheDeliberateDebugReceivers() {
        val allowed = mapOf(
            "main" to setOf(".MainActivity"),
            "github" to emptySet(),
            "fdroid" to emptySet(),
            "play" to emptySet(),
            "debug" to setOf(".DebugTestAlertReceiver", ".DebugBluetoothRestartReceiver"),
        )
        for ((set, names) in allowed) {
            val exported = componentPattern.findAll(manifest(set))
                .filter { it.groupValues[2].contains("android:exported=\"true\"") }
                .map { componentName(it.groupValues[2]) }.toSet()
            assertEquals("exported components of the $set manifest (add one only with a security review)", names, exported)
        }
    }

    // ---- Foreground services: every declared type needs its FOREGROUND_SERVICE_<TYPE> permission (Android 14, targetSdk 34)

    @Test
    fun everyForegroundServiceTypeHasItsPermission() {
        val main = manifest("main")
        assertTrue("FOREGROUND_SERVICE", main.contains("android.permission.FOREGROUND_SERVICE\""))
        val types = Regex("android:foregroundServiceType=\"([^\"]+)\"").findAll(listOf("main", "github", "fdroid", "play", "debug").joinToString("\n") { manifest(it) })
            .flatMap { it.groupValues[1].split('|') }.toSet()
        assertTrue("the session service declares types", types.isNotEmpty())
        for (type in types) {
            val permission = "FOREGROUND_SERVICE_" + type.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
            assertTrue("foregroundServiceType=$type needs <uses-permission android.permission.$permission>", main.contains("android.permission.$permission\""))
        }
    }

    // ---- Dynamic receivers: RECEIVER_(NOT_)EXPORTED for anything but system broadcasts (Android 14 SecurityException, targetSdk 34)

    /**
     * Files whose registerReceiver calls listen only to platform broadcasts (Bluetooth adapter/ACL/bond state, location mode,
     * sticky battery); Android says to pass no flag for those. A new file must pass a flag or be added here with that reason.
     */
    private val systemBroadcastReceiverFiles = setOf(
        "AdvertisingBudget.kt", "BluetoothEpoch.kt", "BluetoothRestart.kt", "GapNameProbe.kt", "GattLinkLifetime.kt",
        "ScanRuntime.kt", "MainActivity.kt", "DebugSessionTelemetry.kt", "BleCocClient.kt", "CocAutoGatt.kt",
    )

    @Test
    fun everyDynamicReceiverEitherPassesAnExportFlagOrIsListedAsSystemOnly() {
        for (set in listOf("main", "github", "fdroid", "play", "debug")) {
            for (source in kotlinFiles(set)) {
                val text = code(source)
                Regex("""\bregisterReceiver\(""").findAll(text).forEach { match ->
                    val window = text.substring(match.range.first, minOf(text.length, match.range.first + 420))
                    val sticky = window.startsWith("registerReceiver(null")
                    val flagged = window.contains("RECEIVER_EXPORTED") || window.contains("RECEIVER_NOT_EXPORTED")
                    assertTrue(
                        "${source.name}: registerReceiver without RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED and not in systemBroadcastReceiverFiles " +
                            "(Android 14 throws SecurityException for non-system broadcasts)",
                        sticky || flagged || source.name in systemBroadcastReceiverFiles,
                    )
                    if (!flagged && !sticky) {
                        // An unflagged registration must only name platform actions.
                        assertTrue(
                            "${source.name}: an unflagged registerReceiver must use a platform action, not an app-defined one",
                            !Regex("""IntentFilter\(\s*"(com\.example|io\.github)""").containsMatchIn(window),
                        )
                    }
                }
            }
        }
    }

    // ---- PendingIntent: mutability flag always (Android 12 / targetSdk 31), explicit target when mutable (Android 14)

    @Test
    fun everyPendingIntentIsImmutableOrAnExplicitMutableOne() {
        for (set in listOf("main", "github", "fdroid", "play", "debug")) {
            for (source in kotlinFiles(set)) {
                val text = code(source)
                Regex("""PendingIntent\.get(Activity|Broadcast|Service|ForegroundService)\(""").findAll(text).forEach { match ->
                    val window = text.substring(match.range.first, minOf(text.length, match.range.first + 420))
                    val mutable = window.contains("FLAG_MUTABLE")
                    assertTrue(
                        "${source.name}: PendingIntent.get${match.groupValues[1]} needs FLAG_IMMUTABLE or FLAG_MUTABLE (Android 12 throws without one)",
                        window.contains("FLAG_IMMUTABLE") || mutable,
                    )
                    if (mutable) {
                        assertTrue(
                            "${source.name}: a FLAG_MUTABLE PendingIntent needs an explicit component or package (Android 14 throws for an implicit one)",
                            window.contains("setPackage") || window.contains("::class.java"),
                        )
                    }
                }
            }
        }
    }

    // ---- Notifications (Android 13 runtime permission), full-screen intent (Android 10 normal permission; 14 user toggle)

    @Test
    fun notificationUsersDeclarePostNotificationsAndFullScreenIntent() {
        val main = manifest("main")
        val posts = kotlinFiles("main").any { code(it).contains(".notify(") }
        if (posts) assertTrue("notify() is used, so POST_NOTIFICATIONS must be declared (Android 13)", main.contains("android.permission.POST_NOTIFICATIONS\""))
        val fullScreen = kotlinFiles("main").any { code(it).contains("setFullScreenIntent") }
        if (fullScreen) assertTrue("setFullScreenIntent is used, so USE_FULL_SCREEN_INTENT must be declared (Android 10)", main.contains("android.permission.USE_FULL_SCREEN_INTENT\""))
        val canCheck = kotlinFiles("main").any { code(it).contains("canUseFullScreenIntent") }
        if (fullScreen) assertTrue("Android 14 lets the user revoke the full-screen intent; the code must read canUseFullScreenIntent", canCheck)
    }

    // ---- Exact alarms (Android 12 / 14), install permission, boot receivers

    @Test
    fun exactAlarmsAreNotUsedWithoutThePermissionAndTheCheck() {
        val alarmUsers = kotlinFiles("main").plus(kotlinFiles("github")).filter {
            code(it).contains("AlarmManager") && Regex("""\.(setExact|setExactAndAllowWhileIdle|setAlarmClock)\(""").containsMatchIn(code(it))
        }
        if (alarmUsers.isNotEmpty()) {
            assertTrue("exact alarms need SCHEDULE_EXACT_ALARM and a canScheduleExactAlarms check (Android 12/14)",
                manifest("main").contains("SCHEDULE_EXACT_ALARM") && alarmUsers.all { code(it).contains("canScheduleExactAlarms") })
        }
    }

    @Test
    fun theInstallerFlowExistsOnlyWhereTheInstallPermissionIs() {
        val installerUsers = kotlinFiles("main").filter { Regex("""packageInstaller|canRequestPackageInstalls""").containsMatchIn(code(it)) }
        assertTrue("main (shared with F-Droid) must not install packages: ${installerUsers.map { it.name }}", installerUsers.isEmpty())
        assertTrue(manifest("github").contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest("fdroid").contains("REQUEST_INSTALL_PACKAGES") && manifest("fdroid").contains("tools:node=\"remove\""))
        val play = flavours.single { it.name == "play" }
        assertTrue("the Google Play build updates only through Play", !declares(play, "REQUEST_INSTALL_PACKAGES"))
        assertTrue("the Play build has no updater code", kotlinFiles("play").none { Regex("""packageInstaller|canRequestPackageInstalls""").containsMatchIn(code(it)) })
    }

    // ---- Google Play policy (docs/dev/architecture.md "Distribution and releases"): permissions Play restricts or forbids for this app

    @Test
    fun thePlayBuildDeclaresNoPermissionPlayWouldReject() {
        val play = flavours.single { it.name == "play" }
        for (permission in listOf(
            "ACCESS_BACKGROUND_LOCATION", // Location permissions policy: none of MeshLive's features needs it
            "QUERY_ALL_PACKAGES", // Package visibility policy: the <queries> names packages instead
            "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", // Exact alarm policy: no alarm or calendar core function
            "FOREGROUND_SERVICE_LOCATION", // no Play location FGS use case covers the Android 10-11 background scan
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", // Play opens the settings list instead of requesting an exemption
        )) {
            assertTrue("play declares $permission", !declares(play, permission))
        }
        val types = Regex("android:foregroundServiceType=\"([^\"]+)\"").findAll(manifest("play")).flatMap { it.groupValues[1].split('|') }.toSet()
        assertEquals("the Play session service types (each needs a Play Console declaration)", setOf("camera", "microphone", "connectedDevice", "mediaPlayback"), types)
    }

    @Test
    fun theBootReceiverStartsOnlyTheConnectedDeviceService() {
        // Android 15 (targetSdk 35): BOOT_COMPLETED receivers cannot start camera, microphone, mediaPlayback, dataSync,
        // phoneCall or mediaProjection foreground services (ForegroundServiceStartNotAllowedException).
        val boot = file("src/main/java/com/example/bluetoothtest/BootResume.kt").readText()
        assertTrue(boot.contains("usesCamera = false"))
        assertTrue(boot.contains("usesMicrophone = false"))
    }

    // ---- Targeting 36: back, large screens, backup, 16 KB pages

    @Test
    fun noCodeUsesTheBackKeyPathsPredictiveBackRemoved() {
        // Android 16, targetSdk 36: onBackPressed() and KEYCODE_BACK are no longer called; use OnBackPressedCallback.
        for (source in kotlinFiles("main").plus(kotlinFiles("github"))) {
            val text = code(source)
            assertTrue("${source.name} overrides onBackPressed", !Regex("""override fun onBackPressed\s*\(""").containsMatchIn(text))
            assertTrue("${source.name} handles KEYCODE_BACK", !text.contains("KEYCODE_BACK"))
        }
    }

    @Test
    fun portraitLockedAppKeepsItsLargeScreenOptOutUntilTargetSdk37() {
        // Android 16, targetSdk 36: screenOrientation/resizeableActivity are ignored on sw >= 600dp unless this property is set;
        // the opt-out is removed for targetSdk 37, so raising targetSdk needs adaptive layouts first.
        val main = manifest("main")
        assertTrue(main.contains("android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"))
        val targetSdk = Regex("targetSdk\\s*=\\s*(\\d+)").find(file("build.gradle.kts").readText())!!.groupValues[1].toInt()
        assertTrue("targetSdk $targetSdk: the large-screen opt-out does not exist at 37; make the layouts adaptive before raising it", targetSdk < 37)
    }

    @Test
    fun backupAndDeviceTransferAreExplicitlyExcluded() {
        // Android 12+: allowBackup="false" is not honoured by every maker's device-to-device transfer; explicit rules are.
        val main = manifest("main")
        assertTrue(main.contains("android:allowBackup=\"false\""))
        assertTrue(main.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = file("src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section[^>]*>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(rules)?.groupValues?.get(1)
            assertTrue("$section section present", body != null)
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue("$section excludes $domain", body!!.contains("<exclude domain=\"$domain\""))
            }
        }
    }

    @Test
    fun fineLocationIsNeverRequestedWithoutCoarseOnAndroid12() {
        // Android 12+ (targetSdk 31+) ignores a fine-location runtime request that does not carry coarse location
        // ("ACCESS_FINE_LOCATION must be requested with ACCESS_COARSE_LOCATION"); lint CoarseFineLocation also flags the manifest.
        val main = manifest("main")
        val fine = Regex("<uses-permission\\s[^>]*ACCESS_FINE_LOCATION[^>]*>").find(main)!!.value
        val coarse = Regex("<uses-permission\\s[^>]*ACCESS_COARSE_LOCATION[^>]*>").find(main)?.value
        assertTrue("ACCESS_COARSE_LOCATION must be declared next to ACCESS_FINE_LOCATION", coarse != null)
        assertEquals(Regex("maxSdkVersion=\"(\\d+)\"").find(fine)!!.groupValues[1], Regex("maxSdkVersion=\"(\\d+)\"").find(coarse!!)!!.groupValues[1])
        for (sdk in 29..32) {
            val asked = OfflineAppSharePolicy.permissionsFor(sdk)
            if ("android.permission.ACCESS_FINE_LOCATION" in asked && sdk >= 31) {
                assertTrue("API $sdk asks fine without coarse: $asked", "android.permission.ACCESS_COARSE_LOCATION" in asked)
            }
        }
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), OfflineAppSharePolicy.permissionsFor(30))
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            OfflineAppSharePolicy.permissionsFor(31),
        )
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), OfflineAppSharePolicy.permissionsFor(33))
    }

    @Test
    fun nativeLibrariesAreLinkedFor16KbPages() {
        // Android 15+ devices with 16 KB pages (Google Play requirement from 2025-11-01 for targetSdk 35+).
        val cmake = file("src/main/cpp/CMakeLists.txt").readText()
        assertTrue(cmake.contains("max-page-size=16384"))
    }
}
