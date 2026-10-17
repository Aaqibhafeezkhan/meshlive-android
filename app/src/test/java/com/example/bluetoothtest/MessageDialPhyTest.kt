package com.example.bluetoothtest

import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A store-and-forward message-exchange dial gets the call's 2M protections (lane3-asus-a15s-m31-20261013: the A15s dialing the
 * ASUS lost the ASUS -> A15s direction right after the 2M PHY update; six of seven dials ended in an EOFException about 5 s later).
 * A fake LE link reproduces that ordering: the link opens, the PHY moves to 2M, and the peer's hello never arrives.
 */
class MessageDialPhyTest {
    /** A hung dial fails the test instead of the build (the fake link has no supervision timeout of its own). */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    /**
     * A guard that cannot fire during a test, however starved the machine is: tests about a handshake that finishes in time must
     * not race a real wall-clock guard. The guard thread is released the moment the attempt settles.
     */
    private val unreachableGuardMs = 3_600_000L

    /** A link whose peer -> us direction works only on 1M: on 2M the hello read blocks until the link is closed, then EOF. */
    private class FakeLeLink(val phy: BlePhyPreference) {
        private val closed = CountDownLatch(1)
        @Volatile var closeCount = 0

        fun close() {
            if (closed.count > 0L) closeCount++
            closed.countDown()
        }

        /** The hop handshake's read of the peer hello. */
        fun readHello(): String {
            if (phy == BlePhyPreference.ONE_M_ADAPTIVE) return "hello"
            closed.await()
            throw EOFException("link closed before the peer hello")
        }
    }

    private val noRunLimit = { _: Long -> true }

    @Test
    fun aFirstTwoMLinkWhoseHelloNeverArrivesIsClosedAtTheStallGuardAndRetriedOnOneM() {
        val opened = ArrayList<FakeLeLink>()
        var retries = 0
        val startedNs = System.nanoTime()
        val dialed = MessageDialPhy.dial(
            firstPhy = BlePhyPreference.TWO_M,
            stallGuardMs = 150,
            retryAllowed = noRunLimit,
            elapsedMs = { (System.nanoTime() - startedNs) / 1_000_000 },
            attempt = { phy, guard ->
                MessageDialPhy.openAndAuthenticate(
                    stallGuardMs = guard,
                    open = { FakeLeLink(phy).also(opened::add) },
                    close = { it.close() },
                    authenticate = { it.readHello() },
                    running = { true },
                    nowMs = { System.nanoTime() / 1_000_000 },
                )
            },
            beforeRetry = { error ->
                retries++
                assertTrue("the guard reports a stalled setup", error is CallSetupStalledException)
            },
        )
        assertEquals(1, retries)
        assertTrue(dialed.retried)
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, dialed.phy)
        assertEquals("hello", dialed.link)
        assertEquals(2, opened.size)
        assertEquals("the dead 2M link was closed", 1, opened[0].closeCount)
        assertEquals("the healthy 1M link stays open", 0, opened[1].closeCount)
        assertSame(opened[1], dialed.connection)
        // The fake link never ends by itself, so a link that was closed at all was closed by the guard. The wall-clock bound only
        // catches a hang and is far above anything a starved machine needs.
        assertTrue("closed at the guard, not after a supervision timeout", (System.nanoTime() - startedNs) / 1_000_000 < 60_000)
    }

    @Test
    fun aHandshakeThatFinishesBeforeTheGuardIsKeptWholeAndNeverRetried() {
        val attempts = AtomicInteger()
        val dialed = MessageDialPhy.dial(
            firstPhy = BlePhyPreference.TWO_M,
            stallGuardMs = unreachableGuardMs,
            retryAllowed = noRunLimit,
            elapsedMs = { 0L },
            attempt = { phy, guard ->
                attempts.incrementAndGet()
                MessageDialPhy.openAndAuthenticate(
                    stallGuardMs = guard,
                    open = { FakeLeLink(BlePhyPreference.ONE_M_ADAPTIVE) },
                    close = { it.close() },
                    authenticate = { it.readHello() },
                    running = { true },
                    nowMs = { System.nanoTime() / 1_000_000 },
                ).also { assertEquals(BlePhyPreference.TWO_M, phy) }
            },
            beforeRetry = { fail("no retry expected") },
        )
        assertFalse(dialed.retried)
        assertEquals(BlePhyPreference.TWO_M, dialed.phy)
        assertEquals(1, attempts.get())
        assertEquals(0, dialed.connection.closeCount)
    }

    @Test
    fun theRetryIsBoundedToOneAttemptOnOneMAndFailsTheSecondErrorThrough() {
        val phys = ArrayList<BlePhyPreference>()
        try {
            MessageDialPhy.dial<FakeLeLink, String>(
                firstPhy = BlePhyPreference.TWO_M,
                stallGuardMs = 100,
                retryAllowed = noRunLimit,
                elapsedMs = { 0L },
                attempt = { phy, _ -> phys += phy; throw IOException("down on $phy") },
                beforeRetry = { },
            )
            fail("the second failure must propagate")
        } catch (error: IOException) {
            assertEquals("down on ${BlePhyPreference.ONE_M_ADAPTIVE}", error.message)
        }
        assertEquals(listOf(BlePhyPreference.TWO_M, BlePhyPreference.ONE_M_ADAPTIVE), phys)
    }

    @Test
    fun noRetryWhenTheFirstAttemptWasAlreadyOnOneMOutsideTheEarlyLossWindowOrNotLe() {
        val ok = AdaptivePhyMemory.EARLY_LOSS_MS
        assertTrue(MessageDialPhy.retryEligible(true, true, true, BlePhyPreference.TWO_M, 0))
        assertTrue(MessageDialPhy.retryEligible(true, true, true, BlePhyPreference.TWO_M, ok))
        assertFalse("already 1M (remembered bad)", MessageDialPhy.retryEligible(true, true, true, BlePhyPreference.ONE_M_ADAPTIVE, 0))
        assertFalse("window over", MessageDialPhy.retryEligible(true, true, true, BlePhyPreference.TWO_M, ok + 1))
        assertFalse("Classic has no PHY", MessageDialPhy.retryEligible(true, true, false, BlePhyPreference.TWO_M, 0))
        assertFalse("switch off", MessageDialPhy.retryEligible(false, true, true, BlePhyPreference.TWO_M, 0))
        assertFalse("endpoint stopped", MessageDialPhy.retryEligible(true, false, true, BlePhyPreference.TWO_M, 0))

        // A first failure with no retry allowed is the original error, and the first attempt then runs unguarded.
        var guardSeen = -1L
        try {
            MessageDialPhy.dial<FakeLeLink, String>(
                firstPhy = BlePhyPreference.ONE_M_ADAPTIVE,
                stallGuardMs = 3_000,
                retryAllowed = { false },
                elapsedMs = { 0L },
                attempt = { _, guard -> guardSeen = guard; throw EOFException("first") },
                beforeRetry = { fail("no retry expected") },
            )
            fail("expected the first error")
        } catch (error: EOFException) {
            assertEquals("first", error.message)
        }
        assertEquals("no guard where no retry could follow", 0L, guardSeen)
    }

    @Test
    fun aNonIoFailureIsNeverRetriedAndThePeerKeepsItsPhyOtherwise() {
        try {
            MessageDialPhy.dial<FakeLeLink, String>(
                firstPhy = BlePhyPreference.TWO_M,
                stallGuardMs = 100,
                retryAllowed = noRunLimit,
                elapsedMs = { 0L },
                attempt = { _, _ -> throw IllegalStateException("a stranger is only a pseudonym") },
                beforeRetry = { fail("not retried") },
            )
            fail("expected the exception")
        } catch (error: IllegalStateException) {
            assertEquals("a stranger is only a pseudonym", error.message)
        }
    }

    @Test
    fun anAuthenticatedTwoMExchangeThatDiedEarlyWithoutAnyAnswerIsEarlyLossEverythingElseIsNot() {
        fun early(
            outcome: StoreForwardSyncOutcome = StoreForwardSyncOutcome.INTERRUPTED,
            sent: Int = 0,
            received: Int = 0,
            card: Boolean = false,
            sinceAuthMs: Long = 5_000,
            twoM: Boolean = true,
            sendLinks: Int = 1,
        ) = MessageDialPhy.earlyLossWithoutAnswer(outcome, sent, received, card, sinceAuthMs, twoM, sendLinks)
        assertTrue(early())
        assertTrue(early(outcome = StoreForwardSyncOutcome.TIMEOUT, sinceAuthMs = 14_000))
        assertFalse("complete", early(outcome = StoreForwardSyncOutcome.COMPLETE))
        assertFalse("rate limited is the peer's policy", early(outcome = StoreForwardSyncOutcome.RATE_LIMITED))
        assertFalse("a suspend is live media", early(outcome = StoreForwardSyncOutcome.SUSPENDED_BY_PEER))
        assertFalse("a chunk moved", early(sent = 1))
        assertFalse("a chunk moved", early(received = 1))
        assertFalse("the peer's card arrived, so its hello did", early(card = true))
        assertFalse("too late to blame the PHY", early(sinceAuthMs = AdaptivePhyMemory.EARLY_LOSS_MS + 1))
        assertFalse("not on 2M", early(twoM = false))
        assertFalse("sending on several links", early(sendLinks = 2))
    }

    @Test
    fun theStallGuardOfAnAttemptThatAlreadyFinishedNeverClosesItsLink() {
        val link = FakeLeLink(BlePhyPreference.ONE_M_ADAPTIVE)
        val stalls = AtomicInteger()
        val guardsBefore = guardThreads()
        val (connection, hello) = MessageDialPhy.openAndAuthenticate(
            stallGuardMs = unreachableGuardMs,
            open = { link },
            close = { it.close() },
            authenticate = { it.readHello() },
            running = { true },
            nowMs = { System.nanoTime() / 1_000_000 },
            onStall = { stalls.incrementAndGet() },
        )
        // Let the guard thread run to its end (it wakes the moment the attempt settles): the settled attempt must not be claimed
        // as stalled by it. Waiting for the thread, not for a time, holds on any machine.
        val end = System.nanoTime() + 60_000_000_000L
        while ((guardThreads() - guardsBefore).any { it.isAlive }) {
            check(System.nanoTime() < end) { "the guard thread never ended" }
            Thread.sleep(5)
        }
        assertSame(link, connection)
        assertEquals("hello", hello)
        assertEquals(0, stalls.get())
        assertEquals(0, link.closeCount)
    }

    private fun guardThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.name == "MessageSyncSetupStall" }.toSet()
}
