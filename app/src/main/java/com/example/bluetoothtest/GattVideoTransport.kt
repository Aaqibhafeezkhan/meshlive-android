package com.example.bluetoothtest

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
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GattVideoConnection(
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val phySummary: () -> String,
    val receiveQueueState: () -> Pair<Int, Int>,
    val requestConnectionPriority: (BleConnectionPriority) -> Boolean = { false },
    private val closeAction: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        closeAction()
    }
}

internal class GattBytePipe(
    capacity: Int = PIPE_CHUNKS,
    private val onFailure: (String) -> Unit,
) : InputStream() {
    private data class Chunk(val bytes: ByteArray, val start: Int, val end: Int)

    private val chunks = LinkedBlockingQueue<Chunk>(capacity)
    private val capacity = capacity
    private val queuedBytes = java.util.concurrent.atomic.AtomicInteger()
    private val closed = AtomicBoolean(false)
    private var current = CLOSED_CHUNK
    private var offset = 0
    private var nextPacketSequence = 0
    private val recentPacketSequences = LinkedHashSet<Int>()
    private val pendingPackets = TreeMap<Int, ByteArray>()

    fun offer(bytes: ByteArray): Boolean = offer(bytes, 0, bytes.size)

    fun offer(bytes: ByteArray, start: Int, length: Int): Boolean {
        require(start >= 0 && length >= 0 && start + length <= bytes.size)
        if (closed.get() || length == 0) return false
        val ownedBytes = bytes.copyOfRange(start, start + length)
        queuedBytes.addAndGet(ownedBytes.size)
        if (!chunks.offer(Chunk(ownedBytes, 0, ownedBytes.size))) {
            queuedBytes.addAndGet(-ownedBytes.size)
            onFailure("input queue overflowed")
            close()
            return false
        }
        return true
    }

    @Synchronized
    fun offerPacket(packet: ByteArray): Boolean {
        if (closed.get()) return false
        if (packet.size < GATT_CHUNK_HEADER_BYTES || packet[0] != TYPE_DATA) {
            onFailure("received an invalid chunk header")
            close()
            return false
        }
        val sequence = decodeChunkSequence(packet)
        if (sequence in recentPacketSequences || sequence in pendingPackets) return true
        if (sequence > nextPacketSequence) {
            if (
                sequence - nextPacketSequence > MAX_REORDERED_PACKET_DISTANCE ||
                pendingPackets.size >= MAX_REORDERED_PACKETS
            ) {
                onFailure("received chunk $sequence; expected $nextPacketSequence")
                close()
                return false
            }
            pendingPackets[sequence] = packet.copyOf()
            return true
        }
        if (sequence != nextPacketSequence) {
            onFailure("received chunk $sequence; expected $nextPacketSequence")
            close()
            return false
        }
        if (!acceptNextPacket(packet)) return false
        while (true) {
            val pending = pendingPackets.remove(nextPacketSequence) ?: break
            if (!acceptNextPacket(pending)) return false
        }
        return true
    }

    private fun acceptNextPacket(packet: ByteArray): Boolean {
        if (recentPacketSequences.size >= RECENT_PACKET_HISTORY) {
            recentPacketSequences.remove(recentPacketSequences.first())
        }
        recentPacketSequences += nextPacketSequence
        nextPacketSequence++
        return offer(packet, GATT_CHUNK_HEADER_BYTES, packet.size - GATT_CHUNK_HEADER_BYTES)
    }

    fun queueState(): Pair<Int, Int> = chunks.size to capacity

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        require(targetOffset >= 0 && length >= 0 && targetOffset + length <= target.size)
        if (length == 0) return 0
        while (offset >= current.end) {
            current = try {
                chunks.take()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("GATT input interrupted", interrupted)
            }
            offset = current.start
            if (current === CLOSED_CHUNK) return -1
            queuedBytes.addAndGet(-(current.end - current.start))
        }
        val count = minOf(length, current.end - offset)
        current.bytes.copyInto(target, targetOffset, offset, offset + count)
        offset += count
        return count
    }

    /** Bytes already received but not yet read (lets link self-measurement exclude buffered data). */
    override fun available(): Int = (current.end - offset).coerceAtLeast(0) + queuedBytes.get().coerceAtLeast(0)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingPackets.clear()
        chunks.clear()
        chunks.offer(CLOSED_CHUNK)
    }

    private companion object {
        const val PIPE_CHUNKS = 512
        const val RECENT_PACKET_HISTORY = 4_096
        const val MAX_REORDERED_PACKETS = 4_096
        const val MAX_REORDERED_PACKET_DISTANCE = 4_096
        val CLOSED_CHUNK = Chunk(ByteArray(0), 0, 0)
    }
}

internal abstract class ChunkedGattOutputStream : OutputStream() {
    protected abstract val payloadBytes: Int
    protected abstract fun sendPacket(value: ByteArray)
    private var nextPacketSequence = 0

    @Synchronized
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, payloadBytes)
            val packet = ByteArray(count + GATT_CHUNK_HEADER_BYTES)
            packet[0] = TYPE_DATA
            encodeChunkSequence(nextPacketSequence, packet)
            bytes.copyInto(
                packet,
                GATT_CHUNK_HEADER_BYTES,
                sourceOffset,
                sourceOffset + count,
            )
            sendPacket(packet)
            nextPacketSequence++
            sourceOffset += count
            remaining -= count
        }
    }

    override fun flush() = Unit

    protected companion object {
        const val TYPE_DATA: Byte = 1
    }
}

private class GattClientOutputStream(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    mtu: Int,
    private val acknowledgedWrites: Boolean,
    onWriteTiming: ((String) -> Unit)?,
) : ChunkedGattOutputStream() {
    override val payloadBytes = gattChunkPayloadBytes(mtu, DebugTunables.experimentalAlignedGattWrites)
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeStatus = BluetoothGatt.GATT_FAILURE
    private val closed = AtomicBoolean(false)
    private var nextWriteAtNs = 0L
    private val writeTiming = onWriteTiming?.takeIf { BuildConfig.DEBUG }?.let(::GattWriteTiming)
    @Volatile private var writeCallbackAtNs = 0L

    fun onWrite(status: Int) {
        if (writeTiming != null) writeCallbackAtNs = System.nanoTime()
        writeStatus = status
        writeLatch?.countDown()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun sendPacket(value: ByteArray) {
        if (closed.get()) throw IOException("GATT output is closed")
        val writeType = if (acknowledgedWrites) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        var status = writeOnce(value, writeType)
        gattWriteFallbackType(acknowledgedWrites, status)?.let { fallbackType ->
            status = writeOnce(value, fallbackType)
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT video write failed (status $status)")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeOnce(value: ByteArray, writeType: Int): Int {
        paceWrite(value.size)
        val latch = CountDownLatch(1)
        writeLatch = latch
        writeStatus = BluetoothGatt.GATT_FAILURE
        val timing = writeTiming
        val startedNs = if (timing != null) System.nanoTime() else 0L
        if (timing != null) writeCallbackAtNs = 0L
        var apiReturnedNs: Long? = null
        var outcome = "aborted"
        try {
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeWhenNotBusy(value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
            if (timing != null) apiReturnedNs = System.nanoTime()
            if (!queued) {
                outcome = "rejected"
                throw IOException("GATT video write queue rejected a packet")
            }
            if (!latch.await(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                outcome = "timeout"
                throw IOException("GATT video write timed out")
            }
            writeLatch = null
            outcome = if (writeStatus == BluetoothGatt.GATT_SUCCESS) "success" else "status=$writeStatus"
            return writeStatus
        } finally {
            timing?.record(value.size, startedNs, apiReturnedNs, writeCallbackAtNs.takeIf { it != 0L },
                System.nanoTime(), outcome)
        }
    }

    /**
     * API 33+: the stack holds one write permit per peer address for every client and app
     * (GattService.writeCharacteristic). A busy return is retried briefly (registry entry
     * gatt-write-busy-retry) instead of failing the link; the write is the same, so order holds.
     */
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun writeWhenNotBusy(value: ByteArray, writeType: Int): Int {
        val outcome = retryBusyGattWrite(
            busyCode = BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            retry = busyRetry,
            write = { gatt.writeCharacteristic(characteristic, value, writeType) },
            sleepMs = { ms -> Thread.sleep(ms) },
            nowNs = System::nanoTime,
            closed = closed::get,
        )
        if (outcome.retries > 0) Log.i("BluetoothVideo", "GATT write busy: retries=${outcome.retries} | result=${outcome.result}")
        return outcome.result
    }

    private val busyRetry: GattWriteBusyRetry? =
        runCatching { BluetoothTransportPolicy.writeBusyRetry() }.getOrNull()

    private fun paceWrite(packetBytes: Int) {
        if (!acknowledgedWrites) return
        val nowNs = System.nanoTime()
        val waitNs = nextWriteAtNs - nowNs
        if (waitNs > 0L) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("GATT video write pacing interrupted", interrupted)
            }
        }
        nextWriteAtNs = maxOf(System.nanoTime(), nextWriteAtNs) +
            gattWritePacingIntervalNs(packetBytes, LEGACY_GATT_CALL_BYTES_PER_SECOND)
    }

    override fun close() {
        closed.set(true)
        writeLatch?.countDown()
    }
}

/**
 * ATT value bytes per GATT write or notification. By default the largest value the MTU allows,
 * capped at the 512-byte attribute maximum (MTU 517 -> 512). The ATT PDU (3-byte opcode/handle +
 * value) travels in an L2CAP PDU with a 4-byte header, and the link layer carries at most 251 octets
 * per data PDU with data length extension (Core Vol 6 Part B 4.5.10); an LL PDU never carries parts
 * of two L2CAP PDUs. A 512-byte value is a 519-byte L2CAP PDU: three LL PDUs of 251 + 251 + 17
 * octets, the last nearly empty but costing a full PDU slot and inter-frame spacing. Android 14+
 * always negotiates MTU 517 and ignores later MTU requests (Android 14 behaviour changes), so the
 * request MTU cannot fix this on newer clients. Android CoC is already aligned: senders cut K-frames
 * to the peer MPS - 4 (AOSP l2c_fcr.cc), and the MPS is one 251-octet LE buffer.
 *
 * Experimental (`test_gatt_aligned_writes`, off): size each value so its L2CAP PDU fills whole
 * 251-octet PDUs (495 bytes = 2 PDUs at MTU >= 498, 244 bytes = 1 PDU at MTU >= 247): 245 instead of
 * about 170 payload bytes per LL PDU, +12% airtime efficiency at 2M, up to +44% where a controller
 * caps PDUs per connection event. Links without data length extension (27-octet PDUs) are unaffected.
 */
internal fun gattValueBytes(mtu: Int, alignToLePdus: Boolean): Int {
    val maxValue = (mtu - ATT_OVERHEAD).coerceIn(1, MAX_ATTRIBUTE_BYTES)
    if (!alignToLePdus) return maxValue
    val wholePdus = (maxValue + ATT_OVERHEAD + L2CAP_BASIC_HEADER_BYTES) / LE_MAX_DATA_PDU_OCTETS
    return if (wholePdus >= 1) {
        wholePdus * LE_MAX_DATA_PDU_OCTETS - ATT_OVERHEAD - L2CAP_BASIC_HEADER_BYTES
    } else {
        maxValue
    }
}

/** App payload bytes per GATT packet after the 5-byte chunk header. */
internal fun gattChunkPayloadBytes(mtu: Int, alignToLePdus: Boolean): Int =
    (gattValueBytes(mtu, alignToLePdus) - GATT_CHUNK_HEADER_BYTES).coerceAtLeast(1)

internal fun gattWriteFallbackType(acknowledgedWrites: Boolean, status: Int): Int? =
    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE.takeIf {
        acknowledgedWrites && status == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
    }

internal fun gattWritePacingIntervalNs(packetBytes: Int, bytesPerSecond: Int): Long {
    require(packetBytes >= 0)
    require(bytesPerSecond > 0)
    return (packetBytes * 1_000_000_000L + bytesPerSecond - 1L) / bytesPerSecond
}

private class GattServerOutputStream(
    private val server: BluetoothGattServer,
    private val device: BluetoothDevice,
    private val characteristic: BluetoothGattCharacteristic,
    mtu: Int,
) : ChunkedGattOutputStream() {
    override val payloadBytes = gattChunkPayloadBytes(mtu, DebugTunables.experimentalAlignedGattWrites)
    @Volatile private var notifyLatch: CountDownLatch? = null
    @Volatile private var notifyStatus = BluetoothGatt.GATT_FAILURE
    private val closed = AtomicBoolean(false)

    fun onNotificationSent(status: Int) {
        notifyStatus = status
        notifyLatch?.countDown()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun sendPacket(value: ByteArray) {
        if (closed.get()) throw IOException("GATT output is closed")
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
        if (!latch.await(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("GATT notification timed out")
        notifyLatch = null
        if (notifyStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT notification failed (status $notifyStatus)")
        }
    }

    override fun close() {
        closed.set(true)
        notifyLatch?.countDown()
    }
}

object GattVideoClient {
    fun open(
        context: Context,
        device: BluetoothDevice,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
        onStatus: (String) -> Unit,
        acknowledgedWrites: Boolean = false,
        onWriteTiming: ((String) -> Unit)? = null,
        deferConnectionPriority: Boolean = false,
    ): GattVideoConnection {
        var attempt = 1
        var extendedRetries = 0
        while (true) {
            try {
                return openOnce(
                    context,
                    device,
                    phyPreference,
                    connectionPriority,
                    onStatus,
                    acknowledgedWrites,
                    onWriteTiming,
                    deferConnectionPriority,
                )
            } catch (error: IOException) {
                val retryable = runCatching { BluetoothTransportPolicy.retryableConnectStatuses() }
                    .getOrDefault(setOf(BluetoothTransportPolicy.GATT_ERROR_133))
                val allowed = leSetupRetryAllowed(
                    message = error.message,
                    attemptsUsed = attempt,
                    maxAttempts = GATT_OPEN_ATTEMPTS,
                    classifiedRetryable = isRetryableGattVideoFailure(error.message, retryable),
                    extendedRetriesUsed = extendedRetries,
                    nextAttemptMs = GATT_OPEN_RETRY_DELAY_MS + TimeUnit.SECONDS.toMillis(CONNECTION_TIMEOUT_SECONDS),
                    remainingBudgetMs = LeSetupBudget.remainingMs(),
                )
                if (!allowed) throw error
                if (leSetupStatus(error.message)?.let { it != BluetoothTransportPolicy.GATT_ERROR_133 } == true) extendedRetries++
                attempt += 1
                onStatus(leSetupRetryNotice("GATT video", error.message, "retrying once"))
                Thread.sleep(GATT_OPEN_RETRY_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openOnce(
        context: Context,
        device: BluetoothDevice,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
        onStatus: (String) -> Unit,
        acknowledgedWrites: Boolean,
        onWriteTiming: ((String) -> Unit)?,
        deferConnectionPriority: Boolean = false,
    ): GattVideoConnection {
        val result = AtomicReference<GattVideoConnection?>()
        val error = AtomicReference<String?>()
        val complete = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val connectionSetupStarted = AtomicBoolean(false)
        val serviceDiscoveryStarted = AtomicBoolean(false)
        val serviceConfigured = AtomicBoolean(false)
        val cacheRefreshAttempted = AtomicBoolean(false)
        val deferPriority = BuildConfig.DEBUG && deferConnectionPriority
        val deferredPriorityRequested = if (deferPriority) AtomicBoolean(false) else null
        val setupStartedNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val setupAttemptId = if (BuildConfig.DEBUG) UUID.randomUUID().toString() else ""
        val setupStage = if (BuildConfig.DEBUG) AtomicReference("created") else null
        val setupLogCount = if (BuildConfig.DEBUG) AtomicInteger(0) else null
        val latch = CountDownLatch(1)
        val mtuRequestedAtNs = AtomicLong(0L)
        // MTU wait verdicts (GattMtuWait): queued behind the platform's discovery vs dropped.
        val mtuWait = GattMtuWait(
            probeEnabled = DebugTunables.gattMtuProbe && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        )
        val teardown = GattTeardownSignal()
        val input = GattBytePipe { failure -> onStatus("GATT video input $failure") }
        var mtu = DEFAULT_MTU
        var phySummary = "PHY pending"
        var gatt: BluetoothGatt? = null
        var output: GattClientOutputStream? = null
        val adaptivePhyRef = AtomicReference<AdaptivePhyLink?>()
        fun adaptivePhy(gatt: BluetoothGatt): AdaptivePhyLink {
            adaptivePhyRef.get()?.let { return it }
            adaptivePhyRef.compareAndSet(null, AdaptivePhyLink("gatt", gatt.device?.address, gatt, restorePriority = connectionPriority))
            LeChildInterval.register(gatt.device?.address, gatt)
            return adaptivePhyRef.get()!!
        }
        val activeGattRef = AtomicReference<BluetoothGatt?>()
        // One requested-vs-observed PHY/MTU/priority/interval line (BluetoothTransportPolicy).
        val linkDiag = LeLinkDiagnostics.create("gatt-video", device.address).also { it.requestedPhy = phyPreference.label }
        val priorityKeeper = LeConnectionPriorityKeeper(
            link = "gatt-video",
            sendRequest = { priority -> activeGattRef.get()?.requestConnectionPriority(priority.requestValue) == true },
            schedule = { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
            active = { !cancelled.get() && !teardown.disconnected },
            log = { line -> Log.i("BluetoothVideo", line) },
        )

        fun logSetup(stage: String, details: String = "", terminal: Boolean = false) {
            val lastStage = setupStage ?: return
            if (!terminal && complete.get()) return
            val previousStage = lastStage.getAndSet(stage)
            if (!terminal && setupLogCount!!.getAndIncrement() >= 64) return
            Log.i(
                "GattVideoSetup",
                "attempt=$setupAttemptId elapsedMs=${(System.nanoTime() - setupStartedNs) / 1_000_000L} " +
                    "stage=$stage last=$previousStage $details",
            )
        }

        fun finish(connection: GattVideoConnection? = null, message: String? = null) {
            if (cancelled.get()) return
            if (!complete.compareAndSet(false, true)) return
            result.set(connection)
            error.set(message)
            logSetup("finish", "success=${connection != null} failure=${message ?: "none"}", terminal = true)
            latch.countDown()
        }

        fun discoverServicesOnce(activeGatt: BluetoothGatt) {
            if (cancelled.get()) return
            if (serviceDiscoveryStarted.compareAndSet(false, true)) {
                logSetup("services_request", "cacheRefresh=false")
                val queued = activeGatt.discoverServices()
                logSetup("services_return", "cacheRefresh=false queued=$queued")
                if (!queued) finish(message = "Could not discover GATT video service")
            }
        }

        val callback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) teardown.onDisconnected()
                if (cancelled.get()) return
                logSetup("connection_callback", "status=$status state=$newState")
                when {
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        input.close()
                        output?.close()
                        if (!complete.get()) {
                            finish(message = "GATT video receiver disconnected (status $status)")
                        }
                    }
                    status != BluetoothGatt.GATT_SUCCESS -> finish(message = "GATT video connection failed (status $status)")
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        if (!connectionSetupStarted.compareAndSet(false, true)) return
                        activeGattRef.set(activeGatt)
                        if (!deferPriority) {
                            logSetup("priority_request", "value=${connectionPriority.requestValue} placement=connected")
                            val priorityQueued = priorityKeeper.request(connectionPriority)
                            linkDiag.onPriority(connectionPriority, priorityQueued)
                            logSetup("priority_return", "queued=$priorityQueued")
                            onStatus(
                                "GATT video requested ${connectionPriority.label} priority " +
                                    if (priorityQueued) "queued" else "failed",
                            )
                        }
                        // PHY update is deferred until the subscription completes: a PHY procedure
                        // started beside the connection update and the peer's own PHY request
                        // collided (status 42) and left a legacy stack without any callback.
                        // Experimental DEBUG `test_gatt_request_mtu` (default 517).
                        val requestedMtu = DebugTunables.gattRequestMtu ?: MAX_MTU
                        logSetup("mtu_request", "requested=$requestedMtu")
                        linkDiag.requestedMtu = requestedMtu
                        mtuRequestedAtNs.set(System.nanoTime())
                        mtuWait.onRequested(System.nanoTime() / 1_000_000L)
                        val mtuQueued = activeGatt.requestMtu(requestedMtu)
                        logSetup("mtu_return", "queued=$mtuQueued")
                        if (!mtuQueued) {
                            mtuRequestedAtNs.set(0L)
                            discoverServicesOnce(activeGatt)
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(activeGatt: BluetoothGatt, newMtu: Int, status: Int) {
                if (cancelled.get()) return
                mtuRequestedAtNs.set(0L)
                mtuWait.onMtuCallback()
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_MTU
                linkDiag.onMtu(mtu, status)
                logSetup("mtu_callback", "status=$status reported=$newMtu effective=$mtu")
                onStatus("GATT video MTU $mtu")
                discoverServicesOnce(activeGatt)
            }

            override fun onPhyUpdate(activeGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                if (cancelled.get()) return
                mtuWait.onPlatformCallback(System.nanoTime() / 1_000_000L)
                logSetup("phy_update_callback", "status=$status tx=$txPhy rx=$rxPhy")
                linkDiag.onPhy(txPhy, rxPhy, status)
                if (complete.get()) linkDiag.log("phy_update")
                phySummary = phySummary(txPhy, rxPhy, status)
                adaptivePhy(activeGatt).onPhy(txPhy, status)
                onStatus("GATT video $phySummary")
            }

            override fun onPhyRead(activeGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                if (cancelled.get()) return
                mtuWait.onPlatformCallback(System.nanoTime() / 1_000_000L)
                logSetup("phy_read_callback", "status=$status tx=$txPhy rx=$rxPhy")
                linkDiag.onPhy(txPhy, rxPhy, status)
                if (complete.get()) linkDiag.log("phy_read")
                phySummary = phySummary(txPhy, rxPhy, status)
                adaptivePhy(activeGatt).onPhy(txPhy, status)
                onStatus("GATT video $phySummary")
            }

            /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
            @Suppress("unused")
            fun onConnectionUpdated(activeGatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
                LeConnectionParameterLog.record(
                    "gatt-video", LeLinkRole.CENTRAL, activeGatt.device?.address, interval, latency, timeout, status,
                )
                if (!cancelled.get()) {
                    logSetup("conn_params_callback", "intervalUnits=$interval latency=$latency timeout=$timeout status=$status")
                    linkDiag.onParams(interval, latency, timeout, status)
                    if (complete.get()) linkDiag.log("conn_params")
                }
                priorityKeeper.onConnectionUpdated(status)
            }

            @Suppress("DEPRECATION")
            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
                if (cancelled.get()) return
                logSetup("services_callback", "status=$status")
                if (!serviceConfigured.compareAndSet(false, true)) return
                val service = activeGatt.getService(GATT_DATA_SERVICE_UUID)
                val writeCharacteristic = service?.getCharacteristic(WRITE_UUID)
                val notifyCharacteristic = service?.getCharacteristic(NOTIFY_UUID)
                val descriptor = notifyCharacteristic?.getDescriptor(CCCD_UUID)
                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    (writeCharacteristic == null || notifyCharacteristic == null || descriptor == null) &&
                    cacheRefreshAttempted.compareAndSet(false, true) &&
                    refreshGattServices(activeGatt)
                ) {
                    onStatus("Refreshing a stale GATT service cache")
                    logSetup("cache_refresh", "scheduled=true")
                    serviceConfigured.set(false)
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            if (!cancelled.get() && !complete.get()) {
                                logSetup("services_request", "cacheRefresh=true")
                                val queued = activeGatt.discoverServices()
                                logSetup("services_return", "cacheRefresh=true queued=$queued")
                                if (!queued) finish(message = "Could not rediscover the GATT video service")
                            }
                        },
                        GATT_CACHE_REFRESH_DELAY_MS,
                    )
                    return
                }
                if (
                    status != BluetoothGatt.GATT_SUCCESS ||
                    writeCharacteristic == null ||
                    notifyCharacteristic == null ||
                    descriptor == null
                ) {
                    finish(message = "GATT video service discovery failed (status $status)")
                    return
                }
                output = GattClientOutputStream(activeGatt, writeCharacteristic, mtu, acknowledgedWrites, onWriteTiming)
                logSetup("notification_request", "enabled=true")
                val notificationsEnabled = activeGatt.setCharacteristicNotification(notifyCharacteristic, true)
                logSetup("notification_return", "enabled=$notificationsEnabled")
                if (!notificationsEnabled) {
                    finish(message = "Could not enable GATT video notifications")
                    return
                }
                logSetup("descriptor_request", "cccd=true")
                val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activeGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    activeGatt.writeDescriptor(descriptor)
                }
                logSetup("descriptor_return", "queued=$queued")
                if (!queued) finish(message = "Could not subscribe to GATT video data")
            }

            override fun onDescriptorWrite(
                activeGatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (cancelled.get()) return
                logSetup("descriptor_callback", "cccd=${descriptor.uuid == CCCD_UUID} status=$status")
                if (descriptor.uuid != CCCD_UUID || status != BluetoothGatt.GATT_SUCCESS) {
                    finish(message = "GATT video subscription failed (status $status)")
                    return
                }
                val activeOutput = output ?: return
                if (!complete.get() && deferredPriorityRequested?.compareAndSet(false, true) == true) {
                    logSetup("priority_request", "value=${connectionPriority.requestValue} placement=subscribed")
                    val priorityQueued = priorityKeeper.request(connectionPriority)
                    linkDiag.onPriority(connectionPriority, priorityQueued)
                    logSetup("priority_return", "queued=$priorityQueued")
                    onStatus(
                        "GATT video requested ${connectionPriority.label} priority " +
                            if (priorityQueued) "queued" else "failed",
                    )
                }
                if (!complete.get()) {
                    logSetup("phy_update_request", "mask=${phyPreference.mask} option=${phyPreference.option} placement=subscribed")
                    activeGatt.setPreferredPhy(phyPreference.mask, phyPreference.mask, phyPreference.option)
                    activeGatt.readPhy()
                }
                linkDiag.log("setup")
                finish(
                    GattVideoConnection(
                        input,
                        AdaptivePhyOutputStream(activeOutput, adaptivePhy(activeGatt)),
                        { phySummary },
                        input::queueState,
                        { priority -> priorityKeeper.request(priority) },
                    ) {
                        cancelled.set(true)
                        // Android 10/11: close only after the disconnect finished (GattClientTeardown).
                        GattClientTeardown.disconnectAndClose(activeGatt, teardown, "gatt-video")
                    },
                )
            }

            override fun onCharacteristicWrite(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (cancelled.get()) return
                if (characteristic.uuid == WRITE_UUID) output?.onWrite(status)
            }

            @Deprecated("Deprecated in Android")
            override fun onCharacteristicChanged(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (cancelled.get()) return
                if (characteristic.uuid != NOTIFY_UUID) return
                val callbackValue = characteristic.value.copyOf()
                handleIncoming(input, callbackValue)
            }

            override fun onCharacteristicChanged(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (cancelled.get()) return
                if (characteristic.uuid == NOTIFY_UUID) handleIncoming(input, value)
            }
        }

        return awaitGattSetup(
            awaitCompletion = {
                logSetup("connect_request", "deferPriority=$deferPriority")
                gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: run {
                        logSetup("connect_return", "created=false")
                        throw IOException("Could not start GATT video connection")
                    }
                logSetup("connect_return", "created=true")
                val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECTION_TIMEOUT_SECONDS)
                var completed = false
                while (!completed && System.nanoTime() < deadlineNs) {
                    completed = latch.await(SETUP_POLL_MS, TimeUnit.MILLISECONDS)
                    val mtuRequested = mtuRequestedAtNs.get()
                    if (!completed && mtuRequested != 0L) {
                        val nowMs = System.nanoTime() / 1_000_000L
                        val waitedMs = (System.nanoTime() - mtuRequested) / 1_000_000L
                        when (val verdict = mtuWait.poll(nowMs)) {
                            GattMtuWait.Verdict.WAIT -> Unit
                            GattMtuWait.Verdict.SEND_PROBE -> {
                                // A controller read: it bypasses the platform's queued discovery, and its
                                // callback needs the same client record the MTU request needs.
                                val probing = activeGattRef.get()
                                if (probing == null) {
                                    mtuWait.onProbeUnavailable()
                                } else {
                                    logSetup("mtu_probe_request", "waitedMs=$waitedMs")
                                    mtuWait.onProbeSent(nowMs)
                                    runCatching { probing.readPhy() }.onFailure { mtuWait.onProbeUnavailable() }
                                }
                            }
                            else -> {
                                logSetup(
                                    "mtu_timeout",
                                    "waitedMs=$waitedMs reason=$verdict platformAlive=${mtuWait.platformAlive} " +
                                        "probeSent=${mtuWait.probeSent}",
                                )
                                finish(message = GATT_MTU_STALLED_MESSAGE)
                                completed = true
                            }
                        }
                    }
                }
                if (!completed) logSetup("await_timeout", "deadlineSeconds=$CONNECTION_TIMEOUT_SECONDS")
                completed
            },
            connection = result::get,
            failure = error::get,
            closeFailedSetup = {
                cancelled.set(true)
                complete.set(true)
                logSetup("cancel", "connectionPresent=${result.get() != null}", terminal = true)
                input.close()
                output?.close()
                gatt?.let { GattClientTeardown.disconnectAndClose(it, teardown, "gatt-video-setup") }
            },
        )
    }

    private const val GATT_OPEN_ATTEMPTS = 2
    private const val GATT_OPEN_RETRY_DELAY_MS = 500L
}

internal fun awaitGattSetup(
    awaitCompletion: () -> Boolean,
    connection: () -> GattVideoConnection?,
    failure: () -> String?,
    closeFailedSetup: () -> Unit,
): GattVideoConnection {
    var handedOff = false
    try {
        if (!awaitCompletion()) throw IOException("Timed out connecting GATT video")
        val accepted = connection() ?: throw IOException(failure() ?: "Could not connect GATT video")
        handedOff = true
        return accepted
    } finally {
        if (!handedOff) closeFailedSetup()
    }
}

internal data class BusyWriteOutcome(val result: Int, val retries: Int)

/**
 * Issues one GATT write and repeats the same write while the stack answers [busyCode], every
 * [GattWriteBusyRetry.stepMs] until [GattWriteBusyRetry.maxWaitMs] (registry gatt-write-busy-retry).
 * Without [retry] the first answer is final (pre-audit behaviour). Never reorders or skips data.
 */
internal fun retryBusyGattWrite(
    busyCode: Int,
    retry: GattWriteBusyRetry?,
    write: () -> Int,
    sleepMs: (Long) -> Unit,
    nowNs: () -> Long,
    closed: () -> Boolean,
): BusyWriteOutcome {
    var result = write()
    if (result != busyCode || retry == null) return BusyWriteOutcome(result, 0)
    val deadlineNs = nowNs() + TimeUnit.MILLISECONDS.toNanos(retry.maxWaitMs)
    var retries = 0
    while (result == busyCode && nowNs() < deadlineNs && !closed()) {
        try {
            sleepMs(retry.stepMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
        retries++
        result = write()
    }
    return BusyWriteOutcome(result, retries)
}

internal const val GATT_MTU_STALLED_MESSAGE = "GATT video MTU exchange stalled"

/**
 * [retryableStatuses] defaults to the pre-audit rule (133 only); production passes
 * BluetoothTransportPolicy.retryableConnectStatuses() (registry entry le-connect-retry-link-statuses).
 */
internal fun isRetryableGattVideoFailure(
    message: String?,
    retryableStatuses: Set<Int> = setOf(BluetoothTransportPolicy.GATT_ERROR_133),
): Boolean =
    message == GATT_MTU_STALLED_MESSAGE ||
        isRetryableLeSetupStatus(message, retryableStatuses, GATT_VIDEO_CONNECTION_FAILURES)

private val GATT_VIDEO_CONNECTION_FAILURES =
    listOf("GATT video connection failed (status ", "GATT video receiver disconnected (status ")

class GattVideoServer(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val announcement: DiscoveryAnnouncement,
    private val phyPreference: BlePhyPreference,
    private val onStatus: (String) -> Unit,
    private val onConnection: (GattVideoConnection) -> Unit,
) : Closeable {
    /**
     * Dual listening (DualListening.kt): false registers the service without any advertising set, so a CoC
     * endpoint's one connectable set stays the only one and a GATT central reaches this service at that
     * address. True (the default, every other caller): advertises as always. Set before [start].
     */
    @Volatile internal var advertise: Boolean = true

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val advertiseHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var serviceLatch: CountDownLatch? = null
    private var serviceStatus = BluetoothGatt.GATT_FAILURE
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var activeDevice: BluetoothDevice? = null
    @Volatile private var input: GattBytePipe? = null
    @Volatile private var output: GattServerOutputStream? = null
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var currentPhySummary = "PHY pending"
    @Volatile private var advertising = false

    /** Keeps its random address through the low-latency -> balanced step (StableAdvertisingSet). */
    @Volatile private var advertisement = newAdvertisement(announcement)

    private fun newAdvertisement(value: DiscoveryAnnouncement) = StableAdvertisingSet(
        adapter,
        connectable = true,
        data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleL2capControlProtocol.SERVICE_UUID))
            .build(),
        scanResponse = AdvertiseData.Builder()
            .addManufacturerData(
                BleL2capControlProtocol.MESH_MANUFACTURER_ID,
                DiscoveryAnnouncementCodec.encode(value),
            )
            .build(),
        label = "GATT video",
        onStatus = onStatus,
        onLost = ::onAdvertisingLost,
        nonDiscoverable = NonDiscoverableAdvertising.forNewSet(connectable = true),
    )

    /**
     * Rotating IDs (RV6): while this server still advertises and no peer is connected, stop the set, stay
     * silent for [silenceMs] and advertise [next] on a new set (new address). The GATT service stays.
     */
    @SuppressLint("MissingPermission")
    fun reannounce(next: DiscoveryAnnouncement, silenceMs: Long) {
        if (closed.get() || !advertise) return
        val wasAdvertising = synchronized(this) {
            val advertisingNow = advertising && activeDevice == null
            if (advertisingNow) stopAdvertising()
            advertisement = newAdvertisement(next)
            advertisingNow
        }
        if (!wasAdvertising) return
        try {
            Thread.sleep(silenceMs)
        } catch (_: InterruptedException) {
            return
        }
        if (closed.get() || activeDevice != null) return
        runCatching { startAdvertising(lowLatency = false) }
            .onFailure { onStatus("GATT video could not advertise the new ID: ${it.message}") }
    }

    private val mtuByAddress = HashMap<String, Int>()

    private fun rememberMtu(address: String, value: Int) = synchronized(mtuByAddress) {
        if (mtuByAddress.size >= MAX_REMEMBERED_MTUS) mtuByAddress.clear()
        mtuByAddress[address] = value
    }

    private fun rememberedMtu(address: String): Int = synchronized(mtuByAddress) { mtuByAddress[address] } ?: DEFAULT_MTU

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != GATT_DATA_SERVICE_UUID) return
            serviceStatus = status
            serviceLatch?.countDown()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (closed.get()) return
            if (
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED &&
                gattServerOwnsCallback(activeDevice?.address, device.address)
            ) {
                // The client requests the preferred PHY after subscribing; a simultaneous
                // server-initiated update collided with it (status 42).
                server?.readPhy(device)
            } else if (
                newState == BluetoothProfile.STATE_DISCONNECTED &&
                activeDevice?.address == device.address
            ) {
                closeConnection()
                if (advertise && !closed.get() && !advertising) thread(name = "GattVideoReadvertise") {
                    runCatching { startAdvertising() }
                        .onFailure { onStatus("GATT video could not advertise again: ${it.message}") }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            if (closed.get()) return
            // A non-advertising second server hears every central that connects to the phone (AOSP tells all
            // registered apps, gatt_send_conn_cback), so it keeps the MTU per device and takes the admitted one.
            if (!advertise) rememberMtu(device.address, newMtu)
            if (!gattServerOwnsCallback(activeDevice?.address, device.address)) return
            mtu = newMtu
            onStatus("GATT video RX MTU $newMtu")
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            if (closed.get() || !gattServerOwnsCallback(activeDevice?.address, device.address)) return
            currentPhySummary = phySummary(txPhy, rxPhy, status)
            onStatus("GATT video RX $currentPhySummary")
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) =
            onPhyUpdate(device, txPhy, rxPhy, status)

        /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            if (closed.get()) return
            LeConnectionParameterLog.record(
                "gatt-video", LeLinkRole.PERIPHERAL, device.address, interval, latency, timeout, status,
            )
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
            val valid = !closed.get() && activeDevice == null && descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic.uuid == NOTIFY_UUID &&
                !preparedWrite && offset == 0 &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0,
                    null,
                )
            }
            if (!valid) return
            activeDevice = device
            stopAdvertising()
            if (!advertise) {
                // Events heard before admission belonged to other centrals: start from this device's own state.
                mtu = rememberedMtu(device.address)
                currentPhySummary = "PHY pending"
                server?.readPhy(device)
            }
            val characteristic = server?.getService(GATT_DATA_SERVICE_UUID)
                ?.getCharacteristic(NOTIFY_UUID) ?: return
            val activeInput = GattBytePipe { failure ->
                onStatus("GATT video receiver $failure")
            }
            val activeServer = server ?: return
            val activeOutput = GattServerOutputStream(activeServer, device, characteristic, mtu)
            input = activeInput
            output = activeOutput
            onConnection(
                GattVideoConnection(
                    activeInput,
                    activeOutput,
                    { currentPhySummary },
                    activeInput::queueState,
                ) {
                    runCatching { activeServer.cancelConnection(device) }
                },
            )
        }

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
            val peerMatches = activeDevice?.address == device.address
            val requestSupported = characteristic.uuid == WRITE_UUID && !preparedWrite && offset == 0
            val activeInput = input
            val valid = peerMatches && requestSupported && handleIncoming(activeInput, value)
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0,
                    null,
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (activeDevice?.address == device.address) output?.onNotificationSent(status)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        check(!closed.get()) { "GATT video server is closed" }
        val activeServer = bluetoothManager.openGattServer(appContext, callback)
            ?: throw IOException("Could not open GATT video server")
        server = activeServer
        val writeCharacteristic = BluetoothGattCharacteristic(
                WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notifyCharacteristic = BluetoothGattCharacteristic(
            NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val service = BluetoothGattService(
            GATT_DATA_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(writeCharacteristic)
            addCharacteristic(notifyCharacteristic)
        }
        val latch = CountDownLatch(1)
        serviceLatch = latch
        if (!activeServer.addService(service) || !latch.await(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Could not register GATT video service")
        }
        serviceLatch = null
        if (serviceStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT video service failed to register (status $serviceStatus)")
        }
        if (!advertise) {
            onStatus("GATT video service is ready (the CoC control server advertises)")
            return
        }
        startAdvertising()
        onStatus("GATT video receiver is advertising")
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() = startAdvertising(lowLatency = true)

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAdvertising(lowLatency: Boolean) {
        // The one funnel of every advertising entry point: a dual-listening second server never advertises.
        if (closed.get() || advertising || !advertise) return
        val status = advertisement.start(lowLatency, CONTROL_TIMEOUT_SECONDS)
            ?: throw IOException("Timed out advertising GATT video")
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            throw IOException("GATT video advertising failed (code $status)")
        }
        if (closed.get()) {
            advertisement.stop()
            return
        }
        advertising = true
        if (lowLatency && !closed.get()) {
            advertiseHandler.postDelayed(::switchToBalancedAdvertising, LOW_LATENCY_ADVERTISE_BURST_MS)
        }
    }

    /** A failed fallback left nothing advertising: clear the state and start once more. */
    private fun onAdvertisingLost(reason: String) {
        advertising = false
        if (closed.get() || activeDevice != null) return
        thread(name = "GattVideoReadvertise") {
            runCatching { startAdvertising(lowLatency = false) }
                .onFailure { onStatus("GATT video is not advertising ($reason); restart failed: ${it.message}") }
        }
    }

    private fun switchToBalancedAdvertising() {
        if (closed.get() || activeDevice != null) return
        // Same set, same address: a peer that just discovered it can still connect.
        if (DebugTunables.stableAdvertisingAddress && advertisement.switchToBalanced()) return
        thread(name = "GattVideoAdvertiseBalance") {
            runCatching {
                stopAdvertising()
                startAdvertising(lowLatency = false)
            }.onFailure { onStatus("GATT video balanced advertising failed: ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stopAdvertising() {
        if (!advertising) return
        advertisement.stop()
        advertising = false
    }

    private fun closeConnection() {
        input?.close()
        input = null
        output?.close()
        output = null
        activeDevice = null
        mtu = DEFAULT_MTU
        currentPhySummary = "PHY pending"
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        advertiseHandler.removeCallbacksAndMessages(null)
        stopAdvertising()
        activeDevice?.let { device -> runCatching { server?.cancelConnection(device) } }
        closeConnection()
        runCatching { server?.close() }
        server = null
    }
}

internal fun gattServerOwnsCallback(admittedPeerAddress: String?, callbackPeerAddress: String): Boolean =
    admittedPeerAddress == null || admittedPeerAddress == callbackPeerAddress

internal fun handleIncoming(pipe: GattBytePipe?, value: ByteArray): Boolean {
    if (pipe == null || value.isEmpty()) return false
    return when (value[0]) {
        TYPE_DATA -> pipe.offerPacket(value)
        2.toByte() -> true
        else -> false
    }
}

internal fun refreshGattServices(gatt: BluetoothGatt): Boolean = runCatching {
    BluetoothGatt::class.java.getMethod("refresh").invoke(gatt) as? Boolean ?: false
}.getOrDefault(false)

private fun encodeChunkSequence(sequence: Int, target: ByteArray) {
    target[1] = (sequence ushr 24).toByte()
    target[2] = (sequence ushr 16).toByte()
    target[3] = (sequence ushr 8).toByte()
    target[4] = sequence.toByte()
}

private fun decodeChunkSequence(source: ByteArray): Int =
    ((source[1].toInt() and 0xff) shl 24) or
        ((source[2].toInt() and 0xff) shl 16) or
        ((source[3].toInt() and 0xff) shl 8) or
        (source[4].toInt() and 0xff)

private fun phySummary(txPhy: Int, rxPhy: Int, status: Int): String =
    if (status == BluetoothGatt.GATT_SUCCESS) {
        "PHY TX ${phyName(txPhy)} / RX ${phyName(rxPhy)}"
    } else {
        "PHY unavailable (status $status)"
    }

private fun phyName(phy: Int): String = when (phy) {
    BluetoothDevice.PHY_LE_1M -> "1M"
    BluetoothDevice.PHY_LE_2M -> "2M"
    BluetoothDevice.PHY_LE_CODED -> "Coded"
    else -> "unknown($phy)"
}

private val WRITE_UUID: UUID = UUID.fromString("c8d5a103-9f88-4f65-a03d-7f5a2fb921fa")
private val NOTIFY_UUID: UUID = UUID.fromString("c8d5a104-9f88-4f65-a03d-7f5a2fb921fa")
private val GATT_DATA_SERVICE_UUID: UUID = UUID.fromString("c8d5a105-9f88-4f65-a03d-7f5a2fb921fa")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val DEFAULT_MTU = 23
private const val MAX_REMEMBERED_MTUS = 8
private const val MAX_MTU = 517
private const val ATT_OVERHEAD = 3
private const val MAX_ATTRIBUTE_BYTES = 512
private const val L2CAP_BASIC_HEADER_BYTES = 4
/** Largest LE data PDU payload with data length extension (Core Vol 6 Part B 4.5.10). */
private const val LE_MAX_DATA_PDU_OCTETS = 251
internal const val GATT_CHUNK_HEADER_BYTES = 5
private const val TYPE_DATA: Byte = 1
private const val IO_TIMEOUT_SECONDS = 8L
private const val CONNECTION_TIMEOUT_SECONDS = 12L
private const val SETUP_POLL_MS = 250L
private const val CONTROL_TIMEOUT_SECONDS = 8L
private const val LOW_LATENCY_ADVERTISE_BURST_MS = 10_000L
private const val LEGACY_GATT_CALL_BYTES_PER_SECOND = 24_000
private const val GATT_CACHE_REFRESH_DELAY_MS = 300L
