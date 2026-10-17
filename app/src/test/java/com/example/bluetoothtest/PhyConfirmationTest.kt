package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch P: PHY confirmation rules, the Android 13 readPhy backstop, and the mask rules for connectGatt. */
class PhyConfirmationTest {
    @Test
    fun theMaskAndValueConstantsMatchTheFramework() {
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, LePhy.MASK_1M)
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, LePhy.MASK_2M)
        assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, LePhy.MASK_CODED)
        assertEquals(BluetoothDevice.PHY_LE_1M, LePhy.ONE_M)
        assertEquals(BluetoothDevice.PHY_LE_2M, LePhy.TWO_M)
        assertEquals(BluetoothDevice.PHY_LE_CODED, LePhy.CODED)
    }

    @Test
    fun theConnectMaskAlwaysCarries1M() {
        assertEquals(LePhy.MASK_1M, LePhy.connectGattMask(0))
        assertEquals(LePhy.MASK_1M or LePhy.MASK_2M, LePhy.connectGattMask(LePhy.MASK_2M))
        assertEquals(LePhy.MASK_1M or LePhy.MASK_CODED, LePhy.connectGattMask(LePhy.MASK_CODED))
        assertEquals(LePhy.MASK_1M, LePhy.connectGattMask(LePhy.MASK_1M))
        assertEquals("unknown bits are dropped", LePhy.MASK_1M, LePhy.connectGattMask(0x40))
    }

    @Test
    fun aMaskNamesOnePhyOnlyWhenItHasOneBit() {
        assertEquals(LePhy.TWO_M, LePhy.valueForMask(BluetoothDevice.PHY_LE_2M_MASK))
        assertEquals(LePhy.CODED, LePhy.valueForMask(BluetoothDevice.PHY_LE_CODED_MASK))
        assertNull(LePhy.valueForMask(LePhy.MASK_1M or LePhy.MASK_2M))
        assertNull(LePhy.valueForMask(0))
    }

    @Test
    fun codedIsConfirmedOnlyByAReportedCodedPhyWithStatusZero() {
        assertTrue(codedPhyConfirmed(LePhy.CODED, 0))
        assertFalse("a request is not a report", codedPhyConfirmed(null, null))
        assertFalse(codedPhyConfirmed(LePhy.TWO_M, 0))
        assertFalse(codedPhyConfirmed(LePhy.CODED, 42))
    }

    @Test
    fun aCodedPriorIsTrustedOnlyAfterTheStackReportedCoded() {
        assertNull(trustedExperimentalPhy(null, LePhy.CODED))
        assertEquals(ExperimentalBlePhy.LE_1M, trustedExperimentalPhy(ExperimentalBlePhy.LE_1M, null))
        assertNull("requested Coded, nothing reported", trustedExperimentalPhy(ExperimentalBlePhy.CODED_S2, null))
        assertNull("requested Coded, the link reports 1M", trustedExperimentalPhy(ExperimentalBlePhy.CODED_S8, LePhy.ONE_M))
        assertEquals(ExperimentalBlePhy.CODED_S2, trustedExperimentalPhy(ExperimentalBlePhy.CODED_S2, LePhy.CODED))
    }

    @Test
    fun theLabelsOfTheRequestedPhyNameTheValueExpected() {
        assertEquals(LePhy.TWO_M, phyValueForLabel("2M"))
        assertEquals(LePhy.ONE_M, phyValueForLabel("1M (adaptive)"))
        assertEquals(LePhy.ONE_M, phyValueForLabel("1M (experimental)"))
        assertEquals(LePhy.CODED, phyValueForLabel("Coded S8 (experimental)"))
        assertNull(phyValueForLabel("fast"))
    }

    @Test
    fun androidThirteenReadsEarlyAndOftenAndTheOthersOnceAtNordicsOneSecond() {
        assertEquals(listOf(200L, 600L, 1_000L, 1_400L, 1_800L), PhyWaitPlan.ANDROID_13.pollOffsets())
        assertEquals(listOf(1_000L), PhyWaitPlan.NORDIC.pollOffsets())
        assertEquals(PhyWaitPlan.ANDROID_13, PhyWaitPlan.forDevice(33, emptySet()))
        assertEquals(PhyWaitPlan.NORDIC, PhyWaitPlan.forDevice(32, emptySet()))
        assertEquals(PhyWaitPlan.NORDIC, PhyWaitPlan.forDevice(34, emptySet()))
        assertEquals("the entry's off switch", PhyWaitPlan.NORDIC, PhyWaitPlan.forDevice(33, setOf(PhyLinkPolicy.ANDROID13_BACKSTOP_ID)))
        assertEquals(PhyWaitPlan.NORDIC, PhyWaitPlan.forDevice(33, setOf("all")))
    }

    private class FollowerRig(plan: PhyWaitPlan = PhyWaitPlan.ANDROID_13, expected: Int = LePhy.TWO_M) {
        val time = FakeTime()
        var reads = 0
        val lines = mutableListOf<String>()
        var end: Pair<PhyRequestEnd, Int?>? = null
        val follower = PhyRequestFollower(
            link = "test",
            expectedTx = expected,
            plan = plan,
            readPhy = { reads++; true },
            scheduleAt = time::schedule,
            log = { lines += it },
            nowMs = { time.now },
            onEnd = { e, tx -> end = e to tx },
        )
    }

    @Test
    fun aMissingUpdateIsFoundByAPollThatShowsTheRequestedPhy() {
        val rig = FollowerRig()
        rig.follower.start()
        rig.time.advance(200)
        assertEquals(1, rig.reads)
        rig.follower.onRead(LePhy.ONE_M, 0) // the controller has not switched yet
        assertNull(rig.end)
        rig.time.advance(400)
        assertEquals(2, rig.reads)
        rig.follower.onRead(LePhy.TWO_M, 0)
        assertEquals(PhyRequestEnd.READ to LePhy.TWO_M, rig.end)
        rig.time.advance(5_000)
        assertEquals("no poll after the answer", 2, rig.reads)
        assertTrue(rig.lines.single().contains("end=read"))
    }

    @Test
    fun aPhyThatNeverShowsEndsUnconfirmedAfterTheLastPoll() {
        val rig = FollowerRig()
        rig.follower.start()
        rig.time.advance(2_499)
        assertNull(rig.end)
        rig.time.advance(1)
        assertEquals(PhyRequestEnd.UNCONFIRMED, rig.end?.first)
        assertEquals(5, rig.reads)
        assertTrue(rig.lines.single().contains("polls=5"))
    }

    @Test
    fun anUpdateEndsTheRequestWithoutAnyRead() {
        val rig = FollowerRig()
        rig.follower.start()
        rig.time.advance(100)
        rig.follower.onUpdate(LePhy.TWO_M, 0)
        rig.time.advance(5_000)
        assertEquals(0, rig.reads)
        assertEquals(PhyRequestEnd.UPDATE, rig.end?.first)
    }

    @Test
    fun anUpdateForAnotherPhyOrWithAnErrorIsNotAConfirmation() {
        val other = FollowerRig()
        other.follower.start()
        other.follower.onUpdate(LePhy.ONE_M, 0)
        assertEquals(PhyRequestEnd.UPDATE_OTHER, other.end?.first)

        val failed = FollowerRig()
        failed.follower.start()
        failed.follower.onUpdate(LePhy.TWO_M, 42)
        assertEquals(PhyRequestEnd.UPDATE_OTHER, failed.end?.first)
    }

    @Test
    fun nordicsPlanIsOneReadAtOneSecond() {
        val rig = FollowerRig(plan = PhyWaitPlan.NORDIC)
        rig.follower.start()
        rig.time.advance(999)
        assertEquals(0, rig.reads)
        rig.time.advance(1)
        assertEquals(1, rig.reads)
        rig.time.advance(500)
        assertEquals(PhyRequestEnd.UNCONFIRMED, rig.end?.first)
    }

    @Test
    fun aReadWithAnErrorStatusIsIgnored() {
        val rig = FollowerRig()
        rig.follower.start()
        rig.follower.onRead(LePhy.TWO_M, 133)
        assertNull(rig.end)
    }

    // ---- PhyMoveRequester ----

    private class MoverRig(plan: PhyWaitPlan?, gateOn: Boolean? = null, throwing: Boolean = false) {
        val time = FakeTime()
        val calls = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val gate = gateOn?.let {
            LlProcedureGate("move", LeLinkRole.CENTRAL, { time.now }, time::schedule, { lines += it }, enabled = { it }, onLinkLost = { _, _, _, _ -> })
        }
        val mover = PhyMoveRequester(
            link = "move",
            requestPhy = { calls += "set+read"; if (throwing) error("binder died") },
            readPhy = { calls += "poll"; true },
            targetTx = LePhy.ONE_M,
            gate = gate,
            plan = plan,
            scheduleAt = time::schedule,
            nowMs = { time.now },
            log = { lines += it },
        )
    }

    @Test
    fun withoutGateOrPlanTheMoveIsOneImmediateRequestAsBefore() {
        val rig = MoverRig(plan = null)
        assertTrue(rig.mover.move())
        rig.time.advance(10_000)
        assertEquals(listOf("set+read"), rig.calls)
    }

    @Test
    fun aRequestThatThrowsIsNotQueued() {
        assertFalse(MoverRig(plan = null, throwing = true).mover.move())
    }

    @Test
    fun withTheAndroid13PlanTheMoveIsFollowedByPollsUntilTheReadShowsThePhy() {
        val rig = MoverRig(plan = PhyWaitPlan.ANDROID_13)
        assertTrue(rig.mover.move())
        rig.time.advance(200)
        assertEquals(listOf("set+read", "poll"), rig.calls)
        rig.mover.onRead(LePhy.ONE_M, 0)
        rig.time.advance(5_000)
        assertEquals(listOf("set+read", "poll"), rig.calls)
        assertTrue(rig.lines.any { "end=read" in it })
    }

    @Test
    fun aMoveInsideAnEnabledGateWaitsForThePhyStepAndEndsOnTheReadBackstop() {
        val rig = MoverRig(plan = PhyWaitPlan.ANDROID_13, gateOn = true)
        assertTrue(rig.mover.move())
        assertEquals(listOf("set+read"), rig.calls)
        rig.mover.onRead(LePhy.TWO_M, 0) // the old PHY, a read taken too early
        assertFalse(rig.gate!!.isIdle())
        rig.mover.onRead(LePhy.ONE_M, 0)
        assertTrue("the gate step ended on the matching read", rig.gate.isIdle())
    }

    @Test
    fun aGateThatIsOffSendsTheMoveStraightOut() {
        val rig = MoverRig(plan = null, gateOn = false)
        assertTrue(rig.mover.move())
        assertEquals(listOf("set+read"), rig.calls)
    }
}
