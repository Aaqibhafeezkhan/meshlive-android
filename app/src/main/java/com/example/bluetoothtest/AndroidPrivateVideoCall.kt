package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AndroidPrivateVideoCall(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val transport: BluetoothTransportOption,
    private val codec: VideoCodec,
    private val availableCodecs: List<VideoCodec>,
    private val settings: VideoSettings,
    private val rotationDegrees: Int,
    private val remoteSurfaceProvider: () -> Surface,
    private val playbackDelayProfile: PlaybackDelayProfile,
    private val phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
    private val ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
    private val supportedVideoModes: (VideoCodec, Int) -> List<DeviceVideoMode>,
    private val onStatus: (String) -> Unit,
    private val onPhase: (PrivateCallState) -> Unit,
    private val onIncoming: (String) -> Unit,
    private val onRemoteVideoFormat: (StreamProtocol.Header) -> Unit,
    private val onRemoteCameraEnabled: (Boolean) -> Unit,
    private val onRemoteMuted: (Boolean) -> Unit,
    private val onStartLocalMedia: (VideoSettings, Int, VideoCodec, PlaybackDelayProfile) -> Unit,
    private val onLocalKeyFrameNeeded: () -> Unit,
    private val onEnded: (String) -> Unit,
    /** About 1 s of this phone's sending on the call link, for Automatic rate control. */
    private val onLinkSample: (LinkRateSample) -> Unit = {},
    private val debugTraceSessionName: String? = null,
    private val debugGattDeferConnectionPriority: Boolean = false,
    debugPipelineTiming: Boolean = false,
    /** Experimental voice calls (IntercomFeatures, default off), read when this endpoint is created. */
    private val voiceCallsEnabled: Boolean = false,
    /** The ringing call's offered type changed (experimental voice calls only). */
    private val onIncomingCallType: (CallType) -> Unit = {},
    /**
     * Experimental store-and-forward messages (StoreForwardFeature, default off). Null: this
     * endpoint never reads or accepts hop type 11 and behaves exactly as before.
     */
    private val storeForwardNode: (() -> StoreForwardNode)? = null,
    /** A message exchange on this endpoint finished (experimental store-and-forward only). */
    private val onMessageSync: (StoreForwardSyncResult) -> Unit = {},
    /**
     * An outgoing message exchange failed before a BSF1 result existed (link or BMH2 handshake).
     * Reported for the experimental automatic sync backoff; unused by default.
     */
    private val onMessageSyncFailed: (String) -> Unit = {},
    /**
     * An outgoing LE CoC connection (PSM read, channel open or connect) failed before the encrypted
     * handshake. Reported so the experimental CoC -> GATT fallback can retry; unused by default.
     */
    private val onCocConnectFailed: (String) -> Unit = {},
    /**
     * Experimental call encoder fit (CallEncoderFit, default off: returns its input): the geometry
     * this phone's encoder can sustain in a call, chosen before the call header is sent.
     */
    private val fitCallVideo: (VideoSettings, VideoCodec) -> VideoSettings = { requested, _ -> requested },
    /** Experimental peer labels: verified labels for the peer with this 12-hex prefix changed. */
    private val onPeerLabels: (String) -> Unit = {},
) : Closeable {
    private sealed interface OutboundControl {
        data class Signal(val message: PrivateCallSignalMessage, val closeAfter: Boolean = false) : OutboundControl
        data object Header : OutboundControl
        data object KeyFrameRequest : OutboundControl
        data class DisplayRotation(val degrees: Int) : OutboundControl
        /** Experimental peer labels: one `BLB1` container (BME1 type 10), ordered with the signals. */
        class Labels(val payload: ByteArray) : OutboundControl
    }

    private data class OutboundMedia(
        val flags: Int,
        val presentationTimeUs: Long,
        val payload: ByteArray,
        val debugQueuedAtNs: Long = 0L,
        val queuedAtNs: Long = System.nanoTime(),
    ) {
        val isAudio: Boolean get() = flags and StreamProtocol.FLAG_AUDIO != 0
        val isKeyFrame: Boolean get() = flags and StreamProtocol.FLAG_KEY_FRAME != 0
        val isDiscardable: Boolean get() = flags and StreamProtocol.FLAG_DISCARDABLE != 0

        fun asRecoveryPoint(): OutboundMedia = copy(
            flags = flags or StreamProtocol.FLAG_DISCONTINUITY,
        )
    }

    private class DebugTxCounters {
        val videoInputs = AtomicLong()
        val audioInputs = AtomicLong()
        val configInputs = AtomicLong()
        val videoWrites = AtomicLong()
        val audioWrites = AtomicLong()
        val configWrites = AtomicLong()
        val queueDrops = AtomicLong()
        val inactiveInputs = AtomicLong()
        val mutedAudio = AtomicLong()
        /** Audio records removed by the call queue (queueDrops counts audio and video together). */
        val audioDrops = AtomicLong()
    }

    private data class CallConnection(
        val input: InputStream,
        val output: OutputStream,
        val description: String,
        val closeConnection: () -> Unit,
        /** The RFCOMM/CoC socket, for learning a Classic peer's address after authentication. */
        val socket: BluetoothSocket? = null,
        /** A BMH2 link already authenticated while choosing among Classic candidates. */
        val link: SecureBluetoothSession? = null,
        /** LE peer address, binding the authenticated hop to this link for [AdaptivePhyMemory]. */
        val address: String? = null,
        /** Current LE PHY summary ("PHY TX 2M / RX 2M"); empty when unknown or Classic. */
        val phySummary: () -> String = { "" },
    ) : Closeable {
        override fun close() = closeConnection()
    }

    private val appContext = context.applicationContext
    private val identity = AndroidNodeIdentityStore().getOrCreate()
    private val stackFingerprint = privateCallStackFingerprint()
    /** Experimental legacy low delay (off by default), read once; direct calls only. */
    private val legacyLowDelay = DebugTunables.callLegacyLowDelay
    /** Experimental measured relayed-call cap (off by default), read once; relayed calls only. */
    private val relayedMeasuredCap = DebugTunables.relayedCallMeasuredCap

    /** Relayed route settings: the legacy cap, or (experimental) this phone's measured-link budget. */
    private fun routeSettings(hops: Int): VideoSettings {
        val budget = if (hops > 0 && relayedMeasuredCap) {
            relayedCallMeasuredBudgetBps(
                runCatching { LinkCapacity.measuredLinkKbps(transport) }.getOrNull(),
                privateCallAudioBitRate(transport, stackFingerprint),
            )
        } else {
            null
        }
        val routed = privateCallVideoSettingsForRoute(settings, hops, budget)
        if (hops > 0) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call relayed_cap: relayHops=$hops | measuredCap=$relayedMeasuredCap | " +
                    "budgetKbps=${budget?.div(1_000) ?: "none"} | fps=${routed.fps} | bitrateKbps=${routed.bitRate / 1_000}",
            )
        }
        return routed
    }
    /** The decode mask this endpoint advertised in its end-to-end hello (privateCallEndpointDecodeMask). */
    @Volatile private var advertisedDecodeMask = 0
    private val capabilities = privateCallCapabilities(transport, stackFingerprint)

    /**
     * Experimental rotating discovery IDs (RotatingDiscoveryFeature, default off), fixed for this
     * endpoint: call offers advertise version 5 with the role-0 RDI, link hellos carry bit 3 and
     * the first hop record each way is `BDO1`. Off: [linkCapabilities] equals [capabilities].
     */
    private val rotatingIds = RotatingDiscoveryFeature.enabled.also { on ->
        if (on) RotatingDiscovery.init(context, identity.nodeId)
    }
    /**
     * Classic call availability keeps version 2: the Classic address is permanent and paired peers
     * resolve every address anyway (RV2), and ClassicConnector finds the callee by its fixed prefix.
     */
    private val rotatingAdvert = rotatingIds && transport != BluetoothTransportOption.CLASSIC_RFCOMM
    private val linkCapabilities = if (rotatingIds) {
        capabilities.copy(transportMask = capabilities.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
    } else {
        capabilities
    }
    /** Epoch of the call offer on air (rotating IDs only); -1 when none. */
    @Volatile private var advertisedEpoch = -1L
    /** The offer on air is a Visible-by-handle window (version 2) rather than a rotating ID. */
    @Volatile private var advertisedVisible = false
    /** Advances when a boundary restart closes the listener on purpose, so its accept thread stays quiet. */
    @Volatile private var listenerGeneration = 0
    /** Counts stopAvailability calls not caused by an incoming connection (calls, exchanges, close). */
    @Volatile private var fullAvailabilityStops = 0
    private val boundaryListener: (Long) -> Unit = { epoch -> onDiscoveryBoundary(epoch) }
    /**
     * The end-to-end hello's decode mask (privateCallEndpointDecodeMask), fixed when the endpoint is
     * created. Only a VP9-restricted phone scans its platform decoders for it.
     */
    private val endpointDecodeMask: Int = privateCallEndpointDecodeMask(
        restrictedMask = capabilities.decodeCodecMask,
        platformMask = if (privateCallDecodeRestricted(stackFingerprint, transport)) {
            VideoCodec.availableDecoders().fold(0) { mask, decoder -> mask or codecCapabilityMask(decoder.protocolId) }
        } else {
            capabilities.decodeCodecMask
        },
        fallback = DebugTunables.callCodecFallback,
    )
    private val running = AtomicBoolean(true)
    private val callRunning = AtomicBoolean(false)
    private val endedCallbackSent = AtomicBoolean(false)
    /** Set when this phone's own media pipeline ended the call; reported instead of "Call ended". */
    @Volatile private var localFailureMessage: String? = null
    private val controls = BoundedControlQueue<OutboundControl>(
        MemoryThermalPolicy.controlQueueCapacity(MemoryThermalPolicy.CALL_CONTROL_QUEUE_CAPACITY),
        "call",
    )
    /** Experimental bounded media age, read once before the queue is sized. */
    private val boundedMediaAgeOn = BoundedMediaAge.enabled
    private val mediaCapacity = settings.queueCapacity.coerceAtLeast(16)
    /**
     * Call audio has its own room in the send queue (default on; control arm
     * `test_call_audio_headroom=false`), so audio waiting behind one long video write no longer
     * counts against the video budget. See [privateCallAudioHeadroomPackets].
     */
    private val audioHeadroom = !boundedMediaAgeOn && DebugTunables.callAudioHeadroom
    // Bounded media age adds headroom past the nominal capacity for records it never drops or evicts
    // (audio, codec configuration, recovery keyframes); off, the queue is exactly as before.
    private val media = ArrayBlockingQueue<OutboundMedia>(
        mediaCapacity + when {
            boundedMediaAgeOn -> BoundedMediaAge.criticalHeadroom(mediaCapacity)
            audioHeadroom -> privateCallAudioHeadroomPackets()
            else -> 0
        },
    )
    @Volatile private var state: PrivateCallState? = null
    /** Call-type negotiation of the current call (experimental voice calls; VIDEO when off). */
    @Volatile private var negotiation: VoiceCallNegotiation? = null
    @Volatile private var muted = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    /** LE CoC availability: a lease on the role's long-lived listener (CocListeners). */
    @Volatile private var cocListener: CocListenLease<BluetoothSocket>? = null
    @Volatile private var classicAdvertiser: BleDiscoveryAdvertiser? = null
    @Volatile private var cocControlServer: BleL2capControlServer? = null
    @Volatile private var gattServer: GattVideoServer? = null
    @Volatile private var bleControlConnection: BleL2capControlConnection? = null
    /** System.nanoTime when this call's hop link authenticated (0 = not yet); for early-loss memory. */
    @Volatile private var authenticatedAtNs = 0L
    /** System.nanoTime of the last call-writer flush that returned (send-side progress). */
    @Volatile private var lastSendProgressNs = 0L
    /** Advertised prefix of a relayed outgoing offer (null for direct or incoming calls). */
    @Volatile private var offerViaPrefix: String? = null
    /** System.nanoTime when this call first became ACTIVE (0 = not yet); the early-loss window start. */
    @Volatile private var activeAtNs = 0L
    /** PHY this endpoint asked for on the current LE link (both roles; 2M unless remembered or retried). */
    @Volatile private var requestedLinkPhy: BlePhyPreference = BlePhyPreference.TWO_M
    /** The Classic candidate socket being opened, so close() can abort a page/SDP in progress. */
    @Volatile private var pendingClassicSocket: BluetoothSocket? = null
    @Volatile private var connection: CallConnection? = null
    @Volatile private var secureSession: SecureBluetoothSession? = null
    @Volatile private var contentSession: EndToEndVideoSession? = null
    @Volatile private var listenerThread: Thread? = null
    @Volatile private var connectionThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var readerThread: Thread? = null
    /** The running store-and-forward exchange, if this link is one (never a call at the same time). */
    @Volatile private var messageSync: StoreForwardLinkSession? = null
    /** A connection that arrived during an exchange; answered as a call once the exchange stops. */
    @Volatile private var pendingCallConnection: CallConnection? = null
    @Volatile private var mediaReceiver: PrivateCallMediaReceiver? = null
    @Volatile private var lastHeartbeatNs = 0L
    @Volatile private var activeSettings = settings
    @Volatile private var activeCodec = codec
    @Volatile private var activePlaybackDelayProfile = playbackDelayProfile
    @Volatile private var relayHops = 0
    @Volatile private var peerMaxBitrateKbps: Int? = null
    private var droppingVideoUntilKeyFrame = false
    private var droppedMediaPackets = 0L
    /**
     * Experimental temporal layering (off by default): skip verified-discardable call video while
     * the call queue is behind or full, before dropping reference frames. Off: nothing is thinned.
     */
    private val thinDiscardable = DebugTunables.experimentalTemporalLayers
    private val thinnedVideoFrames = AtomicLong()

    /**
     * Experimental call video fragments (off by default, see CallVideoFragments): advertised in this
     * endpoint's signed hello and used only when the peer advertised them too on a direct call.
     */
    private val videoFragmentsOn = DebugTunables.callVideoFragments

    /** Experimental peer labels (PeerLabelFeature, default off), fixed for this endpoint. */
    private val labelsOn = PeerLabelFeature.enabled
    @Volatile private var peerAcceptsLabels = false
    /** R3: at most one LABELS record received before this session's Invite, verified on Invite. */
    private var bufferedLabels: ByteArray? = null
    private var callLabels: LabelInbox? = null
    /** R1: this callee's own name, signed at Invite time, sent only with Accept (or at Ringing to a verified contact). */
    @Volatile private var pendingAcceptLabels: ByteArray? = null
    /** Subject typed for this outgoing call (captured when the call starts). */
    @Volatile private var outgoingSubject: String? = null

    /** Experimental speech-led call playout (off by default, see CallPlayoutPolicy). */
    private val speechLedPlayout = DebugTunables.callSpeechLedPlayout
    @Volatile private var peerAcceptsVideoFragments = false
    private val videoReassembler = CallVideoReassembler()
    private val fragmentedVideoFrames = AtomicLong()
    private val interleavedAudioPackets = AtomicLong()

    /**
     * Experimental bounded media age (off by default, see BoundedMediaAge): call video may wait in
     * this queue only as long as keeps speech-synchronous video near the 400 ms mouth-to-ear budget.
     * Audio is never shed by this policy. Off: unchanged. The queue-age measurements are logged in
     * both arms (BENCHMARK media_age).
     */
    private val boundedAge = boundedMediaAgeOn
    private var criticalOverflowDrops = 0L
    private val callShedder = MediaAgeShedder(BoundedMediaAge.CALL_SHED_COOLDOWN_NS)
    private val pauseGovernor = CallVideoPauseGovernor()
    /** Shed or paused video: overuse evidence for rate control (0 while the experiment is off). */
    private val boundedShedFrames = AtomicLong()
    private var boundedPausedFrames = 0L
    @Volatile private var peerAddedDelayMs = 0
    /**
     * Bounded media age only: every queue change in send() and the writer's take share this monitor,
     * so a shed is atomic for the writer. Unused while the experiment is off.
     */
    private val mediaLock = Object()
    private val videoQueueWaitMax = WindowMax()
    private val audioQueueWaitMax = WindowMax()
    // Hop-local rate evidence, as for streams: what this phone offered and sent, how old its
    // backlog is, and what the peer reports about the media it receives from this phone.
    private val sampleOfferedBytes = AtomicLong()
    @Volatile private var peerMeasuredKbps = 0
    /** Experimental call-link pacing (DebugTunables.callLinkPacing): socket transports only. */
    private val pacer = LinkPacer()
    private val pacedWrites = callLinkPaced(transport, DebugTunables.callLinkPacing, DebugTunables.linkPacing)
    @Volatile private var peerLateUntilNs = 0L
    private val peerLateness = PeerLatenessTrend()
    // Version 3 delivery reports (hop-local, 2026-10-09): media this phone received from the peer,
    // what the peer reports it received from this phone (against this phone's own write times), and
    // whether the peer accepts version 3. Recreated for every link.
    @Volatile private var receivedDelivery = LinkDeliveryCounter()
    @Volatile private var peerDelivery = PeerDeliveryEstimate()
    @Volatile private var deliveryReports = CallDeliveryReports(enabled = true)
    @Volatile private var largeFrameGraceUntilNs = 0L
    private var typicalVideoBytes = 0L
    private val pendingAudioPackets = ArrayList<OutboundMedia>(CONSTRAINED_COC_AUDIO_BATCH_SIZE)
    private var lastRecoveryKeyFrameRequestNs = 0L
    private val debugTxCounters = if (BuildConfig.DEBUG) DebugTxCounters() else null
    private val debugTxFinalReported = AtomicBoolean(false)
    private var lastDebugTxReportNs = 0L
    private val debugPipelineTiming = BuildConfig.DEBUG && debugPipelineTiming
    private val debugAudioBatchWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugVideoQueueWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugAudioQueueWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugMediaWriteWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugMediaFlushWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private var debugStageTrace: DebugStageTrace? = null

    @SuppressLint("MissingPermission")
    fun startAvailability() {
        check(running.get()) { "Private-call endpoint is closed" }
        check(listenerThread == null && gattServer == null) { "Private-call availability already started" }
        openCallListener()
        if (rotatingAdvert) RotatingDiscovery.addBoundaryListener(boundaryListener)
        onStatus("Available for a private call over ${transport.shortLabel}")
    }

    /**
     * Rotating IDs: at an epoch boundary an idle listener stops (break-before-make), stays silent
     * for 1-8 s (RV12) and reopens with the new RDI on a new advertising set; on LE CoC stacks with
     * per-session listeners the PSM changes too (RV1). A call or connection in progress keeps its
     * link (availability is already stopped then); the next listener uses the current epoch (RV4).
     */
    private fun onDiscoveryBoundary(epoch: Long) {
        if (!running.get()) return
        // Also restarts when a Visible-by-handle window starts or ends (always on a new set, RV5).
        if (epoch == advertisedEpoch && RotatingDiscovery.visibleByHandle() == advertisedVisible) return
        thread(name = "PrivateCallIdRotation") {
            val silenceMs = RotatingDiscovery.boundarySilenceMs()
            synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                if (listenerThread == null && gattServer == null) return@thread
                listenerGeneration++
                stopAvailability(forActiveConnection = false)
                advertisedEpoch = -1L
            }
            try {
                Thread.sleep(silenceMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            val stopsBefore = synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                if (listenerThread != null || gattServer != null) return@thread
                fullAvailabilityStops
            }
            // Outside the monitor: opening waits for advertising callbacks on the main thread, where
            // startOutgoing may be waiting for this monitor (review finding 2026-10-10).
            val opened = runCatching { openCallListener() }
            synchronized(this) {
                // A call, exchange or close stopped availability while this reopen ran: stop what it
                // published, exactly as that stop would have. An incoming connection does not count.
                if (fullAvailabilityStops != stopsBefore || !running.get()) {
                    stopAvailability(forActiveConnection = false)
                    return@thread
                }
            }
            opened.onSuccess {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK rotid_restart: role=call | epoch=$advertisedEpoch | visible=$advertisedVisible | " +
                            "silenceMs=$silenceMs | transport=${transport.shortLabel}",
                    )
                }
            }.onFailure { error ->
                // The old ID never comes back (design 4.4); the status line says availability dropped.
                onStatus(
                    "Private-call availability paused while changing discovery ID: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCallListener() {
        val generation = listenerGeneration
        val visible = rotatingAdvert && RotatingDiscovery.visibleByHandle()
        val rotatingEntry = if (rotatingAdvert && !visible) RotatingDiscovery.advertise(RotatingDiscoveryIds.ROLE_CALL_OFFER) else null
        advertisedVisible = visible
        advertisedEpoch = rotatingEntry?.epoch ?: if (visible) RotatingDiscovery.currentEpoch() else -1L
        val announcement = DiscoveryAnnouncement(
            nodeIdPrefix = rotatingEntry?.rdi ?: identity.nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            version = if (rotatingEntry != null) {
                DiscoveryAnnouncement.ROTATING_VERSION
            } else {
                DiscoveryAnnouncement.FIXED_PREFIX_VERSION
            },
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or when (transport) {
                    BluetoothTransportOption.CLASSIC_RFCOMM ->
                        DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
                    BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
                    BluetoothTransportOption.BLE_L2CAP -> 0
                },
            capabilitySummary = capabilities.encodeCodecMask and capabilities.decodeCodecMask,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
        )
        when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                val activeServer = ClassicSockets.listen(adapter, "Bluetooth Private Video Call", ClassicRole.PRIVATE_CALL)
                serverSocket = activeServer
                classicAdvertiser = BleDiscoveryAdvertiser(adapter, announcement, onStatus)
                    .also(BleDiscoveryAdvertiser::start)
                listenerThread = thread(name = "PrivateCallClassicAccept") {
                    try {
                        acceptIncoming(socketConnection(activeServer.accept()))
                    } catch (error: Exception) {
                        reportListenerFailure(error, generation)
                    }
                }
            }
            BluetoothTransportOption.BLE_L2CAP -> {
                // One long-lived listener per role (CocListeners): Android 10 never frees a closed
                // LE CoC server, so a listener per availability exhausted the A15s stack.
                val activeServer = CocListeners.listen(adapter, "private-call")
                cocListener = activeServer
                cocControlServer = BleL2capControlServer(
                    appContext,
                    adapter,
                    activeServer.psm,
                    announcement,
                    onStatus,
                    phyPreference,
                ).also(BleL2capControlServer::start)
                listenerThread = thread(name = "PrivateCallCocAccept") {
                    try {
                        val socket = activeServer.accept()
                        cocControlServer?.stopAdvertising()
                        acceptIncoming(socketConnection(socket))
                    } catch (error: Exception) {
                        reportListenerFailure(error, generation)
                    }
                }
            }
            BluetoothTransportOption.BLE_GATT -> {
                gattServer = GattVideoServer(
                    appContext,
                    adapter,
                    announcement,
                    phyPreference,
                    onStatus,
                ) { activeConnection ->
                    // During an experimental message exchange a new connection may be a call that
                    // preempts it (acceptIncoming); otherwise exactly as before.
                    if (!running.get() || (callRunning.get() && messageSync == null)) {
                        activeConnection.close()
                    } else {
                        acceptIncoming(gattConnection(activeConnection))
                    }
                }.also(GattVideoServer::start)
            }
        }
    }

    /**
     * Experimental store-and-forward (second review N3): while an exchange holds the endpoint, a call
     * listener stays open so an incoming call can preempt the exchange instead of being refused.
     * On LE CoC this costs one more listen registration (counted by CocRegistrations).
     */
    private fun reopenListenerForCalls() {
        if (!running.get()) return
        runCatching {
            when (transport) {
                BluetoothTransportOption.BLE_GATT -> if (gattServer == null) openCallListener()
                else -> if (serverSocket == null && cocListener == null) {
                    cocControlServer?.close()
                    cocControlServer = null
                    openCallListener()
                }
            }
        }.onFailure { Log.w("BluetoothVideo", "Store-forward: call listener during an exchange unavailable", it) }
    }

    /** What this phone sends in the current call: VOICE starts no camera, encoder or video packet. */
    val localCallType: CallType
        get() = negotiation?.localType ?: CallType.VIDEO

    @Synchronized
    fun startOutgoing(peer: BleL2capPeer, callType: CallType = CallType.VIDEO): Boolean {
        if (!canStartOutgoingPrivateCall(running.get(), state, callRunning.get())) return false
        // An offer above the app's relay limit is refused, not clamped (PrivateCallRelayChain).
        val hops = PrivateCallRelayChain.callerRelayHops(peer.announcement.routeCost) ?: return false
        // Rotating IDs, phase 1: relays never mirror a version 5 offer, and the end-to-end opening
        // (BDX1) is not built yet, so a relayed version 5 offer cannot be verified: refuse it.
        // Classic offers never rotate (rotatingAdvert), so a version 5 Classic offer is refused too.
        if (peer.announcement.rotating && (
                !rotatingIds || peer.announcement.routeCost != 0 ||
                    peer.announcement.flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT != 0
                )
        ) {
            return false
        }
        negotiation = VoiceCallNegotiation.outgoing(voiceCallsEnabled, callType)
        outgoingSubject = PeerLabelFeature.pendingCallSubject.also { PeerLabelFeature.pendingCallSubject = null }
        relayHops = hops
        activeSettings = routeSettings(relayHops)
        val initial = PrivateCallState.outgoing()
        state = initial
        onPhase(initial)
        stopAvailability(forActiveConnection = false)
        connectionThread = thread(name = "PrivateCallConnect") {
            val direct = peer.announcement.routeCost == 0
            offerViaPrefix = if (direct) null else AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
            val firstPhy = if (transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                AdaptivePhyMemory.phyFor(
                    transport,
                    peer.announcement.nodeIdPrefix,
                    phyPreference,
                    "call",
                    relayedOffer = !direct,
                )
            } else {
                phyPreference
            }
            // Experimental test_coc_single_initiator (default off): the higher-ID phone of a direct pair
            // holds its call and yields when the peer's call reaches it first.
            val holdoffMs = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM || peer.announcement.rotating) 0L else CocSingleInitiator.holdoffMs(
                CocSingleInitiator.enabled(),
                identity.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                peer.announcement.nodeIdPrefix,
                direct,
            )
            if (holdoffMs > 0L) {
                val peerLabel = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                val yielded = try {
                    CocSingleInitiator.awaitPeerCall(holdoffMs, { callRunning.get() || !running.get() })
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    true
                }
                Log.i("BluetoothVideo", CocSingleInitiator.line("responder", peerLabel, holdoffMs, if (yielded) "yielded" else "dialed"))
                // The peer's call (or a stop) won the holdoff: this attempt ends without a failure notice.
                if (yielded) return@thread
            }
            val startedAtMs = SystemClock.elapsedRealtime()
            // Experimental test_peer_address_book: a direct callee whose address is stale or replaced is
            // rescanned by node prefix first (same prefix and route cost; the handshake verifies identity).
            val firstPeer = if (PeerAddressBook.enabled() && direct && transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                val key = PeerAddressBook.prefixKey(peer.announcement.nodeIdPrefix)
                val reason = PeerAddressBook.shared.rescanReason(key, peer.device.address)
                if (reason == null) {
                    peer
                } else {
                    rediscoverPeer(peer, startedAtMs).also { fresh ->
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK peer_address: link=call | node=$key | reason=$reason | " +
                                "rescanned=${fresh !== peer} | sameAddress=${fresh.device.address == peer.device.address}",
                        )
                    }
                }
            } else {
                peer
            }
            try {
                try {
                    // Inner GATT/CoC retries may only use the part of the early-loss window that still
                    // leaves room for the 2M -> 1M setup retry below (BluetoothTransportPolicy.leSetupRetryAllowed).
                    if (adaptivePhySetupRetryAllowed(direct, firstPhy, 0L)) {
                        val stallGuardMs = if (DebugTunables.phySetupStallGuard) AdaptivePhyMemory.SETUP_STALL_MS else 0L
                        LeSetupBudget.within(startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS) {
                            openAndEstablishOutgoing(firstPeer, firstPhy, stallGuardMs)
                        }
                    } else {
                        openAndEstablishOutgoing(firstPeer, firstPhy)
                    }
                } catch (error: IOException) {
                    if (PeerAddressBook.enabled()) {
                        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                        val status = leSetupStatus(error.message)
                        val action = LeConnectFailureClassifier.classify(elapsedMs, status, fastRetriesUsed = 0)
                        Log.i("BluetoothVideo", LeConnectFailureClassifier.line("call", elapsedMs, status, action))
                    }
                    if (!adaptivePhySetupRetryAllowed(direct, firstPhy, SystemClock.elapsedRealtime() - startedAtMs)) {
                        throw error
                    }
                    val node = offerLogLabel(peer.announcement)
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK adaptive_phy: link=call | node=$node | from=2M to=1M | reason=setup_retry | " +
                            "attempt=2 | error=${error.message ?: error.javaClass.simpleName}",
                    )
                    onStatus("Call setup on LE 2M failed; retrying once on LE 1M")
                    discardFailedOutgoingSetup()
                    val retryPeer = if (adaptivePhyRetryRediscovers(DebugTunables.phySetupStallGuard, peer.announcement.routeCost)) {
                        rediscoverPeer(peer, startedAtMs)
                    } else {
                        peer
                    }
                    openAndEstablishOutgoing(retryPeer, BlePhyPreference.ONE_M_ADAPTIVE)
                    // Requested vs effective PHY of this call link, explicit for the scorer (2M is the app's
                    // adaptive default here: test_ble_phy has no 2M value, so source=adaptive).
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK link_phy requested=2M effective=1M " +
                            "reason=${if (error is CallSetupStalledException) "setup_stall" else "setup_retry"} " +
                            "source=adaptive link=call node=$node",
                    )
                    // The 1M retry authenticated: remember this hop peer as bad on 2M (not a relaying hop).
                    if (!hopRelaysSkipsMemory()) {
                        secureSession?.peerNodeId?.shortLabel()?.let {
                            AdaptivePhyMemory.recordBad(transport, it, "setup_retry")
                        }
                        offerViaPrefix?.let { AdaptivePhyMemory.recordBadVia(transport, it, "setup_retry") }
                    }
                }
            } catch (error: Exception) {
                fail(privateCallConnectionFailureMessage(transport, error))
            }
        }
        return true
    }

    /**
     * Opens and authenticates one outgoing link. With [stallGuardMs] > 0 (the first 2M attempt that can
     * still retry on 1M), a link whose hop authentication has not finished [stallGuardMs] after it opened
     * is closed so the setup fails fast into the 1M retry (see [AdaptivePhyMemory.SETUP_STALL_MS]).
     */
    private fun openAndEstablishOutgoing(peer: BleL2capPeer, linkPhy: BlePhyPreference, stallGuardMs: Long = 0L) {
        val activeConnection = try {
            openOutgoing(peer, linkPhy)
        } catch (error: Exception) {
            if (running.get() && transport == BluetoothTransportOption.BLE_L2CAP) {
                onCocConnectFailed(error.message ?: error.javaClass.simpleName)
            }
            throw error
        }
        val setupState = CallSetupStallState()
        val settled = CountDownLatch(1)
        val openedAtMs = SystemClock.elapsedRealtime()
        val node = offerLogLabel(peer.announcement)
        if (stallGuardMs > 0L) {
            thread(name = "PrivateCallSetupStall", isDaemon = true) {
                val settledInTime = try {
                    settled.await(stallGuardMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val sinceOpenMs = SystemClock.elapsedRealtime() - openedAtMs
                // The CAS decides a handshake that completes at the deadline: only a still-pending attempt is closed.
                if (running.get() && adaptivePhySetupStalled(stallGuardMs, settledInTime, sinceOpenMs) &&
                    setupState.claimStall()
                ) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK adaptive_phy: link=call | node=$node | phy=${linkPhy.label} | reason=setup_stall | " +
                            "sinceOpenMs=$sinceOpenMs | guardMs=$stallGuardMs | action=close",
                    )
                    runCatching { activeConnection.close() }
                }
            }
        }
        try {
            establish(activeConnection, initiator = true, peer.announcement) {
                val hopAuthMs = SystemClock.elapsedRealtime() - openedAtMs
                val kept = setupState.authenticated()
                settled.countDown()
                // Measured hop-authentication time after the link opened, every LE call attempt: the evidence
                // for the setup-stall deadline (AdaptivePhyMemory.SETUP_STALL_MS).
                if (transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK call_setup: node=$node | phy=${linkPhy.label} | hopAuthMs=$hopAuthMs | " +
                            "guardMs=$stallGuardMs | kept=$kept",
                    )
                }
                if (!kept) throw CallSetupStalledException("LE call setup stalled")
            }
        } catch (error: Exception) {
            if (setupState.stalled && error !is CallSetupStalledException) {
                throw CallSetupStalledException("LE call setup stalled", error)
            }
            throw error
        } finally {
            setupState.ended()
            settled.countDown()
        }
        if (setupState.stalled) throw CallSetupStalledException("LE call setup stalled")
    }

    /**
     * Finds the current advertisement of [peer] before the 1M retry. A peer whose 2M link failed drops it
     * (at once on our LL_TERMINATE, else after its own supervision timeout), restarts its call listener
     * and advertises again under a new random address (ASUS snoop, xpair-asusa15s-adf8236d-l3p-1 0003:
     * re-advertised 1.6 s after its disconnect under a new random address while the A15s retried the old
     * address until its 12 s connect deadline). Same node prefix and route cost only; the
     * end-to-end handshake still verifies the identity. Falls back to [peer] when none is seen in time.
     */
    private fun rediscoverPeer(peer: BleL2capPeer, startedAtMs: Long): BleL2capPeer {
        val budgetMs = (startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS - SystemClock.elapsedRealtime())
            .coerceIn(0L, AdaptivePhyMemory.REDISCOVER_MS)
        if (budgetMs <= 0L || !running.get()) return peer
        val prefix = peer.announcement.nodeIdPrefix
        val routeCost = peer.announcement.routeCost
        val found = ArrayBlockingQueue<BleL2capPeer>(1)
        val discovery = BleL2capDiscovery(
            adapter,
            onPeer = { found.offer(it) },
            onFinished = { },
            onError = { Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=call | rediscover=error | message=$it") },
            // The selected version too (RV15): a version 2 advert with the same bytes never replaces a
            // selected version 5 offer, which would swap the opening check for the prefix check.
            acceptAnnouncement = { matchesRediscoveredPeer(prefix, routeCost, it) && it.version == peer.announcement.version },
            scanDurationMs = budgetMs,
            // Rotating IDs: the same 6 bytes match only within the callee's epoch; this continues the
            // user's own call attempt (RV3 allows no connection started by a match alone).
            acceptRotating = peer.announcement.rotating,
        )
        val scanStartedMs = SystemClock.elapsedRealtime()
        return try {
            discovery.start()
            val fresh = found.poll(budgetMs, TimeUnit.MILLISECONDS)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK adaptive_phy: link=call | node=${offerLogLabel(peer.announcement)} | " +
                    "rediscover=${if (fresh == null) "none" else "found"} | " +
                    "sameAddress=${fresh?.device?.address == peer.device.address} | " +
                    "waitedMs=${SystemClock.elapsedRealtime() - scanStartedMs} | budgetMs=$budgetMs",
            )
            fresh ?: peer
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            peer
        } finally {
            discovery.close()
        }
    }

    /**
     * One bounded 1M retry: LE call, first attempt on 2M, within [AdaptivePhyMemory.EARLY_LOSS_MS]. A
     * relayed offer's hop is the relay; the retry is hop-local, so relayed offers retry too.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun adaptivePhySetupRetryAllowed(direct: Boolean, firstPhy: BlePhyPreference, elapsedMs: Long): Boolean =
        adaptivePhySetupRetryEligible(
            enabled = DebugTunables.adaptivePhy && DebugTunables.adaptivePhyMemory,
            running = running.get() && state?.phase != PrivateCallPhase.ENDED,
            le = transport != BluetoothTransportOption.CLASSIC_RFCOMM,
            direct = true,
            firstOnTwoM = firstPhy == BlePhyPreference.TWO_M,
            elapsedMs = elapsedMs,
        )

    /** Closes a failed outgoing attempt so the retry starts from a clean link. */
    private fun discardFailedOutgoingSetup() {
        connection?.close()
        connection = null
        bleControlConnection?.close()
        bleControlConnection = null
        writerThread?.interrupt()
        readerThread?.interrupt()
        secureSession?.close()
        secureSession = null
        contentSession?.close()
        contentSession = null
        authenticatedAtNs = 0L
        activeAtNs = 0L
        callRunning.set(false)
    }

    /**
     * A 2M LE link to this call's hop peer failed within [AdaptivePhyMemory.EARLY_LOSS_MS] of
     * authentication: remember the peer as bad on 2M so the next link to it starts on 1M.
     */
    private fun noteEarlyLinkLoss(error: Exception) {
        if (error !is IOException || transport == BluetoothTransportOption.CLASSIC_RFCOMM) return
        val hop = secureSession?.peerNodeId?.shortLabel() ?: return
        val phy = runCatching { connection?.phySummary?.invoke() }.getOrNull().orEmpty()
        val wasTwoM = linkWasTwoM(phy, requestedLinkPhy == BlePhyPreference.TWO_M)
        // Measured from authentication to the moment the link stopped making progress, on both roles:
        // a GATT write timeout reports the loss only after its own timeout (the A15s central logged
        // "GATT video write timed out" 23.3 s after authentication while the ASUS saw the same loss at
        // 14.2 s; xpair-asusa15s-351baa8e-fb2257/0003), so the detection time overstates the window.
        val stalledAtNs = callLinkStalledAtNs(lastHeartbeatNs, lastSendProgressNs, System.nanoTime())
        // Window start: media start (ACTIVE) when reached, else authentication. Ringing is human-paced:
        // the A15s caller reached ACTIVE 16.4 s after authentication and lost the link 7 s later.
        val authMs = callEarlyLossWindowStartNs(authenticatedAtNs, activeAtNs) / 1_000_000
        val stalledMs = maxOf(stalledAtNs / 1_000_000, authMs)
        val early = authenticatedAtNs > 0 &&
            AdaptivePhyMemory.isEarlyLoss(authMs, stalledMs, wasTwoM, NodeLinkLoad.sendLinks(transport))
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=call | node=$hop | early_loss=$early | stalledAfterMs=${stalledMs - authMs} | " +
                "detectedAfterMs=${System.nanoTime() / 1_000_000 - authMs} | phy=${phy.ifEmpty { "unknown" }} | " +
                "requested=${requestedLinkPhy.label} | error=${error.message ?: error.javaClass.simpleName}",
        )
        if (!early) return
        if (hopRelaysSkipsMemory()) {
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=$hop | bad2m=not_recorded | reason=early_loss | hop_relays=true")
            return
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=call | node=$hop | from=2M to=1M | reason=early_loss | " +
                "afterMs=${stalledMs - authMs} | error=${error.message ?: error.javaClass.simpleName} | next=1M",
        )
        AdaptivePhyMemory.recordBad(transport, hop, "early_loss")
        offerViaPrefix?.let { AdaptivePhyMemory.recordBadVia(transport, it, "early_loss") }
    }

    /**
     * This call's hop peer relays it ([AdaptivePhyMemory.callHopRelays]), so a loss on this link is not
     * evidence about the pair's 2M ([RelayUpstream]; `test_relay_phy_guard=false` records it again).
     */
    private fun hopRelaysSkipsMemory(): Boolean = AdaptivePhyMemory.skipsRelayingHop(
        AdaptivePhyMemory.callHopRelays(
            offerVia = offerViaPrefix != null,
            relayHops = relayHops,
            hopTransportMask = secureSession?.peerCapabilities?.transportMask ?: 0,
        ),
        DebugTunables.relayPhyGuard,
    )

    /** True while this endpoint's link is a message exchange instead of a call. */
    val messageSyncActive: Boolean
        get() = messageSync != null

    /**
     * Experimental: exchanges store-and-forward messages with [peer] over a fresh BMH2 link on the
     * call transport, then ends this endpoint (the activity restarts availability). Returns false
     * when the feature is off or a call/exchange is already running.
     */
    @Synchronized
    fun startMessageSync(peer: BleL2capPeer): Boolean {
        val nodeProvider = storeForwardNode ?: return false
        if (!canStartOutgoingPrivateCall(running.get(), state, callRunning.get())) return false
        if (!callRunning.compareAndSet(false, true)) return false
        stopAvailability(forActiveConnection = false)
        connectionThread = thread(name = "MessageSyncConnect") {
            try {
                val activeConnection = openOutgoing(peer)
                connection = activeConnection
                val link = activeConnection.link ?: SecureBluetoothHandshake.initiate(
                    BufferedInputStream(activeConnection.input, ioConfig.bufferBytes),
                    BufferedOutputStream(activeConnection.output, ioConfig.bufferBytes),
                    identity,
                    linkCapabilities,
                )
                secureSession = link
                checkSelectedPeer(peer.announcement, link, exchangeLinkOpening(link, initiator = true), messageSync = true)
                runMessageSync(nodeProvider(), link, activeConnection, initiator = true, firstRecord = null)
            } catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                runCatching { onMessageSyncFailed(detail) }
                finish("Message exchange failed: $detail")
            }
        }
        return true
    }

    /** Live media needs the radio: the exchange stops within one chunk and resumes next time. */
    fun suspendMessageSync(): Boolean {
        val session = messageSync ?: return false
        session.requestSuspend()
        return true
    }

    private fun runMessageSync(
        node: StoreForwardNode,
        link: SecureBluetoothSession,
        activeConnection: CallConnection,
        initiator: Boolean,
        firstRecord: SecureRecord?,
    ) {
        link.reader.acceptStoreForward = true
        val session = StoreForwardLinkSession(
            link,
            node,
            initiator,
            closeLink = { activeConnection.close() },
            log = { Log.i("BluetoothVideo", it) },
            firstRecord = firstRecord,
        )
        messageSync = session
        reopenListenerForCalls()
        onStatus("Exchanging messages with ${link.peerNodeId.shortLabel()} over ${activeConnection.description}")
        var pendingCall: CallConnection? = null
        val result = try {
            session.run()
        } finally {
            synchronized(this) {
                messageSync = null
                pendingCall = pendingCallConnection
                pendingCallConnection = null
            }
        }
        onMessageSync(result)
        val call = pendingCall
        if (call != null && running.get()) {
            // An incoming call preempted the exchange: release the exchange link and answer the call
            // on this endpoint as an ordinary incoming call.
            runCatching { activeConnection.close() }
            secureSession?.close()
            secureSession = null
            connection = null
            callRunning.set(false)
            onStatus(storeForwardSyncMessage(result))
            acceptIncoming(call)
            return
        }
        call?.close()
        stopAvailability(forActiveConnection = false)
        finish(storeForwardSyncMessage(result))
    }

    fun accept() {
        val current = requireNotNull(state)
        require(current.phase == PrivateCallPhase.INCOMING_RINGING) { "There is no incoming call to accept" }
        transition(PrivateCallEvent.LOCAL_ACCEPT)
        // R1: the callee's name goes only with Accept (never on Ringing to a stranger).
        pendingAcceptLabels?.let { controls.offerMust(OutboundControl.Labels(it)) }
        pendingAcceptLabels = null
        // ACCEPT, then CAMERA_OFF for a voice answer (experimental), in one ordered control queue.
        (negotiation?.acceptSignals() ?: listOf(PrivateCallSignal.ACCEPT)).forEach { enqueueSignal(it) }
        startLocalMedia()
    }

    fun decline(reason: PrivateCallReason = PrivateCallReason.REJECTED) {
        val current = requireNotNull(state)
        require(current.phase == PrivateCallPhase.INCOMING_RINGING) { "There is no incoming call to decline" }
        transition(PrivateCallEvent.LOCAL_DECLINE)
        enqueueSignal(PrivateCallSignal.DECLINE, reason, closeAfter = true)
    }

    fun cancel() {
        val phase = state?.phase ?: return
        if (phase != PrivateCallPhase.OUTGOING_INVITE && phase != PrivateCallPhase.OUTGOING_RINGING) return
        transition(PrivateCallEvent.LOCAL_CANCEL)
        if (!callRunning.get()) {
            finish("Call cancelled")
            return
        }
        enqueueSignal(PrivateCallSignal.CANCEL, closeAfter = true)
    }

    fun hangUp() {
        val phase = state?.phase ?: return
        if (phase != PrivateCallPhase.NEGOTIATING_MEDIA && phase != PrivateCallPhase.ACTIVE) {
            cancel()
            return
        }
        transition(PrivateCallEvent.LOCAL_HANG_UP)
        enqueueSignal(PrivateCallSignal.HANG_UP, PrivateCallReason.NORMAL, closeAfter = true)
    }

    @Synchronized
    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        // Send the partial batch captured before the mute (constrained links batch up to 20 x 20 ms);
        // clearing it cut a median 104-112 ms, at most 361 ms, off the end of each push-to-talk turn
        // on ASUS CoC calls (voice-ptt-51cc8028 receiver traces).
        if (value) flushPendingAudio()
        if (callRunning.get()) {
            enqueueSignal(
                if (value) PrivateCallSignal.MICROPHONE_MUTED
                else PrivateCallSignal.MICROPHONE_UNMUTED,
            )
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (!callRunning.get()) return
        enqueueSignal(if (enabled) PrivateCallSignal.CAMERA_ON else PrivateCallSignal.CAMERA_OFF)
    }

    /**
     * Experimental push-to-talk: holding unmutes and releasing mutes through [setMuted] (the
     * existing mute path; the microphone encoder keeps running). [muted] is the state to apply, or
     * null when the press/release changes nothing. Logged for later measurement.
     */
    fun pushToTalk(pressed: Boolean, muted: Boolean?, source: String) {
        muted?.let(::setMuted)
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_ptt: callId=${state?.callId} | action=${if (pressed) "press" else "release"} | " +
                "muted=${this.muted} | changed=${muted != null} | source=$source | atNs=${System.nanoTime()}",
        )
    }

    /** Logs a local video upgrade/downgrade or a layout change of the current call. */
    fun logCallMode(event: String, localVideo: Boolean, remoteVideo: Boolean, layout: CallLayout) {
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_mode: callId=${state?.callId} | event=$event | callType=${localCallType.label} | " +
                "localVideo=$localVideo | remoteVideo=$remoteVideo | layout=${layout.name.lowercase()} | " +
                "atNs=${System.nanoTime()}",
        )
    }

    fun setDisplayRotation(degrees: Int) {
        if (!callRunning.get()) return
        controls.offerKeyed("display-rotation", OutboundControl.DisplayRotation(degrees), replace = true)
    }

    @Synchronized
    fun send(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        if (!boundedAge) return sendMedia(flags, presentationTimeUs, bytes)
        // Experimental bounded media age: the writer takes under mediaLock, so nothing it writes can
        // interleave with a shed (no partial GOP, no reordering).
        synchronized(mediaLock) {
            try {
                sendMedia(flags, presentationTimeUs, bytes)
            } finally {
                mediaLock.notifyAll()
            }
        }
    }

    private fun sendMedia(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        if (flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0) {
            debugStageTrace?.record("send_enqueue", presentationTimeUs, bytes.size)
        }
        if (!callRunning.get() || state?.phase !in setOf(
                PrivateCallPhase.NEGOTIATING_MEDIA,
                PrivateCallPhase.ACTIVE,
            )
        ) {
            debugTxCounters?.inactiveInputs?.incrementAndGet()
            return
        }
        var packet = OutboundMedia(
            flags, presentationTimeUs, bytes,
            debugQueuedAtNs = if (debugPipelineTiming) System.nanoTime() else 0L,
        )
        if (packet.isAudio && muted && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
            debugTxCounters?.mutedAudio?.incrementAndGet()
            return
        }
        debugTxCounters?.let { counters ->
            when {
                flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 -> counters.configInputs
                packet.isAudio -> counters.audioInputs
                else -> counters.videoInputs
            }.incrementAndGet()
        }
        sampleOfferedBytes.addAndGet(bytes.size.toLong())
        if (!packet.isAudio && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) noteVideoFrame(packet)
        if (packet.flags == StreamProtocol.FLAG_AUDIO) {
            val batchSize = privateCallAudioBatchSizeForRoute(
                transport,
                relayHops,
                stackFingerprint,
                peerMaxBitrateKbps,
                legacyLowDelay,
            )
            if (batchSize > 1) {
                pendingAudioPackets += packet
                if (pendingAudioPackets.size < batchSize) return
                packet = takePendingAudioBatch() ?: return
            }
        }
        enqueueMedia(packet)
    }

    /** The waiting audio access units as one record (single, pair or batch), or null if none wait. */
    private fun takePendingAudioBatch(): OutboundMedia? {
        if (pendingAudioPackets.isEmpty()) return null
        val batchReadyNs = if (debugPipelineTiming) System.nanoTime() else 0L
        debugAudioBatchWait?.recordNanos(batchReadyNs - pendingAudioPackets.first().debugQueuedAtNs)
        val combined = combineCallAudio(pendingAudioPackets.map { it.toStreamPacket() })
        pendingAudioPackets.clear()
        return OutboundMedia(combined.flags, combined.presentationTimeUs, combined.payload, batchReadyNs)
    }

    /** Queues the partial audio batch now (mute, push-to-talk release) instead of discarding it. */
    private fun flushPendingAudio() {
        val batch = takePendingAudioBatch() ?: return
        if (!callRunning.get()) return
        if (!boundedAge) return enqueueMedia(batch)
        synchronized(mediaLock) {
            try {
                enqueueMedia(batch)
            } finally {
                mediaLock.notifyAll()
            }
        }
    }

    private fun enqueueMedia(packet: OutboundMedia) {
        if (boundedAge) {
            // Experimental bounded media age owns the whole queue policy (sendBounded); off, the
            // original path below runs unchanged.
            if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0 && applyBoundedMediaAge(packet)) return
            droppedMediaPackets += sendBounded(packet)
            return
        }
        if (droppingVideoUntilKeyFrame) {
            when {
                packet.isAudio -> droppedMediaPackets += offerLatestAudio(packet)
                packet.isKeyFrame -> {
                    droppedMediaPackets += dropQueuedVideoPackets()
                    droppedMediaPackets += offerCritical(packet.asRecoveryPoint())
                    droppingVideoUntilKeyFrame = false
                    Log.i(
                        "BluetoothVideo",
                        "Call video recovered at a keyframe after $droppedMediaPackets media drops",
                    )
                }
                else -> {
                    droppedMediaPackets++
                    debugTxCounters?.queueDrops?.incrementAndGet()
                    requestRecoveryKeyFrameIfDue()
                }
            }
            return
        }
        if (thinDiscardable) {
            // Experimental temporal-layer thinning (see TemporalLayerThinning): skip discardable
            // frames while the call link is behind, instead of dropping references and waiting for
            // a keyframe.
            val oldestAgeMs = media.peek()?.let { (System.nanoTime() - it.queuedAtNs) / 1_000_000L } ?: 0L
            if (TemporalLayerThinning.shouldThin(packet.isDiscardable, oldestAgeMs)) {
                thinnedVideoFrames.incrementAndGet()
                return
            }
        }
        if (offerWithinCallBudget(packet)) return
        if (thinDiscardable) {
            // Full queue: discardable frames go first; reference frames survive without a keyframe.
            if (packet.isDiscardable) {
                thinnedVideoFrames.incrementAndGet()
                return
            }
            if (dropQueuedDiscardablePackets() > 0 && offerWithinCallBudget(packet)) return
        }

        val removedVideo = dropQueuedVideoPackets()
        val additionallyDropped = when {
            packet.isAudio -> offerLatestAudio(packet).also { droppingVideoUntilKeyFrame = true }
            packet.isKeyFrame -> offerCritical(packet.asRecoveryPoint()).also {
                droppingVideoUntilKeyFrame = false
            }
            else -> 1.also {
                droppingVideoUntilKeyFrame = true
                debugTxCounters?.queueDrops?.incrementAndGet()
            }
        }
        droppedMediaPackets += removedVideo + additionallyDropped
        if (droppingVideoUntilKeyFrame) requestRecoveryKeyFrameIfDue(force = true)
    }

    @Synchronized
    private fun acceptIncoming(activeConnection: CallConnection) {
        requestedLinkPhy = phyPreference
        offerViaPrefix = null
        // Experimental store-and-forward (N3): a connection arriving during an exchange is treated as
        // a call; the exchange suspends within one chunk and the call is accepted afterwards.
        if (messageSync != null) {
            if (pendingCallConnection != null) {
                activeConnection.close()
                return
            }
            pendingCallConnection = activeConnection
            messageSync?.requestSuspend()
            onStatus("Incoming connection: pausing the message exchange")
            return
        }
        if (state != null || !callRunning.compareAndSet(false, true)) {
            activeConnection.close()
            return
        }
        stopAvailability(forActiveConnection = true)
        connectionThread = thread(name = "PrivateCallIncomingHandshake") {
            try {
                establish(activeConnection, initiator = false, selected = null)
            } catch (error: Exception) {
                fail("Incoming call failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun establish(
        activeConnection: CallConnection,
        initiator: Boolean,
        selected: DiscoveryAnnouncement?,
        onHopAuthenticated: () -> Unit = {},
    ) {
        if (!callRunning.get() && !callRunning.compareAndSet(false, true)) {
            activeConnection.close()
            return
        }
        connection = activeConnection
        val link = activeConnection.link ?: run {
            val input = BufferedInputStream(activeConnection.input, ioConfig.bufferBytes)
            val output = BufferedOutputStream(activeConnection.output, ioConfig.bufferBytes)
            if (initiator) {
                SecureBluetoothHandshake.initiate(input, output, identity, linkCapabilities)
            } else {
                SecureBluetoothHandshake.respond(input, output, identity, linkCapabilities, ::strangerTarpit)
            }
        }
        require(link.peerCapabilities.transportMask and transport.capabilityMask != 0) {
            "Peer did not authenticate ${transport.shortLabel} support"
        }
        val opening = exchangeLinkOpening(link, initiator)
        selected?.let { checkSelectedPeer(it, link, opening) }
        onHopAuthenticated()
        // test_coc_phy_after_connect (default off): the CoC central requests the PHY only now.
        if (initiator) bleControlConnection?.requestPhyAfterConnect("call")
        if (!initiator) learnClassicPeerAddress(activeConnection.socket, link.peerNodeId, "incoming")
        // Experimental store-and-forward (only when enabled): an incoming link whose first record is
        // a BSF1 message is a message exchange, not a call. Any other first record continues into
        // the unchanged call handshake, and type 11 is rejected again from then on.
        val firstRecord = storeForwardNode?.takeIf { !initiator }?.let { nodeProvider ->
            val record = readFirstRecordForStoreForwardDispatch(link)
            if (record.type == SecureRecordType.STORE_FORWARD) {
                // A message exchange is a user-visible action, not a probe (RV8 tarpit).
                if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
                secureSession = link
                runMessageSync(nodeProvider(), link, activeConnection, initiator = false, firstRecord = record)
                return
            }
            record
        }
        if (endpointDecodeMask != capabilities.decodeCodecMask) {
            Log.i(
                "BluetoothVideo",
                "Private-call compatibility: end-to-end hello prefers VP9 and also offers this phone's other " +
                    "decoders (mask ${capabilities.decodeCodecMask} -> $endpointDecodeMask)",
            )
        }
        advertisedDecodeMask = endpointDecodeMask
        val endpointCapabilities = PeerLabelFeature.advertise(
            CallVideoFragments.advertise(
                capabilities.copy(decodeCodecMask = endpointDecodeMask),
                videoFragmentsOn,
            ),
            labelsOn,
        )
        val endpoint = if (initiator) {
            EndToEndVideoHandshake.initiate(link, identity, endpointCapabilities)
        } else {
            EndToEndVideoHandshake.respond(link, identity, endpointCapabilities, firstRecord)
        }
        selected?.let { announcement ->
            if (announcement.rotating) {
                // Direct only (startOutgoing): the opened link identity must be the call endpoint.
                require(endpoint.peerNodeId == link.peerNodeId) {
                    "Call peer identity changed between discovery and connection"
                }
            } else {
                require(
                    endpoint.peerNodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                        .contentEquals(announcement.nodeIdPrefix),
                ) { "Call peer identity changed between discovery and connection" }
            }
        }
        peerMaxBitrateKbps = endpoint.peerCapabilities.maxBitrateKbps
        peerAcceptsVideoFragments = CallVideoFragments.negotiated(videoFragmentsOn, endpoint.peerCapabilities, relayHops = 0)
        // Type 9 parses only on this endpoint's opener and only after it advertised fragments.
        endpoint.opener.acceptVideoFragments = videoFragmentsOn
        // Type 10 likewise, only after this endpoint advertised labels (hello bit 5).
        endpoint.opener.acceptLabels = labelsOn
        peerAcceptsLabels = PeerLabelFeature.negotiated(labelsOn, endpoint.peerCapabilities)
        if (videoFragmentsOn) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_video_fragments: negotiated=$peerAcceptsVideoFragments | " +
                    "fragmentBytes=${CallVideoFragments.FRAGMENT_BYTES} | " +
                    "minPacketBytes=${CallVideoFragments.MIN_FRAGMENTED_PACKET_BYTES}",
            )
        }
        activePlaybackDelayProfile = currentCallPlaybackProfile()
        activeCodec = requireNotNull(
            privateCallCodecForPeer(
                codec,
                availableCodecs,
                endpoint.peerCapabilities.decodeCodecMask,
            ),
        ) { "Peer cannot decode any locally available call codec" }
        if (activeCodec != codec) {
            Log.i(
                "BluetoothVideo",
                "Private-call negotiation selected ${activeCodec.displayName} for the peer decoder",
            )
        }
        activeSettings = privateCallVideoSettingsForPeer(
            activeSettings,
            transport,
            peerMaxBitrateKbps,
        )
        secureSession = link
        contentSession = endpoint
        authenticatedAtNs = System.nanoTime()
        lastSendProgressNs = authenticatedAtNs
        activeAtNs = 0L
        AdaptivePhyMemory.bind(activeConnection.address, transport, link.peerNodeId.shortLabel(), hopRelays = offerViaPrefix != null)
        lastHeartbeatNs = System.nanoTime()
        receivedDelivery = LinkDeliveryCounter()
        peerDelivery = PeerDeliveryEstimate()
        deliveryReports = CallDeliveryReports(DebugTunables.deliveryReports)
        startWriter(link, endpoint)
        startReader(link, endpoint, initiator)
        if (initiator) {
            // Experimental peer labels: the caller's name and subject, bound to this call ID, queued
            // before Invite only when the callee's endpoint hello has bit 5 (design 3.4).
            if (peerAcceptsLabels) {
                val binding = LabelBinding.call(requireNotNull(state).callId)
                val labels = listOfNotNull(
                    LocalLabels.signed(identity, LabelKind.PERSON_NAME, binding, PeerLabelFeature.localName),
                    LocalLabels.signed(identity, LabelKind.CALL_SUBJECT, binding, outgoingSubject),
                )
                if (labels.isNotEmpty()) controls.offerMust(OutboundControl.Labels(SignedLabelCodec.encodeContainer(labels)))
                Log.i("BluetoothVideo", "BENCHMARK peer_labels call_tx: role=caller | count=${labels.size}")
            }
            // INVITE, then CAMERA_OFF for a voice call (experimental), in one ordered control queue.
            (negotiation?.inviteSignals() ?: listOf(PrivateCallSignal.INVITE)).forEach { enqueueSignal(it) }
        }
        onStatus(
            "Encrypted private-call link to ${endpoint.peerNodeId.shortLabel()} over " +
                "${activeConnection.description}",
        )
    }

    private fun startWriter(link: SecureBluetoothSession, endpoint: EndToEndVideoSession) {
        writerThread = thread(name = "PrivateCallWriter") {
            try {
                var lastHeartbeatWrittenNs = 0L
                var lastFlushNs = System.nanoTime()
                var sampleStartNs = System.nanoTime()
                var sampleSentBytes = 0L
                var sampleThinned = thinnedVideoFrames.get()
                var sampleShed = boundedShedFrames.get()
                val mediaFlushIntervalNs = privateCallMediaFlushIntervalMs(
                    transport,
                    stackFingerprint,
                    peerMaxBitrateKbps,
                ) * 1_000_000L
                // Tell the peer this hop accepts version 3 (delivery) reports. Older endpoints take a
                // version-1 heartbeat as plain liveness and keep sending version 2.
                if (deliveryReports.sendsHello) {
                    SecureLinkControlProtocol.writeHeartbeat(link.writer)
                    link.writer.flush()
                    lastSendProgressNs = System.nanoTime()
                }
                if (pacedWrites) {
                    LinkPacerSeed.seed(pacer, transport)?.let { kbps ->
                        Log.i("BluetoothVideo", "Call link pacer seeded at $kbps kbps until the peer's first report (${transport.shortLabel})")
                    }
                }
                while (callRunning.get()) {
                    val control = controls.poll()
                    var closeAfter = false
                    when (control) {
                        is OutboundControl.Signal -> {
                            writeCallSignal(link, endpoint, control.message)
                            closeAfter = control.closeAfter
                        }
                        OutboundControl.Header -> EndToEndStreamProtocol.writeHeader(
                            link.writer,
                            endpoint.sealer,
                            activeCodec.protocolId,
                            activeSettings,
                            rotationDegrees,
                            includeAudio = true,
                        )
                        OutboundControl.KeyFrameRequest -> EndToEndStreamProtocol.writeKeyFrameRequest(
                            link.writer,
                            endpoint.sealer,
                        )
                        is OutboundControl.Labels -> link.writer.write(
                            SecureRecordType.END_TO_END_CONTENT,
                            endpoint.sealer.seal(EndToEndContentType.LABELS, 0, control.payload),
                        )
                        is OutboundControl.DisplayRotation -> EndToEndStreamProtocol.writePacket(
                            link.writer,
                            endpoint.sealer,
                            StreamProtocol.FLAG_DISPLAY_ROTATION,
                            0L,
                            StreamProtocol.encodeDisplayRotation(control.degrees),
                        )
                        null -> {
                            // Experimental call-link pacing: wait for link credit before video, in
                            // slices so controls and heartbeats keep flowing; audio is never held.
                            val paceWaitNs = if (pacedWrites && media.none(OutboundMedia::isAudio)) pacer.waitNs() else 0L
                            val packet = if (paceWaitNs > 0L) {
                                val sliceNs = minOf(paceWaitNs, CALL_PACING_SLICE_NS)
                                Thread.sleep(sliceNs / 1_000_000L, (sliceNs % 1_000_000L).toInt())
                                null
                            } else {
                                takeNextMedia()
                            }
                            if (packet != null) {
                                // Media age spent in this queue (both arms; BENCHMARK media_age).
                                val waitedMs = (System.nanoTime() - packet.queuedAtNs).coerceAtLeast(0L) / 1_000_000L
                                if (packet.isAudio) {
                                    audioQueueWaitMax.record(waitedMs)
                                } else if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                    videoQueueWaitMax.record(waitedMs)
                                }
                                if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                    debugStageTrace?.record("write_begin", packet.presentationTimeUs, packet.payload.size)
                                }
                                val writeStartedNs = if (debugPipelineTiming) System.nanoTime() else 0L
                                if (packet.debugQueuedAtNs > 0L) {
                                    (if (packet.isAudio) debugAudioQueueWait else debugVideoQueueWait)
                                        ?.recordNanos(writeStartedNs - packet.debugQueuedAtNs)
                                }
                                val pacedStartNs = System.nanoTime()
                                val written = writeMediaRecord(link, endpoint, packet) {
                                    val flushNs = System.nanoTime()
                                    if (mediaFlushIntervalNs == 0L || flushNs - lastFlushNs >= mediaFlushIntervalNs) {
                                        link.writer.flush()
                                        lastSendProgressNs = System.nanoTime()
                                        lastFlushNs = flushNs
                                    }
                                }
                                // Charged in payload bytes; record headers add about 50 B, inside PACE_GAIN.
                                if (pacedWrites) pacer.onSent(written.toInt(), pacedStartNs)
                                sampleSentBytes += written
                                debugMediaWriteWait?.recordNanos(System.nanoTime() - writeStartedNs)
                                if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                    debugStageTrace?.record("write_end", packet.presentationTimeUs, packet.payload.size)
                                }
                                debugTxCounters?.let { counters ->
                                    when {
                                        packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 -> counters.configWrites
                                        packet.isAudio -> counters.audioWrites
                                        else -> counters.videoWrites
                                    }.incrementAndGet()
                                }
                            }
                        }
                    }
                    val nowNs = System.nanoTime()
                    if (BuildConfig.DEBUG && nowNs - lastDebugTxReportNs >= DEBUG_TX_REPORT_INTERVAL_NS) {
                        reportDebugTx()
                        lastDebugTxReportNs = nowNs
                    }
                    if (nowNs - sampleStartNs >= LINK_SAMPLE_NS) {
                        val thinnedNow = thinnedVideoFrames.get()
                        val shedNow = boundedShedFrames.get()
                        onLinkSample(
                            LinkRateSample(
                                windowNs = nowNs - sampleStartNs,
                                offeredBytes = sampleOfferedBytes.getAndSet(0L),
                                sentBytes = sampleSentBytes,
                                backlogAgeMs = media.peek()?.let { ((nowNs - it.queuedAtNs) / 1_000_000L).toInt() } ?: 0,
                                largeFrameGrace = nowNs < largeFrameGraceUntilNs,
                                measuredLinkKbps = peerMeasuredKbps,
                                childLate = nowNs < peerLateUntilNs,
                                peer = link.peerNodeId.shortLabel(),
                                thinnedFrames = thinnedNow - sampleThinned,
                                shedFrames = shedNow - sampleShed,
                                deliveredKbps = peerDelivery.deliveredKbps(),
                                peerQueueMs = peerDelivery.queueMs(),
                            ),
                        )
                        sampleThinned = thinnedNow
                        sampleShed = shedNow
                        sampleSentBytes = 0L
                        sampleStartNs = nowNs
                    }
                    var heartbeatWritten = false
                    if (nowNs - lastHeartbeatWrittenNs >= REPORT_INTERVAL_NS) {
                        // The heartbeat carries this phone's view of the media it receives on this
                        // link, so the peer's sender can adapt (hop-local; a relay forwards liveness only).
                        val arrivalKbps = link.reader.arrivalCapacityKbps() ?: 0
                        val report = mediaReceiver?.takeLinkReport(arrivalKbps)
                            ?: LinkCapacityReport(arrivalKbps, linkLate = false)
                        // Version 3 only to a direct peer that said it accepts it (an older endpoint
                        // closes the call on anything but version 2; a relay would drop it).
                        val delivery = deliveryReports.deliveryFor(state != null, relayHops) { receivedDelivery.snapshot() }
                        SecureLinkControlProtocol.writeCapacityReport(link.writer, report.copy(delivery = delivery))
                        lastHeartbeatWrittenNs = nowNs
                        heartbeatWritten = true
                    }
                    if (
                        mediaFlushIntervalNs == 0L ||
                        control != null ||
                        heartbeatWritten ||
                        nowNs - lastFlushNs >= mediaFlushIntervalNs
                    ) {
                        val flushStartedNs = if (debugPipelineTiming) System.nanoTime() else 0L
                        link.writer.flush()
                        lastSendProgressNs = System.nanoTime()
                        debugMediaFlushWait?.recordNanos(System.nanoTime() - flushStartedNs)
                        lastFlushNs = nowNs
                    }
                    if (closeAfter) {
                        val closeWait = privateCallCloseWait(transport, DebugTunables.callCloseWaitsForPeer)
                        val outcome = awaitPrivateCallClose(readerThread, closeWait)
                        if (closeWait.maxMs > 0L) {
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK call close_drain: waitForPeer=${closeWait.waitForPeer} | " +
                                    "peerClosed=${outcome.peerClosed} | waitedMs=${outcome.waitedMs} | " +
                                    "capMs=${closeWait.maxMs}",
                            )
                        }
                        finish("Call ended")
                        return@thread
                    }
                    if (nowNs - lastHeartbeatNs >= HEARTBEAT_TIMEOUT_NS) {
                        throw IOException("Call peer stopped responding")
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                // A send failing on a link the peer is already tearing down after its HANG_UP (phase ENDED,
                // finish still pending on the reader) is that hang-up, not a call failure (mvp-smoke 0006).
                if (callRunning.get() && !privateCallAlreadyEnded(state?.phase)) {
                    noteEarlyLinkLoss(error)
                    fail("Call send failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun startReader(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        initiator: Boolean,
    ) {
        readerThread = thread(name = "PrivateCallReader") {
            try {
                while (callRunning.get()) {
                    val record = link.reader.read()
                    lastHeartbeatNs = System.nanoTime()
                    when (record.type) {
                        SecureRecordType.LINK_HEARTBEAT -> {
                            val report = SecureLinkControlProtocol.requireHeartbeat(record)
                            if (report == null) notePeerHeartbeat(null) else onPeerReport(report)
                        }
                        SecureRecordType.END_TO_END_CONTENT -> {
                            val content = endpoint.opener.open(record.payload)
                            handleContent(content, initiator)
                        }
                        else -> error("Unexpected private-call record ${record.type}")
                    }
                }
            } catch (error: LocalMediaPipelineException) {
                if (callRunning.get() && state?.phase != PrivateCallPhase.ENDED) endForLocalMediaFailure(error)
            } catch (error: Exception) {
                if (callRunning.get() && state?.phase != PrivateCallPhase.ENDED) {
                    noteEarlyLinkLoss(error)
                    fail("Call link lost: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * This phone's decoder/playback failed while the link was healthy: hang up cleanly (the peer
     * sees an error hang-up, not a lost link) and report the local cause, instead of treating it
     * as link loss.
     */
    private fun endForLocalMediaFailure(error: LocalMediaPipelineException) {
        val message = privateCallLocalMediaFailureMessage(error)
        Log.e("BluetoothVideo", "Private call ending on a local media failure (not link loss): stage=${error.stage}", error)
        localFailureMessage = message
        onStatus(message)
        mediaReceiver?.close()
        val phase = state?.phase
        val hungUp = (phase == PrivateCallPhase.NEGOTIATING_MEDIA || phase == PrivateCallPhase.ACTIVE) &&
            runCatching {
                transition(PrivateCallEvent.LOCAL_HANG_UP)
                enqueueSignal(PrivateCallSignal.HANG_UP, PrivateCallReason.ERROR, closeAfter = true)
            }.isSuccess
        if (!hungUp) fail(message)
    }

    private fun handleContent(content: EndToEndContentRecord, initiator: Boolean) {
        when (content.type) {
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                handleSignal(PrivateCallSignalCodec.decode(content.payload), initiator)
            EndToEndContentType.LABELS -> {
                val current = state
                if (current == null) {
                    // R3: hold one record until the Invite names the call ID it must be bound to.
                    if (bufferedLabels == null) bufferedLabels = content.payload
                } else {
                    applyCallLabels(content.payload, current.callId)
                }
            }
            EndToEndContentType.STREAM_HEADER -> {
                videoReassembler.requireIdleFor("A call stream header")
                debugStageTrace?.close()
                debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSessionName, "call_transport")
                val header = EndToEndStreamProtocol.decodeHeader(content.payload)
                require(advertisedDecodeMask and codecCapabilityMask(header.codecId) != 0) {
                    "This phone cannot decode the peer's call codec"
                }
                activePlaybackDelayProfile = currentCallPlaybackProfile()
                require(contentSession?.peerCapabilities?.encodeCodecMask
                    ?.and(codecCapabilityMask(header.codecId)) != 0) {
                    "Peer did not authenticate use of its call codec"
                }
                if (activePlaybackDelayProfile != playbackDelayProfile) {
                    onStatus("Private-call compatibility using High stability playback for this Bluetooth route")
                }
                val receiverProfile = privateCallReceiverPlaybackProfile(
                    activePlaybackDelayProfile,
                    localCallType,
                    DebugTunables.voiceCallLowDelay,
                )
                if (receiverProfile != activePlaybackDelayProfile) {
                    Log.i("BluetoothVideo", "BENCHMARK call_playout_profile: profile=${receiverProfile.name} | " +
                        "route=${activePlaybackDelayProfile.name} | callType=${localCallType.label} | experimental=true")
                }
                mediaReceiver = PrivateCallMediaReceiver(
                    remoteSurfaceProvider(),
                    receiverProfile,
                    transport,
                    onRemoteVideoFormat,
                    onRemoteCameraEnabled,
                    { controls.offerKeyed("key-frame-request", OutboundControl.KeyFrameRequest) },
                    onStatus,
                    DebugMediaTrace.startIfRequested(
                        appContext, debugTraceSessionName, "receiver", header.codecId,
                        header.width, header.height, header.fps,
                    ),
                    if (BuildConfig.DEBUG) state?.callId?.toString() else null,
                    debugTraceContext = appContext,
                    debugTraceSession = debugTraceSessionName,
                    videoGrowthLimitMs = CallPlayoutPolicy.videoGrowthLimitMs(speechLedPlayout),
                ).also { it.setHeader(header) }
                if (state?.phase == PrivateCallPhase.NEGOTIATING_MEDIA) {
                    transition(PrivateCallEvent.MEDIA_READY)
                }
            }
            EndToEndContentType.MEDIA_PACKET -> {
                val receiver = requireNotNull(mediaReceiver) { "Peer sent media before its call header" }
                val packet = EndToEndStreamProtocol.decodePacket(content)
                // The same bytes the peer counts as written (it writes rotation outside its media queue).
                if (packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION == 0) receivedDelivery.add(packet.payload.size)
                if (!packet.isAudio) videoReassembler.requireIdleFor("A whole call video packet")
                deliverMedia(receiver, packet)
            }
            EndToEndContentType.VIDEO_FRAGMENT -> {
                // Experimental call video fragments: accepted only after this endpoint advertised them.
                require(videoFragmentsOn) { "Peer sent call video fragments this endpoint did not advertise" }
                val receiver = requireNotNull(mediaReceiver) { "Peer sent media before its call header" }
                // Counted per fragment on arrival (not at reassembly), exactly as the peer wrote it.
                receivedDelivery.add(content.payload.size - CallVideoFragments.HEADER_BYTES)
                val complete = videoReassembler.accept(content.flags, content.payload) ?: return
                deliverMedia(receiver, EndToEndStreamProtocol.decodeReassembledPacket(content.flags, complete))
            }
            EndToEndContentType.KEY_FRAME_REQUEST -> {
                require(content.payload.isEmpty()) { "Invalid call keyframe request" }
                onLocalKeyFrameNeeded()
            }
            else -> error("Unexpected private-call content ${content.type}")
        }
    }

    private fun handleSignal(message: PrivateCallSignalMessage, initiator: Boolean) {
        val current = state
        if (current == null) {
            require(!initiator && message.signal == PrivateCallSignal.INVITE) {
                "Expected a private-call invitation"
            }
            val incoming = PrivateCallState.incoming(message.callId)
            negotiation = VoiceCallNegotiation.incoming(voiceCallsEnabled)
            relayHops = message.relayHops
            activeSettings = privateCallVideoSettingsForPeer(
                routeSettings(relayHops),
                transport,
                peerMaxBitrateKbps,
            )
            state = incoming
            onPhase(incoming)
            bufferedLabels?.let { applyCallLabels(it, message.callId) }
            bufferedLabels = null
            if (peerAcceptsLabels) prepareCalleeLabels(message.callId)
            enqueueSignal(PrivateCallSignal.RINGING)
            // A stranger whose call rings was not a probe (RV8 tarpit counts only silent handshakes).
            if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
            onIncoming(contentSession?.peerNodeId?.shortLabel() ?: "Nearby device")
            return
        }
        require(message.callId == current.callId) { "Private-call ID changed during the call" }
        when (message.signal) {
            PrivateCallSignal.RINGING -> transition(PrivateCallEvent.REMOTE_RINGING)
            PrivateCallSignal.ACCEPT -> {
                transition(PrivateCallEvent.REMOTE_ACCEPT)
                startLocalMedia()
            }
            PrivateCallSignal.DECLINE -> {
                transition(PrivateCallEvent.REMOTE_DECLINE)
                finish("Call declined")
            }
            PrivateCallSignal.CANCEL -> {
                transition(PrivateCallEvent.REMOTE_CANCEL)
                finish("Caller cancelled")
            }
            PrivateCallSignal.HANG_UP -> {
                transition(PrivateCallEvent.REMOTE_HANG_UP)
                finish(
                    if (message.reason == PrivateCallReason.ERROR) {
                        "Peer's phone ended the call after a local media failure"
                    } else {
                        "Peer ended the call"
                    },
                )
            }
            PrivateCallSignal.CAMERA_OFF -> {
                noteRemoteCameraSignal(current.phase, enabled = false)
                mediaReceiver?.setRemoteCameraEnabled(false) ?: onRemoteCameraEnabled(false)
            }
            PrivateCallSignal.CAMERA_ON -> {
                noteRemoteCameraSignal(current.phase, enabled = true)
                mediaReceiver?.setRemoteCameraEnabled(true) ?: onRemoteCameraEnabled(true)
            }
            PrivateCallSignal.MICROPHONE_MUTED -> onRemoteMuted(true)
            PrivateCallSignal.MICROPHONE_UNMUTED -> onRemoteMuted(false)
            PrivateCallSignal.INVITE -> error("Duplicate private-call invitation")
        }
    }

    /**
     * Experimental peer labels: verifies one container against this call's ID and the authenticated
     * endpoint peer, then publishes the peer's self-chosen name and subject for this call only.
     */
    private fun applyCallLabels(payload: ByteArray, callId: java.util.UUID) {
        val endpoint = contentSession ?: return
        val inbox = callLabels ?: LabelInbox().also { callLabels = it }
        inbox.accept(payload, endpoint.peerNodeId, LabelBinding.call(callId), "call")
        val prefix = endpoint.peerNodeId.shortLabel()
        val name = inbox.text(LabelKind.PERSON_NAME)
        val subject = inbox.text(LabelKind.CALL_SUBJECT)
        SessionPeerLabels.update(prefix) { it.copy(selfName = name, callSubject = subject) }
        Log.i("BluetoothVideo", "BENCHMARK peer_labels call_rx: name=${name != null} | subject=${subject != null}")
        onPeerLabels(prefix)
    }

    /**
     * Signs this callee's name once at Invite time (off the UI thread). R1: it is sent at Ringing only
     * to a verified contact (full fingerprint), otherwise only with Accept.
     */
    private fun prepareCalleeLabels(callId: java.util.UUID) {
        val endpoint = contentSession ?: return
        val label = LocalLabels.signed(identity, LabelKind.PERSON_NAME, LabelBinding.call(callId), PeerLabelFeature.localName)
            ?: return
        val container = SignedLabelCodec.encodeContainer(listOf(label))
        val verified = runCatching {
            PeerIdentityGate.store?.state(endpoint.peerNodeId, endpoint.peerFingerprint) == VerificationState.VERIFIED
        }.getOrDefault(false)
        if (verified) {
            controls.offerMust(OutboundControl.Labels(container))
        } else {
            pendingAcceptLabels = container
        }
    }

    /** Experimental voice calls: Camera off while ringing is the caller's voice offer. */
    private fun noteRemoteCameraSignal(phase: PrivateCallPhase, enabled: Boolean) {
        val active = negotiation ?: return
        if (!active.onRemoteCameraSignal(phase, enabled)) return
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_type offer: callId=${state?.callId} | " +
                "offered=${if (active.peerOfferedVoice) "voice" else "video"}",
        )
        onIncomingCallType(if (active.peerOfferedVoice) CallType.VOICE else CallType.VIDEO)
    }

    private fun startLocalMedia() {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call identity: callId=${state?.callId}")
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_type: callId=${state?.callId} | role=${state?.role?.name?.lowercase()} | " +
                "requested=${negotiation?.requested?.label ?: "video"} | local=${localCallType.label} | " +
                "peerOfferedVoice=${negotiation?.peerOfferedVoice == true} | voiceCallsEnabled=$voiceCallsEnabled",
        )
        if (activeCodec.usesSurfaceInput) {
            val compatible = privateCallCameraVideoSettings(
                activeSettings,
                supportedVideoModes(activeCodec, activeSettings.bitRate),
            )
            if (compatible != activeSettings) {
                onStatus("Call camera selected supported ${compatible.resolution.width}x${compatible.resolution.height}/${compatible.fps} mode")
                activeSettings = compatible
            }
        }
        if (localCallType == CallType.VIDEO) {
            val fitted = fitCallVideo(activeSettings, activeCodec)
            if (fitted != activeSettings) {
                if (BuildConfig.DEBUG) Log.i(
                    "BluetoothVideo",
                    "BENCHMARK call_encoder_fit: requested=${activeSettings.resolution.width}x${activeSettings.resolution.height} | " +
                        "effective=${fitted.resolution.width}x${fitted.resolution.height} | fps=${fitted.fps} | " +
                        "encoder=${activeCodec.encoderName}",
                )
                onStatus(
                    "Call video ${fitted.resolution.width}x${fitted.resolution.height} instead of " +
                        "${activeSettings.resolution.width}x${activeSettings.resolution.height}: this phone's encoder " +
                        "could not keep ${fitted.fps} FPS at that size in an earlier call",
                )
                activeSettings = fitted
            }
        }
        controls.offerMust(OutboundControl.Header)
        activePlaybackDelayProfile = currentCallPlaybackProfile()
        onStartLocalMedia(
            activeSettings,
            privateCallAudioBitRate(transport, stackFingerprint, peerMaxBitrateKbps),
            activeCodec,
            activePlaybackDelayProfile,
        )
    }

    @Synchronized
    private fun transition(event: PrivateCallEvent) {
        val next = requireNotNull(state).transition(event)
        state = next
        if (next.phase == PrivateCallPhase.ACTIVE && activeAtNs == 0L) activeAtNs = System.nanoTime()
        onPhase(next)
    }

    private fun enqueueSignal(
        signal: PrivateCallSignal,
        reason: PrivateCallReason = PrivateCallReason.NONE,
        closeAfter: Boolean = false,
    ) {
        val callId = requireNotNull(state).callId
        // Hang-up, decline, cancel and every other signal must reach the peer: never refused.
        controls.offerMust(
            OutboundControl.Signal(
                PrivateCallSignalMessage(
                    callId,
                    signal,
                    reason,
                    relayHops = if (signal == PrivateCallSignal.INVITE) relayHops else 0,
                ),
                closeAfter,
            ),
        )
    }

    private fun writeCallSignal(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        message: PrivateCallSignalMessage,
    ) {
        link.writer.write(
            SecureRecordType.END_TO_END_CONTENT,
            endpoint.sealer.seal(
                EndToEndContentType.PRIVATE_CALL_SIGNAL,
                0,
                PrivateCallSignalCodec.encode(message),
            ),
        )
    }

    /** Call playout profile for the current route; relay hops are known once the invitation arrived. */
    private fun currentCallPlaybackProfile(): PlaybackDelayProfile = privateCallPlaybackDelayProfile(
        playbackDelayProfile,
        transport,
        stackFingerprint,
        peerMaxBitrateKbps,
        privateCallLegacyLowDelay(legacyLowDelay, relayHops),
    )

    private fun deliverMedia(receiver: PrivateCallMediaReceiver, packet: StreamProtocol.Packet) {
        if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
            debugStageTrace?.record("receive", packet.presentationTimeUs, packet.payload.size)
        }
        if (packet.flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) {
            StreamProtocol.splitAudioPair(packet).forEach(receiver::offer)
        } else {
            receiver.offer(packet)
        }
    }

    /**
     * Writes one queued media record and returns the media payload bytes written. With negotiated
     * call video fragments (experimental, direct calls only) a large video frame goes as ordered
     * fragments and queued call audio is written between them ([flushBetween] applies the writer's
     * flush rule); otherwise exactly one whole media packet, as before.
     */
    private fun writeMediaRecord(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        packet: OutboundMedia,
        flushBetween: () -> Unit,
    ): Long {
        val fragmenting = peerAcceptsVideoFragments && relayHops == 0 &&
            CallVideoFragments.shouldFragment(packet.payload.size + StreamProtocol.PACKET_HEADER_BYTES, packet.flags)
        if (!fragmenting) {
            EndToEndStreamProtocol.writePacket(
                link.writer,
                endpoint.sealer,
                packet.flags,
                packet.presentationTimeUs,
                packet.payload,
            )
            // Delivery accounting (link report version 3): per record, as the peer counts it.
            peerDelivery.onSent(packet.payload.size, System.nanoTime())
            return packet.payload.size.toLong()
        }
        val fragments = CallVideoFragments.split(
            EndToEndStreamProtocol.encodePacket(packet.flags, packet.presentationTimeUs, packet.payload),
        )
        var written = packet.payload.size.toLong()
        fragments.forEachIndexed { index, fragment ->
            EndToEndStreamProtocol.writeVideoFragment(link.writer, endpoint.sealer, packet.flags, fragment)
            // Each fragment's chunk counts when written and when it arrives, so audio written between
            // fragments keeps the same byte order on both sides.
            peerDelivery.onSent(fragment.size - CallVideoFragments.HEADER_BYTES, System.nanoTime())
            if (index < fragments.lastIndex && callRunning.get()) {
                flushBetween()
                written += writeInterleavedAudio(link, endpoint, flushBetween)
            }
        }
        fragmentedVideoFrames.incrementAndGet()
        return written
    }

    /** Experimental call video fragments: queued call audio written between two fragments. */
    private fun writeInterleavedAudio(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        flushBetween: () -> Unit,
    ): Long {
        var written = 0L
        while (callRunning.get()) {
            val audio = takeQueuedAudio() ?: break
            audioQueueWaitMax.record((System.nanoTime() - audio.queuedAtNs).coerceAtLeast(0L) / 1_000_000L)
            EndToEndStreamProtocol.writePacket(
                link.writer,
                endpoint.sealer,
                audio.flags,
                audio.presentationTimeUs,
                audio.payload,
            )
            peerDelivery.onSent(audio.payload.size, System.nanoTime())
            debugTxCounters?.audioWrites?.incrementAndGet()
            interleavedAudioPackets.incrementAndGet()
            written += audio.payload.size
        }
        if (written > 0L) flushBetween()
        return written
    }

    private fun takeQueuedAudio(): OutboundMedia? = if (boundedAge) {
        synchronized(mediaLock) { takePrivateCallAudio(media, OutboundMedia::isAudio) }
    } else {
        takePrivateCallAudio(media, OutboundMedia::isAudio)
    }

    /** Sealing happens after this selection, so live audio can safely pass queued video. */
    private fun onPeerReport(report: LinkCapacityReport) {
        if (report.arrivalKbps > 0) peerMeasuredKbps = report.arrivalKbps
        if (pacedWrites && report.arrivalKbps > 0) {
            // The peer measured arrival trains of this phone's media: the same single-link evidence
            // a stream child reports (experimental for calls).
            pacer.setMeasuredLinkKbps(report.arrivalKbps)
            LinkCapacity.recordProbe(transport, report.arrivalKbps)
        }
        peerAddedDelayMs = report.addedDelayMs
        if (peerLateness.onReport(report)) peerLateUntilNs = System.nanoTime() + PeerLatenessTrend.LATE_HOLD_NS
        notePeerHeartbeat(report)
        deliveryReports.accepted(report)?.let { delivery ->
            if (!peerDelivery.onReport(delivery)) {
                Log.w("BluetoothVideo", "Call link: the peer's delivery counters went backwards; queue measurement off for this link")
            }
        }
    }

    /** Full node ID (hex) of the authenticated end-to-end peer, for the call screen's verification UI. */
    val endToEndPeerNodeId: String?
        get() = contentSession?.peerNodeId?.toString()

    /** The adjacent hop's node label (a relay on a relayed call), as its link rate samples name it. */
    val adjacentPeerLabel: String
        get() = secureSession?.peerNodeId?.shortLabel() ?: ""

    /** A version-1 heartbeat ([report] null) or a version 3 report says the adjacent hop accepts version 3. */
    private fun notePeerHeartbeat(report: LinkCapacityReport?) {
        if (deliveryReports.onPeerHeartbeat(report)) {
            Log.i("BluetoothVideo", "Call link: the adjacent hop accepts delivery reports (link report version 3)")
        }
    }

    /** Keyframes and scene cuts drain over the link; their backlog is expected, not congestion. */
    private fun noteVideoFrame(packet: OutboundMedia) {
        val size = packet.payload.size.toLong()
        val typical = typicalVideoBytes
        typicalVideoBytes = if (typical == 0L) size else (typical * 15 + size.coerceAtMost(typical * 4)) / 16
        val graceNs = largeFrameDrainGraceNs(size, typical, packet.isKeyFrame, 0L, peerMeasuredKbps * 125L)
            ?: return
        largeFrameGraceUntilNs = maxOf(largeFrameGraceUntilNs, System.nanoTime() + graceNs)
    }

    private fun takeNextMedia(): OutboundMedia? = if (boundedAge) {
        // Experimental bounded media age: take under the shed lock (see mediaLock).
        takeNextUnderLock(mediaLock, media, OutboundMedia::isAudio, WRITER_POLL_MS)
    } else {
        pollNextPrivateCallMedia(media, WRITER_POLL_MS, OutboundMedia::isAudio)
    }

    /**
     * Experimental bounded media age: queues [packet] (after the age policy) and returns the drops.
     * Audio and codec configuration (csd) are always queued and never evicted; while video waits for
     * a keyframe every inter frame is dropped and counted as shed (rate-control overuse evidence).
     */
    private fun sendBounded(packet: OutboundMedia): Int {
        if (packet.isAudio || packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) return offerBounded(packet)
        if (droppingVideoUntilKeyFrame) {
            if (!packet.isKeyFrame) {
                debugTxCounters?.queueDrops?.incrementAndGet()
                boundedShedFrames.incrementAndGet()
                requestRecoveryKeyFrameIfDue()
                return 1
            }
            val removed = dropQueuedVideoPackets()
            boundedShedFrames.addAndGet(removed.toLong())
            droppingVideoUntilKeyFrame = false
            Log.i("BluetoothVideo", "Call video recovered at a keyframe after ${droppedMediaPackets + removed} media drops")
            return removed + offerBounded(packet.asRecoveryPoint())
        }
        if (thinDiscardable) {
            val oldestAgeMs = media.peek()?.let { (System.nanoTime() - it.queuedAtNs) / 1_000_000L } ?: 0L
            if (TemporalLayerThinning.shouldThin(packet.isDiscardable, oldestAgeMs)) {
                thinnedVideoFrames.incrementAndGet()
                return 0
            }
        }
        return offerBounded(packet)
    }

    /** Experimental bounded media age: [offerCodecSafely] with this call's counters and keyframe wait. */
    private fun offerBounded(packet: OutboundMedia): Int {
        val critical = packet.isAudio || packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 || packet.isKeyFrame
        val result = offerCodecSafely(mediaLock, media, packet, mediaCapacity, critical) { callMediaShedClass(it.flags) }
        var drops = result.shed.size
        if (!result.queued) drops++
        if (drops > 0) {
            debugTxCounters?.queueDrops?.addAndGet(drops.toLong())
            boundedShedFrames.addAndGet(drops.toLong())
        }
        if (result.criticalOverflow) {
            criticalOverflowDrops++
            Log.w("BluetoothVideo", "Bounded media age: call queue headroom full; refused an arriving ${if (packet.isAudio) "audio" else "critical video"} record")
        }
        if (result.awaitKeyFrame) {
            droppingVideoUntilKeyFrame = true
            requestRecoveryKeyFrameIfDue(force = true)
        }
        return drops
    }

    /**
     * The default (no-loss arm) queue admission. With [audioHeadroom] video and codec configuration
     * are refused only when queued non-audio records fill [mediaCapacity]; audio uses the extra
     * room. Without it (control arm), any record is refused once [mediaCapacity] records of either
     * kind are queued, as before.
     */
    private fun offerWithinCallBudget(packet: OutboundMedia): Boolean {
        if (!audioHeadroom || packet.isAudio) return media.offer(packet)
        if (privateCallVideoBudgetFull(media, mediaCapacity, OutboundMedia::isAudio)) return false
        return media.offer(packet)
    }

    private fun offerLatestAudio(packet: OutboundMedia): Int {
        if (media.offer(packet)) return 0
        val oldestAudio = media.firstOrNull(OutboundMedia::isAudio) ?: media.peek()
        if (oldestAudio != null && media.remove(oldestAudio)) {
            debugTxCounters?.queueDrops?.incrementAndGet()
            if (oldestAudio.isAudio) debugTxCounters?.audioDrops?.incrementAndGet()
        }
        check(media.offer(packet)) { "Could not retain the latest call audio packet" }
        return if (oldestAudio == null) 0 else 1
    }

    private fun offerCritical(packet: OutboundMedia): Int {
        if (media.offer(packet)) return 0
        val removed = media.poll()
        if (removed != null) debugTxCounters?.queueDrops?.incrementAndGet()
        check(media.offer(packet)) { "Could not queue a call video recovery point" }
        return if (removed == null) 0 else 1
    }

    private fun OutboundMedia.toStreamPacket() =
        StreamProtocol.Packet(flags, presentationTimeUs, payload)

    /** Experimental temporal layering only: removes queued discardable call video. */
    private fun dropQueuedDiscardablePackets(): Int {
        var removed = 0
        for (queued in media.toList()) {
            if (queued.isDiscardable && media.remove(queued)) removed++
        }
        thinnedVideoFrames.addAndGet(removed.toLong())
        return removed
    }

    /**
     * [keepCodecConfig]: experimental bounded media age never sheds codec configuration (csd); the
     * default no-loss path keeps its original behaviour (the experiment is off, so it is false).
     */
    private fun dropQueuedVideoPackets(keepCodecConfig: Boolean = boundedAge): Int {
        var removed = 0
        for (queued in media.toList()) {
            val codecConfig = queued.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0
            if (!queued.isAudio && !(keepCodecConfig && codecConfig) && media.remove(queued)) {
                removed++
                debugTxCounters?.queueDrops?.incrementAndGet()
            }
        }
        return removed
    }

    /**
     * Experimental bounded media age for outgoing call video ([packet] is video). Returns true when
     * [packet] is consumed (dropped). Steps after the bitrate/frame-rate ladder (driven by the shed
     * evidence in LinkRateSample): verified non-reference frames are skipped from half the budget;
     * beyond the budget the queue skips to its newest keyframe or waits for a fresh one; when skips
     * keep recurring video pauses (audio only) and resumes at a keyframe after the link recovers.
     * Queued media is plaintext; records are sealed when written, so BME1/BMS1 stay contiguous.
     */
    private fun applyBoundedMediaAge(packet: OutboundMedia): Boolean {
        val nowNs = System.nanoTime()
        val budgetMs = callVideoBudgetMs()
        val ageMs = oldestVideoAgeMs(nowNs)
        if (pauseGovernor.paused) {
            // Audio still queues while video is paused: resume only once all queued media is fresh.
            val mediaAgeMs = oldestMediaAgeMs(nowNs)
            if (!pauseGovernor.shouldResume(nowNs, mediaAgeMs, budgetMs, nowNs < peerLateUntilNs)) {
                boundedPausedFrames++
                boundedShedFrames.incrementAndGet()
                debugTxCounters?.queueDrops?.incrementAndGet()
                droppedMediaPackets++
                return true
            }
            pauseGovernor.onResumed(nowNs)
            droppingVideoUntilKeyFrame = true
            requestRecoveryKeyFrameIfDue(force = true)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK media_age_event: role=call_tx | callId=${state?.callId} | event=video_resumed | " +
                    "pausedMs=${pauseGovernor.pausedMs(nowNs)} | queueAgeMs=$mediaAgeMs | budgetMs=$budgetMs",
            )
            onStatus("Experimental bounded media age: the link recovered; resuming video at a keyframe")
            return false
        }
        if (droppingVideoUntilKeyFrame) return false
        if (packet.isDiscardable && BoundedMediaAge.nonReferenceSkipAllowed(activeCodec.protocolId) &&
            ageMs >= BoundedMediaAge.thinThresholdMs(budgetMs)
        ) {
            thinnedVideoFrames.incrementAndGet()
            return true
        }
        if (!callShedder.shouldShed(ageMs, budgetMs, nowNs < largeFrameGraceUntilNs, nowNs)) return false
        val (plan, removedMedia) = shedAtomically(
            mediaLock,
            media,
            { queuedMedia -> callMediaShedClass(queuedMedia.flags) },
            { queuedMedia -> (nowNs - queuedMedia.queuedAtNs) / 1_000_000L <= budgetMs },
        )
        val removed = removedMedia.size
        callShedder.recordShed(plan, removed, nowNs)
        // The existing keyframe wait (droppingVideoUntilKeyFrame) owns recovery from here.
        callShedder.reset()
        debugTxCounters?.queueDrops?.addAndGet(removed.toLong())
        droppedMediaPackets += removed
        boundedShedFrames.addAndGet(removed.toLong())
        Log.i(
            "BluetoothVideo",
            "BENCHMARK media_age_shed: role=call_tx | callId=${state?.callId} | queueAgeMs=$ageMs | " +
                "budgetMs=$budgetMs | peerAddedDelayMs=$peerAddedDelayMs | removed=$removed | " +
                "awaitKeyFrame=${plan.awaitKeyFrame} | events=${callShedder.shedEvents}",
        )
        if (pauseGovernor.onShed(nowNs)) {
            val more = dropQueuedVideoPackets()
            droppedMediaPackets += more + 1
            boundedPausedFrames++
            boundedShedFrames.addAndGet(more + 1L)
            debugTxCounters?.queueDrops?.incrementAndGet()
            droppingVideoUntilKeyFrame = false
            Log.i(
                "BluetoothVideo",
                "BENCHMARK media_age_event: role=call_tx | callId=${state?.callId} | event=video_paused | " +
                    "pauseEvents=${pauseGovernor.pauseEvents} | backoffMs=${pauseGovernor.currentBackoffMs} | " +
                    "queueAgeMs=$ageMs | budgetMs=$budgetMs",
            )
            onStatus("Experimental bounded media age: video paused because the link is too slow; audio continues")
            return true
        }
        if (plan.awaitKeyFrame) {
            droppingVideoUntilKeyFrame = true
            requestRecoveryKeyFrameIfDue(force = true)
        }
        return false
    }

    private fun callVideoBudgetMs(): Long =
        BoundedMediaAge.callVideoQueueBudgetMs(peerAddedDelayMs, BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS)

    /** Oldest queued media of either kind (audio included). */
    private fun oldestMediaAgeMs(nowNs: Long): Long =
        media.minOfOrNull { it.queuedAtNs }?.let { (nowNs - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    private fun oldestVideoAgeMs(nowNs: Long): Long =
        media.firstOrNull { !it.isAudio && it.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0 }
            ?.let { (nowNs - it.queuedAtNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    private fun requestRecoveryKeyFrameIfDue(force: Boolean = false) {
        val nowNs = System.nanoTime()
        if (!force && nowNs - lastRecoveryKeyFrameRequestNs < RECOVERY_REQUEST_INTERVAL_NS) return
        lastRecoveryKeyFrameRequestNs = nowNs
        onLocalKeyFrameNeeded()
    }

    @Synchronized
    private fun reportDebugTx(final: Boolean = false) {
        val counters = debugTxCounters ?: return
        val callId = state?.callId ?: return
        if (final && !debugTxFinalReported.compareAndSet(false, true)) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK call tx: callId=$callId | final=$final | " +
                "queue=${media.size}/${media.size + media.remainingCapacity()} | pendingAudio=${pendingAudioPackets.size} | " +
                "videoInputs=${counters.videoInputs.get()} | audioInputs=${counters.audioInputs.get()} | " +
                "configInputs=${counters.configInputs.get()} | videoWrites=${counters.videoWrites.get()} | " +
                "audioWrites=${counters.audioWrites.get()} | configWrites=${counters.configWrites.get()} | " +
                "queueDrops=${counters.queueDrops.get()} | " +
                (if (thinDiscardable) "thinned=${thinnedVideoFrames.get()} | " else "") +
                "inactiveInputs=${counters.inactiveInputs.get()} | " +
                (if (peerAcceptsVideoFragments) {
                    "fragmentedFrames=${fragmentedVideoFrames.get()} | interleavedAudio=${interleavedAudioPackets.get()} | "
                } else {
                    ""
                }) +
                "mutedAudio=${counters.mutedAudio.get()} | audioDrops=${counters.audioDrops.get()}",
        )
        val nowNs = System.nanoTime()
        // Both arms: this direction's media age in the call queue and any bounded-age degradation.
        Log.i(
            "BluetoothVideo",
            "BENCHMARK media_age: role=call_tx | callId=$callId | final=$final | " +
                "policy=${BoundedMediaAge.policyLabel(boundedAge)} | " +
                "videoQueueAgeMaxMs=${videoQueueWaitMax.take()} | audioQueueAgeMaxMs=${audioQueueWaitMax.take()} | " +
                "videoQueueAgeNowMs=${oldestVideoAgeMs(nowNs)} | peerAddedDelayMs=$peerAddedDelayMs | " +
                "budgetMs=${callVideoBudgetMs()} | shedEvents=${callShedder.shedEvents} | " +
                "shedFrames=${boundedShedFrames.get()} | thinned=${thinnedVideoFrames.get()} | " +
                "videoPaused=${pauseGovernor.paused} | pauseEvents=${pauseGovernor.pauseEvents} | " +
                "pausedMs=${pauseGovernor.pausedMs(nowNs)} | pausedFrames=$boundedPausedFrames | " +
                "criticalOverflowDrops=$criticalOverflowDrops",
        )
        if (debugPipelineTiming) {
            for ((stage, stats) in listOf(
                    "audio_batch_fill" to debugAudioBatchWait,
                    "video_queue" to debugVideoQueueWait,
                    "audio_queue" to debugAudioQueueWait,
                    "media_write" to debugMediaWriteWait,
                    "media_flush" to debugMediaFlushWait,
                )
            ) {
                val timing = requireNotNull(stats).snapshot()
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK call pipeline: callId=$callId | final=$final | stage=$stage | " +
                        "samples=${timing.samples} | window=last256 | p50Ms=${timing.p50Ms} | " +
                        "p95Ms=${timing.p95Ms} | p99Ms=${timing.p99Ms} | maxMs=${timing.maxMs}",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openOutgoing(peer: BleL2capPeer, linkPhy: BlePhyPreference = phyPreference): CallConnection {
        requestedLinkPhy = linkPhy
        return openOutgoingLink(peer, linkPhy)
    }

    private fun openOutgoingLink(peer: BleL2capPeer, linkPhy: BlePhyPreference): CallConnection = when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> {
            // Same candidate order, deadlines and identity check as the stream sender; a relayed
            // call's immediate peer is the relay, which advertises the callee's prefix.
            val connected = try {
                ClassicConnector.connect(
                    appContext,
                    adapter,
                    peer.device,
                    peer.announcement.nodeIdPrefix,
                    ClassicRole.PRIVATE_CALL,
                    verifyLinkPrefix = peer.announcement.routeCost == 0,
                    totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
                    allowInquiry = true,
                    isCancelled = { !running.get() },
                    onSocket = { pendingClassicSocket = it },
                    handshake = { socket ->
                        SecureBluetoothHandshake.initiate(
                            BufferedInputStream(socket.inputStream, ioConfig.bufferBytes),
                            BufferedOutputStream(socket.mediaOutputStream(), ioConfig.bufferBytes),
                            identity,
                            linkCapabilities,
                        )
                    },
                    verify = { link ->
                        require(link.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                            "Peer did not authenticate ${transport.shortLabel} support"
                        }
                    },
                    onStatus = onStatus,
                )
            } finally {
                pendingClassicSocket = null
            }
            socketConnection(connected.socket).copy(link = connected.session)
        }
        BluetoothTransportOption.BLE_L2CAP -> {
            val socket = BleCocClient.connect(
                appContext,
                peer.device,
                onStatus,
                linkPhy,
                "private-call-connect",
            ) { bleControlConnection = it }
            socketConnection(socket)
        }
        BluetoothTransportOption.BLE_GATT -> gattConnection(
            GattVideoClient.open(
                appContext,
                peer.device,
                linkPhy,
                BleConnectionPriority.HIGH,
                onStatus,
                acknowledgedWrites = false,
                deferConnectionPriority = debugGattDeferConnectionPriority,
            ),
            address = runCatching { peer.device.address }.getOrNull(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun socketConnection(socket: BluetoothSocket): CallConnection {
        val mps = runCatching { socket.maxTransmitPacketSize }.getOrDefault(0)
        return CallConnection(
            socket.inputStream,
            socket.mediaOutputStream(),
            "${transport.shortLabel}${if (mps > 0) " (MPS $mps)" else ""}",
            socket::close,
            socket = socket,
            address = runCatching { socket.remoteDevice?.address }.getOrNull(),
            phySummary = { bleControlConnection?.phySummary ?: cocControlServer?.phySummary ?: "" },
        )
    }

    private fun gattConnection(active: GattVideoConnection, address: String? = null) = CallConnection(
        active.inputStream,
        active.outputStream,
        "${transport.shortLabel} (${active.phySummary()})",
        active::close,
        address = address,
        phySummary = active.phySummary,
    )

    /** Log label of a selected offer: release builds never log a rotating ID (design section 6). */
    private fun offerLogLabel(announcement: DiscoveryAnnouncement): String =
        if (announcement.rotating && !BuildConfig.DEBUG) "rotating" else AdaptivePhyMemory.prefixLabel(announcement.nodeIdPrefix)

    /**
     * Rotating IDs: the first hop record each way when both link hellos carry bit 3. A responder
     * opens only its pinned call-offer IDs (section 6); an initiator advertised nothing and sends
     * none. Null when not negotiated (switch off here or on the peer): no record is exchanged.
     */
    private fun exchangeLinkOpening(link: SecureBluetoothSession, initiator: Boolean): List<OpeningEntry>? {
        if (!rotatingIds) return null
        val entries = if (initiator) emptyList() else RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_CALL_OFFER)
        return LinkOpeningExchange.exchange(link, linkCapabilities, entries)
    }

    /**
     * RV15: the check follows the advert this caller selected, never the peer's hello. Direct
     * version 2 calls keep the end-to-end prefix check in [establish]; message exchanges check the
     * link prefix here as before.
     */
    private fun checkSelectedPeer(
        selected: DiscoveryAnnouncement,
        link: SecureBluetoothSession,
        opening: List<OpeningEntry>?,
        messageSync: Boolean = false,
    ) {
        if (!selected.rotating && !messageSync) return
        val result = DiscoverySelectionCheck.check(
            selected,
            RotatingDiscoveryIds.ROLE_CALL_OFFER,
            link.peerNodeId.toByteArray(),
            opening,
        )
        if (selected.rotating) {
            val detail = if (BuildConfig.DEBUG && result == DiscoverySelectionCheck.Result.PASS) {
                // DEBUG only: host tools map ID -> prefix; release never logs both (design section 6).
                " | rdi=${selected.nodeIdPrefix.joinToString("") { "%02x".format(it) }} | prefix=${link.peerNodeId.shortLabel()}"
            } else {
                ""
            }
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=call | result=$result$detail")
        }
        require(result == DiscoverySelectionCheck.Result.PASS) { "Peer identity changed between discovery and connection" }
    }

    /** RV8: a stranger's hello waits 2^(n-6) s (max 30 s) for the n-th silent handshake this epoch. */
    private fun strangerTarpit(peerNodeId: NodeId) {
        if (!rotatingIds) return
        if (PeerIdentityGate.store?.stateForNodeId(peerNodeId) == VerificationState.VERIFIED) return
        val delayMs = runCatching {
            RotatingDiscovery.tarpit.nextDelayMs(RotatingDiscovery.currentEpoch())
        }.getOrDefault(0L)
        if (delayMs >= 1_000L) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_tarpit: delayMs=$delayMs")
        }
        if (delayMs > 0L) Thread.sleep(delayMs)
    }

    private fun reportListenerFailure(error: Exception, generation: Int = listenerGeneration) {
        if (generation != listenerGeneration) return
        if (running.get() && state == null && !callRunning.get()) {
            val message = "Private-call listener stopped: ${error.message ?: error.javaClass.simpleName}"
            onStatus(message)
            finish(message)
        }
    }

    private fun fail(message: String) {
        state?.let { current ->
            if (current.phase != PrivateCallPhase.ENDED) {
                state = runCatching { current.transition(PrivateCallEvent.LINK_LOST) }
                    .getOrElse { current.copy(phase = PrivateCallPhase.ENDED) }
                onPhase(requireNotNull(state))
            }
        }
        finish(message)
    }

    private fun finish(message: String) {
        callRunning.set(false)
        contentSession?.peerNodeId?.shortLabel()?.let(SessionPeerLabels::clear)
        bufferedLabels = null
        pendingAcceptLabels = null
        callLabels = null
        reportDebugTx(final = true)
        pendingAudioPackets.clear()
        media.clear()
        controls.clear()
        mediaReceiver?.close()
        mediaReceiver = null
        if (transport == BluetoothTransportOption.BLE_GATT) {
            gattServer?.close()
            gattServer = null
        }
        connection?.close()
        connection = null
        secureSession?.close()
        secureSession = null
        contentSession?.close()
        contentSession = null
        writerThread?.interrupt()
        readerThread?.interrupt()
        if (endedCallbackSent.compareAndSet(false, true)) onEnded(localFailureMessage ?: message)
    }

    private fun stopAvailability(forActiveConnection: Boolean) {
        if (!forActiveConnection) fullAvailabilityStops++
        // Rotating IDs: the call-offer ID leaves the air; it stays openable for 60 s (OpeningPins).
        if (rotatingAdvert) RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_CALL_OFFER)
        serverSocket?.let { active ->
            serverSocket = null
            runCatching { active.close() }
        }
        cocListener?.let { active ->
            cocListener = null
            runCatching { active.close() }
        }
        classicAdvertiser?.close()
        classicAdvertiser = null
        if (forActiveConnection && transport == BluetoothTransportOption.BLE_L2CAP) {
            cocControlServer?.stopAdvertising()
        } else {
            cocControlServer?.close()
            cocControlServer = null
        }
        if (!forActiveConnection) {
            gattServer?.close()
            gattServer = null
        }
        listenerThread?.interrupt()
        listenerThread = null
    }

    override fun close() {
        debugStageTrace?.close()
        debugStageTrace = null
        if (!running.getAndSet(false)) return
        if (rotatingAdvert) RotatingDiscovery.removeBoundaryListener(boundaryListener)
        pendingCallConnection?.close()
        pendingCallConnection = null
        pendingClassicSocket?.let { runCatching { it.close() } }
        pendingClassicSocket = null
        callRunning.set(false)
        reportDebugTx(final = true)
        pendingAudioPackets.clear()
        stopAvailability(forActiveConnection = false)
        bleControlConnection?.close()
        bleControlConnection = null
        connectionThread?.interrupt()
        writerThread?.interrupt()
        readerThread?.interrupt()
        mediaReceiver?.close()
        mediaReceiver = null
        connection?.close()
        connection = null
        contentSession?.close()
        contentSession = null
        secureSession?.close()
        secureSession = null
    }

    private companion object {
        const val CONSTRAINED_COC_AUDIO_BATCH_SIZE = 20
        const val WRITER_POLL_MS = 20L
        const val CALL_PACING_SLICE_NS = 5_000_000L
        /** Heartbeats carry a link report; the peer's rate control acts on its trend. */
        const val REPORT_INTERVAL_NS = 1_000_000_000L
        const val LINK_SAMPLE_NS = 1_000_000_000L
        const val HEARTBEAT_TIMEOUT_NS = 8_000_000_000L
        const val RECOVERY_REQUEST_INTERVAL_NS = 750_000_000L
        const val DEBUG_TX_REPORT_INTERVAL_NS = 5_000_000_000L
    }
}

internal fun storeForwardSyncMessage(result: StoreForwardSyncResult): String {
    val received = result.opened.size
    val base = when (result.outcome) {
        StoreForwardSyncOutcome.COMPLETE -> "Messages exchanged"
        StoreForwardSyncOutcome.SUSPENDED_LOCAL, StoreForwardSyncOutcome.SUSPENDED_BY_PEER ->
            "Message exchange paused for a call; it resumes next time"
        else -> "Message exchange interrupted; it resumes next time"
    }
    return "$base (${result.chunksSent} chunks sent, ${result.chunksReceived} received, $received new)"
}

internal fun privateCallConnectionFailureMessage(
    transport: BluetoothTransportOption,
    detail: String,
): String = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
    "Classic call could not reach this phone ($detail). Pair the phones once or switch both phones to BLE GATT."
} else {
    "Call could not connect: $detail"
}

/** An unresolved Classic peer explains itself; anything else keeps the transport's message. */
internal fun privateCallConnectionFailureMessage(
    transport: BluetoothTransportOption,
    error: Throwable,
): String = (error as? ClassicPeerUnresolvedException)?.message
    ?: privateCallConnectionFailureMessage(transport, error.message ?: error.javaClass.simpleName)

/**
 * Second record of a call's end, logged from the UI thread after the call has been torn down.
 * The first one ("Private call ended: ...") is logged on the reader/writer thread in the same
 * millisecond as the burst of final reports; on ASUS (Android 11 logd) that window repeatedly lost
 * log lines from several threads at once (calls-noloss-m31asus-51cc8028/0007), although the end had
 * been handled. The text deliberately differs from the first record so it is never counted as a
 * second unexpected termination; the benchmark runner/scorer accept either as the same evidence.
 */
internal fun privateCallEndedUiEvidence(message: String): String =
    "BENCHMARK call ended_ui: message=$message"

/** True once the call state reached ENDED (e.g. the peer's HANG_UP), before finish() has run. */
internal fun privateCallAlreadyEnded(phase: PrivateCallPhase?): Boolean = phase == PrivateCallPhase.ENDED

/**
 * Experimental call-link pacing applies to socket transports only: a GATT call write already blocks
 * for its own transmission, so its backlog is visible in the app queue without a pacer.
 */
internal fun callLinkPaced(
    transport: BluetoothTransportOption,
    callLinkPacing: Boolean,
    linkPacing: Boolean,
): Boolean = callLinkPacing && linkPacing && transport != BluetoothTransportOption.BLE_GATT

/**
 * How the hang-up/decline/cancel writer waits before closing its link. A socket write only reaches
 * the stack's queue: the fixed 400 ms close lost the HANG_UP and ~1.3 s of media behind a 542 ms
 * CoC peer queue (xpair2-s24m31-431d40dc/0002: S24 closed 0.43 s after "control end applied"; the
 * M31 saw "Call link lost: EOFException"). The peer closes only after reading the in-order HANG_UP,
 * so the writer waits for its reader to see that close, capped. GATT closes at once (its writes
 * complete per packet). DEBUG `test_call_close_wait_peer=false` is the fixed-400 ms control arm.
 */
internal data class PrivateCallCloseWait(val waitForPeer: Boolean, val maxMs: Long)

internal const val PRIVATE_CALL_PEER_CLOSE_WAIT_MS = 3_000L
internal const val PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS = 400L

internal fun privateCallCloseWait(transport: BluetoothTransportOption, waitForPeer: Boolean): PrivateCallCloseWait =
    when {
        transport == BluetoothTransportOption.BLE_GATT -> PrivateCallCloseWait(waitForPeer = false, maxMs = 0L)
        waitForPeer -> PrivateCallCloseWait(waitForPeer = true, maxMs = PRIVATE_CALL_PEER_CLOSE_WAIT_MS)
        else -> PrivateCallCloseWait(waitForPeer = false, maxMs = PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS)
    }

internal data class PrivateCallCloseOutcome(val peerClosed: Boolean, val waitedMs: Long)

/** Waits per [wait]; [reader] ends when the peer closes the link. Interruptible like the old sleep. */
internal fun awaitPrivateCallClose(
    reader: Thread?,
    wait: PrivateCallCloseWait,
    nowNs: () -> Long = System::nanoTime,
): PrivateCallCloseOutcome {
    if (wait.maxMs <= 0L) return PrivateCallCloseOutcome(peerClosed = false, waitedMs = 0L)
    val startedNs = nowNs()
    if (wait.waitForPeer && reader != null && reader !== Thread.currentThread()) {
        reader.join(wait.maxMs)
    } else {
        Thread.sleep(wait.maxMs)
    }
    val peerClosed = wait.waitForPeer && reader != null && !reader.isAlive
    return PrivateCallCloseOutcome(peerClosed, (nowNs() - startedNs) / 1_000_000L)
}

internal fun canStartOutgoingPrivateCall(
    running: Boolean,
    state: PrivateCallState?,
    callRunning: Boolean,
): Boolean = running && state == null && !callRunning

/**
 * Send-queue room reserved for call audio: 3 s of 20 ms packets. A large video frame blocks the
 * call writer for its whole air time; in calls-noloss-m31asus-51cc8028/0002 (GATT) audio waited up
 * to 646 ms behind one write while queued video was at most 57 ms old, so 33 slots sized for 500 ms
 * of audio plus video filled with audio alone. The overflow then dropped every queued video frame
 * and forced a keyframe, whose own long write repeated the cycle (8, 60 and 15 frames dropped within
 * 4.2 s; 104 such recoveries in the 46 no-loss calls of calls-noloss-*-51cc8028). Dropping video
 * cannot shorten the write the audio waits for.
 */
internal fun privateCallAudioHeadroomPackets(): Int = 150

/** True when queued non-audio records already fill [videoCapacity] (audio is not counted). */
internal fun <T> privateCallVideoBudgetFull(queue: Collection<T>, videoCapacity: Int, isAudio: (T) -> Boolean): Boolean =
    queue.size >= videoCapacity && queue.count { !isAudio(it) } >= videoCapacity

/** The oldest queued audio record, if any (the call writer is the only remover besides overflow). */
internal fun <T> takePrivateCallAudio(
    queue: ArrayBlockingQueue<T>,
    isAudio: (T) -> Boolean,
): T? {
    val waitingAudio = queue.firstOrNull(isAudio) ?: return null
    return if (queue.remove(waitingAudio)) waitingAudio else null
}

internal fun <T> pollNextPrivateCallMedia(
    queue: ArrayBlockingQueue<T>,
    timeoutMs: Long,
    isAudio: (T) -> Boolean,
): T? {
    val waitingAudio = queue.firstOrNull(isAudio)
    return if (waitingAudio != null && queue.remove(waitingAudio)) {
        waitingAudio
    } else {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

/** Pure gate for the one bounded 1M call-setup retry (adaptive PHY memory). */
internal fun adaptivePhySetupRetryEligible(
    enabled: Boolean,
    running: Boolean,
    le: Boolean,
    direct: Boolean,
    firstOnTwoM: Boolean,
    elapsedMs: Long,
): Boolean = enabled && running && le && direct && firstOnTwoM && elapsedMs in 0..AdaptivePhyMemory.EARLY_LOSS_MS

/**
 * The guarded first 2M attempt is stalled: [stallGuardMs] (0 = guard off) passed since the link opened
 * without hop authentication. Hop authentication on a healthy fresh link takes 0.1-0.3 s.
 */
internal fun adaptivePhySetupStalled(stallGuardMs: Long, authenticated: Boolean, sinceOpenMs: Long): Boolean =
    stallGuardMs > 0L && !authenticated && sinceOpenMs >= stallGuardMs

/** A fresh advertisement is the same call peer (same node prefix and route cost) for the 1M retry. */
internal fun matchesRediscoveredPeer(prefix: ByteArray, routeCost: Int, announcement: DiscoveryAnnouncement): Boolean =
    announcement.routeCost == routeCost && announcement.nodeIdPrefix.contentEquals(prefix)

/**
 * The 1M retry rescans only for a direct offer (route cost 0): its advertiser is the callee itself, so
 * the node prefix names the hop and the end-to-end handshake verifies it. A relayed offer's advertiser
 * is a relay that carries the far callee's prefix; any other relay at the same cost advertises the
 * same announcement, so a rescan could silently swap the requested parent. It keeps the original relay.
 */
internal fun adaptivePhyRetryRediscovers(guardOn: Boolean, routeCost: Int): Boolean = guardOn && routeCost == 0

/** The guarded first 2M attempt was closed before its hop authentication finished. */
internal class CallSetupStalledException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Who owns a guarded setup attempt: the setup thread when hop authentication finishes (or the attempt
 * ends) first, the stall guard when its deadline claims the still-pending attempt first. Exactly one
 * side wins, so a handshake completing just after the deadline is either kept whole or closed and
 * retried, never both, and an attempt that already ended is never logged as stalled.
 */
internal class CallSetupStallState {
    private val state = java.util.concurrent.atomic.AtomicInteger(PENDING)

    /** Hop authentication finished; false when the guard already closed the link. */
    fun authenticated(): Boolean = state.compareAndSet(PENDING, AUTHENTICATED) || state.get() == AUTHENTICATED

    /** The setup thread left establish (success or failure) without authentication. */
    fun ended() {
        state.compareAndSet(PENDING, ENDED)
    }

    /** The guard deadline passed: true only when the attempt was still pending (the guard closes it). */
    fun claimStall(): Boolean = state.compareAndSet(PENDING, STALLED)

    val stalled: Boolean get() = state.get() == STALLED

    private companion object {
        const val PENDING = 0
        const val AUTHENTICATED = 1
        const val STALLED = 2
        const val ENDED = 3
    }
}

/** The link ran on 2M: confirmed by the stack, or 2M was requested and no other PHY was ever reported. */
internal fun linkWasTwoM(phySummary: String, requestedTwoM: Boolean): Boolean =
    "TX 2M" in phySummary ||
        (requestedTwoM && "TX 1M" !in phySummary && "Coded" !in phySummary)

/**
 * When a call link stopped making progress: the earlier of the last received record and the last
 * writer flush that returned (a dead link stops both; a one-way stall stops one), never after [nowNs].
 */
internal fun callLinkStalledAtNs(lastReadNs: Long, lastSendNs: Long, nowNs: Long): Long {
    val known = listOf(lastReadNs, lastSendNs).filter { it > 0 }
    return (known.minOrNull() ?: nowNs).coerceAtMost(nowNs)
}

/** Early-loss window start: when the call became ACTIVE, or authentication while it has not. */
internal fun callEarlyLossWindowStartNs(authenticatedAtNs: Long, activeAtNs: Long): Long =
    if (activeAtNs > 0) activeAtNs else authenticatedAtNs
