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
 * identity's presence vault (generation, key `P` and the contacts' leases, sealed in no-backup
 * storage; Phase 2), the RV4 epoch clock, the per-role opening pins, the recognition table and the
 * stranger tarpit, and tells registered advertisers when an epoch boundary passed so they restart on
 * a new set (break-before-make, RV6/RV12).
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

    // Lazy: the off paths call offAir and removeBoundaryListener, and touching this object must not need a Looper.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val random = SecureRandom()
    private val listeners = CopyOnWriteArraySet<(Long) -> Unit>()
    private val pins = HashMap<Int, OpeningPins>()
    val tarpit = StrangerTarpit()

    private var appContext: Context? = null
    private var nodeId: ByteArray? = null
    private var clock: DiscoveryEpochClock? = null
    private var vault: PresenceVault? = null
    @Volatile private var checkedEpoch = -1L
    @Volatile private var windowLogged = false

    /** Phase 2 recognition table: rebuilt at each tick and whenever leases or contacts change. */
    val recognition = RecognitionTable()
    private val tableThread = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RotatingDiscoveryTable").apply { isDaemon = true }
    }

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
        val legacyFile = File(app.noBackupFilesDir, KEY_FILE)
        vault = PresenceVault(
            store = PresenceStore(
                File(File(app.noBackupFilesDir, IdentityVerification.DIRECTORY), PRESENCE_FILE),
                IdentityVerification.atRestCipher(),
            ),
            floor = object : GenerationFloor {
                override fun get(): Long = activeClock.state.generation
                override fun raiseTo(value: Long) = activeClock.raiseGeneration(value)
            },
            legacyKey = { readLegacyKey(legacyFile) },
            onLegacyImported = { runCatching { legacyFile.delete() } },
            // New windows start at the epoch this phone advertises (the monotonic E, or the wall epoch when it is ahead).
            epochSource = {
                maxOf(activeClock.state.lastEpoch, RotatingDiscoveryIds.wallEpoch(Math.floorDiv(DiscoveryWallClock.nowMs(), 1000L)))
            },
        )
        runCatching { vault?.ensureLoaded() }
        pins.clear()
        requestRecognitionRebuild()
        // A contact forgotten while the experiment was off is no longer a lease holder (PresenceVault.reconcile);
        // off the caller's thread, because the verified-contact store and the vault use the Keystore.
        val activeVault = vault
        tableThread.execute {
            runCatching {
                val verification = IdentityVerification.get(app)
                if (verification.storeUnreadableCause() == null) {
                    val verified = verification.verifiedContacts()
                    // An empty list from a store whose file exists is a failed read, not "everyone was forgotten".
                    val consistent = verified.isNotEmpty() || PeerIdentityGate.store?.hasAny() != true
                    if (consistent) activeVault?.reconcile(verified.map { it.fingerprint.nodeId }.toSet())
                }
            }
            // Security review 2026-10-27, item 8: a contact blocked while rotating IDs were off (the vault did not exist)
            // loses the lease it holds now, as a block made while they were on does.
            // Kept pending until both the vault and the block list can be read (reviewer review finding 5).
            runCatching {
                activeVault?.requestBlockedReconcile {
                    if (BlockedPeople.isUnreadable(app)) null else BlockedPeople.list(app).map { it.fingerprint.nodeId }.toSet()
                }
            }
            requestRecognitionRebuild()
        }
    }

    /** True once [init] ran in this process; the DEBUG epoch length can no longer change (DiscoveryEpochLength). */
    @Synchronized
    fun isStarted(): Boolean = clock != null

    /** The presence vault (null before [init]); Phase 2 session and UI code read and change it through this. */
    @Synchronized
    fun presenceVault(): PresenceVault? = vault

    /** Emergency wipe: the wipe deletes the files and preferences; this drops the in-memory copies. */
    @Synchronized
    fun clearForWipe() {
        vault?.wipe()
        vault = null
        recognition.rebuild(emptyList(), 0L, 0L)
        ownIdsCache = null
        clock = null
        nodeId = null
        appContext = null
        pins.clear()
        checkedEpoch = -1L
        windowLogged = false
        visibleUntilEpoch = -1L
        visibleNotified = false
        listeners.clear()
    }

    /** The application context given to [init], or null (the notification of an ended window needs one). */
    @Synchronized
    fun appContextOrNull(): Context? = appContext

    /**
     * Ends a Visible-by-handle window now (Visible by name starts: the two never run together) and tells the advertisers,
     * which restart on a new set with version 5.
     */
    fun stopVisibleByHandle() {
        synchronized(this) { visibleUntilEpoch = -1L }
        val epoch = runCatching { currentEpoch() }.getOrDefault(-1L)
        handler.post { listeners.forEach { listener -> runCatching { listener(epoch) } } }
    }

    /** The epoch to advertise now (RV4); regenerates the key on a forward-jump recovery. */
    @Synchronized
    fun currentEpoch(): Long {
        val activeClock = checkNotNull(clock) { "Rotating discovery is not initialised" }
        val reading = activeClock.read(DiscoveryWallClock.nowMs(), SystemClock.elapsedRealtime())
        if (reading.newGeneration) {
            vault?.adoptGeneration(activeClock.state.generation)
            pins.clear()
            Log.i(TAG, debugPrefix() + "rotid_clock: event=forward_jump_recovery | generation=${activeClock.state.generation}")
            requestRecognitionRebuild()
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
        val entry = ownEntryFor(epoch, epoch, role) ?: error("No day key for epoch $epoch")
        pins.getOrPut(role) { OpeningPins() }.onAdvertised(entry, SystemClock.elapsedRealtime())
        return entry
    }

    /** The entry of [idEpoch] under the day key of its day (independent random keys, PROTOCOL "Keys and epochs"). */
    private fun ownEntryFor(idEpoch: Long, currentEpoch: Long, role: Int): OpeningEntry? {
        val key = checkNotNull(vault).dayKey(idEpoch, currentEpoch) ?: return null
        return try {
            RotatingDiscoveryIds.ownEntry(key, checkNotNull(nodeId), idEpoch, role)
        } finally {
            key.fill(0)
        }
    }

    /**
     * This phone's role ID for the current epoch, without touching the opening pins (a reach-page advertiser asks
     * once a second; only a real advertiser may move the pins).
     */
    @Synchronized
    fun currentId(role: Int): ByteArray {
        val epoch = currentEpoch()
        return (ownEntryFor(epoch, epoch, role) ?: error("No day key for epoch $epoch")).rdi
    }

    private var ownIdsCache: Triple<Long, Long, List<ByteArray>>? = null

    /**
     * This phone's role ID of the current and the previous epoch (poison reverse in the reach table compares
     * with both, because a boundary may have passed). Cached per epoch and generation.
     */
    @Synchronized
    fun ownRdis(role: Int): List<ByteArray> {
        val activeVault = vault ?: return emptyList()
        val node = nodeId ?: return emptyList()
        val epoch = runCatching { currentEpoch() }.getOrNull() ?: return emptyList()
        val generation = activeVault.generation()
        val key = (epoch shl 8) xor role.toLong()
        ownIdsCache?.takeIf { it.first == key && it.second == generation }?.let { return it.third }
        // The previous epoch's key exists only while the window still holds yesterday (first epoch of a day); a
        // phone that just started has no previous ID to compare with.
        val ids = listOf(epoch, epoch - 1).filter { it >= 0 }.mapNotNull { ownEntryFor(it, epoch, role)?.rdi }
        ownIdsCache = Triple(key, generation, ids)
        return ids
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
        val toBoundary = RotatingDiscoveryIds.msUntilBoundary(DiscoveryWallClock.nowMs()) + 50L
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
            requestRecognitionRebuild()
            if (previous >= 0) {
                if (BuildConfig.DEBUG) {
                    val offsetMs = Math.floorMod(DiscoveryWallClock.nowMs(), RotatingDiscoveryIds.epochSeconds * 1000L)
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
        val active = VisibleByHandleWindow.isActive(end, Math.floorDiv(DiscoveryWallClock.nowMs(), 1000L)) &&
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
        NameWindowRuntime.onHandleWindowStart() // a Visible-by-name window ends: never both
        val nowSeconds = Math.floorDiv(DiscoveryWallClock.nowMs(), 1000L)
        synchronized(this) {
            visibleUntilEpoch = VisibleByHandleWindow.endEpoch(nowSeconds)
            visibleStartedElapsedMs = SystemClock.elapsedRealtime()
            visibleNotified = true
        }
        val epoch = runCatching { currentEpoch() }.getOrDefault(-1L)
        Log.i(TAG, debugPrefix() + "rotid_visible_window: event=start | endEpoch=$visibleUntilEpoch")
        handler.post { listeners.forEach { listener -> runCatching { listener(epoch) } } }
        return visibleUntilEpoch * RotatingDiscoveryIds.epochSeconds * 1000L
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
        // Absent in a store written before the DEBUG short epoch existed: that was always the default length.
        epochSeconds = prefs.getLong("epochSeconds", RotatingDiscoveryIds.EPOCH_SECONDS),
    )

    private fun saveState(prefs: SharedPreferences, state: EpochClockState) {
        prefs.edit()
            .putLong("lastEpoch", state.lastEpoch)
            .putLong("tickWallEpoch", state.tickWallEpoch)
            .putLong("tickElapsedMs", state.tickElapsedMs)
            .putString("bootId", state.bootId)
            .putLong("generation", state.generation)
            .putLong("epochSeconds", state.epochSeconds)
            // commit(): a revocation moves the generation counter and must survive a crash (RD1).
            .commit()
    }

    /** The Phase 1 plain key file: generation (8, big-endian) | key (32). Imported into the vault once, then deleted. */
    private fun readLegacyKey(file: File): Pair<Long, ByteArray>? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size != 8 + RotatingDiscoveryIds.PRESENCE_KEY_BYTES) return null
        val generation = java.nio.ByteBuffer.wrap(bytes, 0, 8).long
        return generation to bytes.copyOfRange(8, bytes.size)
    }

    // ----- Phase 2: recognition -----

    /**
     * Rebuilds the recognition table off the calling thread (RV10): contacts that gave this phone leases,
     * roles 0 and 1, the epochs around the learned skew. A no-op until a vault exists.
     */
    fun requestRecognitionRebuild() {
        tableThread.execute {
            runCatching {
                // Once per day this deletes the day key that left the window, creates the next one and prunes
                // contacts' days that are over, off the advertisers' path (PROTOCOL "Keys and epochs").
                val (activeVault, tickEpoch) = synchronized(this) { vault to (clock?.state?.lastEpoch ?: -1L) }
                if (activeVault != null && tickEpoch >= 0) {
                    val changed = activeVault.maintain(tickEpoch)
                    activeVault.windowInfo()?.let { (first, last, count) ->
                        if (changed || !windowLogged) {
                            windowLogged = true
                            Log.i(
                                TAG,
                                debugPrefix() + "rotid_presence: event=window | generation=${activeVault.generation()} | " +
                                    "firstDay=$first | lastDay=$last | count=$count | changed=$changed",
                            )
                        }
                    }
                }
                val (holders, epoch) = synchronized(this) {
                    val current = vault?.takeIf { it.persistent }?.leaseHolders().orEmpty()
                    current to (clock?.state?.lastEpoch ?: -1L)
                }
                recognition.rebuild(holders, epoch, DiscoveryWallClock.nowMs())
                if (BuildConfig.DEBUG && holders.isNotEmpty()) {
                    Log.i(TAG, "BENCHMARK rotid_presence: event=table | contacts=${holders.size} | entries=${recognition.size} | epoch=$epoch")
                }
            }
        }
    }

    /**
     * Contacts whose lease-derived ID equals the announcement (a version 5 call offer matches role 0, a
     * stream offer role 1). A hit is only a hint: the UI shows it unconfirmed and nothing connects from it.
     */
    /** Design OR: the contact's search window (role-0 IDs by epoch) under the recognition policy, or null. */
    internal fun seekWindow(contactPrefixHex: String): RelaySeekSelectors.Window? = recognition.seekWindow(contactPrefixHex)

    fun recognize(announcement: DiscoveryAnnouncement): List<RecognitionTable.Hit> {
        if (!announcement.rotating || announcement.isWindowOffer) return emptyList() // window IDs belong to no lease
        val role = when {
            announcement.isStreamOffer() -> RotatingDiscoveryIds.ROLE_STREAM_OFFER
            announcement.isPrivateCallOffer() -> RotatingDiscoveryIds.ROLE_CALL_OFFER
            else -> return emptyList()
        }
        return recognition.lookup(announcement.nodeIdPrefix).filter { it.role == role }
    }

    private const val PRESENCE_FILE = "presence-leases.bin"
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

/**
 * Applies the explicit option "Use a neutral Bluetooth name ("Phone")" (NeutralBluetoothName has the pure rules;
 * PROTOCOL "Pre-handshake exposure"). Called when the option changes and when the app starts or resumes, never
 * per call, advertising session or epoch. The saved original name lives in this app's preferences (it is lost
 * with the app's data; the adapter name itself survives uninstall), and is shown in Settings whenever it exists.
 */
object NeutralBluetoothNameController {
    private const val PREFS = "rotating_discovery_name"
    private const val KEY_ORIGINAL = "originalName"
    private const val KEY_APPLIED = "applied"

    /** [paused]: the user renamed the phone after the option was applied; the option must be switched off. */
    class Outcome(val changed: Boolean, val restored: Boolean, val paused: Boolean = false)

    /** The name saved when the option was turned on (null: none), for the Settings text. */
    fun savedOriginal(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORIGINAL, null)

    /** The adapter's name now, or null when it cannot be read. */
    @SuppressLint("MissingPermission")
    fun currentName(adapter: BluetoothAdapter?): String? = runCatching { adapter?.name }.getOrNull()

    /**
     * Makes the adapter name match [optionOn]. [everChosen] is false while the preference was never written: a
     * restore then undoes the automatic rename of builds before 2026-10-11 and is logged as a migration.
     */
    @SuppressLint("MissingPermission")
    fun apply(context: Context, adapter: BluetoothAdapter?, optionOn: Boolean, everChosen: Boolean = true): Outcome {
        adapter ?: return Outcome(changed = false, restored = false)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = currentName(adapter)
        val saved = prefs.getString(KEY_ORIGINAL, null)
        val applied = prefs.getBoolean(KEY_APPLIED, false)
        val action = if (optionOn) {
            NeutralBluetoothName.onEnabled(current, saved, applied)
        } else {
            NeutralBluetoothName.onDisabled(current, saved)
        }
        action.saveOriginal?.let { prefs.edit().putString(KEY_ORIGINAL, it).apply() }
        val changed = action.setName?.let { name -> runCatching { adapter.setName(name) }.getOrDefault(false) } ?: false
        if (action.clearSaved && (action.setName == null || changed)) prefs.edit().remove(KEY_ORIGINAL).apply()
        // "Applied" means a write of Phone succeeded, or the adapter already read Phone with the option on.
        if (optionOn && (changed || current == NeutralBluetoothName.NEUTRAL)) prefs.edit().putBoolean(KEY_APPLIED, true).apply()
        if (!optionOn || action.pause) prefs.edit().remove(KEY_APPLIED).apply()
        val restored = !optionOn && changed
        if (action.setName != null || action.pause || (!optionOn && action.clearSaved)) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "rotid_name: option=$optionOn | changed=$changed | paused=${action.pause} | migrated=${restored && !everChosen}",
            )
        }
        return Outcome(changed, restored, action.pause)
    }
}
