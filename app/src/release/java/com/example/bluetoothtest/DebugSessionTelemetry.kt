package com.example.bluetoothtest

import android.content.Context
import android.content.Intent
import java.io.Closeable

class DebugSessionTelemetry private constructor() : Closeable {
    override fun close() = Unit

    companion object {
        fun startIfRequested(context: Context, intent: Intent, role: String): DebugSessionTelemetry? = null
    }
}
