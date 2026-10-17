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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AndroidPrivateVideoCall(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val endpointTransport: BluetoothTransportOption,
    private val codec: VideoCodec,
    private val availableCodecs: List<VideoCodec>,
    private val settings: VideoSettings,
    private val rotationDegrees: Int,
    private val remoteSurfaceProvider: () -> Surface,
    private val playbackDelayProfile: PlaybackDelayProfile,
    private val phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
    private val ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
    /** The camera/encoder modes for the requested call video (GPU scale ladder: the rung sizes of that request). */
    private val supportedVideoModes: (VideoCodec, VideoSettings) -> List<DeviceVideoMode>,
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
    /**
     * Messaging v2 (switch `test_messaging_v2`, default off): whether this phone offers a chat session to [NodeId] in a message
     * exchange (HELLO flag CHAT). The policy lives in [ChatSessionController]; false keeps every exchange a plain one.
     */
    private val chatCapable: (NodeId, Boolean) -> Boolean = { _, _ -> false },
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
    /**
     * Experimental relayed calls (RelayedCallsFeature, default off). Null: this endpoint never reads hop
     * type 12, signs no relay mark and sets no consent bit, and behaves exactly as before.
     */
    private val relayHelper: RelayHelperHost? = null,
    /** Call blocking (CallBlocking.kt): decides at INVITE time whether an incoming call may ring. Local only. */
    private val callGate: CallGate = CallBlocking.gate(context),
) : Closeable {
    private sealed interface OutboundControl {
        data class Signal(val message: PrivateCallSignalMessage, val closeAfter: Boolean = false) : OutboundControl
        data object Header : OutboundControl
        data object KeyFrameRequest : OutboundControl
        data class DisplayRotation(val degrees: Int) : OutboundControl
        /** Experimental peer labels: one `BLB1` container (BME1 type 10), ordered with the signals. */
        class Labels(val payload: ByteArray) : OutboundControl

        /** Experimental rotating IDs: one `BDX1` container (BME1 type 11), ordered with the signals. */
        class Discovery(val payload: ByteArray) : OutboundControl

        /**
         * Experimental BMH3 pseudonyms: this phone's identity reveal, built (a Keystore signature) on the writer thread
         * when its turn in the ordered queue comes, so the Accept tap never waits for it.
         */
        class Reveal(val build: () -> ByteArray) : OutboundControl
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
        /** Experimental dual listening: the transport this incoming link arrived on when it is not the endpoint's own. */
        val arrivedOn: BluetoothTransportOption? = null,
    ) : Closeable {
        override fun close() = closeConnection()
    }

    /**
     * Experimental dual listening (DualListeningFeature, default off): the transport of the link this endpoint
     * accepted or opened when it is not the endpoint's own (a CoC endpoint answering or dialing over GATT).
     * Null (always, with the switch off) reads exactly the old values. Written when a link starts and cleared
     * when an attempt is discarded; every policy that followed the endpoint's transport follows the link.
     */
    @Volatile private var linkTransportOverride: BluetoothTransportOption? = null
    private val transport: BluetoothTransportOption get() = linkTransportOverride ?: endpointTransport

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
    private val capabilitiesByTransport = HashMap<BluetoothTransportOption, NodeCapabilities>()
    private val capabilities: NodeCapabilities
        get() = synchronized(capabilitiesByTransport) {
            capabilitiesByTransport.getOrPut(transport) { privateCallCapabilities(transport, stackFingerprint) }
        }

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
    private val linkCapabilitiesBase: NodeCapabilities
        get() = if (rotatingIds) {
            capabilities.copy(transportMask = capabilities.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
        } else {
            capabilities
        }

    /** Experimental relayed calls: fixed for this endpoint; LE only (Classic is not relayed on demand). */
    private val relayedCallsOn = relayHelper != null && RelayedCallsFeature.enabled &&
        transport != BluetoothTransportOption.CLASSIC_RFCOMM

    /** The link hello signed now: with relayed calls on it follows the helper state (PROTOCOL capability bits). */
    private val linkCapabilities: NodeCapabilities
        get() = if (relayedCallsOn) {
            RelayedCalls.linkCapabilities(
                linkCapabilitiesBase,
                featureOn = true,
                helperActive = relayHelper?.helperActive() == true && linkTransportOverride == null,
            )
        } else {
            linkCapabilitiesBase
        }

    /** The on-demand relay this endpoint runs for a SETUP it accepted (relayed calls only). */
    @Volatile private var relayDuty: BluetoothPrivateCallRelay? = null

    /** A relay duty (not a call) used this endpoint: its end must not open the call screens. */
    @Volatile var endedFromRelayDuty = false
        private set

    /** Call blocking: this endpoint declined a blocked or unknown caller; its end must not open any screen. */
    @Volatile var endedQuietly = false
        private set

    /** The route the current outgoing call asked for (relayed calls only) and when its setup began. */
    @Volatile private var pendingRelayRoute: RelayRoute? = null

    /** A route whose READY arrived but whose target is not yet verified end to end (marked used/failed then). */
    @Volatile private var relayRouteUnverified: RelayRoute? = null
    @Volatile private var relaySetupStartedNs = 0L
    @Volatile private var preSetupWatchdog: Thread? = null

    /** True while this phone relays someone else's call. */
    val relayingNow: Boolean get() = relayDuty != null
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
    private val decodeMaskByTransport = HashMap<BluetoothTransportOption, Int>()
    private val endpointDecodeMask: Int
        get() = synchronized(decodeMaskByTransport) {
            decodeMaskByTransport.getOrPut(transport) {
                privateCallEndpointDecodeMask(
                    restrictedMask = capabilities.decodeCodecMask,
                    platformMask = if (privateCallDecodeRestricted(stackFingerprint, transport)) {
                        VideoCodec.availableDecoders().fold(0) { mask, decoder -> mask or codecCapabilityMask(decoder.protocolId) }
                    } else {
                        capabilities.decodeCodecMask
                    },
                    fallback = DebugTunables.callCodecFallback,
                )
            }
        }
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
    /** Experimental dual listening: this CoC endpoint's second listener (the GATT call service), while it exists. */
    @Volatile private var dualSecondary: DualSecondaryHost? = null
    /** Experimental dual listening: the transports the current outgoing attempt may use, in order. */
    @Volatile private var dualPlan: List<BluetoothTransportOption> = emptyList()
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
    // Promoted for GATT endpoints in user launches (DebugTunables.callGattSpeechDefaults); benchmark arms stay extras-only.
    private val gattSpeechDefaults =
        DebugTunables.callGattSpeechDefaults && endpointTransport == BluetoothTransportOption.BLE_GATT
    private val videoFragmentsOn = DebugTunables.callVideoFragments || gattSpeechDefaults

    /** Experimental peer labels (PeerLabelFeature, default off), fixed for this endpoint. */
    private val labelsOn = PeerLabelFeature.enabled
    @Volatile private var peerAcceptsLabels = false

    /** Rotating IDs, Phase 2: the OFFER / PRESENCE_LEASE rules of this call session (null: not negotiated). */
    @Volatile private var presenceSession: PresenceSession? = null

    /**
     * Rotating IDs: the callee's `BDX1` OPENING this caller still waits for (it must be the callee's first
     * end-to-end record), with what it has to open. Null: nothing awaited.
     */
    private class AwaitedOpening(val selected: DiscoveryAnnouncement, val endpointNode: NodeId, val linkNode: NodeId)

    /**
     * BMH3 pseudonyms: a caller that selected a rotating offer and met a pseudonymous callee has not yet proved the callee
     * is the owner of the offer; the callee's reveal does (PROTOCOL "When the reveal happens"). Cleared once verified.
     */
    private class AwaitedReveal(val selected: DiscoveryAnnouncement)

    @Volatile private var awaitedReveal: AwaitedReveal? = null

    /** Visible by name (default off): the offer and page sets of an open window, owned by this endpoint. */
    private var windowAdvertiser: NameWindowAdvertiser? = null
    private val windowLock = Any()
    private val windowListener: () -> Unit = { syncWindowAdvertiser() }

    /**
     * BVMID2 (Naming v2, default off with `bvmid2`). Responder: this connection's gate on the displayed verification code and,
     * once the handshake matched it, the verify-only [QrSessionScope]. Scanner: the parsed code this outgoing connection
     * scans and what to tell the screen when the answerer proved to hold the code's key. See [QrCodeDisplay].
     */
    @Volatile private var qrGate: QrConnectionGate? = null
    @Volatile private var qrScope: QrSessionScope? = null
    @Volatile internal var qrScanPayload: Bvmid2Payload? = null
    @Volatile var onQrScanReached: ((IdentityFingerprint, NodeId) -> Unit)? = null

    /**
     * Naming v2: what the caller still holds back and until when ([CallLabelPolicy]). A name or subject whose moment is
     * [LabelSendMoment.AFTER_REVEAL], [LabelSendMoment.AFTER_REVEAL_IF_VERIFIED] or [LabelSendMoment.AFTER_OPENING] is
     * queued by the reveal or the opening handler; [LabelSendMoment.NOW] was queued before the Invite.
     */
    @Volatile private var heldLabels = CallerLabelPlan(LabelSendMoment.NEVER, LabelSendMoment.NEVER)

    /** The key this phone signed its end-to-end handshake with (the real identity, or the pseudonym it presented). */
    @Volatile private var endpointSigner: SigningIdentity = identity

    /** The callee's pinned opening entries, kept for its reveal (a pseudonymous callee sends no OPENING at the Invite). */
    @Volatile private var revealEntries: List<OpeningEntry> = emptyList()
    @Volatile private var awaitedOpening: AwaitedOpening? = null
    /** Callee, audience "Only people I verified" in a stranger session: the name waits for the caller's verified reveal. */
    @Volatile private var pendingRevealLabels: ByteArray? = null

    /** Callee: the role-0 entries pinned when the connection was accepted, sent once with the answer to the Invite. */
    @Volatile private var openingToSend: List<OpeningEntry>? = null

    /** Both endpoint hellos carried bit 3 in this session: BDX1 records are exchanged. */
    @Volatile private var discoveryNegotiated = false
    /** R3: at most one LABELS record received before this session's Invite, verified on Invite. */
    private var bufferedLabels: ByteArray? = null
    private var callLabels: LabelInbox? = null
    /** R1: this callee's own name, signed at Invite time, sent only with Accept (or at Ringing to a verified contact). */
    @Volatile private var pendingAcceptLabels: ByteArray? = null
    /** Subject typed for this outgoing call (captured when the call starts). */
    @Volatile private var outgoingSubject: String? = null

    /** Experimental speech-led call playout (off by default, see CallPlayoutPolicy). */
    private val speechLedPlayout = DebugTunables.callSpeechLedPlayout || gattSpeechDefaults
    @Volatile private var peerAcceptsVideoFragments = false
    private val videoReassembler = CallVideoReassembler()
    private val fragmentedVideoFrames = AtomicLong()
    private val interleavedAudioPackets = AtomicLong()

    /**
     * Experimental airtime-sized fragments (off by default, see CallFragmentAirtime): target link time
     * per fragment in ms, 0 = the fixed 2 KB fragments. Counts only with [videoFragmentsOn].
     */
    private val fragmentAirtimeMs = if (videoFragmentsOn) CallFragmentAirtime.clampTargetMs(DebugTunables.callFragmentAirtimeMs) else 0
    /** Delivered-rate peak, fragment statistics and the learned probe: recreated for every link. */
    @Volatile private var fragmentRates = CallFragmentRateEstimator()
    @Volatile private var fragmentStats = CallFragmentStats()
    @Volatile private var fragmentProbeKbps: Int? = null

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
    private val pacedWrites: Boolean
        get() = callLinkPaced(transport, DebugTunables.callLinkPacing, DebugTunables.linkPacing)
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
        if (rotatingAdvert) NameWindowRuntime.addListener(windowListener)
        // Bluetooth ON recovery (BluetoothOnRecovery.kt): the listener's native parts die with the adapter.
        BluetoothEpoch.register(listenerEpochName, onOff = {}, onOn = ::recoverListenerAfterBluetoothOn)
        onStatus("Available for a private call over ${endpointTransport.shortLabel}")
    }

    private val listenerEpochName = "call-listener-" + Integer.toHexString(System.identityHashCode(this))

    /**
     * Bluetooth ON recovery (C): turning Bluetooth off and on destroys the call listener's GATT server and its video service,
     * the CoC control server and listener, and the advertising set. The advertising watchdog alone brings back only the set, so a
     * caller discovered the phone and then failed with "GATT video service discovery failed" (lane15 a55m31-edge4-rerun). An
     * idle listener is closed and reopened through the same path as the rotating-ID restart ([onDiscoveryBoundary]): stopAvailability,
     * then openCallListener, with no silence gap. A call or exchange in progress is left to its own end-of-call restart.
     */
    private fun recoverListenerAfterBluetoothOn() {
        if (!BluetoothOnRecovery.enabled) return
        thread(name = "PrivateCallBluetoothOn") {
            val component = BluetoothOnRecovery.COMPONENT_CALL_LISTENER
            val decision = synchronized(this) {
                BluetoothOnRecoveryPolicy.callListener(
                    running = running.get(),
                    idle = state == null && !callRunning.get(),
                    listening = listenerThread != null || gattServer != null || cocControlServer != null || classicAdvertiser != null,
                )
            }
            if (!decision.restart) {
                BluetoothOnRecovery.log(component, "skipped", decision.reason, "transport=${endpointTransport.shortLabel}")
                return@thread
            }
            synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                listenerGeneration++
                stopAvailability(forActiveConnection = false)
                advertisedEpoch = -1L
            }
            val stopsBefore = synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                if (listenerThread != null || gattServer != null) return@thread
                fullAvailabilityStops
            }
            // Outside the monitor, as in onDiscoveryBoundary: opening waits for advertising callbacks on the main thread.
            val opened = runCatching { openCallListener() }
            synchronized(this) {
                if (fullAvailabilityStops != stopsBefore || !running.get()) {
                    stopAvailability(forActiveConnection = false)
                    BluetoothOnRecovery.log(component, "skipped", "closed_or_stopped_during_reopen", "transport=${endpointTransport.shortLabel}")
                    return@thread
                }
            }
            opened.onSuccess {
                BluetoothOnRecovery.log(component, "restarted", decision.reason, "transport=${endpointTransport.shortLabel}")
                onStatus("Available for a private call over ${endpointTransport.shortLabel}")
            }.onFailure { error ->
                BluetoothOnRecovery.log(
                    component, "skipped", "reopen_failed", "transport=${endpointTransport.shortLabel} | error=${error.message ?: error.javaClass.simpleName}",
                )
                onStatus("Private-call availability paused after Bluetooth came back: ${error.message ?: error.javaClass.simpleName}")
            }
        }
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
            // startOutgoing may be waiting for this monitor (codex review 2026-10-10).
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
                            "silenceMs=$silenceMs | transport=${endpointTransport.shortLabel}",
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

    private fun closeWindowAdvertiser() {
        synchronized(windowLock) {
            windowAdvertiser?.close()
            windowAdvertiser = null
        }
    }

    /** The window offer: this endpoint's own offer fields on version 8, the role 3 ID and the name hash. */
    private fun windowOffer(rdi: ByteArray, hash: ByteArray): DiscoveryAnnouncement = WindowOffer.announcement(
        rdi,
        hash,
        transportFlags = when (endpointTransport) {
            BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
            else -> 0
        },
        capabilitySummary = capabilities.encodeCodecMask and capabilities.decodeCodecMask,
    )

    /**
     * Starts or stops the window's sets to match the window and this endpoint: wanted only while the window is open, the
     * endpoint is listening and no call or exchange holds it. A phone that cannot start the sets says so and ends the window.
     */
    private fun syncWindowAdvertiser() {
        if (!rotatingAdvert || !running.get()) return
        thread(name = "NameWindowSync", isDaemon = true) {
            synchronized(windowLock) {
                val listening = listenerThread != null || gattServer != null || cocControlServer != null
                val wanted = NameWindowRuntime.active() && state == null && !callRunning.get() && listening
                if (!wanted) {
                    windowAdvertiser?.close()
                    windowAdvertiser = null
                    return@synchronized
                }
                if (windowAdvertiser != null) return@synchronized
                val advertiser = NameWindowAdvertiser(adapter, ::windowOffer, onStatus)
                if (advertiser.start()) {
                    windowAdvertiser = advertiser
                } else {
                    advertiser.close()
                    NameWindowRuntime.reportCannotShow()
                }
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
        val baseAnnouncement = DiscoveryAnnouncement(
            nodeIdPrefix = rotatingEntry?.rdi ?: identity.nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            version = if (rotatingEntry != null) {
                DiscoveryAnnouncement.ROTATING_VERSION
            } else {
                DiscoveryAnnouncement.FIXED_PREFIX_VERSION
            },
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or when (endpointTransport) {
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
        // Experimental relayed calls: the consent bit and the helper flag (identity when off or rotating).
        val relayedAnnouncement = if (relayedCallsOn) {
            val consent = relayHelper?.consentOn() == true
            val helping = relayHelper?.helperActive() == true
            relayHelper?.offerOpened(helping, consent)
            RelayedCalls.offerFor(
                baseAnnouncement,
                consentOn = consent,
                helperActive = helping,
                rotatingCompat = RelayedCallsFeature.rotatingCompat,
            )
        } else {
            baseAnnouncement
        }
        // Experimental hidden identities (BMH3): a version 2 offer carries the hint bit "this endpoint accepts BMH3
        // handshakes" (the same instance when the switch is off, so the offer is byte-identical).
        val announcement = Bmh3Hint.offerFor(relayedAnnouncement, Bmh3Feature.enabled)
        when (endpointTransport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                val activeServer = ClassicSockets.listen(adapter, "Bluetooth Private Video Call", ClassicRole.PRIVATE_CALL)
                serverSocket = activeServer
                classicAdvertiser = BleDiscoveryAdvertiser(adapter, announcement, onStatus)
                    .also(BleDiscoveryAdvertiser::start)
                listenerThread = thread(name = "PrivateCallClassicAccept") {
                    try {
                        val accepted = activeServer.accept()
                        ClassicRfcommCap.note(accepted)
                        acceptIncoming(socketConnection(accepted))
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
                // Experimental dual listening (default off): the GATT call service first, the hint only once it
                // runs. Off: no server is created and the control server advertises `announcement` itself.
                val advertisedOffer = dualListeningOffer(announcement)
                cocControlServer = BleL2capControlServer(
                    appContext,
                    adapter,
                    activeServer.psm,
                    advertisedOffer,
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
        // A Visible-by-name window adds its sets to this listener (and follows its rotation).
        syncWindowAdvertiser()
    }

    /**
     * Experimental dual listening: starts this CoC endpoint's second listener (the GATT call service, in its
     * own server, sharing the control server's one advertising set) and returns the offer to advertise: the
     * hint is set only when that service is running. Never throws; failure means the plain CoC offer.
     */
    private fun dualListeningOffer(offer: DiscoveryAnnouncement): DiscoveryAnnouncement {
        if (!DualListeningFeature.enabled || !DualListeningWire.mayCarry(offer)) return offer
        // A second listener that already exists (it carries a link) is never registered twice.
        if (dualSecondary != null || linkTransportOverride != null) return offer
        val host = DualSecondaryHost(
            create = {
                GattDualSecondaryServer(appContext, adapter, offer, phyPreference, onStatus) { connection ->
                    // As a GATT endpoint does: an exchange may be preempted by a call, otherwise one call at a time.
                    if (!running.get() || (callRunning.get() && messageSync == null)) {
                        connection.close()
                    } else {
                        acceptIncoming(
                            gattConnection(connection, arrivedOn = BluetoothTransportOption.BLE_GATT),
                        )
                    }
                }
            },
            settle = DualListeningRuntime.settle,
            sleepMs = Thread::sleep,
            log = DualListeningRuntime::log,
        )
        dualSecondary = host
        val ready = host.open()
        if (!ready) {
            host.close()
            dualSecondary = null
        }
        val advertised = DualListeningWire.offerFor(offer, enabled = true, secondaryReady = ready)
        DualListeningRuntime.log(
            DualListeningLog.offer(DualListeningWire.hasHint(advertised), advertised.capabilitySummary, if (ready) "ready" else "failed"),
        )
        return advertised
    }

    /**
     * Experimental store-and-forward (second review N3): while an exchange holds the endpoint, a call
     * listener stays open so an incoming call can preempt the exchange instead of being refused.
     * On LE CoC this costs one more listen registration (counted by CocRegistrations).
     */
    private fun reopenListenerForCalls() {
        if (!running.get()) return
        runCatching {
            when (endpointTransport) {
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

    /** A BVMID2 verification scan shows no call screen: its phases are not reported to the UI. */
    private fun notifyPhase(phase: PrivateCallState) {
        if (qrScanPayload == null) onPhase(phase)
    }

    /** True while this endpoint scans a verification code (it dials like a call but is not one). */
    val isQrScan: Boolean get() = qrScanPayload != null

    @Synchronized
    fun startOutgoing(peer: BleL2capPeer, callType: CallType = CallType.VIDEO): Boolean {
        if (!canStartOutgoingPrivateCall(running.get(), state, callRunning.get())) return false
        // An offer above the app's relay limit is refused, not clamped (PrivateCallRelayChain).
        val hops = PrivateCallRelayChain.callerRelayHops(peer.announcement.routeCost) ?: return false
        // Rotating IDs: a version 5 offer needs this phone's switch on (it can only decode one then). A relayed
        // one (phase 1b: relays mirror it) is verified end to end by the BDX1 OPENING. Classic offers never
        // rotate (rotatingAdvert), so a version 5 Classic offer is refused.
        if (peer.announcement.rotating && (
                !rotatingIds ||
                    peer.announcement.flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT != 0
                )
        ) {
            return false
        }
        // A relayed row needs the relayed-call runtime on this endpoint, and a benchmark that requires a chain
        // never accepts a direct (or DEBUG-relay) route: direct fallback is a failed requested chain.
        if (peer.relayRoute != null && !relayedCallsOn) return false
        if (relayedCallsOn && RelayedCallsFeature.requireRelays > 0 && peer.relayRoute == null) return false
        negotiation = VoiceCallNegotiation.outgoing(voiceCallsEnabled, callType)
        outgoingSubject = PeerLabelFeature.pendingCallSubject.also { PeerLabelFeature.pendingCallSubject = null }
        relayHops = hops
        pendingRelayRoute = if (relayedCallsOn) peer.relayRoute else null
        relaySetupStartedNs = System.nanoTime()
        activeSettings = routeSettings(relayHops)
        val initial = PrivateCallState.outgoing()
        state = initial
        notifyPhase(initial)
        // Experimental dual listening (default off): the transports this call attempt may use, in order.
        linkTransportOverride = null
        dualPlan = DualCallerPlan.plan(endpointTransport, peer.announcement, DualListeningFeature.enabled)
        if (DualListeningFeature.enabled) DualListeningRuntime.log(DualListeningLog.plan(endpointTransport, dualPlan))
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
                    // A stalled 2M setup is the pair's signature (healthy setups authenticate within about 1 s): remember it now.
                    // The retry may not find the callee again (b2-weaklink-ab call-a15s-asus-control-r1: no new advertisement
                    // within 8 s, then 12 s to the old address) and then recorded nothing, so every later call repeated the stall.
                    AdaptivePhyMemory.advertisedPeerToRemember(
                        enabled = DebugTunables.stallRemembersTwoM && DebugTunables.adaptivePhyMemory,
                        stalled = error is CallSetupStalledException,
                        direct = direct,
                        rotating = peer.announcement.rotating,
                        nodePrefix = peer.announcement.nodeIdPrefix,
                    )?.let { AdaptivePhyMemory.recordBad(transport, it, "setup_stall") }
                    discardFailedOutgoingSetup()
                    val retryPeer = if (adaptivePhyRetryRediscovers(DebugTunables.phySetupStallGuard, peer.announcement.routeCost)) {
                        rediscoverPeer(peer, startedAtMs, stalled = error is CallSetupStalledException)
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
                relayRouteUnverified?.let { unverified ->
                    relayHelper?.routeOutcome(unverified, false, null)
                    relayRouteUnverified = null
                }
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
            // A dual attempt already tried GATT too: the safety net's retry would repeat that.
            if (running.get() && transport == BluetoothTransportOption.BLE_L2CAP && dualPlan.size < 2) {
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
        // Every other attempt (the 1M retry, a first attempt without the guard, Classic): an answerer that keeps the
        // link open and never completes the hop handshake is closed after CallSetupDeadlines.HOP_AUTH_MS, so the
        // call fails visibly instead of waiting in OUTGOING (a phone in pseudonym mode that refuses our old-style
        // handshake closes the link; a silent one must not hold the caller either).
        if (stallGuardMs <= 0L) {
            thread(name = "PrivateCallHopAuthDeadline", isDaemon = true) {
                val settledInTime = try {
                    settled.await(CallSetupDeadlines.HOP_AUTH_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!settledInTime && running.get() && setupState.claimTimeout()) {
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            CallSetupDeadlines.logLine(node, SystemClock.elapsedRealtime() - openedAtMs),
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
            if (setupState.timedOut && error !is CallHandshakeTimeoutException) {
                throw CallHandshakeTimeoutException(CallSetupDeadlines.timeoutMessage(), error)
            }
            // The answerer closed the link before the hop handshake finished (not our guard): say why it may have.
            if (setupState.pending && error is IOException &&
                error !is CallSetupStalledException && error !is CallHandshakeTimeoutException
            ) {
                throw CallHandshakeClosedException(CallSetupDeadlines.closedMessage(), error)
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
     * re-advertised 1.6 s after its disconnect as 70:dc:ca:68:95:a6 while the A15s retried the old
     * 43:10:fa:c4:f8:8c until its 12 s connect deadline). Same node prefix and route cost only; the
     * end-to-end handshake still verifies the identity. Falls back to [peer] when none is seen in time.
     */
    private fun rediscoverPeer(peer: BleL2capPeer, startedAtMs: Long, stalled: Boolean = false): BleL2capPeer {
        // Experimental test_stall_rediscover_ms (default 8000 = the clamp below): after a setup stall the scan is not
        // bound by the early-loss window, so a callee that re-advertises 8.6-12.6 s after the close is still found.
        val armMs = DebugTunables.stallRediscoverMs
        val budgetMs = StallRediscover.budgetMs(armMs, stalled, startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS - SystemClock.elapsedRealtime())
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
            if (stalled || armMs > StallRediscover.DEFAULT_MS) {
                Log.i(
                    "BluetoothVideo",
                    StallRediscover.line(
                        budgetMs, SystemClock.elapsedRealtime() - scanStartedMs, fresh != null,
                        fresh?.device?.address == peer.device.address, stalled, armMs,
                    ),
                )
            }
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
        linkTransportOverride = null
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

    /** Messaging v2: the running exchange is a chat session (it stays open while a thread is on screen). */
    val messageSyncIsChat: Boolean
        get() = messageSync?.isChat == true

    /**
     * Experimental: exchanges store-and-forward messages with [peer] over a fresh BMH2 link on the
     * call transport, then ends this endpoint (the activity restarts availability). Returns false
     * when the feature is off or a call/exchange is already running.
     */
    @Synchronized
    fun startMessageSync(peer: BleL2capPeer): Boolean {
        val nodeProvider = storeForwardNode ?: return false
        if (!canStartOutgoingPrivateCall(running.get(), state, callRunning.get())) return false
        // Message exchanges are dialed on the endpoint's own transport only (DECISIONS Design D): a row listed only
        // because of the dual hint (a GATT endpoint, a CoC offer) is not an exchange target.
        if (DualCallerPlan.listsHintedOffer(endpointTransport, peer.announcement, DualListeningFeature.enabled)) return false
        if (!callRunning.compareAndSet(false, true)) return false
        linkTransportOverride = null
        dualPlan = emptyList()
        stopAvailability(forActiveConnection = false)
        connectionThread = thread(name = "MessageSyncConnect") {
            try {
                val setupStartNs = System.nanoTime()
                val (activeConnection, link) = dialMessageLink(peer)
                secureSession = link
                // A message exchange needs a stable identity on both ends (cards, mailbox keys, bundle IDs that stay
                // the same for days): with a stranger it would hand over or link exactly what the pseudonym hides.
                // Contacts still exchange (real key on both ends). PROTOCOL "Message exchanges".
                check(!link.ownPseudonymous && !link.peerPseudonymous) {
                    "A message exchange needs a verified contact: a stranger is only a pseudonym"
                }
                checkSelectedPeer(peer.announcement, link, exchangeLinkOpening(link, initiator = true), messageSync = true)
                runMessageSync(
                    nodeProvider(), link, activeConnection, initiator = true, firstRecord = null,
                    setupMs = (System.nanoTime() - setupStartNs) / 1_000_000,
                )
            } catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                runCatching { onMessageSyncFailed(detail) }
                finish("Message exchange failed: $detail")
            }
        }
        return true
    }

    /**
     * Opens and authenticates the link of a message-exchange dial with the call's 2M protections ([MessageDialPhy],
     * switch `test_message_adaptive_phy`): a peer remembered bad on 2M starts on 1M, a first 2M link whose hop authentication
     * stalls is closed after [AdaptivePhyMemory.SETUP_STALL_MS], and one bounded 1M retry (to the peer's current
     * advertisement) follows inside [AdaptivePhyMemory.EARLY_LOSS_MS]. Off: one attempt on the endpoint's PHY, as before.
     */
    private fun dialMessageLink(peer: BleL2capPeer): Pair<CallConnection, SecureBluetoothSession> {
        val le = transport != BluetoothTransportOption.CLASSIC_RFCOMM
        val adaptive = le && DebugTunables.messageAdaptivePhy
        val startedAtMs = SystemClock.elapsedRealtime()
        val node = offerLogLabel(peer.announcement)
        val firstPhy = if (adaptive) {
            AdaptivePhyMemory.phyFor(transport, peer.announcement.nodeIdPrefix, phyPreference, "message")
        } else {
            phyPreference
        }
        var currentPeer = peer
        fun retryAllowed(elapsedMs: Long) = adaptive && MessageDialPhy.retryEligible(
            enabled = DebugTunables.adaptivePhy && DebugTunables.adaptivePhyMemory,
            running = running.get(),
            le = le,
            firstPhy = firstPhy,
            elapsedMs = elapsedMs,
        )
        fun openAndAuthenticate(phy: BlePhyPreference, guardMs: Long): Pair<CallConnection, SecureBluetoothSession> =
            MessageDialPhy.openAndAuthenticate(
                stallGuardMs = guardMs,
                open = { openOutgoing(currentPeer, phy).also { connection = it } },
                close = { it.close() },
                authenticate = { opened ->
                    opened.link ?: SecureBluetoothHandshake.initiate(
                        BufferedInputStream(opened.input, ioConfig.bufferBytes),
                        BufferedOutputStream(opened.output, ioConfig.bufferBytes),
                        identity,
                        linkCapabilities,
                        bmh3 = Bmh3Wiring.initiator(appContext, peer.announcement, Bmh3Policy.Layer.LINK, rotatingIds),
                    )
                },
                running = running::get,
                nowMs = SystemClock::elapsedRealtime,
                onStall = { sinceOpenMs ->
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK adaptive_phy: link=message | node=$node | phy=${phy.label} | reason=setup_stall | " +
                            "sinceOpenMs=$sinceOpenMs | guardMs=$guardMs | action=close",
                    )
                },
            )
        val stallGuardMs = if (DebugTunables.phySetupStallGuard) AdaptivePhyMemory.SETUP_STALL_MS else 0L
        val dialed = MessageDialPhy.dial(
            firstPhy = firstPhy,
            stallGuardMs = stallGuardMs,
            retryAllowed = ::retryAllowed,
            elapsedMs = { SystemClock.elapsedRealtime() - startedAtMs },
            attempt = { phy, guardMs ->
                // Inner GATT/CoC retries may only use the part of the early-loss window that still leaves room for the 1M retry.
                if (phy == firstPhy && retryAllowed(0L)) {
                    LeSetupBudget.within(startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS) { openAndAuthenticate(phy, guardMs) }
                } else {
                    openAndAuthenticate(phy, guardMs)
                }
            },
            beforeRetry = { error ->
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK adaptive_phy: link=message | node=$node | from=2M to=1M | reason=setup_retry | attempt=2 | " +
                        "error=${error.message ?: error.javaClass.simpleName}",
                )
                onStatus("Message exchange setup on LE 2M failed; retrying once on LE 1M")
                discardFailedMessageSetup()
                currentPeer = if (adaptivePhyRetryRediscovers(DebugTunables.phySetupStallGuard, peer.announcement.routeCost)) {
                    rediscoverPeer(peer, startedAtMs)
                } else {
                    peer
                }
            },
        )
        if (dialed.retried) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK link_phy requested=2M effective=1M reason=" +
                    "${if (dialed.firstError is CallSetupStalledException) "setup_stall" else "setup_retry"} " +
                    "source=adaptive link=message node=$node",
            )
            // The 1M retry authenticated where 2M did not: remember this hop peer as bad on 2M.
            AdaptivePhyMemory.recordBad(transport, dialed.link.peerNodeId.shortLabel(), "message_setup_retry")
        }
        return dialed.connection to dialed.link
    }

    /** Closes a failed message-dial attempt so the 1M retry starts from a clean link (the exchange keeps the endpoint). */
    private fun discardFailedMessageSetup() {
        connection?.close()
        connection = null
        bleControlConnection?.close()
        bleControlConnection = null
        secureSession?.close()
        secureSession = null
    }

    /**
     * An authenticated 2M exchange this phone dialed that died within [AdaptivePhyMemory.EARLY_LOSS_MS] with no answer at
     * all (no HELLO, no chunk): remember the peer as bad on 2M so the next dial starts on 1M
     * ([MessageDialPhy.earlyLossWithoutAnswer]).
     */
    private fun noteMessageExchangeEarlyLoss(result: StoreForwardSyncResult, activeConnection: CallConnection, sinceAuthMs: Long) {
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM || !DebugTunables.messageAdaptivePhy) return
        // An exchange cut because this endpoint is closing (the user left the screen) says nothing about the link.
        if (!running.get() || result.detail == "interrupted") return
        if (!DebugTunables.adaptivePhy || !DebugTunables.adaptivePhyMemory) return
        val phy = runCatching { activeConnection.phySummary() }.getOrDefault("")
        val wasTwoM = linkWasTwoM(phy, requestedLinkPhy == BlePhyPreference.TWO_M)
        val early = MessageDialPhy.earlyLossWithoutAnswer(
            outcome = result.outcome,
            chunksSent = result.chunksSent,
            chunksReceived = result.chunksReceived,
            cardReceived = result.cardOffer != null,
            sinceAuthMs = sinceAuthMs,
            wasTwoM = wasTwoM,
            sendLinks = NodeLinkLoad.sendLinks(transport),
        )
        if (!early) return
        val hop = result.peer.shortLabel()
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=message | node=$hop | from=2M to=1M | reason=early_loss | afterMs=$sinceAuthMs | " +
                "outcome=${result.outcome} | phy=${phy.ifEmpty { "unknown" }} | next=1M",
        )
        AdaptivePhyMemory.recordBad(transport, hop, "message_early_loss")
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
        setupMs: Long = -1,
    ) {
        link.reader.acceptStoreForward = true
        val session = StoreForwardLinkSession(
            link,
            node,
            initiator,
            closeLink = { activeConnection.close() },
            log = { Log.i("BluetoothVideo", it) },
            firstRecord = firstRecord,
            transportLabel = transport.shortLabel,
            setupMs = setupMs,
            appState = { MessageAppState.describe(appContext) },
            chatCapable = runCatching { chatCapable(link.peerNodeId, initiator) }.getOrDefault(false),
        )
        messageSync = session
        reopenListenerForCalls()
        onStatus("Exchanging messages with ${link.peerNodeId.shortLabel()} over ${activeConnection.description}")
        var pendingCall: CallConnection? = null
        val exchangeStartedNs = System.nanoTime()
        val result = try {
            session.run()
        } finally {
            synchronized(this) {
                messageSync = null
                pendingCall = pendingCallConnection
                pendingCallConnection = null
            }
        }
        if (initiator) {
            runCatching { noteMessageExchangeEarlyLoss(result, activeConnection, (System.nanoTime() - exchangeStartedNs) / 1_000_000) }
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
        queueRevealOnAccept()
        pendingAcceptLabels?.let { controls.offerMust(OutboundControl.Labels(it)) }
        pendingAcceptLabels = null
        // ACCEPT, then CAMERA_OFF for a voice answer (experimental), in one ordered control queue.
        (negotiation?.acceptSignals() ?: listOf(PrivateCallSignal.ACCEPT)).forEach { enqueueSignal(it) }
        startLocalMedia()
        startPresenceSessionAsync()
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
        // Experimental dual listening: a link that arrived on the second transport makes this endpoint run
        // GATT's hello and policies for it, and only while the second listener really existed.
        val arrivedOn = activeConnection.arrivedOn
        if (arrivedOn != null && arrivedOn != endpointTransport &&
            !DualLinkRule.arrivalAllowed(endpointTransport, dualSecondary?.ready == true, arrivedOn)
        ) {
            callRunning.set(false)
            activeConnection.close()
            return
        }
        linkTransportOverride = arrivedOn?.takeIf { it != endpointTransport }
        val dualEndpoint = DualListeningFeature.enabled && (arrivedOn != null || dualSecondary != null)
        if (DualListeningFeature.enabled) {
            DualListeningRuntime.log(DualListeningLog.link(arrivedOn ?: endpointTransport, endpointTransport))
        }
        stopAvailability(forActiveConnection = true)
        if (relayedCallsOn) startPreSetupWatchdog(activeConnection)
        if (CallSetupDeadlines.incomingDeadlineApplies(dualEndpoint, relayedCallsOn, DebugTunables.callHandshakeDeadlineAll)) {
            startIncomingDeadline(activeConnection, dualEndpoint)
        }
        connectionThread = thread(name = "PrivateCallIncomingHandshake") {
            try {
                establish(activeConnection, initiator = false, selected = null)
            } catch (error: Exception) {
                // One line on the answering phone for every refused or failed incoming handshake (a refused old-style
                // handshake of a phone in pseudonym mode included), with the fixed reason vocabulary and no identifiers.
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + CallSetupDeadlines.incomingFailureLine(error),
                )
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
        presenceSession = null
        awaitedOpening = null
        openingToSend = null
        discoveryNegotiated = false
        // Rotating IDs: the IDs a callee may open are pinned when the connection is accepted, not when a
        // record is sent (a relay chain can deliver the caller handshake much later; PROTOCOL Phase 1b).
        val pinsAtAccept = if (rotatingIds && !initiator) {
            // While a Visible-by-name window is open the opening holds the current role 0 and role 3 entries (at most two).
            WindowOpening.entries(
                RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_CALL_OFFER),
                RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_WINDOW_OFFER),
                windowOpen = NameWindowRuntime.active(),
            )
        } else {
            emptyList()
        }
        // Experimental hidden identities (BMH3): null (switch off, or a version 2 peer with no hint and no pin) is
        // BMH2 exactly as before. The end-to-end handshake below makes the same choice.
        // One throwaway for the caller's link and end-to-end handshakes of this attempt, so a callee sees one stranger.
        val callerAlias = if (initiator && Bmh3PseudonymFeature.active(rotatingIds)) Bmh3Policy.pseudonymKeys.fresh() else null
        val bmh3Initiator = if (initiator) {
            Bmh3Wiring.initiator(appContext, selected, Bmh3Policy.Layer.LINK, rotatingIds, strangerIdentity = callerAlias, qrScan = qrScanPayload)
        } else {
            null
        }
        // BVMID2: one gate per incoming connection, shared by its link and end-to-end handshakes (single reservation).
        qrGate = if (!initiator && QrMeet.display.hasCode()) QrMeet.display.newGate() else null
        val bmh3Responder = if (initiator) {
            null
        } else {
            Bmh3Wiring.responder(
                pinsAtAccept, identity.nodeId.toByteArray(), rotatingIds,
                qrDisplay = qrGate?.let { QrMeet.display }, qrGate = qrGate,
            )
        }
        heldLabels = CallerLabelPlan(LabelSendMoment.NEVER, LabelSendMoment.NEVER)
        pendingRevealLabels = null
        awaitedReveal = null
        revealEntries = emptyList()
        endpointSigner = identity
        val link = activeConnection.link ?: run {
            val input = BufferedInputStream(activeConnection.input, ioConfig.bufferBytes)
            val output = BufferedOutputStream(activeConnection.output, ioConfig.bufferBytes)
            if (initiator) {
                SecureBluetoothHandshake.initiate(input, output, identity, linkCapabilities, bmh3 = bmh3Initiator)
            } else {
                SecureBluetoothHandshake.respond(
                    input, output, identity, linkCapabilities, bmh3 = bmh3Responder, beforeHello = ::strangerTarpit,
                )
            }
        }
        require(link.peerCapabilities.transportMask and transport.capabilityMask != 0) {
            "Peer did not authenticate ${transport.shortLabel} support"
        }
        // Experimental dual listening: a link on the second transport must be one the selected offer allowed.
        if (initiator && selected != null && DualListeningFeature.enabled &&
            (linkTransportOverride != null || DualListeningWire.hasHint(selected))
        ) {
            require(DualLinkRule.verifyCaller(selected, transport, link.peerCapabilities.transportMask) == DualLinkRule.Result.PASS) {
                "Peer did not offer or authenticate ${transport.shortLabel} support"
            }
        }
        val opening = exchangeLinkOpening(link, initiator, pinsAtAccept)
        // A pseudonymous callee cannot open the selected ID yet; its reveal binds it later (awaitedReveal).
        if (!link.peerPseudonymous) selected?.let { checkSelectedPeer(it, link, opening) }
        onHopAuthenticated()
        // test_coc_phy_after_connect (default off): the CoC central requests the PHY only now.
        if (initiator) bleControlConnection?.requestPhyAfterConnect("call")
        if (!initiator) learnClassicPeerAddress(activeConnection.socket, link.peerNodeId, "incoming")
        // Experimental relayed calls: the caller of a relayed route asks the first helper to extend it
        // (SETUP/READY) before the end-to-end handshake runs through the chain.
        if (initiator) pendingRelayRoute?.let { route -> runRelaySetup(link, activeConnection, route, opening) }
        // Experimental store-and-forward / relayed calls (only when enabled): an incoming link whose first
        // record is a BSF1 message is a message exchange, and a BRS1 SETUP is a relay request, not a call.
        // Any other first record continues into the unchanged call handshake, and types 11 and 12 are
        // rejected again from then on.
        val storeForwardDispatch = storeForwardNode?.takeIf { !initiator }
        val firstRecord = if (storeForwardDispatch != null || (relayedCallsOn && !initiator)) {
            val record = readFirstRecordForDispatch(
                link,
                acceptStoreForward = storeForwardDispatch != null && linkTransportOverride == null,
                acceptRelaySetup = relayedCallsOn,
            )
            preSetupWatchdog?.interrupt()
            preSetupWatchdog = null
            if ((record.type == SecureRecordType.STORE_FORWARD || record.type == SecureRecordType.RELAY_SETUP) &&
                (link.ownPseudonymous || link.peerPseudonymous || link.qrTagMatched)
            ) {
                // A stranger is only a pseudonym here: no card, no offers, no relay setup (PROTOCOL "Message exchanges").
                error("A message exchange or relay request needs a verified contact")
            }
            if (record.type == SecureRecordType.STORE_FORWARD && storeForwardDispatch != null) {
                // A message exchange is a user-visible action, not a probe (RV8 tarpit).
                if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
                secureSession = link
                runMessageSync(storeForwardDispatch(), link, activeConnection, initiator = false, firstRecord = record)
                return
            }
            if (record.type == SecureRecordType.RELAY_SETUP) {
                secureSession = link
                serveRelaySetup(link, activeConnection, record)
                return
            }
            record
        } else {
            null
        }
        if (endpointDecodeMask != capabilities.decodeCodecMask) {
            Log.i(
                "BluetoothVideo",
                "Private-call compatibility: end-to-end hello prefers VP9 and also offers this phone's other " +
                    "decoders (mask ${capabilities.decodeCodecMask} -> $endpointDecodeMask)",
            )
        }
        advertisedDecodeMask = endpointDecodeMask
        val endpointCapabilities = CallEndpointOpening.advertise(
            PeerLabelFeature.advertise(
                CallVideoFragments.advertise(
                    capabilities.copy(decodeCodecMask = endpointDecodeMask),
                    videoFragmentsOn,
                ),
                labelsOn,
            ),
            rotatingIds,
        )
        val endpointBmh3 = if (bmh3Initiator != null) {
            Bmh3Wiring.initiator(appContext, selected, Bmh3Policy.Layer.ENDPOINT, rotatingIds, strangerIdentity = callerAlias, qrScan = qrScanPayload)
        } else {
            null
        }
        val endpoint = if (initiator) {
            EndToEndVideoHandshake.initiate(
                link,
                identity,
                endpointCapabilities,
                // The same choice as the link's (a relay is assumed to run this build). The callee is bound to the
                // selected advert before this phone reveals its end-to-end identity.
                endpointBmh3,
            )
        } else {
            EndToEndVideoHandshake.respond(link, identity, endpointCapabilities, firstRecord, bmh3Responder)
        }
        // BVMID2: the connection matched the displayed code. It exists only to verify: scope it, commit the reservation
        // (the completed end-to-end handshake is the verification event), and end it after a short grace.
        if (!initiator && endpoint.qrTagMatched) beginQrVerifySession(endpoint)
        // Rotating IDs, Phase 1b: when both endpoint hellos carry bit 3 the callee's first end-to-end record,
        // sent right before its first answer to the Invite, is a BDX1 OPENING (its pinned role-0 entries). A
        // caller that selected a version 5 offer verifies it before anything else from the callee (RD6).
        discoveryNegotiated = CallEndpointOpening.negotiated(rotatingIds, endpoint.peerCapabilities)
        // BMH3 pseudonyms: the key this phone signed the labels with, and (callee) the entries it will open only at Accept.
        val ownPseudonym = endpoint.reveal?.ownPseudonymous == true
        if (ownPseudonym) {
            endpointSigner = endpointBmh3?.strangerIdentity ?: bmh3Responder?.strangerIdentity ?: identity
            Bmh3Android.log(
                "bmh3: event=peer | layer=endpoint | pseudonymous=${endpoint.peerIsPseudonym}" +
                    (debugPeer4(endpoint)?.let { " | peer4=$it" } ?: ""),
            )
        }
        if (discoveryNegotiated && !initiator) {
            if (ownPseudonym) revealEntries = pinsAtAccept else openingToSend = pinsAtAccept
        }
        if (discoveryNegotiated) {
            // The presence rules exist from the handshake on, so an OFFER that arrives before this side's own
            // start (the peer answered first) is recorded, not dropped; nothing is sent before the call is answered.
            // A throwaway peer is judged at its reveal (newPresenceSession again there), not as a stranger now.
            presenceSession = newPresenceSession(endpoint.peerNodeId, endpoint.peerFingerprint, "handshake", log = !endpoint.peerIsPseudonym)
        }
        selected?.let { announcement ->
            if (endpoint.peerIsPseudonym) {
                // The callee answered with a throwaway (version 5 or version 2 offer alike): the binding to the selected
                // offer arrives inside its reveal, after Accept. A direct call must still reach the same throwaway at
                // both layers (one stranger, no relay in between).
                require(discoveryNegotiated) { "Call peer identity changed between discovery and connection" }
                require(announcement.routeCost > 0 || endpoint.peerNodeId == link.peerNodeId) {
                    "Call peer identity changed between discovery and connection"
                }
                awaitedReveal = AwaitedReveal(announcement)
            } else if (announcement.rotating) {
                // RV15: the check follows the selected version 5 offer. Direct calls also keep the link
                // opening (checkSelectedPeer); a relayed offer is verified end to end, by the OPENING.
                require(discoveryNegotiated) { "Call peer identity changed between discovery and connection" }
                awaitedOpening = AwaitedOpening(announcement, endpoint.peerNodeId, link.peerNodeId)
            } else {
                require(
                    endpoint.peerNodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                        .contentEquals(announcement.nodeIdPrefix),
                ) { "Call peer identity changed between discovery and connection" }
            }
        }
        // Experimental relayed calls: the route proved itself only now, when the target's identity checked out.
        relayRouteUnverified?.let { verified ->
            relayHelper?.routeOutcome(verified, true, null)
            relayRouteUnverified = null
        }
        peerMaxBitrateKbps = endpoint.peerCapabilities.maxBitrateKbps
        peerAcceptsVideoFragments = CallVideoFragments.negotiated(videoFragmentsOn, endpoint.peerCapabilities, relayHops = 0)
        // Type 9 parses only on this endpoint's opener and only after it advertised fragments.
        endpoint.opener.acceptVideoFragments = videoFragmentsOn
        // Type 10 likewise, only after this endpoint advertised labels (hello bit 5).
        endpoint.opener.acceptLabels = labelsOn
        // Type 11 (BDX1) likewise, only after both endpoint hellos carried bit 3.
        endpoint.opener.acceptDiscovery = discoveryNegotiated
        peerAcceptsLabels = PeerLabelFeature.negotiated(labelsOn, endpoint.peerCapabilities)
        if (videoFragmentsOn) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_video_fragments: negotiated=$peerAcceptsVideoFragments | " +
                    "fragmentBytes=${CallVideoFragments.FRAGMENT_BYTES} | " +
                    "minPacketBytes=${CallVideoFragments.MIN_FRAGMENTED_PACKET_BYTES}",
            )
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_fragment_airtime config: mode=${if (fragmentAirtimeMs > 0) "airtime" else "fixed"} | " +
                    "negotiated=$peerAcceptsVideoFragments | targetMs=$fragmentAirtimeMs | " +
                    "fixedChunkBytes=${CallVideoFragments.FRAGMENT_BYTES} | minChunkBytes=${CallFragmentAirtime.MIN_CHUNK_BYTES} | " +
                    "maxChunkBytes=${CallFragmentAirtime.MAX_CHUNK_BYTES} | " +
                    "perFragmentOverheadBytes=${CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES} | " +
                    "maxOverheadPct=${CallFragmentAirtime.MAX_OVERHEAD_PERCENT} | " +
                    "minChunkOverheadPct=${"%.1f".format(java.util.Locale.ROOT, CallFragmentAirtime.overheadPercent(CallFragmentAirtime.MIN_CHUNK_BYTES))} | " +
                    "fixedChunkOverheadPct=${"%.1f".format(java.util.Locale.ROOT, CallFragmentAirtime.overheadPercent(CallVideoFragments.FRAGMENT_BYTES))}",
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
        fragmentRates = CallFragmentRateEstimator()
        fragmentStats = CallFragmentStats()
        fragmentProbeKbps = if (fragmentAirtimeMs > 0) runCatching { LinkCapacity.measuredLinkKbps(transport) }.getOrNull() else null
        startWriter(link, endpoint)
        startReader(link, endpoint, initiator)
        val scan = qrScanPayload
        if (initiator && scan != null) {
            // BVMID2 scanner: the answerer proved it holds the code's key at both layers. No Invite, no labels, no call:
            // hand the screen the authenticated peer (it asks "Is this ...'s screen you scanned?") and end the session.
            onQrScanReached?.invoke(endpoint.peerFingerprint, endpoint.peerNodeId)
            thread(name = "QrScanClose", isDaemon = true) {
                Thread.sleep(QrSessionScope.CLOSE_GRACE_MS)
                finish("Verification scan finished")
            }
            return
        }
        if (initiator) {
            // Experimental peer labels: the caller's name and subject, bound to this call ID, queued
            // before Invite only when the callee's endpoint hello has bit 5 (design 3.4).
            // A relayed version 5 callee is proven only by its OPENING, which it sends after the Invite: the
            // name and subject then follow it (the callee takes labels before or after the Invite alike).
            // Naming v2 (Design G, G1): one decision, [CallLabelPolicy.callerPlan]. The subject is always held until the
            // callee's reveal is verified; the name goes now (stranger session, "Show my name before they answer"),
            // after the reveal (the Design F control arm), after the OPENING (a relayed callee) or only to verified
            // contacts, by the audience setting.
            val plan = CallLabelPolicy.callerPlan(
                CallerLabelContext(
                    labelsNegotiated = peerAcceptsLabels,
                    hasName = PeerLabelFeature.localName != null,
                    hasSubject = outgoingSubject != null,
                    audience = PeerLabelFeature.audience,
                    nameBeforeAnswer = PeerLabelFeature.nameBeforeAnswer,
                    calleeAwaitsReveal = awaitedReveal != null,
                    relayedAwaitsOpening = awaitedOpening?.selected?.routeCost?.let { it > 0 } == true,
                    calleeIsVerifiedContact = peerIsVerifiedContact(endpoint),
                ),
            )
            heldLabels = plan
            val nameNow = plan.name == LabelSendMoment.NOW
            val subjectNow = plan.subject == LabelSendMoment.NOW
            if (nameNow || subjectNow) queueCallerLabels(includeName = nameNow, includeSubject = subjectNow)
            // INVITE, then CAMERA_OFF for a voice call (experimental), in one ordered control queue.
            (negotiation?.inviteSignals() ?: listOf(PrivateCallSignal.INVITE)).forEach { enqueueSignal(it) }
        }
        onStatus(
            "Encrypted private-call link to " +
                (if (endpoint.peerIsPseudonym) "an unverified stranger" else endpoint.peerNodeId.shortLabel()) +
                " over ${activeConnection.description}",
        )
    }

    /** DEBUG only: the first four bytes of the node ID the peer presented, to compare pseudonyms across epochs. */
    private fun debugPeer4(endpoint: EndToEndVideoSession): String? =
        if (BuildConfig.DEBUG) endpoint.peerNodeId.toString().take(8) else null

    /** The peer's real key (not a throwaway) equals a verified contact's right now. False for any pseudonym. */
    private fun peerIsVerifiedContact(endpoint: EndToEndVideoSession): Boolean =
        !endpoint.peerIsPseudonym && runCatching {
            PeerIdentityGate.store?.state(endpoint.peerNodeId, endpoint.peerFingerprint) == VerificationState.VERIFIED
        }.getOrDefault(false)

    private fun queueCallerLabels(includeName: Boolean = true, includeSubject: Boolean = true) {
        val binding = LabelBinding.call(requireNotNull(state).callId)
        val labels = listOfNotNull(
            if (includeName) LocalLabels.signed(endpointSigner, LabelKind.PERSON_NAME, binding, PeerLabelFeature.localName) else null,
            if (includeSubject) LocalLabels.signed(endpointSigner, LabelKind.CALL_SUBJECT, binding, outgoingSubject) else null,
        )
        if (labels.isNotEmpty()) controls.offerMust(OutboundControl.Labels(SignedLabelCodec.encodeContainer(labels)))
        Log.i("BluetoothVideo", "BENCHMARK peer_labels call_tx: role=caller | count=${labels.size}")
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
                        is OutboundControl.Discovery -> link.writer.write(
                            SecureRecordType.END_TO_END_CONTENT,
                            endpoint.sealer.seal(EndToEndContentType.DISCOVERY, 0, control.payload),
                        )
                        is OutboundControl.Reveal -> link.writer.write(
                            SecureRecordType.END_TO_END_CONTENT,
                            endpoint.sealer.seal(EndToEndContentType.DISCOVERY, 0, control.build()),
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
                    reportFragmentStatsIfDue()
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

    /** Responder side of a BVMID2 session: scope, completion event and the 60 s limit (PROTOCOL "Scope of the session"). */
    private fun beginQrVerifySession(endpoint: EndToEndVideoSession) {
        val scope = QrSessionScope(SystemClock.elapsedRealtime())
        qrScope = scope
        endedQuietly = true // a verification session is not a call: its end opens no screen
        val gate = qrGate
        val committed = gate?.complete(endpoint.peerFingerprint) == true
        Bmh3Android.log("bmh3: event=qr_session | role=displayer | committed=$committed")
        thread(name = "QrVerifyLimit", isDaemon = true) {
            Thread.sleep(if (committed) QrSessionScope.CLOSE_GRACE_MS else QrSessionScope.MAX_SESSION_MS)
            if (qrScope === scope) finish("Verification session finished")
        }
    }

    private fun handleContent(content: EndToEndContentRecord, initiator: Boolean) {
        qrScope?.let { scope ->
            // A verification session accepts the scanner's name label and nothing else (no Invite, discovery, media, ...).
            if (scope.expired(SystemClock.elapsedRealtime()) || scope.refuseContent(content.type)) {
                Bmh3Android.log("bmh3: event=qr_session | role=displayer | violation=content")
                error("Verification session scope")
            }
        }
        awaitedOpening?.let { wait ->
            // The callee's first end-to-end record must be its OPENING; anything else, or a mismatch, ends the call.
            awaitedOpening = null
            verifyAwaitedOpening(wait, content)
            val held = heldLabels
            val nameNow = held.name == LabelSendMoment.AFTER_OPENING
            val subjectNow = held.subject == LabelSendMoment.AFTER_OPENING
            if (nameNow || subjectNow) {
                heldLabels = CallerLabelPlan(
                    if (nameNow) LabelSendMoment.NEVER else held.name,
                    if (subjectNow) LabelSendMoment.NEVER else held.subject,
                )
                queueCallerLabels(includeName = nameNow, includeSubject = subjectNow)
            }
            return
        }
        when (content.type) {
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                handleSignal(PrivateCallSignalCodec.decode(content.payload), initiator)
            EndToEndContentType.DISCOVERY -> handleDiscoveryContent(content.payload)
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
            // Rotating IDs: the OPENING is the callee's first record, ahead of any label or answer.
            openingToSend?.let { entries ->
                openingToSend = null
                controls.offerMust(OutboundControl.Discovery(DiscoveryExchange.encode(DiscoveryExchange.Opening(entries))))
            }
            state = incoming
            // Call blocking: a blocked person, or any unknown caller while that setting is on, never rings. The
            // caller sees the normal Ringing and then the normal Decline (after a human-like delay); this phone
            // shows nothing, posts nothing and does not wake the screen.
            declineSilentlyIfBlocked(incoming.callId)?.let { return }
            onPhase(incoming)
            bufferedLabels?.let { applyCallLabels(it, message.callId) }
            bufferedLabels = null
            if (peerAcceptsLabels) prepareCalleeLabels(message.callId)
            enqueueSignal(PrivateCallSignal.RINGING)
            // A stranger whose call rings was not a probe (RV8 tarpit counts only silent handshakes).
            if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
            onIncoming(contentSession?.peerNodeId?.shortLabel() ?: "Nearby device")
            if (contentSession?.peerIsPseudonym == true) {
                onStatus("Incoming call from an unverified stranger: your identity is revealed only if you answer")
            }
            // Relayed means the authenticated link peer is not the authenticated call peer; the Invite's own
            // relay count is the caller's claim and cannot switch the cap off.
            val viaRelay = relayHops > 0 || (contentSession?.peerNodeId != null && contentSession?.peerNodeId != secureSession?.peerNodeId)
            if (relayedCallsOn && viaRelay) scheduleRelayedRingingCap(incoming.callId)
            return
        }
        require(message.callId == current.callId) { "Private-call ID changed during the call" }
        when (message.signal) {
            PrivateCallSignal.RINGING -> transition(PrivateCallEvent.REMOTE_RINGING)
            PrivateCallSignal.ACCEPT -> {
                // A pseudonymous callee's reveal comes before its Accept in the same ordered queue; an Accept without
                // a verified reveal is a callee that could not prove it owns the offer.
                require(awaitedReveal == null) { "Call peer identity changed between discovery and connection" }
                transition(PrivateCallEvent.REMOTE_ACCEPT)
                startLocalMedia()
                startPresenceSessionAsync()
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
        // Naming v2: a verified contact's last claimed name is kept (sealed, local) for its detail and the one change note.
        if (name != null && peerIsVerifiedContact(endpoint)) {
            val fingerprint = endpoint.peerFingerprint
            thread(name = "ContactClaimRecord", isDaemon = true) {
                runCatching { ContactClaims.store(appContext).recordClaim(fingerprint, name, System.currentTimeMillis()) }
            }
        }
        Log.i("BluetoothVideo", "BENCHMARK peer_labels call_rx: name=${name != null} | subject=${subject != null}")
        onPeerLabels(prefix)
    }

    /**
     * Signs this callee's name once at Invite time (off the UI thread). R1: it is sent at Ringing only
     * to a verified contact (full fingerprint), otherwise only with Accept.
     */
    private fun prepareCalleeLabels(callId: java.util.UUID) {
        val endpoint = contentSession ?: return
        val label = LocalLabels.signed(endpointSigner, LabelKind.PERSON_NAME, LabelBinding.call(callId), PeerLabelFeature.localName)
            ?: return
        val container = SignedLabelCodec.encodeContainer(listOf(label))
        // Naming v2: one decision, [CallLabelPolicy.calleeMoment] (audience, verified contact, stranger session).
        val moment = CallLabelPolicy.calleeMoment(
            CalleeLabelContext(
                labelsNegotiated = peerAcceptsLabels,
                hasName = true,
                audience = PeerLabelFeature.audience,
                ownPseudonymous = endpoint.reveal?.ownPseudonymous == true,
                callerIsVerifiedContact = peerIsVerifiedContact(endpoint),
            ),
        )
        when (moment) {
            LabelSendMoment.AT_RINGING -> controls.offerMust(OutboundControl.Labels(container))
            LabelSendMoment.AT_ACCEPT -> pendingAcceptLabels = container
            LabelSendMoment.AFTER_REVEAL_IF_VERIFIED -> pendingRevealLabels = container
            else -> Unit
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
                supportedVideoModes(activeCodec, activeSettings),
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
        notifyPhase(next)
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
     * flush rule); otherwise exactly one whole media packet, as before. The fragment size is the
     * fixed 2 KB, or (experimental, [fragmentAirtimeMs] > 0) sized by the link rate (CallFragmentAirtime).
     */
    private fun writeMediaRecord(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        packet: OutboundMedia,
        flushBetween: () -> Unit,
    ): Long {
        val fragmenting = peerAcceptsVideoFragments && relayHops == 0 &&
            CallFragmentAirtime.shouldFragment(
                packet.payload.size + StreamProtocol.PACKET_HEADER_BYTES,
                packet.flags,
                fragmentAirtimeMs,
                fragmentRate(),
            )
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
        val fragmentWriter = CallFragmentWriter<OutboundMedia>(
            targetMs = fragmentAirtimeMs,
            rate = { fragmentRate() },
            stats = fragmentStats,
            nanoTime = System::nanoTime,
            running = callRunning::get,
            writeFragment = { flags, fragment ->
                EndToEndStreamProtocol.writeVideoFragment(link.writer, endpoint.sealer, flags, fragment)
                // Each fragment's chunk counts when written and when it arrives, so audio written between
                // fragments keeps the same byte order on both sides.
                peerDelivery.onSent(fragment.size - CallVideoFragments.HEADER_BYTES, System.nanoTime())
            },
            takeAudio = { takeQueuedAudio() },
            audioQueuedAtNs = OutboundMedia::queuedAtNs,
            writeAudio = { audio -> writeInterleavedAudio(link, endpoint, audio) },
            flushBetween = flushBetween,
            afterFragment = { reportFragmentStatsIfDue() },
        )
        val audioBytes = fragmentWriter.writeFrame(
            EndToEndStreamProtocol.encodePacket(packet.flags, packet.presentationTimeUs, packet.payload),
            packet.flags,
        )
        fragmentedVideoFrames.incrementAndGet()
        return packet.payload.size.toLong() + audioBytes
    }

    /** Experimental call video fragments: one queued call audio packet written between two fragments. */
    private fun writeInterleavedAudio(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        audio: OutboundMedia,
    ): Long {
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
        return audio.payload.size.toLong()
    }

    /** The best rate evidence for sizing airtime fragments (unknown = fixed size); see CallFragmentAirtime.pickRate. */
    private fun fragmentRate(): FragmentRate =
        if (fragmentAirtimeMs <= 0) FragmentRate.UNKNOWN
        else CallFragmentAirtime.pickRate(fragmentRates.peakKbps(), peerMeasuredKbps, fragmentProbeKbps)

    /** BENCHMARK call_fragment_airtime: one line per second with fragmented frames (both fragment arms). */
    private fun reportFragmentStatsIfDue(force: Boolean = false) {
        if (!peerAcceptsVideoFragments) return
        fragmentStats.takeWindow(FRAGMENT_STATS_INTERVAL_NS, force)?.let { window ->
            Log.i("BluetoothVideo", window.benchmarkLine(state?.callId?.toString(), fragmentAirtimeMs))
        }
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
            } else if (fragmentAirtimeMs > 0) {
                fragmentRates.onDelivered(peerDelivery.deliveredKbps())
            }
        }
    }

    /** Full node ID (hex) of the authenticated end-to-end peer, for the call screen's verification UI. */
    val endToEndPeerNodeId: String?
        get() = contentSession?.effectivePeerNodeId?.toString()

    /** BMH3 pseudonyms: the peer is still only a throwaway key (no identity to show, remember or verify yet). */
    val peerStillPseudonym: Boolean
        get() = contentSession?.peerIsPseudonym == true

    /**
     * Naming v2: the generated name of an incoming stranger ("Gold Fox"), made from the throwaway key's node ID on both
     * phones of the call; null once the peer is revealed, when generated names are off or the lists are not loaded.
     */
    /**
     * Naming v2 (review F20): the generated name of the throwaway this call began with, for the recents entry of a person who
     * is NOT a verified contact (null for a real-key peer, and for a stranger who then turned out to be a verified contact).
     * Unlike [strangerGeneratedName] it still answers after the peer revealed its real key.
     */
    fun recentStrangerName(): String? {
        val session = contentSession ?: return null
        if (session.reveal?.peerPseudonymous != true) return null
        val revealedVerified = runCatching {
            session.reveal?.revealed != null &&
                PeerIdentityGate.store?.state(session.effectivePeerNodeId, session.effectivePeerFingerprint) == VerificationState.VERIFIED
        }.getOrDefault(false)
        if (revealedVerified) return null
        val throwawayNode = session.peerNodeId.toByteArray()
        val name = StrangerNames.derive(StrangerNames.Input.throwawayNodeId(throwawayNode)) ?: return null
        return name.text.lowercase()
    }

    fun strangerGeneratedName(): String? {
        if (!peerStillPseudonym || !NamingFeatures.generatedNamesOn()) return null
        val throwawayNode = contentSession?.peerNodeId?.toByteArray() ?: return null
        return StrangerNames.derive(StrangerNames.Input.throwawayNodeId(throwawayNode))?.text
    }

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
                            bmh3 = Bmh3Wiring.initiator(appContext, peer.announcement, Bmh3Policy.Layer.LINK, rotatingIds),
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
        BluetoothTransportOption.BLE_L2CAP -> if (dualPlan.size > 1) {
            openCocThenGatt(peer, linkPhy)
        } else {
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

    private fun gattConnection(
        active: GattVideoConnection,
        address: String? = null,
        arrivedOn: BluetoothTransportOption? = null,
    ) = CallConnection(
        active.inputStream,
        active.outputStream,
        "${(arrivedOn ?: transport).shortLabel} (${active.phySummary()})",
        active::close,
        address = address,
        phySummary = active.phySummary,
        arrivedOn = arrivedOn,
    )

    /**
     * Experimental dual listening: tries the CoC phase, then the GATT call service of the same device inside
     * this attempt (no scan between). The plan is [dualPlan]; a CoC failure that GATT repaired is not a
     * failure of this phone's CoC, so the safety net is not told.
     */
    private fun openCocThenGatt(peer: BleL2capPeer, linkPhy: BlePhyPreference): CallConnection {
        val runner = DualAttemptRunner<CallConnection>(
            clockMs = SystemClock::elapsedRealtime,
            cancelled = { !running.get() },
            log = DualListeningRuntime::log,
        )
        val outcome = runner.run(dualPlan) { attempt, _ ->
            if (attempt == BluetoothTransportOption.BLE_L2CAP) openCocPhase(peer, linkPhy) else openGattAfterCoc(peer, linkPhy)
        }
        if (outcome.fallback) {
            DualListeningRuntime.log(
                DualListeningLog.fallback(BluetoothTransportOption.BLE_L2CAP, outcome.transport, outcome.firstError, outcome.firstPhaseMs),
            )
            onStatus(DualListeningLog.FALLBACK_NOTICE)
        }
        return outcome.link
    }

    /** The CoC phase of [openCocThenGatt]: the unchanged CoC client, cut at [DualSetupBudget.COC_PHASE_MS]. */
    private fun openCocPhase(peer: BleL2capPeer, linkPhy: BlePhyPreference): CallConnection {
        val startedMs = SystemClock.elapsedRealtime()
        val outerDeadlineMs = LeSetupBudget.remainingMs(startedMs)?.let { startedMs + it }
        val deadlineMs = DualSetupBudget.cocDeadline(startedMs, outerDeadlineMs)
        val pendingSocket = AtomicReference<BluetoothSocket?>()
        val expired = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        thread(name = "DualCocPhase", isDaemon = true) {
            val waitMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (!finished.await(waitMs, TimeUnit.MILLISECONDS)) {
                expired.set(true)
                DualListeningRuntime.log(
                    "dual_listening: event=coc_phase_deadline | phaseMs=${SystemClock.elapsedRealtime() - startedMs}",
                )
                runCatching { pendingSocket.get()?.close() }
                runCatching { bleControlConnection?.close() }
            }
        }
        try {
            when (DualListeningFeature.injection(BuildConfig.DEBUG, DualListeningFeature.enabled, DualListeningFeature.inject)) {
                DualListeningFeature.Injection.NONE -> Unit
                DualListeningFeature.Injection.COC_CONNECT_FAIL -> throw IOException("dual_listening: injected CoC connect failure")
                DualListeningFeature.Injection.COC_HANG -> {
                    Thread.sleep((deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 200L)
                    throw IOException("dual_listening: injected CoC hang until the phase deadline")
                }
            }
            val socket = LeSetupBudget.within(deadlineMs) {
                BleCocClient.connect(
                    appContext,
                    peer.device,
                    onStatus,
                    linkPhy,
                    "private-call-connect",
                    // A socket created after the deadline is closed at once, not left connecting.
                    onSocket = { socket -> if (expired.get()) runCatching { socket.close() } else pendingSocket.set(socket) },
                ) { bleControlConnection = it }
            }
            return socketConnection(socket)
        } catch (error: Exception) {
            runCatching { pendingSocket.get()?.close() }
            runCatching { bleControlConnection?.close() }
            bleControlConnection = null
            throw error
        } finally {
            finished.countDown()
        }
    }

    /** The GATT attempt of [openCocThenGatt]: from here on this link, hello and policies are GATT's. */
    private fun openGattAfterCoc(peer: BleL2capPeer, linkPhy: BlePhyPreference): CallConnection {
        linkTransportOverride = BluetoothTransportOption.BLE_GATT
        try {
            return gattConnection(
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
        } catch (error: Exception) {
            linkTransportOverride = null
            throw error
        }
    }

    /** Log label of a selected offer: release builds never log a rotating ID (design section 6). */
    private fun offerLogLabel(announcement: DiscoveryAnnouncement): String =
        if (announcement.rotating && !BuildConfig.DEBUG) "rotating" else AdaptivePhyMemory.prefixLabel(announcement.nodeIdPrefix)

    /**
     * Rotating IDs: the first hop record each way when both link hellos carry bit 3. A responder
     * opens only its pinned call-offer IDs (section 6); an initiator advertised nothing and sends
     * none. Null when not negotiated (switch off here or on the peer): no record is exchanged.
     */
    private fun exchangeLinkOpening(
        link: SecureBluetoothSession,
        initiator: Boolean,
        pinsAtAccept: List<OpeningEntry> = emptyList(),
    ): List<OpeningEntry>? {
        if (!rotatingIds) return null
        // A pseudonymous responder cleared link bit 3 and withholds its salt; a pseudonymous peer's block cleared it too,
        // so neither side sends a BDO1 (an initiator that presented a pseudonym to an ordinary responder still does).
        if (link.peerPseudonymous || (link.ownPseudonymous && !initiator)) return null
        val entries = if (initiator) emptyList() else pinsAtAccept
        return LinkOpeningExchange.exchange(link, linkCapabilities, entries)
    }

    /** DEBUG shows the ID next to the prefix; release builds never log both (PROTOCOL section 6). */
    private fun logRotatingOpening(
        role: String,
        result: DiscoverySelectionCheck.Result,
        selected: DiscoveryAnnouncement,
        peer: NodeId,
    ) {
        val detail = if (BuildConfig.DEBUG && result == DiscoverySelectionCheck.Result.PASS) {
            " | rdi=${selected.nodeIdPrefix.joinToString("") { "%02x".format(it) }} | prefix=${peer.shortLabel()}"
        } else {
            ""
        }
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=$role | result=$result$detail")
    }

    /**
     * Rotating IDs, Phase 2: starts the OFFER / PRESENCE_LEASE rules once the call is answered (an OFFER to a
     * call nobody answered would tell the peer that this phone holds its fingerprint; RD6). Eligible only
     * with a persistent vault, a peer that is a verified contact right now and the per-contact switch on.
     */
    private fun startPresenceSession() {
        val session = presenceSession ?: return
        val endpoint = contentSession ?: return
        runPresenceActions(session.start(), endpoint)
    }

    /** Off the UI thread: the verified-contact store and the vault may touch the Keystore. */
    private fun startPresenceSessionAsync() {
        if (presenceSession == null) return
        thread(name = "PresenceStart", isDaemon = true) { runCatching { startPresenceSession() } }
    }

    /**
     * The OFFER / PRESENCE_LEASE rules of this call, for the peer's identity as known now ([peer] is the real identity:
     * after a BMH3 pseudonym call's reveal it is the revealed contact, never the throwaway). Eligibility has one
     * definition ([PresenceEligibility]); a session that is not eligible logs why, once, so a missing lease exchange is
     * explained rather than silent. Off the UI thread: it may load the vault (Keystore).
     */
    private fun newPresenceSession(peer: NodeId, fingerprint: IdentityFingerprint, source: String, log: Boolean = true): PresenceSession {
        val reason = presenceIneligibleReason(peer, fingerprint)
        if (reason != null && log) logPresence("ineligible", "reason=$reason | source=$source")
        return PresenceSession(reason == null)
    }

    private fun presenceIneligibleReason(peer: NodeId, fingerprint: IdentityFingerprint): String? {
        val vault = RotatingDiscovery.presenceVault()
        return PresenceEligibility.reason(
            vaultPresent = vault != null,
            // A load that failed once (a Keystore not ready at cold start) is retried here, not judged from its first miss.
            persistent = vault?.ensureLoaded() == true,
            peerState = PeerIdentityGate.store?.state(peer, fingerprint),
            visibleToThem = vault?.visibleToThem(peer) ?: false,
            codeOnlyUnscanned = NamingFeatures.bvmid2On() && runCatching {
                CodeOnlyLeaseRule.blocked(
                    bvmid2On = true,
                    method = PeerIdentityGate.store?.all()?.firstOrNull { it.fingerprint == fingerprint }?.method,
                    scannedBothWays = ContactClaims.store(appContext).get(fingerprint)?.scannedBothWays == true,
                    holdsLease = vault?.contact(peer)?.presence != null,
                )
            }.getOrDefault(false),
        )
    }

    private fun runPresenceActions(actions: List<PresenceSession.Action>, endpoint: EndToEndVideoSession) {
        val vault = RotatingDiscovery.presenceVault()
        // The real identity: after a pseudonym call's reveal the revealed contact, otherwise the authenticated peer.
        val peer = endpoint.effectivePeerNodeId
        val peerFingerprint = endpoint.effectivePeerFingerprint
        actions.forEach { action ->
            when (action) {
                PresenceSession.Action.SendOffer -> {
                    val reason = presenceIneligibleReason(peer, peerFingerprint)
                    if (vault != null && reason == null) {
                        controls.offerMust(OutboundControl.Discovery(DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer)))
                        logPresence("offer_sent")
                    } else {
                        presenceSession?.offerWithheld()
                        logPresence("offer_withheld", "reason=${reason ?: "no_vault"}")
                    }
                }
                PresenceSession.Action.SendLease -> {
                    // RD1: eligibility is re-checked at send time (a Forget, a switch or a reset since the
                    // OFFER), and the delivery is recorded durably BEFORE the lease is queued: if it cannot be
                    // stored, nothing is sent, so a revocation can never miss a lease that left the phone.
                    // One locked step in the vault (check, durable record, key and generation from one state).
                    val epoch = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull()
                    val lease = if (vault != null && epoch != null) {
                        vault.deliverLease(peer, epoch) {
                            PeerIdentityGate.store?.state(peer, peerFingerprint) == VerificationState.VERIFIED
                        }
                    } else {
                        null
                    }
                    if (lease != null) {
                        controls.offerMust(OutboundControl.Discovery(DiscoveryExchange.encode(lease)))
                        logPresence("lease_sent", "generation=${lease.generation}")
                    } else {
                        logPresence("lease_withheld", "reason=${if (vault == null || epoch == null) "no_vault_or_epoch" else "not_deliverable"}")
                    }
                }
                is PresenceSession.Action.StoreLease -> {
                    val epoch = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull()
                    val stored = vault != null && epoch != null &&
                        vault.storeLease(peer, action.lease, epoch, System.currentTimeMillis())
                    if (stored) RotatingDiscovery.requestRecognitionRebuild()
                    logPresence("lease_stored", "changed=$stored | generation=${action.lease.generation}")
                }
                is PresenceSession.Action.Reject -> {
                    Log.w("BluetoothVideo", "SECURITY DISCOVERY RECORD REJECTED reason=${action.reason}")
                    logPresence("lease_rejected", "reason=${action.reason}")
                }
            }
        }
    }

    /** The caller's check of the callee's OPENING against the version 5 offer it selected (RV15, RD4 epoch window). */
    private fun verifyAwaitedOpening(wait: AwaitedOpening, content: EndToEndContentRecord) {
        val result = CallEndpointOpening.verifyFirstRecord(
            wait.selected,
            wait.endpointNode,
            wait.linkNode,
            direct = wait.selected.routeCost == 0,
            first = content,
        )
        logRotatingOpening("call_endpoint", result, wait.selected, wait.endpointNode)
        require(result == DiscoverySelectionCheck.Result.PASS) { "Call peer identity changed between discovery and connection" }
    }

    /**
     * BMH3 pseudonyms: the callee's reveal goes out ahead of its Accept, with the opening entries it withheld at the Invite
     * (PROTOCOL "When the reveal happens"). Nothing is sent to a peer that did not declare a pseudonym itself.
     */
    private fun queueRevealOnAccept() {
        val endpoint = contentSession ?: return
        val reveal = endpoint.reveal ?: return
        if (!reveal.ownPseudonymous || !reveal.peerPseudonymous || reveal.localRole != Bmh3Reveal.ROLE_CALLEE) return
        val entries = revealEntries
        controls.offerMust(OutboundControl.Reveal { reveal.sendOwn(identity, entries) })
        Bmh3Android.log("bmh3: event=reveal | role=callee | result=sent")
    }

    /** The peer's reveal: signature and session binding, then the verified-contact gate and (caller) the discovery binding. */
    private fun handleReveal(record: DiscoveryExchange.IdentityReveal) {
        val endpoint = contentSession ?: error("Unexpected identity reveal")
        val session = endpoint.reveal ?: error("Unexpected identity reveal")
        val role = if (session.localRole == Bmh3Reveal.ROLE_CALLER) "caller" else "callee"
        try {
            val verified = session.onPeerReveal(record)
            // Throws when a verified contact's prefix shows another key (refused before this phone reveals anything).
            val revealedFingerprint = PeerIdentityGate.admit(verified.nodeId, verified.spki)
            val revealedVerifiedContact = runCatching {
                PeerIdentityGate.store?.state(verified.nodeId, revealedFingerprint) == VerificationState.VERIFIED
            }.getOrDefault(false)
            // Callee, audience "Only people I verified": the name goes only now, and only to a verified contact.
            pendingRevealLabels?.let { container ->
                pendingRevealLabels = null
                if (revealedVerifiedContact) controls.offerMust(OutboundControl.Labels(container))
            }
            awaitedReveal?.let { wait ->
                val result = CallEndpointOpening.check(
                    wait.selected, verified.nodeId, endpoint.peerNodeId, direct = false, opening = verified.entries,
                )
                logRotatingOpening("call_reveal", result, wait.selected, verified.nodeId)
                require(result == DiscoverySelectionCheck.Result.PASS) { "Call peer identity changed between discovery and connection" }
                awaitedReveal = null
                val held = heldLabels
                val nameNow = held.name == LabelSendMoment.AFTER_REVEAL ||
                    (held.name == LabelSendMoment.AFTER_REVEAL_IF_VERIFIED && revealedVerifiedContact)
                val subjectNow = held.subject == LabelSendMoment.AFTER_REVEAL
                heldLabels = CallerLabelPlan(
                    if (held.name == LabelSendMoment.AFTER_REVEAL || held.name == LabelSendMoment.AFTER_REVEAL_IF_VERIFIED) {
                        LabelSendMoment.NEVER
                    } else {
                        held.name
                    },
                    if (subjectNow) LabelSendMoment.NEVER else held.subject,
                )
                if (nameNow || subjectNow) queueCallerLabels(includeName = nameNow, includeSubject = subjectNow)
            }
            Bmh3Android.log("bmh3: event=reveal | role=$role | result=verified")
        } catch (error: Exception) {
            Bmh3Android.log("bmh3: event=reveal | role=$role | result=${error.javaClass.simpleName}")
            throw error
        }
        // The call is now with a person: presence leases may be offered to a verified contact, and the call screen,
        // the history and the verification flow see the real identity.
        presenceSession = newPresenceSession(endpoint.effectivePeerNodeId, endpoint.effectivePeerFingerprint, "reveal")
        if (session.localRole == Bmh3Reveal.ROLE_CALLEE) {
            startPresenceSessionAsync()
        } else if (session.callerMayReveal()) {
            // The caller reveals only now: the callee proved it owns the offer this phone selected.
            controls.offerMust(OutboundControl.Reveal { session.sendOwn(identity, emptyList()) })
            Bmh3Android.log("bmh3: event=reveal | role=caller | result=sent")
        }
        onStatus("The other phone's identity is confirmed: ${endpoint.effectivePeerNodeId.shortLabel()}")
    }

    private fun handleDiscoveryContent(payload: ByteArray) {
        if (payload.size > DiscoveryExchange.MAGIC.size && payload[DiscoveryExchange.MAGIC.size].toInt() == DiscoveryExchange.KIND_IDENTITY_REVEAL) {
            // A reveal decides whether the call goes on, so unlike the other records a malformed one ends the call.
            val record = DiscoveryExchange.decode(payload) as? DiscoveryExchange.IdentityReveal ?: error("Unexpected identity reveal")
            handleReveal(record)
            return
        }
        val session = presenceSession ?: return
        val endpoint = contentSession ?: return
        val record = try {
            DiscoveryExchange.decode(payload)
        } catch (error: IllegalArgumentException) {
            // Never fails the call (PROTOCOL Phase 2): an undecodable record is dropped and logged.
            Log.w("BluetoothVideo", "SECURITY DISCOVERY RECORD REJECTED reason=undecodable")
            return
        }
        val verifiedNow = PeerIdentityGate.store?.state(endpoint.peerNodeId, endpoint.peerFingerprint) ==
            VerificationState.VERIFIED
        val actions = session.onRecord(record, verifiedNow)
        if (actions.isEmpty()) return
        // The vault and the verified-contact store touch the Keystore and the disk: not on the reader thread.
        thread(name = "PresenceAction", isDaemon = true) { runCatching { runPresenceActions(actions, endpoint) } }
    }

    private fun logPresence(event: String, detail: String = "") {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_presence: event=$event" +
                if (detail.isNotEmpty()) " | $detail" else "",
        )
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
        // A relayed version 5 offer is verified end to end by the BDX1 OPENING (the relay cannot open the
        // callee's ID and its hello carries no bit 3).
        if (selected.rotating && selected.routeCost > 0 && !messageSync) return
        val result = DiscoverySelectionCheck.check(
            selected,
            RotatingDiscoveryIds.roleFor(selected),
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
                notifyPhase(requireNotNull(state))
            }
        }
        finish(message)
    }

    private fun finish(message: String) {
        qrGate?.fail()
        qrGate = null
        qrScope = null
        callRunning.set(false)
        contentSession?.peerNodeId?.shortLabel()?.let(SessionPeerLabels::clear)
        bufferedLabels = null
        pendingAcceptLabels = null
        pendingRevealLabels = null
        callLabels = null
        reportDebugTx(final = true)
        reportFragmentStatsIfDue(force = true)
        pendingAudioPackets.clear()
        media.clear()
        controls.clear()
        mediaReceiver?.close()
        mediaReceiver = null
        if (transport == BluetoothTransportOption.BLE_GATT) {
            gattServer?.close()
            gattServer = null
        }
        dualSecondary?.close()
        dualSecondary = null
        connection?.close()
        connection = null
        secureSession?.close()
        secureSession = null
        contentSession?.close()
        contentSession = null
        writerThread?.interrupt()
        readerThread?.interrupt()
        // A BVMID2 verification scan is not a call: its end opens no screen (MainActivity treats it as a quiet end).
        if (qrScanPayload != null) endedQuietly = true
        if (endedCallbackSent.compareAndSet(false, true)) onEnded(localFailureMessage ?: message)
    }

    // ----- Experimental relayed calls (DECISIONS Design C) -----

    /** The user's own activity ends a relay in progress at once (PLAN C5). True when one was running. */
    fun preemptRelay(): Boolean {
        val relay = relayDuty ?: return false
        relay.close()
        return true
    }

    private fun runRelaySetup(
        link: SecureBluetoothSession,
        activeConnection: CallConnection,
        route: RelayRoute,
        helperOpening: List<OpeningEntry>?,
    ) {
        val spentMs = (System.nanoTime() - relaySetupStartedNs) / 1_000_000L
        val remaining = (RelayedCalls.SETUP_DEADLINE_MS - spentMs).coerceIn(0L, RelayedCalls.SETUP_DEADLINE_MS.toLong()).toInt()
        try {
            val relays = RelaySetupExchange.requestRoute(link, route, remaining, helperOpening) { activeConnection.close() }
            val required = RelayedCallsFeature.requireRelays
            if (required > 0 && relays != required) {
                throw RelaySetupFailedException(null, "The route used $relays relays, but exactly $required were required")
            }
            relayHops = relays
            activeSettings = routeSettings(relayHops)
            relayRouteUnverified = route
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_setup: role=caller | result=ready | budget=${route.relays} | relays=$relays",
            )
        } catch (error: RelaySetupFailedException) {
            relayHelper?.routeOutcome(route, false, error.reason)
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_setup: role=caller | result=failed | budget=${route.relays} | reason=${error.reason?.label ?: "invalid"}",
            )
            throw error
        }
    }

    /**
     * A link accepted by a call endpoint that has not finished both handshakes within
     * [DualSetupBudget.INCOMING_HANDSHAKE_MS] is closed, so a silent stranger on any door cannot hold the endpoint
     * (on a dual endpoint a GATT central enabling notifications takes the CoC listener and the advertisement with it).
     * Every CoC and GATT endpoint since Q29 (2026-10-14); see [CallSetupDeadlines.incomingDeadlineApplies].
     */
    private fun startIncomingDeadline(activeConnection: CallConnection, dualEndpoint: Boolean) {
        thread(name = "DualIncomingDeadline", isDaemon = true) {
            try {
                Thread.sleep(DualSetupBudget.INCOMING_HANDSHAKE_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (running.get() && callRunning.get() && connection === activeConnection && contentSession == null) {
                if (dualEndpoint) DualListeningRuntime.log("dual_listening: event=incoming_deadline | closed=true")
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + CallSetupDeadlines.incomingDeadlineLine(dualEndpoint),
                )
                runCatching { activeConnection.close() }
            }
        }
    }

    /** A helper closes an accepted link that has not finished the handshake and delivered its first record. */
    private fun startPreSetupWatchdog(activeConnection: CallConnection) {
        preSetupWatchdog = thread(name = "PrivateCallPreSetupWatchdog", isDaemon = true) {
            try {
                Thread.sleep(RelayedCalls.PRE_SETUP_WAIT_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=pre_setup_timeout",
            )
            runCatching { activeConnection.close() }
        }
    }

    /** The caller as known before the call rings (and after it, once a stranger revealed its real key). */
    fun incomingCaller(): IncomingCaller? {
        val session = contentSession ?: return null
        return if (session.peerIsPseudonym) IncomingCaller(true, null) else IncomingCaller(false, session.effectivePeerFingerprint)
    }

    /**
     * Call blocking: when the gate refuses this caller, answers with the ordinary Ringing, declines through the
     * normal decline after a short random delay and returns Unit; null means the call rings as usual.
     */
    private fun declineSilentlyIfBlocked(callId: UUID): Unit? {
        val caller = incomingCaller() ?: return null
        val decision = callGate.check(caller)
        if (decision == CallGateDecision.RING) return null
        endedQuietly = true
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=declined | reason=" +
                (if (decision == CallGateDecision.DECLINE_BLOCKED_PERSON) "blocked_person" else "unknown_caller") +
                " | pseudonymous=${caller.pseudonymous}",
        )
        if (decision == CallGateDecision.DECLINE_UNKNOWN) {
            runCatching { RecentCalls.recordBlocked(appContext, System.currentTimeMillis()) }
        }
        enqueueSignal(PrivateCallSignal.RINGING)
        // From the caller's side this was a ringing call, not a probe (RV8 tarpit counts only silent handshakes).
        if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
        val delayMs = CallBlocking.declineDelayMs()
        thread(name = "BlockedCallDecline", isDaemon = true) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                val current = state ?: return@thread
                if (current.callId != callId || current.phase != PrivateCallPhase.INCOMING_RINGING) return@thread
                runCatching { decline(PrivateCallReason.REJECTED) }
            }
        }
        return Unit
    }

    /** A relayed incoming call that rings unanswered this long is declined, so ringing cannot hold helpers. */
    private fun scheduleRelayedRingingCap(callId: UUID) {
        thread(name = "RelayedRingingCap", isDaemon = true) {
            try {
                Thread.sleep(RelayedCalls.RELAYED_RINGING_CAP_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                val current = state ?: return@thread
                if (current.callId != callId || current.phase != PrivateCallPhase.INCOMING_RINGING) return@thread
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_call: ringing_cap_declined",
                )
                runCatching { decline(PrivateCallReason.REJECTED) }
            }
        }
    }

    /** Handles the SETUP that arrived as the first record: refuse it, or relay the call for its caller. */
    private fun serveRelaySetup(link: SecureBluetoothSession, activeConnection: CallConnection, record: SecureRecord) {
        val host = relayHelper
        val setup = runCatching {
            RelaySetupCodec.decode(record.payload, acceptRdi = rotatingIds && RelayedCallsFeature.rotatingCompat)
        }.getOrNull() as? RelaySetupMessage.Setup
        // Experimental dual listening: a relay duty runs on the endpoint's own transport only (PROTOCOL).
        if (host == null || setup == null || !RelayedCalls.linkUnderstandsSetup(link.peerCapabilities) ||
            linkTransportOverride != null
        ) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=invalid",
            )
            runCatching { activeConnection.close() }
            endRelayDutyNow("Ignored an invalid relay request")
            return
        }
        when (val admission = host.admit(link.peerNodeId, setup.target, setup.budget, transport, setup.rotating)) {
            is RelayAdmission.Refuse -> {
                runCatching { RelaySetupExchange.writeMessage(link, RelaySetupMessage.Fail(setup.setupId, admission.reason)) }
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "relayed_setup: role=helper | result=refused | budget=${setup.budget} | reason=${admission.reason.label}",
                )
                // The reply needs a moment to leave before the link closes.
                thread(name = "RelaySetupRefuse", isDaemon = true) {
                    try {
                        Thread.sleep(REFUSE_DRAIN_MS)
                    } catch (_: InterruptedException) {
                        // closing now is fine
                    }
                    endRelayDutyNow("Refused a relay request (${admission.reason.label})")
                }
            }
            is RelayAdmission.Accept -> {
                if (!running.get()) {
                    runCatching { activeConnection.close() }
                    return
                }
                val relay = BluetoothPrivateCallRelay(
                    onStatus = { message -> Log.i("BluetoothVideo", message) },
                    onStopped = { relayDutyStopped() },
                )
                relayDuty = relay
                host.relayStarted(setup.budget)
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "relayed_setup: role=helper | result=accepted | budget=${setup.budget} | next_budget=${admission.nextBudget}",
                )
                relay.startOnDemand(
                    appContext,
                    adapter,
                    admission.nextHop,
                    transport,
                    BluetoothPrivateCallRelay.OnDemandParent(link, activeConnection::close, activeConnection.phySummary),
                    setup.target,
                    admission.nextBudget,
                    admission.hopPrefix,
                    setup.remainingMs,
                    rotating = setup.rotating,
                    onReady = { relays ->
                        RelaySetupExchange.writeMessage(link, RelaySetupMessage.Ready(setup.setupId, relays))
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=ready | relays=$relays",
                        )
                    },
                    onFailed = { reason ->
                        runCatching { RelaySetupExchange.writeMessage(link, RelaySetupMessage.Fail(setup.setupId, reason)) }
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=failed | reason=${reason.label}",
                        )
                    },
                )
            }
        }
    }

    private fun relayDutyStopped() {
        if (relayDuty == null) return
        relayDuty = null
        relayHelper?.relayEnded()
        endRelayDutyNow("Stopped helping a call nearby")
    }

    /** A relay duty (or a refused request) used this endpoint: the activity restarts availability quietly. */
    private fun endRelayDutyNow(message: String) {
        endedFromRelayDuty = true
        finish(message)
    }

    private fun stopAvailability(forActiveConnection: Boolean) {
        // A Visible-by-name window shows nothing while this phone is in a connection or not listening.
        closeWindowAdvertiser()
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
        // Experimental dual listening: the second listener goes with the availability, except while it
        // carries the accepted link (a GATT link on a CoC endpoint; finish() closes it then).
        if (!forActiveConnection || linkTransportOverride == null) {
            dualSecondary?.close()
            dualSecondary = null
        }
        listenerThread?.interrupt()
        listenerThread = null
    }

    override fun close() {
        debugStageTrace?.close()
        debugStageTrace = null
        if (!running.getAndSet(false)) return
        BluetoothEpoch.unregister(listenerEpochName)
        // Experimental relayed calls: closing the endpoint (screen change, switch off) ends a relay duty too.
        relayDuty?.close()
        preSetupWatchdog?.interrupt()
        if (rotatingAdvert) RotatingDiscovery.removeBoundaryListener(boundaryListener)
        NameWindowRuntime.removeListener(windowListener)
        pendingCallConnection?.close()
        pendingCallConnection = null
        pendingClassicSocket?.let { runCatching { it.close() } }
        pendingClassicSocket = null
        callRunning.set(false)
        reportDebugTx(final = true)
        reportFragmentStatsIfDue(force = true)
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
        const val REFUSE_DRAIN_MS = 300L
        const val CONSTRAINED_COC_AUDIO_BATCH_SIZE = 20
        const val WRITER_POLL_MS = 20L
        const val CALL_PACING_SLICE_NS = 5_000_000L
        /** Heartbeats carry a link report; the peer's rate control acts on its trend. */
        const val REPORT_INTERVAL_NS = 1_000_000_000L
        const val LINK_SAMPLE_NS = 1_000_000_000L
        const val HEARTBEAT_TIMEOUT_NS = 8_000_000_000L
        const val RECOVERY_REQUEST_INTERVAL_NS = 750_000_000L
        const val DEBUG_TX_REPORT_INTERVAL_NS = 5_000_000_000L
        const val FRAGMENT_STATS_INTERVAL_NS = 1_000_000_000L
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
    bondFree: Boolean = ClassicFeatures.current.bondFree,
): String = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
    if (bondFree) {
        "Classic call could not reach this phone ($detail). Both phones need Connect without pairing turned " +
            "on, and a phone this one has not met must be visible. Or switch both phones to BLE GATT."
    } else {
        "Classic call could not reach this phone ($detail). Pair the phones once, turn on Connect without " +
            "pairing (Settings > Features) on both phones, or switch both phones to BLE GATT."
    }
} else {
    "Call could not connect: $detail"
}

/** An unresolved Classic peer explains itself; anything else keeps the transport's message. */
internal fun privateCallConnectionFailureMessage(
    transport: BluetoothTransportOption,
    error: Throwable,
    bondFree: Boolean = ClassicFeatures.current.bondFree,
): String = (error as? ClassicPeerUnresolvedException)?.message
    ?: privateCallConnectionFailureMessage(transport, error.message ?: error.javaClass.simpleName, bondFree)

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

/** The answerer kept the link open and never finished the hop handshake ([CallSetupDeadlines.HOP_AUTH_MS]). */
internal class CallHandshakeTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The answerer closed the link before the hop handshake finished (for example a refused old-style handshake). */
internal class CallHandshakeClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

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

    /** The hop-handshake deadline of an unguarded attempt passed: true only when it was still pending. */
    fun claimTimeout(): Boolean = state.compareAndSet(PENDING, TIMED_OUT)

    val stalled: Boolean get() = state.get() == STALLED

    val timedOut: Boolean get() = state.get() == TIMED_OUT

    /** Neither authenticated nor claimed by a guard nor ended: the hop handshake is still open (or just failed). */
    val pending: Boolean get() = state.get() == PENDING

    private companion object {
        const val PENDING = 0
        const val AUTHENTICATED = 1
        const val STALLED = 2
        const val ENDED = 3
        const val TIMED_OUT = 4
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
