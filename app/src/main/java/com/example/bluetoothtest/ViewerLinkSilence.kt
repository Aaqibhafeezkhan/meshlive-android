package com.example.bluetoothtest

/**
 * Viewer link silence watch (switch `viewer_link_silence`, default on; DEBUG `test_viewer_link_silence=false` is the
 * control arm).
 *
 * A viewer reads its parent link with a blocking read that has no timeout (BluetoothSocket and the GATT pipe both
 * block until data or close). Only a relayed CoC/Classic child had a watch on that read ([relayParentSilent], the
 * relay parent-link watch). A direct viewer, and a GATT viewer, whose parent link stays up but delivers nothing
 * would show a frozen picture until the user left: neither side sees an error, because the viewer's heartbeats still
 * reach the source (which keeps the source's own child-heartbeat timeout quiet) and the source's writes still return.
 *
 * This watch closes such a link after [SILENCE_NS] without a single byte, so the ordinary link-loss recovery
 * reconnects; closing the viewer's end is also what tells the source to drop the dead child. It judges only a video
 * stream that has started (the authenticated header arrived) and is live with its camera on: a healthy source then
 * sends a frame every 1/FPS (66 ms at 15 FPS), so 8 s of total silence is 120 missing frames, not jitter. A paused
 * stream, a camera-off media state and audio-only live (whose Experimental DTX may legitimately send nothing) never
 * judge, and the window restarts when such a state ends.
 *
 * Pure: the clock and the last-read time are passed in.
 */
internal class ViewerLinkSilenceWatch(private val silenceNs: Long = SILENCE_NS) {
    /** Start of the window that may be judged; 0 until the first tick. */
    private var judgedFromNs = 0L

    /**
     * Returns the silent time in milliseconds when the link should be closed, else null.
     * [lastReadNs] is when a read last returned data (0 before the first), [mediaStarted] that the stream header
     * arrived, and [silenceExpected] that the source said nothing needs to flow (pause, camera off, audio-only).
     */
    fun onTick(nowNs: Long, lastReadNs: Long, mediaStarted: Boolean, silenceExpected: Boolean): Long? {
        if (!mediaStarted || silenceExpected) {
            judgedFromNs = nowNs
            return null
        }
        if (judgedFromNs == 0L) judgedFromNs = nowNs
        val silentNs = nowNs - maxOf(lastReadNs, judgedFromNs)
        return if (silentNs >= silenceNs) silentNs / 1_000_000L else null
    }

    companion object {
        /** Equal to the source's CoC/Classic child-heartbeat timeout ([receiverHeartbeatTimeoutNs]). */
        const val SILENCE_NS = 8_000_000_000L
        const val CHECK_MS = 500L

        /**
         * Whether this watch runs on a viewer link: when on, and the link is not already watched by the relay
         * parent-link watch (one silence watch per link).
         */
        fun applies(enabled: Boolean, relayParentWatchRunning: Boolean): Boolean = enabled && !relayParentWatchRunning

        fun benchmarkLine(silentMs: Long, transport: BluetoothTransportOption, routeCost: Int): String =
            "BENCHMARK viewer_link_silence: silentMs=$silentMs | transport=${transport.name} | " +
                "route=${if (routeCost > 0) "relayed" else "direct"} | thresholdMs=${SILENCE_NS / 1_000_000L} | action=close"
    }
}
