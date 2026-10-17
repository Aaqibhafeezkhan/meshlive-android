package com.example.bluetoothtest

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ActiveVideoSessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Active Bluetooth video",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val usesCamera = intent?.getBooleanExtra(EXTRA_USES_CAMERA, false) == true
        // A voice call (experimental) records audio without the camera.
        val usesMicrophone = usesCamera || intent?.getBooleanExtra(EXTRA_USES_MICROPHONE, false) == true
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
        // Audio-only live (Design AL): a listener runs under the media-playback type, a broadcaster under microphone.
        val audioMode = AudioLiveServiceMode.from(intent?.getStringExtra(EXTRA_AUDIO_LIVE))
        // Experimental CallStyle (CallNotificationActions): outgoing/active calls get Hang up.
        val ongoingCall = intent?.getBooleanExtra(EXTRA_CALL_STYLE, false) == true
        // Experimental relayed calls: "Helping a call nearby" carries a Stop action.
        val relayStopAction = intent?.getBooleanExtra(EXTRA_RELAY_STOP, false) == true
        // Q16 (2026-10-14): the peer's name is part of the full notification only; the lock-screen version stays name-free.
        val peerName = NotificationPrivacy.sessionPeerName(intent?.getStringExtra(EXTRA_PEER_NAME))
        val activityIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = if (audioMode == AudioLiveServiceMode.LISTEN) buildListenNotification(pendingIntent) else NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle("Bluetooth video is active")
            .setContentText(
                NotificationPrivacy.sessionContentText(
                    statusText ?: if (usesCamera) {
                        "Broadcasting camera and microphone over Bluetooth"
                    } else {
                        "Bluetooth stream connected"
                    },
                    peerName,
                    ongoingCall,
                ),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (ongoingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .let { builder ->
                if (!ongoingCall) return@let builder
                // The person is the peer's name when known (full notification only), else the status text.
                builder.setStyle(CallNotificationActions.ongoingStyle(this, NotificationPrivacy.ongoingPersonName(statusText, peerName)))
            }
            .let { builder ->
                if (relayStopAction) {
                    builder.addAction(0, getString(R.string.relay_stop_action), RelayedCallsNotification.stopIntent(this))
                } else {
                    builder
                }
            }
            .let { builder ->
                if (audioMode == AudioLiveServiceMode.BROADCAST) {
                    builder.addAction(0, getString(R.string.audio_live_notification_mute), AudioLiveNotification.muteIntent(this))
                        .addAction(0, getString(R.string.audio_live_notification_stop), AudioLiveNotification.stopIntent(this))
                } else {
                    builder
                }
            }
            .let { builder ->
                // Lock screen: "Bluetooth Video / Active" only (NotificationPrivacy) unless the user opted in to names there.
                NotificationPrivacy.apply(
                    builder,
                    this,
                    CHANNEL_ID,
                    if (audioMode == AudioLiveServiceMode.BROADCAST) NotificationKind.AUDIO_BROADCAST else NotificationKind.SESSION,
                    R.drawable.ic_stat_meshlive,
                    peerNamesOptIn = peerName != null && audioMode != AudioLiveServiceMode.BROADCAST &&
                        NotificationPrivacy.showPeerNames(this),
                )
            }
            .build()
        // Q37 (Android 10-11): start() decided whether the location type is wanted (app visible, Location on, FINE granted).
        val locationType = intent?.getBooleanExtra(EXTRA_LOCATION_TYPE, false) == true
        val serviceType = requestedTypes(
            usesCamera, usesMicrophone, mediaPlayback = audioMode == AudioLiveServiceMode.LISTEN, location = locationType,
        )
        val outcome = startForegroundWithFallback(notification, serviceType)
        startPending = false
        logStart(outcome)
        if (outcome.effectiveTypes != 0) {
            ProcessExitReport.markSession(this, true)
            // Experimental, default off (SessionWakeLock): tied to the foreground state, after startForeground succeeded.
            SessionWakeLock.onSessionStarted(
                this,
                ServiceTypePolicy.sessionKind(
                    usesCamera = usesCamera,
                    usesMicrophone = usesMicrophone,
                    ongoingCall = ongoingCall,
                    relayStopAction = relayStopAction,
                    audioLive = audioMode != AudioLiveServiceMode.NONE,
                    hasStatusText = statusText != null,
                ),
            )
        }
        if (stopRequested) {
            // stop() arrived before this start was delivered: Android requires startForeground first,
            // then the service may end (otherwise ForegroundServiceDidNotStartInTimeException).
            stopRequested = false
            SessionWakeLock.onSessionEnded()
            updateHeldTypes(0)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Android 12+ may refuse a foreground start from the background
     * (ForegroundServiceStartNotAllowedException), and Android 14+ refuses the camera/microphone
     * types outside their while-in-use state (SecurityException "... foreground only permission").
     * With registry entry bg-fgs-start-fallback (default on) only those two refusals are handled:
     * a refused camera/microphone type retries with connectedDevice so the Bluetooth session
     * survives; a refused retry stops the service (startForeground already cleared the
     * start-foreground deadline, AOSP ActiveServices). Every start reports requested vs effective
     * types (`BENCHMARK foreground_service`); any difference is a session failure for the scorer
     * and a visible notice for the user. Any other exception (manifest bugs) propagates.
     */
    private fun startForegroundWithFallback(notification: android.app.Notification, serviceType: Int): ForegroundServiceOutcome {
        val outcome = ForegroundStartSequence(
            start = { types -> ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types) },
            fallbackEnabled = { foregroundFallbackEnabled() },
            isRefusal = { error -> isForegroundRefusal(error) },
        ).run(serviceType)
        publish(outcome)
        if (outcome.effectiveTypes == 0) stopSelf()
        return outcome
    }

    /** `BENCHMARK fgs_start`: types actually held, startForegroundService-to-startForeground latency, qualifying permissions. */
    private fun logStart(outcome: ForegroundServiceOutcome) {
        val requestedAt = startRequestedAtMs
        startRequestedAtMs = 0L
        val latency = if (requestedAt > 0L) SystemClock.elapsedRealtime() - requestedAt else null
        val permissions = buildList {
            add("camera" to granted(Manifest.permission.CAMERA))
            add("microphone" to granted(Manifest.permission.RECORD_AUDIO))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add("btConnect" to granted(Manifest.permission.BLUETOOTH_CONNECT))
                add("btScan" to granted(Manifest.permission.BLUETOOTH_SCAN))
                add("btAdvertise" to granted(Manifest.permission.BLUETOOTH_ADVERTISE))
            } else {
                add("fineLocation" to granted(Manifest.permission.ACCESS_FINE_LOCATION))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add("notifications" to granted(Manifest.permission.POST_NOTIFICATIONS))
        }
        logLine(ServiceTypePolicy.startLine(outcome.effectiveTypes, latency, permissions))
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The listener's notification: framework media style bound to the framework media session, actions Mute
     * and Stop, and a lock-screen version with neutral text only (a new notification kind; no name, no title).
     */
    private fun buildListenNotification(contentIntent: PendingIntent): android.app.Notification {
        val publicVersion = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(NotificationPrivacy.PUBLIC_TITLE)
            .setContentText(NotificationPrivacy.publicText(NotificationKind.AUDIO_LISTEN))
            .build()
        val style = android.app.Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        AudioLiveMediaSession.token?.let(style::setMediaSession)
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(getString(R.string.audio_live_notification_listening))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(android.app.Notification.CATEGORY_TRANSPORT)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(
                android.app.Notification.Action.Builder(
                    null, getString(R.string.audio_live_notification_mute), AudioLiveNotification.muteIntent(this),
                ).build(),
            )
            .addAction(
                android.app.Notification.Action.Builder(
                    null, getString(R.string.audio_live_notification_stop), AudioLiveNotification.stopIntent(this),
                ).build(),
            )
            .setStyle(style)
            .build()
    }

    /**
     * Android 15 starts a timeout for dataSync/mediaProcessing/shortService types (and API 34 for shortService). No type
     * this service declares has one today (CP-03); the stub only guards a future type change: log it and end cleanly,
     * otherwise Android throws RemoteServiceException a few seconds later.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        logLine(ServiceTypePolicy.timeoutLine(startId, fgsType))
        endAfterTimeout()
    }

    override fun onTimeout(startId: Int) {
        logLine(ServiceTypePolicy.timeoutLine(startId, null))
        endAfterTimeout()
    }

    private fun endAfterTimeout() {
        SessionWakeLock.onSessionEnded()
        updateHeldTypes(0)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The task was swiped away. The activity that owned the app's scanners is gone, so stop them together (S-09: Android 10
     * keeps a scanner registered for a dead process). Batch S supplies `ScanRegistry.stopAll()` through [ServiceCleanup].
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val stopped = ServiceCleanup.stopScans(ServiceSwitches.stopScansOnExit, ServiceCleanup.Reason.TASK_REMOVED, appVisible = false)
        logLine("service_exit: event=task_removed | scans_stopped=$stopped")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SessionWakeLock.onSessionEnded()
        ProcessExitReport.markSession(this, false)
        val stopped = ServiceCleanup.stopScans(ServiceSwitches.stopScansOnExit, ServiceCleanup.Reason.SERVICE_DESTROYED, appVisible())
        if (stopped) logLine("service_exit: event=destroyed | scans_stopped=true")
        updateHeldTypes(0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "active_bluetooth_video"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_USES_CAMERA = "uses_camera"
        private const val EXTRA_STATUS_TEXT = "status_text"
        private const val EXTRA_USES_MICROPHONE = "uses_microphone"
        private const val EXTRA_CALL_STYLE = "call_style"
        private const val EXTRA_RELAY_STOP = "relay_stop_action"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_AUDIO_LIVE = "audio_live_mode"
        private const val EXTRA_LOCATION_TYPE = "location_type"

        /** SystemClock.elapsedRealtime() when start() asked Android for the service; 0 when none is pending. */
        @Volatile private var startRequestedAtMs = 0L

        /**
         * Asks Android to run the session service. Returns the immediate answer: [ForegroundStartRequest.refused]
         * is set when Android refused the start itself (then no service runs). The types the service
         * finally holds arrive through [outcomeListener] and the `BENCHMARK foreground_service` line.
         */
        fun start(
            context: Context,
            usesCamera: Boolean,
            statusText: String? = null,
            usesMicrophone: Boolean = usesCamera,
            ongoingCallStyle: Boolean = false,
            relayStopAction: Boolean = false,
            audioLive: AudioLiveServiceMode = AudioLiveServiceMode.NONE,
            peerName: String? = null,
        ): ForegroundStartRequest {
            val includeLocation = locationTypeWanted(context)
            val requested = requestedTypes(
                usesCamera, usesMicrophone, mediaPlayback = audioLive == AudioLiveServiceMode.LISTEN, location = includeLocation,
            )
            return try {
                stopRequested = false
                startPending = true
                startRequestedAtMs = SystemClock.elapsedRealtime()
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ActiveVideoSessionService::class.java)
                        .putExtra(EXTRA_USES_CAMERA, usesCamera)
                        .putExtra(EXTRA_USES_MICROPHONE, usesMicrophone)
                        .putExtra(EXTRA_STATUS_TEXT, statusText)
                        .putExtra(EXTRA_CALL_STYLE, ongoingCallStyle)
                        .putExtra(EXTRA_RELAY_STOP, relayStopAction)
                        .putExtra(EXTRA_PEER_NAME, peerName)
                        .putExtra(EXTRA_AUDIO_LIVE, audioLive.name)
                        .putExtra(EXTRA_LOCATION_TYPE, includeLocation),
                )
                ForegroundStartRequest(requested, null)
            } catch (error: RuntimeException) {
                if (!foregroundFallbackEnabled() || !isForegroundRefusal(error)) throw error
                // ForegroundServiceStartNotAllowedException: no service was created, so nothing must stop.
                startPending = false
                val reason = "${error.javaClass.simpleName}: ${error.message}"
                publish(ForegroundServiceOutcome(requested, 0, reason))
                ForegroundStartRequest(requested, reason)
            }
        }

        internal fun requestedTypes(
            usesCamera: Boolean,
            usesMicrophone: Boolean,
            mediaPlayback: Boolean = false,
            location: Boolean = false,
        ): Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or when {
                usesCamera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                usesMicrophone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            } or (if (mediaPlayback) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) or
                (if (location) ServiceTypePolicy.LOCATION else 0)

        /**
         * Q37: whether this start asks for the location type (API 29-30 only; see [ServiceTypePolicy.locationTypeWanted]).
         * Logs the inputs and the AppOps FINE_LOCATION mode once per start so a field report shows why it was or was not asked.
         */
        private fun locationTypeWanted(context: Context): Boolean {
            val sdk = Build.VERSION.SDK_INT
            if (sdk !in 29..30) return false
            val policyActive = ServiceFamilyPolicy.locationTypeActive()
            val locationOn = runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true }.getOrDefault(false)
            val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val visible = appVisible()
            val held = ServiceTypePolicy.hasLocation(heldTypes)
            val wanted = ServiceTypePolicy.locationTypeWanted(sdk, policyActive, locationOn, fine, visible, held)
            val appOp = runCatching {
                val ops = context.getSystemService(AppOpsManager::class.java)
                when (ops?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), context.packageName)) {
                    AppOpsManager.MODE_ALLOWED -> "allowed"
                    AppOpsManager.MODE_FOREGROUND -> "foreground"
                    AppOpsManager.MODE_IGNORED -> "ignored"
                    AppOpsManager.MODE_ERRORED -> "errored"
                    null -> "unknown"
                    else -> "other"
                }
            }.getOrDefault("unknown")
            logLine(
                "fgs_location: wanted=$wanted | sdk=$sdk | policy=$policyActive | location_on=$locationOn | fine=$fine | " +
                    "visible=$visible | held=$held | appop_fine=$appOp",
            )
            return wanted
        }

        /** True while an activity of this app is in the foreground (process importance FOREGROUND), not merely a running service. */
        internal fun appVisible(): Boolean = runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            ServiceTypePolicy.isForegroundImportance(info.importance)
        }.getOrDefault(false)

        private fun logLine(line: String) {
            try { android.util.Log.i("BluetoothVideo", "BENCHMARK $line") } catch (_: RuntimeException) {} // Release: stripped (proguard-release-logs.pro).
        }

        /** Set by the visible activity to show [ForegroundServiceOutcome.userNotice]; null when none. */
        @Volatile var outcomeListener: ((ForegroundServiceOutcome) -> Unit)? = null

        @Volatile var lastOutcome: ForegroundServiceOutcome? = null
            private set

        /**
         * Foreground-service types the running service holds now (0 when stopped or refused). Call
         * reachability (CallReachability) keeps the endpoint in the background only while this
         * includes connectedDevice.
         */
        @Volatile var heldTypes: Int = 0
            private set

        fun holdsConnectedDevice(): Boolean = (heldTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0

        /**
         * Set by the activity while call reachability is on: [heldTypes] changed, including a
         * refusal in onStartCommand that arrives after start() returned, or the service ending.
         * Invoked on the caller's thread; the activity posts its check (never re-enters a stop).
         */
        @Volatile var heldTypesListener: (() -> Unit)? = null

        internal fun updateHeldTypes(types: Int) {
            if (heldTypes == types) return
            heldTypes = types
            try { heldTypesListener?.invoke() } catch (_: RuntimeException) {}
        }

        private fun publish(outcome: ForegroundServiceOutcome) {
            lastOutcome = outcome
            updateHeldTypes(outcome.effectiveTypes)
            try { android.util.Log.i("BluetoothVideo", outcome.benchmarkLine()) } catch (_: RuntimeException) {} // Release: stripped (proguard-release-logs.pro).
            if (outcome.mismatch) {
                BackgroundReliability.recordForegroundServiceFailure(outcome.summary())
                runCatching { outcomeListener?.invoke(outcome) }
            }
        }

        private fun foregroundFallbackEnabled(): Boolean =
            runCatching { DeviceFamilyPolicy.current().has<ForegroundServiceStartFallback>() }.getOrDefault(false)

        internal fun isForegroundRefusal(error: Throwable): Boolean = isForegroundServiceRefusal(error)

        /**
         * A start that Android has accepted but not yet delivered to onStartCommand. Stopping the
         * service in that window makes Android crash the app ("Bringing down service while still
         * waiting for start foreground", seen on a fresh launch when reachability started and stopped
         * the service within ~40 ms), so stop() then defers to onStartCommand. Main thread only.
         */
        @Volatile private var startPending = false
        @Volatile private var stopRequested = false

        fun stop(context: Context) {
            updateHeldTypes(0)
            if (startPending) {
                stopRequested = true
                return
            }
            context.stopService(Intent(context, ActiveVideoSessionService::class.java))
        }
    }
}
