package com.example.bluetoothtest

/**
 * The seam between the Add contact screen ([VerifyContactActivity]) and the call endpoint that MainActivity owns, for BVMID2
 * (Naming v2, `bvmid2`, off). The screen never touches Bluetooth: it asks the host to keep this phone call-ready while the
 * screen is open (so a scanner can reach the code's role-0 ID), reads the ID currently on air to draw a code, and asks the
 * host to scan someone else's code. MainActivity registers the host in `onCreate` and clears it in `onDestroy`; with no host
 * (or the switch off, or rotating IDs off) [available] is false and the screen keeps the earlier BVMID1 flow.
 *
 * Reachability rule (review F10): when the user had "Allow calls" off, the screen makes the phone call-ready only for its own
 * lifetime ([QrCodeDisplay.screenOnlyReachable]) and the responder accepts nothing but a connection whose tag matches the
 * code; leaving the screen restores the previous state (it is recomputed from the preference, so a crash leaves nothing on).
 */
internal object MeetBridge {
    enum class Failure { NOT_READY, NOT_FOUND, COULD_NOT_CONNECT }

    interface ScanListener {
        /** The host is looking for the phone that shows the code (up to [SEARCH_MS]). */
        fun onSearching()

        /** The answerer proved it holds the code's key at both layers. The caller still has to ask the user to confirm. */
        fun onReached(peer: IdentityFingerprint, node: NodeId)

        fun onFailed(failure: Failure)
    }

    interface Host {
        /** The Meet screen opened: keep the call endpoint on air. */
        fun begin()

        /** The Meet screen left: restore the reachability the user chose. */
        fun end()

        /** The role-0 ID currently on air, or null while the endpoint is not advertising yet. */
        fun currentRdi(): ByteArray?

        /** Whether "Allow calls" is on, so the phone is reachable without this screen. */
        fun reachableWithoutScreen(): Boolean

        fun scan(payload: Bvmid2Payload, listener: ScanListener)

        fun cancelScan()
    }

    @Volatile var host: Host? = null

    /** True when the BVMID2 flow can run: switch on, rotating IDs on and an endpoint host registered. */
    fun available(): Boolean = NamingFeatures.bvmid2On() && RotatingDiscoveryFeature.enabled && host != null

    /** Search window for the code's advert and the overall limit of one scan (every address is tried within it). */
    const val SEARCH_MS = QrScanPolicy.SEARCH_MS
    const val SCAN_LIMIT_MS = 30_000L
}
