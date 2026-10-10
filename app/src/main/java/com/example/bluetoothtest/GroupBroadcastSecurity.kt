package com.example.bluetoothtest

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.UUID

enum class GroupRelayControlType(val wireValue: Int) {
    JOIN(1),
    SOURCE_HELLO(2),
    RECEIVER_HELLO(3),
    WRAPPED_KEY(4),
    RECEIVER_CONTROL(5),
    RECEIVER_LEFT(6),
}

data class GroupRelayControlMessage(
    val type: GroupRelayControlType,
    val receiverNodeId: NodeId,
    val payload: ByteArray = ByteArray(0),
)

object GroupRelayControlCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
    private const val HEADER_BYTES = 4 + 1 + NodeId.BYTE_COUNT + 4
    private const val MAX_PAYLOAD_BYTES = 4 * 1024

    fun encode(value: GroupRelayControlMessage): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES + value.payload.size).also { bytes ->
            DataOutputStream(bytes).use { output ->
                require(value.payload.size <= MAX_PAYLOAD_BYTES) { "Group control payload is too large" }
                output.write(magic)
                output.writeByte(value.type.wireValue)
                output.write(value.receiverNodeId.toByteArray())
                output.writeInt(value.payload.size)
                output.write(value.payload)
            }
        }.toByteArray()

    fun decode(bytes: ByteArray): GroupRelayControlMessage {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid group relay control" }
        val typeValue = input.readUnsignedByte()
        val type = GroupRelayControlType.entries.firstOrNull { it.wireValue == typeValue }
            ?: error("Unknown group relay control type $typeValue")
        val receiver = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT).also(input::readFully))
        val payloadBytes = input.readInt()
        require(payloadBytes in 0..MAX_PAYLOAD_BYTES) { "Invalid group control payload size" }
        require(payloadBytes == input.available()) { "Truncated or trailing group control data" }
        return GroupRelayControlMessage(type, receiver, ByteArray(payloadBytes).also(input::readFully))
    }
}

data class BroadcastGroupKey(
    val streamId: UUID,
    val epoch: Long,
    val key: ByteArray,
) {
    init {
        require(epoch > 0)
        require(key.size == MeshAead.KEY_BYTES)
    }
}

object BroadcastGroupKeyCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'G'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    private const val ENCODED_BYTES = 4 + 16 + 8 + MeshAead.KEY_BYTES

    fun encode(value: BroadcastGroupKey): ByteArray = ByteArrayOutputStream(ENCODED_BYTES).also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.writeLong(value.streamId.mostSignificantBits)
            output.writeLong(value.streamId.leastSignificantBits)
            output.writeLong(value.epoch)
            output.write(value.key)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): BroadcastGroupKey {
        require(bytes.size == ENCODED_BYTES) { "Invalid broadcast group key length" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid broadcast group key" }
        return BroadcastGroupKey(
            UUID(input.readLong(), input.readLong()),
            input.readLong(),
            ByteArray(MeshAead.KEY_BYTES).also(input::readFully),
        )
    }
}

class BroadcastGroupSourceSession internal constructor(
    val streamId: UUID,
    val epoch: Long,
    val streamSealer: EndToEndContentSealer,
    private val receivers: Map<NodeId, EndToEndVideoSession>,
    /** Receivers refused by the identity gate: they got no key and are not counted. */
    refusedReceivers: Set<NodeId> = emptySet(),
) : Closeable {
    private val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<NodeId>().also { it.addAll(refusedReceivers) }

    fun isRefusedReceiver(receiver: NodeId): Boolean = receiver in refused

    /** The relay reported a refused receiver gone; true once per refused receiver. */
    fun markRefusedReceiverLeft(receiver: NodeId): Boolean = refused.remove(receiver)

    private val activeReceivers = java.util.concurrent.ConcurrentHashMap.newKeySet<NodeId>().also {
        it.addAll(receivers.keys)
    }

    val receiverCount: Int
        get() = receivers.size

    fun allReceiversSupportCodec(codecMask: Int): Boolean =
        receivers.values.all { it.peerCapabilities.decodeCodecMask and codecMask != 0 }

    fun openReceiverControl(receiver: NodeId, encoded: ByteArray): EndToEndContentRecord {
        val endpoint = receivers[receiver] ?: error("Unknown group receiver")
        return endpoint.opener.open(encoded).also {
            require(
                (it.type == EndToEndContentType.KEY_FRAME_REQUEST ||
                    it.type == EndToEndContentType.STREAM_END_ACK) && it.payload.isEmpty(),
            ) {
                "Invalid group receiver control"
            }
        }
    }

    fun markReceiverDisconnected(receiver: NodeId): Boolean =
        receivers.containsKey(receiver) && activeReceivers.remove(receiver)

    override fun close() {
        streamSealer.close()
        receivers.values.forEach(EndToEndVideoSession::close)
    }
}

class BroadcastGroupReceiverSession internal constructor(
    val streamId: UUID,
    val epoch: Long,
    val endpoint: EndToEndVideoSession,
    val streamOpener: EndToEndContentOpener,
) : Closeable {
    override fun close() {
        streamOpener.close()
        endpoint.close()
    }
}

object BroadcastGroupHandshake {
    fun initiate(
        context: Context,
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        receiverCount: Int,
        secureRandom: SecureRandom = SecureRandom(),
    ): BroadcastGroupSourceSession {
        require(receiverCount in 2..0xff) { "A group branch needs at least two receivers" }
        return initiateWithEpoch(link, identity, capabilities, receiverCount, BroadcastGroupEpochStore.next(context), secureRandom)
    }

    internal fun initiateWithEpoch(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        receiverCount: Int,
        epoch: Long,
        secureRandom: SecureRandom = SecureRandom(),
    ): BroadcastGroupSourceSession {
        require(receiverCount in 2..0xff) { "A group branch needs at least two receivers" }
        val streamId = UUID.randomUUID()
        val refused = linkedSetOf<NodeId>()
        val groupKey = ByteArray(MeshAead.KEY_BYTES).also(secureRandom::nextBytes)
        val receivers = linkedMapOf<NodeId, EndToEndVideoSession>()
        try {
            repeat(receiverCount) {
                val join = readControl(link, GroupRelayControlType.JOIN)
                require(join.payload.isEmpty()) { "Invalid group join" }
                require(join.receiverNodeId !in receivers && join.receiverNodeId !in refused) { "Duplicate group receiver" }
                val pending = MeshHandshake.start(
                    identity,
                    HandshakeRole.INITIATOR,
                    capabilities,
                    secureRandom,
                    HandshakePurpose.END_TO_END,
                )
                writeControl(
                    link,
                    GroupRelayControlMessage(
                        GroupRelayControlType.SOURCE_HELLO,
                        join.receiverNodeId,
                        HandshakeHelloCodec.encode(pending.hello),
                    ),
                )
                val response = readControl(link, GroupRelayControlType.RECEIVER_HELLO)
                require(response.receiverNodeId == join.receiverNodeId) { "Group receiver order changed" }
                val remote = HandshakeHelloCodec.decode(response.payload)
                require(remote.nodeId == join.receiverNodeId) { "Group receiver identity changed" }
                val endpoint = try {
                    endpointSession(remote, MeshHandshake.finish(pending, remote))
                } catch (mismatch: IdentityMismatchException) {
                    // Drop only this receiver: it gets an empty wrapped key, which its endpoint cannot
                    // open, so it fails closed and the relay reports it gone. The others continue.
                    refused += join.receiverNodeId
                    writeControl(
                        link,
                        GroupRelayControlMessage(GroupRelayControlType.WRAPPED_KEY, join.receiverNodeId, ByteArray(0)),
                    )
                    return@repeat
                }
                receivers[join.receiverNodeId] = endpoint
                val wrappedKey = endpoint.sealer.seal(
                    EndToEndContentType.GROUP_KEY,
                    0,
                    BroadcastGroupKeyCodec.encode(BroadcastGroupKey(streamId, epoch, groupKey)),
                )
                writeControl(
                    link,
                    GroupRelayControlMessage(
                        GroupRelayControlType.WRAPPED_KEY,
                        join.receiverNodeId,
                        wrappedKey,
                    ),
                )
            }
            require(receivers.isNotEmpty()) { "Every group receiver was refused" }
            return BroadcastGroupSourceSession(
                streamId,
                epoch,
                EndToEndContentSealer(groupKey),
                receivers,
                refused,
            )
        } catch (error: Exception) {
            receivers.values.forEach(EndToEndVideoSession::close)
            throw error
        } finally {
            groupKey.fill(0)
        }
    }

    fun respond(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        secureRandom: SecureRandom = SecureRandom(),
    ): BroadcastGroupReceiverSession {
        val helloRecord = link.reader.read()
        require(helloRecord.type == SecureRecordType.END_TO_END_HANDSHAKE) {
            "Expected a group source endpoint handshake"
        }
        val remote = HandshakeHelloCodec.decode(helloRecord.payload)
        require(remote.role == HandshakeRole.INITIATOR) { "Expected a group source initiator" }
        val pending = MeshHandshake.start(
            identity,
            HandshakeRole.RESPONDER,
            capabilities,
            secureRandom,
            HandshakePurpose.END_TO_END,
        )
        val endpoint = endpointSession(remote, MeshHandshake.finish(pending, remote))
        try {
            link.writer.write(
                SecureRecordType.END_TO_END_HANDSHAKE,
                HandshakeHelloCodec.encode(pending.hello),
            )
            link.writer.flush()
            val wrapped = link.reader.read()
            require(wrapped.type == SecureRecordType.END_TO_END_CONTENT) {
                "Expected a receiver-wrapped group key"
            }
            val opened = endpoint.opener.open(wrapped.payload)
            require(opened.type == EndToEndContentType.GROUP_KEY) { "Expected a broadcast group key" }
            val group = BroadcastGroupKeyCodec.decode(opened.payload)
            return BroadcastGroupReceiverSession(
                group.streamId,
                group.epoch,
                endpoint,
                EndToEndContentOpener(group.key),
            ).also { group.key.fill(0) }
        } catch (error: Exception) {
            endpoint.close()
            throw error
        }
    }

    private fun readControl(
        link: SecureBluetoothSession,
        expectedType: GroupRelayControlType,
    ): GroupRelayControlMessage {
        val record = link.reader.read()
        require(record.type == SecureRecordType.GROUP_CONTROL) { "Expected group relay control" }
        return GroupRelayControlCodec.decode(record.payload).also {
            require(it.type == expectedType) { "Expected group control $expectedType, got ${it.type}" }
        }
    }

    private fun writeControl(link: SecureBluetoothSession, message: GroupRelayControlMessage) {
        link.writer.write(SecureRecordType.GROUP_CONTROL, GroupRelayControlCodec.encode(message))
        link.writer.flush()
    }

    private fun endpointSession(
        remote: SignedHandshakeHello,
        keys: MeshSessionKeys,
    ): EndToEndVideoSession {
        val fingerprint = try {
            PeerIdentityGate.admit(keys.peerNodeId, remote.identityPublicKey)
        } catch (error: RuntimeException) {
            keys.sendKey.fill(0)
            keys.receiveKey.fill(0)
            throw error
        }
        return EndToEndVideoSession(
            keys.peerNodeId,
            remote.capabilities,
            EndToEndContentSealer(keys.sendKey),
            EndToEndContentOpener(keys.receiveKey),
            fingerprint,
        ).also {
            keys.sendKey.fill(0)
            keys.receiveKey.fill(0)
        }
    }
}

private object BroadcastGroupEpochStore {
    @Synchronized
    fun next(context: Context): Long {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val current = preferences.getLong(KEY_EPOCH, 0L)
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        check(preferences.edit().putLong(KEY_EPOCH, next).commit()) {
            "Could not persist the broadcast group epoch"
        }
        return next
    }

    private const val PREFERENCES = "broadcast_group_security"
    private const val KEY_EPOCH = "next_epoch"
}
