package com.example.bluetoothtest

// Debug-build GATT benchmark implementation.

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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class GattBenchmarkConfig(
    val dataPacketsPerFence: Int = 2048,
    val connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
    /** Servers advertise this one-byte tag so a multi-phone probe connects the intended pair. */
    val advertiseTag: Int? = null,
    /** Label prefix for status lines when one phone runs two benchmark links at once. */
    val label: String = "",
    /** Pace data to this payload rate (0 saturates), modelling a live stream on one link. */
    val rateKbps: Int = 0,
) {
    init {
        require(dataPacketsPerFence in 1..MAX_DATA_PACKETS_PER_FENCE)
    }

    companion object {
        const val MAX_DATA_PACKETS_PER_FENCE = 2048
    }
}

class BleGattBenchmark(
    context: Context,
    private val adapter: BluetoothAdapter,
    reportStatus: (String) -> Unit,
    private val onFinished: () -> Unit,
    private val config: GattBenchmarkConfig = GattBenchmarkConfig(),
) : Closeable {
    private val onStatus: (String) -> Unit = { message -> reportStatus(config.label + message) }
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val running = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeStatus = BluetoothGatt.GATT_FAILURE
    @Volatile private var notifyLatch: CountDownLatch? = null
    @Volatile private var notifyStatus = BluetoothGatt.GATT_FAILURE
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var startedNs = 0L
    @Volatile private var reportNs = 0L
    @Volatile private var reportBytes = 0L
    @Volatile private var totalBytes = 0L
    @Volatile private var serverSendsNotifications = false
    @Volatile private var clientReceivesNotifications = false
    @Volatile private var notificationDurationSeconds = 0
    private val notificationStarted = AtomicBoolean(false)
    private var worker: Thread? = null
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            fail("GATT advertising failed (code $errorCode)")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gattServer?.setPreferredPhy(
                    device,
                    requestedPhy.mask,
                    requestedPhy.mask,
                    requestedPhy.option,
                )
                gattServer?.readPhy(device)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            mtu = newMtu
            onStatus("GATT ${if (serverSendsNotifications) "NOTIFY TX" else "RX"} MTU $newMtu")
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) =
            reportPhy(if (serverSendsNotifications) "NOTIFY TX" else "RX", txPhy, rxPhy, status)

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) =
            reportPhy(if (serverSendsNotifications) "NOTIFY TX" else "RX", txPhy, rxPhy, status)

        /** Hidden framework callback (not in the SDK stubs); see LeConnectionParameterLog. */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) =
            LeConnectionParameterLog.record(
                "gatt-benchmark", LeLinkRole.PERIPHERAL, device.address, interval, latency, timeout, status,
            )

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val valid = characteristic.uuid == DATA_UUID && !preparedWrite && offset == 0 && value.isNotEmpty()
            val endReceived = valid && handleIncoming(value)
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0,
                    null,
                )
            }
            if (endReceived) {
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, END_GRACE_MS)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val valid = serverSendsNotifications && descriptor.uuid == CCCD_UUID &&
                !preparedWrite && offset == 0 &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0,
                    null,
                )
            }
            if (!valid || !notificationStarted.compareAndSet(false, true)) return
            val characteristic = descriptor.characteristic
            worker = thread(name = "BleGattNotificationSender") {
                runCatching {
                    sendNotifications(device, characteristic, notificationDurationSeconds)
                }.onFailure { error -> fail(error.message ?: error.javaClass.simpleName) }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyStatus = status
            notifyLatch?.countDown()
        }
    }

    private lateinit var requestedPhy: BlePhyPreference

    @SuppressLint("MissingPermission")
    fun startReceiver(phyPreference: BlePhyPreference) {
        startServer(phyPreference, sendsNotifications = false, durationSeconds = 0)
    }

    fun startNotificationTransmitter(
        durationSeconds: Int,
        phyPreference: BlePhyPreference,
    ) {
        startServer(phyPreference, sendsNotifications = true, durationSeconds.coerceIn(1, 120))
    }

    @SuppressLint("MissingPermission")
    private fun startServer(
        phyPreference: BlePhyPreference,
        sendsNotifications: Boolean,
        durationSeconds: Int,
    ) {
        check(running.compareAndSet(false, true)) { "GATT benchmark is already running" }
        requestedPhy = phyPreference
        serverSendsNotifications = sendsNotifications
        notificationDurationSeconds = durationSeconds
        val server = bluetoothManager.openGattServer(appContext, serverCallback)
            ?: throw IOException("Could not open GATT benchmark server")
        gattServer = server
        val characteristic = BluetoothGattCharacteristic(
            DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(characteristic) }
        if (!server.addService(service)) {
            close()
            throw IOException("Could not register GATT benchmark service")
        }
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IOException("BLE advertising is unavailable")
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build(),
            config.advertiseTag?.let { tag ->
                // A 128-bit UUID list plus service data would exceed legacy advertising space.
                AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE_UUID), byteArrayOf(tag.toByte())).build()
            } ?: AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            advertiseCallback,
        )
        onStatus(
            "GATT ${if (sendsNotifications) "NOTIFY TX" else "RX"} ready; " +
                "requested ${phyPreference.label} PHY",
        )
    }

    @SuppressLint("MissingPermission")
    fun startSender(device: BluetoothDevice, durationSeconds: Int, phyPreference: BlePhyPreference) {
        startClient(device, durationSeconds.coerceIn(1, 120), phyPreference, receivesNotifications = false)
    }

    fun startNotificationReceiver(device: BluetoothDevice, phyPreference: BlePhyPreference) {
        startClient(device, durationSeconds = 0, phyPreference, receivesNotifications = true)
    }

    @SuppressLint("MissingPermission")
    private fun startClient(
        device: BluetoothDevice,
        durationSeconds: Int,
        phyPreference: BlePhyPreference,
        receivesNotifications: Boolean,
    ) {
        check(running.compareAndSet(false, true)) { "GATT benchmark is already running" }
        requestedPhy = phyPreference
        clientReceivesNotifications = receivesNotifications
        val side = if (receivesNotifications) "NOTIFY RX" else "TX"
        onStatus("GATT $side connecting to ${device.name ?: device.address}")
        gatt = device.connectGatt(
            appContext,
            false,
            clientCallback(durationSeconds),
            BluetoothDevice.TRANSPORT_LE,
        ) ?: throw IOException("Could not start GATT benchmark connection")
    }

    private fun clientCallback(durationSeconds: Int) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> fail("GATT connection failed (status $status)")
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    val priorityQueued = activeGatt.requestConnectionPriority(
                        config.connectionPriority.requestValue,
                    )
                    onStatus(
                        "GATT ${if (clientReceivesNotifications) "NOTIFY RX" else "TX"} requested " +
                            "${config.connectionPriority.label} priority " +
                            if (priorityQueued) "queued" else "failed",
                    )
                    activeGatt.setPreferredPhy(
                        requestedPhy.mask,
                        requestedPhy.mask,
                        requestedPhy.option,
                    )
                    activeGatt.readPhy()
                    if (!activeGatt.requestMtu(MAX_MTU)) activeGatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED && running.get() ->
                    fail("GATT benchmark peer disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(activeGatt: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_MTU
            onStatus("GATT ${if (clientReceivesNotifications) "NOTIFY RX" else "TX"} MTU $mtu")
            if (!activeGatt.discoverServices()) fail("Could not discover GATT benchmark service")
        }

        override fun onPhyUpdate(activeGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) =
            reportPhy(if (clientReceivesNotifications) "NOTIFY RX" else "TX", txPhy, rxPhy, status)

        override fun onPhyRead(activeGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) =
            reportPhy(if (clientReceivesNotifications) "NOTIFY RX" else "TX", txPhy, rxPhy, status)

        /** Hidden framework callback (not in the SDK stubs); see LeConnectionParameterLog. */
        @Suppress("unused")
        fun onConnectionUpdated(activeGatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) =
            LeConnectionParameterLog.record(
                "gatt-benchmark", LeLinkRole.CENTRAL, activeGatt.device?.address, interval, latency, timeout, status,
            )

        private var refreshedServiceCache = false

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
            val characteristic = activeGatt.getService(SERVICE_UUID)?.getCharacteristic(DATA_UUID)
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic == null && !refreshedServiceCache) {
                // Bonded peers can serve a stale cached database from another GATT server.
                refreshedServiceCache = true
                val refreshed = runCatching {
                    activeGatt.javaClass.getMethod("refresh").invoke(activeGatt) as Boolean
                }.getOrDefault(false)
                onStatus("GATT benchmark service missing from cached database; refresh=$refreshed")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!activeGatt.discoverServices()) fail("Could not rediscover GATT benchmark service")
                }, SERVICE_REFRESH_DELAY_MS)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                fail("GATT benchmark service discovery failed (status $status)")
                return
            }
            if (clientReceivesNotifications) {
                subscribeToNotifications(activeGatt, characteristic)
                return
            }
            worker = thread(name = "BleGattBenchmarkSender") {
                runCatching { sendPayload(activeGatt, characteristic, durationSeconds) }
                    .onFailure { error -> fail(error.message ?: error.javaClass.simpleName) }
            }
        }

        override fun onCharacteristicWrite(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeStatus = status
            writeLatch?.countDown()
        }

        override fun onDescriptorWrite(
            activeGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!clientReceivesNotifications || descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onStatus("GATT NOTIFY RX subscribed")
            } else {
                fail("GATT notification subscription failed (status $status)")
            }
        }

        @Deprecated("Deprecated in Android")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                receiveNotification(characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = receiveNotification(value)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun subscribeToNotifications(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null || !activeGatt.setCharacteristicNotification(characteristic, true)) {
            fail("Could not enable GATT benchmark notifications")
            return
        }
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            activeGatt.writeDescriptor(descriptor)
        }
        if (!queued) fail("Could not subscribe to GATT benchmark notifications")
    }

    private fun receiveNotification(value: ByteArray) {
        if (!clientReceivesNotifications || value.isEmpty()) return
        if (handleIncoming(value)) {
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, END_GRACE_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPayload(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        durationSeconds: Int,
    ) {
        write(activeGatt, characteristic, startPacket(durationSeconds), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        val payloadSize = (mtu - ATT_OVERHEAD - 1).coerceIn(1, MAX_ATTRIBUTE_BYTES - 1)
        val packet = ByteArray(payloadSize + 1).also { bytes ->
            bytes[0] = TYPE_DATA
            var state = 0x13579bdf
            for (index in 1 until bytes.size) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                bytes[index] = state.toByte()
            }
        }
        onStatus(
            "GATT TX payload $payloadSize B x ${config.dataPacketsPerFence}; " +
                "priority ${config.connectionPriority.label}",
        )
        val begin = System.nanoTime()
        var sent = 0L
        var packetsInWindow = 0
        while (running.get() && System.nanoTime() - begin < durationSeconds * 1_000_000_000L) {
            write(
                activeGatt,
                characteristic,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            sent += payloadSize
            pace(begin, sent)
            packetsInWindow++
            if (packetsInWindow == config.dataPacketsPerFence) {
                write(
                    activeGatt,
                    characteristic,
                    byteArrayOf(TYPE_FENCE),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                packetsInWindow = 0
            }
        }
        write(activeGatt, characteristic, byteArrayOf(TYPE_END), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        val seconds = (System.nanoTime() - begin) / 1_000_000_000.0
        onStatus("GATT TX FINAL %.3f Mbps, %.1f MB in %.1fs".format(
            sent * 8 / seconds / 1_000_000,
            sent / 1_000_000.0,
            seconds,
        ))
        finish()
    }

    private fun pace(beginNs: Long, sentBytes: Long) {
        if (config.rateKbps <= 0) return
        val dueNs = beginNs + sentBytes * 8 * 1_000_000L / config.rateKbps
        val waitNs = dueNs - System.nanoTime()
        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
    }

    @SuppressLint("MissingPermission")
    private fun sendNotifications(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        durationSeconds: Int,
    ) {
        notify(device, characteristic, startPacket(durationSeconds))
        val payloadSize = (mtu - ATT_OVERHEAD - 1).coerceIn(1, MAX_ATTRIBUTE_BYTES - 1)
        val packet = ByteArray(payloadSize + 1).also { bytes ->
            bytes[0] = TYPE_DATA
            var state = 0x13579bdf
            for (index in 1 until bytes.size) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                bytes[index] = state.toByte()
            }
        }
        onStatus(
            "GATT NOTIFY TX payload $payloadSize B; priority requested by client",
        )
        val begin = System.nanoTime()
        var sent = 0L
        while (running.get() && System.nanoTime() - begin < durationSeconds * 1_000_000_000L) {
            notify(device, characteristic, packet)
            sent += payloadSize
            pace(begin, sent)
        }
        notify(device, characteristic, byteArrayOf(TYPE_END))
        val seconds = (System.nanoTime() - begin) / 1_000_000_000.0
        onStatus("GATT NOTIFY TX FINAL %.3f Mbps, %.1f MB in %.1fs".format(
            sent * 8 / seconds / 1_000_000,
            sent / 1_000_000.0,
            seconds,
        ))
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, END_GRACE_MS)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun notify(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val server = gattServer ?: throw IOException("GATT notification server closed")
        val latch = CountDownLatch(1)
        notifyLatch = latch
        notifyStatus = BluetoothGatt.GATT_FAILURE
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
        if (!queued) throw IOException("GATT notification queue rejected a packet")
        if (!latch.await(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("GATT notification timed out")
        }
        notifyLatch = null
        if (notifyStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT notification failed (status $notifyStatus)")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun write(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ) {
        val latch = CountDownLatch(1)
        writeLatch = latch
        writeStatus = BluetoothGatt.GATT_FAILURE
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            activeGatt.writeCharacteristic(characteristic)
        }
        if (!queued) throw IOException("GATT write queue rejected a packet")
        if (!latch.await(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("GATT write timed out")
        writeLatch = null
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) throw IOException("GATT write failed (status $writeStatus)")
    }

    private fun handleIncoming(value: ByteArray): Boolean {
        when (value[0]) {
            TYPE_START -> {
                startedNs = System.nanoTime()
                reportNs = startedNs
                reportBytes = 0
                totalBytes = 0
            }
            TYPE_DATA -> if (startedNs != 0L) {
                val bytes = (value.size - 1).toLong()
                totalBytes += bytes
                reportBytes += bytes
                val now = System.nanoTime()
                if (now - reportNs >= REPORT_NS) {
                    onStatus("GATT RX %.3f Mbps, %.1f MB total".format(
                        reportBytes * 8.0 * 1_000 / (now - reportNs),
                        totalBytes / 1_000_000.0,
                    ))
                    reportNs = now
                    reportBytes = 0
                }
            }
            TYPE_FENCE -> Unit
            TYPE_END -> {
                val seconds = (System.nanoTime() - startedNs).coerceAtLeast(1) / 1_000_000_000.0
                onStatus("GATT RX FINAL %.3f Mbps, %.1f MB in %.1fs".format(
                    totalBytes * 8 / seconds / 1_000_000,
                    totalBytes / 1_000_000.0,
                    seconds,
                ))
                return true
            }
        }
        return false
    }

    private fun reportPhy(side: String, txPhy: Int, rxPhy: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            onStatus("GATT $side PHY TX ${phyName(txPhy)} / RX ${phyName(rxPhy)}")
        } else {
            onStatus("GATT $side PHY unavailable (status $status)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    private fun fail(message: String) {
        if (running.get()) onStatus("GATT benchmark failed: $message")
        finish()
    }

    private fun finish() {
        if (!running.getAndSet(false)) return
        closeResources()
        if (finished.compareAndSet(false, true)) onFinished()
    }

    override fun close() {
        running.set(false)
        closeResources()
        if (finished.compareAndSet(false, true)) onFinished()
    }

    @SuppressLint("MissingPermission")
    private fun closeResources() {
        stopAdvertising()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        runCatching { gattServer?.close() }
        gattServer = null
        worker?.interrupt()
        worker = null
        notifyLatch?.countDown()
        notifyLatch = null
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4ea6f201-13ee-41eb-99b4-63aaf69a495a")
        private val DATA_UUID: UUID = UUID.fromString("4ea6f202-13ee-41eb-99b4-63aaf69a495a")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TYPE_START: Byte = 1
        private const val TYPE_DATA: Byte = 2
        private const val TYPE_END: Byte = 3
        private const val TYPE_FENCE: Byte = 4
        private const val DEFAULT_MTU = 23
        private const val MAX_MTU = 517
        private const val ATT_OVERHEAD = 3
        private const val MAX_ATTRIBUTE_BYTES = 512
        private const val WRITE_TIMEOUT_SECONDS = 5L
        private const val REPORT_NS = 1_000_000_000L
        private const val SERVICE_REFRESH_DELAY_MS = 600L
        private const val END_GRACE_MS = 500L
        private const val SCAN_TIMEOUT_MS = 8_000L

        private fun startPacket(durationSeconds: Int): ByteArray =
            ByteBuffer.allocate(5).put(TYPE_START).putInt(durationSeconds).array()

        private fun phyName(phy: Int): String = when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> "Coded"
            else -> "unknown($phy)"
        }

        @SuppressLint("MissingPermission")
        fun findReceiver(
            adapter: BluetoothAdapter,
            onFound: (BluetoothDevice) -> Unit,
            onError: (String) -> Unit,
            targetTag: Int? = null,
        ): Closeable {
            val scanner = adapter.bluetoothLeScanner
                ?: throw IOException("BLE scanning is unavailable")
            val closed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!closed.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    scanner.stopScan(this)
                    onFound(result.device)
                }

                override fun onScanFailed(errorCode: Int) {
                    if (!closed.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    onError("GATT scan failed (code $errorCode)")
                }
            }
            val filter = targetTag?.let { tag ->
                ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), byteArrayOf(tag.toByte())).build()
            } ?: ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            scanner.startScan(
                listOf(filter),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            handler.postDelayed({
                if (!closed.compareAndSet(false, true)) return@postDelayed
                scanner.stopScan(callback)
                onError("GATT benchmark receiver not found")
            }, SCAN_TIMEOUT_MS)
            return Closeable {
                handler.removeCallbacksAndMessages(null)
                if (closed.compareAndSet(false, true)) scanner.stopScan(callback)
            }
        }
    }
}
