package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * DEBUG only, experimental, off unless the launch extra `test_gap_name_probe` is present: the "unpaired central" of
 * phone test P7 (PLAN "Discovery privacy"; PROTOCOL "Pre-handshake exposure"). It scans for app adverts, connects to up
 * to [MAX_TARGETS] of them as an unbonded GATT client and logs what a stranger can read before any handshake:
 *
 * - `gap_name_probe: event=target`: the address, the announcement kind and version (so the advertiser's set is known);
 * - `event=name`: the ATT status of reading Device Name (0x2A00), the length and whether it is the neutral name
 *   (the name itself is never logged); status 0 = readable, 5 = insufficient authentication (hidden);
 * - `event=services` and `event=db_hash`: the size of the GATT database and the Database Hash (0x2B2A), which may be
 *   the same in every epoch and differs between phones (RH2);
 * - with the extra value `pair`, `event=bond`: whether a Pairing Request is accepted and what the bond state does.
 *
 * The extra value is `read` (name, services, hash) or `pair` (also asks to bond); any other non-false value, a boolean
 * `true` included, means `read` ([optionFrom]). The probed phone sees an ordinary incoming connection that never
 * handshakes (its 20 s deadline and the stranger tarpit apply), so probe idle phones.
 *
 * Start path (fixed 2026-10-12): the probe used to start only from `onPermissionsReady`, which runs when a camera,
 * watch or call screen opens, never for a launch that stays on Home (the P7 harness on idle phones logged nothing in
 * three launch variants). [MainActivity] now asks from `onResume` too, and every non-start logs
 * `gap_name_probe: event=skipped | reason=...` ([skipReason]); `event=requested` names the option and the call site.
 */
internal object GapNameProbe {
    const val EXTRA = "test_gap_name_probe"
    private const val TAG = "BluetoothVideo"
    private const val MAX_TARGETS = 4
    private const val SCAN_MS = 12_000L
    private const val TARGET_TIMEOUT_MS = 20_000L
    private const val BOND_WAIT_MS = 10_000L

    private val GAP_SERVICE = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    private val GATT_SERVICE = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    private val DATABASE_HASH = UUID.fromString("00002b2a-0000-1000-8000-00805f9b34fb")

    // Lazy: the pure helpers of this object (optionFrom, skipReason) run in JVM tests, which have no main looper.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var running = false

    /** The probe option of a launch extra of any type (`--es ... read|pair`, `--ez ... true`); null: not requested. */
    fun optionFrom(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) "read" else null
        is CharSequence -> value.toString().trim().lowercase().let { text ->
            when (text) {
                "", "false", "0", "off", "no" -> null
                "pair" -> "pair"
                else -> "read"
            }
        }
        else -> "read"
    }

    /** Why the probe cannot run now (a fixed vocabulary for the log), or null when it can. */
    fun skipReason(
        debugBuild: Boolean,
        running: Boolean,
        adapterPresent: Boolean,
        bluetoothOn: Boolean,
        permissionGranted: Boolean,
        scannerPresent: Boolean,
    ): String? = when {
        !debugBuild -> "not_a_debug_build"
        running -> "already_running"
        !adapterPresent -> "no_bluetooth_adapter"
        !bluetoothOn -> "bluetooth_off"
        !permissionGranted -> "permission_missing"
        !scannerPresent -> "no_le_scanner"
        else -> null
    }

    /** The permission an unbonded scan and connection needs on this Android version. */
    fun permissionGranted(context: Context): Boolean {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Starts the probe when the launch asked for it. Returns true once it started (the caller then stops asking); false
     * with a logged reason (once per distinct reason) when it could not, so a later call site can retry.
     */
    @SuppressLint("MissingPermission")
    fun maybeStart(context: Context, adapter: BluetoothAdapter?, extras: android.os.Bundle?, source: String): Boolean {
        val option = optionFrom(extras?.get(EXTRA)) ?: return false
        val scanner = if (adapter != null && adapter.isEnabled && permissionGranted(context)) adapter.bluetoothLeScanner else null
        val reason = skipReason(
            debugBuild = BuildConfig.DEBUG,
            running = running,
            adapterPresent = adapter != null,
            bluetoothOn = adapter?.isEnabled == true,
            permissionGranted = permissionGranted(context),
            scannerPresent = scanner != null,
        )
        if (reason != null) {
            if (reason != lastSkipReason) {
                lastSkipReason = reason
                Log.i(TAG, "BENCHMARK gap_name_probe: event=skipped | reason=$reason | source=$source | sdk=${android.os.Build.VERSION.SDK_INT}")
            }
            return false
        }
        Log.i(TAG, "BENCHMARK gap_name_probe: event=requested | option=$option | source=$source | sdk=${android.os.Build.VERSION.SDK_INT}")
        start(context, adapter, option)
        return true
    }

    @Volatile private var lastSkipReason: String? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context, adapter: BluetoothAdapter?, option: String) {
        if (!BuildConfig.DEBUG || adapter == null || running) return
        val scanner = adapter.bluetoothLeScanner ?: return
        running = true
        val app = context.applicationContext
        val pair = option.equals("pair", ignoreCase = true)
        val targets = LinkedHashMap<String, Pair<BluetoothDevice, String>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (targets.size >= MAX_TARGETS || targets.containsKey(address)) return
                val bytes = result.scanRecord?.getManufacturerSpecificData(BleL2capControlProtocol.MESH_MANUFACTURER_ID)
                val note = runCatching { DiscoveryAnnouncementCodec.decode(requireNotNull(bytes), acceptRotating = true) }.getOrNull()
                    ?.let { "version=${it.version} | kind=${kindOf(it)}" } ?: "version=? | kind=?"
                targets[address] = result.device to note
                Log.i(TAG, "BENCHMARK gap_name_probe: event=target | address=$address | $note")
            }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleL2capControlProtocol.SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        handler.postDelayed({
            runCatching { scanner.stopScan(callback) }
            probeNext(app, targets.values.toMutableList(), pair)
        }, SCAN_MS)
    }

    private fun kindOf(announcement: DiscoveryAnnouncement): String = when {
        announcement.isStreamOffer() -> "stream_offer"
        announcement.isPrivateCallOffer() -> "call_offer"
        else -> "viewer_request"
    }

    private fun probeNext(app: Context, queue: MutableList<Pair<BluetoothDevice, String>>, pair: Boolean) {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            running = false
            Log.i(TAG, "BENCHMARK gap_name_probe: event=done")
            return
        }
        probe(app, next.first, pair) { probeNext(app, queue, pair) }
    }

    @SuppressLint("MissingPermission")
    private fun probe(app: Context, device: BluetoothDevice, pair: Boolean, done: () -> Unit) {
        val address = device.address
        var finished = false
        var gatt: BluetoothGatt? = null
        var bondReceiver: BroadcastReceiver? = null

        fun finish(reason: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(address)
            runCatching { bondReceiver?.let(app::unregisterReceiver) }
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            Log.i(TAG, "BENCHMARK gap_name_probe: event=finished | address=$address | reason=$reason")
            handler.postDelayed(done, 1_500L)
        }

        fun readsDone() {
            if (!pair) return finish("reads_done")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (changed?.address != address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    Log.i(TAG, "BENCHMARK gap_name_probe: event=bond | address=$address | state=$state")
                }
            }
            bondReceiver = receiver
            app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            val asked = runCatching { device.createBond() }.getOrDefault(false)
            Log.i(TAG, "BENCHMARK gap_name_probe: event=bond | address=$address | requested=$asked")
            handler.postAtTime({ finish("bond_wait_over") }, address, android.os.SystemClock.uptimeMillis() + BOND_WAIT_MS)
        }

        fun readHashOrFinish(g: BluetoothGatt) {
            val hash = g.getService(GATT_SERVICE)?.getCharacteristic(DATABASE_HASH)
            if (hash == null || !g.readCharacteristic(hash)) readsDone()
        }

        fun onRead(g: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
            when (uuid) {
                DEVICE_NAME -> {
                    val name = value?.toString(Charsets.UTF_8)
                    Log.i(
                        TAG,
                        "BENCHMARK gap_name_probe: event=name | address=$address | status=$status | readable=${status == 0} | " +
                            "length=${value?.size ?: 0} | neutral=${name == NeutralBluetoothName.NEUTRAL}",
                    )
                    readHashOrFinish(g)
                }
                DATABASE_HASH -> {
                    Log.i(
                        TAG,
                        "BENCHMARK gap_name_probe: event=db_hash | address=$address | status=$status | " +
                            "hash=${value?.joinToString("") { "%02x".format(it) } ?: "none"}",
                    )
                    readsDone()
                }
            }
        }

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "BENCHMARK gap_name_probe: event=connected | address=$address | status=$status")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish("disconnected_status_$status")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val services = g.services
                Log.i(
                    TAG,
                    "BENCHMARK gap_name_probe: event=services | address=$address | status=$status | count=${services.size} | " +
                        "uuids=${services.joinToString(",") { it.uuid.toString().take(8) }}",
                )
                val name = g.getService(GAP_SERVICE)?.getCharacteristic(DEVICE_NAME)
                if (name == null || !g.readCharacteristic(name)) readHashOrFinish(g)
            }

            // The hidden connection-update callback every app GATT callback declares (LeConnectionParametersTest).
            fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) = Unit

            // API 33+ delivers the value here; older releases call the deprecated overload below.
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                onRead(g, characteristic.uuid, value, status)

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (android.os.Build.VERSION.SDK_INT < 33) onRead(g, characteristic.uuid, characteristic.value, status)
            }
        }
        handler.postAtTime({ finish("timeout") }, address, android.os.SystemClock.uptimeMillis() + TARGET_TIMEOUT_MS)
        gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }
}
