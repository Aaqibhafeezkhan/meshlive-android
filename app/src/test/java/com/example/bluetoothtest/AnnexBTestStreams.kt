package com.example.bluetoothtest

import java.io.ByteArrayOutputStream

/** Test-only bit writer: u(n), ue, se, flags, RBSP trailing bits and emulation-prevention insertion. */
internal class TestBitWriter {
    private val out = ByteArrayOutputStream()
    private var cur = 0
    private var used = 0

    fun u(bits: Int, value: Long): TestBitWriter {
        for (i in bits - 1 downTo 0) bit(((value shr i) and 1L).toInt())
        return this
    }

    fun flag(b: Boolean): TestBitWriter = bit(if (b) 1 else 0)

    fun bit(b: Int): TestBitWriter {
        cur = (cur shl 1) or b
        if (++used == 8) {
            out.write(cur)
            cur = 0
            used = 0
        }
        return this
    }

    fun zeros(n: Int): TestBitWriter {
        repeat(n) { bit(0) }
        return this
    }

    fun ue(v: Long): TestBitWriter {
        val x = v + 1
        val len = 64 - java.lang.Long.numberOfLeadingZeros(x)
        zeros(len - 1)
        return u(len, x)
    }

    fun ue(v: Int): TestBitWriter = ue(v.toLong())

    fun se(v: Long): TestBitWriter = ue(if (v > 0) 2 * v - 1 else -2 * v)

    fun trailing(): TestBitWriter {
        bit(1)
        while (used != 0) bit(0)
        return this
    }

    /** Raw bytes, requires byte alignment. */
    fun bytes(): ByteArray {
        check(used == 0) { "not byte aligned" }
        return out.toByteArray()
    }
}

/** Synthetic H.264 and HEVC streams (profiles and shapes the Design HC gate accepts, and variations that it rejects). */
internal object AnnexBTestStreams {
    val SC4 = byteArrayOf(0, 0, 0, 1)
    val SC3 = byteArrayOf(0, 0, 1)

    fun escape(rbsp: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var zeros = 0
        for (x in rbsp) {
            val b = x.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) {
                o.write(3)
                zeros = 0
            }
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    fun avcNal(type: Int, rbsp: ByteArray, refIdc: Int = 3): ByteArray =
        byteArrayOf(((refIdc shl 5) or type).toByte()) + escape(rbsp)

    fun hevcNal(type: Int, rbsp: ByteArray, layerId: Int = 0, tidPlus1: Int = 1): ByteArray =
        byteArrayOf(((type shl 1) or (layerId shr 5)).toByte(), (((layerId and 31) shl 3) or tidPlus1).toByte()) + escape(rbsp)

    fun annexB(vararg nals: ByteArray, fourByte: Boolean = true): ByteArray = annexB(nals.toList(), fourByte)

    fun annexB(nals: List<ByteArray>, fourByte: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        for (n in nals) {
            o.write(if (fourByte) SC4 else SC3)
            o.write(n)
        }
        return o.toByteArray()
    }

    /** Pad a parameter-set NAL with non-zero bytes after its parsed fields (those bytes are never read). */
    fun padTo(nal: ByteArray, size: Int): ByteArray {
        require(size >= nal.size)
        return nal + ByteArray(size - nal.size) { 0x5A }
    }

    /** Non-zero slice payload bytes so that no accidental start code appears. */
    private fun sliceBody(w: TestBitWriter, body: ByteArray?, length: Int) {
        val data = body ?: ByteArray(length) { (0x40 + it % 100).toByte() }
        for (b in data) w.u(8, (b.toLong() and 0xFF))
    }

    // ---------------------------------------------------------------- H.264

    data class AvcVui(
        val aspectExtended: Boolean = false,
        val signalType: Boolean = false,
        val chromaLoc: Boolean = false,
        val timing: Boolean = false,
        val nalHrdCpb: Int = -1,
        val vclHrdCpb: Int = -1,
        val restriction: Boolean = true,
        val reorder: Long = 0,
        /** Override hrd cpb_cnt_minus1 raw value (to test the 32-entry bound). */
        val nalHrdCpbCntMinus1Raw: Long = -1,
    )

    data class AvcSps(
        val profile: Int = 66,
        val level: Int = 31,
        val spsId: Int = 0,
        val widthMbs: Int = 40,
        val heightMbs: Int = 30,
        val cropRight: Int = 0,
        val cropBottom: Int = 0,
        val pocType: Int = 2,
        val vui: AvcVui? = null,
        val maxRef: Int = 1,
        val frameMbsOnly: Boolean = true,
        val chroma: Int = 1,
        val bitDepth: Int = 8,
        val scalingLists: Int = -1,
        val scalingDelta: Long = 0,
        val pocCycle: Int = 0,
        val pocOffset: Long = 0,
        val log2PocLsbMinus4: Int = 4,
        val pad: Int = 0,
    ) {
        fun build(): ByteArray {
            val w = TestBitWriter()
            w.u(8, profile.toLong()).u(8, 0).u(8, level.toLong()).ue(spsId)
            if (profile == 100) {
                w.ue(chroma).ue(bitDepth - 8).ue(bitDepth - 8).flag(false)
                w.flag(scalingLists >= 0)
                if (scalingLists >= 0) {
                    for (i in 0 until 8) {
                        val present = i < scalingLists
                        w.flag(present)
                        if (present) {
                            var last = 8
                            var next = 8
                            for (j in 0 until (if (i < 6) 16 else 64)) {
                                if (next != 0) {
                                    w.se(scalingDelta)
                                    next = (last + scalingDelta.toInt() + 256) % 256
                                }
                                last = if (next == 0) last else next
                            }
                        }
                    }
                }
            }
            w.ue(0) // log2_max_frame_num_minus4
            w.ue(pocType)
            when (pocType) {
                0 -> w.ue(log2PocLsbMinus4)
                1 -> {
                    w.flag(false).se(0).se(0).ue(pocCycle)
                    repeat(pocCycle) { w.se(pocOffset) }
                }
            }
            w.ue(maxRef).flag(false)
            w.ue(widthMbs - 1).ue(heightMbs - 1).flag(frameMbsOnly)
            if (!frameMbsOnly) w.flag(false)
            w.flag(true) // direct_8x8_inference_flag
            val crop = cropRight != 0 || cropBottom != 0
            w.flag(crop)
            if (crop) w.ue(0).ue(cropRight).ue(0).ue(cropBottom)
            w.flag(vui != null)
            vui?.let { writeVui(w, it) }
            w.trailing()
            val nal = avcNal(7, w.bytes())
            return if (pad > 0) padTo(nal, pad) else nal
        }

        private fun writeVui(w: TestBitWriter, v: AvcVui) {
            w.flag(v.aspectExtended)
            if (v.aspectExtended) w.u(8, 255).u(16, 4).u(16, 3)
            w.flag(false) // overscan
            w.flag(v.signalType)
            if (v.signalType) w.u(3, 5).flag(false).flag(true).u(8, 1).u(8, 1).u(8, 1)
            w.flag(v.chromaLoc)
            if (v.chromaLoc) w.ue(1).ue(1)
            w.flag(v.timing)
            if (v.timing) w.u(32, 1).u(32, 60).flag(true)
            val nal = v.nalHrdCpb >= 0 || v.nalHrdCpbCntMinus1Raw >= 0
            w.flag(nal)
            if (nal) hrd(w, if (v.nalHrdCpbCntMinus1Raw >= 0) v.nalHrdCpbCntMinus1Raw else (v.nalHrdCpb - 1).toLong())
            w.flag(v.vclHrdCpb >= 0)
            if (v.vclHrdCpb >= 0) hrd(w, (v.vclHrdCpb - 1).toLong())
            if (nal || v.vclHrdCpb >= 0) w.flag(false)
            w.flag(false) // pic_struct_present_flag
            w.flag(v.restriction)
            if (v.restriction) {
                w.flag(true).ue(2).ue(1).ue(16).ue(16).ue(v.reorder).ue(1)
            }
        }

        private fun hrd(w: TestBitWriter, cpbCntMinus1: Long) {
            w.ue(cpbCntMinus1).u(4, 1).u(4, 1)
            val n = cpbCntMinus1.coerceAtMost(40)
            for (i in 0..n) w.ue(1000).ue(2000).flag(false)
            w.u(5, 23).u(5, 23).u(5, 23).u(5, 24)
        }
    }

    fun avcPps(ppsId: Int = 0, spsId: Int = 0, sliceGroupsMinus1: Int = 0, redundantPic: Boolean = false, pad: Int = 0): ByteArray {
        val w = TestBitWriter()
        w.ue(ppsId).ue(spsId).flag(false).flag(false).ue(sliceGroupsMinus1)
        if (sliceGroupsMinus1 > 0) w.ue(0) // slice_group_map_type (never parsed past)
        w.ue(0).ue(0).flag(false).u(2, 0).se(0).se(0).se(0).flag(true).flag(false).flag(redundantPic)
        w.trailing()
        val nal = avcNal(8, w.bytes())
        return if (pad > 0) padTo(nal, pad) else nal
    }

    fun avcSlice(idr: Boolean, sliceType: Int = if (idr) 7 else 5, ppsId: Int = 0, firstMb: Int = 0, length: Int = 200, body: ByteArray? = null): ByteArray {
        val w = TestBitWriter()
        w.ue(firstMb).ue(sliceType).ue(ppsId)
        sliceBody(w, body, length)
        w.trailing()
        return avcNal(if (idr) 5 else 1, w.bytes(), if (idr) 3 else 2)
    }

    fun avcSei(): ByteArray = avcNal(6, byteArrayOf(5, 4, 1, 2, 3, 4, 0x80.toByte()), 0)
    fun avcAud(): ByteArray = avcNal(9, byteArrayOf(0xF0.toByte()), 0)
    fun avcFiller(n: Int = 8): ByteArray = avcNal(12, ByteArray(n) { 0xFF.toByte() } + byteArrayOf(0x80.toByte()), 0)

    // ---------------------------------------------------------------- HEVC

    data class HevcSps(
        val vpsId: Int = 0,
        val spsId: Int = 0,
        val width: Int = 640,
        val height: Int = 480,
        val cropRight: Int = 0,
        val cropBottom: Int = 0,
        val maxSubLayersMinus1: Int = 0,
        val profileSpace: Int = 0,
        val profileIdc: Int = 1,
        val compat: Long = 0x60000000L,
        val level: Int = 120,
        val chroma: Int = 1,
        val bitDepthLuma: Int = 8,
        val bitDepthChroma: Int = 8,
        val maxDecMinus1: Int = 1,
        val reorder: Int = 0,
        val pad: Int = 0,
    ) {
        fun build(): ByteArray {
            val w = TestBitWriter()
            w.u(4, vpsId.toLong()).u(3, maxSubLayersMinus1.toLong()).flag(true)
            w.u(2, profileSpace.toLong()).flag(false).u(5, profileIdc.toLong()).u(32, compat)
            w.u(4, 9).u(32, 0).u(11, 0).u(1, 0)
            w.u(8, level.toLong())
            w.ue(spsId).ue(chroma)
            if (chroma == 3) w.flag(false)
            w.ue(width).ue(height)
            val crop = cropRight != 0 || cropBottom != 0
            w.flag(crop)
            if (crop) w.ue(0).ue(cropRight).ue(0).ue(cropBottom)
            w.ue(bitDepthLuma - 8).ue(bitDepthChroma - 8).ue(4)
            w.flag(true).ue(maxDecMinus1).ue(reorder).ue(0)
            // log2_min_luma_coding_block_size_minus3 and later fields are never read by the gate.
            w.ue(0).ue(2).ue(0).ue(2).ue(1).ue(1)
            w.trailing()
            val nal = hevcNal(33, w.bytes())
            return if (pad > 0) padTo(nal, pad) else nal
        }
    }

    fun hevcVps(vpsId: Int = 0, maxLayersMinus1: Int = 0, maxSubLayersMinus1: Int = 0, pad: Int = 0): ByteArray {
        val w = TestBitWriter()
        w.u(4, vpsId.toLong()).u(1, 1).u(1, 1).u(6, maxLayersMinus1.toLong()).u(3, maxSubLayersMinus1.toLong()).flag(true).u(16, 0xFFFF)
        w.u(8, 0x01).u(32, 0x60000000L).u(32, 0x90000000L).u(16, 0).u(8, 120)
        w.trailing()
        val nal = hevcNal(32, w.bytes())
        return if (pad > 0) padTo(nal, pad) else nal
    }

    fun hevcPps(ppsId: Int = 0, spsId: Int = 0, extraBits: Int = 0, dependent: Boolean = false, outputFlag: Boolean = false, pad: Int = 0): ByteArray {
        val w = TestBitWriter()
        w.ue(ppsId).ue(spsId).flag(dependent).flag(outputFlag).u(3, extraBits.toLong()).flag(false).ue(0).ue(0).flag(false)
        w.trailing()
        val nal = hevcNal(34, w.bytes())
        return if (pad > 0) padTo(nal, pad) else nal
    }

    fun hevcSlice(
        idr: Boolean,
        sliceType: Int = if (idr) 2 else 1,
        ppsId: Int = 0,
        firstSlice: Boolean = true,
        extraBits: Int = 0,
        nalType: Int = if (idr) 19 else 1,
        length: Int = 200,
        body: ByteArray? = null,
    ): ByteArray {
        val w = TestBitWriter()
        w.flag(firstSlice)
        if (nalType in 16..23) w.flag(false)
        w.ue(ppsId)
        repeat(extraBits) { w.flag(true) }
        w.ue(sliceType)
        sliceBody(w, body, length)
        w.trailing()
        return hevcNal(nalType, w.bytes())
    }

    fun hevcAud(): ByteArray = hevcNal(35, byteArrayOf(0x50))
    fun hevcPrefixSei(): ByteArray = hevcNal(39, byteArrayOf(5, 4, 1, 2, 3, 4, 0x80.toByte()))
    fun hevcSuffixSei(): ByteArray = hevcNal(40, byteArrayOf(5, 4, 1, 2, 3, 4, 0x80.toByte()))
    fun hevcFiller(n: Int = 8): ByteArray = hevcNal(38, ByteArray(n) { 0xFF.toByte() } + byteArrayOf(0x80.toByte()))

    // ---------------------------------------------------------------- whole sets

    class Sets(val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray) {
        fun nals(): List<ByteArray> = listOfNotNull(vps, sps, pps)
        fun annexB(): ByteArray = annexB(nals())
    }

    fun avcSets(sps: AvcSps = AvcSps(), ppsId: Int = 0) = Sets(null, sps.build(), avcPps(ppsId, sps.spsId))
    fun hevcSets(sps: HevcSps = HevcSps(), ppsId: Int = 0, extraBits: Int = 0) =
        Sets(hevcVps(sps.vpsId), sps.build(), hevcPps(ppsId, sps.spsId, extraBits))

    fun setsFor(codec: AnnexBCodec, width: Int = 640, height: Int = 480): Sets = when (codec) {
        AnnexBCodec.H264 -> {
            val wm = (width + 15) / 16
            val hm = (height + 15) / 16
            avcSets(AvcSps(widthMbs = wm, heightMbs = hm, cropRight = (wm * 16 - width) / 2, cropBottom = (hm * 16 - height) / 2))
        }
        AnnexBCodec.HEVC -> {
            val cw = (width + 7) / 8 * 8
            val ch = (height + 7) / 8 * 8
            hevcSets(HevcSps(width = cw, height = ch, cropRight = (cw - width) / 2, cropBottom = (ch - height) / 2))
        }
    }

    fun slice(codec: AnnexBCodec, idr: Boolean, ppsId: Int = 0, length: Int = 200): ByteArray = when (codec) {
        AnnexBCodec.H264 -> avcSlice(idr, ppsId = ppsId, length = length)
        AnnexBCodec.HEVC -> hevcSlice(idr, ppsId = ppsId, length = length)
    }

    /** A canonical IDR access unit: sets then slice. */
    fun idrAu(codec: AnnexBCodec, sets: Sets = setsFor(codec), ppsId: Int = 0): ByteArray =
        annexB(sets.nals() + slice(codec, true, ppsId))

    fun pAu(codec: AnnexBCodec, ppsId: Int = 0): ByteArray = annexB(slice(codec, false, ppsId))
}
