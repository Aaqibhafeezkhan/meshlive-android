package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundOemTest {
    private fun traits(manufacturer: String, sdk: Int = 34, rom: Map<String, String> = emptyMap()) = DeviceTraits(
        "mt6768", "mt6768", sdk, manufacturer = manufacturer,
        runtime = DeviceRuntimeTraits(romProperties = rom),
    )

    private fun resolve(manufacturer: String, sdk: Int = 34, off: Set<String> = emptySet()) =
        DeviceFamilyPolicy.resolve(traits(manufacturer, sdk), off)

    private fun oemKeys(manufacturer: String, sdk: Int = 34) =
        resolve(manufacturer, sdk).effects<OemBackgroundGuidance>().map { it.vendorKey }

    // ---- registry rules -------------------------------------------------------------------------------------

    @Test
    fun everyEntryOfTheBatchMeetsTheStrictRules() {
        val own = BackgroundOem.entries
        assertEquals(own.size, own.map { it.id }.toSet().size)
        for (entry in own + BackgroundPolicy.BATTERY_OPTIMIZATION) {
            val problems = DevicePolicyEntryRules.violations(entry, strict = true)
            assertEquals("${entry.id}: $problems", emptyList<String>(), problems)
            assertEquals(entry.id, PolicyArea.BACKGROUND, entry.area)
            assertEquals(entry.id, PolicyValidation.UPSTREAM_DOCUMENTED, entry.validation)
            assertTrue(entry.id, entry.fallback.isNotBlank())
            assertTrue(entry.id, entry.sources.isNotEmpty() && entry.sources.all { it.reference.startsWith("http") })
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertFalse(entry.id, entry.proposed)
        }
        // Text-only guidance never claims HIGH confidence on community sources.
        assertTrue(BackgroundOem.entries.none { it.confidence == PolicyConfidence.HIGH })
    }

    @Test
    fun theBatteryEntryDescribesTheOfferedPrompt() {
        val entry = BackgroundPolicy.BATTERY_OPTIMIZATION
        assertTrue(entry.behaviour, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in entry.behaviour)
        assertTrue(entry.behaviour, "ACTION_NEEDED" in entry.behaviour)
        assertTrue(entry.sources.any { it.reference.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") })
        // The old ban text is gone from the policy's own KDoc-visible entry texts.
        assertFalse(entry.behaviour.contains("no REQUEST_IGNORE"))
    }

    @Test
    fun allEntriesReachTheRegistryAndOffSwitchesWork() {
        val registry = DevicePolicyRegistry.entries.map { it.id }
        assertTrue(registry.containsAll(BackgroundOem.entries.map { it.id }))
        assertTrue(registry.containsAll(BackgroundPolicy.OEM_GUIDANCE.map { it.id }))
        assertTrue(resolve("HONOR").isActive("bg-oem-honor"))
        assertFalse(resolve("HONOR", off = setOf("bg-oem-honor")).isActive("bg-oem-honor"))
        assertFalse(resolve("HONOR", off = setOf("all")).isActive("bg-oem-honor"))
    }

    // ---- makers ---------------------------------------------------------------------------------------------

    @Test
    fun honorInfinixItelAndIqooHaveGuidance() {
        assertEquals(listOf("honor"), oemKeys("HONOR"))
        for (name in listOf("INFINIX", "Infinix mobility limited", "itel", "ITEL MOBILE LIMITED")) {
            assertEquals(name, listOf("infinix-itel"), oemKeys(name))
        }
        assertEquals(listOf("vivo"), oemKeys("vivo"))
        assertEquals(listOf("vivo"), oemKeys("iQOO"))
        // Tecno keeps its own entry; Honor does not inherit Huawei's.
        assertEquals(listOf("tecno"), oemKeys("TECNO MOBILE LIMITED"))
        assertEquals(listOf("huawei"), oemKeys("HUAWEI"))
        val vivo = BackgroundPolicy.OEM_GUIDANCE.single { it.id == "bg-oem-vivo" }
        assertTrue(vivo.effects.filterIsInstance<OemBackgroundGuidance>().single().steps.any { "Allow background usage > Unrestricted" in it })
    }

    @Test
    fun everyOemTextStaysShortAndNamesItsBasis() {
        for (entry in BackgroundOem.entries.filter { e -> e.effects.any { it is OemBackgroundGuidance } }) {
            val g = entry.effects.filterIsInstance<OemBackgroundGuidance>().single()
            assertTrue(entry.id, g.steps.size in 2..6 && g.steps.all { it.length <= 200 })
            assertTrue(entry.id, g.note.isNotBlank() && g.osCovered.isNotBlank())
        }
        val infinix = BackgroundOem.INFINIX_ITEL.effects.filterIsInstance<OemBackgroundGuidance>().single()
        assertTrue(infinix.note, "inference" in infinix.note)
        assertTrue(infinix.note, "airplane mode" in infinix.note)
        val tecno = BackgroundPolicy.OEM_GUIDANCE.single { it.id == "bg-oem-tecno" }.effects.filterIsInstance<OemBackgroundGuidance>().single()
        assertTrue(tecno.note, "airplane mode" in tecno.note)
    }

    @Test
    fun samsungKeepsAdvisoryCitesBitchatAndClaimsNoServiceGuarantee() {
        val samsung = BackgroundPolicy.OEM_GUIDANCE.single { it.id == "bg-oem-samsung" }
        val g = samsung.effects.filterIsInstance<OemBackgroundGuidance>().single()
        val allText = (listOf(samsung.behaviour, g.note, g.osCovered) + g.steps).joinToString(" ").lowercase()
        assertFalse(allText, "guaranteed" in allText)
        assertTrue(g.note, "not assumed to be enough" in g.note)
        assertTrue(samsung.sources.any { it.reference == "https://github.com/permissionlesstech/bitchat-android/issues/124" })
        assertTrue(g.steps.any { "Adaptive battery" in it })
        assertTrue(g.steps.any { "Put unused apps to sleep" in it })
        assertTrue(g.steps.any { "Never auto sleeping apps" in it })
        val link = g.settings.single()
        assertEquals("com.samsung.android.lool", link.packageName)
        assertEquals(2, link.intExtras["activity_type"])
        // The Samsung developer page stays cited for the deep link only, with the statement marked as not relied on.
        assertTrue(samsung.sources.single { it.reference.contains("developer.samsung.com") }.note.contains("NOT relied on"))
        // Severity stays advisory (conflict K3): no killer, so ADVISORY.
        val issue = BackgroundReliability.evaluate(
            BackgroundSnapshot(34, "samsung", true, false, 10, false, true, null, emptySet(), null),
            resolve("samsung"),
        ).single { it.id == "oem-samsung" }
        assertEquals(ReliabilitySeverity.ADVISORY, issue.severity)
    }

    // ---- ROM generation -------------------------------------------------------------------------------------

    @Test
    fun hyperOsAndMiuiGetDifferentText() {
        assertNull(BackgroundOem.variantFor("xiaomi", 33, emptyMap()))
        val byVersion = BackgroundOem.variantFor("xiaomi", 34, emptyMap())!!
        assertEquals("HyperOS", byVersion.label)
        val byProperty = BackgroundOem.variantFor("xiaomi", 33, mapOf(BackgroundOem.PROP_HYPEROS to "OS1.0"))!!
        assertEquals("HyperOS", byProperty.label)
        assertTrue(byVersion.steps.any { "App smart power saving" in it })
        assertTrue(byVersion.steps.all { it.length <= 200 })
        assertNull(BackgroundOem.variantFor("samsung", 34, emptyMap()))
    }

    @Test
    fun colorOsTwelveAndNewerIsLabelledAsUnconfirmed() {
        assertNull(BackgroundOem.variantFor("oppo", 30, emptyMap()))
        assertNull(BackgroundOem.variantFor("oneplus", 30, emptyMap()))
        for (key in listOf("oppo", "oneplus")) {
            val variant = BackgroundOem.variantFor(key, 33, emptyMap())!!
            assertTrue(variant.osCovered, "Don't Kill My App covers ColorOS 6" in variant.osCovered)
            assertTrue(variant.osCovered, "not confirmed" in variant.osCovered)
            assertTrue(variant.steps.all { it.length <= 200 })
        }
    }

    // ---- package scan ---------------------------------------------------------------------------------------

    @Test
    fun theKillerCatalogueStatesWhatIsVerified() {
        val byName = BackgroundOem.KILLERS.associateBy { it.packageName }
        assertEquals(BackgroundOem.KILLERS.size, byName.size)
        for (name in listOf("com.mediatek.duraspeed", "com.huawei.powergenie", "com.motorola.batterycare", "com.evenwell.emm")) {
            assertTrue(name, byName.getValue(name).verified && byName.getValue(name).blocksApps)
        }
        assertFalse("from memory", byName.getValue("com.hihonor.powergenie").verified)
        assertTrue(byName.getValue("com.hihonor.powergenie").provenance.contains("FROM MEMORY"))
        // The Phone Master app is where the settings live, not a killer.
        assertFalse(byName.getValue("com.transsion.phonemaster").blocksApps)
        assertTrue(BackgroundOem.KILLERS.all { it.provenance.isNotBlank() })
        assertEquals(BackgroundOem.KILLERS.map { it.packageName }, BackgroundOem.QUERY_PACKAGES)
        // Every package the existing per-maker entries name is in the catalogue.
        val named = BackgroundPolicy.OEM_GUIDANCE.flatMap { it.effects.filterIsInstance<OemBackgroundGuidance>() }.flatMap { it.killerPackages }
        assertTrue(named.toString(), BackgroundOem.QUERY_PACKAGES.containsAll(named))
    }

    @Test
    fun theScanIsByInstalledPackageNotByManufacturer() {
        val installed = setOf("com.mediatek.duraspeed", "com.transsion.phonemaster", "com.hihonor.powergenie")
        // Most severe first: verified killer, then the unverified one, then the marker.
        assertEquals(
            listOf("com.mediatek.duraspeed", "com.hihonor.powergenie", "com.transsion.phonemaster"),
            BackgroundOem.installed(installed).map { it.packageName },
        )
        assertEquals("honor", BackgroundOem.familyFromPackages(setOf("com.hihonor.powergenie")))
        assertEquals("infinix-itel", BackgroundOem.familyFromPackages(setOf("com.transsion.phonemaster")))
        assertNull(BackgroundOem.familyFromPackages(setOf("com.mediatek.duraspeed")))
        assertNull(BackgroundOem.familyFromPackages(emptySet()))
    }

    private val manifest: String? = run {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "app/src/main/AndroidManifest.xml")
            if (candidate.isFile) return@run candidate.readText()
            val local = java.io.File(dir, "src/main/AndroidManifest.xml")
            if (local.isFile) return@run local.readText()
            dir = dir.parentFile
        }
        null
    }

    @Test
    fun theManifestDeclaresEveryCataloguePackageExceptTheListedPendingOnes() {
        val text = manifest ?: return
        val declared = Regex("<package\\s+android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        val missing = BackgroundOem.QUERY_PACKAGES.filterNot { it in declared }.toSet()
        // Root hook: add the missing <package> lines to <queries> (batch B1 owns the manifest), then this set may be emptied.
        assertTrue("undeclared and not listed as pending: ${missing - BackgroundOem.PENDING_MANIFEST_QUERIES}",
            BackgroundOem.PENDING_MANIFEST_QUERIES.containsAll(missing))
        assertTrue(BackgroundOem.QUERY_PACKAGES.containsAll(BackgroundOem.PENDING_MANIFEST_QUERIES))
        assertNotNull(text)
    }
}
