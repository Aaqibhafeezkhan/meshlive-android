package com.example.bluetoothtest

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Hide phone name" on by default (2026-10-27): what a person sees from a fresh install, an answer to the onboarding
 * question either way, a restart, and an update from a build where they had turned the option off. The production
 * rules ([FeatureCatalog], [NeutralNameChoice]) run against [FakePhone]; MainActivity and OnboardingActivity only
 * call them.
 */
class NeutralNameDefaultTest {
    private lateinit var phone: FakePhone

    @Before
    fun setUp() {
        phone = FakePhone(Files.createTempDirectory("neutral-name").toFile())
        FeatureCatalog.clearForTest()
        NeutralBluetoothNameController.resetProcessForTest()
    }

    @After
    fun tearDown() {
        FeatureCatalog.clearForTest()
        phone.root.deleteRecursively()
    }

    /** What applyNeutralName() decides at an app start: (resolved option, rename now, still to ask). */
    private data class Start(val resolved: Boolean, val renameOn: Boolean, val asks: Boolean)

    private fun startApp(benchmark: Boolean = false, extras: Map<String, Boolean> = emptyMap()): Start {
        val settings = NeutralNameChoice.settings(phone.start())
        FeatureCatalog.clearForTest()
        AppFeatures.registerAll(updaterAvailable = false)
        val stored: (String) -> Boolean? = { key -> if (settings.contains(key)) settings.getBoolean(key, false) else null }
        val snapshot = FeatureCatalog.refresh(benchmark, { extras[it] }, stored, debugBuild = true)
        val resolved = snapshot.isOn(NeutralNameOption.spec)
        val choice = NeutralNameChoice.storedChoice(settings)
        return Start(resolved, NeutralNameChoice.renameOn(resolved, choice, benchmark), NeutralNameChoice.needsAsking(choice, benchmark))
    }

    /** The onboarding page (or the one-time question after an update): "Hide name" or "Keep my name". */
    private fun answer(hide: Boolean) = NeutralNameChoice.record(NeutralNameChoice.settings(phone.start()), hide)

    @Test
    fun aFreshInstallDefaultsToOnButRenamesOnlyAfterTheUserAnswers() {
        val first = startApp()
        assertTrue("default on for someone who never chose", first.resolved)
        assertFalse("no rename before the question was answered", first.renameOn)
        assertTrue(first.asks)
        assertNull(NeutralNameChoice.storedChoice(NeutralNameChoice.settings(phone.start())))
    }

    @Test
    fun hideNameIsRememberedAcrossARestart() {
        startApp()
        answer(hide = true)
        val restarted = startApp()
        assertEquals(Start(resolved = true, renameOn = true, asks = false), restarted)
        assertEquals(true, NeutralNameChoice.storedChoice(NeutralNameChoice.settings(phone.start())))
    }

    @Test
    fun keepMyNameIsRememberedAcrossARestartAndBeatsTheDefault() {
        startApp()
        answer(hide = false)
        val restarted = startApp()
        assertEquals(Start(resolved = false, renameOn = false, asks = false), restarted)
        assertEquals(false, NeutralNameChoice.storedChoice(NeutralNameChoice.settings(phone.start())))
    }

    @Test
    fun anExistingUserWhoTurnedItOffKeepsItOffAndIsNotAskedAgain() {
        // An earlier build stored the explicit "off" from the Settings switch.
        NeutralNameChoice.settings(phone.start()).edit().putBoolean(NeutralNameOption.PREF, false).apply()
        assertEquals(Start(resolved = false, renameOn = false, asks = false), startApp())
    }

    @Test
    fun anExistingUserWhoNeverChoseIsAskedOnceBeforeAnyRename() {
        // Updated from a build where the option was off by default and never touched: nothing stored.
        val updated = startApp()
        assertTrue(updated.asks)
        assertFalse(updated.renameOn)
        answer(hide = true)
        assertEquals(Start(resolved = true, renameOn = true, asks = false), startApp())
    }

    @Test
    fun aBenchmarkLaunchIgnoresTheDefaultAndTheStoredChoiceAndNeverAsks() {
        answer(hide = true)
        assertEquals(Start(resolved = false, renameOn = false, asks = false), startApp(benchmark = true))
        assertEquals(
            Start(resolved = true, renameOn = true, asks = false),
            startApp(benchmark = true, extras = mapOf(NeutralNameOption.EXTRA to true)),
        )
        // A test phone that never answered: still no question and no rename without the extra.
        phone.clearPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES)
        assertEquals(Start(resolved = false, renameOn = false, asks = false), startApp(benchmark = true))
    }

    @Test
    fun theSettingsSwitchShowsOffWithAReasonWhileTheNameCannotBeHidden() {
        assertEquals(NeutralNameChoice.Shown.ON, NeutralNameChoice.shown(optionOn = true, canRename = true, refused = false))
        val noPermission = NeutralNameChoice.shown(optionOn = true, canRename = false, refused = false)
        assertEquals(NeutralNameChoice.Shown.OFF_NO_PERMISSION, noPermission)
        assertFalse(noPermission.checked)
        val refused = NeutralNameChoice.shown(optionOn = true, canRename = true, refused = true)
        assertEquals(NeutralNameChoice.Shown.OFF_REFUSED, refused)
        assertFalse(refused.checked)
        assertEquals(NeutralNameChoice.Shown.OFF, NeutralNameChoice.shown(optionOn = false, canRename = false, refused = false))
    }

    // ----- the adapter rename itself (release recheck 2026-10-28: M31 run 2 lost "Galaxy M31") -----

    /**
     * The phone's Bluetooth adapter as Android behaves: setName() is accepted at once and the name changes only when
     * the stack reports it ([land], AOSP AdapterProperties' property callback, which also sends
     * ACTION_LOCAL_NAME_CHANGED). Without Nearby devices both calls throw.
     */
    private class Radio(var name: String?) : NeutralBluetoothNameController.Radio {
        var permission = true
        var stackApplies = true
        val queued = ArrayDeque<String>()
        var writes = 0

        override fun name(): String? {
            if (!permission) throw SecurityException("BLUETOOTH_CONNECT")
            return name
        }

        override fun setName(name: String): Boolean {
            if (!permission) throw SecurityException("BLUETOOTH_CONNECT")
            writes++
            if (stackApplies) queued.addLast(name)
            return true
        }

        /** Every accepted write lands, in order. */
        fun land() {
            while (queued.isNotEmpty()) name = queued.removeFirst()
        }

        /** Only the oldest accepted write lands. */
        fun landOne() {
            name = queued.removeFirst()
        }
    }

    private val radio = Radio("Galaxy M31")
    private var nowMs = 1_000L

    private fun settings() = NeutralNameChoice.settings(phone.start())

    private fun nameState() = phone.preferences(NeutralBluetoothNameController.PREFS)

    private fun saved() = NeutralBluetoothNameController.savedOriginal(nameState())

    /**
     * What MainActivity.applyNeutralName() does on a normal launch (after the app start, onResume after onboarding, the
     * permission-ready path, Bluetooth on, a name-change broadcast, the switch): resolve the option, apply it, switch
     * it off when the controller says the user renamed the phone, and show the switch.
     */
    private fun applyNeutralName(canRename: Boolean = radio.permission): NeutralNameChoice.Shown {
        val settings = settings()
        val stored = NeutralNameChoice.storedChoice(settings)
        val resolved = FeatureCatalog.resolve(NeutralNameOption.spec, false, { false }) { key ->
            if (settings.contains(key)) settings.getBoolean(key, false) else null
        }
        var optionOn = NeutralNameChoice.renameOn(resolved, stored, benchmarkLaunch = false)
        var refused = false
        if (canRename) {
            val outcome = NeutralBluetoothNameController.apply(
                nameState(), radio, optionOn, settings.contains(NeutralNameOption.PREF), nowMs,
            )
            refused = outcome.refused
            if (outcome.paused) {
                NeutralNameChoice.record(settings, hide = false)
                optionOn = false
            }
        }
        return NeutralNameChoice.shown(optionOn, canRename, refused)
    }

    @Test
    fun hideNameKeepsTheOriginalWhenTheAppAppliesAgainBeforeAndroidReportsTheNewName() {
        assertEquals(NeutralNameChoice.Shown.OFF, applyNeutralName()) // app start, before onboarding's answer
        answer(hide = true)
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) // onResume after onboarding
        assertEquals("the rename is asynchronous", "Galaxy M31", radio.name)
        // The permission-ready path in the same main-thread pass, then Bluetooth-on and resume: still the old name.
        repeat(3) { assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) }
        assertEquals("one write while it is on its way", 1, radio.writes)
        assertEquals("not taken for a user rename", true, NeutralNameChoice.storedChoice(settings()))
        assertEquals("Galaxy M31", saved())
        radio.land()
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) // ACTION_LOCAL_NAME_CHANGED
        assertEquals("Phone", radio.name)
        // Settings > Privacy > Hide phone name off: the phone's own name comes back.
        answer(hide = false)
        assertEquals(NeutralNameChoice.Shown.OFF, applyNeutralName())
        radio.land()
        applyNeutralName()
        assertEquals("Galaxy M31", radio.name)
        assertNull(saved())
    }

    @Test
    fun aProcessRestartWhileTheRenameIsOnItsWayKeepsTheOriginal() {
        answer(hide = true)
        radio.stackApplies = false // the write never lands (Bluetooth went off, process killed)
        applyNeutralName()
        assertEquals("Galaxy M31", saved())
        NeutralBluetoothNameController.resetProcessForTest()
        radio.stackApplies = true
        nowMs += 50
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) // the new process writes again
        assertEquals(2, radio.writes)
        assertEquals(true, NeutralNameChoice.storedChoice(settings()))
        assertEquals("Galaxy M31", saved())
        radio.land()
        applyNeutralName()
        assertEquals("Phone", radio.name)
        answer(hide = false)
        applyNeutralName()
        radio.land()
        applyNeutralName()
        assertEquals("Galaxy M31", radio.name)
    }

    @Test
    fun nearbyDevicesGrantedAfterTheAnswerRenamesOnceAndKeepsTheOriginal() {
        radio.permission = false
        answer(hide = true)
        assertEquals(NeutralNameChoice.Shown.OFF_NO_PERMISSION, applyNeutralName())
        // Called anyway: an unreadable name changes nothing and is not a refusal.
        val blind = NeutralBluetoothNameController.apply(nameState(), radio, true, true, nowMs)
        assertFalse(blind.refused)
        assertEquals(0, radio.writes)
        radio.permission = true // granted on onboarding's page
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) // permission result
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) // onResume
        radio.land()
        applyNeutralName()
        assertEquals("Phone", radio.name)
        assertEquals("Galaxy M31", saved())
        assertEquals(1, radio.writes)
    }

    @Test
    fun turningItOffBeforePhoneLandsEndsWithTheOwnName() {
        answer(hide = true)
        applyNeutralName()
        answer(hide = false)
        // Still reads "Galaxy M31", but our "Phone" is on its way: the restore is written after it.
        assertEquals(NeutralNameChoice.Shown.OFF, applyNeutralName())
        radio.land()
        applyNeutralName()
        assertEquals("Galaxy M31", radio.name)
        assertNull(saved())
    }

    @Test
    fun onOffOnWhileTheRestoreIsOnItsWayEndsHiddenAndCanStillRestore() {
        answer(hide = true)
        applyNeutralName()
        radio.land()
        applyNeutralName()
        answer(hide = false)
        applyNeutralName() // restore written
        answer(hide = true)
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName()) // "Phone" read here is stale
        radio.land() // the restore, then "Phone" again
        applyNeutralName()
        assertEquals("Phone", radio.name)
        assertEquals(true, NeutralNameChoice.storedChoice(settings()))
        answer(hide = false)
        applyNeutralName()
        radio.land()
        applyNeutralName()
        assertEquals("Galaxy M31", radio.name)
    }

    @Test
    fun aRenameByTheUserAfterThePhoneReadsPhoneStillWins() {
        answer(hide = true)
        applyNeutralName()
        radio.land()
        applyNeutralName()
        radio.name = "Kitchen" // Android settings
        assertEquals(NeutralNameChoice.Shown.OFF, applyNeutralName())
        assertEquals(false, NeutralNameChoice.storedChoice(settings()))
        assertEquals("Kitchen", radio.name)
        assertNull(saved())
    }

    @Test
    fun aRenameAndroidAcceptsButNeverAppliesIsShownAsRefused() {
        radio.stackApplies = false
        answer(hide = true)
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName())
        repeat(NeutralBluetoothNameController.MAX_UNCONFIRMED_WRITES) {
            nowMs += NeutralBluetoothNameController.IN_FLIGHT_MS
            applyNeutralName()
        }
        assertEquals(NeutralNameChoice.Shown.OFF_REFUSED, applyNeutralName())
        assertEquals(NeutralBluetoothNameController.MAX_UNCONFIRMED_WRITES, radio.writes)
        assertEquals("Galaxy M31", saved())
    }

    @Test
    fun aPhoneLeftCalledPhoneWithNothingSavedOffersItsNameBack() {
        // The 0.4.1 release-candidate state: option recorded off, adapter "Phone", nothing saved.
        radio.name = "Phone"
        answer(hide = false)
        assertEquals(NeutralNameChoice.Shown.OFF, applyNeutralName())
        assertTrue(NeutralBluetoothName.needsOwnName(radio.name, saved()))
        assertEquals("Galaxy M31", NeutralBluetoothName.suggestedOwnName("Galaxy M31", "SM-M315F"))
        assertEquals("SM-M315F", NeutralBluetoothName.suggestedOwnName("Phone", "SM-M315F"))
        assertFalse(NeutralBluetoothNameController.renameToOwnName(nameState(), radio, "Phone"))
        assertTrue(NeutralBluetoothNameController.renameToOwnName(nameState(), radio, " Galaxy M31 "))
        radio.land()
        applyNeutralName()
        assertEquals("Galaxy M31", radio.name)
        assertFalse(NeutralBluetoothName.needsOwnName(radio.name, saved()))
    }

    @Test
    fun theNeutralNameIsNeverKeptAsTheOriginal() {
        radio.name = "Phone"
        answer(hide = true)
        assertEquals(NeutralNameChoice.Shown.ON, applyNeutralName())
        assertNull(saved())
        nameState().edit().putString("originalName", "Phone").apply() // whatever wrote it
        assertNull(saved())
        assertFalse(NeutralBluetoothNameController.saveOwnName(nameState(), "Phone"))
        assertFalse(NeutralBluetoothNameController.saveOwnName(nameState(), "  "))
        // Turning the switch on at "Phone" asks for the phone's own name; turning it off then restores it.
        assertTrue(NeutralBluetoothNameController.saveOwnName(nameState(), "Galaxy M31"))
        answer(hide = false)
        applyNeutralName()
        radio.land()
        applyNeutralName()
        assertEquals("Galaxy M31", radio.name)
    }
}
