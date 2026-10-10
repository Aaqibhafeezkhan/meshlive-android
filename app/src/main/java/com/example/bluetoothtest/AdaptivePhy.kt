package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
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
    enum class Verdict { NONE, KEEP, MOVE_TO_1M }

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
        if (meanKbps * 4 < oneEventKbps) return Verdict.KEEP
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
) {
    private var policy = AdaptivePhyPolicy()
    @Volatile private var txPhy = 0
    @Volatile private var moved = false
    private var reanchors = 0

    fun onPhy(tx: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) txPhy = tx
    }

    @SuppressLint("MissingPermission")
    fun onWrite(writeBytes: Int, blockedMs: Long) {
        val reanchorOn = DebugTunables.relayReanchor && RelayUpstream.active
        if (moved || (!DebugTunables.adaptivePhy && !reanchorOn)) return
        val intervalMs = LeConnectionParameterLog.latest(address)?.intervalMs?.takeIf { it > 0 }
            ?: AdaptivePhyPolicy.DEFAULT_INTERVAL_MS
        val oneEvent = AdaptivePhyPolicy.oneEventKbps(intervalMs)
        val verdict = policy.onWrite(
            clock(),
            writeBytes,
            blockedMs,
            txPhy == BluetoothDevice.PHY_LE_2M,
            oneEvent,
            sharedAirtime = adaptivePhySharedAirtime(link),
        )
        if (verdict != AdaptivePhyPolicy.Verdict.NONE && reanchorOn &&
            relayChildStarved(policy.lastKbps, oneEvent) && reanchors < RelayReanchor.MAX_ATTEMPTS
        ) {
            reanchors++
            policy = AdaptivePhyPolicy()
            val queued = RelayReanchor.request(gatt, restorePriority)
            log("phy=unchanged", "relay_reanchor | attempt=$reanchors | queued=$queued", intervalMs, oneEvent)
            return
        }
        if (!DebugTunables.adaptivePhy) return
        when (verdict) {
            AdaptivePhyPolicy.Verdict.NONE -> Unit
            AdaptivePhyPolicy.Verdict.KEEP -> log("from=2M to=2M", "kept", intervalMs, oneEvent)
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
                moved = true
                val queued = moveTo1M()
                log("from=2M to=1M", "one_pdu_per_event | queued=$queued", intervalMs, oneEvent)
                AdaptivePhyMemory.recordBadForAddress(address, "one_pdu_per_event")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun moveTo1M(): Boolean = runCatching {
        gatt.setPreferredPhy(
            BluetoothDevice.PHY_LE_1M_MASK,
            BluetoothDevice.PHY_LE_1M_MASK,
            BluetoothDevice.PHY_OPTION_NO_PREFERRED,
        )
        gatt.readPhy()
    }.isSuccess

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

    @Volatile private var prefs: SharedPreferences? = null
    private val addressHops = ConcurrentHashMap<String, Pair<BluetoothTransportOption, String>>()

    fun attach(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs?.let { store ->
            val stale = store.all.keys.filter { it.startsWith("bad2m.") }
            if (stale.isNotEmpty()) {
                store.edit().apply { stale.forEach(::remove) }.apply()
                Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | cleared_format1=${stale.size}")
            }
            if (!store.getBoolean(GATT_FANOUT_PURGED, false)) {
                val polluted = gattFanOutPollutedKeys(store.all.keys)
                store.edit().apply {
                    polluted.forEach(::remove)
                    putBoolean(GATT_FANOUT_PURGED, true)
                }.apply()
                Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | cleared_gatt_fanout=${polluted.size}")
            }
            if (DebugTunables.relayPhyGuard && !store.getBoolean(RELAYED_CALL_PURGED, false)) {
                val relayed = relayedCallViaKeys(store.all.keys)
                store.edit().apply {
                    relayed.forEach(::remove)
                    putBoolean(RELAYED_CALL_PURGED, true)
                }.apply()
                Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | cleared_relayed_call_via=${relayed.size}")
            }
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
        val keys = store.all.keys.filter { it.startsWith("bad2m") }
        if (keys.isNotEmpty()) store.edit().apply { keys.forEach(::remove) }.commit()
        return keys.size
    }

    /** DEBUG launch record: every bad-2M mark with its age in hours and whether it is still fresh. */
    fun summary(nowMs: Long = System.currentTimeMillis()): String {
        val store = prefs ?: return "unavailable"
        val marks = store.all.filter { (key, value) -> key.startsWith("bad2m") && value is Long }
        if (marks.isEmpty()) return "none"
        return marks.entries.sortedBy { it.key }.joinToString(" | ") { (key, value) ->
            val at = value as Long
            "%s=%.1fh%s".format(java.util.Locale.ROOT, key, (nowMs - at) / 3_600_000.0, if (fresh(at, nowMs)) "" else "(stale)")
        }
    }

    /** Early loss: a 2M link lost within [EARLY_LOSS_MS] of authentication while not sending on 2+ links. */
    internal fun isEarlyLoss(authenticatedAtMs: Long, lostAtMs: Long, wasTwoM: Boolean, sendLinks: Int): Boolean =
        wasTwoM && sendLinks < 2 && authenticatedAtMs > 0 && lostAtMs - authenticatedAtMs in 0..EARLY_LOSS_MS

    fun isBad(transport: BluetoothTransportOption, node: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || node.isEmpty()) return false
        return fresh(prefs?.getLong(key(transport, node), 0L) ?: 0L, nowMs)
    }

    fun recordBad(transport: BluetoothTransportOption, node: String, reason: String) {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || node.isEmpty()) return
        if (RelayUpstream.active && DebugTunables.relayPhyGuard) {
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=$node | bad2m=not_recorded | reason=$reason | relay_upstream=true")
            return
        }
        prefs?.edit()?.putLong(key(transport, node), System.currentTimeMillis())?.apply()
        Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=$node | transport=${transport.name} | bad2m=recorded | reason=$reason")
    }

    /** The link to [address] authenticated [node] as its hop peer on [transport]. */
    fun bind(address: String?, transport: BluetoothTransportOption, node: String, hopRelays: Boolean = false) {
        if (address == null || node.isEmpty()) return
        addressHops[address] = transport to node
        if (hopRelays) relayingHops += address else relayingHops -= address
    }

    /** Addresses whose bound hop peer forwards this session onward (a relayed call's relay hop). */
    private val relayingHops: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun recordBadForAddress(address: String?, reason: String) {
        val (transport, node) = address?.let(addressHops::get) ?: return
        if (skipsRelayingHop(address in relayingHops, DebugTunables.relayPhyGuard)) {
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=$node | bad2m=not_recorded | reason=$reason | hop_relays=true")
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
     * 1M while that peer is remembered bad on 2M, otherwise [default].
     */
    fun phyFor(
        transport: BluetoothTransportOption,
        nodePrefix: ByteArray,
        default: BlePhyPreference,
        link: String,
        relayedOffer: Boolean = false,
    ): BlePhyPreference {
        val node = prefixLabel(nodePrefix)
        // A relayed offer is advertised by the hop relay with the far callee's prefix, so the hop's own
        // node is unknown before connecting; its entry is kept under that advertised prefix instead.
        val bad = if (relayedOffer) isBadVia(transport, node) else isBad(transport, node)
        if (!bad) return default
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=$link | node=$node | from=2M to=1M | reason=remembered | " +
                "at=connect | via=$relayedOffer",
        )
        return BlePhyPreference.ONE_M_ADAPTIVE
    }

    internal fun prefixLabel(nodePrefix: ByteArray): String = nodePrefix.joinToString("") { "%02x".format(it) }

    internal fun viaKey(transport: BluetoothTransportOption, advertisedPrefix: String) =
        "bad2m${FORMAT}via.${transport.name}.$advertisedPrefix"

    fun isBadVia(transport: BluetoothTransportOption, advertisedPrefix: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || advertisedPrefix.isEmpty()) return false
        return fresh(prefs?.getLong(viaKey(transport, advertisedPrefix), 0L) ?: 0L, nowMs)
    }

    /** The hop that carries a relayed offer advertised as [advertisedPrefix] was bad on 2M. */
    fun recordBadVia(transport: BluetoothTransportOption, advertisedPrefix: String, reason: String) {
        if (!enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM || advertisedPrefix.isEmpty()) return
        if (RelayUpstream.active && DebugTunables.relayPhyGuard) return
        prefs?.edit()?.putLong(viaKey(transport, advertisedPrefix), System.currentTimeMillis())?.apply()
        Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | via=$advertisedPrefix | transport=${transport.name} | bad2m=recorded | reason=$reason")
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
        relays.incrementAndGet()
    }

    fun exit() {
        relays.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

/**
 * A relay child link measured (backlogged, after a MOVE_TO_1M verdict) at no less than half of one PDU
 * per event: the one-PDU-per-event 2M pattern, not the 4-37 kbps a parent/child anchor collision leaves
 * at 15 ms (relaylink-gattdef-7a6c401c). The verdict already bounds it at 1.25 PDUs per event.
 */
internal fun relayOnePduBand(meanKbps: Int, oneEventKbps: Int): Boolean =
    oneEventKbps > 0 && meanKbps * 2 >= oneEventKbps

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

    @SuppressLint("MissingPermission")
    fun request(gatt: BluetoothGatt, restore: BleConnectionPriority): Boolean {
        val queued = runCatching { gatt.requestConnectionPriority(BleConnectionPriority.BALANCED.requestValue) }.getOrDefault(false)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching { gatt.requestConnectionPriority(restore.requestValue) } },
            RESTORE_DELAY_MS,
        )
        return queued
    }
}

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
