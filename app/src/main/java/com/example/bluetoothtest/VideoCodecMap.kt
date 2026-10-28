package com.example.bluetoothtest

/**
 * The one mapping table of PROTOCOL "Draft: HEVC and H.264 video codecs": `BTV3` codec ID, hello mask bit (link and
 * end-to-end hellos) and announcement `capabilitySummary` bit. The hello and summary bits differ (summary bit 3 is the
 * dual-listening hint and bit 4 relay consent), so a hello mask is never copied into a summary: every summary site goes
 * through [helloMaskToSummary] or [summaryBit], and every reader through [summaryToHelloMask].
 *
 * With both Design HC masters off no hello mask carries bits 2 or 3, so every mapped summary equals today's bytes.
 */
internal object VideoCodecMap {
    private class Row(val codecId: Int, val helloBit: Int, val summaryBit: Int, val label: String)

    private val rows = listOf(
        Row(StreamProtocol.CODEC_VP9, NodeCapabilities.CODEC_VP9, 0x01, "VP9"),
        Row(StreamProtocol.CODEC_AV1, NodeCapabilities.CODEC_AV1, 0x02, "AV1"),
        Row(StreamProtocol.CODEC_H264, NodeCapabilities.CODEC_H264, 0x04, "H264"),
        Row(StreamProtocol.CODEC_HEVC, NodeCapabilities.CODEC_HEVC, 0x80, "HEVC"),
    )

    /** Every hello bit that names a codec (bits 0-3); bits 4 and 7 are end-to-end preferences, not codecs. */
    val HELLO_CODEC_BITS: Int = rows.fold(0) { mask, row -> mask or row.helloBit }

    /** Every summary bit that names a codec (0x87). */
    val SUMMARY_CODEC_BITS: Int = rows.fold(0) { mask, row -> mask or row.summaryBit }

    fun helloBit(codecId: Int): Int = rows.firstOrNull { it.codecId == codecId }?.helloBit ?: 0

    fun summaryBit(codecId: Int): Int = rows.firstOrNull { it.codecId == codecId }?.summaryBit ?: 0

    /** Log/UI label: VP9, AV1, H264, HEVC (anything else is labelled VP9, exactly as the two-codec labels were). */
    fun label(codecId: Int): String = rows.firstOrNull { it.codecId == codecId }?.label ?: "VP9"

    /** The `BTV3` codec ID of one hello bit, or null. */
    fun codecOfHelloBit(bit: Int): Int? = rows.firstOrNull { it.helloBit == bit }?.codecId

    /** A hello codec mask as summary codec bits; preference bits (4, 7) and unknown bits are dropped. */
    fun helloMaskToSummary(helloMask: Int): Int =
        rows.fold(0) { summary, row -> if (helloMask and row.helloBit != 0) summary or row.summaryBit else summary }

    /** Summary codec bits as a hello mask (for intersecting with a decode mask); non-codec summary bits are dropped. */
    fun summaryToHelloMask(summary: Int): Int =
        rows.fold(0) { mask, row -> if (summary and row.summaryBit != 0) mask or row.helloBit else mask }

    /**
     * Whether an offer's [summary] names a codec in this phone's hello decode mask. The summary is normalised to hello
     * bits first: summary bit 3 (dual listening) never matches HEVC, and summary bit 7 (HEVC) needs decode bit 3.
     */
    fun summaryDecodable(summary: Int, localDecodeHelloMask: Int): Boolean =
        summary != 0 && summaryToHelloMask(summary) and localDecodeHelloMask != 0

    /** Design HC: codec 4 (H.264) or 5 (HEVC), whose parameter sets travel in-band in every IDR. */
    fun isInBandParameterSetCodec(codecId: Int): Boolean =
        codecId == StreamProtocol.CODEC_H264 || codecId == StreamProtocol.CODEC_HEVC

    /**
     * PROTOCOL "Access-unit validation", outcomes: a `FLAG_CODEC_CONFIG` video packet on codec 4 or 5 closes the
     * session as an unsupported stream. Receivers call this where they would collect codec configuration.
     */
    fun requireNoCodecConfig(codecId: Int, flags: Int) {
        check(!(isInBandParameterSetCodec(codecId) && flags and StreamProtocol.FLAG_CODEC_CONFIG != 0)) {
            "Unsupported ${label(codecId)} stream: codec_config packet"
        }
    }
}
