package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The promoted features' one-default-one-switch contract (CallFeatureSettings.kt). */
class CallFeatureSettingsTest {
    @Test
    fun fullScreenGuidanceFollowsTheSystemGrantAndControlArm() {
        assertTrue(FullScreenIntentAccess.showGuidance(34, true, false))
        assertFalse(FullScreenIntentAccess.showGuidance(33, true, false))
        assertFalse(FullScreenIntentAccess.showGuidance(35, true, true))
        assertFalse(FullScreenIntentAccess.showGuidance(35, false, false))
    }

    private val untouched = { _: String -> null as Boolean? }

    private fun resolve(
        benchmark: Boolean,
        extras: Set<String> = emptySet(),
        stored: Map<String, Boolean> = emptyMap(),
    ) = CallFeatureResolver.resolve(benchmark, { it in extras }, { stored[it] })

    @Test
    fun everyFeatureHasItsOwnPreferenceKeyAndLaunchExtra() {
        val features = CallFeature.entries
        assertEquals(features.size, features.map { it.prefKey }.toSet().size)
        assertEquals(features.size, features.map { it.benchmarkExtra }.toSet().size)
        features.forEach { assertTrue(it.name, it.benchmarkExtra.startsWith("test_")) }
        // The old experimental extras are the benchmark switches and keep their names (case IDs stay valid).
        assertEquals("test_voice_calls", CallFeature.VOICE_CALLS.benchmarkExtra)
        assertEquals("test_call_ringback", CallFeature.RINGBACK.benchmarkExtra)
        assertEquals("test_call_proximity_camera_pause", CallFeature.EAR_CAMERA_PAUSE.benchmarkExtra)
        assertEquals("test_call_lock_screen", CallFeature.LOCK_SCREEN_CALLS.benchmarkExtra)
        assertEquals("test_call_style_notification", CallFeature.CALL_NOTIFICATIONS.benchmarkExtra)
        assertEquals("test_store_forward", CallFeature.MESSAGES.benchmarkExtra)
        assertEquals("test_sf_auto_sync", CallFeature.AUTO_SYNC.benchmarkExtra)
        assertEquals("test_peer_labels", CallFeature.NAMES_AND_TITLES.benchmarkExtra)
    }

    @Test
    fun theOldPreferenceKeysStillCarryTheirSwitches() {
        // Users who turned an Advanced switch on keep it on after promotion.
        assertEquals("experimental_voice_calls_v2", CallFeature.VOICE_CALLS.prefKey)
        assertEquals("experimental_store_forward", CallFeature.MESSAGES.prefKey)
        assertEquals("experimental_peer_labels_v2", CallFeature.NAMES_AND_TITLES.prefKey)
    }

    @Test
    fun theDefaultsAreTheSingleShippedConstants() {
        val none = resolve(benchmark = false)
        assertEquals(CallFeatureDefaults.VOICE_CALLS, none.voiceCalls)
        assertEquals(CallFeatureDefaults.RINGBACK, none.ringback)
        assertEquals(CallFeatureDefaults.EAR_CAMERA_PAUSE, none.earCameraPause)
        assertEquals(CallFeatureDefaults.LOCK_SCREEN_CALLS, none.lockScreenCalls)
        assertEquals(CallFeatureDefaults.CALL_NOTIFICATIONS, none.callNotifications)
        assertEquals(CallFeatureDefaults.MESSAGES, none.messages)
        assertEquals(CallFeatureDefaults.AUTO_SYNC && CallFeatureDefaults.MESSAGES, none.autoSync)
        assertEquals(CallFeatureDefaults.NAMES_AND_TITLES, none.namesAndTitles)
    }

    @Test
    fun anExplicitChoiceBeatsTheDefaultBothWays() {
        val allOn = CallFeature.entries.associate { it.prefKey to true }
        val on = resolve(benchmark = false, stored = allOn)
        assertEquals(CallFeatureSet(true, true, true, true, true, true, true, true), on)
        val allOff = CallFeature.entries.associate { it.prefKey to false }
        assertEquals(CallFeatureSet(), resolve(benchmark = false, stored = allOff))
    }

    @Test
    fun automaticSyncNeverRunsWithoutMessages() {
        val syncOnly = mapOf(CallFeature.AUTO_SYNC.prefKey to true)
        assertFalse(resolve(benchmark = false, stored = syncOnly).autoSync)
        val both = syncOnly + (CallFeature.MESSAGES.prefKey to true)
        assertTrue(resolve(benchmark = false, stored = both).autoSync)
        val extrasSyncOnly = setOf(CallFeature.AUTO_SYNC.benchmarkExtra)
        assertFalse(resolve(benchmark = true, extras = extrasSyncOnly).autoSync)
        assertTrue(resolve(benchmark = true, extras = extrasSyncOnly + CallFeature.MESSAGES.benchmarkExtra).autoSync)
    }

    @Test
    fun aBenchmarkLaunchReadsOnlyItsExtrasAndIgnoresEveryStoredChoice() {
        val allOn = CallFeature.entries.associate { it.prefKey to true }
        assertEquals(CallFeatureSet(), resolve(benchmark = true, stored = allOn))
        val onlyVoice = resolve(benchmark = true, extras = setOf("test_voice_calls"), stored = allOn)
        assertEquals(CallFeatureSet(voiceCalls = true), onlyVoice)
    }

    @Test
    fun theDefaultNeverDependsOnManualSettingsOrTheBuildType() {
        // The resolver has no advanced-settings or debug-build input at all: same answer for any build.
        assertEquals(resolve(benchmark = false), CallFeatureResolver.resolve(false, { true }, untouched))
    }

    @Test
    fun fullScreenAlertPromptShowsOnlyWhenTheFeatureIsOnAndAndroid14RefusesIt() {
        val sdk14 = 34
        assertTrue(FullScreenIntentAccess.needsPrompt(sdk14, featureOn = true, canUse = false))
        assertFalse(FullScreenIntentAccess.needsPrompt(sdk14, featureOn = true, canUse = true))
        assertFalse(FullScreenIntentAccess.needsPrompt(sdk14, featureOn = false, canUse = false))
        assertFalse("before Android 14 the permission is a plain install-time grant", FullScreenIntentAccess.needsPrompt(33, true, false))
    }

    /** Redmi 12 (HyperOS, API 35): access denied, so the calls area offers it once; Settings keeps its link; no nagging. */
    @Test
    fun theCallsAreaOffersFullScreenAccessOnceOnlyWhenAndroidRefusesIt() {
        assertTrue(FullScreenIntentAccess.offerOnce(35, featureOn = true, canUse = false, alreadyPrompted = false))
        assertFalse("never a second time", FullScreenIntentAccess.offerOnce(35, featureOn = true, canUse = false, alreadyPrompted = true))
        assertFalse("allowed: nothing to offer", FullScreenIntentAccess.offerOnce(36, featureOn = true, canUse = true, alreadyPrompted = false))
        assertFalse("lock-screen calls off", FullScreenIntentAccess.offerOnce(35, featureOn = false, canUse = false, alreadyPrompted = false))
        assertFalse("Android 13 grants it at install", FullScreenIntentAccess.offerOnce(33, featureOn = true, canUse = false, alreadyPrompted = false))
        assertTrue("Settings still shows its link after the one prompt", FullScreenIntentAccess.needsPrompt(35, featureOn = true, canUse = false))
        assertEquals("call_fsi: allowed=false | prompted=true", FullScreenIntentAccess.logLine(allowed = false, prompted = true))
    }

    /**
     * Phone run userflow2-ef96bbf0 xiaomi-fsi-prompt: the Calls tab opens on its Nearby segment (showCallsNearby, the call
     * browser), but the offer was wired only to the Recent segment (showCallsScreen) and to a resume with Recent visible,
     * so it never showed. Every way into the calls area offers it (once).
     */
    @Test
    fun everyWayIntoTheCallsAreaOffersFullScreenAccess() {
        val source = listOf(
            java.io.File("src/main/java/com/example/bluetoothtest/MainActivity.kt"),
            java.io.File("app/src/main/java/com/example/bluetoothtest/MainActivity.kt"),
        ).first { it.exists() }.readText().replace("\r\n", "\n")
        listOf("private fun showCallsNearby()", "private fun showContactsTab()", "private fun showCallsScreen(").forEach { entry ->
            val body = source.substringAfter(entry).substringBefore("\n    }\n")
            assertTrue(entry, body.contains("checkFullScreenCallAccess(offerPrompt = true)"))
        }
        val resume = source.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue(resume.contains("it == MainTab.CALLS || it == MainTab.CONTACTS"))
    }

    @Test
    fun theNotificationPermissionIsAskedOnlyOnAndroid13WhenMissing() {
        assertTrue(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 33, granted = false))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 33, granted = true))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 32, granted = false))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = false, sdkInt = 34, granted = false))
    }

    @Test
    fun aUserWithOnlyTheLockScreenCallOnIsAskedForTheNotificationPermission() {
        // A release user session: lock-screen calls on, CallStyle notifications turned off.
        val settings = CallUxSettings.resolve(debugBuild = false, benchmarkLaunch = false, stored = { key ->
            if (key == CallFeature.CALL_NOTIFICATIONS.prefKey) false else null
        }) { false }
        assertTrue(settings.lockScreen)
        assertFalse(settings.callStyleNotifications)
        assertTrue(callAlertNeedsNotificationPermission(settings, sdkInt = 33, granted = false))
        assertFalse(callAlertNeedsNotificationPermission(CallUxSettings(), sdkInt = 33, granted = false))
    }

    @Test
    fun theLockScreenPolicyShowsTheCallOnlyWhileACallExists() {
        assertTrue(CallLockScreenPolicy.showOverLockScreen(true, PrivateCallPhase.INCOMING_RINGING))
        assertTrue(CallLockScreenPolicy.showOverLockScreen(true, PrivateCallPhase.ACTIVE))
        assertFalse(CallLockScreenPolicy.showOverLockScreen(true, PrivateCallPhase.ENDED))
        assertFalse(CallLockScreenPolicy.showOverLockScreen(true, null))
        assertFalse(CallLockScreenPolicy.showOverLockScreen(false, PrivateCallPhase.ACTIVE))
        assertTrue(CallLockScreenPolicy.turnScreenOn(true, PrivateCallPhase.INCOMING_RINGING))
        assertFalse(CallLockScreenPolicy.turnScreenOn(true, PrivateCallPhase.ACTIVE))
        assertNotEquals(CallLockScreenPolicy.turnScreenOn(true, PrivateCallPhase.INCOMING_RINGING), CallLockScreenPolicy.turnScreenOn(false, PrivateCallPhase.INCOMING_RINGING))
    }
}
