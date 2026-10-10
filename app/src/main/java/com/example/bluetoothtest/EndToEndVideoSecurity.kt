package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.ArrayDeque

enum class EndToEndContentType(val wireValue: Int) {
    STREAM_HEADER(1),
    MEDIA_PACKET(2),
    STREAM_STATE(4),
    KEY_FRAME_REQUEST(5),
    PRIVATE_CALL_SIGNAL(6),
    GROUP_KEY(7),
    STREAM_END_ACK(8),

    /** Experimental call video fragments (CallVideoFragments); only after both endpoints advertised them. */
    VIDEO_FRAGMENT(9),

    /** Experimental peer labels (PeerLabelFeature): one `BLB1` container; only after both endpoint hellos set bit 5. */
    LABELS(10),
}

enum class StreamLifecycleState(val wireValue: Int) {
    LIVE(1),
    PAUSED(2),
    ENDED(3),
    ;

    companion object {
        fun decode(payload: ByteArray): StreamLifecycleState {
            require(payload.size == 1) { "Invalid stream-state message length" }
            val wireValue = payload[0].toInt() and 0xff
            return entries.firstOrNull { it.wireValue == wireValue }
                ?: error("Unknown stream state $wireValue")
        }
    }

    fun encode(): ByteArray = byteArrayOf(wireValue.toByte())
}

sealed interface ReceivedStreamContent {
    data class Media(val packet: StreamProtocol.Packet) : ReceivedStreamContent
    data class State(val state: StreamLifecycleState) : ReceivedStreamContent
}

enum class StreamRouteWithdrawal(val wireValue: Int) {
    LOCAL_CAPACITY(1),
}

object StreamRouteControlProtocol {
    fun encode(reason: StreamRouteWithdrawal): ByteArray = byteArrayOf(reason.wireValue.toByte())

    fun decode(payload: ByteArray): StreamRouteWithdrawal {
        require(payload.size == 1) { "Invalid route-control message length" }
        val wireValue = payload[0].toInt() and 0xff
        return StreamRouteWithdrawal.entries.firstOrNull { it.wireValue == wireValue }
            ?: error("Unknown route withdrawal $wireValue")
    }
}

class StreamRouteWithdrawnException(val reason: StreamRouteWithdrawal) :
    IOException("Immediate parent withdrew this stream route: ${reason.name}")

enum class SourceControlMessage {
    LINK_HEARTBEAT,
    KEY_FRAME_REQUEST,
    STREAM_END_ACK,
    ROUTE_WITHDRAWAL_ACK,
}

data class EndToEndContentMetadata(
    val type: EndToEndContentType,
    val sequence: Long,
    val flags: Int,
    val ciphertextBytes: Int,
)

data class EndToEndContentRecord(
    val type: EndToEndContentType,
    val flags: Int,
    val payload: ByteArray,
)

class EndToEndContentSealer internal constructor(key: ByteArray) : Closeable {
    private val key = key.copyOf()
    private var nextSequence = 0L
    private var corruptNextRecord = false

    @Synchronized
    fun seal(type: EndToEndContentType, flags: Int, payload: ByteArray): ByteArray {
        require(flags in 0..0xff) { "Invalid end-to-end content flags" }
        require(payload.size <= EndToEndContentProtocol.MAX_PLAINTEXT_BYTES) {
            "End-to-end content is too large"
        }
        val header = EndToEndContentProtocol.encodeHeader(
            type,
            nextSequence,
            flags,
            payload.size + MeshAead.TAG_BYTES,
        )
        val ciphertext = MeshAead.encrypt(key, nextSequence, payload, header)
        if (corruptNextRecord && ciphertext.isNotEmpty()) {
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            corruptNextRecord = false
        }
        nextSequence++
        return header + ciphertext
    }

    internal fun corruptNextRecordForTest() {
        corruptNextRecord = true
    }

    override fun close() = key.fill(0)
}

class EndToEndContentOpener internal constructor(key: ByteArray) : Closeable {
    private val key = key.copyOf()
    private var nextSequence = 0L

    /**
     * Experimental call video fragments: only a call endpoint that advertised them sets this. Every
     * other opener rejects content type 9 exactly as an unknown type, as before.
     */
    @Volatile internal var acceptVideoFragments = false

    /**
     * Experimental peer labels: only an endpoint that advertised labels (hello bit 5) sets this.
     * Every other opener rejects content type 10 exactly as an unknown type, as before.
     */
    @Volatile internal var acceptLabels = false

    internal fun copyKeyForForwarding(): ByteArray = key.copyOf()

    @Synchronized
    fun open(encoded: ByteArray): EndToEndContentRecord {
        val metadata = EndToEndContentProtocol.inspect(encoded, acceptVideoFragments, acceptLabels)
        require(metadata.sequence >= nextSequence) {
            "Replayed or reordered end-to-end sequence ${metadata.sequence}; expected at least $nextSequence"
        }
        val header = encoded.copyOfRange(0, EndToEndContentProtocol.HEADER_BYTES)
        val ciphertext = encoded.copyOfRange(EndToEndContentProtocol.HEADER_BYTES, encoded.size)
        val plaintext = try {
            MeshAead.decrypt(key, metadata.sequence, ciphertext, header)
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException(
                "End-to-end decrypt failed: type=${metadata.type} sequence=${metadata.sequence} " +
                    "ciphertextBytes=${metadata.ciphertextBytes}",
                error,
            )
        }
        nextSequence = metadata.sequence + 1
        return EndToEndContentRecord(metadata.type, metadata.flags, plaintext)
    }

    override fun close() = key.fill(0)
}

object EndToEndContentProtocol {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    const val HEADER_BYTES = 18
    const val MAX_PLAINTEXT_BYTES = StreamProtocol.MAX_PAYLOAD_BYTES + StreamProtocol.PACKET_HEADER_BYTES

    internal fun encodeHeader(
        type: EndToEndContentType,
        sequence: Long,
        flags: Int,
        ciphertextBytes: Int,
    ): ByteArray = ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
        DataOutputStream(bytes).apply {
            write(magic)
            writeByte(type.wireValue)
            writeLong(sequence)
            writeByte(flags)
            writeInt(ciphertextBytes)
        }
    }.toByteArray()

    fun inspect(
        encoded: ByteArray,
        acceptVideoFragments: Boolean = false,
        acceptLabels: Boolean = false,
    ): EndToEndContentMetadata {
        require(encoded.size >= HEADER_BYTES) { "Truncated end-to-end content" }
        val input = DataInputStream(ByteArrayInputStream(encoded, 0, HEADER_BYTES))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid end-to-end content magic" }
        val typeValue = input.readUnsignedByte()
        val type = EndToEndContentType.entries.firstOrNull { it.wireValue == typeValue }
            ?.takeIf { it != EndToEndContentType.VIDEO_FRAGMENT || acceptVideoFragments }
            ?.takeIf { it != EndToEndContentType.LABELS || acceptLabels }
            ?: error("Unknown end-to-end content type $typeValue")
        val sequence = input.readLong()
        require(sequence >= 0) { "Invalid end-to-end content sequence" }
        val flags = input.readUnsignedByte()
        val ciphertextBytes = input.readInt()
        require(ciphertextBytes in MeshAead.TAG_BYTES..MAX_PLAINTEXT_BYTES + MeshAead.TAG_BYTES) {
            "Invalid end-to-end content size $ciphertextBytes"
        }
        require(encoded.size == HEADER_BYTES + ciphertextBytes) { "Truncated end-to-end content payload" }
        require(type == EndToEndContentType.MEDIA_PACKET || type == EndToEndContentType.VIDEO_FRAGMENT || flags == 0) {
            "Non-media end-to-end content cannot carry packet flags"
        }
        return EndToEndContentMetadata(type, sequence, flags, ciphertextBytes)
    }
}

data class EndToEndVideoSession(
    val peerNodeId: NodeId,
    val peerCapabilities: NodeCapabilities,
    val sealer: EndToEndContentSealer,
    val opener: EndToEndContentOpener,
    /** Full SHA-256 fingerprint of the peer's authenticated identity key (PROTOCOL "Identity verification"). */
    val peerFingerprint: IdentityFingerprint,
) : Closeable {
    override fun close() {
        sealer.close()
        opener.close()
    }
}

internal fun meshPacketFlags(mediaFlags: Int): Int =
    mediaFlags and (MeshEnvelope.FLAG_CONTROL or MeshEnvelope.FLAG_TERMINAL).inv()

class EndToEndStreamWriter(
    private val linkWriter: SecureRecordWriter,
    private val contentSealer: EndToEndContentSealer,
    sourceNodeId: NodeId,
    useMeshEnvelope: Boolean,
    initialTtl: Int = DEFAULT_MESH_TTL,
    meshStreamId: UUID? = null,
) {
    private val streamId = if (useMeshEnvelope) meshStreamId ?: UUID.randomUUID() else null
    private val sourceNodeId = sourceNodeId
    private val initialTtl = initialTtl
    private var nextMeshSequence = 0L

    init {
        require(initialTtl in 1..0xff)
        require(useMeshEnvelope || meshStreamId == null) { "A direct stream cannot set a mesh stream ID" }
    }

    val meshStreamId: UUID?
        get() = streamId

    fun writeHeader(
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int,
        includeAudio: Boolean,
    ): Int = writeSealed(
        EndToEndContentType.STREAM_HEADER,
        0,
        EndToEndStreamProtocol.encodeHeader(codecId, settings, rotationDegrees, includeAudio),
    )

    fun writePacket(flags: Int, presentationTimeUs: Long, payload: ByteArray): Int = writeSealed(
        EndToEndContentType.MEDIA_PACKET,
        flags,
        EndToEndStreamProtocol.encodePacket(flags, presentationTimeUs, payload),
    )

    fun writeState(state: StreamLifecycleState): Int = writeSealed(
        EndToEndContentType.STREAM_STATE,
        0,
        state.encode(),
        if (state == StreamLifecycleState.ENDED) MeshEnvelope.FLAG_TERMINAL else 0,
    )

    private fun writeSealed(
        type: EndToEndContentType,
        flags: Int,
        plaintext: ByteArray,
        additionalMeshFlags: Int = 0,
    ): Int {
        val sealed = contentSealer.seal(type, flags, plaintext)
        val activeStreamId = streamId
            ?: return linkWriter.write(SecureRecordType.END_TO_END_CONTENT, sealed)
        val meshFlags = meshPacketFlags(flags) or additionalMeshFlags or if (type == EndToEndContentType.STREAM_STATE) {
            MeshEnvelope.FLAG_CONTROL
        } else {
            0
        }
        val envelope = MeshEnvelope(
            streamId = activeStreamId,
            sourceNodeId = sourceNodeId,
            sequence = nextMeshSequence++,
            ttl = initialTtl,
            flags = meshFlags,
            payload = sealed,
        )
        return linkWriter.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
    }

    companion object {
        const val DEFAULT_MESH_TTL = 8
    }
}

class EndToEndStreamReader(
    private val linkReader: SecureRecordReader,
    private val contentOpener: EndToEndContentOpener,
    private val expectedSourceNodeId: NodeId,
    private val onMeshEnvelope: (MeshEnvelope) -> Unit = {},
) {
    private enum class WireMode { DIRECT, MESH }

    private var wireMode: WireMode? = null
    private var streamId: UUID? = null
    private var lastMeshSequence = -1L
    private val duplicates = DuplicateWindow()
    private val pendingMedia = ArrayDeque<StreamProtocol.Packet>()

    val meshStreamId: UUID?
        get() = streamId

    /**
     * Media packet payload bytes read so far and when the last arrived, counted before an audio pair
     * is split: the bytes the parent counts as written (link report version 3 delivery).
     */
    internal val delivery = LinkDeliveryCounter()

    fun readHeader(): StreamProtocol.Header {
        val content = readContent()
        require(content.type == EndToEndContentType.STREAM_HEADER) {
            "Expected an end-to-end stream header"
        }
        return EndToEndStreamProtocol.decodeHeader(content.payload)
    }

    fun readStreamContent(): ReceivedStreamContent {
        pendingMedia.pollFirst()?.let { return ReceivedStreamContent.Media(it) }
        val content = readContent()
        return when (content.type) {
            EndToEndContentType.MEDIA_PACKET -> {
                val packet = EndToEndStreamProtocol.decodePacket(content)
                delivery.add(packet.payload.size)
                if (packet.flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) {
                    val split = StreamProtocol.splitAudioPair(packet)
                    pendingMedia.addLast(split[1])
                    ReceivedStreamContent.Media(split[0])
                } else {
                    ReceivedStreamContent.Media(packet)
                }
            }
            EndToEndContentType.STREAM_STATE ->
                ReceivedStreamContent.State(StreamLifecycleState.decode(content.payload))
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                error("Private-call signals are not broadcast stream content")
            else -> error("Unexpected end-to-end stream content ${content.type}")
        }
    }

    private fun readContent(): EndToEndContentRecord {
        val record = linkReader.read()
        var authenticatedEnvelope: MeshEnvelope? = null
        val sealed = when (record.type) {
            SecureRecordType.END_TO_END_CONTENT -> {
                require(wireMode != WireMode.MESH) { "Mesh stream changed to direct records" }
                wireMode = WireMode.DIRECT
                record.payload
            }
            SecureRecordType.MESH_ENVELOPE -> {
                require(wireMode != WireMode.DIRECT) { "Direct stream changed to mesh envelopes" }
                wireMode = WireMode.MESH
                val envelope = MeshEnvelopeCodec.decode(record.payload)
                require(envelope.sourceNodeId == expectedSourceNodeId) {
                    "Mesh source identity does not match the endpoint handshake"
                }
                require(envelope.ttl > 0) { "Mesh packet arrived with expired TTL" }
                val activeStreamId = streamId
                if (activeStreamId == null) {
                    streamId = envelope.streamId
                } else {
                    require(envelope.streamId == activeStreamId) { "Mesh stream ID changed" }
                }
                require(envelope.sequence > lastMeshSequence) {
                    "Replayed or reordered mesh sequence ${envelope.sequence}"
                }
                require(duplicates.markIfNew(envelope)) { "Duplicate mesh packet" }
                lastMeshSequence = envelope.sequence
                authenticatedEnvelope = envelope
                envelope.payload
            }
            SecureRecordType.ROUTE_CONTROL ->
                throw StreamRouteWithdrawnException(StreamRouteControlProtocol.decode(record.payload))
            else -> error("Expected end-to-end stream content, got ${record.type}")
        }
        return contentOpener.open(sealed).also {
            authenticatedEnvelope?.let(onMeshEnvelope)
        }
    }
}

object EndToEndVideoHandshake {
    fun initiate(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
    ): EndToEndVideoSession {
        val pending = MeshHandshake.start(
            identity,
            HandshakeRole.INITIATOR,
            capabilities,
            purpose = HandshakePurpose.END_TO_END,
        )
        link.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, HandshakeHelloCodec.encode(pending.hello))
        link.writer.flush()
        val remote = readHello(link)
        val keys = MeshHandshake.finish(pending, remote)
        return session(remote, keys)
    }

    fun respond(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        /** A first record the caller already read from [link] (experimental store-and-forward dispatch). */
        firstRecord: SecureRecord? = null,
    ): EndToEndVideoSession {
        val remote = readHello(link, firstRecord)
        require(remote.role == HandshakeRole.INITIATOR) { "Expected an end-to-end initiator" }
        val pending = MeshHandshake.start(
            identity,
            HandshakeRole.RESPONDER,
            capabilities,
            purpose = HandshakePurpose.END_TO_END,
        )
        val keys = MeshHandshake.finish(pending, remote)
        link.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, HandshakeHelloCodec.encode(pending.hello))
        link.writer.flush()
        return session(remote, keys)
    }

    private fun readHello(link: SecureBluetoothSession, firstRecord: SecureRecord? = null): SignedHandshakeHello {
        val record = firstRecord ?: link.reader.read()
        require(record.type == SecureRecordType.END_TO_END_HANDSHAKE) {
            "Expected an end-to-end handshake"
        }
        return HandshakeHelloCodec.decode(record.payload)
    }

    private fun session(
        remote: SignedHandshakeHello,
        keys: MeshSessionKeys,
    ): EndToEndVideoSession {
        // Throws before any content key exists when a verified contact's prefix shows another key.
        val fingerprint = try {
            PeerIdentityGate.admit(keys.peerNodeId, remote.identityPublicKey)
        } catch (error: RuntimeException) {
            keys.sendKey.fill(0)
            keys.receiveKey.fill(0)
            throw error
        }
        return sessionWithFingerprint(remote, keys, fingerprint)
    }

    private fun sessionWithFingerprint(
        remote: SignedHandshakeHello,
        keys: MeshSessionKeys,
        fingerprint: IdentityFingerprint,
    ): EndToEndVideoSession = EndToEndVideoSession(
        peerNodeId = keys.peerNodeId,
        peerCapabilities = remote.capabilities,
        sealer = EndToEndContentSealer(keys.sendKey),
        opener = EndToEndContentOpener(keys.receiveKey),
        peerFingerprint = fingerprint,
    ).also {
        keys.sendKey.fill(0)
        keys.receiveKey.fill(0)
    }
}

object EndToEndStreamProtocol {
    fun encodeHeader(
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int,
        includeAudio: Boolean,
    ): ByteArray = ByteArrayOutputStream().also {
        StreamProtocol.writeHeader(it, codecId, settings, rotationDegrees, includeAudio)
    }.toByteArray()

    fun decodeHeader(payload: ByteArray): StreamProtocol.Header {
        val input = ByteArrayInputStream(payload)
        return StreamProtocol.readHeader(input).also {
            require(input.available() == 0) { "Trailing end-to-end stream header data" }
        }
    }

    fun encodePacket(
        flags: Int,
        presentationTimeUs: Long,
        payload: ByteArray,
    ): ByteArray = ByteArrayOutputStream(StreamProtocol.PACKET_HEADER_BYTES + payload.size).also {
        StreamProtocol.writePacket(it, flags, presentationTimeUs, payload)
    }.toByteArray()

    fun decodePacket(content: EndToEndContentRecord): StreamProtocol.Packet {
        require(content.type == EndToEndContentType.MEDIA_PACKET) { "Expected a media packet" }
        val input = ByteArrayInputStream(content.payload)
        return StreamProtocol.readPacket(input).also {
            require(input.available() == 0) { "Trailing end-to-end media packet data" }
            require(it.flags == content.flags) {
                "End-to-end packet flags do not match authenticated metadata"
            }
        }
    }

    fun writeHeader(
        linkWriter: SecureRecordWriter,
        contentSealer: EndToEndContentSealer,
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int,
        includeAudio: Boolean,
    ): Int {
        val plaintext = encodeHeader(codecId, settings, rotationDegrees, includeAudio)
        return writeContent(
            linkWriter,
            contentSealer.seal(EndToEndContentType.STREAM_HEADER, 0, plaintext),
        )
    }

    fun readHeader(
        linkReader: SecureRecordReader,
        contentOpener: EndToEndContentOpener,
    ): StreamProtocol.Header {
        val content = readContent(linkReader, contentOpener, EndToEndContentType.STREAM_HEADER)
        return decodeHeader(content.payload)
    }

    fun writePacket(
        linkWriter: SecureRecordWriter,
        contentSealer: EndToEndContentSealer,
        flags: Int,
        presentationTimeUs: Long,
        payload: ByteArray,
    ): Int {
        val plaintext = encodePacket(flags, presentationTimeUs, payload)
        return writeContent(
            linkWriter,
            contentSealer.seal(EndToEndContentType.MEDIA_PACKET, flags, plaintext),
        )
    }

    /**
     * Experimental call video fragments: writes one fragment payload (CallVideoFragments.split) of
     * a video packet with [flags]. Sealed exactly like a media packet, with its own sequence.
     */
    fun writeVideoFragment(
        linkWriter: SecureRecordWriter,
        contentSealer: EndToEndContentSealer,
        flags: Int,
        fragment: ByteArray,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(EndToEndContentType.VIDEO_FRAGMENT, flags, fragment),
    )

    /** Decodes a reassembled fragmented packet with the same checks as a whole media packet. */
    fun decodeReassembledPacket(flags: Int, plaintext: ByteArray): StreamProtocol.Packet =
        decodePacket(EndToEndContentRecord(EndToEndContentType.MEDIA_PACKET, flags, plaintext))

    fun readPacket(
        linkReader: SecureRecordReader,
        contentOpener: EndToEndContentOpener,
    ): StreamProtocol.Packet {
        val content = readContent(linkReader, contentOpener, EndToEndContentType.MEDIA_PACKET)
        return decodePacket(content)
    }

    fun writeState(
        linkWriter: SecureRecordWriter,
        contentSealer: EndToEndContentSealer,
        state: StreamLifecycleState,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(EndToEndContentType.STREAM_STATE, 0, state.encode()),
    )

    fun readStreamContent(
        linkReader: SecureRecordReader,
        contentOpener: EndToEndContentOpener,
    ): ReceivedStreamContent {
        val record = linkReader.read()
        if (record.type == SecureRecordType.ROUTE_CONTROL) {
            throw StreamRouteWithdrawnException(StreamRouteControlProtocol.decode(record.payload))
        }
        require(record.type == SecureRecordType.END_TO_END_CONTENT) { "Expected end-to-end content" }
        val content = contentOpener.open(record.payload)
        return when (content.type) {
            EndToEndContentType.MEDIA_PACKET -> {
                val input = ByteArrayInputStream(content.payload)
                val packet = StreamProtocol.readPacket(input)
                require(input.available() == 0) { "Trailing end-to-end media packet data" }
                require(packet.flags == content.flags) {
                    "End-to-end packet flags do not match authenticated metadata"
                }
                ReceivedStreamContent.Media(packet)
            }
            EndToEndContentType.STREAM_STATE ->
                ReceivedStreamContent.State(StreamLifecycleState.decode(content.payload))
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                error("Private-call signals are not broadcast stream content")
            else -> error("Unexpected end-to-end stream content ${content.type}")
        }
    }

    fun writeKeyFrameRequest(
        linkWriter: SecureRecordWriter,
        contentSealer: EndToEndContentSealer,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(
            EndToEndContentType.KEY_FRAME_REQUEST,
            0,
            ByteArray(0),
        ),
    )

    fun writeStreamEndAck(
        linkWriter: SecureRecordWriter,
        contentSealer: EndToEndContentSealer,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(
            EndToEndContentType.STREAM_END_ACK,
            0,
            ByteArray(0),
        ),
    )

    fun readSourceControl(
        linkReader: SecureRecordReader,
        contentOpener: EndToEndContentOpener,
        onCapacityReport: (LinkCapacityReport) -> Unit = {},
    ): SourceControlMessage {
        val record = linkReader.read()
        return when (record.type) {
            SecureRecordType.END_TO_END_CONTENT -> {
                val content = contentOpener.open(record.payload)
                require(
                    content.type == EndToEndContentType.KEY_FRAME_REQUEST ||
                        content.type == EndToEndContentType.STREAM_END_ACK,
                ) {
                    "Expected an end-to-end receiver control"
                }
                require(content.payload.isEmpty()) { "Invalid receiver control" }
                if (content.type == EndToEndContentType.KEY_FRAME_REQUEST) {
                    SourceControlMessage.KEY_FRAME_REQUEST
                } else {
                    SourceControlMessage.STREAM_END_ACK
                }
            }
            SecureRecordType.LINK_HEARTBEAT -> {
                SecureLinkControlProtocol.requireHeartbeat(record)?.let(onCapacityReport)
                SourceControlMessage.LINK_HEARTBEAT
            }
            SecureRecordType.ROUTE_CONTROL_ACK -> {
                StreamRouteControlProtocol.decode(record.payload)
                SourceControlMessage.ROUTE_WITHDRAWAL_ACK
            }
            else -> error("Unexpected source control record ${record.type}")
        }
    }

    private fun writeContent(writer: SecureRecordWriter, encoded: ByteArray): Int =
        writer.write(SecureRecordType.END_TO_END_CONTENT, encoded)

    private fun readContent(
        reader: SecureRecordReader,
        opener: EndToEndContentOpener,
        expectedType: EndToEndContentType,
    ): EndToEndContentRecord {
        val record = reader.read()
        require(record.type == SecureRecordType.END_TO_END_CONTENT) { "Expected end-to-end content" }
        return opener.open(record.payload).also {
            require(it.type == expectedType) { "Expected end-to-end $expectedType content" }
        }
    }
}
