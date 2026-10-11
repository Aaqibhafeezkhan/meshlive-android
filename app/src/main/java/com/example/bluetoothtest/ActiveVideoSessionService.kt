package com.example.bluetoothtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
        // Experimental CallStyle (CallNotificationActions): outgoing/active calls get Hang up.
        val ongoingCall = intent?.getBooleanExtra(EXTRA_CALL_STYLE, false) == true
        // Experimental relayed calls: "Helping a call nearby" carries a Stop action.
        val relayStopAction = intent?.getBooleanExtra(EXTRA_RELAY_STOP, false) == true
        val activityIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle("Bluetooth video is active")
            .setContentText(
                statusText ?: if (usesCamera) {
                    "Broadcasting camera and microphone over Bluetooth"
                } else {
                    "Bluetooth stream connected"
                },
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (ongoingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .let { builder ->
                if (!ongoingCall) return@let builder
                // Never the peer's name here (NotificationKind.SESSION): the status text is the person.
                builder.setStyle(CallNotificationActions.ongoingStyle(this, statusText ?: CallNotificationActions.NEUTRAL_PERSON))
            }
            .let { builder ->
                if (relayStopAction) {
                    builder.addAction(0, getString(R.string.relay_stop_action), RelayedCallsNotification.stopIntent(this))
                } else {
                    builder
                }
            }
            .let { builder ->
                // Lock screen: "Bluetooth Video / Active" only (NotificationPrivacy); never peer names.
                NotificationPrivacy.apply(
                    builder,
                    this,
                    CHANNEL_ID,
                    NotificationKind.SESSION,
                    R.drawable.ic_stat_meshlive,
                    peerNamesOptIn = false,
                )
            }
            .build()
        val serviceType = requestedTypes(usesCamera, usesMicrophone)
        startForegroundWithFallback(notification, serviceType)
        startPending = false
        if (stopRequested) {
            // stop() arrived before this start was delivered: Android requires startForeground first,
            // then the service may end (otherwise ForegroundServiceDidNotStartInTimeException).
            stopRequested = false
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
    private fun startForegroundWithFallback(notification: android.app.Notification, serviceType: Int) {
        val refusal = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            null
        } catch (error: RuntimeException) {
            if (!foregroundFallbackEnabled() || !isForegroundRefusal(error)) throw error
            "${error.javaClass.simpleName}: ${error.message}"
        }
        if (refusal == null) {
            publish(ForegroundServiceOutcome(serviceType, serviceType, null))
            return
        }
        val fallbackType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (serviceType == fallbackType) {
            publish(ForegroundServiceOutcome(serviceType, 0, refusal))
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallbackType)
            publish(ForegroundServiceOutcome(serviceType, fallbackType, refusal))
        } catch (error: RuntimeException) {
            if (!isForegroundRefusal(error)) throw error
            publish(ForegroundServiceOutcome(serviceType, 0, "$refusal; fallback ${error.javaClass.simpleName}: ${error.message}"))
            stopSelf()
        }
    }

    override fun onDestroy() {
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
        ): ForegroundStartRequest {
            val requested = requestedTypes(usesCamera, usesMicrophone)
            return try {
                stopRequested = false
                startPending = true
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ActiveVideoSessionService::class.java)
                        .putExtra(EXTRA_USES_CAMERA, usesCamera)
                        .putExtra(EXTRA_USES_MICROPHONE, usesMicrophone)
                        .putExtra(EXTRA_STATUS_TEXT, statusText)
                        .putExtra(EXTRA_CALL_STYLE, ongoingCallStyle)
                        .putExtra(EXTRA_RELAY_STOP, relayStopAction),
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

        internal fun requestedTypes(usesCamera: Boolean, usesMicrophone: Boolean): Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or when {
                usesCamera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                usesMicrophone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
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
