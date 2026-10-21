package com.example.bluetoothtest

/** A saved identity or past handshake cannot authenticate the source of the current stream. */
internal object StreamModerationPolicy {
    fun canModerateIdentity(sourceAuthenticated: Boolean, fingerprintKnown: Boolean): Boolean =
        sourceAuthenticated && fingerprintKnown
}
