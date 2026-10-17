package com.example.bluetoothtest

import java.util.concurrent.TimeUnit

/**
 * Per-link byte window (G-08, Experimental `test_gatt_byte_window`). `notifyCharacteristicChanged` and
 * `writeCharacteristic` (without response) report nothing about congestion: SUCCESS means the request reached the
 * stack, and `onNotificationSent` / `onCharacteristicWrite` mean the packet was accepted into the host queue, not that
 * it left the radio. The host queue holds about 200 ATT PDUs per fixed channel before it reports congestion
 * (l2c_utils.cc buff_quota = 100 * (tx_rate + rx_rate)); at MTU 247 that is about 49 kB, seconds of latency at
 * 100-300 kbps, and past it the stack drops silently. Waiting for the callback therefore bounds the call count, not the
 * bytes queued ahead of the next packet.
 *
 * The window bounds the bytes MeshLive keeps in that queue. With a drain model (`test_gatt_window_kbps` or a rate
 * provider) the queue estimate is a leaky bucket: bytes added on each send, drained at the modelled link rate with
 * elapsed time. Without one the estimate falls back to bytes sent and not yet reported by the stack's callback, which
 * for MeshLive's one-packet-at-a-time senders never exceeds a packet and only yields the diagnostic peak.
 *
 * Off by default: the drain rate of a link is not observable from the app, and a wrong model either starves a healthy
 * link or does nothing. The window is a switchable measurement tool, not a proven fix.
 */
internal class GattByteWindow(
    private val maxBytes: Int,
    private val drainBytesPerSecond: () -> Long,
    private val nowNs: () -> Long = System::nanoTime,
    private val sleepNs: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
) {
    private var queuedBytes = 0.0
    private var lastNs = nowNs()
    var peakBytes = 0
        private set
    var waits = 0L
        private set
    var waitedNs = 0L
        private set
    var bytesAdmitted = 0L
        private set

    val enabled: Boolean get() = maxBytes > 0

    /**
     * Blocks until [size] more bytes fit under the window, then counts them as queued. A packet larger than the window
     * passes when the queue is empty (never deadlocks). Returns the nanoseconds waited.
     */
    fun acquire(size: Int): Long {
        if (!enabled) return 0L
        var waitedHere = 0L
        while (true) {
            val waitNs = synchronized(this) {
                drainTo(nowNs())
                val fits = queuedBytes + size <= maxBytes || queuedBytes <= 0.0
                if (fits) {
                    queuedBytes += size
                    bytesAdmitted += size
                    if (queuedBytes.toInt() > peakBytes) peakBytes = queuedBytes.toInt()
                    return@synchronized 0L
                }
                val rate = drainBytesPerSecond()
                // Without a drain model only onSent frees bytes; wait for it in short slices instead of guessing.
                if (rate <= 0L) NO_MODEL_SLICE_NS else ((queuedBytes + size - maxBytes) * 1e9 / rate).toLong().coerceAtLeast(MIN_SLICE_NS)
            }
            if (waitNs == 0L) break
            sleepNs(waitNs)
            waitedHere += waitNs
            synchronized(this) { waitedNs += waitNs; waits++ }
            if (waitedHere > MAX_WAIT_NS) {
                // The model never drained the queue: do not hold the link hostage to a wrong model.
                synchronized(this) { queuedBytes = 0.0; queuedBytes += size; bytesAdmitted += size }
                return waitedHere
            }
        }
        return waitedHere
    }

    /** The stack reported the packet accepted. Frees its bytes only when no drain model runs (see the class comment). */
    fun onSent(size: Int) {
        if (!enabled) return
        synchronized(this) {
            if (drainBytesPerSecond() <= 0L) queuedBytes = (queuedBytes - size).coerceAtLeast(0.0)
        }
    }

    @Synchronized
    fun queuedEstimate(): Int {
        drainTo(nowNs())
        return queuedBytes.toInt()
    }

    private fun drainTo(now: Long) {
        val rate = drainBytesPerSecond()
        if (rate > 0L && now > lastNs) {
            queuedBytes = (queuedBytes - (now - lastNs) * rate / 1e9).coerceAtLeast(0.0)
        }
        lastNs = now
    }

    fun line(link: String): String =
        "BENCHMARK gatt_byte_window: link=$link | maxBytes=$maxBytes | drainKbps=${drainBytesPerSecond() * 8 / 1000} | " +
            "peakBytes=$peakBytes | waits=$waits | waitedMs=${waitedNs / 1_000_000L} | admittedBytes=$bytesAdmitted"

    companion object {
        private const val NO_MODEL_SLICE_NS = 5_000_000L
        private const val MIN_SLICE_NS = 1_000_000L
        private const val MAX_WAIT_NS = 2_000_000_000L

        /** The window for a new link from the switches, or null when off. */
        fun fromSwitches(): GattByteWindow? {
            val bytes = GattSwitches.byteWindowBytes
            if (bytes <= 0) return null
            val kbps = GattSwitches.windowDrainKbps
            return GattByteWindow(bytes, { kbps * 1000L / 8L })
        }
    }
}
