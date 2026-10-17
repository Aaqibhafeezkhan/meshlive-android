package com.example.bluetoothtest

/**
 * Decoder configure-format ladder (family backlog D-03/D-04, Experimental): which low-latency and
 * priority keys a decoder format carries, from the most to the least aggressive set. The first
 * rung that configures wins; a failed configure drops the newest key first (Moonlight
 * setDecoderLowLatencyOptions, open-headunit DecoderConfigLadder, WebRTC/Media3 one-key-at-a-time rule).
 *
 * Layers, oldest first: [KEY_LOW_LATENCY] (only when the component declares FEATURE_LowLatency, as
 * before), then vendor keys (only the Qualcomm/Exynos/generic keys the component lists in
 * getSupportedVendorParameters(), API 31+; MediaTek's format keys by component name), then
 * KEY_PRIORITY=0. Never KEY_OPERATING_RATE (Media3 M-30, Moonlight L-5: configure crashes).
 *
 * A configure that does not throw is not proof the component acted (open-headunit): the effect is judged
 * by the `decoder_behaviour` log lines (decode latency p99, decoded vs rendered frames), not by this list.
 * Evidence for these keys is AVC/HEVC; VP9/AV1 vendor support is unconfirmed.
 */
internal object DecoderLowLatencyLadder {
    /** MediaFormat.KEY_LOW_LATENCY (API 30). */
    const val KEY_LOW_LATENCY = "low-latency"

    /** MediaFormat.KEY_PRIORITY (API 23): 0 = real-time. */
    const val KEY_PRIORITY = "priority"

    /** Qualcomm C2/OMX decoder (hardware/qcom sdm845 omx_vdec_extensions.hpp; Moonlight L-6/L-10). */
    const val VENDOR_QTI = "vendor.qti-ext-dec-low-latency.enable"

    /** Exynos RTC decoder low-latency option (Moonlight L-8/L-10). */
    const val VENDOR_RTC = "vendor.rtc-ext-dec-low-latency.enable"

    /** Generic vendor key (Amlogic; Moonlight L-9/L-10). */
    const val VENDOR_GENERIC = "vendor.low-latency.enable"

    /** Vendor keys that are set to 1 when the component lists them. */
    val KNOWN_VENDOR_KEYS: List<String> = listOf(VENDOR_QTI, VENDOR_RTC, VENDOR_GENERIC)

    /**
     * MediaTek's modified ACodec reads these format keys (OMX index LowLatencyDecode, VdecNoReorderMode,
     * VdecUseClearMotion). Set by component name because they are not listed as vendor parameters;
     * ClearMotion interpolation adds latency and inserts frames that corrupt frame-timing measurements.
     */
    val MEDIATEK_FORMAT_KEYS: List<Pair<String, Int>> =
        listOf("vdec-lowlatency" to 1, "vdec-no-record" to 1, "use-clearmotion-mode" to 0)

    /** Names of the MediaTek decoders the format keys are tried on (OMX builds; c2.mtk unverified). */
    fun isMediaTekDecoder(componentName: String): Boolean =
        componentName.startsWith("OMX.MTK.") || componentName.startsWith("c2.mtk.")

    /** One configure format: its extra integer [keys] and a short [label] for the log. */
    data class Rung(val label: String, val keys: Map<String, Int>)

    /**
     * The rungs to try for [componentName], most keys first. With [ladder] and [priority] both false
     * this is exactly one rung, KEY_LOW_LATENCY when [featureLowLatency] and [preferLowLatency] (the
     * behaviour before this ladder existed, including its single attempt).
     * [vendorParameters] is the component's getSupportedVendorParameters() (null: API < 31 or unread).
     */
    fun plan(
        componentName: String,
        featureLowLatency: Boolean,
        preferLowLatency: Boolean,
        ladder: Boolean,
        priority: Boolean,
        vendorParameters: Collection<String>?,
    ): List<Rung> {
        val standard = linkedMapOf<String, Int>()
        if (featureLowLatency && preferLowLatency) standard[KEY_LOW_LATENCY] = 1
        if (!ladder && !priority) return listOf(Rung(label(standard.keys, false, false), standard))

        val vendor = linkedMapOf<String, Int>()
        if (ladder) {
            val listed = vendorParameters?.toSet().orEmpty()
            KNOWN_VENDOR_KEYS.filter { it in listed }.forEach { vendor[it] = 1 }
            if (isMediaTekDecoder(componentName)) MEDIATEK_FORMAT_KEYS.forEach { (key, value) -> vendor[key] = value }
        }
        val rungs = ArrayList<Rung>(4)
        fun add(keys: Map<String, Int>, hasVendor: Boolean, hasPriority: Boolean) {
            val rung = Rung(label(keys.keys, hasVendor, hasPriority), keys)
            if (rungs.none { it.keys == rung.keys }) rungs += rung
        }
        val withPriority = if (priority) LinkedHashMap(standard + vendor).also { it[KEY_PRIORITY] = 0 } else null
        if (withPriority != null) add(withPriority, vendor.isNotEmpty(), true)
        if (vendor.isNotEmpty()) add(LinkedHashMap(standard + vendor), true, false)
        add(standard, false, false)
        // The plain format is the last resort once any experimental key was in play.
        add(emptyMap(), false, false)
        return rungs
    }

    private fun label(keys: Set<String>, vendor: Boolean, priority: Boolean): String {
        val parts = ArrayList<String>()
        if (KEY_LOW_LATENCY in keys) parts += "low-latency"
        if (vendor) parts += "vendor(${keys.filter { it != KEY_LOW_LATENCY && it != KEY_PRIORITY }.joinToString("+")})"
        if (priority) parts += "priority0"
        return parts.joinToString("+").ifEmpty { "plain" }
    }

    /** Vendor parameter names worth logging as evidence of what a component offers. */
    private val INTERESTING = Regex("latency|lowlat|clearmotion|picture-order|reorder|norecord|no-record", RegexOption.IGNORE_CASE)

    fun interestingVendorParameters(vendorParameters: Collection<String>?): List<String> =
        vendorParameters.orEmpty().filter { INTERESTING.containsMatchIn(it) }.sorted()
}
