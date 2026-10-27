package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Bond-free Classic, shown to users as "Connect without pairing" (user request 2026-10-09 "Classic as
 * good as LE"; promoted to Settings > Features on 2026-10-11, default
 * [ConnectivityFeatureDefaults.CLASSIC_WITHOUT_PAIRING] until phone test T7 passes, design decision Q-P2).
 *
 * Why Classic needed a mutual Android bond:
 * 1. Private calls, the call relay and the stream relay opened Android's *secure* RFCOMM sockets
 *    (createRfcommSocketToServiceRecord / listenUsingRfcommWithServiceRecord). Android documents
 *    those as requiring an authenticated (MITM-protected) link key, i.e. a numeric-comparison bond
 *    on both phones. A stale one-sided bond (S24 kept M31's key, M31 had none) fails there too.
 *    Streams already used insecure sockets.
 * 2. Discovery is BLE. Phones advertise with resolvable private addresses that only a bond's IRK
 *    resolves, and Android hides a phone's own address from apps (BluetoothAdapter.getAddress
 *    returns 02:00:00:00:00:00 since 6.0), so without a bond a phone cannot find the BR/EDR address
 *    RFCOMM must page. This is the "Classic identity boundary" in DECISIONS.
 *
 * With [ClassicFeatureSettings.bondFree] on, every Classic role uses insecure RFCOMM (BMH2/BMS1
 * provide authentication and ordered encryption, exactly as on LE CoC and GATT, which never use
 * Android link security), and [ClassicAddressBook] remembers the address at which an authenticated
 * identity was reached over Classic in either direction. A phone met once is paged directly next
 * time. A first contact without any bond uses a Classic inquiry (ClassicInquiry), which finds only
 * a discoverable phone. Both phones need the switch: a secure listener refuses an insecure caller.
 */
data class ClassicFeatureSettings(
    val bondFree: Boolean = false,
    /**
     * Bounded connect path for every Classic role (shared candidate order, per-attempt deadlines,
     * random-address skip, fast unresolved error). Off: each role connects as before 2026-10-09
     * (legacyClassicCandidatePlan). Bond-free mode needs it and turns it on, so the setting
     * "Connect without pairing" is also the switch for bounded connects; alone it is a benchmark
     * extra only ([EXTRA_BOUNDED_CONNECT]).
     */
    val boundedConnect: Boolean = false,
    /**
     * Ask Android (system dialog) to make this phone discoverable once per process when it becomes
     * available for Classic calls. Only a call or call relay runs an inquiry for an unknown peer:
     * a stream sender must connect within the broadcaster's 12 s, shorter than one inquiry, so
     * streams reach only phones already met.
     */
    val requestVisibility: Boolean = false,
) {
    val usesBoundedConnect: Boolean
        get() = boundedConnect || bondFree

    companion object {
        const val EXTRA_BOND_FREE = "test_classic_bond_free"
        const val EXTRA_BOUNDED_CONNECT = "test_classic_bounded_connect"
        const val EXTRA_REQUEST_VISIBILITY = "test_classic_request_visible"
        const val PREF_BOND_FREE = "experimental_classic_bond_free"

        /**
         * The shared feature rule ([FeatureCatalog.resolve]): a DEBUG benchmark launch uses only its
         * extras (default off) so a stored choice never leaks into a measured case. Every other launch
         * uses the user's explicit choice ([stored] null when never touched) or the shipped default,
         * with no dependence on Manual settings or the build type. Outside benchmark launches the
         * bond-free mode also asks for visibility, because a first contact needs one discoverable phone.
         */
        fun resolve(
            benchmarkLaunch: Boolean,
            extra: (String) -> Boolean,
            stored: (String) -> Boolean?,
        ): ClassicFeatureSettings {
            val bondFree = FeatureCatalog.resolve(ConnectivityFeatures.classicWithoutPairing, benchmarkLaunch, extra, stored)
            return if (benchmarkLaunch) {
                ClassicFeatureSettings(
                    bondFree = bondFree,
                    boundedConnect = extra(EXTRA_BOUNDED_CONNECT),
                    requestVisibility = bondFree && extra(EXTRA_REQUEST_VISIBILITY),
                )
            } else {
                ClassicFeatureSettings(bondFree = bondFree, requestVisibility = bondFree)
            }
        }
    }
}

/** Process-wide effective Classic switches, refreshed by the activity. */
object ClassicFeatures {
    @Volatile var current = ClassicFeatureSettings()

    fun label(settings: ClassicFeatureSettings = current): String =
        "bondFree=${settings.bondFree} | boundedConnect=${settings.usesBoundedConnect} | " +
            "requestVisibility=${settings.requestVisibility}"
}

/** Classic RFCOMM roles; they differ only in which socket security Android applies. */
internal enum class ClassicRole(val label: String, val historicallySecure: Boolean) {
    STREAM_SENDER("stream", false),
    STREAM_RECEIVER("stream-receiver", false),
    STREAM_FORWARDER("forwarder", false),
    PRIVATE_CALL("call", true),
    CALL_RELAY("call-relay", true),
    STREAM_RELAY("relay", true),
}

/**
 * Whether [role] uses Android's secure RFCOMM (authenticated link key: a numeric-comparison bond on
 * both phones). Streams have always used insecure sockets; calls and relays used secure ones. Bond-
 * free Classic makes every role insecure; the app's BMH2 handshake (signed persistent identity,
 * ephemeral ECDH, discovery-bound node prefix) and BMS1/BME1 ordered AES-GCM records are unchanged,
 * and they never depended on Android link security (LE CoC and GATT have none).
 */
internal fun classicRoleUsesSecureRfcomm(role: ClassicRole, bondFree: Boolean): Boolean =
    role.historicallySecure && !bondFree

internal object ClassicSockets {
    @SuppressLint("MissingPermission")
    fun listen(adapter: BluetoothAdapter, serviceName: String, role: ClassicRole): BluetoothServerSocket =
        if (classicRoleUsesSecureRfcomm(role, ClassicFeatures.current.bondFree)) {
            adapter.listenUsingRfcommWithServiceRecord(serviceName, ClassicTransport.APP_SERVICE.serviceUuid)
        } else {
            adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, ClassicTransport.APP_SERVICE.serviceUuid)
        }

    /**
     * [bondFree] is pinned by the connector at the start of a connect, so a settings change in the
     * middle of one (a bond-free connect in progress) can never open a secure socket to the peer.
     */
    @SuppressLint("MissingPermission")
    fun create(device: BluetoothDevice, role: ClassicRole, bondFree: Boolean = ClassicFeatures.current.bondFree): BluetoothSocket =
        if (classicRoleUsesSecureRfcomm(role, bondFree)) {
            device.createRfcommSocketToServiceRecord(ClassicTransport.APP_SERVICE.serviceUuid)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(ClassicTransport.APP_SERVICE.serviceUuid)
        }
}

/**
 * Device-family policy candidate (shaped for the DeviceFamilyPolicy registry, kept here so the
 * registry owner can adopt it):
 * - matcher: [classicScanAddressTypeReported], Android 15+ (SDK 35), where
 *   BluetoothDevice.getAddressType() is public API;
 * - behaviour: Classic never pages a scanned BLE address that Android reports as random;
 * - sources: SDK platform android-35 android.jar has BluetoothDevice.getAddressType() and
 *   android-34 does not; AOSP packages/modules/Bluetooth 745ee92 framework BluetoothDevice.java
 *   getAddressType() and api/current.txt; Bluetooth Core v5.4 Vol 6 Part B 1.3 (random device
 *   addresses are LE-only) and Vol 2 Part B 8.3 (BR/EDR paging targets the BD_ADDR);
 * - evidence: media-workflow-7396b46e-setup Classic S24 -> M31 paged unresolved candidates until the
 *   broadcaster's 12 s timeout (DECISIONS "Classic identity boundary"); the skip itself is
 *   unmeasured on phones;
 * - confidence: high for the API and the spec; per-OEM reporting of the type is unmeasured;
 * - off-switch: DEBUG `test_classic_page_random_address=true` pages the address anyway.
 * Older APIs cannot tell, so the address stays a short guess: a peer that advertises with its
 * public address is still reachable.
 */
internal fun classicScanAddressTypeReported(fingerprint: BluetoothStackFingerprint): Boolean =
    fingerprint.sdkInt >= 35

@SuppressLint("NewApi")
internal fun classicScanAddressIsRandom(
    device: BluetoothDevice,
    fingerprint: BluetoothStackFingerprint = BluetoothStackFingerprint.current(),
): Boolean = !DebugTunables.classicPageRandomAddress && classicScanAddressTypeReported(fingerprint) &&
    runCatching { device.addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM }.getOrDefault(false)

/**
 * Remembered BR/EDR addresses of authenticated app identities. A hint, never trust: every use is
 * verified again by BMH2, an address that authenticates as another identity is forgotten, and
 * nothing here authorizes anything. One address belongs to one phone, so learning an address
 * removes any other identity recorded at it (a reinstalled app gets a new identity).
 */
internal class ClassicAddressBookState(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
) {
    data class Entry(val nodeIdHex: String, val address: String, val learnedAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** Returns true when the stored mapping changed. */
    @Synchronized
    fun learn(nodeIdHex: String, address: String, nowMs: Long): Boolean {
        val node = nodeIdHex.lowercase(Locale.US)
        val normalized = normalizeAddress(address) ?: return false
        if (!NODE_HEX.matches(node)) return false
        val previous = entries[node]
        entries.values.removeAll { it.address == normalized && it.nodeIdHex != node }
        entries.remove(node)
        entries[node] = Entry(node, normalized, nowMs)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        return previous == null || previous.address != normalized || nowMs - previous.learnedAtMs > REFRESH_MS
    }

    /** The most recently learned fresh address whose identity starts with [prefixHex]. */
    @Synchronized
    fun lookup(prefixHex: String, nowMs: Long): String? {
        val prefix = prefixHex.lowercase(Locale.US)
        if (prefix.isEmpty()) return null
        return entries.values
            .filter { it.nodeIdHex.startsWith(prefix) && nowMs - it.learnedAtMs in 0 until ttlMs }
            .maxByOrNull(Entry::learnedAtMs)
            ?.address
    }

    /** Forgets [address] for identities starting with [prefixHex] (it authenticated as someone else). */
    @Synchronized
    fun forget(prefixHex: String, address: String): Boolean {
        val normalized = normalizeAddress(address) ?: return false
        val prefix = prefixHex.lowercase(Locale.US)
        return entries.values.removeAll { it.address == normalized && it.nodeIdHex.startsWith(prefix) }
    }

    @Synchronized
    fun prune(nowMs: Long) {
        entries.values.removeAll { nowMs - it.learnedAtMs !in 0 until ttlMs }
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun encode(): String = entries.values.joinToString("\n") { "${it.nodeIdHex} ${it.address} ${it.learnedAtMs}" }

    companion object {
        const val MAX_ENTRIES = 64
        /** A public address does not change; the limit only bounds how long metadata is kept. */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1_000
        /** An unchanged mapping is rewritten at most daily, so a phone in regular use does not expire. */
        const val REFRESH_MS = 24L * 60 * 60 * 1_000
        private val NODE_HEX = Regex("[0-9a-f]{32}")
        private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        fun normalizeAddress(address: String): String? =
            address.trim().uppercase(Locale.US).takeIf { ADDRESS.matches(it) && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }

        fun decode(text: String, maxEntries: Int = MAX_ENTRIES, ttlMs: Long = TTL_MS): ClassicAddressBookState {
            val state = ClassicAddressBookState(maxEntries, ttlMs)
            text.lineSequence().mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size != 3) return@mapNotNull null
                parts[2].toLongOrNull()?.let { Triple(parts[0], parts[1], it) }
            }.sortedBy { it.third }.forEach { (node, address, at) -> state.learn(node, address, at) }
            return state
        }
    }
}

/**
 * Process-wide address book for bond-free Classic. Kept in no-backup storage (cloud backup and
 * device transfer never copy it); written only while the experiment is on. DEBUG launches can add
 * per-prefix overrides (`test_classic_addresses`, `test_classic_address` + `_node`) that are never
 * persisted, so a benchmark can supply addresses read over adb. Sealed with the verified-contacts key
 * since 2026-10-27 (review finding 6: node IDs next to Bluetooth addresses are who this phone met);
 * the plain `classic-addresses.txt` of earlier builds is read once and deleted.
 */
internal object ClassicAddressBook {
    private const val LEGACY_FILE_NAME = "classic-addresses.txt"
    private const val FILE_NAME = "classic-addresses.bin"
    @Volatile private var file: SealedTextFile? = null
    private var state = ClassicAddressBookState()

    /** False while the stored book could not be read (a passing Keystore or disk failure): it is never written over then. */
    private var loadedFromFile = false
    private val debugOverrides = ConcurrentHashMap<String, String>()

    @Synchronized
    fun attach(context: Context) {
        val dir = context.applicationContext.noBackupFilesDir
        attach(SealedTextFile(File(dir, FILE_NAME), { IdentityVerification.atRestCipher() }, legacyPlain = File(dir, LEGACY_FILE_NAME)))
    }

    /** Production attaches the sealed file in no-backup storage; a JVM test passes its own. */
    @Synchronized
    internal fun attach(storage: SealedTextFile) {
        file = storage
        state = ClassicAddressBookState()
        loadedFromFile = false
        reloadIfNeeded(storage)
        state.prune(System.currentTimeMillis())
    }

    /** DEBUG only: "prefixhex=AA:BB:CC:DD:EE:FF,prefix2=..." (12-hex-digit announcement prefixes). */
    fun setDebugOverrides(entries: Map<String, String>) {
        debugOverrides.clear()
        if (!BuildConfig.DEBUG) return
        entries.forEach { (prefix, address) ->
            val normalized = ClassicAddressBookState.normalizeAddress(address) ?: return@forEach
            val key = prefix.lowercase(Locale.US)
            if (key.length == DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2 && key.all { it in "0123456789abcdef" }) {
                debugOverrides[key] = normalized
            }
        }
    }

    fun debugOverride(prefix: ByteArray): String? = debugOverrides[prefix.toPrefixHex()]

    @Synchronized
    fun lookup(prefix: ByteArray): String? =
        if (ClassicFeatures.current.bondFree) state.lookup(prefix.toPrefixHex(), System.currentTimeMillis()) else null

    /**
     * Records [address] for [nodeId] after an authenticated Classic link (either direction). A throwaway key
     * ([peerPseudonymous], BMH3 pseudonyms) is nobody to find again: nothing is learned (review 2026-10-27, finding 5).
     */
    @Synchronized
    fun learn(nodeId: NodeId, address: String?, direction: String, peerPseudonymous: Boolean = false) {
        if (!ClassicFeatures.current.bondFree || address == null || peerPseudonymous) return
        if (state.learn(nodeId.toString(), address, System.currentTimeMillis())) {
            persist()
            Log.i(
                "BluetoothVideo",
                "Classic address learned | " + (if (BuildConfig.DEBUG) "peer=${nodeId.shortLabel()} | address=...${address.takeLast(5)} | " else "") +
                    "direction=$direction",
            )
        }
    }

    @Synchronized
    fun forget(prefix: ByteArray, address: String) {
        if (state.forget(prefix.toPrefixHex(), address)) persist()
    }

    /**
     * Forgets every remembered address and deletes the file. Called when the user switches "Connect
     * without pairing" off: the hints are only used while it is on, so none are kept meanwhile.
     */
    @Synchronized
    fun clear() {
        state = ClassicAddressBookState()
        loadedFromFile = true
        file?.delete()
    }

    /** Reads the stored book once it can be read, keeping what this process learned meanwhile (newer entries win). */
    private fun reloadIfNeeded(storage: SealedTextFile): Boolean {
        if (loadedFromFile) return true
        val text = try {
            storage.read()
        } catch (unavailable: SealedTextUnavailable) {
            return false
        }
        state = ClassicAddressBookState.decode(listOfNotNull(text, state.encode()).joinToString("\n"))
        loadedFromFile = true
        return true
    }

    private fun persist() {
        val target = file ?: return
        if (!reloadIfNeeded(target)) return
        // An emergency wipe in progress refuses the write (SealedTextFile checks WipeGuard): nothing is recreated.
        runCatching { target.write(state.encode()) }
            .onFailure { runCatching { Log.w("BluetoothVideo", "Classic address book not saved (${it.javaClass.simpleName})") } }
    }

    private fun ByteArray.toPrefixHex(): String =
        copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).joinToString("") { "%02x".format(it) }
}

/** Parses the DEBUG `test_classic_addresses` extra: "prefix=AA:BB:CC:DD:EE:FF,prefix2=...". */
internal fun parseClassicAddressOverrides(value: String?): Map<String, String> =
    value.orEmpty().split(',', ';').mapNotNull { entry ->
        val parts = entry.split('=')
        if (parts.size != 2) return@mapNotNull null
        val prefix = parts[0].trim().lowercase(Locale.US)
        val address = ClassicAddressBookState.normalizeAddress(parts[1]) ?: return@mapNotNull null
        prefix to address
    }.toMap()

/**
 * Learns the remote BR/EDR address of an accepted or opened Classic socket once the link handshake authenticated it;
 * never for a peer that presented a throwaway key ([peerPseudonymous]).
 */
@SuppressLint("MissingPermission")
internal fun learnClassicPeerAddress(socket: BluetoothSocket?, peer: NodeId, direction: String, peerPseudonymous: Boolean = false) {
    if (socket == null || !ClassicFeatures.current.bondFree || peerPseudonymous) return
    if (socket.connectionType != BluetoothSocket.TYPE_RFCOMM) return
    ClassicAddressBook.learn(peer, runCatching { socket.remoteDevice.address }.getOrNull(), direction, peerPseudonymous)
}
