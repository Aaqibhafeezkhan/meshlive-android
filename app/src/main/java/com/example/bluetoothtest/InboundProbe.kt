package com.example.bluetoothtest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Measurement 7 of DECISIONS "Design RR" (stream link role reversal): DEBUG-only, default off, Experimental.
 *
 * Question: when a phone is LE central on its parent link at 15 ms under media load (the M31 sourcing a stream
 * to an ASUS viewer), and a second phone dials its connectable GATT server again and again, does each dial draw a
 * fresh child-after-parent phase, do about 80% of the dials stay alive, and does the parent link keep completing?
 * The probe carries no authentication, no media and no hop records: it is a connectable sink and a redialing
 * writer, nothing else. Launch extras (DEBUG builds only):
 *
 *  - `test_inbound_probe=server` on the phone that hosts the sink (the M31, which also runs the ordinary stream source)
 *  - `test_inbound_probe=dial` on the phone that dials it (the A15s), with `test_inbound_probe_dials` (default 15),
 *    `test_inbound_probe_hold_ms` (20000), `test_inbound_probe_gap_ms` (2000) and `test_inbound_probe_kbps` (60)
 *
 * Log lines: `BENCHMARK inbound_probe: role=server|dial | ...` (see [InboundProbeLog]). Pair them with HCI snoops on
 * both phones and `python -B tools/relay_dual_role_snoop.py --swapped` on the server phone's snoop.
 */
internal data class InboundProbeConfig(
    val role: Role,
    val dials: Int,
    val holdMs: Long,
    val gapMs: Long,
    val kbps: Int,
) {
    enum class Role(val label: String) { SERVER("server"), DIAL("dial") }

    companion object {
        const val DEFAULT_DIALS = 15
        const val DEFAULT_HOLD_MS = 20_000L
        const val DEFAULT_GAP_MS = 2_000L
        const val DEFAULT_KBPS = 60

        /** Null unless [role] is exactly `server` or `dial`; the numbers are clamped to sane probe ranges. */
        fun parse(role: String?, dials: Int?, holdMs: Int?, gapMs: Int?, kbps: Int?): InboundProbeConfig? {
            val parsed = Role.values().firstOrNull { it.label == role } ?: return null
            return InboundProbeConfig(
                role = parsed,
                dials = (dials ?: DEFAULT_DIALS).coerceIn(1, 200),
                holdMs = (holdMs?.toLong() ?: DEFAULT_HOLD_MS).coerceIn(1_000L, 600_000L),
                gapMs = (gapMs?.toLong() ?: DEFAULT_GAP_MS).coerceIn(0L, 60_000L),
                kbps = (kbps ?: DEFAULT_KBPS).coerceIn(1, 400),
            )
        }
    }
}

internal enum class InboundProbeResult(val label: String) {
    /** Held for the whole hold at no less than half the offered rate. */
    CARRIED("carried"),

    /** Held for the whole hold but under half the offered rate. */
    LOW_RATE("low_rate"),

    /** Connected, then no write completed for [InboundProbeVerdict.SILENT_MS], or the link timed out (status 8). */
    SILENT("silent"),

    /** The connect itself failed with GATT status 62 (HCI 0x3E, connection failed to be established). */
    ESTABLISH_FAILED("0x3E"),

    /** No connection inside the connect budget (or another connect failure). */
    CONNECT_TIMEOUT("connect_timeout"),

    /** Connected, but the probe service was not found. */
    NO_SERVICE("no_service"),

    /** The link ended by itself before the hold was over, not by a timeout. */
    DROPPED("dropped"),
}

/** The dial's verdict from what the callbacks saw; pure so the schedule of results is testable. */
internal object InboundProbeVerdict {
    const val SILENT_MS = 3_000L
    const val GATT_CONN_FAIL_ESTABLISH = 62
    const val GATT_CONN_TIMEOUT = 8
    private const val CARRIED_SHARE = 0.5

    /** Bits per millisecond equal kilobits per second. */
    fun achievedKbps(ackedBytes: Long, heldMs: Long): Double = if (heldMs <= 0L) 0.0 else ackedBytes * 8.0 / heldMs

    /**
     * [linkStatus] is the GATT status of a disconnect the probe did not ask for (null when the link was still up);
     * [silentMs] how long no write had completed at the end of the hold; [heldMs] how long the hold really ran.
     */
    fun classify(
        connected: Boolean,
        connectStatus: Int?,
        serviceFound: Boolean,
        linkStatus: Int?,
        silentMs: Long,
        heldMs: Long,
        targetHoldMs: Long,
        ackedBytes: Long,
        offeredKbps: Int,
    ): InboundProbeResult = when {
        !connected && connectStatus == GATT_CONN_FAIL_ESTABLISH -> InboundProbeResult.ESTABLISH_FAILED
        !connected -> InboundProbeResult.CONNECT_TIMEOUT
        !serviceFound -> InboundProbeResult.NO_SERVICE
        linkStatus == GATT_CONN_TIMEOUT || silentMs >= SILENT_MS -> InboundProbeResult.SILENT
        linkStatus != null && heldMs < targetHoldMs -> InboundProbeResult.DROPPED
        achievedKbps(ackedBytes, heldMs) >= offeredKbps * CARRIED_SHARE -> InboundProbeResult.CARRIED
        else -> InboundProbeResult.LOW_RATE
    }
}

/** When the next dial starts, how fast the writer may go, and when to look for the advertiser again. */
internal object InboundProbeSchedule {
    const val RESCAN_AFTER_CONNECT_TIMEOUTS = 2
    const val MIN_CHUNK = 20
    const val MAX_CHUNK = 244

    /** Delay before dial number [doneDials] + 1, or null when all dials are done. The first dial starts at once. */
    fun nextDelayMs(config: InboundProbeConfig, doneDials: Int): Long? = when {
        doneDials >= config.dials -> null
        doneDials == 0 -> 0L
        else -> config.gapMs
    }

    fun shouldRescan(consecutiveConnectTimeouts: Int): Boolean = consecutiveConnectTimeouts >= RESCAN_AFTER_CONNECT_TIMEOUTS

    /** Bytes the pacer may have written [elapsedMs] into the hold at [kbps]: kbps is bits per ms, so bytes = kbps * ms / 8. */
    fun allowedBytes(kbps: Int, elapsedMs: Long): Long = kbps.toLong() * elapsedMs / 8

    /** One write per ATT payload (MTU - 3), kept inside the 244-byte data PDU the controllers negotiate. */
    fun chunkBytes(mtu: Int): Int = (mtu - 3).coerceIn(MIN_CHUNK, MAX_CHUNK)
}

/** Log line text (the `BENCHMARK ` prefix is added where it is written). */
internal object InboundProbeLog {
    fun mask(address: String?): String = if (address.isNullOrEmpty()) "?" else "..." + address.takeLast(5)

    fun config(config: InboundProbeConfig): String =
        "inbound_probe: role=${config.role.label} | event=config | dials=${config.dials} | holdMs=${config.holdMs} | " +
            "gapMs=${config.gapMs} | kbps=${config.kbps}"

    fun dial(
        n: Int,
        of: Int,
        result: InboundProbeResult,
        kbps: Double,
        heldMs: Long,
        connectMs: Long,
        mtu: Int,
        status: Int?,
        atMs: Long,
    ): String = "inbound_probe: role=dial | n=$n | of=$of | result=${result.label} | " +
        "kbps=${String.format(Locale.US, "%.1f", kbps)} | heldMs=$heldMs | connectMs=$connectMs | mtu=$mtu | " +
        "status=${status ?: "none"} | atMs=$atMs"

    fun scan(found: Boolean, device: String?, atMs: Long): String =
        "inbound_probe: role=dial | event=scan | found=$found | device=${mask(device)} | atMs=$atMs"

    fun server(event: String, device: String?, clients: Int, atMs: Long, extra: String = ""): String =
        "inbound_probe: role=server | event=$event | device=${mask(device)} | clients=$clients" +
            (if (extra.isEmpty()) "" else " | $extra") + " | atMs=$atMs"

    /** One line at the end of a dial run: how many dials ended each way. */
    fun summary(results: List<InboundProbeResult>): String {
        val counts = InboundProbeResult.values().joinToString(" | ") { r -> "${r.label}=${results.count { it == r }}" }
        return "inbound_probe: role=dial | event=done | dials=${results.size} | $counts"
    }
}

/** DEBUG-only entry point. Never used in release builds; a second call in the same process does nothing. */
internal object InboundProbe {
    private const val TAG = "BluetoothVideo"
    val SERVICE_UUID: UUID = UUID.fromString("7f3c0a10-5b1e-4d7a-9c21-0b6e4d1a9f31")
    val WRITE_UUID: UUID = UUID.fromString("7f3c0a10-5b1e-4d7a-9c21-0b6e4d1a9f32")
    val NOTIFY_UUID: UUID = UUID.fromString("7f3c0a10-5b1e-4d7a-9c21-0b6e4d1a9f33")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val MTU_REQUEST = 517
    private const val PERMISSION_WAIT_MS = 60_000L

    private val started = AtomicBoolean(false)

    fun configFromTunables(): InboundProbeConfig? = if (!BuildConfig.DEBUG) null else InboundProbeConfig.parse(
        DebugTunables.inboundProbe, DebugTunables.inboundProbeDials, DebugTunables.inboundProbeHoldMs,
        DebugTunables.inboundProbeGapMs, DebugTunables.inboundProbeKbps,
    )

    fun startIfRequested(context: Context) {
        val config = configFromTunables() ?: return
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        emit(InboundProbeLog.config(config))
        thread(name = "inbound-probe", isDaemon = true) {
            if (!awaitPermissions(appContext, config.role)) {
                emit("inbound_probe: role=${config.role.label} | event=blocked | reason=bluetooth permission not granted")
                return@thread
            }
            runCatching {
                when (config.role) {
                    InboundProbeConfig.Role.SERVER -> InboundProbeServer(appContext, this::emit).start()
                    InboundProbeConfig.Role.DIAL -> InboundProbeDialer(appContext, config, this::emit).run()
                }
            }.onFailure { emit("inbound_probe: role=${config.role.label} | event=error | ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    internal fun emit(line: String) {
        Log.i(TAG, "BENCHMARK $line")
    }

    internal fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun required(role: InboundProbeConfig.Role): List<String> = when {
        Build.VERSION.SDK_INT >= 31 -> when (role) {
            InboundProbeConfig.Role.SERVER -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            InboundProbeConfig.Role.DIAL -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        }
        role == InboundProbeConfig.Role.DIAL -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    private fun awaitPermissions(context: Context, role: InboundProbeConfig.Role): Boolean {
        val deadline = nowMs() + PERMISSION_WAIT_MS
        while (true) {
            if (required(role).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
            if (nowMs() >= deadline) return false
            Thread.sleep(1_000L)
        }
    }

    internal fun adapter(context: Context): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
}

/** The connectable sink: one service, one write characteristic, one notify characteristic, any number of clients. */
@SuppressLint("MissingPermission")
internal class InboundProbeServer(private val context: Context, private val emit: (String) -> Unit) {
    private class Client(val device: BluetoothDevice, val connectedAtMs: Long) {
        val bytes = AtomicLong(0)
        var lastTickBytes = 0L
        @Volatile var notifyEnabled = false
        @Volatile var mtu = 23
    }

    private val handler = Handler(Looper.getMainLooper())
    private val clients = ConcurrentHashMap<String, Client>()
    private var server: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var startPending = false

    fun start() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        EncoderBenchGuard.check("InboundProbe.gattServer")
        val opened = manager.openGattServer(context, callback)
            ?: throw IllegalStateException("openGattServer returned null")
        server = opened
        val service = BluetoothGattService(InboundProbe.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                InboundProbe.WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val notify = BluetoothGattCharacteristic(InboundProbe.NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        notify.addDescriptor(
            BluetoothGattDescriptor(
                InboundProbe.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(notify)
        notifyCharacteristic = notify
        opened.addService(service)
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            clients.forEach { (address, client) ->
                val total = client.bytes.get()
                val kbps = (total - client.lastTickBytes) * 8.0 / TICK_MS
                client.lastTickBytes = total
                emit(
                    InboundProbeLog.server(
                        "stats", address, clients.size, InboundProbe.nowMs(),
                        "bytes=$total | kbps=${String.format(Locale.US, "%.1f", kbps)} | mtu=${client.mtu}",
                    ),
                )
                if (client.notifyEnabled) notifyOne(address)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyOne(address: String) {
        val srv = server ?: return
        val characteristic = notifyCharacteristic ?: return
        val device = clients[address]?.device ?: return
        val payload = ByteArray(20) { 0x5A }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(device, characteristic, false, payload)
            } else {
                characteristic.value = payload
                srv.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    /** Legacy connectable advertising stops when a central connects, so it is restarted after every connect and disconnect. */
    private fun restartAdvertising(delayMs: Long) {
        handler.post {
            if (startPending) return@post
            startPending = true
            runCatching { InboundProbe.adapter(context)?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            advertising = false
            handler.postDelayed({ startAdvertising() }, delayMs)
        }
    }

    private fun startAdvertising() {
        startPending = false
        val advertiser = InboundProbe.adapter(context)?.bluetoothLeAdvertiser
        if (advertiser == null) {
            emit(InboundProbeLog.server("advertising", null, clients.size, InboundProbe.nowMs(), "ok=false | reason=no advertiser"))
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(InboundProbe.SERVICE_UUID))
            .build()
        EncoderBenchGuard.check("InboundProbe.advertise")
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { emit(InboundProbeLog.server("advertising", null, clients.size, InboundProbe.nowMs(), "ok=false | reason=${it.javaClass.simpleName}")) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            emit(InboundProbeLog.server("advertising", null, clients.size, InboundProbe.nowMs(), "ok=true"))
        }

        override fun onStartFailure(errorCode: Int) {
            emit(InboundProbeLog.server("advertising", null, clients.size, InboundProbe.nowMs(), "ok=false | code=$errorCode"))
            // ADVERTISE_FAILED_ALREADY_STARTED (3) means it is still on air; any other failure is retried once.
            if (errorCode != AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) restartAdvertising(1_000L)
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            emit(InboundProbeLog.server("service", null, 0, InboundProbe.nowMs(), "status=$status"))
            if (status == 0) restartAdvertising(0L)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val now = InboundProbe.nowMs()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clients[device.address] = Client(device, now)
                emit(InboundProbeLog.server("connect", device.address, clients.size, now, "status=$status"))
                restartAdvertising(300L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val client = clients.remove(device.address)
                emit(
                    InboundProbeLog.server(
                        "disconnect", device.address, clients.size, now,
                        "status=$status | bytes=${client?.bytes?.get() ?: 0} | lifeMs=${client?.let { now - it.connectedAtMs } ?: 0}",
                    ),
                )
                restartAdvertising(300L)
            }
        }

        /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            LeConnectionParameterLog.record("inbound-probe", LeLinkRole.PERIPHERAL, device.address, interval, latency, timeout, status)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            clients[device.address]?.mtu = mtu
            emit(InboundProbeLog.server("mtu", device.address, clients.size, InboundProbe.nowMs(), "mtu=$mtu"))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            clients[device.address]?.bytes?.addAndGet((value?.size ?: 0).toLong())
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == InboundProbe.CCCD_UUID) {
                clients[device.address]?.notifyEnabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    private companion object {
        const val TICK_MS = 2_000L
    }
}

/** The dialing writer: scan once, then connect, hold under load, disconnect and connect again, N times. */
@SuppressLint("MissingPermission")
internal class InboundProbeDialer(
    private val context: Context,
    private val config: InboundProbeConfig,
    private val emit: (String) -> Unit,
) {
    private class DialState {
        val connectDone = CountDownLatch(1)
        val mtuDone = CountDownLatch(1)
        val servicesDone = CountDownLatch(1)
        val descriptorDone = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        @Volatile var connected = false
        @Volatile var connectStatus: Int? = null
        @Volatile var mtu = 23
        @Volatile var linkStatus: Int? = null
        @Volatile var weClose = false
        val writeInFlight = AtomicBoolean(false)
        val ackedBytes = AtomicLong(0)
        @Volatile var lastChunk = 0
        @Volatile var lastProgressMs = 0L
    }

    fun run() {
        val results = ArrayList<InboundProbeResult>()
        var device = scan() ?: return
        var connectTimeouts = 0
        while (true) {
            val delay = InboundProbeSchedule.nextDelayMs(config, results.size) ?: break
            if (delay > 0) Thread.sleep(delay)
            val result = dialOnce(device, results.size + 1)
            results += result
            connectTimeouts = if (result == InboundProbeResult.CONNECT_TIMEOUT) connectTimeouts + 1 else 0
            if (InboundProbeSchedule.shouldRescan(connectTimeouts)) {
                scan()?.let { device = it }
                connectTimeouts = 0
            }
        }
        emit(InboundProbeLog.summary(results))
    }

    private fun scan(): BluetoothDevice? {
        val scanner = InboundProbe.adapter(context)?.bluetoothLeScanner
        if (scanner == null) {
            emit(InboundProbeLog.scan(false, null, InboundProbe.nowMs()))
            return null
        }
        val found = CountDownLatch(1)
        var hit: BluetoothDevice? = null
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (hit == null) {
                    hit = result.device
                    found.countDown()
                }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(InboundProbe.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        EncoderBenchGuard.check("InboundProbe.scan")
        if (!ScanSwitches.probeUngated &&
            !ScanPolicy.reserveBlocking(ScanStartGate.shared, runCatching { BluetoothTransportPolicy.scanBudget() }.getOrNull())
        ) {
            emit(InboundProbeLog.scan(false, null, InboundProbe.nowMs()))
            return null
        }
        scanner.startScan(listOf(filter), settings, scanCallback)
        // Batch S: a probe scan is an app scanner too (stopAll on teardown, Android 10 leak marker); timed, so never renewed.
        ScanRegistry.register("inbound-probe-scan", stop = { runCatching { scanner.stopScan(scanCallback) } }, start = {}, renewable = false, restartable = false)
        try {
            found.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { scanner.stopScan(scanCallback) }
            ScanRegistry.unregister("inbound-probe-scan")
        }
        emit(InboundProbeLog.scan(hit != null, hit?.address, InboundProbe.nowMs()))
        return hit
    }

    @Suppress("DEPRECATION")
    private fun dialOnce(device: BluetoothDevice, n: Int): InboundProbeResult {
        val state = DialState()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state.connected = true
                    state.connectDone.countDown()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!state.connected) state.connectStatus = status
                    if (!state.weClose && state.connected && state.linkStatus == null) state.linkStatus = status
                    state.connectDone.countDown()
                    state.disconnected.countDown()
                }
            }

            /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
            @Suppress("unused")
            fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
                LeConnectionParameterLog.record("inbound-probe", LeLinkRole.CENTRAL, gatt.device?.address, interval, latency, timeout, status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) state.mtu = mtu
                state.mtuDone.countDown()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                state.servicesDone.countDown()
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                state.descriptorDone.countDown()
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    state.ackedBytes.addAndGet(state.lastChunk.toLong())
                    state.lastProgressMs = InboundProbe.nowMs()
                }
                state.writeInFlight.set(false)
            }
        }
        val startedMs = InboundProbe.nowMs()
        EncoderBenchGuard.check("InboundProbe.connectGatt")
        val gatt = checkNotNull(GattConnectCompat.connect(device, context, callback, BluetoothDevice.TRANSPORT_LE, owner = "inbound-probe")) {
            "connectGatt returned no client"
        }
        state.connectDone.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val connectMs = InboundProbe.nowMs() - startedMs
        if (!state.connected) {
            state.weClose = true
            gatt.close()
            GattResourceRegistry.shared.releaseObject(gatt)
            return finish(n, state, connectMs, 0L, false, 0L)
        }
        // The same sequence the stream transport uses: HIGH priority, then the 517 MTU request, then discovery.
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (gatt.requestMtu(MTU_REQUEST)) state.mtuDone.await(MTU_WAIT_MS, TimeUnit.MILLISECONDS)
        gatt.discoverServices()
        state.servicesDone.await(SERVICES_WAIT_MS, TimeUnit.MILLISECONDS)
        val service = gatt.getService(InboundProbe.SERVICE_UUID)
        val write = service?.getCharacteristic(InboundProbe.WRITE_UUID)
        if (service == null || write == null || state.linkStatus != null) {
            return closeAndFinish(gatt, state, n, connectMs, serviceFound = false, heldMs = 0L)
        }
        service.getCharacteristic(InboundProbe.NOTIFY_UUID)?.let { notify ->
            gatt.setCharacteristicNotification(notify, true)
            notify.getDescriptor(InboundProbe.CCCD_UUID)?.let { cccd ->
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (gatt.writeDescriptor(cccd)) state.descriptorDone.await(DESCRIPTOR_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
        write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val chunk = ByteArray(InboundProbeSchedule.chunkBytes(state.mtu)) { 0x33 }
        val holdStartMs = InboundProbe.nowMs()
        state.lastProgressMs = holdStartMs
        var written = 0L
        while (true) {
            val now = InboundProbe.nowMs()
            val elapsed = now - holdStartMs
            if (elapsed >= config.holdMs || state.linkStatus != null) break
            if (now - state.lastProgressMs >= InboundProbeVerdict.SILENT_MS) break
            if (written < InboundProbeSchedule.allowedBytes(config.kbps, elapsed) && state.writeInFlight.compareAndSet(false, true)) {
                state.lastChunk = chunk.size
                val queued = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(write, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    write.value = chunk
                    gatt.writeCharacteristic(write)
                }
                if (queued) written += chunk.size else state.writeInFlight.set(false)
            } else {
                Thread.sleep(2L)
            }
        }
        val heldMs = InboundProbe.nowMs() - holdStartMs
        return closeAndFinish(gatt, state, n, connectMs, serviceFound = true, heldMs = heldMs)
    }

    private fun closeAndFinish(gatt: BluetoothGatt, state: DialState, n: Int, connectMs: Long, serviceFound: Boolean, heldMs: Long): InboundProbeResult {
        val silentMs = InboundProbe.nowMs() - state.lastProgressMs
        val result = finish(n, state, connectMs, heldMs, serviceFound, if (serviceFound) silentMs else 0L)
        state.weClose = true
        gatt.disconnect()
        // A starved link cannot carry the terminate indication: it ends at the supervision timeout, so wait that long.
        state.disconnected.await(DISCONNECT_WAIT_MS, TimeUnit.MILLISECONDS)
        gatt.close()
        GattResourceRegistry.shared.releaseObject(gatt)
        return result
    }

    private fun finish(n: Int, state: DialState, connectMs: Long, heldMs: Long, serviceFound: Boolean, silentMs: Long): InboundProbeResult {
        val result = InboundProbeVerdict.classify(
            connected = state.connected,
            connectStatus = state.connectStatus,
            serviceFound = serviceFound || !state.connected,
            linkStatus = state.linkStatus,
            silentMs = silentMs,
            heldMs = heldMs,
            targetHoldMs = config.holdMs,
            ackedBytes = state.ackedBytes.get(),
            offeredKbps = config.kbps,
        )
        emit(
            InboundProbeLog.dial(
                n, config.dials, result, InboundProbeVerdict.achievedKbps(state.ackedBytes.get(), heldMs), heldMs, connectMs,
                state.mtu, state.linkStatus ?: state.connectStatus, InboundProbe.nowMs(),
            ),
        )
        return result
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val MTU_REQUEST = 517
        const val MTU_WAIT_MS = 3_000L
        const val SERVICES_WAIT_MS = 5_000L
        const val DESCRIPTOR_WAIT_MS = 2_000L
        const val DISCONNECT_WAIT_MS = 7_000L
    }
}
