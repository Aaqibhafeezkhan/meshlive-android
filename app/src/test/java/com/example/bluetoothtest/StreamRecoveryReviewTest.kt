package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream join/recovery review fixes 2026-10-27 (B1-B4, B8, B9, S3, T10, T11). */
class StreamRecoveryReviewTest {
    @Test
    fun aReconnectThatNeverBecameActiveIsNotCountedActive() {
        val flags = ReceiverActivityFlags()
        flags.startAttempt()
        flags.onActive()
        assertTrue(flags.attempt)
        assertTrue(flags.session)
        // Link lost; the recovery reconnect starts a new attempt in the same playback session.
        flags.startAttempt()
        assertFalse(flags.attempt)
        assertTrue(flags.session)
        // A new join (not preserving playback) starts a new session.
        flags.newSession()
        assertFalse(flags.session)
    }

    @Test
    fun joinWaitsLongerOnlyForALinkAlreadyInSetup() {
        val wait = ReceiverJoinWait(startedMs = 1_000L, baseMs = 15_000L)
        assertEquals(15_000L, wait.remainingMs(1_000L))
        // No central connected: the deadline stands.
        assertFalse(wait.extendForLinkInSetup(setupCentralSinceMs = null, nowMs = 16_000L))
        // A central from before this join does not count.
        assertFalse(wait.extendForLinkInSetup(setupCentralSinceMs = 500L, nowMs = 16_000L))
        // The parent connected at 14 s and is still setting up at the deadline: once, 6 s more.
        assertTrue(wait.extendForLinkInSetup(setupCentralSinceMs = 15_000L, nowMs = 16_000L))
        assertEquals(ReceiverJoinWait.SETUP_EXTENSION_MS, wait.remainingMs(16_000L))
        assertFalse(wait.extendForLinkInSetup(setupCentralSinceMs = 15_000L, nowMs = 22_000L))
    }

    @Test
    fun anUnauthenticatedConnectionDoesNotEndTheJoin() {
        val wait = ReceiverJoinWait(startedMs = 0L, baseMs = 15_000L)
        assertTrue(wait.keepWaitingAfter(authenticated = false, nowMs = 5_000L))
        // The parent authenticated and then failed: that is this join's failure.
        assertFalse(wait.keepWaitingAfter(authenticated = true, nowMs = 5_000L))
        assertFalse(wait.keepWaitingAfter(authenticated = false, nowMs = 15_000L))
    }

    @Test
    fun gattJoinTimeoutCountsAgainstTheParent() {
        val cause = ReceiverStopCause.joinTimeout()
        assertEquals(ReceiverStopKind.JOIN_TIMEOUT, cause.kind)
        assertTrue(isChildEstablishmentFailure(wasActive = false, stopSignature = cause.signature))
        assertFalse(isChildEstablishmentFailure(wasActive = true, stopSignature = cause.signature))
        assertTrue(cause.retriesAutomatically)
    }

    @Test
    fun gattControlFailureEndsTheLinkOnlyWhenTheParentWentQuiet() {
        assertTrue(GattControlFailure.endsLink(ioError = true, parentHeardRecently = false))
        assertFalse(GattControlFailure.endsLink(ioError = true, parentHeardRecently = true))
        assertFalse(GattControlFailure.endsLink(ioError = false, parentHeardRecently = false))
    }

    @Test
    fun upstreamLossClosesTheChildAtOnce() {
        assertEquals(0L, ForwarderChildClose.writerWaitMs(withdrawing = false, terminalForwarded = false, withdrawalAckMs = 5_000L, gracefulMs = 2_000L))
        assertEquals(2_000L, ForwarderChildClose.writerWaitMs(withdrawing = false, terminalForwarded = true, withdrawalAckMs = 5_000L, gracefulMs = 2_000L))
        assertEquals(5_000L, ForwarderChildClose.writerWaitMs(withdrawing = true, terminalForwarded = false, withdrawalAckMs = 5_000L, gracefulMs = 2_000L))
    }

    @Test
    fun serverFreesItsSlotWhenThisPhoneClosesTheLink() {
        val own = GattServerLocalClose.steps("AA", "AA", advertise = true, serverClosed = false, advertising = false)
        assertTrue(own.closeConnection)
        assertTrue(own.readvertise)
        // Another central's close, a closed server, or an advert already on: nothing to redo.
        assertFalse(GattServerLocalClose.steps("BB", "AA", true, false, false).closeConnection)
        assertFalse(GattServerLocalClose.steps("AA", "AA", true, serverClosed = true, advertising = false).readvertise)
        assertFalse(GattServerLocalClose.steps("AA", "AA", true, false, advertising = true).readvertise)
        assertFalse(GattServerLocalClose.steps("AA", "AA", advertise = false, serverClosed = false, advertising = false).readvertise)
    }

    @Test
    fun mainLooperConnectNeverLeaksAClientCreatedAtTheTimeout() {
        // The posted call creates its client only after the waiter gave up: it must be closed, not returned or leaked.
        repeat(20) {
            val lateClosed = AtomicInteger(0)
            var posted: (() -> Unit)? = null
            val result = GattConnectCompat.connectOn(
                arm = GattConnectCompat.Arm.MAIN_LOOPER,
                onMainThread = false,
                post = { action -> posted = action; true },
                call = { "client" },
                timeoutMs = 1L,
                onLate = { lateClosed.incrementAndGet() },
            )
            assertNull(result)
            posted!!.invoke()
            assertEquals(1, lateClosed.get())
        }
    }
}
