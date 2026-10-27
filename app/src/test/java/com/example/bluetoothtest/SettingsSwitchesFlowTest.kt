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

    /** An app start: every feature registers (AppFeatures, as MainActivity and the Application do), then the stored choices are read. */
    private fun startApp(): FeatureSnapshot {
        FeatureCatalog.clearForTest()
        AppFeatures.registerAll(updaterAvailable = true)
        return FeatureCatalog.refresh(
            false,
            { null },
            { key -> if (settings().contains(key)) settings().getBoolean(key, false) else null },
            advancedSettings = settings().getBoolean(PREF_ADVANCED_SETTINGS, false),
        )
    }

    /** The switches a user can touch: constants have no stored choice and no row. */
    private fun switches(): List<FeatureSpec> = FeatureCatalog.all().filterNot { it.constant }

    /** Every sub-option below [spec], at any depth. */
    private fun descendantsOf(spec: FeatureSpec, all: List<FeatureSpec>): List<FeatureSpec> {
        val direct = all.filter { spec.id in it.dependsOn }
        return direct + direct.flatMap { descendantsOf(it, all) }
    }

    /** What the Settings card stores when the user touches a switch; an Advanced-only row is reached with Advanced on. */
    private fun touch(spec: FeatureSpec, enabled: Boolean) {
        val edit = settings().edit().putBoolean(requireNotNull(spec.prefKey), enabled)
        if (spec.advancedOnly) edit.putBoolean(PREF_ADVANCED_SETTINGS, true)
        edit.apply()
    }

    @Test
    fun everySwitchTurnedOnSurvivesARestartAndTurnedOffStaysOff() {
        startApp()
        val specs = switches()
        assertTrue("the app registers features", specs.isNotEmpty())

        specs.forEach { touch(it, true) }
        val allOn = startApp()
        specs.forEach { spec ->
            // A sub-option is on only while its parents are on; every parent is on here.
            assertTrue("${spec.id} stays on after a restart", allOn.isOn(spec))
            spec.dependsOn.forEach { parent -> assertTrue("$parent is on for ${spec.id}", allOn.isOn(parent)) }
        }

        specs.forEach { touch(it, false) }
        val allOff = startApp()
        specs.forEach { spec -> assertFalse("${spec.id} stays off after a restart", allOff.isOn(spec)) }
    }

    @Test
    fun oneSwitchAtATimeChangesOnlyItself() {
        val specs = startApp().let { switches() }
        val standing = startApp()
        specs.filter { it.dependsOn.isEmpty() }.forEach { spec ->
            val before = standing.isOn(spec)
            touch(spec, !before)
            val after = startApp()
            assertEquals("${spec.id} follows the user's choice", !before, after.isOn(spec))
            val below = descendantsOf(spec, specs).map { it.id }.toSet()
            specs.filter { it.id != spec.id && it.id !in below }.forEach { other ->
                assertEquals("${other.id} is unchanged by ${spec.id}", standing.isOn(other), after.isOn(other))
            }
            settings().edit().remove(spec.prefKey).remove(PREF_ADVANCED_SETTINGS).apply()
        }
    }

    @Test
    fun aSwitchTheUserNeverTouchedFollowsTheShippedDefaultAndAnExplicitOffBeatsADefaultOn() {
        val specs = startApp().let { FeatureCatalog.all() }
        val fresh = startApp()
        specs.filter { it.dependsOn.isEmpty() }.forEach { spec ->
            assertEquals("${spec.id} default", spec.defaultEnabled, fresh.isOn(spec))
        }
        val defaultOn = specs.filter { it.dependsOn.isEmpty() && it.defaultEnabled && !it.constant }
        assertTrue("some features ship on", defaultOn.isNotEmpty())
        defaultOn.forEach { touch(it, false) }
        val after = startApp()
        defaultOn.forEach { assertFalse("${it.id}: the user's off wins over the default", after.isOn(it)) }
    }

    @Test
    fun aSubOptionIsOffWhileItsParentIsOffWhateverItsOwnChoice() {
        val specs = startApp().let { switches() }
        specs.filter { it.dependsOn.isNotEmpty() }.forEach { child ->
            child.dependsOn.mapNotNull(FeatureCatalog::spec).filterNot { it.constant }.forEach { parent ->
                touch(child, true)
                touch(parent, false)
                val snapshot = startApp()
                assertFalse("${child.id} needs ${parent.id}", snapshot.isOn(child))
                settings().edit().remove(child.prefKey).remove(parent.prefKey).apply()
            }
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
