package com.example.bluetoothtest

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Schedules a task; the returned function cancels it. Android: the main looper; JVM tests: a fake clock. */
internal fun interface ScanTimer {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

internal class HandlerScanTimer : ScanTimer {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}

/**
 * The scan restart interval (batch S-02, fixes R19). AOSP downgrades a filtered scan that one app has
 * kept running: Android 10-13 `ScanManager.regularScanTimeout` after 30 minutes
 * (AdapterService default scan timeout), Android 14+ after 10 minutes (AdapterService.java
 * android14-release:6777-6782; AppScanStats.isScanningTooLong measures from the per-app
 * `mScanStartTime`, which restarts only when the app had no scan at all, A14 AppScanStats 265-267).
 * The restart therefore leaves a margin under each window: 8 min (A14+) and 25 min (A10-13). The
 * previous constant, 20 min, was longer than the Android 14+ window.
 */
internal object ScanRenewalPolicy {
    const val LEGACY_INTERVAL_MS = 20 * 60_000L
    const val ANDROID14_INTERVAL_MS = 8 * 60_000L
    const val ANDROID10_13_INTERVAL_MS = 25 * 60_000L
    const val ANDROID14_WINDOW_MS = 10 * 60_000L
    const val ANDROID10_13_WINDOW_MS = 30 * 60_000L

    /** All scanners stay stopped at least this long so the stack sees "no scan" and restarts its per-app clock. */
    const val STOP_GAP_MS = 1_000L

    /** The stack's downgrade window for [sdk]. */
    fun windowMs(sdk: Int): Long = if (sdk >= 34) ANDROID14_WINDOW_MS else ANDROID10_13_WINDOW_MS

    fun intervalMs(sdk: Int, legacy: Boolean = ScanSwitches.renewalLegacy): Long = when {
        legacy -> LEGACY_INTERVAL_MS
        sdk >= 34 -> ANDROID14_INTERVAL_MS
        else -> ANDROID10_13_INTERVAL_MS
    }

    /**
     * Whether the relay helper's own restart-by-age is due. It never queues behind the scan quota: with
     * no free slot the running scan stays (a restart would stop it and leave a gap) and the next tick
     * asks again. With the app-wide renewal on ([appWideRenewal]) that coordinator owns the age.
     */
    fun ageRestartDue(ageMs: Long, intervalMs: Long, gateHasSlot: Boolean, appWideRenewal: Boolean): Boolean =
        !appWideRenewal && ageMs >= intervalMs && gateHasSlot
}

internal enum class ScanState { RUNNING, PAUSED, HALTED }

/**
 * Registry of every live scanner of the app (batch S-03). Each owner registers a `stop` (stop the
 * system scan; idempotent) and a `start` (start it again through ScanStartGate). Fixed interface for
 * the foreground-service batch: `register`, `stopAll`, `renewAll`.
 */
internal class ScanRegistryImpl(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    class Entry(
        val name: String,
        val stop: () -> Unit,
        val start: () -> Unit,
        /** A continuous scan that may be stopped and restarted; false for a timed (call-list) scan. */
        val renewable: Boolean,
        @Volatile var startedAtMs: Long,
        /** False for a one-shot probe whose `start` is a no-op: the Bluetooth ON recovery must not stop it. */
        val restartable: Boolean = true,
    ) {
        @Volatile var state: ScanState = ScanState.RUNNING
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** When the app's scan set became non-empty (the stack's per-app clock starts here); 0 when empty. */
    @Volatile var activeSinceMs: Long = 0L
        private set

    /** Called with true when the first scanner registers and false when the last one leaves. */
    @Volatile var onOccupancyChanged: ((Boolean) -> Unit)? = null

    @Synchronized
    fun register(name: String, stop: () -> Unit, start: () -> Unit, renewable: Boolean = true, restartable: Boolean = true) {
        val wasEmpty = entries.isEmpty()
        val now = clock()
        entries[name] = Entry(name, stop, start, renewable, now, restartable)
        if (wasEmpty) {
            activeSinceMs = now
            onOccupancyChanged?.invoke(true)
        }
    }

    @Synchronized
    fun unregister(name: String) {
        if (entries.remove(name) == null) return
        if (entries.isEmpty()) {
            activeSinceMs = 0L
            onOccupancyChanged?.invoke(false)
        }
    }

    /** A resume could not start (Bluetooth off): the entry waits for the next resume. */
    @Synchronized
    fun markPausedAgain(name: String) {
        entries[name]?.state = ScanState.PAUSED
    }

    /** The scanner restarted: its age (and the app clock when everything restarted) begins now. */
    @Synchronized
    fun markStarted(name: String) {
        entries[name]?.startedAtMs = clock()
    }

    val size: Int @Synchronized get() = entries.size

    @Synchronized
    fun names(): List<String> = entries.keys.toList()

    @Synchronized
    fun state(name: String): ScanState? = entries[name]?.state

    @Synchronized
    fun running(renewableOnly: Boolean = false): List<Entry> =
        entries.values.filter { it.state == ScanState.RUNNING && (!renewableOnly || it.renewable) }

    /** A timed (call-list) discovery is in flight: renewal waits for it. */
    @Synchronized
    fun hasTimedRunning(): Boolean = entries.values.any { !it.renewable && it.state == ScanState.RUNNING }

    /** Age of the oldest running scanner (the stack's view of this app), or 0. */
    @Synchronized
    fun oldestRunningAgeMs(): Long {
        val oldest = entries.values.filter { it.state == ScanState.RUNNING }.minOfOrNull { it.startedAtMs } ?: return 0L
        return (clock() - oldest).coerceAtLeast(0L)
    }

    /**
     * Bluetooth ON recovery: what to do with every registered scanner now that the adapter is back (the ON edge was at
     * [onEdgeMs]).
     */
    @Synchronized
    fun planBluetoothOn(onEdgeMs: Long): List<Pair<Entry, BluetoothOnDecision>> =
        entries.values.map { it to BluetoothOnRecoveryPolicy.scanner(it.state, it.restartable, it.startedAtMs, onEdgeMs) }

    /**
     * Bluetooth ON recovery: stops the scanners that were started before the adapter was ON (still RUNNING here, but the stack
     * never held them), then resumes every paused one, so each restarts through its own gated start. [report] gets one
     * decision per entry.
     */
    fun recoverAfterBluetoothOn(onEdgeMs: Long, report: (String, BluetoothOnDecision) -> Unit = { _, _ -> }) {
        val plan = planBluetoothOn(onEdgeMs)
        pause(plan.filter { it.second.restart && it.first.state == ScanState.RUNNING }.map { it.first })
        for ((entry, decision) in plan) report(entry.name, decision)
        resume()
    }

    /** Stops [list] (their system scans) and marks them paused. Idempotent per entry. */
    fun pause(list: List<Entry>) {
        for (entry in list) {
            val stop = synchronized(this) {
                if (entries[entry.name] !== entry || entry.state != ScanState.RUNNING) null else {
                    entry.state = ScanState.PAUSED
                    entry.stop
                }
            } ?: continue
            runCatching(stop)
        }
    }

    /** Starts every paused entry among [candidates] (or all paused ones), skipping halted and unregistered ones. */
    fun resume(candidates: List<Entry>? = null) {
        val toStart = synchronized(this) {
            (candidates ?: entries.values.toList()).filter { entry ->
                entries[entry.name] === entry && entry.state == ScanState.PAUSED
            }.onEach { it.state = ScanState.RUNNING; it.startedAtMs = clock() }
        }
        for (entry in toStart) runCatching(entry.start)
    }

    /**
     * Teardown: stops every scanner for good (service onDestroy / onTaskRemoved, planned exits). Entries
     * stay registered, marked halted, so no renewal or Bluetooth-on restart brings them back; their owner's
     * own close() still unregisters them. Idempotent.
     */
    fun stopAll(): Int {
        val toStop = synchronized(this) {
            val list = entries.values.filter { it.state != ScanState.HALTED }
            val needStop = list.filter { it.state == ScanState.RUNNING }
            list.forEach { it.state = ScanState.HALTED }
            needStop
        }
        for (entry in toStop) runCatching(entry.stop)
        return toStop.size
    }
}

enum class RenewalOutcome { NOT_DUE, DISABLED, NOTHING_TO_RENEW, DEFERRED_CALL, DEFERRED_QUOTA, PENDING, STARTED }

/**
 * App-wide scan renewal (batch S-03; named "renewal" because RotatingDiscovery already owns "epoch").
 * Every interval it stops ALL registered scanners together, waits [ScanRenewalPolicy.STOP_GAP_MS], and
 * restarts them through the scan-start gate. A restart that the quota could not absorb, or a call
 * discovery in flight, defers the renewal; it never stacks. Experimental, default off
 * (`test_scan_renewal_all`); [renewNow] with `force` is also used for environment changes and for the
 * watchdog's one gated restart, which are not Experimental arms of their own.
 */
internal class ScanRenewal(
    private val registry: ScanRegistryImpl,
    private val gate: ScanStartGate,
    private val budget: () -> LeScanStartBudget?,
    private val timer: ScanTimer,
    private val clock: () -> Long,
    private val sdk: Int,
    private val enabled: () -> Boolean = { ScanSwitches.renewalAll },
    private val legacy: () -> Boolean = { ScanSwitches.renewalLegacy },
    /** A private call is dialling or ringing right now (set by the call layer). */
    private val dialInProgress: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    @Volatile var lastRenewalMs: Long = 0L
        private set

    /** Stop-gap is running: scanners are stopped and not yet restarted. */
    @Volatile var pending: Boolean = false
        private set

    @Volatile var renewals: Int = 0
        private set

    val interval: Long get() = ScanRenewalPolicy.intervalMs(sdk, legacy())

    /** Called every tick (about 30 s). Renews when the app's scan clock passed [interval]. */
    fun tick(): RenewalOutcome {
        if (!enabled()) return RenewalOutcome.DISABLED
        if (pending) return RenewalOutcome.PENDING
        val now = clock()
        val clockStart = maxOf(registry.activeSinceMs, lastRenewalMs)
        if (registry.activeSinceMs == 0L || now - clockStart < interval) return RenewalOutcome.NOT_DUE
        return renewNow("interval", force = false)
    }

    /**
     * Stops and restarts every renewable scanner. Without [force] a timed discovery in flight or a dial
     * defers it; with it (location switched on, watchdog) only the quota can.
     */
    fun renewNow(reason: String, force: Boolean): RenewalOutcome {
        if (pending) return RenewalOutcome.PENDING
        val list = registry.running(renewableOnly = true)
        if (list.isEmpty()) return RenewalOutcome.NOTHING_TO_RENEW
        if (!force && (registry.hasTimedRunning() || dialInProgress())) {
            log("scan_renewal: event=deferred | reason=$reason | why=call_in_progress | scanners=${list.size}")
            return RenewalOutcome.DEFERRED_CALL
        }
        val limit = budget()
        if (limit != null) {
            // Keep one slot free for a call's discovery unless the whole budget is smaller than that.
            val need = (list.size + if (force) 0 else 1).coerceAtMost(limit.maxStarts)
            val free = limit.maxStarts - gate.used(limit)
            if (free < need) {
                log("scan_renewal: event=deferred | reason=$reason | why=scan_quota | scanners=${list.size} | freeSlots=$free")
                return RenewalOutcome.DEFERRED_QUOTA
            }
        }
        pending = true
        registry.pause(list)
        log("scan_renewal: event=stopped | reason=$reason | scanners=${list.size} | names=${list.joinToString(",") { it.name }}")
        timer.schedule(ScanRenewalPolicy.STOP_GAP_MS) {
            registry.resume(list)
            lastRenewalMs = clock()
            renewals++
            pending = false
            log("scan_renewal: event=restarted | reason=$reason | scanners=${list.size} | renewals=$renewals")
        }
        return RenewalOutcome.STARTED
    }

    /** The Location switch went on (S-01): scanners were silently blocked; restart them, gated. */
    fun restartAfterEnvironmentChange(): RenewalOutcome = renewNow("environment", force = true)
}

/**
 * Pause bookkeeping for the connect-establishment arm (batch S-07, Experimental, default off). The
 * dial paths call [pauseFor]; with `test_scan_pause_during_connect` off they only record the window,
 * which tags discovery statistics that overlap an own connect attempt (diagnostic, always on).
 */
internal class ScanGateImpl(
    private val registry: ScanRegistryImpl,
    private val timer: ScanTimer,
    private val clock: () -> Long,
    private val pauseEnabled: () -> Boolean = { ScanSwitches.pauseDuringConnect },
) {
    private val windows = ArrayDeque<LongArray>() // [startMs, endMs]
    private var pausedUntilMs = 0L
    private var cancelResume: (() -> Unit)? = null
    private var paused: List<ScanRegistryImpl.Entry> = emptyList()

    @Volatile var pausedNow: Boolean = false
        private set

    @Synchronized
    fun pauseFor(ms: Long) {
        val now = clock()
        val end = now + ms.coerceAtLeast(0L)
        windows.addLast(longArrayOf(now, end))
        while (windows.size > MAX_WINDOWS) windows.removeFirst()
        if (!pauseEnabled() || ms <= 0L) return
        if (!pausedNow) {
            paused = registry.running(renewableOnly = true)
            registry.pause(paused)
            pausedNow = true
        }
        pausedUntilMs = maxOf(pausedUntilMs, end)
        cancelResume?.invoke()
        cancelResume = timer.schedule(pausedUntilMs - now) { resume() }
    }

    @Synchronized
    private fun resume() {
        if (!pausedNow) return
        pausedNow = false
        cancelResume = null
        pausedUntilMs = 0L
        // The restart is the entries' own start (it reserves a slot at ScanStartGate).
        registry.resume(paused)
        paused = emptyList()
    }

    /** Milliseconds of [startMs, endMs] that overlap own connect attempts; 0 when none. */
    @Synchronized
    fun overlapMs(startMs: Long, endMs: Long): Long {
        var total = 0L
        for (w in windows) {
            val from = maxOf(startMs, w[0])
            val to = minOf(endMs, w[1])
            if (to > from) total += to - from
        }
        return total
    }

    private companion object {
        const val MAX_WINDOWS = 64
    }
}

/** Process-wide instances (batch S). The wiring to Android (clock, timer, gate) lives here and in [ScanRuntime]. */
internal object ScanRegistry {
    val shared = ScanRegistryImpl()

    fun register(name: String, stop: () -> Unit, start: () -> Unit, renewable: Boolean = true, restartable: Boolean = true) =
        shared.register(name, stop, start, renewable, restartable)

    fun unregister(name: String) = shared.unregister(name)

    /** Stops every app scanner for good (foreground-service teardown, planned exits). Idempotent. */
    fun stopAll() {
        shared.stopAll()
    }

    /**
     * Stops all scanners together and restarts them through the scan-start gate. Subject to the same
     * deferral rules as the timed renewal; true when a renewal started.
     */
    fun renewAll(): Boolean = ScanRuntime.renewal.renewNow("manual", force = false) == RenewalOutcome.STARTED
}

/** `ScanGate.pauseFor(ms)`: consulted by BleL2capDiscovery's dial paths (root hook in the GATT/CoC connect code). */
internal object ScanGate {
    val shared by lazy { ScanGateImpl(ScanRegistry.shared, HandlerScanTimer(), SystemClock::elapsedRealtime) }

    fun pauseFor(ms: Long) = shared.pauseFor(ms)

    fun overlapMs(startMs: Long, endMs: Long): Long = shared.overlapMs(startMs, endMs)
}

internal fun scanLog(line: String) {
    Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line)
}
