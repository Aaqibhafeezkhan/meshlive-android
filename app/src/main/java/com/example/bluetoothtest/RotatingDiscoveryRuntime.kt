package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Android side of the experimental rotating discovery IDs (RotatingDiscovery.kt). Holds this
 * identity's presence key (32 random bytes in no-backup storage; Phase 1 never sends it), the RV4
 * epoch clock, the per-role opening pins and the stranger tarpit, and tells registered advertisers
 * when an epoch boundary passed so they restart on a new set (break-before-make, RV6/RV12).
 *
 * The boundary check runs on the main-thread handler at the UTC boundary and at least every
 * [MAX_CHECK_MS]; when Doze delays it the stale ID stays on air a little longer, which costs privacy
 * but never breaks a connection (design 4.4, RV16: no exact alarm, so no SCHEDULE_EXACT_ALARM).
 */
object RotatingDiscovery {
    private const val PREFS = "rotating_discovery"
    private const val KEY_FILE = "rotating_discovery_presence_key"
    private const val MAX_CHECK_MS = 60_000L
    private const val TAG = "BluetoothVideo"

    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val listeners = CopyOnWriteArraySet<(Long) -> Unit>()
    private val pins = HashMap<Int, OpeningPins>()
    val tarpit = StrangerTarpit()

    private var appContext: Context? = null
    private var nodeId: ByteArray? = null
    private var clock: DiscoveryEpochClock? = null
    private var presenceKey: ByteArray? = null
    @Volatile private var checkedEpoch = -1L

    @Synchronized
    fun init(context: Context, identity: NodeId) {
        val idBytes = identity.toByteArray()
        if (appContext != null && nodeId?.contentEquals(idBytes) == true) return
        val app = context.applicationContext
        appContext = app
        nodeId = idBytes
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bootId = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT).toString()
        }.getOrDefault("unknown")
        val activeClock = DiscoveryEpochClock(loadState(prefs), bootId) { saveState(prefs, it) }
        clock = activeClock
        presenceKey = loadOrCreateKey(File(app.noBackupFilesDir, KEY_FILE), activeClock.state.generation)
        pins.clear()
    }

    /** Emergency wipe: the wipe deletes the files and preferences; this drops the in-memory copies. */
    @Synchronized
    fun clearForWipe() {
        presenceKey?.fill(0)
        presenceKey = null
        clock = null
        nodeId = null
        appContext = null
        pins.clear()
        checkedEpoch = -1L
        visibleUntilEpoch = -1L
        visibleNotified = false
        listeners.clear()
    }

    /** The epoch to advertise now (RV4); regenerates the key on a forward-jump recovery. */
    @Synchronized
    fun currentEpoch(): Long {
        val activeClock = checkNotNull(clock) { "Rotating discovery is not initialised" }
        val reading = activeClock.read(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (reading.newGeneration) {
            val app = checkNotNull(appContext)
            presenceKey?.fill(0)
            presenceKey = newKey(File(app.noBackupFilesDir, KEY_FILE), activeClock.state.generation)
            pins.clear()
            Log.i(TAG, debugPrefix() + "rotid_clock: event=forward_jump_recovery | generation=${activeClock.state.generation}")
        }
        return reading.epoch
    }

    /**
     * The ID this phone advertises now for [role], recorded as the pinned opening for that role.
     * Release builds never log the ID next to the prefix (design section 6).
     */
    @Synchronized
    fun advertise(role: Int): OpeningEntry {
        val epoch = currentEpoch()
        val entry = RotatingDiscoveryIds.ownEntry(checkNotNull(presenceKey), checkNotNull(nodeId), epoch, role)
        pins.getOrPut(role) { OpeningPins() }.onAdvertised(entry, SystemClock.elapsedRealtime())
        return entry
    }

    /** The role's advertising stopped; its ID stays openable for 60 s only (OpeningPins). */
    @Synchronized
    fun offAir(role: Int) {
        pins[role]?.offAir(SystemClock.elapsedRealtime())
    }

    /** The openings a responder sends for [role]: the ID on air now, plus the previous one for 60 s. */
    @Synchronized
    fun pinned(role: Int): List<OpeningEntry> = pins[role]?.pinned(SystemClock.elapsedRealtime()).orEmpty()

    /** [listener] gets each new epoch; it compares with the epoch it advertises and restarts if older. */
    fun addBoundaryListener(listener: (Long) -> Unit) {
        val epoch = runCatching { currentEpoch() }.getOrNull()
        synchronized(this) { if (epoch != null && checkedEpoch < 0) checkedEpoch = epoch }
        listeners.add(listener)
        handler.post(::schedule)
    }

    fun removeBoundaryListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        if (listeners.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val epochMs = RotatingDiscoveryIds.EPOCH_SECONDS * 1000L
        val toBoundary = epochMs - Math.floorMod(nowMs, epochMs) + 50L
        handler.postDelayed(check, minOf(toBoundary, MAX_CHECK_MS))
    }

    private val check = Runnable {
        val epoch = runCatching { currentEpoch() }.getOrNull()
        // A window that ended between boundaries (elapsed bound) restarts the advertisers at once.
        val windowEnded = visibleNotified && !visibleByHandle()
        if (windowEnded) {
            visibleNotified = false
            Log.i(TAG, debugPrefix() + "rotid_visible_window: event=end")
            if (epoch != null && epoch == checkedEpoch) listeners.forEach { listener -> runCatching { listener(epoch) } }
        }
        if (epoch != null && epoch != checkedEpoch) {
            val previous = checkedEpoch
            checkedEpoch = epoch
            if (previous >= 0) {
                if (BuildConfig.DEBUG) {
                    val offsetMs = Math.floorMod(System.currentTimeMillis(), RotatingDiscoveryIds.EPOCH_SECONDS * 1000L)
                    Log.i(TAG, "BENCHMARK rotid_boundary: epoch=$epoch | previous=$previous | wallOffsetMs=$offsetMs | listeners=${listeners.size}")
                }
                listeners.forEach { listener -> runCatching { listener(epoch) } }
            }
        }
        schedule()
    }

    /** End epoch of the current Visible-by-handle window, or -1 (in memory only: a restart ends it). */
    @Volatile private var visibleUntilEpoch = -1L
    @Volatile private var visibleStartedElapsedMs = 0L
    @Volatile private var visibleNotified = false

    /**
     * True while a Visible-by-handle window runs (call offers then use version 2). Once over it is
     * latched off, and it never outlasts [VisibleByHandleWindow.MAX_MS] of elapsed time, so a wall
     * clock moved back cannot revive or stretch it.
     */
    @Synchronized
    fun visibleByHandle(): Boolean {
        val end = visibleUntilEpoch
        if (end < 0) return false
        val active = VisibleByHandleWindow.isActive(end, Math.floorDiv(System.currentTimeMillis(), 1000L)) &&
            SystemClock.elapsedRealtime() - visibleStartedElapsedMs < VisibleByHandleWindow.MAX_MS
        if (!active) visibleUntilEpoch = -1L
        return active
    }

    /**
     * Starts (or extends) a window and tells the advertisers at once, so it begins on a new set. The
     * boundary check that ends it restarts them again with version 5 on a new set (RV5). Returns the
     * wall time (ms) at which it ends.
     */
    fun startVisibleByHandle(): Long {
        val nowSeconds = Math.floorDiv(System.currentTimeMillis(), 1000L)
        synchronized(this) {
            visibleUntilEpoch = VisibleByHandleWindow.endEpoch(nowSeconds)
            visibleStartedElapsedMs = SystemClock.elapsedRealtime()
            visibleNotified = true
        }
        val epoch = runCatching { currentEpoch() }.getOrDefault(-1L)
        Log.i(TAG, debugPrefix() + "rotid_visible_window: event=start | endEpoch=$visibleUntilEpoch")
        handler.post { listeners.forEach { listener -> runCatching { listener(epoch) } } }
        return visibleUntilEpoch * RotatingDiscoveryIds.EPOCH_SECONDS * 1000L
    }

    /** Random silence between stopping the old set and starting the new one (RV12: 1-8 s). */
    fun boundarySilenceMs(): Long = 1_000L + random.nextInt(7_001)

    private fun debugPrefix(): String = if (BuildConfig.DEBUG) "BENCHMARK " else ""

    private fun loadState(prefs: SharedPreferences) = EpochClockState(
        lastEpoch = prefs.getLong("lastEpoch", -1L),
        tickWallEpoch = prefs.getLong("tickWallEpoch", -1L),
        tickElapsedMs = prefs.getLong("tickElapsedMs", 0L),
        bootId = prefs.getString("bootId", "") ?: "",
        generation = prefs.getLong("generation", 1L),
    )

    private fun saveState(prefs: SharedPreferences, state: EpochClockState) {
        prefs.edit()
            .putLong("lastEpoch", state.lastEpoch)
            .putLong("tickWallEpoch", state.tickWallEpoch)
            .putLong("tickElapsedMs", state.tickElapsedMs)
            .putString("bootId", state.bootId)
            .putLong("generation", state.generation)
            .apply()
    }

    /** File = generation (8, big-endian) | key (32). A missing, short or older file gets a new key. */
    private fun loadOrCreateKey(file: File, generation: Long): ByteArray {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes != null && bytes.size == 8 + RotatingDiscoveryIds.PRESENCE_KEY_BYTES) {
            val stored = java.nio.ByteBuffer.wrap(bytes, 0, 8).long
            if (stored == generation) return bytes.copyOfRange(8, bytes.size)
        }
        return newKey(file, generation)
    }

    private fun newKey(file: File, generation: Long): ByteArray {
        val key = ByteArray(RotatingDiscoveryIds.PRESENCE_KEY_BYTES).also(random::nextBytes)
        val out = java.nio.ByteBuffer.allocate(8 + key.size).putLong(generation).put(key).array()
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(out)
            if (!temp.renameTo(file)) {
                file.writeBytes(out)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Rotating discovery: presence key not saved (${it.javaClass.simpleName})") }
        return key
    }
}

/** Link opening exchange (hop type 13) right after the link handshake, before any other record. */
object LinkOpeningExchange {
    fun negotiated(local: NodeCapabilities, peer: NodeCapabilities): Boolean =
        local.transportMask and RotatingDiscoveryIds.LINK_HELLO_BIT != 0 &&
            peer.transportMask and RotatingDiscoveryIds.LINK_HELLO_BIT != 0

    /** Writes this side's `BDO1`, then reads the peer's. Null when either hello lacks bit 3. */
    fun exchange(link: SecureBluetoothSession, local: NodeCapabilities, entries: List<OpeningEntry>): List<OpeningEntry>? {
        if (!negotiated(local, link.peerCapabilities)) return null
        link.writer.write(SecureRecordType.DISCOVERY_OPENING, DiscoveryOpeningCodec.encode(entries))
        link.writer.flush()
        link.reader.acceptDiscoveryOpening = true
        val record = try {
            link.reader.read()
        } finally {
            link.reader.acceptDiscoveryOpening = false
        }
        require(record.type == SecureRecordType.DISCOVERY_OPENING) { "Expected the discovery opening" }
        return DiscoveryOpeningCodec.decode(record.payload)
    }
}

/** RV1: neutral adapter name while the experiment is on; the user's name comes back when it is off. */
object NeutralBluetoothNameController {
    private const val PREFS = "rotating_discovery_name"
    private const val KEY_ORIGINAL = "originalName"

    @SuppressLint("MissingPermission")
    fun apply(context: Context, adapter: BluetoothAdapter?, enabled: Boolean) {
        adapter ?: return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { adapter.name }.getOrNull()
        val saved = prefs.getString(KEY_ORIGINAL, null)
        val action = if (enabled) {
            NeutralBluetoothName.onEnabled(current, saved)
        } else {
            NeutralBluetoothName.onDisabled(current, saved)
        }
        action.saveOriginal?.let { prefs.edit().putString(KEY_ORIGINAL, it).apply() }
        val changed = action.setName?.let { name -> runCatching { adapter.setName(name) }.getOrDefault(false) } ?: false
        if (action.clearSaved && (action.setName == null || changed)) prefs.edit().remove(KEY_ORIGINAL).apply()
        if (action.setName != null) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "rotid_name: enabled=$enabled | neutral=${action.setName == NeutralBluetoothName.NEUTRAL} | changed=$changed",
            )
        }
    }
}
