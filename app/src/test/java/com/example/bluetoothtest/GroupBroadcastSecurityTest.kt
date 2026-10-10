package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GroupBroadcastSecurityTest {
    @Test
    fun controlAndGroupKeyCodecsRoundTripWithoutTrailingData() {
        val receiver = node(3)
        val control = GroupRelayControlMessage(
            GroupRelayControlType.RECEIVER_CONTROL,
            receiver,
            byteArrayOf(4, 5, 6),
        )
        val encodedControl = GroupRelayControlCodec.encode(control)
        val decodedControl = GroupRelayControlCodec.decode(encodedControl)
        assertEquals(control.type, decodedControl.type)
        assertEquals(control.receiverNodeId, decodedControl.receiverNodeId)
        assertArrayEquals(control.payload, decodedControl.payload)
        assertThrows(IllegalArgumentException::class.java) {
            GroupRelayControlCodec.decode(encodedControl + 0)
        }
        val receiverLeft = GroupRelayControlMessage(GroupRelayControlType.RECEIVER_LEFT, receiver)
        val decodedReceiverLeft = GroupRelayControlCodec.decode(
            GroupRelayControlCodec.encode(receiverLeft),
        )
        assertEquals(GroupRelayControlType.RECEIVER_LEFT, decodedReceiverLeft.type)
        assertEquals(receiver, decodedReceiverLeft.receiverNodeId)
        assertEquals(0, decodedReceiverLeft.payload.size)

        val group = BroadcastGroupKey(UUID.randomUUID(), 7, ByteArray(MeshAead.KEY_BYTES) { it.toByte() })
        val decodedGroup = BroadcastGroupKeyCodec.decode(BroadcastGroupKeyCodec.encode(group))
        assertEquals(group.streamId, decodedGroup.streamId)
        assertEquals(group.epoch, decodedGroup.epoch)
        assertArrayEquals(group.key, decodedGroup.key)
    }

    @Test
    fun identicalGroupCiphertextOpensIndependentlyAtTwoReceivers() {
        val key = ByteArray(MeshAead.KEY_BYTES) { (it * 7).toByte() }
        val encoded = EndToEndContentSealer(key).use { sealer ->
            sealer.seal(EndToEndContentType.MEDIA_PACKET, StreamProtocol.FLAG_KEY_FRAME, byteArrayOf(9, 8, 7))
        }

        val first = EndToEndContentOpener(key).use { it.open(encoded) }
        val second = EndToEndContentOpener(key).use { it.open(encoded) }
        assertEquals(EndToEndContentType.MEDIA_PACKET, first.type)
        assertArrayEquals(first.payload, second.payload)
        assertArrayEquals(byteArrayOf(9, 8, 7), second.payload)
    }

    @Test
    fun groupKeyIsWrappedSeparatelyForEachAuthenticatedReceiver() {
        val firstEndpointKey = ByteArray(MeshAead.KEY_BYTES) { 1 }
        val secondEndpointKey = ByteArray(MeshAead.KEY_BYTES) { 2 }
        val group = BroadcastGroupKey(UUID.randomUUID(), 11, ByteArray(MeshAead.KEY_BYTES) { 3 })
        val plain = BroadcastGroupKeyCodec.encode(group)
        val firstWrapped = EndToEndContentSealer(firstEndpointKey).use {
            it.seal(EndToEndContentType.GROUP_KEY, 0, plain)
        }
        val secondWrapped = EndToEndContentSealer(secondEndpointKey).use {
            it.seal(EndToEndContentType.GROUP_KEY, 0, plain)
        }

        assertNotEquals(firstWrapped.toList(), secondWrapped.toList())
        assertArrayEquals(
            plain,
            EndToEndContentOpener(firstEndpointKey).use { it.open(firstWrapped).payload },
        )
        assertArrayEquals(
            plain,
            EndToEndContentOpener(secondEndpointKey).use { it.open(secondWrapped).payload },
        )
        assertThrows(Exception::class.java) {
            EndToEndContentOpener(secondEndpointKey).use { it.open(firstWrapped) }
        }
    }

    private fun node(seed: Int): NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (seed + it).toByte() })
}
