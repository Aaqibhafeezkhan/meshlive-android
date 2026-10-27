package com.example.bluetoothtest

/**
 * Experimental (default off, DEBUG `test_relay_join_key_frame`): one source-side tracker per direct child link. A
 * keyframe request from a relay-capable child (it may have children of its own) that follows at least [CALM_MS] without
 * a request from that child is honoured at once, bypassing the encoder's shared recovery back-off
 * ([KeyFrameRequestBackoff]: 1 s doubling to 8 s).
 *
 * Why: a viewer that joins through a forwarder needs a keyframe from the source. The forwarder regenerates its new
 * child's request toward the source, which cannot tell it from a loss recovery and applies the back-off. Saved runs
 * (2026-10-26): authenticated-to-stream-header was a median 402 ms on direct joins (whose keyframe is forced at
 * peer-ready) but 1127 ms through one relay (p90 3484 ms) and 2248 ms through two. After a calm gap, a request from a
 * relay child is most likely such a join. The bound is at most one forced keyframe per relay child per [CALM_MS]: a
 * child that keeps asking more often never forces one. Wire-compatible: requests and keyframes are unchanged
 * (Design JK is the tagged alternative). Off: every request takes the back-off as before.
 */
internal class RelayJoinKeyFrame(startedAtMs: Long, private val calmMs: Long = CALM_MS) {
    /** The child's peer-ready keyframe is already forced, so the calm window starts at authentication. */
    private var lastRequestAtMs = startedAtMs

    /** Records one request; true when it must bypass the back-off (forced keyframe). */
    @Synchronized
    fun onRequest(nowMs: Long, enabled: Boolean, childRelays: Boolean): Boolean {
        val calm = nowMs - lastRequestAtMs >= calmMs
        lastRequestAtMs = nowMs
        return enabled && childRelays && calm
    }

    companion object {
        /** Twice the back-off's 1 s floor. */
        const val CALM_MS = 2_000L
    }
}
