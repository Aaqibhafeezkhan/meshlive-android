package com.example.bluetoothtest

/** Short display of a colon-separated certificate fingerprint for the About screen (2026-10-17 redesign). */
internal object CertificateText {
    /** "C5:43:5D:9B … 0D:B3": the first four and last two bytes; short inputs come back unchanged. */
    fun short(fingerprint: String): String {
        val parts = fingerprint.split(':')
        if (parts.size <= 6) return fingerprint
        return parts.take(4).joinToString(":") + " … " + parts.takeLast(2).joinToString(":")
    }
}
