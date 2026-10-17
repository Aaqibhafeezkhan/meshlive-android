package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch P additions to LeConnectionPriorityKeeper: the parameters of each request in the log (CONN-01), interval drift
 * detection with an Experimental reassert (Nordic #537/#558), and the gate path (BACKLOG P-01). The retry behaviour itself
 * stays in LeConnectionPriorityKeeperTest.
 */
class LePriorityKeeperBatchPTest {
    private class Harness(
        sdk: Int = 34,
        reassert: Boolean = false,
        suppressed: Boolean = false,
        gateOn: Boolean? = null,
        logRequests: Boolean = true,
    ) {
        val time = FakeTime()
        val requests = mutableListOf<BleConnectionPriority>()
        val lines = mutableListOf<String>()
        var active = true
        val gate = gateOn?.let {
            LlProcedureGate("test", LeLinkRole.CENTRAL, { time.now }, time::schedule, { }, enabled = { it }, onLinkLost = { _, _, _, _ -> })
        }
        val keeper = LeConnectionPriorityKeeper(
            link = "test",
            sendRequest = { requests += it; true },
            schedule = time::schedule,
            active = { active },
            log = { lines += it },
            enabled = { true },
            gate = gate,
            reassertEnabled = { reassert },
            reassertSuppressed = { suppressed },
            sdkInt = sdk,
            requestLogEnabled = { logRequests },
        )

        fun drift() = lines.filter { "le_priority_drift" in it }
    }

    @Test
    fun aRequestLogsTheIntervalLatencyAndSupervisionTimeoutItSets() {
        val h = Harness(sdk = 34)
        h.keeper.request(BleConnectionPriority.HIGH)
        assertEquals(
            "BENCHMARK le_priority_request: link=test | priority=high | requestedIntervalMs=11.25-15.00 | latency=0 | " +
                "supervisionMs=5000 | sdk=34",
            h.lines.single(),
        )
    }

    @Test
    fun androidNineAndEarlierSetATwentySecondTimeoutAndAndroidTenAndLaterFive() {
        assertEquals(20_000, lePriorityParameters(BleConnectionPriority.HIGH, 28).supervisionTimeoutMs)
        assertEquals(5_000, lePriorityParameters(BleConnectionPriority.HIGH, 29).supervisionTimeoutMs)
        assertEquals(5_000, lePriorityParameters(BleConnectionPriority.BALANCED, 36).supervisionTimeoutMs)
    }

    @Test
    fun theParametersTableMatchesAospConfig() {
        val high = lePriorityParameters(BleConnectionPriority.HIGH, 33)
        assertEquals(9 to 12, high.minIntervalUnits to high.maxIntervalUnits)
        val balanced = lePriorityParameters(BleConnectionPriority.BALANCED, 33)
        assertEquals(24 to 40, balanced.minIntervalUnits to balanced.maxIntervalUnits)
        val low = lePriorityParameters(BleConnectionPriority.LOW, 33)
        assertEquals(80 to 100, low.minIntervalUnits to low.maxIntervalUnits)
        assertEquals(2, low.latency)
        val dck = lePriorityParameters(BleConnectionPriority.DCK, 34)
        assertEquals(24 to 24, dck.minIntervalUnits to dck.maxIntervalUnits)
        val dckOld = lePriorityParameters(BleConnectionPriority.DCK, 33)
        assertEquals("below API 34 DCK is sent as HIGH", 9 to 12, dckOld.minIntervalUnits to dckOld.maxIntervalUnits)
    }

    @Test
    fun aRetryDoesNotLogTheParametersAgain() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0x23)
        h.time.advance(500)
        assertEquals(1, h.lines.count { "le_priority_request" in it })
        assertEquals(2, h.requests.size)
    }

    @Test
    fun theRequestLineCanBeSwitchedOff() {
        val h = Harness(logRequests = false)
        h.keeper.request(BleConnectionPriority.HIGH)
        assertTrue(h.lines.isEmpty())
    }

    @Test
    fun aDriftIsLoggedButNotActedOnByDefault() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 36) // 45 ms
        assertEquals(1, h.requests.size)
        assertEquals(0, h.time.pending)
        assertTrue(h.drift().single().contains("requestedMaxMs=15.00 | reportedMs=45.00 | action=none (experiment off)"))
    }

    @Test
    fun anIntervalInsideTheRequestedRangeOrBelowItIsNoDrift() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 12)
        h.keeper.onConnectionUpdated(0, intervalUnits = 6) // 7.5 ms during discovery
        h.keeper.onConnectionUpdated(0) // no interval known
        assertTrue(h.drift().isEmpty())
    }

    @Test
    fun withTheSwitchOnADriftRequestsTheSamePriorityAgainAtMostTwice() {
        val h = Harness(reassert = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 36)
        assertEquals(1, h.requests.size)
        h.time.advance(999)
        assertEquals(1, h.requests.size)
        h.time.advance(1)
        assertEquals(listOf(BleConnectionPriority.HIGH, BleConnectionPriority.HIGH), h.requests)

        h.keeper.onConnectionUpdated(0, intervalUnits = 30) // still above 15 ms, a different value: not a floor
        h.time.advance(3_000)
        assertEquals(3, h.requests.size)

        h.keeper.onConnectionUpdated(0, intervalUnits = 24)
        h.time.advance(10_000)
        assertEquals("two reasserts per request, then give up", 3, h.requests.size)
        assertTrue(h.drift().any { "action=gave_up" in it })
    }

    @Test
    fun anIntervalThatStaysTheSameAfterAReassertIsAFloorNotARevert() {
        val h = Harness(reassert = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 16) // 20 ms: the hearing-aid floor
        h.time.advance(1_000)
        assertEquals(2, h.requests.size)
        h.keeper.onConnectionUpdated(0, intervalUnits = 16)
        h.time.advance(10_000)
        assertEquals(2, h.requests.size)
        assertTrue(h.drift().any { "action=none (floor: unchanged after reassert)" in it })
    }

    @Test
    fun aRelayReanchorChangingThePriorityOnPurposeIsNeverFoughtWith() {
        val h = Harness(reassert = true, suppressed = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 36)
        assertEquals(0, h.time.pending)
        assertTrue(h.drift().single().contains("none (relay re-anchor in progress)"))
    }

    @Test
    fun aNewRequestCancelsAPendingReassertAndResetsTheCounters() {
        val h = Harness(reassert = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 36)
        h.keeper.request(BleConnectionPriority.BALANCED)
        h.time.advance(5_000)
        assertEquals(listOf(BleConnectionPriority.HIGH, BleConnectionPriority.BALANCED), h.requests)
    }

    @Test
    fun aClosedLinkIsNotReasserted() {
        val h = Harness(reassert = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0, intervalUnits = 36)
        h.active = false
        h.time.advance(5_000)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun dckAboveItsOwnIntervalIsDriftOnApi34AndHighOnOlderStacks() {
        val h34 = Harness(sdk = 34, reassert = false)
        h34.keeper.request(BleConnectionPriority.DCK)
        h34.keeper.onConnectionUpdated(0, intervalUnits = 24)
        assertTrue(h34.drift().isEmpty())
        h34.keeper.onConnectionUpdated(0, intervalUnits = 36)
        assertEquals(1, h34.drift().size)

        val h33 = Harness(sdk = 33)
        h33.keeper.request(BleConnectionPriority.DCK)
        h33.keeper.onConnectionUpdated(0, intervalUnits = 24) // 30 ms is above HIGH's 15 ms
        assertEquals(1, h33.drift().size)
    }

    @Test
    fun driftLinesAreBoundedPerRequest() {
        val h = Harness()
        h.keeper.request(BleConnectionPriority.HIGH)
        repeat(10) { h.keeper.onConnectionUpdated(0, intervalUnits = 36) }
        assertEquals(LeConnectionPriorityKeeper.MAX_DRIFT_LOGS, h.drift().size)
    }

    // ---- gate path ----

    @Test
    fun withAnEnabledGateARequestWaitsForTheProcedureInFlight() {
        val h = Harness(gateOn = true)
        var mtuStarted = false
        h.gate!!.submit(LlProcedure.MTU, "mtu") { mtuStarted = true; true }
        assertTrue(mtuStarted)
        assertTrue("the keeper reports the request as queued", h.keeper.request(BleConnectionPriority.HIGH))
        assertTrue("not sent while the MTU exchange is in flight", h.requests.isEmpty())
        h.gate.onMtuChanged(0)
        h.time.advance(400)
        assertEquals(listOf(BleConnectionPriority.HIGH), h.requests)
    }

    @Test
    fun theKeepersUpdateReportCompletesTheGateStepSoTheNextProcedureFollows() {
        val h = Harness(gateOn = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        var phyStarted = false
        h.gate!!.submit(LlProcedure.PHY, "phy") { phyStarted = true; true }
        assertFalse(phyStarted)
        h.keeper.onConnectionUpdated(0, intervalUnits = 12)
        h.time.advance(750)
        assertTrue(phyStarted)
    }

    @Test
    fun aRetryAfterAFailedUpdateGoesThroughTheGateToo() {
        val h = Harness(gateOn = true)
        h.keeper.request(BleConnectionPriority.HIGH)
        h.keeper.onConnectionUpdated(0x23, intervalUnits = 0)
        h.time.advance(500)
        assertEquals("the retry waits for the gate's settle gap after the failed update", 1, h.requests.size)
        h.time.advance(250)
        assertEquals(listOf(BleConnectionPriority.HIGH, BleConnectionPriority.HIGH), h.requests)
    }

    @Test
    fun aGateThatIsOffChangesNothing() {
        val h = Harness(gateOn = false)
        assertTrue(h.keeper.request(BleConnectionPriority.HIGH))
        assertEquals(listOf(BleConnectionPriority.HIGH), h.requests)
    }
}
