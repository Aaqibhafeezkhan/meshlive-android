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

/**
 * Hold-off after the stall guard closed a child link (default on; DEBUG launch extra `test_relay_stall_holdoff=false` is the
 * control arm). A dead child anchor ends with the child still holding the dead link: its own supervision timeout runs out
 * about 5 s after its last reception, it then tears its receiver down and restarts discovery behind its route cooldown
 * (8 to 13 s), and the advertisements it shows in between belong to the receiver that is going away.
 *
 * Why (b13-audio-chain-626c4b2c music, b5-audio-music-chain, same signature in all three failing audio chains, M31 relay to an
 * A15s child): the guard closed the first link 2.0 s after the interval update (`relay_child_stall stage=hop`), the relay saw
 * the child's advertisement 3.2 s later (the receiver was already stopping: its advertising set started and stopped within
 * 6 ms) and dialed it twice, each dial a 5 s `status 133`; the stall plus the first 133 were the two establishment failures
 * that withdrew the offer for 5 minutes, so a 16 percent dead-anchor attempt (9 of 55 in relayfix-0725a213) became a
 * permanent chain failure. Dials to that child inside [HOLDOFF_MS] of a stall close are skipped (not counted); the child's
 * real next request, after its cooldown, dials on a fresh anchor phase.
 */
internal class RelayStallHoldoff(private val holdoffMs: Long = HOLDOFF_MS) {
    private val until = HashMap<String, Long>()

    @Synchronized
    fun onStall(key: String, nowMs: Long) {
        until[key] = nowMs + holdoffMs
    }

    /** Milliseconds left on [key]'s hold-off (0 when none). */
    @Synchronized
    fun remainingMs(key: String, nowMs: Long): Long {
        val end = until[key] ?: return 0L
        if (nowMs >= end) {
            until.remove(key)
            return 0L
        }
        return end - nowMs
    }

    @Synchronized
    fun onSuccess(key: String) {
        until.remove(key)
    }

    companion object {
        /** About the child's route cooldown (8 to 13 s) less the time its dead link needs to expire. */
        const val HOLDOFF_MS = 10_000L
    }
}

/** The relay's hold-off, kept across forwarders like [RelayChildEstablishment]. */
internal object RelayChildStall {
    val holdoff = RelayStallHoldoff()
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
