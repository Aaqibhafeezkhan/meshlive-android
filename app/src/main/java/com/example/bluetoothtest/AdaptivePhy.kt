package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptive LE PHY: a central moves one link from 2M to 1M when that link, measured while this phone
 * is backlogged on it, carries no more than about one data PDU per connection event.
 *
 * Root cause (HCI snoop, 2026-10-09, slowlink-asussnoop2-b9029e48 / slowlink-phy1m-b9029e48): the
 * ASUS (central) <-> A15s link negotiated data length 251/251 and PHY 2M/2M with all updates status 0,
 * yet completed exactly one ACL packet per 15 ms event in each direction (Number Of Completed Packets
 * 1 per event, 60 packets/s) while the ASUS controller held all 16 LE buffers full: 60 kbps on CoC,
 * 84 kbps on GATT. The same call on LE 1M completed 4 packets per event (218 kbps, 3.6x), and the M31
 * central carried 6-10 packets per event to the same A15s on 2M. Which side ends the events cannot be
 * seen from the host, so this is not keyed to any model: any 2M link that measures this slow is moved,
 * every other link keeps 2M. Either side may ask for a PHY update (Core v5.4 Vol 6 Part B 5.1.10), and
 * the result is restricted to the PHYs both preferences allow, so the central's 1M-only preference is
 * enough. One move per link and no return to 2M on that link (no flapping); a new link starts on 2M.
 *
 * Measurement is hop-local: bytes this phone's writer handed the link per window, counted only when the
 * writer spent at least [BACKLOG_FRACTION] of the window blocked (the link, not the encoder, was the
 * limit). After one warm-up window, the mean of [SLOW_WINDOWS] backlogged windows decides once: at or
 * below 1.25 x the one-PDU-per-event payload moves the link to 1M, above it keeps 2M. DEBUG launch extra `test_adaptive_phy=false` is the control arm. Line: `BENCHMARK adaptive_phy`.
 */
internal class AdaptivePhyPolicy(
    private val windowMs: Long = WINDOW_MS,
    private val slowWindows: Int = SLOW_WINDOWS,
) {
    /**
     * [STALLED]: the backlogged writer carried less than a quarter of one PDU per event for the whole decision (3 windows).
     * Not the one-PDU-per-event pattern, so [MOVE_TO_1M] does not apply; whether it is a relay's scheduling collision or a
     * link that is alive but sending almost nothing is for the caller to say ([AdaptivePhyLink]).
     */
    enum class Verdict { NONE, KEEP, MOVE_TO_1M, STALLED }

    private var windowStartMs = -1L
    private var bytes = 0L
    private var blockedMs = 0L
    private var warmedUp = false
    private val backloggedKbps = mutableListOf<Int>()
    private var decided = false
    var lastKbps = 0
        private set
    var lastBlockedFraction = 0.0
        private set

    /**
     * One write of [writeBytes] that spent [writeBlockedMs] inside the link's writer, ending at [nowMs].
     * [onTwoM] is the link's current PHY; [oneEventKbps] is the payload one 251-octet PDU per
     * connection event carries at the link's interval.
     */
    @Synchronized
    fun onWrite(
        nowMs: Long,
        writeBytes: Int,
        writeBlockedMs: Long,
        onTwoM: Boolean,
        oneEventKbps: Int,
        sharedAirtime: Boolean = false,
    ): Verdict {
        if (decided) return Verdict.NONE
        if (windowStartMs < 0) windowStartMs = nowMs - writeBlockedMs
        bytes += writeBytes
        blockedMs += writeBlockedMs
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return Verdict.NONE
        val kbps = (bytes * 8 / elapsed).toInt()
        val fraction = blockedMs.toDouble() / elapsed
        windowStartMs = nowMs
        bytes = 0
        blockedMs = 0
        lastKbps = kbps
        lastBlockedFraction = fraction
        if (!onTwoM || fraction < BACKLOG_FRACTION || oneEventKbps <= 0) return Verdict.NONE
        // This phone sends to two or more links at once: each link's share of the radio says nothing
        // about one link's PDUs per event. mesh4-041da09f 0002: an ASUS source with two children moved
        // both to 1M at 134 kbps each (child-measured 264/228 kbps before), TX fell to 168/139 kbps and
        // a 244 kbps fan-out ceiling was learned. Such windows are not evidence either way.
        if (sharedAirtime) return Verdict.NONE
        // The first backlogged window also fills the socket/stack buffers, so it overstates the link
        // (a 1-PDU-per-event CoC link that carried 123 kbps measured 199 kbps in its first window).
        if (!warmedUp) {
            warmedUp = true
            return Verdict.NONE
        }
        backloggedKbps += kbps
        if (backloggedKbps.size < slowWindows) return Verdict.NONE
        decided = true
        val meanKbps = backloggedKbps.sum() / backloggedKbps.size
        lastKbps = meanKbps
        // Far below one PDU per event is a stalled link, not the one-PDU-per-event pattern: mesh4-7a6c401c
        // 0001 moved ASUS -> M31 at 8 kbps (a relay's downstream link while it was peripheral upstream)
        // and remembered the M31 as a bad-2M peer. Keep 2M and record nothing for such links.
        if (meanKbps * 4 < oneEventKbps) return Verdict.STALLED
        return if (meanKbps * 4 <= oneEventKbps * 5) Verdict.MOVE_TO_1M else Verdict.KEEP
    }

    companion object {
        const val WINDOW_MS = 2_000L
        const val SLOW_WINDOWS = 3
        const val BACKLOG_FRACTION = 0.5

        /** Interval assumed before the stack reports one: the high-priority maximum (Android 11.25-15 ms). */
        const val DEFAULT_INTERVAL_MS = 15.0

        /** Payload of one 251-octet PDU per event (247 after the L2CAP header) at [intervalMs]. */
        fun oneEventKbps(intervalMs: Double): Int =
            if (intervalMs <= 0) 0 else (LeConnectionParameters.DLE_PDU_L2CAP_PAYLOAD_BYTES * 8 / intervalMs).toInt()
    }
}

/** One central LE link whose PHY [AdaptivePhyPolicy] may move. */
internal class AdaptivePhyLink(
    private val link: String,
    private val address: String?,
    private val gatt: BluetoothGatt,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Priority this link asked for; restored after a relay re-anchor. */
    private val restorePriority: BleConnectionPriority = BleConnectionPriority.HIGH,
    /**
     * True while the peer's data is still arriving on this link. A stalled writer ([AdaptivePhyPolicy.Verdict.STALLED]) on a link
     * the peer is still heard on is a one-way 2M stall (b10 1-defaults/d2: 7 to 10 kbps written for 90 s while 95 authenticated
     * heartbeats arrived); without it the stall is left to the supervision and heartbeat timeouts.
     */
    private val peerHeard: () -> Boolean = { false },
    /** Called after this link asked for 1M on its own measurement (not a relay move): the stream sender's backlog skip. */
    private val onMovedTo1M: () -> Unit = {},
    /**
     * The link's [LlProcedureGate] (BACKLOG P-01). With it, the move to 1M and the relay re-anchor's priority
     * requests enter the gate's queue instead of going straight to the stack; null, or a gate that is off, is the
     * old behaviour. The call site that owns the gate also feeds it the MTU, discovery and priority callbacks.
     */
    private val gate: LlProcedureGate? = null,
    /**
     * Follows the move to 1M with readPhy polls when the stack may not call onPhyUpdate (Android 13, CONN-09). The
     * default applies the plan only where the `android13-phy-update-callback-backstop` entry applies.
     */
    private val waitPlan: PhyWaitPlan? = defaultBackstopPlan(),
) {
    private var policy = AdaptivePhyPolicy()
    @Volatile private var txPhy = 0
    @Volatile private var moved = false

    /** Experimental adaptive split ([adaptiveSplitFor]): the split this link was moved to, or null (not tried). */
    @Volatile private var splitApplied: GattPhySplit? = null
    @Volatile private var splitMover: PhyMoveRequester? = null
    private var reanchors = 0

    private val phyChanges = PhyChangeTracker()

    private val mover = PhyMoveRequester(
        link = link,
        requestPhy = {
            gatt.setPreferredPhy(
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED,
            )
            gatt.readPhy()
        },
        readPhy = { gatt.readPhy(); true },
        targetTx = LePhy.ONE_M,
        gate = gate,
        plan = waitPlan,
        scheduleAt = { delayMs, action -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
        nowMs = clock,
        log = { Log.i("BluetoothVideo", it) },
    )

    /** The PHY the stack last REPORTED for this link with status 0 (update or read), or null before any report. */
    val confirmedTxPhy: Int? get() = txPhy.takeIf { it != 0 }

    /** onPhyUpdate: the stack's answer to a PHY request (BACKLOG P-01: also completes the gate's PHY step). */
    fun onPhyUpdate(tx: Int, status: Int) {
        mover.onUpdate(tx, status)
        splitMover?.onUpdate(tx, status)
        trackPhy(tx, status)
    }

    /** onPhyRead (a poll or any readPhy): ends the follower and the gate step only when it shows the requested PHY. */
    fun onPhyRead(tx: Int, status: Int) {
        mover.onRead(tx, status)
        splitMover?.onRead(tx, status)
        trackPhy(tx, status)
    }

    /**
     * A PHY report whose source the caller did not say (the existing call sites pass onPhyUpdate and onPhyRead
     * here alike). Tracks the transmit PHY and lets the backstop follower and the gate see a report that matches
     * the requested PHY; a mismatch could be a read taken before the update, so it ends nothing.
     */
    fun onPhy(tx: Int, status: Int) {
        mover.onRead(tx, status)
        splitMover?.onRead(tx, status)
        trackPhy(tx, status)
    }

    private fun trackPhy(tx: Int, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        txPhy = tx
        // A real PHY change makes the evidence gathered before it stale (LinkChangeEvents).
        if (phyChanges.onPhy(tx)) {
            LinkChangeEvents.phyChanged()
            Log.i(
                "BluetoothVideo",
                "BENCHMARK link_change: kind=phy | link=$link | peer=...${address?.takeLast(5) ?: "unknown"} | txPhy=$tx | " +
                    "epoch=${LinkChangeEvents.epoch}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun onWrite(writeBytes: Int, blockedMs: Long) {
        // Dual-role probe (BACKLOG P-03): a child link of a relay carries data while its parent link is up.
        if (RelayUpstream.active) DualRoleProbes.onChildData(address, writeBytes, peerHeard())
        val reanchorOn = DebugTunables.relayReanchor && RelayUpstream.active
        if (moved || (!DebugTunables.adaptivePhy && !reanchorOn)) return
        val intervalMs = LeConnectionParameterLog.latest(address)?.intervalMs?.takeIf { it > 0 }
            ?: AdaptivePhyPolicy.DEFAULT_INTERVAL_MS
        val oneEvent = AdaptivePhyPolicy.oneEventKbps(intervalMs)
        val verdict = policy.onWrite(
            clock(),
            writeBytes,
            blockedMs,
            adaptiveLinkOnTwoM(txPhy, splitApplied),
            oneEvent,
            sharedAirtime = adaptivePhySharedAirtime(link),
        )
        if (verdict != AdaptivePhyPolicy.Verdict.NONE && reanchorOn &&
            relayChildStarved(policy.lastKbps, oneEvent) && reanchors < RelayReanchor.MAX_ATTEMPTS
        ) {
            reanchors++
            policy = AdaptivePhyPolicy()
            val queued = RelayReanchor.request(gatt, restorePriority, gate)
            log("phy=unchanged", "relay_reanchor | attempt=$reanchors | queued=$queued", intervalMs, oneEvent)
            return
        }
        if (!DebugTunables.adaptivePhy) return
        when (verdict) {
            AdaptivePhyPolicy.Verdict.NONE -> Unit
            AdaptivePhyPolicy.Verdict.KEEP -> log("from=2M to=2M", "kept", intervalMs, oneEvent)
            AdaptivePhyPolicy.Verdict.STALLED -> if (
                stalledTwoMShouldMove(DebugTunables.stalledTwoMMove, RelayUpstream.active && DebugTunables.relayPhyGuard, peerHeard())
            ) {
                if (trySplit("stalled_one_way", intervalMs, oneEvent)) return
                moved = true
                val queued = moveTo1M()
                log("from=2M to=1M", "stalled_one_way | queued=$queued$afterSplit", intervalMs, oneEvent)
                AdaptivePhyMemory.recordBadForAddress(address, "stalled_one_way")
                if (queued) runCatching { onMovedTo1M() }
            } else {
                // A relay's downstream link (mesh4-7a6c401c 0001: 8 kbps, scheduling collision) or a link the peer is no
                // longer heard on: no PHY evidence, keep 2M and remember nothing.
                log("from=2M to=2M", "kept", intervalMs, oneEvent)
            }
            AdaptivePhyPolicy.Verdict.MOVE_TO_1M -> if (RelayUpstream.active && DebugTunables.relayPhyGuard) {
                moved = true
                if (DebugTunables.relayOnePduMove && relayOnePduBand(policy.lastKbps, oneEvent)) {
                    // mesh4-8211dc8f 0004: ASUS relay -> A15s child at 121 kbps with one PDU per 15 ms
                    // event worth 131 kbps, the pair's 2M signature (snoop: 1 packet/event on 2M, 4 on
                    // 1M), far above the 4-37 kbps a scheduling collision leaves. Move this link only;
                    // a relay link is still not evidence for the memory.
                    val queued = moveTo1M()
                    log("from=2M to=1M", "relay_one_pdu | queued=$queued | memory=none", intervalMs, oneEvent)
                } else {
                    // A relay's own child link: starvation here is the parent/child scheduling collision
                    // (relaylink-gattdef-7a6c401c), not this pair's 2M. Keep 2M, remember nothing.
                    log("from=2M to=2M", "relay_scheduling | action=none", intervalMs, oneEvent)
                }
            } else {
                if (trySplit("one_pdu_per_event", intervalMs, oneEvent)) return
                moved = true
                val queued = moveTo1M()
                log("from=2M to=1M", "one_pdu_per_event | queued=$queued$afterSplit", intervalMs, oneEvent)
                AdaptivePhyMemory.recordBadForAddress(address, "one_pdu_per_event")
                if (queued) runCatching { onMovedTo1M() }
            }
        }
    }

    companion object {
        /** A peer whose data arrived within this window is "heard": authenticated heartbeats come about every 1 to 2 s. */
        const val PEER_HEARD_MS = 4_000L
    }

    /** Log suffix of a full move that follows a split on the same link; empty (the old line) when no split was tried. */
    private val afterSplit: String
        get() = if (splitApplied != null) " | after_split=true" else ""

    /**
     * Experimental adaptive split ([adaptiveSplitFor]): instead of the full move to 1M, asks this central for the split once per
     * link, so only the peripheral -> central direction goes to 1M. Returns true when the split was queued (the decision is then
     * re-armed, so a link that still fails moves to full 1M with `after_split=true`, memory as before); false leaves the full move
     * to the caller. The peer is not remembered bad on 2M here, because the link was repaired without leaving 2M in the media
     * direction. Line: `BENCHMARK adaptive_phy: link=gatt ... to=split-tx2m-rx1m | reason=<verdict> | queued=true | memory=none`.
     */
    @SuppressLint("MissingPermission")
    private fun trySplit(verdict: String, intervalMs: Double, oneEvent: Int): Boolean {
        val split = adaptiveSplitFor(
            DebugTunables.adaptivePhySplit, link, DebugTunables.experimentalPhySplit, DebugTunables.experimentalBlePhy, splitApplied != null,
        ) ?: return false
        val requester = PhyMoveRequester(
            link = link,
            requestPhy = {
                gatt.setPreferredPhy(split.txMask, split.rxMask, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                gatt.readPhy()
            },
            readPhy = { gatt.readPhy(); true },
            targetTx = LePhy.valueForMask(split.txMask) ?: LePhy.TWO_M,
            gate = gate,
            plan = waitPlan,
            scheduleAt = { delayMs, action -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
            nowMs = clock,
            log = { Log.i("BluetoothVideo", it) },
        )
        val queued = requester.move()
        if (!queued) return false
        splitMover = requester
        splitApplied = split
        log("from=2M to=${adaptiveSplitLabel(split)}", "$verdict | queued=true | memory=none", intervalMs, oneEvent)
        policy = AdaptivePhyPolicy()
        runCatching { onMovedTo1M() }
        return true
    }

    /** Asks for 1M: through the LL procedure gate when there is one (and it is on), else straight to the stack. */
    @SuppressLint("MissingPermission")
    private fun moveTo1M(): Boolean = mover.move()

    private fun log(change: String, reason: String, intervalMs: Double, oneEvent: Int) {
        Log.i(
            "BluetoothVideo",
            String.format(
                Locale.ROOT,
                "BENCHMARK adaptive_phy: link=%s | peer=...%s | %s | reason=%s | kbps=%d | blocked=%.2f | " +
                    "intervalMs=%.2f | oneEventKbps=%d",
                link, address?.takeLast(5) ?: "unknown", change, reason, policy.lastKbps,
                policy.lastBlockedFraction, intervalMs, oneEvent,
            ),
        )
    }
}

/** Measures the time each write spends blocked in the link's writer and feeds [link]. */
internal class AdaptivePhyOutputStream(output: OutputStream, private val link: AdaptivePhyLink) : FilterOutputStream(output) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        val started = SystemClock.elapsedRealtime()
        out.write(b, off, len)
        link.onWrite(len, SystemClock.elapsedRealtime() - started)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
}

/**
 * Central CoC links: the control GATT connection (which owns the LE link's PHY) is registered by peer
 * address while it is open, so every CoC media writer to that peer ([mediaOutputStream]) is measured.
 */
internal object AdaptivePhyRegistry {
    private val links = ConcurrentHashMap<String, AdaptivePhyLink>()

    fun register(address: String, link: AdaptivePhyLink) {
        links[address] = link
    }

    fun unregister(address: String, link: AdaptivePhyLink) {
        links.remove(address, link)
    }

    fun forAddress(address: String?): AdaptivePhyLink? = address?.let(links::get)
}

/**
 * The bad-2M marks of [AdaptivePhyMemory]: `key -> epoch ms` (purge flags are marks with the value 1), held in memory
 * and written sealed ([SealedTextFile]) off the caller's thread after each change. Review 2026-10-27 (finding 6): the
 * keys carry the node labels of peers this phone met, which were plain preferences before.
 */
internal class AdaptivePhyMarks(private val file: SealedTextFile?, private val writeAsync: Boolean = true) {
    private val values = ConcurrentHashMap<String, Long>()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AdaptivePhyMarks").apply { isDaemon = true }
    }

    /** False while the sealed file could not be read (a passing failure): it is never written over then. */
    @Volatile private var loadedFromFile = file == null

    init {
        reloadIfNeeded()
    }

    /** Reads the stored marks once they can be read; marks recorded meanwhile in this process win. */
    @Synchronized
    private fun reloadIfNeeded(): Boolean {
        if (loadedFromFile) return true
        val sealed = file ?: return true
        val text = try {
            sealed.read()
        } catch (unavailable: SealedTextUnavailable) {
            return false
        } catch (other: Exception) {
            null
        }
        val recent = HashMap(values)
        text?.let(::decodeInto)
        values.putAll(recent)
        loadedFromFile = true
        return true
    }

    fun get(key: String): Long? = values[key]

    fun put(key: String, value: Long) {
        values[key] = value
        persist()
    }

    fun flag(key: String): Boolean = (values[key] ?: 0L) != 0L

    fun keys(): Set<String> = values.keys.toSet()

    fun all(): Map<String, Long> = HashMap(values)

    fun remove(keys: Collection<String>, setFlag: String? = null) {
        keys.forEach { values.remove(it) }
        setFlag?.let { values[it] = 1L }
        if (keys.isNotEmpty() || setFlag != null) persist()
    }

    /** The plain preferences of an earlier build: Long marks and Boolean purge flags. */
    fun importLegacy(entries: Map<String, *>) {
        entries.forEach { (key, value) ->
            when (value) {
                is Long -> values[key] = value
                is Boolean -> if (value) values[key] = 1L
            }
        }
        persist()
    }

    fun encode(): String = values.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    private fun decodeInto(text: String) {
        text.lineSequence().forEach { line ->
            val at = line.lastIndexOf('=')
            if (at <= 0) return@forEach
            line.substring(at + 1).toLongOrNull()?.let { values[line.substring(0, at)] = it }
        }
    }

    private fun persist() {
        val sealed = file ?: return
        val write = Runnable { if (reloadIfNeeded()) runCatching { sealed.write(encode()) } }
        if (writeAsync) writer.execute(write) else write.run()
    }
}

/**
 * Remembers, per authenticated hop peer and transport, that 2M was measured bad: an adaptive move
 * fired on a link to it ([AdaptivePhyLink]), or a 2M link to it died within [EARLY_LOSS_MS] of
 * authentication while this phone sent on fewer than two links of that transport. Later
 * connections to that peer request 1M at connection time, before media.
 *
 * Why: when the A15s is central to the ASUS (A15s calls ASUS), the 2M link died 1-8 s after call
 * setup on GATT and CoC (ASUS `onServerCongestion congested=true` never cleared, then A15s
 * `reason=0x0008`, supervision timeout; xpair-asusa15s-405fffae/0003), before the measuring fallback
 * (warm-up + 3 windows) could act. Keys are the authenticated BMH2 hop node ID (12 hex), so the
 * memory never follows an unauthenticated advertisement; only a direct (route cost 0) peer's
 * advertised prefix is used to look it up before connecting. Entries expire after [TTL_MS]; healthy
 * peers have no entry and stay on 2M. Fan-out windows never record (the policy ignores them).
 * DEBUG launch extra `test_adaptive_phy_memory=false` is the control arm. Lines:
 * `BENCHMARK adaptive_phy: ... reason=remembered|early_loss|setup_retry`.
 */
internal object AdaptivePhyMemory {
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val EARLY_LOSS_MS = 15_000L

    /**
     * Setup-stall guard for the first 2M call attempt ([DebugTunables.phySetupStallGuard]). Hop
     * authentication on a fresh link takes 0.1-0.3 s (S24 110 ms, ASUS central 300 ms). With the A15s
     * central to the ASUS, the ASUS -> A15s direction stopped right after LE PHY Update Complete (2M):
     * ASUS snoop showed only the first 251-octet fragment of the A15s hello and no ATT after it, the
     * A15s timed out (0x08) 5 s later and the ASUS 5 s after that, or the link crawled for 18-20 s
     * (snoop-asusa15s-1 0002, snoop-asusa15s-phy-1 0001); the same request on 1M went ACTIVE in 2.5 s.
     * Closing at this deadline (the central -> peripheral direction still delivers LL_TERMINATE_IND)
     * leaves room for the 1M retry inside [EARLY_LOSS_MS]. Measured margin: 353 saved outgoing GATT call
     * setups (PHY report -> authenticated link) had healthy p90 S24 221, ASUS 229, M31 171, A15s 1050 ms
     * and worst healthy 1050 ms; every LE call attempt now logs `BENCHMARK call_setup: ... hopAuthMs=`.
     */
    const val SETUP_STALL_MS = 3_000L

    /** Longest scan for the failed peer's new advertisement before the 1M retry. */
    const val REDISCOVER_MS = 8_000L
    private const val PREFS = "adaptive_phy_memory"

    /** The marks (node labels of peers this phone met) in a sealed file since 2026-10-27 (review finding 6). */
    @Volatile private var prefs: AdaptivePhyMarks? = null
    private val addressHops = ConcurrentHashMap<String, Pair<BluetoothTransportOption, String>>()
    private const val SEALED_FILE = "adaptive-phy-memory.bin"

    fun attach(context: Context) {
        // The persistent link-change rollback memory (Experimental test_link_change_rollback) and the learned
        // child-link limit share this startup hook.
        runCatching { LinkChangeRollbacks.attach(context) }
        runCatching { DualRoleProbes.attach(context) }
        val app = context.applicationContext
        val marks = AdaptivePhyMarks(SealedTextFile(File(app.noBackupFilesDir, SEALED_FILE), { IdentityVerification.atRestCipher() }))
        // Earlier builds kept the marks in plain preferences: moved once into the sealed file, then deleted.
        runCatching {
            val legacy = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                marks.importLegacy(legacy.all)
                legacy.edit().clear().commit()
            }
            app.deleteSharedPreferences(PREFS)
        }
        prefs = marks
        val store = marks
        val stale = store.keys().filter { it.startsWith("bad2m.") }
        if (stale.isNotEmpty()) {
            store.remove(stale)
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | cleared_format1=${stale.size}")
        }
        if (!store.flag(GATT_FANOUT_PURGED)) {
            val polluted = gattFanOutPollutedKeys(store.keys())
            store.remove(polluted, setFlag = GATT_FANOUT_PURGED)
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | cleared_gatt_fanout=${polluted.size}")
        }
        if (DebugTunables.relayPhyGuard && !store.flag(RELAYED_CALL_PURGED)) {
            val relayed = relayedCallViaKeys(store.keys())
            store.remove(relayed, setFlag = RELAYED_CALL_PURGED)
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | cleared_relayed_call_via=${relayed.size}")
        }
    }

    /**
     * One-time purge: every `via` entry came from a relayed call's hop loss, which [skipsRelayingHop] no
     * longer records (relaycall-b9726176-loop4-1 0003: the M31 caller started its hop to the ASUS relay
     * on 1M from a via entry for the A15s callee). Direct entries cannot be told apart and age out.
     */
    internal const val RELAYED_CALL_PURGED = "purged.relayed_call_via.v1"

    internal fun relayedCallViaKeys(keys: Collection<String>): List<String> =
        keys.filter { it.startsWith("bad2m${FORMAT}via.") }

    /**
     * One-time purge: until ffa5590 a GATT source's windows counted only CoC sends as shared airtime,
     * so format-2 BLE_GATT entries may come from a fan-out window (mesh4-659efc38 0003 recorded the
     * ASUS on the M31; mesh4-8211dc8f 0003 then put the ASUS on 1M beside a 2M S24 child and it starved
     * to withdrawal). CoC entries were already guarded and stay.
     */
    internal const val GATT_FANOUT_PURGED = "purged.gatt_fanout.v1"

    internal fun gattFanOutPollutedKeys(keys: Collection<String>): List<String> {
        val direct = key(BluetoothTransportOption.BLE_GATT, "")
        val via = viaKey(BluetoothTransportOption.BLE_GATT, "")
        return keys.filter { it.startsWith(direct) || it.startsWith(via) }
    }

    private val enabled: Boolean
        get() = DebugTunables.adaptivePhy && DebugTunables.adaptivePhyMemory

    /**
     * Format 2 (2026-10-09): entries written before the relay guard ([RelayUpstream]) may come from a
     * relay's starved child link (a scheduling collision, e.g. the ASUS relay remembered the M31 on CoC in
     * mesh4-7a6c401c), so format-1 keys are ignored and removed at [attach].
     */
    internal fun key(transport: BluetoothTransportOption, node: String) = "bad2m$FORMAT.${transport.name}.$node"

    private const val FORMAT = 2

    /** Experimental learned-limit recovery: a mark not re-confirmed within this ages out. */
    const val RECOVERY_TTL_MS = 24L * 60 * 60 * 1000

    /** A recorded entry is valid for [ttlMs] (and not from the future after a clock change). */
    internal fun fresh(recordedAtMs: Long, nowMs: Long, ttlMs: Long = ttlMs()): Boolean =
        recordedAtMs > 0 && nowMs - recordedAtMs in 0 until ttlMs

    /** Re-recording a mark refreshes its time, so a re-confirmed mark stays. */
    internal fun ttlMs(recovery: Boolean = DebugTunables.ceilingProbeRecovery): Long =
        if (recovery) RECOVERY_TTL_MS else TTL_MS

    /** DEBUG: removes every bad-2M mark (direct and via); returns how many. */
    fun clearAll(): Int {
        val store = prefs ?: return 0
        val keys = store.keys().filter { it.startsWith("bad2m") }
        if (keys.isNotEmpty()) store.remove(keys)
        return keys.size
    }

    /** DEBUG launch record: every bad-2M mark with its age in hours and whether it is still fresh. */
    fun summary(nowMs: Long = System.currentTimeMillis()): String {
        val store = prefs ?: return "unavailable"
        val marks = store.all().filterKeys { it.startsWith("bad2m") }
        if (marks.isEmpty()) return "none"
        return marks.entries.sortedBy { it.key }.joinToString(" | ") { (key, value) ->
            val at = value
            "%s=%.1fh%s".format(java.util.Locale.ROOT, key, (nowMs - at) / 3_600_000.0, if (fresh(at, nowMs)) "" else "(stale)")
        }
    }

    /** Early loss: a 2M link lost within [EARLY_LOSS_MS] of authentication while not sending on 2+ links. */
    internal fun isEarlyLoss(authenticatedAtMs: Long, lostAtMs: Long, wasTwoM: Boolean, sendLinks: Int): Boolean =
        wasTwoM && sendLinks < 2 && authenticatedAtMs > 0 && lostAtMs - authenticatedAtMs in 0..EARLY_LOSS_MS

    fun isBad(transport: BluetoothTransportOption, node: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || node.isEmpty()) return false
        return fresh(prefs?.get(key(transport, node)) ?: 0L, nowMs)
    }

    fun recordBad(transport: BluetoothTransportOption, node: String, reason: String) {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || node.isEmpty()) return
        if (RelayUpstream.active && DebugTunables.relayPhyGuard) {
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=${AdaptivePhyMemory.logLabel(node)} | bad2m=not_recorded | reason=$reason | relay_upstream=true")
            return
        }
        prefs?.put(key(transport, node), System.currentTimeMillis())
        Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=${AdaptivePhyMemory.logLabel(node)} | transport=${transport.name} | bad2m=recorded | reason=$reason")
    }

    /** The link to [address] authenticated [node] as its hop peer on [transport]. */
    fun bind(address: String?, transport: BluetoothTransportOption, node: String, hopRelays: Boolean = false) {
        if (address == null || node.isEmpty()) return
        addressHops[address] = transport to node
        if (hopRelays) relayingHops += address else relayingHops -= address
    }

    /** Addresses whose bound hop peer forwards this session onward (a relayed call's relay hop). */
    private val relayingHops: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The authenticated hop node bound to [address] (12 hex), or [address] itself; the rollback memories key by this. */
    fun peerKey(address: String?): String? = address?.let { addressHops[it]?.second ?: it }

    fun recordBadForAddress(address: String?, reason: String) {
        val (transport, node) = address?.let(addressHops::get) ?: return
        if (skipsRelayingHop(address in relayingHops, DebugTunables.relayPhyGuard)) {
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=${AdaptivePhyMemory.logLabel(node)} | bad2m=not_recorded | reason=$reason | hop_relays=true")
            return
        }
        recordBad(transport, node, reason)
    }

    /**
     * A link to a hop that is itself relaying shares that relay's radio with its other hop, so its
     * one-PDU windows are not evidence about the pair's 2M. relaycall-f40e9f42 0002: the ASUS caller
     * moved its link to the S24 call relay at 122 kbps and remembered the S24 as bad-2M; mesh streams
     * then put the S24 on 1M beside a 2M child, the ASUS starved a viewer and learned a 223 kbps fan-out
     * ceiling (mesh4-b9726176-loop4-1 0002). The PHY move on the live link still happens.
     */
    internal fun skipsRelayingHop(hopRelays: Boolean, guard: Boolean): Boolean = hopRelays && guard

    /**
     * A call endpoint's hop peer relays the call: a relayed offer ([offerVia]), an invitation that
     * crossed relays ([relayHops]), or the hop's signed link hello carrying
     * [PrivateCallRelayChain.CALL_RELAY_CAPABILITY] (a callee knows this before the invitation arrives).
     * A relay ends both of its hops when either fails, so the endpoint's loss can come from a far hop
     * (relaycall-b9726176-loop4-1 0004: the A15s and ASUS endpoints both recorded the S24 relay).
     */
    internal fun callHopRelays(offerVia: Boolean, relayHops: Int, hopTransportMask: Int): Boolean =
        offerVia || relayHops > 0 || hopTransportMask and PrivateCallRelayChain.CALL_RELAY_CAPABILITY != 0

    /**
     * PHY to request for a new link to the direct peer advertising [nodePrefix] on [transport]:
     * 1M while that peer is remembered bad on 2M, otherwise [default]. [memoryNode] is the direct peer's memory key
     * ([offerMemoryNode]: the advertised prefix, or a recognised rotating offer's contact node); null means none (an
     * unrecognised rotating offer), so the memories are not consulted.
     */
    fun phyFor(
        transport: BluetoothTransportOption,
        nodePrefix: ByteArray,
        default: BlePhyPreference,
        link: String,
        relayedOffer: Boolean = false,
        memoryNode: String? = prefixLabel(nodePrefix),
    ): BlePhyPreference {
        val advertised = prefixLabel(nodePrefix)
        val node = if (relayedOffer) advertised else memoryNode ?: return default
        val keySource = if (node == advertised) "prefix" else "contact"
        // A relayed offer is advertised by the hop relay with the far callee's prefix, so the hop's own
        // node is unknown before connecting; its entry is kept under that advertised prefix instead.
        val bad = if (relayedOffer) isBadVia(transport, node) else isBad(transport, node)
        // A link that was lost within seconds of a PHY update stays on 1M for this peer (Experimental
        // test_ll_gate session memory / test_link_change_rollback persistent memory, BACKLOG P-01/P-02). Both
        // memories key by the advertised node prefix here; the authenticated hop node ID is what they record
        // when it is bound, which for a direct peer is the same 12-hex node whose prefix this is.
        val rolledBack = !bad && !relayedOffer && LinkChangeRollbacks.isDowngraded(node, LinkChangeKind.PHY)
        if (!bad && !rolledBack) return default
        if (rolledBack) {
            runCatching {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK adaptive_phy: link=$link | node=${AdaptivePhyMemory.logLabel(node)} | from=2M to=1M | reason=link_lost_after_phy_change | at=connect",
                )
            }
            return BlePhyPreference.ONE_M_ADAPTIVE
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=$link | node=${AdaptivePhyMemory.logLabel(node)} | from=2M to=1M | reason=remembered | " +
                "at=connect | via=$relayedOffer | key=$keySource",
        )
        return BlePhyPreference.ONE_M_ADAPTIVE
    }

    internal fun prefixLabel(nodePrefix: ByteArray): String = nodePrefix.joinToString("") { "%02x".format(it) }

    /**
     * A peer's node label in a log line: DEBUG builds keep it for the benchmark tools; a release log names no peer
     * (security review 2026-10-27, item 13: a shared or bug-report log listed the peers this phone met).
     */
    internal fun logLabel(node: String, debug: Boolean = BuildConfig.DEBUG): String = if (debug) node else "peer"

    /**
     * The bad-2M memory key of a direct offer advertising [nodePrefix]. A stable advertisement keys by its prefix. A rotating
     * (pseudonym) offer changes with its epoch, so with [contactKey] (DebugTunables.phyMemoryContactKey) it keys by the node
     * label of the one contact it is recognised as ([recognisedContact], local presence-lease recognition: the same 12-hex
     * label the 1M retry records from the authenticated hop node), and has no key when unrecognised. Without [contactKey]
     * (control arm) the pseudonym itself, which can never match a recorded entry. The memory stays in local preferences.
     */
    internal fun offerMemoryNode(
        rotating: Boolean,
        nodePrefix: ByteArray,
        contactKey: Boolean,
        recognisedContact: () -> String?,
    ): String? = when {
        !rotating || !contactKey -> prefixLabel(nodePrefix)
        else -> runCatching(recognisedContact).getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The memory key (the advertised node prefix label, as [phyFor] looks it up) to record when a first 2M call setup stalled, or
     * null. Only a stalled setup (hop authentication unfinished after the guard), a direct offer (a relayed offer's hop is the
     * relay, which [skipsRelayingHop] keeps out of the memory) and a stable advertised prefix (a rotating pseudonym changes with
     * its epoch, so an entry under it could never be found again). A rotating offer recognised as one contact is remembered
     * under [contactNode] instead ([offerMemoryNode]; null when unrecognised or the contact key is off).
     */
    internal fun advertisedPeerToRemember(
        enabled: Boolean,
        stalled: Boolean,
        direct: Boolean,
        rotating: Boolean,
        nodePrefix: ByteArray,
        contactNode: String? = null,
    ): String? = when {
        !enabled || !stalled || !direct -> null
        rotating -> contactNode?.takeIf { it.isNotEmpty() }
        nodePrefix.isNotEmpty() -> prefixLabel(nodePrefix)
        else -> null
    }

    internal fun viaKey(transport: BluetoothTransportOption, advertisedPrefix: String) =
        "bad2m${FORMAT}via.${transport.name}.$advertisedPrefix"

    fun isBadVia(transport: BluetoothTransportOption, advertisedPrefix: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || advertisedPrefix.isEmpty()) return false
        return fresh(prefs?.get(viaKey(transport, advertisedPrefix)) ?: 0L, nowMs)
    }

    /** The hop that carries a relayed offer advertised as [advertisedPrefix] was bad on 2M. */
    fun recordBadVia(transport: BluetoothTransportOption, advertisedPrefix: String, reason: String) {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || advertisedPrefix.isEmpty()) return
        if (RelayUpstream.active && DebugTunables.relayPhyGuard) return
        prefs?.put(viaKey(transport, advertisedPrefix), System.currentTimeMillis())
        Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | via=${AdaptivePhyMemory.logLabel(advertisedPrefix)} | transport=${transport.name} | bad2m=recorded | reason=$reason")
    }
}

/**
 * Whether this phone currently forwards a stream (a stream forwarder is open), so it is
 * peripheral on its parent link and central on its child link. A relay's child link shares the radio
 * with the parent link's events: HCI snoop on the ASUS relay (S24 -> ASUS -> M31, both links 15 ms,
 * relaylink-gattdef-7a6c401c) showed the parent's multi-packet events filling 7-10 ms of every
 * interval and the child carrying 11 kbps when its anchor fell inside them, 296 kbps when it did not.
 * That is scheduling, not the pair's PHY, so adaptive PHY neither moves nor remembers such links.
 * Private-call relays do not count: a call hop carries one call (no fan-out, voice or a call-sized
 * video), and every relayed call with an ASUS <-> A15s hop failed while those hops stayed on 2M
 * (relaycall-659efc38 0003/0005/0006), so call-relay hops use adaptive PHY like direct calls
 * ([CallRelayHopLoss]). Their losses are not written to the bad-2M memory while this guard is on
 * ([AdaptivePhyMemory.skipsRelayingHop]): in relaycall-b9726176-loop4-1 every relayed early loss
 * cascaded (a relay closes both hops), recording healthy S24/M31/ASUS pairs, and the 1M retry did not
 * save the A15s-central -> ASUS link either (xpair-asusa15s-351baa8e 0003, fb2306 0003).
 * DEBUG launch extra `test_relay_phy_guard=false` is the control arm.
 */
internal object RelayUpstream {
    private val relays = java.util.concurrent.atomic.AtomicInteger(0)

    val active: Boolean
        get() = relays.get() > 0

    fun enter() {
        // The first forwarder means a parent link is up: the dual-role probe starts watching (BACKLOG P-03).
        if (relays.incrementAndGet() == 1) runCatching { DualRoleProbes.onParentUp() }
    }

    fun exit() {
        if (relays.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) runCatching { DualRoleProbes.onParentLost() }
    }
}

/**
 * A relay child link measured (backlogged, after a MOVE_TO_1M verdict) at no less than half of one PDU
 * per event: the one-PDU-per-event 2M pattern, not the 4-37 kbps a parent/child anchor collision leaves
 * at 15 ms (relaylink-gattdef-7a6c401c). The verdict already bounds it at 1.25 PDUs per event.
 */
internal fun relayOnePduBand(meanKbps: Int, oneEventKbps: Int): Boolean =
    oneEventKbps > 0 && meanKbps * 2 >= oneEventKbps

/**
 * A stalled 2M writer ([AdaptivePhyPolicy.Verdict.STALLED]) moves to 1M and remembers the peer only on a link that is not a
 * relay's own (scheduling collision, not the pair's 2M) and whose peer is still heard (a dead link is the supervision timeout's).
 */
internal fun stalledTwoMShouldMove(enabled: Boolean, relayGuarded: Boolean, peerHeard: Boolean): Boolean =
    enabled && !relayGuarded && peerHeard

/** A relay child link carrying at most about one PDU per event while backlogged (or stalled). */
internal fun relayChildStarved(meanKbps: Int, oneEventKbps: Int): Boolean =
    oneEventKbps > 0 && meanKbps * 4 <= oneEventKbps * 5

/**
 * Experimental relay re-anchor (default off): a relay whose child link is starved while it relays asks for two
 * connection updates (BALANCED, then its own priority back after [RESTORE_DELAY_MS]); each LL connection
 * update lets this central's controller place the child's anchor again (Core v5.4 Vol 6 Part B 5.1.1,
 * WinOffset). The child's interval equals the parent's (15 ms), so a child anchor inside the parent's
 * long multi-packet events starves it for the whole connection (relaylink-gattdef-7a6c401c: 11 vs 296
 * kbps with identical settings); a public API cannot request another interval (the hidden
 * requestLeConnectionUpdate is blocked for apps on Android 11). At most [MAX_ATTEMPTS] per link.
 * DEBUG launch extra `test_relay_reanchor=true` turns it on. Line: `adaptive_phy ... relay_reanchor`.
 */
internal object RelayReanchor {
    const val MAX_ATTEMPTS = 3
    const val RESTORE_DELAY_MS = 1_000L

    /**
     * Wall-clock-free marker (System.nanoTime based) until which this process is changing a link's priority on purpose:
     * the interval-drift reassert of [LeConnectionPriorityKeeper] must not fight the BALANCED step.
     */
    @Volatile private var inProgressUntilMs = 0L

    /** True from a re-anchor request until its restore request had time to be answered. */
    val inProgress: Boolean get() = System.nanoTime() / 1_000_000L < inProgressUntilMs

    /** The restore request is answered within this time after it is sent (a connection update takes a few intervals). */
    const val SETTLE_AFTER_RESTORE_MS = 4_000L

    /**
     * With a [gate] the BALANCED and restore requests are gate steps (never overlapping a PHY or MTU request on the
     * link); a gate that is off, or no gate, sends them straight out as before.
     */
    @SuppressLint("MissingPermission")
    fun request(gatt: BluetoothGatt, restore: BleConnectionPriority, gate: LlProcedureGate? = null): Boolean {
        inProgressUntilMs = System.nanoTime() / 1_000_000L + RESTORE_DELAY_MS + SETTLE_AFTER_RESTORE_MS
        val queued = send(gatt, BleConnectionPriority.BALANCED, "reanchor_balanced", gate)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { send(gatt, restore, "reanchor_restore", gate) },
            RESTORE_DELAY_MS,
        )
        return queued
    }

    @SuppressLint("MissingPermission")
    private fun send(gatt: BluetoothGatt, priority: BleConnectionPriority, label: String, gate: LlProcedureGate?): Boolean {
        val start = { runCatching { gatt.requestConnectionPriority(priority.requestValue) }.getOrDefault(false) }
        if (gate == null) return start()
        return when (gate.submit(LlProcedure.CONNECTION_PARAMS, label) { start() }) {
            GateAdmission.IMMEDIATE_OK, GateAdmission.QUEUED -> true
            else -> false
        }
    }
}

/**
 * Plan for [AdaptivePhyLink]'s readPhy backstop: Android 13's polling plan where the
 * `android13-phy-update-callback-backstop` entry applies (SDK 33, not switched off), else null (the single
 * read of the move itself, as before).
 */
internal fun defaultBackstopPlan(): PhyWaitPlan? = runCatching {
    PhyWaitPlan.ANDROID_13.takeIf {
        PhyLinkPolicy.phyCallbackBackstopActive(android.os.Build.VERSION.SDK_INT, DeviceFamilyPolicy.switchedOff)
    }
}.getOrNull()

/**
 * This phone sends to two or more media links at once (on either LE transport). GATT links counted
 * only CoC sends before: mesh4-659efc38 0003 moved an M31 GATT source's child to 1M at 150 kbps while
 * it fed two GATT children, and remembered the ASUS as a bad-2M peer.
 */
internal fun adaptivePhySharedAirtime(
    link: String,
    sendLinks: (BluetoothTransportOption) -> Int = NodeLinkLoad::sendLinks,
): Boolean {
    val transport = if (link == "gatt") BluetoothTransportOption.BLE_GATT else BluetoothTransportOption.BLE_L2CAP
    return sendLinks(transport) >= 2 ||
        sendLinks(BluetoothTransportOption.BLE_L2CAP) + sendLinks(BluetoothTransportOption.BLE_GATT) >= 2
}

/**
 * Early loss on one private-call relay hop: the hop's link stopped making progress within
 * [AdaptivePhyMemory.EARLY_LOSS_MS] of the relay starting to forward, on 2M (confirmed, or requested and
 * never reported otherwise). Pure, so the relay's decision is testable.
 */
internal object CallRelayHopLoss {
    fun isEarly(forwardingStartNs: Long, stalledAtNs: Long, phySummary: String, requestedTwoM: Boolean, sendLinks: Int): Boolean {
        if (forwardingStartNs <= 0) return false
        val startMs = forwardingStartNs / 1_000_000
        val stalledMs = maxOf(stalledAtNs / 1_000_000, startMs)
        return AdaptivePhyMemory.isEarlyLoss(startMs, stalledMs, linkWasTwoM(phySummary, requestedTwoM), sendLinks)
    }
}

/**
 * Experimental `test_stall_rediscover_ms` (default 8000 = the earlier behaviour): how long the 1M retry after a
 * setup stall keeps scanning for the callee's new advertisement. The 2M stall closes the link, but when the
 * A15s controller never delivers LL_TERMINATE the ASUS only sees its supervision timeout 7-9 s later and
 * re-advertises 1.6 s after that, so 8.6-12.6 s after the close (lane11 b2-calls-a15s-asus); the 8 s scan,
 * clamped further by [AdaptivePhyMemory.EARLY_LOSS_MS] minus the elapsed time, ended with `none` and the retry
 * dialed the stale address (a 12 s connect timeout). In all seven successful rediscoveries of that evidence the
 * address had changed. A value above [AdaptivePhyMemory.REDISCOVER_MS] applies only after a
 * [CallSetupStalledException] and replaces the clamp for that path with the configured total.
 */
internal object StallRediscover {
    const val DEFAULT_MS = AdaptivePhyMemory.REDISCOVER_MS
    const val MAX_MS = 60_000L

    /** The configured total, kept in 8 s..60 s (8 s is the earlier behaviour). */
    fun normalize(valueMs: Long): Long = valueMs.coerceIn(DEFAULT_MS, MAX_MS)

    /** Scan budget: the earlier clamp, or the configured total after a stall when the arm is on. */
    fun budgetMs(armMs: Long, stalled: Boolean, earlyLossRemainingMs: Long): Long {
        val arm = normalize(armMs)
        return if (stalled && arm > DEFAULT_MS) arm else earlyLossRemainingMs.coerceIn(0L, DEFAULT_MS)
    }

    /** Which address the retry dials: the fresh advertisement when one arrived within the budget, else the stale one. */
    fun dialsFresh(budgetMs: Long, foundAfterMs: Long?): Boolean = foundAfterMs != null && foundAfterMs in 0..budgetMs

    fun line(budgetMs: Long, waitedMs: Long, found: Boolean, sameAddress: Boolean, stalled: Boolean, armMs: Long): String =
        "BENCHMARK call_rediscover: budgetMs=$budgetMs | waitedMs=$waitedMs | found=$found | sameAddress=$sameAddress | " +
            "dialed=${if (found) "fresh" else "stale"} | stalled=$stalled | armMs=${normalize(armMs)}"
}
