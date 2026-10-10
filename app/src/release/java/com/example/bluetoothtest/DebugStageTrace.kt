package com.example.bluetoothtest

import java.io.Closeable

class DebugStageTrace private constructor() : Closeable {
    fun record(stage: String, ptsUs: Long, bytes: Int = 0, atNs: Long = 0L, extra: Map<String, Any>? = null) = Unit
    override fun close() = Unit
    companion object {
        fun startIfRequested(session: String?, role: String): DebugStageTrace? = null
    }
}
