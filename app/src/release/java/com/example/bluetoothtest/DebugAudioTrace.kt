package com.example.bluetoothtest

import android.content.Context
import java.io.Closeable

class DebugAudioTrace private constructor() : Closeable {
    fun record(stage: String, ptsUs: Long, bytes: ByteArray, offset: Int = 0, count: Int = bytes.size,
        trackFrame: Long? = null, trackNs: Long? = null, trackUnderruns: Int? = null) = Unit
    override fun close() = Unit
    fun invalidate(reason: String) = Unit
    companion object {
        fun startIfRequested(context: Context?, session: String?, role: String): DebugAudioTrace? = null
    }
}
