package com.example.bluetoothtest

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Relay child stall guard (default on; DEBUG launch extra `test_relay_child_stall_guard=false` is the
 * control arm and restores the previous behaviour exactly).
 *
 * Why (HCI snoops, relayfix-0725a213 diagM1/diagP2 and mesh4ab-52681dbf-loop14b-1, 2026-10-11): a relay is
 * peripheral to its parent and central to its child, and both links settle at the same 15 ms interval
 * with a fixed relative anchor phase. On the Exynos 9611 controller (M31) a child anchor that falls in the
 * wrong part of the parent's 15 ms cycle (about 11.3 ms or more after the parent anchor, so under about
 * 4 ms before the next one) leaves the child link without a single completed exchange: all 9 deaths
 * logged `Disconnection_Complete 0x08` 5.03-5.14 s after the last completed packet, which came
 * 77-92 ms after the interval update to 15 ms. Nothing the app can send gets through, so the relay held
 * its only child slot for the whole supervision timeout (5.0 s, 333 intervals) and then reported
 * `GATT video receiver disconnected (status 8)`, which the establishment tracker did not count.
 * Healthy links answered the first hop hello in 59-214 ms (p50 116 ms; 30 measured); the one slower
 * reply (1.24 s) came over a link that was starving at 6 kbps.
 *
 * Rule: from the moment the child link is usable until the end-to-end handshake finishes, a link that
 * delivers no inbound byte for [DEFAULT_STALL_MS] is closed by the relay. Before hop authentication the
 * failure counts as an establishment failure (RelayChildEstablishment, threshold unchanged), so a child
 * that keeps landing on a dead anchor is handed to another parent by the existing fallback instead of
 * looping on 5.9 s attempts. This does not change which anchor a connection gets; it only shortens a dead
 * attempt from about 5.9 s after the connection to about 3 s and makes it visible to the fallback. The
 * media phase is not covered (RelayChildStarvation, RelayParentTrickle and the withdrawal policy own it).
 */
internal class RelayChildStallGuard(
    startedAtMs: Long,
    private val stallMs: Long = DEFAULT_STALL_MS,
) {
    private var lastProgressMs = startedAtMs
    private val startedMs = startedAtMs
    private var maxSilentMs = 0L
    private var completedAtMs: Long? = null

    /** Inbound bytes arrived at [nowMs]. */
    @Synchronized
    fun onProgress(nowMs: Long) {
        maxSilentMs = maxOf(maxSilentMs, nowMs - lastProgressMs)
        lastProgressMs = maxOf(lastProgressMs, nowMs)
    }

    /** The end-to-end handshake finished; the guard stops judging. */
    @Synchronized
    fun complete(nowMs: Long) {
        if (completedAtMs == null) {
            maxSilentMs = maxOf(maxSilentMs, nowMs - lastProgressMs)
            completedAtMs = nowMs
        }
    }

    @Synchronized
    fun isOpen(): Boolean = completedAtMs == null

    /** Silent milliseconds when the guard is still open and the link has been silent for [stallMs], else null. */
    @Synchronized
    fun stalledFor(nowMs: Long): Long? {
        if (completedAtMs != null) return null
        return (nowMs - lastProgressMs).takeIf { it >= stallMs }
    }

    @Synchronized
    fun handshakeMs(): Long? = completedAtMs?.let { it - startedMs }

    /** Longest silent gap seen while the guard was open: the false-positive margin of [stallMs]. */
    @Synchronized
    fun maxSilentMs(): Long = maxSilentMs

    companion object {
        /**
         * Over 9x the slowest hello reply on a link that was not starving (214 ms) and 1.6x the slowest
         * on a starving one (1.24 s, already unusable for a stream); the supervision timeout is 5 s.
         */
        const val DEFAULT_STALL_MS = 2_000L
        const val POLL_MS = 250L
    }
}

/** Reports every successful read so [RelayChildStallGuard] sees inbound progress without touching the framing. */
internal class ProgressInputStream(
    delegate: InputStream,
    private val onBytes: () -> Unit,
) : FilterInputStream(delegate) {
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) onBytes()
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) onBytes()
        return count
    }
}

/**
 * A stall before hop authentication is a link-establishment failure for the tracker (same effect as a
 * GATT 133 or HCI 0x3E); after hop authentication the existing rule (no count) stands.
 */
internal fun isRelayStallEstablishmentFailure(stalled: Boolean, hopAuthenticated: Boolean): Boolean =
    stalled && !hopAuthenticated
