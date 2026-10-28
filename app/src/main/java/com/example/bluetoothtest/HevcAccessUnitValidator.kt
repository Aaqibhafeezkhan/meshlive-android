package com.example.bluetoothtest

/**
 * HEVC (ITU-T H.265) syntax for the access-unit gate: NAL header and type rules (2, 3), VPS (7), SPS (8),
 * PPS (10) and the slice header prefix (4). Reads only the fields PROTOCOL names, within the parse budget.
 */
internal object HevcAccessUnitValidator : AnnexBSyntax {
    const val TRAIL_N = 0
    const val TRAIL_R = 1
    const val IDR_W_RADL = 19
    const val IDR_N_LP = 20
    const val VPS = 32
    const val SPS = 33
    const val PPS = 34
    const val AUD = 35
    const val FILLER = 38
    const val PREFIX_SEI = 39
    const val SUFFIX_SEI = 40

    override val codec = AnnexBCodec.HEVC
    override val headerBytes = 2
    override val vpsType = VPS
    override val spsType = SPS
    override val ppsType = PPS

    override fun nalType(first: Byte): Int = AnnexB.nalType(AnnexBCodec.HEVC, first)

    override fun headerValid(nal: ByteArray): Boolean {
        if (nal.size < 2) return false
        val b0 = nal[0].toInt() and 0xFF
        val b1 = nal[1].toInt() and 0xFF
        val forbidden = b0 shr 7
        val layerId = ((b0 and 1) shl 5) or (b1 shr 3)
        val tidPlus1 = b1 and 7
        return forbidden == 0 && layerId == 0 && tidPlus1 == 1
    }

    override fun isAllowed(type: Int): Boolean = when (type) {
        TRAIL_N, TRAIL_R, IDR_W_RADL, IDR_N_LP, VPS, SPS, PPS, AUD, FILLER, PREFIX_SEI, SUFFIX_SEI -> true
        else -> false
    }

    override fun isSlice(type: Int): Boolean = type == TRAIL_N || type == TRAIL_R || isIdr(type)
    override fun isIdr(type: Int): Boolean = type == IDR_W_RADL || type == IDR_N_LP
    override fun isStripped(type: Int): Boolean = type == AUD || type == FILLER || type == PREFIX_SEI || type == SUFFIX_SEI
    override fun isTrailing(type: Int): Boolean = type == FILLER || type == SUFFIX_SEI

    /** Rule 7. Returns the VPS id. */
    override fun parseVps(nal: ByteArray): Int {
        val r = AnnexB.parameterSetReader(nal, AnnexBCodec.HEVC, 7, VPS)
        val id = r.u(4)
        r.skip(2) // vps_base_layer_internal_flag, vps_base_layer_available_flag
        if (r.u(6) != 0) throw RuleViolation(7, "vps_max_layers_minus1 not 0", VPS)
        if (r.u(3) != 0) throw RuleViolation(7, "vps_max_sub_layers_minus1 not 0", VPS)
        return id
    }

    /** Rules 6 and 8. */
    override fun parseSps(nal: ByteArray, env: SizeEnvelope): SpsInfo {
        val r = AnnexB.parameterSetReader(nal, AnnexBCodec.HEVC, 8, SPS)
        val unusual = ArrayList<Pair<String, Long>>(2)
        val vpsId = r.u(4)
        if (r.u(3) != 0) throw RuleViolation(8, "sps_max_sub_layers_minus1 not 0", SPS)
        r.skip(1) // sps_temporal_id_nesting_flag
        // profile_tier_level with no sub-layers: 12 bytes.
        if (r.u(2) != 0) throw RuleViolation(8, "general_profile_space not 0", SPS)
        r.skip(1) // general_tier_flag
        val profileIdc = r.u(5)
        val compat = r.bits(32)
        val compatMain = (compat shr 30) and 1L == 1L // general_profile_compatibility_flag[1]
        if (profileIdc != 1 && !compatMain) throw RuleViolation(8, "not Main profile (idc=$profileIdc)", SPS)
        r.skip(48) // progressive/interlaced/non_packed/frame_only flags, 43 reserved bits, 1 more bit
        val level = r.u(8)
        if (level > 186) throw RuleViolation(8, "general_level_idc $level above 186", SPS)
        if (level > 123) unusual.add("general_level_idc" to level.toLong())
        val spsId = r.ue(15)
        if (r.ue() != 1L) throw RuleViolation(8, "chroma_format_idc not 1", SPS)
        val width = r.ue()
        val height = r.ue()
        var cropL = 0L
        var cropR = 0L
        var cropT = 0L
        var cropB = 0L
        if (r.flag()) {
            cropL = r.ue()
            cropR = r.ue()
            cropT = r.ue()
            cropB = r.ue()
        }
        if (r.ue() != 0L) throw RuleViolation(8, "bit_depth_luma not 8", SPS)
        if (r.ue() != 0L) throw RuleViolation(8, "bit_depth_chroma not 8", SPS)
        r.ue(12) // log2_max_pic_order_cnt_lsb_minus4
        r.flag() // sps_sub_layer_ordering_info_present_flag: one entry either way (max_sub_layers_minus1 == 0)
        val maxDecMinus1 = r.ue(15)
        if (maxDecMinus1 > 5) unusual.add("sps_max_dec_pic_buffering_minus1" to maxDecMinus1.toLong())
        if (r.ue() != 0L) throw RuleViolation(8, "sps_max_num_reorder_pics not 0", SPS)
        val visW = env.visible(width, cropL, cropR, 2, "width")
        val visH = env.visible(height, cropT, cropB, 2, "height")
        env.requireHevc(width, height, visW, visH)
        return SpsInfo(spsId, vpsId, width.toInt(), height.toInt(), visW, visH, unusual)
    }

    /** Rule 10. */
    override fun parsePps(nal: ByteArray): PpsInfo {
        val r = AnnexB.parameterSetReader(nal, AnnexBCodec.HEVC, 10, PPS)
        val id = r.ue(63)
        val spsId = r.ue(15)
        r.skip(1) // dependent_slice_segments_enabled_flag
        r.skip(1) // output_flag_present_flag
        val extra = r.u(3) // num_extra_slice_header_bits
        return PpsInfo(id, spsId, extra)
    }

    /** Rule 4. */
    override fun parseSlice(nal: ByteArray, type: Int, numExtraSliceHeaderBits: Int): SliceInfo {
        val r = AnnexB.sliceReader(nal, AnnexBCodec.HEVC, type)
        if (!r.flag()) throw RuleViolation(4, "first_slice_segment_in_pic_flag not 1", type)
        if (type in 16..23) r.skip(1) // no_output_of_prior_pics_flag
        val ppsId = r.ue(63)
        r.skip(numExtraSliceHeaderBits)
        val sliceType = r.ue()
        val intra = when (sliceType) {
            1L -> false
            2L -> true
            else -> throw RuleViolation(4, "slice_type $sliceType not P or I", type)
        }
        if (isIdr(type) && !intra) throw RuleViolation(4, "IDR slice_type not I", type)
        return SliceInfo(ppsId, intra)
    }
}
