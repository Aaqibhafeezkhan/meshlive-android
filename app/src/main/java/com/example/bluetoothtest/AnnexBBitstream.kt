package com.example.bluetoothtest

/**
 * Pure-Kotlin Annex B plumbing for the HEVC / H.264 access-unit validators (PROTOCOL "Draft: HEVC and H.264
 * video codecs", "Access-unit validation"). Written from ITU-T H.264 and H.265 only. No Android imports.
 */
internal enum class AnnexBCodec(val protocolId: Int, val label: String) {
    H264(4, "H264"),
    HEVC(5, "HEVC");

    companion object {
        fun of(protocolId: Int): AnnexBCodec? = values().firstOrNull { it.protocolId == protocolId }
    }
}

/** One NAL unit: [type] from its header, [bytes] the escaped NAL including the header and without a start code. */
internal class NalUnit(val type: Int, val bytes: ByteArray)

/** A violated validation rule (PROTOCOL rule number 1..11). Carries no stack trace: rejects are expected input. */
internal class RuleViolation(val rule: Int, message: String, val nalType: Int = -1) :
    RuntimeException(message, null, false, false)

internal sealed class AnnexBScan {
    class Ok(val nals: List<NalUnit>) : AnnexBScan()

    /** [partial] holds the NAL units found before the failure (used only to recognise an IDR). */
    class Fail(val reason: String, val partial: List<NalUnit>) : AnnexBScan()
}

/** Common parsed parameter-set facts shared by both codecs. */
internal class SpsInfo(
    val id: Int,
    /** HEVC VPS id; -1 for H.264. */
    val vpsId: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val unusual: List<Pair<String, Long>>,
)

internal class PpsInfo(val id: Int, val spsId: Int, val numExtraSliceHeaderBits: Int)

/** [intra] is true for an I slice, false for a P slice. */
internal class SliceInfo(val ppsId: Int, val intra: Boolean)

/** Per-codec syntax. Implemented by [HevcAccessUnitValidator] and [AvcAccessUnitValidator]. */
internal interface AnnexBSyntax {
    val codec: AnnexBCodec
    val headerBytes: Int
    val vpsType: Int
    val spsType: Int
    val ppsType: Int
    fun nalType(first: Byte): Int
    fun headerValid(nal: ByteArray): Boolean
    fun isAllowed(type: Int): Boolean
    fun isSlice(type: Int): Boolean
    fun isIdr(type: Int): Boolean

    /** SEI, AUD and filler: removed before decoding, contents never parsed. */
    fun isStripped(type: Int): Boolean

    /** True for types that must follow the slice (filler, HEVC suffix SEI). */
    fun isTrailing(type: Int): Boolean
    fun parseVps(nal: ByteArray): Int
    fun parseSps(nal: ByteArray, env: SizeEnvelope): SpsInfo
    fun parsePps(nal: ByteArray): PpsInfo
    fun parseSlice(nal: ByteArray, type: Int, numExtraSliceHeaderBits: Int): SliceInfo

    companion object {
        fun of(codec: AnnexBCodec): AnnexBSyntax = when (codec) {
            AnnexBCodec.HEVC -> HevcAccessUnitValidator
            AnnexBCodec.H264 -> AvcAccessUnitValidator
        }
    }
}

/**
 * Per-axis session envelope (rule 6): the larger coded side against the larger header side and the smaller
 * against the smaller (either orientation), each header side rounded up to a multiple of 16 (H.264 macroblocks).
 * HEVC uses [requireHevc] instead: coding-tree padding up to 63 samples per axis.
 */
internal class SizeEnvelope(headerMaxWidth: Int, headerMaxHeight: Int) {
    private val big: Long
    private val small: Long
    private val headerBig: Long = maxOf(headerMaxWidth, headerMaxHeight).toLong()
    private val headerSmall: Long = minOf(headerMaxWidth, headerMaxHeight).toLong()

    init {
        val w = roundUp16(headerMaxWidth.toLong())
        val h = roundUp16(headerMaxHeight.toLong())
        big = maxOf(w, h)
        small = minOf(w, h)
    }

    /**
     * HEVC rule 6 (widened 2026-10-28 before HEVC shipped; DECISIONS "Design HC", "HEVC coded-size padding"): an
     * encoder may pad each coded axis to its coding-tree size and crop back, as NVIDIA's Windows HEVC encoder does
     * (426x240 coded 448x256). Each coded side must be a multiple of the smallest coding block (8, the minimum
     * MinCbSizeY), exceed its cropped side by less than 64 (the largest CTB), and the cropped size must fit the header
     * per axis (either orientation). So the coded size stays below the header plus 64 per axis.
     */
    fun requireHevc(codedWidth: Long, codedHeight: Long, visibleWidth: Int, visibleHeight: Int) {
        if (codedWidth % HEVC_MIN_CB != 0L || codedHeight % HEVC_MIN_CB != 0L) {
            throw RuleViolation(6, "HEVC coded size ${codedWidth}x$codedHeight not a multiple of $HEVC_MIN_CB")
        }
        if (codedWidth - visibleWidth >= HEVC_MAX_CTB || codedHeight - visibleHeight >= HEVC_MAX_CTB) {
            throw RuleViolation(
                6, "HEVC coded size ${codedWidth}x$codedHeight pads ${visibleWidth}x$visibleHeight by $HEVC_MAX_CTB or more",
            )
        }
        if (maxOf(visibleWidth, visibleHeight) > headerBig || minOf(visibleWidth, visibleHeight) > headerSmall) {
            throw RuleViolation(6, "cropped size ${visibleWidth}x$visibleHeight outside the header size")
        }
    }

    fun accepts(codedWidth: Long, codedHeight: Long): Boolean =
        codedWidth > 0 && codedHeight > 0 && maxOf(codedWidth, codedHeight) <= big && minOf(codedWidth, codedHeight) <= small

    /** Visible size after cropping with checked (Long) arithmetic; both sides must stay at least 16. */
    fun visible(coded: Long, cropA: Long, cropB: Long, unit: Int, what: String): Int {
        val v = coded - unit.toLong() * (cropA + cropB)
        if (v < MIN_VISIBLE) throw RuleViolation(6, "$what visible size $v below $MIN_VISIBLE")
        return v.toInt()
    }

    fun requireCoded(codedWidth: Long, codedHeight: Long) {
        if (!accepts(codedWidth, codedHeight)) {
            throw RuleViolation(6, "coded size ${codedWidth}x$codedHeight outside the session envelope")
        }
    }

    companion object {
        const val MIN_VISIBLE = 16L
        /** HEVC: the smallest legal MinCbSizeY; every legal coded width and height is a multiple of it. */
        const val HEVC_MIN_CB = 8L
        /** HEVC: the largest coding tree block (64x64); padding must stay below it per axis. */
        const val HEVC_MAX_CTB = 64L
        val UNBOUNDED = SizeEnvelope(Int.MAX_VALUE, Int.MAX_VALUE)
        private fun roundUp16(v: Long): Long = if (v <= 0) 0 else (v + 15) / 16 * 16
    }
}

/** Bit reader over an already unescaped, budget-limited RBSP. Every failure throws [RuleViolation] of [rule]. */
internal class BitReader(private val data: ByteArray, private val rule: Int, private val nalType: Int = -1) {
    private var bit = 0L
    private val totalBits = data.size.toLong() * 8

    private fun fail(msg: String): Nothing = throw RuleViolation(rule, msg, nalType)

    fun bitsLeft(): Long = totalBits - bit

    fun flag(): Boolean = u(1) == 1

    /** n in 0..31. */
    fun u(n: Int): Int {
        require(n in 0..31)
        return bits(n).toInt()
    }

    /** n in 0..32. */
    fun bits(n: Int): Long {
        if (n == 0) return 0
        if (bit + n > totalBits) fail("read past end or parse budget")
        var v = 0L
        var left = n
        while (left > 0) {
            val byteIndex = (bit shr 3).toInt()
            val bitInByte = (bit and 7).toInt()
            val take = minOf(left, 8 - bitInByte)
            val cur = data[byteIndex].toInt() and 0xFF
            val chunk = (cur shr (8 - bitInByte - take)) and ((1 shl take) - 1)
            v = (v shl take) or chunk.toLong()
            bit += take
            left -= take
        }
        return v
    }

    fun skip(n: Int) {
        if (n < 0 || bit + n > totalBits) fail("skip past end or parse budget")
        bit += n
    }

    /** Unsigned Exp-Golomb: more than 31 leading zeros rejects (so the value always fits 32 bits). */
    fun ue(): Long {
        var zeros = 0
        while (true) {
            if (bit >= totalBits) fail("ue past end or parse budget")
            val b = (data[(bit shr 3).toInt()].toInt() shr (7 - (bit and 7).toInt())) and 1
            bit++
            if (b == 1) break
            zeros++
            if (zeros > 31) fail("ue with more than 31 leading zeros")
        }
        val suffix = bits(zeros)
        return (1L shl zeros) - 1 + suffix
    }

    /** ue(v) that must not exceed [max]. */
    fun ue(max: Int): Int {
        val v = ue()
        if (v > max) fail("ue value $v above $max")
        return v.toInt()
    }

    /** Signed Exp-Golomb (32-bit range). */
    fun se(): Long {
        val k = ue()
        return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
    }
}

internal object AnnexB {
    const val MAX_NALS_PER_ACCESS_UNIT = 8
    const val PARAMETER_SET_BUDGET = 512
    const val SLICE_HEADER_BUDGET = 64
    const val MAX_PARAMETER_SET_NAL_BYTES = 1024

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun nalType(codec: AnnexBCodec, first: Byte): Int {
        val b = first.toInt() and 0xFF
        return if (codec == AnnexBCodec.HEVC) (b shr 1) and 0x3F else b and 0x1F
    }

    fun headerBytes(codec: AnnexBCodec): Int = if (codec == AnnexBCodec.HEVC) 2 else 1

    /**
     * PROTOCOL rule 1 and "Scanning and escapes". The payload starts with a 3- or 4-byte start code. Inside a NAL
     * unit `00 00 03` followed by 00..03 is an escape, `00 00 03` as the last bytes is allowed, `00 00 03` followed
     * by a byte above 03 and `00 00 02` reject, `00 00 00` and `00 00 01` end the NAL unit (zero bytes before a
     * start code are trailing zeros). Stops at the first NAL unit beyond [maxNals].
     */
    fun scan(payload: ByteArray, codec: AnnexBCodec, maxNals: Int = MAX_NALS_PER_ACCESS_UNIT): AnnexBScan {
        val n = payload.size
        if (n == 0) return AnnexBScan.Fail("empty_payload", emptyList())
        val out = ArrayList<NalUnit>()
        var s = startCodeEnd(payload, 0)
        if (s < 0) return AnnexBScan.Fail("no_start_code", out)
        val minNal = headerBytes(codec)
        while (true) {
            if (out.size >= maxNals) return AnnexBScan.Fail("too_many_nals", out)
            var k = s
            var end = -1
            var next = -1
            scanLoop@ while (k < n) {
                if (payload[k].toInt() != 0 || k + 1 >= n || payload[k + 1].toInt() != 0) {
                    k++
                    continue
                }
                if (k + 2 >= n) {
                    end = k
                    break
                }
                when (payload[k + 2].toInt() and 0xFF) {
                    0 -> {
                        var q = k + 2
                        while (q < n && payload[q].toInt() == 0) q++
                        if (q >= n) {
                            end = k
                        } else if (payload[q].toInt() == 1) {
                            end = k
                            next = q + 1
                        } else {
                            return AnnexBScan.Fail("forbidden_zero_run", out)
                        }
                        break@scanLoop
                    }
                    1 -> {
                        end = k
                        next = k + 3
                        break@scanLoop
                    }
                    2 -> return AnnexBScan.Fail("forbidden_000002", out)
                    3 -> {
                        if (k + 3 < n && (payload[k + 3].toInt() and 0xFF) > 3) {
                            return AnnexBScan.Fail("bad_escape", out)
                        }
                        k += 3
                    }
                    else -> k += 3
                }
            }
            if (end < 0) {
                end = n
                while (end > s && payload[end - 1].toInt() == 0) end--
            }
            if (end - s < minNal) return AnnexBScan.Fail("short_nal", out)
            out.add(NalUnit(nalType(codec, payload[s]), payload.copyOfRange(s, end)))
            if (next < 0) break
            s = next
        }
        return AnnexBScan.Ok(out)
    }

    /** Index just after a leading `00 00 01` / `00 00 00 01` at [at], else -1. */
    private fun startCodeEnd(b: ByteArray, at: Int): Int {
        if (b.size >= at + 3 && b[at].toInt() == 0 && b[at + 1].toInt() == 0 && b[at + 2].toInt() == 1) return at + 3
        if (b.size >= at + 4 && b[at].toInt() == 0 && b[at + 1].toInt() == 0 && b[at + 2].toInt() == 0 && b[at + 3].toInt() == 1) {
            return at + 4
        }
        return -1
    }

    /**
     * Unescaped bytes of [nal] after its [from]-byte header, at most [budget] of them (the parse budget: a field
     * beyond it reads past the returned array and rejects).
     */
    fun unescape(nal: ByteArray, from: Int, budget: Int): ByteArray {
        val out = ByteArray(minOf(budget, maxOf(0, nal.size - from)))
        var o = 0
        var zeros = 0
        var i = from
        while (i < nal.size && o < out.size) {
            val b = nal[i].toInt() and 0xFF
            i++
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[o++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    fun parameterSetReader(nal: ByteArray, codec: AnnexBCodec, rule: Int, type: Int): BitReader =
        BitReader(unescape(nal, headerBytes(codec), PARAMETER_SET_BUDGET), rule, type)

    fun sliceReader(nal: ByteArray, codec: AnnexBCodec, type: Int): BitReader =
        BitReader(unescape(nal, headerBytes(codec), SLICE_HEADER_BUDGET), 4, type)

    fun withStartCode(nal: ByteArray): ByteArray {
        val o = ByteArray(4 + nal.size)
        o[3] = 1
        System.arraycopy(nal, 0, o, 4, nal.size)
        return o
    }
}
