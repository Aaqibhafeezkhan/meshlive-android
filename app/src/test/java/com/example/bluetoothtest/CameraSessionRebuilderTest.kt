package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake camera callbacks drive the rebuild state machine; the new session must wait for the right onClosed. */
class CameraSessionRebuilderTest {
    private class Clock(var now: Long = 10_000L)

    private class Scheduler(private val clock: Clock) : CameraRebuildScheduler {
        private data class Item(val at: Long, val action: Runnable)

        private val items = mutableListOf<Item>()
        override fun postDelayed(delayMs: Long, action: Runnable) {
            items += Item(clock.now + delayMs, action)
        }

        override fun remove(action: Runnable) {
            items.removeAll { it.action === action }
        }

        /** Advances time, running every action that falls due in order. */
        fun advance(ms: Long) {
            val target = clock.now + ms
            while (true) {
                val next = items.filter { it.at <= target }.minByOrNull { it.at } ?: break
                items.remove(next)
                clock.now = maxOf(clock.now, next.at)
                next.action.run()
            }
            clock.now = target
        }

        val pending: Int get() = items.size
    }

    private class Host(var session: Boolean = true) : CameraRebuildHost {
        val events = mutableListOf<String>()
        override fun hasSession(): Boolean = session
        override fun closeSession() {
            events += "closeSession"
            session = false
        }

        override fun closeDevice() {
            events += "closeDevice"
        }

        override fun openDevice() {
            events += "openDevice"
        }

        override fun createSession() {
            events += "createSession"
            session = true
        }

        override fun log(message: String) {
            events += "log:$message"
        }

        override fun rebuildFailed(message: String) {
            events += "failed:$message"
        }

        fun calls(): List<String> = events.filterNot { it.startsWith("log:") }
    }

    private fun make(host: Host = Host()): Triple<CameraSessionRebuilder, Host, Scheduler> {
        val clock = Clock()
        val scheduler = Scheduler(clock)
        return Triple(CameraSessionRebuilder(host, scheduler, { clock.now }), host, scheduler)
    }

    @Test
    fun sessionOnlyKeepsTheFormerImmediateRecreate() {
        val (rebuilder, host, _) = make()
        rebuilder.rebuild(CameraRebuildMode.SESSION_ONLY)
        assertEquals(listOf("closeSession", "createSession"), host.calls())
        assertFalse(rebuilder.busy)
    }

    @Test
    fun waitSessionClosedCreatesTheNextSessionOnlyAfterOnClosed() {
        val (rebuilder, host, _) = make()
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        assertEquals(listOf("closeSession"), host.calls())
        assertTrue(rebuilder.busy)
        rebuilder.onSessionClosed()
        assertEquals(listOf("closeSession", "createSession"), host.calls())
        assertFalse(rebuilder.busy)
    }

    @Test
    fun waitSessionClosedFallsBackToATimeoutWhenOnClosedNeverComes() {
        val (rebuilder, host, scheduler) = make()
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        scheduler.advance(CameraSessionRebuilder.SESSION_CLOSE_TIMEOUT_MS - 1)
        assertEquals(listOf("closeSession"), host.calls())
        scheduler.advance(2)
        assertEquals(listOf("closeSession", "createSession"), host.calls())
    }

    @Test
    fun waitSessionClosedWithoutASessionCreatesAtOnce() {
        val (rebuilder, host, _) = make(Host(session = false))
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        assertEquals(listOf("createSession"), host.calls())
    }

    @Test
    fun reopenClosesSessionThenDeviceThenOpensThenCreates() {
        val (rebuilder, host, _) = make()
        rebuilder.rebuild(CameraRebuildMode.REOPEN_DEVICE)
        assertEquals(listOf("closeSession"), host.calls())
        rebuilder.onSessionClosed()
        assertEquals(listOf("closeSession", "closeDevice"), host.calls())
        assertTrue(rebuilder.reopening)
        rebuilder.onDeviceClosed()
        assertEquals(listOf("closeSession", "closeDevice", "openDevice"), host.calls())
        assertFalse("an open before onOpened must not create a session", host.calls().contains("createSession"))
        assertTrue(rebuilder.onDeviceOpened())
        assertEquals(listOf("closeSession", "closeDevice", "openDevice", "createSession"), host.calls())
        assertFalse(rebuilder.busy)
    }

    @Test
    fun anOpenThatBelongsToNoRebuildIsLeftToTheCaller() {
        val (rebuilder, host, _) = make()
        assertFalse(rebuilder.onDeviceOpened())
        assertTrue(host.calls().isEmpty())
    }

    @Test
    fun reopenIsLimitedToOnePerThreeSecondsAndPostponedNotDropped() {
        val (rebuilder, host, scheduler) = make()
        fun completeReopen() {
            rebuilder.onSessionClosed()
            rebuilder.onDeviceClosed()
            assertTrue(rebuilder.onDeviceOpened())
        }
        rebuilder.rebuild(CameraRebuildMode.REOPEN_DEVICE)
        completeReopen()
        host.events.clear()
        scheduler.advance(1_000)
        rebuilder.rebuild(CameraRebuildMode.REOPEN_DEVICE)
        // Too soon: nothing happens yet.
        assertEquals(emptyList<String>(), host.calls())
        scheduler.advance(1_999)
        assertEquals(emptyList<String>(), host.calls())
        scheduler.advance(2)
        assertEquals(listOf("closeSession"), host.calls())
        completeReopen()
        assertEquals("closeSession", host.calls().first())
        assertEquals("createSession", host.calls().last())
    }

    @Test
    fun deviceThatNeverReopensIsReportedNotSilentlyLeftWithoutACamera() {
        val (rebuilder, host, scheduler) = make()
        rebuilder.rebuild(CameraRebuildMode.REOPEN_DEVICE)
        rebuilder.onSessionClosed()
        rebuilder.onDeviceClosed()
        scheduler.advance(CameraSessionRebuilder.DEVICE_OPEN_TIMEOUT_MS + 1)
        assertTrue(host.events.any { it.startsWith("failed:") })
        assertFalse(rebuilder.busy)
    }

    @Test
    fun missingDeviceCloseCallbackTimesOutAndStillReopens() {
        val (rebuilder, host, scheduler) = make(Host(session = false))
        rebuilder.rebuild(CameraRebuildMode.REOPEN_DEVICE)
        assertEquals(listOf("closeDevice"), host.calls())
        scheduler.advance(CameraSessionRebuilder.DEVICE_CLOSE_TIMEOUT_MS + 1)
        assertEquals(listOf("closeDevice", "openDevice"), host.calls())
    }

    @Test
    fun aRebuildDuringARebuildIsCoalescedAndRunsOnceAfterwards() {
        val (rebuilder, host, _) = make()
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        rebuilder.rebuild(CameraRebuildMode.SESSION_ONLY)
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        assertEquals(listOf("closeSession"), host.calls())
        rebuilder.onSessionClosed()
        // First rebuild created its session, then the queued one closed it again and waits for its onClosed.
        assertEquals(listOf("closeSession", "createSession", "closeSession"), host.calls())
        rebuilder.onSessionClosed()
        assertEquals(listOf("closeSession", "createSession", "closeSession", "createSession"), host.calls())
    }

    @Test
    fun cancelDropsEveryPendingStep() {
        val (rebuilder, host, scheduler) = make()
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        rebuilder.cancel()
        scheduler.advance(10_000)
        rebuilder.onSessionClosed()
        assertEquals(listOf("closeSession"), host.calls())
        assertEquals(0, scheduler.pending)
        assertFalse(rebuilder.busy)
    }

    @Test
    fun rebuildWithNoPreviewStillCreatesTheEncoderOnlySession() {
        // V-26: a background session has no preview; the stream plan keeps the encoder input and the rebuild path is unchanged.
        val plans = CameraStreamPlanner.plans(hasPreview = false, hasReference = false)
        assertEquals(listOf("encoder"), plans.map { it.streams })
        val (rebuilder, host, _) = make()
        rebuilder.rebuild(CameraRebuildMode.WAIT_SESSION_CLOSED)
        rebuilder.onSessionClosed()
        assertEquals("createSession", host.calls().last())
    }
}
