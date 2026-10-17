package com.example.bluetoothtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Experimental, default off (`test_session_wake_lock`, registry entry `fgs-session-wake-lock`): an operation-scoped
 * PARTIAL_WAKE_LOCK while a screen-off session forwards media without an open microphone or speaker (device-family
 * backlog B-04; research cpu-power CP-01/CP-28/CP-42, codec-background B6).
 *
 * Why: a foreground service keeps the process alive, not the CPU. A call with an open microphone or speaker is
 * protected by the audio stack's own wake locks; a video-only relay or stream hop is not, so its timers can fire late
 * while the screen is off. AOSP keeps a foreground service's wake lock in Doze (PowerManagerService disables it only
 * for a process state worse than BOUND_FOREGROUND_SERVICE) and Low Power Standby disables it; Samsung One UI 3-5
 * reportedly blocks it, so a held lock proves nothing: check `adb shell dumpsys power` and timer lateness before any
 * default changes.
 *
 * Shape: the lock is renewable (Briar's pattern): every [RENEW_MS] the controller re-evaluates and re-acquires with a
 * [LOCK_TIMEOUT_MS] safety timeout, so a dead process or a missed release cannot hold the CPU awake for long. It is
 * held only while the screen is off, the session is an active hop (not idle availability) and the rolling daily
 * budget is not used up. The budget is a hard cap below the Play vitals "excessive wake lock" threshold (2 h/24 h).
 */
internal enum class WakeLockDecision { HOLD, RELEASE_DISABLED, RELEASE_SCREEN_ON, RELEASE_IDLE, RELEASE_BUDGET }

internal object SessionWakeLockPolicy {
    fun decide(enabled: Boolean, screenInteractive: Boolean, hopLive: Boolean, budgetLeftMs: Long): WakeLockDecision = when {
        !enabled -> WakeLockDecision.RELEASE_DISABLED
        screenInteractive -> WakeLockDecision.RELEASE_SCREEN_ON
        !hopLive -> WakeLockDecision.RELEASE_IDLE
        budgetLeftMs <= 0L -> WakeLockDecision.RELEASE_BUDGET
        else -> WakeLockDecision.HOLD
    }
}

/** The held-time budget: a fixed 24 h window (not a sliding one), reset when the window has elapsed. */
internal class WakeLockBudget(private val store: Store, private val capMs: () -> Long, private val windowMs: Long = DAY_MS) {
    interface Store {
        var windowStartMs: Long
        var heldMs: Long
    }

    /** Milliseconds that may still be held now; Long.MAX_VALUE when the cap is off (0 minutes). */
    fun remainingMs(nowMs: Long): Long {
        val cap = capMs()
        if (cap <= 0L) return Long.MAX_VALUE
        rollWindow(nowMs)
        return (cap - store.heldMs).coerceAtLeast(0L)
    }

    fun add(nowMs: Long, heldMs: Long) {
        if (heldMs <= 0L) return
        rollWindow(nowMs)
        store.heldMs += heldMs
    }

    fun heldMs(nowMs: Long): Long {
        rollWindow(nowMs)
        return store.heldMs
    }

    private fun rollWindow(nowMs: Long) {
        if (store.windowStartMs <= 0L || nowMs - store.windowStartMs >= windowMs || nowMs < store.windowStartMs) {
            store.windowStartMs = nowMs
            store.heldMs = 0L
        }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

internal interface WakeLockHandle {
    val isHeld: Boolean
    fun acquire(timeoutMs: Long)
    fun release()
}

/**
 * The lock state machine; the service drives [tick] every [RENEW_MS]. Pure so the lifecycle is unit tested with a fake
 * clock and a fake lock.
 */
internal class SessionWakeLockController(
    private val lock: WakeLockHandle,
    private val budget: WakeLockBudget,
    private val enabled: () -> Boolean,
    private val screenInteractive: () -> Boolean,
    private val hopLive: () -> Boolean,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private var heldSinceMs = 0L
    private var accountedUntilMs = 0L
    var lastDecision: WakeLockDecision? = null
        private set

    /** Evaluate now: acquire or refresh the lock when it is wanted, release it otherwise. */
    fun tick(): WakeLockDecision {
        val now = clock()
        account(now)
        val decision = SessionWakeLockPolicy.decide(enabled(), screenInteractive(), hopLive(), budget.remainingMs(now))
        if (decision == WakeLockDecision.HOLD) {
            if (!lock.isHeld) {
                heldSinceMs = now
                accountedUntilMs = now
                log("session_wake_lock: state=acquired | budget_left_min=${budgetLeftMinutes(now)}")
            }
            lock.acquire(LOCK_TIMEOUT_MS)
        } else if (lock.isHeld) {
            release(now, decision.name.lowercase())
        }
        lastDecision = decision
        return decision
    }

    /** Session end or service destroy: always release and account the time. */
    fun stop(reason: String = "service_stopped") {
        val now = clock()
        account(now)
        if (lock.isHeld) release(now, reason)
        lastDecision = null
    }

    private fun release(now: Long, reason: String) {
        lock.release()
        log("session_wake_lock: state=released | reason=$reason | held_ms=${now - heldSinceMs} | day_held_min=${budget.heldMs(now) / 60_000}")
    }

    private fun account(now: Long) {
        if (!lock.isHeld) return
        budget.add(now, now - accountedUntilMs)
        accountedUntilMs = now
    }

    private fun budgetLeftMinutes(now: Long): Long {
        val left = budget.remainingMs(now)
        return if (left == Long.MAX_VALUE) -1 else left / 60_000
    }

    companion object {
        const val RENEW_MS = 60_000L
        const val LOCK_TIMEOUT_MS = 90_000L
    }
}

/** Android binding: tag `meshlive:session`, not reference counted. */
internal object SessionWakeLock {
    const val TAG = "meshlive:session"

    /**
     * Root hook (optional): true while this phone forwards media. The default treats every active session as a live hop;
     * the main activity may narrow it (for example to "a child is subscribed").
     */
    @Volatile var hopLive: () -> Boolean = { true }

    @Volatile private var currentKind = ServiceTypePolicy.SessionKind.IDLE_AVAILABILITY
    private var controller: SessionWakeLockController? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            val current = controller ?: return
            runCatching { current.tick() }
            if (controller != null) handler.postDelayed(this, SessionWakeLockController.RENEW_MS)
        }
    }

    /** Call after startForeground succeeded (the lock lives and dies with the foreground state). Main thread. */
    fun onSessionStarted(context: Context, kind: ServiceTypePolicy.SessionKind) {
        if (!ServiceSwitches.sessionWakeLock || !ServiceFamilyPolicy.sessionWakeLockActive()) return
        currentKind = kind
        if (kind == ServiceTypePolicy.SessionKind.IDLE_AVAILABILITY) {
            // Idle availability never holds the lock; a controller from an earlier active start re-evaluates at once.
            controller?.let { runCatching { it.tick() } }
            return
        }
        val power = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
        if (controller == null) {
            val appContext = context.applicationContext
            val store = PreferencesBudgetStore(appContext)
            controller = SessionWakeLockController(
                lock = PowerManagerLock(power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply { setReferenceCounted(false) }),
                budget = WakeLockBudget(store, { ServiceSwitches.sessionWakeLockCapMinutes * 60_000L }),
                enabled = { ServiceSwitches.sessionWakeLock },
                screenInteractive = { power.isInteractive },
                hopLive = { currentKind == ServiceTypePolicy.SessionKind.ACTIVE && hopLive() },
                clock = System::currentTimeMillis,
                log = ::logLine,
            )
        }
        if (!ticking) {
            ticking = true
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    /** Service onDestroy or stopForeground. */
    fun onSessionEnded() {
        handler.removeCallbacks(tick)
        ticking = false
        controller?.stop()
        controller = null
    }

    private fun logLine(line: String) {
        try { android.util.Log.i("BluetoothVideo", "BENCHMARK $line") } catch (_: RuntimeException) {}
    }

    private class PowerManagerLock(private val lock: PowerManager.WakeLock) : WakeLockHandle {
        override val isHeld: Boolean get() = lock.isHeld
        override fun acquire(timeoutMs: Long) = lock.acquire(timeoutMs)
        override fun release() { if (lock.isHeld) lock.release() }
    }

    /**
     * The budget survives process death (a fresh process must not restart the day's count); it uses the UI preferences
     * file, which the emergency wipe already clears. Times are wall-clock milliseconds (elapsedRealtime restarts at boot and would lose the day's count).
     */
    private class PreferencesBudgetStore(context: Context) : WakeLockBudget.Store {
        private val prefs = context.getSharedPreferences(OnboardingActivity.PREFERENCES, Context.MODE_PRIVATE)
        override var windowStartMs: Long
            get() = prefs.getLong(KEY_WINDOW, 0L)
            set(value) { prefs.edit().putLong(KEY_WINDOW, value).apply() }
        override var heldMs: Long
            get() = prefs.getLong(KEY_HELD, 0L)
            set(value) { prefs.edit().putLong(KEY_HELD, value).apply() }
    }

    private const val KEY_WINDOW = "session_wake_lock_window_ms"
    private const val KEY_HELD = "session_wake_lock_held_ms"
}
