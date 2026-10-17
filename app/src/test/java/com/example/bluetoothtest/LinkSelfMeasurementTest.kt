package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkSelfMeasurementTest {
    private var nowNs = 0L

    @Test
    fun aRecordArrivingOverTimeMeasuresTheLinkRate() {
        val meter = ArrivalTrainMeter({ nowNs })
        meter.beginRecord()
        meter.onRead(17, waited = true, availableAfter = 0)
        // 20 chunks of 1 000 bytes, one every 10 ms: 20 000 bytes in 200 ms = 800 kbps.
        repeat(20) {
            nowNs += 10_000_000L
            meter.onRead(1_000, waited = true, availableAfter = 0)
        }
        assertEquals(800, meter.endRecord())
        assertEquals(800, meter.estimateKbps())
    }

    @Test
    fun bytesAlreadyBufferedWhenTheTrainStartsAreExcluded() {
        // The reader was busy: 10 000 bytes were waiting, so they did not arrive during the train.
        val meter = ArrivalTrainMeter({ nowNs })
        meter.beginRecord()
        meter.onRead(17, waited = true, availableAfter = 10_000)
        meter.onRead(10_000, waited = false, availableAfter = 0)
        repeat(10) {
            nowNs += 10_000_000L
            meter.onRead(1_000, waited = true, availableAfter = 0)
        }
        assertEquals(800, meter.endRecord())
    }

    @Test
    fun smallOrFullyBufferedRecordsAreNotMeasured() {
        val meter = ArrivalTrainMeter({ nowNs })
        meter.beginRecord()
        meter.onRead(2_000, waited = true, availableAfter = 0)
        nowNs += 20_000_000L
        meter.onRead(2_000, waited = true, availableAfter = 0)
        assertNull(meter.endRecord())
        meter.beginRecord()
        meter.onRead(40_000, waited = false, availableAfter = 0)
        assertNull(meter.endRecord())
        assertNull(meter.estimateKbps())
    }

    @Test
    fun theEstimateIsTheMedianTrainAndExpires() {
        val meter = ArrivalTrainMeter({ nowNs }, windowNs = 1_000_000_000L)
        listOf(500, 600, 5_000).forEach { kbps ->
            meter.beginRecord()
            meter.onRead(1, waited = true, availableAfter = 0)
            val durationNs = 200_000_000L
            nowNs += durationNs
            meter.onRead((kbps * durationNs / 8_000_000L).toInt(), waited = true, availableAfter = 0)
            meter.endRecord()
        }
        assertEquals(600, meter.estimateKbps())
        nowNs += 2_000_000_000L
        assertNull(meter.estimateKbps())
    }

    @Test
    fun timedStreamPassesBytesThroughUnchanged() {
        val data = ByteArray(10_000) { it.toByte() }
        val meter = ArrivalTrainMeter({ nowNs })
        val input = ArrivalTimedInputStream(data.inputStream() as InputStream, meter)
        meter.beginRecord()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(777)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        meter.endRecord()
        assertEquals(data.toList(), out.toByteArray().toList())
    }

    @Test
    fun capacityReportsRoundTripAndPlainHeartbeatsCarryNone() {
        val report = SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(2, 0x03, 0x84.toByte(), 1, 0x01, 0x2c))
        assertEquals(
            LinkCapacityReport(900, linkLate = true, addedDelayMs = 300),
            SecureLinkControlProtocol.requireHeartbeat(report),
        )
        assertNull(SecureLinkControlProtocol.requireHeartbeat(SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))))
        val unknownFlag = SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(2, 0, 0, 2, 0, 0))
        assertEquals(true, runCatching { SecureLinkControlProtocol.requireHeartbeat(unknownFlag) }.isFailure)
    }

    @Test
    fun aReportWithoutDeliveryStaysTheExactVersion2BytesOlderPeersAccept() {
        val bytes = SecureLinkControlProtocol.encodeCapacityReport(LinkCapacityReport(900, linkLate = true, addedDelayMs = 300))
        assertEquals(listOf<Byte>(2, 0x03, 0x84.toByte(), 1, 0x01, 0x2c), bytes.toList())
    }

    @Test
    fun deliveryReportsRoundTripAsVersion3() {
        val report = LinkCapacityReport(784, linkLate = false, addedDelayMs = 461, LinkDelivery(0x1_2345_6789L, 0x1_0000_0010L))
        val bytes = SecureLinkControlProtocol.encodeCapacityReport(report)
        assertEquals(14, bytes.size)
        assertEquals(3, bytes[0].toInt())
        // Both counters travel modulo 2^32.
        assertEquals(
            report.copy(delivery = LinkDelivery(0x2345_6789L, 0x10L)),
            SecureLinkControlProtocol.requireHeartbeat(SecureRecord(SecureRecordType.LINK_HEARTBEAT, bytes)),
        )
    }

    @Test
    fun laterReportVersionsAreReadForTheirVersion3FieldsAndNeverCloseTheLink() {
        val v3 = SecureLinkControlProtocol.encodeCapacityReport(LinkCapacityReport(600, true, 120, LinkDelivery(5_000, 2_000)))
        // A version 4 report: new flag bits (2-7) and trailing fields.
        val v4 = v3.copyOf().also { it[0] = 4; it[3] = (1 or 0x84).toByte() } + byteArrayOf(9, 9, 9, 9)
        assertEquals(
            LinkCapacityReport(600, true, 120, LinkDelivery(5_000, 2_000)),
            SecureLinkControlProtocol.requireHeartbeat(SecureRecord(SecureRecordType.LINK_HEARTBEAT, v4)),
        )
        // Still bounded: a truncated version 3, an oversized report and unknown version 2 flags fail.
        for (bad in listOf(v3.copyOf(13), v3 + ByteArray(51), byteArrayOf(2, 0, 0, 2, 0, 0), byteArrayOf(0))) {
            assertEquals(true, runCatching {
                SecureLinkControlProtocol.requireHeartbeat(SecureRecord(SecureRecordType.LINK_HEARTBEAT, bad))
            }.isFailure)
        }
    }

    @Test
    fun aDeliveryOnlyReportIsFlaggedSoItDoesNotFeedTheLatenessTrend() {
        val report = LinkCapacityReport(600, linkLate = true, addedDelayMs = 120, LinkDelivery(9_000, 3_000), deliveryOnly = true)
        val bytes = SecureLinkControlProtocol.encodeCapacityReport(report)
        assertEquals(1 or 2, bytes[3].toInt())
        assertEquals(report, SecureLinkControlProtocol.requireHeartbeat(SecureRecord(SecureRecordType.LINK_HEARTBEAT, bytes)))
        // Without delivery there is nothing "only" about it: an exact version 2 report.
        assertEquals(6, SecureLinkControlProtocol.encodeCapacityReport(report.copy(delivery = null)).size)
    }

    @Test
    fun theDeliveryCounterStampsTheArrivalOfTheLastCountedBytesNotTheReportTime() {
        var now = 5_000_000_000L
        val counter = LinkDeliveryCounter { now }
        now += 200_000_000L
        counter.add(1_200)
        counter.add(0)
        now += 300_000_000L
        counter.add(800)
        // The report goes out a second later; the clock still says when the last byte arrived.
        now += 1_000_000_000L
        assertEquals(LinkDelivery(2_000, 500), counter.snapshot())
    }

    @Test
    fun pacingAllowsOnlyAShortBurstAheadOfTheMeasuredLink() {
        val pacer = LinkPacer({ nowNs })
        assertEquals(0L, pacer.waitNs())
        pacer.onSent(100_000)
        assertEquals(0L, pacer.waitNs()) // unpaced until a measurement arrives
        // 800 kbps measured -> paced at 120 000 B/s; a 12 000-byte frame drains in 100 ms.
        pacer.setMeasuredLinkKbps(800)
        pacer.onSent(12_000)
        assertEquals(50_000_000L, pacer.waitNs())
        nowNs += 60_000_000L
        assertEquals(0L, pacer.waitNs())
    }

    // mesh4-57484598 gap A: the child link was written unpaced until its first arrival report.

    @Test
    fun aSeededLinkIsPacedFromTheFirstWriteAndTheFirstReportReplacesTheSeed() {
        val pacer = LinkPacer({ nowNs })
        assertEquals(false, pacer.measured)
        assertEquals(true, pacer.seedKbps(400)) // 400 kbps x 1.2 = 60 000 B/s
        assertEquals(60_000L, pacer.bytesPerSecond)
        assertEquals(false, pacer.seedKbps(900)) // a second seed does not override
        pacer.onSent(6_000) // 100 ms of link time
        assertEquals(50_000_000L, pacer.waitNs())
        pacer.setMeasuredLinkKbps(1_200) // 180 000 B/s: the remaining 100 ms drains in a third
        assertEquals(true, pacer.measured)
        assertEquals(180_000L, pacer.bytesPerSecond)
        assertEquals(0L, pacer.waitNs())
        assertEquals(false, pacer.seedKbps(100)) // never re-seeded once measured
    }

    @Test
    fun aSeedHeldWithoutAnyReportDecaysSoTheBacklogShowsInTheAppQueue() {
        val pacer = LinkPacer({ nowNs })
        pacer.seedKbps(800) // 120 000 B/s
        nowNs += 3_000_000_000L
        assertEquals(120_000L, pacer.bytesPerSecond)
        nowNs += 2_000_000_000L // 5 s of silence: one halving
        assertEquals(60_000L, pacer.bytesPerSecond)
        nowNs += 60_000_000_000L // a long silence stops at the floor
        assertEquals((LinkPacer.MIN_SEED_KBPS * 1_000L / 8 * LinkPacer.PACE_GAIN).toLong(), pacer.bytesPerSecond)
        // A report at any time restores the measured rate.
        pacer.setMeasuredLinkKbps(800)
        assertEquals(120_000L, pacer.bytesPerSecond)
    }

    // lane12 s6-threshold-360p: 640x360 at 500 kbps never formed a train, the viewer stayed silent and
    // the seed decayed to the 150 kbps floor (about 157 kbps delivered, 65 s queue).

    @Test
    fun onTimeReportsFromAnUnmeasuredChildHoldTheSeed() {
        val pacer = LinkPacer({ nowNs })
        pacer.seedKbps(1_488)
        val seeded = pacer.bytesPerSecond
        repeat(30) { // 60 s of on-time reports every 2 s
            nowNs += ON_TIME_REPORT_INTERVAL_NS
            assertEquals(true, pacer.holdSeed())
            assertEquals(seeded, pacer.bytesPerSecond)
        }
        // Reports stop (the link backed up): the seed decays as before.
        nowNs += 60_000_000_000L
        assertEquals((LinkPacer.MIN_SEED_KBPS * 1_000L / 8 * LinkPacer.PACE_GAIN).toLong(), pacer.bytesPerSecond)
        // A later on-time report restores it; a measurement then replaces it for good.
        assertEquals(true, pacer.holdSeed())
        assertEquals(seeded, pacer.bytesPerSecond)
        pacer.setMeasuredLinkKbps(600)
        assertEquals(false, pacer.holdSeed())
        assertEquals(90_000L, pacer.bytesPerSecond)
    }

    // lane15 pairB2: a fresh-install A55 seeded at the 350 kbps GATT prior held a 400 kbps stream below
    // its offered load; the ASUS reported on time while the sender queue grew to 14 s.

    @Test
    fun theExperimentalRaiseGrowsAHeldSeedOnlyWhileTheSenderBacksUp() {
        val pacer = LinkPacer({ nowNs })
        pacer.seedKbps(350)
        // Off (negative) or a short queue: held unchanged.
        assertEquals(true, pacer.holdSeed())
        assertEquals(true, pacer.holdSeed(LinkPacer.SEED_RAISE_BACKLOG_MS - 1))
        assertEquals(350, pacer.seedKbps)
        // Backed up: +25% per on-time report.
        assertEquals(true, pacer.holdSeed(LinkPacer.SEED_RAISE_BACKLOG_MS))
        assertEquals(437, pacer.seedKbps)
        assertEquals((437.5 * 1_000 / 8 * LinkPacer.PACE_GAIN).toLong(), pacer.bytesPerSecond)
        repeat(20) { pacer.holdSeed(10_000L) }
        assertEquals(LinkPacer.SEED_RAISE_CAP_KBPS, pacer.seedKbps)
        // Without reports it still decays; a measurement still replaces it.
        nowNs += 60_000_000_000L
        assertEquals((LinkPacer.MIN_SEED_KBPS * 1_000L / 8 * LinkPacer.PACE_GAIN).toLong(), pacer.bytesPerSecond)
        pacer.setMeasuredLinkKbps(600)
        assertEquals(false, pacer.holdSeed(10_000L))
        assertEquals(90_000L, pacer.bytesPerSecond)
    }

    @Test
    fun aRaisedSeedDrainsWhatWasAlreadyChargedAtTheNewPace() {
        val pacer = LinkPacer({ nowNs }, burstNs = 0L)
        pacer.seedKbps(400) // 60 000 B/s paced
        pacer.onSent(60_000, nowNs) // one second ahead
        assertEquals(1_000_000_000L, pacer.waitNs())
        pacer.holdSeed(LinkPacer.SEED_RAISE_BACKLOG_MS) // 500 kbps
        assertEquals(800_000_000L, pacer.waitNs())
    }

    @Test
    fun holdingAnUnseededPacerLeavesItUnpaced() {
        val pacer = LinkPacer({ nowNs })
        assertEquals(false, pacer.holdSeed())
        assertEquals(0L, pacer.bytesPerSecond)
    }

    @Test
    fun anOnTimeReportIsDueOnlyForAnUnmeasuredOnTimeLinkWithNewMedia() {
        val due = ON_TIME_REPORT_INTERVAL_NS
        assertEquals(true, onTimeReportDue(true, 0L, tight = false, mediaArrived = true, sinceLastReportNs = due))
        assertEquals(false, onTimeReportDue(false, 0L, tight = false, mediaArrived = true, sinceLastReportNs = due))
        assertEquals(false, onTimeReportDue(true, 1L, tight = false, mediaArrived = true, sinceLastReportNs = due))
        assertEquals(false, onTimeReportDue(true, 0L, tight = true, mediaArrived = true, sinceLastReportNs = due))
        assertEquals(false, onTimeReportDue(true, 0L, tight = false, mediaArrived = false, sinceLastReportNs = due))
        assertEquals(false, onTimeReportDue(true, 0L, tight = false, mediaArrived = true, sinceLastReportNs = due - 1))
        // Inside the parent's grace, so a single lost report does not start the decay.
        assertEquals(true, ON_TIME_REPORT_INTERVAL_NS * 2 <= LinkPacer.SILENCE_GRACE_NS)
    }

    @Test
    fun recordsJustUnderTheTrainMinimumAreNeverMeasured() {
        // An 8.3 KB keyframe (640x360 at 500 kbps) minus the first read is under MIN_TRAIN_BYTES.
        val meter = ArrivalTrainMeter({ nowNs })
        meter.beginRecord()
        meter.onRead(512, waited = true, availableAfter = 0)
        repeat(30) {
            nowNs += 5_000_000L
            meter.onRead(260, waited = false, availableAfter = 0)
        }
        assertNull(meter.endRecord())
        assertEquals(0L, meter.trains)
    }

    @Test
    fun aSeedBelowTheFloorIsNotRaisedByDecay() {
        assertEquals(10_000L, LinkPacer.decayedSeed(10_000L, 100_000_000_000L))
    }

    @Test
    fun theSeedUsesTheMeasuredLinkElseAConservativePrior() {
        assertEquals(620, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_GATT, 620, true))
        assertEquals(350, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_GATT, null, true))
        assertEquals(250, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_GATT, 0, false))
        assertEquals(200, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_L2CAP, null, false))
        // Every prior stays below the fan-out aggregate priors (700-800 kbps).
        BluetoothTransportOption.entries.forEach { transport ->
            listOf(true, false, null).forEach { phy ->
                assert(LinkPacerSeed.priorKbps(transport, phy) < MeshBandwidthEstimator.safeAggregateKbps(transport))
            }
        }
    }

    @Test
    fun aBlockingWriteIsChargedFromItsStartSoItsTransmitTimeIsNotPaidTwice() {
        val pacer = LinkPacer({ nowNs })
        pacer.setMeasuredLinkKbps(800) // 120 000 B/s
        // A GATT write of 120 000 bytes blocks for the full second it takes to transmit.
        val startedNs = nowNs
        nowNs += 1_000_000_000L
        pacer.onSent(120_000, startedNs)
        assertEquals(0L, pacer.waitNs())
        // A non-blocking write charged at its start still drains at the measured rate.
        pacer.onSent(12_000, nowNs)
        assertEquals(50_000_000L, pacer.waitNs())
    }

    @Test
    fun aPlainHeartbeatSuchAsARelayForwardsCarriesNoReport() {
        // The call relay forwards liveness only; an empty payload used to close relayed calls.
        assertNull(SecureLinkControlProtocol.requireHeartbeat(SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))))
    }
}
