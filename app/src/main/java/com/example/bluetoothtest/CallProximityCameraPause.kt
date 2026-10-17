package com.example.bluetoothtest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Camera pause at the ear (Settings > Features "Pause video at the ear", promoted 2026-10-11, shipped
 * default [CallFeatureDefaults.EAR_CAMERA_PAUSE]; DEBUG `test_call_proximity_camera_pause`, benchmark
 * tunable `call_proximity_camera_pause`).
 *
 * The proximity screen-off lock (PrivateCallAudioSession, README) already turns the screen off
 * while call audio is on the earpiece and the phone is near. During a video call the camera kept
 * sending a picture of the user's ear/cheek, spending radio airtime and battery. On: while the
 * user's camera is on, audio is on the earpiece and the proximity sensor reports near, the
 * outgoing camera stops and the peer gets the ordinary Camera off signal; far again (or a route
 * change away from the earpiece) restarts it with Camera on. The user's own camera choice is never
 * changed. Pattern from Meshenger's proximity camera switch, re-implemented from the Android
 * SensorManager documentation.
 */
internal object ProximityCameraPausePolicy {
    /** Proximity sensors are often binary (0 or maximumRange); below 5 cm or the range counts as near. */
    const val NEAR_CM = 5f

    fun isNear(distanceCm: Float, maximumRangeCm: Float): Boolean =
        distanceCm < minOf(NEAR_CM, maximumRangeCm.takeIf { it > 0f } ?: NEAR_CM)

    fun shouldPause(enabled: Boolean, userCameraOn: Boolean, earpiece: Boolean, near: Boolean): Boolean =
        enabled && userCameraOn && earpiece && near
}

/**
 * Hysteresis for the proximity pause (device-family backlog V-01): a pause or resume is a camera
 * stop or restart, and a restart on some families is a session rebuild or even a device reopen
 * (CameraX CloseCameraDeviceOnCameraGraphCloseQuirk), so the sensor flapping at the ear must not
 * cause one per flap. Near has to hold for [nearConfirmMs] before the camera is paused, and two
 * toggles are at least [minToggleGapMs] apart (at most one reopen per 3 s). Only the proximity
 * reading is debounced: the user turning the camera off, or leaving the earpiece, clears the
 * pause at once. Experimental: applied only with `test_proximity_pause_hysteresis` ([CameraSwitches]).
 */
internal class ProximityPauseDebounce(
    private val nearConfirmMs: Long = NEAR_CONFIRM_MS,
    private val minToggleGapMs: Long = MIN_TOGGLE_GAP_MS,
) {
    /** [apply]: the new pause state to take now (null: keep); [recheckInMs]: look again after this delay. */
    data class Decision(val apply: Boolean?, val recheckInMs: Long?)

    private var wanted: Boolean? = null
    private var wantedSinceMs = 0L
    private var lastToggleMs = Long.MIN_VALUE

    fun decide(nowMs: Long, wantPause: Boolean, currentlyPaused: Boolean): Decision {
        if (wantPause == currentlyPaused) {
            wanted = null
            return Decision(null, null)
        }
        if (wanted != wantPause) {
            wanted = wantPause
            wantedSinceMs = nowMs
        }
        val confirmAt = wantedSinceMs + if (wantPause) nearConfirmMs else 0L
        val gapAt = if (lastToggleMs == Long.MIN_VALUE) Long.MIN_VALUE else lastToggleMs + minToggleGapMs
        val readyAt = maxOf(confirmAt, gapAt)
        if (nowMs >= readyAt) {
            wanted = null
            lastToggleMs = nowMs
            return Decision(wantPause, null)
        }
        return Decision(null, readyAt - nowMs)
    }

    /** The pause was cleared outside the proximity rule (camera off, route change): the next toggle is not delayed by it. */
    fun clearedExternally() {
        wanted = null
    }

    companion object {
        const val NEAR_CONFIRM_MS = 1_500L
        const val MIN_TOGGLE_GAP_MS = 3_000L
    }
}

/** Owns the proximity listener and reports pause changes on the main thread. */
internal class CallProximityCameraPause(
    context: Context,
    private val onPauseChanged: (Boolean) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var near = false
    private var userCameraOn = false
    private var earpiece = false
    var paused = false
        private set
    private val debounce = ProximityPauseDebounce()
    private val handler = Handler(Looper.getMainLooper())
    private val recheck = Runnable { evaluate() }

    /** False on phones without a proximity sensor: nothing is ever paused. */
    val available: Boolean get() = sensor != null

    fun start() {
        val sensor = sensor ?: return
        runCatching { sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        runCatching { sensors?.unregisterListener(this) }
        handler.removeCallbacks(recheck)
        near = false
        paused = false
        debounce.clearedExternally()
    }

    fun update(userCameraOn: Boolean, earpiece: Boolean) {
        this.userCameraOn = userCameraOn
        this.earpiece = earpiece
        evaluate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = sensor ?: return
        near = ProximityCameraPausePolicy.isNear(event.values.firstOrNull() ?: Float.MAX_VALUE, sensor.maximumRange)
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun evaluate() {
        handler.removeCallbacks(recheck)
        val eligible = userCameraOn && earpiece
        val wanted = ProximityCameraPausePolicy.shouldPause(true, userCameraOn, earpiece, near)
        val pause = if (!CameraSwitches.proximityPauseHysteresis) {
            // Shipped behaviour: pause and resume follow the sensor at once.
            wanted
        } else if (!eligible) {
            // Camera off or off the earpiece: not a proximity flap, clear at once.
            debounce.clearedExternally()
            wanted
        } else {
            val decision = debounce.decide(SystemClock.elapsedRealtime(), wanted, paused)
            decision.recheckInMs?.let { handler.postDelayed(recheck, it) }
            decision.apply ?: paused
        }
        if (pause == paused) return
        paused = pause
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_proximity_camera: paused=$pause | near=$near | earpiece=$earpiece | cameraOn=$userCameraOn",
        )
        onPauseChanged(pause)
    }
}
