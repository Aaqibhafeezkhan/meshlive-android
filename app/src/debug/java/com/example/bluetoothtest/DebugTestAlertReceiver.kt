package com.example.bluetoothtest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import java.nio.ByteBuffer

class DebugTestAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(EXTRA_CUE) == CUE_NATIVE_PROBE) {
            val pending = goAsync()
            Thread {
                val result = runCatching {
                    verifyNativeYuvCopy()
                    NativeAomEncoder(
                        width = 320,
                        height = 180,
                        fps = 15,
                        bitrate = 300_000,
                        cpuUsed = 8,
                        threads = 2,
                        variableBitrate = false,
                        dropFrames = true,
                    ).use { encoder ->
                        val input = ByteBuffer.allocateDirect(320 * 180 * 3 / 2)
                        repeat(320 * 180) { input.put(it, 96.toByte()) }
                        repeat(320 * 180 / 2) { input.put(320 * 180 + it, 128.toByte()) }
                        encoder.encode(input, 0, true)
                    }
                }
                val message = result.fold(
                    onSuccess = { packets ->
                        "Native libaom ${NativeAomBridge.nativeVersion()} OK: " +
                            "${packets.size} packet(s), ${packets.sumOf { it.bytes.size }} bytes"
                    },
                    onFailure = { error ->
                        "Native libaom FAILED: ${error.message ?: error.javaClass.simpleName}"
                    },
                )
                Log.i("BluetoothVideo", message)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                pending.finish()
            }.start()
            return
        }
        val start = intent.getStringExtra(EXTRA_CUE) != CUE_STOP
        val message = if (start) "START MOTION NOW" else "STOP MOTION"
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        tone.startTone(
            if (start) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_NACK,
            if (start) START_TONE_MS else STOP_TONE_MS,
        )
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(if (start) START_TONE_MS.toLong() else STOP_TONE_MS.toLong())
            } finally {
                tone.release()
                pending.finish()
            }
        }.start()

        val vibration = if (start) {
            VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 500), -1)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 700), -1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(vibration)
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(vibration)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        Log.i("BluetoothVideo", "Debug motion alert: $message")
    }

    private fun verifyNativeYuvCopy() {
        val y = ByteBuffer.allocateDirect(16).apply {
            repeat(16) { put(it, it.toByte()) }
        }
        val u = ByteBuffer.allocateDirect(8).apply {
            intArrayOf(20, 21, 22, 23).forEachIndexed { index, value ->
                put((index / 2) * 4 + (index % 2) * 2, value.toByte())
            }
        }
        val v = ByteBuffer.allocateDirect(8).apply {
            intArrayOf(30, 31, 32, 33).forEachIndexed { index, value ->
                put((index / 2) * 4 + (index % 2) * 2, value.toByte())
            }
        }
        val output = ByteBuffer.allocateDirect(24)
        check(
            NativeAomBridge.nativeCopyYuv420ToI420(
                y, 0, y.capacity(), 4, 1,
                u, 0, u.capacity(), 4, 2,
                v, 0, v.capacity(), 4, 2,
                4, 4, output, 4, 4,
            ),
        )
        val actual = ByteArray(24) { index -> output.get(index) }
        val expected = ByteArray(24) { index ->
            when (index) {
                in 0..15 -> index.toByte()
                in 16..19 -> (20 + index - 16).toByte()
                else -> (30 + index - 20).toByte()
            }
        }
        check(actual.contentEquals(expected)) { "Native YUV conversion mismatch" }
    }

    private companion object {
        const val EXTRA_CUE = "cue"
        const val CUE_STOP = "stop"
        const val CUE_NATIVE_PROBE = "native_probe"
        const val START_TONE_MS = 1_800
        const val STOP_TONE_MS = 1_000
    }
}
