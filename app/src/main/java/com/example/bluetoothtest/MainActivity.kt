package com.example.bluetoothtest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Surface
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.viewfinder.core.ScaleType
import androidx.camera.viewfinder.view.ViewfinderView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private enum class SessionRole(val label: String) {
    SEND("Send camera"),
    RECEIVE("Receive video"),
    RELAY("Relay video"),
}

private enum class AppScreen {
    HOME,
    CAMERA,
    RECEIVER_BROWSER,
    PLAYBACK,
    CALL_BROWSER,
    CALL,
    SETTINGS,
}

internal fun classicTargetMatches(
    deviceName: String?,
    deviceAddress: String,
    requestedTarget: String,
): Boolean = deviceName?.trim().equals(requestedTarget.trim(), ignoreCase = true) ||
    deviceAddress.equals(requestedTarget, ignoreCase = true)

internal fun debugTransportOverride(requested: String?): BluetoothTransportOption? = when (requested) {
    "classic" -> BluetoothTransportOption.CLASSIC_RFCOMM
    "ble" -> BluetoothTransportOption.BLE_L2CAP
    "gatt" -> BluetoothTransportOption.BLE_GATT
    else -> null
}

internal fun shouldRevealPrivateCallRemotePreview(
    isCallScreen: Boolean,
    remoteVideoStarted: Boolean,
): Boolean = isCallScreen && remoteVideoStarted

internal const val PRIVATE_CALL_PEER_STALE_MS = 12_000L

/**
 * Suffix for every visible-continuity line: when the first frame reached this surface, as the
 * startup time and its age at this report. A report that survives device log loss then still proves
 * and dates the first surface; nothing is printed before a frame reached the surface.
 */
internal fun continuityFirstSurfaceSuffix(snapshot: RenderedFrameContinuitySnapshot): String {
    val startupMs = snapshot.startupMs ?: return ""
    val agoMs = snapshot.firstFrameAgoMs ?: return ""
    if (snapshot.renderedFrames <= 0L) return ""
    return " | first surface %.1f ms after start, %.1f ms ago".format(Locale.US, startupMs, agoMs)
}

/**
 * DEBUG: true when this activity was created only to deliver the runner's `shutdown` control (no
 * saved state), i.e. the app had already exited. Such a start must not bring the app up.
 */
internal fun isColdStartShutdown(debug: Boolean, restoring: Boolean, control: String?): Boolean =
    debug && !restoring && control == "shutdown"

internal fun privateCallPeerIsFresh(lastSeenMs: Long, nowMs: Long): Boolean =
    nowMs - lastSeenMs <= PRIVATE_CALL_PEER_STALE_MS

internal fun privateCallPeerWasRefreshed(observedAtMs: Long, selectedAtMs: Long): Boolean =
    observedAtMs >= selectedAtMs

internal fun streamRouteSuppressionDeadline(existingDeadlineMs: Long?, requestedDeadlineMs: Long): Long =
    maxOf(existingDeadlineMs ?: 0L, requestedDeadlineMs)

private val UNEXPECTED_LOSS_RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L, 8_000L)

/**
 * Backoff before retrying the same authenticated route after an active stream was lost.
 * A direct viewer's only route is its source, so a fixed 8 s cooldown made recovery
 * exceed the 5 s target by construction; repeated failures still back off to 8 s.
 */
internal fun unexpectedLossRetryDelayMs(consecutiveFailures: Int): Long =
    UNEXPECTED_LOSS_RETRY_DELAYS_MS[(consecutiveFailures - 1).coerceIn(0, UNEXPECTED_LOSS_RETRY_DELAYS_MS.lastIndex)]

internal fun recoveryParentAfterRouteFailure(existing: ByteArray?, wasActive: Boolean, failedParent: ByteArray): ByteArray? =
    existing ?: failedParent.copyOf().takeIf { wasActive }

internal fun streamCapacityGenerationAdvanced(previous: Int, current: Int): Boolean =
    ((current - previous) and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK) in 1..63

internal fun streamCapacityWithdrawalNotice(routeCost: Int?): String = when (routeCost) {
    0 -> "No free connection slot on this source. Finding another route..."
    null -> "The parent stopped forwarding. Finding another route..."
    else -> "The relay connection became unstable. Finding another route..."
}

internal data class StreamReconnectTarget(
    val sourcePrefix: String,
    val streamToken: Int,
    val transport: BluetoothTransportOption,
) {
    fun matches(announcement: DiscoveryAnnouncement): Boolean =
        sourcePrefix == announcement.nodeIdPrefix.toHexKey() &&
            streamToken == announcement.streamToken &&
            transport == announcement.transportOption()

    companion object {
        fun from(peer: BleL2capPeer): StreamReconnectTarget = StreamReconnectTarget(
            peer.announcement.nodeIdPrefix.toHexKey(),
            peer.announcement.streamToken,
            peer.announcement.transportOption(),
        )
    }
}

private fun ByteArray.toHexKey(): String = joinToString("") { "%02x".format(it) }

class MainActivity : AppCompatActivity() {
    private lateinit var homeScreen: View
    private lateinit var cameraScreen: View
    private lateinit var receiverBrowserScreen: View
    private lateinit var callBrowserScreen: View
    private lateinit var callScreen: View
    private lateinit var preview: TextureView
    private lateinit var cameraViewfinder: ViewfinderView
    private lateinit var callRemotePreview: TextureView
    private lateinit var callLocalViewfinder: ViewfinderView
    private lateinit var callBrowserStatus: TextView
    private lateinit var callPeerList: ListView
    private lateinit var callStatusText: TextView
    private lateinit var callProfileText: TextView
    private lateinit var incomingCallControls: View
    private lateinit var activeCallControls: View
    private lateinit var cancelCallButton: View
    private lateinit var callMuteButton: Button
    private lateinit var callCameraButton: Button
    private lateinit var callSwitchCameraButton: Button
    private lateinit var callSpeakerButton: Button
    private lateinit var callRemoteCameraOff: View
    private lateinit var callScreenController: CallScreenController
    private lateinit var settingsScreen: View
    private lateinit var homeConfigurationSummary: TextView
    private lateinit var privateCallProfileSummary: TextView
    private lateinit var cameraSwitchButton: View
    private lateinit var doneSettingsButton: View
    private lateinit var transportSpinner: Spinner
    private lateinit var destinationGroup: View
    private lateinit var destinationLabel: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var rateControlSpinner: Spinner
    private lateinit var advancedSettingsSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var advancedSettingsDescriptionText: TextView
    private lateinit var rateControlLabel: TextView
    private lateinit var rateControlDescriptionText: TextView
    private lateinit var videoScaleModeSpinner: Spinner
    private lateinit var videoScaleModeDescriptionText: TextView
    private lateinit var receiverPlaybackDelaySlider: Slider
    private lateinit var playbackDelayValueText: TextView
    private lateinit var playbackStabilityText: TextView
    private lateinit var refreshButton: Button
    private lateinit var capabilityText: TextView
    private lateinit var decoderSupportText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateText: TextView
    private lateinit var bitrateSeekBar: SeekBar
    private lateinit var streamSummaryText: TextView
    private lateinit var streamToggleButton: ImageButton
    private lateinit var broadcastPauseButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var connectionStatusDot: View
    private lateinit var deviceHealthOverlay: View
    private lateinit var thermalStatusBadge: TextView
    private lateinit var batteryTemperatureBadge: TextView
    private lateinit var thermalReductionSwitch: MaterialSwitch
    private lateinit var deviceHealthOverlaySwitch: MaterialSwitch
    private lateinit var forwardReceivedStreamsSwitch: MaterialSwitch
    private lateinit var experimentalTemporalLayersSwitch: MaterialSwitch
    private lateinit var relayChildMediaAgeSwitch: MaterialSwitch
    private lateinit var relayChildMediaAgeDescriptionText: TextView
    private lateinit var experimentalTemporalLayersDescriptionText: TextView
    private lateinit var experimentalVoiceCallsSwitch: MaterialSwitch
    private lateinit var experimentalVoiceCallsDescriptionText: TextView
    private lateinit var experimentalPushToTalkSwitch: MaterialSwitch
    private lateinit var experimentalPttModeSwitch: MaterialSwitch
    private lateinit var experimentalPushToTalkDescriptionText: TextView
    private lateinit var experimentalStoreForwardSwitch: MaterialSwitch
    private lateinit var experimentalPeerLabelsSwitch: MaterialSwitch
    private lateinit var experimentalPeerLabelsDescriptionText: TextView
    private lateinit var experimentalStoreForwardDescriptionText: TextView
    private lateinit var experimentalCocAutoGattSwitch: MaterialSwitch
    private lateinit var experimentalCocAutoGattDescriptionText: TextView
    private lateinit var experimentalClassicBondFreeSwitch: MaterialSwitch
    private lateinit var experimentalClassicBondFreeDescriptionText: TextView
    private lateinit var experimentalCompactDiscoverySwitch: MaterialSwitch
    private lateinit var experimentalRotatingIdsSwitch: MaterialSwitch
    private lateinit var experimentalRotatingIdsDescriptionText: TextView
    private lateinit var rotatingIdsVisibleButton: com.google.android.material.button.MaterialButton
    private lateinit var experimentalCompactDiscoveryDescriptionText: TextView
    private lateinit var experimentalOfflineAppShareSwitch: MaterialSwitch
    private lateinit var experimentalOfflineAppShareDescriptionText: TextView
    /** Bond-free Classic asks Android for visibility at most once per process (system dialog). */
    private var classicVisibilityRequested = false
    private lateinit var videoBackButton: View
    private lateinit var receiverWaitingOverlay: View
    private lateinit var receiverWaitingProgress: View
    private lateinit var receiverWaitingText: TextView
    private lateinit var receiverBrowserTitle: TextView
    private lateinit var receiverBrowserStatus: TextView
    private lateinit var streamList: ListView
    // Redesigned UI (2026-10-10): views and state that only drive presentation.
    private lateinit var homeReadinessIcon: ImageView
    private lateinit var homeReadinessTitle: TextView
    private lateinit var homeReadinessText: TextView
    private lateinit var homeReadinessAction: com.google.android.material.button.MaterialButton
    private lateinit var homeCallSubtitle: TextView
    private lateinit var homeIdentityText: TextView
    private lateinit var callSecurityChip: TextView
    private lateinit var callLockButton: ImageButton
    private lateinit var callPartyPanel: View
    private lateinit var callPartyName: TextView
    private lateinit var callPartyState: TextView
    private lateinit var callMuteLabel: TextView
    private lateinit var callCameraLabel: TextView
    private lateinit var callSpeakerLabel: TextView
    private lateinit var broadcastStatusText: TextView
    private lateinit var streamSecurityText: TextView
    private lateinit var watchRelaySwitch: MaterialSwitch
    private lateinit var advancedSection: View
    private lateinit var advancedToggleIcon: View
    private lateinit var experimentsHiddenHint: View
    private lateinit var callChromeAutoHide: CallChromeAutoHide
    private val panicTapDetector = MultiTapDetector()
    private var homePermissionsAsked = false
    private var bluetoothStateReceiverRegistered = false
    private var lastConnectedCount = 0
    /** Route cost of the advertisement this phone dialled (relays on the way), for the call chip. */
    private var privateCallDialledRelays: Int? = null
    private var privateCallPartyName: String = ""
    /** The latest technical link statuses of this call (PHY, link, failures) for Link health. */
    private val privateCallLinkDetails = ArrayDeque<String>()

    private fun notePrivateCallLinkDetail(message: String) {
        if (privateCallLinkDetails.lastOrNull() == message) return
        privateCallLinkDetails.addLast(message)
        while (privateCallLinkDetails.size > LINK_DETAIL_LINES) privateCallLinkDetails.removeFirst()
    }

    /** Quick wipe waiting out its Undo window (null when none). */
    private var pendingPanicWipe: Runnable? = null
    /** Verification of the watched stream's authenticated source, looked up off the main thread. */
    private var streamSourceVerification: Pair<String, ContactVerification>? = null
    private var streamSourceLookup: String? = null
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            updateHomeReadiness()
            refreshCallReachability()
        }
    }
    private val homePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        homePermissionsAsked = true
        updateHomeReadiness()
        refreshCallReachability()
    }
    private val homeBluetoothEnableRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateHomeReadiness()
        refreshCallReachability()
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java).adapter
    }
    private val powerManager: PowerManager by lazy {
        getSystemService(PowerManager::class.java)
    }
    private val deviceVideoCapabilities by lazy { DeviceVideoCapabilities(this) }
    private val appPreferences by lazy { getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE) }
    private var pairedDevices = emptyList<BluetoothDevice>()
    private var blePeers = emptyList<BleL2capPeer>()
    private var bleDiscovery: BleL2capDiscovery? = null
    private var codecs = emptyList<VideoCodec>()
    private var decoders = emptyList<VideoDecoderSupport>()
    private var deviceModes = emptyList<DeviceVideoMode>()
    private var updatingModePickers = false
    private var updatingRateControlPicker = false
    private var rateControlOptions = listOf(EncoderRateControl.AUTOMATIC)
    private var settings = VideoSettings.DEFAULT
    private var encoderRateControl = EncoderRateControl.AUTOMATIC
    /**
     * Auto (false): the app picks every technical value from measurements (Automatic rate control,
     * adaptive playout, speed governor, learned capacity). Advanced (true): the user's exact manual
     * choices apply and nothing adapts; no Auto option is mixed into the manual pickers.
     */
    private var advancedSettings = false
    private var videoScaleMode = VideoScaleMode.FILL
    private var receiverPlaybackDelay = PlaybackDelayProfile.AUTO
    private var showDeviceHealthOverlay = true
    private var forwardReceivedStreams = false
    private var sender: BluetoothVideoSender? = null
    private var broadcaster: BluetoothVideoBroadcaster? = null
    private var receiver: AndroidVideoReceiver? = null
    private var relay: BluetoothVideoRelay? = null
    private var privateCallRelay: BluetoothPrivateCallRelay? = null
    private var wifiAdviceAnsweredAtMs = 0L
    @Volatile private var linkRateLimitBps: Int? = null
    /** Automatic rate control for this phone's outgoing call video (hop-local, like streams). */
    @Volatile private var privateCallRateController: LinkRateController? = null
    @Volatile private var privateCallLinkRateLimitBps: Int? = null
    @Volatile private var privateCallLinkFps: Int? = null
    private var debugBenchmarkCoordinator: DebugBenchmarkCoordinator? = null
    private val bluetoothRestartProbe: () -> String? = { activeBluetoothSession() }
    private var debugSessionTelemetry: DebugSessionTelemetry? = null
    private var cameraEncoder: CameraVideoEncoder? = null
    private var microphoneEncoder: MicrophoneOpusEncoder? = null
    private var privateCall: AndroidPrivateVideoCall? = null
    private var privateCallPhase: PrivateCallPhase? = null
    private var privateCallAvailabilityStarting = false
    /** The endpoint finished startAvailability and is advertising + listening (CallReachability). */
    private var privateCallAvailable = false
    /** The current call arrived through Home reachability: go back to Home when it ends. */
    private var privateCallReturnHome = false
    /** Between onStart and onStop: a foreground service may be started now (Android 12+). */
    private var activityStarted = false
    private var lastReachabilityLog: String? = null
    private var privateCallDiscovery: BleL2capDiscovery? = null
    private var privateCallPeers = emptyList<BleL2capPeer>()
    private val privateCallPeersLastSeen = mutableMapOf<String, Pair<BleL2capPeer, Long>>()
    private var pendingPrivateCallPeerKey: String? = null
    /** Experimental automatic store-and-forward sync (StoreForwardAutoSyncFeature, default off). */
    private var autoSyncScheduler: StoreForwardAutoSyncScheduler? = null
    private val autoSyncEvaluating = java.util.concurrent.atomic.AtomicBoolean(false)
    private val autoSyncLastEvalMs = mutableMapOf<String, Long>()
    private val autoSyncLastLog = mutableMapOf<String, String>()
    /** The peer an automatic exchange is refreshing before it connects; a user tap replaces it. */
    private var autoSyncPendingKey: String? = null
    /** Version of the selected offer: a refresh must match it (rotating IDs, RV15). */
    private var pendingPrivateCallPeerVersion = DiscoveryAnnouncement.FIXED_PREFIX_VERSION
    private var pendingPrivateCallSelectedAtMs = 0L
    @Volatile private var privateCallCameraEncoder: CameraVideoEncoder? = null
    private val privateCallCameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PrivateCallCameraLifecycle")
    }
    private var privateCallMicrophoneEncoder: MicrophoneOpusEncoder? = null
    private var privateCallAudioSession: PrivateCallAudioSession? = null
    /** DEBUG benchmark self-view of the raw encoder input; null with a camera or when disabled. */
    private var privateCallRawSelfView: CallRawSelfView? = null
    private var privateCallSettings: VideoSettings? = null
    /** This call's resolution ladder (CallResolutionLadderFeature); null while off or not applicable. */
    private var privateCallResolutionLadder: CallResolutionLadder? = null
    private var privateCallCodec: VideoCodec? = null
    private var privateCallActiveAudioBitRate: Int? = null
    private var privateCallMuted = false
    private var privateCallCameraEnabled = true
    /** Experimental voice calls and push-to-talk (IntercomFeatures, default off). */
    private lateinit var intercomCallUi: IntercomCallUi
    private var pendingPrivateCallType = CallType.VIDEO
    /** Experimental store-and-forward: the pending peer selection is a message exchange, not a call. */
    private var pendingPrivateCallMessageSync = false
    /** Incoming ringtone/vibration and the experimental ringback (CallRinger). */
    private val callRinger by lazy { CallRinger(this) }
    /** The user's mute plus the audio-focus hold (CallFocusHold); replaced at each call's media start. */
    private var privateCallFocusHold = CallFocusHold()
    /** Experimental camera pause at the ear (test_call_proximity_camera_pause); null while off. */
    private var privateCallProximityPause: CallProximityCameraPause? = null
    /** True while the camera is stopped only because the phone is at the ear. */
    private var privateCallCameraPausedAtEar = false
    /** Experimental lock-screen call presentation (off by default; see CallLockScreen). */
    private val callLockScreen by lazy { CallLockScreen(this) }
    private var callNotificationPermissionAsked = false
    private val callNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> Log.i("BluetoothVideo", "Call alert notifications permission granted=$granted") }
    @Volatile private var privateCallRemoteSurface: Surface? = null
    private var privateCallSurfaceFormat: SurfaceVideoFormat? = null
    private var privateCallRemoteVideoStarted = false
    private var debugPrivateCallSurfacePending = false
    private var privateCallMediaStartedAtNs = 0L
    private var privateCallContinuityTracker: RenderedFrameContinuityTracker? = null
    private var privateCallContinuityLastReportNs = 0L
    private var pendingDebugPrivateCallInvite: Pair<String, String?>? = null
    private var ivfReplay: IvfVideoReplay? = null
    private var activeStreamSettings: VideoSettings? = null
    private var cameraPreview: CameraPreview? = null
    private var activeCameraViewfinderNeedsRefresh = false
    private var streamAdvertiser: BleDiscoveryAdvertiser? = null
    /** Experimental peer labels: public title pages beside the stream offer (closed with it). */
    private var streamTitleAdvertiser: TitleAdvertiser? = null
    private var streamDiscovery: BleL2capDiscovery? = null
    private var nearbyStreamDiscovery: BleL2capDiscovery? = null
    private var pendingViewerPeers = emptyList<BleL2capPeer>()
    private val viewerPeersLastSeen = mutableMapOf<String, Pair<BleL2capPeer, Long>>()
    private var nearbyStreamPeers = emptyList<BleL2capPeer>()
    private val nearbyStreamsLastSeen = mutableMapOf<String, Pair<BleL2capPeer, Long>>()
    private var selectedStreamPeer: BleL2capPeer? = null
    private var streamToken = 0
    private var streamCapacityGeneration = 0
    private var streamAdvertisedFull = false
    private var streamRouteToken = 0
    private var streamPresenceStarting = false
    private var streamArmed = false
    private var streamPaused = false
    private var receiverStreamEnded = false
    private var receiverWasActive = false
    private var receiverRouteWithdrawal: StreamRouteWithdrawal? = null
    private var receiverBrowserStartedAtNs = 0L
    private var receiverFirstOfferLogged = false
    private var receiverJoinStartedAtNs = 0L
    private var receiverFirstSurfaceForJoinPending = false
    private var receiverPlaybackPaused = false
    private val receiverRecoveryNoticePolicy = ReceiverRecoveryNoticePolicy()
    private var receiverRecoveryNoticeRunnable: Runnable? = null
    private var viewerDiscoveryContinuousLowLatency: Boolean? = null
    private var receiverContinuityTracker: RenderedFrameContinuityTracker? = null
    private var receiverContinuityLastReportNs = 0L
    private var activeStreamTarget: LogicalStreamTarget? = null
    @Volatile private var activeStreamSourceNodeId: NodeId? = null
    private var reconnectTarget: LogicalStreamTarget? = null
    private var reconnectPreferredTransport: BluetoothTransportOption? = null
    private var reconnectFromNodeIdPrefix: ByteArray? = null
    /** After a link loss of an active relayed route, that relay is tried first (RelayChildReanchor.kt). */
    private var preferredRecoveryParent: ByteArray? = null
    /** Until this elapsedRealtime, reconnection waits for [preferredRecoveryParent]'s offer (0 = no wait). */
    private var preferredRecoveryGraceUntilMs = 0L
    private var preferredRecoveryGraceLogged = false
    private val preferredRecoveryGraceExpired = Runnable {
        if (reconnectTarget != null && receiver == null) refreshNearbyStreamPeers()
    }
    private val suppressedStreamRoutes = mutableMapOf<StreamReconnectTarget, Long>()
    private val capacitySuppressedRouteGenerations = mutableMapOf<StreamReconnectTarget, Int>()
    private val consecutiveRouteFailures = mutableMapOf<StreamReconnectTarget, Int>()
    /** Parents whose links failed to establish (hop-local; ranks them last for a bounded time). */
    private val parentEstablishment = EstablishmentFailureTracker()

    private fun establishmentFailing(announcement: DiscoveryAnnouncement): Boolean =
        DebugTunables.relayEstablishFallback && parentEstablishment.isMarked(
            AdaptivePhyMemory.prefixLabel(announcement.nodeIdPrefix),
            SystemClock.elapsedRealtime(),
        )

    /** Logs when the establishment rule moved the choice away from a marked parent. */
    private fun logEstablishmentFallback(candidates: List<BleL2capPeer>, chosen: BleL2capPeer?) {
        if (chosen == null || establishmentFailing(chosen.announcement)) return
        val avoided = candidates.filter { establishmentFailing(it.announcement) }
        if (avoided.isEmpty()) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK relay_parent_fallback: reason=establishment | side=child | avoided=" +
                avoided.joinToString(",") { AdaptivePhyMemory.prefixLabel(it.announcement.nodeIdPrefix) } +
                " | selected=${AdaptivePhyMemory.prefixLabel(chosen.announcement.nodeIdPrefix)} | " +
                "hops=${chosen.announcement.routeCost}",
        )
    }
    /** Same-signature receiver failures; survives a reconnect becoming briefly active. */
    private val repeatedReceiverFailures = RepeatedFailureBackoff()
    /** A local decoder/playback failure stopped playback; debug auto-select must not loop on it. */
    private var receiverLocalMediaFailure: String? = null
    private var preserveReceiverContinuityOnNextFormat = false
    private var receiverBrowserNotice: String? = null
    private var receiverBrowserNoticeDeadlineMs = 0L
    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val privateCallDiscoveryHandler = Handler(Looper.getMainLooper())
    private val sessionHandler = Handler(Looper.getMainLooper())
    private val pausedHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!streamPaused) return
            broadcaster?.repeatPausedState()
            sessionHandler.postDelayed(this, PAUSED_HEARTBEAT_MS)
        }
    }
    private val receiverBrowserNoticeTimeoutRunnable = Runnable {
        receiverBrowserNotice = null
        receiverBrowserNoticeDeadlineMs = 0L
        if (appScreen == AppScreen.RECEIVER_BROWSER) refreshNearbyStreamPeers()
    }
    private var activityResumed = false
    private var localTestRunning = false
    private var sessionRole = SessionRole.SEND
    private var appScreen = AppScreen.HOME
    private var settingsReturnScreen = AppScreen.HOME
    private var cameraLens = CameraLens.BACK
    private var availableCameraLenses = emptyList<CameraLens>()
    private var outgoingTransport = BluetoothTransportOption.DEFAULT
    /**
     * Experimental CoC -> GATT fallback (CocAutoGatt.kt, default off). The transport the current call
     * endpoint was created with; null outside a call-browser session. With the flag off it always
     * equals [outgoingTransport], so [currentCallTransport] is exactly the old behaviour.
     */
    private var callEndpointTransport: BluetoothTransportOption? = null
    private var callTransportChoice: CocTransportChoice? = null
    /** A call to retry over GATT once the GATT endpoint is available (set after a CoC connect failure). */
    private var cocRetryPeer: Pair<BleL2capPeer, CallType>? = null
    /** A GATT peer chosen from an L2CAP session: the endpoint is rebuilt on GATT, then this peer is called. */
    private var cocRebuildPeer: Pair<BleL2capPeer, CallType>? = null
    /** The peer of the outgoing call in progress, kept so a CoC connect failure can retry it over GATT. */
    private var lastOutgoingCallPeer: Pair<BleL2capPeer, CallType>? = null
    private val currentCallTransport: BluetoothTransportOption
        get() = callEndpointTransport ?: outgoingTransport
    private val debugBlePhyPreference = BlePhyPreference.TWO_M
    private val debugCorruptRecord: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_corrupt_record", false)
    }
    private val debugPreserveCallBitrate: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_preserve_call_bitrate", false)
    }
    private val debugGattDeferConnectionPriority: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_gatt_defer_connection_priority", false)
    }
    private val debugVideoPresentationTiming: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_video_presentation_timing", false)
    }
    /** On by default; a debug launch may turn it off for A/B. */
    private val debugReleaseVideoAtPlayout: Boolean by lazy {
        !BuildConfig.DEBUG || intent.getBooleanExtra("test_release_video_at_playout", true)
    }
    private val debugCallPipelineTiming: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_call_pipeline_timing", false)
    }
    private val debugMediaTraceSession: String? by lazy {
        intent.getStringExtra("test_media_trace")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
    }
    /** On by default where the decoder supports it; a debug launch may turn it off for A/B. */
    private val debugLowLatencyDecoder: Boolean by lazy {
        !BuildConfig.DEBUG || intent.getBooleanExtra("test_decoder_low_latency", true)
    }
    private val debugBroadcastAudioEnabled: Boolean by lazy {
        !BuildConfig.DEBUG || intent.getBooleanExtra("test_audio", true)
    }
    private val debugPreferredDecoderName: String? by lazy {
        intent.getStringExtra("test_decoder")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
    }
    private val debugStreamIoConfig: VideoStreamIoConfig by lazy {
        if (!BuildConfig.DEBUG) return@lazy VideoStreamIoConfig.DEFAULT
        val bufferKiB = intent.getIntExtra(
            "test_stream_buffer_kib",
            VideoStreamIoConfig.DEFAULT_BUFFER_BYTES / 1_024,
        ).coerceIn(
            VideoStreamIoConfig.MIN_BUFFER_BYTES / 1_024,
            VideoStreamIoConfig.MAX_BUFFER_BYTES / 1_024,
        )
        VideoStreamIoConfig(
            bufferBytes = intent.getIntExtra("test_stream_buffer_bytes", 0)
                .takeIf { it > 0 }
                ?.coerceIn(VideoStreamIoConfig.MIN_BUFFER_BYTES, VideoStreamIoConfig.MAX_BUFFER_BYTES)
                ?: (bufferKiB * 1_024),
            flushPolicy = VideoFlushPolicy.fromDebugValue(
                intent.getStringExtra("test_stream_flush"),
            ),
        )
    }

    private fun streamIoConfigFor(transport: BluetoothTransportOption): VideoStreamIoConfig {
        if (BuildConfig.DEBUG && (
                intent.hasExtra("test_stream_buffer_kib") || intent.hasExtra("test_stream_buffer_bytes")
            )
        ) {
            return debugStreamIoConfig
        }
        return debugStreamIoConfig.copy(bufferBytes = defaultVideoStreamBufferBytes(transport))
    }
    private val debugReplayPath: String? by lazy {
        intent.getStringExtra("test_replay_ivf")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
    }
    private val debugRawInputFile: File? by lazy {
        intent.getStringExtra("test_raw_yuv")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.let(::File)
    }
    /** DEBUG raw-input call self-view rate: 5 FPS by default, 0 turns it off. */
    private val debugCallRawSelfViewFps: Int by lazy {
        if (BuildConfig.DEBUG) intent.getIntExtra("test_call_raw_self_view_fps", 5).coerceIn(0, 15) else 0
    }
    private val debugReferenceAudioFile: File? by lazy {
        intent.getStringExtra("test_reference_audio")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.let(::File)
    }
    private val debugMediaImpairmentFile: File? by lazy {
        intent.getStringExtra("test_media_impairment")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.let(::File)
    }
    private var debugBroadcastImpairment: DebugMediaImpairment? = null
    private var debugCallImpairment: DebugMediaImpairment? = null

    private fun debugImpairedSend(
        send: (Int, Long, ByteArray) -> Unit,
        call: Boolean = false,
    ): (Int, Long, ByteArray) -> Unit {
        if (debugMediaImpairmentFile == null) return send
        val impairment = if (call) {
            debugCallImpairment ?: DebugMediaImpairment.startIfRequested(this, debugMediaTraceSession, debugMediaImpairmentFile)
                .also { debugCallImpairment = it }
        } else {
            debugBroadcastImpairment ?: DebugMediaImpairment.startIfRequested(this, debugMediaTraceSession, debugMediaImpairmentFile)
                .also { debugBroadcastImpairment = it }
        }
        return if (impairment == null) send else { flags, ptsUs, bytes -> impairment.deliver(flags, ptsUs, bytes, send) }
    }
    private var debugControlSequence = 0L
    private var debugPendingControl: Pair<Long, Long>? = null

    private fun logDebugControlCompletion(control: String, applied: Boolean, pending: Pair<Long, Long>) {
        if (debugMediaTraceSession == null) return
        Log.i("BluetoothVideo", "BENCHMARK lifecycle v1 " + JSONObject()
            .put("session", debugMediaTraceSession).put("pid", android.os.Process.myPid())
            .put("clock", "System.nanoTime").put("action_id", pending.first).put("action", control)
            .put("kind", "ack").put("authenticated", false)
            .put("command_ns", pending.second).put("ack_ns", System.nanoTime())
            .put("applied", applied).put("scope", "Local app acceptance; remote completion requires its own acknowledgement")
            .toString())
    }

    private fun referenceAudioVideoFrames(selected: VideoSettings = activeStreamSettings ?: settings): Long {
        if (debugReferenceAudioFile == null) return 0L
        val raw = requireNotNull(debugRawInputFile) { "Reference audio requires the matching native raw video" }
        val frameBytes = selected.resolution.width.toLong() * selected.resolution.height * 3 / 2
        require(frameBytes > 0 && raw.length() % frameBytes == 0L) { "Reference raw video has incomplete native frames" }
        return raw.length() / frameBytes
    }
    private val debugAutoBroadcastRequested: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_auto_broadcast", false)
    }
    private val debugAutoSelectStream: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_auto_select_stream", false)
    }
    private val debugStreamNodePrefix: String? by lazy {
        intent.getStringExtra("test_stream_node")?.lowercase(Locale.US)
            ?.takeIf { BuildConfig.DEBUG && it.matches(Regex("[0-9a-f]{12}")) }
    }
    private val debugMinimumRouteCost: Int by lazy {
        if (BuildConfig.DEBUG) {
            intent.getIntExtra("test_min_route_cost", 0).coerceAtLeast(0)
        } else {
            0
        }
    }
    private val debugClassicAddressNodePrefix: String? by lazy {
        intent.getStringExtra("test_classic_address_node")
            ?.lowercase(Locale.US)
            ?.takeIf { BuildConfig.DEBUG && it.length == DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2 }
    }
    private val debugClassicAddress: String? by lazy {
        intent.getStringExtra("test_classic_address")
            ?.takeIf { BuildConfig.DEBUG && BluetoothAdapter.checkBluetoothAddress(it) }
    }
    private val debugMaximumChildren: Int? by lazy {
        intent.getIntExtra("test_max_children", 0)
            .takeIf { BuildConfig.DEBUG && it > 0 }
            ?.coerceAtMost(8)
    }
    private val debugRequireRelay: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_require_relay", false)
    }
    private val debugRelayChildCount: Int by lazy {
        if (BuildConfig.DEBUG) {
            intent.getIntExtra("test_relay_children", 1).coerceIn(1, 3)
        } else {
            1
        }
    }
    private val debugPrivateCallRelay: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_private_call_relay", false)
    }
    /** DEBUG call relay: take only the offer for this 12-hex-digit callee prefix. */
    private val debugRelayCalleePrefix: String? by lazy {
        intent.getStringExtra("test_relay_callee")?.lowercase(Locale.US)
            ?.takeIf { BuildConfig.DEBUG && it.matches(Regex("[0-9a-f]{12}")) }
    }
    /** DEBUG call relay: take only an offer at this route cost (0 = the callee, n = the n-th relay). */
    private val debugRelayRouteCost: Int? by lazy {
        intent.getIntExtra("test_relay_route_cost", -1).takeIf { BuildConfig.DEBUG && it >= 0 }
    }
    private val debugReceiverPacketDelayMs: Long by lazy {
        if (BuildConfig.DEBUG) {
            intent.getLongExtra("test_receiver_delay_ms", 0L).coerceIn(0L, 1_000L)
        } else {
            0L
        }
    }
    private val debugSourceGattPriority: BleConnectionPriority? by lazy {
        intent.getStringExtra("test_source_gatt_priority")
            ?.takeIf { BuildConfig.DEBUG && it in setOf("high", "dck", "balanced") }
            ?.let(BleConnectionPriority::fromDebugValue)
    }
    private val debugForwarderGattPriority: BleConnectionPriority? by lazy {
        intent.getStringExtra("test_forwarder_gatt_priority")
            ?.takeIf { BuildConfig.DEBUG && it in setOf("high", "dck", "balanced", "low") }
            ?.let(BleConnectionPriority::fromDebugValue)
    }
    private val debugDirectGattHeartbeat: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_direct_gatt_heartbeat", true)
    }
    private val debugGattWriteTiming: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_gatt_write_timing", false)
    }
    private val debugPlaybackDelayMs: Long? by lazy {
            intent.getIntExtra("test_playback_delay_ms", -1)
                .takeIf { BuildConfig.DEBUG && it >= 0 }
                ?.coerceAtMost(500)
                ?.toLong()
    }
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalListenerRegistered = false
    private var lastThermalHeadroomSampleMs = -THERMAL_HEADROOM_SAMPLE_MS
    private var lastBatteryTemperatureSampleMs = -BATTERY_TEMPERATURE_SAMPLE_MS
    private var cachedBatteryTemperatureC: Float? = null
    private var automaticThermalReductionEnabled = true
    private var thermalAdaptationApplied = false

    /** The automatic-cooling setting AND the registry entry (`test_device_policy_off=thermal-status-ladder`). */
    private val thermalReductionOn: Boolean
        get() = automaticThermalReductionEnabled && MemoryThermalPolicy.ladderActive()

    /** The forecast experiment acts only while thermal reduction is on (benchmark profiles turn it off). */
    private val thermalForecastOn: Boolean
        get() = MemoryThermalPolicy.headroomForecast && thermalReductionOn
    private var thermalForecastHeadroom: Float? = null
    private var lastThermalBenchmarkLine = ""
    private val thermalHandler = Handler(Looper.getMainLooper())
    private val thermalDisplayRunnable = object : Runnable {
        override fun run() {
            sampleThermalHeadroomIfDue()
            updateThermalBadge()
            if (activityResumed) thermalHandler.postDelayed(this, THERMAL_BADGE_REFRESH_MS)
        }
    }
    private val thermalRecoveryRunnable = Runnable {
        if (thermalAdaptationApplied && !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) {
            val percent = applyThermalAdaptation(thermalStatus)
                ?: return@Runnable
            setStatus(
                if (percent == 100) {
                    "Phone cooled down: restored the selected stream limits."
                } else {
                    "Phone cooled down: automatic cooling now uses $percent% stream limits."
                },
            )
        }
    }
    private var surfaceVideoFormat: SurfaceVideoFormat? = null
    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
        val previousStatus = thermalStatus
        thermalStatus = status
        updateThermalBadge()
        val videoActive = thermalVideoActive()
        logThermalDecision(
            status,
            MemoryThermalPolicy.effectiveStatus(status, thermalForecastHeadroom, thermalForecastOn),
            0,
            "listener",
        )
        when {
            ThermalGuardPolicy.shouldBlockStreaming(status) && videoActive -> {
                thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                if (privateCall != null) {
                    stopPrivateCallEndpoint("Call stopped: the phone reached critical thermal stress.")
                } else {
                    stopStreaming("Stopped automatically: the phone reached critical thermal stress.")
                }
            }
            status < previousStatus && videoActive && thermalAdaptationApplied -> {
                thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                thermalHandler.postDelayed(thermalRecoveryRunnable, THERMAL_RECOVERY_DELAY_MS)
            }
            ThermalGuardPolicy.shouldReduceWorkload(
                status,
                thermalReductionOn,
            ) && videoActive && activeStreamSettings != null -> {
                thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                val percent = applyThermalAdaptation(status) ?: 100
                setStatus(
                    "Automatic cooling: FPS and bitrate are limited to $percent% of your selections.",
                )
            }
            else -> {
                if (status >= PowerManager.THERMAL_STATUS_MODERATE && videoActive) {
                    setStatus(
                        if (!thermalReductionOn) {
                            "Phone is hot; automatic cooling is off."
                        } else {
                            "Phone is hot; this device has no active camera encoder to reduce."
                        },
                    )
                }
                updateStartEnabled()
            }
        }
    }
    private val settingsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (appScreen) {
                AppScreen.SETTINGS -> showSettings(false)
                AppScreen.CAMERA -> showHomeScreen()
                AppScreen.RECEIVER_BROWSER -> showHomeScreen()
                AppScreen.PLAYBACK -> showReceiverBrowser()
                AppScreen.CALL_BROWSER -> showHomeScreen()
                AppScreen.CALL -> endPrivateCall()
                AppScreen.HOME -> Unit
            }
        }
    }
    private val benchmarkFrames: Int by lazy {
        if (BuildConfig.DEBUG) intent.getIntExtra("benchmark_frames", 0).coerceAtLeast(0) else 0
    }
    private val requestedTransportExperiment: String? by lazy {
        intent.getStringExtra("test_experiment")?.lowercase()?.takeIf { BuildConfig.DEBUG }
    }
    private val debugPrivateCallBrowser: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_private_call_browser", false)
    }
    private var transportExperimentPending = true
    private val debugLaunchRequested: Boolean by lazy {
        BuildConfig.DEBUG &&
            (intent.getBooleanExtra("test_auto_start", false) || debugAutoBroadcastRequested ||
                debugAutoSelectStream || debugPrivateCallBrowser)
    }
    private var debugAutoStartPending = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasRequiredPermissions()) {
            ensureBluetoothEnabledAndRefresh()
        } else {
            setStatus("Camera, microphone, and Nearby devices permissions are required.")
            updateStartEnabled()
        }
    }
    private val bluetoothEnableRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            onPermissionsReady()
        } else {
            setStatus("Bluetooth is off.")
            updateStartEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isColdStartShutdown(BuildConfig.DEBUG, savedInstanceState != null, intent.getStringExtra("test_control"))) {
            // A runner's shutdown control reached an app that had already exited, so Android started
            // a fresh process for it (graceful_stop's am start; a new ASUS process after cleanup
            // quarantined phones in bmaraw-ab-call-9c121b30-run1 and calls-noloss-m31asus-1756e738
            // 0008). Leave without opening Bluetooth, camera or services, and end the process so
            // cleanup finds none.
            Log.i("BluetoothVideo", "BENCHMARK control shutdown applied: cold start exits")
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        if (EmergencyWipe.isPending(this)) {
            val resumed = EmergencyWipe.resumePendingWipe(this)
            if (!resumed.complete) {
                // An interrupted wipe still cannot finish: start no transport, key or store, and say so.
                Log.w("BluetoothVideo", "EMERGENCY_WIPE pending and incomplete: showing the wipe-incomplete screen")
                startupAborted = true
                startActivity(WipeIncompleteActivity.intent(this, resumed))
                finish()
                return
            }
        }
        if (debugEmergencyWipeAuthorized(intent)) {
            // DEBUG-only control: wipe before any key, store or radio opens; the process then ends.
            finishAndRemoveTask()
            EmergencyWipe.wipe(this, WipeReason.DEBUG_CONTROL)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        LinkCapacity.attach(this, bluetoothAdapter)
        DeviceTraits.attachBluetoothAdapter(bluetoothAdapter)
        DebugTunables.devicePolicyOff = parseDevicePolicySwitches(intent.getStringExtra("test_device_policy_off"))
        // Verified contacts are checked by every end-to-end handshake; this opens no key until one exists.
        IdentityVerification.attach(this)
        EmergencyWipe.registerSessionTerminator(emergencyWipeTerminator)
        runCatching { AppShare.cleanupExpired(this) } // Shared APK copies live at most AppSharePolicy.COPY_TTL_MS.
        runCatching { DozeWatchdog.attach(this) } // Observes and logs Doze/standby only (DozeWatchdog).
        if (BuildConfig.DEBUG) BluetoothRestart.busyProbe = bluetoothRestartProbe
        DebugTunables.sourceGattPriority = debugSourceGattPriority
        DebugTunables.encoderRateTrim = intent.getBooleanExtra("test_encoder_rate_trim", true)
        DebugTunables.encoderBitrateAdjuster = intent.getBooleanExtra("test_encoder_bitrate_adjuster", true)
        DebugTunables.encoderFramerateAdjuster = intent.getBooleanExtra("test_encoder_framerate_adjuster", false)
        DebugTunables.benchmarkLaunch = intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        DebugTunables.linkPacing = intent.getBooleanExtra("test_link_pacing", true)
        DebugTunables.scanRecovery = intent.getBooleanExtra("test_scan_recovery", true)
        DebugTunables.linkPacerSeed = intent.getBooleanExtra("test_link_pacer_seed", true)
        DebugTunables.experimentalPeerLinkSeed = intent.getBooleanExtra("test_link_pacer_peer_seed", false)
        DebugTunables.callLinkPacing = intent.getBooleanExtra("test_call_link_pacing", false)
        DebugTunables.classicPageRandomAddress = intent.getBooleanExtra("test_classic_page_random_address", false)
        DebugTunables.fanOutEarlyRelease = intent.getBooleanExtra("test_fanout_early_release", true)
        DebugTunables.playbackGain = intent.getFloatExtra("test_playback_gain", -1f).takeIf { it >= 0f }
        MemoryThermalPolicy.headroomForecast = intent.getBooleanExtra("test_thermal_forecast", false)
        MemoryThermalPolicy.speedFloor = intent.getBooleanExtra("test_thermal_speed_floor", false)
        MemoryThermalPolicy.configureForDevice(
            (getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true,
        )
        Log.i("BluetoothVideo", NativeCompat.currentReportLine())
        DebugTunables.callLadderFromRequest = intent.getBooleanExtra("test_call_ladder_from_request", false)
        DebugTunables.deliveryRateControl = intent.getBooleanExtra("test_delivery_rate_control", false)
        DebugTunables.deliveryReports = intent.getBooleanExtra("test_delivery_reports", true)
        DebugTunables.streamDeliveryReports = intent.getBooleanExtra("test_stream_delivery_reports", false)
        DebugTunables.startRateGuard = intent.getBooleanExtra("test_start_rate_guard", true)
        DebugTunables.callNativeOpus = intent.getBooleanExtra("test_call_native_opus", false)
        DebugTunables.callNativeOpusFec = intent.getBooleanExtra("test_call_native_opus_fec", true)
        DebugTunables.callNativeOpusLossPercent =
            intent.getIntExtra("test_call_native_opus_loss_pct", NativeOpusSettings.DEFAULT_LOSS_PERCENT)
        DebugTunables.callNativeOpusDtx = intent.getBooleanExtra("test_call_native_opus_dtx", false)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK rate_control_policy: deliveryReports=${DebugTunables.deliveryReports} | " +
                    "deliveryProbing=${DebugTunables.deliveryRateControl} | " +
                    "streamDeliveryReports=${DebugTunables.streamDeliveryReports} | " +
                    "startRateGuard=${startRateGuardOn()} | " +
                    "bitrateAdjuster=${DebugTunables.encoderBitrateAdjuster}",
            )
            // A/B arms start from the same learned start rates (24 h, per peer), and every launch
            // records what it starts from.
            if (intent.getBooleanExtra("test_reset_learned_rates", false)) {
                Log.i("BluetoothVideo", "BENCHMARK learned_rates: reset | cleared=${LinkCapacity.clearSustainedRates()}")
            }
            Log.i("BluetoothVideo", "BENCHMARK learned_rates: ${LinkCapacity.sustainedRatesSummary()}")
            // Learned limits (ceilings, bad-2M marks) persist across runs and builds: record them on
            // every launch, and let A/B arms start clean (mesh4-b9726176-loop4-1: stale M31 relay and
            // GATT fan-out ceilings and an ASUS bad-2M mark refused four viewers).
            DebugTunables.ceilingProbeRecovery = intent.getBooleanExtra("test_ceiling_probe_recovery", false)
            if (intent.getBooleanExtra("test_reset_learned_state", false)) {
                Log.i("BluetoothVideo", "BENCHMARK learned_state: reset | cleared=${LinkCapacity.resetLearnedState()}")
            }
            Log.i(
                "BluetoothVideo",
                "BENCHMARK learned_state: ${LinkCapacity.learnedStateSummary()} | " +
                    "ceilingProbeRecovery=${DebugTunables.ceilingProbeRecovery}",
            )
        }
        DebugTunables.experimentalBoundedMediaAge = intent.getBooleanExtra("test_bounded_media_age", false)
        applyRelayChildMediaAge()
        DebugTunables.callVideoFragments = intent.getBooleanExtra("test_call_video_fragments", false)
        DebugTunables.callSpeechLedPlayout = intent.getBooleanExtra("test_call_speech_led_playout", false)
        DebugTunables.callAudioHeadroom = intent.getBooleanExtra("test_call_audio_headroom", true)
        DebugTunables.callCodecFallback = intent.getBooleanExtra("test_call_codec_fallback", true)
        DebugTunables.callLegacyLowDelay = intent.getBooleanExtra("test_call_legacy_low_delay", false)
        DebugTunables.callLegacyRelief = intent.getBooleanExtra("test_call_legacy_relief", false)
        DebugTunables.callLockScreen = intent.getBooleanExtra("test_call_lock_screen", false)
        CallUx.settings = CallUxSettings.resolve(BuildConfig.DEBUG, DebugTunables.benchmarkLaunch) {
            intent.getBooleanExtra(it, false)
        }
        DebugTunables.callAudioConceal = CallUx.settings.audioConcealment
        registerCallNotificationReceiver()
        NotificationPrivacy.debugPeerNames = if (intent.hasExtra("test_lock_screen_peer_names")) {
            intent.getBooleanExtra("test_lock_screen_peer_names", false)
        } else {
            null
        }
        DebugTunables.callFirstFrameNow = intent.getBooleanExtra("test_call_first_frame_now", false)
        DebugTunables.callEncoderFit = intent.getBooleanExtra("test_call_encoder_fit", false)
        DebugTunables.callRelayChain = intent.getBooleanExtra("test_call_relay_chain", true)
        DebugTunables.relayedCallMeasuredCap = intent.getBooleanExtra("test_relayed_call_measured_cap", false)
        DebugTunables.callCloseWaitsForPeer = intent.getBooleanExtra("test_call_close_wait_peer", true)
        DebugTunables.relayChildReanchor = intent.getBooleanExtra("test_relay_child_reanchor", true)
        DebugTunables.relayRecoveryGrace = intent.getBooleanExtra("test_relay_recovery_grace", true)
        DebugTunables.relayParentTrickle = intent.getBooleanExtra("test_relay_parent_trickle", true)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_policy: videoFragments=${DebugTunables.callVideoFragments} | " +
                    "speechLedPlayout=${DebugTunables.callSpeechLedPlayout} | " +
                    "audioHeadroom=${DebugTunables.callAudioHeadroom} | codecFallback=${DebugTunables.callCodecFallback} | " +
                    "legacyLowDelay=${DebugTunables.callLegacyLowDelay} | legacyRelief=${DebugTunables.callLegacyRelief} | " +
                    "lockScreen=${DebugTunables.callLockScreen} | firstFrameNow=${DebugTunables.callFirstFrameNow} | " +
                    "encoderFit=${DebugTunables.callEncoderFit} | relayChain=${DebugTunables.callRelayChain} | " +
                    "relayedMeasuredCap=${DebugTunables.relayedCallMeasuredCap} | " +
                    "closeWaitsForPeer=${DebugTunables.callCloseWaitsForPeer} | ${CallUx.label()}",
            )
        }
        if (BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK media_age_policy: policy=${BoundedMediaAge.policyLabel()} | " +
                    "relayChild=${DebugTunables.experimentalRelayChildMediaAge}")
        }
        DebugTunables.hardwareQpMin = intent.getIntExtra("test_hw_qp_min", -1).takeIf { it >= 0 }
        DebugTunables.hardwareQpMax = intent.getIntExtra("test_hw_qp_max", -1).takeIf { it >= 0 }
        DebugTunables.bitrateModeOverride = intent.getIntExtra("test_bitrate_mode", -1).takeIf { it in 0..2 }
        DebugTunables.keyFrameShaping = intent.getFloatExtra("test_keyframe_shaping", -1f)
            .takeIf { it > 0f && it < 1f }?.toDouble()
        DebugTunables.hardwareTemporalLayers = intent.getBooleanExtra("test_hardware_temporal_layers", false)
        DebugTunables.sharedCocListener = if (intent.hasExtra("test_coc_shared_listener")) {
            intent.getBooleanExtra("test_coc_shared_listener", false)
        } else {
            null
        }
        DebugTunables.audioGapAlignment = intent.getBooleanExtra("test_audio_gap_align", true)
        DebugTunables.voiceCallLowDelay = intent.getBooleanExtra("test_voice_call_low_delay", false)
        DebugTunables.callAudioOwnClock = intent.getBooleanExtra("test_call_audio_own_clock", false)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_policy: gapAlign=${DebugTunables.audioGapAlignment} | " +
                    "voiceCallLowDelay=${DebugTunables.voiceCallLowDelay} | " +
                    "callAudioOwnClock=${DebugTunables.callAudioOwnClock}",
            )
        }
        DebugTunables.stableAdvertisingAddress = intent.getBooleanExtra("test_stable_adv_address", true)
        DebugTunables.deferredGattClose = if (intent.hasExtra("test_deferred_gatt_close")) {
            intent.getBooleanExtra("test_deferred_gatt_close", false)
        } else {
            null
        }
        DebugTunables.cocFreshAcl = if (intent.hasExtra("test_coc_fresh_acl")) {
            intent.getBooleanExtra("test_coc_fresh_acl", false)
        } else {
            null
        }
        DebugTunables.cocSequencedPhy = intent.getBooleanExtra("test_coc_sequenced_phy", true)
        DebugTunables.cocPhyAfterConnect = intent.getBooleanExtra("test_coc_phy_after_connect", false)
        DebugTunables.cocSingleInitiator = intent.getBooleanExtra("test_coc_single_initiator", false)
        DebugTunables.peerAddressBook = intent.getBooleanExtra("test_peer_address_book", false)
        DebugTunables.cocCoalesceSdus = intent.getBooleanExtra("test_coc_coalesce_sdus", false)
        DebugTunables.lePriorityRetry = intent.getBooleanExtra("test_le_priority_retry", true)
        DebugTunables.forwarderChildIntervalUnits = intent.getIntExtra("test_forwarder_child_interval_units", -1).takeIf { it > 0 }
        DebugTunables.forwarderCocPriority = intent.getStringExtra("test_forwarder_coc_priority")
            ?.takeIf { it in setOf("high", "dck", "balanced", "low") }
            ?.let(BleConnectionPriority::fromDebugValue)
        DebugTunables.adaptivePhy = intent.getBooleanExtra("test_adaptive_phy", true)
        DebugTunables.relayPhyGuard = intent.getBooleanExtra("test_relay_phy_guard", true)
        DebugTunables.relayOnePduMove = intent.getBooleanExtra("test_relay_one_pdu_move", false)
        DebugTunables.relayColdStart = intent.getBooleanExtra("test_relay_cold_start", true)
        DebugTunables.relayEstablishFallback = intent.getBooleanExtra("test_relay_establish_fallback", true)
        DebugTunables.fanoutProvenLinks = intent.getBooleanExtra("test_fanout_proven_links", true)
        DebugTunables.relayReanchor = intent.getBooleanExtra("test_relay_reanchor", false)
        DebugTunables.adaptivePhyMemory = intent.getBooleanExtra("test_adaptive_phy_memory", true)
        DebugTunables.phySetupStallGuard = intent.getBooleanExtra("test_phy_setup_stall", true)
        DebugTunables.cocSinglePduSdus = intent.getBooleanExtra("test_coc_single_pdu_sdus", true)
        DebugTunables.experimentalBlePhy = ExperimentalBlePhy.fromDebugValue(intent.getStringExtra("test_ble_phy"))
        DebugTunables.gattRequestMtu = intent.getIntExtra("test_gatt_request_mtu", -1).takeIf { it > 0 }
        DebugTunables.experimentalAlignedGattWrites = intent.getBooleanExtra("test_gatt_aligned_writes", false)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK le_link_policy: sharedCocListener=${CocListeners.sharedEnabled()} | " +
                    "callLinkPacing=${DebugTunables.callLinkPacing} | " +
                    "stableAdvertisingAddress=${DebugTunables.stableAdvertisingAddress} | " +
                    "deferredGattClose=${GattClientTeardown.deferred()} | " +
                    "cocFreshAcl=${BleCocFreshAcl.enabled()} | " +
                    "blePhy=${BlePhyPreference.TWO_M.label} | " +
                    "cocSequencedPhy=${DebugTunables.cocSequencedPhy} | " +
                    "cocPhyAfterConnect=${DebugTunables.cocPhyAfterConnect} | " +
                    "cocSingleInitiator=${DebugTunables.cocSingleInitiator} | " +
                    "peerAddressBook=${DebugTunables.peerAddressBook} | " +
                    "cocCoalesceSdus=${DebugTunables.cocCoalesceSdus}(effective=${CocSduCoalescing.effective()}) | " +
                    "lePriorityRetry=${DebugTunables.lePriorityRetry} | " +
                    "adaptivePhy=${DebugTunables.adaptivePhy} | " +
                    "adaptivePhyMemory=${DebugTunables.adaptivePhyMemory} | " +
                    "phySetupStall=${DebugTunables.phySetupStallGuard} | " +
                    "relayOnePduMove=${DebugTunables.relayOnePduMove} | " +
                    "relayParentTrickle=${DebugTunables.relayParentTrickle} | " +
                    "cocSinglePduSdus=${DebugTunables.cocSinglePduSdus} | " +
                    "gattRequestMtu=${DebugTunables.gattRequestMtu ?: "default"} | " +
                    "gattAlignedWrites=${DebugTunables.experimentalAlignedGattWrites}",
            )
        }
        DeviceFamilyPolicy.current().let { policy ->
            // Log.println, not Log.i: kept in release builds (proguard-release-logs.pro strips Log.v/d/i).
            Log.println(Log.INFO, "BluetoothVideo", DeviceFamilyPolicy.benchmarkLine(policy))
            policy.switchedOff.forEach { Log.w("BluetoothVideo", "Device policy entry switched off: ${it.id}") }
            if (DebugTunables.callLegacyRelief) {
                Log.i("BluetoothVideo", "Device policy call view: legacy relief switches off " +
                    DevicePolicyRegistry.LEGACY_QUALCOMM_CALL.filter(policy::isActive).ifEmpty { listOf("none") }.joinToString(","))
            }
        }
        BackgroundReliability.logStatus(this)
        // Requested vs effective foreground-service types (bg-fgs-start-fallback): tell the user when
        // Android withheld the camera/microphone or the whole background session.
        ActiveVideoSessionService.outcomeListener = { outcome ->
            runOnUiThread {
                if (!isDestroyed) {
                    setStatus(outcome.userNotice())
                    Toast.makeText(this, outcome.userNotice(), Toast.LENGTH_LONG).show()
                }
            }
        }
        // Scan quota (le-scan-start-quota): the stack counts per UID across our restarts; seed the gate
        // from this boot's persisted starts and say when Android's limit delays a search.
        ScanStartGate.shared.attach(PreferencesScanStartStore(this))
        ScanStartGate.deferralListener = { waitMs ->
            runOnUiThread {
                if (!isDestroyed) setStatus("Android limits Bluetooth searches; searching again in ${(waitMs + 999) / 1_000} s.")
            }
        }
        // Workstream C experiments (all off by default; see DebugTunables).
        DebugTunables.sceneCutShaping = SceneCutShapingMode.fromDebugValue(intent.getIntExtra("test_scene_cut_shaping", 0))
        DebugTunables.vp9QuantizerFloor = intent.getIntExtra("test_vp9_q_floor", -1).takeIf { it > 0 }
        DebugTunables.experimentalEncoderRanking = intent.getBooleanExtra("test_encoder_ranking", false)
        DebugTunables.experimentalBurstDrain = intent.getBooleanExtra("test_burst_drain", false)
        DebugTunables.playoutBurstGrowth = intent.getFloatExtra("test_playout_burst_growth", -1f)
            .takeIf { it >= 1f }?.toDouble()
        DebugTunables.intraRefreshFrames = intent.getIntExtra("test_intra_refresh_frames", -1).takeIf { it > 0 }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK burst_experiments: scene_cut_shaping=${DebugTunables.sceneCutShaping.label} | " +
                    "vp9_q_floor=${DebugTunables.vp9QuantizerFloor ?: "off"} | " +
                    "encoder_ranking=${DebugTunables.experimentalEncoderRanking} | " +
                    "burst_drain=${DebugTunables.experimentalBurstDrain} | " +
                    "playout_burst_growth=${DebugTunables.playoutBurstGrowth ?: "off"} | " +
                    "intra_refresh_frames=${DebugTunables.intraRefreshFrames ?: "off"}",
            )
        }

        homeScreen = findViewById(R.id.homeScreen)
        cameraScreen = findViewById(R.id.cameraScreen)
        receiverBrowserScreen = findViewById(R.id.receiverBrowserScreen)
        callBrowserScreen = findViewById(R.id.callBrowserScreen)
        callScreen = findViewById(R.id.callScreen)
        preview = findViewById(R.id.preview)
        cameraViewfinder = findViewById(R.id.cameraViewfinder)
        callRemotePreview = findViewById(R.id.callRemotePreview)
        callLocalViewfinder = findViewById(R.id.callLocalViewfinder)
        callBrowserStatus = findViewById(R.id.callBrowserStatus)
        callPeerList = findViewById(R.id.callPeerList)
        callStatusText = findViewById(R.id.callStatusText)
        callProfileText = findViewById(R.id.callProfileText)
        incomingCallControls = findViewById(R.id.incomingCallControls)
        activeCallControls = findViewById(R.id.activeCallControls)
        cancelCallButton = findViewById(R.id.cancelCallButton)
        callMuteButton = findViewById(R.id.callMuteButton)
        callCameraButton = findViewById(R.id.callCameraButton)
        callSwitchCameraButton = findViewById(R.id.callSwitchCameraButton)
        callSpeakerButton = findViewById(R.id.callSpeakerButton)
        callRemoteCameraOff = findViewById(R.id.callRemoteCameraOff)
        callScreenController = CallScreenController(
            callScreen = findViewById(R.id.callScreen),
            remoteContainer = findViewById(R.id.callRemoteContainer),
            remoteCameraOff = findViewById(R.id.callRemoteCameraOff),
            remoteStateText = findViewById(R.id.callRemoteStateText),
            localContainer = findViewById(R.id.callLocalContainer),
            localViewfinder = callLocalViewfinder,
            localRawPreview = findViewById<ImageView>(R.id.callLocalRawPreview),
            topBar = findViewById(R.id.callTopBar),
            infoButton = findViewById(R.id.callInfoButton),
            profileText = callProfileText,
            bottomBars = listOf(
                incomingCallControls,
                activeCallControls,
                cancelCallButton,
                findViewById(R.id.callPushToTalkButton),
            ),
        )
        intercomCallUi = IntercomCallUi(
            voicePanel = findViewById(R.id.callVoicePanel),
            voicePeerText = findViewById(R.id.callVoicePeerText),
            voiceTypeText = findViewById(R.id.callVoiceTypeText),
            voiceTimer = findViewById(R.id.callVoiceTimer),
            pushToTalkButton = findViewById(R.id.callPushToTalkButton),
            onPushToTalk = ::onPrivateCallPushToTalk,
        )
        settingsScreen = findViewById(R.id.settingsScreen)
        homeConfigurationSummary = findViewById(R.id.homeConfigurationSummary)
        privateCallProfileSummary = findViewById(R.id.privateCallProfileSummary)
        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)
        doneSettingsButton = findViewById(R.id.doneSettingsButton)
        transportSpinner = findViewById(R.id.transportSpinner)
        destinationGroup = findViewById(R.id.destinationGroup)
        destinationLabel = findViewById(R.id.destinationLabel)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        codecSpinner = findViewById(R.id.codecSpinner)
        rateControlSpinner = findViewById(R.id.rateControlSpinner)
        advancedSettingsSwitch = findViewById(R.id.advancedSettingsSwitch)
        advancedSettingsDescriptionText = findViewById(R.id.advancedSettingsDescriptionText)
        rateControlLabel = findViewById(R.id.rateControlLabel)
        rateControlDescriptionText = findViewById(R.id.rateControlDescriptionText)
        videoScaleModeSpinner = findViewById(R.id.videoScaleModeSpinner)
        videoScaleModeDescriptionText = findViewById(R.id.videoScaleModeDescriptionText)
        receiverPlaybackDelaySlider = findViewById(R.id.receiverPlaybackDelaySlider)
        playbackDelayValueText = findViewById(R.id.playbackDelayValueText)
        playbackStabilityText = findViewById(R.id.playbackStabilityText)
        refreshButton = findViewById(R.id.refreshButton)
        capabilityText = findViewById(R.id.capabilityText)
        decoderSupportText = findViewById(R.id.decoderSupportText)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        bitrateText = findViewById(R.id.bitrateText)
        bitrateSeekBar = findViewById(R.id.bitrateSeekBar)
        streamSummaryText = findViewById(R.id.streamSummaryText)
        streamToggleButton = findViewById(R.id.streamToggleButton)
        broadcastPauseButton = findViewById(R.id.broadcastPauseButton)
        statusText = findViewById(R.id.statusText)
        connectionStatusDot = findViewById(R.id.connectionStatusDot)
        deviceHealthOverlay = findViewById(R.id.deviceHealthOverlay)
        thermalStatusBadge = findViewById(R.id.thermalStatusBadge)
        batteryTemperatureBadge = findViewById(R.id.batteryTemperatureBadge)
        thermalReductionSwitch = findViewById(R.id.thermalReductionSwitch)
        deviceHealthOverlaySwitch = findViewById(R.id.deviceHealthOverlaySwitch)
        forwardReceivedStreamsSwitch = findViewById(R.id.forwardReceivedStreamsSwitch)
        experimentalTemporalLayersSwitch = findViewById(R.id.experimentalTemporalLayersSwitch)
        relayChildMediaAgeSwitch = findViewById(R.id.relayChildMediaAgeSwitch)
        relayChildMediaAgeDescriptionText = findViewById(R.id.relayChildMediaAgeDescriptionText)
        experimentalTemporalLayersDescriptionText = findViewById(R.id.experimentalTemporalLayersDescriptionText)
        experimentalVoiceCallsSwitch = findViewById(R.id.experimentalVoiceCallsSwitch)
        experimentalVoiceCallsDescriptionText = findViewById(R.id.experimentalVoiceCallsDescriptionText)
        experimentalPushToTalkSwitch = findViewById(R.id.experimentalPushToTalkSwitch)
        experimentalPttModeSwitch = findViewById(R.id.experimentalPttModeSwitch)
        experimentalPushToTalkDescriptionText = findViewById(R.id.experimentalPushToTalkDescriptionText)
        experimentalStoreForwardSwitch = findViewById(R.id.experimentalStoreForwardSwitch)
        experimentalPeerLabelsSwitch = findViewById(R.id.experimentalPeerLabelsSwitch)
        experimentalPeerLabelsDescriptionText = findViewById(R.id.experimentalPeerLabelsDescriptionText)
        experimentalStoreForwardDescriptionText = findViewById(R.id.experimentalStoreForwardDescriptionText)
        experimentalCocAutoGattSwitch = findViewById(R.id.experimentalCocAutoGattSwitch)
        experimentalCocAutoGattDescriptionText = findViewById(R.id.experimentalCocAutoGattDescriptionText)
        experimentalClassicBondFreeSwitch = findViewById(R.id.experimentalClassicBondFreeSwitch)
        experimentalClassicBondFreeDescriptionText = findViewById(R.id.experimentalClassicBondFreeDescriptionText)
        experimentalCompactDiscoverySwitch = findViewById(R.id.experimentalCompactDiscoverySwitch)
        experimentalRotatingIdsSwitch = findViewById(R.id.experimentalRotatingIdsSwitch)
        experimentalRotatingIdsDescriptionText = findViewById(R.id.experimentalRotatingIdsDescriptionText)
        rotatingIdsVisibleButton = findViewById(R.id.rotatingIdsVisibleButton)
        experimentalCompactDiscoveryDescriptionText = findViewById(R.id.experimentalCompactDiscoveryDescriptionText)
        experimentalOfflineAppShareSwitch = findViewById(R.id.experimentalOfflineAppShareSwitch)
        experimentalOfflineAppShareDescriptionText = findViewById(R.id.experimentalOfflineAppShareDescriptionText)
        videoBackButton = findViewById(R.id.videoBackButton)
        receiverWaitingOverlay = findViewById(R.id.receiverWaitingOverlay)
        receiverWaitingProgress = findViewById(R.id.receiverWaitingProgress)
        receiverWaitingText = findViewById(R.id.receiverWaitingText)
        receiverBrowserTitle = findViewById(R.id.receiverBrowserTitle)
        receiverBrowserStatus = findViewById(R.id.receiverBrowserStatus)
        streamList = findViewById(R.id.streamList)
        automaticThermalReductionEnabled = appPreferences.getBoolean(
            PREF_AUTOMATIC_THERMAL_REDUCTION,
            true,
        )
        showDeviceHealthOverlay = appPreferences.getBoolean(PREF_SHOW_DEVICE_HEALTH, false)
        forwardReceivedStreams = appPreferences.getBoolean(
            PREF_FORWARD_RECEIVED_STREAMS,
            relayDefaultOn(intent.extras?.keySet()?.any { it.startsWith("test_") } == true),
        )
        encoderRateControl = runCatching {
            EncoderRateControl.valueOf(
                appPreferences.getString(
                    PREF_ENCODER_RATE_CONTROL,
                    EncoderRateControl.AUTOMATIC.name,
                ) ?: EncoderRateControl.AUTOMATIC.name,
            )
        }.getOrDefault(EncoderRateControl.AUTOMATIC)
        outgoingTransport = runCatching {
            BluetoothTransportOption.valueOf(
                appPreferences.getString(
                    PREF_OUTGOING_TRANSPORT,
                    BluetoothTransportOption.DEFAULT.name,
                ) ?: BluetoothTransportOption.DEFAULT.name,
            )
        }.getOrDefault(BluetoothTransportOption.DEFAULT)
        restoreVideoScaleMode()
        receiverPlaybackDelay = PlaybackDelayProfile.fromStoredValue(
            appPreferences.getString(PREF_RECEIVER_PLAYBACK_DELAY, null),
        )
        advancedSettings = appPreferences.getBoolean(PREF_ADVANCED_SETTINGS, false)
        applySettingsModeValues()
        applyExperimentalTemporalLayers()
        applyRelayChildMediaAge()
        applyIntercomFeatures()
        CocRegistrations.install(this)
        applyCocAutoGatt()
        ClassicAddressBook.attach(this)
        applyClassicFeatures()
        applyCompactDiscovery()
        applyRotatingIds()
        applyCallReachability()
        applyOfflineAppShare()
        applyCallResolutionLadder()
        applyVideoScaleMode()
        applyDeviceHealthOverlayVisibility()
        restoreVideoSettings()
        applyDebugLaunchConfiguration()
        debugSessionTelemetry = DebugSessionTelemetry.startIfRequested(
            this,
            intent,
            sessionRole.label,
        )

        ViewCompat.setOnApplyWindowInsetsListener(homeScreen) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(settingsScreen) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(receiverBrowserScreen) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16, systemBars.top + 16, 16, systemBars.bottom + 16)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(callBrowserScreen) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16, systemBars.top + 16, 16, systemBars.bottom + 16)
            insets
        }
        val incomingControlsBottomPadding = incomingCallControls.paddingBottom
        val activeControlsBottomPadding = activeCallControls.paddingBottom
        val cancelButtonBottomMargin =
            (cancelCallButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val pushToTalkButton = findViewById<View>(R.id.callPushToTalkButton)
        val pushToTalkBottomMargin = (pushToTalkButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(callScreen) { _, insets ->
            val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            incomingCallControls.setPadding(
                incomingCallControls.paddingLeft,
                incomingCallControls.paddingTop,
                incomingCallControls.paddingRight,
                incomingControlsBottomPadding + systemBars.bottom,
            )
            activeCallControls.setPadding(
                activeCallControls.paddingLeft,
                activeCallControls.paddingTop,
                activeCallControls.paddingRight,
                activeControlsBottomPadding + systemBars.bottom,
            )
            (cancelCallButton.layoutParams as ViewGroup.MarginLayoutParams).let { params ->
                params.bottomMargin = cancelButtonBottomMargin + systemBars.bottom
                cancelCallButton.layoutParams = params
            }
            (pushToTalkButton.layoutParams as ViewGroup.MarginLayoutParams).let { params ->
                params.bottomMargin = pushToTalkBottomMargin + systemBars.bottom
                pushToTalkButton.layoutParams = params
            }
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            callScreenController.setInsets(
                maxOf(systemBars.left, cutout.left),
                maxOf(systemBars.top, cutout.top),
                maxOf(systemBars.right, cutout.right),
                maxOf(systemBars.bottom, cutout.bottom),
            )
            insets
        }

        onBackPressedDispatcher.addCallback(this, settingsBackCallback)
        findViewById<View>(R.id.streamModeButton).setOnClickListener { showStreamScreen() }
        findViewById<View>(R.id.receiveModeButton).setOnClickListener { showReceiverBrowser() }
        findViewById<View>(R.id.privateCallButton).setOnClickListener { showPrivateCallBrowser() }
        findViewById<View>(R.id.homeSettingsButton).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.homeMessagesButton).setOnClickListener {
            if (StoreForwardFeature.enabled) startActivity(Intent(this, MessagesActivity::class.java))
        }
        findViewById<View>(R.id.homeChangeSettingsButton).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.receiverBrowserBackButton).setOnClickListener { showHomeScreen() }
        findViewById<View>(R.id.receiverRefreshButton).setOnClickListener { restartNearbyStreams() }
        findViewById<View>(R.id.callBrowserBackButton).setOnClickListener { showHomeScreen() }
        findViewById<View>(R.id.callRefreshButton).setOnClickListener { restartPrivateCallDiscovery() }
        videoBackButton.setOnClickListener {
            if (appScreen == AppScreen.PLAYBACK) showReceiverBrowser() else showHomeScreen()
        }
        streamList.setOnItemClickListener { _, _, position, _ ->
            nearbyStreamPeers.getOrNull(position)?.let { peer ->
                withWifiCheck {
                    // An explicit choice is a fresh attempt: forget local failures and repeat counts.
                    receiverLocalMediaFailure = null
                    repeatedReceiverFailures.reset()
                    connectToStream(peer)
                }
            }
        }
        callPeerList.setOnItemClickListener { _, _, position, _ ->
            peerForListPosition(position)?.let { peer -> withWifiCheck { choosePrivateCallType(peer) } }
        }
        callPeerList.setOnItemLongClickListener { _, _, position, _ ->
            val peer = peerForListPosition(position) ?: return@setOnItemLongClickListener false
            showPrivateCallPeerDetails(peer)
            true
        }
        findViewById<View>(R.id.acceptCallButton).setOnClickListener {
            if (!ensureThermalReady()) {
                setCallStatus("Cannot accept: heat status is Critical or higher.")
                return@setOnClickListener
            }
            stopPrivateCallRinging()
            // A dialog here could let the ringing call time out; advise without blocking.
            WifiCoexistence.advice()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            runCatching { privateCall?.accept() }.onFailure { setCallStatus(it.message ?: "Could not accept") }
        }
        findViewById<View>(R.id.declineCallButton).setOnClickListener {
            stopPrivateCallRinging()
            runCatching { privateCall?.decline() }.onFailure { setCallStatus(it.message ?: "Could not decline") }
        }
        cancelCallButton.setOnClickListener {
            stopPrivateCallRinging()
            privateCall?.cancel()
        }
        findViewById<View>(R.id.endCallButton).setOnClickListener {
            stopPrivateCallRinging()
            privateCall?.hangUp()
        }
        callMuteButton.setOnClickListener { togglePrivateCallMute() }
        bindHoldMuteToTalk()
        callCameraButton.setOnClickListener { togglePrivateCallCamera() }
        callSwitchCameraButton.setOnClickListener { switchPrivateCallCamera() }
        callSpeakerButton.setOnClickListener { togglePrivateCallSpeaker() }
        availableCameraLenses = deviceVideoCapabilities.availableCameraLenses()
        if (cameraLens !in availableCameraLenses) {
            cameraLens = availableCameraLenses.firstOrNull() ?: CameraLens.BACK
        }
        cameraSwitchButton.setOnClickListener { switchCamera() }
        broadcastPauseButton.setOnClickListener { toggleBroadcastPause() }
        doneSettingsButton.setOnClickListener { showSettings(false) }
        bindRedesignedUi()

        configureTransport()
        configureSettingsControls()
        refreshCodecs()
        applyDebugCodecSelection()
        codecSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshRateControlPicker()
                refreshVideoModes()
                updateDeviceCapabilities()
                updateStreamSummary()
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                applyVideoSurfaceTransform()
                if (appScreen == AppScreen.PLAYBACK && receiver == null) {
                    if (reconnectTarget != null) refreshNearbyStreams() else startReceiving()
                } else {
                    startIdleCameraPreview()
                }
                updateStartEnabled()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyVideoSurfaceTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopIdleCameraPreview()
                if (sender != null || broadcaster != null || receiver != null) {
                    stopStreaming("Video surface was closed.")
                }
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (appScreen != AppScreen.PLAYBACK) return
                val tracker = receiverContinuityTracker ?: return
                val nowNs = System.nanoTime()
                if (debugVideoPresentationTiming) receiver?.recordSurfaceUpdate(surface.timestamp, nowNs)
                tracker.recordFrame(nowNs)
                if (receiverWaitingOverlay.visibility == View.VISIBLE &&
                    receiver != null && receiverWasActive && !receiverRecoveryNoticePolicy.recovering &&
                    !receiverPlaybackPaused && !receiverStreamEnded
                ) hideReceiverOverlay()
                if (receiverFirstSurfaceForJoinPending && receiverWasActive) {
                    receiverFirstSurfaceForJoinPending = false
                    Log.i(
                        "BluetoothVideo",
                        "Receiver startup: selection-to-first-surface %.1f ms".format(
                            Locale.US,
                            (nowNs - receiverJoinStartedAtNs) / 1_000_000.0,
                        ),
                    )
                }
                if (receiverContinuityLastReportNs == 0L || nowNs - receiverContinuityLastReportNs >= CONTINUITY_REPORT_NS) {
                    val snapshot = tracker.snapshot(nowNs)
                    if (snapshot.renderedFrames > 0L) {
                        logReceiverContinuity(snapshot)
                        receiverContinuityLastReportNs = nowNs
                    }
                }
            }
        }

        callRemotePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                privateCallRemoteSurface?.release()
                privateCallRemoteSurface = Surface(surface)
                applyPrivateCallSurfaceTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyPrivateCallSurfaceTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                privateCallRemoteSurface?.release()
                privateCallRemoteSurface = null
                privateCallRemoteVideoStarted = false
                if (appScreen == AppScreen.CALL) privateCall?.hangUp()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (shouldRevealPrivateCallRemotePreview(
                        appScreen == AppScreen.CALL,
                        privateCallRemoteVideoStarted,
                    )
                ) {
                    if (callRemotePreview.alpha != 1f) callRemotePreview.alpha = 1f
                    callScreenController.onRemoteFrame(System.nanoTime())
                    if (intercomCallUi.onRemoteFrame()) onPrivateCallLayoutChanged("remote_frame")
                    val tracker = privateCallContinuityTracker ?: return
                    val nowNs = System.nanoTime()
                    tracker.recordFrame(nowNs)
                    val reportDue = privateCallContinuityLastReportNs == 0L ||
                        nowNs - privateCallContinuityLastReportNs >= CONTINUITY_REPORT_NS
                    if (!reportDue && !debugPrivateCallSurfacePending) return
                    val snapshot = tracker.snapshot(nowNs)
                    if (snapshot.renderedFrames == 0L) return
                    if (debugPrivateCallSurfacePending) {
                        debugPrivateCallSurfacePending = false
                        Log.i("BluetoothVideo", "BENCHMARK call first_surface: frames=${snapshot.renderedFrames}")
                    }
                    if (privateCallContinuityLastReportNs == 0L) {
                        Log.i("BluetoothVideo", "Private call startup: media-to-first-surface %.1f ms".format(
                            Locale.US,
                            snapshot.startupMs ?: 0.0,
                        ))
                    }
                    if (reportDue) {
                        logReceiverContinuity(snapshot, label = "Private call")
                        privateCallContinuityLastReportNs = nowNs
                    }
                }
            }
        }

        refreshButton.setOnClickListener { ensurePermissionsAndRefresh() }
        streamToggleButton.setOnClickListener {
            if (usesBroadcastDiscovery()) {
                if (streamArmed) {
                    stopStreaming("Stopped.")
                } else {
                    withWifiCheck {
                        // Experimental peer labels: optional public title, fixed for this stream.
                        promptLabelText(LabelKind.STREAM_TITLE, R.string.stream_title_prompt, R.string.stream_title_hint, PeerLabelFeature.localStreamTitle) { title ->
                            if (PeerLabelFeature.enabled) PeerLabelFeature.localStreamTitle = title
                            armDiscoverableStream()
                        }
                    }
                }
            } else if (sender != null || broadcaster != null || receiver != null || relay != null ||
                privateCallRelay != null
            ) {
                stopStreaming("Stopped.")
            } else if (sessionRole == SessionRole.SEND) {
                withWifiCheck { startStreaming() }
            } else if (sessionRole == SessionRole.RELAY) {
                withWifiCheck { startRelaying() }
            } else {
                withWifiCheck { startReceiving() }
            }
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("offline_benchmark", false)) {
            showStreamScreen()
            startDebugBenchmark { it.runOffline(intent) }
        } else if (debugPrivateCallBrowser) {
            showPrivateCallBrowser()
        } else if (requestedTransportExperiment != null) {
            showDebugLaunchScreen()
            ensurePermissionsAndRefresh()
        } else if (debugAutoSelectStream) {
            showReceiverBrowser()
        } else if (debugAutoBroadcastRequested) {
            showStreamCamera()
        } else if (debugAutoStartPending) {
            showDebugLaunchScreen()
            ensurePermissionsAndRefresh()
        } else {
            showHomeScreen(stopActive = false)
            // First run: explain the app and its permissions. Never for automation launches
            // (they always carry extras), never on recreation.
            if (shouldShowOnboarding(
                    OnboardingActivity.isDone(this),
                    launchHasExtras = intent.extras?.isEmpty == false,
                    recreated = savedInstanceState != null,
                )
            ) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
        }
        // A launch with benchmark/control extras is automation: do not show onboarding later.
        if (intent.extras?.keySet()?.any { it.startsWith("test_") || it == "offline_benchmark" } == true) {
            OnboardingActivity.markDone(this)
        }
        registerThermalGuard()
    }

    /** Views and handlers added by the UI redesign. Presentation only: no media/protocol changes. */
    private fun bindRedesignedUi() {
        homeReadinessIcon = findViewById(R.id.homeReadinessIcon)
        homeReadinessTitle = findViewById(R.id.homeReadinessTitle)
        homeReadinessText = findViewById(R.id.homeReadinessText)
        homeReadinessAction = findViewById(R.id.homeReadinessAction)
        homeCallSubtitle = findViewById(R.id.homeCallSubtitle)
        homeIdentityText = findViewById(R.id.homeIdentityText)
        callSecurityChip = findViewById(R.id.callSecurityChip)
        callPartyPanel = findViewById(R.id.callPartyPanel)
        callPartyName = findViewById(R.id.callPartyName)
        callPartyState = findViewById(R.id.callPartyState)
        callMuteLabel = findViewById(R.id.callMuteLabel)
        callCameraLabel = findViewById(R.id.callCameraLabel)
        callSpeakerLabel = findViewById(R.id.callSpeakerLabel)
        broadcastStatusText = findViewById(R.id.broadcastStatusText)
        streamSecurityText = findViewById(R.id.streamSecurityText)
        watchRelaySwitch = findViewById(R.id.watchRelaySwitch)
        advancedSection = findViewById(R.id.advancedSection)
        advancedToggleIcon = findViewById(R.id.advancedToggleIcon)
        experimentsHiddenHint = findViewById(R.id.experimentsHiddenHint)
        streamList.emptyView = findViewById(R.id.streamEmptyView)
        callPeerList.emptyView = findViewById(R.id.callEmptyView)
        val callTopBar = findViewById<View>(R.id.callTopBar)
        val callInfoButton = findViewById<View>(R.id.callInfoButton)
        callChromeAutoHide = CallChromeAutoHide(
            views = { listOf(callTopBar, activeCallControls) },
            // Call details moved into the call info sheet: a tap only shows or hides the controls.
            detailsShown = { true },
            toggleDetails = {},
        )
        // The info and lock buttons open the call info sheet (encryption, route, verification, details).
        callInfoButton.setOnClickListener { showLinkHealthSheet() }
        callLockButton = findViewById(R.id.callLockButton)
        callLockButton.setOnClickListener { showLinkHealthSheet() }
        callPeerList.adapter = peopleAdapter
        refreshContactNames()
        // A tap on the video brings the call controls back (or hides them).
        callScreen.setOnClickListener { callChromeAutoHide.toggle() }
        callSecurityChip.setOnClickListener { showLinkHealthSheet() }
        homeReadinessAction.setOnClickListener { onHomeReadinessAction() }
        findViewById<View>(R.id.homePushToTalkButton).setOnClickListener { onHomePushToTalk() }
        findViewById<View>(R.id.homeIdentityCard).setOnClickListener {
            startActivity(VerifyContactActivity.intent(this))
        }
        findViewById<View>(R.id.verifyContactButton).setOnClickListener {
            startActivity(VerifyContactActivity.intent(this))
        }
        findViewById<View>(R.id.aboutRow).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        findViewById<View>(R.id.emergencyWipeButton).setOnClickListener { confirmEmergencyWipe() }
        findViewById<View>(R.id.exportDiagnosticsButton).setOnClickListener { exportDiagnostics() }
        findViewById<View>(R.id.shareAppButton).apply {
            visibility = if (DistributionUiBackend.current.shareAvailable) View.VISIBLE else View.GONE
            setOnClickListener { runCatching { DistributionUiBackend.current.shareApk(this@MainActivity) } }
        }
        findViewById<View>(R.id.shareAppNearbyButton).setOnClickListener {
            OfflineAppShareActivity.startOrExplain(this@MainActivity)
        }
        // Wi-Fi Direct shares the radio with Bluetooth: the share screen refuses (and stops) while
        // any stream, call, relay or pending reconnect exists.
        OfflineAppShareGate.liveSessionCheck = ::liveSessionBlocksAppShare
        findViewById<View>(R.id.reportBugButton).apply {
            val reportBug = SupportLinks.reportBugIntent()
            visibility = if (reportBug != null) View.VISIBLE else View.GONE
            setOnClickListener {
                runCatching { startActivity(reportBug) }
                    .onFailure { Toast.makeText(this@MainActivity, R.string.help_no_browser, Toast.LENGTH_LONG).show() }
            }
        }
        val panicSwitch = findViewById<MaterialSwitch>(R.id.panicGestureSwitch)
        panicSwitch.isChecked = appPreferences.getBoolean(PREF_PANIC_GESTURE, false)
        panicSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(PREF_PANIC_GESTURE, enabled).apply()
        }
        findViewById<View>(R.id.homeTitle).setOnClickListener {
            if (appPreferences.getBoolean(PREF_PANIC_GESTURE, false) &&
                panicTapDetector.onTap(SystemClock.elapsedRealtime())
            ) {
                startPanicWipeCountdown()
            }
        }
        findViewById<View>(R.id.advancedToggleRow).apply {
            contentDescription = getString(R.string.advanced_expand_description)
            setOnClickListener { setAdvancedExpanded(advancedSection.visibility != View.VISIBLE) }
        }
        // "Relay for others" on the Watch screen mirrors the Settings switch (same preference).
        watchRelaySwitch.isChecked = forwardReceivedStreams
        watchRelaySwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled != forwardReceivedStreams) forwardReceivedStreamsSwitch.isChecked = enabled
        }
        applyExperimentsHint()
        updateHomeActions()
        updateHomeReadiness()
        // Empty states lead somewhere (skill: "turn empty states into opportunities with a CTA").
        bindEmptyState(R.id.streamEmptyView, R.drawable.ic_live, R.string.watch_empty_title, R.string.watch_empty_text, R.string.watch_empty_action) {
            showStreamScreen()
        }
        bindEmptyState(R.id.callEmptyView, R.drawable.ic_group, R.string.people_empty_title, R.string.people_empty_text, R.string.people_empty_action) {
            restartPrivateCallDiscovery()
        }
        bindBrowserStatus(
            receiverBrowserStatus,
            streamList,
            setOf("No nearby streams yet. Scanning continues.", "Scanning for nearby streams…", getString(R.string.watch_scanning)),
        )
        bindBrowserStatus(
            callBrowserStatus,
            callPeerList,
            setOf(getString(R.string.people_starting), "No available people yet. Scanning continues."),
        )
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedSection.visibility = if (expanded) View.VISIBLE else View.GONE
        advancedToggleIcon.animate().rotation(if (expanded) 180f else 0f).setDuration(150L).start()
    }

    private fun applyExperimentsHint() {
        if (::experimentsHiddenHint.isInitialized) {
            experimentsHiddenHint.visibility = if (advancedSettings) View.GONE else View.VISIBLE
        }
    }

    /** Readiness card: Bluetooth support, permissions and Bluetooth state, with one action. */
    private fun updateHomeReadiness() {
        if (!::homeReadinessTitle.isInitialized) return
        val adapter = bluetoothAdapter
        val permissionsGranted = OnboardingActivity.permissions(this)
            .filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        val bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val state = homeReadiness(adapter != null, permissionsGranted, bluetoothOn)
        val (icon, tint, title, text) = when (state) {
            HomeReadiness.UNSUPPORTED -> HomeCard(R.drawable.ic_bluetooth_disabled, R.color.danger,
                R.string.readiness_unsupported_title, R.string.readiness_unsupported_text)
            HomeReadiness.PERMISSIONS_NEEDED -> HomeCard(R.drawable.ic_warning, R.color.warning,
                R.string.readiness_permissions_title, R.string.readiness_permissions_text)
            HomeReadiness.BLUETOOTH_OFF -> HomeCard(R.drawable.ic_bluetooth_disabled, R.color.warning,
                R.string.readiness_off_title, R.string.readiness_off_text)
            HomeReadiness.READY -> HomeCard(R.drawable.ic_bluetooth, R.color.success,
                R.string.readiness_ready_title, R.string.readiness_ready_text)
        }
        homeReadinessIcon.setImageResource(icon)
        homeReadinessIcon.imageTintList = ContextCompat.getColorStateList(this, tint)
        homeReadinessTitle.setText(title)
        if (state == HomeReadiness.READY) {
            // "Visible to nearby phones" is true only while the call endpoint really advertises.
            homeReadinessText.setText(
                when (homeReachability(currentReachabilityPlan(), privateCallAvailable, privateCallAvailabilityStarting)) {
                    HomeReachability.REACHABLE -> R.string.readiness_reachable_text
                    HomeReachability.STARTING -> R.string.readiness_reachable_starting_text
                    HomeReachability.NOT_REACHABLE -> R.string.readiness_not_reachable_text
                },
            )
        } else {
            homeReadinessText.setText(text)
        }
        homeReadinessAction.visibility = when (state) {
            HomeReadiness.PERMISSIONS_NEEDED, HomeReadiness.BLUETOOTH_OFF -> View.VISIBLE
            else -> View.GONE
        }
        homeReadinessAction.setText(
            when {
                state == HomeReadiness.BLUETOOTH_OFF -> R.string.readiness_off_action
                homePermissionsAsked -> R.string.readiness_permissions_settings_action
                else -> R.string.readiness_permissions_action
            },
        )
        homeReadinessAction.tag = state
        val deepSleep = runCatching { DistributionUiBackend.current.deepSleepBanner(this) }.getOrNull()
        findViewById<TextView>(R.id.homeDeepSleepBanner).apply {
            setText(deepSleep)
            visibility = if (deepSleep != null) View.VISIBLE else View.GONE
        }
    }

    private data class HomeCard(val icon: Int, val tint: Int, val title: Int, val text: Int)

    private fun onHomeReadinessAction() {
        when (homeReadinessAction.tag as? HomeReadiness) {
            HomeReadiness.BLUETOOTH_OFF ->
                runCatching { homeBluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            HomeReadiness.PERMISSIONS_NEEDED -> if (homePermissionsAsked) {
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null)),
                    )
                }
            } else {
                homePermissionRequest.launch(OnboardingActivity.permissions(this).toTypedArray())
            }
            else -> Unit
        }
    }

    private fun updateHomeActions() {
        if (!::homeCallSubtitle.isInitialized) return
        homeCallSubtitle.setText(
            if (IntercomFeatures.current.voiceCalls) R.string.home_call_subtitle_both else R.string.home_call_subtitle_video,
        )
        val context = applicationContext
        Thread {
            val fingerprint = SecurityUiBackend.current.ownFingerprint(context)
            runOnUiThread {
                if (fingerprint != null && ::homeIdentityText.isInitialized) {
                    homeIdentityText.text = getString(R.string.home_identity_text_id, IdentityHandle.fromPrefixHex(fingerprint.take(12)))
                }
            }
        }.start()
    }

    /** Push-to-talk tile: the existing experimental PTT switches, then the people list. */
    /** The (hidden) Home row explains how talking works in a call: hold Mute while muted. */
    private fun onHomePushToTalk() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_mic)
            .setTitle(R.string.ptt_enable_title)
            .setMessage(R.string.ptt_enable_message)
            .setPositiveButton(R.string.ptt_enable_confirm, null)
            .show()
    }

    private fun loadIdentityIntoSettings() {
        val fingerprintText = findViewById<TextView>(R.id.identityFingerprintText)
        val handleText = findViewById<TextView>(R.id.identityHandleText)
        val safetyText = findViewById<TextView>(R.id.identitySafetyNumberText)
        val context = applicationContext
        val messages = StoreForwardFeature.enabled
        Thread {
            val fingerprint = SecurityUiBackend.current.ownFingerprint(context)
            val safety = if (messages) {
                runCatching { StoreForwardRuntime.node(context).ownCard.safetyNumber() }.getOrNull()
            } else {
                null
            }
            runOnUiThread {
                handleText.text = fingerprint?.let { IdentityHandle.fromPrefixHex(it.take(12)) }.orEmpty()
                handleText.visibility = if (fingerprint != null) View.VISIBLE else View.GONE
                refreshIdentityName()
                fingerprintText.text = fingerprint?.let { IdentityFormat.groupedHex(it) }
                    ?: getString(R.string.identity_unavailable)
                safetyText.visibility = if (safety != null) View.VISIBLE else View.GONE
                if (safety != null) safetyText.text = getString(R.string.identity_safety_number, safety)
            }
        }.start()
        refreshReliabilityCard()
        val apkInfo = runCatching { DistributionUiBackend.current.apkInfo(this) }.getOrNull()
        findViewById<TextView>(R.id.shareAppInfoText).apply {
            text = apkInfo
            visibility = if (apkInfo != null) View.VISIBLE else View.GONE
        }
    }

    private fun refreshReliabilityCard() {
        val container = findViewById<ViewGroup>(R.id.reliabilityContainer)
        val summary = findViewById<TextView>(R.id.reliabilitySummary)
        while (container.childCount > 1) container.removeViewAt(1)
        val issues = runCatching { ReliabilityUiBackend.current.issues(this) }.getOrDefault(emptyList())
        if (issues.isEmpty()) {
            summary.setText(R.string.reliability_ok)
            summary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0)
            summary.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.success)
            return
        }
        summary.text = resources.getString(R.string.reliability_issues, issues.size)
        summary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
        summary.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.warning)
        val inflater = layoutInflater
        issues.forEach { issue ->
            val title = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_App_Title)
                text = issue.title
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            val guidance = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_App_Body)
                text = issue.guidance
            }
            container.addView(title)
            container.addView(guidance)
            issue.settingsIntent?.let { settingsIntent ->
                val button = inflater.inflate(R.layout.item_text_button, container, false)
                    as com.google.android.material.button.MaterialButton
                button.setText(R.string.reliability_fix)
                button.setOnClickListener { runCatching { startActivity(settingsIntent) } }
                container.addView(button)
            }
        }
    }

    private fun exportDiagnostics() {
        val context = applicationContext
        Thread {
            val report = runCatching { DiagnosticsUiBackend.current.build(context) }
                .getOrElse { "Diagnostics failed: ${it.javaClass.simpleName}" }
            runOnUiThread {
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    android.content.ClipData.newPlainText(getString(R.string.help_export_subject), report),
                )
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.help_export_subject))
                    .putExtra(Intent.EXTRA_TEXT, report)
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.help_export))) }
            }
        }.start()
    }

    /** Settings wipe: the user types WIPE; the button stays disabled until they do. */
    private fun confirmEmergencyWipe() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.wipe_type_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.wipe_confirm_title)
            .setMessage(R.string.wipe_confirm_message)
            .setView(frame)
            .setPositiveButton(R.string.wipe_confirm_action) { _, _ ->
                performEmergencyWipe(panic = false, confirmation = input.text?.toString()?.trim())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        val erase = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        erase.isEnabled = false
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                erase.isEnabled = s?.toString()?.trim() == WIPE_CONFIRMATION_TEXT
            }
        })
    }

    /** Quick wipe from the home-title gesture: a 3-second Undo, then the same wipe. */
    private fun startPanicWipeCountdown() {
        if (pendingPanicWipe != null) return
        val wipe = Runnable {
            pendingPanicWipe = null
            performEmergencyWipe(panic = true, confirmation = null)
        }
        pendingPanicWipe = wipe
        sessionHandler.postDelayed(wipe, PANIC_WIPE_DELAY_MS)
        com.google.android.material.snackbar.Snackbar
            .make(homeScreen, R.string.wipe_panic_countdown, PANIC_WIPE_DELAY_MS.toInt())
            .setAction(R.string.wipe_panic_undo) {
                sessionHandler.removeCallbacks(wipe)
                pendingPanicWipe = null
                Log.i("BluetoothVideo", "Emergency wipe: panic gesture undone")
            }
            .show()
    }

    private fun performEmergencyWipe(panic: Boolean, confirmation: String?) {
        Log.i("BluetoothVideo", "Emergency wipe requested: ${if (panic) "panic_gesture" else "settings"}")
        // Onboarding comes back on the next launch (the wipe normally ends the process).
        OnboardingActivity.reset(this)
        val context = applicationContext
        Thread {
            val error = runCatching { SecurityUiBackend.current.wipe(context, panic, confirmation) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (error != null) {
                    OnboardingActivity.markDone(this)
                    Toast.makeText(this, getString(R.string.wipe_failed, error), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                startActivity(
                    Intent(this, OnboardingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                finish()
            }
        }.start()
    }

    /** In-call chip: encryption, route and verification of the end-to-end peer. */
    private fun updateCallSecurityChip() {
        if (!::callSecurityChip.isInitialized) return
        val endpoint = privateCall
        val peer = endpoint?.endToEndPeerNodeId
        if (endpoint == null || peer == null) {
            callSecurityChip.visibility = View.GONE
            return
        }
        val route = callRoute(endpoint.adjacentPeerLabel, peer, privateCallDialledRelays)
        val routeText = when (route) {
            is CallRoute.Relayed -> when (route.relays) {
                null -> getString(R.string.call_security_relayed_unknown)
                1 -> getString(R.string.call_security_relayed_one)
                else -> getString(R.string.call_security_relayed, route.relays)
            }
            CallRoute.Direct -> getString(R.string.call_security_direct)
            null -> getString(R.string.call_security_encrypted)
        }
        val context = applicationContext
        Thread {
            val state = runCatching { SecurityUiBackend.current.verificationState(context, peer) }
                .getOrDefault(ContactVerification.UNVERIFIED)
            runOnUiThread {
                if (privateCall !== endpoint) return@runOnUiThread
                val stateText = getString(
                    when (state) {
                        ContactVerification.VERIFIED -> R.string.verification_verified
                        ContactVerification.UNVERIFIED -> R.string.verification_unverified
                        ContactVerification.MISMATCH -> R.string.verification_mismatch
                    },
                )
                callSecurityChip.text = "$routeText. $stateText"
                callSecurityChip.contentDescription = getString(R.string.call_security_description, routeText, stateText)
                val mismatch = state == ContactVerification.MISMATCH
                callSecurityChip.setBackgroundResource(
                    if (mismatch) R.drawable.mismatch_banner_background else R.drawable.call_overlay_background,
                )
                callSecurityChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    when (state) {
                        ContactVerification.VERIFIED -> R.drawable.ic_verified_user
                        ContactVerification.UNVERIFIED -> R.drawable.ic_lock
                        ContactVerification.MISMATCH -> R.drawable.ic_warning
                    },
                    0, 0, 0,
                )
                // The text stays off the call screen (it is the first line of the call info sheet);
                // only the small lock shows, red when the identity does not match.
                callSecurityChip.visibility = View.GONE
                callLockButton.visibility = View.VISIBLE
                callLockButton.contentDescription = callSecurityChip.contentDescription
                callLockButton.setImageResource(if (mismatch) R.drawable.ic_warning else R.drawable.ic_lock)
                callLockButton.setBackgroundResource(
                    if (mismatch) R.drawable.mismatch_banner_background else R.drawable.call_overlay_background,
                )
                if (mismatch && !isFinishing && !isDestroyed) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.call_mismatch_title)
                        .setMessage(R.string.call_mismatch_message)
                        .setPositiveButton(R.string.action_close, null)
                        .show()
                }
            }
        }.start()
    }

    /** Link health: connection, route, requested vs current video, security; read-only. */
    private fun showLinkHealthSheet() {
        val endpoint = privateCall ?: return
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_link_health, null)
        val settings = privateCallSettings
        val limitBps = privateCallLinkRateLimitBps
        val rows = buildList {
            callSecurityChip.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            add(getString(R.string.link_health_connection, currentCallTransport.shortLabel))
            callProfileText.text?.toString()?.takeIf { it.isNotBlank() }?.let { addAll(it.lines()) }
            if (settings != null) {
                add(
                    getString(
                        R.string.link_health_video,
                        settings.resolution.width,
                        settings.resolution.height,
                        settings.fps,
                        settings.bitRate / 1_000,
                    ),
                )
                if (limitBps != null) add(getString(R.string.link_health_limit, limitBps / 1_000))
            }
            privateCallActiveAudioBitRate?.let { add(getString(R.string.link_health_audio, it / 1_000)) }
            if (privateCallLinkDetails.isNotEmpty()) {
                add("")
                add(getString(R.string.link_health_details))
                addAll(privateCallLinkDetails)
            }
        }
        view.findViewById<TextView>(R.id.linkHealthRows).text = rows.joinToString("\n")
        view.findViewById<View>(R.id.linkHealthVerify).setOnClickListener {
            sheet.dismiss()
            openCallVerification()
        }
        view.findViewById<View>(R.id.linkHealthCopy).setOnClickListener {
            val context = applicationContext
            val callText = rows.joinToString("\n")
            Thread {
                val report = runCatching { DiagnosticsUiBackend.current.build(context) }.getOrDefault("")
                runOnUiThread {
                    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            getString(R.string.help_export_subject),
                            "$report\ncall:\n$callText",
                        ),
                    )
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        if (privateCall === endpoint) {
            sheet.setContentView(view)
            sheet.show()
        }
    }

    private fun openCallVerification() {
        val peer = privateCall?.endToEndPeerNodeId ?: return
        startActivity(VerifyContactActivity.intent(this, peer, privateCallPartyName, inCall = true))
    }

    /** Caller card before media: [incoming] ringing or this phone calling. */
    private fun showCallParty(name: String, stateRes: Int) {
        if (!::callPartyPanel.isInitialized) return
        privateCallPartyName = name
        callPartyName.text = name
        callPartyState.setText(stateRes)
        callPartyPanel.visibility = View.VISIBLE
        // One name line and one state line, like a phone app: no extra status bubble.
        callStatusText.visibility = View.GONE
    }

    /** Naming design 3.4: the caller's optional signed subject, under the name while ringing. */
    private fun showCallSubject(subject: String?) {
        findViewById<TextView>(R.id.callPartySubject).apply {
            text = subject
            visibility = if (subject.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    /** On air: the live dot blinks once a second (the one deliberate motion; off with animations off). */
    private var liveDotAnimator: android.animation.ObjectAnimator? = null

    private fun setLiveDotBlinking(on: Boolean) {
        if (!::connectionStatusDot.isInitialized) return
        if (on && liveDotAnimator == null) {
            liveDotAnimator = android.animation.ObjectAnimator.ofFloat(connectionStatusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 500L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else if (!on) {
            liveDotAnimator?.cancel()
            liveDotAnimator = null
            connectionStatusDot.alpha = 1f
        }
    }

    private fun hideCallParty() {
        if (::callPartyPanel.isInitialized) callPartyPanel.visibility = View.GONE
    }

    /** Live/watch chip on the shared video screen; the dot keeps its earlier colour and description. */
    private fun updateBroadcastStatusChip() {
        if (!::broadcastStatusText.isInitialized) return
        val streaming = streamArmed || sender != null || broadcaster != null
        val state = liveChipState(sessionRole.name, streaming, streamPaused, lastConnectedCount)
        broadcastStatusText.text = when (state) {
            LiveChipState.NOT_LIVE -> getString(R.string.live_status_off)
            LiveChipState.LIVE -> getString(R.string.live_status_on, lastConnectedCount)
            LiveChipState.PAUSED -> getString(R.string.live_status_paused, lastConnectedCount)
            LiveChipState.WATCH_WAITING -> getString(R.string.watch_status_waiting)
            LiveChipState.WATCHING -> when {
                forwardReceivedStreams -> {
                    val battery = getSystemService(android.os.BatteryManager::class.java)
                        ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        ?.takeIf { it in 0..100 }
                    if (battery != null) getString(R.string.watch_status_relaying_battery, battery)
                    else getString(R.string.watch_status_relaying)
                }
                else -> {
                    val relays = selectedStreamPeer?.announcement?.routeCost?.let(::advertisedRelayCount) ?: 0
                    if (relays > 0) getString(R.string.watch_status_relayed, relays) else getString(R.string.watch_status_direct)
                }
            }
            LiveChipState.RELAY_WAITING -> getString(R.string.relay_status_off)
            LiveChipState.RELAYING -> getString(R.string.relay_status_on)
        }
        setLiveDotBlinking(state == LiveChipState.LIVE)
        val chip = broadcastStatusText.parent as? View
        chip?.setBackgroundResource(
            if (state == LiveChipState.LIVE) R.drawable.live_chip_background else R.drawable.call_overlay_background,
        )
        // Watching: whose stream this is (authenticated source) and whether it is verified.
        val source = activeStreamSourceNodeId?.toString()
        if (state == LiveChipState.WATCHING && source != null) {
            val cached = streamSourceVerification?.takeIf { it.first == source }?.second
            if (cached == null) {
                if (streamSourceLookup != source) {
                    streamSourceLookup = source
                    val context = applicationContext
                    Thread {
                        val result = runCatching { SecurityUiBackend.current.verificationState(context, source) }
                            .getOrDefault(ContactVerification.UNVERIFIED)
                        runOnUiThread {
                            streamSourceLookup = null
                            streamSourceVerification = source to result
                            if (!isDestroyed) updateBroadcastStatusChip()
                        }
                    }.start()
                }
                streamSecurityText.visibility = View.GONE
                return
            }
            val verification = cached
            val label = getString(
                when (verification) {
                    ContactVerification.VERIFIED -> R.string.verification_verified
                    ContactVerification.UNVERIFIED -> R.string.verification_unverified
                    ContactVerification.MISMATCH -> R.string.verification_mismatch
                },
            )
            // Encryption is always on: only a lock (tap for details); words appear only on a problem.
            val mismatch = verification == ContactVerification.MISMATCH
            streamSecurityText.text = if (mismatch) getString(R.string.verification_mismatch) else ""
            streamSecurityText.contentDescription = getString(R.string.stream_security, label)
            streamSecurityText.setBackgroundResource(
                if (mismatch) R.drawable.mismatch_banner_background else R.drawable.call_overlay_background,
            )
            streamSecurityText.setOnClickListener { showStreamInfo() }
            streamSecurityText.visibility = View.VISIBLE
        } else {
            streamSecurityText.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Experimental CallStyle Answer (CallNotificationActions); a token-less intent is ignored.
        if (intent.hasExtra(CallNotificationActions.EXTRA_ACTION)) {
            onCallNotificationAction(intent)
            return
        }
        if (!BuildConfig.DEBUG) return
        if (debugEmergencyWipeAuthorized(intent)) {
            EmergencyWipe.wipe(this, WipeReason.DEBUG_CONTROL)
            return
        }
        val control = intent.getStringExtra("test_control") ?: return
        val pendingControl = ++debugControlSequence to System.nanoTime()
        if (debugMediaTraceSession != null) Log.i("BluetoothVideo", "BENCHMARK lifecycle v1 " + JSONObject()
            .put("session", debugMediaTraceSession).put("pid", android.os.Process.myPid())
            .put("clock", "System.nanoTime").put("action_id", pendingControl.first).put("action", control)
            .put("kind", "command").put("command_ns", pendingControl.second).toString())
        if (control == "invite" || control.startsWith("call:") ||
            control == "invite_voice" || control.startsWith("voice_call:") || control.startsWith("sf_sync:")
        ) {
            // onResume restarts discovery and clears its old selection. Queue after that restart.
            // invite_voice / voice_call:PREFIX are the experimental voice-call variants.
            pendingDebugPrivateCallInvite = control to if (control == "invite" || control == "invite_voice") {
                intent.getStringExtra("test_call_peer")
            } else control.substringAfter(':')
            debugPendingControl = pendingControl
            return
        }
        val applied = when (control) {
            "pause" -> if (broadcaster != null && !streamPaused) { toggleBroadcastPause(); true } else false
            "resume" -> if (broadcaster != null && streamPaused) { toggleBroadcastPause(); true } else false
            "flip" -> if (debugRawInputFile != null) false
                else if (privateCallPhase == PrivateCallPhase.ACTIVE && privateCallCameraEnabled &&
                privateCallCameraEncoder != null && availableCameraLenses.size > 1
            ) { switchPrivateCallCamera(); true }
                else if (cameraEncoder != null) { switchCamera(); true } else false
            "accept" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.INCOMING_RINGING &&
                ensureThermalReady()
            ) {
                stopPrivateCallRinging()
                runCatching { privateCall?.accept() }.isSuccess
            } else false
            "decline" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.INCOMING_RINGING) {
                stopPrivateCallRinging()
                runCatching { privateCall?.decline() }.isSuccess
            } else false
            "cancel" -> if (privateCall != null && privateCallPhase in setOf(
                    PrivateCallPhase.OUTGOING_INVITE, PrivateCallPhase.OUTGOING_RINGING,
                )
            ) { stopPrivateCallRinging(); privateCall?.cancel(); true } else false
            "mute", "unmute" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.ACTIVE &&
                privateCallMuted != (control == "mute")
            ) { togglePrivateCallMute(); true } else false
            "video_off", "video_on" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.ACTIVE &&
                privateCallCameraEnabled != (control == "video_on")
            ) { togglePrivateCallCamera(); true } else false
            "speaker", "earpiece" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.ACTIVE &&
                privateCallAudioSession?.speakerOn == (control == "earpiece")
            ) togglePrivateCallSpeaker() else false
            "ptt_press", "ptt_release" -> onPrivateCallPushToTalk(control == "ptt_press", "debug")
            // Experimental store-and-forward (needs test_store_forward): pause a running exchange,
            // accept every pending card, or queue a generated message (sf_send:PREFIX12:BYTES).
            "sf_suspend" -> StoreForwardFeature.enabled && privateCall?.suspendMessageSync() == true
            "sf_accept_cards" -> StoreForwardFeature.enabled && debugAcceptStoreForwardCards()
            "background" -> if (appScreen == AppScreen.CALL && privateCallPhase == PrivateCallPhase.ACTIVE) {
                moveTaskToBack(true)
            } else false
            "foreground" -> appScreen == AppScreen.CALL && privateCallPhase == PrivateCallPhase.ACTIVE
            "end" -> if (privateCall != null && privateCallPhase in setOf(
                    PrivateCallPhase.NEGOTIATING_MEDIA, PrivateCallPhase.ACTIVE,
                )
            ) { stopPrivateCallRinging(); privateCall?.hangUp(); true }
                else if (broadcaster != null) { stopStreaming("Stopped."); true } else false
            // Graceful exit before a runner force-stop: onDestroy closes the call endpoint, CoC
            // listeners and discovery. It does not stop the Android 10 LE CoC leak: that stack
            // never stops a closed LE CoC server, gracefully closed or killed ("Error starting
            // l2cap_listen", bmaraw-ab-call-e9c421d9-r1); CocListenerHub bounds it per role.
            "shutdown" -> {
                stopPrivateCallRinging()
                privateCall?.hangUp()
                finishAndRemoveTask()
                true
            }
            else -> control.startsWith("sf_send:") && StoreForwardFeature.enabled && debugStoreForwardSend(control)
        }
        Log.i("BluetoothVideo", "BENCHMARK control $control ${if (applied) "applied" else "rejected"}")
        logDebugControlCompletion(control, applied, pendingControl)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!BuildConfig.DEBUG) return
        val (control, peerPrefix) = pendingDebugPrivateCallInvite ?: return
        pendingDebugPrivateCallInvite = null
        val voice = control == "invite_voice" || control.startsWith("voice_call:")
        val applied = debugInvitePrivateCall(
            peerPrefix,
            if (voice) CallType.VOICE else CallType.VIDEO,
            messageSync = control.startsWith("sf_sync:"),
        )
        Log.i("BluetoothVideo", "BENCHMARK control $control ${if (applied) "applied" else "rejected"}")
        debugPendingControl?.let { logDebugControlCompletion(control, applied, it) }
        debugPendingControl = null
    }

    private fun debugInvitePrivateCall(
        requestedPrefix: String?,
        callType: CallType = CallType.VIDEO,
        messageSync: Boolean = false,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (callType == CallType.VOICE && !IntercomFeatures.current.voiceCalls) return false
        if (messageSync && !StoreForwardFeature.enabled) return false
        // Experimental automatic sync: a requested call or exchange replaces a pending automatic one,
        // and a running automatic exchange is suspended (this request is rejected; retry after it stops).
        autoSyncPendingKey?.let { cancelAutoSync(it, "debug_invite") }
        if (StoreForwardAutoSyncFeature.enabled && privateCall?.suspendMessageSync() == true) return false
        val prefix = requestedPrefix?.lowercase(Locale.US)
        val peer = prefix?.takeIf { it.matches(Regex("[0-9a-f]{12}")) }?.let { requested ->
            privateCallPeers.singleOrNull { it.announcement.nodeIdPrefix.toHexKey() == requested }
        }
        if (appScreen == AppScreen.CALL_BROWSER && privateCall != null &&
            !privateCallAvailabilityStarting && privateCallPhase == null &&
            pendingPrivateCallPeerKey == null && peer != null && ensureThermalReady()
        ) {
            startOutgoingPrivateCall(peer, callType, messageSync)
            return true
        }
        return false
    }

    /** DEBUG: accepts every pending store-and-forward card and opens messages that waited for it. */
    private fun debugAcceptStoreForwardCards(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        val pending = node.contacts.all().filter { it.state == ContactState.PENDING }
        pending.forEach { contact ->
            if (runCatching { node.contacts.accept(contact.nodeId, "") }.isFailure) return@forEach
            val opened = node.openPendingFrom(contact.nodeId)
            Log.i("BluetoothVideo", "BENCHMARK store_forward accepted node=${contact.nodeId} opened=${opened.size}")
        }
        return pending.isNotEmpty()
    }

    /** DEBUG: `sf_send:PREFIX12:BYTES` queues a generated message of BYTES to the accepted contact. */
    private fun debugStoreForwardSend(control: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val parts = control.split(':')
        val prefix = parts.getOrNull(1)?.lowercase(Locale.US) ?: return false
        val size = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..BundleMessage.MAX_BODY_BYTES } ?: return false
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        val contact = node.contacts.accepted().singleOrNull { it.nodeId.toString().startsWith(prefix) } ?: return false
        val kind = when {
            size <= 2_000 -> MessageKind.TEXT
            size <= 400_000 -> MessageKind.PHOTO
            else -> MessageKind.VIDEO
        }
        val body = if (kind == MessageKind.TEXT) {
            ByteArray(size) { ('a'.code + it % 26).toByte() }
        } else {
            ByteArray(size).also(java.security.SecureRandom()::nextBytes)
        }
        val stored = runCatching {
            node.send(
                contact.nodeId,
                BundleMessage(
                    kind, System.currentTimeMillis(), ByteArray(16), null,
                    if (kind == MessageKind.TEXT) "text/plain" else "application/octet-stream", body,
                ),
            )
        }.getOrElse {
            Log.w("BluetoothVideo", "BENCHMARK store_forward send failed: ${it.message}")
            return false
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK store_forward queued id=${stored.idHex} kind=${kind.label} bytes=$size " +
                "chunks=${stored.chunkCount} to=${contact.nodeId}",
        )
        return true
    }

    private fun applyDebugLaunchConfiguration() {
        if (!BuildConfig.DEBUG) return
        require(debugRawInputFile == null || debugReplayPath == null) {
            "Raw encoder motion and preencoded IVF replay are separate benchmark sources"
        }
        debugRawInputFile?.let { Log.i("BluetoothVideo", "Test raw encoder input: ${it.path}; physical camera motion unmeasured") }
        val autoStart = intent.getBooleanExtra("test_auto_start", false)
        val autoBroadcast = debugAutoBroadcastRequested
        val autoSelect = debugAutoSelectStream
        if (!autoStart && !autoBroadcast && !autoSelect && !debugPrivateCallBrowser &&
            requestedTransportExperiment == null
        ) return
        debugAutoStartPending = autoStart || autoBroadcast
        if (intent.hasExtra("test_thermal_reduction")) {
            automaticThermalReductionEnabled = intent.getBooleanExtra(
                "test_thermal_reduction",
                automaticThermalReductionEnabled,
            )
        }
        if (intent.hasExtra("test_forward_received_streams")) {
            forwardReceivedStreams = intent.getBooleanExtra(
                "test_forward_received_streams",
                forwardReceivedStreams,
            )
        }
        if (autoStart || autoBroadcast || autoSelect) {
            sessionRole = when (intent.getStringExtra("test_role")) {
                "receive" -> SessionRole.RECEIVE
                "relay" -> SessionRole.RELAY
                else -> SessionRole.SEND
            }
        }
        debugTransportOverride(intent.getStringExtra("test_transport"))?.let { outgoingTransport = it }
        val bitRateKbps = intent.getIntExtra(
            "test_bitrate_kbps",
            settings.bitRate / 1_000,
        ).coerceIn(VideoSettings.MIN_BIT_RATE_KBPS, outgoingTransport.maxVideoBitrateKbps)
        val width = intent.getIntExtra("test_width", settings.resolution.width).coerceAtLeast(1)
        val height = intent.getIntExtra("test_height", settings.resolution.height).coerceAtLeast(1)
        val fps = intent.getIntExtra("test_fps", settings.fps).coerceIn(1, 60)
        settings = settings.copy(
            resolution = VideoResolution(width, height, "${height}p"),
            fps = fps,
            bitRate = bitRateKbps * 1_000,
        )
        intent.getStringExtra("test_playback_delay")?.let { requested ->
            receiverPlaybackDelay = PlaybackDelayProfile.fromStoredValue(requested)
        }
        Log.i(
            "BluetoothVideo",
            "Test launch: ${sessionRole.label}, ${outgoingTransport.shortLabel}, ${width}x$height @ $fps, $bitRateKbps kbps",
        )
    }

    private fun applyDebugCodecSelection() {
        if (!BuildConfig.DEBUG || (sessionRole != SessionRole.SEND && !debugPrivateCallBrowser)) return
        intent.getStringExtra("test_codec")?.let { requested ->
            val codecIndex = codecs.indexOfFirst { codec ->
                when (requested.lowercase()) {
                    "av1" -> codec.protocolId == StreamProtocol.CODEC_AV1
                    "vp9" -> codec.protocolId == StreamProtocol.CODEC_VP9
                    else -> codec.encoderName.equals(requested, ignoreCase = true)
                }
            }
            require(codecIndex >= 0) { "Requested benchmark encoder is unavailable: $requested" }
            codecSpinner.setSelection(codecIndex, false)
            refreshVideoModes()
            Log.i("BluetoothVideo", "Test encoder: ${codecs[codecIndex].displayName} / ${codecs[codecIndex].encoderName}")
        }
        intent.getStringExtra("test_rate_control")?.let { requested ->
            val control = when (requested.lowercase()) {
                "automatic" -> EncoderRateControl.AUTOMATIC
                "cbr" -> EncoderRateControl.CBR
                "vbr" -> EncoderRateControl.VBR
                "cbr_fd", "cbr-fd" -> EncoderRateControl.CBR_FD
                else -> error("Unknown benchmark bitrate allocation: $requested")
            }
            val codec = requireNotNull(codecs.getOrNull(codecSpinner.selectedItemPosition)) {
                "No encoder is available for benchmark bitrate allocation"
            }
            require(control in codec.availableRateControls()) {
                "Requested benchmark bitrate allocation $requested is unavailable for ${codec.encoderName}"
            }
            // The temporary default encoder's picker must not overwrite the requested encoder's allocation.
            // A manual allocation is an Advanced-mode request for this session (not persisted).
            advancedSettings = control != EncoderRateControl.AUTOMATIC
            encoderRateControl = control
            refreshRateControlPicker()
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder allocation selection: requested=${control.name} | " +
                    "encoder=${codec.encoderName} | selected=${encoderRateControl.name} | " +
                    "mode=${codec.resolveBitrateMode(encoderRateControl)}",
            )
        }
    }

    private fun usesBroadcastDiscovery(): Boolean =
        sessionRole == SessionRole.SEND && (!debugLaunchRequested || debugAutoBroadcastRequested)

    private fun configureTransport() {
        transportSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            BluetoothTransportOption.entries.map(BluetoothTransportOption::label),
        )
        transportSpinner.setSelection(outgoingTransport.ordinal, false)
        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = BluetoothTransportOption.entries[position]
                if (outgoingTransport != selected) {
                    stopStreamPresence()
                    bleDiscovery?.close()
                    bleDiscovery = null
                    blePeers = emptyList()
                    outgoingTransport = selected
                    applyTransportBitrateLimit(persist = true)
                    if (!debugLaunchRequested && requestedTransportExperiment == null) {
                        appPreferences.edit()
                            .putString(PREF_OUTGOING_TRANSPORT, selected.name)
                            .apply()
                    }
                }
                destinationLabel.text = when {
                    sessionRole == SessionRole.RELAY && debugRelayChildCount > 1 ->
                        "Next $debugRelayChildCount BLE receivers"
                    sessionRole == SessionRole.RELAY -> "Next BLE receiver"
                    outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM -> "Paired destination"
                    else -> "Active BLE receiver"
                }
                refreshButton.text = if (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM) {
                    "Refresh paired devices"
                } else {
                    "Scan for BLE receivers"
                }
                updateDestinationControlsVisibility()
                updateSettingsLabels()
                if (appScreen != AppScreen.HOME &&
                    (appScreen != AppScreen.SETTINGS || debugLaunchRequested)
                ) {
                    ensurePermissionsAndRefresh()
                }
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showHomeScreen(stopActive: Boolean = true, reachabilityDelayMs: Long = 0L) {
        if (stopActive) stopStreaming("Stopped.")
        resetReceiverRecoveryNotice()
        hideReceiverOverlay()
        // Always reachable (CallReachability): an idle endpoint from the Call screen keeps
        // advertising and listening on Home; only the people scan stops.
        val homePlan = CallReachability.plan(
            currentReachabilityPlanInputs(ReachabilityScreen.HOME),
        )
        if (homePlan.endpoint && privateCallEndpointIdle() && privateCallAvailable) {
            stopPrivateCallDiscovery()
            privateCallPeers = emptyList()
            privateCallPeersLastSeen.clear()
        } else {
            // A call that ended through Home reachability handed its service back (onEnded).
            stopPrivateCallEndpoint(
                keepService = homePlan.endpoint && privateCall == null && ActiveVideoSessionService.holdsConnectedDevice(),
            )
        }
        CocRegistrations.session.reset()
        cancelStreamReconnect()
        reconnectFromNodeIdPrefix = null
        preferredRecoveryParent = null
        selectedStreamPeer = null
        stopStreamPresence()
        stopNearbyStreamDiscovery()
        stopIdleCameraPreview()
        appScreen = AppScreen.HOME
        homeScreen.visibility = View.VISIBLE
        cameraScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        settingsBackCallback.isEnabled = false
        updateSystemBars(visible = true)
        updateHomeReadiness()
        updateHomeActions()
        updateRecentCalls()
        refreshCallReachability(reachabilityDelayMs)
    }

    private fun showStreamScreen() {
        stopPrivateCallEndpoint()
        stopNearbyStreamDiscovery()
        sessionRole = SessionRole.SEND
        showStreamCamera()
    }

    private fun showStreamCamera() {
        appScreen = AppScreen.CAMERA
        homeScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        cameraScreen.visibility = View.VISIBLE
        cameraViewfinder.visibility = if (debugRawInputFile == null) View.VISIBLE else View.GONE
        preview.visibility = View.GONE
        cameraSwitchButton.visibility = View.VISIBLE
        streamToggleButton.visibility = View.VISIBLE
        updatePauseButton()
        connectionStatusDot.visibility = View.VISIBLE
        videoBackButton.visibility = View.VISIBLE
        videoBackButton.contentDescription = "Back to Home"
        if (debugRawInputFile != null) {
            showReceiverOverlay("Automated motion test\nRecorded scene · camera inactive", showProgress = false)
        } else hideReceiverOverlay()
        settingsBackCallback.isEnabled = true
        updateConnectionDot(0)
        updateSystemBars(visible = false)
        ensurePermissionsAndRefresh()
        startIdleCameraPreview()
    }

    private fun showReceiverBrowser(cancelReconnect: Boolean = true) {
        stopPrivateCallEndpoint()
        finishReceiverContinuity()
        if (receiver != null || relay != null || privateCallRelay != null) stopStreaming("Stopped receiving.")
        if (cancelReconnect) {
            cancelStreamReconnect()
            reconnectFromNodeIdPrefix = null
            preferredRecoveryParent = null
            activeStreamTarget = null
            activeStreamSourceNodeId = null
            preserveReceiverContinuityOnNextFormat = false
            clearReceiverBrowserNotice()
        }
        selectedStreamPeer = null
        stopStreamPresence()
        stopIdleCameraPreview()
        sessionRole = SessionRole.RECEIVE
        appScreen = AppScreen.RECEIVER_BROWSER
        receiverBrowserStartedAtNs = System.nanoTime()
        receiverFirstOfferLogged = false
        homeScreen.visibility = View.GONE
        cameraScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.VISIBLE
        callBrowserScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        receiverBrowserTitle.setText(R.string.watch_title)
        if (::watchRelaySwitch.isInitialized) watchRelaySwitch.isChecked = forwardReceivedStreams
        hideReceiverOverlay()
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = true)
        ensurePermissionsAndRefresh()
    }

    private fun showPlaybackScreen() {
        appScreen = AppScreen.PLAYBACK
        homeScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        cameraScreen.visibility = View.VISIBLE
        cameraViewfinder.visibility = View.GONE
        preview.visibility = View.VISIBLE
        cameraSwitchButton.visibility = View.GONE
        streamToggleButton.visibility = View.GONE
        broadcastPauseButton.visibility = View.GONE
        connectionStatusDot.visibility = View.VISIBLE
        updateConnectionDot(0)
        videoBackButton.visibility = View.VISIBLE
        videoBackButton.contentDescription = "Back to nearby broadcasts"
        showReceiverOverlay("Connecting to stream", showProgress = true)
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = false)
    }

    private fun showDebugLaunchScreen() {
        when (sessionRole) {
            SessionRole.SEND -> {
                appScreen = AppScreen.CAMERA
                homeScreen.visibility = View.GONE
                receiverBrowserScreen.visibility = View.GONE
                callBrowserScreen.visibility = View.GONE
                callScreen.visibility = View.GONE
                settingsScreen.visibility = View.GONE
                cameraScreen.visibility = View.VISIBLE
                connectionStatusDot.visibility = View.VISIBLE
                videoBackButton.visibility = View.VISIBLE
                videoBackButton.contentDescription = "Back to Home"
                updateConnectionDot(0)
                if (debugRawInputFile != null) {
                    cameraViewfinder.visibility = View.GONE
                    showReceiverOverlay("Automated motion test\nRecorded scene · camera inactive", showProgress = false)
                }
            }
            SessionRole.RECEIVE -> showPlaybackScreen()
            SessionRole.RELAY -> {
                appScreen = AppScreen.CAMERA
                homeScreen.visibility = View.GONE
                receiverBrowserScreen.visibility = View.GONE
                callBrowserScreen.visibility = View.GONE
                callScreen.visibility = View.GONE
                settingsScreen.visibility = View.GONE
                cameraScreen.visibility = View.VISIBLE
                cameraViewfinder.visibility = View.GONE
                preview.visibility = View.GONE
                cameraSwitchButton.visibility = View.GONE
                broadcastPauseButton.visibility = View.GONE
                streamToggleButton.visibility = View.VISIBLE
                connectionStatusDot.visibility = View.VISIBLE
                videoBackButton.visibility = View.VISIBLE
                videoBackButton.contentDescription = "Back to Home"
                updateConnectionDot(0)
            }
        }
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = false)
    }

    private fun showSettings(show: Boolean) {
        if (show) {
            settingsReturnScreen = appScreen
            appScreen = AppScreen.SETTINGS
            homeScreen.visibility = View.GONE
            cameraScreen.visibility = if (settingsReturnScreen == AppScreen.CAMERA) View.VISIBLE else View.GONE
            receiverBrowserScreen.visibility = View.GONE
            callBrowserScreen.visibility = View.GONE
            callScreen.visibility = View.GONE
            settingsScreen.visibility = View.VISIBLE
            settingsScreen.post { settingsScreen.scrollTo(0, 0) }
            loadIdentityIntoSettings()
            updateDestinationControlsVisibility()
        } else {
            settingsScreen.visibility = View.GONE
            when (settingsReturnScreen) {
                AppScreen.HOME -> showHomeScreen(stopActive = false)
                AppScreen.CAMERA -> {
                    appScreen = AppScreen.CAMERA
                    cameraScreen.visibility = View.VISIBLE
                    if (usesBroadcastDiscovery()) {
                        ensurePermissionsAndRefresh()
                    }
                    settingsBackCallback.isEnabled = true
                    updateSystemBars(visible = false)
                }
                AppScreen.RECEIVER_BROWSER -> showReceiverBrowser()
                AppScreen.PLAYBACK -> showPlaybackScreen()
                AppScreen.CALL_BROWSER -> showPrivateCallBrowser()
                AppScreen.CALL -> Unit
                AppScreen.SETTINGS -> showHomeScreen(stopActive = false)
            }
        }
        if (show) {
            settingsBackCallback.isEnabled = true
            updateSystemBars(visible = true)
        }
    }

    private fun updateDestinationControlsVisibility() {
        if (!::destinationLabel.isInitialized) return
        val directSendTest = BuildConfig.DEBUG &&
            intent.getBooleanExtra("test_auto_start", false) &&
            !debugAutoBroadcastRequested &&
            sessionRole == SessionRole.SEND
        destinationGroup.visibility = if (
            directSendTest || sessionRole == SessionRole.RELAY
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun switchCamera() {
        if (debugRawInputFile != null) return
        if (sender != null || broadcaster != null || receiver != null || relay != null ||
            privateCallRelay != null ||
            cameraEncoder != null
        ) return
        val currentIndex = availableCameraLenses.indexOf(cameraLens)
        cameraLens = availableCameraLenses[(currentIndex + 1) % availableCameraLenses.size]
        refreshVideoModes()
        setStatus("${cameraLens.displayName} camera selected.")
        updateCameraSwitchButton()
    }

    private fun updateCameraSwitchButton() {
        if (!::cameraSwitchButton.isInitialized) return
        val idle = sender == null && broadcaster == null && receiver == null && relay == null &&
            privateCallRelay == null &&
            cameraEncoder == null && !localTestRunning
        cameraSwitchButton.visibility = if (
            sessionRole == SessionRole.SEND && availableCameraLenses.size > 1 && debugRawInputFile == null
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        cameraSwitchButton.isEnabled = idle
        val nextLens = availableCameraLenses.firstOrNull { it != cameraLens }
        cameraSwitchButton.contentDescription = nextLens?.let { "Switch to ${it.displayName.lowercase()} camera" }
    }

    private fun updateSystemBars(visible: Boolean) {
        WindowCompat.getInsetsController(window, findViewById(R.id.main)).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Light screens get dark bar icons in light mode; video screens keep light icons.
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            // Monochrome dark in every mode: light bar icons always (night kept for clarity).
            isAppearanceLightStatusBars = visible && !night && resources.getBoolean(R.bool.light_system_bars)
            isAppearanceLightNavigationBars = visible && !night && resources.getBoolean(R.bool.light_system_bars)
            if (visible) {
                show(WindowInsetsCompat.Type.systemBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun startDebugBenchmark(run: (DebugBenchmarkCoordinator) -> Unit) {
        if (!BuildConfig.DEBUG || localTestRunning) return
        localTestRunning = true
        updateStartEnabled()
        lateinit var coordinator: DebugBenchmarkCoordinator
        coordinator = DebugBenchmarkCoordinator(this, ::setStatus) {
            runOnUiThread {
                if (debugBenchmarkCoordinator === coordinator) debugBenchmarkCoordinator = null
                localTestRunning = false
                updateStartEnabled()
            }
        }
        debugBenchmarkCoordinator?.close()
        debugBenchmarkCoordinator = coordinator
        run(coordinator)
    }

    private fun configureSettingsControls() {
        val scaleModeLabels = VideoScaleMode.entries.map(VideoScaleMode::label)
        videoScaleModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            scaleModeLabels,
        )
        videoScaleModeSpinner.setSelection(
            VideoScaleMode.entries.indexOf(videoScaleMode),
            false,
        )
        videoScaleModeDescriptionText.text = videoScaleMode.description
        videoScaleModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = VideoScaleMode.entries.getOrNull(position) ?: return
                videoScaleMode = selected
                videoScaleModeDescriptionText.text = selected.description
                appPreferences.edit()
                    .putString(PREF_VIDEO_SCALE_MODE, selected.name)
                    .apply()
                applyVideoScaleMode()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        deviceHealthOverlaySwitch.isChecked = showDeviceHealthOverlay
        deviceHealthOverlaySwitch.setOnCheckedChangeListener { _, enabled ->
            showDeviceHealthOverlay = enabled
            appPreferences.edit().putBoolean(PREF_SHOW_DEVICE_HEALTH, enabled).apply()
            applyDeviceHealthOverlayVisibility()
        }
        forwardReceivedStreamsSwitch.isChecked = forwardReceivedStreams
        forwardReceivedStreamsSwitch.setOnCheckedChangeListener { _, enabled ->
            forwardReceivedStreams = enabled
            appPreferences.edit().putBoolean(PREF_FORWARD_RECEIVED_STREAMS, enabled).apply()
            if (::watchRelaySwitch.isInitialized && watchRelaySwitch.isChecked != enabled) watchRelaySwitch.isChecked = enabled
        }
        receiverPlaybackDelaySlider.value = receiverPlaybackDelay.sliderValue
        experimentalTemporalLayersSwitch.isChecked =
            appPreferences.getBoolean(PREF_EXPERIMENTAL_TEMPORAL_LAYERS, false)
        experimentalTemporalLayersSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(PREF_EXPERIMENTAL_TEMPORAL_LAYERS, enabled).apply()
            applyExperimentalTemporalLayers()
        }
        relayChildMediaAgeSwitch.isChecked = appPreferences.getBoolean(PREF_RELAY_CHILD_MEDIA_AGE, true)
        relayChildMediaAgeSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(PREF_RELAY_CHILD_MEDIA_AGE, enabled).apply()
            applyRelayChildMediaAge()
        }
        listOf(
            experimentalVoiceCallsSwitch to IntercomFeatureSettings.PREF_VOICE_CALLS,
            experimentalPushToTalkSwitch to IntercomFeatureSettings.PREF_PUSH_TO_TALK,
            experimentalPttModeSwitch to IntercomFeatureSettings.PREF_PTT_MODE,
            experimentalStoreForwardSwitch to StoreForwardFeature.PREF,
            experimentalPeerLabelsSwitch to PeerLabelFeature.PREF,
        ).forEach { (switch, key) ->
            switch.isChecked = appPreferences.getBoolean(key, false)
            switch.setOnCheckedChangeListener { _, enabled ->
                appPreferences.edit().putBoolean(key, enabled).apply()
                applyIntercomFeatures()
            }
        }
        findViewById<MaterialSwitch>(R.id.reachableForCallsSwitch).apply {
            isChecked = appPreferences.getBoolean(PREF_REACHABLE_FOR_CALLS, true)
            setOnCheckedChangeListener { _, enabled ->
                appPreferences.edit().putBoolean(PREF_REACHABLE_FOR_CALLS, enabled).apply()
                applyCallReachability()
                refreshCallReachability()
            }
        }
        experimentalCocAutoGattSwitch.isChecked = appPreferences.getBoolean(CocAutoGattSettings.PREF_ENABLED, false)
        experimentalCocAutoGattSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(CocAutoGattSettings.PREF_ENABLED, enabled).apply()
            applyCocAutoGatt()
        }
        experimentalClassicBondFreeSwitch.isChecked =
            appPreferences.getBoolean(ClassicFeatureSettings.PREF_BOND_FREE, false)
        experimentalClassicBondFreeSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(ClassicFeatureSettings.PREF_BOND_FREE, enabled).apply()
            applyClassicFeatures()
        }
        experimentalCompactDiscoverySwitch.isChecked = appPreferences.getBoolean(CompactDiscoveryFeature.PREF, false)
        experimentalCompactDiscoverySwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(CompactDiscoveryFeature.PREF, enabled).apply()
            applyCompactDiscovery()
        applyRotatingIds()
        }
        experimentalRotatingIdsSwitch.isChecked = appPreferences.getBoolean(RotatingDiscoveryFeature.PREF, false)
        experimentalRotatingIdsSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(RotatingDiscoveryFeature.PREF, enabled).apply()
            applyRotatingIds()
        }
        rotatingIdsVisibleButton.setOnClickListener {
            if (!RotatingDiscoveryFeature.enabled) {
                Toast.makeText(this, R.string.rotating_ids_visible_off, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val endsAt = RotatingDiscovery.startVisibleByHandle()
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(endsAt))
            Toast.makeText(this, getString(R.string.rotating_ids_visible_until, time), Toast.LENGTH_LONG).show()
        }
        experimentalOfflineAppShareSwitch.isChecked = appPreferences.getBoolean(OfflineAppShareFeature.PREF, false)
        experimentalOfflineAppShareSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(OfflineAppShareFeature.PREF, enabled).apply()
            applyOfflineAppShare()
        }
        advancedSettingsSwitch.isChecked = advancedSettings
        applySettingsModeVisibility()
        advancedSettingsSwitch.setOnCheckedChangeListener { _, enabled ->
            advancedSettings = enabled
            appPreferences.edit().putBoolean(PREF_ADVANCED_SETTINGS, enabled).apply()
            applySettingsModeValues()
            applyExperimentalTemporalLayers()
            applyRelayChildMediaAge()
            applyIntercomFeatures()
            applyCocAutoGatt()
            applyClassicFeatures()
            applyCompactDiscovery()
        applyRotatingIds()
            applyOfflineAppShare()
            receiverPlaybackDelaySlider.value = receiverPlaybackDelay.sliderValue
            refreshRateControlPicker()
            applySettingsModeVisibility()
            updatePrivateCallProfileSummary()
            updateSettingsLabels()
        }
        receiverPlaybackDelaySlider.addOnChangeListener { _, value, _ ->
            val selected = PlaybackDelayProfile.fromSliderValue(value)
            if (selected == receiverPlaybackDelay) return@addOnChangeListener
            receiverPlaybackDelay = selected
            updatePlaybackDelayLabels()
            updatePrivateCallProfileSummary()
            appPreferences.edit()
                .putString(PREF_RECEIVER_PLAYBACK_DELAY, selected.name)
                .apply()
        }
        thermalReductionSwitch.isChecked = automaticThermalReductionEnabled
        thermalReductionSwitch.setOnCheckedChangeListener { _, enabled ->
            automaticThermalReductionEnabled = enabled
            appPreferences.edit()
                .putBoolean(PREF_AUTOMATIC_THERMAL_REDUCTION, enabled)
                .apply()
            thermalHandler.removeCallbacks(thermalRecoveryRunnable)
            if (activeStreamSettings != null) {
                applyThermalAdaptation(thermalStatus)
                setStatus(
                    if (enabled) {
                        "Automatic cooling enabled for hot conditions."
                    } else {
                        "Automatic cooling disabled. Critical heat will still stop streaming."
                    },
                )
            }
        }
        rateControlSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingRateControlPicker) return
                encoderRateControl = rateControlOptions.getOrNull(position)
                    ?: EncoderRateControl.AUTOMATIC
                if (advancedSettings && encoderRateControl != EncoderRateControl.AUTOMATIC) {
                    appPreferences.edit()
                        .putString(PREF_MANUAL_RATE_CONTROL, encoderRateControl.name)
                        .apply()
                }
                updateRateControlDescription()
                updateSettingsLabels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingModePickers) return
                val mode = deviceModes.getOrNull(position) ?: return
                settings = settings.copy(resolution = mode.resolution)
                persistVideoSettings()
                updatingModePickers = true
                refreshFpsPicker(settings.fps)
                updatingModePickers = false
                updateSettingsLabels()
                restartIdleCameraPreview()
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingModePickers) return
                val mode = selectedDeviceMode() ?: return
                val fps = mode.fpsOptions.getOrNull(position) ?: return
                settings = settings.copy(fps = fps)
                persistVideoSettings()
                updateSettingsLabels()
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        configureSeekBar(
            bitrateSeekBar,
            (bitrateSliderMaximumKbps() - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS,
            (settings.bitRate / 1_000 - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS,
            { progress ->
                val kbps = VideoSettings.MIN_BIT_RATE_KBPS + progress * VideoSettings.BIT_RATE_STEP_KBPS
                settings = settings.copy(bitRate = kbps * 1_000)
                updateSettingsLabels()
            },
            {
                refreshVideoModes()
                persistVideoSettings()
            },
        )
        updateSettingsLabels()
    }

    /**
     * Experimental temporal layering (off by default; see DebugTunables.experimentalTemporalLayers).
     * A DEBUG benchmark launch (any `test_` extra) uses only its `test_temporal_layers` extra, default
     * off, so a stored UI choice never leaks into a measured case. Otherwise the persisted Advanced
     * setting applies, and only while Advanced settings are on. Read when a stream starts.
     */
    private fun applyExperimentalTemporalLayers() {
        applyExperimentalTemporalLayersOnly()
    }

    /**
     * Relay-child skip-to-keyframe (on by default; DebugTunables.experimentalRelayChildMediaAge). A
     * DEBUG benchmark launch uses only its `test_relay_child_media_age` extra (default on). Otherwise
     * the Advanced setting applies in any build, and the default (on) applies while Advanced settings
     * are off. Read when forwarding starts.
     */
    private fun applyRelayChildMediaAge() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        DebugTunables.experimentalRelayChildMediaAge = relayChildMediaAgeSetting(
            benchmarkLaunch = benchmarkLaunch,
            benchmarkExtra = intent.getBooleanExtra("test_relay_child_media_age", true),
            advancedSettings = advancedSettings,
            storedChoice = appPreferences.getBoolean(PREF_RELAY_CHILD_MEDIA_AGE, true),
        )
    }

    private fun applyExperimentalTemporalLayersOnly() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        DebugTunables.experimentalTemporalLayers = if (benchmarkLaunch) {
            intent.getBooleanExtra("test_temporal_layers", false)
        } else {
            advancedSettings && appPreferences.getBoolean(PREF_EXPERIMENTAL_TEMPORAL_LAYERS, false)
        }
    }

    /**
     * Experimental voice calls and push-to-talk (off by default; see IntercomFeatureSettings). Same
     * rule as temporal layering: a DEBUG benchmark launch uses only its `test_voice_calls`,
     * `test_push_to_talk` and `test_ptt_mode` extras; otherwise the Advanced settings apply. Read
     * when the call endpoint is created and when a call's media starts.
     */
    private fun applyIntercomFeatures() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val resolved = IntercomFeatureSettings.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        if (resolved != IntercomFeatures.current && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK intercom_features: ${IntercomFeatures.label(resolved)}")
        }
        IntercomFeatures.current = resolved
        applyStoreForwardFeature(benchmarkLaunch)
        applyPeerLabelFeature(benchmarkLaunch)
        updateHomeActions()
    }

    /**
     * Experimental peer labels (PeerLabelFeature, default off): same launch rule as the intercom
     * switches (`test_peer_labels`). A DEBUG benchmark launch may also give `test_peer_label_name`,
     * `test_call_subject` and `test_stream_title` (strings) so phone cases need no typing. Off: no
     * hello bit, no LABELS record, no title pages, and no name field.
     */
    private fun applyPeerLabelFeature(benchmarkLaunch: Boolean) {
        val enabled = PeerLabelFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        if (enabled != PeerLabelFeature.enabled) {
            Log.i("BluetoothVideo", "BENCHMARK peer_labels_feature: enabled=$enabled")
        }
        PeerLabelFeature.enabled = enabled
        val storedName = if (benchmarkLaunch) {
            intent.getStringExtra("test_peer_label_name")
        } else {
            appPreferences.getString(PeerLabelFeature.PREF_NAME, null)
        }
        PeerLabelFeature.localName = storedName?.let { runCatching { LabelText.normalize(LabelKind.PERSON_NAME, it) }.getOrNull() }
        if (benchmarkLaunch) {
            PeerLabelFeature.pendingCallSubject = intent.getStringExtra("test_call_subject")
            PeerLabelFeature.localStreamTitle = intent.getStringExtra("test_stream_title")
                ?.let { runCatching { LabelText.normalize(LabelKind.STREAM_TITLE, it) }.getOrNull() }
        }
        if (!enabled) {
            SessionPeerLabels.clearAll()
            StreamTitles.board.clear()
        }
        refreshIdentityName()
    }

    /** Settings > Your ID: the optional name button (experimental peer labels only). */
    private fun refreshIdentityName() {
        val button = findViewById<com.google.android.material.button.MaterialButton>(R.id.identityNameButton) ?: return
        val note = findViewById<TextView>(R.id.identityNameNote)
        val visible = if (PeerLabelFeature.enabled) View.VISIBLE else View.GONE
        button.visibility = visible
        note?.visibility = visible
        button.text = PeerLabelFeature.localName?.let { getString(R.string.identity_name_set, it) }
            ?: getString(R.string.identity_name_none)
        button.setOnClickListener {
            promptLabelText(LabelKind.PERSON_NAME, R.string.identity_name_title, R.string.identity_name_hint, PeerLabelFeature.localName) { name ->
                PeerLabelFeature.localName = name
                appPreferences.edit().apply {
                    if (name == null) remove(PeerLabelFeature.PREF_NAME) else putString(PeerLabelFeature.PREF_NAME, name)
                }.apply()
                refreshIdentityName()
            }
        }
    }

    /**
     * One optional text field (name, call subject or stream title) checked with [LabelText]. Empty
     * means none. With peer labels off, [onDone] runs at once with null (no dialog, unchanged flow).
     */
    private fun promptLabelText(kind: LabelKind, titleRes: Int, hintRes: Int, initial: String?, onDone: (String?) -> Unit) {
        if (!PeerLabelFeature.enabled) {
            onDone(null)
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(hintRes)
            setText(initial.orEmpty())
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(kind.maxBytes))
        }
        val container = android.widget.FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isBlank()) {
                    onDone(null)
                } else {
                    val result = runCatching { LabelText.normalize(kind, text) }
                    result.onSuccess(onDone).onFailure { error ->
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.identity_name_invalid, error.message ?: "invalid"),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        onDone(null)
                    }
                }
            }
            .setNegativeButton(R.string.action_skip) { _, _ -> onDone(null) }
            .setOnCancelListener { onDone(null) }
            .show()
    }

    /**
     * Experimental store-and-forward messages (StoreForwardFeature, default off): same launch rule
     * as the intercom switches (`test_store_forward`). Off: no Messages entry and every call
     * endpoint is created without the message-exchange hook.
     */
    private fun applyStoreForwardFeature(benchmarkLaunch: Boolean) {
        val enabled = StoreForwardFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        if (enabled != StoreForwardFeature.enabled && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK store_forward_feature: enabled=$enabled")
        }
        StoreForwardFeature.enabled = enabled
        findViewById<View>(R.id.homeMessagesButton)?.visibility = if (enabled) View.VISIBLE else View.GONE
        // Experimental automatic sync: DEBUG launch extra `test_sf_auto_sync` only, on top of the above.
        val auto = StoreForwardAutoSyncFeature.resolve(benchmarkLaunch, { intent.getBooleanExtra(it, false) }, enabled)
        if (auto != StoreForwardAutoSyncFeature.enabled && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK sf_sync auto=feature enabled=$auto")
        }
        StoreForwardAutoSyncFeature.enabled = auto
    }

    /**
     * Experimental automatic CoC -> GATT fallback (off by default; see CocAutoGattSettings). Same
     * rule as the other experiments: a DEBUG benchmark launch uses only `test_coc_auto_gatt` and
     * `test_coc_gatt_threshold`; otherwise the Advanced setting applies. Read when a call endpoint is
     * created.
     */
    private fun applyCocAutoGatt() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val resolved = CocAutoGattSettings.resolve(
            benchmarkLaunch,
            intent.getBooleanExtra(CocAutoGattSettings.EXTRA_ENABLED, false),
            intent.getIntExtra(CocAutoGattSettings.EXTRA_THRESHOLD, CocAutoGattSettings.DEFAULT_THRESHOLD),
            advancedSettings,
            appPreferences.getBoolean(CocAutoGattSettings.PREF_ENABLED, false),
            appPreferences.getInt(CocAutoGattSettings.PREF_THRESHOLD, CocAutoGattSettings.DEFAULT_THRESHOLD),
        )
        if (resolved != CocAutoGatt.current) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "coc_auto_gatt: enabled=${resolved.enabled} | threshold=${resolved.threshold}",
            )
        }
        CocAutoGatt.current = resolved
    }

    /**
     * Experimental bond-free Classic (ClassicFeatureSettings, default off). Same launch rule as the
     * other experiments: a DEBUG benchmark launch uses only `test_classic_bond_free` and
     * `test_classic_request_visible`; otherwise the Advanced setting applies. DEBUG address overrides
     * (`test_classic_addresses`, or `test_classic_address` + `test_classic_address_node`) let a run
     * supply each peer's Classic address read over adb; they are never stored. Read when a Classic
     * socket is created.
     */
    private fun applyClassicFeatures() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val resolved = ClassicFeatureSettings.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        if (resolved != ClassicFeatures.current) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "classic_features: ${ClassicFeatures.label(resolved)}")
        }
        ClassicFeatures.current = resolved
        if (BuildConfig.DEBUG) {
            val overrides = parseClassicAddressOverrides(intent.getStringExtra("test_classic_addresses")).toMutableMap()
            val legacyPrefix = debugClassicAddressNodePrefix
            val legacyAddress = debugClassicAddress
            if (legacyPrefix != null && legacyAddress != null) overrides[legacyPrefix] = legacyAddress
            ClassicAddressBook.setDebugOverrides(overrides)
            if (overrides.isNotEmpty()) Log.i("BluetoothVideo", "BENCHMARK classic_address_overrides: count=${overrides.size}")
        }
    }

    /**
     * Experimental iPhone compact discovery (CompactDiscoveryFeature, default off). Same launch rule
     * as the other experiments: a DEBUG benchmark launch uses only `test_ios_compact_discovery`;
     * otherwise the Advanced setting applies. Read when a discovery scan is created.
     */
    private fun applyCompactDiscovery() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val enabled = CompactDiscoveryFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        if (enabled != CompactDiscoveryFeature.enabled) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "compact_discovery: enabled=$enabled")
        }
        CompactDiscoveryFeature.enabled = enabled
    }

    /**
     * Experimental rotating discovery IDs (RotatingDiscoveryFeature, default off). Same launch rule
     * as the other experiments (DEBUG benchmark launch: only `test_rotating_ids`). Read when a call
     * endpoint or call scan is created. RV1: while on, the Bluetooth name is the neutral one; turning
     * it off restores the user's name (NeutralBluetoothNameController).
     */
    private fun applyRotatingIds() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val enabled = RotatingDiscoveryFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        val changed = enabled != RotatingDiscoveryFeature.enabled
        if (changed) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotating_ids: enabled=$enabled")
        }
        RotatingDiscoveryFeature.enabled = enabled
        if (enabled) runCatching { RotatingDiscovery.init(this, AndroidNodeIdentityStore().getOrCreate().nodeId) }
        // DEBUG benchmark launch: `test_rotating_visible_window` starts a window (old-build interop cases).
        if (enabled && benchmarkLaunch && intent.getBooleanExtra("test_rotating_visible_window", false)) {
            runCatching { RotatingDiscovery.startVisibleByHandle() }
        }
        // RV1 neutral name. A DEBUG benchmark launch renames only with `test_rotating_neutral_name`:
        // test phones keep their names, and the legacy Classic candidate
        // plan, which matches bonded phones by name, never sees several phones called "Phone".
        val renameAllowed = !benchmarkLaunch || intent.getBooleanExtra("test_rotating_neutral_name", false)
        if (hasRequiredPermissions() && (renameAllowed || !enabled)) {
            runCatching { NeutralBluetoothNameController.apply(this, bluetoothAdapter, enabled && renameAllowed) }
        }
    }

    /**
     * Always reachable for calls (CallReachabilityFeature, default on): a DEBUG benchmark launch
     * uses only `test_always_reachable`, so cases that request a role keep their advertising.
     */
    /**
     * Call resolution ladder (CallResolutionLadderFeature, default on for user calls): a DEBUG
     * benchmark launch keeps its exact geometry unless it carries `test_call_resolution_ladder=true`.
     */
    private fun applyCallResolutionLadder() {
        val extras = intent.extras?.keySet().orEmpty()
        val benchmarkLaunch = BuildConfig.DEBUG && extras.any { it.startsWith("test_") }
        val enabled = CallResolutionLadderFeature.resolve(
            benchmarkLaunch,
            hasExtra = BuildConfig.DEBUG && CallResolutionLadderFeature.EXTRA in extras,
            extraValue = intent.getBooleanExtra(CallResolutionLadderFeature.EXTRA, false),
        )
        CallResolutionLadderFeature.enabled = enabled
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_resolution_ladder: enabled=$enabled")
    }

    private fun applyCallReachability() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val enabled = CallReachabilityFeature.resolve(
            benchmarkLaunch,
            stored = { appPreferences.getBoolean(PREF_REACHABLE_FOR_CALLS, true) },
        ) { intent.getBooleanExtra(it, false) }
        CallReachabilityFeature.enabled = enabled
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: enabled=$enabled")
        // Off (every benchmark launch without the extra): no listener, so nothing changes.
        ActiveVideoSessionService.heldTypesListener = if (enabled) {
            { sessionHandler.post(callReachabilityServiceChanged) }
        } else {
            null
        }
    }

    /**
     * The session service's held types changed after start() returned (Android refused the type in
     * onStartCommand, or the service ended). A Home endpoint in the background must not outlive the
     * connectedDevice service it depends on, so stop it when the plan no longer allows it. This
     * never starts an endpoint: starts keep their own triggers and delays (GATT restart pause).
     */
    private val callReachabilityServiceChanged = Runnable {
        if (isFinishing || isDestroyed || !::codecSpinner.isInitialized) return@Runnable
        if (reachabilityScreen() == ReachabilityScreen.HOME && !currentReachabilityPlan().endpoint &&
            (privateCallEndpointIdle() || privateCallAvailabilityStarting)
        ) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "call_reachability: endpoint stopped | reason=foreground_service_lost",
            )
            stopPrivateCallEndpoint()
        }
        updateHomeReadiness()
    }

    private fun reachabilityScreen(): ReachabilityScreen {
        val screen = if (appScreen == AppScreen.SETTINGS) settingsReturnScreen else appScreen
        return when (screen) {
            AppScreen.HOME, AppScreen.SETTINGS -> ReachabilityScreen.HOME
            AppScreen.CALL_BROWSER -> ReachabilityScreen.CALL_BROWSER
            AppScreen.CALL -> ReachabilityScreen.CALL
            AppScreen.CAMERA -> ReachabilityScreen.LIVE
            AppScreen.RECEIVER_BROWSER, AppScreen.PLAYBACK -> ReachabilityScreen.WATCH
        }
    }

    /** Bluetooth on and every call permission granted (the home readiness card's READY). */
    private fun callReachabilityReady(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val bluetoothOn = runCatching { adapter.isEnabled }.getOrDefault(false)
        return bluetoothOn && OnboardingActivity.permissions(this)
            .filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun currentReachabilityPlan(): ReachabilityPlan =
        CallReachability.plan(currentReachabilityPlanInputs(reachabilityScreen()))

    private fun currentReachabilityPlanInputs(screen: ReachabilityScreen): ReachabilityInputs {
        val batteryPercent = runCatching {
            getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        return ReachabilityInputs(
            featureEnabled = CallReachabilityFeature.enabled,
            screen = screen,
            foreground = activityStarted,
            foregroundServiceHeld = ActiveVideoSessionService.holdsConnectedDevice(),
            bluetoothReady = callReachabilityReady(),
            batteryLow = batteryPercent != null && batteryPercent in 0 until CallReachability.LOW_BATTERY_PERCENT,
            batterySaver = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false),
        )
    }

    /** An idle endpoint that only waits for incoming calls (no call ringing or connected). */
    private fun privateCallEndpointIdle(): Boolean =
        privateCall != null && privateCallPhase == null && appScreen != AppScreen.CALL

    /**
     * Applies [CallReachability] on Home (and Settings-from-Home); other screens keep managing the
     * endpoint themselves. Never touches a ringing or connected call.
     */
    private fun refreshCallReachability(delayMs: Long = 0L) {
        if (delayMs > 0L) {
            sessionHandler.removeCallbacks(callReachabilityRefresh)
            sessionHandler.postDelayed(callReachabilityRefresh, delayMs)
            return
        }
        if (isFinishing || isDestroyed || !::codecSpinner.isInitialized) return
        val plan = currentReachabilityPlan()
        val line = plan.logLine()
        if (CallReachabilityFeature.enabled && line != lastReachabilityLog) {
            lastReachabilityLog = line
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: $line")
        }
        if (reachabilityScreen() == ReachabilityScreen.HOME) {
            if (plan.endpoint) {
                if (privateCall == null && !privateCallAvailabilityStarting) {
                    startPrivateCallEndpoint()
                } else if (privateCallAvailable) {
                    ensureReachabilityService()
                }
            } else if (privateCallEndpointIdle() || privateCallAvailabilityStarting) {
                stopPrivateCallEndpoint()
            }
        }
        updateHomeReadiness()
    }

    private val callReachabilityRefresh = Runnable { refreshCallReachability() }

    /**
     * Keeps the connectedDevice foreground service for an idle available endpoint (stopStreaming
     * stops the service when Home or the Call screen opens). Started only while visible, as
     * Android 12+ requires; a refusal is reported by the service (BENCHMARK foreground_service).
     */
    private fun ensureReachabilityService() {
        if (!CallReachabilityFeature.enabled || !activityStarted || !privateCallAvailable) return
        if (ActiveVideoSessionService.holdsConnectedDevice()) return
        runCatching {
            ActiveVideoSessionService.start(this, usesCamera = false, statusText = getString(R.string.reachable_service_text))
        }.onFailure { Log.w("BluetoothVideo", "Call reachability service start failed", it) }
    }

    /**
     * Experimental "Share app nearby" (OfflineAppShareFeature, default off). Same launch rule as the
     * other experiments: a DEBUG benchmark launch uses only `test_offline_app_share`; otherwise the
     * Advanced setting applies. It only shows the Help button; nothing runs until that screen opens.
     */
    private fun applyOfflineAppShare() {
        val benchmarkLaunch = BuildConfig.DEBUG &&
            intent.extras?.keySet()?.any { it.startsWith("test_") } == true
        val enabled = OfflineAppShareFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            advancedSettings,
            { appPreferences.getBoolean(it, false) },
        )
        if (enabled != OfflineAppShareFeature.enabled) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "offline_app_share: enabled=$enabled")
        }
        OfflineAppShareFeature.enabled = enabled
        findViewById<View>(R.id.shareAppNearbyButton)?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private val classicVisibilityRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // RESULT_CANCELED means refused; otherwise the result code is the granted duration in seconds.
        Log.i("BluetoothVideo", "Classic visibility request result: ${result.resultCode}")
    }

    /**
     * Bond-free Classic: a first contact needs the listening phone to be discoverable, because Android
     * reveals a phone's Classic address to an unpaired phone only through inquiry. Asks Android's
     * system dialog (the user decides) at most once per process, when this phone starts listening.
     */
    @SuppressLint("MissingPermission")
    private fun requestClassicVisibilityIfNeeded(transport: BluetoothTransportOption) {
        if (transport != BluetoothTransportOption.CLASSIC_RFCOMM || classicVisibilityRequested) return
        if (!ClassicFeatures.current.requestVisibility) return
        val adapter = bluetoothAdapter ?: return
        if (runCatching { adapter.scanMode }.getOrNull() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return
        classicVisibilityRequested = true
        runCatching {
            classicVisibilityRequest.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, CLASSIC_VISIBILITY_SECONDS),
            )
        }.onFailure { Log.w("BluetoothVideo", "Classic visibility request unavailable", it) }
    }

    /** Applies the mode's effective technical values; manual choices stay stored for Advanced. */
    private fun applySettingsModeValues() {
        if (advancedSettings) {
            encoderRateControl = runCatching {
                EncoderRateControl.valueOf(appPreferences.getString(PREF_MANUAL_RATE_CONTROL, null) ?: "CBR")
            }.getOrDefault(EncoderRateControl.CBR)
            receiverPlaybackDelay = PlaybackDelayProfile.fromStoredValue(
                appPreferences.getString(PREF_RECEIVER_PLAYBACK_DELAY, null),
            ).takeIf { it != PlaybackDelayProfile.AUTO } ?: PlaybackDelayProfile.BALANCED
        } else {
            encoderRateControl = EncoderRateControl.AUTOMATIC
            receiverPlaybackDelay = PlaybackDelayProfile.AUTO
        }
    }

    private fun applySettingsModeVisibility() {
        val manual = if (advancedSettings) View.VISIBLE else View.GONE
        rateControlLabel.visibility = manual
        rateControlSpinner.visibility = manual
        rateControlDescriptionText.visibility = manual
        receiverPlaybackDelaySlider.visibility = manual
        playbackStabilityText.visibility = manual
        experimentalTemporalLayersSwitch.visibility = manual
        experimentalTemporalLayersDescriptionText.visibility = manual
        relayChildMediaAgeSwitch.visibility = manual
        relayChildMediaAgeDescriptionText.visibility = manual
        experimentalVoiceCallsSwitch.visibility = manual
        experimentalVoiceCallsDescriptionText.visibility = manual
        // Push-to-talk is not a user-facing mode (design decision 2026-10-10): extras only.
        experimentalPushToTalkSwitch.visibility = View.GONE
        experimentalPttModeSwitch.visibility = View.GONE
        experimentalPushToTalkDescriptionText.visibility = manual
        experimentalStoreForwardSwitch.visibility = manual
        experimentalStoreForwardDescriptionText.visibility = manual
        experimentalPeerLabelsSwitch.visibility = manual
        experimentalPeerLabelsDescriptionText.visibility = manual
        experimentalCocAutoGattSwitch.visibility = manual
        experimentalCocAutoGattDescriptionText.visibility = manual
        experimentalClassicBondFreeSwitch.visibility = manual
        experimentalClassicBondFreeDescriptionText.visibility = manual
        experimentalCompactDiscoverySwitch.visibility = manual
        experimentalCompactDiscoveryDescriptionText.visibility = manual
        experimentalRotatingIdsSwitch.visibility = manual
        experimentalRotatingIdsDescriptionText.visibility = manual
        rotatingIdsVisibleButton.visibility = manual
        experimentalOfflineAppShareSwitch.visibility = manual
        experimentalOfflineAppShareDescriptionText.visibility = manual
        applyExperimentsHint()
        advancedSettingsDescriptionText.text = if (advancedSettings) {
            "On: every technical value below is exactly what you choose; nothing adapts automatically."
        } else {
            "Off (recommended): this phone tunes buffering, link rate, encoder speed and viewer capacity " +
                "from what it measures. You still choose codec, resolution, frame rate and bitrate."
        }
        updatePlaybackDelayLabels()
    }

    private fun updatePlaybackDelayLabels() {
        playbackDelayValueText.text = if (receiverPlaybackDelay == PlaybackDelayProfile.AUTO) {
            "Playback buffer: automatic, ${receiverPlaybackDelay.delayMs}-${receiverPlaybackDelay.adaptiveMaxDelayMs} ms; " +
                "no frames are dropped"
        } else {
            "Playback buffer: ${receiverPlaybackDelay.delayMs}-${receiverPlaybackDelay.adaptiveMaxDelayMs} ms " +
                "(grows only while the link is late)"
        }
        playbackStabilityText.text =
            "Playback stability: ${receiverPlaybackDelay.stabilityLabel}"
    }

    private fun applyDeviceHealthOverlayVisibility() {
        if (::deviceHealthOverlay.isInitialized) {
            deviceHealthOverlay.visibility = if (showDeviceHealthOverlay) View.VISIBLE else View.GONE
        }
    }

    private fun restoreVideoSettings() {
        val default = VideoSettings.DEFAULT
        val width = appPreferences.getInt(PREF_VIDEO_WIDTH, default.resolution.width)
            .coerceIn(1, 4_096)
        val height = appPreferences.getInt(PREF_VIDEO_HEIGHT, default.resolution.height)
            .coerceIn(1, 4_096)
        settings = default.copy(
            resolution = VideoResolution(width, height, "${height}p"),
            fps = appPreferences.getInt(PREF_VIDEO_FPS, default.fps).coerceIn(1, 60),
            bitRate = appPreferences.getInt(PREF_VIDEO_BITRATE, default.bitRate).coerceIn(
                VideoSettings.MIN_BIT_RATE_KBPS * 1_000,
                maxOf(
                    outgoingTransport.maxVideoBitrateKbps,
                    LinkCapacity.measuredLinkKbps(outgoingTransport) ?: 0,
                ) * 1_000,
            ),
        )
    }

    /**
     * Workstream D: the highest video bitrate this phone measured its link can carry on the selected
     * transport (from children's packet-train reports and probes). The fixed per-transport number is
     * only the fallback before any measurement.
     */
    private fun measuredBitrateCeilingKbps(): Int? = LinkCapacity.measuredLinkKbps(outgoingTransport)?.let { link ->
        val video = MeshBandwidthEstimator.videoKbpsForLink(link.toDouble(), settings.fps)
        (video - (video - VideoSettings.MIN_BIT_RATE_KBPS).mod(VideoSettings.BIT_RATE_STEP_KBPS))
            .coerceAtLeast(VideoSettings.MIN_BIT_RATE_KBPS)
    }

    /** A saved choice above the measured ceiling stays selectable; it is labelled, never lowered. */
    private fun bitrateSliderMaximumKbps(): Int =
        maxOf(measuredBitrateCeilingKbps() ?: outgoingTransport.maxVideoBitrateKbps, settings.bitRate / 1_000)

    /** Workstream C: each viewer's share of this phone's measured link bounds one frame's size. */
    private fun updateEncoderFrameBudget(viewers: Int) {
        val linkKbps = LinkCapacity.measuredLinkKbps(outgoingTransport)
            ?: LinkCapacity.priorKbps(outgoingTransport).toInt()
        cameraEncoder?.updateLinkFrameBudget(linkKbps / viewers.coerceAtLeast(1))
    }

    private fun applyTransportBitrateLimit(persist: Boolean) {
        val maximumKbps = measuredBitrateCeilingKbps() ?: outgoingTransport.maxVideoBitrateKbps
        // Only the hard transport bound (raised by a higher measurement) lowers a saved choice.
        val hardMaximumKbps = maxOf(outgoingTransport.maxVideoBitrateKbps, measuredBitrateCeilingKbps() ?: 0)
        val clampedBitrate = settings.bitRate.coerceAtMost(hardMaximumKbps * 1_000)
        val changed = clampedBitrate != settings.bitRate
        if (changed) settings = settings.copy(bitRate = clampedBitrate)
        if (::bitrateSeekBar.isInitialized) {
            bitrateSeekBar.max = (maxOf(maximumKbps, settings.bitRate / 1_000) - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS
            bitrateSeekBar.progress = (settings.bitRate / 1_000 - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS
        }
        if (changed && persist) persistVideoSettings()
    }

    private fun persistVideoSettings() {
        if (debugLaunchRequested || requestedTransportExperiment != null) return
        appPreferences.edit()
            .putInt(PREF_VIDEO_WIDTH, settings.resolution.width)
            .putInt(PREF_VIDEO_HEIGHT, settings.resolution.height)
            .putInt(PREF_VIDEO_FPS, settings.fps)
            .putInt(PREF_VIDEO_BITRATE, settings.bitRate)
            .apply()
    }

    private fun configureSeekBar(
        seekBar: SeekBar,
        maximum: Int,
        initial: Int,
        onChanged: (Int) -> Unit,
        onStopped: () -> Unit = {},
    ) {
        seekBar.max = maximum
        seekBar.progress = initial
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                onChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = onStopped()
        })
    }

    private fun updateSettingsLabels() {
        val measured = measuredBitrateCeilingKbps()
        val limitLabel = when {
            measured == null -> "${outgoingTransport.maxVideoBitrateKbps} kbps max until this phone measures the link"
            settings.bitRate / 1_000 > measured -> "above the $measured kbps this phone measured; may stall"
            else -> "this phone measured up to $measured kbps"
        }
        bitrateText.text = "Broadcast video bitrate: ${settings.bitRate / 1_000} kbps ($limitLabel)"
        if (::streamSummaryText.isInitialized) updateStreamSummary()
        if (::capabilityText.isInitialized) updateDeviceCapabilities()
        updatePrivateCallProfileSummary()
    }

    private fun updatePrivateCallProfileSummary() {
        if (!::privateCallProfileSummary.isInitialized) return
        val selectedCodec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        if (selectedCodec == null) {
            privateCallProfileSummary.text = "No compatible private-call video encoder on this phone."
            return
        }
        val fingerprint = privateCallStackFingerprint()
        val callCodec = privateCallCodec(selectedCodec, codecs, fingerprint, outgoingTransport)
        val callSettings = privateCallVideoSettings(
            settings, outgoingTransport, fingerprint,
            preserveSelectedBitrate = callBitrateAdapts(),
        )
        val audioBitRate = privateCallAudioBitRate(outgoingTransport, fingerprint)
        val playback = privateCallPlaybackDelayProfile(
            receiverPlaybackDelay,
            outgoingTransport,
            fingerprint,
            null,
            legacyLowDelay = DebugTunables.callLegacyLowDelay,
        )
        privateCallProfileSummary.text =
            "Selected video: ${if (selectedCodec.protocolId == StreamProtocol.CODEC_AV1) "AV1" else "VP9"} | " +
                "${settings.resolution.width}x${settings.resolution.height} @ ${settings.fps} FPS | " +
                "${settings.bitRate / 1_000} kbps | ${receiverPlaybackDelay.delayMs} ms buffer\n" +
                "Call on this phone: ${privateCallProfileLabel(callSettings, audioBitRate, callCodec, playback)} " +
                "(${playback.delayMs} ms buffer)\n" +
                "The other phone may require a different profile, shown during the call."
    }

    private fun privateCallProfileLabel(
        callSettings: VideoSettings,
        audioBitRate: Int,
        callCodec: VideoCodec,
        playback: PlaybackDelayProfile,
    ): String {
        val codecName = if (callCodec.protocolId == StreamProtocol.CODEC_AV1) "AV1" else "VP9"
        return "$codecName | ${callSettings.resolution.width}x${callSettings.resolution.height} @ " +
            "${callSettings.fps} FPS | ${callSettings.bitRate / 1_000} kbps video | " +
            "${audioBitRate / 1_000} kbps audio | ${playback.label}"
    }

    private fun updateDeviceCapabilities() {
        if (!::capabilityText.isInitialized) return
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        if (codec == null) {
            capabilityText.text = "No AV1 or VP9 encoder"
            return
        }
        val selectedMode = selectedDeviceMode()
        capabilityText.text = buildList {
            add(
                "Camera input: " + when {
                    codec.usesSurfaceInput -> "direct surface"
                    codec.backend == VideoEncoderBackend.NATIVE_LIBAOM -> "YUV to native libaom"
                    else -> "YUV copy (native size)"
                },
            )
            if (deviceModes.isEmpty()) {
                add("Native modes: none at ${settings.bitRate / 1_000} kbps")
            } else {
                val smallest = deviceModes.first().resolution
                val largest = deviceModes.last().resolution
                add(
                    "Native sizes: ${deviceModes.size} " +
                        "(${smallest.width}×${smallest.height} to ${largest.width}×${largest.height})",
                )
            }
            selectedMode?.let { mode ->
                add("FPS: ${mode.fpsOptions.joinToString(" / ")}")
                mode.encoderFpsEstimate?.let { estimate ->
                    add("OEM codec estimate: %.0f-%.0f FPS".format(estimate.lower, estimate.upper))
                }
            }
        }.joinToString("\n")
    }

    private fun updateStreamSummary() {
        if (!::streamSummaryText.isInitialized) return
        val estimate = MeshBandwidthEstimator.estimate(
            settings,
            outgoingTransport,
            MeshNodeRole.SOURCE,
            plannedChildren = 1,
        )
        val budgetKbps = LinkCapacity.budgetKbps(outgoingTransport, LinkLoadKind.FAN_OUT, estimate.perLinkKbps)
        val relayBudgetKbps = LinkCapacity.budgetKbps(outgoingTransport, LinkLoadKind.RELAY, estimate.perLinkKbps)
        streamSummaryText.text = "Bandwidth/stream: ~%.0f kbps\n".format(estimate.perLinkKbps) +
            "Video: ${settings.bitRate / 1_000} kbps\n" +
            "Mode: ${settings.resolution.width}×${settings.resolution.height} @ ${settings.fps} FPS\n" +
            "This phone on ${outgoingTransport.shortLabel}: ~%.0f kbps total, ".format(budgetKbps) +
            "${(budgetKbps / estimate.perLinkKbps).toInt()} viewer link(s), " +
            (if (relayFits(relayBudgetKbps, estimate.perLinkKbps)) "can relay" else "cannot relay") +
            (WifiCoexistence.advice()?.let { "\n$it" } ?: "")
        if (::homeConfigurationSummary.isInitialized) {
            homeConfigurationSummary.text =
                "${settings.resolution.width}×${settings.resolution.height} | ${settings.fps} FPS | " +
                "~%.0f kbps\n%s".format(estimate.perLinkKbps, outgoingTransport.shortLabel)
        }
    }

    private fun refreshCodecs() {
        val selectedEncoder = codecs.getOrNull(codecSpinner.selectedItemPosition)?.encoderName
        codecs = rankEncoders(VideoCodec.availableEncoders())
        decoders = VideoCodec.availableDecoders()
        codecSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            codecs.map(VideoCodec::displayName).ifEmpty { listOf("No compatible AV1 or VP9 encoder") },
        )
        codecs.indexOfFirst { it.encoderName == selectedEncoder }
            .takeIf { it >= 0 }
            ?.let(codecSpinner::setSelection)
        decoderSupportText.text = decoders.joinToString("\n") { decoder ->
            "${decoder.codecName} - ${if (decoder.hardwareAccelerated) "hardware" else "software"}"
        }.ifEmpty { "No compatible AV1 or VP9 decoder" }
        refreshRateControlPicker()
        refreshVideoModes()
        updateDeviceCapabilities()
        updateStreamSummary()
        updateStartEnabled()
    }

    /**
     * Experimental encoder ranking (off by default): the encoder measured on this phone
     * to keep scene cuts small and keep up with the frame rate becomes the default choice. Off: the
     * static hardware-first order. The order and records are logged in both arms (DEBUG).
     */
    private fun rankEncoders(available: List<VideoCodec>): List<VideoCodec> {
        val store = EncoderBurstProfileStore(this)
        val records = available.associate { it.encoderName to runCatching { store.load(it.encoderName) }.getOrNull() }
        val ranked = if (DebugTunables.experimentalEncoderRanking) {
            EncoderBurstRanking.order(available) { name -> records[name] }
        } else {
            available
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder_ranking: experiment=${DebugTunables.experimentalEncoderRanking} | order=" +
                    ranked.joinToString(",") { it.encoderName } + " | records=" +
                    available.joinToString(",") { codec ->
                        val record = records[codec.encoderName]
                        if (record == null) {
                            "${codec.encoderName}=unmeasured"
                        } else {
                            "${codec.encoderName}=burst ${roundedForLog(record.burstSeconds, 2)} s" +
                                (record.encodeLoad?.let { " load ${roundedForLog(it, 2)}" } ?: "") +
                                " fits ${record.fits} (${record.sessions} sessions)"
                        }
                    },
            )
        }
        return ranked
    }

    private fun refreshRateControlPicker() {
        if (!::rateControlSpinner.isInitialized) return
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        val available = codec?.availableRateControls() ?: listOf(EncoderRateControl.AUTOMATIC)
        rateControlOptions = if (advancedSettings) {
            available.filter { it != EncoderRateControl.AUTOMATIC }.ifEmpty { available }
        } else {
            listOf(EncoderRateControl.AUTOMATIC)
        }
        val selectedIndex = rateControlOptions.indexOf(encoderRateControl).takeIf { it >= 0 } ?: 0
        updatingRateControlPicker = true
        rateControlSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rateControlOptions.map(EncoderRateControl::label),
        )
        rateControlSpinner.setSelection(selectedIndex, false)
        encoderRateControl = rateControlOptions[selectedIndex]
        updatingRateControlPicker = false
        updateRateControlDescription()
    }

    private fun updateRateControlDescription() {
        if (::rateControlDescriptionText.isInitialized) {
            rateControlDescriptionText.text = encoderRateControl.description
        }
    }

    private fun refreshVideoModes() {
        val preferredResolution = settings.resolution
        val preferredFps = settings.fps
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        deviceModes = codec?.let { selected ->
            runCatching { deviceVideoCapabilities.modesFor(selected, settings.bitRate, cameraLens) }
                .getOrDefault(emptyList())
        }.orEmpty()

        updatingModePickers = true
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceModes.map { mode ->
                "${mode.resolution.width}×${mode.resolution.height} (${mode.resolution.label})"
            }.ifEmpty { listOf("No native modes") },
        )
        val selectedIndex = deviceModes.indexOfFirst { it.resolution == preferredResolution }
            .takeIf { it >= 0 }
            ?: deviceModes.indices.minByOrNull { index ->
                val resolution = deviceModes[index].resolution
                kotlin.math.abs(
                    resolution.width.toLong() * resolution.height -
                        preferredResolution.width.toLong() * preferredResolution.height,
                )
            }
        selectedIndex?.let { index ->
            resolutionSpinner.setSelection(index, false)
            settings = settings.copy(resolution = deviceModes[index].resolution)
        }
        refreshFpsPicker(preferredFps)
        updatingModePickers = false
        updateSettingsLabels()
        restartIdleCameraPreview()
        updateStartEnabled()
    }

    private fun refreshFpsPicker(preferredFps: Int) {
        val options = selectedDeviceMode()?.fpsOptions.orEmpty()
        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { "$it FPS" }.ifEmpty { listOf("No supported FPS") },
        )
        val selectedIndex = options.indexOf(preferredFps)
            .takeIf { it >= 0 }
            ?: options.indices.minByOrNull { index -> kotlin.math.abs(options[index] - preferredFps) }
        selectedIndex?.let { index ->
            fpsSpinner.setSelection(index, false)
            settings = settings.copy(fps = options[index])
        }
    }

    private fun selectedDeviceMode(): DeviceVideoMode? =
        deviceModes.getOrNull(resolutionSpinner.selectedItemPosition)

    private fun onPermissionsReady() {
        // RV1: the neutral name may have been skipped while permissions were missing; apply it
        // before any rotating call presence starts.
        if (RotatingDiscoveryFeature.enabled) applyRotatingIds()
        when {
            requestedTransportExperiment != null -> {
                if (transportExperimentPending) runRequestedTransportExperiment()
            }
            appScreen == AppScreen.CAMERA && usesBroadcastDiscovery() -> {
                startIdleCameraPreview()
                startStreamPresence()
            }
            appScreen == AppScreen.RECEIVER_BROWSER -> refreshNearbyStreams()
            appScreen == AppScreen.CALL_BROWSER -> startPrivateCallEndpoint()
            appScreen == AppScreen.PLAYBACK && reconnectTarget != null -> refreshNearbyStreams()
            appScreen == AppScreen.PLAYBACK &&
                (selectedStreamPeer != null || debugLaunchRequested) ->
                preview.post { if (receiver == null) startReceiving() }
            else -> {
                refreshDevices()
                startIdleCameraPreview()
            }
        }
        updateStartEnabled()
    }

    private fun startStreamPresence() {
        val adapter = bluetoothAdapter ?: return
        if (appScreen != AppScreen.CAMERA || !usesBroadcastDiscovery() ||
            !streamArmed || streamAdvertiser != null ||
            streamPresenceStarting || !hasRequiredPermissions()
        ) {
            return
        }
        if (streamToken == 0) streamToken = StreamTokenGenerator.next()
        streamPresenceStarting = true
        val activeToken = streamToken
        val advertisedGeneration = streamCapacityGeneration
        val advertisedFull = broadcaster?.sourceSlotsFull() == true
        val activeTransport = outgoingTransport
        val activeCodec = codecs.getOrNull(codecSpinner.selectedItemPosition) ?: return
        val advertisedSettings = settings
        thread(name = "BleStreamAdvertiser") {
            val result = runCatching {
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val nodePrefix = identity.nodeId.toByteArray()
                    .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                val routeToken = StreamRouteToken.forAdvertiser(activeToken, nodePrefix)
                BleDiscoveryAdvertiser(
                    adapter,
                    DiscoveryAnnouncement(
                        nodeIdPrefix = nodePrefix,
                        flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or
                            (if (streamPaused) DiscoveryAnnouncement.FLAG_STREAM_PAUSED else 0) or
                            when (activeTransport) {
                                BluetoothTransportOption.CLASSIC_RFCOMM ->
                                    DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
                                BluetoothTransportOption.BLE_GATT ->
                                    DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
                                BluetoothTransportOption.BLE_L2CAP -> 0
                            },
                        capabilitySummary = activeCodec.capabilityMask,
                        streamToken = routeToken,
                        routeCost = 0,
                        sequence = (advertisedGeneration and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK) or
                            if (advertisedFull) DiscoveryAnnouncement.SEQUENCE_SOURCE_FULL else 0,
                        streamWidth = advertisedSettings.resolution.width,
                        streamHeight = advertisedSettings.resolution.height,
                        streamFps = advertisedSettings.fps,
                        streamBitrateKbps = advertisedSettings.bitRate / 1_000,
                    ),
                    { message ->
                        if (streamPaused) {
                            setStatus("Broadcast paused. Connections remain available.")
                        } else {
                            setStatus(message)
                        }
                    },
                ).also(BleDiscoveryAdvertiser::start) to routeToken
            }
            // Experimental peer labels: public title pages (unconfirmed for viewers until they join).
            // Started after the stream offer so a refused extra set never affects the stream.
            val titlePages = result.getOrNull()?.second?.let { routeToken ->
                PeerLabelFeature.localStreamTitle?.takeIf { PeerLabelFeature.enabled }?.let { title ->
                    if (AdvertisingSetProbe.lastFree == null) runCatching { AdvertisingSetProbe.run(adapter) }
                    runCatching { TitleAdvertiser(adapter, routeToken, title) }.getOrNull()?.takeIf {
                        it.start() == android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS
                    }
                }
            }
            runOnUiThread {
                streamPresenceStarting = false
                result.onSuccess { (advertiser, routeToken) ->
                    if (appScreen == AppScreen.CAMERA && streamArmed && streamToken == activeToken &&
                        streamCapacityGeneration == advertisedGeneration
                    ) {
                        streamTitleAdvertiser?.close()
                        streamTitleAdvertiser = titlePages
                        streamAdvertiser = advertiser
                        streamAdvertisedFull = advertisedFull
                        streamRouteToken = routeToken
                        startViewerRequestDiscovery()
                    } else {
                        advertiser.close()
                        titlePages?.close()
                        if (appScreen == AppScreen.CAMERA && streamArmed && streamToken == activeToken) {
                            startStreamPresence()
                        }
                    }
                }.onFailure { error ->
                    streamArmed = false
                    stopForegroundSessionIfIdle()
                    setStatus("Could not advertise this stream: ${error.message ?: error.javaClass.simpleName}")
                    updateStartEnabled()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startViewerRequestDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (streamDiscovery != null || streamToken == 0 || streamRouteToken == 0 ||
            appScreen != AppScreen.CAMERA ||
            sender != null
        ) {
            return
        }
        val activeToken = streamToken
        val activeRouteToken = streamRouteToken
        val sourceSlotsFull = broadcaster?.sourceSlotsFull() == true
        val classicSetupPending = classicSourceConnectionPending()
        val radioBusy = sourceSlotsFull || classicSetupPending
        val testScanMode = if (BuildConfig.DEBUG) {
            intent.getStringExtra("test_viewer_scan_mode")?.takeIf {
                outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM ||
                    outgoingTransport == BluetoothTransportOption.BLE_GATT && it == "off_when_full"
            }
        } else null
        val pauseAtCapacity = when (testScanMode) {
            "off_when_full" -> true
            "balanced", "low_latency" -> false
            else -> pauseClassicViewerDiscoveryAtCapacity(outgoingTransport, BluetoothStackFingerprint.current())
        }
        if (pauseAtCapacity && sourceSlotsFull) return
        val continuousLowLatency = when (testScanMode) {
            "balanced" -> false
            "low_latency" -> true
            else -> useContinuousLowLatencyViewerDiscovery(
                outgoingTransport, BluetoothStackFingerprint.current(), radioBusy,
            )
        }
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (streamDiscovery !== discovery) return@runOnUiThread
                    viewerPeersLastSeen[peer.device.address] = peer to SystemClock.elapsedRealtime()
                    refreshPendingViewerPeers()
                    if (streamArmed) startRequestedBroadcast()
                }
            },
            {
                runOnUiThread {
                    if (streamDiscovery !== discovery) return@runOnUiThread
                    streamDiscovery = null
                    refreshPendingViewerPeers()
                    if (appScreen == AppScreen.CAMERA && streamToken == activeToken) {
                        discoveryHandler.postDelayed(::startViewerRequestDiscovery, DISCOVERY_RESTART_MS)
                    }
                }
            },
            { message ->
                runOnUiThread {
                    if (streamDiscovery !== discovery) return@runOnUiThread
                    streamDiscovery = null
                    refreshPendingViewerPeers()
                    setStatus(message)
                    if (appScreen == AppScreen.CAMERA && streamToken == activeToken) {
                        discoveryHandler.postDelayed(::startViewerRequestDiscovery, SCAN_ERROR_RETRY_MS)
                    }
                }
            },
            { announcement ->
                announcement.isViewerRequest(activeRouteToken) &&
                    announcement.transportOption() == outgoingTransport
            },
            scanDurationMs = CONTINUOUS_SCAN_MS,
            reportDuplicates = true,
            continuousLowLatency = continuousLowLatency,
            startLowLatency = when (testScanMode) {
                "balanced" -> false
                "low_latency" -> true
                else -> !(outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM && radioBusy)
            },
        )
        streamDiscovery = discovery
        viewerDiscoveryContinuousLowLatency = continuousLowLatency
        discovery.start()
        if (testScanMode == "balanced" || testScanMode == "low_latency") {
            Log.i("BluetoothVideo", "DEBUG: Classic viewer discovery scan mode $testScanMode")
        } else if (classicSetupPending && !sourceSlotsFull) {
            Log.i("BluetoothVideo", "Classic connection pending; balanced viewer discovery frees the radio for setup")
        } else if (sourceSlotsFull && outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            Log.i("BluetoothVideo", "Classic source slots full; balanced viewer discovery protects active media")
        } else if (!continuousLowLatency) {
            Log.i(
                "BluetoothVideo",
                "Legacy Qualcomm Classic viewer discovery will switch to balanced scan while streaming.",
            )
        }
        discoveryHandler.postDelayed(::expireViewerRequests, VIEWER_EXPIRY_CHECK_MS)
    }

    private fun refreshViewerDiscoveryForCapacity() {
        val testScanMode = if (BuildConfig.DEBUG) {
            intent.getStringExtra("test_viewer_scan_mode")?.takeIf {
                outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM ||
                    outgoingTransport == BluetoothTransportOption.BLE_GATT && it == "off_when_full"
            }
        } else null
        if (testScanMode == null && classicSourceConnectionPending() && streamDiscovery?.isLowLatency() == true) {
            // Measured ASUS->S24 Classic setup: low-latency viewer scanning during page/SDP/RFCOMM
            // took 3.7-3.9 s versus 1.9 s with balanced scanning. Request detection stays fast
            // because the low-latency scan only yields while a connection is in flight.
            discoveryHandler.removeCallbacksAndMessages(null)
            streamDiscovery?.close()
            streamDiscovery = null
            startViewerRequestDiscovery()
            return
        }
        val pauseAtCapacity = when (testScanMode) {
            "off_when_full" -> true
            "balanced", "low_latency" -> false
            else -> pauseClassicViewerDiscoveryAtCapacity(outgoingTransport, BluetoothStackFingerprint.current())
        }
        if (pauseAtCapacity) {
            if (broadcaster?.sourceSlotsFull() == true) {
                if (streamDiscovery != null) {
                    streamDiscovery?.close()
                    streamDiscovery = null
                    val transportLabel = outgoingTransport.shortLabel
                    Log.i("BluetoothVideo", "$transportLabel source slots full; viewer discovery stopped")
                }
            } else if (streamDiscovery == null) {
                val transportLabel = outgoingTransport.shortLabel
                Log.i("BluetoothVideo", "$transportLabel source slot free; viewer discovery restarting")
                startViewerRequestDiscovery()
            }
            return
        }
        if (outgoingTransport != BluetoothTransportOption.CLASSIC_RFCOMM ||
            streamDiscovery == null || BuildConfig.DEBUG &&
            intent.getStringExtra("test_viewer_scan_mode") in setOf("balanced", "low_latency")
        ) return
        val continuous = useContinuousLowLatencyViewerDiscovery(
            outgoingTransport, BluetoothStackFingerprint.current(), broadcaster?.sourceSlotsFull() == true,
        )
        if (viewerDiscoveryContinuousLowLatency == continuous) return
        discoveryHandler.removeCallbacksAndMessages(null)
        streamDiscovery?.close()
        streamDiscovery = null
        Log.i("BluetoothVideo", "Source slot availability changed; refreshing Classic viewer discovery")
        startViewerRequestDiscovery()
    }

    private fun classicSourceConnectionPending(): Boolean =
        outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM &&
            broadcaster?.connectionAttemptsPending() == true

    private fun expireViewerRequests() {
        if (streamDiscovery == null || appScreen != AppScreen.CAMERA) return
        refreshPendingViewerPeers()
        discoveryHandler.postDelayed(::expireViewerRequests, VIEWER_EXPIRY_CHECK_MS)
    }

    private fun refreshPendingViewerPeers() {
        val cutoff = SystemClock.elapsedRealtime() - VIEWER_REQUEST_STALE_MS
        viewerPeersLastSeen.entries.removeAll { it.value.second < cutoff }
        pendingViewerPeers = viewerPeersLastSeen.values.map { it.first }.sortedBy(BleL2capPeer::label)
        if (broadcaster == null) updateConnectionDot(pendingViewerPeers.size)
        updateStartEnabled()
    }

    private fun stopStreamPresence(resetToken: Boolean = true) {
        discoveryHandler.removeCallbacksAndMessages(null)
        streamDiscovery?.close()
        streamDiscovery = null
        streamAdvertiser?.close()
        streamAdvertiser = null
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        pendingViewerPeers = emptyList()
        viewerPeersLastSeen.clear()
        if (resetToken) {
            streamToken = 0
            streamCapacityGeneration = 0
            streamRouteToken = 0
        }
        if (::connectionStatusDot.isInitialized) updateConnectionDot(0)
    }

    private fun refreshStreamPresenceAdvertisement() {
        streamAdvertiser?.close()
        streamAdvertiser = null
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        startStreamPresence()
    }

    @SuppressLint("MissingPermission")
    private fun refreshNearbyStreams() {
        val adapter = bluetoothAdapter ?: return
        if (!isSeekingStreamRoute() || !hasRequiredPermissions()) return
        if (nearbyStreamDiscovery != null) return
        refreshNearbyStreamPeers()
        if (!isSeekingStreamRoute() || receiver != null) return
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (nearbyStreamDiscovery !== discovery) return@runOnUiThread
                    if (!receiverFirstOfferLogged && receiverBrowserStartedAtNs > 0L) {
                        receiverFirstOfferLogged = true
                        Log.i(
                            "BluetoothVideo",
                            "Discovery startup: browser-to-first-offer %.1f ms".format(
                                Locale.US,
                                (System.nanoTime() - receiverBrowserStartedAtNs) / 1_000_000.0,
                            ),
                        )
                    }
                    val streamKey = peer.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) } +
                        ":${peer.announcement.transportOption().name}"
                    nearbyStreamsLastSeen[streamKey] = peer to SystemClock.elapsedRealtime()
                    refreshNearbyStreamPeers()
                }
            },
            {
                runOnUiThread {
                    if (nearbyStreamDiscovery !== discovery) return@runOnUiThread
                    nearbyStreamDiscovery = null
                    if (isSeekingStreamRoute()) {
                        discoveryHandler.postDelayed(::refreshNearbyStreams, DISCOVERY_RESTART_MS)
                    }
                }
            },
            { message ->
                runOnUiThread {
                    if (nearbyStreamDiscovery !== discovery) return@runOnUiThread
                    nearbyStreamDiscovery = null
                    if (appScreen == AppScreen.RECEIVER_BROWSER) receiverBrowserStatus.text = message
                    if (isSeekingStreamRoute()) {
                        discoveryHandler.postDelayed(
                            ::refreshNearbyStreams,
                            if (reconnectTarget != null) ROUTE_SCAN_ERROR_RETRY_MS else SCAN_ERROR_RETRY_MS,
                        )
                    }
                }
            },
            DiscoveryAnnouncement::isStreamOffer,
            scanDurationMs = CONTINUOUS_SCAN_MS,
            reportDuplicates = true,
            continuousLowLatency = true,
        )
        nearbyStreamDiscovery = discovery
        discovery.start()
        discoveryHandler.postDelayed(::expireNearbyStreams, NEARBY_EXPIRY_CHECK_MS)
    }

    private fun restartNearbyStreams() {
        nearbyStreamsLastSeen.clear()
        nearbyStreamPeers = emptyList()
        updateNearbyStreamList()
        receiverBrowserStatus.text = "Scanning for nearby streams…"
        if (nearbyStreamDiscovery == null) refreshNearbyStreams()
    }

    private fun refreshNearbyStreamPeers() {
        val nowMs = SystemClock.elapsedRealtime()
        nearbyStreamsLastSeen.entries.removeAll {
            !streamOfferIsFresh(it.value.second, nowMs)
        }
        suppressedStreamRoutes.entries.removeAll { it.value <= nowMs }
        capacitySuppressedRouteGenerations.keys.retainAll(suppressedStreamRoutes.keys)
        nearbyStreamsLastSeen.values.forEach { (peer, _) ->
            val refreshed = capacitySuppressedRouteGenerations.filter { (route, generation) ->
                route.matches(peer.announcement) && streamCapacityGenerationAdvanced(generation, peer.announcement.capacityGeneration())
            }.keys
            refreshed.forEach { route ->
                capacitySuppressedRouteGenerations.remove(route)
                suppressedStreamRoutes.remove(route)
                Log.i("BluetoothVideo", "Source capacity changed; retrying the authenticated route instead of waiting for cooldown")
            }
        }
        val updatedPeers = nearbyStreamsLastSeen.values.map { it.first }
            .filterNot { peer ->
                suppressedStreamRoutes.keys.any { route -> route.matches(peer.announcement) }
            }
            .sortedBy(BleL2capPeer::label)
        val listChanged = nearbyStreamPeers.size != updatedPeers.size ||
            nearbyStreamPeers.zip(updatedPeers).any { (old, new) ->
                old.announcement.streamToken != new.announcement.streamToken ||
                    old.announcement.flags != new.announcement.flags ||
                    !old.announcement.nodeIdPrefix.contentEquals(new.announcement.nodeIdPrefix)
            }
        nearbyStreamPeers = updatedPeers
        if (listChanged) updateNearbyStreamList()
        val activeNotice = receiverBrowserNotice?.takeIf {
            SystemClock.elapsedRealtime() <= receiverBrowserNoticeDeadlineMs
        }
        receiverBrowserStatus.text = activeNotice ?: if (nearbyStreamPeers.isEmpty()) {
            "No nearby streams yet. Scanning continues."
        } else {
            resources.getQuantityString(R.plurals.watch_live_count, nearbyStreamPeers.size, nearbyStreamPeers.size)
        }
        val pendingReconnect = reconnectTarget
        if (pendingReconnect != null) {
            val reconnectCandidates = withPreferredParentGrace(
                nearbyStreamPeers
                    .filter { pendingReconnect.matches(it.announcement) }
                    .filter { supportsAdvertisedCodec(it.announcement) },
            )
            reconnectCandidates.asSequence()
                .sortedWith(
                    compareBy<BleL2capPeer> {
                        establishmentRank(establishmentFailing(it.announcement), DebugTunables.relayEstablishFallback)
                    }.thenBy {
                        preferredRecoveryRank(it.announcement.nodeIdPrefix, preferredRecoveryParent)
                    }.thenBy {
                        streamRouteRank(it.announcement, reconnectPreferredTransport).first
                    }.thenBy {
                        streamRouteRank(it.announcement, reconnectPreferredTransport).second
                    }.thenBy {
                        streamRouteRank(it.announcement, reconnectPreferredTransport).third
                    },
                )
                .firstOrNull()
                ?.also { logEstablishmentFallback(reconnectCandidates, it) }
                ?.let { peer ->
                    if (appScreen == AppScreen.RECEIVER_BROWSER) {
                        receiverBrowserStatus.text = "Route restored. Reconnecting..."
                    } else {
                        setStatus("Another route found. Reconnecting...")
                    }
                    connectToStream(peer)
                    return
                }
            if (appScreen == AppScreen.RECEIVER_BROWSER) {
                receiverBrowserStatus.text = "Connection lost. Looking for the same stream..."
            }
        }
        if (debugAutoSelectStream && selectedStreamPeer == null && receiverLocalMediaFailure == null) {
            nearbyStreamPeers.firstOrNull {
                !it.announcement.isFullStreamOffer() &&
                it.announcement.routeCost >= debugMinimumRouteCost &&
                    (debugStreamNodePrefix == null ||
                        it.announcement.nodeIdPrefix.joinToString("") { byte -> "%02x".format(byte) } == debugStreamNodePrefix)
            }?.let(::connectToStream)
        }
    }

    private fun expireNearbyStreams() {
        if (nearbyStreamDiscovery == null || !isSeekingStreamRoute()) return
        refreshNearbyStreamPeers()
        discoveryHandler.postDelayed(::expireNearbyStreams, NEARBY_EXPIRY_CHECK_MS)
    }

    private fun isSeekingStreamRoute(): Boolean =
        appScreen == AppScreen.RECEIVER_BROWSER ||
            (appScreen == AppScreen.PLAYBACK && reconnectTarget != null)

    private fun updateNearbyStreamList() {
        if (!::streamList.isInitialized) return
        val rows = nearbyStreamPeers.map { peer ->
            val relays = advertisedRelayCount(peer.announcement.routeCost) ?: 0
            val supported = supportsAdvertisedCodec(peer.announcement)
            val paused = peer.announcement.flags and DiscoveryAnnouncement.FLAG_STREAM_PAUSED != 0
            val full = peer.announcement.isFullStreamOffer()
            val parts = buildList {
                add(
                    when (relays) {
                        0 -> getString(R.string.people_reach_nearby)
                        1 -> getString(R.string.people_reach_via_one)
                        else -> getString(R.string.people_reach_via, relays)
                    },
                )
                if (paused) add(getString(R.string.stream_paused))
                if (full) add(getString(R.string.stream_full))
                if (!supported) add(getString(R.string.stream_unsupported))
            }
            // Broadcaster: a title when one exists (unconfirmed until joined), else "Live from <name>".
            val prefix = peer.announcement.nodeIdPrefix.toHexKey()
            val label = PeerLabels.forPeer(prefix, contactNames[prefix])
            val advertisedTitle = if (PeerLabelFeature.enabled) StreamTitles.board.title(peer.announcement.streamToken) else null
            val signedTitle = label.streamTitle?.takeIf { label.streamTitleConfirmed }
            val title = when {
                signedTitle != null -> getString(R.string.stream_row_title, signedTitle)
                advertisedTitle != null -> getString(R.string.stream_title_unconfirmed, advertisedTitle)
                else -> getString(R.string.stream_row_live_from, label.listName())
            }
            ListRow(
                title = title,
                subtitle = parts.joinToString(", "),
                iconRes = if (relays > 0) R.drawable.ic_relay else R.drawable.ic_live,
                iconTintRes = if (supported && !full) R.color.live_red else R.color.text_secondary,
                enabled = supported && !full,
            )
        }
        streamList.adapter = ListRowAdapter(this, rows)
    }

    private fun stopNearbyStreamDiscovery() {
        discoveryHandler.removeCallbacksAndMessages(null)
        nearbyStreamDiscovery?.close()
        nearbyStreamDiscovery = null
    }

    private fun showPrivateCallBrowser(restartDelayMs: Long = 0L) {
        stopStreaming("Stopped.")
        stopNearbyStreamDiscovery()
        stopIdleCameraPreview()
        appScreen = AppScreen.CALL_BROWSER
        sessionRole = SessionRole.SEND
        homeScreen.visibility = View.GONE
        cameraScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.VISIBLE
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = true)
        if (restartDelayMs > 0L) {
            privateCallDiscoveryHandler.postDelayed(
                {
                    if (appScreen == AppScreen.CALL_BROWSER && privateCall == null) {
                        ensurePermissionsAndRefresh()
                    }
                },
                restartDelayMs,
            )
        } else {
            ensurePermissionsAndRefresh()
        }
    }

    private fun showPrivateCallScreen(message: String) {
        stopPrivateCallDiscovery()
        appScreen = AppScreen.CALL
        homeScreen.visibility = View.GONE
        cameraScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callScreen.visibility = View.VISIBLE
        privateCallRemoteVideoStarted = false
        callRemotePreview.alpha = 0f
        callScreenController.reset()
        callScreenController.setForeground(activityResumed)
        intercomCallUi.reset()
        showCallStatusText(message)
        callRemoteCameraOff.visibility = View.GONE
        callChromeAutoHide.reset()
        privateCallLinkDetails.clear()
        callSecurityChip.visibility = View.GONE
        hideCallParty()
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = false)
        callRemotePreview.post {
            callRemotePreview.surfaceTexture?.let { texture ->
                privateCallRemoteSurface?.release()
                privateCallRemoteSurface = Surface(texture)
            }
        }
    }

    @SuppressLint("MissingPermission")
    /** Logs an experimental CoC -> GATT choice: requested and effective are always both stated. */
    private fun logCocTransportChoice(event: String, choice: CocTransportChoice, detail: String? = null) {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "call transport: event=$event | ${choice.logLine()}" + (detail?.let { " | detail=$it" } ?: ""),
        )
    }

    /** Shows the visible "Using BLE GATT" notice for an automatic choice. */
    private fun showCocTransportNotice(choice: CocTransportChoice) {
        val notice = choice.notice ?: return
        callBrowserStatus.text = notice
        Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
    }

    /**
     * [forcedChoice] is a one-call GATT choice for a GATT peer from an L2CAP session (experimental
     * CoC -> GATT fallback); otherwise the transport follows the flag, the CoC registration count
     * and earlier failures, which with the flag off is exactly the selected transport.
     */
    private fun startPrivateCallEndpoint(forcedChoice: CocTransportChoice? = null) {
        val onCallScreen = appScreen == AppScreen.CALL_BROWSER
        // Always reachable (CallReachability): Home starts the same endpoint, without scanning.
        val homeStart = !onCallScreen && reachabilityScreen() == ReachabilityScreen.HOME &&
            currentReachabilityPlan().endpoint
        if (onCallScreen && !hasRequiredPermissions()) return
        if (!onCallScreen && !homeStart) return
        if (onCallScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callLockScreenNeedsNotificationPermission(
                DebugTunables.callLockScreen || CallUx.settings.callStyleNotifications,
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            ) && !callNotificationPermissionAsked
        ) {
            callNotificationPermissionAsked = true
            callNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val adapter = bluetoothAdapter ?: return
        if (privateCall == null) {
            val selectedCodec = codecs.getOrNull(codecSpinner.selectedItemPosition)
            if (selectedCodec == null) {
                callBrowserStatus.text = "This phone has no supported video encoder."
                return
            }
            val transportChoice = forcedChoice
                ?: CocRegistrations.session.choose(outgoingTransport, CocAutoGatt.current)
            val endpointTransport = transportChoice.effective
            callTransportChoice = transportChoice
            callEndpointTransport = endpointTransport
            if (transportChoice.autoGattEnabled) {
                logCocTransportChoice("endpoint", transportChoice)
                if (transportChoice.automatic && forcedChoice == null) showCocTransportNotice(transportChoice)
            }
            val fingerprint = privateCallStackFingerprint()
            val codec = privateCallCodec(
                selectedCodec,
                codecs,
                fingerprint,
                endpointTransport,
            )
            if (codec != selectedCodec) {
                Log.i(
                    "BluetoothVideo",
                    "Private-call compatibility selected ${codec.displayName} for the legacy Qualcomm stack",
                )
            }
            val callSettings = privateCallVideoSettings(
                settings,
                endpointTransport,
                fingerprint,
                preserveSelectedBitrate = callBitrateAdapts(),
            )
            if (BuildConfig.DEBUG) Log.i(
                "BluetoothVideo",
                "BENCHMARK call selected: encoder=${selectedCodec.encoderName} | " +
                    "width=${settings.resolution.width} | height=${settings.resolution.height} | " +
                    "fps=${settings.fps} | bitrateKbps=${settings.bitRate / 1_000} | " +
                    "playbackMs=${receiverPlaybackDelay.delayMs} | rateControl=${encoderRateControl.name} | " +
                    "thermalReduction=$thermalReductionOn (setting=$automaticThermalReductionEnabled)",
            )
            privateCallCodec = codec
            privateCallSettings = callSettings
            privateCallPhase = null
            lateinit var endpoint: AndroidPrivateVideoCall
            val callTransport = endpointTransport
            endpoint = AndroidPrivateVideoCall(
                this,
                adapter,
                callTransport,
                codec,
                codecs,
                callSettings,
                outgoingVideoRotationDegrees(),
                remoteSurfaceProvider = {
                    privateCallRemoteSurface
                        ?: callRemotePreview.surfaceTexture?.let(::Surface)?.also {
                            privateCallRemoteSurface = it
                        }
                        ?: error("Call video surface is not ready")
                },
                playbackDelayProfile = receiverPlaybackDelay,
                phyPreference = debugBlePhyPreference,
                ioConfig = streamIoConfigFor(outgoingTransport),
                supportedVideoModes = { activeCodec, bitRate ->
                    deviceVideoCapabilities.modesFor(activeCodec, bitRate, cameraLens)
                },
                debugTraceSessionName = debugMediaTraceSession,
                debugGattDeferConnectionPriority = debugGattDeferConnectionPriority,
                debugPipelineTiming = debugCallPipelineTiming,
                voiceCallsEnabled = IntercomFeatures.current.voiceCalls,
                onIncomingCallType = { type ->
                    runOnUiThread { if (privateCall === endpoint) onIncomingPrivateCallType(type) }
                },
                storeForwardNode = if (StoreForwardFeature.enabled) {
                    { StoreForwardRuntime.node(applicationContext) }
                } else {
                    null
                },
                onMessageSync = { result ->
                    Log.i("BluetoothVideo", "Store-forward exchange: ${result.summary()}")
                    if (StoreForwardAutoSyncFeature.enabled) runOnUiThread { onAutoSyncExchangeResult(result) }
                    val opened = result.opened.size
                    if (opened > 0) runOnUiThread {
                        Toast.makeText(this, "$opened new message${if (opened == 1) "" else "s"}", Toast.LENGTH_LONG).show()
                    }
                },
                onMessageSyncFailed = { detail ->
                    if (StoreForwardAutoSyncFeature.enabled) runOnUiThread {
                        val scheduler = autoSyncScheduler
                        val key = scheduler?.inFlight()
                        if (scheduler != null && key != null) {
                            scheduler.onConnectFailed(key, SystemClock.elapsedRealtime())
                            logAutoSync("connect_failed", key, "detail=${detail.replace(' ', '_').take(80)}")
                        }
                    }
                },
                onStatus = { message ->
                    Log.i("BluetoothVideo", message)
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            // Technical diagnostics stay in the log line above, not on screen.
                            val shown = displayStatus(message)
                            if (shown == null) notePrivateCallLinkDetail(message)
                            if (shown != null) {
                                if (appScreen == AppScreen.CALL) showCallStatusText(shown)
                                else callBrowserStatus.text = shown
                            }
                        }
                    }
                },
                onPhase = { callState ->
                    runOnUiThread {
                        if (privateCall === endpoint) updatePrivateCallUi(callState)
                    }
                },
                onIncoming = { peerLabel ->
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        privateCallReturnHome = reachabilityScreen() == ReachabilityScreen.HOME
                        showPrivateCallScreen(
                            "Incoming encrypted call from ${incomingPeerName(peerLabel)}",
                        )
                        intercomCallUi.setPeerLabel(incomingPeerName(peerLabel))
                        privateCallDialledRelays = null
                        showCallParty(incomingPeerName(peerLabel), R.string.call_incoming_video)
                        callPartyState.text = incomingStateText(peerLabel, video = true)
                        showCallSubject(
                            PeerLabels.forPeer(peerLabel.lowercase().take(12), null).callSubject
                                ?.let { getString(R.string.call_subject_line, it) },
                        )
                        incomingCallControls.visibility = View.VISIBLE
                        activeCallControls.visibility = View.GONE
                        cancelCallButton.visibility = View.GONE
                        ActiveVideoSessionService.start(
                            this,
                            usesCamera = false,
                            statusText = "Incoming encrypted call. Tap to answer.",
                        )
                        startPrivateCallRinging()
                        callLockScreen.showIncomingAlert(incomingPeerName(peerLabel))
                    }
                },
                onRemoteVideoFormat = { header ->
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        if (BuildConfig.DEBUG) Log.i(
                            "BluetoothVideo",
                            "BENCHMARK call remote: codec=${header.codecId} | width=${header.width} | " +
                                "height=${header.height} | fps=${header.fps} | rotation=${header.rotationDegrees}",
                        )
                        privateCallSurfaceFormat = SurfaceVideoFormat(
                            header.width,
                            header.height,
                            header.rotationDegrees,
                            videoScaleMode,
                        )
                        privateCallRemoteVideoStarted = true
                        debugPrivateCallSurfacePending = BuildConfig.DEBUG
                        if (privateCallContinuityTracker == null) {
                            privateCallContinuityTracker = RenderedFrameContinuityTracker(
                                header.fps,
                                privateCallMediaStartedAtNs.takeIf { it > 0L } ?: System.nanoTime(),
                            )
                            privateCallContinuityTracker?.setActive(
                                activityResumed && callRemoteCameraOff.visibility != View.VISIBLE,
                            )
                        }
                        applyPrivateCallSurfaceTransform()
                    }
                },
                onRemoteCameraEnabled = { enabled ->
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call remote_camera enabled=$enabled")
                            callRemoteCameraOff.visibility = if (enabled) View.GONE else View.VISIBLE
                            callScreenController.setRemoteCameraEnabled(enabled)
                            if (enabled) debugPrivateCallSurfacePending = BuildConfig.DEBUG
                            privateCallContinuityTracker?.setActive(enabled && activityResumed)
                            if (intercomCallUi.onRemoteCamera(enabled)) {
                                onPrivateCallLayoutChanged(if (enabled) "remote_video_on" else "remote_video_off")
                            }
                        }
                    }
                },
                onRemoteMuted = { muted ->
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call remote_mute muted=$muted")
                            setCallStatus(if (muted) "Peer microphone muted" else "Peer microphone active")
                        }
                    }
                },
                onStartLocalMedia = { activeCallSettings, activeAudioBitRate, activeCallCodec, activePlayback ->
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            privateCallSettings = activeCallSettings
                            privateCallActiveAudioBitRate = activeAudioBitRate
                            privateCallCodec = activeCallCodec
                            if (BuildConfig.DEBUG) Log.i(
                                "BluetoothVideo",
                                "BENCHMARK call effective: encoder=${activeCallCodec.encoderName} | " +
                                    "codec=${activeCallCodec.protocolId} | width=${activeCallSettings.resolution.width} | " +
                                    "height=${activeCallSettings.resolution.height} | fps=${activeCallSettings.fps} | " +
                                    "bitrateKbps=${activeCallSettings.bitRate / 1_000} | audioKbps=${activeAudioBitRate / 1_000} | " +
                                    "playbackMs=${activePlayback.delayMs} | rateControl=${encoderRateControl.name}",
                            )
                            val profileLabel = privateCallProfileLabel(
                                activeCallSettings,
                                activeAudioBitRate,
                                activeCallCodec,
                                activePlayback,
                            ) + if (debugRawInputFile != null) "\nAutomated motion test · recorded scene" else ""
                            // Hidden behind the info button; the details stay in the log.
                            Log.i("BluetoothVideo", "Call details: " + profileLabel.replace('\n', ' '))
                            callScreenController.setProfileText(profileLabel)
                            privateCallLinkRateLimitBps = null
                            privateCallLinkFps = null
                            privateCallRateController = if (encoderRateControl == EncoderRateControl.AUTOMATIC) {
                                Log.i(
                                    "BluetoothVideo",
                                    "Call rate adaptation armed (Automatic): requested ${activeCallSettings.bitRate / 1_000} kbps",
                                )
                                LinkRateController(
                                    activeCallSettings.bitRate,
                                    audioBps = activeAudioBitRate,
                                    // A first call with this peer starts at about half the measured link
                                    // (both directions share it), not at the full request; with the
                                    // learned start on, an unmeasured link starts at 400 kbps too.
                                    memory = if (startRateGuardOn()) {
                                        LinkCapacity.sustainedRateMemory(callTransport, "call") {
                                            CallStartRate.unknownPeerStartBps(
                                                LinkCapacity.measuredLinkKbps(callTransport),
                                                activeAudioBitRate,
                                            )
                                        }
                                    } else {
                                        LinkCapacity.sustainedRateMemory(callTransport, "call", buildKeyed = true) {
                                            LinkCapacity.measuredLinkKbps(callTransport)?.let { kbps ->
                                                (kbps * 1_000 * CALL_LINK_SHARE).toInt() - activeAudioBitRate
                                            }
                                        }
                                    },
                                    // Experimental bounded media age: the call's video age budget is congestion.
                                    congestedBacklogMs = if (DebugTunables.experimentalBoundedMediaAge) {
                                        BoundedMediaAge.callVideoQueueBudgetMs(
                                            0,
                                            BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS,
                                        ).toInt()
                                    } else {
                                        LinkRateController.CONGESTED_BACKLOG_MS
                                    },
                                    frameRateLadder = FrameRateLadder(
                                        activeCallSettings.fps,
                                        maxOf(MIN_CALL_FPS, activeCallSettings.fps / 3),
                                        floorBps = if (DebugTunables.callLadderFromRequest) {
                                            null
                                        } else {
                                            FrameRateLadder.floorBpsFor(
                                                activeCallSettings.resolution.width,
                                                activeCallSettings.resolution.height,
                                                activeCallSettings.fps,
                                            )
                                        },
                                    ),
                                    deliveryProbing = DebugTunables.deliveryRateControl,
                                    rememberCuts = !DebugTunables.deliveryReports,
                                ).also { controller ->
                                    if (startRateGuardOn()) {
                                        // The learned start applies from the first frame (startPrivateCallMedia).
                                        val startBps = controller.seed(PRIVATE_CALL_RATE_LINK, endpoint.adjacentPeerLabel, System.nanoTime())
                                        privateCallLinkRateLimitBps = startBps.takeIf { it < activeCallSettings.bitRate }
                                        privateCallLinkFps = controller.frameRateFor(startBps).takeIf { it < activeCallSettings.fps }
                                        Log.i(
                                            "BluetoothVideo",
                                            "Call rate adaptation: starting at ${startBps / 1_000} of ${activeCallSettings.bitRate / 1_000} kbps " +
                                                "(learned or conservative start)",
                                        )
                                    }
                                }
                            } else {
                                null
                            }
                            privateCallResolutionLadder = newPrivateCallResolutionLadder(activeCallSettings, activeCallCodec)
                            startPrivateCallMedia()
                        }
                    }
                },
                onLocalKeyFrameNeeded = { privateCallCameraEncoder?.requestKeyFrame() },
                onLinkSample = ::onPrivateCallLinkSample,
                fitCallVideo = { requested, activeCallCodec ->
                    // Experimental call encoder fit (default off). Raw benchmark inputs fix the geometry.
                    if (!DebugTunables.callEncoderFit || debugRawInputFile != null) {
                        requested
                    } else {
                        val fitStore = CallEncoderFitPreferences(this)
                        CallEncoderFit.fitted(
                            requested,
                            deviceVideoCapabilities.modesFor(activeCallCodec, requested.bitRate, cameraLens),
                        ) { width, height, fps -> fitStore.isOverloaded(nativeEncoderSpeedKey(width, height, fps)) }
                    }
                },
                onPeerLabels = { prefix ->
                    // Experimental peer labels: the other person's verified name arrived (the callee's
                    // only with Accept, R1); refresh the shown name without changing the call state.
                    runOnUiThread {
                        if (privateCall !== endpoint || !::callPartyPanel.isInitialized) return@runOnUiThread
                        if (callPartyPanel.visibility == View.VISIBLE) {
                            val name = incomingPeerName(prefix)
                            privateCallPartyName = name
                            callPartyName.text = name
                        }
                    }
                },
                onCocConnectFailed = { detail ->
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        val retry = CocRegistrations.session.onCocFailure(
                            outgoingTransport, CocAutoGatt.current, CocFallbackReason.CONNECT_FAILED,
                        ) ?: return@runOnUiThread
                        cocRetryPeer = lastOutgoingCallPeer
                        logCocTransportChoice("connect_failed", retry, detail)
                    }
                },
                onEnded = { message ->
                    Log.i("BluetoothVideo", "Private call ended: $message")
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        val returnHome = privateCallReturnHome && CallReachabilityFeature.enabled
                        privateCallReturnHome = false
                        stopPrivateCallRinging()
                        callLockScreen.apply(null)
                        cleanupPrivateCallMedia(keepService = returnHome)
                        // Home reachability: hand the running service back to connectedDevice only
                        // instead of stopping it, so a call that ended in the background leaves the
                        // phone reachable (a background start after a stop would be refused).
                        if (returnHome) keepReachabilityServiceAfterCall()
                        endpoint.close()
                        privateCall = null
                        // Logged after the teardown, outside the log-loss window that follows the final reports.
                        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", privateCallEndedUiEvidence(message))
                        privateCallSettings = null
                        privateCallResolutionLadder = null
                        privateCallRateController = null
                        privateCallLinkRateLimitBps = null
                        privateCallLinkFps = null
                        privateCallCodec = null
                        privateCallAvailable = false
                        if (returnHome && !isFinishing && !isDestroyed) {
                            // The call came in through Home reachability: back to Home, available again
                            // after the same GATT server pause as the Call screen.
                            showHomeScreen(
                                stopActive = false,
                                reachabilityDelayMs = if (callTransport == BluetoothTransportOption.BLE_GATT) {
                                    GATT_CALL_RESTART_DELAY_MS
                                } else {
                                    0L
                                },
                            )
                            Toast.makeText(this, "$message. Reachable for calls again.", Toast.LENGTH_SHORT).show()
                        } else if (!isFinishing && !isDestroyed) {
                            showPrivateCallBrowser(
                                if (callTransport == BluetoothTransportOption.BLE_GATT) {
                                    GATT_CALL_RESTART_DELAY_MS
                                } else {
                                    0L
                                },
                            )
                            callBrowserStatus.text = "$message. Available for another call."
                            if (cocRetryPeer != null) {
                                CocRegistrations.session.choose(outgoingTransport, CocAutoGatt.current).notice
                                    ?.let { callBrowserStatus.text = "$it. $message." }
                            }
                        }
                    }
                },
            )
            privateCall = endpoint
            privateCallAvailabilityStarting = true
            callBrowserStatus.setText(R.string.people_starting)
            thread(name = "PrivateCallAvailability") {
                val result = runCatching { endpoint.startAvailability() }
                runOnUiThread {
                    if (privateCall !== endpoint) {
                        endpoint.close()
                        return@runOnUiThread
                    }
                    privateCallAvailabilityStarting = false
                    privateCallAvailable = result.isSuccess
                    result.onFailure { error ->
                        // The A15s stack stops accepting LE CoC listens after ~15 registrations per
                        // Bluetooth process (bmaraw-ab-call-9c121b30-*); this used to fail silently and
                        // the phone neither became callable nor scanned for others.
                        Log.w("BluetoothVideo", "Private call availability failed over ${callTransport.shortLabel}", error)
                        endpoint.close()
                        privateCall = null
                        // Experimental CoC -> GATT fallback (default off): retry this session over GATT.
                        val gattRetry = if (callTransport == BluetoothTransportOption.BLE_L2CAP) {
                            CocRegistrations.session.onCocFailure(
                                outgoingTransport, CocAutoGatt.current, CocFallbackReason.AVAILABILITY_FAILED,
                            )
                        } else {
                            null
                        }
                        if (gattRetry != null) {
                            logCocTransportChoice("availability_failed", gattRetry, error.message)
                            startPrivateCallEndpoint()
                            return@onFailure
                        }
                        callBrowserStatus.text = if (callTransport == BluetoothTransportOption.BLE_L2CAP) {
                            "Can't receive calls over L2CAP right now (${error.message}). " +
                                "Switch to BLE GATT or restart Bluetooth."
                        } else {
                            "Private calls unavailable: ${error.message}"
                        }
                    }.onSuccess {
                        // The Classic "make visible" dialog belongs to the Call screen, never Home.
                        val homeEndpoint = reachabilityScreen() == ReachabilityScreen.HOME
                        if (!homeEndpoint) requestClassicVisibilityIfNeeded(callTransport)
                        if (BuildConfig.DEBUG) Log.i(
                            "BluetoothVideo",
                            "BENCHMARK call available: node=" + AndroidNodeIdentityStore().getOrCreate().nodeId
                                .toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).toHexKey(),
                        )
                        if (!homeEndpoint) {
                            ActiveVideoSessionService.start(
                                this,
                                usesCamera = false,
                                statusText = "Available for private Bluetooth calls",
                            )
                        } else {
                            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: available on ${reachabilityScreen().name}")
                            // Holds the connectedDevice service while visible; started in the
                            // background without that service, the endpoint stops again.
                            refreshCallReachability()
                        }
                        val rebuildPeer = cocRebuildPeer
                        cocRebuildPeer = null
                        if (rebuildPeer != null) {
                            // The GATT peer chosen from an L2CAP session: the endpoint now uses GATT.
                            pendingPrivateCallType = rebuildPeer.second
                            connectOutgoingPrivateCall(rebuildPeer.first)
                            return@onSuccess
                        }
                        restartPrivateCallDiscovery()
                        val retryPeer = cocRetryPeer
                        cocRetryPeer = null
                        if (retryPeer != null) startOutgoingPrivateCall(retryPeer.first, retryPeer.second)
                    }
                }
            }
            return
        }
        if (privateCallAvailabilityStarting) return
        // An endpoint kept from Home reachability: stopStreaming stopped its service.
        if (onCallScreen && privateCallAvailable) ensureReachabilityService()
        restartPrivateCallDiscovery()
    }

    private fun restartPrivateCallDiscovery() {
        if (appScreen != AppScreen.CALL_BROWSER || !hasRequiredPermissions()) return
        stopPrivateCallDiscovery()
        if (privateCallPeers.isEmpty()) {
            callBrowserStatus.setText(R.string.people_starting)
        }
        val adapter = bluetoothAdapter ?: return
        val localPrefix = AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (privateCallDiscovery !== discovery) return@runOnUiThread
                    val key = peer.announcement.nodeIdPrefix.toHexKey()
                    if (BuildConfig.DEBUG && key !in privateCallPeersLastSeen) Log.i(
                        "BluetoothVideo",
                        "BENCHMARK call discovered: node=$key | transport=${peer.announcement.transportOption().name}",
                    )
                    val observedAtMs = SystemClock.elapsedRealtime()
                    privateCallPeersLastSeen[key] = peer to observedAtMs
                    refreshPrivateCallPeers()
                    if (
                        pendingPrivateCallPeerKey == key &&
                        peer.announcement.version == pendingPrivateCallPeerVersion &&
                        privateCallPeerWasRefreshed(observedAtMs, pendingPrivateCallSelectedAtMs)
                    ) {
                        pendingPrivateCallPeerKey = null
                        connectOutgoingPrivateCall(peer)
                    } else if (StoreForwardAutoSyncFeature.enabled) {
                        maybeAutoSync(peer, observedAtMs)
                    }
                }
            },
            {
                runOnUiThread {
                    if (privateCallDiscovery !== discovery) return@runOnUiThread
                    privateCallDiscovery = null
                    if (appScreen == AppScreen.CALL_BROWSER) {
                        privateCallDiscoveryHandler.postDelayed(
                            ::restartPrivateCallDiscovery,
                            DISCOVERY_RESTART_MS,
                        )
                    }
                }
            },
            { message ->
                runOnUiThread {
                    if (privateCallDiscovery !== discovery) return@runOnUiThread
                    privateCallDiscovery = null
                    callBrowserStatus.text = message
                    privateCallDiscoveryHandler.postDelayed(
                        ::restartPrivateCallDiscovery,
                        SCAN_ERROR_RETRY_MS,
                    )
                }
            },
            acceptAnnouncement = { announcement ->
                announcement.isPrivateCallOffer() &&
                    // No relay of this app advertises a longer route (PrivateCallRelayChain.MAX_RELAYS).
                    PrivateCallRelayChain.callerRelayHops(announcement.routeCost) != null &&
                    // Rotating IDs, phase 1: only direct LE version 5 offers can be verified (startOutgoing).
                    (!announcement.rotating || (announcement.routeCost == 0 &&
                        announcement.flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT == 0)) &&
                    CocAutoGattSession.acceptsAdvertised(
                        outgoingTransport, currentCallTransport, announcement.transportOption(), CocAutoGatt.current,
                    ) &&
                    !announcement.nodeIdPrefix.contentEquals(localPrefix)
            },
            scanDurationMs = CONTINUOUS_SCAN_MS,
            reportDuplicates = true,
            continuousLowLatency = true,
            acceptRotating = RotatingDiscoveryFeature.enabled,
        )
        privateCallDiscovery = discovery
        discovery.start()
        privateCallDiscoveryHandler.postDelayed(::expirePrivateCallPeers, NEARBY_EXPIRY_CHECK_MS)
    }

    private fun refreshPrivateCallPeers() {
        val nowMs = SystemClock.elapsedRealtime()
        privateCallPeersLastSeen.entries.removeAll {
            !privateCallPeerIsFresh(it.value.second, nowMs)
        }
        val updatedPeers = privateCallPeersLastSeen.values.map { it.first }
            .sortedBy(BleL2capPeer::label)
        val listChanged = privateCallPeers.size != updatedPeers.size ||
            privateCallPeers.zip(updatedPeers).any { (old, new) ->
                old.label != new.label ||
                    old.device.address != new.device.address ||
                    !old.announcement.nodeIdPrefix.contentEquals(new.announcement.nodeIdPrefix)
            }
        privateCallPeers = updatedPeers
        if (listChanged) updatePrivateCallPeerList()
        completePendingCallBack()
        val status = if (privateCallPeers.isEmpty()) {
            getString(R.string.people_starting)
        } else {
            resources.getQuantityString(R.plurals.people_nearby_count, privateCallPeers.size, privateCallPeers.size)
        }
        val autoNotice = callTransportChoice?.takeIf { callEndpointTransport != null }?.notice
        val statusWithNotice = if (autoNotice != null) "$autoNotice. $status" else status
        if (callBrowserStatus.text.toString() != statusWithNotice) callBrowserStatus.text = statusWithNotice
    }

    private fun expirePrivateCallPeers() {
        if (appScreen != AppScreen.CALL_BROWSER || privateCallDiscovery == null) return
        refreshPrivateCallPeers()
        privateCallDiscoveryHandler.postDelayed(::expirePrivateCallPeers, NEARBY_EXPIRY_CHECK_MS)
    }

    /** People nearby as a call list: Contacts (saved when verified) first, then Nearby. */
    private fun updatePrivateCallPeerList() {
        if (!::callPeerList.isInitialized) return
        val voice = IntercomFeatures.current.voiceCalls
        val colliding = PeerLabels.collidingShortHandles(
            privateCallPeers.filterNot { it.announcement.rotating }.map { it.announcement.nodeIdPrefix.toHexKey() },
        )
        val people = privateCallPeers.map { peer ->
            val prefix = peer.announcement.nodeIdPrefix.toHexKey()
            val relays = advertisedRelayCount(peer.announcement.routeCost) ?: 0
            PeopleItem.Person(
                key = prefix,
                // Rotating IDs (design 5.4): a stranger's 15-minute ID is never shown as a handle or
                // matched to a contact before the handshake ("Nearby phone", no handle, no check mark).
                name = if (peer.announcement.rotating) {
                    getString(R.string.people_rotating_phone)
                } else {
                    PeerLabels.forPeer(prefix, contactNames[prefix]).listName(fullHandle = prefix in colliding)
                },
                reach = when (relays) {
                    0 -> getString(R.string.people_reach_nearby)
                    1 -> getString(R.string.people_reach_via_one)
                    else -> getString(R.string.people_reach_via, relays)
                },
                relayed = relays > 0,
                avatarColor = identityColor(prefix),
                showVoice = voice,
            )
        }
        peopleAdapter.submit(
            peopleSections(
                people,
                contactNames.keys,
                getString(R.string.people_section_contacts),
                getString(R.string.people_section_nearby),
            ),
        )
    }

    private val peopleAdapter by lazy {
        PeopleAdapter(this) { key, video ->
            privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == key }?.let { peer ->
                withWifiCheck {
                    // Experimental peer labels: optional subject, signed and sent inside the call only.
                    promptLabelText(LabelKind.CALL_SUBJECT, R.string.call_subject_title, R.string.call_subject_hint, null) { subject ->
                        if (PeerLabelFeature.enabled) PeerLabelFeature.pendingCallSubject = subject
                        callPeerDirectly(peer, if (video) CallType.VIDEO else CallType.VOICE)
                    }
                }
            }
        }
    }

    private fun peerForListPosition(position: Int): BleL2capPeer? {
        val key = peopleAdapter.personAt(position)?.key ?: return null
        return privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == key }
    }

    /** The row's call buttons: the chosen call type directly (same checks as choosing from the menu). */
    private fun callPeerDirectly(peer: BleL2capPeer, type: CallType) {
        if (privateCall?.suspendMessageSync() == true) {
            callBrowserStatus.text = "Pausing the message exchange for your call. Tap the person again in a moment."
            return
        }
        startOutgoingPrivateCall(peer, if (IntercomFeatures.current.voiceCalls) type else CallType.VIDEO)
    }

    /** Saved names of verified contacts by node-ID prefix (read off the main thread). */
    @Volatile private var contactNames: Map<String, String> = emptyMap()

    private fun refreshContactNames() {
        val context = applicationContext
        Thread {
            val names = runCatching { SecurityUiBackend.current.contactNames(context) }.getOrDefault(emptyMap())
            runOnUiThread {
                if (names != contactNames) {
                    contactNames = names
                    updatePrivateCallPeerList()
                }
            }
        }.start()
    }

    /** One clean name for a discovered person (never raw hex). */
    private fun peerName(peer: BleL2capPeer): String {
        if (peer.announcement.rotating) return getString(R.string.people_rotating_phone)
        val prefix = peer.announcement.nodeIdPrefix.toHexKey()
        return PeerLabels.forPeer(prefix, contactNames[prefix]).displayName
    }

    /** One clean name for the authenticated peer of an incoming call ([label] is its 12-hex node label). */
    private fun incomingPeerName(label: String): String {
        val prefix = label.lowercase().take(12)
        return PeerLabels.forPeer(prefix, contactNames[prefix]).callerLine()
    }

    /** A self-claimed name is never shown without the handle (R5): it goes on the state line. */
    private fun incomingStateText(label: String, video: Boolean): String {
        val prefix = label.lowercase().take(12)
        val peerLabel = PeerLabels.forPeer(prefix, contactNames[prefix])
        val state = getString(if (video) R.string.call_incoming_video else R.string.call_incoming_voice)
        return if (peerLabel.nameKind == PeerLabel.NameKind.SELF_NAMED) {
            getString(R.string.call_incoming_with_handle, state, peerLabel.handle)
        } else {
            state
        }
    }

    // ----- Relay control (design decision 2026-10-10) -----

    private fun isBenchmarkLaunch(): Boolean = intent.extras?.keySet()?.any { it.startsWith("test_") } == true

    /**
     * Forwarding for a join of [streamToken]: the per-stream choice, else the global default.
     * Normal use pauses it below 20 % battery (not charging) or in Battery Saver; benchmark launches
     * are unaffected (they set test_forward_received_streams explicitly).
     */
    private fun effectiveForwarding(streamToken: Int): Boolean {
        val wanted = forwardReceivedStreams
        if (!wanted || isBenchmarkLaunch()) return wanted
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        val saver = powerManager.isPowerSaveMode
        val pause = relayAutoPaused(level, charging, saver)
        if (pause) Log.i("BluetoothVideo", "Relay auto-pause: battery=$level% charging=$charging saver=$saver; not forwarding this stream")
        return !pause
    }

    // ----- Recent calls (Home) -----

    /** A call back waits for that person to appear in discovery: prefix, video, deadline. */
    private var pendingCallBack: Triple<String, Boolean, Long>? = null

    private fun recordRecentCall() {
        val endpoint = privateCall ?: return
        val prefix = endpoint.endToEndPeerNodeId?.take(12) ?: return
        if (isBenchmarkLaunch()) return // automated runs leave no history
        val call = RecentCall(prefix, endpoint.localCallType == CallType.VIDEO, System.currentTimeMillis())
        val context = applicationContext
        Thread { runCatching { RecentCalls.record(context, call) } }.start()
    }

    private fun updateRecentCalls() {
        val list = findViewById<ViewGroup>(R.id.homeRecentList) ?: return
        val empty = findViewById<View>(R.id.homeRecentEmpty)
        val context = applicationContext
        Thread {
            val calls = runCatching { RecentCalls.load(context) }.getOrDefault(emptyList()).take(HOME_RECENT_ROWS)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                list.removeAllViews()
                calls.forEach { call -> list.addView(recentRow(list, call)) }
                empty.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    /** The shared person row (item_person_row), filled for a recent call. */
    private fun recentRow(parent: ViewGroup, call: RecentCall): View {
        val row = layoutInflater.inflate(R.layout.item_person_row, parent, false)
        val label = PeerLabels.forPeer(call.nodePrefixHex, contactNames[call.nodePrefixHex])
        val name = label.callerLine()
        val time = android.text.format.DateUtils.getRelativeTimeSpanString(
            call.atMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        row.findViewById<TextView>(R.id.personAvatar).apply {
            text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.surface_variant)
        }
        row.findViewById<TextView>(R.id.personName).text = name
        row.findViewById<TextView>(R.id.personReach).apply {
            text = getString(if (call.video) R.string.recent_video else R.string.recent_voice, time)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
        val voiceAllowed = IntercomFeatures.current.voiceCalls
        row.findViewById<View>(R.id.personVoiceButton).apply {
            visibility = if (voiceAllowed) View.VISIBLE else View.GONE
            contentDescription = getString(R.string.people_voice_call, name)
            setOnClickListener { callBack(call.nodePrefixHex, video = false, name = name) }
        }
        row.findViewById<View>(R.id.personVideoButton).apply {
            contentDescription = getString(R.string.people_video_call, name)
            setOnClickListener { callBack(call.nodePrefixHex, video = true, name = name) }
        }
        row.setOnClickListener { callBack(call.nodePrefixHex, video = call.video || !voiceAllowed, name = name) }
        row.contentDescription = getString(R.string.peer_row_description, name, row.findViewById<TextView>(R.id.personReach).text)
        return row
    }

    /** One tap call back: open People and call this person as soon as discovery sees them. */
    private fun callBack(prefix: String, video: Boolean, name: String) {
        pendingCallBack = Triple(prefix, video, SystemClock.elapsedRealtime() + CALL_BACK_WAIT_MS)
        showPrivateCallBrowser()
        callBrowserStatus.text = getString(R.string.recent_call_back, name)
        privateCallDiscoveryHandler.postDelayed({
            val pending = pendingCallBack ?: return@postDelayed
            if (pending.first == prefix && SystemClock.elapsedRealtime() >= pending.third) {
                pendingCallBack = null
                if (appScreen == AppScreen.CALL_BROWSER) callBrowserStatus.text = getString(R.string.recent_not_nearby, name)
            }
        }, CALL_BACK_WAIT_MS)
    }

    /** Called when the people list changes: completes a pending call back. */
    private fun completePendingCallBack() {
        val (prefix, video, deadline) = pendingCallBack ?: return
        if (SystemClock.elapsedRealtime() > deadline) {
            pendingCallBack = null
            return
        }
        val peer = privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == prefix } ?: return
        pendingCallBack = null
        withWifiCheck { callPeerDirectly(peer, if (video) CallType.VIDEO else CallType.VOICE) }
    }

    /** Fills the shared empty-state component (view_empty_state) of one screen. */
    private fun bindEmptyState(rootId: Int, icon: Int, title: Int, text: Int, action: Int, onAction: () -> Unit) {
        val root = findViewById<View>(rootId)
        root.findViewById<ImageView>(R.id.emptyStateIcon).setImageResource(icon)
        root.findViewById<TextView>(R.id.emptyStateTitle).setText(title)
        root.findViewById<TextView>(R.id.emptyStateText).setText(text)
        root.findViewById<TextView>(R.id.emptyStateAction).apply {
            setText(action)
            setOnClickListener { onAction() }
        }
    }

    /** Hides a browser's status line while its list is empty and the line only repeats the empty state. */
    private fun bindBrowserStatus(status: TextView, list: android.widget.ListView, generic: Set<String>) {
        val sync = {
            val empty = list.adapter == null || list.adapter.isEmpty
            val text = status.text?.toString().orEmpty()
            status.visibility = if (text.isBlank() || (empty && text in generic)) View.GONE else View.VISIBLE
        }
        status.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = sync()
        })
        list.viewTreeObserver.addOnGlobalLayoutListener { sync() }
        sync()
    }

    /** Full peer identity (node prefix, Bluetooth address, transport) behind the friendly list label. */
    private fun showPrivateCallPeerDetails(peer: BleL2capPeer) {
        val prefix = peer.announcement.nodeIdPrefix.toHexKey()
        val handle = if (peer.announcement.rotating) {
            getString(R.string.people_rotating_handle)
        } else {
            PeerLabels.forPeer(prefix, contactNames[prefix]).handle
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(peerName(peer))
            .setMessage(
                getString(
                    R.string.people_details,
                    handle,
                    peer.advertisedName?.takeIf { it.isNotBlank() } ?: "-",
                    peer.device.address,
                    peer.announcement.transportOption().shortLabel,
                    prefix,
                ),
            )
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun stopPrivateCallDiscovery() {
        autoSyncPendingKey?.let { cancelAutoSync(it, "discovery_stopped") }
        pendingPrivateCallPeerKey = null
        privateCallDiscoveryHandler.removeCallbacksAndMessages(null)
        privateCallDiscovery?.close()
        privateCallDiscovery = null
    }

    private fun autoSyncGate(): AutoSyncGate = AutoSyncGate(
        onCallScreen = appScreen == AppScreen.CALL_BROWSER,
        endpointIdle = privateCall != null && privateCallAvailable && !privateCallAvailabilityStarting &&
            cocRebuildPeer == null && cocRetryPeer == null,
        callActive = privateCallPhase != null,
        liveMedia = sender != null || broadcaster != null || receiver != null || relay != null ||
            privateCallRelay != null || cameraEncoder != null || microphoneEncoder != null,
        exchangeRunning = privateCall?.messageSyncActive == true,
        userSelectionPending = pendingPrivateCallPeerKey != null,
        thermalBlocked = ThermalGuardPolicy.shouldBlockStreaming(thermalStatus),
    )

    private fun logAutoSync(event: String, key: String, detail: String = "") {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK sf_sync auto=$event peer=$key${if (detail.isEmpty()) "" else " $detail"}")
    }

    /** Logs a wait/skip only when it changes for that peer, so continuous discovery does not flood the log. */
    private fun logAutoSyncChange(key: String, state: String) {
        if (autoSyncLastLog[key] == state) return
        autoSyncLastLog[key] = state
        logAutoSync(state.substringBefore(' '), key, state.substringAfter(' ', ""))
    }

    private fun autoSyncSchedulerOrCreate(): StoreForwardAutoSyncScheduler = autoSyncScheduler ?: StoreForwardAutoSyncScheduler(
        AutoSyncPolicy(),
        AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).toHexKey(),
    ).also { autoSyncScheduler = it }

    /**
     * Experimental automatic sync: called from the Call screen's existing discovery for each sighting.
     * Gates on the UI thread, reads the store on one background thread (at most one evaluation at a
     * time, each peer at most every few seconds), then re-checks the gate before dialling.
     */
    private fun maybeAutoSync(peer: BleL2capPeer, observedAtMs: Long) {
        val key = peer.announcement.nodeIdPrefix.toHexKey()
        // Rotating IDs (RV3): a rotating ID never starts a connection by itself, before BMH3.
        if (peer.announcement.rotating) return
        val blocked = autoSyncGate().blockedBy()
        if (blocked != null) {
            logAutoSyncChange(key, "skip why=$blocked")
            return
        }
        val lastEval = autoSyncLastEvalMs[key]
        if (lastEval != null && observedAtMs - lastEval < AUTO_SYNC_EVAL_INTERVAL_MS) return
        if (!autoSyncEvaluating.compareAndSet(false, true)) return
        autoSyncLastEvalMs[key] = observedAtMs
        val scheduler = autoSyncSchedulerOrCreate()
        val prefix = peer.announcement.nodeIdPrefix.copyOf()
        val context = applicationContext
        autoSyncExecutor.execute {
            val outcome = runCatching {
                val node = StoreForwardRuntime.node(context)
                val ledger = StoreForwardRuntime.outboxLedger(context)
                val policy = AutoSyncPolicy()
                val need = StoreForwardAutoSyncPlanner.need(node, ledger, StoreForwardAutoSyncPlanner.contactFor(node, prefix), policy)
                need to scheduler.decide(key, SystemClock.elapsedRealtime(), need.reason(policy.pullEnabled), null)
            }
            autoSyncEvaluating.set(false)
            runOnUiThread {
                val (need, decision) = outcome.getOrElse { error ->
                    logAutoSyncChange(key, "skip why=error_${error.javaClass.simpleName}")
                    return@runOnUiThread
                }
                when (decision) {
                    is AutoSyncDecision.Skip -> logAutoSyncChange(key, "skip why=${decision.why}")
                    is AutoSyncDecision.Wait -> logAutoSyncChange(
                        key,
                        "wait reason=${decision.reason} failures=${scheduler.failures(key)} queued=${need.queued} failed=${need.failed}",
                    )
                    is AutoSyncDecision.Start -> {
                        val nowBlocked = autoSyncGate().blockedBy()
                        val current = privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == key }
                        if (nowBlocked != null || current == null || !StoreForwardAutoSyncFeature.enabled) {
                            logAutoSyncChange(key, "skip why=${nowBlocked ?: "peer_gone"}")
                            return@runOnUiThread
                        }
                        autoSyncLastLog.remove(key)
                        scheduler.begin(key, SystemClock.elapsedRealtime())
                        autoSyncPendingKey = key
                        logAutoSync(
                            "start", key,
                            "reason=${decision.reason} failures=${scheduler.failures(key)} queued=${need.queued} " +
                                "failed=${need.failed} carried=${need.carried}",
                        )
                        startOutgoingPrivateCall(current, messageSync = true, auto = true)
                    }
                }
            }
        }
    }

    /** A user tap, a requested call or discovery stopping replaced an automatic start before it connected. */
    private fun cancelAutoSync(key: String, why: String) {
        if (autoSyncPendingKey != key) return
        autoSyncPendingKey = null
        if (pendingPrivateCallPeerKey == key) {
            pendingPrivateCallPeerKey = null
            pendingPrivateCallMessageSync = false
        }
        autoSyncScheduler?.cancel(key)
        logAutoSync("preempted", key, "why=$why")
    }

    /** Any exchange result, either role: attempt counts for still-queued messages, then the peer's backoff. */
    private fun onAutoSyncExchangeResult(result: StoreForwardSyncResult) {
        val context = applicationContext
        val key = StoreForwardAutoSyncScheduler.keyOf(result.peer)
        val scheduler = autoSyncSchedulerOrCreate()
        val auto = scheduler.inFlight() == key
        val progressed = StoreForwardAutoSyncScheduler.progressed(result)
        scheduler.onResult(key, SystemClock.elapsedRealtime(), result.outcome, progressed)
        autoSyncExecutor.execute {
            val attempts = runCatching {
                StoreForwardAutoSyncPlanner.recordExchange(
                    StoreForwardRuntime.node(context), StoreForwardRuntime.outboxLedger(context), result,
                )
            }.getOrElse { emptyMap() }
            val maxAttempts = AutoSyncPolicy().maxAttempts
            logAutoSync(
                "result", key,
                "initiated=$auto outcome=${result.outcome} progressed=$progressed " +
                    "failures=${scheduler.failures(key)} still_queued=${attempts.size} " +
                    "newly_failed=${attempts.values.count { it == maxAttempts }}",
            )
        }
    }

    /** Experimental voice calls: asks Voice call or Video call; otherwise a video call as before. */
    private fun choosePrivateCallType(peer: BleL2capPeer) {
        // Experimental automatic sync: the user's tap replaces an automatic start still refreshing.
        autoSyncPendingKey?.let { cancelAutoSync(it, "user_tap") }
        // Experimental store-and-forward: a live call takes the radio from a running exchange.
        if (privateCall?.suspendMessageSync() == true) {
            callBrowserStatus.text = "Pausing the message exchange for your call. Tap the person again in a moment."
            return
        }
        val messages = StoreForwardFeature.enabled
        if (!IntercomFeatures.current.voiceCalls && !messages) {
            startOutgoingPrivateCall(peer)
            return
        }
        val name = peerName(peer)
        if (!messages) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Call $name")
                .setItems(arrayOf("Voice call", "Video call")) { _, which ->
                    startOutgoingPrivateCall(peer, if (which == 0) CallType.VOICE else CallType.VIDEO)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val options = buildList<Pair<String, () -> Unit>> {
            if (IntercomFeatures.current.voiceCalls) add("Voice call" to { startOutgoingPrivateCall(peer, CallType.VOICE) })
            add("Video call" to { startOutgoingPrivateCall(peer, CallType.VIDEO) })
            add("Exchange messages (Experimental)" to { startOutgoingPrivateCall(peer, messageSync = true) })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startOutgoingPrivateCall(
        discoveredPeer: BleL2capPeer,
        callType: CallType = CallType.VIDEO,
        messageSync: Boolean = false,
        auto: Boolean = false,
    ) {
        if (pendingPrivateCallPeerKey != null) return
        pendingPrivateCallType = callType
        pendingPrivateCallMessageSync = messageSync && StoreForwardFeature.enabled
        val key = discoveredPeer.announcement.nodeIdPrefix.toHexKey()
        pendingPrivateCallPeerKey = key
        pendingPrivateCallPeerVersion = discoveredPeer.announcement.version
        pendingPrivateCallSelectedAtMs = SystemClock.elapsedRealtime()
        callBrowserStatus.text = if (auto) {
            "Syncing messages with ${peerName(discoveredPeer)} automatically..."
        } else {
            "Refreshing this person's Bluetooth address..."
        }
        privateCallDiscoveryHandler.postDelayed(
            {
                if (pendingPrivateCallPeerKey == key) {
                    pendingPrivateCallPeerKey = null
                    if (auto && autoSyncPendingKey == key) {
                        autoSyncPendingKey = null
                        pendingPrivateCallMessageSync = false
                        autoSyncScheduler?.onConnectFailed(key, SystemClock.elapsedRealtime())
                        logAutoSync("connect_failed", key, "detail=refresh_timeout")
                        refreshPrivateCallPeers()
                    } else {
                        callBrowserStatus.text = "That person is not responding yet. Scanning continues."
                    }
                }
            },
            PRIVATE_CALL_SELECTION_REFRESH_TIMEOUT_MS,
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectOutgoingPrivateCall(discoveredPeer: BleL2capPeer) {
        val peerKey = discoveredPeer.announcement.nodeIdPrefix.toHexKey()
        val autoSync = autoSyncPendingKey == peerKey
        if (autoSync && (autoSyncGate().copy(userSelectionPending = false).blockedBy() != null || privateCall == null)) {
            // Something took the radio or the endpoint between the decision and the refresh.
            cancelAutoSync(peerKey, "gate_closed")
            pendingPrivateCallMessageSync = false
            return
        }
        if (!ensureThermalReady()) return
        val endpoint = privateCall ?: return
        // Experimental CoC -> GATT fallback (default off): a peer that advertises GATT (it fell back)
        // is called over GATT, so this endpoint is rebuilt on GATT for this call.
        if (CocAutoGatt.current.enabled && discoveredPeer.announcement.transportOption() ==
            BluetoothTransportOption.BLE_GATT && currentCallTransport != BluetoothTransportOption.BLE_GATT
        ) {
            if (autoSync) {
                // An automatic exchange never rebuilds the call endpoint; a tapped Exchange still can.
                cancelAutoSync(peerKey, "needs_transport_rebuild")
                pendingPrivateCallMessageSync = false
                refreshPrivateCallPeers()
                return
            }
            val gattChoice = CocRegistrations.session.onPeerAdvertisesGatt(outgoingTransport, CocAutoGatt.current)
            if (gattChoice != null) {
                logCocTransportChoice("peer_advertises_gatt", gattChoice, discoveredPeer.announcement.nodeIdPrefix.toHexKey())
                val type = pendingPrivateCallType
                stopPrivateCallEndpoint()
                cocRebuildPeer = discoveredPeer to type
                showCocTransportNotice(gattChoice)
                startPrivateCallEndpoint(gattChoice)
                return
            }
        }
        val peer = if (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            resolveClassicViewerPeers(listOf(discoveredPeer)).first()
        } else {
            discoveredPeer
        }
        autoSyncPendingKey = null
        stopPrivateCallDiscovery()
        if (pendingPrivateCallMessageSync) {
            pendingPrivateCallMessageSync = false
            val peerName = peerName(peer)
            val syncing = runCatching { endpoint.startMessageSync(peer) }.getOrDefault(false)
            callBrowserStatus.text = if (syncing) "Exchanging messages with $peerName..." else "Message exchange could not start."
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK store_forward sync_start peer=${peer.label} started=$syncing")
            if (autoSync) {
                logAutoSync("connecting", peerKey, "started=$syncing")
                if (!syncing) autoSyncScheduler?.onConnectFailed(peerKey, SystemClock.elapsedRealtime())
            }
            return
        }
        // Not a message exchange (an automatic start never reaches here): release its slot.
        if (autoSync) autoSyncScheduler?.cancel(peerKey)
        // Only calls are recorded for the experimental CoC -> GATT call retry, never a message exchange.
        lastOutgoingCallPeer = discoveredPeer to pendingPrivateCallType
        val started = runCatching { endpoint.startOutgoing(peer, pendingPrivateCallType) }.getOrElse { error ->
            if (privateCall === endpoint) {
                stopPrivateCallEndpoint()
                showPrivateCallBrowser()
                callBrowserStatus.text = "Call could not start: ${error.message ?: error.javaClass.simpleName}"
            }
            return
        }
        if (!started) {
            if (privateCall === endpoint && appScreen == AppScreen.CALL_BROWSER) {
                callBrowserStatus.text = "An incoming call is already connecting."
            }
            return
        }
        Log.i("BluetoothVideo", "Calling ${peer.label}")
        val peerName = peerName(peer)
        showPrivateCallScreen("Calling $peerName...")
        intercomCallUi.setPeerLabel(peerName)
        privateCallDialledRelays = advertisedRelayCount(discoveredPeer.announcement.routeCost)
        val voice = endpoint.localCallType == CallType.VOICE
        if (!voice) showCallParty(peerName, R.string.call_outgoing)
        if (voice) intercomCallUi.showPendingVoice(incoming = false)
        incomingCallControls.visibility = View.GONE
        activeCallControls.visibility = View.GONE
        cancelCallButton.visibility = View.VISIBLE
        ActiveVideoSessionService.start(
            this,
            usesCamera = !voice,
            statusText = "Calling over encrypted Bluetooth",
            usesMicrophone = true,
            ongoingCallStyle = CallUx.settings.callStyleNotifications,
        )
    }

    private fun updatePrivateCallUi(callState: PrivateCallState) {
        privateCallPhase = callState.phase
        callLockScreen.apply(callState.phase.takeIf { it != PrivateCallPhase.ENDED })
        updatePrivateCallRingback(callState.phase)
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call phase ${callState.phase.name}")
        callScreenController.setMediaPhase(
            callState.phase == PrivateCallPhase.NEGOTIATING_MEDIA || callState.phase == PrivateCallPhase.ACTIVE,
        )
        when (callState.phase) {
            PrivateCallPhase.OUTGOING_INVITE -> {
                setCallStatus("Connecting encrypted call...")
                if (::callPartyState.isInitialized) callPartyState.setText(R.string.call_outgoing)
            }
            PrivateCallPhase.OUTGOING_RINGING -> {
                setCallStatus("Ringing...")
                if (::callPartyState.isInitialized) callPartyState.setText(R.string.call_ringing)
            }
            PrivateCallPhase.INCOMING_RINGING -> Unit
            PrivateCallPhase.NEGOTIATING_MEDIA -> {
                if (privateCallMediaStartedAtNs == 0L) privateCallMediaStartedAtNs = System.nanoTime()
                hideCallParty()
                stopPrivateCallRinging()
                incomingCallControls.visibility = View.GONE
                cancelCallButton.visibility = View.GONE
                activeCallControls.visibility = View.VISIBLE
                setCallStatus(
                    if (privateCall?.localCallType == CallType.VOICE) "Starting encrypted voice call..."
                    else "Starting two-way video and audio...",
                )
            }
            PrivateCallPhase.ACTIVE -> {
                recordRecentCall()
                stopPrivateCallRinging()
                incomingCallControls.visibility = View.GONE
                cancelCallButton.visibility = View.GONE
                activeCallControls.visibility = View.VISIBLE
                setCallStatus("Encrypted private call")
                hideCallParty()
                updateCallSecurityChip()
                // Bars fade while video plays; voice-only calls keep them (nothing to look at).
                callChromeAutoHide.enabled = privateCall?.localCallType != CallType.VOICE
            }
            PrivateCallPhase.ENDED -> {
                callChromeAutoHide.reset()
                hideCallParty()
            }
        }
    }

    private fun startPrivateCallMedia() {
        if (privateCallCameraEncoder != null || privateCallMicrophoneEncoder != null) return
        val endpoint = privateCall ?: return
        val codec = privateCallCodec ?: return
        val callSettings = privateCallSettings ?: return
        val referenceFrames = runCatching {
            if (debugRawInputFile != null) requireNativeRawVideoMode(settings, callSettings)
            referenceAudioVideoFrames(callSettings)
        }.getOrElse { error ->
            Log.i("BluetoothVideo", "BENCHMARK native call input rejected: ${error.message}")
            stopPrivateCallEndpoint("Call native input could not start: ${error.message}")
            return
        }
        activeStreamSettings = callSettings
        // Experimental voice calls: VOICE starts no camera or video encoder (VIDEO when off).
        val callType = endpoint.localCallType
        val startMuted = intercomCallUi.startMedia(callType, IntercomFeatures.current)
        ActiveVideoSessionService.start(
            this,
            usesCamera = callType == CallType.VIDEO,
            statusText = if (callType == CallType.VOICE) "Encrypted private voice call" else "Encrypted private video call",
            usesMicrophone = true,
            ongoingCallStyle = CallUx.settings.callStyleNotifications,
        )
        privateCallFocusHold = CallFocusHold().also { it.onUserMuted(startMuted) }
        privateCallAudioSession = PrivateCallAudioSession(
            this,
            startOnSpeaker = intercomCallUi.routePolicy?.initialSpeakerOn ?: true,
            onRouteChanged = { updatePrivateCallControlLabels() },
            // Audio focus (CallAudioFocus): on for user sessions; benchmark launches keep the former
            // no-focus behaviour unless test_call_audio_focus opts in.
            onFocusAction = if (CallUx.settings.audioFocus) ::onPrivateCallFocusAction else null,
        )
        // Volume keys change the call volume even in a silent moment of the call.
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        // PTT mode (experimental) starts muted on the existing mute path, before the microphone runs.
        privateCallMuted = startMuted
        if (startMuted) endpoint.setMuted(true)
        privateCallCameraEnabled = callType == CallType.VIDEO
        privateCallCameraPausedAtEar = false
        privateCallProximityPause = if (CallUx.settings.proximityCameraPause) {
            CallProximityCameraPause(this, ::onPrivateCallProximityPause).also { it.start() }
        } else {
            null
        }
        intercomCallUi.layout?.let { layout ->
            endpoint.logCallMode("media_start", intercomCallUi.localVideo, intercomCallUi.remoteVideo, layout)
        }
        showPrivateCallLocalPreview()
        updatePrivateCallControlLabels()
        lateinit var audioEncoder: MicrophoneOpusEncoder
        audioEncoder = MicrophoneOpusEncoder(
            debugImpairedSend(endpoint::send, call = true),
            { message -> Log.i("BluetoothVideo", message) },
            { message ->
                runOnUiThread {
                    if (privateCallMicrophoneEncoder === audioEncoder) {
                        stopPrivateCallEndpoint("Call microphone stopped: $message")
                    }
                }
            },
            communicationMode = true,
            bitRate = privateCallActiveAudioBitRate ?: privateCallAudioBitRate(
                outgoingTransport,
                privateCallStackFingerprint(),
            ),
            debugReferenceFile = debugReferenceAudioFile,
            debugReferenceVideoFrames = referenceFrames,
            debugReferenceVideoFps = callSettings.fps,
            // A voice call has no camera encoder to supply the raw-fixture clock; the reference
            // audio then waited forever and sent no audio at all (voice-ptt-e151a61f). Voice calls
            // start their own audio-only clock.
            debugReferenceClock = if (privateCall?.localCallType == CallType.VOICE) {
                val voiceClock = RawReferenceClock(ptsOriginUs = 0L, monotonicOriginNs = System.nanoTime())
                ({ voiceClock })
            } else {
                ({ privateCallCameraEncoder?.benchmarkRawClock })
            },
            debugTraceContext = this,
            debugTraceSession = debugMediaTraceSession,
        )
        privateCallMicrophoneEncoder = audioEncoder
        runCatching { audioEncoder.start() }.onFailure { error ->
            stopPrivateCallEndpoint("Call microphone could not start: ${error.message}")
            return
        }
        startPrivateCallCamera(codec, callSettings)
    }

    private fun startPrivateCallCamera(codec: VideoCodec, callSettings: VideoSettings) {
        val endpoint = privateCall ?: return
        if (!privateCallCameraEnabled || privateCallCameraEncoder != null) return
        val corruptTestFrame = if (BuildConfig.DEBUG) intent.getIntExtra("test_corrupt_call_video_frame", 0) else 0
        var testFrameCount = 0
        val send = debugImpairedSend(endpoint::send, call = true)
        // Call resolution ladder: the rung for the current link; the call header keeps callSettings.
        val encoderSettings = privateCallResolutionLadder?.let { callSettings.copy(resolution = it.current) } ?: callSettings
        lateinit var encoder: CameraVideoEncoder
        encoder = CameraVideoEncoder(
            this,
            callLocalViewfinder,
            cameraLens,
            codec,
            encoderSettings,
            referenceFrameLimit = 0,
            debugTraceSessionName = debugMediaTraceSession,
            onPacket = { flags, timeUs, bytes ->
                if (privateCallCameraEncoder === encoder) {
                    if (flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) testFrameCount++
                    if (corruptTestFrame > 0 && testFrameCount == corruptTestFrame &&
                        flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
                    ) {
                        Log.i("BluetoothVideo", "DEBUG: injecting one invalid call video frame $testFrameCount")
                        send(flags, timeUs, byteArrayOf(0))
                    } else {
                        send(flags, timeUs, bytes)
                    }
                }
            },
            onStatus = { message -> Log.i("BluetoothVideo", "Call camera: $message") },
            onFailure = { error ->
                runOnUiThread {
                    if (privateCallCameraEncoder === encoder) {
                        stopPrivateCallEndpoint("Call camera stopped: ${error.message}")
                    }
                }
            },
            rateControl = encoderRateControl,
            scaleMode = videoScaleMode,
            debugRawInputFile = debugRawInputFile,
            onNativeOverloadAtFastest = if (DebugTunables.callEncoderFit) {
                { key ->
                    CallEncoderFitPreferences(this).recordOverload(key)
                    Log.i("BluetoothVideo", "BENCHMARK call_encoder_fit: overloaded=$key | remembered for later calls")
                }
            } else {
                null
            },
        )
        privateCallCameraEncoder = encoder
        // The learned call start (and any thermal limit) before the encoder configures, not after a second.
        if (startRateGuardOn() && privateCallLinkRateLimitBps != null) applyThermalAdaptation(thermalStatus)
        startPrivateCallRawSelfView(encoder, encoderSettings)
        privateCallCameraExecutor.execute {
            if (privateCallCameraEncoder !== encoder) return@execute
            val startedNs = System.nanoTime()
            runCatching { encoder.start() }.onSuccess {
                Log.i("BluetoothVideo", "Private call camera start: %.1f ms on %s".format(
                    (System.nanoTime() - startedNs) / 1_000_000.0, Thread.currentThread().name,
                ))
                runOnUiThread {
                    if (privateCallCameraEncoder === encoder) applyThermalAdaptation(thermalStatus)
                }
            }.onFailure { error ->
                encoder.close()
                runOnUiThread {
                    if (privateCallCameraEncoder === encoder) {
                        privateCallCameraEncoder = null
                        if (encoderSettings.resolution != callSettings.resolution && privateCallResolutionLadder != null) {
                            // A ladder rung failed: drop the ladder for this call and keep the start geometry.
                            privateCallResolutionLadder = null
                            logCallResolution(
                                "requested=${callSettings.resolution.width}x${callSettings.resolution.height} " +
                                    "effective=${callSettings.resolution.width}x${callSettings.resolution.height} " +
                                    "reason=rung_failed(${encoderSettings.resolution.width}x${encoderSettings.resolution.height})",
                            )
                            startPrivateCallCamera(codec, callSettings)
                        } else {
                            stopPrivateCallEndpoint("Call camera could not start: ${error.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * DEBUG raw-input self-view: the raw fixture frame the encoder is reading, at a low rate on its
     * own background thread. `test_call_raw_self_view_fps` (default 5, 0 = off) controls it.
     */
    private fun startPrivateCallRawSelfView(encoder: CameraVideoEncoder, callSettings: VideoSettings) {
        privateCallRawSelfView?.close()
        privateCallRawSelfView = null
        val file = debugRawInputFile ?: return
        if (!BuildConfig.DEBUG || debugCallRawSelfViewFps <= 0) return
        privateCallRawSelfView = CallRawSelfView(
            file,
            callSettings.resolution.width,
            callSettings.resolution.height,
            callSettings.fps,
            debugCallRawSelfViewFps,
            clock = { encoder.benchmarkRawClock },
            onFrame = { bitmap ->
                if (privateCallCameraEncoder === encoder) callScreenController.showRawFrame(bitmap)
            },
        ).also { selfView ->
            selfView.setPaused(!activityResumed)
            selfView.start()
        }
    }

    /** Shows the self-view the current input supports: the camera viewfinder or the raw input. */
    private fun showPrivateCallLocalPreview() {
        val preview = when {
            !privateCallCameraEnabled -> CallScreenController.LocalPreview.NONE
            debugRawInputFile == null -> CallScreenController.LocalPreview.CAMERA
            BuildConfig.DEBUG && debugCallRawSelfViewFps > 0 -> CallScreenController.LocalPreview.RAW
            else -> CallScreenController.LocalPreview.NONE
        }
        callScreenController.setLocalPreview(preview, videoScaleMode)
    }

    private fun stopPrivateCallCamera() {
        privateCallRawSelfView?.close()
        privateCallRawSelfView = null
        val encoder = privateCallCameraEncoder ?: return
        privateCallCameraEncoder = null
        privateCallCameraExecutor.execute {
            val startedNs = System.nanoTime()
            encoder.close()
            Log.i("BluetoothVideo", "Private call camera close: %.1f ms on %s".format(
                (System.nanoTime() - startedNs) / 1_000_000.0, Thread.currentThread().name,
            ))
        }
    }

    /**
     * Experimental push-to-talk (touch, accessibility toggle or DEBUG `ptt_press`/`ptt_release`):
     * holding unmutes and releasing mutes through the existing mute path. Returns true if applied.
     */
    private fun onPrivateCallPushToTalk(pressed: Boolean, source: String): Boolean {
        val endpoint = privateCall ?: return false
        val ptt = intercomCallUi.pushToTalk ?: return false
        if (privateCallPhase != PrivateCallPhase.NEGOTIATING_MEDIA && privateCallPhase != PrivateCallPhase.ACTIVE) {
            return false
        }
        val result = if (pressed) ptt.press() else ptt.release()
        if (!result.accepted) return false
        privateCallMuted = ptt.muted
        endpoint.pushToTalk(pressed, result.mutedChange?.let(privateCallFocusHold::onUserMuted), source)
        intercomCallUi.playCue(pressed)
        updatePrivateCallControlLabels()
        return true
    }

    /** Experimental voice calls: the ringing call's caller offered (or withdrew) a voice call. */
    private fun onIncomingPrivateCallType(type: CallType) {
        if (privateCallPhase != PrivateCallPhase.INCOMING_RINGING) return
        val acceptButton = findViewById<View>(R.id.acceptCallButton)
        if (type == CallType.VOICE) {
            intercomCallUi.showPendingVoice(incoming = true)
            hideCallParty() // the voice panel shows the caller
            setCallStatus("Incoming encrypted voice call")
            acceptButton.contentDescription = "Accept the voice call. Your camera stays off."
        } else {
            intercomCallUi.hidePendingVoice()
            setCallStatus("Incoming encrypted call")
            acceptButton.contentDescription = null
        }
    }

    /** Experimental voice calls: voice/video layout changed; the route follows unless chosen. */
    private fun onPrivateCallLayoutChanged(event: String) {
        val layout = intercomCallUi.layout ?: return
        intercomCallUi.routePolicy?.speakerForLayout(layout)?.let { speaker ->
            privateCallAudioSession?.setSpeakerOn(speaker, explicit = false)
        }
        privateCall?.logCallMode(event, intercomCallUi.localVideo, intercomCallUi.remoteVideo, layout)
        updatePrivateCallControlLabels()
    }

    /**
     * While muted, press and hold Mute to talk; releasing mutes again (the existing mute path).
     * Not used while the experimental push-to-talk button (launch extras) owns the microphone.
     */
    private fun bindHoldMuteToTalk() {
        callMuteButton.setOnTouchListener(
            HoldToTalkGesture(
                canStart = { privateCallMuted && intercomCallUi.pushToTalk == null && privateCall != null },
                onTalk = { talking ->
                    val endpoint = privateCall ?: return@HoldToTalkGesture
                    // While another call holds the microphone (audio focus hold) the call stays muted.
                    endpoint.setMuted(privateCallFocusHold.onUserMuted(!talking))
                    Log.i("BluetoothVideo", "Call hold-mute-to-talk: ${if (talking) "talking" else "muted"}")
                    callMuteLabel.setText(if (talking) R.string.call_talking else R.string.call_unmute)
                    callMuteButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                },
            ),
        )
    }

    /** The first time the user mutes, a one-line tip about holding Mute to talk. */
    private fun maybeShowHoldMuteTip() {
        val prefs = getSharedPreferences(OnboardingActivity.PREFERENCES, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_HOLD_MUTE_TIP_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_HOLD_MUTE_TIP_SHOWN, true).apply()
        com.google.android.material.snackbar.Snackbar
            .make(callScreen, R.string.call_hold_mute_tip, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    /**
     * Audio focus changed during the call (CallAudioFocus): a transient loss, such as a cellular
     * call, holds the call on the existing mute path (the peer gets Microphone muted, signal 9);
     * regaining focus restores the user's own mute choice.
     */
    private fun onPrivateCallFocusAction(action: FocusAction) {
        val endpoint = privateCall ?: return
        val muted = privateCallFocusHold.onAction(action) ?: return
        endpoint.setMuted(muted)
        if (privateCallPhase == PrivateCallPhase.ACTIVE) {
            setCallStatus(
                if (privateCallFocusHold.held) "On hold: another call is using the microphone"
                else "Encrypted private call",
            )
        }
        updatePrivateCallControlLabels()
    }

    private fun togglePrivateCallMute() {
        // Experimental push-to-talk: while the talk button is held it owns the microphone.
        val ptt = intercomCallUi.pushToTalk
        if (ptt?.held == true) return
        privateCallMuted = !privateCallMuted
        ptt?.setMuted(privateCallMuted)
        // While another call holds the microphone (audio focus hold) the call stays muted.
        privateCall?.setMuted(privateCallFocusHold.onUserMuted(privateCallMuted))
        updatePrivateCallControlLabels()
        if (privateCallMuted && ptt == null) maybeShowHoldMuteTip()
    }

    private fun togglePrivateCallCamera() {
        privateCallCameraEnabled = !privateCallCameraEnabled
        if (!privateCallCameraEnabled) {
            stopPrivateCallCamera()
            showPrivateCallLocalPreview()
        } else {
            showPrivateCallLocalPreview()
            val codec = privateCallCodec ?: return
            val callSettings = privateCallSettings ?: return
            startPrivateCallCamera(codec, callSettings)
        }
        privateCall?.setCameraEnabled(privateCallCameraEnabled)
        // Experimental voice calls: adding video to a voice call needs the camera service type.
        if (privateCallCameraEnabled && privateCall?.localCallType == CallType.VOICE) {
            ActiveVideoSessionService.start(
                this,
                usesCamera = true,
                statusText = "Encrypted private video call",
                ongoingCallStyle = CallUx.settings.callStyleNotifications,
            )
        }
        val layoutChanged = intercomCallUi.onLocalVideo(privateCallCameraEnabled)
        val event = if (privateCallCameraEnabled) "local_video_on" else "local_video_off"
        if (layoutChanged) {
            onPrivateCallLayoutChanged(event)
        } else {
            intercomCallUi.layout?.let { layout ->
                privateCall?.logCallMode(event, intercomCallUi.localVideo, intercomCallUi.remoteVideo, layout)
            }
        }
        updatePrivateCallControlLabels()
    }

    private fun switchPrivateCallCamera() {
        if (debugRawInputFile != null) return
        if (!privateCallCameraEnabled || availableCameraLenses.size < 2) return
        stopPrivateCallCamera()
        val currentIndex = availableCameraLenses.indexOf(cameraLens)
        cameraLens = availableCameraLenses[(currentIndex + 1) % availableCameraLenses.size]
        privateCall?.setDisplayRotation(
            CameraOrientation.currentCameraRotationDegrees(this, cameraLens),
        )
        val codec = privateCallCodec ?: return
        val callSettings = privateCallSettings ?: return
        startPrivateCallCamera(codec, callSettings)
        privateCall?.setCameraEnabled(true)
    }

    /**
     * Experimental camera pause at the ear (CallProximityCameraPause): the outgoing camera stops
     * while the phone is near on the earpiece and restarts when it leaves; the peer sees the
     * ordinary Camera off/on signals and the user's camera choice is unchanged.
     */
    private fun onPrivateCallProximityPause(paused: Boolean) {
        if (privateCallPhase != PrivateCallPhase.ACTIVE && privateCallPhase != PrivateCallPhase.NEGOTIATING_MEDIA) return
        if (paused) {
            if (!privateCallCameraEnabled || privateCallCameraEncoder == null) return
            privateCallCameraPausedAtEar = true
            stopPrivateCallCamera()
            privateCall?.setCameraEnabled(false)
        } else if (privateCallCameraPausedAtEar) {
            privateCallCameraPausedAtEar = false
            if (!privateCallCameraEnabled) return
            val codec = privateCallCodec ?: return
            val callSettings = privateCallSettings ?: return
            startPrivateCallCamera(codec, callSettings)
            privateCall?.setCameraEnabled(true)
        }
    }

    private fun updatePrivateCallControlLabels() {
        privateCallProximityPause?.update(
            userCameraOn = privateCallCameraEnabled,
            earpiece = privateCallAudioSession?.output == CallOutput.EARPIECE,
        )
        if (!::callMuteButton.isInitialized) return
        // Round icon buttons: the label is the TextView below; checked = muted / video off / earpiece.
        callMuteLabel.setText(if (privateCallMuted) R.string.call_unmute else R.string.call_mute)
        callCameraLabel.setText(if (privateCallCameraEnabled) R.string.call_video_off else R.string.call_video_on)
        (callMuteButton as? com.google.android.material.button.MaterialButton)?.let {
            it.isChecked = privateCallMuted
            it.setIconResource(if (privateCallMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        (callCameraButton as? com.google.android.material.button.MaterialButton)?.let {
            it.isChecked = !privateCallCameraEnabled
            it.setIconResource(if (privateCallCameraEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
        }
        callSwitchCameraButton.isEnabled = privateCallCameraEnabled && availableCameraLenses.size > 1 &&
            debugRawInputFile == null
        val audio = privateCallAudioSession
        val speakerOn = audio?.speakerOn ?: true
        val output = audio?.output ?: CallOutput.SPEAKER
        callSpeakerLabel.text = output.label
        (callSpeakerButton as? com.google.android.material.button.MaterialButton)?.let {
            it.isChecked = speakerOn
            it.setIconResource(if (speakerOn) R.drawable.ic_volume_up else R.drawable.ic_volume_down)
        }
        callSpeakerButton.isEnabled = audio?.quietRouteAvailable == true
        callSpeakerButton.contentDescription = if (speakerOn) {
            "Sound plays from the speaker. Tap to use the earpiece, or a plugged-in headset."
        } else {
            "Sound plays from the ${output.label.lowercase()}. Tap to use the speaker."
        }
        callMuteButton.contentDescription = if (privateCallMuted) "Unmute your microphone" else "Mute your microphone"
        callCameraButton.contentDescription = if (privateCallCameraEnabled) "Turn your video off" else "Turn your video on"
        callSwitchCameraButton.contentDescription = "Switch between front and back camera"
        // Experimental voice calls: the voice layout offers Add video and hides Flip.
        val voiceLayout = intercomCallUi.layout == CallLayout.VOICE
        (callSwitchCameraButton.parent as? View)?.visibility = if (voiceLayout) View.GONE else View.VISIBLE
        callSwitchCameraButton.visibility = if (voiceLayout) View.GONE else View.VISIBLE
        if (voiceLayout) {
            (callCameraButton as? com.google.android.material.button.MaterialButton)?.isChecked = false
            callCameraLabel.setText(R.string.call_add_video)
            callCameraButton.contentDescription =
                "Turn on your video. The other person chooses whether to turn on theirs."
        }
        intercomCallUi.updatePushToTalkButton()
    }

    /** Loudspeaker (the default) or the quieter handset earpiece for call audio. */
    private fun togglePrivateCallSpeaker(): Boolean {
        val audio = privateCallAudioSession ?: return false
        intercomCallUi.routePolicy?.onUserChoice()
        val wanted = !audio.speakerOn
        val speakerOn = audio.setSpeakerOn(wanted)
        Log.i("BluetoothVideo", "Call audio route: ${if (speakerOn) "speaker" else "earpiece"} (requested ${if (wanted) "speaker" else "earpiece"})")
        updatePrivateCallControlLabels()
        return speakerOn == wanted
    }

    private fun setCallStatus(message: String) {
        Log.i("BluetoothVideo", message)
        if (message == ACTIVE_CALL_STATUS) {
            // The security chip already says "Encrypted"; keep the text for TalkBack only.
            callStatusText.text = message
            callStatusText.visibility = View.GONE
            return
        }
        displayStatus(message)?.let(::showCallStatusText)
    }

    private fun showCallStatusText(text: String) {
        callStatusText.text = text
        if (::callPartyPanel.isInitialized && callPartyPanel.visibility == View.VISIBLE) {
            // The caller card's state line carries the status; the name is already shown.
            if (!text.startsWith("Calling") && !text.startsWith("Incoming")) callPartyState.text = text
            callStatusText.visibility = View.GONE
            return
        }
        callStatusText.visibility = View.VISIBLE
    }

    private fun applyPrivateCallSurfaceTransform() {
        val format = privateCallSurfaceFormat ?: return
        VideoSurfaceTransform.apply(
            callRemotePreview,
            format.width,
            format.height,
            format.rotationDegrees,
            format.scaleMode,
            debugTraceSession = debugMediaTraceSession,
        )
    }

    private fun endPrivateCall() {
        val endpoint = privateCall
        if (endpoint == null) {
            showPrivateCallBrowser()
            return
        }
        endpoint.hangUp()
    }

    private fun cleanupPrivateCallMedia(keepService: Boolean = false) {
        val impairment = debugCallImpairment
        impairment?.cancel()
        debugCallImpairment = null
        privateCallContinuityTracker?.let {
            logReceiverContinuity(it.snapshot(System.nanoTime()), final = true, label = "Private call")
        }
        privateCallContinuityTracker = null
        privateCallMediaStartedAtNs = 0L
        privateCallContinuityLastReportNs = 0L
        stopPrivateCallRinging()
        privateCallProximityPause?.stop()
        privateCallProximityPause = null
        privateCallCameraPausedAtEar = false
        stopPrivateCallCamera()
        privateCallMicrophoneEncoder?.close()
        privateCallMicrophoneEncoder = null
        // Camera shutdown is serialized on its executor; late cancelled offers remain visible.
        privateCallCameraExecutor.execute { impairment?.close() }
        privateCallAudioSession?.close()
        privateCallAudioSession = null
        privateCallActiveAudioBitRate = null
        privateCallRemoteSurface?.release()
        privateCallRemoteSurface = null
        privateCallSurfaceFormat = null
        privateCallRemoteVideoStarted = false
        debugPrivateCallSurfacePending = false
        activeStreamSettings = null
        if (::callRemotePreview.isInitialized) {
            callRemotePreview.alpha = 0f
            callRemotePreview.setTransform(null)
        }
        if (::callScreenController.isInitialized) {
            callScreenController.reset()
            callScreenController.setForeground(false)
        }
        if (::intercomCallUi.isInitialized) intercomCallUi.close()
        volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        if (!keepService) ActiveVideoSessionService.stop(this)
    }

    /** See the onEnded caller: connectedDevice only, or stop if Android refuses. */
    private fun keepReachabilityServiceAfterCall() {
        val refused = runCatching {
            ActiveVideoSessionService.start(this, usesCamera = false, statusText = getString(R.string.reachable_service_text)).refused
        }.getOrElse { it.message ?: it.javaClass.simpleName }
        if (refused != null) {
            Log.w("BluetoothVideo", "Call reachability service after call refused: $refused")
            ActiveVideoSessionService.stop(this)
        }
    }

    private fun stopPrivateCallEndpoint(message: String? = null, keepService: Boolean = false) {
        stopPrivateCallRinging()
        callRinger.stopRingback()
        stopPrivateCallDiscovery()
        cleanupPrivateCallMedia(keepService)
        privateCall?.close()
        privateCall = null
        privateCallPhase = null
        privateCallAvailabilityStarting = false
        privateCallAvailable = false
        privateCallReturnHome = false
        privateCallSettings = null
        privateCallResolutionLadder = null
        privateCallRateController = null
        privateCallLinkRateLimitBps = null
        privateCallLinkFps = null
        privateCallCodec = null
        privateCallPeers = emptyList()
        privateCallPeersLastSeen.clear()
        callEndpointTransport = null
        callTransportChoice = null
        cocRetryPeer = null
        cocRebuildPeer = null
        message?.let(::setStatus)
    }

    private fun startPrivateCallRinging() {
        stopPrivateCallRinging()
        // Ringer policy (CallRinger): silent/vibrate/DND and a fallback ringtone; benchmark launches
        // keep the former ring unless test_call_ringer_policy opts in.
        callRinger.startIncoming(CallUx.settings.ringerPolicy)
    }

    private fun stopPrivateCallRinging() {
        callLockScreen.cancelIncomingAlert()
        callRinger.stopIncoming()
    }

    /** Experimental ringback (test_call_ringback): the caller hears ringing while the callee rings. */
    private fun updatePrivateCallRingback(phase: PrivateCallPhase?) {
        if (CallUx.settings.ringback && phase == PrivateCallPhase.OUTGOING_RINGING) {
            callRinger.startRingback()
        } else {
            callRinger.stopRingback()
        }
    }

    private fun connectToStream(peer: BleL2capPeer) {
        if (!supportsAdvertisedCodec(peer.announcement)) {
            receiverBrowserStatus.text = "This phone cannot decode that stream's codec."
            return
        }
        if (peer.announcement.isFullStreamOffer() && reconnectTarget?.matches(peer.announcement) != true) {
            receiverBrowserStatus.text = "That stream has no free viewer slot right now. It will show as available when one frees."
            return
        }
        val preservingPlayback = appScreen == AppScreen.PLAYBACK &&
            reconnectTarget?.matches(peer.announcement) == true && receiverWasActive
        resetReceiverRecoveryNotice()
        val logicalTarget = LogicalStreamTarget.from(peer.announcement)
        Log.i(
            "BluetoothVideo",
            "Selected route token=${peer.announcement.streamToken.toUInt().toString(16)}, " +
                "logical=${logicalTarget.token.toUInt().toString(16)}, " +
                "hops=${peer.announcement.routeCost}",
        )
        cancelStreamReconnect()
        stopNearbyStreamDiscovery()
        receiverJoinStartedAtNs = System.nanoTime()
        if (!preservingPlayback) {
            finishReceiverContinuity()
            receiverContinuityTracker = null
            receiverContinuityLastReportNs = 0L
            receiverWasActive = false
            activeStreamSourceNodeId = null
            reconnectFromNodeIdPrefix = null
            preferredRecoveryParent = null
        }
        preserveReceiverContinuityOnNextFormat = preservingPlayback
        activeStreamTarget = logicalTarget
        receiverRouteWithdrawal = null
        selectedStreamPeer = peer
        if (preservingPlayback) {
            showReceiverOverlay("Reconnecting to stream", showProgress = true)
            updateConnectionDot(0)
        } else {
            showPlaybackScreen()
        }
        setStatus("Requesting ${peer.label}…")
        if (hasRequiredPermissions()) {
            preview.post { if (receiver == null) startReceiving() }
        } else {
            ensurePermissionsAndRefresh()
        }
    }

    private fun supportsAdvertisedCodec(announcement: DiscoveryAnnouncement): Boolean {
        val localDecoderMask = decoders.fold(0) { mask, decoder ->
            mask or codecCapabilityMask(decoder.protocolId)
        }
        return announcement.capabilitySummary != 0 &&
            announcement.capabilitySummary and localDecoderMask != 0
    }

    private fun connectToCachedStreamRoute(target: LogicalStreamTarget): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        suppressedStreamRoutes.entries.removeAll { it.value <= nowMs }
        val peer = withPreferredParentGrace(
            nearbyStreamsLastSeen.values.asSequence()
                .filter { (_, lastSeenMs) -> nowMs - lastSeenMs <= CACHED_ROUTE_MAX_AGE_MS }
                .map { it.first }
                .filter { target.matches(it.announcement) }
                .filter { supportsAdvertisedCodec(it.announcement) }
                .filterNot { candidate ->
                    suppressedStreamRoutes.keys.any { route -> route.matches(candidate.announcement) }
                }
                .toList(),
        )
        val chosen = peer.asSequence()
            .sortedWith(
                compareBy<BleL2capPeer> {
                    establishmentRank(establishmentFailing(it.announcement), DebugTunables.relayEstablishFallback)
                }.thenBy {
                    preferredRecoveryRank(it.announcement.nodeIdPrefix, preferredRecoveryParent)
                }.thenBy {
                    streamRouteRank(it.announcement, reconnectPreferredTransport).first
                }.thenBy {
                    streamRouteRank(it.announcement, reconnectPreferredTransport).second
                }.thenBy {
                    streamRouteRank(it.announcement, reconnectPreferredTransport).third
                },
            )
            .firstOrNull() ?: return false
        logEstablishmentFallback(peer, chosen)
        setStatus("Using a recently seen route to the same stream...")
        connectToStream(chosen)
        return true
    }

    /** Holds reconnection briefly for the previous relay after a re-anchor (RelayChildReanchor.kt). */
    private fun withPreferredParentGrace(candidates: List<BleL2capPeer>): List<BleL2capPeer> {
        val nowMs = SystemClock.elapsedRealtime()
        val preferred = preferredRecoveryParent
        val allowed = recoveryCandidatesDuringGrace(
            candidates,
            { it.announcement.nodeIdPrefix },
            preferred,
            preferredRecoveryGraceUntilMs,
            nowMs,
        )
        if (preferred != null && allowed.isEmpty() && candidates.isNotEmpty() && !preferredRecoveryGraceLogged) {
            preferredRecoveryGraceLogged = true
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relay_recovery_grace: waitingFor=${AdaptivePhyMemory.prefixLabel(preferred)} | " +
                    "held=${candidates.joinToString(",") { AdaptivePhyMemory.prefixLabel(it.announcement.nodeIdPrefix) }} | " +
                    "remainingMs=${preferredRecoveryGraceUntilMs - nowMs}",
            )
        }
        return allowed
    }

    private fun returnToStreamBrowser(
        peer: BleL2capPeer?,
        streamEnded: Boolean,
        allowReconnect: Boolean,
        failureNotice: String? = null,
        localMediaFailure: Boolean = false,
        minimumRetryDelayMs: Long = 0L,
    ) {
        val logicalTarget = activeStreamTarget
        // A local decoder/playback failure says nothing about the route: no cooldown for it.
        if (!streamEnded && peer != null && !localMediaFailure) {
            val failedRoute = StreamReconnectTarget.from(peer)
            val failures = (consecutiveRouteFailures[failedRoute] ?: 0) + 1
            consecutiveRouteFailures[failedRoute] = failures
            val suppressionMs = maxOf(
                if (allowReconnect && receiverWasActive) {
                    unexpectedLossRetryDelayMs(failures)
                } else {
                    FAILED_ROUTE_SUPPRESSION_MS
                },
                minimumRetryDelayMs,
            )
            suppressedStreamRoutes[failedRoute] = streamRouteSuppressionDeadline(
                suppressedStreamRoutes[failedRoute],
                SystemClock.elapsedRealtime() + suppressionMs,
            )
            // A failed cached offer needs a fresh advertisement before it can be retried.
            nearbyStreamsLastSeen.entries.removeAll { failedRoute.matches(it.value.first.announcement) }
            Log.i(
                "BluetoothVideo",
                "Receiver route cooldown: parent=${failedRoute.sourcePrefix} | " +
                    "deadlineMs=${suppressedStreamRoutes[failedRoute]} | wasActive=$receiverWasActive | " +
                    "sameFailureRepeats=${repeatedReceiverFailures.repeats} | repeatBackoffMs=$minimumRetryDelayMs",
            )
        }
        if (!streamEnded && allowReconnect && peer != null && logicalTarget != null) {
            reconnectTarget = logicalTarget
            reconnectPreferredTransport = peer.announcement.transportOption()
            reconnectFromNodeIdPrefix = recoveryParentAfterRouteFailure(
                reconnectFromNodeIdPrefix, receiverWasActive, peer.announcement.nodeIdPrefix,
            )
            selectedStreamPeer = null
            updateConnectionDot(0)
            val notice = failureNotice ?: "Connection lost. Finding another route..."
            setStatus(notice)
            if (connectToCachedStreamRoute(logicalTarget)) return
            showReceiverOverlay(notice, showProgress = true)
            refreshNearbyStreams()
            return
        } else {
            cancelStreamReconnect()
        }
        activeStreamTarget = null
        activeStreamSourceNodeId = null
        reconnectFromNodeIdPrefix = null
        preferredRecoveryParent = null
        preserveReceiverContinuityOnNextFormat = false
        showReceiverBrowser(cancelReconnect = false)
        if (streamEnded) {
            showReceiverBrowserNotice("Stream ended. Scanning for nearby broadcasts.")
        } else if (allowReconnect && peer != null) {
            receiverBrowserStatus.text = "Connection lost. Looking for the same stream..."
        } else {
            showReceiverBrowserNotice(failureNotice ?: "Could not connect. Scanning for nearby broadcasts.")
        }
    }

    private fun updateConnectionDot(receiverCount: Int) {
        if (!::connectionStatusDot.isInitialized) return
        lastConnectedCount = receiverCount
        updateBroadcastStatusChip()
        val connected = receiverCount > 0
        connectionStatusDot.backgroundTintList = ColorStateList.valueOf(
            if (connected) Color.rgb(46, 160, 67) else Color.rgb(211, 47, 47),
        )
        connectionStatusDot.contentDescription = when (sessionRole) {
            SessionRole.RECEIVE -> if (connected) "Stream connected" else "Waiting for stream"
            SessionRole.SEND -> if (connected) {
                "$receiverCount receiver(s) connected"
            } else {
                "No receiver connected"
            }
            SessionRole.RELAY -> if (connected) "Relay connected" else "Relay disconnected"
        }
    }

    private fun showReceiverOverlay(message: String, showProgress: Boolean, noticeAlpha: Float = 1f) {
        if (receiverWaitingOverlay.visibility != View.VISIBLE || receiverWaitingText.text.toString() != message ||
            (receiverWaitingProgress.visibility == View.VISIBLE) != showProgress
        ) {
            Log.i("BluetoothVideo", "Receiver overlay: visible | text=$message | progress=$showProgress | noticeAlpha=$noticeAlpha")
        }
        receiverWaitingText.text = message
        receiverWaitingText.alpha = noticeAlpha
        receiverWaitingProgress.alpha = noticeAlpha
        receiverWaitingProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        receiverWaitingOverlay.visibility = View.VISIBLE
    }

    private fun hideReceiverOverlay() {
        if (receiverWaitingOverlay.visibility == View.VISIBLE) {
            Log.i("BluetoothVideo", "Receiver overlay: hidden | text=${receiverWaitingText.text}")
        }
        receiverWaitingOverlay.visibility = View.GONE
    }

    private fun resetReceiverRecoveryNotice() {
        receiverRecoveryNoticeRunnable?.let(sessionHandler::removeCallbacks)
        receiverRecoveryNoticeRunnable = null
        receiverRecoveryNoticePolicy.update(false, SystemClock.elapsedRealtime())
    }

    private fun updatePauseButton() {
        if (!::broadcastPauseButton.isInitialized) return
        val available = sessionRole == SessionRole.SEND && appScreen == AppScreen.CAMERA &&
            ((broadcaster != null && streamArmed) || (BuildConfig.DEBUG && sender != null))
        broadcastPauseButton.visibility = if (available) View.VISIBLE else View.GONE
        broadcastPauseButton.setImageResource(
            if (streamPaused) R.drawable.ic_play else R.drawable.ic_pause,
        )
        broadcastPauseButton.contentDescription = if (streamPaused) {
            "Resume broadcast"
        } else {
            "Pause broadcast"
        }
    }

    private fun cancelStreamReconnect() {
        reconnectTarget = null
        reconnectPreferredTransport = null
    }

    private fun showReceiverBrowserNotice(message: String) {
        receiverBrowserNotice = message
        receiverBrowserNoticeDeadlineMs = SystemClock.elapsedRealtime() + RECEIVER_NOTICE_MS
        receiverBrowserStatus.text = message
        sessionHandler.removeCallbacks(receiverBrowserNoticeTimeoutRunnable)
        sessionHandler.postDelayed(receiverBrowserNoticeTimeoutRunnable, RECEIVER_NOTICE_MS)
    }

    private fun clearReceiverBrowserNotice() {
        receiverBrowserNotice = null
        receiverBrowserNoticeDeadlineMs = 0L
        sessionHandler.removeCallbacks(receiverBrowserNoticeTimeoutRunnable)
    }

    private fun ensurePermissionsAndRefresh() {
        val needed = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            ensureBluetoothEnabledAndRefresh()
        } else {
            permissionRequest.launch(needed.toTypedArray())
        }
    }

    private fun ensureBluetoothEnabledAndRefresh() {
        val adapter = bluetoothAdapter
        when {
            adapter == null -> onPermissionsReady()
            adapter.isEnabled -> onPermissionsReady()
            else -> {
                setStatus("Turn on Bluetooth to continue.")
                bluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
            if ((sessionRole == SessionRole.SEND || appScreen == AppScreen.CALL_BROWSER ||
                    appScreen == AppScreen.CALL) && requestedTransportExperiment == null
            ) {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.RECORD_AUDIO)
            }
            val needsBleAccess = outgoingTransport.isBle || requestedTransportExperiment != null ||
                usesBroadcastDiscovery() || debugAutoSelectStream || sessionRole == SessionRole.RELAY ||
                appScreen == AppScreen.CALL_BROWSER || appScreen == AppScreen.CALL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (needsBleAccess) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                }
            } else if (needsBleAccess) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    @SuppressLint("MissingPermission")
    private fun runRequestedTransportExperiment() {
        if (!BuildConfig.DEBUG) return
        requestedTransportExperiment ?: return
        if (!transportExperimentPending) return
        transportExperimentPending = false
        debugAutoStartPending = false
        val adapter = bluetoothAdapter ?: return
        startDebugBenchmark { coordinator ->
            coordinator.runTransport(intent, adapter, outgoingTransport)
        }
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        bleDiscovery?.close()
        bleDiscovery = null
        val adapter = bluetoothAdapter
        when {
            adapter == null -> setStatus("This phone does not support Bluetooth.")
            !adapter.isEnabled -> setStatus("Turn Bluetooth on, then refresh.")
            appScreen == AppScreen.RECEIVER_BROWSER -> refreshNearbyStreams()
            appScreen == AppScreen.CALL_BROWSER -> startPrivateCallEndpoint()
            appScreen == AppScreen.CAMERA && usesBroadcastDiscovery() ->
                startStreamPresence()
            sessionRole == SessionRole.RECEIVE -> {
                setStatus(
                    if (outgoingTransport.isBle) {
                        "Direct BLE receiver test is ready."
                    } else {
                        "Direct Classic receiver test is ready."
                    },
                )
            }
            sessionRole == SessionRole.RELAY -> refreshBleReceivers(adapter)
            debugLaunchRequested && outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM ->
                refreshClassicDestinations(adapter)
            debugLaunchRequested -> refreshBleReceivers(adapter)
            else -> setStatus("Ready. Start a broadcast or open Nearby broadcasts.")
        }
        if (ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) {
            setStatus("Phone is critically hot. Let it cool before starting video.")
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            setStatus(
                if (thermalReductionOn) {
                    "Phone is hot. Automatic cooling will reduce FPS and bitrate."
                } else {
                    "Phone is hot. Automatic cooling is off."
                },
            )
        }
        updateStartEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun refreshClassicDestinations(adapter: BluetoothAdapter) {
        pairedDevices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        val labels = pairedDevices.map { "${it.name ?: "Unnamed device"} (${it.address})" }
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("No paired devices") },
        )
        val requestedTarget = if (BuildConfig.DEBUG) intent.getStringExtra("test_target") else null
        val requestedTargetIndex = requestedTarget?.let { target ->
            pairedDevices.indexOfFirst { device ->
                classicTargetMatches(device.name, device.address, target)
            }
        } ?: -1
        if (requestedTargetIndex >= 0) deviceSpinner.setSelection(requestedTargetIndex, false)

        val message = when {
            labels.isEmpty() -> "No paired receivers. Pair the other device in Android Bluetooth settings first."
            requestedTarget != null && requestedTargetIndex < 0 ->
                "Requested Classic receiver '$requestedTarget' is not paired."
            else -> "Direct Classic test target ready."
        }
        setStatus(message)
    }

    @SuppressLint("MissingPermission")
    private fun refreshBleReceivers(adapter: BluetoothAdapter) {
        blePeers = emptyList()
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Scanning for active BLE receivers…"),
        )
        setStatus("Scanning for phones with this app's BLE receiver turned on…")
        // A call relay never takes an offer for itself (PrivateCallRelayChain.admitsOffer).
        val localRelayPrefix = if (debugPrivateCallRelay) {
            AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        } else {
            ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        }
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (bleDiscovery !== discovery || blePeers.any { it.device.address == peer.device.address }) {
                        return@runOnUiThread
                    }
                    blePeers = (blePeers + peer).sortedBy(BleL2capPeer::label)
                    deviceSpinner.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        blePeers.map(BleL2capPeer::label),
                    )
                    setStatus("Found ${blePeers.size} active BLE receiver(s). Choose one and connect.")
                    updateStartEnabled()
                }
            },
            { count ->
                runOnUiThread {
                    if (bleDiscovery !== discovery) return@runOnUiThread
                    bleDiscovery = null
                    if (count == 0) {
                        deviceSpinner.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_dropdown_item,
                            listOf("No active BLE receivers found"),
                        )
                        setStatus("No BLE receiver found. Turn Receiver ON on the other Android phone, then refresh.")
                    }
                    updateStartEnabled()
                }
            },
            { message ->
                runOnUiThread {
                    if (bleDiscovery !== discovery) return@runOnUiThread
                    bleDiscovery = null
                    setStatus(message)
                    updateStartEnabled()
                }
            },
            acceptAnnouncement = { announcement ->
                (if (debugPrivateCallRelay) {
                    PrivateCallRelayChain.admitsOffer(announcement, localRelayPrefix, DebugTunables.callRelayChain) &&
                        (debugRelayCalleePrefix == null ||
                            announcement.nodeIdPrefix.toHexKey() == debugRelayCalleePrefix) &&
                        (debugRelayRouteCost == null || announcement.routeCost == debugRelayRouteCost)
                } else {
                    announcement.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 &&
                        !announcement.isPrivateCallOffer()
                }) &&
                    announcement.transportOption() == outgoingTransport &&
                    (!debugRequireRelay ||
                        announcement.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0)
            },
        )
        bleDiscovery = discovery
        discovery.start()
    }

    @SuppressLint("MissingPermission")
    private fun selectedDestination(): BluetoothDevice? = if (sessionRole == SessionRole.RELAY) {
        selectedBlePeer()?.device
    } else {
        when (outgoingTransport) {
            BluetoothTransportOption.CLASSIC_RFCOMM ->
                if (BuildConfig.DEBUG && intent.hasExtra("test_target")) {
                    val requestedTarget = intent.getStringExtra("test_target").orEmpty()
                    pairedDevices.firstOrNull { device ->
                        classicTargetMatches(device.name, device.address, requestedTarget)
                    }
                } else {
                    pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
                }
            BluetoothTransportOption.BLE_L2CAP -> blePeers.getOrNull(deviceSpinner.selectedItemPosition)?.device
            BluetoothTransportOption.BLE_GATT -> blePeers.getOrNull(deviceSpinner.selectedItemPosition)?.device
        }
    }

    private fun selectedBlePeer(): BleL2capPeer? =
        blePeers.getOrNull(deviceSpinner.selectedItemPosition)

    private fun updateStartEnabled() {
        val idle = sender == null && broadcaster == null && receiver == null && relay == null &&
            privateCallRelay == null &&
            !localTestRunning
        val controlsIdle = idle && !streamArmed
        val thermalReady = !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)
        val selectedModeSupported = selectedDeviceMode()?.let { mode ->
            mode.resolution == settings.resolution && settings.fps in mode.fpsOptions
        } == true
        val bluetoothReady = bluetoothAdapter?.isEnabled == true
        val transportReady = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ||
            (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM && debugLaunchRequested)
        val destinationReady = if (
            usesBroadcastDiscovery()
        ) {
            true
        } else {
            selectedDestination() != null
        }
        val permissionsReady = hasRequiredPermissions()
        val canStart = when (sessionRole) {
            SessionRole.SEND -> destinationReady && codecs.isNotEmpty() && controlsIdle &&
                selectedModeSupported && bluetoothReady && transportReady &&
                permissionsReady && thermalReady
            SessionRole.RECEIVE -> preview.isAvailable && idle && bluetoothReady && transportReady &&
                permissionsReady && thermalReady
            SessionRole.RELAY -> destinationReady && blePeers.size >= debugRelayChildCount &&
                idle && bluetoothReady &&
                permissionsReady && thermalReady
        }
        val streamActive = streamArmed || sender != null || broadcaster != null ||
            receiver != null || relay != null || privateCallRelay != null
        streamToggleButton.isEnabled = streamActive || canStart
        streamToggleButton.setBackgroundResource(
            if (streamActive) R.drawable.stream_control_stop else R.drawable.stream_control_start,
        )
        streamToggleButton.backgroundTintList = null
        streamToggleButton.contentDescription = when (sessionRole) {
            SessionRole.SEND -> if (streamActive) "Stop broadcast" else "Start broadcast"
            SessionRole.RECEIVE -> if (streamActive) "Stop receiving" else "Start receiving"
            SessionRole.RELAY -> if (streamActive) "Stop relay" else "Start relay"
        }
        streamToggleButton.alpha = if (streamToggleButton.isEnabled) 1f else 0.5f
        updateBroadcastStatusChip()
        refreshButton.isEnabled = controlsIdle
        transportSpinner.isEnabled = controlsIdle && sessionRole != SessionRole.RELAY
        val destinationControlsEnabled = controlsIdle && sessionRole != SessionRole.RECEIVE
        val sendControlsEnabled = controlsIdle
        deviceSpinner.isEnabled = destinationControlsEnabled
        codecSpinner.isEnabled = sendControlsEnabled
        rateControlSpinner.isEnabled = sendControlsEnabled && rateControlOptions.isNotEmpty()
        resolutionSpinner.isEnabled = sendControlsEnabled && deviceModes.isNotEmpty()
        fpsSpinner.isEnabled = sendControlsEnabled && selectedDeviceMode()?.fpsOptions?.isNotEmpty() == true
        bitrateSeekBar.isEnabled = sendControlsEnabled
        updateCameraSwitchButton()
        updatePauseButton()
        if (debugAutoStartPending && canStart) {
            debugAutoStartPending = false
            streamToggleButton.post {
                if (sender == null && broadcaster == null && receiver == null && relay == null &&
                    privateCallRelay == null
                ) {
                    when (sessionRole) {
                        SessionRole.SEND -> startStreaming()
                        SessionRole.RECEIVE -> startReceiving()
                        SessionRole.RELAY -> startRelaying()
                    }
                }
            }
        }
    }

    private fun startStreaming() {
        if (!ensureThermalReady()) return
        if (usesBroadcastDiscovery()) {
            if (streamArmed) startRequestedBroadcast() else armDiscoverableStream()
            return
        }
        val device = selectedDestination() ?: return
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition) ?: return
        if (debugReplayPath != null) stopIdleCameraPreview()
        ActiveVideoSessionService.start(this, usesCamera = debugReplayPath == null)
        bleDiscovery?.close()
        bleDiscovery = null
        val activeTransport = outgoingTransport
        val activeSettings = settings
        val rotationDegrees = outgoingVideoRotationDegrees()
        activeStreamSettings = activeSettings
        applyThermalAdaptation(thermalStatus)
        val rateController = newLinkRateController(activeSettings, activeTransport)
        lateinit var newSender: BluetoothVideoSender
        newSender = BluetoothVideoSender(
            queueCapacity = activeSettings.queueCapacity,
            onLinkSample = { sample -> onLinkRateSample(rateController, "direct", sample) },
            debugDirectGattHeartbeat = debugDirectGattHeartbeat,
            debugGattWriteTiming = debugGattWriteTiming,
            onStatus = { message ->
                if (sender === newSender) setStatus(message)
            },
            onDisconnected = {
                runOnUiThread {
                    if (sender !== newSender) return@runOnUiThread
                    cameraEncoder?.close()
                    cameraEncoder = null
                    microphoneEncoder?.close()
                    microphoneEncoder = null
                    ivfReplay?.close()
                    ivfReplay = null
                    activeStreamSettings = null
                    thermalAdaptationApplied = false
                    thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                    sender = null
                    updateConnectionDot(0)
                    stopForegroundSessionIfIdle()
                    startIdleCameraPreview()
                    updateStartEnabled()
                }
            },
            onRemoteKeyFrameNeeded = { cameraEncoder?.requestKeyFrame() },
            thermalStatusProvider = { thermalStatus },
        )
        sender = newSender
        updateStartEnabled()
        val expectedPeerNodeIdPrefix = if (activeTransport.isBle) {
            selectedBlePeer()?.announcement?.nodeIdPrefix
        } else {
            null
        }
        newSender.connect(
            this,
            device,
            codec,
            activeSettings,
            activeTransport,
            rotationDegrees,
            expectedPeerNodeIdPrefix,
            debugReplayPath == null && debugBroadcastAudioEnabled,
            debugBlePhyPreference,
            debugCorruptRecord,
            debugMediaTraceSession != null,
            streamIoConfigFor(activeTransport),
            debugTraceSession = debugMediaTraceSession,
        ) {
            runOnUiThread {
                if (sender !== newSender) return@runOnUiThread
                updateConnectionDot(1)
                debugReplayPath?.let { replayPath ->
                    runCatching {
                        IvfVideoReplay(
                            File(replayPath),
                            codec.protocolId,
                            activeSettings,
                            debugImpairedSend(newSender::send),
                            { message -> if (sender === newSender) setStatus(message) },
                        ).also {
                            ivfReplay = it
                            it.start()
                        }
                    }.onFailure { error ->
                        if (sender === newSender) {
                            stopStreaming("Replay could not start: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                    return@runOnUiThread
                }
                val reusableViewfinderSurface = detachIdleCameraViewfinderSurface()
                clearVideoSurfaceTransform()
                lateinit var encoder: CameraVideoEncoder
                encoder = CameraVideoEncoder(
                    this,
                    cameraViewfinder,
                    cameraLens,
                    codec,
                    activeSettings,
                    benchmarkFrames,
                    debugMediaTraceSession,
                    debugImpairedSend(newSender::send),
                    { message ->
                        if (sender === newSender && cameraEncoder === encoder) setStatus(message)
                    },
                    reusableViewfinderSurface,
                    encoderRateControl,
                    videoScaleMode,
                    {
                        runOnUiThread {
                            if (
                                sender !== newSender || microphoneEncoder != null ||
                                !debugBroadcastAudioEnabled
                            ) return@runOnUiThread
                            runCatching { startMicrophoneAudio(newSender::send) }
                                .onFailure { error ->
                                    if (sender === newSender) {
                                        stopStreaming(
                                            "Microphone could not start: " +
                                                (error.message ?: error.javaClass.simpleName),
                                        )
                                    }
                                }
                        }
                    },
                    debugRawInputFile = debugRawInputFile,
                )
                cameraEncoder = encoder
                runCatching {
                    encoder.start()
                    applyThermalAdaptation(thermalStatus)
                }.onFailure { error ->
                    if (sender === newSender) {
                        stopStreaming("Camera or microphone could not start: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun outgoingVideoRotationDegrees(): Int =
        if (debugReplayPath != null || debugRawInputFile != null) 0
        else CameraOrientation.currentCameraRotationDegrees(this, cameraLens)

    private fun armDiscoverableStream() {
        if (!ensureThermalReady()) return
        streamArmed = true
        ActiveVideoSessionService.start(this, usesCamera = true)
        updateConnectionDot(0)
        setStatus("Stream is ON. Waiting for a receiver.")
        updateStartEnabled()
        startStreamPresence()
    }

    @SuppressLint("MissingPermission")
    private fun startRequestedBroadcast() {
        if (!streamArmed || !ensureThermalReady()) return
        val peers = resolveClassicViewerPeers(pendingViewerPeers)
        if (peers.isEmpty()) {
            setStatus("No receiver is ready yet.")
            return
        }
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition) ?: return
        val activeSettings = activeStreamSettings ?: settings
        val rotationDegrees = outgoingVideoRotationDegrees()
        broadcaster?.let { activeBroadcaster ->
            activeBroadcaster.connect(
                this,
                peers,
                codec,
                activeSettings,
                outgoingTransport,
                rotationDegrees,
                debugReplayPath == null && debugBroadcastAudioEnabled,
                debugBlePhyPreference,
                debugCorruptRecord,
                debugMediaTraceSession != null,
                streamIoConfigFor(outgoingTransport),
                debugMaximumChildren,
                debugTraceSession = debugMediaTraceSession,
            )
            refreshViewerDiscoveryForCapacity()
            return
        }
        activeStreamSettings = activeSettings
        applyThermalAdaptation(thermalStatus)
        val broadcastRateController = newLinkRateController(activeSettings, outgoingTransport)
        lateinit var newBroadcaster: BluetoothVideoBroadcaster
        newBroadcaster = BluetoothVideoBroadcaster(
            queueCapacity = activeSettings.queueCapacity,
            onLinkSample = { peer, sample -> onLinkRateSample(broadcastRateController, peer, sample) },
            encoderAtRateFloor = { broadcastRateController?.atRateFloor == true },
            debugDirectGattHeartbeat = debugDirectGattHeartbeat,
            debugGattWriteTiming = debugGattWriteTiming,
            onStatus = { message -> if (broadcaster === newBroadcaster) setStatus(message) },
            onConnectionsChanged = { count ->
                runOnUiThread {
                    if (broadcaster === newBroadcaster) {
                        updateConnectionDot(count)
                        updateEncoderFrameBudget(count)
                        updateStartEnabled()
                        refreshViewerDiscoveryForCapacity()
                        if (streamAdvertiser != null && streamArmed &&
                            newBroadcaster.sourceSlotsFull() != streamAdvertisedFull
                        ) {
                            // Tell viewers the source is full (or has a slot again) instead of
                            // letting them wait on "Connecting" and loop through cooldowns.
                            refreshStreamPresenceAdvertisement()
                        }
                    }
                }
            },
            onAllDisconnected = {
                runOnUiThread {
                    if (broadcaster !== newBroadcaster) return@runOnUiThread
                    cameraEncoder?.close()
                    cameraEncoder = null
                    microphoneEncoder?.close()
                    microphoneEncoder = null
                    ivfReplay?.close()
                    ivfReplay = null
                    activeStreamSettings = null
                    thermalAdaptationApplied = false
                    thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                    sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
                    streamPaused = false
                    broadcaster = null
                    updateConnectionDot(0)
                    startIdleCameraPreview()
                    startStreamPresence()
                    updateStartEnabled()
                }
            },
            onFirstConnected = {
                runOnUiThread {
                    if (broadcaster !== newBroadcaster) return@runOnUiThread
                    startBroadcastMedia(newBroadcaster, codec, activeSettings)
                }
            },
            onPeerReady = { cameraEncoder?.requestKeyFrame(force = true) },
            onRemoteKeyFrameNeeded = { cameraEncoder?.requestKeyFrame() },
            thermalStatusProvider = { thermalStatus },
            onCapacityAvailable = {
                runOnUiThread {
                    if (broadcaster === newBroadcaster && streamArmed) {
                        streamCapacityGeneration =
                            (streamCapacityGeneration + 1) and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK
                        if (streamAdvertiser != null) refreshStreamPresenceAdvertisement()
                    }
                }
            },
        )
        broadcaster = newBroadcaster
        updateStartEnabled()
        newBroadcaster.connect(
            this,
            peers,
            codec,
            activeSettings,
            outgoingTransport,
            rotationDegrees,
            debugReplayPath == null && debugBroadcastAudioEnabled,
            debugBlePhyPreference,
            debugCorruptRecord,
            debugMediaTraceSession != null,
            streamIoConfigFor(outgoingTransport),
            debugMaximumChildren,
            debugTraceSession = debugMediaTraceSession,
        )
        refreshViewerDiscoveryForCapacity()
    }

    private fun toggleBroadcastPause() {
        if (sessionRole != SessionRole.SEND) return
        val activeBroadcaster = broadcaster
        val activeSender = sender
        if (activeBroadcaster == null && activeSender == null) return
        if (activeBroadcaster == null) {
            streamPaused = !streamPaused
            if (streamPaused) activeSender?.pauseStream() else activeSender?.resumeStream()
            setStatus(if (streamPaused) "Test stream paused." else "Test stream resumed.")
            updatePauseButton()
            updateStartEnabled()
            return
        }
        if (!streamPaused) {
            streamPaused = true
            activeBroadcaster.pauseStream()
            cameraEncoder?.close()
            cameraEncoder = null
            microphoneEncoder?.close()
            microphoneEncoder = null
            ivfReplay?.close()
            ivfReplay = null
            startIdleCameraPreview()
            sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
            sessionHandler.postDelayed(pausedHeartbeatRunnable, PAUSED_HEARTBEAT_MS)
            refreshStreamPresenceAdvertisement()
            setStatus("Broadcast paused. Connections remain available.")
        } else {
            val codec = codecs.getOrNull(codecSpinner.selectedItemPosition) ?: return
            val activeSettings = activeStreamSettings ?: return
            streamPaused = false
            sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
            activeBroadcaster.resumeStream()
            refreshStreamPresenceAdvertisement()
            stopIdleCameraPreview()
            setStatus("Resuming broadcast...")
            startBroadcastMedia(activeBroadcaster, codec, activeSettings)
        }
        updatePauseButton()
        updateStartEnabled()
    }

    private fun startBroadcastMedia(
        activeBroadcaster: BluetoothVideoBroadcaster,
        codec: VideoCodec,
        activeSettings: VideoSettings,
    ) {
        if (broadcaster !== activeBroadcaster || streamPaused) return
        debugReplayPath?.let { replayPath ->
            runCatching {
                IvfVideoReplay(
                    File(replayPath),
                    codec.protocolId,
                    activeSettings,
                    debugImpairedSend(activeBroadcaster::send),
                    { message -> if (broadcaster === activeBroadcaster) setStatus(message) },
                ).also {
                    ivfReplay = it
                    it.start()
                }
            }.onFailure { error ->
                if (broadcaster === activeBroadcaster) {
                    stopStreaming("Replay could not start: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            return
        }
        val reusableViewfinderSurface = detachIdleCameraViewfinderSurface()
        clearVideoSurfaceTransform()
        lateinit var encoder: CameraVideoEncoder
        encoder = CameraVideoEncoder(
            this,
            cameraViewfinder,
            cameraLens,
            codec,
            activeSettings,
            benchmarkFrames,
            debugMediaTraceSession,
            debugImpairedSend(activeBroadcaster::send),
            { message ->
                if (broadcaster === activeBroadcaster && cameraEncoder === encoder) setStatus(message)
            },
            reusableViewfinderSurface,
            encoderRateControl,
            videoScaleMode,
            {
                runOnUiThread {
                    if (
                        broadcaster !== activeBroadcaster || streamPaused || microphoneEncoder != null ||
                            !debugBroadcastAudioEnabled
                    ) return@runOnUiThread
                    runCatching { startMicrophoneAudio(activeBroadcaster::send) }
                        .onFailure { error ->
                            if (broadcaster === activeBroadcaster) {
                                stopStreaming(
                                    "Microphone could not start: " +
                                        (error.message ?: error.javaClass.simpleName),
                                )
                            }
                        }
                }
            },
            debugRawInputFile = debugRawInputFile,
        )
        cameraEncoder = encoder
        layeringPolicy?.let { policy -> encoder.setDroppableFrames(policy.layered) }
        runCatching {
            encoder.start()
            updateEncoderFrameBudget(1)
            applyThermalAdaptation(thermalStatus)
        }.onFailure { error ->
            if (broadcaster === activeBroadcaster) {
                stopStreaming("Camera or microphone could not start: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveClassicViewerPeers(peers: List<BleL2capPeer>): List<BleL2capPeer> {
        if (outgoingTransport != BluetoothTransportOption.CLASSIC_RFCOMM) return peers
        val bondedDevices = bluetoothAdapter?.bondedDevices.orEmpty()
        return peers.map { peer ->
            val nodePrefix = peer.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }
            val debugAddressMatch = debugClassicAddress?.takeIf {
                nodePrefix == debugClassicAddressNodePrefix
            }?.let { bluetoothAdapter?.getRemoteDevice(it) }
            val addressMatch = bondedDevices.firstOrNull { device ->
                device.address.equals(peer.device.address, ignoreCase = true)
            }
            val discoveredName = runCatching { peer.device.name }.getOrNull()
            val uniqueNameMatch = if (addressMatch == null && !discoveredName.isNullOrBlank()) {
                bondedDevices.filter { device ->
                    runCatching { device.name }.getOrNull()
                        .equals(discoveredName, ignoreCase = true)
                }.singleOrNull()
            } else {
                null
            }
            peer.copy(device = debugAddressMatch ?: addressMatch ?: uniqueNameMatch ?: peer.device)
        }
    }

    private fun startReceiving() {
        if (appScreen != AppScreen.PLAYBACK || receiver != null || !receiverStartAllowed(
                selectedStreamPeer != null,
                reconnectTarget != null,
                debugLaunchRequested && !debugAutoSelectStream,
            )
        ) return
        if (!ensureThermalReady()) return
        val adapter = bluetoothAdapter ?: return
        val texture = preview.surfaceTexture ?: return
        val activeTransport = selectedStreamPeer?.announcement?.transportOption() ?: outgoingTransport
        ActiveVideoSessionService.start(this, usesCamera = false)
        receiverStreamEnded = false
        receiverPlaybackPaused = false
        receiverRouteWithdrawal = null
        resetReceiverRecoveryNotice()
        if (receiverJoinStartedAtNs <= 0L) receiverJoinStartedAtNs = System.nanoTime()
        receiverFirstSurfaceForJoinPending = true
        lateinit var newReceiver: AndroidVideoReceiver
        newReceiver = AndroidVideoReceiver(
            Surface(texture),
            { header ->
                runOnUiThread {
                    if (receiver !== newReceiver) return@runOnUiThread
                    if (!preserveReceiverContinuityOnNextFormat) {
                        if (receiverContinuityTracker != null) finishReceiverContinuity()
                        if (receiverJoinStartedAtNs <= 0L) receiverJoinStartedAtNs = System.nanoTime()
                        receiverContinuityTracker = RenderedFrameContinuityTracker(
                            header.fps,
                            receiverJoinStartedAtNs,
                        )
                    }
                    setVideoSurfaceFormat(header.width, header.height, header.rotationDegrees)
                }
            },
            { message ->
                runOnUiThread {
                    if (receiver === newReceiver) setStatus(message)
                }
            },
            { active ->
                runOnUiThread {
                    if (receiver === newReceiver && appScreen == AppScreen.PLAYBACK) {
                        if (active) {
                            receiverWasActive = true
                            consecutiveRouteFailures.clear()
                            reconnectFromNodeIdPrefix = null
                            preferredRecoveryParent = null
                            preserveReceiverContinuityOnNextFormat = false
                        }
                        updateConnectionDot(if (active) 1 else 0)
                    }
                }
            },
            { state ->
                runOnUiThread {
                    if (receiver !== newReceiver || appScreen != AppScreen.PLAYBACK) return@runOnUiThread
                    when (state) {
                        StreamLifecycleState.LIVE -> {
                            receiverPlaybackPaused = false
                            receiverContinuityTracker?.setActive(true)
                            showReceiverOverlay("Resuming stream", showProgress = true)
                        }
                        StreamLifecycleState.PAUSED -> {
                            receiverPlaybackPaused = true
                            resetReceiverRecoveryNotice()
                            receiverContinuityTracker?.setActive(false)
                            showReceiverOverlay("Paused by broadcaster", showProgress = false)
                        }
                        StreamLifecycleState.ENDED -> {
                            resetReceiverRecoveryNotice()
                            receiverContinuityTracker?.setActive(false)
                            receiverStreamEnded = true
                            updateConnectionDot(0)
                            showReceiverOverlay("Stream ended", showProgress = false)
                        }
                    }
                }
            },
            preferLowLatencyDecoder = debugLowLatencyDecoder,
            playbackDelayProfile = receiverPlaybackDelay,
            playbackDelayMs = debugPlaybackDelayMs ?: receiverPlaybackDelay.delayMs,
            preferredDecoderName = debugPreferredDecoderName,
            debugPresentationTiming = debugVideoPresentationTiming,
            debugReleaseAtPlayout = debugReleaseVideoAtPlayout,
            debugPacketDelayMs = debugReceiverPacketDelayMs,
            debugForwarderGattPriority = debugForwarderGattPriority,
            debugDirectGattHeartbeat = debugDirectGattHeartbeat,
            debugTraceSessionName = debugMediaTraceSession,
            forwardReceivedStreams = effectiveForwarding(selectedStreamPeer?.announcement?.streamToken ?: 0),
            expectedMediaSourceNodeId = activeStreamSourceNodeId,
            onMediaSourceAuthenticated = { sourceNodeId ->
                if (receiver === newReceiver) {
                    activeStreamSourceNodeId = sourceNodeId
                }
            },
            onVideoRecovery = { recovering ->
                runOnUiThread {
                    if (receiver !== newReceiver || appScreen != AppScreen.PLAYBACK) {
                        return@runOnUiThread
                    }
                    receiverRecoveryNoticeRunnable?.let(sessionHandler::removeCallbacks)
                    receiverRecoveryNoticeRunnable = null
                    val nowMs = SystemClock.elapsedRealtime()
                    receiverRecoveryNoticePolicy.update(recovering, nowMs)
                    Log.i(
                        "BluetoothVideo",
                        "Receiver recovery UI: recovering=$recovering | firstSurfacePending=$receiverFirstSurfaceForJoinPending | " +
                            "noticeAlpha=${receiverWaitingText.alpha}",
                    )
                    if (receiverPlaybackPaused || receiverStreamEnded || receiverFirstSurfaceForJoinPending) {
                        return@runOnUiThread
                    }
                    if (recovering) {
                        // Keep the historical corruption cover; brief recovery needs no flashing label.
                        if (receiverRecoveryNoticePolicy.noticeDelayRemainingMs(nowMs) != 0L) {
                            showReceiverOverlay("", showProgress = false)
                        }
                        val notice = object : Runnable {
                            override fun run() {
                                if (receiverRecoveryNoticeRunnable !== this || receiver !== newReceiver ||
                                    appScreen != AppScreen.PLAYBACK || !receiverRecoveryNoticePolicy.recovering ||
                                    receiverPlaybackPaused || receiverStreamEnded
                                ) return
                                val alpha = receiverRecoveryNoticePolicy.noticeAlpha(SystemClock.elapsedRealtime())
                                showReceiverOverlay("Recovering video", showProgress = true, noticeAlpha = alpha)
                                if (alpha < 1f) {
                                    sessionHandler.postDelayed(this, 16L)
                                } else {
                                    receiverRecoveryNoticeRunnable = null
                                    Log.i("BluetoothVideo", "Receiver recovery notice: fully visible")
                                }
                            }
                        }
                        receiverRecoveryNoticeRunnable = notice
                        sessionHandler.postDelayed(
                            notice,
                            receiverRecoveryNoticePolicy.noticeDelayRemainingMs(nowMs) ?: 0L,
                        )
                    } else if (receiverWaitingText.text.toString() in setOf("Recovering video", "")) {
                        hideReceiverOverlay()
                    }
                }
            },
            onRouteWithdrawn = { reason ->
                runOnUiThread {
                    if (receiver !== newReceiver) return@runOnUiThread
                    receiverRouteWithdrawal = reason
                    selectedStreamPeer?.let { peer ->
                        val withdrawnRoute = StreamReconnectTarget.from(peer)
                        suppressedStreamRoutes[withdrawnRoute] = streamRouteSuppressionDeadline(
                            suppressedStreamRoutes[withdrawnRoute],
                            SystemClock.elapsedRealtime() + WITHDRAWN_ROUTE_SUPPRESSION_MS,
                        )
                        if (reason == StreamRouteWithdrawal.LOCAL_CAPACITY) {
                            capacitySuppressedRouteGenerations[withdrawnRoute] = peer.announcement.capacityGeneration()
                        }
                    }
                }
            },
        ) { failureNotice ->
            runOnUiThread {
                if (receiver !== newReceiver) return@runOnUiThread
                val peer = selectedStreamPeer
                val ended = receiverStreamEnded
                val wasActive = receiverWasActive
                val routeWithdrawal = receiverRouteWithdrawal
                val stopCause = newReceiver.stopCause
                preferredRecoveryParent = recoveryParentPreference(
                    wasActive = wasActive,
                    linkLoss = routeWithdrawal == null && stopCause?.kind == ReceiverStopKind.LINK && !ended,
                    parentRouteCost = peer?.announcement?.routeCost,
                    parentPrefix = peer?.announcement?.nodeIdPrefix,
                    enabled = DebugTunables.relayChildReanchor,
                )
                preferredRecoveryGraceUntilMs = preferredParentGraceDeadline(
                    preferredRecoveryParent,
                    SystemClock.elapsedRealtime(),
                    DebugTunables.relayRecoveryGrace,
                )
                preferredRecoveryGraceLogged = false
                sessionHandler.removeCallbacks(preferredRecoveryGraceExpired)
                if (preferredRecoveryGraceUntilMs > 0L) {
                    // Offers keep arriving while scanning; this re-check covers a quiet scan.
                    sessionHandler.postDelayed(preferredRecoveryGraceExpired, PREFERRED_PARENT_GRACE_MS)
                }
                val localMediaFailure = stopCause?.retriesAutomatically == false
                var establishFallback = false
                if (DebugTunables.relayEstablishFallback && peer != null) {
                    val parentKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                    val nowMs = SystemClock.elapsedRealtime()
                    if (wasActive) {
                        parentEstablishment.onSuccess(parentKey)
                    } else if (!ended && isChildEstablishmentFailure(wasActive, stopCause?.signature) &&
                        parentEstablishment.onFailure(parentKey, nowMs)
                    ) {
                        // The chosen parent cannot establish a link: look for the next-best route to
                        // the same stream now instead of waiting for another explicit choice.
                        establishFallback = true
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK relay_parent_fallback: reason=establishment | side=child | parent=$parentKey | " +
                                "hops=${peer.announcement.routeCost} | failures=${EstablishmentFailureTracker.THRESHOLD} | " +
                                "windowMs=${EstablishmentFailureTracker.WINDOW_MS} | markMs=${EstablishmentFailureTracker.MARK_MS}",
                        )
                    }
                }
                val repeatBackoffMs = if (!ended && !localMediaFailure && stopCause != null) {
                    repeatedReceiverFailures.record(stopCause.signature, SystemClock.elapsedRealtime())
                } else {
                    0L
                }
                if (localMediaFailure) {
                    receiverLocalMediaFailure = failureNotice
                    Log.w(
                        "BluetoothVideo",
                        "BENCHMARK receiver local media failure: no reconnect | cause=${stopCause?.signature}",
                    )
                } else if (repeatBackoffMs > 0L) {
                    Log.w(
                        "BluetoothVideo",
                        "Receiver failure repeated ${repeatedReceiverFailures.repeats}x: backing off " +
                            "$repeatBackoffMs ms | cause=${stopCause?.signature}",
                    )
                }
                resetReceiverRecoveryNotice()
                receiver = null
                stopForegroundSessionIfIdle()
                updateStartEnabled()
                if (appScreen == AppScreen.PLAYBACK) {
                    returnToStreamBrowser(
                        peer,
                        ended,
                        allowReconnect = !localMediaFailure &&
                            (wasActive || routeWithdrawal == StreamRouteWithdrawal.LOCAL_CAPACITY || establishFallback),
                        failureNotice = if (!localMediaFailure && routeWithdrawal == StreamRouteWithdrawal.LOCAL_CAPACITY) {
                            streamCapacityWithdrawalNotice(peer?.announcement?.routeCost)
                        } else {
                            failureNotice
                        },
                        localMediaFailure = localMediaFailure,
                        minimumRetryDelayMs = repeatBackoffMs,
                    )
                }
            }
        }
        receiver = newReceiver
        updateStartEnabled()
        val streamPeer = selectedStreamPeer
        runCatching {
            newReceiver.start(
                this,
                adapter,
                activeTransport,
                streamToken = streamPeer?.announcement?.streamToken ?: 0,
                routeCost = streamPeer?.announcement?.routeCost ?: 0,
                streamBitrateKbps = streamPeer?.announcement?.streamBitrateKbps ?: 0,
                expectedSourceNodeIdPrefix = streamPeer?.announcement?.nodeIdPrefix,
                recoveryFromNodeIdPrefix = reconnectFromNodeIdPrefix,
                phyPreference = debugBlePhyPreference,
                ioConfig = streamIoConfigFor(activeTransport),
            )
        }.onFailure { error ->
            if (receiver === newReceiver) {
                receiver = null
                newReceiver.close()
                stopForegroundSessionIfIdle()
                setStatus("Receiver could not start: ${error.message ?: error.javaClass.simpleName}")
                updateStartEnabled()
            }
        }
    }

    private fun startRelaying() {
        if (!ensureThermalReady()) return
        val adapter = bluetoothAdapter ?: return
        val children = if (debugRelayChildCount > 1) {
            blePeers.take(debugRelayChildCount)
        } else {
            listOfNotNull(selectedBlePeer())
        }
        if (children.size != debugRelayChildCount) {
            setStatus("Waiting for $debugRelayChildCount distinct receivers.")
            return
        }
        ActiveVideoSessionService.start(this, usesCamera = false)
        bleDiscovery?.close()
        bleDiscovery = null
        if (debugPrivateCallRelay) {
            val callee = children.singleOrNull() ?: run {
                setStatus("A private call relay requires one callee.")
                return
            }
            lateinit var newRelay: BluetoothPrivateCallRelay
            newRelay = BluetoothPrivateCallRelay(
                { message -> if (privateCallRelay === newRelay) setStatus(message) },
            ) {
                runOnUiThread {
                    if (privateCallRelay !== newRelay) return@runOnUiThread
                    privateCallRelay = null
                    stopForegroundSessionIfIdle()
                    updateStartEnabled()
                }
            }
            privateCallRelay = newRelay
            updateStartEnabled()
            runCatching { newRelay.start(this, adapter, callee, outgoingTransport) }.onFailure { error ->
                if (privateCallRelay === newRelay) {
                    privateCallRelay = null
                    newRelay.close()
                    stopForegroundSessionIfIdle()
                    setStatus("Private-call relay could not start: ${error.message ?: error.javaClass.simpleName}")
                    updateStartEnabled()
                }
            }
            return
        }
        lateinit var newRelay: BluetoothVideoRelay
        newRelay = BluetoothVideoRelay(
            { message ->
                if (relay === newRelay) setStatus(message)
            },
        ) {
            runOnUiThread {
                if (relay !== newRelay) return@runOnUiThread
                relay = null
                stopForegroundSessionIfIdle()
                updateStartEnabled()
            }
        }
        relay = newRelay
        updateStartEnabled()
        runCatching { newRelay.start(this, adapter, children, outgoingTransport) }.onFailure { error ->
            if (relay === newRelay) {
                relay = null
                newRelay.close()
                stopForegroundSessionIfIdle()
                setStatus("Relay could not start: ${error.message ?: error.javaClass.simpleName}")
                updateStartEnabled()
            }
        }
    }

    private fun stopStreaming(message: String) {
        val impairment = debugBroadcastImpairment
        impairment?.cancel()
        debugBroadcastImpairment = null
        resetReceiverRecoveryNotice()
        finishReceiverContinuity()
        thermalHandler.removeCallbacks(thermalRecoveryRunnable)
        sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
        thermalAdaptationApplied = false
        streamArmed = false
        streamPaused = false
        if (usesBroadcastDiscovery()) {
            stopStreamPresence()
        }
        localTestRunning = false
        val activeSender = sender
        sender = null
        activeSender?.endStream()
        val activeBroadcaster = broadcaster
        broadcaster = null
        activeBroadcaster?.endStream()
        val activeReceiver = receiver
        receiver = null
        activeReceiver?.close()
        val activeRelay = relay
        relay = null
        activeRelay?.close()
        val activePrivateCallRelay = privateCallRelay
        privateCallRelay = null
        activePrivateCallRelay?.close()
        ActiveVideoSessionService.stop(this)
        cameraEncoder?.close()
        cameraEncoder = null
        microphoneEncoder?.close()
        microphoneEncoder = null
        ivfReplay?.close()
        ivfReplay = null
        impairment?.close()
        activeStreamSettings = null
        clearVideoSurfaceTransform()
        if (appScreen == AppScreen.CAMERA) {
            startIdleCameraPreview()
            if (usesBroadcastDiscovery()) {
                startStreamPresence()
            }
        }
        setStatus(message)
        updateStartEnabled()
    }

    private fun finishReceiverContinuity() {
        val tracker = receiverContinuityTracker ?: return
        logReceiverContinuity(tracker.snapshot(System.nanoTime()), final = true)
        receiverContinuityTracker = null
        receiverJoinStartedAtNs = 0L
        receiverContinuityLastReportNs = 0L
    }

    private fun logReceiverContinuity(
        snapshot: RenderedFrameContinuitySnapshot,
        final: Boolean = false,
        label: String = "Receiver",
    ) {
        Log.i(
            "BluetoothVideo",
            "$label visible continuity${if (final) " FINAL" else ""}: " +
                "${snapshot.renderedFrames} frames | ${snapshot.freezeEvents} freeze event(s), " +
                "%.1f ms, %.2f%% | threshold %.1f ms".format(
                    Locale.US,
                    snapshot.freezeDurationMs,
                    snapshot.freezeRatioPercent,
                    snapshot.freezeThresholdMs,
                ) + " | surface cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms".format(
                    Locale.US,
                    snapshot.surfaceFrameInterval.p50Ms,
                    snapshot.surfaceFrameInterval.p95Ms,
                    snapshot.surfaceFrameInterval.p99Ms,
                    snapshot.surfaceFrameInterval.maxMs,
                ) + continuityFirstSurfaceSuffix(snapshot),
        )
        // Both arms: user-centred continuity (frozen share, longest stall) for the media-age A/B.
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK continuity: role=${if (label == "Receiver") "stream_rx" else "call_rx"} | final=$final | " +
                "policy=${BoundedMediaAge.policyLabel()} | frames=${snapshot.renderedFrames} | " +
                "freezeEvents=${snapshot.freezeEvents} | " +
                "frozenMs=%.1f | frozenPct=%.2f | longestStallMs=%.1f | activeMs=%.1f".format(
                    Locale.US,
                    snapshot.freezeDurationMs,
                    snapshot.freezeRatioPercent,
                    snapshot.longestGapMs,
                    snapshot.observedActiveMs,
                ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneAudio(
        onPacket: (flags: Int, timeUs: Long, bytes: ByteArray) -> Unit,
    ) {
        check(microphoneEncoder == null) { "Microphone audio is already active" }
        lateinit var newEncoder: MicrophoneOpusEncoder
        newEncoder = MicrophoneOpusEncoder(
            debugImpairedSend(onPacket),
            { message -> if (microphoneEncoder === newEncoder) setStatus(message) },
            { message ->
                runOnUiThread {
                    if (microphoneEncoder === newEncoder) {
                        stopStreaming("Microphone audio stopped: $message")
                    }
                }
            },
            debugReferenceFile = debugReferenceAudioFile,
            debugReferenceVideoFrames = referenceAudioVideoFrames(),
            debugReferenceVideoFps = (activeStreamSettings ?: settings).fps,
            debugReferenceClock = { cameraEncoder?.benchmarkRawClock },
            debugTraceContext = this,
            debugTraceSession = debugMediaTraceSession,
        )
        microphoneEncoder = newEncoder
        runCatching { newEncoder.start() }.onFailure {
            if (microphoneEncoder === newEncoder) microphoneEncoder = null
            newEncoder.close()
            throw it
        }
    }

    private fun stopForegroundSessionIfIdle() {
        if (!streamArmed && sender == null && broadcaster == null && receiver == null && relay == null &&
            privateCallRelay == null
        ) {
            ActiveVideoSessionService.stop(this)
        }
    }

    private fun setVideoSurfaceFormat(
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        surfaceVideoFormat = SurfaceVideoFormat(
            width,
            height,
            rotationDegrees,
            videoScaleMode,
        )
        applyVideoSurfaceTransform()
    }

    private fun applyVideoSurfaceTransform() {
        val format = surfaceVideoFormat ?: return
        VideoSurfaceTransform.apply(
            preview,
            format.width,
            format.height,
            format.rotationDegrees,
            format.scaleMode,
            debugTraceSession = debugMediaTraceSession,
        )
    }

    private fun restoreVideoScaleMode() {
        videoScaleMode = VideoScaleMode.fromUnifiedOrLegacyValues(
            appPreferences.getString(PREF_VIDEO_SCALE_MODE, null),
            appPreferences.getString(PREF_SENDER_PREVIEW_SCALE_MODE, null),
            appPreferences.getString(PREF_RECEIVER_PLAYBACK_SCALE_MODE, null),
        )
        appPreferences.edit()
            .putString(PREF_VIDEO_SCALE_MODE, videoScaleMode.name)
            .remove(PREF_SENDER_PREVIEW_SCALE_MODE)
            .remove(PREF_RECEIVER_PLAYBACK_SCALE_MODE)
            .apply()
    }

    private fun applyVideoScaleMode() {
        cameraViewfinder.scaleType = when (videoScaleMode) {
            VideoScaleMode.FILL -> ScaleType.FILL_CENTER
            VideoScaleMode.FIT -> ScaleType.FIT_CENTER
        }
        surfaceVideoFormat = surfaceVideoFormat?.copy(scaleMode = videoScaleMode)
        applyVideoSurfaceTransform()
        privateCallSurfaceFormat = privateCallSurfaceFormat?.copy(scaleMode = videoScaleMode)
        callLocalViewfinder.scaleType = when (videoScaleMode) {
            VideoScaleMode.FILL -> ScaleType.FILL_CENTER
            VideoScaleMode.FIT -> ScaleType.FIT_CENTER
        }
        applyPrivateCallSurfaceTransform()
    }

    private fun clearVideoSurfaceTransform() {
        surfaceVideoFormat = null
        if (::preview.isInitialized) preview.setTransform(null)
    }

    private fun startIdleCameraPreview() {
        if (debugRawInputFile != null || !activityResumed || appScreen != AppScreen.CAMERA || sessionRole != SessionRole.SEND ||
            sender != null || (broadcaster != null && !streamPaused) || receiver != null ||
            cameraEncoder != null || cameraPreview != null ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lateinit var newPreview: CameraPreview
        newPreview = CameraPreview(
            this,
            cameraViewfinder,
            cameraLens,
            settings.resolution,
            videoScaleMode,
        ) { message -> if (cameraPreview === newPreview) setStatus(message) }
        cameraPreview = newPreview
        runCatching { newPreview.start() }.onFailure { error ->
            if (cameraPreview === newPreview) cameraPreview = null
            newPreview.close()
            setStatus("Camera preview could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopIdleCameraPreview() {
        cameraPreview?.close()
        cameraPreview = null
    }

    private fun detachIdleCameraViewfinderSurface(): Pair<Surface, CameraViewfinderSurface>? {
        val activePreview = cameraPreview ?: return null
        cameraPreview = null
        val surface = activePreview.detachViewfinderSurface()
        activePreview.close()
        return surface
    }

    private fun restartIdleCameraPreview() {
        stopIdleCameraPreview()
        startIdleCameraPreview()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (!bluetoothStateReceiverRegistered) {
            registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            bluetoothStateReceiverRegistered = true
        }
        updateHomeReadiness()
        // Pick up link capacity measured since the picker was configured (workstream D).
        if (::bitrateSeekBar.isInitialized) {
            applyTransportBitrateLimit(persist = false)
            updateSettingsLabels()
        }
        if (BuildConfig.DEBUG && privateCall != null) Log.i("BluetoothVideo", "BENCHMARK call lifecycle foreground applied")
        if (appScreen == AppScreen.PLAYBACK) receiverContinuityTracker?.setActive(true)
        if (appScreen == AppScreen.CALL && callRemoteCameraOff.visibility != View.VISIBLE) {
            debugPrivateCallSurfacePending = BuildConfig.DEBUG
            privateCallContinuityTracker?.setActive(true)
        }
        if (appScreen == AppScreen.CALL) callScreenController.setForeground(true)
        privateCallRawSelfView?.setPaused(false)
        thermalHandler.removeCallbacks(thermalDisplayRunnable)
        thermalHandler.post(thermalDisplayRunnable)
        if (activeCameraViewfinderNeedsRefresh) {
            activeCameraViewfinderNeedsRefresh = false
            cameraEncoder?.refreshViewfinderSurface()
            privateCallCameraEncoder?.refreshViewfinderSurface()
        }
        startIdleCameraPreview()
        if (appScreen == AppScreen.RECEIVER_BROWSER && hasRequiredPermissions()) {
            refreshNearbyStreams()
        }
        if (appScreen == AppScreen.CALL_BROWSER && hasRequiredPermissions()) {
            startPrivateCallEndpoint()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sessions are owned by this activity, so recreating it would end a live stream or call
        // (theme schedule, font/display size, split-screen, or Android 16 large-screen rotation).
        // Apply new resources by recreating only when nothing is live.
        val live = streamArmed || sender != null || broadcaster != null || receiver != null ||
            relay != null || privateCallRelay != null ||
            (privateCall != null && !(reachabilityScreen() == ReachabilityScreen.HOME && privateCallEndpointIdle()))
        Log.i("BluetoothVideo", "Configuration changed | liveSession=$live | recreate=${!live}")
        if (!live) recreate()
    }

    override fun onPause() {
        activityResumed = false
        if (bluetoothStateReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothStateReceiverRegistered = false
        }
        if (BuildConfig.DEBUG && privateCall != null) Log.i("BluetoothVideo", "BENCHMARK call lifecycle background applied")
        if (appScreen == AppScreen.PLAYBACK) receiverContinuityTracker?.setActive(false)
        if (appScreen == AppScreen.CALL) privateCallContinuityTracker?.setActive(false)
        if (::callScreenController.isInitialized) callScreenController.setForeground(false)
        privateCallRawSelfView?.setPaused(true)
        thermalHandler.removeCallbacks(thermalDisplayRunnable)
        if (appScreen == AppScreen.RECEIVER_BROWSER) stopNearbyStreamDiscovery()
        if (appScreen == AppScreen.CALL_BROWSER) stopPrivateCallDiscovery()
        stopIdleCameraPreview()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (!startupAborted) refreshCallReachability()
    }

    override fun onStop() {
        if (cameraEncoder != null || privateCallCameraEncoder != null) {
            activeCameraViewfinderNeedsRefresh = true
        }
        activityStarted = false
        // Home reachability continues in the background only with the connectedDevice service.
        if (!startupAborted) refreshCallReachability()
        super.onStop()
    }

    /**
     * Before a user starts streaming, viewing or calling, recommends turning off 2.4 GHz Wi-Fi,
     * which time-shares the radio with Bluetooth: S24 -> M31 GATT 600 kbps ran at 1.9 FPS with
     * the M31 on 2.4 GHz Wi-Fi and 13 FPS with it off. The user may continue anyway.
     */
    private fun withWifiCheck(action: () -> Unit) {
        val advice = WifiCoexistence.advice()
        val recentlyAnswered = System.currentTimeMillis() - wifiAdviceAnsweredAtMs < WIFI_ADVICE_QUIET_MS
        if (advice == null || recentlyAnswered) {
            action()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Turn off Wi-Fi for smoother video")
            .setMessage(advice)
            .setPositiveButton("Open Wi-Fi settings") { _, _ ->
                wifiAdviceAnsweredAtMs = System.currentTimeMillis()
                val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(android.provider.Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                }
                runCatching { startActivity(panel) }
            }
            .setNegativeButton("Continue anyway") { _, _ ->
                wifiAdviceAnsweredAtMs = System.currentTimeMillis()
                action()
            }
            .show()
    }

    private fun setStatus(message: String) {
        Log.i("BluetoothVideo", message)
        runOnUiThread { statusText.text = message }
    }

    private fun registerThermalGuard() {
        thermalStatus = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(mainExecutor, thermalStatusListener)
        thermalListenerRegistered = true
        updateThermalBadge()
        if (ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) {
            setStatus("Phone is critically hot. Let it cool before starting video.")
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            setStatus(
                if (thermalReductionOn) {
                    "Phone is hot. Streams will start with reduced FPS and bitrate."
                } else {
                    "Phone is hot. Automatic cooling is off."
                },
            )
        }
        updateStartEnabled()
    }

    /** Automatic rate control adapts to measured delivery; CBR/VBR/CBR-FD keep the exact selection. */
    /**
     * Automatic calls fit the measured link instead of the fixed per-direction caps (GATT 180, CoC
     * 200, Classic 250, legacy GATT 150 kbps): uncapped GATT calls M31<->ASUS ran 300 and 500 kbps per
     * direction with 0 freezes and adapted 800 down to 450-700 kbps (lane-b-calls-529f36cc). Exact
     * CBR/VBR modes have no adaptation and keep the caps; a DEBUG run can still force preservation.
     */
    private fun callBitrateAdapts(): Boolean =
        encoderRateControl == EncoderRateControl.AUTOMATIC ||
            debugPreserveCallBitrate && debugPreserveCallBitrateApplies(outgoingTransport)

    private fun newLinkRateController(selected: VideoSettings, transport: BluetoothTransportOption): LinkRateController? {
        linkRateLimitBps = null
        layeringPolicy = if (DebugTunables.experimentalTemporalLayers) {
            LayeringPolicy(
                priorLinkKbps = LinkCapacity.measuredLinkKbps(transport),
                initialOfferedKbps = (selected.bitRate + StreamProtocol.AUDIO_BIT_RATE) / 1_000,
            )
        } else {
            null
        }
        return if (encoderRateControl == EncoderRateControl.AUTOMATIC) {
            Log.i("BluetoothVideo", "Rate adaptation armed (Automatic): requested ${selected.bitRate / 1_000} kbps")
            LinkRateController(
                selected.bitRate,
                memory = LinkCapacity.sustainedRateMemory(transport, "stream", buildKeyed = !startRateGuardOn()),
                burstAwareDrain = DebugTunables.experimentalBurstDrain,
                deliveryProbing = DebugTunables.deliveryRateControl,
                rememberCuts = !DebugTunables.deliveryReports,
            )
        } else {
            null
        }
    }

    /** Learned call start and build-independent learned rates; off in the delivery-reports control arm too. */
    private fun startRateGuardOn(): Boolean = DebugTunables.startRateGuard && DebugTunables.deliveryReports

    private fun onPrivateCallLinkSample(sample: LinkRateSample) {
        val controller = privateCallRateController ?: return
        val changed = controller.onSample(PRIVATE_CALL_RATE_LINK, sample, System.nanoTime())
        if (changed == null) {
            // The ladder's hold times need every sample, not only target changes.
            if (privateCallResolutionLadder != null) runOnUiThread { evaluatePrivateCallResolution(controller) }
            return
        }
        runOnUiThread {
            if (privateCallRateController !== controller) return@runOnUiThread
            val selected = privateCallSettings ?: return@runOnUiThread
            privateCallLinkRateLimitBps = changed.takeIf { it < selected.bitRate }
            val linkFps = controller.frameRateFor(changed).coerceAtMost(selected.fps)
            privateCallLinkFps = linkFps.takeIf { it < selected.fps }
            val thermalMaximum = ThermalGuardPolicy.maximumBitrate(
                selected.bitRate,
                thermalStatus,
                thermalReductionOn,
            )
            val thermalFps = ThermalGuardPolicy.maximumFps(selected.fps, thermalStatus, thermalReductionOn)
            privateCallCameraEncoder?.updateBitrate(minOf(changed, thermalMaximum))
            privateCallCameraEncoder?.updateFrameRate(minOf(linkFps, thermalFps))
            Log.i(
                "BluetoothVideo",
                "Call rate adaptation: sending ${changed / 1_000} of ${selected.bitRate / 1_000} kbps video at $linkFps FPS " +
                    "(backlog ${sample.backlogAgeMs} ms, peer late ${sample.childLate}, measured ${sample.measuredLinkKbps} kbps, " +
                    "delivered ${sample.deliveredKbps} kbps, peer queue ${sample.peerQueueMs} ms)",
            )
            evaluatePrivateCallResolution(controller)
        }
    }

    /** The video target the call encoder now has: the link limit and the thermal limit, the lower. */
    private fun privateCallVideoTargetBps(selected: VideoSettings): Int = minOf(
        privateCallLinkRateLimitBps ?: selected.bitRate,
        ThermalGuardPolicy.maximumBitrate(selected.bitRate, thermalStatus, thermalReductionOn),
    )

    /**
     * Call resolution ladder (CallResolutionLadderFeature, default on for user calls). Built from
     * the modes every lens offers at the call's FPS, below the call's start geometry (already
     * limited by DeviceFamilyPolicy call caps and the encoder fit). Exact rate control, raw inputs
     * and benchmark launches keep the requested geometry and log requested == effective.
     */
    private fun newPrivateCallResolutionLadder(settings: VideoSettings, codec: VideoCodec): CallResolutionLadder? {
        val size = "${settings.resolution.width}x${settings.resolution.height}"
        val offReason = when {
            !CallResolutionLadderFeature.enabled -> if (DebugTunables.benchmarkLaunch) "off_benchmark" else "off"
            privateCallRateController == null -> "off_exact_rate_control"
            debugRawInputFile != null -> "off_raw_input"
            else -> null
        }
        if (offReason != null) {
            logCallResolution("requested=$size effective=$size reason=$offReason")
            return null
        }
        val ladder = runCatching {
            val lenses = availableCameraLenses.ifEmpty { listOf(cameraLens) }
            val common = lenses.map { lens ->
                deviceVideoCapabilities.modesFor(codec, settings.bitRate, lens)
                    .filter { settings.fps in it.fpsOptions }
                    .map { it.resolution.width to it.resolution.height }
                    .toSet()
            }.reduce { all, lens -> all intersect lens }
            CallResolutionLadder(
                settings.resolution,
                common.map { (width, height) -> VideoResolution(width, height, "${height}p") },
                codec.protocolId,
            )
        }.getOrElse { error ->
            Log.w("BluetoothVideo", "Call resolution ladder unavailable: ${error.message}")
            null
        }
        if (ladder == null || !ladder.active) {
            logCallResolution("requested=$size effective=$size reason=no_smaller_rung")
            return null
        }
        val decision = ladder.start(privateCallVideoTargetBps(settings), SystemClock.elapsedRealtime())
        logCallResolution(
            "requested=$size effective=${decision.resolution.width}x${decision.resolution.height} " +
                "reason=${decision.reason} | codec=${codec.protocolId} | fps=${settings.fps} | " +
                "rungs=${ladder.rungs.joinToString(",") { "${it.width}x${it.height}" }}",
        )
        return ladder
    }

    private fun logCallResolution(detail: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_resolution $detail")
    }

    /** Applies a ladder change by restarting the call camera encoder; its first frame is a keyframe. */
    private fun evaluatePrivateCallResolution(controller: LinkRateController) {
        if (privateCallRateController !== controller) return
        val ladder = privateCallResolutionLadder ?: return
        val selected = privateCallSettings ?: return
        val decision = ladder.onTarget(privateCallVideoTargetBps(selected), SystemClock.elapsedRealtime()) ?: return
        logCallResolution(
            "requested=${ladder.requested.width}x${ladder.requested.height} " +
                "effective=${decision.resolution.width}x${decision.resolution.height} reason=${decision.reason}",
        )
        // Camera off: the next camera start uses the new rung.
        if (!privateCallCameraEnabled || privateCallCameraEncoder == null) return
        val codec = privateCallCodec ?: return
        stopPrivateCallCamera()
        startPrivateCallCamera(codec, selected)
    }

    /** Experimental temporal layering only (null otherwise): droppable frames while a link is tight. */
    @Volatile private var layeringPolicy: LayeringPolicy? = null

    private fun onLinkRateSample(controller: LinkRateController?, link: Any, sample: LinkRateSample) {
        layeringPolicy?.onSample(sample)?.let { layered ->
            cameraEncoder?.setDroppableFrames(layered)
            Log.i(
                "BluetoothVideo",
                if (layered) {
                    "Droppable frames on (experimental): a link is tight or not yet measured"
                } else {
                    "Droppable frames off (experimental): every link has had room for 20 s; all frames are references"
                },
            )
        }
        if (BuildConfig.DEBUG && controller != null &&
            (sample.childLate || sample.backlogAgeMs >= LinkRateController.CONGESTED_BACKLOG_MS ||
                sample.peerQueueMs >= LinkRateController.QUEUE_PRESENT_MS)
        ) {
            Log.i(
                "BluetoothVideo",
                "Rate evidence: offered ${sample.offeredBytes * 8 / 1_000} kbit, sent ${sample.sentBytes * 8 / 1_000} kbit, " +
                    "backlog ${sample.backlogAgeMs} ms, child late ${sample.childLate}, " +
                    "measured ${sample.measuredLinkKbps} kbps, large-frame grace ${sample.largeFrameGrace}, " +
                    "large frame ${sample.largeFrameAgeMs} ms ago, burst-attributed " +
                    "${DebugTunables.experimentalBurstDrain && sample.childLate && LinkRateController.burstExplainsLateness(sample)}, " +
                    "delivered ${sample.deliveredKbps} kbps, peer queue ${sample.peerQueueMs} ms",
            )
        }
        val changed = controller?.onSample(link, sample, System.nanoTime()) ?: return
        runOnUiThread {
            val selected = activeStreamSettings ?: return@runOnUiThread
            linkRateLimitBps = changed.takeIf { it < selected.bitRate }
            applyThermalAdaptation(thermalStatus)
            val message = if (linkRateLimitBps != null) {
                "Link limited: sending ${changed / 1_000} of ${selected.bitRate / 1_000} kbps video"
            } else {
                "Link recovered: sending the selected ${selected.bitRate / 1_000} kbps video"
            }
            Log.i("BluetoothVideo", "Rate adaptation: $message")
            setStatus(message)
        }
    }

    /** Emits the `BENCHMARK thermal:` evidence line when its content changes (not on every rate sample). */
    private fun logThermalDecision(reported: Int, effective: Int, speedFloorValue: Int, source: String) {
        val line = MemoryThermalPolicy.benchmarkLine(
            reported, thermalForecastHeadroom, effective, thermalReductionOn, speedFloorValue, source,
        )
        val content = line.substringBefore(" | source=")
        if (content == lastThermalBenchmarkLine) return
        lastThermalBenchmarkLine = content
        Log.i("BluetoothVideo", line)
    }

    private fun applyThermalAdaptation(
        reportedStatus: Int,
        source: String = "adapt",
    ): Int? {
        val selected = activeStreamSettings ?: return null
        val status = MemoryThermalPolicy.effectiveStatus(
            reportedStatus,
            thermalForecastHeadroom,
            thermalForecastOn,
        )
        val speedFloorValue = MemoryThermalPolicy.nativeSpeedFloor(
            status,
            thermalReductionOn,
            MemoryThermalPolicy.speedFloor,
        )
        cameraEncoder?.updateThermalSpeedFloor(speedFloorValue)
        privateCallCameraEncoder?.updateThermalSpeedFloor(speedFloorValue)
        logThermalDecision(reportedStatus, status, speedFloorValue, source)
        val maximumFps = ThermalGuardPolicy.maximumFps(
            selected.fps,
            status,
            thermalReductionOn,
        )
        val maximumBitrate = ThermalGuardPolicy.maximumBitrate(
            selected.bitRate,
            status,
            thermalReductionOn,
        )
        // The stricter of the thermal and measured-link limits applies to the stream encoder.
        cameraEncoder?.updateBitrate(linkRateLimitBps?.let { minOf(it, maximumBitrate) } ?: maximumBitrate)
        cameraEncoder?.updateFrameRate(maximumFps)
        privateCallCameraEncoder?.updateBitrate(privateCallLinkRateLimitBps?.let { minOf(it, maximumBitrate) } ?: maximumBitrate)
        privateCallCameraEncoder?.updateFrameRate(privateCallLinkFps?.let { minOf(it, maximumFps) } ?: maximumFps)
        thermalAdaptationApplied = maximumFps < selected.fps || maximumBitrate < selected.bitRate
        return ThermalGuardPolicy.workloadPercent(status, thermalReductionOn)
    }

    /**
     * Experimental "Share app nearby" gate: a stream, call, relay or reconnect exists. The idle
     * always-reachable call endpoint (a [privateCall] with no phase) only advertises and listens, so
     * it does not block sharing; an incoming call gives it a phase and stops the share.
     */
    private fun liveSessionBlocksAppShare(): Boolean =
        streamArmed || sender != null || broadcaster != null || receiver != null || relay != null ||
            privateCallRelay != null || cameraEncoder != null || privateCallCameraEncoder != null ||
            (privateCall != null && privateCallPhase != null) || reconnectTarget != null

    private fun thermalVideoActive(): Boolean = streamArmed || sender != null || broadcaster != null ||
        receiver != null || relay != null || privateCallRelay != null || cameraEncoder != null ||
        privateCall != null || privateCallCameraEncoder != null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val action = MemoryThermalPolicy.trimAction(level)
        if (action == MemoryThermalPolicy.TrimAction.NONE || !MemoryThermalPolicy.trimReleaseActive()) return
        var released = 0
        // Expired suppressions are rebuilt for free; they are only dropped earlier than the next refresh.
        val nowMs = SystemClock.elapsedRealtime()
        val before = suppressedStreamRoutes.size
        suppressedStreamRoutes.entries.removeAll { it.value <= nowMs }
        capacitySuppressedRouteGenerations.keys.retainAll(suppressedStreamRoutes.keys)
        released += before - suppressedStreamRoutes.size
        // A pending reconnect still needs its candidates and failure counters: it counts as active.
        val active = thermalVideoActive() || reconnectTarget != null
        if (action == MemoryThermalPolicy.TrimAction.RELEASE_IDLE_DISCOVERY && !active) {
            released += viewerPeersLastSeen.size + nearbyStreamsLastSeen.size + privateCallPeersLastSeen.size
            viewerPeersLastSeen.clear()
            nearbyStreamsLastSeen.clear()
            privateCallPeersLastSeen.clear()
        }
        Log.i("BluetoothVideo", MemoryThermalPolicy.trimLine(level, action, active, released))
    }

    private fun ensureThermalReady(): Boolean {
        if (!ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) return true
        setStatus("Phone is critically hot. Let it cool before starting video.")
        return false
    }

    private fun updateThermalBadge() {
        if (!::thermalStatusBadge.isInitialized || !::batteryTemperatureBadge.isInitialized) return
        val (label, color) = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SHUTDOWN ->
                "Shutdown" to Color.rgb(183, 28, 28)
            thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY ->
                "Emergency" to Color.rgb(183, 28, 28)
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ->
                "Critical" to Color.rgb(198, 40, 40)
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Very hot" to Color.rgb(211, 47, 47)
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                "Hot" to Color.rgb(230, 107, 0)
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT ->
                "Warm" to Color.rgb(196, 139, 0)
            else -> "Normal" to Color.rgb(46, 160, 67)
        }
        val heatText = "Heat: $label"
        val batteryText = batteryTemperatureC()?.let { temperature ->
            "Battery: ${String.format(Locale.US, "%.1f", temperature)}°C"
        } ?: "Battery: --"
        thermalStatusBadge.text = heatText
        thermalStatusBadge.contentDescription = heatText
        thermalStatusBadge.backgroundTintList = ColorStateList.valueOf(color)
        batteryTemperatureBadge.text = batteryText
        batteryTemperatureBadge.contentDescription = batteryText
        updateVideoHealthNotice()
    }

    /** One short, actionable line on the video screens: heat reduction or low battery, else nothing. */
    private fun updateVideoHealthNotice() {
        val notice = findViewById<TextView>(R.id.videoHealthNotice) ?: return
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val text = videoHealthNotice(
            thermalReductionActive = automaticThermalReductionEnabled && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE,
            batteryPercent = level,
            charging = battery?.isCharging == true,
        )?.let(::getString)
        notice.text = text
        notice.visibility = if (text != null && appScreen in setOf(AppScreen.CAMERA, AppScreen.PLAYBACK)) View.VISIBLE else View.GONE
    }

    /** Watch: the lock explains encryption and verification of the stream source. */
    private fun showStreamInfo() {
        val source = activeStreamSourceNodeId?.toString()?.take(12)
        val name = source?.let { PeerLabels.forPeer(it, contactNames[it]).callerLine() } ?: "-"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_lock)
            .setTitle(R.string.stream_info_title)
            .setMessage(getString(R.string.stream_info_message, name))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun sampleThermalHeadroomIfDue() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastThermalHeadroomSampleMs < THERMAL_HEADROOM_SAMPLE_MS) return
        lastThermalHeadroomSampleMs = nowMs
        // The ADPF forecast (10 s ahead) is read only when the experiment is on; otherwise the current value.
        val forecastSeconds = if (thermalForecastOn) THERMAL_FORECAST_SECONDS else 0
        val headroom = powerManager.getThermalHeadroom(forecastSeconds)
        if (!headroom.isFinite()) {
            thermalForecastHeadroom = null
            return
        }
        Log.d("BluetoothVideo", "Thermal headroom ${String.format(Locale.US, "%.2f", headroom)}")
        if (!thermalForecastOn) {
            thermalForecastHeadroom = null
            return
        }
        val before = MemoryThermalPolicy.effectiveStatus(thermalStatus, thermalForecastHeadroom, true)
        thermalForecastHeadroom = headroom
        val after = MemoryThermalPolicy.effectiveStatus(thermalStatus, headroom, true)
        if (after != before && thermalVideoActive() && activeStreamSettings != null &&
            !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)
        ) {
            applyThermalAdaptation(thermalStatus, "forecast")
        }
    }

    private fun batteryTemperatureC(): Float? {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastBatteryTemperatureSampleMs < BATTERY_TEMPERATURE_SAMPLE_MS) {
            return cachedBatteryTemperatureC
        }
        lastBatteryTemperatureSampleMs = nowMs
        cachedBatteryTemperatureC = runCatching {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@runCatching null
            val tenthsCelsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenthsCelsius == Int.MIN_VALUE) null else tenthsCelsius / 10f
        }.getOrNull()
        return cachedBatteryTemperatureC
    }

    /**
     * DEBUG Bluetooth restart guard (DebugBluetoothRestartReceiver; main thread): the first session
     * that holds Bluetooth objects, or null when none does.
     */
    private fun activeBluetoothSession(): String? = when {
        privateCall != null -> "private_call"
        privateCallRelay != null -> "call_relay"
        broadcaster != null -> "broadcast"
        receiver != null -> "receiver"
        relay != null -> "relay"
        debugBenchmarkCoordinator != null -> "benchmark"
        else -> null
    }

    /**
     * DEBUG only. The launcher activity is exported, so the extra alone is not enough: it must carry
     * the one-shot token adb wrote into internal storage (EmergencyWipe.DEBUG_TOKEN_FILE). The panic
     * and confirmed wipes are reachable only from in-app UI.
     */
    private fun debugEmergencyWipeAuthorized(intent: Intent): Boolean =
        BuildConfig.DEBUG && EmergencyWipe.consumeDebugWipeToken(this, intent.getStringExtra(DEBUG_EMERGENCY_WIPE_EXTRA))

    /**
     * Emergency wipe step 4 (main thread): end every call, stream, relay and discovery this activity
     * owns, as onDestroy does, after a best-effort hang-up. The wipe then ends the process.
     */
    /** True when onCreate left early for the wipe-incomplete screen. */
    private var startupAborted = false

    private val emergencyWipeTerminator = WipeSessionTerminator {
        runCatching { privateCall?.hangUp() }
        thermalHandler.removeCallbacksAndMessages(null)
        sessionHandler.removeCallbacksAndMessages(null)
        runCatching { stopIdleCameraPreview() }
        runCatching { stopStreamPresence() }
        runCatching { stopNearbyStreamDiscovery() }
        runCatching { stopPrivateCallEndpoint() }
        runCatching { bleDiscovery?.close() }
        bleDiscovery = null
        runCatching { stopStreaming("Stopped.") }
        ActiveVideoSessionService.stop(this)
        finishAndRemoveTask()
    }

    /**
     * A CallStyle notification action (experimental, CallNotificationActions). Answer arrives as an
     * activity intent, Decline and Hang up as not-exported broadcasts; each must carry this process's
     * token and only acts in the matching call phase.
     */
    private fun onCallNotificationAction(intent: Intent) {
        val action = intent.getStringExtra(CallNotificationActions.EXTRA_ACTION)
        if (!CallUx.settings.callStyleNotifications ||
            !CallNotificationActions.isAuthentic(action, intent.getStringExtra(CallNotificationActions.EXTRA_TOKEN))
        ) {
            Log.w("BluetoothVideo", "Ignored a call notification action without a valid token")
            return
        }
        val command = CallNotificationActions.commandFor(action, privateCallPhase)
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_notification_action: action=$action | phase=${privateCallPhase?.name ?: "none"} | command=${command.name}",
        )
        when (command) {
            CallNotificationCommand.ACCEPT -> {
                if (!ensureThermalReady()) {
                    setCallStatus("Cannot accept: heat status is Critical or higher.")
                    return
                }
                stopPrivateCallRinging()
                runCatching { privateCall?.accept() }.onFailure { setCallStatus(it.message ?: "Could not accept") }
            }
            CallNotificationCommand.DECLINE -> {
                stopPrivateCallRinging()
                runCatching { privateCall?.decline() }.onFailure { setCallStatus(it.message ?: "Could not decline") }
            }
            CallNotificationCommand.CANCEL -> {
                stopPrivateCallRinging()
                privateCall?.cancel()
            }
            CallNotificationCommand.HANG_UP -> {
                stopPrivateCallRinging()
                privateCall?.hangUp()
            }
            CallNotificationCommand.IGNORE -> Unit
        }
    }

    private val callNotificationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            onCallNotificationAction(intent)
        }
    }
    private var callNotificationReceiverRegistered = false

    private fun registerCallNotificationReceiver() {
        if (!CallUx.settings.callStyleNotifications || callNotificationReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            callNotificationReceiver,
            android.content.IntentFilter(CallNotificationActions.BROADCAST_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        callNotificationReceiverRegistered = true
    }

    override fun onDestroy() {
        if (callNotificationReceiverRegistered) {
            runCatching { unregisterReceiver(callNotificationReceiver) }
            callNotificationReceiverRegistered = false
        }
        if (startupAborted) {
            // onCreate stopped before any view, service or radio existed.
            super.onDestroy()
            return
        }
        ActiveVideoSessionService.outcomeListener = null
        ActiveVideoSessionService.heldTypesListener = null
        sessionHandler.removeCallbacks(callReachabilityServiceChanged)
        ScanStartGate.deferralListener = null
        OfflineAppShareGate.liveSessionCheck = null
        EmergencyWipe.unregisterSessionTerminator(emergencyWipeTerminator)
        if (BuildConfig.DEBUG && BluetoothRestart.busyProbe === bluetoothRestartProbe) BluetoothRestart.busyProbe = null
        thermalHandler.removeCallbacksAndMessages(null)
        sessionHandler.removeCallbacksAndMessages(null)
        stopIdleCameraPreview()
        stopStreamPresence()
        stopNearbyStreamDiscovery()
        stopPrivateCallEndpoint()
        bleDiscovery?.close()
        bleDiscovery = null
        debugBenchmarkCoordinator?.close()
        debugBenchmarkCoordinator = null
        debugSessionTelemetry?.close()
        debugSessionTelemetry = null
        if (thermalListenerRegistered) {
            powerManager.removeThermalStatusListener(thermalStatusListener)
            thermalListenerRegistered = false
        }
        stopStreaming("Stopped.")
        privateCallCameraExecutor.shutdown()
        // A quick wipe still inside its Undo window was not undone: run it now rather than let
        // the activity teardown cancel it silently (it uses only the application context).
        pendingPanicWipe?.let { wipe ->
            sessionHandler.removeCallbacks(wipe)
            pendingPanicWipe = null
            Log.i("BluetoothVideo", "Emergency wipe: panic gesture runs at activity teardown")
            OnboardingActivity.reset(this)
            val context = applicationContext
            Thread { runCatching { SecurityUiBackend.current.wipe(context, panic = true, confirmationText = null) } }.start()
        }
        if (::callChromeAutoHide.isInitialized) callChromeAutoHide.release()
        super.onDestroy()
    }

    private data class SurfaceVideoFormat(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val scaleMode: VideoScaleMode,
    )

    private companion object {
        /** Key of the single call link in its rate controller. */
        const val PRIVATE_CALL_RATE_LINK = "private-call"
        /** The call frame-rate ladder never goes below this (faces stay readable). */
        const val MIN_CALL_FPS = 5
        /** One direction's share of a two-way call link: half, less the 15% rate-control headroom. */
        const val CALL_LINK_SHARE = 0.425
        /** Android's default visibility duration; a first contact needs about one 10 s inquiry. */
        const val CLASSIC_VISIBILITY_SECONDS = 120
        const val APP_PREFERENCES = "bluetooth_video_settings"
        /** DEBUG launch/new-intent string extra: the adb-written token; runs EmergencyWipe and ends the process. */
        const val DEBUG_EMERGENCY_WIPE_EXTRA = "test_emergency_wipe"
        const val PREF_AUTOMATIC_THERMAL_REDUCTION = "automatic_thermal_reduction"
        const val PREF_SHOW_DEVICE_HEALTH = "show_device_health"
        const val PREF_FORWARD_RECEIVED_STREAMS = "forward_received_streams"
        const val PREF_ENCODER_RATE_CONTROL = "encoder_rate_control"
        const val PREF_MANUAL_RATE_CONTROL = "manual_rate_control"
        const val PREF_ADVANCED_SETTINGS = "advanced_settings"
        const val PREF_EXPERIMENTAL_TEMPORAL_LAYERS = "experimental_temporal_layers"
        const val PREF_RELAY_CHILD_MEDIA_AGE = "relay_child_media_age"
        const val PREF_OUTGOING_TRANSPORT = "outgoing_transport"
        const val PREF_VIDEO_WIDTH = "video_width"
        const val PREF_VIDEO_HEIGHT = "video_height"
        const val PREF_VIDEO_FPS = "video_fps"
        const val PREF_VIDEO_BITRATE = "video_bitrate"
        const val PREF_SENDER_PREVIEW_SCALE_MODE = "sender_preview_scale_mode"
        const val PREF_RECEIVER_PLAYBACK_SCALE_MODE = "receiver_playback_scale_mode"
        const val PREF_RECEIVER_PLAYBACK_DELAY = "receiver_playback_delay"
        const val PREF_VIDEO_SCALE_MODE = "video_scale_mode"
        const val THERMAL_BADGE_REFRESH_MS = 5_000L
        const val THERMAL_HEADROOM_SAMPLE_MS = 10_000L
        const val THERMAL_FORECAST_SECONDS = 10
        const val BATTERY_TEMPERATURE_SAMPLE_MS = 30_000L
        const val CONTINUITY_REPORT_NS = 5_000_000_000L
        const val THERMAL_RECOVERY_DELAY_MS = 30_000L
        const val CONTINUOUS_SCAN_MS = 0L
        const val DISCOVERY_RESTART_MS = 500L
        const val GATT_CALL_RESTART_DELAY_MS = 1_500L
        const val PRIVATE_CALL_SELECTION_REFRESH_TIMEOUT_MS = 3_000L
        /** Experimental automatic sync: how often one peer's sightings may query the message store. */
        const val AUTO_SYNC_EVAL_INTERVAL_MS = 5_000L
        /** One process-wide thread for automatic-sync store reads, so activity recreation never adds threads. */
        private val autoSyncExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
        const val VIEWER_REQUEST_STALE_MS = 12_000L
        const val VIEWER_EXPIRY_CHECK_MS = 4_000L
        const val CACHED_ROUTE_MAX_AGE_MS = 10_000L
        const val NEARBY_EXPIRY_CHECK_MS = 1_000L
        const val SCAN_ERROR_RETRY_MS = 30_000L
        const val ROUTE_SCAN_ERROR_RETRY_MS = 2_000L
        const val PAUSED_HEARTBEAT_MS = 2_000L
        const val FAILED_ROUTE_SUPPRESSION_MS = 8_000L
        const val WITHDRAWN_ROUTE_SUPPRESSION_MS = 30_000L
        const val RECEIVER_NOTICE_MS = 3_000L
        const val WIFI_ADVICE_QUIET_MS = 10 * 60 * 1_000L
    }
}
