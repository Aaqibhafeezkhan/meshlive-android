package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LlProcedureGate (BACKLOG P-01): one link-layer procedure in flight, settle gaps, bounded waits that are not stacked on,
 * one 1M retry after 0x23/0x2A, a peripheral that waits for the central, and a pass-through when the gate is off.
 */
class LlProcedureGateTest {
    private class Rig(
        role: LeLinkRole = LeLinkRole.CENTRAL,
        enabled: Boolean = true,
        timing: LlGateTiming = LlGateTiming(),
    ) {
        val time = FakeTime()
        val lines = mutableListOf<String>()
        val starts = mutableListOf<String>()
        val lost = mutableListOf<Triple<String?, List<RecordedChange>, Int>>()
        val gate = LlProcedureGate(
            link = "test",
            role = role,
            nowMs = { time.now },
            schedule = time::schedule,
            log = { lines += it },
            enabled = { enabled },
            timing = timing,
            peerKey = { "peer1" },
            onLinkLost = { peer, changes, _, status -> lost += Triple(peer, changes, status) },
        )

        fun submit(
            kind: LlProcedure,
            label: String,
            accepted: Boolean = true,
            expectedTx: Int? = null,
            fallback: (() -> Boolean)? = null,
            onDone: ((GateOutcome) -> Unit)? = null,
        ): GateAdmission = gate.submit(kind, label, expectedTx, fallback, null, onDone) {
            starts += label
            accepted
        }
    }

    @Test
    fun aGateThatIsOffStartsEverythingAtOnceAndStillRecordsTheChange() {
        val rig = Rig(enabled = false)
        assertEquals(GateAdmission.IMMEDIATE_OK, rig.submit(LlProcedure.PHY, "phy"))
        assertEquals(GateAdmission.IMMEDIATE_OK, rig.submit(LlProcedure.CONNECTION_PARAMS, "conn"))
        assertEquals(GateAdmission.IMMEDIATE_FAILED, rig.submit(LlProcedure.MTU, "mtu", accepted = false))
        assertEquals(listOf("phy", "conn", "mtu"), rig.starts)
        assertEquals(
            listOf(LinkChangeKind.PHY, LinkChangeKind.PRIORITY, LinkChangeKind.MTU),
            rig.gate.recentChanges().map { it.kind },
        )
        assertTrue("no gate line without a gate", rig.lines.none { "event=queued" in it })
    }

    @Test
    fun oneProcedureAtATimeWithASettleGapAfterEach() {
        val fresh = Rig()
        assertEquals(GateAdmission.QUEUED, fresh.submit(LlProcedure.MTU, "mtu"))
        fresh.submit(LlProcedure.PHY, "phy")
        fresh.submit(LlProcedure.CONNECTION_PARAMS, "conn")
        assertEquals("only the first request is out", listOf("mtu"), fresh.starts)
        fresh.time.advance(100)
        assertEquals("no callback yet, so no second request", listOf("mtu"), fresh.starts)
        fresh.gate.onMtuChanged(0)
        fresh.time.advance(399)
        assertEquals("the data-length update after the MTU gets 400 ms", listOf("mtu"), fresh.starts)
        fresh.time.advance(1)
        assertEquals(listOf("mtu", "phy"), fresh.starts)
        fresh.gate.onPhyUpdate(LePhy.TWO_M, 0)
        fresh.time.advance(1_499)
        assertEquals("a gap of seconds between a PHY update and a priority change", listOf("mtu", "phy"), fresh.starts)
        fresh.time.advance(1)
        assertEquals(listOf("mtu", "phy", "conn"), fresh.starts)
        fresh.gate.onConnectionUpdated(0, 12)
        assertTrue(fresh.gate.isIdle())
    }

    @Test
    fun aCallbackOfAnotherKindDoesNotEndTheProcedureInFlight() {
        val rig = Rig()
        rig.submit(LlProcedure.PHY, "phy")
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn")
        rig.gate.onMtuChanged(0)
        rig.gate.onServicesDiscovered(0)
        rig.time.advance(2_000)
        assertEquals(listOf("phy"), rig.starts)
    }

    @Test
    fun aMissingCallbackIsBoundedAndNoSecondRequestOfThatKindIsStackedOnIt() {
        val rig = Rig()
        val outcomes = mutableListOf<GateOutcome>()
        rig.submit(LlProcedure.PHY, "phy1", onDone = { outcomes += it })
        rig.submit(LlProcedure.PHY, "phy2", onDone = { outcomes += it })
        rig.time.advance(2_499)
        assertEquals(listOf("phy1"), rig.starts)
        rig.time.advance(1)
        assertEquals("the timeout releases the slot and drops the queued twin", listOf(GateOutcome.TIMED_OUT, GateOutcome.DROPPED_STUCK), outcomes)
        assertEquals(listOf("phy1"), rig.starts)
        assertEquals(GateAdmission.DROPPED_STUCK, rig.submit(LlProcedure.PHY, "phy3"))

        // Another procedure is not blocked by the stuck one once the settle gap passed.
        assertEquals(GateAdmission.QUEUED, rig.submit(LlProcedure.MTU, "mtu"))
        rig.time.advance(1_500)
        assertEquals(listOf("phy1", "mtu"), rig.starts)

        // A late callback clears the stuck mark.
        rig.gate.onPhyUpdate(LePhy.TWO_M, 0)
        assertEquals(GateAdmission.QUEUED, rig.submit(LlProcedure.PHY, "phy4"))
        assertTrue(rig.lines.any { "event=late_callback" in it })
    }

    @Test
    fun aConnectionUpdateThatIsNeverReportedIsNotStuck() {
        // Stacks without the hidden callback never report one; the keeper's later request must still go out.
        val rig = Rig()
        val outcomes = mutableListOf<GateOutcome>()
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn1", onDone = { outcomes += it })
        rig.time.advance(1_500)
        assertEquals(listOf(GateOutcome.TIMED_OUT), outcomes)
        assertEquals(GateAdmission.QUEUED, rig.submit(LlProcedure.CONNECTION_PARAMS, "conn2"))
        rig.time.advance(750)
        assertEquals(listOf("conn1", "conn2"), rig.starts)
    }

    @Test
    fun aPhyCollisionIsRetriedOnceOn1MAfterThreeQuartersOfASecond() {
        val rig = Rig()
        val calls = mutableListOf<String>()
        val outcomes = mutableListOf<GateOutcome>()
        rig.gate.submit(LlProcedure.PHY, "phy", LePhy.TWO_M, { calls += "1M"; true }, null, { outcomes += it }) {
            calls += "2M"
            true
        }
        rig.gate.onPhyUpdate(LePhy.ONE_M, 35)
        rig.time.advance(749)
        assertEquals(listOf("2M"), calls)
        rig.time.advance(1)
        assertEquals("the retry asks for 1M, not 2M again", listOf("2M", "1M"), calls)
        rig.gate.onPhyUpdate(LePhy.ONE_M, 42)
        rig.time.advance(10_000)
        assertEquals("one retry only", listOf("2M", "1M"), calls)
        assertEquals(listOf(GateOutcome.FAILED_STATUS), outcomes)
        assertTrue(rig.lines.any { "event=retry" in it && "status=35" in it && "on1M=true" in it })
    }

    @Test
    fun aRetryWithoutAFallbackRepeatsTheSameRequest() {
        val rig = Rig()
        rig.submit(LlProcedure.PHY, "phy")
        rig.gate.onPhyUpdate(LePhy.TWO_M, 42)
        rig.time.advance(750)
        assertEquals(listOf("phy", "phy"), rig.starts)
    }

    @Test
    fun aRequestTheStackRefusesDoesNotHoldTheLinkAndTheNextOneFollows() {
        val rig = Rig()
        val outcomes = mutableListOf<GateOutcome>()
        rig.submit(LlProcedure.PHY, "phy", accepted = false, onDone = { outcomes += it })
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn")
        rig.time.advance(99)
        assertEquals(listOf("phy"), rig.starts)
        rig.time.advance(1)
        assertEquals(listOf("phy", "conn"), rig.starts)
        assertEquals(listOf(GateOutcome.NOT_QUEUED), outcomes)
    }

    @Test
    fun aLaterPriorityRequestReplacesAnEarlierOneThatHasNotStarted() {
        val rig = Rig()
        val outcomes = mutableListOf<GateOutcome>()
        rig.submit(LlProcedure.MTU, "mtu")
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn-high", onDone = { outcomes += it })
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn-dck")
        assertEquals(listOf(GateOutcome.SUPERSEDED), outcomes)
        rig.gate.onMtuChanged(0)
        rig.time.advance(400)
        assertEquals(listOf("mtu", "conn-dck"), rig.starts)
    }

    @Test
    fun aCallbackThatArrivesInsideTheRequestStillMovesTheQueueOn() {
        val rig = Rig()
        val order = mutableListOf<String>()
        rig.gate.submit(LlProcedure.PHY, "phy") {
            order += "phy"
            rig.gate.onPhyUpdate(LePhy.TWO_M, 0)
            true
        }
        rig.gate.submit(LlProcedure.CONNECTION_PARAMS, "conn") { order += "conn"; true }
        rig.time.advance(1_500)
        assertEquals(listOf("phy", "conn"), order)
    }

    @Test
    fun aReadOfTheRequestedPhyEndsTheStepButAReadOfTheOldPhyDoesNot() {
        val rig = Rig()
        rig.submit(LlProcedure.PHY, "phy", expectedTx = LePhy.ONE_M)
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn")
        rig.gate.onPhyRead(LePhy.TWO_M, 0)
        rig.time.advance(2_000)
        assertEquals("a read taken before the update shows the old PHY", listOf("phy"), rig.starts)
        rig.gate.onPhyRead(LePhy.ONE_M, 0)
        rig.time.advance(1_500)
        assertEquals(listOf("phy", "conn"), rig.starts)
    }

    @Test
    fun aPeripheralWaitsForTheCentralsFirstUpdateAndDiscoveryBeforeAskingForAPhy() {
        val rig = Rig(role = LeLinkRole.PERIPHERAL)
        rig.submit(LlProcedure.PHY, "phy")
        rig.time.advance(50)
        rig.gate.onPeerServicesDiscovered()
        rig.time.advance(50)
        rig.gate.onConnectionUpdated(0, 24) // 30 ms interval: three intervals = 90 ms, floor 100 ms
        rig.time.advance(99)
        assertEquals(emptyList<String>(), rig.starts)
        rig.time.advance(1)
        assertEquals(listOf("phy"), rig.starts)
        assertTrue(rig.lines.any { "event=peripheral_wait" in it })
    }

    @Test
    fun aPeripheralWhoseCentralNeverUpdatesAsksAtTheCeiling() {
        val rig = Rig(role = LeLinkRole.PERIPHERAL)
        rig.submit(LlProcedure.PHY, "phy")
        rig.time.advance(3_999)
        assertEquals(emptyList<String>(), rig.starts)
        rig.time.advance(1)
        assertEquals(listOf("phy"), rig.starts)
    }

    @Test
    fun theHoldoffGrowsWithAnUpdateToALongInterval() {
        val rig = Rig(role = LeLinkRole.PERIPHERAL)
        rig.submit(LlProcedure.PHY, "phy")
        rig.gate.onPeerServicesDiscovered()
        rig.gate.onConnectionUpdated(0, 80) // 100 ms interval: three intervals = 300 ms
        rig.time.advance(299)
        assertEquals(emptyList<String>(), rig.starts)
        rig.time.advance(1)
        assertEquals(listOf("phy"), rig.starts)
    }

    @Test
    fun aCentralNeverWaitsForThePeripheralHoldoff() {
        val rig = Rig(role = LeLinkRole.CENTRAL)
        rig.submit(LlProcedure.PHY, "phy")
        assertEquals(listOf("phy"), rig.starts)
    }

    @Test
    fun aLinkLostAfterAPhyUpdateIsClassifiedAndHandedToTheRollbackMemory() {
        val rig = Rig()
        rig.submit(LlProcedure.MTU, "mtu")
        rig.gate.onMtuChanged(0)
        rig.submit(LlProcedure.PHY, "phy")
        rig.time.advance(400)
        val dropped = mutableListOf<GateOutcome>()
        rig.submit(LlProcedure.CONNECTION_PARAMS, "conn", onDone = { dropped += it })
        rig.time.advance(1_200)
        rig.gate.onLinkClosed(42)
        assertEquals(listOf(GateOutcome.CLOSED), dropped)
        val (peer, changes, status) = rig.lost.single()
        assertEquals("peer1", peer)
        assertEquals(42, status)
        assertEquals(listOf(LinkChangeKind.MTU, LinkChangeKind.PHY), changes.map { it.kind })
        assertTrue(rig.lines.any { "event=link_lost" in it && "class=procedure_race_after_change" in it })
        assertEquals(GateAdmission.DROPPED_CLOSED, rig.submit(LlProcedure.PHY, "late"))
        rig.gate.onLinkClosed(8) // idempotent
        assertEquals(1, rig.lost.size)
    }

    @Test
    fun theGateLogsNothingSecretAndEveryLineIsABenchmarkLine() {
        val rig = Rig()
        rig.submit(LlProcedure.PHY, "phy")
        rig.gate.onPhyUpdate(LePhy.TWO_M, 0)
        assertTrue(rig.lines.isNotEmpty())
        assertTrue(rig.lines.all { it.startsWith("BENCHMARK ll_gate: link=test | role=central | event=") })
        assertFalse(rig.lines.any { "peer1" in it })
    }
}
