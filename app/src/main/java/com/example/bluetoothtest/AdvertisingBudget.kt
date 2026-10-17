package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSetCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What an advertising set is for. A lower [ordinal] is more important: when the controller has too few
 * advertising instances, probes go first, then the title pages, then the reach pages; the connectable offer and
 * the stream's own discovery advertisement (the session-critical sets) stay (BACKLOG A-02).
 */
internal enum class AdvertisingSetRole(val label: String) {
    /** The stream's own advertisement: connectable offer or discovery set. Never refused by the budget. */
    STABLE("stable"),

    /** Relayed-calls reach pages (second set, scan response only). */
    REACH("reach"),

    /** Public stream title / name pages (second set, scan response only). */
    TITLE("title"),

    /** Throw-away capacity probe sets. */
    PROBE("probe"),
}

/**
 * The advertising-set budget this process has learned (BACKLOG A-02, A-03; bluetooth.md Part K
 * `adv-set-count-is-controller-dependent`, `adv-legacy-single-instance`).
 *
 * The number of advertising instances is whatever the controller reports (CDD only says SHOULD 4, a cheap
 * controller can offer one) and every app and the OS share it, so there is no API to read it and no value to
 * hard-code. This object learns it from start results instead: a refusal with
 * `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS` while the app owns `n` sets means the budget is `n` right now.
 * It then
 *  - keeps the optional sets (reach, title) from starting when there is no room for them plus one spare,
 *  - lets a refused session-critical set release lower-priority sets and retry ([preemptFor]),
 *  - tracks sets whose stop is still in flight, because the stack frees the instance asynchronously and an
 *    immediate start can be refused for that reason alone ([awaitPendingStops]),
 *  - and reports the stuck state ([stuck]): a refusal while the app owns no set at all means other apps or a
 *    leaked stack (Redmi, Mi 11 Lite, Galaxy A54, Oppo reports; the Android 15 slot leak around address rotation)
 *    hold every instance, which only a Bluetooth restart clears.
 *
 * Learning, admission and preemption are ordinary after-failure behaviour (a start that would fail anyway);
 * nothing here changes an advertisement. Time and sleeping are injected so the logic is JVM-testable.
 */
internal class AdvertisingBudget(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val onMainThread: () -> Boolean = { currentThreadIsMain() },
    private val log: (String) -> Unit = { line -> Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line) },
) {
    /** One owned set: who it is for and how to ask its owner to stop it for a more important set. */
    private class Lease(val role: AdvertisingSetRole, val release: (() -> Unit)?)

    private val leases = ConcurrentHashMap<Any, Lease>()
    private val pendingStops = ConcurrentHashMap<Any, Long>()
    private val stuckListeners = CopyOnWriteArrayList<(StuckReport) -> Unit>()

    /** Largest number of sets the app held at once, all started successfully, since the last adapter restart. */
    @Volatile var provenSets: Int = 0
        private set

    /** The budget learned from a TOO_MANY refusal (sets the app may hold now), or null while none was seen. */
    @Volatile private var learnedCeiling: Int? = null
    @Volatile private var learnedAtMs = 0L

    /** Refusals with nothing owned by the app, and when the first / last happened. */
    private var emptyHandedRefusals = 0
    private var firstEmptyHandedAtMs = 0L
    private var lastEmptyHandedAtMs = 0L
    @Volatile private var stuckReport: StuckReport? = null

    /** Bumped when the adapter went off: everything learned before is void. */
    @Volatile var epoch: Int = 0
        private set

    /** The budget in force now (null: unknown). Expires so a freed instance is noticed. */
    fun ceiling(): Int? {
        val cap = AdvertisingSwitches.budgetCap.takeIf { it > 0 }
        var value = learnedCeiling
        if (value != null && clock() - learnedAtMs > CEILING_TTL_MS) {
            learnedCeiling = null
            value = null
        }
        return when {
            value == null -> cap
            cap == null -> value
            else -> minOf(value, cap)
        }
    }

    /**
     * Experimental simulation (`test_adv_budget_cap`): true when a controller with that many instances would refuse
     * one more set now. Sets whose stop is not confirmed yet still hold their instance, as on a real stack.
     */
    fun simulatedRefusal(): Boolean {
        val cap = AdvertisingSwitches.budgetCap
        return cap > 0 && leases.size + pendingStopCount() >= cap
    }

    fun owned(): Int = leases.size

    fun ownedBy(role: AdvertisingSetRole): Int = leases.values.count { it.role == role }

    /** A set was requested successfully and runs: it counts against the budget until [release]d. */
    fun acquire(token: Any, role: AdvertisingSetRole, release: (() -> Unit)? = null) {
        leases[token] = Lease(role, release)
        val now = leases.size
        if (now > provenSets) provenSets = now
        // A start worked: the budget is at least what is held now, the empty-handed streak is over and the
        // stuck state (if any) has cleared.
        if (learnedCeiling != null && now > learnedCeiling!!) learnedCeiling = null
        emptyHandedRefusals = 0
        stuckReport = null
    }

    fun release(token: Any) {
        leases.remove(token)
    }

    /** Why an optional set does not start now, or null when it may. [STABLE] is never skipped. */
    fun skipReason(role: AdvertisingSetRole): String? {
        if (role == AdvertisingSetRole.STABLE) return null
        val limit = ceiling() ?: return null
        // The new set plus one spare for the stable offer's own stop + restart (the probe rule, MIN_FREE_SETS = 2).
        return if (leases.size + 2 > limit) "budget_$limit" else null
    }

    /**
     * A start was refused with [status]. [ownedBefore] is the number of sets the app held when it asked. Returns
     * what the refusal means.
     */
    fun noteRefused(role: AdvertisingSetRole, status: Int, ownedBefore: Int, sdk: Int): Verdict {
        if (status != AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
            return Verdict(tooMany = false, ceiling = ceiling(), stuck = stuckReport != null, suspectedA15Leak = false)
        }
        val now = clock()
        learnedCeiling = ownedBefore
        learnedAtMs = now
        val leak = sdk == ANDROID_15_SDK && ownedBefore <= 1
        if (ownedBefore == 0) {
            if (emptyHandedRefusals == 0) firstEmptyHandedAtMs = now
            emptyHandedRefusals++
            lastEmptyHandedAtMs = now
            if (emptyHandedRefusals >= STUCK_REFUSALS && stuckReport == null) {
                val report = StuckReport(
                    refusals = emptyHandedRefusals,
                    firstAtMs = firstEmptyHandedAtMs,
                    suspectedA15Leak = sdk == ANDROID_15_SDK,
                )
                stuckReport = report
                log("adv_budget: event=stuck | refusals=${report.refusals} | role=${role.label} | suspectedA15Leak=${report.suspectedA15Leak}")
                stuckListeners.forEach { listener -> runCatching { listener(report) } }
            }
        }
        log("adv_budget: event=refused | role=${role.label} | owned=$ownedBefore | ceiling=$ownedBefore | proven=$provenSets | a15LeakSuspect=$leak")
        return Verdict(tooMany = true, ceiling = ownedBefore, stuck = stuckReport != null, suspectedA15Leak = leak)
    }

    /** The probe counted [free] more sets while the app held [ownedNow]; [refused] says it stopped on TOO_MANY. */
    fun noteProbe(free: Int, ownedNow: Int, refused: Boolean) {
        val total = ownedNow + free
        if (total > provenSets) provenSets = total
        if (refused) {
            learnedCeiling = total
            learnedAtMs = clock()
        }
        log("adv_budget: event=probe | free=$free | owned=$ownedNow | refused=$refused | ceiling=${ceiling()} | proven=$provenSets")
    }

    /**
     * Asks the owner of one set of strictly lower priority than [role] to stop it, so that a refused set of [role]
     * can start. Returns the role released, or null when there was none. The caller then waits with
     * [awaitPendingStops] and retries once.
     */
    fun preemptFor(role: AdvertisingSetRole): AdvertisingSetRole? {
        val victim = leases.entries
            .filter { it.value.role.ordinal > role.ordinal && it.value.release != null }
            .maxByOrNull { it.value.role.ordinal } ?: return null
        leases.remove(victim.key)
        log("adv_budget: event=preempt | for=${role.label} | released=${victim.value.role.label} | owned=${leases.size}")
        runCatching { victim.value.release?.invoke() }
        return victim.value.role
    }

    /** A stop was requested for a started set: its instance is free only after [noteStopped]. */
    fun noteStopRequested(token: Any) {
        pendingStops[token] = clock()
    }

    /** `onAdvertisingSetStopped` arrived for [token]. */
    fun noteStopped(token: Any) {
        pendingStops.remove(token)
        leases.remove(token)
    }

    /** The sets whose stop is still in flight (entries older than [PENDING_STOP_TTL_MS] are dropped as lost callbacks). */
    fun pendingStopCount(): Int {
        val now = clock()
        pendingStops.entries.removeAll { now - it.value > PENDING_STOP_TTL_MS }
        return pendingStops.size
    }

    /**
     * Waits (up to [maxMs]) until every requested stop was confirmed, so a start does not race the asynchronous
     * release of an instance. Never waits on the main thread (the callbacks come on it). Returns the time waited.
     */
    fun awaitPendingStops(maxMs: Long = SETTLE_MAX_MS): Long {
        if (maxMs <= 0 || onMainThread()) return 0
        val begin = clock()
        while (pendingStopCount() > 0 && clock() - begin < maxMs) sleeper(SETTLE_POLL_MS)
        val waited = clock() - begin
        if (waited > 0) log("adv_budget: event=settle | waitedMs=$waited | stillPending=${pendingStopCount()}")
        return waited
    }

    fun stuck(): StuckReport? = stuckReport

    /**
     * Whether a connectable set could be (re)started while the phone held a peripheral link
     * (`adv-while-connected-le-states`: many controllers cannot). Null until a watchdog restart tried it; the answer
     * is cached for the process.
     */
    @Volatile var connectedAdvertising: Boolean? = null
        private set

    fun noteConnectedAdvertising(ok: Boolean) {
        if (connectedAdvertising == ok) return
        connectedAdvertising = ok
        log("adv_budget: event=connected_advertising | works=$ok")
    }

    /** Called with the report once, when the stuck signature is first seen (the root shows it in the reliability banner). */
    fun addStuckListener(listener: (StuckReport) -> Unit) {
        stuckListeners += listener
    }

    /** Everything learned is void when the Bluetooth process or adapter restarted: instances and callbacks are gone. */
    fun onAdapterRestarted() {
        leases.clear()
        pendingStops.clear()
        learnedCeiling = null
        provenSets = 0
        emptyHandedRefusals = 0
        stuckReport = null
        connectedAdvertising = null
        AdvertisingSetProbe.lastFree = null
        epoch++
        log("adv_budget: event=adapter_restart | epoch=$epoch")
    }

    /** The adapter state broadcast: off (or turning off) voids everything. */
    fun onAdapterState(state: Int) {
        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF || state == STATE_BLE_TURNING_OFF) {
            onAdapterRestarted()
        }
    }

    data class Verdict(val tooMany: Boolean, val ceiling: Int?, val stuck: Boolean, val suspectedA15Leak: Boolean)

    /**
     * Other apps or a leaked stack hold every advertising instance: refusals kept coming while MeshLive owned no
     * set. [message] is plain language for the user; `Turn Bluetooth off and on` is the documented remedy.
     */
    data class StuckReport(val refusals: Int, val firstAtMs: Long, val suspectedA15Leak: Boolean) {
        val message: String
            get() = "Bluetooth has no free advertising slots, although MeshLive is using none. Other apps or the phone's " +
                "Bluetooth are holding them" + (if (suspectedA15Leak) " (a known Android 15 fault)" else "") +
                ". Turn Bluetooth off and on; if it persists, restart the phone."
    }

    /** The pure plan: which of [requested] may run under [capacity] sets (null: unknown, all may run). */
    fun plan(requested: List<AdvertisingSetRole>, capacity: Int? = ceiling()): Plan = planFor(requested, capacity)

    data class Plan(val run: List<AdvertisingSetRole>, val dropped: List<AdvertisingSetRole>)

    companion object {
        /** A learned budget is trusted this long; other apps' advertisers come and go. */
        const val CEILING_TTL_MS = 5 * 60_000L

        /** Two refusals with nothing owned are the stuck signature (one can be a transient foreign burst). */
        const val STUCK_REFUSALS = 2

        const val PENDING_STOP_TTL_MS = 1_500L
        const val SETTLE_MAX_MS = 700L
        const val SETTLE_POLL_MS = 20L

        /** Android 15's advertising manager leaks a slot when an address update races a set removal (e28bb6de3f, fixed in 16). */
        const val ANDROID_15_SDK = 35

        /** BluetoothAdapter.STATE_BLE_TURNING_OFF (hidden constant, value 16). */
        const val STATE_BLE_TURNING_OFF = 16

        /** The process-wide budget (one controller). */
        val shared: AdvertisingBudget by lazy { AdvertisingBudget() }

        /**
         * Keeps the roles in priority order, stable sets first and always; the optional ones fill what is left of
         * [capacity] after one instance is kept spare for the stable offer's own restart.
         */
        fun planFor(requested: List<AdvertisingSetRole>, capacity: Int?): Plan {
            val ordered = requested.sortedBy { it.ordinal }
            if (capacity == null) return Plan(ordered, emptyList())
            val stable = ordered.filter { it == AdvertisingSetRole.STABLE }
            val optional = ordered.filter { it != AdvertisingSetRole.STABLE }
            val room = (capacity - stable.size - 1).coerceAtLeast(0)
            return Plan(stable + optional.take(room), optional.drop(room))
        }

        private fun currentThreadIsMain(): Boolean = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)

        /**
         * Registers the adapter-state receiver that voids the learned budget on a Bluetooth restart. One call at
         * app start (root hook); calling it again is harmless.
         */
        @Volatile private var receiverRegistered = false

        fun registerAdapterEpoch(context: Context) {
            if (receiverRegistered) return
            receiverRegistered = true
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    shared.onAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
                }
            }
            context.applicationContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }
}

/**
 * Advertisement sizes the way the platform charges them (BluetoothLeAdvertiser.totalBytes): each AD structure costs
 * two header bytes, a connectable legacy advertisement is charged the 3-byte Flags field on Android 13 and below,
 * and from Android 14 only when it is also discoverable. A start that exceeds the budget does not report
 * `ADVERTISE_FAILED_DATA_TOO_LARGE` through the callback: `startAdvertisingSet` throws (ADV-01, `adv-set-oversize-throws`).
 */
internal object AdvertisingPayload {
    const val LEGACY_MAX = 31
    const val FLAGS_BYTES = 3
    const val AD_HEADER_BYTES = 2
    const val MANUFACTURER_ID_BYTES = 2

    /** `setDiscoverable` and the Flags rule change at API 34. */
    const val DISCOVERABLE_SDK = 34

    /** Bytes of an AD structure carrying [dataBytes] of payload. */
    fun field(dataBytes: Int): Int = AD_HEADER_BYTES + dataBytes

    /** Bytes of a manufacturer-specific AD structure with [payloadBytes] after the company identifier. */
    fun manufacturer(payloadBytes: Int): Int = field(MANUFACTURER_ID_BYTES + payloadBytes)

    /** One complete 128-bit service UUID list entry (the MeshLive service UUID is not a 16-bit-base UUID). */
    val SERVICE_UUID_128: Int = field(16)

    private val BLUETOOTH_BASE_LSB: Long = java.util.UUID.fromString("00000000-0000-1000-8000-00805f9b34fb").leastSignificantBits

    /** Width the platform gives [uuid] in a service list: 2 or 4 bytes on the Bluetooth base UUID, else 16. */
    fun uuidBytes(uuid: java.util.UUID): Int {
        val onBase = uuid.leastSignificantBits == BLUETOOTH_BASE_LSB && (uuid.mostSignificantBits and 0xFFFFFFFFL) == 0x1000L
        return when {
            !onBase -> 16
            (uuid.mostSignificantBits ushr 32) <= 0xFFFFL -> 2
            else -> 4
        }
    }

    /**
     * The room a legacy advertisement's primary data has: 31 less the Flags field when the platform charges it.
     * Scan response data is never charged flags (see [scanResponseBudget]).
     */
    fun primaryBudget(connectable: Boolean, discoverable: Boolean, sdk: Int): Int =
        LEGACY_MAX - if (flagsCharged(connectable, discoverable, sdk)) FLAGS_BYTES else 0

    fun scanResponseBudget(): Int = LEGACY_MAX

    fun flagsCharged(connectable: Boolean, discoverable: Boolean, sdk: Int): Boolean =
        connectable && (sdk < DISCOVERABLE_SDK || discoverable)

    /** The smallest primary budget any supported Android gives a connectable legacy set (Android 13 and below). */
    val CONNECTABLE_OLD_SDK_PRIMARY: Int = primaryBudget(connectable = true, discoverable = true, sdk = DISCOVERABLE_SDK - 1)

    /** The platform-formula size of a primary payload made of these parts. */
    fun primaryBytes(serviceUuids128: Int = 0, manufacturerPayloads: List<Int> = emptyList(), txPower: Boolean = false): Int =
        (if (serviceUuids128 > 0) field(16 * serviceUuids128) else 0) +
            manufacturerPayloads.sumOf { manufacturer(it) } +
            (if (txPower) field(1) else 0)

    /** True when the payload fits under the given SDK (the check `startAdvertisingSet` makes before it throws). */
    fun fits(bytes: Int, connectable: Boolean, discoverable: Boolean, sdk: Int): Boolean =
        bytes <= primaryBudget(connectable, discoverable, sdk)

    /** Plain-language size line for the DEBUG log: `primary=18/28 scan=26/31`. */
    fun describe(primary: Int, scan: Int, connectable: Boolean, discoverable: Boolean, sdk: Int): String =
        "primary=$primary/${primaryBudget(connectable, discoverable, sdk)} scan=$scan/${scanResponseBudget()}"
}

/**
 * The platform-formula size of a real [android.bluetooth.le.AdvertiseData] (BluetoothLeAdvertiser.totalBytes), for the
 * DEBUG size line. Never used to block a start: the platform's own check decides.
 */
internal object AdvertisingPayloadMeter {
    fun measure(data: android.bluetooth.le.AdvertiseData?): Int {
        if (data == null) return 0
        var size = 0
        val counts = IntArray(17)
        data.serviceUuids?.forEach { parcel -> counts[AdvertisingPayload.uuidBytes(parcel.uuid)]++ }
        for (width in intArrayOf(2, 4, 16)) {
            if (counts[width] > 0) size += AdvertisingPayload.field(width * counts[width])
        }
        data.serviceData?.forEach { (uuid, bytes) -> size += AdvertisingPayload.field(AdvertisingPayload.uuidBytes(uuid.uuid) + bytes.size) }
        val manufacturer = data.manufacturerSpecificData
        for (index in 0 until manufacturer.size()) size += AdvertisingPayload.manufacturer(manufacturer.valueAt(index).size)
        if (data.includeTxPowerLevel) size += AdvertisingPayload.field(1)
        // A device name's length is not known here; a set that includes it is not measured exactly.
        return size
    }
}
