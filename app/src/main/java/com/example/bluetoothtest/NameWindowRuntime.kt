package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Visible by name at run time (Design G, G5; `visible_by_name`, off). The pure rules are in VisibleByName.kt; this file is the
 * process-wide window ([NameWindowRuntime]), the two extra advertising sets ([NameWindowAdvertiser]) and the notification with
 * its Stop action ([NameWindowNotification], [NameWindowStopReceiver]).
 *
 * While the window is open the ordinary call-ready advert (role 0) is not touched. The endpoint adds a connectable window offer
 * (version 8, the role 3 ID, the name hash in the token field) beside it and a non-connectable set that rotates the name pages.
 * Both are served by the endpoint's one listener (the GATT server or the CoC control server is the same object whatever
 * advertising set a central connects through), the opening entries of a connection hold the current role 0 and role 3 IDs, and
 * both sets end with the window, with the endpoint's availability, or when this phone cannot start a free set (then the user
 * is told "This phone cannot show a name to others" and nothing else changes).
 */
/** The one fact [CallBlocking] needs from an open window, without any Android dependency. */
internal object NameWindowGate {
    @Volatile var allowAnyone = false
}

internal object NameWindowRuntime {
    private val window = VisibleNameWindow({ SystemClock.elapsedRealtime() }, { System.currentTimeMillis() })
    private val windows = VisibleWindows(window, startHandle = { }, stopHandle = { RotatingDiscovery.stopVisibleByHandle() })
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** "Allow calls from anyone for these ten minutes" (asked when Block unknown callers is on); reverts with the window. */
    @Volatile var allowAnyone = false
        private set(value) {
            field = value
            NameWindowGate.allowAnyone = value
        }

    @Volatile private var wasActive = false

    /** The last start could not show a name (no free advertising set): the screen says so once. */
    @Volatile var cannotShow = false
        private set

    /** The endpoint could not start the sets: nothing is shown, and the window ends (the screen reads [cannotShow]). */
    fun reportCannotShow() {
        cannotShow = true
        handler.post { stop(null, "no_free_set") }
    }

    /** The feature is on and the phone uses rotating IDs (the window ID is one). */
    fun available(): Boolean = NamingFeatures.visibleByNameOn() && RotatingDiscoveryFeature.enabled

    fun active(): Boolean = window.active().also { now -> if (wasActive && !now) windowEnded() }

    fun name(): String? = window.name()

    fun nameHash(): ByteArray? = window.hash()

    fun endsAtWallMs(): Long = window.endsAtWallMs

    /** Whether a call from an unknown caller may ring now although "Block unknown callers" is on. */
    fun allowAnyoneNow(): Boolean = allowAnyone && active()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Starts the window for [rawName]; null when the feature is unavailable. Ends a Visible-by-handle window first. */
    fun start(context: Context, rawName: String, callName: String?, confirmedSameAsCall: Boolean, allowAnyoneCalls: Boolean): VisibleNameWindow.Result? {
        if (!available()) return null
        cannotShow = false
        val result = windows.startName(rawName, callName, confirmedSameAsCall)
        if (result == VisibleNameWindow.Result.STARTED) {
            wasActive = true
            allowAnyone = allowAnyoneCalls
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, VisibleNameWindow.DURATION_MS + 500L)
            NameWindowNotification.post(context.applicationContext, window.name().orEmpty(), window.endsAtWallMs)
            log("event=start | tokenSet=true")
            notifyListeners()
        }
        return result
    }

    fun stop(context: Context?, reason: String = "stop") {
        windows.stopAll()
        if (wasActive) windowEnded(reason, context)
    }

    /** Visible-by-handle started: the two never run together. */
    fun onHandleWindowStart() {
        windows.startId()
        if (wasActive) windowEnded("replaced", null)
    }

    private val expire = Runnable { if (!window.active() && wasActive) windowEnded() }

    private fun windowEnded(reason: String = "end", context: Context? = null) {
        wasActive = false
        allowAnyone = false
        handler.removeCallbacks(expire)
        NameWindowNotification.cancel(context ?: RotatingDiscovery.appContextOrNull())
        log("event=$reason | tokenSet=false")
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching { listener() } }
    }

    private fun log(detail: String) {
        // Never the name.
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_visible_window: mode=name | $detail")
    }
}

/**
 * The two extra sets of an open window, owned by the call endpoint. [start] blocks (call it off the main thread) and returns
 * false when the window cannot be shown; [close] stops both. At each epoch boundary inside the window both are rebuilt on the
 * new role 3 ID after the usual 1-8 s silence (new address, no continuity with the old set).
 */
internal class NameWindowAdvertiser(
    private val adapter: BluetoothAdapter,
    /** The window offer for ([rdi], [hash]): the endpoint's own offer fields on version 8. */
    private val offerFor: (ByteArray, ByteArray) -> DiscoveryAnnouncement,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val restarting = AtomicBoolean(false)
    @Volatile private var offerSet: StableAdvertisingSet? = null
    @Volatile private var pages: TitleAdvertiser? = null
    @Volatile private var name: String = ""

    private val boundaryListener: (Long) -> Unit = { _ -> restartOnNewIds() }

    /** False: no free advertising set, a start failure, or no name: the window shows nothing. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val shown = NameWindowRuntime.name() ?: return false
        name = shown
        if (!startSets()) {
            closeSets()
            return false
        }
        RotatingDiscovery.addBoundaryListener(boundaryListener)
        return true
    }

    private fun startSets(): Boolean {
        val free = AdvertisingSetProbe.lastFree ?: runCatching { AdvertisingSetProbe.run(adapter) }.getOrDefault(0)
        if (free < MIN_FREE_SETS) {
            Log.i("BluetoothVideo", "BENCHMARK rotid_visible_window: mode=name | event=skipped | probeFreeSets=$free need=$MIN_FREE_SETS")
            return false
        }
        val entry = runCatching { RotatingDiscovery.advertise(RotatingDiscoveryIds.ROLE_WINDOW_OFFER) }.getOrNull() ?: return false
        val hash = NamePageCodec.nameHash(name)
        val offer = runCatching { offerFor(entry.rdi, hash) }.getOrNull() ?: return false
        val pageList = NamePageCodec.pages(entry.rdi, name) ?: return false
        val set = StableAdvertisingSet(
            adapter,
            connectable = true,
            data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BleL2capControlProtocol.SERVICE_UUID)).build(),
            scanResponse = AdvertiseData.Builder()
                .addManufacturerData(BleL2capControlProtocol.MESH_MANUFACTURER_ID, DiscoveryAnnouncementCodec.encode(offer))
                .build(),
            label = "Window offer",
            onStatus = onStatus,
            onLost = { reason -> Log.w("BluetoothVideo", "Window offer set lost: $reason") },
            nonDiscoverable = NonDiscoverableAdvertising.forNewSet(connectable = true),
        )
        val status = runCatching { set.start(lowLatency = false, START_TIMEOUT_SECONDS) }.getOrNull()
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            if (status != null) runCatching { set.stop() }
            return false
        }
        offerSet = set
        val titleSet = TitleAdvertiser(adapter, pageList, selfRotate = false)
        if (titleSet.start() != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            titleSet.close()
            return false
        }
        pages = titleSet
        return true
    }

    private fun closeSets() {
        pages?.close()
        pages = null
        offerSet?.let { runCatching { it.stop() } }
        offerSet = null
        RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_WINDOW_OFFER)
    }

    private fun restartOnNewIds() {
        if (closed.get() || !restarting.compareAndSet(false, true)) return
        Thread {
            try {
                closeSets()
                runCatching { Thread.sleep(RotatingDiscovery.boundarySilenceMs()) }
                if (!closed.get() && NameWindowRuntime.active()) {
                    val ok = startSets()
                    if (closed.get()) closeSets()
                    if (!ok) closeSets()
                }
            } finally {
                restarting.set(false)
            }
        }.apply { this.name = "NameWindowIdRotation" }.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        RotatingDiscovery.removeBoundaryListener(boundaryListener)
        closeSets()
    }

    companion object {
        /** The offer set, the page set and one spare for the ordinary adverts' own restart. */
        const val MIN_FREE_SETS = 3
        const val START_TIMEOUT_SECONDS = 5L
    }
}

/** The ongoing notification of an open window: a countdown and "Stop now". Never shows more than the name the user chose. */
internal object NameWindowNotification {
    const val CHANNEL_ID = "visible_by_name"
    private const val NOTIFICATION_ID = 1004
    private const val REQUEST_CODE = 4
    const val ACTION_STOP = "io.github.meshlive.app.action.STOP_VISIBLE_BY_NAME"

    fun post(context: Context, name: String, endsAtWallMs: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.visible_name_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val stop = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, NameWindowStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(context.getString(R.string.visible_name_notification_title))
            .setContentText(context.getString(R.string.visible_name_notification_text, name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(endsAtWallMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(0, context.getString(R.string.visible_name_stop), stop)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context?) {
        context ?: return
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}

/** "Stop now" in the notification. Not exported; the window itself is in memory, so a dead process has nothing to stop. */
class NameWindowStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NameWindowNotification.ACTION_STOP) NameWindowRuntime.stop(context.applicationContext, "stop")
    }
}
