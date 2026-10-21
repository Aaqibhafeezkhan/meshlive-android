package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a person goes through the first time they open the app, and what the app remembers when it is opened again
 * (restarted): the first-run screens, the one-time question "Check for updates when online?" answered Yes or No, the
 * battery offer. The real production classes run against [FakePhone]; the Activity glue only calls them.
 *
 * Added after "Check for updates, Yes" crashed the app on Android 14+ and again at every start while ~3,800 tests passed:
 * nothing exercised the answer-then-restart path.
 */
class FirstRunFlowTest {
    private lateinit var phone: FakePhone
    private val minute = 60_000L
    private val day = BatteryExemptionPolicy.ROLE_REOFFER_MS

    @Before
    fun setUp() {
        phone = FakePhone(Files.createTempDirectory("first-run").toFile())
        FeatureCatalog.clearForTest()
    }

    @After
    fun tearDown() {
        FeatureCatalog.clearForTest()
        phone.root.deleteRecursively()
    }

    private fun settings(): MemoryPreferences = phone.preferences(UpdateSettings.PREFERENCES_FILE)

    /** An app start: register the update features and resolve every switch from the stored choices, as MainActivity does. */
    private fun startApp(context: FakeAppContext): FeatureSnapshot {
        UpdateConsent.reconcile(context, settings()) // a restored yes without its marker is dropped first, as UpdatesHome.apply does
        FeatureCatalog.clearForTest()
        UpdateFeatures.registerAll(updaterAvailable = true)
        return FeatureCatalog.refresh(false, { false }) { key -> if (settings().contains(key)) settings().getBoolean(key, false) else null }
    }

    /** What the home screen does when it is idle in front of the user. */
    private fun whenHomeIsIdle(snapshot: FeatureSnapshot, homeIdle: Boolean = true, benchmark: Boolean = false, releaseKey: Boolean = true) =
        UpdateResume.action(
            updaterAvailable = true,
            onlineOffered = releaseKey,
            homeIdle = homeIdle,
            askedAtMs = UpdateCheckStore(PrefsUpdateStore(settings())).load().askedAtMs,
            benchmarkLaunch = benchmark,
            checkEnabled = snapshot.isOn(UpdateFeatures.checkForUpdates),
        )

    @Test
    fun aPlainFirstLaunchShowsTheFirstRunScreensOnceAndARestartDoesNot() {
        val first = phone.start()
        assertTrue(shouldShowOnboarding(OnboardingState.isDone(first), launchHasExtras = false, recreated = false))
        // Rotating the phone while the first-run screens are up must not start them again.
        assertFalse(shouldShowOnboarding(OnboardingState.isDone(first), launchHasExtras = false, recreated = true))
        // Passing the permission page counts as having seen them.
        OnboardingState.markDone(first)
        val restarted = phone.start()
        assertFalse(shouldShowOnboarding(OnboardingState.isDone(restarted), launchHasExtras = false, recreated = false))
    }

    @Test
    fun theUpdateQuestionIsAskedBeforeAnyAnswerAndNeverWhileTheScreenIsBusy() {
        val snapshot = startApp(phone.start())
        assertFalse("no request before the user says yes", snapshot.isOn(UpdateFeatures.checkForUpdates))
        assertEquals(UpdateResumeAction.ASK, whenHomeIsIdle(snapshot))
        assertEquals("a call or stream on screen", UpdateResumeAction.NOTHING, whenHomeIsIdle(snapshot, homeIdle = false))
        assertEquals("an automation launch", UpdateResumeAction.NOTHING, whenHomeIsIdle(snapshot, benchmark = true))
        assertEquals("a copy without the release key", UpdateResumeAction.NOTHING, whenHomeIsIdle(snapshot, releaseKey = false))
    }

    @Test
    fun answeringYesTurnsTheCheckOnAndTheAnswerSurvivesARestart() {
        val first = phone.start()
        UpdateConsent.set(first, settings(), yes = true, nowMs = 5 * minute)
        assertTrue(UpdateConsent.granted(first, settings()))

        // Restart: a new process over the same storage.
        val restarted = phone.start()
        val snapshot = startApp(restarted)
        assertTrue("the choice is still on", snapshot.isOn(UpdateFeatures.checkForUpdates))
        assertTrue(UpdateConsent.granted(restarted, settings()))
        assertEquals("answered: not asked again, the due check runs", UpdateResumeAction.CHECK_NOW, whenHomeIsIdle(snapshot))
    }

    @Test
    fun answeringNoKeepsTheCheckOffAndTheQuestionIsNotAskedAgain() {
        val first = phone.start()
        UpdateConsent.set(first, settings(), yes = false, nowMs = 5 * minute)

        val restarted = phone.start()
        val snapshot = startApp(restarted)
        assertFalse(snapshot.isOn(UpdateFeatures.checkForUpdates))
        assertFalse(UpdateConsent.granted(restarted, settings()))
        assertEquals("No is an answer: neither asked nor checked", UpdateResumeAction.NOTHING, whenHomeIsIdle(snapshot))
        assertFalse("no marker file remains", File(phone.noBackupDir, "update-consent").exists())
    }

    @Test
    fun aYesRestoredFromABackupWithoutItsMarkerIsForgottenAndTheQuestionComesBack() {
        // Android may restore preferences onto another phone; the marker file is not restored.
        settings().edit().putBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, true).putLong(UpdateCheckStore.ASKED, 3 * minute).apply()
        val restored = phone.start()
        val snapshot = startApp(restored)
        assertFalse("a restored yes does not start requests", snapshot.isOn(UpdateFeatures.checkForUpdates))
        assertFalse(UpdateConsent.granted(restored, settings()))
        assertEquals(UpdateResumeAction.ASK, whenHomeIsIdle(snapshot))
    }

    @Test
    fun theSettingsSwitchCountsAsTheAnswerAndTurningItOffForgetsWhatTheCheckLearned() {
        val context = phone.start()
        // The user turns "Check for updates" on in Settings (the switch writes its preference first).
        settings().edit().putBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, true).apply()
        UpdateConsent.onSwitchChanged(context, settings())
        assertTrue(UpdateConsent.granted(context, settings()))
        settings().edit().putString(UpdateCheckStore.LATEST, "v9.9.9").apply()

        settings().edit().putBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, false).apply()
        UpdateConsent.onSwitchChanged(context, settings())
        assertFalse(UpdateConsent.granted(context, settings()))
        assertEquals("no stale 'update available' lingers", null, settings().getString(UpdateCheckStore.LATEST, null))
        val snapshot = startApp(phone.start())
        assertEquals("the switch was the answer: not asked again", UpdateResumeAction.NOTHING, whenHomeIsIdle(snapshot))
    }

    @Test
    fun theBatteryOfferIsMadeOnceAtFirstRunAndAgainForARoleOnlyAfterTheReofferInterval() {
        val context = phone.start()
        val t0 = 10 * day
        assertTrue(BatteryExemptionPrompt.shouldOffer(context, BatteryOfferTrigger.ONBOARDING, t0))
        BatteryExemptionPrompt.markOffered(context, t0)

        val restarted = phone.start()
        assertFalse("the first-run offer is not repeated after a restart", BatteryExemptionPrompt.shouldOffer(restarted, BatteryOfferTrigger.ONBOARDING, t0 + 1))
        assertFalse("turning a role on right after the offer does not nag", BatteryExemptionPrompt.shouldOffer(restarted, BatteryOfferTrigger.ALWAYS_ON_ROLE, t0 + minute))
        assertTrue("after the interval it may ask again", BatteryExemptionPrompt.shouldOffer(restarted, BatteryOfferTrigger.ALWAYS_ON_ROLE, t0 + day + minute))
        assertTrue("Settings always offers while the app is not exempt", BatteryExemptionPrompt.shouldOffer(restarted, BatteryOfferTrigger.SETTINGS, t0 + 1))
    }
}
