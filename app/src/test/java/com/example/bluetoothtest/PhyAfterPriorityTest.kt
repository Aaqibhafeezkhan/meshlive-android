package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PhyAfterPriority: a relay's LE CoC central (M31, Android 12, fresh ACL) requested the PHY 0.6 s after reattaching while the
 * stack's service discovery still held the link at 7.5 ms; the requested 15 ms update then landed after the PHY update and
 * the link died by supervision timeout 5 s later (relay-showcase-20261026 trace-q11a2/a3/b1). The setup PHY now waits for
 * the update that brings the link into the requested window.
 */
class PhyAfterPriorityTest {
    /** A virtual clock: scheduled actions run when [advanceTo] passes their due time. */
    private class Harness(enabled: Boolean = true) {
        var now = 0L
        val due = mutableListOf<Pair<Long, () -> Unit>>()
        val lines = mutableListOf<String>()
        val phySent = mutableListOf<Long>()
        var linkOpen = true
        val hold = PhyAfterPriority(
            link = "test",
            schedule = { delayMs, action -> due += (now + delayMs) to action },
            nowMs = { now },
            log = { lines += it },
            enabled = { enabled },
        )
        val keeper = LeConnectionPriorityKeeper(
            link = "test",
            sendRequest = { true },
            schedule = { _, _ -> },
            active = { true },
            log = { },
            enabled = { false },
            sdkInt = 31,
            requestLogEnabled = { false },
            phyHold = hold,
        )

        fun advanceTo(ms: Long) {
            while (true) {
                val next = due.filter { it.first <= ms }.minByOrNull { it.first } ?: break
                due.remove(next)
                now = next.first
                next.second()
            }
            now = ms
        }

        fun requestPhy() = hold.submit("phy", isActive = { linkOpen }) { phySent += now }
    }

    private val sevenAndAHalfMs = 6
    private val fifteenMs = 12
    private val fortyFiveMs = 36

    @Test
    fun failedRunTimingNowSendsThePhyAfterTheRequestedUpdate() {
        // trace-q11a2 M31 child link, ms after the priority request: 7.5 ms at 311, PHY due at 620, 15 ms at 779.
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.advanceTo(311)
        h.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        h.advanceTo(620)
        h.requestPhy()
        assertTrue("held while the link is at 7.5 ms", h.phySent.isEmpty())
        h.advanceTo(779)
        h.keeper.onConnectionUpdated(0, fifteenMs)
        h.advanceTo(2_000)
        assertEquals(listOf(879L), h.phySent)
        assertTrue(h.lines.any { it.contains("event=release") && it.contains("reason=settled") && it.contains("waitedMs=259") })
    }

    @Test
    fun passedRunTimingIsUnchanged() {
        // trace-q11a1: 7.5 ms at 313, 15 ms at 569, PHY at 620 goes out at once.
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.advanceTo(313)
        h.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        h.advanceTo(569)
        h.keeper.onConnectionUpdated(0, fifteenMs)
        h.advanceTo(620)
        h.requestPhy()
        assertEquals(listOf(620L), h.phySent)
        assertTrue(h.lines.isEmpty())
    }

    @Test
    fun noReportYetSendsAtOnce() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.requestPhy()
        assertEquals(listOf(0L), h.phySent)
    }

    @Test
    fun aLinkClosedWhileHeldNeverSendsThePhy() {
        // The PSM-read link before a fresh ACL: setup ends at 7.5 ms and the client is closed within ~10 ms.
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        h.advanceTo(200)
        h.requestPhy()
        h.advanceTo(202)
        h.keeper.onConnectionUpdated(0, fifteenMs)
        h.advanceTo(210)
        h.linkOpen = false
        h.advanceTo(5_000)
        assertTrue(h.phySent.isEmpty())
        assertTrue(h.lines.any { it.contains("reason=inactive") })
    }

    @Test
    fun anUpdateThatNeverLandsCostsOnlyTheBound() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        h.advanceTo(100)
        h.requestPhy()
        h.keeper.onConnectionUpdated(0x3B, -1)
        h.advanceTo(10_000)
        assertEquals(listOf(100L + PhyAfterPriority.BOUND_MS), h.phySent)
        assertTrue(h.lines.any { it.contains("reason=bound") })
    }

    @Test
    fun thePreRequestIntervalHoldsButAFloorAfterTheRequestDoesNot() {
        // 45 ms before the request: the request has not landed yet, so the PHY waits.
        val held = Harness()
        held.keeper.onConnectionUpdated(0, fortyFiveMs)
        held.keeper.request(BleConnectionPriority.HIGH)
        held.requestPhy()
        assertTrue(held.phySent.isEmpty())
        held.advanceTo(400)
        held.keeper.onConnectionUpdated(0, fifteenMs)
        held.advanceTo(1_000)
        assertEquals(listOf(500L), held.phySent)

        // 20 ms reported after the request (a stack floor above the window): waiting cannot change it.
        val floor = Harness()
        floor.keeper.request(BleConnectionPriority.HIGH)
        floor.keeper.onConnectionUpdated(0, 16)
        floor.requestPhy()
        assertEquals(listOf(0L), floor.phySent)
    }

    @Test
    fun theDiscoveryIntervalAfterAnEarlierReportStillHolds() {
        // trace-q11b1: 45 ms reported just after the request, then the 7.5 ms discovery interval, then 15 ms.
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.advanceTo(7)
        h.keeper.onConnectionUpdated(0, fortyFiveMs)
        h.advanceTo(450)
        h.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        h.advanceTo(602)
        h.requestPhy()
        h.advanceTo(829)
        h.keeper.onConnectionUpdated(0, fifteenMs)
        h.advanceTo(3_000)
        assertEquals(listOf(929L), h.phySent)
    }

    @Test
    fun theControlArmSendsAtOnceAndTheRequestRunsOnlyOnce() {
        val off = Harness(enabled = false)
        off.keeper.request(BleConnectionPriority.HIGH)
        off.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        off.requestPhy()
        assertEquals(listOf(0L), off.phySent)

        val on = Harness()
        on.keeper.request(BleConnectionPriority.HIGH)
        on.keeper.onConnectionUpdated(0, sevenAndAHalfMs)
        on.requestPhy()
        on.keeper.onConnectionUpdated(0, fifteenMs)
        on.keeper.onConnectionUpdated(0, fifteenMs)
        on.advanceTo(10_000)
        assertEquals("the settle release and the bound never both send", 1, on.phySent.size)
    }
}
