package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 3 of Design AL: relay forwarding of an audio-only stream, shedding and verdicts, admission, focus, markers. */
class AudioLiveRelayTest {
    private val streamId = UUID.fromString("c7da2c06-e0de-4d7b-98ed-4916c2f208a3")
    private val identity = KeyPairSigningIdentity(generateEcKeyPair())
    private val source = identity.nodeId
    private val local = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (50 + it).toByte() })
    private val upstream = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (80 + it).toByte() })
    private val child = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (110 + it).toByte() })
    private val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 41).toByte() }
    private val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }

    private fun frame(): ByteArray = ByteArray(60) { 3 }.also { it[0] = 0xf8.toByte() }

    private val opusHead = ByteArray(19).also {
        "OpusHead".forEachIndexed { index, c -> it[index] = c.code.toByte() }
        it[8] = 1
        it[9] = 1
    }

    private fun mesh(sealer: EndToEndContentSealer, sequence: Long, type: EndToEndContentType, flags: Int, plaintext: ByteArray, meshFlags: Int = flags) =
        MeshEnvelope(streamId, source, sequence, 5, meshFlags, sealer.seal(type, flags, plaintext))

    // --- Joining child: no keyframe wait, bootstrap has the header and configuration ----------------------------

    @Test
    fun anAudioOnlyChildJoinsAtTheNextRecordWithTheCachedHeaderAndConfiguration() {
        val signer = AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE)
        val sealer = EndToEndContentSealer(mediaKey)
        val core = ReceiverNodeForwardingCore(local, upstream, streamId, source, 16, audioLive = AudioLiveRelaySettings())
        val header = mesh(sealer, 0, EndToEndContentType.STREAM_HEADER, 0, signer.headerBytes)
        val configRecord = signer.sign(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, opusHead)
        val config = mesh(
            sealer, 1, EndToEndContentType.MEDIA_PACKET, configRecord.flags,
            EndToEndStreamProtocol.encodePacket(configRecord.flags, 0, configRecord.payload),
        )
        // Records arrive before any child exists: nothing to queue them for.
        listOf(header, config).forEach { assertEquals(RelayRejection.NO_CHILDREN, core.receive(it).rejection) }
        val earlier = signer.sign(StreamProtocol.FLAG_AUDIO, 0, frame())
        core.receive(
            mesh(
                sealer, 2, EndToEndContentType.MEDIA_PACKET, earlier.flags,
                EndToEndStreamProtocol.encodePacket(earlier.flags, 0, earlier.payload),
            ),
        )

        val bootstrap = core.addChild(child)
        assertEquals(listOf(0L, 1L), bootstrap.map(MeshEnvelope::sequence))

        // The very next audio record is queued for the child at once: no WAITING_FOR_JOIN_KEYFRAME.
        val live = signer.sign(StreamProtocol.FLAG_AUDIO, 20_000, frame())
        val result = core.receive(
            mesh(
                sealer, 3, EndToEndContentType.MEDIA_PACKET, live.flags,
                EndToEndStreamProtocol.encodePacket(live.flags, 20_000, live.payload),
            ),
        )
        assertEquals(setOf(child), result.queuedChildren)
        assertTrue(result.laggingChildren.isEmpty())
        assertFalse(result.keyFrameReRequest)
        assertEquals(3L, requireNotNull(core.take(child)).sequence)
        sealer.close()
    }

    // --- Shedding with the exact relay predicate -------------------------------------------------------------------

    private class Clock { var ns = 1_000_000_000_000L }

    private fun audioQueue(clock: Clock, ageBound: Boolean = true) =
        RelayChildQueue(audioLive = AudioLiveRelayQueue(ageBound), nanoTime = { clock.ns })

    private fun envelope(sequence: Long, meshFlags: Int) =
        MeshEnvelope(streamId, source, sequence, 5, meshFlags, ByteArray(40))

    private val audioBatch = MeshEnvelope.FLAG_AUDIO or AudioLiveShed.MESH_AUDIO_RECORD_BIT

    @Test
    fun theRelaySheddsOnlyStaleWholeAudioRecordsAndKeepsControlAndConfiguration() {
        val clock = Clock()
        val queue = audioQueue(clock)
        queue.offer(envelope(1, MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_CODEC_CONFIG))
        queue.offer(envelope(2, MeshEnvelope.FLAG_CONTROL)) // PAUSED marker
        var sequence = 3L
        repeat(20) { // 200 ms records, one every 200 ms for 4 s: the child never drains
            queue.offer(envelope(sequence++, audioBatch))
            clock.ns += 200_000_000L
        }
        queue.offer(envelope(sequence++, MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_CONTROL)) // never matches
        val kept = generateSequence { queue.poll() }.toList()
        // Config, control and the odd control+audio envelope survive; stale audio is gone, the newest ~1.5 s stays.
        assertEquals(1L, kept[0].sequence)
        assertEquals(2L, kept[1].sequence)
        assertTrue(kept.any { it.flags == MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_CONTROL })
        val audioKept = kept.filter { it.flags == audioBatch }
        assertTrue("kept ${audioKept.size}", audioKept.size in 8..12)
        assertEquals(22L, audioKept.last().sequence)
        assertTrue(queue.audioShedRecords() > 0)
    }

    @Test
    fun theControlArmNeverSheds() {
        val clock = Clock()
        val queue = audioQueue(clock, ageBound = false)
        repeat(40) {
            queue.offer(envelope(it.toLong(), audioBatch))
            clock.ns += 200_000_000L
        }
        assertEquals(40, queue.size())
        assertEquals(0L, queue.audioShedRecords())
    }

    @Test
    fun aRelayWithdrawsAChildThatKeepsGettingShedOrFallsThirtySecondsBehind() {
        val stalled = Clock()
        val queue = audioQueue(stalled)
        var withdrawnAfterMs: Long? = null
        var sequence = 0L
        for (step in 0 until 150) { // 30 s of a child that never reads
            queue.offer(envelope(sequence++, audioBatch))
            stalled.ns += 200_000_000L
            val verdict = queue.audioVerdict(stalled.ns / 1_000_000L)
            if (verdict != null && withdrawnAfterMs == null) {
                withdrawnAfterMs = step * 200L
                assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, verdict)
            }
        }
        assertNotNull(withdrawnAfterMs)
        assertTrue("withdrawn after $withdrawnAfterMs ms", withdrawnAfterMs!! < 10_000)

        // Control arm: nothing shed, so only the 30 s age rule applies.
        val clock = Clock()
        val unbounded = audioQueue(clock, ageBound = false)
        unbounded.offer(envelope(1, audioBatch))
        clock.ns += 29_000_000_000L
        assertNull(unbounded.audioVerdict(clock.ns / 1_000_000L))
        clock.ns += 2_000_000_000L
        assertEquals(AudioLiveChildHealth.Reason.QUEUE_AGE, unbounded.audioVerdict(clock.ns / 1_000_000L))
    }

    // --- Relay admission, trickle floor, marker agreement --------------------------------------------------------------

    @Test
    fun relayAdmissionAndTheTrickleFloorFollowThePresetNotTheAdvertisedRate() {
        val voice = AudioLiveRelayAdmission.perLinkKbps(AudioLive.PRESET_VOICE)
        assertEquals(41.4, voice, 0.1)
        assertTrue(AudioLiveRelayAdmission.fits(83.0, AudioLive.PRESET_VOICE))
        assertFalse(AudioLiveRelayAdmission.fits(65.0, AudioLive.PRESET_VOICE)) // the weak pair
        assertTrue(AudioLiveRelayAdmission.fits(65.0, AudioLive.PRESET_LOW_DATA))
        assertFalse(AudioLiveRelayAdmission.fits(60.0, AudioLive.PRESET_LOW_DATA))
        assertFalse(AudioLiveRelayAdmission.fits(130.0, AudioLive.PRESET_MUSIC))
        assertTrue(AudioLiveRelayAdmission.fits(430.0, AudioLive.PRESET_MUSIC))
        // The trickle test judges a window under 1/8 of the wire rate (35 kbps for Voice) as starved.
        val voiceRate = AudioLiveRelayAdmission.trickleRateKbps(AudioLive.PRESET_VOICE)
        assertEquals(35, voiceRate)
        val trickle = RelayParentTrickle(voiceRate, 0L)
        assertNull(trickle.onTick(PARENT_SILENCE_NS, 3_750, paused = false)) // 6 kbps over 5 s: above the floor of 4
        val starved = RelayParentTrickle(voiceRate, 0L)
        assertEquals(2, starved.onTick(PARENT_SILENCE_NS, 1_250, paused = false)) // 2 kbps
    }

    @Test
    fun aMarkerThatDisagreesWithTheHeaderFailsTheLinkWithAScopedException() {
        val audioHeader = EndToEndStreamProtocol.decodeHeader(
            AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE).headerBytes, allowAudioOnly = true,
        )
        val videoHeader = StreamProtocol.Header(426, 240, 15, StreamProtocol.CODEC_VP9, 0, 1, 48_000, 1)
        requireAudioLiveMarkerAgreement(true, audioHeader)
        requireAudioLiveMarkerAgreement(false, videoHeader)
        assertTrue(assertThrows(AudioLiveMarkerMismatchException::class.java) {
            requireAudioLiveMarkerAgreement(true, videoHeader)
        }.offerMarkedAudioOnly)
        assertFalse(assertThrows(AudioLiveMarkerMismatchException::class.java) {
            requireAudioLiveMarkerAgreement(false, audioHeader)
        }.offerMarkedAudioOnly)
    }

    // --- Audio focus -----------------------------------------------------------------------------------------------

    @Test
    fun focusLossMutesPlayoutAndAPermanentLossLeavesAfterSixtySeconds() {
        var now = 0L
        val policy = AudioLiveFocusPolicy { now }
        assertEquals(AudioLiveFocusPolicy.Verdict(false, false), policy.verdict())
        // A phone call or a media app takes focus for a while: mute, keep the link, resume on gain.
        assertEquals(
            AudioLiveFocusPolicy.Verdict(true, false),
            policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_LOSS_TRANSIENT),
        )
        now += 600_000
        assertEquals(AudioLiveFocusPolicy.Verdict(true, false), policy.verdict())
        assertEquals(AudioLiveFocusPolicy.Verdict(false, false), policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_GAIN))
        // Ducking requests mute too (a radio announcement is not played quietly under another app).
        assertTrue(policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_LOSS_TRANSIENT_CAN_DUCK).outputMuted)
        policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_GAIN)
        // Permanent loss: muted at once, the link is kept for 60 s, then the listener leaves.
        assertEquals(AudioLiveFocusPolicy.Verdict(true, false), policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_LOSS))
        now += 59_999
        assertFalse(policy.verdict().leave)
        now += 1
        assertEquals(AudioLiveFocusPolicy.Verdict(true, true), policy.verdict())
        // Regaining focus before the deadline cancels the exit.
        val regain = AudioLiveFocusPolicy { now }
        regain.onFocusChange(AudioLiveFocusPolicy.FOCUS_LOSS)
        now += 30_000
        assertEquals(AudioLiveFocusPolicy.Verdict(false, false), regain.onFocusChange(AudioLiveFocusPolicy.FOCUS_GAIN))
        now += 120_000
        assertFalse(regain.verdict().leave)
    }

    @Test
    fun theListenersOwnMuteSurvivesFocusChanges() {
        val policy = AudioLiveFocusPolicy { 0L }
        policy.setUserMuted(true)
        assertTrue(policy.verdict().outputMuted)
        assertTrue(policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_GAIN).outputMuted)
        policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_LOSS_TRANSIENT)
        assertTrue(policy.onFocusChange(AudioLiveFocusPolicy.FOCUS_GAIN).outputMuted)
        policy.setUserMuted(false)
        assertFalse(policy.verdict().outputMuted)
    }

    // --- The signed stream through a relay to a listener ----------------------------------------------------------

    @Test
    fun aRelayCopiesSignedRecordsUnchangedAndTheListenerVerifiesThem() {
        val signer = AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE)
        // Source to relay.
        val sourceBytes = ByteArrayOutputStream()
        val sourceLink = SecureRecordWriter(sourceBytes, linkKey)
        val writer = EndToEndStreamWriter(sourceLink, EndToEndContentSealer(mediaKey), source, true, meshStreamId = streamId)
        writer.writeAudioLiveHeader(signer)
        writer.writeAudioLiveRecord(signer.sign(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, opusHead))
        val units = List(10) { StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, it * 20_000L, frame()) }
        val batch = StreamProtocol.batchAudioPackets(units)
        writer.writeAudioLiveRecord(signer.sign(batch.flags, batch.presentationTimeUs, batch.payload))
        sourceLink.flush()

        // The relay's own reader verifies for local playback and hands every envelope to the forwarding core.
        val core = ReceiverNodeForwardingCore(local, upstream, streamId, source, 16, audioLive = AudioLiveRelaySettings())
        val bootstrap = core.addChild(child)
        assertTrue(bootstrap.isEmpty())
        val relayReader = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(sourceBytes.toByteArray()), linkKey),
            EndToEndContentOpener(mediaKey), source,
        ) { envelope -> core.receive(envelope) }
        assertTrue(relayReader.readHeader(allowAudioOnly = true).isAudioOnly)
        relayReader.readStreamContent() // configuration
        repeat(10) { assertNotNull(relayReader.readStreamContent()) }

        // Relay to the listener: the sealed envelopes are copied, nothing is re-sealed.
        val childBytes = ByteArrayOutputStream()
        val childLink = SecureRecordWriter(childBytes, linkKey)
        while (true) {
            val envelope = core.queueDepth(child).takeIf { it > 0 }?.let { core.take(child) } ?: break
            childLink.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
        }
        childLink.flush()
        val listener = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(childBytes.toByteArray()), linkKey),
            EndToEndContentOpener(mediaKey), source,
        )
        assertEquals(AudioLive.PRESET_VOICE, listener.readHeader(allowAudioOnly = true).audioLivePreset)
        val config = listener.readStreamContent() as ReceivedStreamContent.Media
        assertArrayEquals(opusHead, config.packet.payload)
        val heard = (0 until 10).map { (listener.readStreamContent() as ReceivedStreamContent.Media).packet }
        assertEquals(List(10) { it * 20_000L }, heard.map { it.presentationTimeUs })
    }
}
