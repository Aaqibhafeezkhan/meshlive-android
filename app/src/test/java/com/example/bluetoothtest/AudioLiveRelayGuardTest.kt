package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-AL3 chain ASUS -> A15s (relay) -> M31, build 99f28d1f: the child's link cost the relay its inbound (19 -> 8 kbps,
 * for as long as the child stayed), the parent shed and withdrew the relay, 37 s of audio were lost. The relay now
 * protects its own parent; these tests replay that timeline.
 */
class AudioLiveRelayGuardTest {
    @Before
    fun reset() = AudioLiveRelayMemory.clearForTest()

    // --- The guard on the logged inbound rates ------------------------------------------------------------------------

    /** Feeds [kbps] of payload every 200 ms and evaluates the verdict after every packet, as the receiver does. */
    private class Feed(val guard: AudioLiveRelayInboundGuard, var nowMs: Long = 1_000_000L) {
        var dropAtMs: Long? = null

        fun run(seconds: Double, kbps: Double) {
            val ticks = (seconds * 5).toInt()
            repeat(ticks) {
                nowMs += 200
                guard.onBytes((kbps * 1000 / 8 / 5).toInt(), nowMs)
                if (dropAtMs == null && guard.verdict(nowMs) == AudioLiveRelayInboundGuard.Verdict.DROP_CHILD) dropAtMs = nowMs
            }
        }
    }

    @Test
    fun theLoggedStallIsDroppedWithinAFewSecondsOfTheJoinAndBeforeTheParentShedsTwice() {
        val feed = Feed(AudioLiveRelayInboundGuard(24.0))
        feed.run(20.0, 19.0) // before the child: 19 kbps
        val joinedAt = feed.nowMs
        feed.guard.onChildCount(1, joinedAt)
        feed.run(0.5, 19.0)
        feed.run(20.0, 8.0) // the A15s log: 13, then 8, 8, 8, 7, 7 kbps while the child stays
        val dropAt = requireNotNull(feed.dropAtMs)
        val sinceJoin = dropAt - joinedAt
        assertTrue("dropped $sinceJoin ms after the join", sinceJoin in 3_500..6_500)
        assertEquals(19.0, feed.guard.baselineKbps, 0.5)
    }

    @Test
    fun aSteadyChildANoisyLinkAndTheChildsSetupDipNeverDropIt() {
        val steady = Feed(AudioLiveRelayInboundGuard(24.0))
        steady.run(20.0, 19.0)
        steady.guard.onChildCount(1, steady.nowMs)
        steady.run(120.0, 19.0)
        assertNull(steady.dropAtMs)
        assertTrue(steady.guard.childCarriedHealthily(steady.nowMs))

        // The child's setup (connect, MTU, discovery, PHY) costs about a second: 13 kbps for 1.5 s, then normal.
        val setup = Feed(AudioLiveRelayInboundGuard(24.0))
        setup.run(20.0, 19.0)
        setup.guard.onChildCount(1, setup.nowMs)
        setup.run(1.5, 13.0)
        setup.run(60.0, 19.0)
        assertNull(setup.dropAtMs)

        // A mild loss of rate (80% of the baseline) is not the failure: only a collapse below 65% is.
        val mild = Feed(AudioLiveRelayInboundGuard(24.0))
        mild.run(20.0, 19.0)
        mild.guard.onChildCount(1, mild.nowMs)
        mild.run(60.0, 15.5)
        assertNull(mild.dropAtMs)
    }

    @Test
    fun pausesAndTheirResumeAreNotJudgedAndNoChildMeansNoDrop() {
        val noChild = Feed(AudioLiveRelayInboundGuard(24.0))
        noChild.run(30.0, 2.0)
        assertNull(noChild.dropAtMs)

        val paused = Feed(AudioLiveRelayInboundGuard(24.0))
        paused.run(20.0, 19.0)
        paused.guard.onChildCount(1, paused.nowMs)
        paused.run(10.0, 19.0)
        paused.guard.onPausedChanged(true, paused.nowMs)
        paused.run(30.0, 0.0) // the broadcaster paused: no records at all
        assertNull(paused.dropAtMs)
        paused.guard.onPausedChanged(false, paused.nowMs)
        paused.run(10.0, 19.0)
        assertNull(paused.dropAtMs)
    }

    @Test
    fun aFreshRelayWithNoHistoryUsesThePresetRateAsTheYardstick() {
        val feed = Feed(AudioLiveRelayInboundGuard(24.0))
        feed.guard.onChildCount(1, feed.nowMs) // the child is there from the first second
        feed.run(10.0, 8.0) // 8 kbps against 0.75 x 24 = 18 kbps
        assertNotNull(feed.dropAtMs)
        val healthy = Feed(AudioLiveRelayInboundGuard(24.0))
        healthy.guard.onChildCount(1, healthy.nowMs)
        healthy.run(30.0, 19.0)
        assertNull(healthy.dropAtMs)
    }

    // --- Memory ----------------------------------------------------------------------------------------------------------

    @Test
    fun aRelayThatDroppedAChildRefusesToRelayOnThatTransportWithAGrowingCooldown() {
        val key = "BLE_GATT"
        assertEquals(0L, AudioLiveRelayMemory.blockedForMs(key, 0))
        val first = AudioLiveRelayMemory.onDropped(key, 1_000)
        assertEquals(AudioLiveRelayMemory.BASE_MS, first)
        assertEquals(AudioLiveRelayMemory.BASE_MS - 500, AudioLiveRelayMemory.blockedForMs(key, 1_500))
        assertEquals(0L, AudioLiveRelayMemory.blockedForMs(key, 1_000 + first))
        // Another transport is not affected (the problem is a radio-role pair, found per transport).
        assertEquals(0L, AudioLiveRelayMemory.blockedForMs("BLE_L2CAP", 1_500))
        assertEquals(2 * AudioLiveRelayMemory.BASE_MS, AudioLiveRelayMemory.onDropped(key, 500_000))
        assertEquals(4 * AudioLiveRelayMemory.BASE_MS, AudioLiveRelayMemory.onDropped(key, 900_000))
        repeat(10) { AudioLiveRelayMemory.onDropped(key, 2_000_000) }
        assertEquals(AudioLiveRelayMemory.MAX_MS, AudioLiveRelayMemory.onDropped(key, 3_000_000))
        // A child carried healthily forgives the history.
        AudioLiveRelayMemory.onHealthy(key)
        assertEquals(AudioLiveRelayMemory.BASE_MS, AudioLiveRelayMemory.onDropped(key, 4_000_000))
    }

    @Test
    fun theAdmissionLineIsRateLimitedAndStillShowsAChange() {
        val limiter = AudioLiveLogLimiter(minIntervalMs = 30_000)
        assertTrue(limiter.shouldLog("children=4", 0))
        // ~10 requests a second after a withdrawal: silent.
        assertTrue((1..299).none { limiter.shouldLog("children=4", it * 100L) })
        assertTrue(limiter.shouldLog("children=3", 30_100)) // a change always shows
        assertFalse(limiter.shouldLog("children=3", 40_000))
        assertTrue(limiter.shouldLog("children=3", 60_200)) // and the same state shows again after 30 s
    }

    // --- The whole timeline: parent queue, relay inbound, guard ------------------------------------------------------------

    private class Entry(val queuedAtNs: Long)

    /**
     * The ASUS's queue for the A15s and the A15s's inbound, tick by tick (200 ms, virtual time). Before the child
     * the relay drains faster than the source produces; from half a second after the join its inbound capacity
     * is 42% of the stream (8 of 19 kbps) until the child is gone.
     */
    private class Timeline(private val relayGuardActive: Boolean) {
        val queue = java.util.concurrent.LinkedBlockingQueue<Entry>()
        val sendQueue = AudioLiveSendQueue(queue, { true }, { it.queuedAtNs }, { 600 }, ageBound = true)
        val guard = AudioLiveRelayInboundGuard(24.0)
        var nowMs = 1_000_000L
        var childAttached = false
        var childDroppedAtMs: Long? = null
        var parentWithdrawnAtMs: Long? = null
        var parentVerdict: AudioLiveChildHealth.Reason? = null
        private var credit = 0.0
        var shed = 0
        val joinAtMs = nowMs + 30_000

        fun run(seconds: Int) {
            repeat(seconds * 5) {
                nowMs += 200
                val nowNs = nowMs * 1_000_000L
                if (!childAttached && childDroppedAtMs == null && nowMs >= joinAtMs) {
                    childAttached = true
                    guard.onChildCount(1, nowMs)
                }
                // The source produces one record (600 payload bytes) per tick; the parent queues, sheds, judges.
                val entry = Entry(nowNs)
                queue.put(entry)
                val outcome = sendQueue.afterEnqueue(entry, nowNs)
                shed += outcome.removed
                if (outcome.verdict != null && parentWithdrawnAtMs == null) {
                    parentWithdrawnAtMs = nowMs
                    parentVerdict = outcome.verdict
                }
                // The relay's inbound this tick: plenty of room normally, 42% of the stream while carrying the child.
                val degraded = childAttached && nowMs >= joinAtMs + 500
                credit += if (degraded) 0.42 else 6.0
                var taken = 0
                while (credit >= 1.0 && queue.poll() != null) {
                    credit -= 1.0
                    taken++
                }
                if (queue.isEmpty()) credit = minOf(credit, 6.0)
                guard.onBytes(taken * 600, nowMs)
                if (relayGuardActive && childAttached && guard.verdict(nowMs) == AudioLiveRelayInboundGuard.Verdict.DROP_CHILD) {
                    childAttached = false
                    childDroppedAtMs = nowMs
                    guard.onChildCount(0, nowMs)
                }
            }
        }
    }

    @Test
    fun withoutTheGuardTheParentWithdrawsTheRelayAsOnThePhones() {
        val timeline = Timeline(relayGuardActive = false)
        timeline.run(80)
        val withdrawnAt = requireNotNull(timeline.parentWithdrawnAtMs) { "the parent never withdrew" }
        assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, timeline.parentVerdict)
        assertTrue("withdrawn ${withdrawnAt - timeline.joinAtMs} ms after the join", withdrawnAt - timeline.joinAtMs in 3_000..20_000)
        assertTrue(timeline.shed > 0)
    }

    @Test
    fun withTheGuardTheChildIsDroppedAndTheParentNeverWithdrawsTheRelay() {
        val timeline = Timeline(relayGuardActive = true)
        timeline.run(80)
        val droppedAt = requireNotNull(timeline.childDroppedAtMs) { "the child was never dropped" }
        assertTrue("dropped ${droppedAt - timeline.joinAtMs} ms after the join", droppedAt - timeline.joinAtMs in 3_500..7_000)
        assertNull("the parent withdrew the relay at ${timeline.parentWithdrawnAtMs}", timeline.parentWithdrawnAtMs)
        // The relay's inbound recovered and the backlog drained.
        assertTrue(timeline.queue.size <= 2)
        assertEquals(0, timeline.shed)
    }
}
