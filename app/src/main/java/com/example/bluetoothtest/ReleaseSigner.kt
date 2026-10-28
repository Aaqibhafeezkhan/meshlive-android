package com.example.bluetoothtest

import java.security.MessageDigest

/**
 * The signing certificate the project's release APKs carry, pinned in the app (updater). The value
 * is the one constant [AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256], which USER_GUIDE "Verifying a
 * release" and `tools/make_release.py` (`EXPECTED_CERT_SHA256`) must equal (tests of both sides
 * check it). A certificate rotation adds the next certificate here in a release signed by the old
 * key, before the old key stops signing: only then can an installed copy still verify the new APK.
 */
internal object ReleaseSigner {
    /** Colon-separated uppercase SHA-256 of each accepted release certificate. */
    val PINNED: Set<String> = linkedSetOf(AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256.uppercase())

    /** `AA:BB:..` (apksigner's format) of [bytes], for example a DER certificate. */
    fun sha256Colon(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Normalises a SHA-256 written with or without colons, in either case, to the colon-separated
     * uppercase form; null when it is not exactly 64 hex digits.
     */
    fun normalizeColon(value: String): String? {
        val digits = value.filter { it != ':' && !it.isWhitespace() }
        if (digits.length != 64 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return digits.uppercase().chunked(2).joinToString(":")
    }

    /** 64 lowercase hex digits (the `sha256sum` form), or null when [value] is not a SHA-256. */
    fun normalizeHex(value: String): String? =
        normalizeColon(value)?.replace(":", "")?.lowercase()

    /** True when [signers] is exactly one certificate and it is pinned. */
    fun isPinnedSingleSigner(signers: List<String>): Boolean =
        signers.size == 1 && normalizeColon(signers[0])?.let { it in PINNED } == true
}
