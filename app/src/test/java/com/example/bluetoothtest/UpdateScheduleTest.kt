package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {
    private val hour = 60L * 60 * 1000
    private val day = UpdateSchedule.DAY_MS
    private val t0 = 1_800_000_000_000L

    @Test
    fun nothingRunsWhileTheSwitchIsOff() {
        assertFalse(UpdateSchedule.automaticDue(false, UpdateCheckState(), t0))
        assertTrue(UpdateSchedule.automaticDue(true, UpdateCheckState(), t0))
    }

    @Test
    fun afterAnswerTheNextAutomaticCheckWaitsADay() {
        val state = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, "v0.1.0-beta")
        assertFalse(UpdateSchedule.automaticDue(true, state, t0 + 1))
        assertFalse(UpdateSchedule.automaticDue(true, state, t0 + day - 1))
        assertTrue(UpdateSchedule.automaticDue(true, state, t0 + day))
        assertEquals("v0.1.0-beta", state.latestTag)
    }

    @Test
    fun anOfflineAttemptMayRetryAfterAnHourButNeverMoreOften() {
        val state = UpdateSchedule.afterNetworkFailure(UpdateCheckState(), t0)
        assertFalse(UpdateSchedule.automaticDue(true, state, t0 + hour - 1))
        assertTrue(UpdateSchedule.automaticDue(true, state, t0 + hour))
        assertEquals(0L, state.lastReachedMs)
    }

    @Test
    fun anOfflineAttemptAfterASuccessDoesNotBringTheDailyCheckForward() {
        var state = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, "v1.0.0")
        state = UpdateSchedule.afterNetworkFailure(state, t0 + 2 * hour)
        assertFalse(UpdateSchedule.automaticDue(true, state, t0 + 5 * hour))
        assertTrue(UpdateSchedule.automaticDue(true, state, t0 + day + 1))
    }

    @Test
    fun aRateLimitHoldsTheNextRequestUntilGitHubsResetButNotLongerThanADay() {
        val reset = t0 + 40 * 60 * 1000
        val limited = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, null, rateLimitedUntilMs = reset)
        assertFalse(UpdateSchedule.automaticDue(true, limited, reset - 1))
        // The daily rule still applies after the reset (an answer was received).
        assertFalse(UpdateSchedule.automaticDue(true, limited, reset + 1))
        assertTrue(UpdateSchedule.automaticDue(true, limited, t0 + day))
        val absurd = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, null, rateLimitedUntilMs = t0 + 30 * day)
        assertEquals(t0 + day, absurd.notBeforeMs)
        val past = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, null, rateLimitedUntilMs = t0 - 5)
        assertEquals(t0, past.notBeforeMs)
        assertEquals("a normal answer clears the hold", 0L, UpdateSchedule.afterAnswer(limited, t0 + day, null).notBeforeMs)
    }

    @Test
    fun rateLimitHeadersAreReadFromBothStyles() {
        assertNull(UpdateSchedule.rateLimitReset(200, 60, null, 0, t0))
        assertNull(UpdateSchedule.rateLimitReset(404, null, null, null, t0))
        assertEquals(t0 + 120_000, UpdateSchedule.rateLimitReset(429, 120, null, null, t0))
        assertEquals(t0 + 90_000, UpdateSchedule.rateLimitReset(403, 90, 1L, 5, t0))
        assertEquals(1_800_000_600_000L, UpdateSchedule.rateLimitReset(403, null, 1_800_000_600, 0, t0))
        assertNull("a plain 403 with quota left is not a rate limit", UpdateSchedule.rateLimitReset(403, null, 1_800_000_600, 7, t0))
        assertEquals(t0 + hour, UpdateSchedule.rateLimitReset(429, null, null, null, t0))
    }

    @Test
    fun aClockSetBackDoesNotFreezeTheSchedule() {
        val state = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, null)
        assertTrue(UpdateSchedule.automaticDue(true, state, t0 - 10 * day))
    }

    @Test
    fun aManualCheckWaitsTenMinutesAfterTheLastRequest() {
        val state = UpdateSchedule.afterAnswer(UpdateCheckState(), t0, null)
        assertFalse(UpdateSchedule.manualAllowed(state, t0 + 9 * 60_000))
        assertEquals(60_000L, UpdateSchedule.manualWaitMs(state, t0 + 9 * 60_000))
        assertTrue(UpdateSchedule.manualAllowed(state, t0 + 10 * 60_000))
        assertTrue(UpdateSchedule.manualAllowed(UpdateCheckState(), t0))
        assertEquals(0L, UpdateSchedule.manualWaitMs(UpdateCheckState(), t0))
    }

    // ---- ask once ----

    @Test
    fun theQuestionIsAskedOnlyOnTheGithubFlavourOnAReleaseSignedCopyOnceAndOnlyAtRest() {
        assertTrue(UpdatePrompt.shouldAsk(true, true, 0, false, true))
        assertFalse("fdroid flavour", UpdatePrompt.shouldAsk(false, true, 0, false, true))
        assertFalse("debug or F-Droid-signed copy: the answer would change nothing", UpdatePrompt.shouldAsk(true, false, 0, false, true))
        assertFalse("already asked, even if the answer was no", UpdatePrompt.shouldAsk(true, true, t0, false, true))
        assertFalse("benchmark launch", UpdatePrompt.shouldAsk(true, true, 0, true, true))
        assertFalse("a call or onboarding is on screen", UpdatePrompt.shouldAsk(true, true, 0, false, false))
    }

    @Test
    fun aRestoredYesWithoutItsMarkerIsForgottenAndAnAnswerNeedsBoth() {
        assertTrue(UpdateConsent.grantedFor(prefYes = true, markerExists = true))
        assertFalse("a preference restored onto a new phone has no marker", UpdateConsent.grantedFor(true, false))
        assertFalse(UpdateConsent.grantedFor(false, true))
        assertFalse(UpdateConsent.grantedFor(false, false))
        assertTrue(UpdateConsent.mustForget(prefYes = true, markerExists = false))
        assertFalse(UpdateConsent.mustForget(true, true))
        assertFalse("an explicit no stays a no", UpdateConsent.mustForget(false, false))
        assertFalse(UpdateConsent.mustForget(false, true))
    }

    private class MapStore : UpdateKeyValue {
        val longs = HashMap<String, Long>()
        val strings = HashMap<String, String?>()
        override fun getLong(key: String, default: Long) = longs[key] ?: default
        override fun getString(key: String) = strings[key]
        override fun put(longs: Map<String, Long>, strings: Map<String, String?>) {
            this.longs.putAll(longs)
            strings.forEach { (k, v) -> if (v == null) this.strings.remove(k) else this.strings[k] = v }
        }
    }

    @Test
    fun stateSurvivesARestartAndTheAnswerIsNotOverwrittenByACheck() {
        val kv = MapStore()
        val store = UpdateCheckStore(kv)
        assertEquals(UpdateCheckState(), store.load())
        store.markAsked(t0)
        val stale = store.load().copy(askedAtMs = 0)
        store.save(UpdateSchedule.afterAnswer(stale, t0 + 5, "v2.0.0", rateLimitedUntilMs = t0 + 100))
        val loaded = store.load()
        assertEquals("a check that started before the answer must not re-open the question", t0, loaded.askedAtMs)
        assertEquals(t0 + 5, loaded.lastReachedMs)
        assertEquals("v2.0.0", loaded.latestTag)
        assertEquals(t0 + 100, loaded.notBeforeMs)
    }
}
