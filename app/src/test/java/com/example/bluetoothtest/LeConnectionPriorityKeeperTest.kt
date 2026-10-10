package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LeConnectionPriorityKeeper: the ASUS and S24 stacks reported failed LE connection updates
 * (0x23 LL Procedure Collision, 0x3B Unacceptable Connection Parameters) after the app asked for a
 * priority, leaving links at their 45-49 ms creation interval. The keeper repeats the same request
 * after a reported failure, a bounded number of times, and never without a report.
 */
class LeConnectionPriorityKeeperTest {
    private class Harness(enabled: Boolean = true) {
        val requests = mutableListOf<BleConnectionPriority>()
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val lines = mutableListOf<String>()
        var active = true
        val keeper = LeConnectionPriorityKeeper(
            link = "test",
            sendRequest = { requests += it; true },
            schedule = { delay, action -> pending += delay to action },
            active = { active },
            log = { lines += it },
            enabled = { enabled },
        )

        fun runPending() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.second() }
        }
    }

    @Test
    fun successNeverRetries() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0)
        assertEquals(listOf(BleConnectionPriority.HIGH), h.requests)
        assertTrue(h.pending.isEmpty())
    }

    @Test
    fun collisionOrRejectionRepeatsTheSameRequestWithBackoff() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0x23)
        assertEquals(listOf(500L), h.pending.map { it.first })
        h.runPending()
        h.keeper.onConnectionUpdated(0x3B)
        assertEquals(listOf(1_500L), h.pending.map { it.first })
        h.runPending()
        h.keeper.onConnectionUpdated(0x3B)
        assertEquals(listOf(4_000L), h.pending.map { it.first })
        h.runPending()
        h.keeper.onConnectionUpdated(0x3B)
        assertTrue("three retries, then give up", h.pending.isEmpty())
        assertEquals(List(4) { BleConnectionPriority.HIGH }, h.requests)
        assertTrue(h.lines.last().contains("action=gave_up"))
    }

    @Test
    fun noRequestNoRetryAndAnUpdateWithoutAnyRequestIsIgnored() {
        val h = Harness()
        h.keeper.onConnectionUpdated(0x3B)
        assertTrue(h.pending.isEmpty())
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun aNewRequestReplacesAPendingRetryTarget() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.DCK)
        h.keeper.onConnectionUpdated(0x23)
        // The authenticated-link retry asks for HIGH before the scheduled DCK retry runs.
        h.keeper.request(BleConnectionPriority.HIGH)
        h.runPending()
        assertEquals(listOf(BleConnectionPriority.DCK, BleConnectionPriority.HIGH), h.requests)
    }

    @Test
    fun closedLinksAreNotRetried() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0x23)
        h.active = false
        h.runPending()
        assertEquals(listOf(BleConnectionPriority.HIGH), h.requests)
    }

    @Test
    fun controlArmOnlyLogs() {
        val h = Harness(enabled = false)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0x3B)
        assertTrue(h.pending.isEmpty())
        assertTrue(h.lines.single().contains("control arm"))
        assertTrue(DebugTunables.lePriorityRetry)
    }
}
