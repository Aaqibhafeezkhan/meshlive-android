package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerLinkSilenceWatchTest {
    private val ms = 1_000_000L
    private val second = 1_000 * ms
    private val tick = ViewerLinkSilenceWatch.CHECK_MS * ms

    /** Drives the watch every 500 ms from [fromNs] to [toNs]; returns the first close verdict and when it came. */
    private fun run(
        watch: ViewerLinkSilenceWatch,
        fromNs: Long,
        toNs: Long,
        lastRead: (Long) -> Long,
        mediaStarted: (Long) -> Boolean = { true },
        silenceExpected: (Long) -> Boolean = { false },
    ): Pair<Long, Long>? {
        var now = fromNs
        while (now <= toNs) {
            watch.onTick(now, lastRead(now), mediaStarted(now), silenceExpected(now))?.let { return now to it }
            now += tick
        }
        return null
    }

    @Test
    fun aHealthyFifteenFpsStreamIsNeverClosed() {
        val frameNs = 66 * ms
        val verdict = run(ViewerLinkSilenceWatch(), 0L, 600 * second, lastRead = { now -> now - now % frameNs })
        assertNull(verdict)
    }

    @Test
    fun aStartedLinkThatGoesSilentIsClosedAfterEightSecondsNotBefore() {
        // Frames until 5 s, then nothing while the link stays up (the source keeps writing, the viewer gets no byte).
        val verdict = run(ViewerLinkSilenceWatch(), 0L, 60 * second, lastRead = { now -> minOf(now, 5 * second) })
        assertNotNull(verdict)
        val (closedAtNs, silentMs) = verdict!!
        assertEquals(13 * second, closedAtNs)
        assertEquals(8_000L, silentMs)
    }

    @Test
    fun setupAndAuthenticationBeforeTheHeaderAreNotJudged() {
        // No byte for 30 s while joining, then the header and steady media: no close.
        val verdict = run(
            ViewerLinkSilenceWatch(), 0L, 90 * second,
            lastRead = { now -> if (now < 30 * second) 0L else now },
            mediaStarted = { now -> now >= 30 * second },
        )
        assertNull(verdict)
    }

    @Test
    fun aPausedOrCameraOffStreamIsNeverClosedAndGetsAFullWindowAfterResuming() {
        val watch = ViewerLinkSilenceWatch()
        // Media until 10 s, paused (or camera off) from 10 s to 70 s: silence is expected.
        val pausedVerdict = run(
            watch, 0L, 70 * second,
            lastRead = { now -> minOf(now, 10 * second) },
            silenceExpected = { now -> now >= 10 * second },
        )
        assertNull(pausedVerdict)
        // Resumed at 70 s but nothing arrives: the window starts at the resume, not at the last read 60 s earlier.
        val resumedVerdict = run(watch, 70 * second + tick, 120 * second, lastRead = { 10 * second })
        assertNotNull(resumedVerdict)
        assertEquals(78 * second, resumedVerdict!!.first)
    }

    @Test
    fun audioOnlyLiveIsNeverJudged() {
        // Audio-only live with Experimental DTX can send nothing during silence: the caller passes it as expected silence.
        val verdict = run(ViewerLinkSilenceWatch(), 0L, 120 * second, lastRead = { 2 * second }, silenceExpected = { true })
        assertNull(verdict)
    }

    @Test
    fun oneWatchPerLink() {
        assertTrue(ViewerLinkSilenceWatch.applies(enabled = true, relayParentWatchRunning = false))
        assertFalse(ViewerLinkSilenceWatch.applies(enabled = true, relayParentWatchRunning = true))
        assertFalse(ViewerLinkSilenceWatch.applies(enabled = false, relayParentWatchRunning = false))
    }
}
