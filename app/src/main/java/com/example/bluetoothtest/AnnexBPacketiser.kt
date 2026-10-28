package com.example.bluetoothtest

/**
 * Sender side of codecs 4 (H.264) and 5 (HEVC) (PROTOCOL "Sender rules"): turns MediaCodec output into one
 * Annex B access unit per `BTV3` video packet. Parameter sets are cached (newest wins), stripped from every AU
 * and prepended to every IDR; SEI, AUD and filler are removed; the keyframe flag comes from the NAL type.
 * Not thread-safe.
 */
internal class AnnexBPacketiser(val codec: AnnexBCodec) {
    private val syntax = AnnexBSyntax.of(codec)

    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var parsed: ParameterSets? = null
    private var parsedDirty = false
    private var mediaSinceConfig = false
    private var lastPtsUs = Long.MIN_VALUE
    private var haveLastPts = false

    /** Why the cached sets do not parse (null when they do or none are cached); for logging. */
    var configProblem: String? = null
        private set

    /** The complete, parsed set, or null until a complete VPS?/SPS/PPS set is cached and parses. */
    val cachedSets: ParameterSets?
        get() {
            if (parsedDirty) reparse()
            return parsed
        }

    /**
     * A MediaCodec BUFFER_FLAG_CODEC_CONFIG buffer or output-format csd (Annex B, may hold several NAL units).
     * Consecutive config buffers merge into one cached set; config after media starts a new cache.
     */
    fun onCodecConfig(bytes: ByteArray) {
        if (mediaSinceConfig) {
            vps = null
            sps = null
            pps = null
            mediaSinceConfig = false
            parsedDirty = true
        }
        val scan = AnnexB.scan(bytes, codec, MAX_CONFIG_NALS)
        val nals = (scan as? AnnexBScan.Ok)?.nals ?: run {
            configProblem = "malformed codec config: ${(scan as AnnexBScan.Fail).reason}"
            return
        }
        for (nal in nals) absorb(nal)
    }

    fun onAccessUnit(bytes: ByteArray, presentationTimeUs: Long): PacketiserResult {
        val scan = AnnexB.scan(bytes, codec, MAX_INPUT_NALS)
        val nals = when (scan) {
            is AnnexBScan.Ok -> scan.nals
            is AnnexBScan.Fail -> return PacketiserResult.Error("malformed", scan.reason)
        }
        // Any coded-slice NAL type the receiver does not accept (HEVC CRA, H.264 data partitions) is refused here.
        nals.firstOrNull { isVcl(it.type) && !syntax.isSlice(it.type) }?.let {
            return PacketiserResult.Error("unsupported_nal", "slice NAL type ${it.type}")
        }
        val slices = nals.filter { syntax.isSlice(it.type) }
        if (slices.isEmpty()) {
            // Possibly a config-only buffer arriving as media: keep any sets it carries.
            nals.forEach { absorb(it) }
            mediaSinceConfig = true
            return PacketiserResult.Error("no_slice")
        }
        if (slices.size > 1) return PacketiserResult.Error("multi_slice")
        for (nal in nals) {
            if (!syntax.isSlice(nal.type) && !isParameterSet(nal.type) && !syntax.isStripped(nal.type)) {
                return PacketiserResult.Error("unsupported_nal", "NAL type ${nal.type}")
            }
        }
        val slice = slices[0]
        // In-band sets update the cache whether or not this AU is accepted below.
        nals.forEach { absorb(it) }
        mediaSinceConfig = true
        if (haveLastPts && presentationTimeUs <= lastPtsUs) return PacketiserResult.Error("reorder")
        val keyFrame = syntax.isIdr(slice.type)
        val out: ByteArray
        var size: Pair<Int, Int>? = null
        if (keyFrame) {
            val sets = cachedSets
            if (sets == null) return PacketiserResult.Error("no_parameter_sets", configProblem ?: "")
            val parts = ArrayList<ByteArray>(4)
            sets.vps?.let { parts.add(it) }
            parts.add(sets.sps)
            parts.add(sets.pps)
            parts.add(slice.bytes)
            out = join(parts)
            size = sets.codedWidth to sets.codedHeight
        } else {
            out = join(listOf(slice.bytes))
        }
        lastPtsUs = presentationTimeUs
        haveLastPts = true
        return PacketiserResult.Packet(out, keyFrame, size)
    }

    private fun isVcl(type: Int) = if (codec == AnnexBCodec.HEVC) type <= 31 else type in 1..5

    private fun isParameterSet(type: Int) = type == syntax.vpsType || type == syntax.spsType || type == syntax.ppsType

    private fun absorb(nal: NalUnit) {
        when {
            nal.type == syntax.vpsType -> if (vps?.contentEquals(nal.bytes) != true) { vps = nal.bytes; parsedDirty = true }
            nal.type == syntax.spsType -> if (sps?.contentEquals(nal.bytes) != true) { sps = nal.bytes; parsedDirty = true }
            nal.type == syntax.ppsType -> if (pps?.contentEquals(nal.bytes) != true) { pps = nal.bytes; parsedDirty = true }
        }
    }

    private fun reparse() {
        parsedDirty = false
        parsed = null
        configProblem = null
        val s = sps ?: return
        val p = pps ?: return
        if (syntax.vpsType >= 0 && vps == null) return
        try {
            parsed = ParameterSetParser.build(syntax, vps, s, p, SizeEnvelope.UNBOUNDED)
        } catch (v: RuleViolation) {
            configProblem = "rule ${v.rule}: ${v.message}"
        }
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        var n = 0
        for (p in parts) n += 4 + p.size
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            out[o + 3] = 1
            System.arraycopy(p, 0, out, o + 4, p.size)
            o += 4 + p.size
        }
        return out
    }

    private companion object {
        const val MAX_CONFIG_NALS = 16
        const val MAX_INPUT_NALS = 32
    }
}

internal sealed class PacketiserResult {
    class Packet(
        val payload: ByteArray,
        val keyFrame: Boolean,
        /** Visible size from the SPS (cropping applied), keyframes only. */
        val codedSize: Pair<Int, Int>?,
    ) : PacketiserResult()

    /** [reason]: reorder, no_parameter_sets, no_slice, multi_slice, unsupported_nal or malformed. */
    class Error(val reason: String, val detail: String = "") : PacketiserResult()
}
