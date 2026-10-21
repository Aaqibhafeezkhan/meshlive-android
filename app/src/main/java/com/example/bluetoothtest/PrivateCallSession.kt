package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private val EMPTY_CALL_ID = UUID(0L, 0L)

enum class PrivateCallSignal(val wireValue: Int) {
    INVITE(1),
    RINGING(2),
    ACCEPT(3),
    DECLINE(4),
    CANCEL(5),
    HANG_UP(6),
    CAMERA_OFF(7),
    CAMERA_ON(8),
    MICROPHONE_MUTED(9),
    MICROPHONE_UNMUTED(10),
}

enum class PrivateCallReason(val wireValue: Int) {
    NONE(0),
    REJECTED(1),
    BUSY(2),
    UNSUPPORTED(3),
    NORMAL(4),
    ERROR(5),
}

data class PrivateCallSignalMessage(
    val callId: UUID,
    val signal: PrivateCallSignal,
    val reason: PrivateCallReason = PrivateCallReason.NONE,
    val relayHops: Int = 0,
) {
    init {
        require(callId != EMPTY_CALL_ID) { "A private call needs a non-zero call ID" }
        require(relayHops in 0..MAX_RELAY_HOPS) { "Invalid private-call relay count" }
        require(signal == PrivateCallSignal.INVITE || relayHops == 0) {
            "Only a private-call invitation can carry its relay count"
        }
        when (signal) {
            PrivateCallSignal.DECLINE -> require(
                reason == PrivateCallReason.REJECTED ||
                    reason == PrivateCallReason.BUSY ||
                    reason == PrivateCallReason.UNSUPPORTED,
            ) { "A declined call needs a decline reason" }
            PrivateCallSignal.HANG_UP -> require(
                reason == PrivateCallReason.NORMAL || reason == PrivateCallReason.ERROR,
            ) { "A hang-up needs a terminal reason" }
            else -> require(reason == PrivateCallReason.NONE) {
                "$signal cannot carry a reason"
            }
        }
    }

    companion object {
        const val MAX_RELAY_HOPS = 15
    }
}

object PrivateCallSignalCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte())
    const val ENCODED_BYTES = 23

    fun encode(message: PrivateCallSignalMessage): ByteArray = ByteBuffer
        .allocate(ENCODED_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(magic)
        .put(message.signal.wireValue.toByte())
        .putLong(message.callId.mostSignificantBits)
        .putLong(message.callId.leastSignificantBits)
        .put(message.reason.wireValue.toByte())
        .put(message.relayHops.toByte())
        .array()

    fun decode(encoded: ByteArray): PrivateCallSignalMessage {
        require(encoded.size == ENCODED_BYTES) { "Invalid private-call signal length" }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val receivedMagic = ByteArray(magic.size).also(input::get)
        require(receivedMagic.contentEquals(magic)) { "Invalid private-call signal" }
        val signalValue = input.get().toInt() and 0xff
        val signal = PrivateCallSignal.entries.firstOrNull { it.wireValue == signalValue }
            ?: error("Unknown private-call signal $signalValue")
        val callId = UUID(input.long, input.long)
        val reasonValue = input.get().toInt() and 0xff
        val reason = PrivateCallReason.entries.firstOrNull { it.wireValue == reasonValue }
            ?: error("Unknown private-call reason $reasonValue")
        val relayHops = input.get().toInt() and 0xff
        return PrivateCallSignalMessage(callId, signal, reason, relayHops)
    }
}

enum class PrivateCallRole {
    CALLER,
    CALLEE,
}

enum class PrivateCallPhase {
    OUTGOING_INVITE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    NEGOTIATING_MEDIA,
    ACTIVE,
    ENDED,
}

enum class PrivateCallEvent {
    REMOTE_RINGING,
    LOCAL_ACCEPT,
    REMOTE_ACCEPT,
    LOCAL_DECLINE,
    REMOTE_DECLINE,
    LOCAL_CANCEL,
    REMOTE_CANCEL,
    MEDIA_READY,
    LOCAL_HANG_UP,
    REMOTE_HANG_UP,
    LINK_LOST,
}

data class PrivateCallState(
    val callId: UUID,
    val role: PrivateCallRole,
    val phase: PrivateCallPhase,
) {
    init {
        require(callId != EMPTY_CALL_ID) { "A private call needs a non-zero call ID" }
        require(
            (phase != PrivateCallPhase.OUTGOING_INVITE &&
                phase != PrivateCallPhase.OUTGOING_RINGING) ||
                role == PrivateCallRole.CALLER,
        ) { "Only a caller can have an outgoing call phase" }
        require(phase != PrivateCallPhase.INCOMING_RINGING || role == PrivateCallRole.CALLEE) {
            "Only a callee can have an incoming call phase"
        }
    }

    /** A delayed permission result (or duplicate tap) must not revive an ended attempt. */
    internal fun acceptIfRinging(): PrivateCallState? =
        if (phase == PrivateCallPhase.INCOMING_RINGING) transition(PrivateCallEvent.LOCAL_ACCEPT) else null

    fun transition(event: PrivateCallEvent): PrivateCallState {
        val next = when (phase) {
            PrivateCallPhase.OUTGOING_INVITE -> when (event) {
                PrivateCallEvent.REMOTE_RINGING -> PrivateCallPhase.OUTGOING_RINGING
                PrivateCallEvent.REMOTE_ACCEPT -> PrivateCallPhase.NEGOTIATING_MEDIA
                PrivateCallEvent.REMOTE_DECLINE,
                PrivateCallEvent.LOCAL_CANCEL,
                PrivateCallEvent.LINK_LOST,
                -> PrivateCallPhase.ENDED
                else -> null
            }
            PrivateCallPhase.OUTGOING_RINGING -> when (event) {
                PrivateCallEvent.REMOTE_ACCEPT -> PrivateCallPhase.NEGOTIATING_MEDIA
                PrivateCallEvent.REMOTE_DECLINE,
                PrivateCallEvent.LOCAL_CANCEL,
                PrivateCallEvent.LINK_LOST,
                -> PrivateCallPhase.ENDED
                else -> null
            }
            PrivateCallPhase.INCOMING_RINGING -> when (event) {
                PrivateCallEvent.LOCAL_ACCEPT -> PrivateCallPhase.NEGOTIATING_MEDIA
                PrivateCallEvent.LOCAL_DECLINE,
                PrivateCallEvent.REMOTE_CANCEL,
                PrivateCallEvent.LINK_LOST,
                -> PrivateCallPhase.ENDED
                else -> null
            }
            PrivateCallPhase.NEGOTIATING_MEDIA -> when (event) {
                PrivateCallEvent.MEDIA_READY -> PrivateCallPhase.ACTIVE
                PrivateCallEvent.LOCAL_HANG_UP,
                PrivateCallEvent.REMOTE_HANG_UP,
                PrivateCallEvent.LINK_LOST,
                -> PrivateCallPhase.ENDED
                else -> null
            }
            PrivateCallPhase.ACTIVE -> when (event) {
                PrivateCallEvent.LOCAL_HANG_UP,
                PrivateCallEvent.REMOTE_HANG_UP,
                PrivateCallEvent.LINK_LOST,
                -> PrivateCallPhase.ENDED
                else -> null
            }
            PrivateCallPhase.ENDED -> null
        } ?: error("Invalid private-call transition $phase + $event")
        return copy(phase = next)
    }

    companion object {
        fun outgoing(callId: UUID = UUID.randomUUID()) =
            PrivateCallState(callId, PrivateCallRole.CALLER, PrivateCallPhase.OUTGOING_INVITE)

        fun incoming(callId: UUID) =
            PrivateCallState(callId, PrivateCallRole.CALLEE, PrivateCallPhase.INCOMING_RINGING)
    }
}
