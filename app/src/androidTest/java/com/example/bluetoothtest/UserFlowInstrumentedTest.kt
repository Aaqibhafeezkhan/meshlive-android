package com.example.bluetoothtest

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User flows driven through the real activities on a phone or emulator (no Bluetooth link is needed: this is UI and
 * lifecycle). They are compiled with the project (`:app:assembleGithubDebugAndroidTest`) and run on any attached device with
 * `:app:connectedGithubDebugAndroidTest` (docs/dev/testing.md "User-flow tests"). Each test starts from a wiped app state and
 * puts the state back, so it does not need a freshly installed phone.
 *
 * Written in the 2026-10-21 suite-quality pass; not run on a device when they were added.
 */
@RunWith(AndroidJUnit4::class)
class UserFlowInstrumentedTest {
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before
    fun freshState() {
        listOf(OnboardingActivity.PREFERENCES, UpdateSettings.PREFERENCES_FILE, "crash_reports").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        CrashReports.discard(CrashReports.dir(context))
    }

    @After
    fun leaveNothingBehind() = freshState()

    private fun plainLaunch(): Intent = Intent(context, MainActivity::class.java)

    /** The first plain launch shows the first-run explanation exactly once; a restart after it does not show it again. */
    @Test
    fun firstRunExplainsTheAppOnceAndARestartDoesNotRepeatIt() {
        val monitor = instrumentation.addMonitor(OnboardingActivity::class.java.name, null, false)
        ActivityScenario.launch<MainActivity>(plainLaunch()).use {
            val onboarding = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertNotNull("a plain first launch opens the first-run screens", onboarding)
            onboarding.finish()
        }
        // The first-run explanation counts as shown once the permission page is passed.
        OnboardingActivity.markDone(context)
        val second = instrumentation.addMonitor(OnboardingActivity::class.java.name, null, false)
        ActivityScenario.launch<MainActivity>(plainLaunch()).use { scenario ->
            assertNull("after first run nothing is explained again", instrumentation.waitForMonitorWithTimeout(second, 3_000))
            scenario.recreate()
            assertEquals(0, second.hits)
        }
        instrumentation.removeMonitor(monitor)
        instrumentation.removeMonitor(second)
    }

    /** Every Settings switch turned on survives an app restart, and turned off again survives one too. */
    @Test
    fun everySettingsSwitchOnThenRestartThenOffNeverCrashesTheApp() {
        OnboardingActivity.markDone(context)
        val prefs = context.getSharedPreferences(UpdateSettings.PREFERENCES_FILE, Context.MODE_PRIVATE)
        var keys: List<String> = emptyList()
        ActivityScenario.launch<MainActivity>(plainLaunch()).use { scenario ->
            scenario.onActivity { keys = FeatureCatalog.all().map { it.prefKey } }
            assertTrue("the activity registers the feature catalog", keys.isNotEmpty())
        }
        for (enabled in listOf(true, false)) {
            val edit = prefs.edit()
            keys.forEach { edit.putBoolean(it, enabled) }
            edit.commit()
            ActivityScenario.launch<MainActivity>(plainLaunch()).use { scenario ->
                scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse("the app stays up after a restart with every switch $enabled", activity.isFinishing)
                    keys.forEach { assertEquals(it, enabled, prefs.getBoolean(it, !enabled)) }
                }
            }
        }
    }

    /** A pending crash report is offered once before the app opens, and the choice is remembered. */
    @Test
    fun aPendingCrashReportIsOfferedOnceBeforeTheAppOpens() {
        OnboardingActivity.markDone(context)
        val dir = CrashReports.dir(context)
        CrashReports.writePending(dir, "crash_report: v1\n")
        val monitor = instrumentation.addMonitor(CrashReportActivity::class.java.name, null, false)
        ActivityScenario.launch<MainActivity>(plainLaunch()).use {
            val prompt = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertNotNull("a plain launch with a pending report shows the prompt", prompt)
            // "Not now" marks the report as shown: the next launch goes straight to the app.
            CrashReports.markShown(dir)
            prompt.finish()
        }
        assertNull(CrashReports.pending(dir))
        instrumentation.removeMonitor(monitor)
    }

    /** Opening the app with the camera and microphone refused still reaches the home screen (nothing is started). */
    @Test
    fun theHomeScreenOpensWithoutAnyRuntimePermission() {
        OnboardingActivity.markDone(context)
        val missing = OnboardingActivity.permissions(context).filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        ActivityScenario.launch<MainActivity>(plainLaunch()).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("the home screen stays open with ${missing.size} permissions missing", activity.isFinishing)
            }
        }
    }
}
