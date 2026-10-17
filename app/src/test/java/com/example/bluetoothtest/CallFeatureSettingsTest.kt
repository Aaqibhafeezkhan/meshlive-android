package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The promoted features' one-default-one-switch contract (CallFeatureSettings.kt). */
class CallFeatureSettingsTest {
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

    @Test
    fun theNotificationPermissionIsAskedOnlyOnAndroid13WhenMissing() {
        assertTrue(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 33, granted = false))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 33, granted = true))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 32, granted = false))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = false, sdkInt = 34, granted = false))
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
