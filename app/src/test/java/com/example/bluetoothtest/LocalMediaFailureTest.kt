package com.example.bluetoothtest

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalMediaFailureTest {
    @Test
    fun decoderPipelineFailureIsClassifiedAsLocal() {
        val codecError = IllegalStateException("Audio pipeline failed", RuntimeException("codec error 0x80001001"))
        try {
            inLocalMediaStage(LocalMediaStage.AUDIO_PIPELINE) { throw codecError }
            fail("expected a local media failure")
        } catch (error: LocalMediaPipelineException) {
            assertEquals(LocalMediaStage.AUDIO_PIPELINE, error.stage)
            assertSame(codecError, error.cause)
            val notice = localMediaFailureNotice(error)
            assertTrue(notice, notice.contains("audio decode/playback pipeline"))
            assertTrue(notice, notice.contains("Audio pipeline failed"))
            assertTrue(notice, notice.contains("not reconnecting"))
        }
    }

    @Test
    fun interruptionAndAlreadyClassifiedFailuresPassThroughUnchanged() {
        val interrupted = InterruptedException()
        try {
            inLocalMediaStage(LocalMediaStage.VIDEO_DECODER) { throw interrupted }
            fail("expected interruption")
        } catch (error: InterruptedException) {
            assertSame(interrupted, error)
        }
        val inner = LocalMediaPipelineException(LocalMediaStage.VIDEO_DECODER_SETUP, IllegalStateException("no decoder"))
        try {
            inLocalMediaStage(LocalMediaStage.AUDIO_PIPELINE) { throw inner }
            fail("expected the original classification")
        } catch (error: LocalMediaPipelineException) {
            assertSame(inner, error)
        }
    }

    @Test
    fun aSuccessfulStageReturnsItsValue() {
        assertEquals(7, inLocalMediaStage(LocalMediaStage.VIDEO_DECODER) { 7 })
    }

    @Test
    fun onlyLocalMediaStopsSkipAutomaticRetry() {
        val local = ReceiverStopCause.of(
            ReceiverStopKind.LOCAL_MEDIA,
            LocalMediaPipelineException(LocalMediaStage.AUDIO_PIPELINE, IllegalStateException("Audio pipeline failed")),
        )
        assertFalse(local.retriesAutomatically)
        ReceiverStopKind.entries.filter { it != ReceiverStopKind.LOCAL_MEDIA }.forEach { kind ->
            assertTrue(kind.name, ReceiverStopCause.of(kind, IOException("bt socket closed")).retriesAutomatically)
        }
    }

    @Test
    fun failureSignatureIgnoresNumbersButNotTheFault() {
        val first = failureSignature(IOException("bt socket closed, read return: -1 psm 129"))
        val second = failureSignature(IOException("bt socket closed, read return: -1 psm 131"))
        assertEquals(first, second)
        assertNotEquals(first, failureSignature(EOFException("bt socket closed, read return: -1 psm 129")))
        assertNotEquals(
            ReceiverStopCause.of(ReceiverStopKind.LINK, IOException("x")).signature,
            ReceiverStopCause.of(ReceiverStopKind.OTHER, IOException("x")).signature,
        )
    }

    @Test
    fun repeatedIdenticalFailuresBackOffExponentiallyToThirtySeconds() {
        val backoff = RepeatedFailureBackoff()
        // Lane-a A15s: the same failure about every 3 s after each reconnect became active.
        val delays = (0 until 9).map { backoff.record("LINK|same", it * 3_000L) }
        assertEquals(listOf(0L, 0L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun differentOrSpacedFailuresKeepTheExistingRecovery() {
        val backoff = RepeatedFailureBackoff()
        assertEquals(0L, backoff.record("LINK|a", 0L))
        assertEquals(0L, backoff.record("LINK|a", 1_000L))
        assertEquals(0L, backoff.record("OTHER|b", 2_000L))
        assertEquals(0L, backoff.record("LINK|a", 3_000L))
        assertEquals(0L, backoff.record("LINK|a", 4_000L))
        // A failure after a quiet minute is a new episode.
        assertEquals(0L, backoff.record("LINK|a", 70_000L))
        assertEquals(1, backoff.repeats)
        backoff.record("LINK|a", 71_000L)
        assertEquals(2_000L, backoff.record("LINK|a", 72_000L))
        backoff.reset()
        assertEquals(0L, backoff.record("LINK|a", 73_000L))
    }

    @Test
    fun listenerLedgerCountsEachListenerCloseOnce() {
        val ledger = ListenerLedger()
        val first = Any()
        val second = Any()
        assertEquals(1, ledger.opened(first))
        assertEquals(2, ledger.opened(second))
        assertTrue(ledger.closed(first))
        assertFalse(ledger.closed(first))
        assertFalse(ledger.closed(Any()))
        assertEquals(1, ledger.openCount)
        assertEquals(2, ledger.openedTotal)
    }
}
