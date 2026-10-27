package com.example.bluetoothtest

/**
 * The stream receiver's wait for its parent's connection on a join (review B3 and B9, 2026-10-27).
 *
 * B3: the join waited a flat 15 s for the parent's link. A parent whose central had already connected and was in setup
 * (encryption, service discovery, MTU, subscription: about 1 s on a healthy pair, several on a busy relay) at the
 * deadline was abandoned just before it arrived. Once, when a central is connected but not yet admitted at the
 * deadline, the wait is extended by [setupExtensionMs].
 *
 * B9: a connection that failed before its hop handshake authenticated (a stranger, another phone's stale dial, noise)
 * ended the whole join. It is dropped and the wait goes on, within the same deadline.
 */
internal class ReceiverJoinWait(
    private val startedMs: Long,
    baseMs: Long = 15_000L,
    private val setupExtensionMs: Long = SETUP_EXTENSION_MS,
) {
    private var deadlineMs = startedMs + baseMs
    var extended = false
        private set

    fun remainingMs(nowMs: Long): Long = deadlineMs - nowMs

    /** At the deadline: extends once when a central connected after the wait began and is still in setup. */
    fun extendForLinkInSetup(setupCentralSinceMs: Long?, nowMs: Long): Boolean {
        if (extended || setupCentralSinceMs == null || setupCentralSinceMs < startedMs) return false
        extended = true
        deadlineMs = maxOf(deadlineMs, nowMs + setupExtensionMs)
        return true
    }

    /** After a connection failed: keep waiting only when it never authenticated and time is left. */
    fun keepWaitingAfter(authenticated: Boolean, nowMs: Long): Boolean = !authenticated && remainingMs(nowMs) > 0L

    companion object {
        const val SETUP_EXTENSION_MS = 6_000L
    }
}

/** Review B8: a failed GATT control write ends the viewer's parent link only when it was I/O and the parent went quiet. */
internal object GattControlFailure {
    const val QUIET_MS = 2_000L

    fun endsLink(ioError: Boolean, parentHeardRecently: Boolean): Boolean = ioError && !parentHeardRecently
}

/**
 * Review T10 (2026-10-27): what the GATT video server does when this phone closes an admitted connection itself. It only
 * cancelled the connection and relied on the DISCONNECTED callback to free the slot and advertise again; a stack that
 * never delivers it (Android 10/11 lost connection records) left the server admitting no one and not advertising.
 */
internal object GattServerLocalClose {
    data class Steps(val closeConnection: Boolean, val readvertise: Boolean)

    fun steps(activeAddress: String?, closedAddress: String, advertise: Boolean, serverClosed: Boolean, advertising: Boolean): Steps {
        val owns = activeAddress == closedAddress
        return Steps(
            closeConnection = owns,
            readvertise = owns && advertise && !serverClosed && !advertising,
        )
    }
}
