package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure parts of the DEBUG-only inbound-child probe (DECISIONS "Design RR", measurement 7). */
class InboundProbeTest {
    private fun config(dials: Int = 3, gapMs: Long = 2_000L) =
        InboundProbeConfig(InboundProbeConfig.Role.DIAL, dials, 20_000L, gapMs, 60)

    @Test
    fun onlyServerAndDialAreRoles() {
        assertNull(InboundProbeConfig.parse(null, null, null, null, null))
        assertNull(InboundProbeConfig.parse("", null, null, null, null))
        assertNull(InboundProbeConfig.parse("client", null, null, null, null))
        assertEquals(InboundProbeConfig.Role.SERVER, InboundProbeConfig.parse("server", null, null, null, null)?.role)
        assertEquals(InboundProbeConfig.Role.DIAL, InboundProbeConfig.parse("dial", null, null, null, null)?.role)
    }

    @Test
    fun defaultsAreFifteenDialsTwentySecondsTwoSecondGapSixtyKbps() {
        val parsed = InboundProbeConfig.parse("dial", null, null, null, null)
        assertNotNull(parsed)
        assertEquals(15, parsed!!.dials)
        assertEquals(20_000L, parsed.holdMs)
        assertEquals(2_000L, parsed.gapMs)
        assertEquals(60, parsed.kbps)
    }

    @Test
    fun numbersAreClampedToProbeRanges() {
        val low = InboundProbeConfig.parse("dial", 0, 10, -5, 0)!!
        assertEquals(1, low.dials)
        assertEquals(1_000L, low.holdMs)
        assertEquals(0L, low.gapMs)
        assertEquals(1, low.kbps)
        val high = InboundProbeConfig.parse("dial", 9_999, 9_999_999, 999_999, 9_999)!!
        assertEquals(200, high.dials)
        assertEquals(600_000L, high.holdMs)
        assertEquals(60_000L, high.gapMs)
        assertEquals(400, high.kbps)
    }

    @Test
    fun redialScheduleStartsAtOnceThenWaitsTheGapAndEndsAfterTheLastDial() {
        val c = config(dials = 3, gapMs = 2_000L)
        assertEquals(0L, InboundProbeSchedule.nextDelayMs(c, 0))
        assertEquals(2_000L, InboundProbeSchedule.nextDelayMs(c, 1))
        assertEquals(2_000L, InboundProbeSchedule.nextDelayMs(c, 2))
        assertNull(InboundProbeSchedule.nextDelayMs(c, 3))
        assertNull(InboundProbeSchedule.nextDelayMs(c, 7))
        assertEquals(0L, InboundProbeSchedule.nextDelayMs(config(dials = 3, gapMs = 0L), 1))
    }

    @Test
    fun theAdvertiserIsScannedAgainAfterTwoConnectTimeoutsInARow() {
        assertEquals(false, InboundProbeSchedule.shouldRescan(0))
        assertEquals(false, InboundProbeSchedule.shouldRescan(1))
        assertEquals(true, InboundProbeSchedule.shouldRescan(2))
        assertEquals(true, InboundProbeSchedule.shouldRescan(5))
    }

    @Test
    fun pacerAllowsKbpsTimesMillisecondsOverEightBytes() {
        assertEquals(0L, InboundProbeSchedule.allowedBytes(60, 0))
        assertEquals(7_500L, InboundProbeSchedule.allowedBytes(60, 1_000))
        assertEquals(150_000L, InboundProbeSchedule.allowedBytes(60, 20_000))
        assertEquals(50_000L, InboundProbeSchedule.allowedBytes(20, 20_000))
    }

    @Test
    fun chunkIsTheAttPayloadInsideTwentyToTwoHundredFortyFourBytes() {
        assertEquals(244, InboundProbeSchedule.chunkBytes(517))
        assertEquals(97, InboundProbeSchedule.chunkBytes(100))
        assertEquals(20, InboundProbeSchedule.chunkBytes(23))
        assertEquals(20, InboundProbeSchedule.chunkBytes(0))
    }

    private fun classify(
        connected: Boolean = true,
        connectStatus: Int? = null,
        serviceFound: Boolean = true,
        linkStatus: Int? = null,
        silentMs: Long = 20L,
        heldMs: Long = 20_000L,
        acked: Long = 150_000L,
    ) = InboundProbeVerdict.classify(connected, connectStatus, serviceFound, linkStatus, silentMs, heldMs, 20_000L, acked, 60)

    @Test
    fun aFullHoldAtTheOfferedRateIsCarried() {
        assertEquals(InboundProbeResult.CARRIED, classify())
        assertEquals(InboundProbeResult.CARRIED, classify(acked = 75_000L))
    }

    @Test
    fun aFullHoldUnderHalfTheOfferedRateIsLowRate() {
        assertEquals(InboundProbeResult.LOW_RATE, classify(acked = 74_000L))
    }

    @Test
    fun aConnectThatFailsWithStatus62Is0x3EAndAnyOtherConnectFailureATimeout() {
        assertEquals(InboundProbeResult.ESTABLISH_FAILED, classify(connected = false, connectStatus = 62))
        assertEquals(InboundProbeResult.CONNECT_TIMEOUT, classify(connected = false, connectStatus = 133))
        assertEquals(InboundProbeResult.CONNECT_TIMEOUT, classify(connected = false, connectStatus = null))
    }

    @Test
    fun noWriteCompletionForThreeSecondsOrAStatus8DropIsSilent() {
        assertEquals(InboundProbeResult.SILENT, classify(silentMs = 3_000L, heldMs = 4_000L, acked = 100L))
        assertEquals(InboundProbeResult.SILENT, classify(silentMs = 2_999L, heldMs = 4_000L, acked = 100L, linkStatus = 8))
        assertEquals(InboundProbeResult.LOW_RATE, classify(silentMs = 2_999L, acked = 100L))
    }

    @Test
    fun aLinkThatEndsEarlyWithAnotherStatusIsDroppedAndMissingServiceIsReportedFirst() {
        assertEquals(InboundProbeResult.DROPPED, classify(linkStatus = 19, heldMs = 5_000L, acked = 37_500L, silentMs = 100L))
        assertEquals(InboundProbeResult.NO_SERVICE, classify(serviceFound = false, heldMs = 0L, acked = 0L))
    }

    @Test
    fun achievedKbpsIsBitsPerMillisecond() {
        assertEquals(60.0, InboundProbeVerdict.achievedKbps(150_000L, 20_000L), 0.0001)
        assertEquals(0.0, InboundProbeVerdict.achievedKbps(100L, 0L), 0.0)
    }

    @Test
    fun addressesAreMaskedToTheLastTwoOctets() {
        assertEquals("...1C:39", InboundProbeLog.mask("AA:BB:CC:DD:1C:39"))
        assertEquals("?", InboundProbeLog.mask(null))
        assertEquals("?", InboundProbeLog.mask(""))
    }

    @Test
    fun dialLineHasTheDocumentedFields() {
        assertEquals(
            "inbound_probe: role=dial | n=3 | of=15 | result=carried | kbps=58.7 | heldMs=20010 | connectMs=412 | " +
                "mtu=517 | status=none | atMs=1234567",
            InboundProbeLog.dial(3, 15, InboundProbeResult.CARRIED, 58.70, 20_010L, 412L, 517, null, 1_234_567L),
        )
        assertEquals(
            "inbound_probe: role=dial | n=1 | of=15 | result=0x3E | kbps=0.0 | heldMs=0 | connectMs=270 | mtu=23 | " +
                "status=62 | atMs=9",
            InboundProbeLog.dial(1, 15, InboundProbeResult.ESTABLISH_FAILED, 0.0, 0L, 270L, 23, 62, 9L),
        )
    }

    @Test
    fun serverAndConfigAndScanLines() {
        assertEquals(
            "inbound_probe: role=server | event=connect | device=...1C:39 | clients=1 | status=0 | atMs=55",
            InboundProbeLog.server("connect", "AA:BB:CC:DD:1C:39", 1, 55L, "status=0"),
        )
        assertEquals(
            "inbound_probe: role=server | event=advertising | device=? | clients=0 | atMs=7",
            InboundProbeLog.server("advertising", null, 0, 7L),
        )
        assertEquals(
            "inbound_probe: role=dial | event=config | dials=15 | holdMs=20000 | gapMs=2000 | kbps=60",
            InboundProbeLog.config(InboundProbeConfig.parse("dial", null, null, null, null)!!),
        )
        assertEquals(
            "inbound_probe: role=dial | event=scan | found=true | device=...1C:39 | atMs=4",
            InboundProbeLog.scan(true, "AA:BB:CC:DD:1C:39", 4L),
        )
    }

    @Test
    fun summaryCountsEveryResultKind() {
        val results = listOf(
            InboundProbeResult.CARRIED, InboundProbeResult.CARRIED, InboundProbeResult.SILENT, InboundProbeResult.ESTABLISH_FAILED,
        )
        assertEquals(
            "inbound_probe: role=dial | event=done | dials=4 | carried=2 | low_rate=0 | silent=1 | 0x3E=1 | " +
                "connect_timeout=0 | no_service=0 | dropped=0",
            InboundProbeLog.summary(results),
        )
    }
}
