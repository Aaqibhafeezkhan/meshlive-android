package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two flows that end with a clean slate: the app crashed and offers a report at the next launch, and the user wipes the app
 * and opens it again as a new user. The real report, onboarding, update-consent, recent-call and wipe classes run against
 * [FakePhone]; only the Keystore and the running sessions are stand-ins.
 */
class CrashAndWipeFlowTest {
    private lateinit var phone: FakePhone
    private val header = listOf("app_version" to "0.3.0 (3, release)", "android" to "16 (API 36)", "device" to "Test Phone")
    private val week = CrashReports.KEEP_MS

    @Before
    fun setUp() {
        phone = FakePhone(Files.createTempDirectory("crash-wipe").toFile())
        PeerIdentityGate.resetForTest()
        WipeGuard.resetForTest()
        FeatureCatalog.clearForTest()
    }

    @After
    fun tearDown() {
        PeerIdentityGate.resetForTest()
        WipeGuard.resetForTest()
        FeatureCatalog.clearForTest()
        phone.root.deleteRecursively()
    }

    // ---- crash report prompt ----

    private fun crashOnce(context: FakeAppContext, message: String = "boom") {
        val handed = mutableListOf<Throwable>()
        val handler = CrashReports.Handler(CrashReports.dir(context), Thread.UncaughtExceptionHandler { _, error -> handed += error }, { header }, { 1_000L })
        handler.uncaughtException(Thread.currentThread(), IllegalStateException(message))
        assertEquals("Android's own crash handling still runs", 1, handed.size)
    }

    private fun promptedAtLaunch(context: FakeAppContext, extras: Set<String> = emptySet()): Boolean =
        CrashReports.shouldPrompt(CrashReports.enabled(context), CrashReports.pending(CrashReports.dir(context)) != null, extras)

    @Test
    fun aCrashIsOfferedAtTheNextPlainLaunchOnceAndStaysAvailableForExportForAWeek() {
        crashOnce(phone.start())

        val relaunch = phone.start()
        assertTrue("the next plain launch asks first", promptedAtLaunch(relaunch))
        // The user answers (Share or Not now): the report is marked shown.
        CrashReports.markShown(CrashReports.dir(relaunch))
        assertFalse("answered: not asked again", promptedAtLaunch(phone.start()))
        val dir = CrashReports.dir(phone.start())
        assertNotNull("Export diagnostics still has it", CrashReports.latest(dir))
        assertTrue(CrashReports.latest(dir)!!.contains("IllegalStateException"))

        val later = System.currentTimeMillis() + week + 60_000L
        assertNull("after a week it is gone", CrashReports.latest(dir, later))
    }

    @Test
    fun aTestOrDebugLaunchIsNeverInterruptedByThePrompt() {
        crashOnce(phone.start())
        val relaunch = phone.start()
        assertFalse(promptedAtLaunch(relaunch, setOf("test_session_role")))
        assertFalse(promptedAtLaunch(relaunch, setOf("debug_meet")))
        assertNotNull("the report is kept for the next plain launch", CrashReports.pending(CrashReports.dir(relaunch)))
    }

    @Test
    fun dontAskAgainDeletesTheReportsAndStopsThePrompt() {
        crashOnce(phone.start())
        val relaunch = phone.start()
        CrashReports.disable(relaunch)
        assertFalse(CrashReports.enabled(phone.start()))
        assertFalse(promptedAtLaunch(phone.start()))
        assertNull(CrashReports.latest(CrashReports.dir(phone.start())))
    }

    // ---- emergency wipe -> a new user ----

    private inner class PhoneEnvironment : WipeEnvironment {
        private val marker = File(phone.noBackupDir, EmergencyWipe.PENDING_MARKER)

        override fun writePendingMarker() {
            marker.writeText("1")
        }

        override fun clearPendingMarker() {
            marker.delete()
        }

        override fun preservedFiles(): Set<File> = setOf(marker)

        override fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long): TerminatorOutcome =
            EmergencyWipe.runTerminatorsBounded(terminators, { it.run() }, timeoutMs)

        override fun keystoreAliases(): Collection<String> = emptyList()

        override fun deleteKeystoreAlias(alias: String) = Unit

        override fun preferenceNames(): Collection<String> = phone.preferenceNames()

        override fun clearPreferences(name: String) = phone.clearPreferences(name)

        override fun storageRoots(): List<File> = EmergencyWipe.storageRootsFor(phone.root, listOf<File?>(null))

        override fun resetInMemoryState() = Unit

        override fun cancelNotifications() = Unit
    }

    private fun settings() = phone.preferences(UpdateSettings.PREFERENCES_FILE)

    private fun appStart(context: FakeAppContext): FeatureSnapshot {
        UpdateConsent.reconcile(context, settings())
        FeatureCatalog.clearForTest()
        CallFeatureSpecs.registerAll()
        UpdateFeatures.registerAll(updaterAvailable = true)
        return FeatureCatalog.refresh(false, { false }) { key -> if (settings().contains(key)) settings().getBoolean(key, false) else null }
    }

    @Test
    fun afterAWipeTheAppStartsAsForANewUser() {
        // A user who has used the app for a while.
        val used = phone.start()
        OnboardingState.markDone(used)
        UpdateConsent.set(used, settings(), yes = true, nowMs = 5_000L)
        settings().edit().putBoolean(CallFeature.MESSAGES.prefKey, !CallFeatureDefaults.MESSAGES).apply()
        RecentCalls.record(used, RecentCall("0123456789ab", video = false, atMs = 7_000L))
        crashOnce(used)
        val before = appStart(used)
        assertTrue(UpdateConsent.granted(used, settings()))
        assertTrue("the user's choice differs from the default", CallFeatureDefaults.MESSAGES != before.isOn(CallFeatureSpecs.specOf(CallFeature.MESSAGES)))

        val report = EmergencyWipe.run(PhoneEnvironment(), WipeReason.USER_CONFIRMED)
        assertTrue("the wipe finished", report.complete)

        // The app is opened again.
        val fresh = phone.start()
        assertFalse("no interrupted wipe is waiting", EmergencyWipe.isPending(fresh))
        assertTrue("the first-run screens come back", shouldShowOnboarding(OnboardingState.isDone(fresh), launchHasExtras = false, recreated = false))
        assertFalse("the earlier Yes is gone", UpdateConsent.granted(fresh, settings()))
        assertEquals("and so is the fact that the question was asked", 0L, UpdateCheckStore(PrefsUpdateStore(settings())).load().askedAtMs)
        val after = appStart(fresh)
        assertEquals("switches are back at their shipped defaults", CallFeatureDefaults.MESSAGES, after.isOn(CallFeatureSpecs.specOf(CallFeature.MESSAGES)))
        assertTrue("Recent is empty", RecentCalls.load(fresh).isEmpty())
        assertNull("no crash report survives", CrashReports.latest(CrashReports.dir(fresh)))
        assertTrue("the wipe left the directories the app needs to keep running", phone.noBackupDir.isDirectory && phone.filesDir.isDirectory)
    }

    @Test
    fun aWipeNeedsTheTypedConfirmationAndChangesNothingWithoutIt() {
        val used = phone.start()
        OnboardingState.markDone(used)
        assertThrows(IllegalArgumentException::class.java) { EmergencyWipe.wipeConfirmed(used, "wipe") }
        assertThrows(IllegalArgumentException::class.java) { EmergencyWipe.wipeConfirmed(used, "") }
        assertTrue("nothing was touched", OnboardingState.isDone(phone.start()))
    }
}
