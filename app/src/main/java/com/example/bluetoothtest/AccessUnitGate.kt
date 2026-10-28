package com.example.bluetoothtest

/**
 * The receiver's fail-closed access-unit gate for codecs 4 (H.264) and 5 (HEVC) (PROTOCOL "Access-unit
 * validation"). One instance per receive session; not thread-safe (call from the packet-handling thread).
 */
internal class ParameterSets(
    val codec: AnnexBCodec,
    /** Escaped NAL bytes without start codes. */
    val vps: ByteArray?,
    val sps: ByteArray,
    val pps: ByteArray,
    /** Visible size after cropping. */
    val codedWidth: Int,
    val codedHeight: Int,
    val ppsId: Int,
    /** HEVC num_extra_slice_header_bits from the PPS (slice-header parsing); 0 for H.264. */
    val numExtraSliceHeaderBits: Int = 0,
    /** Values above the usual range, accepted and to be logged as au_unusual. */
    val unusual: List<Pair<String, Long>> = emptyList(),
) {
    /** MediaCodec csd: H.264 two buffers (SPS, PPS); HEVC one buffer (VPS, SPS, PPS), each NAL behind a 4-byte start code. */
    fun csd(): List<ByteArray> = when (codec) {
        AnnexBCodec.H264 -> listOf(AnnexB.withStartCode(sps), AnnexB.withStartCode(pps))
        AnnexBCodec.HEVC -> listOf(AnnexB.withStartCode(vps ?: ByteArray(0)) + AnnexB.withStartCode(sps) + AnnexB.withStartCode(pps))
    }

    /** Byte equality of the sets. */
    fun sameAs(other: ParameterSets?): Boolean = other != null && other.codec == codec && matches(other.vps, other.sps, other.pps)

    fun matches(otherVps: ByteArray?, otherSps: ByteArray, otherPps: ByteArray): Boolean {
        val v = vps
        val vpsEqual = if (v == null) otherVps == null else otherVps != null && v.contentEquals(otherVps)
        return vpsEqual && sps.contentEquals(otherSps) && pps.contentEquals(otherPps)
    }
}

internal sealed class AuVerdict {
    class Accept(
        /** SEI, AUD and filler removed; 4-byte start codes. */
        val accessUnit: ByteArray,
        val keyFrame: Boolean,
        /** Non-null only when this IDR changed the active configuration (including the first). */
        val activated: ParameterSets?,
        /** au_unusual field to value, reported with a changed configuration. */
        val unusual: List<Pair<String, Long>>,
    ) : AuVerdict()

    class Reject(
        /** PROTOCOL rule 1..11. */
        val rule: Int,
        /** Offending NAL type, -1 if not applicable. */
        val nalType: Int,
        /** Payload size. */
        val bytes: Int,
        val reason: String,
        /** FLAG_KEY_FRAME was set or an IDR NAL was present. */
        val keyFrame: Boolean,
    ) : AuVerdict()

    /** Unsupported stream: end the session. [reason] is codec_config, discardable or rejected_keyframes. */
    class Close(val reason: String) : AuVerdict()
}

/** Builds and validates one parameter-set group (rules 5 sizes, 6 to 10, chain resolution). */
internal object ParameterSetParser {
    fun build(syntax: AnnexBSyntax, vps: ByteArray?, sps: ByteArray, pps: ByteArray, env: SizeEnvelope): ParameterSets {
        for (nal in listOfNotNull(vps, sps, pps)) {
            if (nal.size > AnnexB.MAX_PARAMETER_SET_NAL_BYTES) {
                throw RuleViolation(5, "parameter set of ${nal.size} bytes above ${AnnexB.MAX_PARAMETER_SET_NAL_BYTES}", syntax.nalType(nal[0]))
            }
        }
        var vpsId = -1
        if (syntax.vpsType >= 0) vpsId = syntax.parseVps(vps ?: throw RuleViolation(5, "missing VPS"))
        val spsInfo = syntax.parseSps(sps, env)
        val ppsInfo = syntax.parsePps(pps)
        if (syntax.vpsType >= 0 && spsInfo.vpsId != vpsId) throw RuleViolation(5, "SPS vps id ${spsInfo.vpsId} does not name the VPS ($vpsId)")
        if (ppsInfo.spsId != spsInfo.id) throw RuleViolation(5, "PPS sps id ${ppsInfo.spsId} does not name the SPS (${spsInfo.id})")
        return ParameterSets(
            syntax.codec, vps, sps, pps, spsInfo.visibleWidth, spsInfo.visibleHeight, ppsInfo.id,
            ppsInfo.numExtraSliceHeaderBits, spsInfo.unusual,
        )
    }
}

internal class AccessUnitGate(
    val codec: AnnexBCodec,
    headerMaxWidth: Int,
    headerMaxHeight: Int,
    private val nowMs: () -> Long,
) {
    private val syntax = AnnexBSyntax.of(codec)
    private val envelope = SizeEnvelope(headerMaxWidth, headerMaxHeight)

    /** The active configuration; changes only inside a fully valid IDR AU. */
    var active: ParameterSets? = null
        private set

    /** True after a rejected keyframe until the next valid IDR: non-IDR AUs are dropped meanwhile (rule 5). */
    var waitingForIdr = false
        private set

    private var closedReason: String? = null
    private val rejectedKeyframeTimes = LongArray(3)
    private var rejectedKeyframeRun = 0
    private var candidateKeyFrame = false

    fun check(payload: ByteArray, flags: Int): AuVerdict {
        closedReason?.let { return AuVerdict.Close(it) }
        if (flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) return close("codec_config")
        if (flags and StreamProtocol.FLAG_DISCARDABLE != 0) return close("discardable")
        candidateKeyFrame = flags and StreamProtocol.FLAG_KEY_FRAME != 0
        return try {
            val verdict = evaluate(payload, flags)
            if (verdict is AuVerdict.Accept && verdict.keyFrame) {
                rejectedKeyframeRun = 0
                waitingForIdr = false
            }
            verdict
        } catch (v: RuleViolation) {
            rejected(v, payload.size)
        }
    }

    private fun close(reason: String): AuVerdict.Close {
        closedReason = reason
        return AuVerdict.Close(reason)
    }

    private fun rejected(v: RuleViolation, bytes: Int): AuVerdict {
        if (candidateKeyFrame) {
            waitingForIdr = true
            val now = nowMs()
            if (rejectedKeyframeRun < 3) {
                rejectedKeyframeTimes[rejectedKeyframeRun++] = now
            } else {
                rejectedKeyframeTimes[0] = rejectedKeyframeTimes[1]
                rejectedKeyframeTimes[1] = rejectedKeyframeTimes[2]
                rejectedKeyframeTimes[2] = now
            }
            if (rejectedKeyframeRun >= 3 && rejectedKeyframeTimes[2] - rejectedKeyframeTimes[0] <= REJECTED_KEYFRAME_WINDOW_MS) {
                return close("rejected_keyframes")
            }
        }
        return AuVerdict.Reject(v.rule, v.nalType, bytes, v.message ?: "rule ${v.rule}", candidateKeyFrame)
    }

    private fun evaluate(payload: ByteArray, flags: Int): AuVerdict {
        if (payload.isEmpty() || payload.size > StreamProtocol.MAX_PAYLOAD_BYTES) {
            throw RuleViolation(1, "payload size ${payload.size} outside 1..${StreamProtocol.MAX_PAYLOAD_BYTES}")
        }
        // Rule 1: framing and escapes.
        val nals = when (val scan = AnnexB.scan(payload, codec)) {
            is AnnexBScan.Ok -> scan.nals
            is AnnexBScan.Fail -> {
                if (scan.partial.any { syntax.isIdr(it.type) }) candidateKeyFrame = true
                throw RuleViolation(1, scan.reason)
            }
        }
        if (nals.any { syntax.isIdr(it.type) }) candidateKeyFrame = true
        // Rules 2 and 3: headers and allowed types.
        for (nal in nals) {
            if (!syntax.headerValid(nal.bytes)) throw RuleViolation(2, "NAL header not valid", nal.type)
        }
        for (nal in nals) {
            if (!syntax.isAllowed(nal.type)) throw RuleViolation(3, "NAL type ${nal.type} not allowed", nal.type)
        }
        // Rule 4: exactly one slice; parameter sets, AUD and prefix SEI before it, filler and suffix SEI after.
        val sliceIndex = nals.indexOfFirst { syntax.isSlice(it.type) }
        if (sliceIndex < 0) throw RuleViolation(4, "no slice NAL unit")
        if (nals.count { syntax.isSlice(it.type) } != 1) throw RuleViolation(4, "more than one slice NAL unit", nals[sliceIndex].type)
        for ((i, nal) in nals.withIndex()) {
            if (i < sliceIndex && syntax.isTrailing(nal.type)) throw RuleViolation(4, "NAL type ${nal.type} before the slice", nal.type)
            if (i > sliceIndex && !syntax.isTrailing(nal.type)) throw RuleViolation(4, "NAL type ${nal.type} after the slice", nal.type)
        }
        val slice = nals[sliceIndex]
        val idr = syntax.isIdr(slice.type)
        val vpsList = nals.filter { it.type == syntax.vpsType }
        val spsList = nals.filter { it.type == syntax.spsType }
        val ppsList = nals.filter { it.type == syntax.ppsType }
        // Rule 5: keyframe flag iff IDR.
        if ((flags and StreamProtocol.FLAG_KEY_FRAME != 0) != idr) {
            throw RuleViolation(5, if (idr) "IDR without the keyframe flag" else "keyframe flag without an IDR", slice.type)
        }
        var activated: ParameterSets? = null
        var unusual: List<Pair<String, Long>> = emptyList()
        if (idr) {
            val wantVps = if (syntax.vpsType >= 0) 1 else 0
            if (vpsList.size != wantVps || spsList.size != 1 || ppsList.size != 1) {
                throw RuleViolation(5, "IDR needs exactly one ${if (wantVps == 1) "VPS, " else ""}SPS and PPS")
            }
            val idxSps = nals.indexOf(spsList[0])
            val idxPps = nals.indexOf(ppsList[0])
            val idxVps = if (wantVps == 1) nals.indexOf(vpsList[0]) else -1
            if (idxVps > idxSps || idxSps > idxPps) throw RuleViolation(5, "parameter sets out of order")
            val current = active
            val newVps = vpsList.firstOrNull()?.bytes
            val sets = if (current != null && current.matches(newVps, spsList[0].bytes, ppsList[0].bytes)) {
                current // identical bytes were already validated against this session's envelope
            } else {
                ParameterSetParser.build(syntax, newVps, spsList[0].bytes, ppsList[0].bytes, envelope)
            }
            val info = syntax.parseSlice(slice.bytes, slice.type, sets.numExtraSliceHeaderBits)
            if (info.ppsId != sets.ppsId) throw RuleViolation(5, "slice PPS id ${info.ppsId} does not name the PPS (${sets.ppsId})", slice.type)
            if (sets !== current) {
                activated = sets
                unusual = sets.unusual
            }
            active = sets // atomic: only reached when the whole AU passed
        } else {
            if (vpsList.isNotEmpty() || spsList.isNotEmpty() || ppsList.isNotEmpty()) {
                throw RuleViolation(5, "non-IDR AU carries a parameter set", (vpsList + spsList + ppsList).first().type)
            }
            val current = active ?: throw RuleViolation(5, "no active configuration before the first IDR", slice.type)
            if (waitingForIdr) throw RuleViolation(5, "dropped until the next valid IDR", slice.type)
            val info = syntax.parseSlice(slice.bytes, slice.type, current.numExtraSliceHeaderBits)
            if (info.ppsId != current.ppsId) throw RuleViolation(5, "slice PPS id ${info.ppsId} is not the active PPS (${current.ppsId})", slice.type)
        }
        return AuVerdict.Accept(strip(nals), idr, activated, unusual)
    }

    /** Rule 11: SEI, AUD and filler are removed; the rest goes out behind 4-byte start codes. */
    private fun strip(nals: List<NalUnit>): ByteArray {
        var size = 0
        for (nal in nals) if (!syntax.isStripped(nal.type)) size += 4 + nal.bytes.size
        val out = ByteArray(size)
        var o = 0
        for (nal in nals) {
            if (syntax.isStripped(nal.type)) continue
            out[o + 3] = 1
            System.arraycopy(nal.bytes, 0, out, o + 4, nal.bytes.size)
            o += 4 + nal.bytes.size
        }
        return out
    }

    companion object {
        const val REJECTED_KEYFRAME_WINDOW_MS = 10_000L

        /**
         * Stateless: the parameter sets of a keyframe AU that passes rules 1-10 against the envelope, else null
         * (used for the initial decoder size and csd).
         */
        fun keyFrameSets(codec: AnnexBCodec, payload: ByteArray, headerMaxWidth: Int, headerMaxHeight: Int): ParameterSets? {
            val v = AccessUnitGate(codec, headerMaxWidth, headerMaxHeight) { 0L }.check(payload, StreamProtocol.FLAG_KEY_FRAME)
            return (v as? AuVerdict.Accept)?.activated
        }
    }
}
