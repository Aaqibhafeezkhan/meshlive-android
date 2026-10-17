package com.example.bluetoothtest

/**
 * Relay child dial gate (Experimental, default off; DEBUG launch extra `test_relay_hold_in_place=true`).
 *
 * Why (lane 8, build 2cc4ca5a, b6-q31-c1-reset 01-A0 / 02-A1 / 06-A3, M31 relay -> A15s): after a failed dial the relay
 * marks the child (`EstablishmentFailureTracker`) or holds the next dial off (`RelayStallHoldoff`). Both checks sat inside
 * `ReceiverStreamForwarder.connectChild`, after the discovery callback had already called `stopPresence()`, and
 * `connectChild` ends every path in `restartPresence()`. A sighting that is only going to be skipped therefore
 * stopped and re-created the relay's offer advertising set and its scan. The child keeps advertising for 15 s, so the
 * scan saw it again about 120 ms later, and the loop spun until Android's scan quota ("BLE scan start deferred 29471
 * ms: scan quota (5 starts per 30 s)") stopped it and left the relay blind for 29 s:
 *   - 06-A3: 32 `skip_connect` lines, 51 Discovery advertising-set starts and 27 deferred scan starts in 5 minutes;
 *     a healthy run (03-A3, 07-A1) has 1 advertising-set start.
 *   - 20:27:23.6 to 24.4 in 06-A3: five stop/start cycles of the offer and the scan in 0.8 s, then the deferral.
 * The relay made 12 dials in those 5 minutes while the child opened 12 advertising windows of 15 s.
 *
 * Rule: a sighting of a child that is marked or held off is dropped in the discovery callback, before `stopPresence()`,
 * so the offer and the scan keep running unchanged (the same state a relay waits in before its first child). The
 * mark and hold-off are not touched: the relay still does not dial a marked or held-off child and still counts
 * nothing. The first sighting after they expire dials at once. The log lines keep their text and are limited to one per
 * child and [LOG_EVERY_MS], with the number of sightings dropped since the last line appended.
 *
 * What this does not do: it does not change which anchor a connection gets (the cause of the dead links is the
 * M31 controller's dual-role scheduling, DECISIONS "Relay dual-role scheduling"), and it does not shorten a mark.
 */
internal object RelayChildDialGate {
    enum class Verdict {
        /** Stop the presence and dial. */
        DIAL,

        /** The child is marked by the establishment tracker (offer withdrawn for it). */
        DEFER_MARKED,

        /** The stall guard or a refusal round just closed this child's link; its receiver is still tearing it down. */
        DEFER_HOLDOFF,
    }

    /** Pure decision. [marked] and [heldMs] are read by the caller; the two switches are the existing control arms. */
    fun verdict(fallbackOn: Boolean, marked: Boolean, holdoffOn: Boolean, heldMs: Long): Verdict = when {
        fallbackOn && marked -> Verdict.DEFER_MARKED
        holdoffOn && heldMs > 0L -> Verdict.DEFER_HOLDOFF
        else -> Verdict.DIAL
    }

    /** At most one deferral log line per child and this many milliseconds. */
    const val LOG_EVERY_MS = 5_000L
}

/** Rate limit for the deferral lines of [RelayChildDialGate]: one line per key and interval, counting what it dropped. */
internal class DialDeferLog(private val everyMs: Long = RelayChildDialGate.LOG_EVERY_MS) {
    private val lastLogMs = HashMap<String, Long>()
    private val dropped = HashMap<String, Int>()

    /** Records one deferred sighting of [key]; returns the number of sightings this line covers, or 0 when no line is due. */
    @Synchronized
    fun onDeferred(key: String, nowMs: Long): Int {
        val count = (dropped[key] ?: 0) + 1
        val last = lastLogMs[key]
        if (last != null && nowMs - last in 0 until everyMs) {
            dropped[key] = count
            return 0
        }
        lastLogMs[key] = nowMs
        dropped.remove(key)
        return count
    }

    /** The child dialled or is gone: the next deferral logs at once. */
    @Synchronized
    fun reset(key: String) {
        lastLogMs.remove(key)
        dropped.remove(key)
    }
}
