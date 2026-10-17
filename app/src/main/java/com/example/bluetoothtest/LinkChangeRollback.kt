package com.example.bluetoothtest

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/** The link changes the app makes after a connection is up, in the order it makes them (MTU, PHY, priority). */
internal enum class LinkChangeKind(val key: String) {
    MTU("mtu"),
    PHY("phy"),
    PRIORITY("prio"),
}

/** A change requested at [atMs] (monotonic ms). */
internal data class RecordedChange(val kind: LinkChangeKind, val atMs: Long)

/** GATT/HCI statuses that mean the link broke (not a clean close): 0x08, 0x22, 0x23, 0x2A, 0x3E, 133, 147. */
internal val LINK_FAULT_STATUSES: Set<Int> = setOf(8, 34, 35, 42, 62, 133, 147)

/** Why a link was lost, as far as the timing against our own changes tells (BACKLOG P-01/P-02, controllers.md C-02). */
internal enum class LinkDropClass(val label: String) {
    /** Not a fault status: a local or remote close. */
    CLEAN("clean"),

    /** 0x3E (or 133) within a second of connecting: setup or radio scheduling, not a procedure. */
    SETUP_OR_SCHEDULING("setup_or_scheduling"),

    /** 0x23/0x2A, or a timeout status within the window after an LL-affecting change: a procedure race. */
    PROCEDURE_RACE("procedure_race_after_change"),

    /** Status 0x08 and the link was silent for about the supervision timeout this phone's priority request set (5 s on Android 10+). */
    SUPERVISION_TIMEOUT("supervision_timeout"),

    OTHER_FAULT("other_fault"),
}

/**
 * Classifies a lost link. [changes] are this link's recent requests; [msSinceConnect] is the link age;
 * [silentMs] is how long nothing was heard from the peer when it dropped (null when unknown);
 * [supervisionMs] is the last supervision timeout the stack reported for the link (null when unknown).
 */
internal fun classifyLinkDrop(
    status: Int,
    msSinceConnect: Long,
    changes: List<RecordedChange>,
    lostAtMs: Long,
    silentMs: Long? = null,
    supervisionMs: Int? = null,
    changeWindowMs: Long = 5_000L,
): LinkDropClass {
    if (status !in LINK_FAULT_STATUSES) return LinkDropClass.CLEAN
    if ((status == 62 || status == 133) && msSinceConnect in 0..1_000L) return LinkDropClass.SETUP_OR_SCHEDULING
    if (status == 35 || status == 42) return LinkDropClass.PROCEDURE_RACE
    if (status == 8 && silentMs != null && supervisionMs != null &&
        silentMs in (supervisionMs - 600L)..(supervisionMs + 1_500L)
    ) return LinkDropClass.SUPERVISION_TIMEOUT
    val recentChange = changes.any { lostAtMs - it.atMs in 0..changeWindowMs }
    if (recentChange && (status == 8 || status == 34)) return LinkDropClass.PROCEDURE_RACE
    return LinkDropClass.OTHER_FAULT
}

/** Key-value storage for [LinkChangeRollback]; a SharedPreferences on a phone, a map in tests and in process memory. */
internal interface RollbackStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

internal class MemoryRollbackStore : RollbackStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun keys(): Set<String> = map.keys.toSet()
}

internal class PreferencesRollbackStore(private val prefs: SharedPreferences) : RollbackStore {
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/**
 * Remembers, per peer, a change after which a link was lost within [windowMs] with a fault status, so the
 * next connection to that peer skips it (BACKLOG P-01 for PHY, P-02 for MTU, PHY and priority).
 *
 * Evidence (all single-report, so Experimental): a link that dies seconds after a PHY update (Samsung S21
 * and Lenovo M10, Nordic DevAcademy 436083 and 465457, Zephyr 94d7669), after an MTU/DLE of 247+ (OPPO A5s
 * MT6765 and an unanswered Nexus 6P thread, Nordic Android-BLE-Library #258, DevZone 68646), or right after
 * discovery with large packets (Redmi/OPPO, GATT 8 after 3-4 s).
 *
 * Rules:
 *  - The LAST change inside the window is rolled back first. If it is already remembered for the peer and
 *    the link is lost again, the next earlier change of the window is remembered (order PRIORITY, PHY, MTU:
 *    the reverse of the order the app makes them).
 *  - A mark is valid while [epoch] is unchanged (security patch plus Bluetooth restart count: a stack fix or
 *    a controller reset can remove the cause) and for at most [ttlMs].
 *  - Only fault statuses ([LINK_FAULT_STATUSES]) count, never a local or remote clean close.
 *
 * Peers are keyed by the caller: the authenticated hop node ID when known, else the address.
 */
internal class LinkChangeRollback(
    private val store: RollbackStore,
    private val epoch: () -> String,
    private val windowMs: Long,
    private val kinds: Set<LinkChangeKind>,
    private val ttlMs: Long = TTL_MS,
    private val log: (String) -> Unit = {},
) {
    private fun key(peer: String, kind: LinkChangeKind) = "rb.${kind.key}.$peer"

    /** The change to remember for a link lost at [lostAtMs] with [status], or null (no fault, no change in the window, nothing left). */
    fun decide(peer: String, changes: List<RecordedChange>, lostAtMs: Long, status: Int, nowMs: Long): LinkChangeKind? {
        if (status !in LINK_FAULT_STATUSES) return null
        val recent = changes.filter { it.kind in kinds && lostAtMs - it.atMs in 0..windowMs }
        if (recent.isEmpty()) return null
        val last = recent.maxByOrNull { it.atMs }!!.kind
        if (!isDowngraded(peer, last, nowMs)) return last
        return listOf(LinkChangeKind.PRIORITY, LinkChangeKind.PHY, LinkChangeKind.MTU)
            .firstOrNull { candidate -> recent.any { it.kind == candidate } && !isDowngraded(peer, candidate, nowMs) }
    }

    /** Remembers the change [decide] picks; returns it. */
    fun onLinkLost(peer: String?, changes: List<RecordedChange>, lostAtMs: Long, status: Int, nowMs: Long): LinkChangeKind? {
        if (peer.isNullOrEmpty()) return null
        val kind = decide(peer, changes, lostAtMs, status, nowMs) ?: return null
        store.write(key(peer, kind), "$nowMs|${epoch()}")
        log("BENCHMARK link_change_rollback: peer=$peer | status=$status | remembered=${kind.key} | windowMs=$windowMs")
        return kind
    }

    fun isDowngraded(peer: String?, kind: LinkChangeKind, nowMs: Long): Boolean {
        if (peer.isNullOrEmpty() || kind !in kinds) return false
        val raw = store.read(key(peer, kind)) ?: return false
        val at = raw.substringBefore('|').toLongOrNull() ?: return false
        val recordedEpoch = raw.substringAfter('|', "")
        return recordedEpoch == epoch() && nowMs - at in 0 until ttlMs
    }

    /** Removes every mark (Bluetooth restarted, or a debug reset). Returns how many. */
    fun clear(): Int {
        val mine = store.keys().filter { it.startsWith("rb.") }
        mine.forEach(store::remove)
        return mine.size
    }

    companion object {
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * The two rollback memories and the answers the connection code asks.
 *
 * - session memory (`test_ll_gate`): PHY only, window 5 s, process lifetime: BACKLOG P-01 "on a drop within
 *   ~5 s of a PHY update remember per peer, stay on 1M".
 * - persistent memory (`test_link_change_rollback`): MTU, PHY and priority, window 10 s, expires with a
 *   Bluetooth restart ([onBluetoothRestarted]) or a security-patch change: BACKLOG P-02.
 */
internal object LinkChangeRollbacks {
    const val SESSION_WINDOW_MS = 5_000L
    const val PERSISTENT_WINDOW_MS = 10_000L

    /** The MTU a peer with a remembered MTU fault is asked for (Nordic #258: 185 worked where 247 killed the link). */
    const val DOWNGRADED_MTU = 185

    @Volatile var epochProvider: () -> String = { defaultEpoch() }

    internal fun defaultEpoch(): String = "${android.os.Build.VERSION.SECURITY_PATCH}:${bluetoothRestarts}"

    /** Bumped by [onBluetoothRestarted]; part of the epoch so a restart invalidates every persisted mark. */
    @Volatile private var bluetoothRestarts = 0

    internal val session = LinkChangeRollback(
        store = MemoryRollbackStore(),
        epoch = { epochProvider() },
        windowMs = SESSION_WINDOW_MS,
        kinds = setOf(LinkChangeKind.PHY),
        ttlMs = 60L * 60 * 1000,
        log = { line -> runCatching { android.util.Log.i("BluetoothVideo", line) } },
    )

    @Volatile internal var persistent: LinkChangeRollback? = null

    /** Called once at startup (AdaptivePhyMemory.attach does it). */
    fun attach(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("link_change_rollback", Context.MODE_PRIVATE)
        persistent = LinkChangeRollback(
            store = PreferencesRollbackStore(prefs),
            epoch = { epochProvider() },
            windowMs = PERSISTENT_WINDOW_MS,
            kinds = LinkChangeKind.entries.toSet(),
            log = { line -> runCatching { android.util.Log.i("BluetoothVideo", line) } },
        )
    }

    /** Hook for the Bluetooth-restart detector (batch S BluetoothEpoch): every mark is stale after a restart. */
    fun onBluetoothRestarted() {
        bluetoothRestarts++
        session.clear()
        persistent?.clear()
    }

    /** A link to [peer] was lost; remembers a change per the enabled switches. */
    fun onLinkLost(peer: String?, changes: List<RecordedChange>, lostAtMs: Long, status: Int, nowMs: Long) {
        if (PhySwitches.llGate) session.onLinkLost(peer, changes, lostAtMs, status, nowMs)
        if (PhySwitches.linkChangeRollback) persistent?.onLinkLost(peer, changes, lostAtMs, status, nowMs)
    }

    fun isDowngraded(peer: String?, kind: LinkChangeKind, nowMs: Long = System.currentTimeMillis()): Boolean =
        (PhySwitches.llGate && session.isDowngraded(peer, kind, nowMs)) ||
            (PhySwitches.linkChangeRollback && persistent?.isDowngraded(peer, kind, nowMs) == true)

    /** The MTU cap for [peer], or null. Hook for the GATT MTU request. */
    fun mtuCap(peer: String?): Int? = DOWNGRADED_MTU.takeIf { isDowngraded(peer, LinkChangeKind.MTU) }

    /** BALANCED instead of [planned] for a peer whose priority change was followed by a lost link. Hook for the priority request. */
    fun priorityFor(peer: String?, planned: BleConnectionPriority): BleConnectionPriority =
        if ((planned == BleConnectionPriority.HIGH || planned == BleConnectionPriority.DCK) &&
            isDowngraded(peer, LinkChangeKind.PRIORITY)
        ) {
            BleConnectionPriority.BALANCED
        } else {
            planned
        }
}
