package com.example.bluetoothtest

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallRingerPolicyTest {
    private fun decide(
        mode: Int = AudioManager.RINGER_MODE_NORMAL,
        filter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
        anyCaller: Boolean = false,
        vibrateWhenRinging: Boolean? = true,
        hasVibrator: Boolean = true,
    ) = CallRingerPolicy.decide(mode, filter, anyCaller, vibrateWhenRinging, hasVibrator)

    @Test
    fun constantsMatchThePlatform() {
        assertEquals(AudioManager.RINGER_MODE_SILENT, CallRingerPolicy.MODE_SILENT)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, CallRingerPolicy.MODE_VIBRATE)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, CallRingerPolicy.MODE_NORMAL)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_UNKNOWN, CallRingerPolicy.FILTER_UNKNOWN)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, CallRingerPolicy.FILTER_ALL)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, CallRingerPolicy.FILTER_PRIORITY)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, CallRingerPolicy.FILTER_NONE)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, CallRingerPolicy.FILTER_ALARMS)
    }

    @Test
    fun ringerModeDecidesSoundAndVibration() {
        assertEquals(RingDecision(true, true, "ringer_normal"), decide())
        assertEquals(RingDecision(true, false, "ringer_normal"), decide(vibrateWhenRinging = false))
        // Unreadable "vibrate when ringing": vibrate, as before.
        assertEquals(RingDecision(true, true, "ringer_normal"), decide(vibrateWhenRinging = null))
        assertEquals(RingDecision(false, true, "ringer_vibrate"), decide(mode = AudioManager.RINGER_MODE_VIBRATE))
        assertEquals(RingDecision(false, false, "ringer_silent"), decide(mode = AudioManager.RINGER_MODE_SILENT))
        assertEquals(RingDecision(false, false, "ringer_vibrate"), decide(mode = AudioManager.RINGER_MODE_VIBRATE, hasVibrator = false))
    }

    @Test
    fun doNotDisturbSilencesUnlessCallsFromAnyoneAreAllowed() {
        assertEquals(RingDecision(false, false, "dnd_none"), decide(filter = NotificationManager.INTERRUPTION_FILTER_NONE))
        assertEquals(RingDecision(false, false, "dnd_alarms"), decide(filter = NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertEquals(RingDecision(false, false, "dnd_priority"), decide(filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertEquals(
            RingDecision(true, true, "ringer_normal"),
            decide(filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, anyCaller = true),
        )
        // Priority mode that allows calls still honours a silent ringer.
        assertEquals(
            RingDecision(false, false, "ringer_silent"),
            decide(mode = AudioManager.RINGER_MODE_SILENT, filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, anyCaller = true),
        )
        assertEquals(RingDecision(true, true, "ringer_normal"), decide(filter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }

    @Test
    fun legacyRingsAndVibratesAlways() {
        assertEquals(RingDecision(sound = true, vibrate = true, reason = "legacy"), CallRingerPolicy.LEGACY)
    }

    @Test
    fun ringtoneFallsBackPastSecurityExceptionsAndMissingTones() {
        val found = CallRingerPolicy.firstAvailable(
            listOf<Pair<String, () -> String?>>(
                "default" to { throw SecurityException("Samsung: WRITE_SETTINGS") },
                "settings_default" to { null },
                "bundled" to { "fallback" },
            ),
        )
        assertEquals("bundled" to "fallback", found)
        assertEquals("default" to "tone", CallRingerPolicy.firstAvailable(listOf<Pair<String, () -> String?>>("default" to { "tone" })))
        assertNull(CallRingerPolicy.firstAvailable(listOf<Pair<String, () -> String?>>("x" to { throw IllegalStateException() })))
    }
}
