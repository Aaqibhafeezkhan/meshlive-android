package com.example.bluetoothtest

/**
 * Experimental temporal-layer thinning (root-cause workstream A, 2026-10-07; removed under the
 * no-frame-loss rule the same day and restored 2026-10-08 as an Experimental option that is off by
 * default: DebugTunables.experimentalTemporalLayers). With the experiment off no frame is ever
 * marked discardable and no queue thins, so the no-loss pipeline is unchanged.
 *
 * Before layering, every congestion response dropped reference frames: the decoder then needed a
 * recovery keyframe, which is the largest frame of the stream (40-136 KB on coverage content), so
 * the same congested link overflowed again and the stream froze in a keyframe loop. A one-viewer
 * slowdown in a fan-out forced the source to either slow everyone or release that viewer.
 *
 * A discardable frame (temporal layer 1: nothing references it and it refreshes no decoder state)
 * can be dropped by the sender, any relay or the receiver without a keyframe. A queue that is
 * already behind drops these first, so a congested link degrades to a smooth base-layer frame rate
 * while healthy links keep the full rate. Measured costs that made it experimental: droppable frames
 * cost ~2.5 VMAF at the same bitrate, and any thinning is frame loss.
 */
internal object TemporalLayerThinning {
    /**
     * Queue age at which discardable frames are skipped: half of the Automatic playout delay's
     * starting value, so thinning acts well before receivers have to stretch their playout.
     */
    val THIN_BACKLOG_MS: Long = PlaybackDelayProfile.AUTO.delayMs / 2L

    fun shouldThin(discardable: Boolean, backlogAgeMs: Long): Boolean =
        discardable && backlogAgeMs >= THIN_BACKLOG_MS
}

/**
 * Measures how much of a stream's video bytes are in the base layer (frames that are not
 * discardable). With layered video an extra viewer or relay child only has to be guaranteed the
 * base layer; this share is measured from the actual frames, never assumed from the encoder request.
 */
internal class LayerShareMeter {
    private var videoBytes = 0L
    private var discardableBytes = 0L

    @Synchronized
    fun add(bytes: Int, discardable: Boolean) {
        videoBytes += bytes
        if (discardable) discardableBytes += bytes
    }

    /** Base-layer fraction of video bytes, or null while no discardable frame has been seen. */
    @Synchronized
    fun baseShare(): Double? =
        if (discardableBytes == 0L || videoBytes == 0L) null else 1.0 - discardableBytes.toDouble() / videoBytes
}

/**
 * Droppable frames cost ~2.5 VMAF at the same bitrate (every other frame cannot be a reference:
 * native AV1 CoC 1000 82.9 vs 85.5 single-layer, thinning-8d460c9a), so they are produced only
 * while some link needs them: at start unless a stored measurement shows room, and while any direct
 * link of this source is tight (thinning, a congested backlog outside large-frame grace, offered load
 * above the child-measured link, or the child reporting that media on this link arrives late). Over
 * the link they removed every freeze (CoC 1000: 4 -> 0, audio drops 358 -> 49). After
 * [RELAXED_HOLD_NS] with room everywhere the encoder uses every frame as a reference again.
 *
 * Hop-local (2026-10-08 restore): the retired multi-hop "downstream tight" flag is not used; only
 * this phone's own links and its direct children's reports about those links count.
 */
internal class LayeringPolicy(
    /** This phone's stored measurement for the transport, used until the child reports. */
    private val priorLinkKbps: Int? = null,
    initialOfferedKbps: Int = 0,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var lastTightNs: Long

    var layered: Boolean
        private set

    init {
        // Start layered unless a stored measurement already shows room for the requested rate.
        val roomy = priorLinkKbps != null && initialOfferedKbps <= priorLinkKbps * TIGHT_LOAD_FRACTION
        layered = !roomy
        lastTightNs = if (roomy) nanoTime() - RELAXED_HOLD_NS else nanoTime()
    }

    /** Returns the new state when it changes. */
    @Synchronized
    fun onSample(sample: LinkRateSample): Boolean? {
        val now = nanoTime()
        val offeredKbps = if (sample.windowNs > 0) sample.offeredBytes * 8 * 1_000_000L / sample.windowNs else 0L
        // Sustained evidence only: a scene-cut backlog that drains (large-frame grace) is not a
        // reason to pay ~2.5 VMAF for 20 s (CoC 600 kbps fell from 87.4 to 85.1, adaptive-36fc9abf).
        // childLate is the child's hop-local report about this link (its playout delay pinned or
        // still growing); the no-loss pipeline has no overflow drops left to count.
        val tight = sample.thinnedFrames > 0 || sample.childLate ||
            sample.backlogAgeMs >= LinkRateController.CONGESTED_BACKLOG_MS && !sample.largeFrameGrace ||
            linkKbps(sample) <= 0 ||
            offeredKbps > linkKbps(sample) * TIGHT_LOAD_FRACTION
        if (tight) lastTightNs = now
        val next = tight || now - lastTightNs < RELAXED_HOLD_NS
        if (next == layered) return null
        layered = next
        return next
    }

    /**
     * Best known capacity: the child's train median reads 10-25% low on CoC and its first report
     * comes from startup trains (GATT read 786 then 996 kbps), so the load trigger only fires above
     * what this link or phone has ever been measured to carry; delivery evidence covers the rest.
     */
    private fun linkKbps(sample: LinkRateSample): Int = maxOf(sample.measuredLinkKbps, priorLinkKbps ?: 0)

    companion object {
        /**
         * Offered above what the child measured. 0.75 kept GATT 900 kbps (93% of a 1.0 Mbps link)
         * layered for VMAF 87.7 vs 90.2, although two single-layer runs there had no freezes.
         */
        const val TIGHT_LOAD_FRACTION = 1.0
        const val RELAXED_HOLD_NS = 20_000_000_000L
    }
}
