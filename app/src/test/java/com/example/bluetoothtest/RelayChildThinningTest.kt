package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental relay child thinning (off by default; RelayChildLayerPolicy): the per-child policy and its
 * hysteresis, the queue levels (whole-record drops only), the end-to-end acceptance of the gaps they leave,
 * the decodability of what a child receives, the audio-only fallback and its resume, and the viewer notice.
 */
class RelayChildThinningTest {
    private var nowNs = 0L

    // ---- policy ----

    private fun sample(
        queueAgeMs: Long = 0,
        childLate: Boolean = false,
        capacity: Int? = null,
        capacityAgeMs: Long = 0,
        offeredFull: Int = 300,
        offeredBase: Int = 160,
        keyFrameBytes: Int = 40_000,
        keyFrameGrace: Boolean = false,
        shedEvents: Long = 0,
        hasBase: Boolean = true,
        hasAudio: Boolean = true,
    ) = RelayChildLayerSample(
        queueAgeMs = queueAgeMs,
        deliveredKbps = 0,
        offeredFullKbps = offeredFull,
        offeredBaseKbps = offeredBase,
        busyCapacityKbps = capacity,
        busyCapacityAgeMs = capacityAgeMs,
        keyFrameBytes = keyFrameBytes,
        keyFrameGrace = keyFrameGrace,
        childLate = childLate,
        shedEvents = shedEvents,
        hasBaseLayer = hasBase,
        hasAudio = hasAudio,
    )

    /** Runs [policy] with [s] every 500 ms from [fromMs] for [forMs]; returns the first change, if any. */
    private fun run(policy: RelayChildLayerPolicy, fromMs: Long, forMs: Long, s: RelayChildLayerSample): RelayChildLayerDecision? {
        var t = fromMs
        while (t <= fromMs + forMs) {
            val decision = policy.onSample(t, s)
            if (decision.changed) return decision
            t += 500
        }
        return null
    }

    @Test
    fun theSwitchIsOffByDefault() {
        assertFalse(DebugTunables.relayChildThinning)
    }

    @Test
    fun aCalmChildStaysAtFullIndefinitely() {
        val policy = RelayChildLayerPolicy()
        assertNull(run(policy, 0, 120_000, sample(queueAgeMs = 40)))
        assertEquals(RelayChildLevel.FULL, policy.level)
        assertEquals(0L, policy.changes)
    }

    @Test
    fun aChildThatStaysBehindLosesTheEnhancementLayerFirst() {
        val policy = RelayChildLayerPolicy()
        assertNull(run(policy, 0, 2_000, sample(queueAgeMs = 100)))
        // The queue is 800 ms behind: not at once (a burst drains), but after the hold.
        val decision = run(policy, 2_500, 5_000, sample(queueAgeMs = 800))!!
        assertEquals(RelayChildLevel.BASE, decision.level)
        assertEquals("queue_behind", decision.reason)
        assertTrue(policy.level == RelayChildLevel.BASE)
    }

    @Test
    fun aKeyframesDrainIsNotEvidenceOfASlowLink() {
        val policy = RelayChildLayerPolicy()
        // 1.4 s behind right after a keyframe left the queue: its airtime, not a steady backlog.
        assertNull(run(policy, 0, 30_000, sample(queueAgeMs = 1_400, keyFrameGrace = true)))
        assertEquals(RelayChildLevel.FULL, policy.level)
        // The same backlog without a keyframe behind it is evidence.
        assertEquals(RelayChildLevel.BASE, run(policy, 31_000, 5_000, sample(queueAgeMs = 1_400))!!.level)
    }

    @Test
    fun aSevereBacklogOrAShedActsImmediately() {
        val severe = RelayChildLayerPolicy()
        severe.onSample(0, sample())
        assertEquals(RelayChildLevel.BASE, severe.onSample(100, sample(queueAgeMs = 2_100)).level)
        val shed = RelayChildLayerPolicy()
        shed.onSample(0, sample())
        val decision = shed.onSample(100, sample(shedEvents = 1))
        assertEquals(RelayChildLevel.BASE, decision.level)
        assertEquals("shed", decision.reason)
    }

    @Test
    fun theChildsOwnLateReportLowersTheBarForBehind() {
        val policy = RelayChildLayerPolicy()
        assertNull(run(policy, 0, 10_000, sample(queueAgeMs = 400)))
        val decision = run(policy, 10_500, 5_000, sample(queueAgeMs = 400, childLate = true))!!
        assertEquals(RelayChildLevel.BASE, decision.level)
        assertEquals("child_late", decision.reason)
    }

    @Test
    fun whenEvenTheBaseLayerDoesNotFitVideoIsPausedAndAudioContinues() {
        val policy = RelayChildLayerPolicy()
        run(policy, 0, 3_000, sample(queueAgeMs = 900))
        assertEquals(RelayChildLevel.BASE, policy.level)
        // Still behind after the settle and the hold: audio only.
        val decision = run(policy, 3_500, 8_000, sample(queueAgeMs = 1_200))!!
        assertEquals(RelayChildLevel.AUDIO, decision.level)
        assertEquals("queue_behind", decision.reason)
    }

    @Test
    fun aMeasuredLinkFarBelowTheBaseLayerSkipsTheBaseStep() {
        val policy = RelayChildLayerPolicy()
        // 70 kbps measured while media waited; the base layer is offered at 160 kbps.
        val decision = run(policy, 0, 6_000, sample(queueAgeMs = 900, capacity = 70, capacityAgeMs = 500))!!
        assertEquals(RelayChildLevel.AUDIO, decision.level)
        assertEquals("base_does_not_fit", decision.reason)
    }

    @Test
    fun aStreamWithoutALayerGoesStraightFromFullToAudioAndNeverWithoutAudio() {
        val noLayer = RelayChildLayerPolicy()
        val decision = run(noLayer, 0, 8_000, sample(queueAgeMs = 1_000, hasBase = false))!!
        assertEquals(RelayChildLevel.AUDIO, decision.level)
        // No audio and no layer: nothing lower exists; the withdrawal rules decide as before.
        val bare = RelayChildLayerPolicy()
        assertNull(run(bare, 0, 30_000, sample(queueAgeMs = 1_900, hasBase = false, hasAudio = false)))
        assertEquals(RelayChildLevel.FULL, bare.level)
    }

    @Test
    fun recoveryIsOneLevelAtATimeAfterACalmHold() {
        val policy = RelayChildLayerPolicy()
        policy.onSample(0, sample())
        policy.onSample(100, sample(queueAgeMs = 2_500)) // -> BASE
        assertEquals(RelayChildLevel.BASE, policy.level)
        // Calm, but not yet held long enough (8 s) or calm long enough (4 s): stay.
        assertNull(run(policy, 600, 6_000, sample(queueAgeMs = 20)))
        val up = run(policy, 6_600, 6_000, sample(queueAgeMs = 20))!!
        assertEquals(RelayChildLevel.FULL, up.level)
        assertEquals("calm_probe", up.reason)
    }

    @Test
    fun aFailedProbeDoublesTheWaitAndAStableOneResetsIt() {
        val policy = RelayChildLayerPolicy()
        policy.onSample(0, sample())
        policy.onSample(100, sample(queueAgeMs = 2_500))
        var probedAt = 600L
        while (!policy.onSample(probedAt, sample(queueAgeMs = 20)).changed) probedAt += 500
        assertEquals(RelayChildLevel.FULL, policy.level)
        assertEquals(8_000L, policy.upHoldMs)
        val failedAt = probedAt + 5_000
        // The probe fails inside the failure window: back to BASE and the next wait doubles.
        policy.onSample(failedAt, sample(queueAgeMs = 2_500))
        assertEquals(RelayChildLevel.BASE, policy.level)
        assertEquals(16_000L, policy.upHoldMs)
        // Next probe has to wait 16 s now: calm for 12 s changes nothing.
        assertNull(run(policy, failedAt + 500, 12_000, sample(queueAgeMs = 20)))
    }

    @Test
    fun aStableProbeResetsTheWait() {
        val policy = RelayChildLayerPolicy()
        policy.onSample(0, sample())
        policy.onSample(100, sample(queueAgeMs = 2_500)) // BASE
        run(policy, 600, 20_000, sample(queueAgeMs = 20)) // probe to FULL (first at about 8 s)
        assertEquals(RelayChildLevel.FULL, policy.level)
        policy.onSample(40_000, sample(queueAgeMs = 2_500)) // late failure: outside the failure window
        assertEquals(RelayChildLevel.BASE, policy.level)
        assertEquals(8_000L, policy.upHoldMs)
        // A probe that fails soon after doubles the wait; one that then holds for 30 s resets it.
        run(policy, 40_500, 20_000, sample(queueAgeMs = 20))
        assertEquals(RelayChildLevel.FULL, policy.level)
        val probedAt = 40_500L + 8_000L
        policy.onSample(probedAt + 5_000, sample(queueAgeMs = 2_500))
        assertEquals(16_000L, policy.upHoldMs)
        run(policy, probedAt + 5_500, 60_000, sample(queueAgeMs = 20))
        assertEquals(RelayChildLevel.FULL, policy.level)
        run(policy, probedAt + 66_000, 40_000, sample(queueAgeMs = 20))
        assertEquals(8_000L, policy.upHoldMs)
    }

    @Test
    fun aProbeBackToVideoWaitsForALinkThatCanCarryItAndItsKeyframe() {
        val policy = RelayChildLayerPolicy()
        policy.onSample(0, sample())
        policy.onSample(100, sample(queueAgeMs = 2_500)) // BASE
        policy.onSample(3_000, sample(queueAgeMs = 2_500)) // AUDIO
        assertEquals(RelayChildLevel.AUDIO, policy.level)
        // Fresh capacity of 100 kbps cannot carry the 160 kbps base layer: no probe, however calm.
        assertNull(run(policy, 3_500, 30_000, sample(queueAgeMs = 10, capacity = 100, capacityAgeMs = 2_000)))
        assertEquals(RelayChildLevel.AUDIO, policy.level)
        // 200 kbps carries the layer, but a 120 KB keyframe would take 4.8 s (limit 4.5 s).
        assertNull(
            run(policy, 34_000, 10_000, sample(queueAgeMs = 10, capacity = 200, capacityAgeMs = 2_000, keyFrameBytes = 120_000)),
        )
        // A stale measurement no longer vetoes (the link may have changed): probe to BASE, not FULL.
        val up = run(policy, 45_000, 10_000, sample(queueAgeMs = 10, capacity = 100, capacityAgeMs = 60_000))!!
        assertEquals(RelayChildLevel.BASE, up.level)
    }

    @Test
    fun anAudioOnlyLevelOnAStreamWithoutALayerResumesAtFull() {
        val policy = RelayChildLayerPolicy()
        val down = run(policy, 0, 8_000, sample(queueAgeMs = 1_000, hasBase = false))!!
        assertEquals(RelayChildLevel.AUDIO, down.level)
        val up = run(policy, 9_000, 30_000, sample(queueAgeMs = 10, hasBase = false))!!
        assertEquals(RelayChildLevel.FULL, up.level)
    }

    @Test
    fun theLinkMeterMeasuresCapacityOnlyWhileMediaWaited() {
        val meter = RelayChildLinkMeter()
        var delivered = 0L
        var rates: RelayChildLinkMeter.Rates? = null
        // 5 s idle (queue empty, 10 kbps delivered): no capacity claim.
        for (t in 0..10) {
            delivered += 625
            rates = meter.onTick(t * 500L, delivered, delivered, delivered, backlogged = false)
        }
        assertNull(rates!!.busyCapacityKbps)
        assertEquals(10, rates.deliveredKbps)
        // 3 s with media waiting while 8000 B/s (64 kbps) leave: that is the link's capacity.
        var offeredFull = delivered
        var offeredBase = delivered
        for (t in 11..17) {
            delivered += 4_000
            offeredFull += 20_000
            offeredBase += 10_000
            rates = meter.onTick(t * 500L, delivered, offeredFull, offeredBase, backlogged = true)
        }
        assertEquals(64, rates!!.busyCapacityKbps)
        assertTrue(rates.offeredFullKbps > rates.offeredBaseKbps)
    }

    // ---- queue levels ----

    private val key = ByteArray(MeshAead.KEY_BYTES) { 7 }
    private var sealer = EndToEndContentSealer(key)
    private var meshSequence = 0L

    /** One source record in stream order: sealed with the next content sequence, mirrored mesh flags. */
    private fun record(flags: Int, size: Int = 100, pts: Long = meshSequence * 66_667L): MeshEnvelope {
        val sequence = meshSequence++
        val plaintext = EndToEndStreamProtocol.encodePacket(flags, pts, ByteArray(size) { sequence.toByte() })
        return MeshEnvelope(
            streamId = STREAM_ID,
            sourceNodeId = SOURCE,
            sequence = sequence,
            ttl = 5,
            flags = meshPacketFlags(flags),
            payload = sealer.seal(EndToEndContentType.MEDIA_PACKET, flags, plaintext),
        )
    }

    private fun keyFrame(size: Int = 4_000) = record(StreamProtocol.FLAG_KEY_FRAME, size)
    private fun base() = record(0)
    private fun enhancement() = record(StreamProtocol.FLAG_DISCARDABLE)
    private fun audio() = record(StreamProtocol.FLAG_AUDIO, 40)
    private fun config() = record(StreamProtocol.FLAG_CODEC_CONFIG, 20)

    private fun queue() = RelayChildQueue(layerThinning = true) { nowNs }

    private fun drain(queue: RelayChildQueue): List<MeshEnvelope> = generateSequence { queue.poll() }.toList()

    @Test
    fun atFullNothingIsDroppedAndAtBaseOnlyDiscardableVideoIs() {
        val q = queue()
        q.waitForKeyFrame()
        assertEquals(ChildQueueOffer.STARTED_AT_KEYFRAME, q.offer(keyFrame()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(base()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(enhancement()))
        assertTrue(q.setLayerLevel(RelayChildLevel.BASE))
        // The enhancement frame that was already queued is gone; base frames, audio and config stay.
        assertEquals(ChildQueueOffer.LAYER_THINNED, q.offer(enhancement()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(base()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(audio()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(config()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(keyFrame()))
        val flags = drain(q).map { it.flags }
        assertFalse(flags.any { it and MeshEnvelope.FLAG_DISCARDABLE != 0 })
        assertEquals(6, flags.size)
        assertTrue(q.layerStats().thinnedFrames >= 2)
        // Back to full: enhancement frames flow again, no keyframe needed.
        assertTrue(q.setLayerLevel(RelayChildLevel.FULL))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(enhancement()))
    }

    @Test
    fun theAudioLevelKeepsAudioConfigurationAndControlAndNothingElse() {
        val q = queue()
        q.waitForKeyFrame()
        q.offer(keyFrame())
        q.offer(base())
        q.offer(audio())
        q.offer(config())
        assertTrue(q.setLayerLevel(RelayChildLevel.AUDIO))
        // Queued video was purged; audio and config remain in order.
        assertEquals(listOf(StreamProtocol.FLAG_AUDIO, StreamProtocol.FLAG_CODEC_CONFIG), drain(q).map { it.flags })
        assertEquals(ChildQueueOffer.LAYER_PAUSED, q.offer(keyFrame()))
        assertEquals(ChildQueueOffer.LAYER_PAUSED, q.offer(base()))
        assertEquals(ChildQueueOffer.LAYER_PAUSED, q.offer(enhancement()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(audio()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(config()))
        // A lifecycle record (control) passes too.
        val control = MeshEnvelope(STREAM_ID, SOURCE, meshSequence++, 5, MeshEnvelope.FLAG_CONTROL, byteArrayOf(1))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(control))
        assertEquals(3, drain(q).size)
        assertEquals(3L, q.layerStats().pausedFrames - 0L)
    }

    @Test
    fun leavingTheAudioLevelWaitsForAKeyframeAndAsksForOnePaced() {
        val q = queue()
        q.waitForKeyFrame()
        q.offer(keyFrame())
        drain(q)
        q.setLayerLevel(RelayChildLevel.AUDIO)
        repeat(5) { q.offer(base()) }
        assertTrue(q.setLayerLevel(RelayChildLevel.BASE))
        // First video after the pause asks for a keyframe, then waits quietly, then asks again later.
        assertEquals(ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME, q.offer(base()))
        assertEquals(ChildQueueOffer.SHED_DROPPED, q.offer(base()))
        assertEquals(ChildQueueOffer.SHED_DROPPED, q.offer(enhancement().let { base() }))
        nowNs += BoundedMediaAge.KEY_FRAME_REREQUEST_NS
        assertEquals(ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME, q.offer(base()))
        // The keyframe ends the wait; what follows is queued and decodable from it.
        assertEquals(ChildQueueOffer.STARTED_AT_KEYFRAME, q.offer(keyFrame()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(base()))
        assertEquals(2L, q.layerStats().keyFrameRequests)
        assertEquals(listOf(true, false), drain(q).let { listOf(it[0].isKeyFrame, it[1].isKeyFrame) })
    }

    @Test
    fun withTheSwitchOffTheQueueNeverDropsForALayer() {
        val q = RelayChildQueue(layerThinning = false) { nowNs }
        assertFalse(q.setLayerLevel(RelayChildLevel.AUDIO))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(keyFrame()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(enhancement()))
        assertEquals(ChildQueueOffer.QUEUED, q.offer(base()))
        assertEquals(3, drain(q).size)
        val core = ReceiverNodeForwardingCore(node(1), node(3), STREAM_ID, SOURCE, queueCapacity = 8)
        core.addChild(node(2))
        assertFalse(core.setChildLayerLevel(node(2), RelayChildLevel.BASE))
    }

    @Test
    fun dropsForALayerNeverAskTheUpstreamForAKeyframe() {
        val core = ReceiverNodeForwardingCore(node(1), node(3), STREAM_ID, SOURCE, queueCapacity = 8, layerThinning = true)
        core.addChild(node(2))
        val start = core.receive(keyFrame())
        assertEquals(setOf(node(2)), start.queuedChildren)
        assertTrue(core.setChildLayerLevel(node(2), RelayChildLevel.BASE))
        val thinned = core.receive(enhancement())
        assertTrue(thinned.queuedChildren.isEmpty() && thinned.laggingChildren.isEmpty() && !thinned.keyFrameReRequest)
        assertTrue(core.setChildLayerLevel(node(2), RelayChildLevel.AUDIO))
        val paused = core.receive(base())
        assertTrue(paused.queuedChildren.isEmpty() && paused.laggingChildren.isEmpty() && !paused.keyFrameReRequest)
        // Audio still reaches the child while video is paused.
        assertEquals(setOf(node(2)), core.receive(audio()).queuedChildren)
        // Resuming asks for exactly the keyframe.
        assertTrue(core.setChildLayerLevel(node(2), RelayChildLevel.BASE))
        val resume = core.receive(base())
        assertTrue(resume.keyFrameReRequest)
        assertEquals(setOf(node(2)), resume.laggingChildren)
        val stats = core.childLayerStats(node(2))!!
        assertEquals(RelayChildLevel.BASE, stats.level)
        assertTrue(stats.hasAudio)
    }

    // ---- end-to-end acceptance and decodability ----

    private data class Frame(val id: Int, val layer: Int, val key: Boolean)

    /** A reference-chain decoder: a key frame resets; T0 refers to the last T0; T1 refers to the last T0. */
    private class ModelDecoder {
        var lastBase = -1
        val shown = mutableListOf<Int>()
        var undecodable = 0

        fun decode(frame: Frame) {
            if (frame.key) {
                lastBase = frame.id
                shown += frame.id
                return
            }
            if (lastBase < 0) {
                undecodable++
                return
            }
            shown += frame.id
            if (frame.layer == 0) lastBase = frame.id
        }
    }

    /** Source stream: key frame, then T0 T1 T0 T1 ... with audio between; returns (frames by sequence, envelopes). */
    private fun sourceStream(count: Int): Pair<Map<Long, Frame>, List<MeshEnvelope>> {
        val frames = mutableMapOf<Long, Frame>()
        val envelopes = mutableListOf<MeshEnvelope>()
        fun add(envelope: MeshEnvelope, frame: Frame?) {
            envelopes += envelope
            if (frame != null) frames[envelope.sequence] = frame
        }
        add(config(), null)
        add(keyFrame(), Frame(0, 0, true))
        for (i in 1 until count) {
            add(audio(), null)
            if (i % 2 == 1) add(base(), Frame(i, 0, false)) else add(enhancement(), Frame(i, 1, false))
        }
        return frames to envelopes
    }

    private fun childReceive(opener: EndToEndContentOpener, envelope: MeshEnvelope): StreamProtocol.Packet {
        val content = opener.open(envelope.payload)
        return EndToEndStreamProtocol.decodePacket(content)
    }

    @Test
    fun theBaseLayerIsAcceptedAndAuthenticatedByTheChildAndEveryFrameItGetsDecodes() {
        val (frames, envelopes) = sourceStream(41)
        val q = queue()
        q.waitForKeyFrame()
        envelopes.take(2).forEach { q.offer(it) }
        q.setLayerLevel(RelayChildLevel.BASE)
        envelopes.drop(2).forEach { q.offer(it) }
        val opener = EndToEndContentOpener(key)
        val decoder = ModelDecoder()
        var audioPackets = 0
        for (envelope in drain(q)) {
            // Sequence gaps (the dropped T1 records) are accepted; flags are bound to the sealed header.
            val packet = childReceive(opener, envelope)
            assertEquals(envelope.flags, meshPacketFlags(packet.flags))
            if (packet.flags and StreamProtocol.FLAG_AUDIO != 0) audioPackets++
            frames[envelope.sequence]?.let(decoder::decode)
        }
        assertEquals(0, decoder.undecodable)
        // Half the inter frames (the T0 ones) plus the key frame: 1 + 20; all 40 audio packets survive.
        assertEquals(21, decoder.shown.size)
        assertTrue(decoder.shown.drop(1).all { frames.values.first { f -> f.id == it }.layer == 0 })
        assertEquals(40, audioPackets)
    }

    @Test
    fun goingBackToFullKeepsEveryLaterFrameDecodable() {
        val (frames, envelopes) = sourceStream(41)
        val q = queue()
        q.waitForKeyFrame()
        val opener = EndToEndContentOpener(key)
        val decoder = ModelDecoder()
        envelopes.take(2).forEach { q.offer(it) }
        q.setLayerLevel(RelayChildLevel.BASE)
        envelopes.subList(2, 42).forEach { q.offer(it) }
        q.setLayerLevel(RelayChildLevel.FULL)
        envelopes.drop(42).forEach { q.offer(it) }
        drain(q).forEach { frames[it.sequence]?.let(decoder::decode); childReceive(opener, it) }
        assertEquals(0, decoder.undecodable)
        assertTrue(decoder.shown.size > 21)
    }

    @Test
    fun anAudioOnlyPauseAndAKeyframeResumeLeaveAnAcceptedStreamAndAReplayStillFails() {
        val q = queue()
        q.waitForKeyFrame()
        val opener = EndToEndContentOpener(key)
        val received = mutableListOf<MeshEnvelope>()
        q.offer(config())
        q.offer(keyFrame())
        repeat(4) { q.offer(base()); q.offer(audio()) }
        received += drain(q)
        q.setLayerLevel(RelayChildLevel.AUDIO)
        repeat(30) { q.offer(base()); q.offer(enhancement()); q.offer(audio()) }
        received += drain(q)
        assertTrue(received.takeLast(30).all { it.isAudio })
        q.setLayerLevel(RelayChildLevel.BASE)
        q.offer(base()) // waits: the child has no reference for it
        q.offer(keyFrame())
        q.offer(base())
        q.offer(enhancement())
        received += drain(q)
        var last: StreamProtocol.Packet? = null
        received.forEach { last = childReceive(opener, it) }
        assertNotNull(last)
        // The resumed video starts at a keyframe, with a large gap behind it.
        val resumed = received.takeLast(2)
        assertTrue(resumed[0].isKeyFrame && !resumed[1].isKeyFrame)
        // A replayed or reordered record is still refused.
        assertThrows(IllegalArgumentException::class.java) { childReceive(opener, received.last()) }
        assertThrows(IllegalArgumentException::class.java) { childReceive(opener, received[3]) }
    }

    @Test
    fun aRelayCannotMakeTheChildAcceptAnAlteredFlag() {
        val frame = enhancement()
        val opener = EndToEndContentOpener(key)
        // The discardable bit lives in the sealed header: flipping it breaks the record.
        val altered = frame.copy(payload = frame.payload.copyOf().also { it[13] = (it[13].toInt() and 0x7f).toByte() })
        assertThrows(Exception::class.java) { opener.open(altered.payload) }
    }

    // ---- controller and viewer ----

    @Test
    fun theControllerStepsAChokedChildDownToAudioAndLogsEachChange() {
        var level = RelayChildLevel.FULL
        var offeredFull = 0L
        var offeredBase = 0L
        var delivered = 0L
        var stats = RelayChildLayerStats(level, 0, 0, 40_000, true, true, false, 0, 0, 0, 0)
        val controller = RelayChildLayerController(
            readStats = { stats.copy(level = level, offeredFullBytes = offeredFull, offeredBaseBytes = offeredBase) },
            applyLevel = { level = it },
        )
        val lines = mutableListOf<String>()
        var t = 0L
        // 300 kbps offered (160 kbps at base) into a 60 kbps link: the queue ages 700 ms per second.
        var age = 0L
        while (t <= 20_000 && level != RelayChildLevel.AUDIO) {
            offeredFull += 37_500 / 2
            offeredBase += if (level == RelayChildLevel.FULL) 20_000 / 2 else 20_000 / 2
            delivered += 7_500 / 2
            age = (age + 350).coerceAtMost(3_000)
            val report = controller.onTick(t, delivered, age, childLate = age > 700)!!
            if (report.decision.changed) {
                lines += relayChildLayerLine("abcd", "change", report)
                age = 0 // the purge empties the queue
            }
            t += 500
        }
        assertEquals(RelayChildLevel.AUDIO, level)
        assertTrue(lines.size >= 1)
        assertTrue(lines.last().startsWith("BENCHMARK relay_child_layer: child=abcd | level=audio | reason="))
        assertTrue(lines.last().contains("queueAgeMs=") && lines.last().contains("deliveredKbps="))
    }

    @Test
    fun theViewerShowsTheNoticeOnlyWhileAudioArrivesWithoutVideo() {
        val watch = WeakLinkPauseWatch()
        // Before any video nothing is claimed (the stream may just be starting).
        assertNull(watch.onAudio(0))
        assertNull(watch.onVideo(100))
        for (t in 200..2_800 step 100) assertNull(watch.onAudio(t.toLong()))
        // 3 s without video while audio keeps coming.
        assertEquals(true, watch.onAudio(3_200))
        assertTrue(watch.paused)
        // One video record clears it.
        assertEquals(false, watch.onVideo(3_300))
        // A source that paused or turned its camera off is not a weak link.
        assertNull(watch.setSourceVideoOff(true, 3_400))
        for (t in 3_500..9_000 step 100) assertNull(watch.onAudio(t.toLong()))
        assertNull(watch.setSourceVideoOff(false, 9_100))
        // No audio either (the whole link is gone): not this notice.
        assertNull(watch.onAudio(9_200))
        assertEquals(false, watch.paused)
    }

    @Test
    fun theNoticeTextIsTheOneTheChildShows() {
        assertEquals("Video paused: weak link", WEAK_LINK_PAUSE_TEXT)
    }

    private fun node(value: Int): NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { value.toByte() })

    private companion object {
        val STREAM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000077")
        val SOURCE: NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 9 })
    }
}
