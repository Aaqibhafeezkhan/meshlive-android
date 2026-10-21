package com.example.bluetoothtest

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings > Features as the user meets it: a switch is turned on, the app is restarted, the switch is still on, it is
 * turned off, the app is restarted, it is still off. Run for every switch the app registers (a new feature is covered the
 * moment it is registered), with the same stored-choice rule the Settings card and MainActivity use.
 */
class SettingsSwitchesFlowTest {
    private lateinit var phone: FakePhone

    @Before
    fun setUp() {
        phone = FakePhone(Files.createTempDirectory("settings-flow").toFile())
        FeatureCatalog.clearForTest()
    }

    @After
    fun tearDown() {
        FeatureCatalog.clearForTest()
        phone.root.deleteRecursively()
    }

    private fun settings() = phone.preferences(UpdateSettings.PREFERENCES_FILE)

    /** An app start: every feature group registers (the list MainActivity.refreshFeatureCatalog registers), then the stored choices are read. */
    private fun startApp(): FeatureSnapshot {
        FeatureCatalog.clearForTest()
        CallFeatureSpecs.registerAll()
        ConnectivityFeatures.registerAll()
        ViewerFeatures.registerAll()
        UpdateFeatures.registerAll(updaterAvailable = true)
        NamingFeatures.registerAll()
        return FeatureCatalog.refresh(false, { false }) { key -> if (settings().contains(key)) settings().getBoolean(key, false) else null }
    }

    /** Every sub-option below [spec], at any depth. */
    private fun descendantsOf(spec: FeatureSpec, all: List<FeatureSpec>): List<FeatureSpec> {
        val direct = all.filter { it.dependsOn == spec.id }
        return direct + direct.flatMap { descendantsOf(it, all) }
    }

    /** What the Settings card stores when the user touches a switch. */
    private fun touch(spec: FeatureSpec, enabled: Boolean) {
        settings().edit().putBoolean(spec.prefKey, enabled).apply()
    }

    @Test
    fun everySwitchTurnedOnSurvivesARestartAndTurnedOffStaysOff() {
        startApp()
        val specs = FeatureCatalog.all()
        assertTrue("the app registers features", specs.isNotEmpty())

        specs.forEach { touch(it, true) }
        val allOn = startApp()
        specs.forEach { spec ->
            val parent = spec.dependsOn
            // A sub-option is on only while its parent is on; every parent is on here.
            assertTrue("${spec.id} stays on after a restart", allOn.isOn(spec))
            if (parent != null) assertTrue("$parent is on for ${spec.id}", allOn.isOn(parent))
        }

        specs.forEach { touch(it, false) }
        val allOff = startApp()
        specs.forEach { spec -> assertFalse("${spec.id} stays off after a restart", allOff.isOn(spec)) }
    }

    @Test
    fun oneSwitchAtATimeChangesOnlyItself() {
        val specs = startApp().let { FeatureCatalog.all() }
        val standing = startApp()
        specs.filter { it.dependsOn == null }.forEach { spec ->
            val before = standing.isOn(spec)
            touch(spec, !before)
            val after = startApp()
            assertEquals("${spec.id} follows the user's choice", !before, after.isOn(spec))
            val below = descendantsOf(spec, specs).map { it.id }.toSet()
            specs.filter { it.id != spec.id && it.id !in below }.forEach { other ->
                assertEquals("${other.id} is unchanged by ${spec.id}", standing.isOn(other), after.isOn(other))
            }
            settings().edit().remove(spec.prefKey).apply()
        }
    }

    @Test
    fun aSwitchTheUserNeverTouchedFollowsTheShippedDefaultAndAnExplicitOffBeatsADefaultOn() {
        val specs = startApp().let { FeatureCatalog.all() }
        val fresh = startApp()
        specs.filter { it.dependsOn == null }.forEach { spec ->
            assertEquals("${spec.id} default", spec.defaultEnabled, fresh.isOn(spec))
        }
        val defaultOn = specs.filter { it.dependsOn == null && it.defaultEnabled }
        assertTrue("some features ship on", defaultOn.isNotEmpty())
        defaultOn.forEach { touch(it, false) }
        val after = startApp()
        defaultOn.forEach { assertFalse("${it.id}: the user's off wins over the default", after.isOn(it)) }
    }

    @Test
    fun aSubOptionIsOffWhileItsParentIsOffWhateverItsOwnChoice() {
        val specs = startApp().let { FeatureCatalog.all() }
        specs.filter { it.dependsOn != null }.forEach { child ->
            touch(child, true)
            touch(requireNotNull(FeatureCatalog.spec(child.dependsOn!!)), false)
            val snapshot = startApp()
            assertFalse("${child.id} needs ${child.dependsOn}", snapshot.isOn(child))
            settings().edit().remove(child.prefKey).remove(FeatureCatalog.spec(child.dependsOn!!)!!.prefKey).apply()
        }
    }

    @Test
    fun theCheckForUpdatesSwitchIsTheAnswerToTheQuestionAcrossRestarts() {
        val check = UpdateFeatures.checkForUpdates
        startApp()
        val context = phone.start()

        touch(check, true)
        UpdateConsent.onSwitchChanged(context, settings())
        assertTrue(startApp().isOn(check))
        assertTrue("a restart keeps the consent marker with the switch", UpdateConsent.granted(phone.start(), settings()))

        touch(check, false)
        UpdateConsent.onSwitchChanged(context, settings())
        assertFalse(startApp().isOn(check))
        assertFalse(UpdateConsent.granted(phone.start(), settings()))
    }
}
