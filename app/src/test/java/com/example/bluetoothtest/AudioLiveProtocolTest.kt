package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 1 of Design AL: offer marker, BTL1 header and signed records, fail-closed media rules, old-peer
 * behaviour, planning arithmetic, and the review fixes 1, 12-14, 17 and 18.
 */
class AudioLiveProtocolTest {
    private val prefix = ByteArray(6) { (it + 1).toByte() }
    private val streamId = UUID.fromString("2a24730d-e89c-4ba6-b18d-e494a5fa69d2")
    private val identity = KeyPairSigningIdentity(generateEcKeyPair())

    private fun offer(
        summary: Int,
        flags: Int = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
        token: Int = 0x40000001,
        width: Int = 0,
        height: Int = 0,
        fps: Int = 0,
        kbps: Int = 24,
    ) = DiscoveryAnnouncement(
        nodeIdPrefix = prefix,
        flags = flags,
        capabilitySummary = summary,
        streamToken = token,
        routeCost = 0,
        sequence = 0,
        streamWidth = width,
        streamHeight = height,
        streamFps = fps,
        streamBitrateKbps = kbps,
    )

    private fun signer(preset: Int = AudioLive.PRESET_VOICE, id: UUID = streamId, who: SigningIdentity = identity) =
        AudioLiveSigner(who, id, preset)

    private fun header(signer: AudioLiveSigner) =
        EndToEndStreamProtocol.decodeHeader(signer.headerBytes, allowAudioOnly = true)

    private fun verifier(signer: AudioLiveSigner, expected: NodeId = identity.nodeId, id: UUID = streamId) =
        AudioLiveVerifier.fromHeader(header(signer), id, expected)

    private fun guard(signer: AudioLiveSigner, clock: () -> Long = { 0L }) =
        AudioLiveStreamGuard(verifier(signer), clock)

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    /** A valid CELT 20 ms mono code-0 frame (TOC 0xF8 = config 31, mono, one frame). */
    private fun frame(size: Int = 60): ByteArray = ByteArray(size) { 1 }.also { it[0] = 0xf8.toByte() }

    private val opusHead = ByteArray(19).also {
        "OpusHead".forEachIndexed { index, c -> it[index] = c.code.toByte() }
        it[8] = 1
        it[9] = 1
        it[10] = 0x38
        it[11] = 0x01
    }

    private fun config(s: AudioLiveSigner, head: ByteArray = opusHead) =
        s.sign(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, head)

    /** One signed record of [units] 20 ms frames starting at [startUnit] (units since stream start). */
    private fun record(s: AudioLiveSigner, startUnit: Int, units: Int = 10, make: (Int) -> ByteArray = { frame() }): AudioLiveSignedRecord {
        val start = startUnit * AudioLive.FRAME_US
        val packets = List(units) { StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, start + it * AudioLive.FRAME_US, make(it)) }
        return when (units) {
            1 -> s.sign(StreamProtocol.FLAG_AUDIO, start, packets[0].payload)
            2 -> StreamProtocol.pairAudioPackets(packets[0], packets[1]).let { s.sign(it.flags, it.presentationTimeUs, it.payload) }
            else -> StreamProtocol.batchAudioPackets(packets).let { s.sign(it.flags, it.presentationTimeUs, it.payload) }
        }
    }

    private fun asPacket(r: AudioLiveSignedRecord) = StreamProtocol.Packet(r.flags, r.presentationTimeUs, r.payload)

    // --- BTL1 header -----------------------------------------------------------------------------------

    @Test
    fun fixedHeaderVectorsMatchProtocolTable() {
        assertEquals("42 54 4c 31 01 bb 80 01 14 02 00", hex(AudioLive.fixedHeader(AudioLive.PRESET_VOICE)))
        assertEquals("42 54 4c 31 01 bb 80 01 14 01 00", hex(AudioLive.fixedHeader(AudioLive.PRESET_LOW_DATA)))
        assertEquals("42 54 4c 31 01 bb 80 01 14 03 00", hex(AudioLive.fixedHeader(AudioLive.PRESET_MUSIC)))
        assertEquals(AudioLive.FIXED_HEADER_BYTES, AudioLive.fixedHeader(2).size)
        // The header payload starts with the fixed bytes and then three length-prefixed blocks.
        val bytes = signer().headerBytes
        assertArrayEquals(AudioLive.fixedHeader(2), bytes.copyOf(11))
        assertEquals(91, ((bytes[11].toInt() and 0xff) shl 8) or (bytes[12].toInt() and 0xff))
    }

    @Test
    fun headerRoundTripsAndCarriesNoVideoFields() {
        for (preset in 1..3) {
            val h = header(signer(preset))
            assertTrue(h.isAudioOnly)
            assertEquals(preset, h.audioLivePreset)
            assertEquals(0, h.width)
            assertEquals(0, h.height)
            assertEquals(0, h.fps)
            assertEquals(0, h.codecId)
            assertTrue(h.hasAudio)
            assertEquals(StreamProtocol.AUDIO_SAMPLE_RATE, h.audioSampleRate)
            assertEquals(1, h.audioChannelCount)
            assertEquals(91, requireNotNull(h.audioLiveAuth).ephemeralSpki.size)
        }
        assertEquals(16, AudioLive.presetKbps(1))
        assertEquals(24, AudioLive.presetKbps(2))
        assertEquals(48, AudioLive.presetKbps(3))
    }

    @Test
    fun malformedHeadersFailClosed() {
        val voice = signer().headerBytes
        fun mutated(index: Int, value: Int) = voice.copyOf().also { it[index] = value.toByte() }
        val bad = listOf(
            "codec 2" to mutated(4, 2),
            "rate 44100" to voice.copyOf().also { it[5] = 0xac.toByte(); it[6] = 0x44 },
            "stereo" to mutated(7, 2),
            "frame 40 ms" to mutated(8, 40),
            "preset 0" to mutated(9, 0),
            "preset 4" to mutated(9, 4),
            "reserved 1" to mutated(10, 1),
            "unknown magic" to mutated(3, '9'.code),
            "key length 0" to voice.copyOf().also { it[11] = 0; it[12] = 0 },
            "key length huge" to voice.copyOf().also { it[11] = 0x7f },
        )
        for ((name, bytes) in bad) {
            assertThrows(name, Exception::class.java) {
                StreamProtocol.readHeader(ByteArrayInputStream(bytes), allowAudioOnly = true)
            }
        }
        assertThrows(Exception::class.java) {
            StreamProtocol.readHeader(ByteArrayInputStream(voice.copyOf(40)), allowAudioOnly = true)
        }
        assertThrows(IllegalArgumentException::class.java) { AudioLive.fixedHeader(0) }
        assertThrows(IllegalArgumentException::class.java) { AudioLive.fixedHeader(4) }
    }

    @Test
    fun trailingByteAfterTheHeaderFailsTheEndToEndDecode() {
        val voice = signer().headerBytes
        assertTrue(EndToEndStreamProtocol.decodeHeader(voice, allowAudioOnly = true).isAudioOnly)
        assertThrows(IllegalArgumentException::class.java) {
            EndToEndStreamProtocol.decodeHeader(voice + 0, allowAudioOnly = true)
        }
    }

    @Test
    fun readersThatDoNotAllowAudioOnlyStopAtTheHeaderLikeABuildWithoutTheFeature() {
        val voice = signer().headerBytes
        val oldReader = assertThrows(IllegalArgumentException::class.java) {
            StreamProtocol.readHeader(ByteArrayInputStream(voice))
        }
        assertEquals("Unsupported stream header BTL1", oldReader.message)
        val endToEnd = assertThrows(IllegalArgumentException::class.java) {
            EndToEndStreamProtocol.decodeHeader(voice)
        }
        assertEquals("Unsupported stream header BTL1", endToEnd.message)
    }

    @Test
    fun videoHeaderIsUnchangedWhenAudioOnlyIsAllowed() {
        val video = ByteArrayOutputStream().also {
            StreamProtocol.writeHeader(it, StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 90, true)
        }.toByteArray()
        val old = StreamProtocol.readHeader(ByteArrayInputStream(video))
        val aware = StreamProtocol.readHeader(ByteArrayInputStream(video), allowAudioOnly = true)
        assertEquals(old, aware)
        assertFalse(aware.isAudioOnly)
    }

    // --- Review 1: the source signs, so a media-key holder cannot forge -------------------------------------

    @Test
    fun aVerifierAcceptsTheSourceAndBindsTheCertificateToStreamAndSource() {
        val s = signer()
        val v = verifier(s)
        assertEquals(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, v.open(asPacket(config(s))).packet.flags)
        val opened = v.open(asPacket(record(s, 0)))
        assertEquals(10, StreamProtocol.splitAudioPair(opened.packet).size)
        // The certificate does not move to another stream ID or another expected source.
        assertThrows(IllegalArgumentException::class.java) { verifier(s, id = UUID(1, 2)) }
        val stranger = KeyPairSigningIdentity(generateEcKeyPair())
        assertThrows(IllegalArgumentException::class.java) { verifier(s, expected = stranger.nodeId) }
        // A header certified by a key that is not the expected source's, even if self-consistent, is refused.
        val forgedHeader = signer(who = stranger)
        assertThrows(IllegalArgumentException::class.java) { verifier(forgedHeader) }
        // A relay that swaps the certificate for its own key signs a header that names itself.
        assertEquals(stranger.nodeId, verifier(forgedHeader, expected = stranger.nodeId).let { stranger.nodeId })
    }

    @Test
    fun aRelayHoldingTheMediaKeyCannotForgeEditOrReplayRecords() {
        val s = signer()
        val v = verifier(s)
        v.open(asPacket(config(s)))
        val first = record(s, 0)
        v.open(asPacket(first))
        // Forged audio signed with another key (a forwarder knows only the media key).
        val attacker = signer(who = KeyPairSigningIdentity(generateEcKeyPair()))
        val forged = asPacket(record(attacker, 10))
        assertThrows(IllegalArgumentException::class.java) { v.open(forged) }
        // Edited payload, edited timestamp, edited flags.
        val next = record(s, 10)
        val edited = next.payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { v.open(StreamProtocol.Packet(next.flags, next.presentationTimeUs, edited)) }
        assertThrows(IllegalArgumentException::class.java) { v.open(StreamProtocol.Packet(next.flags, next.presentationTimeUs + 20_000, next.payload)) }
        assertThrows(IllegalArgumentException::class.java) {
            v.open(StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, next.presentationTimeUs, next.payload))
        }
        // Unsigned / truncated.
        assertThrows(IllegalArgumentException::class.java) { v.open(StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 0, ByteArray(30))) }
        // Replay of an earlier record and reordering fail the strictly increasing rule; gaps are fine.
        assertThrows(IllegalArgumentException::class.java) { v.open(asPacket(first)) }
        v.open(asPacket(record(s, 40)))
        assertThrows(IllegalArgumentException::class.java) { v.open(asPacket(next)) }
        // Another stream's record (same key, different stream ID in the signature) does not verify here.
        val otherStream = signer(id = UUID(9, 9))
        val foreign = asPacket(record(otherStream, 0))
        assertThrows(IllegalArgumentException::class.java) { verifier(s).open(foreign) }
    }

    @Test
    fun rawSignaturesRoundTripThroughDer() {
        repeat(40) {
            val pair = generateEcKeyPair()
            val message = ByteArray(50) { (it + 3).toByte() }
            val raw = EcdsaRaw.sign(pair.private, message)
            assertEquals(64, raw.size)
            assertTrue(EcdsaRaw.verify(pair.public, message, raw))
            assertFalse(EcdsaRaw.verify(pair.public, message + 1, raw))
            assertFalse(EcdsaRaw.verify(pair.public, message, raw.copyOf(63)))
        }
    }

    @Test
    fun guardRejectsVideoRotationKeyframeIntegrityAndDiscontinuityFlagsBeforeVerifying() {
        val g = guard(signer())
        val rejected = listOf(
            0,
            StreamProtocol.FLAG_KEY_FRAME,
            StreamProtocol.FLAG_DISPLAY_ROTATION,
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_KEY_FRAME,
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DISPLAY_ROTATION,
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DEBUG_INTEGRITY,
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DISCONTINUITY,
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DISCARDABLE,
            StreamProtocol.FLAG_CODEC_CONFIG,
        )
        for (flags in rejected) {
            assertThrows("flags $flags", IllegalArgumentException::class.java) {
                g.open(StreamProtocol.Packet(flags, 0, ByteArray(100) { 1 }))
            }
        }
    }

    // --- Review 14: exactly one 20 ms mono Opus frame, checked before any decoder --------------------------

    @Test
    fun opusPacketsMustBeExactlyOneTwentyMsMonoFrame() {
        fun toc(config: Int, stereo: Boolean = false, code: Int = 0) =
            byteArrayOf(((config shl 3) or (if (stereo) 4 else 0) or code).toByte(), 1, 2, 3)
        listOf(1, 5, 9, 13, 15, 19, 23, 27, 31).forEach {
            assertTrue("config $it", AudioLiveOpus.isSingle20msMonoFrame(toc(it)))
        }
        // Other durations: SILK 10/40/60, hybrid 10, CELT 2.5/5/10.
        listOf(0, 2, 3, 4, 6, 7, 8, 10, 11, 12, 14, 16, 17, 18, 20, 21, 22, 24, 25, 26, 28, 29, 30).forEach {
            assertFalse("config $it", AudioLiveOpus.isSingle20msMonoFrame(toc(it)))
        }
        assertFalse(AudioLiveOpus.isSingle20msMonoFrame(toc(31, stereo = true)))
        assertFalse(AudioLiveOpus.isSingle20msMonoFrame(toc(31, code = 1)))
        assertFalse(AudioLiveOpus.isSingle20msMonoFrame(toc(31, code = 2)))
        assertFalse(AudioLiveOpus.isSingle20msMonoFrame(toc(31, code = 3)))
        assertFalse(AudioLiveOpus.isSingle20msMonoFrame(ByteArray(0)))
        assertTrue(AudioLiveOpus.isSingle20msMonoFrame(frame(1275)))
        assertFalse(AudioLiveOpus.isSingle20msMonoFrame(frame(1276)))
    }

    @Test
    fun theGuardRejectsMultiFrameOtherDurationAndStereoPacketsBeforeADecoder() {
        val s = signer()
        val g = guard(s)
        g.open(asPacket(config(s)))
        val twoFrames = frame().also { it[0] = ((31 shl 3) or 1).toByte() }
        val tenMs = frame().also { it[0] = (30 shl 3).toByte() }
        val stereo = frame().also { it[0] = ((31 shl 3) or 4).toByte() }
        for (bad in listOf(twoFrames, tenMs, stereo)) {
            assertThrows(IllegalArgumentException::class.java) {
                guard(s).also { it.open(asPacket(config(s))) }.open(asPacket(record(s, 0, units = 3) { bad }))
            }
        }
        // A stereo or non-OpusHead configuration is refused.
        val stereoHead = opusHead.copyOf().also { it[9] = 2 }
        assertThrows(IllegalArgumentException::class.java) { guard(s).open(asPacket(config(s, stereoHead))) }
        assertThrows(IllegalArgumentException::class.java) { guard(s).open(asPacket(config(s, ByteArray(19)))) }
    }

    @Test
    fun theGuardChecksBatchLengthsAndContiguity() {
        val s = signer()
        val g = guard(s)
        g.open(asPacket(config(s)))
        // 1, 2, 3 and 20 unit records are valid.
        var unit = 0
        for (n in listOf(1, 2, 3, 20)) {
            g.open(asPacket(record(s, unit, n)))
            unit += n
        }
        // Units must be contiguous 20 ms steps: a record whose units are 25 ms apart is refused.
        val packets = List(5) { StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, unit * 20_000L + it * 25_000L, frame()) }
        val batch = StreamProtocol.batchAudioPackets(packets)
        val signed = s.sign(batch.flags, batch.presentationTimeUs, batch.payload)
        assertThrows(IllegalArgumentException::class.java) { g.open(asPacket(signed)) }
    }

    // --- Review 12: gap, resume and burst are validated separately ----------------------------------------

    @Test
    fun validSheddingDoesNotTriggerTheClockRules() {
        var now = 0L
        val s = signer()
        val g = guard(s) { now }
        g.open(asPacket(config(s)))
        var unit = 0
        // 25 records of real time, then a shed that drops 8 records (1.6 s of media), then real time again.
        repeat(25) {
            g.open(asPacket(record(s, unit)))
            unit += 10
            now += 200
        }
        repeat(8) {
            record(s, unit) // signed (sequence consumed) but dropped by a shedding hop
            unit += 10
        }
        now += 200
        g.open(asPacket(record(s, unit)))
        unit += 10
        now += 200
        g.open(asPacket(record(s, unit)))
    }

    @Test
    fun anUnexplainedTimestampJumpEndsTheStream() {
        var now = 0L
        val s = signer()
        val g = guard(s) { now }
        g.open(asPacket(config(s)))
        g.open(asPacket(record(s, 0)))
        // No records dropped (sequence is consecutive) but the timestamp jumps 5 s ahead.
        val jump = record(s, 10 + 250)
        assertThrows(IllegalArgumentException::class.java) { g.open(asPacket(jump)) }
        // Overlapping timestamps fail too.
        val overlap = guard(s).also { it.open(asPacket(record(s, 1000))) }
        assertThrows(IllegalArgumentException::class.java) { overlap.open(asPacket(record(s, 1005))) }
    }

    @Test
    fun pauseAndResumeRestartTheBaselineThroughTheLifecycleRecord() {
        var now = 0L
        val s = signer()
        val g = guard(s) { now }
        g.open(asPacket(config(s)))
        g.open(asPacket(record(s, 0)))
        g.onLifecycleRecord() // PAUSED
        now += 120_000
        g.onLifecycleRecord() // LIVE
        // Source clock jumped by minutes while paused.
        g.open(asPacket(record(s, 15_000)))
        now += 200
        g.open(asPacket(record(s, 15_010)))
    }

    @Test
    fun aBurstAllowanceCoversADrainingBacklogButNotAFlood() {
        var now = 0L
        val s = signer()
        val g = guard(s) { now }
        g.open(asPacket(config(s)))
        // Join with a 20 s backlog: 100 records delivered 20 ms apart, then real time. Fine (lead 20 s).
        var unit = 0
        repeat(100) {
            g.open(asPacket(record(s, unit)))
            unit += 10
            now += 20
        }
        // A source whose media runs at 3x real time exceeds the allowance within seconds.
        now = 0L
        val fast = guard(s) { now }
        fast.open(asPacket(config(s)))
        unit = 100_000
        assertThrows(IllegalArgumentException::class.java) {
            repeat(1_000) {
                fast.open(asPacket(record(s, unit)))
                unit += 30
                now += 200
            }
        }
    }

    // --- Review 13: ingress budgets before allocation or decoding ------------------------------------------

    @Test
    fun recordAndByteBudgetsBoundASource() {
        var now = 0L
        val s = signer()
        val g = guard(s) { now }
        g.open(asPacket(config(s)))
        // 1-unit records at 1000/s exceed the record budget (600 burst, 60/s).
        var unit = 0
        assertThrows(IllegalArgumentException::class.java) {
            repeat(2_000) {
                g.open(asPacket(record(s, unit++, 1)))
                if (it % 10 == 9) now += 10
            }
        }
        // Maximum-size units at plausible timestamps exceed the byte budget.
        now = 0L
        val big = guard(s) { now }
        big.open(asPacket(config(s)))
        unit = 5_000
        assertThrows(IllegalArgumentException::class.java) {
            repeat(2_000) {
                big.open(asPacket(record(s, unit, 20) { frame(1275) }))
                unit += 20
                now += 400
            }
        }
    }

    @Test
    fun oversizeSealedRecordsAreRefusedBeforeParsingAndConfigAndLifecycleRatesAreBounded() {
        val s = signer()
        val g = guard(s)
        g.checkSealedSize(AudioLive.MAX_RECORD_PAYLOAD_BYTES + StreamProtocol.PACKET_HEADER_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            g.checkSealedSize(AudioLive.MAX_RECORD_PAYLOAD_BYTES + StreamProtocol.PACKET_HEADER_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            g.open(StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 0, ByteArray(AudioLive.MAX_RECORD_PAYLOAD_BYTES + 1)))
        }
        val c = config(s)
        val repeated = guard(s)
        repeat(AudioLiveStreamGuard.MAX_CONFIG_RECORDS) { repeated.open(asPacket(c)) }
        assertThrows(IllegalArgumentException::class.java) { repeated.open(asPacket(c)) }
        val lifecycle = guard(s)
        repeat(AudioLiveStreamGuard.CONTROL_BURST.toInt()) { lifecycle.onLifecycleRecord() }
        assertThrows(IllegalArgumentException::class.java) { lifecycle.onLifecycleRecord() }
        assertTrue(AudioLive.MAX_RECORD_PAYLOAD_BYTES + 200 < AudioLive.MAX_RECORD_CIPHERTEXT_BYTES)
    }

    @Test
    fun theLinkReaderRefusesAnOversizeRecordBeforeAllocatingIt() {
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val out = ByteArrayOutputStream()
        val writer = SecureRecordWriter(out, linkKey)
        writer.write(SecureRecordType.END_TO_END_CONTENT, ByteArray(10_000))
        writer.flush()
        val reader = SecureRecordReader(ByteArrayInputStream(out.toByteArray()), linkKey)
        reader.maxCiphertextBytes = 4_096
        val error = assertThrows(IllegalArgumentException::class.java) { reader.read() }
        assertTrue(error.message!!.contains("exceeds this stream's limit"))
        val ok = SecureRecordReader(ByteArrayInputStream(out.toByteArray()), linkKey)
        assertEquals(10_000, ok.read().payload.size)
    }

    // --- Offer marker ----------------------------------------------------------------------------------

    @Test
    fun markerIsExactlyBit6OnAStreamOfferWithNoGeometry() {
        assertEquals(StreamOfferKind.AUDIO_ONLY, offer(0x40).streamOfferKind())
        assertTrue(offer(0x40).isAudioOnlyOffer())
        assertEquals(StreamOfferKind.VIDEO, offer(0x01, width = 640, height = 360, fps = 15).streamOfferKind())
        assertEquals(StreamOfferKind.VIDEO, offer(0x03).streamOfferKind())
        assertEquals(StreamOfferKind.VIDEO, offer(0x04).streamOfferKind())
        assertEquals(StreamOfferKind.VIDEO, offer(0x00).streamOfferKind())
        listOf(0x41, 0x42, 0x43, 0x44, 0x48, 0x60, 0x80 or 0x40, 0xc0, 0x7f).forEach { summary ->
            assertEquals("summary $summary", StreamOfferKind.MALFORMED, offer(summary).streamOfferKind())
            assertFalse(offer(summary).isAudioOnlyOffer())
        }
        val callOffer = offer(
            0x40,
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            token = 0,
        )
        assertEquals(StreamOfferKind.MALFORMED, callOffer.streamOfferKind())
        val viewer = offer(0x40, flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS)
        assertEquals(StreamOfferKind.MALFORMED, viewer.streamOfferKind())
        assertEquals(StreamOfferKind.MALFORMED, offer(0x40, width = 640, height = 360, fps = 15).streamOfferKind())
        assertEquals(StreamOfferKind.MALFORMED, offer(0x40, fps = 15).streamOfferKind())
    }

    @Test
    fun anOlderPhoneFindsNoDecoderOverlapSoTheRowIsUnsupportedAndNeverJoined() {
        val oldDecoderMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1
        val summary = offer(0x40).capabilitySummary
        assertTrue(summary != 0)
        assertEquals(0, summary and oldDecoderMask)
        assertEquals(0, summary and (0x3f or NodeCapabilities.DECODE_PREFERS_VP9))
        assertTrue(offer(0x40).isStreamOffer())
    }

    @Test
    fun audioAndVideoOffersWithACoincidentTokenStayInSeparateWatchRows() {
        val audioOffer = offer(0x40)
        val videoOffer = offer(0x01, width = 426, height = 240, fps = 15)
        assertFalse(LogicalStreamTarget.from(audioOffer).matches(videoOffer))
        assertTrue(LogicalStreamTarget.from(audioOffer).matches(audioOffer.copy(routeCost = 2)))
        val groups = groupStreamRoutes(
            listOf(audioOffer, videoOffer, audioOffer.copy(nodeIdPrefix = ByteArray(6) { 9 })),
            announcement = { it },
            joinable = { true },
        )
        assertEquals(3, groups.size)
    }

    @Test
    fun markerAndHeaderMustAgree() {
        val audioHeader = header(signer())
        val videoHeader = StreamProtocol.Header(426, 240, 15, StreamProtocol.CODEC_VP9, 0, 1, 48_000, 1)
        assertTrue(AudioLive.markerAgreesWithHeader(true, audioHeader))
        assertTrue(AudioLive.markerAgreesWithHeader(false, videoHeader))
        assertFalse(AudioLive.markerAgreesWithHeader(true, videoHeader))
        assertFalse(AudioLive.markerAgreesWithHeader(false, audioHeader))
    }

    @Test
    fun aRelayDerivesItsMarkerFromTheAuthenticatedHeader() {
        val audioHeader = header(signer())
        val videoHeader = StreamProtocol.Header(426, 240, 15, StreamProtocol.CODEC_VP9, 0, 1, 48_000, 1)
        assertEquals(0x40, AudioLive.offerSummary(audioHeader, NodeCapabilities.CODEC_VP9))
        assertEquals(NodeCapabilities.CODEC_VP9, AudioLive.offerSummary(videoHeader, NodeCapabilities.CODEC_VP9))
    }

    // --- Review 17: mismatch penalties are scoped to the attempted route --------------------------------

    @Test
    fun aForgedMismatchPenalisesOnlyTheAttemptedRouteForABoundedTime() {
        val penalties = StreamRoutePenalties(routeTtlMs = 1_000, nodeTtlMs = 5_000, maxEntries = 3)
        val forged = StreamRoutePenalties.Route("AA:BB", "010203040506", 7)
        val honest = StreamRoutePenalties.Route("CC:DD", "010203040506", 7) // same prefix and token, other address
        val other = StreamRoutePenalties.Route("AA:BB", "010203040506", 8)
        penalties.onMismatch(forged, authenticatedNode = null, nowMs = 0)
        assertTrue(penalties.isPenalized(forged, null, 500))
        assertFalse(penalties.isPenalized(honest, null, 500))
        assertFalse(penalties.isPenalized(other, null, 500))
        assertFalse(penalties.isPenalized(forged, null, 1_000)) // expired
        // Only authenticated evidence penalises a node, across all its routes, for longer.
        val node = identity.nodeId
        penalties.onMismatch(forged, authenticatedNode = node, nowMs = 2_000)
        assertTrue(penalties.isPenalized(honest, node, 4_000))
        assertFalse(penalties.isPenalized(honest, null, 4_000))
        assertFalse(penalties.isPenalized(honest, node, 7_001))
        // The table stays bounded under a flood of forged adverts.
        repeat(100) { penalties.onMismatch(StreamRoutePenalties.Route("X$it", "000000000000", it), null, 10_000) }
        val alive = (0 until 100).count { penalties.isPenalized(StreamRoutePenalties.Route("X$it", "000000000000", it), null, 10_001) }
        assertEquals(3, alive)
    }

    // --- Review 18: separate shed predicates for the two flag namespaces --------------------------------

    @Test
    fun shedPredicatesAreExactAndNeverMixTheFlagNamespaces() {
        // Source (inner flags): ordinary audio and audio batches only.
        assertTrue(AudioLiveShed.isSourceRecordSheddable(StreamProtocol.FLAG_AUDIO))
        assertTrue(AudioLiveShed.isSourceRecordSheddable(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR))
        assertFalse(AudioLiveShed.isSourceRecordSheddable(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG))
        assertFalse(AudioLiveShed.isSourceRecordSheddable(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DEBUG_INTEGRITY))
        assertFalse(AudioLiveShed.isSourceRecordSheddable(StreamProtocol.FLAG_KEY_FRAME))
        assertFalse(AudioLiveShed.isSourceRecordSheddable(0))

        // Relay (mesh envelope flags): inner bit 4 means debug integrity, mesh bit 4 means control.
        fun envelope(flags: Int) = MeshEnvelope(streamId, identity.nodeId, 5, 8, flags, ByteArray(10))
        assertTrue(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO), true))
        assertTrue(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO or AudioLiveShed.MESH_AUDIO_RECORD_BIT), true))
        // A mixed-mask predicate ("audio set and codec-config clear") would shed these; exact matching must not.
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_CONTROL), true))
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_TERMINAL), true))
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_CODEC_CONFIG), true))
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_CONTROL), true))
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_KEY_FRAME), true))
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO or MeshEnvelope.FLAG_DISCARDABLE), true))
        // Never on a video stream.
        assertFalse(AudioLiveShed.isRelayEnvelopeSheddable(envelope(MeshEnvelope.FLAG_AUDIO), false))
        // The inner-to-mesh mapping the writer uses keeps the audio batch at mesh flags 72.
        assertEquals(
            MeshEnvelope.FLAG_AUDIO or AudioLiveShed.MESH_AUDIO_RECORD_BIT,
            meshPacketFlags(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR),
        )
    }

    // --- Compact (iPhone) stream offers ------------------------------------------------------------------

    @Test
    fun compactVectorAndRoundTripForTheAudioOnlyMarker() {
        val audioOffer = offer(0x40, flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT)
            .copy(streamToken = 0xdeadbeef.toInt(), routeCost = 1, sequence = 0x05, streamBitrateKbps = 25)
        val uuid = CompactDiscoveryAnnouncement.encode(audioOffer)
        assertEquals("c8d66010-2030-4050-6806-f56df7782f81", uuid.toString())
        val decoded = CompactDiscoveryAnnouncement.decode(uuid)
        assertEquals(0x40, decoded.capabilitySummary)
        assertEquals(0, decoded.streamWidth)
        assertEquals(0, decoded.streamHeight)
        assertEquals(0, decoded.streamFps)
        assertEquals(audioOffer.streamToken, decoded.streamToken)
        assertEquals(25, decoded.streamBitrateKbps)
        assertTrue(decoded.isAudioOnlyOffer())
        // Codec mask 0 (bits 56..54), resolution index 3 (bits 10..9), FPS index 3 (bits 8..7).
        assertEquals(0L, (uuid.leastSignificantBits ushr 54) and 0x7)
        assertEquals(3L, (uuid.leastSignificantBits ushr 9) and 0x3)
        assertEquals(3L, (uuid.leastSignificantBits ushr 7) and 0x3)
        val low = CompactDiscoveryAnnouncement.decode(CompactDiscoveryAnnouncement.encode(audioOffer.copy(streamBitrateKbps = 16)))
        val voice = CompactDiscoveryAnnouncement.decode(CompactDiscoveryAnnouncement.encode(audioOffer.copy(streamBitrateKbps = 24)))
        assertEquals(low.streamBitrateKbps, voice.streamBitrateKbps)
    }

    @Test
    fun compactMaskZeroWithOtherGeometryDecodesAsBefore() {
        val base = offer(0x40, flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE).copy(streamToken = 0xdeadbeef.toInt())
        val audio = CompactDiscoveryAnnouncement.encode(base)
        val bits = audio.leastSignificantBits
        val otherResolution = UUID(audio.mostSignificantBits, bits and (0x3L shl 9).inv())
        assertEquals(0, CompactDiscoveryAnnouncement.decode(otherResolution).capabilitySummary)
        val otherFps = UUID(audio.mostSignificantBits, bits and (0x3L shl 7).inv())
        assertEquals(0, CompactDiscoveryAnnouncement.decode(otherFps).capabilitySummary)
        val vp9 = UUID(audio.mostSignificantBits, bits or (1L shl 54))
        assertEquals(1, CompactDiscoveryAnnouncement.decode(vp9).capabilitySummary)
    }

    @Test
    fun compactEncoderStillRejectsEveryOtherSummaryBit() {
        val base = offer(0x40).copy(streamToken = 0xdeadbeef.toInt())
        listOf(0x41, 0x42, 0x44, 0x48, 0x60, 0x80, 0xc0).forEach { summary ->
            assertThrows("summary $summary", IllegalArgumentException::class.java) {
                CompactDiscoveryAnnouncement.encode(base.copy(capabilitySummary = summary))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompactDiscoveryAnnouncement.encode(base.copy(streamWidth = 640, streamHeight = 360, streamFps = 15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompactDiscoveryAnnouncement.encode(base.copy(flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS))
        }
    }

    @Test
    fun theAnnouncementCodecCarriesTheMarkerInTheSummaryByte() {
        val decoded = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(offer(0x40)))
        assertEquals(0x40, decoded.capabilitySummary)
        assertTrue(decoded.isAudioOnlyOffer())
    }

    // --- Reader integration -----------------------------------------------------------------------------

    private val sourceNode = identity.nodeId
    private val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
    private val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 41).toByte() }

    private fun writer(bytes: ByteArrayOutputStream, mesh: Boolean): Pair<SecureRecordWriter, EndToEndStreamWriter> {
        val link = SecureRecordWriter(bytes, linkKey)
        return link to EndToEndStreamWriter(
            link, EndToEndContentSealer(mediaKey), sourceNode, mesh, meshStreamId = if (mesh) streamId else null,
        )
    }

    private fun reader(bytes: ByteArray, expected: NodeId = sourceNode, clock: () -> Long = { 0L }) = EndToEndStreamReader(
        SecureRecordReader(ByteArrayInputStream(bytes), linkKey),
        EndToEndContentOpener(mediaKey), expected,
    ).also { it.audioClockMs = clock }

    @Test
    fun readerDeliversASignedAudioOnlyStreamEndToEndInBothWireModes() {
        for (mesh in listOf(false, true)) {
            val s = signer(id = if (mesh) streamId else AudioLive.ZERO_STREAM_ID)
            val bytes = ByteArrayOutputStream()
            val (link, w) = writer(bytes, mesh)
            w.writeAudioLiveHeader(s)
            w.writeAudioLiveRecord(config(s))
            w.writeAudioLiveRecord(record(s, 0))
            w.writeState(StreamLifecycleState.ENDED)
            link.flush()

            val r = reader(bytes.toByteArray())
            val h = r.readHeader(allowAudioOnly = true)
            assertTrue(h.isAudioOnly)
            assertEquals(AudioLive.PRESET_VOICE, h.audioLivePreset)
            val cfg = r.readStreamContent() as ReceivedStreamContent.Media
            assertEquals(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, cfg.packet.flags)
            assertArrayEquals(opusHead, cfg.packet.payload)
            val units = (0 until 10).map { (r.readStreamContent() as ReceivedStreamContent.Media).packet }
            assertEquals(List(10) { it * 20_000L }, units.map { it.presentationTimeUs })
            assertTrue(units.all { AudioLiveOpus.isSingle20msMonoFrame(it.payload) })
            assertEquals(StreamLifecycleState.ENDED, (r.readStreamContent() as ReceivedStreamContent.State).state)
        }
    }

    @Test
    fun theWriterRefusesUnsignedPacketsOnAnAudioOnlyStreamAndAForeignSigner() {
        val bytes = ByteArrayOutputStream()
        val (_, w) = writer(bytes, true)
        assertThrows(IllegalArgumentException::class.java) { w.writeAudioLiveHeader(signer(id = UUID(1, 1))) }
        w.writeAudioLiveHeader(signer())
        assertThrows(IllegalArgumentException::class.java) { w.writePacket(StreamProtocol.FLAG_AUDIO, 0, frame()) }
    }

    @Test
    fun readerThatDoesNotAllowAudioOnlyStopsAtTheHeader() {
        val s = signer()
        val bytes = ByteArrayOutputStream()
        val (link, w) = writer(bytes, true)
        w.writeAudioLiveHeader(s)
        link.flush()
        val error = assertThrows(IllegalArgumentException::class.java) { reader(bytes.toByteArray()).readHeader() }
        assertEquals("Unsupported stream header BTL1", error.message)
    }

    @Test
    fun readerRefusesAHeaderCertifiedByAnotherSourceAndForgedMediaFromAForwarder() {
        val s = signer()
        val bytes = ByteArrayOutputStream()
        val (link, w) = writer(bytes, true)
        w.writeAudioLiveHeader(s)
        link.flush()
        val stranger = KeyPairSigningIdentity(generateEcKeyPair()).nodeId
        assertThrows(IllegalArgumentException::class.java) {
            reader(bytes.toByteArray(), expected = stranger).readHeader(allowAudioOnly = true)
        }

        // A viewer-forwarder holds the media key: it can seal anything, but cannot sign as the source.
        val honest = signer()
        val bytes2 = ByteArrayOutputStream()
        val (link2, w2) = writer(bytes2, true)
        w2.writeAudioLiveHeader(honest)
        val attacker = signer(who = KeyPairSigningIdentity(generateEcKeyPair()))
        w2.writeAudioLiveRecord(record(attacker, 0))
        link2.flush()
        val r = reader(bytes2.toByteArray())
        r.readHeader(allowAudioOnly = true)
        assertThrows(IllegalArgumentException::class.java) { r.readStreamContent() }
    }

    @Test
    fun readerFailsAnAudioOnlyStreamOnVideoPacketsAndSecondHeadersAndStillReadsVideo() {
        fun run(block: (EndToEndStreamWriter, AudioLiveSigner) -> Unit): EndToEndStreamReader {
            val s = signer()
            val bytes = ByteArrayOutputStream()
            val (link, w) = writer(bytes, true)
            w.writeAudioLiveHeader(s)
            block(w, s)
            link.flush()
            return reader(bytes.toByteArray()).also { it.readHeader(allowAudioOnly = true) }
        }
        // An unsigned video-style packet cannot be written by the writer, so craft the bytes with a plain writer.
        val bytes = ByteArrayOutputStream()
        val plainLink = SecureRecordWriter(bytes, linkKey)
        val plain = EndToEndStreamWriter(plainLink, EndToEndContentSealer(mediaKey), sourceNode, true, meshStreamId = streamId)
        val s = signer()
        plain.writeAudioLiveHeader(s)
        // A second writer instance (not in audio mode) continues the same link, as a hostile peer could.
        val hostile = EndToEndStreamWriter(plainLink, EndToEndContentSealer(mediaKey), sourceNode, true, meshStreamId = streamId)
        hostile.writePacket(StreamProtocol.FLAG_KEY_FRAME, 0, ByteArray(100))
        plainLink.flush()
        val r = reader(bytes.toByteArray())
        r.readHeader(allowAudioOnly = true)
        assertThrows(Exception::class.java) { r.readStreamContent() }

        assertThrows(IllegalStateException::class.java) {
            run { w, s2 -> w.writeAudioLiveHeader(s2) }.readStreamContent()
        }
        // The same reader path still reads a video stream unchanged.
        val vbytes = ByteArrayOutputStream()
        val (vlink, vw) = writer(vbytes, true)
        vw.writeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, false)
        vw.writePacket(StreamProtocol.FLAG_KEY_FRAME, 0, ByteArray(100))
        vlink.flush()
        val vr = reader(vbytes.toByteArray())
        assertFalse(vr.readHeader(allowAudioOnly = true).isAudioOnly)
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, (vr.readStreamContent() as ReceivedStreamContent.Media).packet.flags)
    }

    // --- Planning arithmetic (Design AL tables, now with the 68-byte signature trailer) -----------------------

    @Test
    fun recordSizesAndRatesMatchTheDesignTable() {
        assertEquals(595 + 68, MeshBandwidthEstimator.audioLiveRecordBytes(16, 10))
        assertEquals(795 + 68, MeshBandwidthEstimator.audioLiveRecordBytes(24, 10))
        assertEquals(465 + 68, MeshBandwidthEstimator.audioLiveRecordBytes(24, 5))
        assertEquals(262 + 68, MeshBandwidthEstimator.audioLiveRecordBytes(24, 2))
        assertEquals(1395 + 68, MeshBandwidthEstimator.audioLiveRecordBytes(48, 10))
        assertEquals(34.5, MeshBandwidthEstimator.audioLiveWireKbps(24, 10), 0.05)
        assertEquals(26.5, MeshBandwidthEstimator.audioLiveWireKbps(16, 10), 0.05)
        assertEquals(58.5, MeshBandwidthEstimator.audioLiveWireKbps(48, 10), 0.05)
        assertEquals(41.4, MeshBandwidthEstimator.audioLivePerLinkKbps(24), 0.05)
        assertEquals(31.8, MeshBandwidthEstimator.audioLivePerLinkKbps(16), 0.05)
        assertEquals(70.2, MeshBandwidthEstimator.audioLivePerLinkKbps(48), 0.05)
        assertEquals(82.8, MeshBandwidthEstimator.audioLiveRelayKbps(24), 0.1)
    }

    @Test
    fun relayAdmissionThresholdsAgainstTheMeasuredCeilings() {
        val voice = MeshBandwidthEstimator.audioLiveRelayKbps(24)
        val low = MeshBandwidthEstimator.audioLiveRelayKbps(16)
        val music = MeshBandwidthEstimator.audioLiveRelayKbps(48)
        listOf(430.0, 770.0).forEach { ceiling -> assertTrue(voice <= ceiling && low <= ceiling && music <= ceiling) }
        assertTrue(voice > 65.0)
        assertTrue(low < 65.0 && low > 0.85 * 65.0 - 5)
        assertEquals(4, MeshBandwidthEstimator.audioLiveSourceChildren(700.0, 24))
        assertEquals(1, MeshBandwidthEstimator.audioLiveSourceChildren(20.0, 24))
        assertEquals(10, MeshBandwidthEstimator.audioLiveSourceChildren(428.0, 24, cap = 100))
    }
}
