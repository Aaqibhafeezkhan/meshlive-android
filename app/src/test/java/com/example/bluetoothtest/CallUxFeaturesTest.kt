package com.example.bluetoothtest

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUxFeaturesTest {
    @Test
    fun userSessionsGetTheBugFixesAndNoExperiment() {
        val release = CallUxSettings.resolve(debugBuild = false, benchmarkLaunch = false) { true }
        assertEquals(CallUxSettings(audioFocus = true, ringerPolicy = true), release)
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
        assertEquals(CallUxSettings(true, true, true, true, true, true), all)
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
