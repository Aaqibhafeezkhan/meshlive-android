package com.example.bluetoothtest

/**
 * Diagnostic log lines that identify the device (build fingerprint, codec component names, policy state) exist only in
 * DEBUG builds. The line is built inside the lambda, so a release build neither formats nor writes it.
 */
internal object ReleaseLogGate {
    fun debugOnly(debug: Boolean, line: () -> String): String? = if (debug) line() else null
}
