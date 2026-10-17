package com.example.bluetoothtest

/**
 * Default on for a live stream with a fixed (manual) bitrate since 2026-10-14 (design decision Q-SKIP1; control `test_phy_move_skip_backlog`,
 * SWITCHES.md): at a PHY move on a live stream sender, video that has waited in the sender's queue for more than about twice the
 * viewer's playout is dropped once, codec-safely, and a keyframe is requested. Off with Auto quality (it gave no benefit there),
 * and never for calls (their bounded-age experiment is separate) and never for audio, codec configuration or lifecycle records.
 *
 * Why: the records queued while a link crawled on the bad PHY were produced for a link that is gone. After the move to 1M the
 * ASUS source's 6.7 s queue (b8 F) drained to only 4.5 s in the next 60 s, 63 to 69 percent of the run was frozen (b10
 * 3-asus-src), and a stalled A15s source reached 65 s (b10 d2): the viewer's playout window can never absorb that, so keeping
 * it adds delay on top of the freeze. The skip loses frames that were already useless; it is not rate control, and a fixed-rate
 * stream above the new link's rate grows its queue again.
 */
internal object PhyMoveSkip {
    /**
     * True while the stream in progress has a fixed (manual) bitrate: neither the Auto ladder nor audio-only live. MainActivity sets it
     * when a stream is armed or started; calls never read it.
     */
    @Volatile var fixedRateStream = false

    /**
     * The effective switch: an explicit [override] (the DEBUG launch extra, true or false) wins, else on exactly for a fixed-rate
     * stream. Auto quality, calls and audio-only live stay off by default.
     */
    fun enabled(override: Boolean?, fixedRateStream: Boolean): Boolean = override ?: fixedRateStream

    /** Twice the Auto profile's largest playout (2 s): the age beyond which queued video cannot be shown in time. */
    const val MIN_THRESHOLD_MS = 4_000L

    /**
     * Flood guard (default on, `test_phy_move_flood_guard`): queued media this old at a PHY move is beyond anything the viewer can
     * play (its adaptive playout tops out at 2 s) and beyond what its receive path can take in one burst. b1-a5a8f056 stalled2m d2:
     * an A15s source moved to 1M with 17 s of audio and 20 s of video queued, wrote it at link speed, and the viewer's input pipe
     * overflowed 11 s later because its receive loop paces audio at real time (audio is queued behind the playback buffer); the
     * viewer then showed nothing for 70 s. Eight seconds is four times the largest playout.
     */
    const val FLOOD_STALE_MS = 8_000L

    /** Removes the records of [queue] for which [stale] holds, in order, and returns them (the iterator removal the writer's take tolerates). */
    fun <T> removeStale(queue: MutableCollection<T>, stale: (T) -> Boolean): List<T> {
        val removed = ArrayList<T>()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (stale(candidate)) {
                iterator.remove()
                removed += candidate
            }
        }
        return removed
    }

    fun floodGuard(enabled: Boolean, isStream: Boolean, oldestVideoAgeMs: Long, oldestAudioAgeMs: Long): Boolean =
        enabled && isStream && maxOf(oldestVideoAgeMs, oldestAudioAgeMs) > FLOOD_STALE_MS

    /** After the drop, non-key video is dropped until a keyframe, but never longer than this (the viewer asks for one itself). */
    const val AWAIT_KEY_FRAME_MAX_MS = 15_000L

    /** The oldest queued video's age above which the backlog is dropped: twice the viewer's playout, at least [MIN_THRESHOLD_MS]. */
    fun thresholdMs(viewerPlayoutMs: Long): Long = maxOf(MIN_THRESHOLD_MS, 2L * viewerPlayoutMs.coerceAtLeast(0L))

    fun shouldSkip(enabled: Boolean, isStream: Boolean, oldestVideoAgeMs: Long, viewerPlayoutMs: Long): Boolean =
        enabled && isStream && oldestVideoAgeMs > thresholdMs(viewerPlayoutMs)

    /**
     * After a drop with no usable keyframe left in the queue, arriving non-key video is dropped until a keyframe arrives (or
     * [AWAIT_KEY_FRAME_MAX_MS] passes), because it references frames that were just removed.
     */
    class AwaitKeyFrame {
        @Volatile private var armedAtMs = -1L

        val armed: Boolean get() = armedAtMs >= 0L

        fun arm(nowMs: Long) {
            armedAtMs = nowMs
        }

        /** True when the arriving video record must be dropped. */
        fun dropArrivingVideo(keyFrame: Boolean, nowMs: Long): Boolean {
            val since = armedAtMs
            if (since < 0L) return false
            if (keyFrame || nowMs - since > AWAIT_KEY_FRAME_MAX_MS) {
                armedAtMs = -1L
                return false
            }
            return true
        }

        fun reset() {
            armedAtMs = -1L
        }
    }
}
