package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

enum class SecureRecordType(val wireValue: Int) {
    END_TO_END_HANDSHAKE(4),
    END_TO_END_CONTENT(5),
    LINK_HEARTBEAT(6),
    MESH_ENVELOPE(7),
    GROUP_CONTROL(8),
    ROUTE_CONTROL(9),
    ROUTE_CONTROL_ACK(10),
    /**
     * Experimental store-and-forward `BSF1` messages (off by default, PROTOCOL "Store-and-forward
     * messages"). A reader rejects this type as unknown unless its owner explicitly opted in with
     * [SecureRecordReader.acceptStoreForward], so every existing call/stream reader is unchanged.
     */
    STORE_FORWARD(11),

    /**
     * Experimental rotating discovery IDs: the link opening `BDO1` (PROTOCOL "Experimental rotating
     * discovery IDs"). Sent only as the first hop record in each direction when both link hellos
     * carry transport bit 3; a reader rejects it as unknown unless [SecureRecordReader.acceptDiscoveryOpening].
     */
    DISCOVERY_OPENING(13),
}

data class SecureRecord(val type: SecureRecordType, val payload: ByteArray)

class SecureRecordWriter internal constructor(
    private val output: OutputStream,
    key: ByteArray,
) : Closeable {
    private val key = key.copyOf()
    private var nextSequence = 0L

    @Synchronized
    fun write(type: SecureRecordType, payload: ByteArray): Int {
        require(payload.size <= MAX_PLAINTEXT_BYTES) { "Secure record is too large" }
        val ciphertextBytes = payload.size + MeshAead.TAG_BYTES
        val header = SecureRecordProtocol.encodeHeader(type, nextSequence, ciphertextBytes)
        val ciphertext = MeshAead.encrypt(key, nextSequence, payload, header)
        output.write(header)
        output.write(ciphertext)
        nextSequence++
        return header.size + ciphertext.size
    }

    @Synchronized
    fun flush() = output.flush()

    override fun close() = key.fill(0)

    companion object {
        const val MAX_PLAINTEXT_BYTES = StreamProtocol.MAX_PAYLOAD_BYTES + 128
    }
}

class SecureRecordReader internal constructor(
    input: InputStream,
    key: ByteArray,
) : Closeable {
    private val arrival = ArrivalTrainMeter()
    private val input = DataInputStream(ArrivalTimedInputStream(input, arrival))
    private val key = key.copyOf()
    private var nextSequence = 0L

    /**
     * Set only by the experimental store-and-forward path on a link it owns. False (default):
     * type 11 is an unknown record type, exactly as before the type existed.
     */
    @Volatile var acceptStoreForward = false

    /** Set only for the single first record of a link whose hellos both carry bit 3 (rotating IDs). */
    @Volatile var acceptDiscoveryOpening = false

    /** This link's recently delivered capacity, measured from large incoming records (kbps). */
    fun arrivalCapacityKbps(): Int? = arrival.estimateKbps()

    val measuredTrains: Long
        get() = arrival.trains

    @Synchronized
    fun read(): SecureRecord {
        arrival.beginRecord()
        val header = ByteArray(SecureRecordProtocol.HEADER_BYTES).also(input::readFully)
        val decoded = SecureRecordProtocol.decodeHeader(header, acceptStoreForward, acceptDiscoveryOpening)
        require(decoded.sequence == nextSequence) {
            "Unexpected secure record sequence ${decoded.sequence}; expected $nextSequence"
        }
        val ciphertext = ByteArray(decoded.ciphertextBytes).also(input::readFully)
        arrival.endRecord()
        val plaintext = try {
            MeshAead.decrypt(key, decoded.sequence, ciphertext, header)
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException(
                "Secure link decrypt failed: type=${decoded.type} sequence=${decoded.sequence} " +
                    "ciphertextBytes=${decoded.ciphertextBytes}",
                error,
            )
        }
        nextSequence++
        return SecureRecord(decoded.type, plaintext)
    }

    override fun close() = key.fill(0)
}

private data class SecureRecordHeader(
    val type: SecureRecordType,
    val sequence: Long,
    val ciphertextBytes: Int,
)

private object SecureRecordProtocol {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    const val HEADER_BYTES = 17

    fun encodeHeader(type: SecureRecordType, sequence: Long, ciphertextBytes: Int): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
            DataOutputStream(bytes).apply {
                write(magic)
                writeByte(type.wireValue)
                writeLong(sequence)
                writeInt(ciphertextBytes)
            }
        }.toByteArray()

    fun decodeHeader(
        bytes: ByteArray,
        acceptStoreForward: Boolean = false,
        acceptDiscoveryOpening: Boolean = false,
    ): SecureRecordHeader {
        require(bytes.size == HEADER_BYTES) { "Invalid secure record header" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid secure record magic" }
        val typeValue = input.readUnsignedByte()
        val type = SecureRecordType.entries.firstOrNull {
            it.wireValue == typeValue && (it != SecureRecordType.STORE_FORWARD || acceptStoreForward) &&
                (it != SecureRecordType.DISCOVERY_OPENING || acceptDiscoveryOpening)
        } ?: error("Unknown secure record type $typeValue")
        val sequence = input.readLong()
        require(sequence >= 0) { "Invalid secure record sequence" }
        val ciphertextBytes = input.readInt()
        require(ciphertextBytes in MeshAead.TAG_BYTES..SecureRecordWriter.MAX_PLAINTEXT_BYTES + MeshAead.TAG_BYTES) {
            "Invalid secure record size $ciphertextBytes"
        }
        return SecureRecordHeader(type, sequence, ciphertextBytes)
    }
}

data class SecureBluetoothSession(
    val peerNodeId: NodeId,
    val peerCapabilities: NodeCapabilities,
    val reader: SecureRecordReader,
    val writer: SecureRecordWriter,
) : Closeable {
    override fun close() {
        reader.close()
        writer.close()
    }
}

object SecureBluetoothHandshake {
    private const val MAX_HELLO_BYTES = 2_048

    fun initiate(
        input: InputStream,
        output: OutputStream,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
    ): SecureBluetoothSession {
        val pending = MeshHandshake.start(identity, HandshakeRole.INITIATOR, capabilities)
        writeHello(output, pending.hello)
        val remote = readHello(input)
        val keys = MeshHandshake.finish(pending, remote)
        return session(input, output, remote, keys)
    }

    /**
     * [beforeHello] runs after the initiator's hello verified and before this side's hello is sent
     * (experimental rotating IDs: the stranger tarpit, RV8). Default: nothing, exactly as before.
     */
    fun respond(
        input: InputStream,
        output: OutputStream,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        beforeHello: (NodeId) -> Unit = {},
    ): SecureBluetoothSession {
        val remote = readHello(input)
        require(remote.role == HandshakeRole.INITIATOR) { "Expected an initiator handshake" }
        val pending = MeshHandshake.start(identity, HandshakeRole.RESPONDER, capabilities)
        val keys = MeshHandshake.finish(pending, remote)
        beforeHello(keys.peerNodeId)
        writeHello(output, pending.hello)
        return session(input, output, remote, keys)
    }

    private fun session(
        input: InputStream,
        output: OutputStream,
        remote: SignedHandshakeHello,
        keys: MeshSessionKeys,
    ): SecureBluetoothSession = SecureBluetoothSession(
        peerNodeId = keys.peerNodeId,
        peerCapabilities = remote.capabilities,
        reader = SecureRecordReader(input, keys.receiveKey),
        writer = SecureRecordWriter(output, keys.sendKey),
    ).also {
        keys.sendKey.fill(0)
        keys.receiveKey.fill(0)
    }

    private fun writeHello(output: OutputStream, hello: SignedHandshakeHello) {
        val bytes = HandshakeHelloCodec.encode(hello)
        require(bytes.size <= MAX_HELLO_BYTES) { "Handshake message is too large" }
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    private fun readHello(input: InputStream): SignedHandshakeHello {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 1..MAX_HELLO_BYTES) { "Invalid handshake message size" }
        return HandshakeHelloCodec.decode(ByteArray(size).also(data::readFully))
    }
}

object SecureLinkControlProtocol {
    /**
     * Version-1 keepalive. On a direct private-call link it also says "this endpoint accepts version
     * 3 reports" (see [CallDeliveryReports]): call endpoints before version 3 only ever sent version 2
     * reports on a direct link and accept a version-1 heartbeat as plain liveness.
     */
    fun writeHeartbeat(writer: SecureRecordWriter): Int =
        writer.write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(VERSION.toByte()))

    /**
     * Hop-local heartbeat carrying what this receiver measured on the link from its parent
     * (workstream B, 0 = not measured yet) and whether media on this link reaches it late (its
     * adaptive playout delay pinned at the maximum). It describes only this one link and is never
     * forwarded (each phone handles its parent and direct children only). It stays inside the
     * authenticated, ordered link encryption.
     */
    fun writeCapacityReport(writer: SecureRecordWriter, report: LinkCapacityReport): Int =
        writer.write(SecureRecordType.LINK_HEARTBEAT, encodeCapacityReport(report))

    /**
     * Version 2 (6 bytes) unless [report] carries [LinkDelivery]; then version 3 (14 bytes). Every
     * parser before version 3 rejects anything but the exact 6-byte version 2 and closes the link, so
     * a caller attaches delivery only to a hop known to accept version 3 (a call peer's version-1
     * hello) or under a stream experiment that requires every phone to run this build.
     */
    internal fun encodeCapacityReport(report: LinkCapacityReport): ByteArray {
        val kbps = report.arrivalKbps.coerceIn(0, 0xffff)
        val addedDelay = report.addedDelayMs.coerceIn(0, 0xffff)
        val delivery = report.delivery
        var flags = if (report.linkLate) FLAG_LINK_LATE else 0
        if (delivery != null && report.deliveryOnly) flags = flags or FLAG_DELIVERY_ONLY
        val base = byteArrayOf(
            (if (delivery == null) REPORT_VERSION else DELIVERY_REPORT_VERSION).toByte(),
            (kbps shr 8).toByte(),
            kbps.toByte(),
            flags.toByte(),
            (addedDelay shr 8).toByte(),
            addedDelay.toByte(),
        )
        if (delivery == null) return base
        return base + delivery.receivedBytes.toUInt32() + delivery.clockMs.toUInt32()
    }

    /**
     * Validates a heartbeat; returns the peer's link report when it carries one.
     *
     * Versions 1 and 2 stay exact. Version 3 and later are forward-compatible: the first 14 bytes are
     * the version 3 fields; flag bits 2-7 and trailing bytes (a later version's additions) are
     * ignored, so a version 4 report never closes a version 3 link. The record is already
     * authenticated and ordered by the link encryption; this only bounds its size.
     */
    fun requireHeartbeat(record: SecureRecord): LinkCapacityReport? {
        require(record.type == SecureRecordType.LINK_HEARTBEAT) { "Expected a link heartbeat" }
        val payload = record.payload
        return when {
            payload.contentEquals(byteArrayOf(VERSION.toByte())) -> null
            payload.size == 6 && payload[0].toInt() == REPORT_VERSION -> {
                val flags = payload[3].toInt() and 0xff
                require(flags and FLAG_LINK_LATE.inv() == 0) { "Invalid link report flags" }
                decodeBaseReport(payload, flags, delivery = null)
            }
            payload.isNotEmpty() && (payload[0].toInt() and 0xff) >= DELIVERY_REPORT_VERSION -> {
                require(payload.size in DELIVERY_REPORT_BYTES..MAX_REPORT_BYTES) { "Invalid link report length" }
                decodeBaseReport(
                    payload,
                    payload[3].toInt() and 0xff,
                    LinkDelivery(payload.uint32At(6), payload.uint32At(10)),
                )
            }
            else -> throw IllegalArgumentException("Unsupported link heartbeat")
        }
    }

    private fun decodeBaseReport(payload: ByteArray, flags: Int, delivery: LinkDelivery?) = LinkCapacityReport(
        (payload[1].toInt() and 0xff) shl 8 or (payload[2].toInt() and 0xff),
        flags and FLAG_LINK_LATE != 0,
        (payload[4].toInt() and 0xff) shl 8 or (payload[5].toInt() and 0xff),
        delivery,
        deliveryOnly = delivery != null && flags and FLAG_DELIVERY_ONLY != 0,
    )

    private fun Long.toUInt32(): ByteArray {
        val value = this and 0xffff_ffffL
        return byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    }

    private fun ByteArray.uint32At(offset: Int): Long =
        (0 until 4).fold(0L) { acc, index -> (acc shl 8) or (this[offset + index].toLong() and 0xff) }

    private const val VERSION = 1
    private const val REPORT_VERSION = 2
    private const val DELIVERY_REPORT_VERSION = 3
    private const val DELIVERY_REPORT_BYTES = 14
    private const val MAX_REPORT_BYTES = 64
    private const val FLAG_LINK_LATE = 1
    /** Version 3: only [LinkDelivery] is new; the lateness fields repeat the last full report. */
    private const val FLAG_DELIVERY_ONLY = 2
}

/**
 * [arrivalKbps] 0 = not measured yet; [linkLate] the child's playout delay is pinned at its maximum;
 * [addedDelayMs] how much delay the child has added above its starting delay to absorb late
 * arrivals on this link. A rising value between reports means the link carries less than it is
 * sent (the parent should lower its rate); a steady one is an absorbed burst. [delivery] (version 3)
 * is what actually arrived; null in version 2 reports. [deliveryOnly] (version 3 flag bit 1): the
 * report was sent only to refresh [delivery]; its lateness fields repeat the last full report and
 * must not feed a lateness trend (a stream viewer reports delivery every second, but lateness only
 * when it changes).
 */
data class LinkCapacityReport(
    val arrivalKbps: Int,
    val linkLate: Boolean,
    val addedDelayMs: Int = 0,
    val delivery: LinkDelivery? = null,
    val deliveryOnly: Boolean = false,
)

/**
 * Version 3 delivery counters (2026-10-09). [receivedBytes]: media payload bytes this receiver has
 * taken off this link since the link started, modulo 2^32 (the same bytes its sender counts as
 * written). [clockMs]: this receiver's link clock, modulo 2^32 (milliseconds since this link started
 * here; not device uptime), at the arrival of the last counted byte, not when the report was
 * written. The sender maps that byte offset to its own write time, so the one-way delay of that byte
 * (minus its rolling minimum) is the forward queue, untouched by how long the report took to return.
 */
data class LinkDelivery(val receivedBytes: Long, val clockMs: Long)

object LocalNodeCapabilities {
    fun forTransport(
        transport: BluetoothTransportOption,
        maxChildren: Int = 0,
    ): NodeCapabilities = NodeCapabilities(
        transportMask = transport.capabilityMask,
        encodeCodecMask = VideoCodec.availableEncoders().fold(0) { mask, codec ->
            mask or codec.capabilityMask
        },
        decodeCodecMask = VideoCodec.availableDecoders().fold(0) { mask, decoder ->
            mask or codecCapabilityMask(decoder.protocolId)
        },
        maxBitrateKbps = transport.maxVideoBitrateKbps,
        maxChildren = maxChildren,
    )
}

val BluetoothTransportOption.capabilityMask: Int
    get() = when (this) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> NodeCapabilities.TRANSPORT_CLASSIC
        BluetoothTransportOption.BLE_L2CAP -> NodeCapabilities.TRANSPORT_BLE_L2CAP
        BluetoothTransportOption.BLE_GATT -> NodeCapabilities.TRANSPORT_BLE_GATT
    }

val VideoCodec.capabilityMask: Int
    get() = codecCapabilityMask(protocolId)

fun codecCapabilityMask(protocolId: Int): Int = when (protocolId) {
    StreamProtocol.CODEC_VP9 -> NodeCapabilities.CODEC_VP9
    StreamProtocol.CODEC_AV1 -> NodeCapabilities.CODEC_AV1
    else -> 0
}

fun NodeId.shortLabel(): String = toString().take(12)
