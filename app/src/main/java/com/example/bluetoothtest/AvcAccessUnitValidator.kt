package com.example.bluetoothtest

/**
 * H.264 (ITU-T H.264) syntax for the access-unit gate: NAL header and type rules (2, 3), SPS (9), PPS (10) and the
 * slice header prefix (4). Reads only the fields PROTOCOL names, within the parse budget.
 */
internal object AvcAccessUnitValidator : AnnexBSyntax {
    const val SLICE = 1
    const val SLICE_IDR = 5
    const val SEI = 6
    const val SPS = 7
    const val PPS = 8
    const val AUD = 9
    const val FILLER = 12

    override val codec = AnnexBCodec.H264
    override val headerBytes = 1
    override val vpsType = -1
    override val spsType = SPS
    override val ppsType = PPS

    override fun nalType(first: Byte): Int = AnnexB.nalType(AnnexBCodec.H264, first)

    override fun headerValid(nal: ByteArray): Boolean = nal.isNotEmpty() && (nal[0].toInt() and 0x80) == 0

    override fun isAllowed(type: Int): Boolean = when (type) {
        SLICE, SLICE_IDR, SEI, SPS, PPS, AUD, FILLER -> true
        else -> false
    }

    override fun isSlice(type: Int): Boolean = type == SLICE || type == SLICE_IDR
    override fun isIdr(type: Int): Boolean = type == SLICE_IDR
    override fun isStripped(type: Int): Boolean = type == SEI || type == AUD || type == FILLER
    override fun isTrailing(type: Int): Boolean = type == FILLER

    override fun parseVps(nal: ByteArray): Int = throw RuleViolation(3, "H.264 has no VPS", nalType(nal[0]))

    /** Rules 6 and 9. */
    override fun parseSps(nal: ByteArray, env: SizeEnvelope): SpsInfo {
        val r = AnnexB.parameterSetReader(nal, AnnexBCodec.H264, 9, SPS)
        val unusual = ArrayList<Pair<String, Long>>(2)
        val profile = r.u(8)
        if (profile != 66 && profile != 77 && profile != 100) {
            throw RuleViolation(9, "profile_idc $profile not 66, 77 or 100", SPS)
        }
        r.skip(8) // constraint_set flags and reserved bits
        val level = r.u(8)
        if (level > 62) throw RuleViolation(9, "level_idc $level above 62", SPS)
        if (level > 41) unusual.add("level_idc" to level.toLong())
        val spsId = r.ue(31)
        if (profile == 100) {
            if (r.ue() != 1L) throw RuleViolation(9, "chroma_format_idc not 1", SPS)
            if (r.ue() != 0L) throw RuleViolation(9, "bit_depth_luma not 8", SPS)
            if (r.ue() != 0L) throw RuleViolation(9, "bit_depth_chroma not 8", SPS)
            r.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (r.flag()) { // seq_scaling_matrix_present_flag
                for (i in 0 until 8) {
                    if (r.flag()) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue(12) // log2_max_frame_num_minus4
        val pocType = r.ue(2)
        when (pocType) {
            0 -> r.ue(12) // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                r.skip(1) // delta_pic_order_always_zero_flag
                r.se() // offset_for_non_ref_pic
                r.se() // offset_for_top_to_bottom_field
                val cycle = r.ue(255) // num_ref_frames_in_pic_order_cnt_cycle
                for (i in 0 until cycle) r.se()
            }
        }
        val maxRef = r.ue(16)
        if (maxRef > 4) unusual.add("max_num_ref_frames" to maxRef.toLong())
        r.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMbs = r.ue() + 1
        if (!r.flag()) throw RuleViolation(9, "frame_mbs_only_flag not 1", SPS)
        r.skip(1) // direct_8x8_inference_flag
        val width = widthMbs * 16
        val height = heightMbs * 16
        env.requireCoded(width, height)
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
        if (pocType != 2) {
            if (!r.flag()) throw RuleViolation(9, "poc type $pocType without VUI", SPS) // vui_parameters_present_flag
            if (!vuiReorderZero(r)) throw RuleViolation(9, "poc type $pocType without max_num_reorder_frames 0", SPS)
        }
        val visW = env.visible(width, cropL, cropR, 2, "width")
        val visH = env.visible(height, cropT, cropB, 2, "height")
        return SpsInfo(spsId, -1, width.toInt(), height.toInt(), visW, visH, unusual)
    }

    /** scaling_list(): at most 64 se(v) entries. */
    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                val delta = r.se()
                if (delta < -128 || delta > 127) throw RuleViolation(9, "delta_scale $delta out of range", SPS)
                next = ((last + delta.toInt() + 256) % 256)
            }
            last = if (next == 0) last else next
        }
    }

    private fun skipHrd(r: BitReader) {
        val cpbCnt = r.ue(31) + 1 // cpb_cnt_minus1 + 1, at most 32
        r.skip(8) // bit_rate_scale, cpb_size_scale
        for (i in 0 until cpbCnt) {
            r.ue() // bit_rate_value_minus1
            r.ue() // cpb_size_value_minus1
            r.skip(1) // cbr_flag
        }
        r.skip(20) // four 5-bit length fields
    }

    /** Parses the VUI up to bitstream_restriction; true when bitstream_restriction_flag is 1 and max_num_reorder_frames 0. */
    private fun vuiReorderZero(r: BitReader): Boolean {
        if (r.flag()) { // aspect_ratio_info_present_flag
            if (r.u(8) == 255) r.skip(32) // Extended_SAR
        }
        if (r.flag()) r.skip(1) // overscan_info_present_flag, overscan_appropriate_flag
        if (r.flag()) { // video_signal_type_present_flag
            r.skip(4) // video_format, video_full_range_flag
            if (r.flag()) r.skip(24) // colour_description_present_flag
        }
        if (r.flag()) { // chroma_loc_info_present_flag
            r.ue()
            r.ue()
        }
        if (r.flag()) r.skip(65) // timing_info_present_flag: 32 + 32 + fixed_frame_rate_flag
        val nal = r.flag()
        if (nal) skipHrd(r)
        val vcl = r.flag()
        if (vcl) skipHrd(r)
        if (nal || vcl) r.skip(1) // low_delay_hrd_flag
        r.skip(1) // pic_struct_present_flag
        if (!r.flag()) return false // bitstream_restriction_flag
        r.skip(1) // motion_vectors_over_pic_boundaries_flag
        r.ue() // max_bytes_per_pic_denom
        r.ue() // max_bits_per_mb_denom
        r.ue() // log2_max_mv_length_horizontal
        r.ue() // log2_max_mv_length_vertical
        return r.ue() == 0L // max_num_reorder_frames
    }

    /** Rule 10. */
    override fun parsePps(nal: ByteArray): PpsInfo {
        val r = AnnexB.parameterSetReader(nal, AnnexBCodec.H264, 10, PPS)
        val id = r.ue(255)
        val spsId = r.ue(31)
        r.skip(1) // entropy_coding_mode_flag
        r.skip(1) // bottom_field_pic_order_in_frame_present_flag
        if (r.ue() != 0L) throw RuleViolation(10, "num_slice_groups_minus1 not 0", PPS)
        r.ue() // num_ref_idx_l0_default_active_minus1
        r.ue() // num_ref_idx_l1_default_active_minus1
        r.skip(1) // weighted_pred_flag
        r.skip(2) // weighted_bipred_idc
        r.se() // pic_init_qp_minus26
        r.se() // pic_init_qs_minus26
        r.se() // chroma_qp_index_offset
        r.skip(1) // deblocking_filter_control_present_flag
        r.skip(1) // constrained_intra_pred_flag
        if (r.flag()) throw RuleViolation(10, "redundant_pic_cnt_present_flag not 0", PPS)
        return PpsInfo(id, spsId, 0)
    }

    /** Rule 4. */
    override fun parseSlice(nal: ByteArray, type: Int, numExtraSliceHeaderBits: Int): SliceInfo {
        val r = AnnexB.sliceReader(nal, AnnexBCodec.H264, type)
        if (r.ue() != 0L) throw RuleViolation(4, "first_mb_in_slice not 0", type)
        val sliceType = r.ue()
        val intra = when (sliceType) {
            0L, 5L -> false
            2L, 7L -> true
            else -> throw RuleViolation(4, "slice_type $sliceType not P or I", type)
        }
        if (isIdr(type) != intra) throw RuleViolation(4, "slice_type $sliceType does not fit NAL type $type", type)
        val ppsId = r.ue(255)
        return SliceInfo(ppsId, intra)
    }
}
