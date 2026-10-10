package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class ForwardedStreamGrant(
    val sourceNodeId: NodeId,
    val streamId: UUID,
    val key: ByteArray,
) {
    init {
        require(key.size == MeshAead.KEY_BYTES)
    }
}

object ForwardedStreamGrantCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'F'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
    private const val ENCODED_BYTES = 4 + NodeId.BYTE_COUNT + 16 + MeshAead.KEY_BYTES

    fun encode(value: ForwardedStreamGrant): ByteArray =
        ByteArrayOutputStream(ENCODED_BYTES).also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.write(value.sourceNodeId.toByteArray())
                output.writeLong(value.streamId.mostSignificantBits)
                output.writeLong(value.streamId.leastSignificantBits)
                output.write(value.key)
            }
        }.toByteArray()

    fun decode(bytes: ByteArray): ForwardedStreamGrant {
        require(bytes.size == ENCODED_BYTES) { "Invalid forwarded-stream grant length" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid forwarded-stream grant" }
        return ForwardedStreamGrant(
            NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT).also(input::readFully)),
            UUID(input.readLong(), input.readLong()),
            ByteArray(MeshAead.KEY_BYTES).also(input::readFully),
        )
    }
}

class ReceiverNodeForwardingCore(
    private val localNodeId: NodeId,
    private val upstreamPeerNodeId: NodeId,
    private val streamId: UUID,
    private val sourceNodeId: NodeId,
    private val queueCapacity: Int,
    /** Experimental temporal layering (off by default): the child queue may thin discardable frames. */
    private val thinDiscardable: Boolean = false,
    /** Experimental bounded media age (off by default): the child queue may shed stale video. */
    private val boundedMediaAge: Boolean = false,
) {
    private val duplicates = DuplicateWindow()
    private val bootstrap = mutableListOf<MeshEnvelope>()
    private var latestControl: MeshEnvelope? = null
    private var childNodeId: NodeId? = null
    private var childQueue: RelayChildQueue? = null
    private var childMediaAgeBound: RelayMediaAgeBound? = null

    init {
        require(queueCapacity > 0)
    }

    @Synchronized
    fun receive(envelope: MeshEnvelope): RelayResult {
        if (envelope.sourceNodeId == localNodeId) return rejected(RelayRejection.LOCAL_LOOP)
        require(envelope.sourceNodeId == sourceNodeId) { "Forwarded stream source changed" }
        require(envelope.streamId == streamId) { "Forwarded stream ID changed" }
        if (!duplicates.markIfNew(envelope)) return rejected(RelayRejection.DUPLICATE)
        remember(envelope)
        val forwarded = envelope.forwarded() ?: return rejected(RelayRejection.TTL_EXPIRED)
        val child = childNodeId ?: return rejected(RelayRejection.NO_CHILDREN)
        if (child == upstreamPeerNodeId) return rejected(RelayRejection.NO_CHILDREN)
        val queue = childQueue ?: return rejected(RelayRejection.NO_CHILDREN)
        return when (queue.offer(forwarded)) {
            ChildQueueOffer.QUEUED,
            ChildQueueOffer.STARTED_AT_KEYFRAME,
            -> RelayResult(forwarded, setOf(child), emptySet(), null)
            ChildQueueOffer.WAITING_FOR_JOIN_KEYFRAME ->
                RelayResult(forwarded, emptySet(), setOf(child), null)
            // Thinning keeps the child decodable, so it must not trigger an upstream keyframe.
            ChildQueueOffer.THINNED -> RelayResult(forwarded, emptySet(), emptySet(), null)
            // Bounded media age shed this child's stale video: it needs the next keyframe, as after a join.
            ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME ->
                RelayResult(forwarded, emptySet(), setOf(child), null, keyFrameReRequest = true)
            ChildQueueOffer.SHED_DROPPED -> RelayResult(forwarded, emptySet(), emptySet(), null)
        }
    }

    @Synchronized
    fun addChild(child: NodeId): List<MeshEnvelope> {
        require(child != localNodeId && child != upstreamPeerNodeId) { "Invalid downstream child" }
        require(childNodeId == null) { "A downstream child is already connected" }
        childNodeId = child
        val bound = if (boundedMediaAge) RelayMediaAgeBound() else null
        childMediaAgeBound = bound
        childQueue = RelayChildQueue(thinDiscardable, bound).also(RelayChildQueue::waitForKeyFrame)
        return (bootstrap + listOfNotNull(latestControl))
            .distinctBy(MeshEnvelope::sequence)
            .sortedBy(MeshEnvelope::sequence)
            .mapNotNull(MeshEnvelope::forwarded)
    }

    @Synchronized
    fun removeChild(child: NodeId) {
        if (childNodeId != child) return
        val removedQueue = childQueue
        childNodeId = null
        childQueue = null
        childMediaAgeBound = null
        removedQueue?.close()
    }

    @Throws(InterruptedException::class)
    fun take(child: NodeId): MeshEnvelope? = synchronized(this) {
        if (childNodeId == child) childQueue else null
    }?.take()

    @Synchronized
    fun queueDepth(child: NodeId): Int = if (childNodeId == child) childQueue?.size() ?: 0 else 0

    /** How far behind live this child's queue is (no media is dropped for a slow child). */
    @Synchronized
    fun backlogAgeMs(child: NodeId): Long =
        if (childNodeId == child) childQueue?.backlogAgeMs() ?: 0 else 0

    /** Experimental temporal layering: discardable frames skipped for [child] (0 when off). */
    @Synchronized
    fun thinnedFrames(child: NodeId): Long =
        if (childNodeId == child) childQueue?.thinnedFrameCount() ?: 0 else 0

    fun hasQueuedAudio(child: NodeId): Boolean =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.hasQueuedAudio() == true

    @Synchronized
    fun hasChild(): Boolean = childNodeId != null

    /** Hop-local: [child]'s reported added playout delay on this link, for bounded media age. */
    @Synchronized
    fun setChildAddedDelayMs(child: NodeId, delayMs: Int) {
        if (childNodeId == child) childMediaAgeBound?.childAddedDelayMs = delayMs
    }

    /** Both arms: [child]'s media age in this relay queue and any bounded-age shedding. */
    @Synchronized
    fun mediaAgeSummary(child: NodeId): String? = childQueue.takeIf { childNodeId == child }?.let { queue ->
        "budgetMs=${childMediaAgeBound?.budgetMs() ?: BoundedMediaAge.streamQueueBudgetMs(0)} | ${queue.mediaAgeSummary()}"
    }

    private fun remember(envelope: MeshEnvelope) {
        val type = EndToEndContentProtocol.inspect(envelope.payload).type
        when {
            type == EndToEndContentType.STREAM_HEADER -> {
                require(bootstrap.none { EndToEndContentProtocol.inspect(it.payload).type == type }) {
                    "Forwarded stream sent more than one header"
                }
                bootstrap += envelope
            }
            type == EndToEndContentType.MEDIA_PACKET && envelope.flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0 -> {
                bootstrap += envelope
                while (bootstrap.count {
                        EndToEndContentProtocol.inspect(it.payload).type == EndToEndContentType.MEDIA_PACKET &&
                            it.flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0
                    } > MAX_CODEC_CONFIG_PACKETS
                ) {
                    val index = bootstrap.indexOfFirst {
                        EndToEndContentProtocol.inspect(it.payload).type == EndToEndContentType.MEDIA_PACKET &&
                            it.flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0
                    }
                    bootstrap.removeAt(index)
                }
            }
            type == EndToEndContentType.STREAM_STATE -> latestControl = envelope
        }
    }

    private fun rejected(reason: RelayRejection) = RelayResult(null, emptySet(), emptySet(), reason)

    private companion object {
        const val MAX_CODEC_CONFIG_PACKETS = 8
    }
}
