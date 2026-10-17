package com.example.bluetoothtest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

/** What the phone's power manager is doing to background work, most restrictive first. */
internal enum class DeviceSleepState(val label: String) {
    /** Doze (ACTION_DEVICE_IDLE_MODE_CHANGED, isDeviceIdleMode): CPU, alarms and jobs are deferred. */
    DEEP_IDLE("deep_idle"),

    /** Low Power Standby enabled (API 33): network and wake locks are restricted while idle. */
    LOW_POWER_STANDBY("low_power_standby"),

    /** Light doze (public from API 33): maintenance windows only. */
    LIGHT_IDLE("light_idle"),

    AWAKE("awake"),
}

/** One reading of the power manager; null fields are not available on this API level. */
internal data class DozeSnapshot(
    val deviceIdle: Boolean,
    val lightIdle: Boolean?,
    val lowPowerStandby: Boolean?,
    val powerSave: Boolean,
    /** PowerManager.isExemptFromLowPowerStandby (API 34): this app is exempt while Low Power Standby is on; null before API 34. */
    val lowPowerStandbyExempt: Boolean? = null,
)

/** Pure rules (unit-tested). */
internal object DozePolicy {
    fun state(snapshot: DozeSnapshot): DeviceSleepState = when {
        snapshot.deviceIdle -> DeviceSleepState.DEEP_IDLE
        snapshot.lowPowerStandby == true -> DeviceSleepState.LOW_POWER_STANDBY
        snapshot.lightIdle == true -> DeviceSleepState.LIGHT_IDLE
        else -> DeviceSleepState.AWAKE
    }

    /** What the UI shows; null while awake. */
    fun message(state: DeviceSleepState): String? = when (state) {
        DeviceSleepState.DEEP_IDLE -> "Phone is in deep sleep; calls may be delayed."
        DeviceSleepState.LOW_POWER_STANDBY -> "Low power standby is on; calls may be delayed while the phone is idle."
        DeviceSleepState.LIGHT_IDLE -> "Phone is dozing; calls may be delayed."
        DeviceSleepState.AWAKE -> null
    }

    /** True when [next] should be reported (state or any reading changed). */
    fun changed(previous: DozeSnapshot?, next: DozeSnapshot): Boolean = previous != next

    /**
     * The banner text for [snapshot]: like [message], but Low Power Standby says so when this app is exempt from it
     * (API 34 `isExemptFromLowPowerStandby`, e.g. while it holds an ongoing-call foreground service), because then calls
     * are not held back by it (backlog B-08). Deep sleep still outranks it.
     */
    fun message(snapshot: DozeSnapshot): String? {
        val state = state(snapshot)
        if (state == DeviceSleepState.LOW_POWER_STANDBY && snapshot.lowPowerStandbyExempt == true) {
            return "Low power standby is on, but this app is allowed to keep working."
        }
        return message(state)
    }

    fun line(snapshot: DozeSnapshot, trigger: String): String =
        "doze_state: state=${state(snapshot).label} | deviceIdle=${snapshot.deviceIdle} | " +
            "lightIdle=${snapshot.lightIdle ?: "unavailable"} | " +
            "lowPowerStandby=${snapshot.lowPowerStandby ?: "unavailable"} | powerSave=${snapshot.powerSave} | " +
            // Only on API 34+, so the line keeps its older shape elsewhere.
            (snapshot.lowPowerStandbyExempt?.let { "lowPowerStandbyExempt=$it | " } ?: "") +
            "trigger=$trigger"
}

/**
 * Timer lateness (backlog B-16, from xDrip's "buggy handset" detector): how late a timer that should fire every
 * [periodMs] arrives. Phone makers' power managers delay timers with the screen off; this measures it instead of
 * guessing from the manufacturer. Root hook: call [onTick] from the session's 1 s heartbeat and log [line] when the
 * session ends. Gaps longer than [resumeAfterMs] mean the heartbeat was not running (no session) and only re-anchor.
 * It decides nothing; setAlarmClock and exact alarms were rejected as keep-alives (backlog B-18).
 */
internal class TimerLateness(
    private val periodMs: Long = 1_000L,
    private val resumeAfterMs: Long = 30L * 60_000L,
) {
    private var lastTickMs = NONE
    private var ticks = 0L
    private var maxLateMs = 0L
    private var lastLateMs = 0L
    private var over1s = 0L
    private var over5s = 0L

    /** Records a tick at [nowMs] (monotonic ms) and returns how late it was. */
    @Synchronized
    fun onTick(nowMs: Long): Long {
        val previous = lastTickMs
        lastTickMs = nowMs
        if (previous == NONE) return 0L
        val gap = nowMs - previous
        if (gap > resumeAfterMs) return 0L
        val late = (gap - periodMs).coerceAtLeast(0L)
        ticks++
        lastLateMs = late
        if (late > maxLateMs) maxLateMs = late
        if (late >= 1_000L) over1s++
        if (late >= 5_000L) over5s++
        return late
    }

    @Synchronized
    fun reset() {
        lastTickMs = NONE
        ticks = 0L
        maxLateMs = 0L
        lastLateMs = 0L
        over1s = 0L
        over5s = 0L
    }

    @Synchronized
    fun maxLateMs(): Long = maxLateMs

    @Synchronized
    fun line(sleepState: String): String =
        "timer_lateness: ticks=$ticks | max_late_ms=$maxLateMs | last_late_ms=$lastLateMs | over_1s=$over1s | over_5s=$over5s | " +
            "period_ms=$periodMs | doze=$sleepState"

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * Doze watchdog (research catalog P15b, 2026-10-10; a Briar-style pattern, no code copied). Observes
 * Doze (`PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`, API 23), light doze
 * (`ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED` + `isDeviceLightIdleMode`, public from API 33), Low Power
 * Standby (`ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED` + `isLowPowerStandbyEnabled`, API 33) and battery
 * saver (`ACTION_POWER_SAVE_MODE_CHANGED`, logged only). It only observes: nothing in the app changes
 * behaviour. Every change is logged (`BENCHMARK doze_state` in DEBUG builds).
 *
 * UI contract: call [attach] once (MainActivity does); read [state] / [message] at any time; add a
 * listener for changes (main thread); [getAndResetSlept] tells whether deep sleep or standby happened
 * since the last call ("this phone slept; connections may have dropped").
 */
internal object DozeWatchdog {
    private const val TAG = "BluetoothVideo"
    private val listeners = CopyOnWriteArrayList<(DeviceSleepState) -> Unit>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var powerManager: PowerManager? = null
    @Volatile private var last: DozeSnapshot? = null
    @Volatile private var slept = false
    private var receiver: BroadcastReceiver? = null

    val state: DeviceSleepState get() = last?.let(DozePolicy::state) ?: DeviceSleepState.AWAKE

    val snapshot: DozeSnapshot? get() = last

    /** The text the UI may show, or null while awake. Low Power Standby says when this app is exempt from it (API 34). */
    fun message(): String? {
        val current = last ?: return DozePolicy.message(state)
        // The exemption can change without a broadcast (an ongoing call starts), so read it again for the text.
        val fresh = if (Build.VERSION.SDK_INT >= 34) runCatching { powerManager?.isExemptFromLowPowerStandby }.getOrNull() else null
        return DozePolicy.message(current.copy(lowPowerStandbyExempt = fresh ?: current.lowPowerStandbyExempt))
    }

    /** Session heartbeat timer lateness (B-16); see [TimerLateness]. */
    val timerLateness = TimerLateness()

    /** Root hook: the session's ~1 s heartbeat. */
    fun onSessionTick(nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        timerLateness.onTick(nowMs)
    }

    /** Root hook: when the session ends. Logs `timer_lateness` (BENCHMARK in DEBUG) and starts a new measurement. */
    fun logTimerLateness() {
        val line = timerLateness.line(state.label)
        if (BuildConfig.DEBUG) Log.i(TAG, "BENCHMARK $line") else Log.w(TAG, line)
        timerLateness.reset()
    }

    /** Registers the receivers once per process (application context); later calls only refresh. */
    @Synchronized
    fun attach(context: Context) {
        val app = context.applicationContext
        if (receiver == null) {
            powerManager = app.getSystemService(PowerManager::class.java)
            val filter = IntentFilter().apply {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) {
                    addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
                    addAction(PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED)
                }
            }
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh(intent.action ?: "broadcast")
            }
            // System broadcasts reach a not-exported receiver.
            ContextCompat.registerReceiver(app, created, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = created
        }
        refresh("attach")
    }

    fun addListener(listener: (DeviceSleepState) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (DeviceSleepState) -> Unit) {
        listeners -= listener
    }

    /** True once after the phone was in deep sleep or Low Power Standby (Briar's doze-flag pattern). */
    fun getAndResetSlept(): Boolean {
        val value = slept
        slept = false
        return value
    }

    private fun read(): DozeSnapshot? {
        val pm = powerManager ?: return null
        return DozeSnapshot(
            deviceIdle = pm.isDeviceIdleMode,
            lightIdle = if (Build.VERSION.SDK_INT >= 33) pm.isDeviceLightIdleMode else null,
            lowPowerStandby = if (Build.VERSION.SDK_INT >= 33) pm.isLowPowerStandbyEnabled else null,
            powerSave = pm.isPowerSaveMode,
            lowPowerStandbyExempt = if (Build.VERSION.SDK_INT >= 34) runCatching { pm.isExemptFromLowPowerStandby }.getOrNull() else null,
        )
    }

    private fun refresh(trigger: String) {
        val next = runCatching { read() }.getOrNull() ?: return
        val previous = last
        if (!DozePolicy.changed(previous, next)) return
        last = next
        val nextState = DozePolicy.state(next)
        if (nextState == DeviceSleepState.DEEP_IDLE || nextState == DeviceSleepState.LOW_POWER_STANDBY) slept = true
        val line = DozePolicy.line(next, trigger.substringAfterLast('.'))
        if (BuildConfig.DEBUG) Log.i(TAG, "BENCHMARK $line") else Log.w(TAG, line)
        val previousState = previous?.let(DozePolicy::state)
        if (previousState != nextState) {
            mainHandler.post { listeners.forEach { listener -> listener(nextState) } }
        }
    }
}
