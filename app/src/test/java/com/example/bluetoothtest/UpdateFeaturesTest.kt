package com.example.bluetoothtest

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateFeaturesTest {
    @Before
    fun clean() = FeatureCatalog.clearForTest()

    @After
    fun cleanAfter() = FeatureCatalog.clearForTest()

    private fun resolve(
        updater: Boolean,
        benchmark: Boolean = false,
        extras: Set<String> = emptySet(),
        stored: Map<String, Boolean> = emptyMap(),
    ): FeatureSnapshot {
        UpdateFeatures.registerAll(updater)
        return FeatureCatalog.resolveAll(benchmark, { it in extras }, { stored[it] })
    }

    @Test
    fun theOnlineRowExistsOnlyWhereTheUpdaterDoes() {
        assertEquals(listOf("check_for_updates", "newer_versions_nearby"), UpdateFeatures.all(true).map { it.id })
        assertEquals(listOf("newer_versions_nearby"), UpdateFeatures.all(false).map { it.id })
        val fdroid = resolve(updater = false)
        assertFalse("nothing can ask for an online check without the updater", fdroid.isOn("check_for_updates"))
        assertNull(FeatureCatalog.spec("check_for_updates"))
    }

    @Test
    fun defaultsAreOnlineOffUntilAskedAndNearbyOn() {
        val snapshot = resolve(updater = true)
        assertFalse("no request before the user said yes", snapshot.isOn("check_for_updates"))
        assertTrue("design decision 2026-10-11", snapshot.isOn("newer_versions_nearby"))
        assertFalse(UpdateFeatureDefaults.CHECK_FOR_UPDATES)
        assertTrue(UpdateFeatureDefaults.NEWER_VERSIONS_NEARBY)
    }

    @Test
    fun theAnswerToTheQuestionIsAnExplicitChoiceThatSticks() {
        assertTrue(resolve(true, stored = mapOf(UpdateSettings.PREF_CHECK_FOR_UPDATES to true)).isOn("check_for_updates"))
        FeatureCatalog.clearForTest()
        assertFalse(resolve(true, stored = mapOf(UpdateSettings.PREF_NEWER_VERSIONS_NEARBY to false)).isOn("newer_versions_nearby"))
    }

    @Test
    fun aBenchmarkLaunchReadsOnlyItsOwnExtrasSoMeasuredHellosAreUnchanged() {
        val plain = resolve(updater = true, benchmark = true, stored = mapOf(UpdateSettings.PREF_CHECK_FOR_UPDATES to true))
        assertFalse(plain.isOn("check_for_updates"))
        assertFalse("default-on does not reach a benchmark case", plain.isOn("newer_versions_nearby"))
        FeatureCatalog.clearForTest()
        val extras = resolve(updater = true, benchmark = true, extras = setOf(UpdateSettings.EXTRA_NEWER_VERSIONS_NEARBY))
        assertTrue(extras.isOn("newer_versions_nearby"))
        assertFalse(extras.isOn("check_for_updates"))
    }

    @Test
    fun updateFeaturesDoNotClashWithTheOtherCatalogs() {
        CallFeatureSpecs.registerAll()
        ConnectivityFeatures.registerAll()
        ViewerFeatures.registerAll()
        UpdateFeatures.registerAll(true)
        val keys = FeatureCatalog.all().mapNotNull { it.prefKey }
        assertEquals(keys.size, keys.toSet().size)
        UpdateFeatures.registerAll(true)
    }

    @Test
    fun theUpdaterReadsTheSameSettingsFileAsTheApp() {
        val main = File("src/main/java/com/example/bluetoothtest/MainActivity.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/com/example/bluetoothtest/MainActivity.kt")
        val declared = Regex("""const val APP_PREFERENCES = "([^"]+)"""").find(main.readText())!!.groupValues[1]
        assertEquals(declared, UpdateSettings.PREFERENCES_FILE)
    }

    @Test
    fun theDocumentsListBothFeaturesAndTheirPhoneTest() {
        val readme = File("../docs/dev/switches.md").takeIf { it.isFile } ?: File("docs/dev/switches.md")
        val text = readme.readText()
        for (needle in listOf("check_for_updates", "newer_versions_nearby", "test_check_for_updates", "test_newer_versions_nearby")) {
            assertTrue("SWITCHES register must mention $needle", text.contains(needle))
        }
        assertEquals("T13", UpdateFeatures.checkForUpdates.phoneTest)
        assertEquals("T13", UpdateFeatures.newerVersionsNearby.phoneTest)
    }

    @Test
    fun theGithubManifestDeclaresTheNetworkStatePermissionTheDailyJobNeeds() {
        // Android 14+ throws SecurityException from JobScheduler.schedule for a network-constrained job without
        // ACCESS_NETWORK_STATE; that crashed "Check for updates" and then every start (user report 2026-10-21).
        // That a refused job cannot crash the app is tested by behaviour in UpdateScreenFlowTest (a scheduler that refuses);
        // this keeps the declaration the system checks.
        val manifest = File("src/github/AndroidManifest.xml").takeIf { it.isFile } ?: File("app/src/github/AndroidManifest.xml")
        val declared = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        assertTrue("ACCESS_NETWORK_STATE is declared: $declared", "android.permission.ACCESS_NETWORK_STATE" in declared)
    }
}
