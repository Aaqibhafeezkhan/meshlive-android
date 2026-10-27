package com.example.bluetoothtest

/**
 * F11, weak-pair reach (2026-10-19). Measured on build ef96bbf0 (ql-ef96bbf0-20261019 a15s-xiaomi):
 * - The A15s (MT6765, Android 10) advertised the code's ID the whole time, on one legacy set (18/28 + 26/31 bytes), in the
 *   balanced mode after its 10 s low-latency burst (AdvertisingSetParameters INTERVAL_MEDIUM = 400 x 0.625 ms = 250 ms,
 *   TX_POWER_MEDIUM).
 * - The Redmi 12 (MT6768, HyperOS 15) heard that advert twice in its normal scans and zero times in the 10 s Meet scan.
 *   Its scans ran at LOW_LATENCY (100/100 ms) but on LE 1M and Coded in turn (`SetScanParameters scan_phy 255 phy 5`:
 *   ScanSettings defaults to PHY_LE_ALL_SUPPORTED), and it heard 16-21 adverts per 10 s across 4-5 phones.
 * - A Windows laptop in the same room heard the phones at 0.85 reports per address per second (busiest 3.2/s), below the
 *   4/s a 250 ms interval would give: the advertising events of a phone that scans continuously, holds connections or
 *   runs several sets share one radio.
 *
 * Answers (each with a DEBUG control arm, [DebugTunables.weakPairReach] / [DebugTunables.scanLe1mOnly]):
 * 1. Scans ask for LE 1M only: legacy adverts are only ever on LE 1M (BT Core Vol 6 Part B 4.4.2), so Coded windows are
 *    wasted time for this app (ScanSettings.Builder.setPhy, ScanSettings.PHY_LE_ALL_SUPPORTED being the default).
 * 2. While a verification code is on screen the call advert stays at ADVERTISE_MODE_LOW_LATENCY / TX_POWER_HIGH
 *    (AdvertisingSetParameters INTERVAL_LOW = 160 x 0.625 ms = 100 ms, the shortest the API offers): [AdvertisingBoost].
 * 3. The Meet scan looks for [MEET_SEARCH_MS] and replaces its scanner once half-way ([meetRestartAtMs]); some stacks
 *    report an address once per scanner.
 * 4. A contact-call wait follows the measured sighting rate ([dialWaitMs]).
 */
internal object AdvertisingBoost {
    /** Pure rule: a live verification code on screen holds the call listener at low latency (feature on). */
    fun holding(codeShown: Boolean, on: Boolean): Boolean = codeShown && on

    /** Whether the call listener's balanced step waits now (a live code is on this phone's screen). */
    fun holding(): Boolean = holding(runCatching { QrMeet.display.hasCode() }.getOrDefault(false), DebugTunables.weakPairReach)
}

internal object WeakPairReach {
    /** The Meet scan looks this long for the code's advert while "Looking for their phone..." shows (Cancel stays). */
    const val MEET_SEARCH_MS = 30_000L

    /** The 10 s search of build ef96bbf0 (control arm). */
    const val MEET_SEARCH_LEGACY_MS = 10_000L

    fun meetSearchMs(on: Boolean): Long = if (on) MEET_SEARCH_MS else MEET_SEARCH_LEGACY_MS

    /** A fresh scanner half-way through a search that has found nothing yet; never in the control arm. */
    fun meetRestartAtMs(on: Boolean): Long? = if (on) MEET_SEARCH_MS / 2 else null

    /** Sightings of one phone a contact-call wait should give room for. */
    const val DIAL_WAIT_SIGHTINGS = 5
    const val DIAL_WAIT_MIN_MS = DebugDialWait.WAIT_MS
    const val DIAL_WAIT_MAX_MS = 30_000L

    /**
     * How long a contact call waits for a sighting: room for [DIAL_WAIT_SIGHTINGS] sightings at this scanner's measured
     * typical gap between sightings of one phone ([CallPeerCadence.typicalGapMs]), between 10 and 30 s. No measurement
     * yet, or the control arm: the 10 s of before.
     */
    fun dialWaitMs(typicalGapMs: Long?, on: Boolean): Long {
        if (!on || typicalGapMs == null || typicalGapMs <= 0L) return DIAL_WAIT_MIN_MS
        return (typicalGapMs * DIAL_WAIT_SIGHTINGS).coerceIn(DIAL_WAIT_MIN_MS, DIAL_WAIT_MAX_MS)
    }

    /**
     * With relayed calls on, a contact may be reachable only through one or two other phones. A route is learned one hop
     * at a time (about 12-20 s for one relay, 25-35 s for two, measured 3-8 s when helpers already know it), so the wait
     * gives two hops room before saying the contact cannot be found.
     */
    const val RELAYED_CONTACT_WAIT_MS = 45_000L

    fun contactDialWaitMs(directWaitMs: Long, relayedCalls: Boolean): Long =
        if (relayedCalls) maxOf(directWaitMs, RELAYED_CONTACT_WAIT_MS) else directWaitMs
}
