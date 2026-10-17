package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSilenceTest {
    private var now = 10_000L
    private val silence = LinkSilence { now }

    @Test
    fun noVerdictBeforeTheLinkIsConnected() {
        silence.begin(LinkSilence.Stage.MTU)
        now += 60_000L
        assertNull(silence.check())
    }

    @Test
    fun aConnectedLinkWithNoAttAnswerIsRemoteGattFullAfterTwoSecondsAndReportedOnce() {
        silence.onConnected()
        now += 100L
        silence.begin(LinkSilence.Stage.MTU)
        now += 1_900L
        assertNull(silence.check())
        now += 150L
        val verdict = silence.check()!!
        assertEquals(LinkSilence.Kind.REMOTE_GATT_FULL, verdict.kind)
        assertEquals(LinkSilence.Stage.MTU, verdict.stage)
        assertEquals(2_050L, verdict.waitedMs)
        assertEquals(2_150L, verdict.connectedForMs)
        // Once: neither the same check nor the MTU stage limit repeats it.
        assertNull(silence.check())
        now += 5_000L
        assertNull(silence.check())
    }

    @Test
    fun aStagePastItsLimitAfterOtherAttTrafficWorkedIsAStageHang() {
        silence.onConnected()
        silence.begin(LinkSilence.Stage.MTU)
        now += 300L
        silence.complete(LinkSilence.Stage.MTU)
        silence.begin(LinkSilence.Stage.DISCOVERY)
        now += 9_900L
        assertNull(silence.check())
        now += 200L
        val verdict = silence.check()!!
        assertEquals(LinkSilence.Kind.STAGE_HANG, verdict.kind)
        assertEquals(LinkSilence.Stage.DISCOVERY, verdict.stage)
        assertNull(silence.check())
    }

    @Test
    fun enabledNotificationsThatNeverArriveAreNotificationSilence() {
        silence.onConnected()
        silence.begin(LinkSilence.Stage.MTU)
        silence.complete(LinkSilence.Stage.MTU)
        silence.begin(LinkSilence.Stage.FIRST_NOTIFICATION)
        now += 5_001L
        assertEquals(LinkSilence.Kind.NOTIFICATION_SILENT, silence.check()!!.kind)
    }

    @Test
    fun aHealthySetupNeverProducesAVerdictAndResetForgetsEverything() {
        silence.onConnected()
        for (stage in listOf(LinkSilence.Stage.MTU, LinkSilence.Stage.DISCOVERY, LinkSilence.Stage.SUBSCRIBE)) {
            silence.begin(stage)
            now += 400L
            silence.complete(stage)
            assertNull(silence.check())
        }
        now += 60_000L
        assertNull(silence.check())
        silence.reset()
        silence.begin(LinkSilence.Stage.MTU)
        now += 10_000L
        assertNull(silence.check()) // not connected again yet
    }

    @Test
    fun recoveryLadderEscalatesOneStepPerFailureOfTheSamePeer() {
        assertEquals(LinkSilence.RecoveryStep.CLOSE_AND_RECONNECT, LinkSilence.recoveryStep(0))
        assertEquals(LinkSilence.RecoveryStep.RECONNECT_WITHOUT_2M, LinkSilence.recoveryStep(1))
        assertEquals(LinkSilence.RecoveryStep.REPORT, LinkSilence.recoveryStep(2))
        assertEquals(LinkSilence.RecoveryStep.REPORT, LinkSilence.recoveryStep(9))
    }

    @Test
    fun statuses129And133And8NeedAFullTeardown() {
        assertTrue(LinkSilence.needsFullTeardown(129))
        assertTrue(LinkSilence.needsFullTeardown(133))
        assertTrue(LinkSilence.needsFullTeardown(8))
        assertFalse(LinkSilence.needsFullTeardown(19))
        assertFalse(LinkSilence.needsFullTeardown(0))
    }

    @Test
    fun theSettleDelayIsAMeasuredParameterWithGadgetbridgeComparators() {
        assertEquals(300L, LinkSilence.settleDelayMs(sdk = 34, bonded = false, override = 0))
        assertEquals(300L, LinkSilence.settleDelayMs(sdk = 35, bonded = false, override = 0))
        assertEquals(1_000L, LinkSilence.settleDelayMs(sdk = 35, bonded = true, override = 0))
        assertEquals(750L, LinkSilence.settleDelayMs(sdk = 35, bonded = true, override = 750))
    }

    @Test
    fun theSupervisionTimeoutArmedByAPriorityRequestExplainsAStatus8After5Seconds() {
        assertEquals(5_000L, LinkSilence.prioritySupervisionMs(29))
        assertEquals(5_000L, LinkSilence.prioritySupervisionMs(31))
        assertEquals(20_000L, LinkSilence.prioritySupervisionMs(27))
        assertTrue(LinkSilence.status8MatchesPrioritySupervision(8, 5_050L, 31))
        assertTrue(LinkSilence.status8MatchesPrioritySupervision(8, 4_600L, 31))
        assertFalse(LinkSilence.status8MatchesPrioritySupervision(8, 9_000L, 31))
        assertFalse(LinkSilence.status8MatchesPrioritySupervision(133, 5_000L, 31))
        assertTrue(LinkSilence.status8MatchesPrioritySupervision(8, 20_100L, 27))
    }

    @Test
    fun theLogLineCarriesTheFingerprintAndTheSupervisionTimeout() {
        silence.onConnected()
        silence.begin(LinkSilence.Stage.MTU)
        now += 2_100L
        val verdict = silence.check()!!
        val fingerprint = LinkSilence.fingerprint(
            DeviceTraits("qcom", "msmnile", 33, manufacturer = "OnePlus", socManufacturer = "QTI", securityPatch = "2024-01-05"),
        )
        val line = LinkSilence.logLine(verdict, "gatt-video", "EE:FF", 133, fingerprint, sdk = 33)
        assertTrue(line, line.startsWith("BENCHMARK gatt_link_silence: link=gatt-video | peer=...EE:FF | kind=remote_gatt_full"))
        assertTrue(line, "lastStatus=133 | fullTeardown=true | prioritySupervisionMs=5000" in line)
        assertTrue(line, "sdk=33 | patch=2024-01-05 | manufacturer=oneplus | soc=QUALCOMM | skin=OXYGENOS" in line)
    }

    @Test
    fun aFailedConnectIsClassifiedByItsDuration() {
        val deadline = 12_000L
        assertEquals(ConnectDurationClass.FAST_ESTABLISH_FAILURE, ConnectDurationClass.classify(133, 600L, deadline))
        assertEquals(ConnectDurationClass.STACK_30S_TIMEOUT, ConnectDurationClass.classify(133, 30_050L, deadline))
        assertEquals(ConnectDurationClass.STACK_30S_TIMEOUT, ConnectDurationClass.classify(147, 29_900L, deadline))
        assertEquals(ConnectDurationClass.OWN_DEADLINE, ConnectDurationClass.classify(133, 12_100L, deadline))
        // A 5 s failure is just data: no Samsung-specific class exists.
        assertEquals(ConnectDurationClass.OTHER, ConnectDurationClass.classify(133, 5_000L, deadline))
        assertEquals(ConnectDurationClass.OTHER, ConnectDurationClass.classify(null, 600L, deadline))
    }
}
