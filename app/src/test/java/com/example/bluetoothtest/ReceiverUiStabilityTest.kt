package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Viewer cover/notice hysteresis and the cooling-route browser line (PLAN: Connecting/Recovering flashes).
 * The episode list is the S22 viewer of 20261003-211741-s24-s22-readysetgo-golden1100-isolated-hold-on-a-be31
 * (s22-21254.log, "Receiver recovery UI: recovering=true/false"), relative to the first true at 21:17:49.376.
 */
class ReceiverUiStabilityTest {
    private val s22Episodes = listOf(
        0L to 1_103L,
        1_655L to 3_060L,
        3_462L to 5_120L,
        5_235L to 7_159L,
        7_297L to 11_462L,
        11_925L to 15_622L,
    )

    private fun stable() = ReceiverRecoveryNoticePolicy(holdMs = { ReceiverRecoveryNoticePolicy.REPEAT_HOLD_MS })

    /** Replays episodes the way MainActivity drives the policy and counts how often the cover turns off. */
    private fun coverOffCount(policy: ReceiverRecoveryNoticePolicy, episodes: List<Pair<Long, Long>>): Int {
        var offs = 0
        var shown = false
        var releaseAt: Long? = null
        for ((start, end) in episodes) {
            // A held cover that expired before this episode started is released first.
            if (shown && releaseAt != null && releaseAt <= start) { shown = false; offs++ }
            policy.update(true, start)
            shown = true
            releaseAt = null
            policy.update(false, end)
            val hold = policy.holdRemainingMs(end)
            if (hold > 0L) releaseAt = end + hold else { shown = false; offs++ }
        }
        if (shown) offs++ // the last held cover releases
        return offs
    }

    @Test
    fun theControlArmFlashesOncePerEpisode() {
        assertEquals(s22Episodes.size, coverOffCount(ReceiverRecoveryNoticePolicy(), s22Episodes))
    }

    @Test
    fun aRepeatingBurstKeepsOneCoverInsteadOfFlashingEveryGap() {
        // The first episode is isolated (clears at once); the five that follow are one held cover.
        assertEquals(2, coverOffCount(stable(), s22Episodes))
    }

    @Test
    fun anIsolatedRecoveryClearsTheInstantItEnds() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_230L)
        assertFalse(policy.recovering)
        assertEquals(0L, policy.holdRemainingMs(1_230L))
        assertFalse(policy.coverHeld(1_230L))
    }

    @Test
    fun twoRecoveriesFarApartAreBothIsolated() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_500L)
        policy.update(true, 1_500L + ReceiverRecoveryNoticePolicy.REPEAT_WITHIN_MS + 1)
        policy.update(false, 6_000L)
        assertEquals(0L, policy.holdRemainingMs(6_000L))
    }

    @Test
    fun aRecoveryThatFollowsARecentReleaseIsHeldAfterItEnds() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_500L)
        policy.update(true, 2_000L)
        policy.update(false, 2_400L)
        assertEquals(ReceiverRecoveryNoticePolicy.REPEAT_HOLD_MS, policy.holdRemainingMs(2_400L))
        assertTrue(policy.coverHeld(3_399L))
        assertFalse(policy.coverHeld(3_400L))
    }

    @Test
    fun theCoverIsNeverReleasedWhileTheDecoderStillReportsRecovery() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_100L)
        policy.update(true, 1_300L)
        assertTrue(policy.recovering)
        assertEquals(0L, policy.holdRemainingMs(60_000L))
        assertTrue(policy.coverHeld(60_000L))
    }

    @Test
    fun aRecoveryInsideTheHoldContinuesTheSameNoticeReveal() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_300L)
        policy.update(true, 1_800L)
        policy.update(false, 4_000L)
        // Third pulse 0.5 s into the hold: the label reveal started at 1.8 s (the second episode), not now.
        policy.update(true, 4_500L)
        assertEquals(0L, policy.noticeDelayRemainingMs(4_500L))
        assertEquals(1f, policy.noticeAlpha(4_500L), 0f)
    }

    @Test
    fun aRecoveryAfterTheHoldExpiredStartsAFreshReveal() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_300L)
        policy.update(true, 1_800L)
        policy.update(false, 4_000L)
        policy.update(true, 5_200L)
        assertEquals(350L, policy.noticeDelayRemainingMs(5_200L))
    }

    @Test
    fun resetForgetsAHeldCoverSoANewReceiverStartsClean() {
        val policy = stable()
        policy.update(true, 1_000L)
        policy.update(false, 1_300L)
        policy.update(true, 1_800L)
        policy.update(false, 2_200L)
        assertTrue(policy.coverHeld(2_300L))
        policy.reset()
        assertFalse(policy.coverHeld(2_300L))
        policy.update(true, 2_400L)
        assertEquals(350L, policy.noticeDelayRemainingMs(2_400L))
    }

    @Test
    fun theBrowserNamesACoolingStreamInsteadOfSayingNoneIsNearby() {
        assertEquals(
            "A stream is in range. Trying it again in about 8 s.",
            receiverBrowserEmptyStatus(coolingRetryMs = 7_100L),
        )
        assertEquals(
            "A stream is in range. Trying it again in about 1 s.",
            receiverBrowserEmptyStatus(coolingRetryMs = 0L),
        )
        assertEquals("No nearby streams yet. Scanning continues.", receiverBrowserEmptyStatus(coolingRetryMs = null))
    }

    @Test
    fun theControlArmKeepsTheOldBrowserLine() {
        assertEquals(
            "No nearby streams yet. Scanning continues.",
            receiverBrowserEmptyStatus(coolingRetryMs = 7_100L, stable = false),
        )
    }
}
