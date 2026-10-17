package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The banner items of batch B2: Q38 battery prompt, Location switch, Bluetooth crash loop, measured kills, scan pause, update notes. */
class BackgroundBannerTest {
    private fun traits(manufacturer: String, sdk: Int = 34, rom: Map<String, String> = emptyMap(), btPackage: String? = null) = DeviceTraits(
        "qcom", "taro", sdk, manufacturer = manufacturer,
        runtime = DeviceRuntimeTraits(
            romProperties = rom,
            bluetoothPackageVersions = btPackage?.let { mapOf(it to "1") }.orEmpty(),
        ),
    )

    private fun snapshot(
        manufacturer: String,
        sdk: Int = 34,
        ignoring: Boolean? = true,
        killers: Set<String> = emptySet(),
    ) = BackgroundSnapshot(sdk, manufacturer, ignoring, false, 10, false, true, null, killers, null)

    private fun issues(s: BackgroundSnapshot, off: Set<String> = emptySet(), rom: Map<String, String> = emptyMap()) =
        BackgroundReliability.evaluate(s, DeviceFamilyPolicy.resolve(traits(s.manufacturer, s.sdkInt, rom, s.bluetoothPackage), off))

    // ---- Q38 battery prompt ---------------------------------------------------------------------------------

    @Test
    fun theBatteryIssueIsActionNeededOnlyWhileAnAlwaysOnRoleIsOn() {
        val quiet = issues(snapshot("Google", ignoring = false)).single { it.id == "battery-optimization" }
        assertEquals(ReliabilitySeverity.ADVISORY, quiet.severity)
        val role = issues(snapshot("Google", ignoring = false).copy(alwaysOnRole = true)).single { it.id == "battery-optimization" }
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, role.severity)
        assertTrue(role.detail, "uses more battery" in role.detail)
        assertEquals(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, role.actions.first().action)
        // Exempt: no issue at all, even with the role on.
        assertTrue(issues(snapshot("Google", ignoring = true).copy(alwaysOnRole = true)).none { it.id == "battery-optimization" })
        // Unreadable state is not reported as a problem.
        assertTrue(issues(snapshot("Google", ignoring = null).copy(alwaysOnRole = true)).none { it.id == "battery-optimization" })
        // The entry's own off switch still removes it.
        assertTrue(issues(snapshot("Google", ignoring = false).copy(alwaysOnRole = true), setOf("bg-battery-optimization"))
            .none { it.id == "battery-optimization" })
    }

    @Test
    fun noIssueTextUsesAlarmWording() {
        val worst = snapshot("Xiaomi", ignoring = false, killers = setOf("com.mediatek.duraspeed")).copy(
            alwaysOnRole = true, locationBlockedReason = ScanEnvironment.REASON_LOCATION_OFF, sdkInt = 30, bluetoothCrashLoop = true,
            lastExitCause = ExitCause.SYSTEM_POLICY.name, lastExitText = "Android froze the app to save battery.", scanPauseSuspect = "oem_pause",
            phoneUpdatedRecently = true, restartedRecently = true, incomingUi = "heads_up_only",
        )
        val banned = listOf("danger", "warning", "critical", "fail", "broken", "dead", "crash", "attack", "error", "!")
        for (issue in issues(worst)) {
            val text = (listOf(issue.title, issue.detail) + issue.steps).joinToString(" ").lowercase()
            for (word in banned) assertFalse("${issue.id}: $word in '$text'", word in text)
        }
    }

    // ---- Location switch on Android 10-11 -------------------------------------------------------------------

    @Test
    fun locationOffOnAndroid10To11GivesAnActionNeededItemWithTheLocationScreen() {
        val s = snapshot("samsung", sdk = 30).copy(locationBlockedReason = ScanEnvironment.REASON_LOCATION_OFF)
        val issue = issues(s).single { it.id == "location-off" }
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, issue.severity)
        assertEquals("Turn on Location so Android lets this phone find nearby phones", issue.title)
        assertEquals(ScanEnvironment.POLICY_ID, issue.policyId)
        assertEquals(ScanEnvironment.LOCATION_SETTINGS_ACTION, issue.actions.first().action)
        assertTrue(issue.detail, "does not read or store where you are" in issue.detail)
        val permission = issues(s.copy(locationBlockedReason = ScanEnvironment.REASON_FINE_LOCATION_MISSING)).single { it.id == "location-permission" }
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, permission.severity)
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, permission.actions.first().action)
    }

    @Test
    fun locationGuidanceIsAndroid10To11OnlyAndHasItsOffSwitch() {
        val blocked = ScanEnvironment.REASON_LOCATION_OFF
        // Android 12+ has no such gate (neverForLocation): even a stray reason raises nothing.
        assertTrue(issues(snapshot("samsung", sdk = 31).copy(locationBlockedReason = blocked)).none { it.id.startsWith("location") })
        assertTrue(issues(snapshot("samsung", sdk = 30).copy(locationBlockedReason = blocked), setOf(ScanEnvironment.POLICY_ID))
            .none { it.id.startsWith("location") })
        assertTrue(issues(snapshot("samsung", sdk = 29).copy(locationBlockedReason = null)).none { it.id.startsWith("location") })
    }

    // ---- Bluetooth crash loop -------------------------------------------------------------------------------

    @Test
    fun aBluetoothCrashLoopIsAnAdvisoryWithTheBluetoothSettings() {
        val issue = issues(snapshot("Google").copy(bluetoothCrashLoop = true)).single { it.id == "bluetooth-crash-loop" }
        assertEquals(ReliabilitySeverity.ADVISORY, issue.severity)
        assertEquals("bluetooth-process-restart-recovery", issue.policyId)
        assertEquals(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, issue.actions.single().action)
        assertTrue(issue.detail, "restart" in issue.detail.lowercase())
        assertTrue(issues(snapshot("Google")).none { it.id == "bluetooth-crash-loop" })
        assertTrue(issues(snapshot("Google").copy(bluetoothCrashLoop = true), setOf("bluetooth-process-restart-recovery"))
            .none { it.id == "bluetooth-crash-loop" })
    }

    @Test
    fun theCrashLoopFlagComesFromThreeQuickOffEpisodes() {
        // BluetoothEpoch.crashLoopSuspected is what the snapshot reads; pin its threshold here so the banner stays meaningful.
        assertEquals(3, BluetoothEpochCore.CRASH_LOOP_EPISODES)
    }

    // ---- Measured kill reasons ------------------------------------------------------------------------------

    private fun exit(cause: ExitCause, session: Boolean = false, ageMs: Long = 60_000L) = snapshot("Google").copy(
        lastExitCause = cause.name, lastExitText = "Android froze the app to save battery.",
        lastExitDuringSession = session, lastExitAgeMs = ageMs,
    )

    @Test
    fun aKillByThePhoneShowsItsMeasuredReason() {
        for (cause in listOf(ExitCause.SYSTEM_POLICY, ExitCause.MEMORY_OR_RESOURCES)) {
            val issue = issues(exit(cause)).single { it.id == "last-exit" }
            assertEquals(ReliabilitySeverity.ADVISORY, issue.severity)
            assertEquals("Android stopped the app earlier", issue.title)
            assertTrue(issue.detail, issue.detail.startsWith("Android froze the app to save battery."))
            assertEquals("bg-process-exit", issue.policyId)
        }
    }

    @Test
    fun aUserStopOrCrashIsOnlyInformationAndOldOrSelfExitsAreSilent() {
        assertEquals(ReliabilitySeverity.INFO, issues(exit(ExitCause.USER, session = true)).single { it.id == "last-exit" }.severity)
        assertTrue(issues(exit(ExitCause.USER, session = false)).none { it.id == "last-exit" })
        assertEquals(ReliabilitySeverity.INFO, issues(exit(ExitCause.APP_FAULT)).single { it.id == "last-exit" }.severity)
        for (quiet in listOf(ExitCause.SELF, ExitCause.PLATFORM_RESTART, ExitCause.UNKNOWN)) {
            assertTrue(quiet.name, issues(exit(quiet)).none { it.id == "last-exit" })
        }
        assertTrue(issues(exit(ExitCause.SYSTEM_POLICY, ageMs = BackgroundReliability.RECENT_EXIT_MS + 1)).none { it.id == "last-exit" })
        assertTrue(issues(exit(ExitCause.SYSTEM_POLICY), setOf("bg-process-exit")).none { it.id == "last-exit" })
        // Android 10 has no exit history (API 30+): the entry does not apply.
        assertTrue(issues(exit(ExitCause.SYSTEM_POLICY).copy(sdkInt = 29)).none { it.id == "last-exit" })
    }

    // ---- Scan paused by the maker (B-15) --------------------------------------------------------------------

    @Test
    fun aMakerScanPauseIsShownOnlyForTheOemSuspect() {
        val oem = issues(snapshot("vivo").copy(scanPauseSuspect = ScanSuspect.OEM_PAUSE.label)).single { it.id == "scan-paused-by-maker" }
        assertEquals(ReliabilitySeverity.ADVISORY, oem.severity)
        assertTrue(oem.title, "battery manager may be pausing Bluetooth search" in oem.title)
        val doze = issues(snapshot("vivo").copy(scanPauseSuspect = ScanSuspect.DOZE.label)).single { it.id == "scan-paused-by-doze" }
        assertEquals(ReliabilitySeverity.INFO, doze.severity)
        for (other in listOf(ScanSuspect.BACKGROUND, ScanSuspect.TIMEOUT_WINDOW, ScanSuspect.QUOTA_SILENT, ScanSuspect.NONE)) {
            assertTrue(other.name, issues(snapshot("vivo").copy(scanPauseSuspect = other.label)).none { it.id.startsWith("scan-paused") })
        }
        assertTrue(issues(snapshot("vivo")).none { it.id.startsWith("scan-paused") })
        assertTrue(issues(snapshot("vivo").copy(scanPauseSuspect = "oem_pause"), setOf("bg-scan-paused-by-maker")).none { it.id.startsWith("scan-paused") })
    }

    // ---- Update, restart, system Bluetooth app (B-13) -------------------------------------------------------

    @Test
    fun theSystemBluetoothAppNoteBecomesAnAdvisoryWhenSomethingPointsAtTrouble() {
        val calm = issues(snapshot("Google")).single { it.id == "system-bluetooth-app" }
        assertEquals(ReliabilitySeverity.INFO, calm.severity)
        assertTrue(calm.steps.first(), "Bluetooth (and Legacy Bluetooth if listed) > Battery > Unrestricted" in calm.steps.first())
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, calm.actions.first().action)
        assertEquals("com.android.bluetooth", calm.actions.first().targetPackage)
        assertTrue(calm.detail, "community guides" in calm.detail && "not from Google" in calm.detail)
        for (relevant in listOf(
            snapshot("Google").copy(alwaysOnRole = true),
            snapshot("Google").copy(phoneUpdatedRecently = true),
            snapshot("Xiaomi").copy(restartedRecently = true),
        )) {
            assertEquals(ReliabilitySeverity.ADVISORY, issues(relevant).single { it.id == "system-bluetooth-app" }.severity)
        }
        // A restart only matters on the makers that are reported to reset settings.
        assertEquals(ReliabilitySeverity.INFO, issues(snapshot("Google").copy(restartedRecently = true)).single { it.id == "system-bluetooth-app" }.severity)
    }

    @Test
    fun theBluetoothAppStepsFollowTheAndroidVersionAndTheMaker() {
        val android15 = issues(snapshot("Xiaomi", sdk = 35)).single { it.id == "system-bluetooth-app" }
        assertTrue(android15.steps.any { "Allow background usage" in it })
        assertTrue(android15.steps.any { "Xiaomi phones may switch it back after a restart" in it })
        val android13 = issues(snapshot("Google", sdk = 33)).single { it.id == "system-bluetooth-app" }
        assertEquals(1, android13.steps.size)
        // The Mainline Bluetooth package is the one opened when the probe found it.
        val google = BackgroundReliability.evaluate(
            snapshot("Google").copy(bluetoothPackage = "com.google.android.bluetooth"),
            DeviceFamilyPolicy.resolve(traits("Google"), emptySet()),
        ).single { it.id == "system-bluetooth-app" }
        assertEquals("com.google.android.bluetooth", google.actions.first().targetPackage)
    }

    @Test
    fun aPhoneUpdateOrXiaomiRestartIsAnInformationNote() {
        val updated = issues(snapshot("Google").copy(phoneUpdatedRecently = true)).single { it.id == "phone-updated" }
        assertEquals(ReliabilitySeverity.INFO, updated.severity)
        assertEquals("Your phone was updated", updated.title)
        val restarted = issues(snapshot("Xiaomi").copy(restartedRecently = true)).single { it.id == "phone-updated" }
        assertEquals("Your phone restarted", restarted.title)
        assertTrue(issues(snapshot("Google").copy(restartedRecently = true)).none { it.id == "phone-updated" })
        assertTrue(issues(snapshot("Google")).none { it.id == "phone-updated" })
        assertTrue(issues(snapshot("Google").copy(phoneUpdatedRecently = true), setOf("bg-phone-updated")).none { it.id == "phone-updated" })
    }

    // ---- Incoming-call pop-up permission (B-14) -------------------------------------------------------------

    @Test
    fun thePopupNoteAppliesToTheRightMakersAndEscalatesOnEvidence() {
        for (maker in listOf("Xiaomi", "vivo", "iQOO", "OPPO", "realme", "OnePlus", "POCO")) {
            val issue = issues(snapshot(maker)).single { it.id == "incoming-call-popup" }
            assertEquals(maker, ReliabilitySeverity.INFO, issue.severity)
        }
        for (maker in listOf("samsung", "Google", "HONOR", "asus")) {
            assertTrue(maker, issues(snapshot(maker)).none { it.id == "incoming-call-popup" })
        }
        assertEquals(ReliabilitySeverity.ADVISORY, issues(snapshot("Xiaomi").copy(incomingUi = "heads_up_only")).single { it.id == "incoming-call-popup" }.severity)
        assertEquals(ReliabilitySeverity.ADVISORY, issues(snapshot("Xiaomi").copy(popupPermissionGranted = false)).single { it.id == "incoming-call-popup" }.severity)
        assertEquals(ReliabilitySeverity.INFO, issues(snapshot("Xiaomi").copy(popupPermissionGranted = true)).single { it.id == "incoming-call-popup" }.severity)
        assertTrue(issues(snapshot("Xiaomi"), setOf("bg-incoming-call-popup")).none { it.id == "incoming-call-popup" })
    }

    // ---- Killer packages ------------------------------------------------------------------------------------

    @Test
    fun aKillerPackageIsReportedWhateverTheManufacturerSays() {
        // Doogee-style white-label phone: no maker entry, DuraSpeed installed.
        val issue = issues(snapshot("DOOGEE", killers = setOf("com.mediatek.duraspeed"))).single { it.id == "killer-com.mediatek.duraspeed" }
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, issue.severity)
        assertEquals("bg-killer-package-scan", issue.policyId)
        assertTrue(issue.steps.first(), "DuraSpeed" in issue.steps.first())
        // The Ulefone entry already names DuraSpeed: it is not reported twice.
        val ulefone = issues(snapshot("ulefone", killers = setOf("com.mediatek.duraspeed")))
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, ulefone.single { it.id == "oem-ulefone" }.severity)
        assertTrue(ulefone.none { it.id.startsWith("killer-") })
        assertTrue(issues(snapshot("DOOGEE", killers = setOf("com.mediatek.duraspeed")), setOf("bg-killer-package-scan"))
            .none { it.id.startsWith("killer-") })
    }

    @Test
    fun anUnverifiedPackageNameOnlyGivesAnAdvisory() {
        val issue = issues(snapshot("SomeBrand", killers = setOf("com.hihonor.powergenie"))).single { it.id == "killer-com.hihonor.powergenie" }
        assertEquals(ReliabilitySeverity.ADVISORY, issue.severity)
        assertTrue(issue.detail, "not confirmed by a source" in issue.detail)
    }

    @Test
    fun aMarkerPackageSelectsTheFamilyGuidanceWhenTheManufacturerDoesNot() {
        // A re-branded Transsion phone with Phone Master installed but an unfamiliar manufacturer string.
        val result = issues(snapshot("Spice Mobile", killers = setOf("com.transsion.phonemaster")))
        val oem = result.single { it.id == "oem-infinix-itel" }
        assertEquals(ReliabilitySeverity.ADVISORY, oem.severity)
        assertEquals("bg-oem-infinix-itel", oem.policyId)
        assertTrue(result.none { it.id == "oem-generic" })
        assertTrue(result.none { it.id.startsWith("killer-") }) // the marker is not a killer
        // Its own off switch removes the marker-selected guidance too.
        assertTrue(issues(snapshot("Spice Mobile", killers = setOf("com.transsion.phonemaster")), setOf("bg-oem-infinix-itel"))
            .any { it.id == "oem-generic" })
        // A matched maker keeps its own entry.
        assertTrue(issues(snapshot("TECNO", killers = setOf("com.transsion.phonemaster"))).any { it.id == "oem-tecno" })
    }

    // ---- Xiaomi MIUI / HyperOS split ------------------------------------------------------------------------

    @Test
    fun xiaomiTextFollowsTheRomGeneration() {
        val hyper = issues(snapshot("Xiaomi", sdk = 34)).single { it.id == "oem-xiaomi" }
        assertTrue(hyper.detail, "looks like HyperOS" in hyper.detail)
        assertTrue(hyper.steps.any { "App smart power saving" in it })
        assertTrue(hyper.steps.any { "Autostart" in it })
        val miui = issues(snapshot("Xiaomi", sdk = 33)).single { it.id == "oem-xiaomi" }
        assertFalse(miui.detail, "HyperOS" in miui.detail.replace("no HyperOS section", ""))
        assertTrue(miui.steps.any { "Battery saver" in it })
        val property = issues(snapshot("Xiaomi", sdk = 33).copy(romProperties = mapOf(BackgroundOem.PROP_HYPEROS to "OS1.0"))).single { it.id == "oem-xiaomi" }
        assertTrue(property.detail, "looks like HyperOS" in property.detail)
    }

    @Test
    fun colorOsIssuesAreLabelledWithTheirBasis() {
        val old = issues(snapshot("OPPO", sdk = 30)).single { it.id == "oem-oppo" }
        assertTrue(old.detail, "ColorOS 6" in old.detail)
        val new = issues(snapshot("OPPO", sdk = 33)).single { it.id == "oem-oppo" }
        assertTrue(new.detail, "not confirmed" in new.detail)
        assertTrue(new.steps.any { "Background activity" in it })
    }

    @Test
    fun communityGuidanceIsNotAttributedToDontKillMyApp() {
        val honor = issues(snapshot("HONOR")).single { it.id == "oem-honor" }
        assertTrue(honor.detail, "Don't Kill My App" !in honor.detail)
        assertTrue(honor.detail, "support page" in honor.detail)
        assertTrue(issues(snapshot("samsung")).single { it.id == "oem-samsung" }.detail.contains("Don't Kill My App"))
    }

    // ---- Settings card --------------------------------------------------------------------------------------

    private fun statusOf(s: BackgroundSnapshot) = BackgroundReliabilityStatus(s, issues(s))

    @Test
    fun aHealthyPhoneGetsACalmCard() {
        assertTrue(BackgroundReliability.selectForCard(statusOf(snapshot("samsung"))).isEmpty())
        assertTrue(BackgroundReliability.selectForCard(statusOf(snapshot("Xiaomi"))).isEmpty())
    }

    @Test
    fun theCardListsUrgentItemsFirstAndHidesInformation() {
        val s = snapshot("Xiaomi", ignoring = false).copy(
            alwaysOnRole = true, lastExitCause = ExitCause.SYSTEM_POLICY.name, lastExitText = "Android froze the app to save battery.", lastExitAgeMs = 1_000L,
            phoneUpdatedRecently = true,
        )
        val card = BackgroundReliability.selectForCard(statusOf(s))
        val ids = card.map { it.id }
        assertEquals("battery-optimization", ids.first())
        assertTrue(ids.toString(), "oem-xiaomi" in ids && "last-exit" in ids && "system-bluetooth-app" in ids)
        assertTrue(ids.toString(), "phone-updated" !in ids && "incoming-call-popup" !in ids) // information stays out
        assertEquals(card.sortedBy { it.severity.ordinal }, card)
    }

    @Test
    fun anInstalledKillerBringsTheMakerAdviceIntoTheCard() {
        val s = snapshot("HUAWEI", killers = setOf("com.huawei.powergenie"))
        val ids = BackgroundReliability.selectForCard(statusOf(s)).map { it.id }
        assertEquals(listOf("oem-huawei"), ids)
        assertNull(BackgroundReliability.selectForCard(statusOf(snapshot("HUAWEI"))).firstOrNull())
    }

    // ---- Foreground type label ------------------------------------------------------------------------------

    @Test
    fun theLocationTypeHasALabelInsteadOfHex() {
        val location = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val connected = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        assertEquals("location", foregroundTypeLabels(location))
        assertEquals("connectedDevice+location", foregroundTypeLabels(connected or location))
        assertEquals("connectedDevice", foregroundTypeLabels(connected))
        // The service batch's labelling helper still agrees.
        assertEquals("connectedDevice+location", ServiceTypePolicy.foregroundTypeLabelsWithLocation(connected or location))
        val dropped = ForegroundServiceOutcome(connected or location, connected, "location type dropped, SecurityException")
        assertTrue(dropped.benchmarkLine(), "requested=connectedDevice+location | effective=connectedDevice" in dropped.benchmarkLine())
        assertEquals("Nearby phones may not be found while the screen is off. Keep the app open.", dropped.userNotice())
    }
}
