package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReceiverStreamForwarder(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val transport: BluetoothTransportOption,
    private val identity: SigningIdentity,
    private val upstreamPeerNodeId: NodeId,
    private val sourceNodeId: NodeId,
    private val streamId: UUID,
    private val mediaKey: ByteArray,
    private val streamToken: Int,
    private val routeCost: Int,
    private val streamBitrateKbps: Int,
    private val header: StreamProtocol.Header,
    private val phyPreference: BlePhyPreference,
    private val ioConfig: VideoStreamIoConfig,
    private val queueCapacity: Int,
    private val onStatus: (String) -> Unit,
    private val onChildCountChanged: (Int) -> Unit,
    private val onChildKeyFrameNeeded: () -> Unit,
    private val debugGattConnectionPriority: BleConnectionPriority? = null,
    /**
     * Experimental peer labels (null = off, unchanged wire): the labels this phone verified from the
     * source for this stream, forwarded byte for byte after GROUP_KEY when the child's hello has bit 5.
     */
    private val forwardLabels: (() -> List<ByteArray>)? = null,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val terminalSeen = AtomicBoolean(false)
    private val withdrawing = AtomicBoolean(false)
    @Volatile private var withdrawalReason: StreamRouteWithdrawal? = null
    private val logicalStreamToken = StreamRouteToken.logicalFrom(
        streamToken,
        upstreamPeerNodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
    )
    private val downstreamStreamToken = StreamRouteToken.forAdvertiser(
        logicalStreamToken,
        identity.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
    )
    private val linkLoadSaturatedByChild = AtomicBoolean(false)
    @Volatile private var activeLinkLoad: NodeLinkLoad.Handle? = null

    /** Experimental temporal layering (off by default): this relay may thin discardable frames. */
    private val thinDiscardable = DebugTunables.experimentalTemporalLayers
    /**
     * Experimental bounded media age, global or relay-child-only (both off by default): the child
     * queue may shed stale video.
     */
    private val boundedMediaAge = BoundedMediaAge.relayChildEnabled()
    /** Relay child re-anchoring (default on; see RelayChildReanchor.kt). Attempts are per child. */
    private val reanchorEnabled = DebugTunables.relayChildReanchor
    private val reanchorBudget = RelayReanchorBudget()
    /**
     * Set when the stall watch closes a starved child on purpose (re-anchor), so the writer's
     * resulting I/O error is reported as the re-anchor and not as "Downstream flush failed".
     */
    private val reanchorClosedChild = AtomicBoolean(false)
    private val core = ReceiverNodeForwardingCore(
        identity.nodeId,
        upstreamPeerNodeId,
        streamId,
        sourceNodeId,
        queueCapacity,
        thinDiscardable,
        boundedMediaAge,
    )
    private val stateLock = Any()
    @Volatile private var advertiser: BleDiscoveryAdvertiser? = null
    /** Experimental peer labels: this forwarder's title pages for a title it verified from the source. */
    @Volatile private var titleAdvertiser: TitleAdvertiser? = null
    @Volatile private var discovery: BleL2capDiscovery? = null
    @Volatile private var child: ChildConnection? = null
    @Volatile private var childWriterThread: Thread? = null

    init {
        RelayUpstream.enter()
        require(streamToken != 0) { "A forwarded stream needs its discovery token" }
    }

    fun start() = startPresence()

    fun onEnvelope(envelope: MeshEnvelope): RelayResult {
        val result = core.receive(envelope)
        if (envelope.flags and MeshEnvelope.FLAG_TERMINAL != 0) {
            terminalSeen.set(true)
            stopPresence()
        }
        return result
    }

    fun awaitTerminalAck(timeoutMs: Long): Boolean {
        val active = child ?: return true
        return active.terminalAck.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * [congested]: this node could not receive and forward together (its own playback degraded
     * after the child joined); the relay ceiling is recorded so it is not retried at once.
     */
    fun withdrawForLocalCapacity(congested: Boolean = false) {
        if (congested) activeLinkLoad?.withdrawnForLocalCapacity()
        if (!withdrawing.compareAndSet(false, true)) return
        withdrawalReason = StreamRouteWithdrawal.LOCAL_CAPACITY
        thread(name = "ReceiverNodeWithdraw") { close() }
    }

    private fun startPresence() {
        if (closed.get() || terminalSeen.get() || core.hasChild() || !connecting.compareAndSet(false, true)) return
        thread(name = "ReceiverNodePresence") {
            try {
                if (closed.get() || terminalSeen.get() || core.hasChild()) return@thread
                val announcement = DiscoveryAnnouncement(
                    nodeIdPrefix = identity.nodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                    flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or
                        DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or transport.discoveryFlag,
                    capabilitySummary = codecCapabilityMask(header.codecId),
                    streamToken = downstreamStreamToken,
                    routeCost = (routeCost + 1).coerceAtMost(0xff),
                    sequence = 0,
                    streamWidth = header.displayWidth,
                    streamHeight = header.displayHeight,
                    streamFps = header.fps,
                    streamBitrateKbps = streamBitrateKbps,
                )
                Log.i(
                    "BluetoothVideo",
                    "Relay route tokens: upstream=${streamToken.toUInt().toString(16)}, " +
                        "logical=${logicalStreamToken.toUInt().toString(16)}, " +
                        "downstream=${downstreamStreamToken.toUInt().toString(16)}",
                )
                val newAdvertiser = BleDiscoveryAdvertiser(
                    adapter,
                    announcement,
                    onStatus,
                    startLowLatency = transport != BluetoothTransportOption.CLASSIC_RFCOMM,
                )
                newAdvertiser.start()
                if (closed.get() || terminalSeen.get() || core.hasChild()) {
                    newAdvertiser.close()
                    return@thread
                }
                advertiser = newAdvertiser
                lateinit var newDiscovery: BleL2capDiscovery
                newDiscovery = BleL2capDiscovery(
                    adapter,
                    { peer ->
                        if (discovery !== newDiscovery || child != null || !connecting.compareAndSet(false, true)) {
                            return@BleL2capDiscovery
                        }
                        stopPresence()
                        connectChild(peer)
                    },
                    {},
                    { message ->
                        if (!closed.get()) {
                            onStatus("Forwarding discovery stopped: $message")
                            restartPresence()
                        }
                    },
                    { value ->
                        val transportMatches = value.transportOption() == transport
                        value.isViewerRequest(downstreamStreamToken) && transportMatches
                    },
                    scanDurationMs = 0L,
                    reportDuplicates = true,
                    // The middle is receiving live media on the same controller. A continuous
                    // low-latency LE scan for child requests stalled its upstream link for up to
                    // ~1 s and cost the source 278-965 dropped packets while a child was joining;
                    // balanced scanning still sees the child's continuous request advertisement.
                    continuousLowLatency = false,
                    startLowLatency = false,
                )
                discovery = newDiscovery
                newDiscovery.start()
                // Experimental title pages after child discovery is up (a first-use probe or a slow
                // start never delays it). Kept only while this presence is still current: a child,
                // close or restart during the blocking start would otherwise leak a running set.
                verifiedTitle()?.let { title ->
                    runCatching { TitleAdvertiser(adapter, downstreamStreamToken, title) }.getOrNull()?.let { pages ->
                        if (pages.start() == android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                            val kept = synchronized(stateLock) {
                                (advertiser === newAdvertiser && !closed.get()).also { current ->
                                    if (current) titleAdvertiser = pages
                                }
                            }
                            if (!kept) pages.close()
                        }
                    }
                }
                onStatus("Forwarding is on. This receiver is available as the next node.")
            } catch (error: Exception) {
                if (!closed.get()) onStatus(
                    "Could not offer the received stream: ${error.message ?: error.javaClass.simpleName}",
                )
                stopPresence()
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun restartPresence() {
        if (closed.get() || terminalSeen.get() || core.hasChild()) return
        stopPresence()
        startPresence()
    }

    private fun stopPresence() {
        synchronized(stateLock) {
            discovery?.close()
            discovery = null
            advertiser?.close()
            advertiser = null
            titleAdvertiser?.close()
            titleAdvertiser = null
        }
    }

    /** The source's stream title this phone verified (signed, bound to this stream), if any. */
    private fun verifiedTitle(): String? = forwardLabels?.invoke()
        ?.mapNotNull { runCatching { SignedLabelCodec.decode(it) }.getOrNull() }
        ?.firstOrNull { it.kind == LabelKind.STREAM_TITLE }
        ?.text

    private fun connectChild(peer: BleL2capPeer) {
        val writerThread = thread(start = false, name = "ReceiverNodeChildConnect") {
            var attemptPhy: BlePhyPreference? = null
            var hopAuthenticated = false
            var pending: ChildConnection? = null
            var pendingSocket: BluetoothSocket? = null
            var pendingGatt: GattVideoConnection? = null
            try {
                if (closed.get() || terminalSeen.get()) return@thread
                val childKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                if (DebugTunables.relayEstablishFallback &&
                    RelayChildEstablishment.tracker.isMarked(childKey, android.os.SystemClock.elapsedRealtime())
                ) {
                    // Offer withdrawn for this child: it times out and falls back to another parent.
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK relay_parent_fallback: reason=establishment | side=relay | child=$childKey | action=skip_connect",
                    )
                    return@thread
                }
                val device = peer.device
                // Classic: the shared connector authenticates the link while choosing a candidate.
                var classicSecure: SecureBluetoothSession? = null
                val socket = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
                    val connected = ClassicConnector.connect(
                        appContext,
                        adapter,
                        peer.device,
                        peer.announcement.nodeIdPrefix,
                        ClassicRole.STREAM_FORWARDER,
                        verifyLinkPrefix = true,
                        totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
                        allowInquiry = false,
                        isCancelled = { closed.get() || terminalSeen.get() },
                        onSocket = { pendingSocket = it },
                        handshake = { candidate ->
                            SecureBluetoothHandshake.initiate(
                                BufferedInputStream(candidate.inputStream, ioConfig.bufferBytes),
                                BufferedOutputStream(candidate.mediaOutputStream(), ioConfig.bufferBytes),
                                identity,
                                LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                            )
                        },
                        onStatus = onStatus,
                    )
                    classicSecure = connected.session
                    connected.socket
                } else {
                    connectSocket(device, childLinkPhy(peer.announcement.nodeIdPrefix).also { attemptPhy = it })
                }
                pendingSocket = socket
                val control = if (transport == BluetoothTransportOption.BLE_L2CAP) {
                    pendingControlConnection
                } else {
                    null
                }
                val gattPriority = forwarderGattPriority(
                    streamBitrateKbps,
                    debugGattConnectionPriority,
                    BuildConfig.DEBUG,
                )
                val gatt = if (transport == BluetoothTransportOption.BLE_GATT) {
                    GattVideoClient.open(
                        appContext,
                        device,
                        childLinkPhy(peer.announcement.nodeIdPrefix).also { attemptPhy = it },
                        gattPriority,
                        onStatus,
                    )
                } else {
                    null
                }
                pendingGatt = gatt
                DebugTunables.forwarderChildIntervalUnits?.let { LeChildInterval.requestLater(device.address, it) }
                val rawInput = gatt?.inputStream ?: socket?.inputStream
                    ?: error("Downstream Bluetooth input is unavailable")
                val rawOutput = gatt?.outputStream ?: socket?.mediaOutputStream()
                    ?: error("Downstream Bluetooth output is unavailable")
                val secure = classicSecure ?: SecureBluetoothHandshake.initiate(
                    BufferedInputStream(rawInput, ioConfig.bufferBytes),
                    BufferedOutputStream(rawOutput, ioConfig.bufferBytes),
                    identity,
                    LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                )
                require(
                    secure.peerNodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                        .contentEquals(peer.announcement.nodeIdPrefix),
                ) { "Downstream identity changed between discovery and connection" }
                hopAuthenticated = true
                RelayChildEstablishment.tracker.onSuccess(AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix))
                AdaptivePhyMemory.bind(device.address, transport, secure.peerNodeId.shortLabel())
                // test_coc_phy_after_connect (default off): PHY only now, before the end-to-end handshake.
                control?.requestPhyAfterConnect("relay")
                val endpoint = EndToEndVideoHandshake.initiate(
                    secure,
                    identity,
                    PeerLabelFeature.advertise(
                        LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                        forwardLabels != null,
                    ),
                )
                require(endpoint.peerNodeId == secure.peerNodeId) {
                    "Downstream link and media identities do not match"
                }
                require(endpoint.peerCapabilities.decodeCodecMask and codecCapabilityMask(header.codecId) != 0) {
                    "Downstream child cannot decode this stream"
                }
                val grant = endpoint.sealer.seal(
                    EndToEndContentType.GROUP_KEY,
                    0,
                    ForwardedStreamGrantCodec.encode(
                        ForwardedStreamGrant(sourceNodeId, streamId, mediaKey),
                    ),
                )
                secure.writer.write(SecureRecordType.END_TO_END_CONTENT, grant)
                forwardLabels?.takeIf {
                    LabelRecords.streamHopNegotiated(true, secure.peerNodeId, endpoint.peerNodeId, endpoint.peerCapabilities)
                }?.let { provider ->
                    // R4: always one record (count 0 when nothing was verified upstream).
                    LabelRecords.write(secure.writer, endpoint.sealer, provider())
                }
                secure.writer.flush()
                gatt?.let { connection ->
                    onStatus(
                        "Downstream GATT ${gattPriority.label} priority retry " +
                            if (connection.requestConnectionPriority(gattPriority)) "queued" else "failed",
                    )
                }
                val connection = ChildConnection(
                    endpoint.peerNodeId,
                    socket,
                    control,
                    gatt,
                    secure,
                    endpoint,
                )
                pending = connection
                pendingSocket = null
                pendingGatt = null
                pendingControlConnection = null
                if (terminalSeen.get()) throw IOException("The upstream stream already ended")
                val bootstrap = core.addChild(endpoint.peerNodeId)
                if (closed.get()) throw IOException("Forwarding was turned off")
                child = connection
                onChildCountChanged(1)
                var lastSequence = -1L
                bootstrap.forEach { envelope ->
                    secure.writer.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
                    lastSequence = envelope.sequence
                }
                secure.writer.flush()
                // Pace the child link from join; its first arrival report replaces the seed.
                LinkPacerSeed.seed(connection.pacer, transport, connection.nodeId.shortLabel())?.let { kbps ->
                    Log.i("BluetoothVideo", "Child link pacer seeded at $kbps kbps until the child's first report (${transport.shortLabel})")
                }
                startChildControlReader(connection)
                startChildHeartbeatWatchdog(connection)
                onChildKeyFrameNeeded()
                onStatus(
                    "Forwarding the original encrypted ${header.displayWidth}x${header.displayHeight} stream " +
                        "to ${endpoint.peerNodeId.shortLabel()} without decoding or re-encoding the forwarded copy.",
                )
                val childHealthPolicy = RelayChildHealthPolicy(sheddingActive = boundedMediaAge)
                val congestionWithdrawn = AtomicBoolean(false)
                fun withdrawCongested(queueDepth: Int, backlogAgeMs: Long, thinnedFrames: Long, stalled: Boolean) {
                    if (!congestionWithdrawn.compareAndSet(false, true)) return
                    activeLinkLoad?.withdrawnForCongestion()
                    Log.w(
                        "BluetoothVideo",
                        "Withdrawing congested downstream route: $queueDepth queued, " +
                            "$backlogAgeMs ms behind live " + (if (thinDiscardable) {
                                "($thinnedFrames experimental discardable frames thinned)"
                            } else {
                                "(nothing was dropped)"
                            }) + if (stalled) " | write stalled" else "",
                    )
                    onStatus(
                        "Forwarding stopped because this immediate child link could not keep up. " +
                            "Local viewing and the source stream continue.",
                    )
                    withdrawForLocalCapacity()
                }
                var lastThinned = 0L
                val writeLatency = RollingLatencyStats()
                val flushLatency = RollingLatencyStats()
                val metricsStartedNs = System.nanoTime()
                var lastMetricsNs = metricsStartedNs
                var bytesWritten = 0L
                val deliveredBytes = AtomicLong(0)
                val starvation = RelayChildStarvation()
                val linkLoad = NodeLinkLoad.open(transport, LinkDirection.SEND).also { activeLinkLoad = it }
                // The writer judges the child only after a write returns, and a write into a stalled
                // link can block for many seconds (mesh4-7a6c401c 0001: ASUS -> M31 CoC at 8 kbps was
                // withdrawn 12.7 s behind live, M31 -> A15s likewise). Judge the same policy on a
                // timer too, so a stalled child is released within the policy's own limits.
                val stallWatch = thread(name = "ReceiverNodeChildStall") {
                    try {
                        while (!closed.get() && child === connection && !congestionWithdrawn.get()) {
                            Thread.sleep(STALL_CHECK_INTERVAL_MS)
                            val backlogAgeMs = core.backlogAgeMs(endpoint.peerNodeId)
                            val tickNs = System.nanoTime()
                            val starvedKbps = if (reanchorEnabled) {
                                starvation.onTick(tickNs, deliveredBytes.get(), backlogAgeMs)
                            } else {
                                null
                            }
                            val attempt = starvedKbps?.let {
                                reanchorBudget.tryAttempt(endpoint.peerNodeId.toString(), tickNs)
                            }
                            if (starvedKbps != null && attempt != null) {
                                // A new connection draws a new anchor; the child keeps its identity
                                // and reconnects through its normal link-loss recovery.
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK relay_reanchor: child=${endpoint.peerNodeId.shortLabel()} | " +
                                        "attempt=$attempt | kbpsBefore=$starvedKbps | backlogMs=$backlogAgeMs",
                                )
                                onStatus("The next phone's link was starved; reconnecting it on a fresh connection.")
                                reanchorClosedChild.set(true)
                                removeChild(connection)
                                restartPresence()
                                return@thread
                            }
                            if (childHealthPolicy.shouldWithdraw(backlogAgeMs, tickNs)) {
                                withdrawCongested(
                                    core.queueDepth(endpoint.peerNodeId),
                                    backlogAgeMs,
                                    core.thinnedFrames(endpoint.peerNodeId),
                                    stalled = true,
                                )
                                return@thread
                            }
                        }
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                val flushRunning = AtomicBoolean(ioConfig.flushPolicy.periodicFlushMs > 0)
                val flushPending = AtomicBoolean(false)
                val pendingFlushBytes = AtomicLong(0)
                val flushFailure = AtomicReference<Exception?>(null)
                val flushSignal = Object()

                fun flushNow() {
                    flushPending.set(false)
                    pendingFlushBytes.set(0)
                    val startedNs = System.nanoTime()
                    secure.writer.flush()
                    flushLatency.recordNanos(System.nanoTime() - startedNs)
                }

                fun accountWrite(wireBytes: Int, force: Boolean) {
                    flushFailure.get()?.let { throw IOException("Downstream flush failed", it) }
                    val pendingBytes = pendingFlushBytes.addAndGet(wireBytes.toLong())
                    if (
                        force || ioConfig.flushPolicy == VideoFlushPolicy.EVERY_FRAME ||
                        pendingBytes >= ioConfig.flushPolicy.byteThreshold
                    ) {
                        flushNow()
                    } else if (flushPending.compareAndSet(false, true)) {
                        synchronized(flushSignal) { flushSignal.notifyAll() }
                    }
                }

                val flushThread = if (ioConfig.flushPolicy.periodicFlushMs > 0) {
                    thread(name = "ReceiverNodeChildFlush") {
                        try {
                            while (flushRunning.get()) {
                                synchronized(flushSignal) {
                                    while (flushRunning.get() && !flushPending.get()) flushSignal.wait()
                                }
                                if (!flushRunning.get()) break
                                Thread.sleep(ioConfig.flushPolicy.periodicFlushMs)
                                if (flushPending.compareAndSet(true, false)) flushNow()
                            }
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (error: Exception) {
                            flushFailure.compareAndSet(null, error)
                        }
                    }
                } else {
                    null
                }
                try {
                    while (!closed.get() && child === connection) {
                        // Audio is never held for link credit.
                        while (!closed.get() && !core.hasQueuedAudio(endpoint.peerNodeId)) {
                            val waitNs = connection.pacer.waitNs()
                            if (waitNs <= 0L) break
                            val sliceNs = minOf(waitNs, 5_000_000L)
                            Thread.sleep(sliceNs / 1_000_000L, (sliceNs % 1_000_000L).toInt())
                        }
                        val envelope = core.take(endpoint.peerNodeId) ?: break
                        if (envelope.sequence <= lastSequence) continue
                        val encodedEnvelope = MeshEnvelopeCodec.encode(envelope)
                        val writeStartedNs = System.nanoTime()
                        val wireBytes = secure.writer.write(
                            SecureRecordType.MESH_ENVELOPE,
                            encodedEnvelope,
                        )
                        connection.pacer.onSent(wireBytes, writeStartedNs)
                        val terminal = envelope.flags and MeshEnvelope.FLAG_TERMINAL != 0
                        accountWrite(wireBytes, force = terminal)
                        writeLatency.recordNanos(System.nanoTime() - writeStartedNs)
                        bytesWritten += wireBytes
                        deliveredBytes.addAndGet(wireBytes.toLong())
                        linkLoad.add(wireBytes)
                        lastSequence = envelope.sequence
                        val nowNs = System.nanoTime()
                        val queueDepth = core.queueDepth(endpoint.peerNodeId)
                        val backlogAgeMs = core.backlogAgeMs(endpoint.peerNodeId)
                        // Experimental temporal layering: thinning means this child link fell behind
                        // (capacity evidence for this relay only; always 0 while the experiment is off).
                        val thinnedFrames = core.thinnedFrames(endpoint.peerNodeId)
                        val thinnedNow = thinnedFrames > lastThinned
                        lastThinned = thinnedFrames
                        if (backlogAgeMs >= LinkRateController.CONGESTED_BACKLOG_MS ||
                            linkLoadSaturatedByChild.getAndSet(false) || thinnedNow
                        ) {
                            linkLoad.saturated()
                        }
                        if (congestionWithdrawn.get()) break
                        if (childHealthPolicy.shouldWithdraw(backlogAgeMs, nowNs)) {
                            withdrawCongested(queueDepth, backlogAgeMs, thinnedFrames, stalled = false)
                            break
                        }
                        if (nowNs - lastMetricsNs >= METRICS_INTERVAL_NS) {
                            val seconds = (nowNs - metricsStartedNs) / 1_000_000_000.0
                            val latency = writeLatency.snapshot()
                            val flush = flushLatency.snapshot()
                            Log.i(
                                "BluetoothVideo",
                                ("Relay ${transport.shortLabel} TX: %.0f kbps | write p50/p95/p99/max " +
                                    "%.1f/%.1f/%.1f/%.1f ms | flush p95 %.1f ms | queue %d | backlog %d ms").format(
                                        bytesWritten * 8 / seconds / 1_000,
                                        latency.p50Ms,
                                        latency.p95Ms,
                                        latency.p99Ms,
                                        latency.maxMs,
                                        flush.p95Ms,
                                        queueDepth,
                                        backlogAgeMs,
                                    ) + if (thinDiscardable) " | thinned $thinnedFrames" else "",
                            )
                            if (BuildConfig.DEBUG) core.mediaAgeSummary(endpoint.peerNodeId)?.let { summary ->
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK media_age: role=relay_tx | policy=${BoundedMediaAge.policyLabel(boundedMediaAge)} | " +
                                        "peer=${endpoint.peerNodeId.shortLabel()} | backlogMs=$backlogAgeMs | $summary",
                                )
                            }
                            lastMetricsNs = nowNs
                        }
                        if (terminal) {
                            connection.terminalAck.await(TERMINAL_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            return@thread
                        }
                    }
                } finally {
                    stallWatch.interrupt()
                    linkLoad.close()
                    flushRunning.set(false)
                    synchronized(flushSignal) { flushSignal.notifyAll() }
                    flushThread?.interrupt()
                    runCatching { flushThread?.join(500L) }
                    if (flushPending.get()) runCatching { flushNow() }
                }
                withdrawalReason?.let { reason ->
                    secure.writer.write(
                        SecureRecordType.ROUTE_CONTROL,
                        StreamRouteControlProtocol.encode(reason),
                    )
                    secure.writer.flush()
                    if (!connection.routeWithdrawalAck.await(
                            ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    ) {
                        Log.w("BluetoothVideo", "Downstream route-withdrawal acknowledgement timed out")
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!closed.get() && !terminalSeen.get()) {
                    if (DebugTunables.relayEstablishFallback &&
                        isRelayEstablishmentFailure(hopAuthenticated, error.message)
                    ) {
                        val childKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                        if (RelayChildEstablishment.tracker.onFailure(childKey, android.os.SystemClock.elapsedRealtime())) {
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK relay_parent_fallback: reason=establishment | side=relay | child=$childKey | " +
                                    "action=withdraw_offer | failures=${EstablishmentFailureTracker.THRESHOLD} | " +
                                    "markMs=${EstablishmentFailureTracker.MARK_MS}",
                            )
                        }
                    }
                    if (relayChildSetupRetriesOn1M(DebugTunables.phySetupStallGuard, attemptPhy, hopAuthenticated)) {
                        val node = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                        twoMSetupFailures += node
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK link_phy: link=relay | node=$node | requested=2M | effective=failed | " +
                                "reason=setup_failed | next=1M | error=${error.javaClass.simpleName}",
                        )
                    }
                    val cause = error.message ?: error.javaClass.simpleName
                    onStatus(
                        if (reanchorClosedChild.get()) {
                            // ASUS 2026-10-10 09:52:08: the re-anchor's own close surfaced as "Downstream flush failed".
                            "Downstream node re-anchored: its starved link was closed so it reconnects on a fresh connection ($cause)"
                        } else {
                            "Downstream node disconnected: $cause"
                        },
                    )
                }
            } finally {
                reanchorClosedChild.set(false)
                pending?.let(::removeChild)
                runCatching { pendingGatt?.close() }
                runCatching { pendingSocket?.close() }
                pendingControlConnection?.let {
                    runCatching { it.close() }
                    pendingControlConnection = null
                }
                connecting.set(false)
                restartPresence()
                if (childWriterThread === Thread.currentThread()) childWriterThread = null
            }
        }
        childWriterThread = writerThread
        writerThread.start()
    }

    @Volatile private var pendingControlConnection: BleL2capControlConnection? = null

    @SuppressLint("MissingPermission")
    /** Adaptive PHY memory: 1M from the start for a child remembered bad on 2M (the child is the hop). */
    private fun childLinkPhy(childPrefix: ByteArray): BlePhyPreference {
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) return phyPreference
        val node = AdaptivePhyMemory.prefixLabel(childPrefix)
        if (DebugTunables.phySetupStallGuard && node in twoMSetupFailures) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK link_phy: link=relay | node=$node | requested=1M | effective=pending | reason=setup_retry",
            )
            return BlePhyPreference.ONE_M_ADAPTIVE
        }
        return AdaptivePhyMemory.phyFor(transport, childPrefix, phyPreference, "relay")
    }

    /**
     * Children whose 2M child-link setup failed before hop authentication in this forwarding session.
     * Hop-local and session-only: a relay's child link is not bad-2M memory evidence (relay guard).
     */
    private val twoMSetupFailures: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice, linkPhy: BlePhyPreference = phyPreference): BluetoothSocket? = when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> error("Classic children connect through ClassicConnector")
        BluetoothTransportOption.BLE_L2CAP -> {
            try {
                BleCocClient.connect(
                    appContext,
                    device,
                    onStatus,
                    linkPhy,
                    "forwarder-connect",
                    DebugTunables.forwarderCocPriority ?: BleConnectionPriority.HIGH,
                ) {
                    pendingControlConnection = it
                }
            } catch (error: Exception) {
                pendingControlConnection?.close()
                pendingControlConnection = null
                throw error
            }
        }
        BluetoothTransportOption.BLE_GATT -> null
    }

    private fun startChildControlReader(connection: ChildConnection) {
        connection.controlThread = thread(name = "ReceiverNodeChildControl") {
            try {
                while (
                    (!closed.get() && child === connection) ||
                    (withdrawalReason != null && connection.routeWithdrawalAck.count > 0L)
                ) {
                    val record = connection.secure.reader.read()
                    when (record.type) {
                        SecureRecordType.LINK_HEARTBEAT -> {
                            // A delivery-only report repeats the last full report (this relay does not use delivery).
                            SecureLinkControlProtocol.requireHeartbeat(record)?.takeUnless { it.deliveryOnly }?.let { report ->
                                // The child's own link is late: capacity evidence for this relay only.
                                if (report.linkLate) linkLoadSaturatedByChild.set(true)
                                core.setChildAddedDelayMs(connection.endpoint.peerNodeId, report.addedDelayMs)
                                if (report.arrivalKbps > 0) {
                                    LinkCapacity.recordProbe(transport, report.arrivalKbps)
                                    LinkCapacity.recordPeerLinkKbps(
                                        transport,
                                        connection.nodeId.shortLabel(),
                                        report.arrivalKbps,
                                    )
                                    if (DebugTunables.linkPacing) {
                                        connection.pacer.setMeasuredLinkKbps(report.arrivalKbps)
                                    }
                                }
                            }
                            connection.lastHeartbeatReceivedNs.set(System.nanoTime())
                        }
                        SecureRecordType.ROUTE_CONTROL_ACK -> {
                            require(
                                StreamRouteControlProtocol.decode(record.payload) == withdrawalReason,
                            ) { "Unexpected route-withdrawal acknowledgement" }
                            connection.routeWithdrawalAck.countDown()
                            return@thread
                        }
                        SecureRecordType.END_TO_END_CONTENT -> {
                            val opened = connection.endpoint.opener.open(record.payload)
                            require(opened.payload.isEmpty()) { "Invalid downstream control payload" }
                            when (opened.type) {
                                EndToEndContentType.KEY_FRAME_REQUEST -> onChildKeyFrameNeeded()
                                EndToEndContentType.STREAM_END_ACK -> {
                                    connection.terminalAck.countDown()
                                    return@thread
                                }
                                else -> error("Unexpected downstream control ${opened.type}")
                            }
                        }
                        else -> error("Unexpected downstream link record ${record.type}")
                    }
                }
            } catch (error: Exception) {
                if (!closed.get() && child === connection && !terminalSeen.get()) {
                    onStatus("Downstream control stopped: ${error.message ?: error.javaClass.simpleName}")
                    removeChild(connection)
                    restartPresence()
                }
            }
        }
    }

    private fun startChildHeartbeatWatchdog(connection: ChildConnection) {
        connection.heartbeatThread = thread(name = "ReceiverNodeChildHeartbeat") {
            try {
                val timeoutNs = receiverHeartbeatTimeoutNs(transport)
                while (!closed.get() && child === connection) {
                    Thread.sleep(receiverHeartbeatIntervalMs(transport))
                    if (System.nanoTime() - connection.lastHeartbeatReceivedNs.get() < timeoutNs) {
                        continue
                    }
                    onStatus("Downstream node stopped responding; reopening this relay slot.")
                    removeChild(connection)
                    restartPresence()
                    return@thread
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun removeChild(connection: ChildConnection) {
        detachChild(connection)
        connection.close()
        pendingControlConnection = null
    }

    private fun detachChild(connection: ChildConnection) {
        if (child !== connection) return
        child = null
        core.removeChild(connection.nodeId)
        onChildCountChanged(0)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        RelayUpstream.exit()
        stopPresence()
        val activeChild = child
        activeChild?.let(::detachChild)
        val activeWriter = childWriterThread
        if (activeWriter != null && activeWriter !== Thread.currentThread()) {
            val timeoutMs = if (withdrawalReason != null) {
                ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS + 1_000L
            } else {
                GRACEFUL_CHILD_CLOSE_TIMEOUT_MS
            }
            runCatching { activeWriter.join(timeoutMs) }
        }
        activeChild?.close()
        pendingControlConnection?.close()
        pendingControlConnection = null
        mediaKey.fill(0)
    }

    private class ChildConnection(
        val nodeId: NodeId,
        private val socket: BluetoothSocket?,
        private val control: BleL2capControlConnection?,
        private val gatt: GattVideoConnection?,
        val secure: SecureBluetoothSession,
        val endpoint: EndToEndVideoSession,
    ) : Closeable {
        val terminalAck = CountDownLatch(1)
        val routeWithdrawalAck = CountDownLatch(1)
        val lastHeartbeatReceivedNs = AtomicLong(System.nanoTime())
        /** Paces this child link at the rate the child measured (see LinkPacer). */
        val pacer = LinkPacer()
        @Volatile var controlThread: Thread? = null
        @Volatile var heartbeatThread: Thread? = null
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            controlThread?.interrupt()
            heartbeatThread?.interrupt()
            runCatching { secure.close() }
            runCatching { endpoint.close() }
            runCatching { gatt?.close() }
            runCatching { socket?.close() }
            runCatching { control?.close() }
        }
    }

    private val BluetoothTransportOption.discoveryFlag: Int
        get() = when (this) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
            BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
            BluetoothTransportOption.BLE_L2CAP -> 0
        }

    private companion object {
        const val METRICS_INTERVAL_NS = 5_000_000_000L
        /** How often a relay child's backlog is judged while its writer may be blocked. */
        const val STALL_CHECK_INTERVAL_MS = 500L
        const val GRACEFUL_CHILD_CLOSE_TIMEOUT_MS = 2_000L
        const val ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS = 4_000L
        const val TERMINAL_ACK_TIMEOUT_MS = 2_000L
    }
}

/**
 * A relay's child-link attempt that failed before hop authentication on a 2M request is retried on 1M
 * for that child (mesh4-c0200edf-loop7-1 0004: the ASUS relay's CoC connect to the A15s failed within
 * 6 ms while its 2M PHY update was in flight, twice; the same pair's call link dies after the 2M PHY
 * update, ec20607). The child re-advertises and is rediscovered under its current address. DEBUG
 * `test_phy_setup_stall=false` is the control arm.
 */
internal fun relayChildSetupRetriesOn1M(guardOn: Boolean, attemptPhy: BlePhyPreference?, hopAuthenticated: Boolean): Boolean =
    guardOn && !hopAuthenticated && attemptPhy == BlePhyPreference.TWO_M
