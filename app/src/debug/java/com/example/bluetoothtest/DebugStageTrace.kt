package com.example.bluetoothtest

import android.util.Log
import org.json.JSONObject
import java.io.Closeable

/** Bounded opt-in stage logging; scope/sequence/final distinguish incomplete or recycled traces. */
class DebugStageTrace private constructor(private val session: String, private val role: String) : Closeable {
    private val scope = System.nanoTime().toString()
    private var sequence = 0L
    private var closed = false

    @Synchronized
    /**
     * [extra] adds diagnostic fields to the row (for example encode_output's parsed VP9 q_idx and
     * frame_type); the scorer keeps unknown fields and never needs them.
     */
    fun record(stage: String, ptsUs: Long, bytes: Int = 0, atNs: Long = System.nanoTime(), extra: Map<String, Any>? = null) {
        if (closed) return
        emit(stage, ptsUs, bytes, atNs, false, extra)
    }

    private fun emit(stage: String, ptsUs: Long, bytes: Int, atNs: Long, final: Boolean, extra: Map<String, Any>? = null) {
        val row = JSONObject()
            .put("session", session).put("scope", scope).put("role", role)
            .put("direction", if (role == "video_encoder" || role == "broadcast_transport" ||
                stage in setOf("send_enqueue", "write_begin", "write_end")) "outgoing" else "incoming")
            .put("pid", android.os.Process.myPid()).put("clock", "System.nanoTime")
            .put("sequence", ++sequence).put("stage", stage).put("pts_us", ptsUs)
            .put("at_ns", atNs).put("bytes", bytes).put("final", final)
        extra?.forEach { (key, value) -> if (!row.has(key)) row.put(key, value) }
        Log.i("BluetoothVideo", "BENCHMARK stage v1 " + row.toString())
    }

    @Synchronized override fun close() {
        if (closed) return
        emit("final", 0, 0, System.nanoTime(), true)
        closed = true
    }

    companion object {
        fun startIfRequested(session: String?, role: String): DebugStageTrace? = session?.takeIf(String::isNotBlank)
            ?.let { DebugStageTrace(it, role) }
    }
}
