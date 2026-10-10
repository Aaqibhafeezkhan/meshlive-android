package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental temporal layering (off by default). Restored 2026-10-08 from the tests deleted on
 * 2026-10-07 and adapted to the no-frame-loss queues: relay and sender queues are unbounded now, so
 * only the "behind by THIN_BACKLOG_MS" gate remains for them; the decode queue thins only when full.
 */
class TemporalLayerThinningTest {
    private var nowNs = 0L

    @Test
    fun aBehindRelayQueueSkipsOnlyDiscardableFramesAndKeepsEveryReference() {
        val queue = RelayChildQueue(thinDiscardable = true) { nowNs }
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(1)))
        nowNs += TemporalLayerThinning.THIN_BACKLOG_MS * 1_000_000L
        // Behind: a discardable arrival is skipped alone.
        assertEquals(ChildQueueOffer.THINNED, queue.offer(envelope(2, MeshEnvelope.FLAG_DISCARDABLE)))
        // Reference frames and audio are always queued; no keyframe is needed.
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(3)))
        assertEquals(ChildQueueOffer.THINNED, queue.offer(envelope(4, MeshEnvelope.FLAG_DISCARDABLE)))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(5, MeshEnvelope.FLAG_AUDIO)))
        assertEquals(listOf(1L, 3L, 5L), generateSequence { queue.poll() }.map { it.sequence }.toList())
        assertEquals(2L, queue.thinnedFrameCount())
    }

    @Test
    fun aRelayQueueThatIsBehindThinsBeforeItOverflows() {
        val queue = RelayChildQueue(thinDiscardable = true) { nowNs }
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(1)))
        nowNs += (TemporalLayerThinning.THIN_BACKLOG_MS - 1) * 1_000_000L
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(2, MeshEnvelope.FLAG_DISCARDABLE)))
        nowNs += 2_000_000L
        assertEquals(ChildQueueOffer.THINNED, queue.offer(envelope(3, MeshEnvelope.FLAG_DISCARDABLE)))
        // Reference frames are still queued while behind.
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(4)))
    }

    @Test
    fun thinnedChildrenDoNotRequestAnUpstreamKeyframe() {
        val core = MeshRelayCore(node(1), thinDiscardable = true, nanoTime = { nowNs })
        core.addChild(node(2))
        assertTrue(core.receive(node(9), envelope(1)).queuedChildren.isNotEmpty())
        nowNs += TemporalLayerThinning.THIN_BACKLOG_MS * 1_000_000L
        val thinned = core.receive(node(9), envelope(2, MeshEnvelope.FLAG_DISCARDABLE))
        assertTrue(thinned.laggingChildren.isEmpty())
        assertTrue(thinned.queuedChildren.isEmpty())
        assertEquals(1L, core.thinnedFrames(node(2)))
    }

    @Test
    fun aFullDecodeQueueDropsDiscardableFramesAndKeepsDecoding() {
        val queue = EncodedFrameQueue(capacity = 2, thinDiscardable = true)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME)))
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_DISCARDABLE)))
        assertTrue(queue.offer(packet(0)))
        assertFalse(queue.offer(packet(StreamProtocol.FLAG_DISCARDABLE)))
        val stats = queue.stats()
        assertFalse(stats.needsKeyFrame)
        assertEquals(2L, stats.thinnedFrames)
        assertEquals(0L, stats.droppedFrames)
    }

    @Test
    fun nativeLayerPatternAlternatesAndRestartsAtKeyframes() {
        assertEquals(listOf(0, 1, 0, 1), (0L until 4L).map { nativeTemporalLayer(true, it) })
        assertEquals(listOf(0, 0, 0), (0L until 3L).map { nativeTemporalLayer(false, it) })
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, nativePacketFlags(keyFrame = true, temporalLayer = 0))
        assertEquals(StreamProtocol.FLAG_DISCARDABLE, nativePacketFlags(keyFrame = false, temporalLayer = 1))
        assertEquals(0, nativePacketFlags(keyFrame = false, temporalLayer = 0))
    }

    @Test
    fun innerAndMeshDiscardableBitsMatch() {
        assertEquals(MeshEnvelope.FLAG_DISCARDABLE, meshPacketFlags(StreamProtocol.FLAG_DISCARDABLE))
        assertTrue(MediaImpairmentPlan.eligible(StreamProtocol.FLAG_DISCARDABLE))
    }

    @Test
    fun onlyChronicThinningIsRateEvidence() {
        // A VBR burst thinned for a few seconds must not cut the rate (600 -> 240 kbps and
        // VMAF 40.5 in pacing-ab-c777b381); thinning in 8 of 10 windows means the link is short.
        val controller = LinkRateController(requestedVideoBps = 1_000_000, audioBps = 0)
        val second = 1_000_000_000L
        val thinned = LinkRateSample(second, 125_000, 80_000, 60, largeFrameGrace = false, thinnedFrames = 7)
        var limit: Int? = null
        for (t in 1..9) limit = controller.onSample("child", thinned, t * second) ?: limit
        assertEquals(null, limit)
        for (t in 10..12) limit = controller.onSample("child", thinned, t * second) ?: limit
        assertTrue(limit != null && limit < 1_000_000)
    }

    @Test
    fun layeredAdmissionPlansTheFirstLinkFullAndOthersAtTheirBaseLayer() {
        // M31 GATT relay: budget 0.9 x 774 = 697 kbps; full link 403, measured base link 263.
        assertEquals(1, MeshBandwidthEstimator.layeredLinkSlots(697.0, 403.0, null))
        assertEquals(2, MeshBandwidthEstimator.layeredLinkSlots(697.0, 403.0, 263.0))
        assertEquals(0, MeshBandwidthEstimator.layeredLinkSlots(300.0, 403.0, 263.0))
        // A "base" that is not smaller than the full link gives no relaxation.
        assertEquals(1, MeshBandwidthEstimator.layeredLinkSlots(697.0, 403.0, 403.0))
    }

    @Test
    fun baseShareIsMeasuredFromFramesAndUnknownUntilALayerAppears() {
        val meter = LayerShareMeter()
        meter.add(10_000, discardable = false)
        assertEquals(null, meter.baseShare())
        meter.add(3_000, discardable = true)
        meter.add(7_000, discardable = false)
        assertEquals(0.85, meter.baseShare()!!, 1e-9)
    }

    @Test
    fun droppableFramesStopOnlyAfterEveryLinkHadRoomFor20Seconds() {
        val second = 1_000_000_000L
        val policy = LayeringPolicy(nanoTime = { nowNs })
        fun roomy() = LinkRateSample(second, 50_000, 50_000, 10, largeFrameGrace = false, measuredLinkKbps = 900)
        assertTrue(policy.layered)
        // Not yet measured: stay layered.
        assertEquals(null, policy.onSample(LinkRateSample(second, 50_000, 50_000, 10, largeFrameGrace = false)))
        for (t in 1..19) {
            nowNs += second
            assertEquals(null, policy.onSample(roomy()))
        }
        nowNs += second
        assertEquals(false, policy.onSample(roomy()))
        // The child reports that media on this link arrives late (hop-local): back on at once.
        nowNs += second
        assertEquals(true, policy.onSample(roomy().copy(childLate = true)))
        // 400 kbps offered on a 350 kbps link is above what the link carries: tight.
        nowNs += 30 * second
        assertEquals(null, policy.onSample(LinkRateSample(second, 50_000, 50_000, 10, largeFrameGrace = false, measuredLinkKbps = 350)))
        // A scene-cut drain (large-frame grace) is not tightness.
        val relaxed = LayeringPolicy(priorLinkKbps = 1_000, initialOfferedKbps = 500, nanoTime = { nowNs })
        assertEquals(null, relaxed.onSample(roomy().copy(backlogAgeMs = 900, largeFrameGrace = true)))
    }

    @Test
    fun aStoredMeasurementWithRoomStartsWithAllFramesAsReferences() {
        assertEquals(false, LayeringPolicy(priorLinkKbps = 1_000, initialOfferedKbps = 500, nanoTime = { nowNs }).layered)
        assertEquals(false, LayeringPolicy(priorLinkKbps = 1_000, initialOfferedKbps = 900, nanoTime = { nowNs }).layered)
        assertEquals(true, LayeringPolicy(priorLinkKbps = 1_000, initialOfferedKbps = 1_100, nanoTime = { nowNs }).layered)
        assertEquals(true, LayeringPolicy(priorLinkKbps = null, initialOfferedKbps = 100, nanoTime = { nowNs }).layered)
    }

    // ---- The experiment is off by default and then changes nothing ----

    @Test
    fun theExperimentIsOffByDefault() {
        assertFalse(DebugTunables.experimentalTemporalLayers)
        assertFalse(DebugTunables.hardwareTemporalLayers)
    }

    @Test
    fun withTheExperimentOffARelayQueueNeverThins() {
        val queue = RelayChildQueue { nowNs }
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(1)))
        nowNs += 10 * TemporalLayerThinning.THIN_BACKLOG_MS * 1_000_000L
        for (sequence in 2L..6L) {
            assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(sequence, MeshEnvelope.FLAG_DISCARDABLE)))
        }
        assertEquals((1L..6L).toList(), generateSequence { queue.poll() }.map { it.sequence }.toList())
        assertEquals(0L, queue.thinnedFrameCount())

        val core = MeshRelayCore(node(1), nanoTime = { nowNs })
        core.addChild(node(2))
        core.receive(node(9), envelope(1))
        nowNs += 10 * TemporalLayerThinning.THIN_BACKLOG_MS * 1_000_000L
        assertEquals(setOf(node(2)), core.receive(node(9), envelope(2, MeshEnvelope.FLAG_DISCARDABLE)).queuedChildren)
        assertEquals(0L, core.thinnedFrames(node(2)))
    }

    @Test(expected = IllegalStateException::class)
    fun withTheExperimentOffAFullDecodeQueueWaitsForTheDecoderInsteadOfThinning() {
        val queue = EncodedFrameQueue(1, backpressureTimeoutNs = 50_000_000L)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME)))
        // Backpressure (and a loud failure if the decoder never drains), never a skipped frame.
        queue.offer(packet(StreamProtocol.FLAG_DISCARDABLE))
    }

    @Test
    fun withTheExperimentOffNoFrameIsMarkedAndAdmissionIsUnchanged() {
        assertTrue((0L until 64L).all { nativeTemporalLayer(false, it) == 0 })
        assertTrue(
            (0L until 64L).none {
                nativePacketFlags(keyFrame = false, nativeTemporalLayer(false, it)) == StreamProtocol.FLAG_DISCARDABLE
            },
        )
        for ((aggregate, perLink) in listOf(697.0 to 403.0, 300.0 to 403.0, 1_200.0 to 343.4, 0.0 to 100.0)) {
            assertEquals(
                kotlin.math.floor(aggregate / perLink).toInt(),
                MeshBandwidthEstimator.layeredLinkSlots(aggregate, perLink, null),
            )
        }
        val settings = VideoSettings(VideoSettings.RESOLUTIONS[1], 15, 250_000, 0)
        val measured = { perLink: Double -> 3.2 * perLink }
        assertEquals(
            MeshBandwidthEstimator.estimate(settings, BluetoothTransportOption.BLE_GATT, MeshNodeRole.SOURCE, 0, measured),
            MeshBandwidthEstimator.estimate(
                settings,
                BluetoothTransportOption.BLE_GATT,
                MeshNodeRole.SOURCE,
                0,
                measured,
                baseLayerShare = null,
            ),
        )
    }

    @Test
    fun windowsWithoutThinningNeverCountAsChronicThinning() {
        val controller = LinkRateController(requestedVideoBps = 1_000_000, audioBps = 0)
        val second = 1_000_000_000L
        // Offered above sent with a short backlog and no thinning: no rate evidence (as before).
        val sample = LinkRateSample(second, 125_000, 80_000, 60, largeFrameGrace = false)
        for (t in 1..30) assertEquals(null, controller.onSample("child", sample, t * second))
        assertEquals(1_000_000, controller.effectiveVideoBps)
    }

    private fun envelope(sequence: Long, flags: Int = 0) = MeshEnvelope(
        streamId = STREAM_ID,
        sourceNodeId = node(8),
        sequence = sequence,
        ttl = 3,
        flags = flags,
        payload = byteArrayOf(1, 2, 3),
    )

    private fun packet(flags: Int) = StreamProtocol.Packet(flags, 0L, byteArrayOf(1, 2, 3))

    private fun node(value: Int): NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { value.toByte() })

    private companion object {
        val STREAM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")
    }
}
