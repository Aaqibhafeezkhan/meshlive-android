package com.example.bluetoothtest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    /** False on phones without a proximity sensor: nothing is ever paused. */
    val available: Boolean get() = sensor != null

    fun start() {
        val sensor = sensor ?: return
        runCatching { sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        runCatching { sensors?.unregisterListener(this) }
        near = false
        paused = false
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
        val pause = ProximityCameraPausePolicy.shouldPause(true, userCameraOn, earpiece, near)
        if (pause == paused) return
        paused = pause
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_proximity_camera: paused=$pause | near=$near | earpiece=$earpiece | cameraOn=$userCameraOn",
        )
        onPauseChanged(pause)
    }
}
