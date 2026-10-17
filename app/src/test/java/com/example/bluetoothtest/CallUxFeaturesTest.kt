package com.example.bluetoothtest

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUxFeaturesTest {
    @Test
    fun userSessionsGetTheBugFixesAndTheCallFeaturesButNoExperiment() {
        val release = CallUxSettings.resolve(debugBuild = false, benchmarkLaunch = false) { true }
        // Ringback, call notifications, ear camera pause and lock-screen calls are on by default since 2026-10-17.
        assertEquals(
            CallUxSettings(
                audioFocus = true, ringerPolicy = true, ringback = true, callStyleNotifications = true,
                proximityCameraPause = true, lockScreen = true,
            ),
            release,
        )
        // A release build ignores launch extras even if something claims a benchmark launch.
        assertEquals(release, CallUxSettings.resolve(debugBuild = false, benchmarkLaunch = true) { true })
        assertEquals(release, CallUxSettings.resolve(debugBuild = true, benchmarkLaunch = false) { true })
    }

    @Test
    fun benchmarkLaunchesBehaveAsBeforeUnlessEachExtraOptsIn() {
        assertEquals(CallUxSettings(), CallUxSettings.resolve(debugBuild = true, benchmarkLaunch = true) { false })
        val focusOnly = CallUxSettings.resolve(true, true) { it == CallUxSettings.EXTRA_AUDIO_FOCUS }
        assertEquals(CallUxSettings(audioFocus = true), focusOnly)
        val all = CallUxSettings.resolve(true, true) { true }
        assertEquals(CallUxSettings(true, true, true, true, true, true, lockScreen = true), all)
        // The lock-screen extra keeps its old name and effect, and a stored choice never leaks into a measured case.
        val lockOnly = CallUxSettings.resolve(true, true, stored = { true }) { it == "test_call_lock_screen" }
        assertEquals(CallUxSettings(lockScreen = true), lockOnly)
    }

    @Test
    fun promotedFeaturesFollowTheStoredChoiceOrTheirDefaultInUserSessions() {
        val promoted = listOf(
            CallFeature.RINGBACK, CallFeature.EAR_CAMERA_PAUSE, CallFeature.LOCK_SCREEN_CALLS, CallFeature.CALL_NOTIFICATIONS,
        )
        val chosen = promoted.map { it.prefKey }.toSet()
        val stored = { key: String -> if (key in chosen) true else null }
        val enabled = CallUxSettings.resolve(debugBuild = false, benchmarkLaunch = false, stored = stored) { false }
        assertEquals(
            CallUxSettings(
                audioFocus = true, ringerPolicy = true, ringback = true, callStyleNotifications = true,
                proximityCameraPause = true, lockScreen = true,
            ),
            enabled,
        )
        // A DEBUG manual launch (no test_ extras) behaves like a user session, stored choices included.
        assertEquals(enabled, CallUxSettings.resolve(debugBuild = true, benchmarkLaunch = false, stored = stored) { false })
        // Untouched switches follow the shipped constants (all off until the phone tests pass).
        val untouched = CallUxSettings.resolve(debugBuild = false, benchmarkLaunch = false, stored = { null }) { true }
        assertEquals(CallFeatureDefaults.RINGBACK, untouched.ringback)
        assertEquals(CallFeatureDefaults.CALL_NOTIFICATIONS, untouched.callStyleNotifications)
        assertEquals(CallFeatureDefaults.EAR_CAMERA_PAUSE, untouched.proximityCameraPause)
        assertEquals(CallFeatureDefaults.LOCK_SCREEN_CALLS, untouched.lockScreen)
        // Audio starvation concealment stays a launch-extra experiment.
        assertFalse(CallUxSettings.resolve(false, false, stored = { true }) { true }.audioConcealment)
    }
}

class CallAudioFocusTest {
    @Test
    fun onlyTransientLossHoldsAndAnyGainResumes() {
        assertEquals(FocusAction.HOLD, CallAudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(FocusAction.NONE, CallAudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(FocusAction.NONE, CallAudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(FocusAction.RESUME, CallAudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(FocusAction.RESUME, CallAudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
    }

    @Test
    fun holdMutesAndResumeRestoresTheUsersOwnChoice() {
        val hold = CallFocusHold()
        assertEquals(true, hold.onAction(FocusAction.HOLD)) // cellular call: send Microphone muted (9)
        assertNull(hold.onAction(FocusAction.HOLD))
        assertEquals(false, hold.onAction(FocusAction.RESUME)) // back: unmute (10)
        assertNull(hold.onAction(FocusAction.NONE))
    }

    @Test
    fun userMuteDuringHoldSurvivesTheResume() {
        val hold = CallFocusHold()
        hold.onAction(FocusAction.HOLD)
        assertTrue(hold.onUserMuted(false)) // push-to-talk or unmute while held stays muted
        assertTrue(hold.onUserMuted(true))
        assertNull(hold.onAction(FocusAction.RESUME)) // still muted by the user: nothing to send
        assertTrue(hold.effectiveMuted)
        assertFalse(hold.onUserMuted(false))
    }

    @Test
    fun withoutFocusEventsTheMuteIsExactlyTheUsers() {
        val hold = CallFocusHold()
        assertTrue(hold.onUserMuted(true))
        assertFalse(hold.onUserMuted(false))
    }
}
