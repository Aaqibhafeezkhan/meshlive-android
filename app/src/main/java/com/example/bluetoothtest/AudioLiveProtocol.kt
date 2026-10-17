package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.Arrays
import java.util.UUID

/**
 * Experimental audio-only live streams ("radio"). Wire rules: PROTOCOL.md "Experimental audio-only live
 * streams"; design and reasons: DECISIONS.md "Design AL". This file holds the pure rules (constants, offer
 * marker, header and signed-record format, validation, budgets, shed predicates, route penalties, planning
 * arithmetic); nothing here touches video streams.
 */
object AudioLive {
    /** `BTL1`, the audio-only stream header magic. */
    val HEADER_MAGIC: ByteArray = byteArrayOf(0x42, 0x54, 0x4c, 0x31)
    const val HEADER_MAGIC_TEXT = "BTL1"

    /** The fixed part of the header (magic, codec, rate, channels, frame ms, preset, reserved). */
    const val FIXED_HEADER_BYTES = 11
    const val FRAME_MS = 20
    const val FRAME_US = 20_000L
    const val FRAME_SAMPLES = 960

    /** A direct (non-mesh) stream binds to the all-zero stream ID, as signed labels do. */
    val ZERO_STREAM_ID: UUID = UUID(0L, 0L)

    /** The Opus maximum packet size, tighter than the generic 16 KiB audio unit limit. */
    const val MAX_OPUS_UNIT_BYTES = 1275

    const val PRESET_LOW_DATA = 1
    const val PRESET_VOICE = 2
    const val PRESET_MUSIC = 3
    const val DEFAULT_PRESET = PRESET_VOICE

    /** Default frames per record (200 ms); a signed record carries 1 to 20 access units. */
    const val DEFAULT_BATCH_FRAMES = 10
    const val MIN_BATCH_FRAMES = 1
    const val MAX_BATCH_FRAMES = 20

    /** Source cap on direct listeners until the fan-out ladder (T-AL4) replaces it with a measured number. */
    const val DEFAULT_MAX_CHILDREN = 4

    /** Age bound (PROTOCOL "Age shedding"): shed oldest whole records beyond [SHED_TRIGGER_MS], down to [SHED_KEEP_MS]. */
    const val SHED_TRIGGER_MS = 3_000L
    const val SHED_KEEP_MS = 1_500L

    /** Withdraw a child whose oldest queued record is older than this (a time limit, not the byte limit). */
    const val WITHDRAW_AGE_MS = 30_000L

    /** Withdraw a child when more than this share of its records was shed over [SHED_RATE_WINDOW_MS]. */
    const val WITHDRAW_SHED_RATE = 0.20
    const val SHED_RATE_WINDOW_MS = 10_000L

    // --- Signed records (Design AL review: viewer-forwarders hold the media key, so the source signs) ------

    /** Raw ECDSA P-256 signature, r || s. */
    const val SIGNATURE_BYTES = 64
    const val SEQ_BYTES = 4
    const val MAX_SPKI_BYTES = 128
    const val MAX_CERT_SIGNATURE_BYTES = 80

    /** Largest signed record payload: sequence + a 20-unit batch of maximum units + signature. */
    const val MAX_RECORD_PAYLOAD_BYTES =
        SEQ_BYTES + 5 + MAX_BATCH_FRAMES * (6 + MAX_OPUS_UNIT_BYTES) + SIGNATURE_BYTES

    /** Ciphertext bound the link reader applies to an audio-only stream (payload + inner and sealing framing). */
    const val MAX_RECORD_CIPHERTEXT_BYTES = 28 * 1024

    /** Bound for the first (header) record of a reader that may meet an audio-only stream. */
    const val MAX_HEADER_CIPHERTEXT_BYTES = 4 * 1024

    val CERT_DOMAIN: ByteArray = "BluetoothVideoMesh/AudioLive/Cert/1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    val RECORD_DOMAIN: ByteArray = "BluetoothVideoMesh/AudioLive/Rec/1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    fun isValidPreset(preset: Int): Boolean = preset in PRESET_LOW_DATA..PRESET_MUSIC

    /** Nominal Opus rate of a preset, in kbps (display, planning and encoder configuration). */
    fun presetKbps(preset: Int): Int = when (preset) {
        PRESET_LOW_DATA -> 16
        PRESET_VOICE -> 24
        PRESET_MUSIC -> 48
        else -> throw IllegalArgumentException("Invalid audio live preset $preset")
    }

    /** The preset whose nominal rate is [kbps] (the `test_audio_live_kbps` override accepts 16, 24 and 48). */
    fun presetForKbps(kbps: Int): Int? = when (kbps) {
        16 -> PRESET_LOW_DATA
        24 -> PRESET_VOICE
        48 -> PRESET_MUSIC
        else -> null
    }

    /** The 11 fixed header bytes of [preset]: `BTL1`, Opus, 48000, mono, 20 ms, preset, reserved 0. */
    fun fixedHeader(preset: Int): ByteArray {
        require(isValidPreset(preset)) { "Invalid audio live preset $preset" }
        return ByteBuffer.allocate(FIXED_HEADER_BYTES)
            .put(HEADER_MAGIC)
            .put(StreamProtocol.AUDIO_CODEC_OPUS.toByte())
            .putShort(StreamProtocol.AUDIO_SAMPLE_RATE.toShort())
            .put(StreamProtocol.AUDIO_CHANNEL_COUNT.toByte())
            .put(FRAME_MS.toByte())
            .put(preset.toByte())
            .put(0)
            .array()
    }

    /**
     * The `capabilitySummary` a stream offer for [header] carries: exactly the audio-only marker for a
     * `BTL1` stream, else [videoSummary] (codec bits). A relay uses this on the authenticated header it
     * received, never its parent's advertisement.
     */
    fun offerSummary(header: StreamProtocol.Header, videoSummary: Int): Int =
        if (header.isAudioOnly) DiscoveryAnnouncement.SUMMARY_AUDIO_ONLY else videoSummary

    /**
     * True when the offer's marker and the authenticated header describe the same stream kind. A reader
     * that selected an offer marked audio-only and got a `BTV3` header, or an unmarked offer and a `BTL1`
     * header, fails the link closed. Penalties for the mismatch follow [StreamRoutePenalties].
     */
    fun markerAgreesWithHeader(offerMarkedAudioOnly: Boolean, header: StreamProtocol.Header): Boolean =
        offerMarkedAudioOnly == header.isAudioOnly

    internal fun certMessage(streamId: UUID, preset: Int, ephemeralSpki: ByteArray): ByteArray =
        ByteArrayOutputStream().also {
            it.write(CERT_DOMAIN)
            it.write(uuidBytes(streamId))
            it.write(fixedHeader(preset))
            it.write(ephemeralSpki)
        }.toByteArray()

    internal fun recordMessage(streamId: UUID, flags: Int, ptsUs: Long, seq: Long, body: ByteArray): ByteArray =
        ByteBuffer.allocate(RECORD_DOMAIN.size + 16 + 1 + 8 + SEQ_BYTES + body.size)
            .put(RECORD_DOMAIN)
            .put(uuidBytes(streamId))
            .put(flags.toByte())
            .putLong(ptsUs)
            .putInt(seq.toInt())
            .put(body)
            .array()

    internal fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
}

/**
 * The signer block of a `BTL1` header: a per-stream public key that signs every record, certified once by
 * the source's identity key (which is the key the listener's expected source node ID names). The identity
 * key signs once per stream, not per record, so the Android Keystore is off the 5-per-second path.
 */
class AudioLiveHeaderAuth(
    val ephemeralSpki: ByteArray,
    val identitySpki: ByteArray,
    /** DER ECDSA signature by the identity key over [AudioLive.certMessage]. */
    val certificate: ByteArray,
) {
    fun writeTo(output: OutputStream) {
        DataOutputStream(output).apply {
            writeShort(ephemeralSpki.size)
            write(ephemeralSpki)
            writeShort(identitySpki.size)
            write(identitySpki)
            writeShort(certificate.size)
            write(certificate)
        }
    }

    companion object {
        fun readFrom(input: DataInputStream): AudioLiveHeaderAuth {
            fun block(max: Int, what: String): ByteArray {
                val size = input.readUnsignedShort()
                require(size in 1..max) { "Invalid audio live $what length $size" }
                return ByteArray(size).also(input::readFully)
            }
            return AudioLiveHeaderAuth(
                block(AudioLive.MAX_SPKI_BYTES, "stream key"),
                block(AudioLive.MAX_SPKI_BYTES, "identity key"),
                block(AudioLive.MAX_CERT_SIGNATURE_BYTES, "certificate"),
            )
        }
    }
}

/** One record signed by the source: the same bytes are sealed for every child. */
class AudioLiveSignedRecord(val flags: Int, val presentationTimeUs: Long, val payload: ByteArray)

/**
 * Source side. Generates the per-stream key, certifies it with [identity] (the same key the listener
 * authenticates as the stream's source), and signs every record: `payload = seq(4) || body || signature(64)`.
 * Signing happens once per record (not once per child). Thread-safe through [sign].
 */
class AudioLiveSigner(
    identity: SigningIdentity,
    val streamId: UUID,
    val preset: Int,
    streamKey: KeyPair = generateEcKeyPair(),
) {
    private val privateKey = streamKey.private
    private var nextSeq = 0L

    /** The full BTL1 header payload (fixed bytes and signer block). */
    val headerBytes: ByteArray

    init {
        require(AudioLive.isValidPreset(preset)) { "Invalid audio live preset $preset" }
        val ephemeralSpki = streamKey.public.encoded
        val certificate = identity.sign(AudioLive.certMessage(streamId, preset, ephemeralSpki))
        val auth = AudioLiveHeaderAuth(ephemeralSpki, identity.publicKeyEncoded, certificate)
        headerBytes = ByteArrayOutputStream().also { StreamProtocol.writeAudioLiveHeader(it, preset, auth) }.toByteArray()
    }

    /**
     * [body] is the packet payload as the existing code builds it: the Opus configuration bytes, one access
     * unit, a `BTA1` pair or a `BTA2` batch, with [flags] the matching inner packet flags.
     */
    @Synchronized
    fun sign(flags: Int, presentationTimeUs: Long, body: ByteArray): AudioLiveSignedRecord {
        val seq = nextSeq++
        require(seq <= 0xffff_ffffL) { "Audio signing sequence exhausted" }
        val signature = EcdsaRaw.sign(privateKey, AudioLive.recordMessage(streamId, flags, presentationTimeUs, seq, body))
        val payload = ByteBuffer.allocate(AudioLive.SEQ_BYTES + body.size + AudioLive.SIGNATURE_BYTES)
            .putInt(seq.toInt()).put(body).put(signature).array()
        return AudioLiveSignedRecord(flags, presentationTimeUs, payload)
    }
}

/**
 * Listener (and relay) side: checks the header's certificate against the expected source node ID, then
 * opens each record. A viewer-forwarder that holds the media key still cannot forge a record, because it
 * does not hold the stream key; it can only withhold or replay (replay fails the sequence rule).
 */
class AudioLiveVerifier private constructor(
    private val streamId: UUID,
    private val streamKey: PublicKey,
) {
    private var lastAudioSeq = -1L

    class Opened(val packet: StreamProtocol.Packet, val seq: Long)

    /** Checks the signature, the sequence rule and strips the envelope; throws on any failure. */
    fun open(packet: StreamProtocol.Packet): Opened {
        val payload = packet.payload
        require(payload.size >= AudioLive.SEQ_BYTES + AudioLive.SIGNATURE_BYTES + 1) { "Unsigned or truncated audio record" }
        val seq = ByteBuffer.wrap(payload, 0, AudioLive.SEQ_BYTES).int.toLong() and 0xffff_ffffL
        val body = payload.copyOfRange(AudioLive.SEQ_BYTES, payload.size - AudioLive.SIGNATURE_BYTES)
        val signature = payload.copyOfRange(payload.size - AudioLive.SIGNATURE_BYTES, payload.size)
        val message = AudioLive.recordMessage(streamId, packet.flags, packet.presentationTimeUs, seq, body)
        require(EcdsaRaw.verify(streamKey, message, signature)) { "Audio record signature does not verify" }
        if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
            // Strictly increasing (gaps are legitimate: age shedding); a replayed or reordered record fails.
            require(seq > lastAudioSeq) { "Replayed or reordered audio record $seq after $lastAudioSeq" }
            lastAudioSeq = seq
        }
        return Opened(StreamProtocol.Packet(packet.flags, packet.presentationTimeUs, body), seq)
    }

    companion object {
        /**
         * [expectedSource] is the node ID the reader already authenticates (the direct endpoint peer, or the
         * source ID in the parent's `BFG1` grant): the certifying identity key must hash to it.
         */
        fun fromHeader(header: StreamProtocol.Header, streamId: UUID, expectedSource: NodeId): AudioLiveVerifier {
            val auth = requireNotNull(header.audioLiveAuth) { "Audio-only header without a signer block" }
            require(header.isAudioOnly) { "Not an audio-only header" }
            require(NodeId.fromPublicKey(auth.identitySpki) == expectedSource) {
                "Audio stream is certified by a key other than the expected source"
            }
            val identityKey = decodeStreamKey(auth.identitySpki)
            val message = AudioLive.certMessage(streamId, header.audioLivePreset, auth.ephemeralSpki)
            require(verifyEcdsa(identityKey, message, auth.certificate)) { "Audio stream certificate does not verify" }
            return AudioLiveVerifier(streamId, decodeStreamKey(auth.ephemeralSpki))
        }

        private fun decodeStreamKey(spki: ByteArray): PublicKey {
            val key = try {
                decodeEcPublicKey(spki)
            } catch (error: java.security.GeneralSecurityException) {
                throw IllegalArgumentException("Invalid audio stream key", error)
            }
            require(key is ECPublicKey && key.params.order.bitLength() == 256) { "Audio stream keys must be P-256" }
            return key
        }
    }
}

/** ECDSA P-256 with fixed-width r || s signatures (the JCA works in DER). */
internal object EcdsaRaw {
    fun sign(privateKey: java.security.PrivateKey, message: ByteArray): ByteArray =
        derToRaw(signEcdsa(privateKey, message))

    fun verify(publicKey: PublicKey, message: ByteArray, raw: ByteArray): Boolean {
        if (raw.size != AudioLive.SIGNATURE_BYTES) return false
        return try {
            verifyEcdsa(publicKey, message, rawToDer(raw))
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    fun derToRaw(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0].toInt() == 0x30) { "Not a DER signature" }
        var offset = 2
        if (der[1].toInt() and 0x80 != 0) offset = 2 + (der[1].toInt() and 0x7f)
        fun integer(): ByteArray {
            require(der[offset].toInt() == 0x02) { "Not a DER integer" }
            val length = der[offset + 1].toInt() and 0xff
            val value = der.copyOfRange(offset + 2, offset + 2 + length)
            offset += 2 + length
            val stripped = value.dropWhile { it.toInt() == 0 }.toByteArray()
            require(stripped.size <= 32) { "Signature integer too large" }
            return ByteArray(32 - stripped.size) + stripped
        }
        val r = integer()
        val s = integer()
        return r + s
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == AudioLive.SIGNATURE_BYTES)
        fun integer(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
            var value = bytes.copyOfRange(start, bytes.size)
            if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

/** How a reader that understands the marker classifies a stream offer. */
enum class StreamOfferKind { VIDEO, AUDIO_ONLY, MALFORMED }

/**
 * Bit 6 of `capabilitySummary` is the audio-only marker only as exactly `0x40` on a stream offer. Any other
 * value with bit 6 set, or `0x40` on something that is not a stream offer, is [StreamOfferKind.MALFORMED]
 * (ignored by the Watch list). Everything without bit 6 is a video offer as before.
 */
fun DiscoveryAnnouncement.streamOfferKind(): StreamOfferKind {
    val bit6 = capabilitySummary and DiscoveryAnnouncement.SUMMARY_AUDIO_ONLY != 0
    return when {
        !bit6 -> StreamOfferKind.VIDEO
        !isStreamOffer() -> StreamOfferKind.MALFORMED
        capabilitySummary != DiscoveryAnnouncement.SUMMARY_AUDIO_ONLY -> StreamOfferKind.MALFORMED
        streamWidth != 0 || streamHeight != 0 || streamFps != 0 -> StreamOfferKind.MALFORMED
        else -> StreamOfferKind.AUDIO_ONLY
    }
}

/** A well-formed audio-only stream offer ([StreamOfferKind.AUDIO_ONLY]). */
fun DiscoveryAnnouncement.isAudioOnlyOffer(): Boolean = streamOfferKind() == StreamOfferKind.AUDIO_ONLY

/** The Opus packet rules of this profile (RFC 6716 section 3.1 table-of-contents byte). */
object AudioLiveOpus {
    /**
     * True for exactly one 20 ms mono frame: TOC code 0 (one frame in the packet), stereo bit clear, and a
     * configuration whose frame duration is 20 ms (SILK 1, 5, 9; hybrid 13, 15; CELT 19, 23, 27, 31).
     * Anything else (other durations, two frames, arbitrary frame counts, padding, stereo) is rejected before
     * the packet reaches a decoder, because RFC 6716 allows up to 120 ms per packet.
     */
    fun isSingle20msMonoFrame(unit: ByteArray): Boolean {
        if (unit.isEmpty() || unit.size > AudioLive.MAX_OPUS_UNIT_BYTES) return false
        val toc = unit[0].toInt() and 0xff
        val config = toc ushr 3
        val stereo = toc and 0x4 != 0
        val code = toc and 0x3
        return code == 0 && !stereo && config in TWENTY_MS_CONFIGS
    }

    private val TWENTY_MS_CONFIGS = setOf(1, 5, 9, 13, 15, 19, 23, 27, 31)

    /** The OpusHead of a configuration record must describe mono, version 1, mapping family 0. */
    fun isMonoConfig(payload: ByteArray): Boolean = try {
        val head = OpusCodecConfig.normalize(listOf(payload)).first()
        head.size >= 19 && head[8].toInt() == 1 && head[9].toInt() == 1 && head[18].toInt() == 0
    } catch (_: IllegalArgumentException) {
        false
    }
}

/** A refilling budget: [capacity] units, [refillPerSecond] units per second. */
internal class TokenBudget(
    private val capacity: Double,
    private val refillPerSecond: Double,
    private val clockMs: () -> Long,
) {
    private var tokens = capacity
    private var lastMs = clockMs()

    fun tryTake(amount: Double): Boolean {
        val now = clockMs()
        tokens = minOf(capacity, tokens + (now - lastMs).coerceAtLeast(0) * refillPerSecond / 1_000.0)
        lastMs = now
        if (tokens < amount) return false
        tokens -= amount
        return true
    }
}

/**
 * Receiver-side rules for an audio-only (BTL1) stream, applied to every media record after the header
 * (PROTOCOL "Media", "Ingress budgets", "Clock rules"). Every violation throws [IllegalArgumentException] and
 * the stream fails closed. One instance per stream; a reader thread owns it.
 */
class AudioLiveStreamGuard internal constructor(
    private val verifier: AudioLiveVerifier,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var codecConfig: ByteArray? = null
    private var configRecords = 0

    /** Short stream label for the BENCHMARK lines (set by the reader; empty in tests). */
    var label: String = ""

    /** `test_audio_live_digests`: log one per-unit hash line per record (see [AudioLiveDigest]). */
    var digests: Boolean = AudioLiveTunables.unitDigests

    private var verifiedRecords = 0L
    private var failedRecords = 0L
    private var lastUnits: List<ByteArray>? = null
    private var lastFirstPtsUs = 0L
    private var lastRecordSeq = -1L
    private var previousEndUs: Long? = null
    private var previousSeq = -1L

    // Burst allowance: how far the media position may run ahead of arrival time (a draining backlog).
    private var maxOffsetMs: Long? = null

    private val recordBudget = TokenBudget(RECORD_BURST, RECORDS_PER_SECOND, clockMs)
    private val byteBudget = TokenBudget(BYTE_BURST, BYTES_PER_SECOND, clockMs)
    private val controlBudget = TokenBudget(CONTROL_BURST, CONTROL_PER_SECOND, clockMs)

    /** A lifecycle record (pause, resume, end) is rate limited, and restarts the media-clock baseline. */
    fun onLifecycleRecord() {
        require(controlBudget.tryTake(1.0)) { "Lifecycle records arrive too fast" }
        previousEndUs = null
        maxOffsetMs = null
        previousSeq = -1L
    }

    /** Checked on the decrypted record before any parsing or copying of the packet. */
    fun checkSealedSize(plaintextBytes: Int) {
        require(plaintextBytes <= AudioLive.MAX_RECORD_PAYLOAD_BYTES + StreamProtocol.PACKET_HEADER_BYTES) {
            "Audio record of $plaintextBytes bytes exceeds the stream limit"
        }
    }

    /**
     * Verifies the source's signature, applies the ingress budgets and the media rules, and returns the packet
     * as the unsigned pipeline expects it (same flags and timestamp, signature and sequence removed).
     */
    fun open(packet: StreamProtocol.Packet): StreamProtocol.Packet {
        lastUnits = null
        val opened = try {
            openRecord(packet)
        } catch (error: Exception) {
            failedRecords++
            AudioLiveLog.info(verifyLine("rejected", error.message))
            throw error
        }
        verifiedRecords++
        val units = lastUnits
        if (digests && units != null) {
            AudioLiveLog.info(AudioLiveDigest.line("rx", label, lastRecordSeq, lastFirstPtsUs, units))
        }
        if (verifiedRecords % VERIFY_LOG_EVERY == 0L) AudioLiveLog.info(verifyLine("progress", null))
        return opened
    }

    /** One BENCHMARK line per [VERIFY_LOG_EVERY] verified records, and one per rejected record. */
    private fun verifyLine(event: String, reason: String?): String =
        "BENCHMARK audio_live_verify: stream=$label | event=$event | verified=$verifiedRecords | " +
            "failures=$failedRecords | lastSeq=$lastRecordSeq" +
            (reason?.let { " | reason=${it.take(100).replace('|', '/')}" } ?: "")

    private fun openRecord(packet: StreamProtocol.Packet): StreamProtocol.Packet {
        val flags = packet.flags
        require(flags and StreamProtocol.FLAG_AUDIO != 0) { "Video packet on an audio-only stream" }
        require(flags and DISALLOWED_FLAGS == 0) { "Flag $flags is not valid on an audio-only stream" }
        require(packet.payload.size <= AudioLive.MAX_RECORD_PAYLOAD_BYTES) {
            "Audio record of ${packet.payload.size} bytes exceeds the stream limit"
        }
        require(recordBudget.tryTake(1.0)) { "Audio records arrive too fast" }
        require(byteBudget.tryTake(packet.payload.size.toDouble())) { "Audio bytes arrive too fast" }
        val opened = verifier.open(packet)
        val body = opened.packet
        if (flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
            require(flags == (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG)) {
                "An Opus configuration cannot be batched"
            }
            require(++configRecords <= MAX_CONFIG_RECORDS) { "Too many Opus configuration records" }
            require(AudioLiveOpus.isMonoConfig(body.payload)) { "Opus configuration is not mono" }
            val known = codecConfig
            if (known == null) {
                codecConfig = body.payload.copyOf()
            } else {
                require(Arrays.equals(known, body.payload)) { "Opus configuration changed on an audio-only stream" }
            }
            return body
        }
        val units = if (flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) {
            StreamProtocol.splitAudioPair(body)
        } else {
            listOf(body)
        }
        require(units.size in AudioLive.MIN_BATCH_FRAMES..AudioLive.MAX_BATCH_FRAMES) { "Invalid audio unit count" }
        // Duration: every unit is exactly one 20 ms mono frame and the units are contiguous.
        val firstUs = units.first().presentationTimeUs
        for ((index, unit) in units.withIndex()) {
            require(AudioLiveOpus.isSingle20msMonoFrame(unit.payload)) { "Not a single 20 ms mono Opus frame" }
            require(unit.presentationTimeUs == firstUs + index * AudioLive.FRAME_US) { "Audio units are not contiguous 20 ms steps" }
        }
        checkGap(firstUs, opened.seq)
        previousEndUs = firstUs + units.size * AudioLive.FRAME_US
        previousSeq = opened.seq
        checkBurst(firstUs)
        lastUnits = units.map { it.payload }
        lastFirstPtsUs = firstUs
        lastRecordSeq = opened.seq
        return body
    }

    /**
     * Gap: the step from the end of the previous record to the start of this one must be explained by dropped
     * records (a shed leaves a sequence gap) plus a small slack; a pause or resume restarts the baseline
     * through [onLifecycleRecord]. Replaces the former flat "1 s step" rule that valid shedding violated.
     */
    private fun checkGap(firstUs: Long, seq: Long) {
        val end = previousEndUs ?: return
        val step = firstUs - end
        require(step >= 0) { "Audio timestamps overlap by ${-step} us" }
        val dropped = (seq - previousSeq - 1).coerceAtLeast(0)
        val allowed = minOf(GAP_SLACK_US + dropped * MAX_RECORD_SPAN_US, MAX_GAP_US)
        require(step <= allowed) { "Audio timestamp gap $step us is not explained by $dropped dropped records" }
    }

    /** Burst: the media position may lead the arrival clock by at most [BURST_ALLOWANCE_MS] (a draining backlog). */
    private fun checkBurst(firstUs: Long) {
        val offset = clockMs() - firstUs / 1_000L
        val peak = maxOf(maxOffsetMs ?: offset, offset)
        maxOffsetMs = peak
        require(peak - offset <= BURST_ALLOWANCE_MS) { "Audio clock runs ${peak - offset} ms ahead of arrival" }
    }

    companion object {
        /** One verification summary line per this many verified records (10 s of Voice at 200 ms). */
        const val VERIFY_LOG_EVERY = 50L
        const val MAX_CONFIG_RECORDS = 4
        const val RECORDS_PER_SECOND = 60.0
        const val RECORD_BURST = 600.0
        const val BYTES_PER_SECOND = 16_000.0
        const val BYTE_BURST = 512.0 * 1024
        const val CONTROL_PER_SECOND = 4.0
        const val CONTROL_BURST = 40.0
        const val GAP_SLACK_US = 100_000L
        const val MAX_RECORD_SPAN_US = AudioLive.MAX_BATCH_FRAMES * AudioLive.FRAME_US
        const val MAX_GAP_US = 60_000_000L
        const val BURST_ALLOWANCE_MS = 32_000L
        private const val DISALLOWED_FLAGS = StreamProtocol.FLAG_KEY_FRAME or
            StreamProtocol.FLAG_DISPLAY_ROTATION or StreamProtocol.FLAG_DEBUG_INTEGRITY or
            StreamProtocol.FLAG_DISCONTINUITY or StreamProtocol.FLAG_DISCARDABLE
    }
}

/**
 * Age shedding predicates (Design AL review 18). The two namespaces share bit positions with different
 * meanings (inner packet bit 4 is the debug-integrity flag, mesh bit 4 is "control"), so each side has its own
 * exact-match predicate and nothing tests a mixed mask.
 */
object AudioLiveShed {
    /** Source: plaintext inner packet flags. Only an ordinary audio record or an audio batch, nothing else. */
    fun isSourceRecordSheddable(innerFlags: Int): Boolean =
        innerFlags == StreamProtocol.FLAG_AUDIO ||
            innerFlags == (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR)

    /**
     * Relay: the flags of a sealed mesh envelope, of an audio-only stream. [MeshEnvelope.FLAG_AUDIO] alone
     * (a single unit) or with the mirrored inner pair bit 6 (a pair or batch). A control or terminal
     * envelope, a codec configuration or a header can never match.
     */
    fun isRelayEnvelopeSheddable(envelope: MeshEnvelope, streamIsAudioOnly: Boolean): Boolean =
        streamIsAudioOnly &&
            (envelope.flags == MeshEnvelope.FLAG_AUDIO || envelope.flags == (MeshEnvelope.FLAG_AUDIO or MESH_AUDIO_RECORD_BIT))

    /** The inner `FLAG_AUDIO_PAIR` bit as it appears in mesh flags (the mesh namespace has no name for it). */
    const val MESH_AUDIO_RECORD_BIT = 64
}

/**
 * Penalties for an audio-only marker that disagrees with the header (Design AL review 17). Offers are
 * unauthenticated, so a mismatch is only evidence about the route that was attempted. A penalty keys on the
 * advertising route (address, prefix, route token), expires, and the table is bounded. A mismatch seen on an
 * authenticated link also penalises that authenticated node ID, for a longer time. The logical stream
 * (prefix or token alone) is never penalised.
 */
class StreamRoutePenalties(
    private val routeTtlMs: Long = ROUTE_TTL_MS,
    private val nodeTtlMs: Long = NODE_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    data class Route(val address: String, val prefixHex: String, val routeToken: Int)

    private val routes = LinkedHashMap<Route, Long>()
    private val nodes = LinkedHashMap<NodeId, Long>()

    /** [authenticatedNode] is non-null only when the header came over an authenticated link from that node. */
    @Synchronized
    fun onMismatch(route: Route, authenticatedNode: NodeId?, nowMs: Long) {
        prune(nowMs)
        routes.remove(route)
        routes[route] = nowMs + routeTtlMs
        if (authenticatedNode != null) {
            nodes.remove(authenticatedNode)
            nodes[authenticatedNode] = nowMs + nodeTtlMs
        }
        while (routes.size > maxEntries) routes.remove(routes.keys.first())
        while (nodes.size > maxEntries) nodes.remove(nodes.keys.first())
    }

    @Synchronized
    fun isPenalized(route: Route, authenticatedNode: NodeId?, nowMs: Long): Boolean {
        prune(nowMs)
        return route in routes || (authenticatedNode != null && authenticatedNode in nodes)
    }

    private fun prune(nowMs: Long) {
        routes.values.removeAll { it <= nowMs }
        nodes.values.removeAll { it <= nowMs }
    }

    companion object {
        const val ROUTE_TTL_MS = 120_000L
        const val NODE_TTL_MS = 600_000L
        const val MAX_ENTRIES = 64
    }
}
