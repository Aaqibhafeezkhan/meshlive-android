package com.example.bluetoothtest

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exports the same camera/codec choices used by Settings, not guessed model presets. */
@RunWith(AndroidJUnit4::class)
class DeviceBenchmarkCapabilitiesTest {
    @Test
    fun exportCompatibleSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capabilities = DeviceVideoCapabilities(context)
        val bitrates = (VideoSettings.MIN_BIT_RATE_KBPS..
            BluetoothTransportOption.entries.maxOf { it.maxVideoBitrateKbps }
            step VideoSettings.BIT_RATE_STEP_KBPS).toList()
        val encoders = JSONArray()
        VideoCodec.availableEncoders().forEach { codec ->
            val modes = JSONArray()
            bitrates.forEach { bitrate ->
                capabilities.modesFor(codec, bitrate * 1000, CameraLens.BACK).forEach { mode ->
                    modes.put(JSONObject()
                        .put("bitrate_kbps", bitrate)
                        .put("width", mode.resolution.width)
                        .put("height", mode.resolution.height)
                        .put("fps", JSONArray(mode.fpsOptions)))
                }
            }
            encoders.put(JSONObject()
                .put("name", codec.encoderName)
                .put("codec", if (codec.protocolId == StreamProtocol.CODEC_AV1) "av1" else "vp9")
                .put("hardware", codec.hardwareAccelerated)
                .put("surface_input", codec.usesSurfaceInput)
                .put("rate_controls", JSONArray(codec.availableRateControls().map { it.name.lowercase() }))
                .put("modes", modes))
        }
        val decoders = JSONArray(VideoCodec.availableDecoders().map {
            if (it.protocolId == StreamProtocol.CODEC_AV1) "av1" else "vp9"
        }.distinct())
        val transportLimits = JSONObject()
        BluetoothTransportOption.entries.forEach { transport ->
            val name = when (transport) {
                BluetoothTransportOption.CLASSIC_RFCOMM -> "classic"
                BluetoothTransportOption.BLE_L2CAP -> "ble"
                BluetoothTransportOption.BLE_GATT -> "gatt"
            }
            transportLimits.put(name, transport.maxVideoBitrateKbps)
        }
        val result = JSONObject()
            .put("schema_version", 1)
            .put("node_prefix", AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
                .take(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                .joinToString("") { "%02x".format(it) })
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("camera_lens", "back")
            .put("bitrates_kbps", JSONArray(bitrates))
            .put("bitrate_step_kbps", VideoSettings.BIT_RATE_STEP_KBPS)
            .put("minimum_bitrate_kbps", VideoSettings.MIN_BIT_RATE_KBPS)
            .put("transport_max_kbps", transportLimits)
            .put("playback", JSONArray(PlaybackDelayProfile.entries.map { it.name.lowercase() }))
            .put("encoders", encoders)
            .put("decoders", decoders)
        assertTrue("No usable video encoders", encoders.length() > 0)
        File(requireNotNull(context.getExternalFilesDir(null)), "benchmark-capabilities.json")
            .writeText(result.toString(2))
        println("BENCHMARK capabilities exported: ${Build.MODEL}, ${encoders.length()} encoders")
    }
}
