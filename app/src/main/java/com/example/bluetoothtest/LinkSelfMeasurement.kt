package com.example.bluetoothtest

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Every link measures its own capacity from the media it already carries (root-cause workstream B,
 * 2026-10-07). No Android API reports what a Bluetooth link can carry; it depends on both phones'
 * controllers, Wi-Fi coexistence and the other links sharing the radio, so any fixed number is
 * wrong for some phone. A sealed record is written back-to-back, so a large record crosses the link
 * as a packet train: bytes / arrival time is the rate the link delivered at that moment (packet-pair
 * dispersion). Keyframes and large frames make every session a speed test without extra traffic.
 *
 * Only bytes that arrive after the reader started waiting count; bytes already buffered when the
 * train began (the reader was busy) are excluded so a backlog cannot look like a fast link.
 */
internal class ArrivalTrainMeter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val windowNs: Long = WINDOW_NS,
) {
    private var inRecord = false
    private var started = false
    private var startNs = 0L
    private var lastNs = 0L
    private var bytes = 0L
    private var bufferedAtStart = 0L
    private val samples = ArrayDeque<Pair<Long, Int>>()

    @Volatile var trains = 0L
        private set

    @Synchronized
    fun beginRecord() {
        inRecord = true
        started = false
        bytes = 0
        bufferedAtStart = 0
    }

    /** [waited]: nothing was buffered before this read; [availableAfter]: bytes buffered after it. */
    @Synchronized
    fun onRead(count: Int, waited: Boolean, availableAfter: Int) {
        if (!inRecord || count <= 0) return
        val now = nanoTime()
        if (!started) {
            if (!waited) return
            started = true
            startNs = now
            lastNs = now
            bufferedAtStart = availableAfter.toLong()
            return
        }
        bytes += count
        lastNs = now
    }

    /** Returns this record's train rate in kbps when it was long enough to measure. */
    @Synchronized
    fun endRecord(): Int? {
        inRecord = false
        if (!started) return null
        val durationNs = lastNs - startNs
        val trainBytes = bytes - bufferedAtStart
        if (trainBytes < MIN_TRAIN_BYTES || durationNs < MIN_TRAIN_NS) return null
        val kbps = (trainBytes * 8_000_000L / durationNs).toInt()
        samples.addLast(lastNs to kbps)
        trains++
        return kbps
    }

    /**
     * Capacity this link delivered recently: the median train in the window. The upper tail is
     * biased high on GATT, where a short train can fall inside a few connection events (the M31
     * reported 1107-1140 kbps against an 892 kbps integrity-checked probe with the old
     * second-highest rule).
     */
    @Synchronized
    fun estimateKbps(): Int? {
        val now = nanoTime()
        while (samples.isNotEmpty() && now - samples.first().first > windowNs) samples.removeFirst()
        val sorted = samples.map { it.second }.sorted()
        return if (sorted.isEmpty()) null else sorted[(sorted.size - 1) / 2]
    }

    companion object {
        /** Enough chunks to average out L2CAP/RFCOMM chunking and GATT notification batching. */
        const val MIN_TRAIN_BYTES = 8_000L
        /** At least several BLE connection events (7.5-15 ms each). */
        const val MIN_TRAIN_NS = 60_000_000L
        const val WINDOW_NS = 10_000_000_000L
    }
}

/**
 * Receiver side of the version 3 delivery report (2026-10-09). The arrival-train estimate above is
 * the rate one record crossed the link at; on a shared, two-way GATT link it overstated what the
 * link sustains (regress-ff0e24e1 ASUS->S24 900 kbps call: trains 745-875 kbps, while the S24
 * received 588-696 kbps), so a sender that cut to it stayed above the link and kept cutting. This
 * counts what actually arrived (media payload bytes, exactly the bytes the sender counts as written)
 * and when the last of them arrived on this link's clock, so the sender can time that byte's trip.
 */
internal class LinkDeliveryCounter(private val nanoTime: () -> Long = System::nanoTime) {
    private val startNs = nanoTime()
    private var receivedBytes = 0L
    private var lastArrivalMs = 0L

    @Synchronized
    fun add(bytes: Int) {
        if (bytes <= 0) return
        receivedBytes += bytes
        lastArrivalMs = (nanoTime() - startNs) / 1_000_000L
    }

    @Synchronized
    fun snapshot(): LinkDelivery = LinkDelivery(receivedBytes, lastArrivalMs)
}

/** Feeds read timing to [meter] without changing the bytes read. */
internal class ArrivalTimedInputStream(
    input: InputStream,
    private val meter: ArrivalTrainMeter,
) : FilterInputStream(input) {
    override fun read(): Int {
        val waited = bufferedBytes() == 0
        val value = super.read()
        if (value >= 0) meter.onRead(1, waited, if (waited) bufferedBytes() else 0)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val waited = bufferedBytes() == 0
        val count = super.read(b, off, len)
        if (count > 0) meter.onRead(count, waited, if (waited) bufferedBytes() else 0)
        return count
    }

    private fun bufferedBytes(): Int = try {
        `in`.available()
    } catch (_: IOException) {
        0
    }
}

/**
 * Keeps the backlog where the app can see and order it (root-cause program, 2026-10-07). In
 * layers-diag-c4db9240 the M31's GATT sender queue read "0/33, 0 ms" while frames reached the ASUS
 * up to 2.2 s late: the Android Bluetooth stack accepted bursts faster than the link drained them,
 * so rate control and audio priority never saw the backlog. Writes are paced at the child-measured
 * delivery rate, allowing only [burstNs] of data ahead of the link; the rest waits, in order and
 * without loss, in the app queue where audio goes first and the backlog drives rate control.
 *
 * Until the child's first arrival report the link used to be written unpaced: in mesh4-57484598 the
 * A15s fed by the ASUS (about 65 kbps) hid 15 s of media in the Android stack, invisible to rate
 * control and route withdrawal, and the report itself queued behind that backlog. [seed] paces the
 * link from join at the learned capacity (or a conservative prior). With no report the seed is
 * halved every [SILENCE_STEP_NS] after [SILENCE_GRACE_NS], down to [MIN_SEED_KBPS], so a link whose
 * reports never arrive still shows its backlog in the app queue. The first report replaces it.
 */
internal class LinkPacer(
    private val nanoTime: () -> Long = System::nanoTime,
    private val burstNs: Long = BURST_NS,
) {
    private var measuredBytesPerSecond = 0L
    private var seedBytesPerSecond = 0L
    private var seededAtNs = 0L
    private var virtualDrainNs = 0L

    /** 0 = unpaced (nothing measured or seeded). The current pace, including seed decay. */
    val bytesPerSecond: Long
        @Synchronized get() = currentRate()

    /** True once the child has reported a measurement; the seed no longer applies. */
    val measured: Boolean
        @Synchronized get() = measuredBytesPerSecond > 0L

    @Synchronized
    fun setMeasuredLinkKbps(kbps: Int) {
        val seededRate = if (measuredBytesPerSecond == 0L) currentRate() else 0L
        val rate = (kbps * 1_000L / 8 * PACE_GAIN).toLong()
        if (seededRate > 0L && rate > 0L) {
            // The first report replaces the seed: the bytes already charged drain at the measured rate.
            val now = nanoTime()
            if (virtualDrainNs > now) virtualDrainNs = now + (virtualDrainNs - now) * seededRate / rate
        }
        measuredBytesPerSecond = rate
    }

    /**
     * Paces this link at [kbps] until the first measurement; ignored once measured or already seeded.
     * Returns true when the seed was applied.
     */
    @Synchronized
    fun seedKbps(kbps: Int): Boolean {
        if (kbps <= 0 || measuredBytesPerSecond > 0L || seedBytesPerSecond > 0L) return false
        seedBytesPerSecond = (kbps * 1_000L / 8 * PACE_GAIN).toLong()
        seededAtNs = nanoTime()
        return true
    }

    private fun currentRate(): Long {
        if (measuredBytesPerSecond > 0L) return measuredBytesPerSecond
        if (seedBytesPerSecond <= 0L) return 0L
        return decayedSeed(seedBytesPerSecond, nanoTime() - seededAtNs)
    }

    /** Nanoseconds to wait before the next write may start. */
    @Synchronized
    fun waitNs(): Long {
        if (currentRate() <= 0L) return 0L
        return (virtualDrainNs - burstNs - nanoTime()).coerceAtLeast(0L)
    }

    /**
     * Charges [bytes] from [writeStartedNs], not from the write's return. A GATT write blocks for its
     * own transmission time; charging from the return double-counted it (lane-b-pacing-529f36cc:
     * 135 KB took 1086 ms to write, then the pacer idled another 1287 ms). Non-blocking CoC/Classic
     * writes return immediately, so their charge is unchanged.
     */
    @Synchronized
    fun onSent(bytes: Int, writeStartedNs: Long = nanoTime()) {
        val rate = currentRate()
        if (rate <= 0L) return
        virtualDrainNs = maxOf(virtualDrainNs, writeStartedNs) + bytes * 1_000_000_000L / rate
    }

    companion object {
        const val BURST_NS = 50_000_000L
        /** The train median read 10-30% below integrity probes; pacing must not cap below the link. */
        const val PACE_GAIN = 1.2
        /** A report normally arrives within a few heartbeats; the seed holds this long unchanged. */
        const val SILENCE_GRACE_NS = 4_000_000_000L
        const val SILENCE_STEP_NS = 2_000_000_000L
        /** The decayed seed never drops below this: a link slower than this shows as backlog anyway. */
        const val MIN_SEED_KBPS = 150

        /** Seed pace after [silenceNs] without any report: halved per step beyond the grace, with a floor. */
        fun decayedSeed(seedBytesPerSecond: Long, silenceNs: Long): Long {
            if (silenceNs <= SILENCE_GRACE_NS) return seedBytesPerSecond
            val steps = ((silenceNs - SILENCE_GRACE_NS) / SILENCE_STEP_NS + 1).coerceAtMost(16).toInt()
            val floor = (MIN_SEED_KBPS * 1_000L / 8 * PACE_GAIN).toLong()
            return maxOf(minOf(seedBytesPerSecond, floor), seedBytesPerSecond shr steps)
        }
    }
}

/** Where a child link's initial pace comes from (see [LinkPacer]). */
internal object LinkPacerSeed {
    /**
     * Conservative per-link prior used when this phone has not measured the transport yet: GATT on 1M
     * PHY measured 0.320 Mbps and unprimed CoC 1M 0.193 Mbps, 2M PHY and Classic carry more
     * (DECISIONS). Deliberately far below the fan-out aggregate priors (700-800 kbps).
     */
    fun priorKbps(
        transport: BluetoothTransportOption,
        le2MPhy: Boolean?,
        experimentalPhy: ExperimentalBlePhy? = DebugTunables.experimentalBlePhy,
    ): Int = experimentalPhy?.pacerPriorKbps(transport) ?: when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> 400
        BluetoothTransportOption.BLE_GATT -> if (le2MPhy == false) 250 else 350
        BluetoothTransportOption.BLE_L2CAP -> if (le2MPhy == false) 200 else 350
    }

    /**
     * The phone's own fresh single-link measurement when it has one, else the conservative prior.
     * With the experimental per-peer seed, the child's own last report for this very link comes first
     * ([peerLinkKbps]): the phone's best probe is measured with its best peer, so a weak peer (ASUS ->
     * A15s about 65 kbps against an ASUS probe near 1 Mbps) was still seeded at the strong rate.
     */
    fun kbps(transport: BluetoothTransportOption, probeKbps: Int?, le2MPhy: Boolean?, peerLinkKbps: Int? = null): Int =
        peerLinkKbps?.takeIf { it > 0 } ?: probeKbps?.takeIf { it > 0 } ?: priorKbps(transport, le2MPhy)

    fun seed(pacer: LinkPacer, transport: BluetoothTransportOption, peer: String? = null): Int? {
        if (!DebugTunables.linkPacing || !DebugTunables.linkPacerSeed) return null
        val peerKbps = peer?.takeIf { DebugTunables.experimentalPeerLinkSeed }
            ?.let { LinkCapacity.peerLinkKbps(transport, it) }
        val kbps = kbps(transport, LinkCapacity.measuredLinkKbps(transport), LinkCapacity.features?.le2MPhy, peerKbps)
        return kbps.takeIf { pacer.seedKbps(it) }
    }
}
