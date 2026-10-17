package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import kotlin.random.Random

/**
 * Bluetooth stack epoch (batch S-08). Everything native dies with the adapter or with a Bluetooth
 * process crash: scanners, advertising sets, GATT servers and their handles, CoC PSMs, connections. The
 * Java wrappers stay alive and their callbacks go silent (BACKLOG B Part K). The epoch counts
 * STATE_ON transitions after an off episode; consumers register `onOff` / `onOn` and rebuild their own
 * state, in registration order on, reverse order off, after a delay with back-off and jitter so a
 * flapping adapter is not hammered.
 *
 * The hidden BLE_ON states (14, 15, 16) are tolerated and ignored: BR/EDR is down but the LE stack is
 * still up, so nothing was lost. Spurious repeats of a state change nothing.
 */
internal class BluetoothEpochCore(
    private val timer: ScanTimer,
    private val random: Random = Random.Default,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = {},
) {
    private class Consumer(val onOff: () -> Unit, val onOn: () -> Unit)

    private val consumers = LinkedHashMap<String, Consumer>()
    private var state = STATE_UNKNOWN
    private var down = false

    /** The consumers were told "off" and have not been told "on" since: a second off is not delivered. */
    private var consumersDown = false
    private var cancelOn: (() -> Unit)? = null
    private val rebuilds = ArrayDeque<Long>()

    /** Incremented once per completed off -> on episode. */
    @Volatile var epoch: Int = 0
        private set

    /**
     * Off episodes inside the last minute. The system restarts a crashed Bluetooth process after 400 ms x (n + 1) and gives
     * up after 6 consecutive restarts, leaving Bluetooth off (BluetoothManagerService.MAX_ERROR_RESTART_RETRIES, RESTART-01),
     * so three quick off -> on flaps is a crash loop worth telling the user about; B2 reads [crashLoopSuspected].
     */
    @Volatile var recentOffEpisodes: Int = 0
        private set

    val crashLoopSuspected: Boolean get() = recentOffEpisodes >= CRASH_LOOP_EPISODES

    private val offTimes = ArrayDeque<Long>()

    /** Elapsed time of the last off / on edge; used to correlate a link wipe-out with a state change. */
    @Volatile var lastStateChangeMs: Long = -1L
        private set

    /**
     * True from the off edge until the delayed on callbacks have run. Anything an owner starts in this window runs
     * against a stack that is about to be (or was just) rebuilt, so [BluetoothOnRecovery] treats a failure inside it
     * as "wait for Bluetooth" and the on callback restarts the component.
     */
    val recoveryPending: Boolean
        @Synchronized get() = down || cancelOn != null

    @Synchronized
    fun register(name: String, onOff: () -> Unit, onOn: () -> Unit) {
        consumers[name] = Consumer(onOff, onOn)
    }

    @Synchronized
    fun unregister(name: String) {
        consumers.remove(name)
    }

    /** The state at attach time: no callbacks, only the baseline. */
    @Synchronized
    fun seed(initialState: Int) {
        state = initialState
        down = initialState == STATE_OFF || initialState == STATE_TURNING_ON || initialState == STATE_TURNING_OFF
    }

    fun onState(newState: Int) {
        val offCallbacks: List<Consumer> = synchronized(this) { transition(newState) } ?: return
        log("bluetooth_epoch: event=off | state=$newState | epoch=$epoch | consumers=${offCallbacks.size}")
        for (consumer in offCallbacks) runCatching(consumer.onOff)
    }


    /** Applies a state change under the lock; the off callbacks to run, or null when there is nothing to run. */
    private fun transition(newState: Int): List<Consumer>? {
        when (newState) {
            STATE_BLE_TURNING_ON, STATE_BLE_ON, STATE_BLE_TURNING_OFF -> return null
            STATE_OFF, STATE_TURNING_OFF, STATE_TURNING_ON -> {
                if (newState == state) return null
                state = newState
                lastStateChangeMs = clock()
                cancelOn?.invoke()
                cancelOn = null
                val first = !down
                down = true
                if (!first || consumersDown) return null
                consumersDown = true
                noteOffEpisode()
                return consumers.values.toList().asReversed()
            }
            STATE_ON -> {
                if (newState == state) return null
                val wasDown = down
                state = newState
                lastStateChangeMs = clock()
                if (!wasDown) return null
                down = false
                epoch++
                scheduleOn(epoch)
                return null
            }
            else -> return null
        }
    }

    private fun noteOffEpisode() {
        val now = clock()
        offTimes.addLast(now)
        while (offTimes.isNotEmpty() && now - offTimes.first() > 60_000L) offTimes.removeFirst()
        recentOffEpisodes = offTimes.size
        if (recentOffEpisodes >= CRASH_LOOP_EPISODES) {
            log("bluetooth_epoch: event=crash_loop_suspected | offEpisodesLastMinute=$recentOffEpisodes | epoch=$epoch")
        }
    }

    /** Delay before the rebuild: 500 ms, doubling per rebuild inside the last minute (cap 8 s), plus jitter. */
    internal fun rebuildDelayMs(rebuildsInLastMinute: Int, jitterMs: Long): Long =
        (BASE_DELAY_MS shl rebuildsInLastMinute.coerceIn(0, 5)).coerceAtMost(MAX_DELAY_MS) + jitterMs

    private fun scheduleOn(forEpoch: Int) {
        val now = clock()
        while (rebuilds.isNotEmpty() && now - rebuilds.first() > 60_000L) rebuilds.removeFirst()
        val delay = rebuildDelayMs(rebuilds.size, random.nextLong(0L, JITTER_MS + 1))
        rebuilds.addLast(now)
        cancelOn = timer.schedule(delay) {
            val callbacks = synchronized(this) {
                if (epoch != forEpoch || state != STATE_ON) return@schedule
                cancelOn = null
                consumersDown = false
                consumers.values.toList()
            }
            log("bluetooth_epoch: event=on | epoch=$forEpoch | delayMs=$delay | consumers=${callbacks.size}")
            for (consumer in callbacks) runCatching(consumer.onOn)
        }
    }

    companion object {
        const val STATE_UNKNOWN = -1
        const val STATE_OFF = BluetoothAdapter.STATE_OFF
        const val STATE_TURNING_ON = BluetoothAdapter.STATE_TURNING_ON
        const val STATE_ON = BluetoothAdapter.STATE_ON
        const val STATE_TURNING_OFF = BluetoothAdapter.STATE_TURNING_OFF

        /** BluetoothAdapter.STATE_BLE_TURNING_ON / STATE_BLE_ON / STATE_BLE_TURNING_OFF (hidden constants). */
        const val STATE_BLE_TURNING_ON = 14
        const val STATE_BLE_ON = 15
        const val STATE_BLE_TURNING_OFF = 16

        const val CRASH_LOOP_EPISODES = 3
        const val BASE_DELAY_MS = 500L
        const val MAX_DELAY_MS = 8_000L
        const val JITTER_MS = 250L
    }
}

/**
 * Detects a Bluetooth controller or stack reset from link behaviour (BACKLOG C-16 / C-23): every live
 * link drops inside [spreadLimitMs] and an adapter state change was seen around it. The result is a
 * correlation field for the benchmark log, never a decision.
 */
internal class ControllerResetDetector(
    private val spreadLimitMs: Long = 200L,
    private val stateWindowMs: Long = 2_000L,
) {
    data class Verdict(val suspected: Boolean, val linksBefore: Int, val dropped: Int, val spreadMs: Long, val stateChangeSeen: Boolean)

    private var linksBefore = 0
    private var firstDropMs = -1L
    private var lastDropMs = -1L
    private var dropped = 0

    /** A link dropped; [liveLinksBefore] counts the links alive immediately before this one dropped. */
    fun onLinkDropped(nowMs: Long, liveLinksBefore: Int) {
        if (firstDropMs < 0L || nowMs - lastDropMs > spreadLimitMs) {
            firstDropMs = nowMs
            linksBefore = liveLinksBefore
            dropped = 0
        }
        lastDropMs = nowMs
        dropped++
    }

    fun verdict(lastStateChangeMs: Long): Verdict {
        val spread = if (firstDropMs < 0L) 0L else lastDropMs - firstDropMs
        val all = firstDropMs >= 0L && linksBefore >= 2 && dropped >= linksBefore && spread <= spreadLimitMs
        val state = lastStateChangeMs >= 0L && firstDropMs >= 0L &&
            lastStateChangeMs in (firstDropMs - stateWindowMs)..(lastDropMs + stateWindowMs)
        return Verdict(all && state, linksBefore, dropped, spread, state)
    }

    fun reset() {
        linksBefore = 0
        firstDropMs = -1L
        lastDropMs = -1L
        dropped = 0
    }
}

/** Process-wide epoch with the Android receiver. */
internal object BluetoothEpoch {
    val core = BluetoothEpochCore(HandlerScanTimer(), log = ::scanLog)
    val resetDetector = ControllerResetDetector()

    @Volatile private var receiver: BroadcastReceiver? = null

    val epoch: Int get() = core.epoch

    fun register(name: String, onOff: () -> Unit, onOn: () -> Unit) = core.register(name, onOff, onOn)

    fun unregister(name: String) = core.unregister(name)

    /**
     * A link just dropped; [liveLinksBefore] counts the links alive before it. The GATT/CoC owners call this
     * (root hook). When the burst of drops finishes, one `controller_reset` line says whether it looks like a
     * controller reset.
     */
    fun noteLinkDropped(liveLinksBefore: Int) {
        resetDetector.onLinkDropped(SystemClock.elapsedRealtime(), liveLinksBefore)
    }

    fun logResetVerdict() {
        val v = resetDetector.verdict(core.lastStateChangeMs)
        scanLog(
            "controller_reset: controller_reset_suspected=${v.suspected} | linksBefore=${v.linksBefore} | " +
                "dropped=${v.dropped} | spreadMs=${v.spreadMs} | stateChangeSeen=${v.stateChangeSeen} | epoch=${core.epoch}",
        )
        resetDetector.reset()
    }

    /** Registers the ACTION_STATE_CHANGED receiver once, seeding the baseline from the adapter. */
    fun attach(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext ?: context
        val adapterState = runCatching {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.state
        }.getOrNull() ?: BluetoothEpochCore.STATE_UNKNOWN
        core.seed(adapterState)
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                core.onState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothEpochCore.STATE_UNKNOWN))
            }
        }
        receiver = created
        runCatching { app.registerReceiver(created, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
    }
}
