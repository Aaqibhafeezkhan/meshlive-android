package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProximityPauseDebounceTest {
    @Test
    fun nearMustHoldForOneAndAHalfSecondsBeforeTheCameraPauses() {
        val debounce = ProximityPauseDebounce()
        val first = debounce.decide(0, wantPause = true, currentlyPaused = false)
        assertNull(first.apply)
        assertEquals(1_500L, first.recheckInMs)
        val mid = debounce.decide(1_000, wantPause = true, currentlyPaused = false)
        assertNull(mid.apply)
        assertEquals(500L, mid.recheckInMs)
        assertEquals(true, debounce.decide(1_500, wantPause = true, currentlyPaused = false).apply)
    }

    @Test
    fun aFlapShorterThanTheConfirmationNeverPauses() {
        val debounce = ProximityPauseDebounce()
        debounce.decide(0, wantPause = true, currentlyPaused = false)
        // The sensor reads far again before 1.5 s: nothing to do, and the next near restarts the clock.
        assertNull(debounce.decide(800, wantPause = false, currentlyPaused = false).apply)
        val again = debounce.decide(2_000, wantPause = true, currentlyPaused = false)
        assertEquals(1_500L, again.recheckInMs)
    }

    @Test
    fun resumeWaitsForThreeSecondsSinceThePauseSoTheCameraIsNotRestartedRapidly() {
        val debounce = ProximityPauseDebounce()
        debounce.decide(0, true, false)
        assertEquals(true, debounce.decide(1_500, true, false).apply) // paused at 1.5 s
        val early = debounce.decide(2_000, wantPause = false, currentlyPaused = true)
        assertNull(early.apply)
        assertEquals(2_500L, early.recheckInMs) // 1.5 s + 3 s = 4.5 s
        assertEquals(false, debounce.decide(4_500, wantPause = false, currentlyPaused = true).apply)
    }

    @Test
    fun aLongNearPeriodResumesAtOnceWhenTheUserMovesAway() {
        val debounce = ProximityPauseDebounce()
        debounce.decide(0, true, false)
        debounce.decide(1_500, true, false)
        assertEquals(false, debounce.decide(60_000, wantPause = false, currentlyPaused = true).apply)
    }

    @Test
    fun noChangeWantedNeedsNothing() {
        val debounce = ProximityPauseDebounce()
        val decision = debounce.decide(0, wantPause = false, currentlyPaused = false)
        assertNull(decision.apply)
        assertNull(decision.recheckInMs)
    }
}
