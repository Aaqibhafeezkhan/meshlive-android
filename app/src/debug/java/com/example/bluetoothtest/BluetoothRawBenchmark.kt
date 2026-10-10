package com.example.bluetoothtest

// Debug-build transport benchmark implementation.

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class RawBenchmarkConfig(
    val payloadBytes: Int = 16 * 1024,
    val windowPackets: Int = 4,
    val readBufferBytes: Int = 64 * 1024,
    val writeBufferBytes: Int = 0,
    val connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
    val cocChannels: Int = 1,
    val acknowledgeWindows: Boolean = true,
    val primeGattMtuForCoc: Boolean = false,
    val keepControlGattForCoc: Boolean = true,
) {
    init {
        require(payloadBytes in 0..MAX_PAYLOAD_BYTES)
        require(windowPackets in 1..MAX_WINDOW_PACKETS)
        require(readBufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES)
        require(writeBufferBytes == 0 || writeBufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES)
        require(cocChannels in 1..MAX_COC_CHANNELS)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        const val MAX_WINDOW_PACKETS = 64
        const val MAX_COC_CHANNELS = 4
        const val MIN_BUFFER_BYTES = 1024
        const val MAX_BUFFER_BYTES = 1024 * 1024
    }
}

class BluetoothRawBenchmark(
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit,
    private val config: RawBenchmarkConfig = RawBenchmarkConfig(),
) : Closeable {
    private val running = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var serverSocket: CocListenLease<BluetoothSocket>? = null
    private val sockets = Collections.synchronizedList(mutableListOf<BluetoothSocket>())
    @Volatile private var controlServer: BleL2capControlServer? = null
    @Volatile private var controlConnection: BleL2capControlConnection? = null
    @Volatile private var discoveryAdvertiser: BleDiscoveryAdvertiser? = null

    @SuppressLint("MissingPermission")
    fun startReceiver(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        phyPreference: BlePhyPreference,
    ) = start("RawBluetoothReceiver") {
        val activeServer = when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                discoveryAdvertiser = BleDiscoveryAdvertiser(
                    adapter,
                    benchmarkAnnouncement(transport),
                    onStatus,
                ).also(BleDiscoveryAdvertiser::start)
                DedicatedCocListener(
                    adapter.listenUsingInsecureRfcommWithServiceRecord("Bluetooth Raw Benchmark", CLASSIC_UUID),
                )
            }
            // One long-lived listener per role (CocListeners): Android 10 never frees a closed LE CoC server.
            BluetoothTransportOption.BLE_L2CAP -> CocListeners.listen(adapter, "raw-benchmark").also { server ->
                controlServer = BleL2capControlServer(
                    context,
                    adapter,
                    server.psm,
                    benchmarkAnnouncement(transport),
                    onStatus,
                    phyPreference,
                ).also(BleL2capControlServer::start)
            }
            BluetoothTransportOption.BLE_GATT -> error("Use the GATT benchmark for BLE GATT")
        }
        serverSocket = activeServer
        val channelCount = if (transport == BluetoothTransportOption.BLE_L2CAP) config.cocChannels else 1
        onStatus("RAW ${transport.shortLabel} RX ready for $channelCount channel(s)")
        val accepted = (1..channelCount).map { channel ->
            activeServer.accept().also { activeSocket ->
                sockets += activeSocket
                if (channelCount > 1) {
                    onStatus("RAW ${transport.shortLabel} RX accepted $channel/$channelCount")
                }
            }
        }
        controlServer?.stopAdvertising()
        discoveryAdvertiser?.close()
        if (accepted.size == 1) {
            receivePayload(accepted.single(), transport)
        } else {
            receivePayloads(accepted, transport)
        }
    }

    @SuppressLint("MissingPermission")
    fun startSender(
        context: Context,
        device: BluetoothDevice,
        transport: BluetoothTransportOption,
        durationSeconds: Int,
        phyPreference: BlePhyPreference,
    ) = start("RawBluetoothSender") {
        val activeSockets = when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> listOf(
                device.createInsecureRfcommSocketToServiceRecord(CLASSIC_UUID),
            )
            BluetoothTransportOption.BLE_L2CAP -> {
                val control = BleL2capPsmReader.open(
                    context,
                    device,
                    onStatus,
                    phyPreference,
                    config.connectionPriority,
                    config.primeGattMtuForCoc,
                )
                controlConnection = control
                List(config.cocChannels) { device.createInsecureL2capChannel(control.psm).also { CocRegistrations.note("connect", "raw-benchmark-connect") } }
            }
            BluetoothTransportOption.BLE_GATT -> error("Use the GATT benchmark for BLE GATT")
        }
        sockets += activeSockets
        activeSockets.forEachIndexed { index, activeSocket ->
            onStatus(
                "RAW ${transport.shortLabel} TX connecting ${index + 1}/${activeSockets.size} " +
                    "to ${device.name ?: device.address}",
            )
            activeSocket.connect()
        }
        if (transport == BluetoothTransportOption.BLE_L2CAP && !config.keepControlGattForCoc) {
            controlConnection?.close()
            controlConnection = null
            onStatus("RAW BLE CoC released the GATT setup connection")
        }
        if (activeSockets.size == 1) {
            sendPayload(activeSockets.single(), transport, durationSeconds.coerceIn(1, 120))
        } else {
            sendPayloads(activeSockets, transport, durationSeconds.coerceIn(1, 120))
        }
    }

    private fun start(name: String, block: () -> Unit) {
        check(running.compareAndSet(false, true)) { "Raw benchmark is already running" }
        worker = thread(name = name) {
            try {
                block()
            } catch (_: EOFException) {
                if (running.get()) onStatus("RAW benchmark peer disconnected")
            } catch (error: Exception) {
                if (running.get()) onStatus("RAW benchmark failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                running.set(false)
                closeResources()
                worker = null
                notifyFinished()
            }
        }
    }

    private data class PayloadResult(
        val bytes: Long,
        val startedNs: Long,
        val endedNs: Long,
    )

    private fun sendPayloads(
        activeSockets: List<BluetoothSocket>,
        transport: BluetoothTransportOption,
        durationSeconds: Int,
    ) {
        val results = Collections.synchronizedList(mutableListOf<PayloadResult>())
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val transfers = activeSockets.mapIndexed { index, activeSocket ->
            thread(name = "RawBluetoothSender-${index + 1}") {
                try {
                    results += sendPayload(activeSocket, transport, durationSeconds, " #${index + 1}")
                } catch (error: Exception) {
                    errors += error
                } finally {
                    closeSocket(activeSocket)
                }
            }
        }
        transfers.forEach(Thread::join)
        errors.firstOrNull()?.let { throw it }
        reportAggregate(transport, "TX", results)
    }

    private fun sendPayload(
        activeSocket: BluetoothSocket,
        transport: BluetoothTransportOption,
        durationSeconds: Int,
        channelLabel: String = "",
    ): PayloadResult {
        val socketOutput = activeSocket.outputStream
        val output = DataOutputStream(
            if (config.writeBufferBytes == 0) {
                socketOutput
            } else {
                BufferedOutputStream(socketOutput, config.writeBufferBytes)
            },
        )
        val input = DataInputStream(activeSocket.inputStream)
        val payloadBytes = if (config.payloadBytes == 0) {
            activeSocket.maxTransmitPacketSize.coerceAtLeast(1)
        } else {
            config.payloadBytes
        }
        onStatus(
            "RAW ${transport.shortLabel} TX$channelLabel socket max ${activeSocket.maxTransmitPacketSize}/" +
                "${activeSocket.maxReceivePacketSize} B; payload $payloadBytes B; " +
                if (config.acknowledgeWindows) {
                    "${config.windowPackets}-packet ACK window; write buffer ${config.writeBufferBytes} B; "
                } else {
                    "no app ACK; write buffer ${config.writeBufferBytes} B; "
                } +
                "priority ${config.connectionPriority.label}",
        )
        output.write(MAGIC)
        output.writeInt(durationSeconds)
        output.writeInt(payloadBytes)
        output.writeInt(if (config.acknowledgeWindows) config.windowPackets else 0)
        output.flush()
        val payload = benchmarkPayload(payloadBytes)
        val unacknowledgedPacket = if (config.acknowledgeWindows) {
            payload
        } else {
            ByteArray(payloadBytes + 1).also { packet ->
                packet[0] = NO_ACK_DATA_BYTE
                payload.copyInto(packet, 1)
            }
        }
        val startedNs = System.nanoTime()
        var reportNs = startedNs
        var reportBytes = 0L
        var totalBytes = 0L
        while (running.get() && System.nanoTime() - startedNs < durationSeconds * 1_000_000_000L) {
            val packets = if (config.acknowledgeWindows) config.windowPackets else 1
            repeat(packets) {
                output.write(unacknowledgedPacket)
                totalBytes += payload.size
                reportBytes += payload.size
            }
            if (config.acknowledgeWindows) {
                output.flush()
                require(input.read() == ACK_BYTE) { "Invalid raw benchmark acknowledgement" }
            }
            val now = System.nanoTime()
            if (now - reportNs >= REPORT_NS) {
                val intervalMbps = reportBytes * 8.0 * 1_000 / (now - reportNs)
                onStatus("RAW ${transport.shortLabel} TX$channelLabel %.3f Mbps, %.1f MB total".format(
                    intervalMbps,
                    totalBytes / 1_000_000.0,
                ))
                reportNs = now
                reportBytes = 0
            }
        }
        if (!config.acknowledgeWindows) {
            output.write(ByteArray(payloadBytes + 1).also { it[0] = NO_ACK_END_BYTE })
            output.flush()
            require(input.read() == ACK_BYTE) { "Invalid raw benchmark final acknowledgement" }
        }
        output.flush()
        val endedNs = System.nanoTime()
        val seconds = (endedNs - startedNs) / 1_000_000_000.0
        onStatus("RAW ${transport.shortLabel} TX$channelLabel FINAL %.3f Mbps, %.1f MB in %.1fs".format(
            totalBytes * 8 / seconds / 1_000_000,
            totalBytes / 1_000_000.0,
            seconds,
        ))
        return PayloadResult(totalBytes, startedNs, endedNs)
    }

    private fun receivePayloads(
        activeSockets: List<BluetoothSocket>,
        transport: BluetoothTransportOption,
    ) {
        val results = Collections.synchronizedList(mutableListOf<PayloadResult>())
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val transfers = activeSockets.mapIndexed { index, activeSocket ->
            thread(name = "RawBluetoothReceiver-${index + 1}") {
                try {
                    results += receivePayload(activeSocket, transport, " #${index + 1}")
                } catch (error: Exception) {
                    errors += error
                } finally {
                    closeSocket(activeSocket)
                }
            }
        }
        transfers.forEach(Thread::join)
        errors.firstOrNull()?.let { throw it }
        reportAggregate(transport, "RX", results)
    }

    private fun receivePayload(
        activeSocket: BluetoothSocket,
        transport: BluetoothTransportOption,
        channelLabel: String = "",
    ): PayloadResult {
        val input = DataInputStream(BufferedInputStream(activeSocket.inputStream, config.readBufferBytes))
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "Invalid raw benchmark header" }
        val durationSeconds = input.readInt()
        val payloadBytes = input.readInt()
        val windowPackets = input.readInt()
        require(
            durationSeconds in 1..120 && payloadBytes in 1..RawBenchmarkConfig.MAX_PAYLOAD_BYTES &&
                windowPackets in 0..RawBenchmarkConfig.MAX_WINDOW_PACKETS,
        ) {
            "Invalid raw benchmark parameters"
        }
        onStatus(
            "RAW ${transport.shortLabel} RX$channelLabel socket max ${activeSocket.maxTransmitPacketSize}/" +
                "${activeSocket.maxReceivePacketSize} B; payload $payloadBytes B; " +
                if (windowPackets == 0) "no app ACK; " else "$windowPackets ACK window; " +
                "read buffer ${config.readBufferBytes} B",
        )
        val output = DataOutputStream(activeSocket.outputStream)
        val buffer = ByteArray(if (windowPackets == 0) payloadBytes + 1 else payloadBytes)
        // Every packet carries the same deterministic bytes, so a lost, duplicated or reordered
        // stack packet shifts the stream and shows up here instead of as silent throughput.
        val expected = benchmarkPayload(payloadBytes)
        var verifiedPackets = 0L
        val startedNs = System.nanoTime()
        var reportNs = startedNs
        var reportBytes = 0L
        var totalBytes = 0L
        while (running.get()) {
            try {
                repeat(windowPackets.coerceAtLeast(1)) {
                    input.readFully(buffer)
                    if (windowPackets == 0) {
                        require(buffer[0] == NO_ACK_DATA_BYTE || buffer[0] == NO_ACK_END_BYTE) {
                            "Invalid raw benchmark packet"
                        }
                        if (buffer[0] == NO_ACK_END_BYTE) return@repeat
                        totalBytes += payloadBytes
                        reportBytes += payloadBytes
                    } else {
                        if (!buffer.contentEquals(expected)) {
                            val at = buffer.indices.first { buffer[it] != expected[it] }
                            throw IllegalStateException(
                                "INTEGRITY FAILED: packet ${verifiedPackets + 1} byte $at of $payloadBytes " +
                                    "after ${totalBytes / 1_024} KiB",
                            )
                        }
                        verifiedPackets++
                        totalBytes += buffer.size
                        reportBytes += buffer.size
                    }
                }
            } catch (_: IOException) {
                break
            }
            if (windowPackets == 0 && buffer[0] == NO_ACK_END_BYTE) {
                output.write(ACK_BYTE)
                output.flush()
                break
            }
            if (windowPackets > 0) {
                output.write(ACK_BYTE)
                output.flush()
            }
            val now = System.nanoTime()
            if (now - reportNs >= REPORT_NS) {
                val intervalMbps = reportBytes * 8.0 * 1_000 / (now - reportNs)
                onStatus("RAW ${transport.shortLabel} RX$channelLabel %.3f Mbps, %.1f MB total".format(
                    intervalMbps,
                    totalBytes / 1_000_000.0,
                ))
                reportNs = now
                reportBytes = 0
            }
        }
        val endedNs = System.nanoTime()
        val seconds = (endedNs - startedNs) / 1_000_000_000.0
        onStatus("RAW ${transport.shortLabel} RX$channelLabel FINAL %.3f Mbps, %.1f MB in %.1fs, %d packets verified".format(
            totalBytes * 8 / seconds / 1_000_000,
            totalBytes / 1_000_000.0,
            seconds,
            verifiedPackets,
        ))
        return PayloadResult(totalBytes, startedNs, endedNs)
    }

    private fun reportAggregate(
        transport: BluetoothTransportOption,
        direction: String,
        results: List<PayloadResult>,
    ) {
        if (results.isEmpty()) return
        val bytes = results.sumOf(PayloadResult::bytes)
        val startedNs = results.minOf(PayloadResult::startedNs)
        val endedNs = results.maxOf(PayloadResult::endedNs)
        val seconds = (endedNs - startedNs) / 1_000_000_000.0
        onStatus(
            "RAW ${transport.shortLabel} $direction AGGREGATE ${results.size} channels: " +
                "%.3f Mbps, %.1f MB in %.1fs".format(bytes * 8 / seconds / 1_000_000, bytes / 1_000_000.0, seconds),
        )
    }

    override fun close() {
        val wasRunning = running.getAndSet(false)
        closeResources()
        if (wasRunning) worker?.interrupt() else notifyFinished()
    }

    private fun notifyFinished() {
        if (finished.compareAndSet(false, true)) onFinished()
    }

    private fun closeResources() {
        val activeSockets = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        activeSockets.forEach { runCatching { it.close() } }
        runCatching { serverSocket?.close() }
        serverSocket = null
        controlServer?.close()
        controlServer = null
        controlConnection?.close()
        controlConnection = null
        discoveryAdvertiser?.close()
        discoveryAdvertiser = null
    }

    private fun closeSocket(activeSocket: BluetoothSocket) {
        sockets.remove(activeSocket)
        runCatching { activeSocket.close() }
    }

    companion object {
        val CLASSIC_UUID: UUID = UUID.fromString("46f9f010-cd63-46ac-a865-42aaadf7a767")
        const val DISCOVERY_TOKEN_CLASSIC: Int = 0x42545831
        const val DISCOVERY_TOKEN_BLE: Int = 0x42545832
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
        private const val ACK_BYTE = 0x06
        private const val NO_ACK_DATA_BYTE: Byte = 0x11
        private const val NO_ACK_END_BYTE: Byte = 0x12
        private const val REPORT_NS = 1_000_000_000L

        private fun benchmarkAnnouncement(transport: BluetoothTransportOption) = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS,
            capabilitySummary = 0,
            streamToken = if (transport == BluetoothTransportOption.BLE_L2CAP) {
                DISCOVERY_TOKEN_BLE
            } else {
                DISCOVERY_TOKEN_CLASSIC
            },
            routeCost = 0,
            sequence = 0,
        )

        private fun benchmarkPayload(size: Int): ByteArray {
            var state = 0x13579bdf
            return ByteArray(size) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                state.toByte()
            }
        }
    }
}
