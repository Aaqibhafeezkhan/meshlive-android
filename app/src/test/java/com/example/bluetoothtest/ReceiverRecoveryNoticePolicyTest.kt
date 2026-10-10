package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverRecoveryNoticePolicyTest {
    @Test
    fun briefRecoveryDoesNotFlashATextNotice() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        assertEquals(350L, policy.noticeDelayRemainingMs(1_000L))
        assertEquals(50L, policy.noticeDelayRemainingMs(1_300L))

        policy.update(false, 1_300L)
        assertFalse(policy.recovering)
        assertNull(policy.noticeDelayRemainingMs(1_350L))
    }

    @Test
    fun persistentRecoveryShowsItsNoticeAtTheBoundedDeadline() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        assertTrue(policy.recovering)
        assertEquals(1L, policy.noticeDelayRemainingMs(1_349L))
        assertEquals(0L, policy.noticeDelayRemainingMs(1_350L))
        assertEquals(0L, policy.noticeDelayRemainingMs(6_000L))
    }

    @Test
    fun duplicateRecoveryCallbacksCannotPostponeAPersistentNotice() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        policy.update(true, 1_200L)
        assertEquals(0L, policy.noticeDelayRemainingMs(1_350L))
    }

    @Test
    fun measuredShortEpisodesAfterTheDelayDoNotFlashOpaqueText() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        assertEquals(0f, policy.noticeAlpha(1_350L), 0f)
        assertTrue(policy.noticeAlpha(1_396L) < 0.001f)
        assertTrue(policy.noticeAlpha(1_471L) < 0.02f)
        policy.update(false, 1_471L)
        assertEquals(0f, policy.noticeAlpha(1_700L), 0f)
    }

    @Test
    fun aSustainedRecoveryBecomesReadableWithoutHoldingTheRecoveredVideo() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        assertTrue(policy.noticeAlpha(1_600L) > 0.25f)
        assertEquals(1f, policy.noticeAlpha(1_700L), 0f)
        policy.update(false, 1_701L)
        assertFalse(policy.recovering)
        assertEquals(0f, policy.noticeAlpha(1_701L), 0f)
    }

    @Test
    fun duplicateCallbacksAndTheNextEpisodeCannotRestartAnOldReveal() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        policy.update(true, 1_600L)
        assertEquals(1f, policy.noticeAlpha(1_700L), 0f)
        policy.update(false, 1_710L)
        policy.update(true, 1_720L)
        assertEquals(0f, policy.noticeAlpha(1_800L), 0f)
    }

    @Test
    fun aFinishedEpisodeCannotRevealTheNextEpisodesNoticeEarly() {
        val policy = ReceiverRecoveryNoticePolicy()
        policy.update(true, 1_000L)
        policy.update(false, 1_250L)
        policy.update(true, 1_300L)
        assertEquals(300L, policy.noticeDelayRemainingMs(1_350L))
    }

    @Test
    fun recreatingTheSurfaceDuringRouteSearchCannotStartAnUnselectedReceiver() {
        assertFalse(receiverStartAllowed(false, true, false))
        assertFalse(receiverStartAllowed(false, true, true))
        assertFalse(receiverStartAllowed(false, false, false))
    }

    @Test
    fun selectedAndExplicitDirectDiagnosticReceiversCanStart() {
        assertTrue(receiverStartAllowed(true, false, false))
        assertTrue(receiverStartAllowed(false, false, true))
        assertFalse(receiverStartAllowed(true, true, false))
    }
}
