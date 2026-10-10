package com.example.bluetoothtest

import kotlin.math.floor

data class MeshProfile(
    val name: String,
    val summary: String,
    val resolution: VideoResolution,
    val fps: Int,
    val bitrateKbps: Int,
)

enum class MeshNodeRole(val label: String, val incomingLinks: Int) {
    SOURCE("Source", 0),
    RELAY("Relay", 1),
}

data class MeshBandwidthEstimate(
    val videoKbps: Int,
    val appHeaderKbps: Double,
    val perLinkKbps: Double,
    val sourceChildren: Int,
    val relayChildren: Int,
    val plannedLoadKbps: Double,
    val plannedUtilizationPercent: Double,
    val plannedWithinBudget: Boolean,
    val safeAggregateKbps: Double,
)

object MeshProfiles {
    val options = listOf(
        MeshProfile("Maximum branching", "360p15 / 150 kbps", VideoSettings.RESOLUTIONS[1], 15, 150),
        MeshProfile("Balanced mesh", "360p24 / 200 kbps", VideoSettings.RESOLUTIONS[1], 24, 200),
        MeshProfile("Smooth mesh", "360p30 / 200 kbps", VideoSettings.RESOLUTIONS[1], 30, 200),
        MeshProfile("Quality mesh", "360p15 / 250 kbps", VideoSettings.RESOLUTIONS[1], 15, 250),
        MeshProfile("High quality", "360p15 / 300 kbps", VideoSettings.RESOLUTIONS[1], 15, 300),
    )

    const val CUSTOM_LABEL = "Custom"

    fun labels(): List<String> = options.map { "${it.name} — ${it.summary}" } + CUSTOM_LABEL
}

object MeshBandwidthEstimator {
    const val TRANSPORT_MARGIN = 0.20

    fun safeAggregateKbps(transport: BluetoothTransportOption): Double = when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> 800.0
        BluetoothTransportOption.BLE_GATT -> 700.0
        BluetoothTransportOption.BLE_L2CAP -> 700.0
    }

    fun estimate(
        settings: VideoSettings,
        transport: BluetoothTransportOption,
        role: MeshNodeRole,
        plannedChildren: Int,
        measuredAggregateKbps: ((perLinkKbps: Double) -> Double)? = null,
        /** Experimental temporal layering only: measured base-layer share of video bytes (null when off). */
        baseLayerShare: Double? = null,
    ): MeshBandwidthEstimate {
        require(plannedChildren >= 0)
        val videoKbps = settings.bitRate / 1_000
        val appHeaderKbps = appHeaderKbps(settings.fps)
        val perLinkKbps = perLinkKbps(videoKbps, settings.fps)
        val safeAggregateKbps = measuredAggregateKbps?.invoke(perLinkKbps) ?: safeAggregateKbps(transport)
        val linkSlots = layeredLinkSlots(safeAggregateKbps, perLinkKbps, baseLayerShare?.let { share ->
            perLinkKbps((videoKbps * share).toInt(), settings.fps)
        })
        val activeLinks = role.incomingLinks + plannedChildren
        val plannedLoadKbps = activeLinks * perLinkKbps
        return MeshBandwidthEstimate(
            videoKbps = videoKbps,
            appHeaderKbps = appHeaderKbps,
            perLinkKbps = perLinkKbps,
            // Never prevent an explicitly selected one-to-one experiment. The budget only
            // controls additional fan-out.
            sourceChildren = linkSlots.coerceAtLeast(1),
            relayChildren = (linkSlots - 1).coerceAtLeast(0),
            plannedLoadKbps = plannedLoadKbps,
            plannedUtilizationPercent = plannedLoadKbps / safeAggregateKbps * 100,
            plannedWithinBudget = plannedLoadKbps <= safeAggregateKbps,
            safeAggregateKbps = safeAggregateKbps,
        )
    }
    /**
     * Links that fit [aggregateKbps]. Without a base layer (always while experimental temporal
     * layering is off) this is floor(aggregate / per-link). With layered video (workstream A) the
     * first link is planned at the full rate and every further link at its measured base layer: a
     * slow link is thinned to a smooth base frame rate instead of starving, so admission guarantees
     * the base, not the whole.
     */
    fun layeredLinkSlots(aggregateKbps: Double, perLinkKbps: Double, baseLinkKbps: Double?): Int = when {
        baseLinkKbps == null || baseLinkKbps >= perLinkKbps -> floor(aggregateKbps / perLinkKbps).toInt()
        aggregateKbps < perLinkKbps -> 0
        else -> 1 + floor((aggregateKbps - perLinkKbps) / baseLinkKbps).toInt()
    }

    /** Video bitrate one link of [linkKbps] carries alongside audio and headers (no planning margin). */
    fun videoKbpsForLink(linkKbps: Double, fps: Int): Int =
        floor(linkKbps - StreamProtocol.AUDIO_BIT_RATE / 1_000 - appHeaderKbps(fps)).toInt()

    /** Planned wire load of one media link carrying [videoKbps] at [fps] plus audio. */
    fun perLinkKbps(videoKbps: Int, fps: Int): Double =
        (videoKbps + StreamProtocol.AUDIO_BIT_RATE / 1_000 + appHeaderKbps(fps)) * (1 + TRANSPORT_MARGIN)

    private fun appHeaderKbps(fps: Int): Double =
        StreamProtocol.PACKET_HEADER_BYTES * 8.0 * (fps + AUDIO_RECORDS_PER_SECOND) / 1_000

    // Two 20 ms Opus access units share one encrypted transport record.
    private const val AUDIO_RECORDS_PER_SECOND = 25
}
