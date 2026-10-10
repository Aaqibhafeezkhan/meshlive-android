package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AndroidVideoReceiver(
    private val surface: Surface,
    private val onVideoFormat: (StreamProtocol.Header) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStreamActive: (Boolean) -> Unit,
    private val onStreamState: (StreamLifecycleState) -> Unit = {},
    private val preferLowLatencyDecoder: Boolean = true,
    private val playbackDelayProfile: PlaybackDelayProfile = PlaybackDelayProfile.BALANCED,
    private val playbackDelayMs: Long = playbackDelayProfile.delayMs,
    private val preferredDecoderName: String? = null,
    private val debugPacketDelayMs: Long = 0,
    private val debugTraceSessionName: String? = null,
    private val forwardReceivedStreams: Boolean = false,
    private val expectedMediaSourceNodeId: NodeId? = null,
    private val onMediaSourceAuthenticated: (NodeId) -> Unit = {},
    private val onForwardedChildrenChanged: (Int) -> Unit = {},
    private val onVideoRecovery: (Boolean) -> Unit = {},
    private val onRouteWithdrawn: (StreamRouteWithdrawal) -> Unit = {},
    private val debugForwarderGattPriority: BleConnectionPriority? = null,
    private val debugDirectGattHeartbeat: Boolean = true,
    private val debugPresentationTiming: Boolean = false,
    private val debugReleaseAtPlayout: Boolean = true,
    private val onStopped: (String?) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    /** The current parent said the stream is paused: silence is expected, not a dead link. */
    @Volatile private var parentStreamPaused = false
    /** The parent's stream header arrived on this link: media can flow, so the trickle test may judge. */
    @Volatile private var parentMediaStarted = false
    /** LE CoC: a lease on the role's long-lived listener (CocListeners). */
    @Volatile private var cocListener: CocListenLease<BluetoothSocket>? = null
    @Volatile private var clientSocket: BluetoothSocket? = null
    @Volatile private var decoder: VideoStreamDecoder? = null
    @Volatile private var audioDecoder: OpusAudioDecoder? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var bleControlServer: BleL2capControlServer? = null
    @Volatile private var gattVideoServer: GattVideoServer? = null
    @Volatile private var classicViewerAdvertiser: BleDiscoveryAdvertiser? = null
    private val debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSessionName, "broadcast_receive")

    /** Why the last stream on this receiver ended; set before [onStopped] runs. */
    @Volatile internal var stopCause: ReceiverStopCause? = null
        private set

    fun recordSurfaceUpdate(timestampNs: Long, callbackNowNs: Long) {
        decoder?.recordSurfaceUpdate(timestampNs, callbackNowNs)
    }

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        streamToken: Int = 0,
        routeCost: Int = 0,
        streamBitrateKbps: Int = 0,
        expectedSourceNodeIdPrefix: ByteArray? = null,
        recoveryFromNodeIdPrefix: ByteArray? = null,
        phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
        ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
    ) {
        check(running.compareAndSet(false, true)) { "Receiver is already running" }
        workerThread = thread(name = "BluetoothVideoReceiver") {
            var failureNotice: String? = null
            try {
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val capabilities = LocalNodeCapabilities.forTransport(
                    transport,
                    maxChildren = if (forwardReceivedStreams && streamToken != 0) 1 else 0,
                )
                when (transport) {
                    BluetoothTransportOption.CLASSIC_RFCOMM -> {
                        serverSocket = ClassicSockets.listen(adapter, "Bluetooth Video", ClassicRole.STREAM_RECEIVER)
                        classicViewerAdvertiser = BleDiscoveryAdvertiser(
                            adapter,
                            DiscoveryAnnouncement(
                                nodeIdPrefix = identity.nodeId.toByteArray()
                                    .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                                flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                                    DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT or
                                    recoveryDiscoveryFlag(recoveryFromNodeIdPrefix),
                                capabilitySummary = capabilities.decodeCodecMask,
                                streamToken = streamToken,
                                routeCost = routeCost,
                                sequence = 0,
                                streamBitrateKbps = streamBitrateKbps,
                                recoveryFromNodeIdPrefix = recoveryFromNodeIdPrefix,
                            ),
                            onStatus,
                        ).also(BleDiscoveryAdvertiser::start)
                        onStatus(
                            if (streamToken != 0) {
                                "Classic stream requested. Waiting for the sender's RFCOMM connection."
                            } else {
                                "Classic receiver is advertising and waiting for an RFCOMM connection."
                            },
                        )
                    }
                    BluetoothTransportOption.BLE_L2CAP -> {
                        // One long-lived listener per role (CocListeners): Android 10 never frees a
                        // closed LE CoC server, so a listener per stream exhausted the A15s stack.
                        val activeServerSocket = CocListeners.listen(adapter, "receiver")
                        cocListener = activeServerSocket
                        val controlServer = BleL2capControlServer(
                            context,
                            adapter,
                            activeServerSocket.psm,
                            DiscoveryAnnouncement(
                                nodeIdPrefix = identity.nodeId.toByteArray()
                                    .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                                flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                                    recoveryDiscoveryFlag(recoveryFromNodeIdPrefix),
                                capabilitySummary = capabilities.decodeCodecMask,
                                streamToken = streamToken,
                                routeCost = routeCost,
                                sequence = 0,
                                streamBitrateKbps = streamBitrateKbps,
                                recoveryFromNodeIdPrefix = recoveryFromNodeIdPrefix,
                            ),
                            onStatus,
                            phyPreference,
                        )
                        bleControlServer = controlServer
                        controlServer.start()
                        onStatus(
                            "BLE receiver is advertising. On the other phone, choose BLE L2CAP CoC, " +
                                "refresh receivers, and connect.",
                        )
                    }
                    BluetoothTransportOption.BLE_GATT -> {
                        receiveGattLoop(
                            context,
                            adapter,
                            streamToken,
                            routeCost,
                            streamBitrateKbps,
                            identity,
                            capabilities,
                            expectedSourceNodeIdPrefix,
                            recoveryFromNodeIdPrefix,
                            phyPreference,
                            ioConfig,
                        )
                        return@thread
                    }
                }
                while (running.get()) {
                    val activeCocListener = cocListener
                    val socket = if (activeCocListener != null) {
                        if (streamToken == 0) {
                            activeCocListener.accept()
                        } else {
                            activeCocListener.accept(CONNECTION_WAIT_TIMEOUT_MS)
                        }
                    } else {
                        val activeServerSocket = serverSocket ?: break
                        if (streamToken == 0) {
                            activeServerSocket.accept()
                        } else {
                            activeServerSocket.accept(CONNECTION_WAIT_TIMEOUT_MS.toInt())
                        }
                    }
                    if (transport == BluetoothTransportOption.BLE_L2CAP) {
                        bleControlServer?.stopAdvertising()
                    }
                    clientSocket = socket
                    try {
                        receive(
                            context,
                            adapter,
                            socket,
                            transport,
                            identity,
                            capabilities,
                            expectedSourceNodeIdPrefix,
                            streamToken,
                            routeCost,
                            streamBitrateKbps,
                            phyPreference,
                            ioConfig,
                        )
                    } catch (error: LocalMediaPipelineException) {
                        // Local decoder/playback failure: stop the receiver (no retry loop).
                        throw error
                    } catch (error: StreamRouteWithdrawnException) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.ROUTE_WITHDRAWN, error)
                        if (running.get()) {
                            onRouteWithdrawn(error.reason)
                            onStatus("The immediate parent withdrew this stream route.")
                        }
                    } catch (error: EOFException) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                        if (running.get()) onStatus(
                            if (streamToken == 0) {
                                "Sender disconnected. Still listening for another sender."
                            } else {
                                "Sender disconnected."
                            },
                        )
                    } catch (error: IOException) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                        if (running.get()) onStatus(
                            if (streamToken == 0) {
                                "Sender disconnected. Still listening for another sender."
                            } else {
                                "Sender disconnected."
                            },
                        )
                    } catch (error: Exception) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.OTHER, error)
                        if (running.get()) {
                            Log.e("BluetoothVideo", "Incoming stream failed: transport=$transport token=$streamToken", error)
                            onStatus(
                                "Incoming stream stopped: ${error.message ?: error.javaClass.simpleName}." +
                                    if (streamToken == 0) " Still listening." else "",
                            )
                        }
                    } finally {
                        onStreamActive(false)
                        closeDecoders()
                        runCatching { socket.close() }
                        clientSocket = null
                        if (running.get() && streamToken == 0 &&
                            transport == BluetoothTransportOption.BLE_L2CAP
                        ) {
                            bleControlServer?.startAdvertising()
                            onStatus("Sender disconnected. BLE receiver is advertising again.")
                        }
                    }
                    if (streamToken != 0) break
                }
            } catch (error: LocalMediaPipelineException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.LOCAL_MEDIA, error)
                if (running.get()) {
                    Log.e(
                        "BluetoothVideo",
                        "Receiver stopped on a local media failure (not link loss; no reconnect): " +
                            "stage=${error.stage} | transport=$transport | token=$streamToken",
                        error,
                    )
                    failureNotice = localMediaFailureNotice(error)
                    onStatus(requireNotNull(failureNotice))
                }
            } catch (error: Exception) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.STARTUP, error)
                if (running.get()) {
                    failureNotice = receiverStartupFailureNotice(
                        transport,
                        streamToken,
                        error.message ?: error.javaClass.simpleName,
                    )
                    onStatus(requireNotNull(failureNotice))
                }
            } finally {
                running.set(false)
                closeSockets()
                bleControlServer?.close()
                bleControlServer = null
                gattVideoServer?.close()
                gattVideoServer = null
                classicViewerAdvertiser?.close()
                classicViewerAdvertiser = null
                workerThread = null
                surface.release()
                onStopped(failureNotice)
            }
        }
    }

    private fun receiveGattLoop(
        context: Context,
        adapter: BluetoothAdapter,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        recoveryFromNodeIdPrefix: ByteArray?,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        val connections = LinkedBlockingQueue<GattVideoConnection>()
        val server = GattVideoServer(
            context,
            adapter,
            DiscoveryAnnouncement(
                nodeIdPrefix = identity.nodeId.toByteArray()
                    .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                    DiscoveryAnnouncement.FLAG_GATT_TRANSPORT or
                    recoveryDiscoveryFlag(recoveryFromNodeIdPrefix),
                capabilitySummary = capabilities.decodeCodecMask,
                streamToken = streamToken,
                routeCost = routeCost,
                sequence = 0,
                streamBitrateKbps = streamBitrateKbps,
                recoveryFromNodeIdPrefix = recoveryFromNodeIdPrefix,
            ),
            phyPreference,
            onStatus,
            connections::offer,
        )
        gattVideoServer = server
        server.start()
        while (running.get()) {
            val connection = if (streamToken == 0) {
                connections.take()
            } else {
                connections.poll(CONNECTION_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return
            }
            try {
                receiveSecure(
                    context,
                    adapter,
                    BufferedInputStream(connection.inputStream, ioConfig.bufferBytes),
                    BufferedOutputStream(connection.outputStream, ioConfig.bufferBytes),
                    BluetoothTransportOption.BLE_GATT,
                    identity,
                    capabilities,
                    expectedSourceNodeIdPrefix,
                    streamToken,
                    routeCost,
                    streamBitrateKbps,
                    phyPreference,
                    ioConfig,
                    connection.receiveQueueState,
                ) { " | ${connection.phySummary()}" }
            } catch (error: LocalMediaPipelineException) {
                throw error
            } catch (error: StreamRouteWithdrawnException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.ROUTE_WITHDRAWN, error)
                if (running.get()) {
                    onRouteWithdrawn(error.reason)
                    onStatus("The immediate parent withdrew this stream route.")
                }
            } catch (error: EOFException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                if (running.get()) onStatus(
                    if (streamToken == 0) {
                        "GATT sender disconnected (${error.message ?: "end of stream"}). Advertising again."
                    } else {
                        "GATT sender disconnected (${error.message ?: "end of stream"})."
                    },
                )
            } catch (error: IOException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                if (running.get()) onStatus(
                    if (streamToken == 0) {
                        "GATT sender disconnected (${error.message ?: "I/O failure"}). Advertising again."
                    } else {
                        "GATT sender disconnected (${error.message ?: "I/O failure"})."
                    },
                )
            } catch (error: Exception) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.OTHER, error)
                if (running.get()) {
                    Log.e("BluetoothVideo", "Incoming GATT stream failed: token=$streamToken", error)
                    onStatus("Incoming GATT stream stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                onStreamActive(false)
                closeDecoders()
                connection.close()
            }
            if (streamToken != 0) return
        }
    }

    @SuppressLint("MissingPermission")
    private fun receive(
        context: Context,
        adapter: BluetoothAdapter,
        socket: BluetoothSocket,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        val readGaps = SocketReadGapStats()
        val input = BufferedInputStream(readGaps.wrap(socket.inputStream), ioConfig.bufferBytes)
        val output = BufferedOutputStream(socket.mediaOutputStream(), ioConfig.bufferBytes)
        parentStreamPaused = false
        parentMediaStarted = false
        // A relay's re-anchor closes this link, but on a starved link (mesh4-659efc38 0001/0005:
        // 0 kbps) even the close may never arrive and this child waited 30 s, unseen by the relay's
        // fresh scan. A live stream always carries audio, so a relayed parent link that delivers no
        // byte at all for PARENT_SILENCE_NS is dead: close it and let link-loss recovery reconnect
        // (to the same relay first) on a new connection.
        val startedNs = System.nanoTime()
        // A trickle defeats the silence test: mesh4-8211dc8f 0001 (M31 -> A15s, 2M) delivered ~12 kbps
        // (read gaps <= 510 ms) for 17 s; the relay's re-anchor closed its socket, but the L2CAP channel
        // stayed up while the stack drained and this child never reconnected.
        val trickle = if (DebugTunables.relayParentTrickle) RelayParentTrickle(streamBitrateKbps, startedNs) else null
        val silenceWatch = if (routeCost > 0 && DebugTunables.relayChildReanchor) {
            thread(name = "ReceiverParentSilence") {
                try {
                    while (running.get()) {
                        Thread.sleep(PARENT_SILENCE_CHECK_MS)
                        val nowNs = System.nanoTime()
                        val paused = parentStreamPaused
                        val silent = relayParentSilent(nowNs, readGaps.lastReadAtNs, startedNs, paused)
                        val trickleKbps = if (silent) {
                            null
                        } else {
                            trickle?.onTick(nowNs, readGaps.bytesRead, paused, armed = parentMediaStarted)
                        }
                        if (silent || trickleKbps != null) {
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK relay_parent_silence: silentMs=" +
                                    "${(nowNs - maxOf(readGaps.lastReadAtNs, startedNs)) / 1_000_000L} | action=close" +
                                    if (trickleKbps != null) {
                                        " | reason=trickle | kbps=$trickleKbps | streamKbps=$streamBitrateKbps"
                                    } else {
                                        " | reason=silent"
                                    },
                            )
                            onStatus("The relay link delivered nothing; reconnecting.")
                            runCatching { socket.close() }
                            return@thread
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } else {
            null
        }
        try {
            receiveSecureWithDetails(context, adapter, input, output, socket, readGaps, transport, identity,
                capabilities, expectedSourceNodeIdPrefix, streamToken, routeCost, streamBitrateKbps,
                phyPreference, ioConfig)
        } finally {
            silenceWatch?.interrupt()
        }
    }

    private fun receiveSecureWithDetails(
        context: Context,
        adapter: BluetoothAdapter,
        input: InputStream,
        output: OutputStream,
        socket: BluetoothSocket,
        readGaps: SocketReadGapStats,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        receiveSecure(
            context,
            adapter,
            input,
            output,
            transport,
            identity,
            capabilities,
            expectedSourceNodeIdPrefix,
            streamToken,
            routeCost,
            streamBitrateKbps,
            phyPreference = phyPreference,
            ioConfig = ioConfig,
            onLinkAuthenticated = { session -> learnClassicPeerAddress(socket, session.peerNodeId, "incoming") },
        ) { linkDetails(transport, socket) + readGaps.summary() }
    }

    private fun receiveSecure(
        context: Context,
        adapter: BluetoothAdapter,
        linkInput: InputStream,
        output: OutputStream,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
        receiveQueueState: () -> Pair<Int, Int>? = { null },
        onLinkAuthenticated: (SecureBluetoothSession) -> Unit = {},
        linkDetails: () -> String,
    ) {
        val linkLoad = NodeLinkLoad.open(transport, LinkDirection.RECEIVE)
        val input = linkLoad.countingInput(linkInput)
        val session = try {
            SecureBluetoothHandshake.respond(
                input,
                output,
                identity,
                capabilities,
            )
        } catch (error: Throwable) {
            linkLoad.close()
            throw error
        }
        var contentSession: EndToEndVideoSession? = null
        var groupSession: BroadcastGroupReceiverSession? = null
        var forwardedMediaOpener: EndToEndContentOpener? = null
        var streamForwarder: ReceiverStreamForwarder? = null
        var labelSourcePrefix: String? = null
        var debugMediaTrace: DebugMediaTrace? = null
        val controlRunning = AtomicBoolean(false)
        var controlThread: Thread? = null
        try {
            require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                "Sender did not authenticate support for ${transport.shortLabel}"
            }
            onLinkAuthenticated(session)
            // Experimental peer labels (default off): bit 5 in this endpoint hello only, never in
            // the link hello and never for the group (BGK1) harness.
            val labelsOn = PeerLabelFeature.enabled
            if (session.peerCapabilities.maxChildren > 1) {
                groupSession = BroadcastGroupHandshake.respond(session, identity, capabilities)
            } else {
                contentSession = EndToEndVideoHandshake.respond(session, identity, PeerLabelFeature.advertise(capabilities, labelsOn))
                    .also { it.opener.acceptLabels = labelsOn }
            }
            val endpointSession = groupSession?.endpoint ?: requireNotNull(contentSession)
            if (expectedSourceNodeIdPrefix != null) {
                require(
                    endpointSession.peerNodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                        .contentEquals(expectedSourceNodeIdPrefix),
                ) { "Stream source identity changed between discovery and connection" }
            }
            val forwardedGrant = if (endpointSession.peerCapabilities.maxChildren > 0) {
                val wrapped = session.reader.read()
                require(wrapped.type == SecureRecordType.END_TO_END_CONTENT) {
                    "Expected a forwarded-stream key grant"
                }
                val opened = endpointSession.opener.open(wrapped.payload)
                require(opened.type == EndToEndContentType.GROUP_KEY) {
                    "Expected a forwarded-stream group key"
                }
                ForwardedStreamGrantCodec.decode(opened.payload)
            } else {
                null
            }
            // Exactly one LABELS record when both endpoint hellos carry bit 5 on this hop (R4): first
            // for a direct stream, right after GROUP_KEY for a forwarded hop. Verified after the header.
            val labelsPayload = contentSession?.takeIf {
                groupSession == null &&
                    LabelRecords.streamHopNegotiated(labelsOn, session.peerNodeId, it.peerNodeId, it.peerCapabilities)
            }?.let { LabelRecords.read(session.reader, it.opener) }
            val mediaOpener = forwardedGrant?.let { grant ->
                EndToEndContentOpener(grant.key).also {
                    forwardedMediaOpener = it
                    grant.key.fill(0)
                }
            } ?: groupSession?.streamOpener ?: endpointSession.opener
            val mediaSourceNodeId = forwardedGrant?.sourceNodeId ?: endpointSession.peerNodeId
            // The forwarder's own full fingerprint was checked in its handshake; the original source is
            // known only by the 128-bit node ID in the grant, compared with verified contacts here.
            forwardedGrant?.let { PeerIdentityGate.admitForwardedSource(it.sourceNodeId) }
            require(expectedMediaSourceNodeId == null || mediaSourceNodeId == expectedMediaSourceNodeId) {
                "The replacement route belongs to a different stream source"
            }
            onMediaSourceAuthenticated(mediaSourceNodeId)
            val pendingForwardingEnvelopes = mutableListOf<MeshEnvelope>()
            val keyFrameRequests = ArrayBlockingQueue<Unit>(1)
            val forwardedChildCount = AtomicInteger(0)
            val forwardedChildAudioDropsAtJoin = AtomicLong(0)
            val downstreamKeyFramePending = AtomicBoolean(false)
            var decoderForForwardingHealth: VideoStreamDecoder? = null
            var relayAllowed = forwardReceivedStreams && streamToken != 0
            // Experimental temporal layering (off by default): when the full stream does not fit
            // receive+forward, the decision waits for real frames; a layered stream needs to
            // guarantee its child only the measured base layer. Unused while the experiment is off.
            val layeredRelayAdmission = DebugTunables.experimentalTemporalLayers
            val relayLayerShare = LayerShareMeter()
            var relayDecisionDueNs = 0L
            var layeredRelayLinkKbps = 0.0
            var layeredRelayBudgetKbps = 0.0

            val streamReader = EndToEndStreamReader(
                session.reader,
                mediaOpener,
                mediaSourceNodeId,
            ) { envelope ->
                if (layeredRelayAdmission && !envelope.isAudio && !envelope.isControl &&
                    envelope.flags and MeshEnvelope.FLAG_CODEC_CONFIG == 0
                ) {
                    relayLayerShare.add(envelope.payload.size, envelope.isDiscardable)
                }
                val activeForwarder = streamForwarder
                if (activeForwarder == null) {
                    if (relayAllowed) {
                        pendingForwardingEnvelopes += envelope
                    }
                } else {
                    val result = activeForwarder.onEnvelope(envelope)
                    if (result.queuedChildren.isNotEmpty() &&
                        envelope.flags and MeshEnvelope.FLAG_KEY_FRAME != 0
                    ) {
                        downstreamKeyFramePending.set(false)
                    }
                    if (result.keyFrameReRequest) {
                        // Experimental bounded media age: the child queue paces these (about every
                        // 1.5 s while its shed waits); the one-slot queue and the source encoder's
                        // backoff still coalesce them with local and join requests.
                        downstreamKeyFramePending.set(true)
                        keyFrameRequests.offer(Unit)
                    } else if (result.laggingChildren.isNotEmpty() &&
                        downstreamKeyFramePending.compareAndSet(false, true)
                    ) {
                        keyFrameRequests.offer(Unit)
                    }
                }
            }
            var header = streamReader.readHeader()
            parentMediaStarted = true
            forwardedGrant?.let { grant ->
                require(streamReader.meshStreamId == grant.streamId) {
                    "Forwarded-stream grant does not match its media stream"
                }
            }
            val streamLabels = LabelInbox()
            labelsPayload?.let { payload ->
                // The all-zero direct-stream binding is accepted only from the direct endpoint peer
                // (PROTOCOL); a forwarded grant naming the zero stream ID gets no labels, so a
                // forwarder cannot replay a source's direct-stream title into a forwarded stream.
                val binding = if (forwardedGrant != null) {
                    LabelBinding.stream(forwardedGrant.streamId).takeIf { it != LabelBinding.DIRECT_STREAM }
                } else {
                    LabelBinding.stream(streamReader.meshStreamId)
                }
                if (binding != null) streamLabels.accept(payload, mediaSourceNodeId, binding, "stream")
                val title = streamLabels.text(LabelKind.STREAM_TITLE)
                val sourcePrefix = mediaSourceNodeId.toString().take(DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2)
                if (title != null) {
                    labelSourcePrefix = sourcePrefix
                    SessionPeerLabels.update(sourcePrefix) { it.copy(streamTitle = title) }
                }
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK peer_labels stream_rx: received=${SignedLabelCodec.decodeContainerCountOrNull(payload)} | " +
                        "title=${title != null} | forwarded=${forwardedGrant != null}",
                )
            }
            if (BuildConfig.DEBUG) {
                Log.i("BluetoothVideo", "Authenticated stream identity: original=${mediaSourceNodeId.shortLabel()} | parent=${session.peerNodeId.shortLabel()} | stream=${streamReader.meshStreamId ?: "direct"} | forwarded=${forwardedGrant != null}")
            }
            require(
                forwardedGrant != null ||
                    endpointSession.peerCapabilities.encodeCodecMask and codecCapabilityMask(header.codecId) != 0,
            ) {
                "Sender did not authenticate support for stream codec ${header.codecId}"
            }
            require(capabilities.decodeCodecMask and codecCapabilityMask(header.codecId) != 0) {
                "This phone cannot decode stream codec ${header.codecId}"
            }
            onVideoFormat(header)
            val receiverNodeHealthPolicy = ReceiverNodeHealthPolicy(header.fps)
            if (relayAllowed && streamBitrateKbps > 0) {
                val relayLinkKbps = MeshBandwidthEstimator.perLinkKbps(streamBitrateKbps, header.fps)
                val measuredRelayBudgetKbps = LinkCapacity.budgetKbps(transport, LinkLoadKind.RELAY, relayLinkKbps)
                val coldStart = DebugTunables.relayColdStart && LinkCapacityPolicy.relayColdStart(
                    LinkCapacity.learned(transport, LinkLoadKind.RELAY),
                    System.currentTimeMillis(),
                )
                val relayBudgetKbps = LinkCapacityPolicy.relayBudgetKbps(measuredRelayBudgetKbps, relayLinkKbps, coldStart)
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK relay_admission: cold_start=%s | needKbps=%.0f | budgetKbps=%.0f | measuredKbps=%.0f | fits=%s | %s".format(
                        java.util.Locale.ROOT,
                        coldStart,
                        2 * relayLinkKbps,
                        relayBudgetKbps,
                        measuredRelayBudgetKbps,
                        relayFits(relayBudgetKbps, relayLinkKbps),
                        LinkCapacity.summary(transport),
                    ),
                )
                if (!relayFits(relayBudgetKbps, relayLinkKbps) && layeredRelayAdmission) {
                    layeredRelayLinkKbps = relayLinkKbps
                    layeredRelayBudgetKbps = relayBudgetKbps
                    relayDecisionDueNs = System.nanoTime() + RELAY_LAYER_EVIDENCE_NS
                    Log.i(
                        "BluetoothVideo",
                        ("Relay decision deferred (experimental layering): receiving and forwarding the full " +
                            "stream need %.0f kbps of %.0f; measuring whether the stream is layered").format(
                            2 * relayLinkKbps,
                            relayBudgetKbps,
                        ),
                    )
                } else if (!relayFits(relayBudgetKbps, relayLinkKbps)) {
                    relayAllowed = false
                    pendingForwardingEnvelopes.clear()
                    Log.i(
                        "BluetoothVideo",
                        "Not relaying: receiving and forwarding need %.0f kbps; measured %s".format(
                            2 * relayLinkKbps,
                            LinkCapacity.summary(transport),
                        ),
                    )
                    onStatus(
                        "This phone measured that it cannot receive and forward this stream on " +
                            "${transport.shortLabel} smoothly, so it is watching without relaying.",
                    )
                }
            }
            fun startForwarder() {
                val activeStreamId = requireNotNull(streamReader.meshStreamId) {
                    "The upstream source did not provide a forwardable mesh stream"
                }
                require(streamToken != 0) { "Forwarding needs a discovered stream token" }
                val forwardingKey = mediaOpener.copyKeyForForwarding()
                streamForwarder = try {
                    ReceiverStreamForwarder(
                        context,
                        adapter,
                        transport,
                        identity,
                        session.peerNodeId,
                        mediaSourceNodeId,
                        activeStreamId,
                        forwardingKey,
                        streamToken,
                        routeCost,
                        streamBitrateKbps,
                        header,
                        phyPreference,
                        ioConfig,
                        forwardingQueueCapacity(header.fps),
                        onStatus,
                        { count ->
                            val activeDecoder = decoderForForwardingHealth
                            downstreamKeyFramePending.set(false)
                            if (count > 0) {
                                forwardedChildAudioDropsAtJoin.set(
                                    audioDecoder?.droppedPackets ?: 0L,
                                )
                                receiverNodeHealthPolicy.childConnected(
                                    activeDecoder?.stats()?.renderedFrameInterval
                                        ?: LatencySnapshot(0, 0.0, 0.0, 0.0, 0.0, 0.0),
                                )
                                activeDecoder?.resetForwardingCadence()
                            } else {
                                receiverNodeHealthPolicy.childDisconnected()
                            }
                            forwardedChildCount.set(count)
                            onForwardedChildrenChanged(count)
                        },
                        onChildKeyFrameNeeded = { keyFrameRequests.offer(Unit) },
                        debugGattConnectionPriority = debugForwarderGattPriority,
                        // Experimental peer labels: forward only labels verified here, byte for byte.
                        forwardLabels = if (labelsOn) streamLabels::verifiedEncoded else null,
                    )
                } catch (error: Exception) {
                    forwardingKey.fill(0)
                    throw error
                }.also { forwarder ->
                    pendingForwardingEnvelopes.forEach { forwarder.onEnvelope(it) }
                    pendingForwardingEnvelopes.clear()
                    forwarder.start()
                }
            }
            if (relayAllowed && relayDecisionDueNs == 0L) {
                startForwarder()
            } else if (!relayAllowed) {
                pendingForwardingEnvelopes.clear()
            }
            debugMediaTrace = DebugMediaTrace.startIfRequested(
                context,
                debugTraceSessionName,
                "receiver",
                header.codecId,
                header.width,
                header.height,
                header.fps,
            )
            val codecName = if (header.codecId == StreamProtocol.CODEC_AV1) "AV1" else "VP9"
            streamReader.meshStreamId?.let {
                onStatus("Receiving serialized mesh stream $it with authenticated source and TTL.")
            }
            val playoutClock = playbackDelayProfile.newPlayoutClock(playbackDelayMs)
            // Latency over stalls (user direction 2026-10-07): a viewer that may relay starts with
            // room for the parent-link stall when its child joins.
            if (streamForwarder != null) playoutClock.reserve(RELAY_JOIN_RESERVE_MS)
            val videoConfig = mutableListOf<ByteArray>()
            val audioConfig = mutableListOf<ByteArray>()
            var firstAudioPresentationTimeUs: Long? = null
            var firstVideoPresentationTimeUs: Long? = null
            var timestampAlignmentLogged = false
            var audioPackets = 0L
            var bytes = 0L
            val controlFailure = AtomicReference<Throwable?>()
            val controlContentSession = endpointSession

            fun requestKeyFrame() {
                keyFrameRequests.offer(Unit)
            }

            controlRunning.set(true)
            controlThread = thread(name = "BluetoothReceiverControl") {
                try {
                    val sendPeriodicHeartbeat = shouldSendPeriodicReceiverHeartbeat(
                        transport,
                        meshLink = forwardedGrant != null || forwardReceivedStreams,
                        debugDirectGattHeartbeat = !BuildConfig.DEBUG || debugDirectGattHeartbeat,
                    )
                    val heartbeatIntervalMs = receiverHeartbeatIntervalMs(transport)
                    if (BuildConfig.DEBUG && debugDirectGattHeartbeat && transport == BluetoothTransportOption.BLE_GATT) {
                        Log.i("BluetoothVideo", "BENCHMARK direct GATT heartbeat send enabled intervalMs=$heartbeatIntervalMs")
                    }
                    if (sendPeriodicHeartbeat) {
                        SecureLinkControlProtocol.writeHeartbeat(session.writer)
                        session.writer.flush()
                    }
                    var lastCapacityReportNs = 0L
                    var reportedTrains = 0L
                    var reportedTight = false
                    var reportedAddedDelayMs = 0L
                    // Experimental stream delivery reports: what arrived, every second (link report
                    // version 3). A stream parent cannot advertise that it accepts version 3, so this
                    // needs the parent on this build too; off, reports stay version 2 and unchanged.
                    // Reports sent only for delivery carry the delivery-only flag, so the parent's
                    // lateness trend still sees full reports exactly when they were sent before.
                    val deliveryReports = DebugTunables.streamDeliveryReports
                    var lastDeliveryReportNs = 0L
                    while (controlRunning.get()) {
                        val keyFrameRequested = keyFrameRequests.poll(
                            minOf(heartbeatIntervalMs, CAPACITY_REPORT_INTERVAL_MS),
                            TimeUnit.MILLISECONDS,
                        ) != null
                        if (keyFrameRequested) {
                            EndToEndStreamProtocol.writeKeyFrameRequest(
                                session.writer,
                                controlContentSession.sealer,
                            )
                            session.writer.flush()
                        }
                        // Workstream B: tell the parent what this link measured and whether a link
                        // below this relay is tight (new trains or a changed state only).
                        val nowNs = System.nanoTime()
                        val trains = session.reader.measuredTrains
                        // Hop-local: media on the link from the parent reaches this phone late (its
                        // adaptive playout delay is pinned at the maximum); the stack can hide that
                        // backlog from the parent. Children's links are this phone's own business.
                        val tight = playoutClock.saturated
                        val addedDelayMs = playoutClock.addedDelayMs
                        val delayChanged = kotlin.math.abs(addedDelayMs - reportedAddedDelayMs) >= DELAY_REPORT_STEP_MS
                        val changed = trains > reportedTrains || tight != reportedTight || delayChanged
                        val reportIntervalNs = CAPACITY_REPORT_INTERVAL_MS * 1_000_000L
                        val fullDue = changed && nowNs - lastCapacityReportNs >= reportIntervalNs
                        val deliveryDue = deliveryReports && nowNs - lastDeliveryReportNs >= reportIntervalNs
                        if (fullDue || deliveryDue) {
                            val kbps = session.reader.arrivalCapacityKbps() ?: 0
                            val full = fullDue && (kbps > 0 || tight != reportedTight || delayChanged)
                            if (full || deliveryDue) {
                                SecureLinkControlProtocol.writeCapacityReport(
                                    session.writer,
                                    LinkCapacityReport(
                                        kbps,
                                        tight,
                                        addedDelayMs.toInt(),
                                        if (deliveryReports) streamReader.delivery.snapshot() else null,
                                        deliveryOnly = !full,
                                    ),
                                )
                                session.writer.flush()
                                lastDeliveryReportNs = nowNs
                                if (full) {
                                    reportedAddedDelayMs = addedDelayMs
                                    Log.i(
                                        "BluetoothVideo",
                                        "Link self-measurement: ${transport.shortLabel} from parent arrives at " +
                                            "$kbps kbps ($trains large-record trains)" +
                                            if (tight) "; media on this link arrives late" else "",
                                    )
                                    lastCapacityReportNs = nowNs
                                    reportedTrains = trains
                                    reportedTight = tight
                                }
                            }
                        }
                        if (sendPeriodicHeartbeat) {
                            SecureLinkControlProtocol.writeHeartbeat(session.writer)
                            session.writer.flush()
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (transport == BluetoothTransportOption.BLE_GATT) {
                        Log.w(
                            "BluetoothVideo",
                            "GATT reverse control unavailable; continuing forward media without it",
                            error,
                        )
                    } else {
                        controlFailure.compareAndSet(null, error)
                    }
                }
            }

            var streamEnded = false
            var streamPaused = false
            var resumePending = false

            fun logTimestampAlignmentIfReady() {
                if (timestampAlignmentLogged) return
                val audioPtsUs = firstAudioPresentationTimeUs ?: return
                val videoPtsUs = firstVideoPresentationTimeUs ?: return
                timestampAlignmentLogged = true
                Log.i(
                    "BluetoothVideo",
                    "Initial A/V timestamp delta: %.1f ms (video minus audio)".format(
                        java.util.Locale.US,
                        (videoPtsUs - audioPtsUs) / 1_000.0,
                    ),
                )
            }

            fun readMediaPacket(): StreamProtocol.Packet? {
                while (running.get() && !streamEnded) {
                    val content = try {
                        streamReader.readStreamContent()
                    } catch (withdrawal: StreamRouteWithdrawnException) {
                        try {
                            session.writer.write(
                                SecureRecordType.ROUTE_CONTROL_ACK,
                                StreamRouteControlProtocol.encode(withdrawal.reason),
                            )
                            session.writer.flush()
                        } catch (acknowledgementError: Exception) {
                            Log.w(
                                "BluetoothVideo",
                                "Could not acknowledge immediate route withdrawal",
                                acknowledgementError,
                            )
                        }
                        throw withdrawal
                    }
                    when (content) {
                        is ReceivedStreamContent.Media -> {
                            val integrity = StreamProtocol.verifyAndStripDebugIntegrity(content.packet)
                            val packet = integrity.packet
                            if (!packet.isAudio &&
                                packet.flags and (
                                    StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION
                                ) == 0
                            ) {
                                debugMediaTrace?.record(
                                    packet.presentationTimeUs,
                                    packet.flags,
                                    packet.payload,
                                    null,
                                    null,
                                    null,
                                    null,
                                    if (integrity.verified) "verified" else "absent",
                                )
                            }
                            if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                debugStageTrace?.record("receive", packet.presentationTimeUs, packet.payload.size)
                            }
                            return packet
                        }
                        is ReceivedStreamContent.State -> {
                            if (BuildConfig.DEBUG && debugPresentationTiming) {
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK received stream control: state=${content.state} nowNs=${System.nanoTime()}",
                                )
                            }
                            onStreamState(content.state)
                            when (content.state) {
                                StreamLifecycleState.LIVE -> {
                                    decoder?.resetRenderedCadence()
                                    resumePending = streamPaused && decoder != null
                                    streamPaused = false
                                    parentStreamPaused = false
                                    onStatus("Broadcaster resumed the stream.")
                                }
                                StreamLifecycleState.PAUSED -> {
                                    decoder?.resetRenderedCadence()
                                    streamPaused = true
                                    parentStreamPaused = true
                                    resumePending = false
                                    onStreamActive(false)
                                    onStatus("Stream paused by broadcaster.")
                                }
                                StreamLifecycleState.ENDED -> {
                                    val childAcknowledged = streamForwarder?.awaitTerminalAck(
                                        FORWARDED_END_ACK_TIMEOUT_MS,
                                    ) ?: true
                                    EndToEndStreamProtocol.writeStreamEndAck(
                                        session.writer,
                                        controlContentSession.sealer,
                                    )
                                    session.writer.flush()
                                    streamEnded = true
                                    onStreamActive(false)
                                    onStatus(
                                        if (childAcknowledged) {
                                            "Stream ended; local and downstream completion acknowledged."
                                        } else {
                                            "Stream ended locally; downstream acknowledgement timed out."
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                return null
            }

            fun handleAudioPacket(packet: StreamProtocol.Packet) {
                require(header.hasAudio) { "Received audio that was not declared in the stream header" }
                if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                    if (audioDecoder == null) audioConfig += packet.payload
                    return
                }
                playoutClock.timing(packet.presentationTimeUs)
                val activeAudioDecoder = audioDecoder ?: inLocalMediaStage(LocalMediaStage.AUDIO_DECODER_SETUP) {
                    OpusAudioDecoder(
                        header,
                        audioConfig,
                        playbackDelayProfile,
                        playoutClock,
                        debugTraceContext = context,
                        debugTraceSession = debugTraceSessionName,
                    )
                }.also { audioDecoder = it }
                inLocalMediaStage(LocalMediaStage.AUDIO_PIPELINE) { activeAudioDecoder.offer(packet) }
                if (firstAudioPresentationTimeUs == null) {
                    firstAudioPresentationTimeUs = packet.presentationTimeUs
                    logTimestampAlignmentIfReady()
                }
                audioPackets++
                bytes += packet.payload.size
            }

            var firstPacket: StreamProtocol.Packet? = null
            while (firstPacket == null && running.get() && !streamEnded) {
                val packet = readMediaPacket() ?: break
                when {
                    packet.isAudio -> handleAudioPacket(packet)
                    packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 ->
                        videoConfig += packet.payload
                    packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION != 0 -> {
                        header = header.copy(rotationDegrees = StreamProtocol.decodeDisplayRotation(packet))
                        onVideoFormat(header)
                    }
                    else -> firstPacket = packet
                }
            }
            if (firstPacket == null) return
            firstVideoPresentationTimeUs = requireNotNull(firstPacket).presentationTimeUs
            logTimestampAlignmentIfReady()

            playoutClock.timing(requireNotNull(firstPacket).presentationTimeUs)
            val activeDecoder = inLocalMediaStage(LocalMediaStage.VIDEO_DECODER_SETUP) {
                VideoStreamDecoder(
                    header,
                    surface,
                    videoConfig,
                    preferLowLatency = preferLowLatencyDecoder,
                    playbackDelayProfile = playbackDelayProfile,
                    playoutClock = playoutClock,
                    preferredDecoderName = preferredDecoderName,
                    debugPresentationTiming = debugPresentationTiming,
                    debugReleaseAtPlayout = debugReleaseAtPlayout,
                    onRecoveryStateChanged = onVideoRecovery,
                    debugTraceSession = debugTraceSessionName,
                )
            }
            decoder = activeDecoder
            decoderForForwardingHealth = activeDecoder
            onStreamActive(!streamPaused)
            onStatus(
                "Receiving end-to-end encrypted stream" +
                    (if (header.hasAudio) " with Opus audio" else "") +
                    " from ${endpointSession.peerNodeId.shortLabel()} " +
                    "over link ${session.peerNodeId.shortLabel()}: " +
                    "$codecName ${header.displayWidth}x${header.displayHeight} at ${header.fps} FPS via ${activeDecoder.decoderName}" +
                    (if (activeDecoder.lowLatencyEnabled) " (low latency)" else "") +
                    " | ${playbackDelayProfile.label} ${playoutClock.currentDelayMs} ms A/V target" +
                    linkDetails(),
            )

            var frames = 0
            var lastReportedBytes = 0L
            var lastReportNs = System.nanoTime()
            val videoReceiveInterval = RollingLatencyStats()
            val videoArrivalLateness = RollingLatencyStats()
            var previousVideoReceivedNs = 0L
            var windowLateMaxMs = 0L
            var packet = requireNotNull(firstPacket)
            while (running.get()) {
                controlFailure.get()?.let { throw IOException("Could not send child control", it) }
                inLocalMediaStage(LocalMediaStage.VIDEO_DECODER) { activeDecoder.checkFailure() }
                if (packet.isAudio) {
                    handleAudioPacket(packet)
                    packet = readMediaPacket() ?: break
                    continue
                }
                if (packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION != 0) {
                    header = header.copy(rotationDegrees = StreamProtocol.decodeDisplayRotation(packet))
                    onVideoFormat(header)
                    packet = readMediaPacket() ?: break
                    continue
                }
                // Network arrival is the only point where video may grow the adaptive delay; the
                // decoder feed and output release only look the target up (MediaPlayoutClock.targetFor).
                val arrivalTiming = videoPacketPlayoutTiming(packet, playoutClock, System.nanoTime())
                inLocalMediaStage(LocalMediaStage.VIDEO_DECODER) { activeDecoder.offer(packet) }
                if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    if (resumePending) {
                        resumePending = false
                        onStreamActive(true)
                    }
                    frames++
                    bytes += packet.payload.size
                    val nowNs = System.nanoTime()
                    if (BuildConfig.DEBUG) {
                        if (previousVideoReceivedNs > 0) videoReceiveInterval.recordNanos(nowNs - previousVideoReceivedNs)
                        previousVideoReceivedNs = nowNs
                        arrivalTiming?.let {
                            videoArrivalLateness.recordNanos(it.latenessMs * 1_000_000L)
                            if (it.latenessMs > windowLateMaxMs) windowLateMaxMs = it.latenessMs
                        }
                    }
                    if (relayDecisionDueNs != 0L && nowNs >= relayDecisionDueNs) {
                        // Experimental temporal layering only (relayDecisionDueNs stays 0 otherwise).
                        relayDecisionDueNs = 0L
                        val baseShare = relayLayerShare.baseShare()
                        val baseLinkKbps = baseShare?.let { share ->
                            MeshBandwidthEstimator.perLinkKbps((streamBitrateKbps * share).toInt(), header.fps)
                        }
                        if (baseLinkKbps != null && layeredRelayBudgetKbps >= layeredRelayLinkKbps + baseLinkKbps) {
                            Log.i(
                                "BluetoothVideo",
                                ("Relaying a layered stream (experimental): base layer %.0f%% of video bytes; " +
                                    "receive %.0f + guaranteed forward %.0f kbps fit %.0f; the child gets the rest " +
                                    "when airtime allows").format(
                                    baseShare * 100,
                                    layeredRelayLinkKbps,
                                    baseLinkKbps,
                                    layeredRelayBudgetKbps,
                                ),
                            )
                            startForwarder()
                        } else {
                            relayAllowed = false
                            pendingForwardingEnvelopes.clear()
                            Log.i(
                                "BluetoothVideo",
                                "Not relaying: receiving and forwarding need %.0f kbps (base layer %s); measured %s".format(
                                    layeredRelayLinkKbps + (baseLinkKbps ?: layeredRelayLinkKbps),
                                    baseShare?.let { "%.0f%%".format(it * 100) } ?: "not layered",
                                    LinkCapacity.summary(transport),
                                ),
                            )
                            onStatus(
                                "This phone measured that it cannot receive and forward this stream on " +
                                    "${transport.shortLabel} smoothly, so it is watching without relaying.",
                            )
                        }
                    }
                    if (nowNs - lastReportNs >= METRICS_INTERVAL_NS) {
                        val seconds = (nowNs - lastReportNs) / 1_000_000_000.0
                        val kbps = ((bytes - lastReportedBytes) * 8 / seconds / 1_000)
                            .toInt().coerceIn(0, 0xffff)
                        val decoderStats = activeDecoder.stats()
                        val audioDropsSinceChild = (
                            (audioDecoder?.droppedPackets ?: 0L) -
                                forwardedChildAudioDropsAtJoin.get()
                            ).coerceAtLeast(0L)
                        val forwardingCadence = if (forwardedChildCount.get() > 0) {
                            activeDecoder.takeForwardingCadence(receiverNodeHealthPolicy.minimumSamples)
                        } else null
                        if (forwardedChildCount.get() > 0 &&
                            receiverNodeHealthPolicy.shouldDetachChild(
                                forwardingCadence,
                                audioDropsSinceChild,
                            )
                        ) {
                            val observedP95Ms = forwardingCadence?.p95Ms
                                ?: decoderStats.renderedFrameInterval.p95Ms
                            requestKeyFrame()
                            streamForwarder?.withdrawForLocalCapacity(congested = true)
                            Log.w(
                                "BluetoothVideo",
                                ("Detached downstream child after sustained receiver-node instability: " +
                                    "p95 %.1f ms at ${header.fps} FPS, " +
                                    "$audioDropsSinceChild audio drops since child join").format(observedP95Ms),
                            )
                            onStatus(
                                "Forwarding stopped on this node because adding its child made the received " +
                                    "audio/video unstable. Local viewing continues; the source stream is unchanged.",
                            )
                        }
                        val renderedFrames = decoderStats.renderedFrames
                        val averageFrameIntervalMs = decoderStats.renderedFrameInterval.averageMs
                        val renderedFps = if (averageFrameIntervalMs > 0.0) {
                            1_000.0 / averageFrameIntervalMs
                        } else {
                            0.0
                        }
                        val transportQueue = receiveQueueState()?.let { "${it.first}/${it.second}" }
                            ?: "unmeasured"
                        if (decoderStats.queue.needsKeyFrame) requestKeyFrame()
                        onStatus(
                            ("End-to-end encrypted $codecName ${header.displayWidth}x${header.displayHeight}: $frames video frames, " +
                                "$audioPackets audio packets, " +
                                "$renderedFrames rendered/${decoderStats.submittedForRenderFrames} submitted, $kbps kbps, %.1f FPS, " +
                                "BT queue $transportQueue, " +
                                "decoder ${decoderStats.queue.depth}/${decoderStats.queue.capacity} " +
                                "(${decoderStats.queue.oldestFrameAgeMs} ms, drops ${decoderStats.queue.droppedFrames}), " +
                                "input wait p95 %.1f ms, playout p95 %.1f ms, " +
                                "shown cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms via ${activeDecoder.decoderName}" +
                                (audioDecoder?.let {
                                    ", audio via ${it.decoderName}, drops ${it.droppedPackets}"
                                }
                                    ?: "")).format(
                                renderedFps,
                                decoderStats.decoderWait.p95Ms,
                                decoderStats.scheduledRenderDelay.p95Ms,
                                decoderStats.renderedFrameInterval.p50Ms,
                                decoderStats.renderedFrameInterval.p95Ms,
                                decoderStats.renderedFrameInterval.p99Ms,
                                decoderStats.renderedFrameInterval.maxMs,
                            ) + linkDetails(),
                        )
                        if (BuildConfig.DEBUG) audioDecoder?.let {
                            Log.i("BluetoothVideo", "Audio drop reasons: ${it.dropReasonSummary()}")
                        }
                        if (BuildConfig.DEBUG) {
                            val arrival = videoReceiveInterval.snapshot()
                            val late = videoArrivalLateness.snapshot()
                            Log.i("BluetoothVideo", "Media delivery: receive cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms | arrival lateness p50/p95/max %.1f/%.1f/%.1f ms | playout delay %d ms".format(arrival.p50Ms, arrival.p95Ms, arrival.p99Ms, arrival.maxMs, late.p50Ms, late.p95Ms, late.maxMs, playoutClock.currentDelayMs))
                            // Both arms: media age this viewer holds (playout delay) plus how late video
                            // arrived beyond it in this window; upstream queue age is in stream_tx/relay_tx.
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK media_age: role=stream_rx | policy=${BoundedMediaAge.policyLabel()} | " +
                                    "playoutDelayMs=${playoutClock.currentDelayMs} | addedDelayMs=${playoutClock.addedDelayMs} | " +
                                    "lateMaxMs=$windowLateMaxMs | heldAgeMaxMs=${playoutClock.currentDelayMs + windowLateMaxMs} | " +
                                    "saturated=${playoutClock.saturated}",
                            )
                            windowLateMaxMs = 0L
                        }
                        lastReportedBytes = bytes
                        lastReportNs = nowNs
                    }
                }
                if (debugPacketDelayMs > 0) Thread.sleep(debugPacketDelayMs)
                packet = readMediaPacket() ?: break
            }
        } finally {
            linkLoad.close()
            controlRunning.set(false)
            controlThread?.interrupt()
            runCatching { controlThread?.join(1_000) }
            streamForwarder?.close()
            onForwardedChildrenChanged(0)
            debugMediaTrace?.close()
            forwardedMediaOpener?.close()
            labelSourcePrefix?.let { prefix -> SessionPeerLabels.update(prefix) { it.copy(streamTitle = null) } }
            if (groupSession != null) groupSession?.close() else contentSession?.close()
            session.close()
        }
    }

    private fun linkDetails(
        transport: BluetoothTransportOption,
        socket: BluetoothSocket,
    ): String = if (transport == BluetoothTransportOption.BLE_L2CAP) {
        val mps = runCatching { socket.maxReceivePacketSize }.getOrDefault(0)
        " | ${bleControlServer?.phySummary ?: "PHY pending"} | RX MPS $mps"
    } else if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
        // Android reports 0 for RFCOMM packet sizes; frames are at most 990 B (BTA_JV_DEF_RFC_MTU).
        " | RFCOMM MTU not exposed"
    } else {
        ""
    }

    override fun close() {
        debugStageTrace?.close()
        if (!running.getAndSet(false)) return
        closeDecoders()
        closeSockets()
        workerThread?.interrupt()
    }

    private fun closeDecoders() {
        decoder?.close()
        decoder = null
        audioDecoder?.close()
        audioDecoder = null
    }

    private fun closeSockets() {
        runCatching { clientSocket?.close() }
        clientSocket = null
        closeServerSocket()
        bleControlServer?.close()
        bleControlServer = null
        gattVideoServer?.close()
        gattVideoServer = null
        classicViewerAdvertiser?.close()
        classicViewerAdvertiser = null
    }

    /** Closes the listener (or releases the CoC lease) exactly once even when close() and the worker race. */
    private fun closeServerSocket() {
        val lease = synchronized(this) { cocListener.also { cocListener = null } }
        runCatching { lease?.close() }
        val active = synchronized(this) { serverSocket.also { serverSocket = null } } ?: return
        runCatching { active.close() }
    }

    private companion object {
        /** Reports go out at most this often, so a delay trend reaches the parent within a second. */
        const val CAPACITY_REPORT_INTERVAL_MS = 1_000L
        const val DELAY_REPORT_STEP_MS = 50L
        /** The measured child-join arrival gap on the relay's parent link (1.7 s, trend-3641df77). */
        const val RELAY_JOIN_RESERVE_MS = 1_500L
        const val METRICS_INTERVAL_NS = 1_000_000_000L
        /** Experimental temporal layering: frames observed before a deferred relay decision. */
        const val RELAY_LAYER_EVIDENCE_NS = 2_000_000_000L
        const val CONNECTION_WAIT_TIMEOUT_MS = 15_000L
        const val FORWARDED_END_ACK_TIMEOUT_MS = 2_000L

        fun forwardingQueueCapacity(fps: Int): Int = maxOf(8, (fps + 50) / 2)
    }
}

internal fun receiverStartupFailureNotice(
    transport: BluetoothTransportOption,
    streamToken: Int,
    detail: String,
): String = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM && streamToken != 0) {
    "Classic connection did not start: $detail. The sender may be busy/full or out of reach. " +
        "Pair the phones if they are unpaired, or switch the broadcaster to BLE GATT."
} else {
    "Receiver stopped: $detail"
}

internal fun usesPeriodicReceiverHeartbeat(transport: BluetoothTransportOption): Boolean =
    transport != BluetoothTransportOption.BLE_GATT

internal fun shouldSendPeriodicReceiverHeartbeat(
    transport: BluetoothTransportOption,
    meshLink: Boolean,
    debugDirectGattHeartbeat: Boolean = true,
): Boolean = usesPeriodicReceiverHeartbeat(transport) || meshLink ||
    (transport == BluetoothTransportOption.BLE_GATT && debugDirectGattHeartbeat)

internal fun shouldExpectPeriodicChildHeartbeat(
    transport: BluetoothTransportOption,
    childCanRelay: Boolean,
    debugDirectGattHeartbeat: Boolean = true,
): Boolean = usesPeriodicReceiverHeartbeat(transport) || childCanRelay ||
    (transport == BluetoothTransportOption.BLE_GATT && debugDirectGattHeartbeat)

internal fun receiverHeartbeatIntervalMs(@Suppress("UNUSED_PARAMETER") transport: BluetoothTransportOption): Long =
    2_000L

internal fun receiverHeartbeatTimeoutNs(transport: BluetoothTransportOption): Long =
    if (transport == BluetoothTransportOption.BLE_GATT) 6_000_000_000L else 8_000_000_000L

private fun recoveryDiscoveryFlag(previousUpstreamNodeIdPrefix: ByteArray?): Int =
    if (previousUpstreamNodeIdPrefix == null) 0 else DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY

/** How often, and after how much total silence, a child judges its relay parent link dead. */
internal const val PARENT_SILENCE_CHECK_MS = 500L
internal const val PARENT_SILENCE_NS = 5_000_000_000L

/**
 * A relayed parent link is dead when no byte arrived for [PARENT_SILENCE_NS] since the later of the
 * last read and the link start, unless the parent said the stream is paused.
 */
internal fun relayParentSilent(nowNs: Long, lastReadNs: Long, startedNs: Long, paused: Boolean): Boolean =
    !paused && nowNs - maxOf(lastReadNs, startedNs) >= PARENT_SILENCE_NS

/**
 * A relayed parent link that delivers fewer than 1/[RelayParentTrickle.FLOOR_DIVISOR] of the stream's
 * bitrate over a whole [PARENT_SILENCE_NS] window is starved, though not silent: mesh4-8211dc8f 0001
 * M31 -> A15s carried ~12 kbps of a 300 kbps stream (first keyframe after 17 s) with read gaps under
 * 0.5 s. HCI snoop (relaylink-*) put a starved relay child at 4-37 kbps, a healthy one at 296-378, and
 * the 2M one-PDU-per-event pair (ASUS -> A15s) at ~120. A floor of bitrate/8 (37.5 kbps at 300) closes
 * only the starved band. Windows that saw the parent's pause, or an unknown bitrate, never judge.
 * Pure (bytes and clock passed in). DEBUG `test_relay_parent_trickle=false` is the control arm.
 */
internal class RelayParentTrickle(private val streamBitrateKbps: Int, startedNs: Long) {
    private var windowStartNs = startedNs
    private var windowStartBytes = 0L
    private var pausedInWindow = false

    /**
     * Returns the window's kbps when the link is starved, else null. [bytesRead] is the running total.
     * Until [armed] (the parent's stream header arrived) the window restarts every tick: connection
     * setup, authentication and the relay's grant are not media delivery. mesh4ab-52681dbf-loop14b-1
     * 0002/0011/0012 closed healthy relayed children 2.8-3.6 s after authentication (8-29 kbps) because
     * the first window began at socket start.
     */
    fun onTick(nowNs: Long, bytesRead: Long, paused: Boolean, armed: Boolean = true): Int? {
        if (!armed) {
            windowStartNs = nowNs
            windowStartBytes = bytesRead
            pausedInWindow = false
            return null
        }
        if (paused) pausedInWindow = true
        val elapsedNs = nowNs - windowStartNs
        if (elapsedNs < PARENT_SILENCE_NS) return null
        val kbps = ((bytesRead - windowStartBytes) * 8_000_000L / elapsedNs).toInt()
        val judged = !pausedInWindow && streamBitrateKbps > 0
        windowStartNs = nowNs
        windowStartBytes = bytesRead
        pausedInWindow = paused
        return if (judged && kbps.toLong() * FLOOR_DIVISOR < streamBitrateKbps) kbps else null
    }

    companion object {
        const val FLOOR_DIVISOR = 8
    }
}

/** A relay carries its incoming link and one child link on the same radio. */
internal fun relayFits(budgetKbps: Double, perLinkKbps: Double): Boolean = budgetKbps >= 2 * perLinkKbps

/**
 * Gaps between socket reads that returned data, below all app buffering: tells radio/stack
 * delivery bursts apart from bunching inside the receiver.
 */
internal class SocketReadGapStats {
    private val gaps = RollingLatencyStats()
    @Volatile private var lastReadNs = 0L
    private val bytes = java.util.concurrent.atomic.AtomicLong(0)
    /** When a read last returned data (System.nanoTime), 0 before the first. */
    val lastReadAtNs: Long get() = lastReadNs
    /** Bytes the socket has returned so far. */
    val bytesRead: Long get() = bytes.get()

    fun wrap(input: java.io.InputStream): java.io.InputStream = object : java.io.FilterInputStream(input) {
        // Not `mark(...)`: inside a FilterInputStream that name is InputStream.mark(readlimit), a no-op here
        // (b0171c5 shipped that shadowing; mesh4-f40e9f42 read gaps were 0.0 and every relayed child
        // closed a healthy parent 5 s after start as "silent").
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it) }
    }

    private fun onRead(count: Int) {
        bytes.addAndGet(count.toLong())
        val now = System.nanoTime()
        val last = lastReadNs
        if (last != 0L) gaps.recordNanos(now - last)
        lastReadNs = now
    }

    fun summary(): String {
        val snapshot = gaps.snapshot()
        return " | socket read gap p95/p99/max %.1f/%.1f/%.1f ms".format(snapshot.p95Ms, snapshot.p99Ms, snapshot.maxMs)
    }
}
