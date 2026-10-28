package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * DEBUG-only capture of the shared Design HC test vectors (DECISIONS "Design HC", Implementation work split: the Android
 * lane captures short HEVC and H.264 access-unit sequences from a phone's hardware encoder; the Windows and Apple lanes
 * place them in their fixtures).
 *
 * Launch extra `test_hc_vectors=true` (DEBUG builds only): every HC encoder of that launch writes, under the app's
 * external files directory `hc-vectors/<codec>_<encoder>_<W>x<H>_<epochMs>/`:
 * - `raw-NNN.bin`: each MediaCodec output buffer as the encoder produced it (codec-config buffers included);
 * - `au-NNN.bin`: each `BTV3` payload the packetiser produced (what goes on the wire);
 * - `manifest.json`: codec, encoder, profile/level, session, rate mode, the input kind (`raw` when the declared generated
 *   raw input drove the encoder, else `camera`, which must never be published) and per-file SHA-256, key flag and PTS.
 * At most [MAX_ACCESS_UNITS] wire access units (two keyframes are forced: the start and one request after
 * [SECOND_KEY_AFTER] frames), then the capture closes itself. Root pulls the directory with adb; nothing is uploaded.
 */
internal class HcTestVectorCapture private constructor(
    private val directory: File,
    private val manifest: JSONObject,
) {
    private val files = JSONArray()
    private var rawIndex = 0
    private var auIndex = 0
    @Volatile var finished = false
        private set

    /** One encoder output buffer before packetising. */
    @Synchronized
    fun onEncoderBuffer(bytes: ByteArray, codecConfig: Boolean, encoderKeyFlag: Boolean, presentationTimeUs: Long) {
        if (finished || rawIndex >= MAX_RAW_BUFFERS) return
        val name = "raw-%03d.bin".format(rawIndex++)
        write(name, bytes, JSONObject().put("kind", "encoder_buffer").put("codecConfig", codecConfig)
            .put("encoderKeyFlag", encoderKeyFlag).put("ptsUs", presentationTimeUs))
    }

    /** One wire access unit. Returns true when the encoder should request the second keyframe now. */
    @Synchronized
    fun onAccessUnit(payload: ByteArray, keyFrame: Boolean, presentationTimeUs: Long, codedSize: Pair<Int, Int>?): Boolean {
        if (finished) return false
        val name = "au-%03d.bin".format(auIndex++)
        write(name, payload, JSONObject().put("kind", "access_unit").put("keyFrame", keyFrame).put("ptsUs", presentationTimeUs)
            .apply { codedSize?.let { put("codedWidth", it.first).put("codedHeight", it.second) } })
        if (auIndex >= MAX_ACCESS_UNITS) finish("complete")
        return auIndex == SECOND_KEY_AFTER
    }

    @Synchronized
    fun finish(outcome: String) {
        if (finished) return
        finished = true
        manifest.put("outcome", outcome).put("files", files)
        runCatching { File(directory, "manifest.json").writeText(manifest.toString(2)) }
        Log.i("BluetoothVideo", "BENCHMARK hc_vectors: dir=${directory.absolutePath} | outcome=$outcome | aus=$auIndex | raw=$rawIndex")
    }

    private fun write(name: String, bytes: ByteArray, entry: JSONObject) {
        runCatching {
            File(directory, name).writeBytes(bytes)
            files.put(entry.put("file", name).put("bytes", bytes.size).put("sha256", sha256(bytes)))
        }.onFailure { finish("write_failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        const val EXTRA = "test_hc_vectors"
        const val MAX_ACCESS_UNITS = 30
        const val MAX_RAW_BUFFERS = 40
        const val SECOND_KEY_AFTER = 15

        /** Set from the launch (DEBUG only). */
        @Volatile var requested: Boolean = false

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A capture for one HC encoder when this DEBUG launch asked for one, else null. */
        fun startIfRequested(
            context: Context,
            codec: VideoCodec,
            settings: VideoSettings,
            bitrateMode: Int?,
            rawInput: Boolean,
        ): HcTestVectorCapture? {
            if (!BuildConfig.DEBUG || !requested || !codec.isHcCodec) return null
            val safeEncoder = codec.encoderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val root = context.getExternalFilesDir(null) ?: return null
            val directory = File(
                root,
                "hc-vectors/${VideoCodecMap.label(codec.protocolId)}_${safeEncoder}_" +
                    "${settings.resolution.width}x${settings.resolution.height}_${System.currentTimeMillis()}",
            )
            if (!directory.mkdirs()) return null
            val manifest = JSONObject()
                .put("format", "meshlive-hc-vectors-1")
                .put("codec", VideoCodecMap.label(codec.protocolId))
                .put("codecId", codec.protocolId)
                .put("encoder", codec.encoderName)
                .put("hardware", codec.hardwareAccelerated)
                .put("profile", codec.profile ?: JSONObject.NULL)
                .put("level", codec.level ?: JSONObject.NULL)
                .put("width", settings.resolution.width)
                .put("height", settings.resolution.height)
                .put("fps", settings.fps)
                .put("bitrateBps", settings.bitRate)
                .put("bitrateMode", HcBitrateModes.label(bitrateMode))
                .put("input", if (rawInput) "raw" else "camera")
                .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("sdk", android.os.Build.VERSION.SDK_INT)
            return HcTestVectorCapture(directory, manifest)
        }
    }
}
