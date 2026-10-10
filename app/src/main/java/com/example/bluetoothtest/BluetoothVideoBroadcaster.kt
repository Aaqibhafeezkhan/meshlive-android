package com.example.bluetoothtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothVideoBroadcaster(
    private val queueCapacity: Int,
    private val onStatus: (String) -> Unit,
    private val onConnectionsChanged: (Int) -> Unit,
    private val onAllDisconnected: () -> Unit,
    private val onFirstConnected: () -> Unit,
    private val onPeerReady: () -> Unit = {},
    private val onRemoteKeyFrameNeeded: () -> Unit = {},
    private val thermalStatusProvider: () -> Int,
    private val onCapacityAvailable: () -> Unit = {},
    private val debugDirectGattHeartbeat: Boolean = true,
    private val debugGattWriteTiming: Boolean = false,
    private val onLinkSample: (Any, LinkRateSample) -> Unit = { _, _ -> },
    /** True while Automatic rate control already sends at its lowest video rate (see [FanOutStarvationPolicy]). */
    private val encoderAtRateFloor: () -> Boolean = { false },
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val streamStarted = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val senders = ConcurrentHashMap<String, BluetoothVideoSender>()
    private val connectedPeers = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var maximumSourceChildren = 0
    /**
     * Experimental temporal layering (off by default): once frames prove the stream is layered,
     * admission plans further viewers at the measured base layer. Never fed while the experiment is off.
     */
    private val layeredAdmission = DebugTunables.experimentalTemporalLayers
    private val layerShare = LayerShareMeter()
    @Volatile private var admissionSettings: VideoSettings? = null
    @Volatile private var admissionTransport: BluetoothTransportOption? = null
    private var lastAdmissionRefreshNs = 0L
    private val capacityRejections = SourceCapacityRejectionPolicy()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val starvation = FanOutStarvationPolicy()
    private val packetLock = Any()
    private val codecConfiguration = mutableListOf<StreamProtocol.Packet>()
    private var pendingAudioPacket: StreamProtocol.Packet? = null
    private val allDisconnectedRunnable = Runnable {
        if (senders.isEmpty() && !closed.get()) onAllDisconnected()
    }

    fun sourceSlotsFull(): Boolean = !closed.get() && maximumSourceChildren > 0 &&
        connectedPeers.size >= maximumSourceChildren

    /** True while an admitted receiver is still connecting/authenticating. */
    fun connectionAttemptsPending(): Boolean = !closed.get() && senders.size > connectedPeers.size

    fun connect(
        context: Context,
        peers: List<BleL2capPeer>,
        codec: VideoCodec,
        settings: VideoSettings,
        transport: BluetoothTransportOption,
        rotationDegrees: Int,
        includeAudio: Boolean,
        phyPreference: BlePhyPreference,
        corruptFirstVideoRecordForTest: Boolean = false,
        proveVideoIntegrityForTest: Boolean = false,
        ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
        maximumChildrenOverride: Int? = null,
        debugTraceSession: String? = null,
    ) {
        require(peers.isNotEmpty()) { "No viewers requested this stream" }
        val maximumChildren = maximumChildrenOverride ?: fanOutChildren(settings, transport)
        maximumSourceChildren = maximumChildren
        if (maximumChildrenOverride == null) {
            admissionSettings = settings
            admissionTransport = transport
            android.util.Log.i(
                "BluetoothVideo",
                "Source fan-out: $maximumChildren children from measured ${LinkCapacity.summary(transport)}" +
                    layerShareLabel(),
            )
            val perLink = MeshBandwidthEstimator.perLinkKbps(settings.bitRate / 1_000, settings.fps)
            android.util.Log.i(
                "BluetoothVideo",
                "BENCHMARK fanout_admission: provenLinks=%s | children=%d | perLinkKbps=%.1f | budgetKbps=%.0f | provenKbps=%d".format(
                    java.util.Locale.ROOT,
                    DebugTunables.fanoutProvenLinks,
                    maximumChildren,
                    perLink,
                    LinkCapacity.budgetKbps(transport, LinkLoadKind.FAN_OUT, perLink),
                    LinkCapacity.learned(transport, LinkLoadKind.FAN_OUT).provenKbps,
                ),
            )
        }
        val disconnectedPreviousKeys = peers.mapNotNull { peer ->
            peer.announcement.recoveryFromNodeIdPrefix?.let(::nodePrefixKey)
        }.toSet()
        val newPeers = peers.distinctBy(::peerIdentityKey)
            .filterNot { senders.containsKey(peerIdentityKey(it)) }
            .filterNot { peer ->
                peer.announcement.recoveryFromNodeIdPrefix == null &&
                    peerIdentityKey(peer) in disconnectedPreviousKeys
            }
            .filterNot { peer ->
                val nowNs = System.nanoTime()
                val previousSender = recoveryPreviousSenderKey(peer)?.let(senders::get)
                capacityRejections.shouldSuppress(
                    peerIdentityKey(peer),
                    nowNs,
                    capacityAvailable = senders.size < maximumChildren,
                    previousParentStale = previousSender != null &&
                        previousSender.expectsPeriodicChildHeartbeat() &&
                        nowNs - previousSender.lastChildHeartbeatNs() >= RECOVERY_STALE_HEARTBEAT_NS,
                )
            }
            .sortedByDescending { recoveryPreviousSenderKey(it) != null }
        var availableSlots = (maximumChildren - senders.size).coerceAtLeast(0)
        var recoveryProbeAdmitted = false
        val admittedPeers = mutableListOf<BleL2capPeer>()
        newPeers.forEach { peer ->
            when {
                availableSlots > 0 -> {
                    admittedPeers += peer
                    availableSlots--
                }
                !recoveryProbeAdmitted && recoveryPreviousSenderKey(peer) != null -> {
                    // This connection carries no media until the old child's authenticated
                    // heartbeat resolves. It may therefore temporarily exceed fan-out by one.
                    admittedPeers += peer
                    recoveryProbeAdmitted = true
                }
            }
        }
        val rejectedCount = newPeers.size - admittedPeers.size
        if (rejectedCount > 0) {
            val admittedKeys = admittedPeers.map(::peerIdentityKey).toSet()
            newPeers.filterNot { peerIdentityKey(it) in admittedKeys }.forEach { peer ->
                capacityRejections.reject(peerIdentityKey(peer), System.nanoTime())
            }
            onStatus(
                "$rejectedCount receiver(s) exceeded the measured ${transport.shortLabel} " +
                    "fan-out budget at ${settings.bitRate / 1_000} kbps.",
            )
        }
        val connectingAttempts = mutableMapOf<String, BluetoothVideoSender>()
        admittedPeers.forEach { peer ->
            val peerKey = peerIdentityKey(peer)
            lateinit var sender: BluetoothVideoSender
            sender = BluetoothVideoSender(
                queueCapacity = queueCapacity,
                onStatus = { message -> onStatus("${peer.label.substringBefore(" (")}: $message") },
                onDisconnected = {
                    if (senders.remove(peerKey, sender)) {
                        connectedPeers.remove(peerKey)
                        onConnectionsChanged(connectedPeers.size)
                        if (senders.size < maximumChildren && !closed.get()) onCapacityAvailable()
                        if (senders.isEmpty() && !closed.get()) {
                            timeoutHandler.removeCallbacks(allDisconnectedRunnable)
                            timeoutHandler.postDelayed(
                                allDisconnectedRunnable,
                                ROUTE_RECOVERY_GRACE_MS,
                            )
                        }
                    }
                },
                onRemoteKeyFrameNeeded = onRemoteKeyFrameNeeded,
                thermalStatusProvider = thermalStatusProvider,
                debugDirectGattHeartbeat = debugDirectGattHeartbeat,
                debugGattWriteTiming = debugGattWriteTiming,
                onLinkSample = { sample ->
                    onLinkSample(peerKey, sample)
                    starvation.onSample(peerKey, sample, System.nanoTime(), encoderAtRateFloor())?.let { withdrawal ->
                        timeoutHandler.post {
                            withdrawStarvedChild(peerKey, sender, transport, withdrawal.carriedAggregateKbps)
                        }
                    }
                },
            )
            if (senders.putIfAbsent(peerKey, sender) != null) return@forEach
            timeoutHandler.removeCallbacks(allDisconnectedRunnable)
            connectingAttempts[peerKey] = sender
            sender.connect(
                context,
                peer.device,
                codec,
                settings,
                transport,
                rotationDegrees,
                peer.announcement.nodeIdPrefix,
                includeAudio,
                phyPreference,
                corruptFirstVideoRecordForTest,
                proveVideoIntegrityForTest,
                ioConfig,
                debugTraceSession = debugTraceSession,
            ) {
                handleAuthenticatedPeer(peer, peerKey, sender, maximumChildren)
            }
        }
        if (connectingAttempts.isNotEmpty()) timeoutHandler.postDelayed({
            if (closed.get()) return@postDelayed
            val stalled = broadcasterTimedOutAttempts(connectingAttempts, senders, connectedPeers)
            if (stalled.isNotEmpty()) {
                onStatus("${stalled.size} receiver connection(s) timed out; continuing with connected receivers.")
                stalled.forEach(BluetoothVideoSender::close)
            }
        }, CONNECTION_TIMEOUT_MS)
    }

    private fun fanOutChildren(settings: VideoSettings, transport: BluetoothTransportOption): Int =
        MeshBandwidthEstimator.estimate(
            settings,
            transport,
            MeshNodeRole.SOURCE,
            plannedChildren = 0,
            measuredAggregateKbps = { perLink -> LinkCapacity.budgetKbps(transport, LinkLoadKind.FAN_OUT, perLink) },
            baseLayerShare = if (layeredAdmission) layerShare.baseShare() else null,
        ).sourceChildren

    /** Experimental temporal layering: empty unless frames proved the stream is layered. */
    private fun layerShareLabel(): String =
        (if (layeredAdmission) layerShare.baseShare() else null)
            ?.let { " | experimental layering, base layer %.0f%% of video bytes".format(it * 100) } ?: ""

    /** Re-plans fan-out from live measurements (capacity reports, expired ceilings); reopens a slot when one fits. */
    private fun refreshAdmissionIfDue() {
        val settings = admissionSettings ?: return
        val transport = admissionTransport ?: return
        val nowNs = System.nanoTime()
        if (nowNs - lastAdmissionRefreshNs < ADMISSION_REFRESH_NS) return
        lastAdmissionRefreshNs = nowNs
        val children = fanOutChildren(settings, transport)
        if (children == maximumSourceChildren) return
        val wasFull = sourceSlotsFull()
        maximumSourceChildren = children
        android.util.Log.i(
            "BluetoothVideo",
            "Source fan-out re-planned: $children children from measured ${LinkCapacity.summary(transport)}" +
                layerShareLabel(),
        )
        if (wasFull != sourceSlotsFull()) onConnectionsChanged(connectedPeers.size)
    }

    fun send(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        if (flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0) {
            if (layeredAdmission) layerShare.add(bytes.size, flags and StreamProtocol.FLAG_DISCARDABLE != 0)
            refreshAdmissionIfDue()
        }
        synchronized(packetLock) {
            val packetFlags = if (flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                StreamProtocol.FLAG_CODEC_CONFIG or (flags and StreamProtocol.FLAG_AUDIO)
            } else {
                flags
            }
            if (packetFlags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                val configuration = StreamProtocol.Packet(packetFlags, presentationTimeUs, bytes.copyOf())
                if (codecConfiguration.none {
                        it.flags == configuration.flags && it.payload.contentEquals(configuration.payload)
                    }
                ) {
                    if (codecConfiguration.size == MAX_CODEC_CONFIGURATION_PACKETS) {
                        codecConfiguration.removeAt(0)
                    }
                    codecConfiguration += configuration
                }
            }
            if (closed.get() || paused.get() && packetFlags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                return
            }
            if (packetFlags == StreamProtocol.FLAG_AUDIO) {
                val current = StreamProtocol.Packet(packetFlags, presentationTimeUs, bytes.copyOf())
                val pending = pendingAudioPacket
                if (pending == null) {
                    pendingAudioPacket = current
                    return
                }
                pendingAudioPacket = null
                val paired = StreamProtocol.pairAudioPackets(pending, current)
                readySenders().forEach {
                    it.send(paired.flags, paired.presentationTimeUs, paired.payload)
                }
                return
            }
            readySenders().forEach { it.send(packetFlags, presentationTimeUs, bytes) }
        }
    }

    fun pauseStream() {
        if (!paused.compareAndSet(false, true)) return
        synchronized(packetLock) {
            flushPendingAudioLocked()
            senders.values.forEach(BluetoothVideoSender::pauseStream)
        }
    }

    fun repeatPausedState() {
        if (!paused.get()) return
        senders.values.forEach(BluetoothVideoSender::repeatPausedState)
    }

    fun resumeStream() {
        if (!paused.compareAndSet(true, false)) return
        senders.values.forEach(BluetoothVideoSender::resumeStream)
    }

    fun endStream() {
        val active = synchronized(packetLock) {
            if (!closed.compareAndSet(false, true)) return
            flushPendingAudioLocked()
            timeoutHandler.removeCallbacksAndMessages(null)
            senders.values.toList().also {
                senders.clear()
                connectedPeers.clear()
                capacityRejections.clear()
            }
        }
        active.forEach(BluetoothVideoSender::endStream)
        onConnectionsChanged(0)
    }

    /** See [FanOutStarvationPolicy]: this phone's radio cannot serve this viewer alongside the others. */
    private fun withdrawStarvedChild(
        peerKey: String,
        sender: BluetoothVideoSender,
        transport: BluetoothTransportOption,
        carriedAggregateKbps: Int,
    ) {
        if (closed.get() || senders[peerKey] !== sender || !senders.remove(peerKey, sender)) return
        connectedPeers.remove(peerKey)
        onConnectionsChanged(connectedPeers.size)
        capacityRejections.reject(peerKey, System.nanoTime())
        starvation.remove(peerKey)
        // Media bytes only (no framing), so the learned ceiling errs conservative.
        LinkCapacity.recordCeiling(transport, LinkLoadKind.FAN_OUT, carriedAggregateKbps)
        sender.withdrawForLocalCapacity()
        android.util.Log.i(
            "BluetoothVideo",
            "Fan-out: withdrew a starved viewer; this phone carried $carriedAggregateKbps kbps across its viewers",
        )
        onStatus("A viewer was getting too little of this phone's Bluetooth airtime; it was asked to find another route.")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timeoutHandler.removeCallbacksAndMessages(null)
        synchronized(packetLock) { pendingAudioPacket = null }
        val active = senders.values.toList()
        senders.clear()
        connectedPeers.clear()
        capacityRejections.clear()
        active.forEach(BluetoothVideoSender::close)
        onConnectionsChanged(0)
    }

    private fun flushPendingAudioLocked() {
        val pending = pendingAudioPacket ?: return
        pendingAudioPacket = null
        readySenders().forEach {
            it.send(pending.flags, pending.presentationTimeUs, pending.payload)
        }
    }

    private fun handleAuthenticatedPeer(
        peer: BleL2capPeer,
        peerKey: String,
        sender: BluetoothVideoSender,
        maximumChildren: Int,
    ) {
        val previousKey = peer.announcement.recoveryFromNodeIdPrefix
            ?.let(::nodePrefixKey)
            ?.takeUnless { it == peerKey }
        val previousSender = previousKey?.let(senders::get)
        if (previousKey == null || previousSender == null ||
            !previousSender.expectsPeriodicChildHeartbeat()
        ) {
            admitSender(peerKey, sender)
            return
        }
        if (connectedPeers.size < maximumChildren) {
            onStatus("Authenticated replacement route admitted in an available source slot.")
            admitSender(peerKey, sender)
            return
        }

        val heartbeatAtProbeStart = previousSender.lastChildHeartbeatNs()
        onStatus("Authenticated replacement route; checking the previous immediate node before sending media.")
        timeoutHandler.post(object : Runnable {
            override fun run() {
                if (closed.get() || senders[peerKey] !== sender) return
                if (senders[previousKey] !== previousSender) {
                    admitSender(peerKey, sender)
                    return
                }
                if (previousSender.lastChildHeartbeatNs() > heartbeatAtProbeStart) {
                    if (connectedPeers.size < maximumChildren) {
                        onStatus("The previous immediate node is still responsive; keeping both admitted routes.")
                        admitSender(peerKey, sender)
                    } else {
                        senders.remove(peerKey, sender)
                        capacityRejections.reject(peerKey, System.nanoTime())
                        sender.withdrawForLocalCapacity()
                        onStatus(
                            "The previous immediate node is still responsive and source fan-out is full; " +
                                "the viewer will keep searching.",
                        )
                    }
                    return
                }
                val heartbeatAgeNs = System.nanoTime() - previousSender.lastChildHeartbeatNs()
                if (heartbeatAgeNs >= RECOVERY_STALE_HEARTBEAT_NS) {
                    val removed = senders.remove(previousKey, previousSender)
                    if (removed) {
                        connectedPeers.remove(previousKey)
                        previousSender.close()
                        onStatus("Removed the unresponsive previous immediate node during route recovery.")
                    }
                    admitSender(peerKey, sender)
                    return
                }
                timeoutHandler.postDelayed(this, RECOVERY_PROBE_INTERVAL_MS)
            }
        })
    }

    private fun admitSender(peerKey: String, sender: BluetoothVideoSender) {
        synchronized(packetLock) {
            if (closed.get() || senders[peerKey] !== sender || !connectedPeers.add(peerKey)) return
            codecConfiguration.forEach { packet ->
                sender.send(packet.flags, packet.presentationTimeUs, packet.payload)
            }
            onConnectionsChanged(connectedPeers.size)
            if (paused.get()) sender.pauseStream()
            if (streamStarted.compareAndSet(false, true)) onFirstConnected()
            onPeerReady()
        }
    }

    private fun readySenders(): List<BluetoothVideoSender> =
        connectedPeers.mapNotNull(senders::get)

    private fun recoveryPreviousSenderKey(peer: BleL2capPeer): String? =
        peer.announcement.recoveryFromNodeIdPrefix
            ?.let(::nodePrefixKey)
            ?.takeUnless { it == peerIdentityKey(peer) }
            ?.takeIf(senders::containsKey)

    private companion object {
        const val ADMISSION_REFRESH_NS = 2_000_000_000L
        const val CONNECTION_TIMEOUT_MS = 12_000L
        const val ROUTE_RECOVERY_GRACE_MS = 6_000L
        const val MAX_CODEC_CONFIGURATION_PACKETS = 4
        const val RECOVERY_PROBE_INTERVAL_MS = 250L
        const val RECOVERY_STALE_HEARTBEAT_NS = 6_000_000_000L
    }
}

private fun peerIdentityKey(peer: BleL2capPeer): String =
    nodePrefixKey(peer.announcement.nodeIdPrefix)

private fun nodePrefixKey(prefix: ByteArray): String =
    prefix.joinToString("") { "%02x".format(it) }

internal fun <T : Any> broadcasterTimedOutAttempts(
    attempts: Map<String, T>,
    current: Map<String, T>,
    connected: Set<String>,
): List<T> = attempts.mapNotNull { (key, attempt) ->
    attempt.takeIf { current[key] === attempt && key !in connected }
}
