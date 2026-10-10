package com.example.bluetoothtest

import android.content.Context
import java.io.Closeable
import java.io.File

class DebugMediaImpairment private constructor() : Closeable {
    fun deliver(flags: Int, ptsUs: Long, bytes: ByteArray, send: (Int, Long, ByteArray) -> Unit) = send(flags, ptsUs, bytes)
    fun cancel() = Unit
    override fun close() = Unit

    companion object {
        fun startIfRequested(context: Context, session: String?, file: File?): DebugMediaImpairment? = null
    }
}
