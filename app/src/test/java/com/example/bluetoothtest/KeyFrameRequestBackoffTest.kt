package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFrameRequestBackoffTest {
    private val second = 1_000_000_000L

    @Test
    fun backToBackRecoveryRequestsAreSpacedFurtherApart() {
        val backoff = KeyFrameRequestBackoff()
        assertTrue(backoff.shouldHonor(0, force = false))
        assertFalse(backoff.shouldHonor(second / 2, force = false))
        assertTrue(backoff.shouldHonor(second + 1, force = false))
        // Requests keep arriving: the next one waits 2 s, then 4 s.
        assertFalse(backoff.shouldHonor(2 * second, force = false))
        assertTrue(backoff.shouldHonor(3 * second + 2, force = false))
        assertFalse(backoff.shouldHonor(6 * second, force = false))
        assertTrue(backoff.shouldHonor(7 * second + 3, force = false))
    }

    @Test
    fun calmLinksResetAndJoiningViewersAreNeverDelayed() {
        val backoff = KeyFrameRequestBackoff()
        backoff.shouldHonor(0, force = false)
        backoff.shouldHonor(second + 1, force = false)
        assertTrue(backoff.shouldHonor(second + 2, force = true))
        assertTrue(backoff.shouldHonor(20 * second, force = false))
        assertTrue(backoff.shouldHonor(21 * second + 1, force = false))
    }
}
