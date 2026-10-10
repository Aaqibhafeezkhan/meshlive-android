package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class MeshEnvelope(
    val streamId: UUID,
    val sourceNodeId: NodeId,
    val sequence: Long,
    val ttl: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    init {
        require(sequence >= 0) { "Sequence numbers must not be negative" }
        require(ttl in 0..0xff)
        require(flags in 0..0xff)
        require(payload.size <= MAX_PAYLOAD_BYTES)
    }

    val isKeyFrame: Boolean
        get() = flags and FLAG_KEY_FRAME != 0
    val isAudio: Boolean
        get() = flags and FLAG_AUDIO != 0
    val isControl: Boolean
        get() = flags and FLAG_CONTROL != 0
    val isDiscardable: Boolean
        get() = flags and FLAG_DISCARDABLE != 0

    fun forwarded(): MeshEnvelope? = if (ttl <= 1) null else copy(ttl = ttl - 1)

    companion object {
        const val FLAG_CODEC_CONFIG = 1
        const val FLAG_KEY_FRAME = 1 shl 1
        const val FLAG_AUDIO = 1 shl 3
        const val FLAG_CONTROL = 1 shl 4
        const val FLAG_TERMINAL = 1 shl 5
        /** Mirrors inner StreamProtocol.FLAG_DISCARDABLE (authenticated): relays may drop it alone. */
        const val FLAG_DISCARDABLE = 1 shl 7
        const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}

object MeshEnvelopeCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val HEADER_BYTES = 50

    fun encode(value: MeshEnvelope): ByteArray = ByteArrayOutputStream(HEADER_BYTES + value.payload.size).also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.writeLong(value.streamId.mostSignificantBits)
            output.writeLong(value.streamId.leastSignificantBits)
            output.write(value.sourceNodeId.toByteArray())
            output.writeLong(value.sequence)
            output.writeByte(value.ttl)
            output.writeByte(value.flags)
            output.writeInt(value.payload.size)
            output.write(value.payload)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): MeshEnvelope {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid mesh packet" }
        val streamId = UUID(input.readLong(), input.readLong())
        val source = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT).also(input::readFully))
        val sequence = input.readLong()
        val ttl = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val payloadSize = input.readInt()
        require(payloadSize in 0..MeshEnvelope.MAX_PAYLOAD_BYTES) { "Invalid mesh payload size" }
        require(payloadSize == input.available()) { "Truncated or trailing mesh packet data" }
        return MeshEnvelope(
            streamId,
            source,
            sequence,
            ttl,
            flags,
            ByteArray(payloadSize).also(input::readFully),
        )
    }
}

data class MeshFrameKey(
    val streamId: UUID,
    val sourceNodeId: NodeId,
    val sequence: Long,
)

class DuplicateWindow(private val maxEntries: Int = 4_096) {
    init {
        require(maxEntries > 0)
    }

    private val entries = object : LinkedHashMap<MeshFrameKey, Unit>(maxEntries + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MeshFrameKey, Unit>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun markIfNew(envelope: MeshEnvelope): Boolean {
        val key = MeshFrameKey(envelope.streamId, envelope.sourceNodeId, envelope.sequence)
        if (entries.containsKey(key)) return false
        entries[key] = Unit
        return true
    }

    @Synchronized
    fun clear() = entries.clear()
}

enum class ChildQueueOffer {
    QUEUED,
    /** The child joined mid-stream: video before its first keyframe cannot be decoded there. */
    WAITING_FOR_JOIN_KEYFRAME,
    STARTED_AT_KEYFRAME,
    /**
     * Experimental temporal layering only: a discardable frame skipped for a child that is behind;
     * decoding continues, no keyframe is needed.
     */
    THINNED,
    /**
     * Experimental bounded media age only: stale video was shed for this child and this video is
     * dropped until the next keyframe; the relay asks its parent for one (as for a join).
     */
    SHED_WAITING_FOR_KEYFRAME,
    /**
     * Experimental bounded media age only: dropped while waiting for that keyframe; a request is
     * already outstanding (repeated with a per-child backoff, RelayRecoveryBackoff, until a keyframe arrives).
     */
    SHED_DROPPED,
}

/**
 * One relay child's ordered queue. Nothing the child can use is ever dropped (user direction
 * 2026-10-07: latency instead of loss). The previous queue dropped all queued video on overflow
 * and made the child wait for a recovery keyframe, which froze it and asked the source for the
 * largest frame of the stream. A slow child link now builds a backlog that the child's adaptive
 * playout absorbs; when the backlog outgrows any playout buffer the relay withdraws the child so it
 * can reroute (RelayChildHealthPolicy) instead of discarding frames. The only skipped media is
 * video before a joining child's first keyframe, which it could not decode anyway.
 */
class RelayChildQueue(
    /**
     * Experimental temporal layering (off by default): skip verified-discardable frames while this
     * child is [TemporalLayerThinning.THIN_BACKLOG_MS] behind. Off: nothing is ever skipped.
     */
    private val thinDiscardable: Boolean = false,
    /**
     * Experimental bounded media age (off by default: null): shed this child's stale video
     * codec-safely beyond its age budget. Null: nothing is ever shed.
     */
    private val mediaAgeBound: RelayMediaAgeBound? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Queued(val envelope: MeshEnvelope, val atNs: Long)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val packets = ArrayDeque<Queued>()
    private var queuedBytes = 0L
    private var waitingForJoinKeyFrame = false
    private var closed = false
    var skippedBeforeJoin: Long = 0
        private set
    var thinnedFrames: Long = 0
        private set
    private var lastKeyFrameTakenNs = Long.MIN_VALUE
    private val videoWaitMax = WindowMax()

    fun offer(envelope: MeshEnvelope): ChildQueueOffer = lock.withLock {
        if (waitingForJoinKeyFrame && !envelope.isControl && !envelope.isAudio) {
            if (!envelope.isKeyFrame) {
                skippedBeforeJoin++
                return ChildQueueOffer.WAITING_FOR_JOIN_KEYFRAME
            }
            waitingForJoinKeyFrame = false
            append(envelope)
            return ChildQueueOffer.STARTED_AT_KEYFRAME
        }
        mediaAgeBound?.let { bound ->
            val shedClass = bound.classify(envelope)
            if (shedClass != ShedClass.KEEP) {
                shedForMediaAge(bound, shedClass == ShedClass.KEY_FRAME)?.let { return it }
            }
        }
        if (thinDiscardable && TemporalLayerThinning.shouldThin(envelope.isDiscardable, backlogAgeMsLocked())) {
            thinnedFrames++
            return ChildQueueOffer.THINNED
        }
        append(envelope)
        ChildQueueOffer.QUEUED
    }

    /**
     * Experimental bounded media age: sheds stale queued video for this child. Returns null when the
     * arriving video is kept, otherwise the drop result: SHED_WAITING_FOR_KEYFRAME when a keyframe
     * request is due (RelayRecoveryBackoff: at once after a calm period, then backing off), else
     * SHED_DROPPED. Lock held, and the writer takes under the same lock, so a shed is atomic.
     */
    private fun shedForMediaAge(bound: RelayMediaAgeBound, keyFrame: Boolean): ChildQueueOffer? {
        val shedder = bound.shedder
        val nowNs = nanoTime()
        if (shedder.dropArrivingVideo(keyFrame)) return keyFrameWaitResult(bound, nowNs)
        val ageMs = oldestVideoAgeMsLocked(bound, nowNs)
        // A keyframe that just left needed its airtime; the frames behind it aged by design.
        val keyFrameGrace = lastKeyFrameTakenNs != Long.MIN_VALUE && nowNs - lastKeyFrameTakenNs < RELAY_KEY_FRAME_GRACE_NS
        val budgetMs = bound.budgetMs()
        if (!shedder.shouldShed(ageMs, budgetMs, keyFrameGrace, nowNs)) return null
        val plan = planCodecSafeShed(
            packets.toList(),
            { queued -> bound.classify(queued.envelope) },
            { queued -> (nowNs - queued.atNs) / 1_000_000L <= budgetMs },
        )
        for (index in plan.drop.sortedDescending()) {
            queuedBytes -= packets.removeAt(index).envelope.payload.size
        }
        shedder.recordShed(plan, plan.drop.size, nowNs)
        if (!plan.awaitKeyFrame) return null
        return if (shedder.dropArrivingVideo(keyFrame)) keyFrameWaitResult(bound, nowNs) else null
    }

    private fun keyFrameWaitResult(bound: RelayMediaAgeBound, nowNs: Long): ChildQueueOffer {
        return if (bound.recovery.tryRequest(nowNs)) {
            ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME
        } else {
            ChildQueueOffer.SHED_DROPPED
        }
    }

    private fun oldestVideoAgeMsLocked(bound: RelayMediaAgeBound, nowNs: Long): Long =
        packets.firstOrNull { bound.classify(it.envelope) != ShedClass.KEEP }
            ?.let { (nowNs - it.atNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    /** Experimental bounded media age counters for this child (zeros when off). */
    fun mediaAgeSummary(): String = lock.withLock {
        val shedder = mediaAgeBound?.shedder
        "videoQueueAgeMaxMs=${videoWaitMax.take()} | shedEvents=${shedder?.shedEvents ?: 0} | " +
            "shedFrames=${shedder?.shedFrames ?: 0} | keyFrameWaitFrames=${shedder?.keyFrameWaitFrames ?: 0} | " +
            "awaitingKeyFrame=${shedder?.awaitingKeyFrame ?: false} | " +
            "keyFrameRequests=${mediaAgeBound?.keyFrameRequests ?: 0} | " +
            "keyFrameRequestsSuppressed=${mediaAgeBound?.recovery?.suppressed ?: 0} | " +
            "recoveryIntervalMs=${(mediaAgeBound?.recovery?.intervalNs ?: 0L) / 1_000_000L}"
    }

    fun poll(): MeshEnvelope? = lock.withLock { removeFirst() }

    @Throws(InterruptedException::class)
    fun take(): MeshEnvelope? = lock.withLock {
        while (packets.isEmpty() && !closed) notEmpty.await()
        removeFirst()
    }

    fun size(): Int = lock.withLock { packets.size }

    fun queuedBytes(): Long = lock.withLock { queuedBytes }

    /** How long the oldest queued media has waited: how far behind live this child is here. */
    fun backlogAgeMs(): Long = lock.withLock { backlogAgeMsLocked() }

    fun thinnedFrameCount(): Long = lock.withLock { thinnedFrames }

    fun hasQueuedAudio(): Boolean = lock.withLock { packets.any { it.envelope.isAudio } }

    /** A child joining mid-stream starts at the next keyframe. */
    fun waitForKeyFrame() = lock.withLock {
        waitingForJoinKeyFrame = true
    }

    fun close() = lock.withLock {
        closed = true
        notEmpty.signalAll()
    }

    private fun backlogAgeMsLocked(): Long =
        packets.firstOrNull()?.let { (nanoTime() - it.atNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    private fun append(envelope: MeshEnvelope) {
        packets.addLast(Queued(envelope, nanoTime()))
        queuedBytes += envelope.payload.size
        notEmpty.signal()
    }

    private fun removeFirst(): MeshEnvelope? = packets.removeFirstOrNull()?.let { queued ->
        val envelope = queued.envelope
        queuedBytes -= envelope.payload.size
        if (!envelope.isAudio && !envelope.isControl && envelope.flags and MeshEnvelope.FLAG_CODEC_CONFIG == 0) {
            val nowNs = nanoTime()
            // Media age spent in this child queue (both arms; BENCHMARK media_age).
            videoWaitMax.record((nowNs - queued.atNs).coerceAtLeast(0L) / 1_000_000L)
            if (envelope.isKeyFrame) lastKeyFrameTakenNs = nowNs
        }
        envelope
    }

    private companion object {
        const val RELAY_KEY_FRAME_GRACE_NS = 3_000_000_000L
    }
}

data class AdmissionDecision(
    val admitted: Boolean,
    val reservedKbps: Int,
    val availableKbps: Int,
)

class LinkAdmissionController(private val maximumAggregateKbps: Int) {
    init {
        require(maximumAggregateKbps > 0)
    }

    private val childReservations = linkedMapOf<NodeId, Int>()
    private var inboundKbps = 0

    @Synchronized
    fun setInboundKbps(value: Int) {
        require(value >= 0)
        inboundKbps = value
    }

    @Synchronized
    fun tryAdmit(child: NodeId, expectedKbps: Int): AdmissionDecision {
        require(expectedKbps > 0)
        val alreadyReserved = childReservations[child] ?: 0
        val usedWithoutChild = inboundKbps + childReservations.values.sum() - alreadyReserved
        val available = (maximumAggregateKbps - usedWithoutChild).coerceAtLeast(0)
        val admitted = expectedKbps <= available
        if (admitted) childReservations[child] = expectedKbps
        return AdmissionDecision(admitted, usedWithoutChild, available)
    }

    @Synchronized
    fun release(child: NodeId) {
        childReservations.remove(child)
    }

    @Synchronized
    fun aggregateReservedKbps(): Int = inboundKbps + childReservations.values.sum()
}

data class RouteCandidate(
    val destination: NodeId,
    val nextHop: NodeId,
    val hopCount: Int,
    val observedAtMs: Long,
) {
    init {
        require(hopCount > 0)
        require(observedAtMs >= 0)
    }
}

class MeshRouteTable(
    private val localNodeId: NodeId,
    private val staleAfterMs: Long = 15_000,
) {
    init {
        require(staleAfterMs > 0)
    }

    private val routes = mutableMapOf<Pair<NodeId, NodeId>, RouteCandidate>()

    @Synchronized
    fun update(candidate: RouteCandidate) {
        if (candidate.nextHop == localNodeId || candidate.destination == localNodeId) return
        val key = candidate.destination to candidate.nextHop
        val current = routes[key]
        if (current == null || candidate.observedAtMs >= current.observedAtMs) routes[key] = candidate
    }

    @Synchronized
    fun best(destination: NodeId, nowMs: Long): RouteCandidate? {
        require(nowMs >= 0)
        return routes.values
            .asSequence()
            .filter { it.destination == destination && nowMs - it.observedAtMs <= staleAfterMs }
            .minWithOrNull(
                compareBy<RouteCandidate> { it.hopCount }
                    .thenByDescending { it.observedAtMs },
            )
    }

    @Synchronized
    fun removeNextHop(nextHop: NodeId) {
        routes.entries.removeAll { it.value.nextHop == nextHop }
    }
}

enum class RelayRejection {
    LOCAL_LOOP,
    DUPLICATE,
    TTL_EXPIRED,
    NO_CHILDREN,
}

data class RelayResult(
    val forwardedEnvelope: MeshEnvelope?,
    val queuedChildren: Set<NodeId>,
    val laggingChildren: Set<NodeId>,
    val rejection: RelayRejection?,
    /**
     * Experimental bounded media age only (always false otherwise): a child shed waits for a keyframe
     * and this request is due on its bounded schedule, so it must be sent even though an earlier
     * request is still outstanding.
     */
    val keyFrameReRequest: Boolean = false,
)

class MeshRelayCore(
    private val localNodeId: NodeId,
    duplicateEntries: Int = 4_096,
    /** Experimental temporal layering (off by default): child queues may thin discardable frames. */
    private val thinDiscardable: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val duplicates = DuplicateWindow(duplicateEntries)
    private val childQueues = linkedMapOf<NodeId, RelayChildQueue>()

    @Synchronized
    fun addChild(child: NodeId) {
        require(child != localNodeId)
        childQueues[child] = RelayChildQueue(thinDiscardable, nanoTime = nanoTime)
    }

    @Synchronized
    fun removeChild(child: NodeId) {
        childQueues.remove(child)
    }

    @Synchronized
    fun receive(incomingPeer: NodeId, envelope: MeshEnvelope): RelayResult {
        if (envelope.sourceNodeId == localNodeId) return rejected(RelayRejection.LOCAL_LOOP)
        if (!duplicates.markIfNew(envelope)) return rejected(RelayRejection.DUPLICATE)
        val forwarded = envelope.forwarded() ?: return rejected(RelayRejection.TTL_EXPIRED)
        val targets = childQueues.filterKeys { it != incomingPeer }
        if (targets.isEmpty()) return rejected(RelayRejection.NO_CHILDREN)
        val queued = linkedSetOf<NodeId>()
        val lagging = linkedSetOf<NodeId>()
        targets.forEach { (child, queue) ->
            when (queue.offer(forwarded)) {
                ChildQueueOffer.QUEUED,
                ChildQueueOffer.STARTED_AT_KEYFRAME,
                -> queued += child
                ChildQueueOffer.WAITING_FOR_JOIN_KEYFRAME -> lagging += child
                // Thinning keeps the child decodable, so it must not trigger an upstream keyframe.
                ChildQueueOffer.THINNED -> Unit
                // Never produced here: this hidden diagnostic relay has no bounded media age.
                ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME -> lagging += child
                ChildQueueOffer.SHED_DROPPED -> Unit
            }
        }
        return RelayResult(forwarded, queued, lagging, null)
    }

    fun poll(child: NodeId): MeshEnvelope? = synchronized(this) { childQueues[child] }?.poll()

    @Throws(InterruptedException::class)
    fun take(child: NodeId): MeshEnvelope? = synchronized(this) { childQueues[child] }?.take()

    @Synchronized
    fun queueDepth(child: NodeId): Int = childQueues[child]?.size() ?: 0

    @Synchronized
    fun backlogAgeMs(child: NodeId): Long = childQueues[child]?.backlogAgeMs() ?: 0

    /** Experimental temporal layering: discardable frames skipped for [child] (0 when off). */
    @Synchronized
    fun thinnedFrames(child: NodeId): Long = childQueues[child]?.thinnedFrameCount() ?: 0

    private fun rejected(reason: RelayRejection) = RelayResult(null, emptySet(), emptySet(), reason)
}

