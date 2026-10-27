package com.example.bluetoothtest

import android.os.SystemClock
import android.util.Log

/** One request of a caller's search, carried by the routed peer the call endpoint dials (Design OR, DEBUG prototype). */
class RelaySeekRoute internal constructor(
    internal val attempt: RelaySeekAttempt,
    internal val request: RelaySeekRings.Request,
)

/**
 * One caller search (DECISIONS "Design OR" revision 3, caller rules 1-7): a saved contact that is not heard directly is
 * sought through up to three first hops in expanding rings until a READY, the contact's own offer appearing, or the 45 s
 * give-up time. The call endpoint drives it from its connect thread ([next], [record]); nothing here touches the radio.
 */
class RelaySeekAttempt internal constructor(
    internal val contactPrefix: String,
    private val transport: BluetoothTransportOption,
    internal val rings: RelaySeekRings,
    private val selectors: List<RelaySeekSelectors.Selector>,
    internal val endMs: Long,
    private val epoch: Long,
) {
    /** The phone-wide relay duty this caller holds for its whole attempt (revision 3, caller rule 1). */
    internal val reservationOwner = Any()
    @Volatile var finished = false
        private set
    @Volatile var succeeded = false
        private set

    /** The next routed peer to dial, or null when the rings are done. [mayWait] allows the 1 s dial-time refresh. */
    internal fun next(mayWait: Boolean): BleL2capPeer? {
        while (true) {
            val request = rings.next(SystemClock.elapsedRealtime()) ?: return null
            val offer = RelaySeekCaller.freshHop(request.hop, transport, mayWait)
            if (offer == null) {
                rings.record(request, RelaySeekRings.Outcome.STALE, 0)
                Log.i("BluetoothVideo", prefix() + RelaySeekLog.line("caller", "stale", request.ring, request.budget, request.selector.kind))
                continue
            }
            if (RelaySeekCaller.memory.recentlyNoRoute(request.hop, request.selector.rdi, request.budget)) {
                rings.record(request, RelaySeekRings.Outcome.NO_ROUTE, 0)
                Log.i("BluetoothVideo", prefix() + RelaySeekLog.line("caller", "remembered_no_route", request.ring, request.budget, request.selector.kind))
                continue
            }
            val route = RelayRoute(
                target = request.selector.rdi.copyOf(),
                helperPrefix = request.hop.copyOf(),
                relays = request.budget,
                rotating = true,
                seek = RelaySeekRoute(this, request),
            )
            return BleL2capPeer(
                device = offer.peer.device,
                label = "seek via ${request.budget}",
                announcement = RelayedCalls.routedAnnouncement(request.selector.rdi, request.budget, offer.announcement),
                relayRoute = route,
            )
        }
    }

    /** The contact's own offer, heard now (the parallel direct check), or null. */
    internal fun directPeer(): BleL2capPeer? = RelaySeekCaller.directPeerFor(contactPrefix, transport)

    internal fun record(route: RelaySeekRoute, outcome: RelaySeekRings.Outcome, dials: Int) {
        rings.record(route.request, outcome, dials)
        when (outcome) {
            RelaySeekRings.Outcome.READY -> {
                succeeded = true
                RelaySeekCaller.memory.rememberReached(contactPrefix, epoch, route.request.hop)
                RelaySeekCaller.memory.reset(contactPrefix)
            }
            RelaySeekRings.Outcome.NO_ROUTE ->
                RelaySeekCaller.memory.rememberNoRoute(route.request.hop, route.request.selector.rdi, route.request.budget)
            else -> Unit
        }
    }

    /** The attempt is over: release the duty; "Can't find" ([found] false, [backoff]) starts the per-contact backoff. */
    internal fun finish(found: Boolean, backoff: Boolean = !found) {
        if (finished) return
        finished = true
        RelayDutyReservation.shared.release(reservationOwner)
        if (!found && backoff) RelaySeekCaller.memory.failed(contactPrefix)
        Log.i(
            "BluetoothVideo",
            prefix() + RelaySeekLog.line("caller", if (found) "finished_found" else "cant_find") +
                " | requests=${rings.requests} | dials=${rings.dials} | selectors=${selectors.size}",
        )
    }

    private fun prefix(): String = if (BuildConfig.DEBUG) "BENCHMARK " else ""
}

/** Android glue of the caller's side: the first hops, the direct check and the memory. */
internal object RelaySeekCaller {
    val memory = RelaySeekCallerMemory(SystemClock::elapsedRealtime)
    private const val WATCHDOG_MARGIN_MS = 5_000L

    sealed class Start {
        class Search(val attempt: RelaySeekAttempt, val peer: BleL2capPeer) : Start()
        class NotPossible(val reason: String) : Start()
        class BackedOff(val waitMs: Long) : Start()
    }

    /**
     * A search for the contact whose node prefix is [contactPrefix] (revision 3, caller rules 1-4), or why none starts.
     * Needs the contact's lease (recognition window), a version 5 helper heard in the last 10 s and the caller's duty.
     */
    fun start(
        contactPrefix: String,
        transport: BluetoothTransportOption,
        maxRelays: Int,
        require: Int,
        /** An automatic retry (not a user's tap): only these wait out the per-contact backoff ("Who carries the risk"). */
        automatic: Boolean = false,
    ): Start {
        if (!RelaySeekFeature.active()) return Start.NotPossible("off")
        if (!RelaySeekFeature.callerScope(RotatingDiscoveryFeature.enabled, Bmh3Feature.enabled)) return Start.NotPossible("scope")
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) return Start.NotPossible("classic")
        val backoff = if (automatic) memory.backoffMs(contactPrefix) else 0L
        if (backoff > 0L) return Start.BackedOff(backoff)
        val window = RotatingDiscovery.seekWindow(contactPrefix) ?: return Start.NotPossible("no_lease")
        val nowWallMs = DiscoveryWallClock.nowMs()
        val epochMs = RotatingDiscoveryIds.epochSeconds * 1_000L
        val untilBoundary = RotatingDiscoveryIds.msUntilBoundary(nowWallMs)
        val epoch = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull() ?: return Start.NotPossible("no_epoch")
        val selectors = RelaySeekSelectors.selectors(
            window,
            directEpoch = directRecognitionEpoch(contactPrefix),
            msUntilBoundary = untilBoundary,
            msSinceBoundary = epochMs - untilBoundary,
            epochSeconds = RotatingDiscoveryIds.epochSeconds,
        )
        if (selectors.isEmpty()) return Start.NotPossible("no_selector")
        val hops = firstHops(contactPrefix, transport, epoch)
        if (hops.isEmpty()) return Start.NotPossible("no_helpers")
        val now = SystemClock.elapsedRealtime()
        val rings = RelaySeekRings(
            attemptEndMs = now + RelaySeek.ATTEMPT_MS,
            selectors = selectors,
            firstHops = hops,
            maxRelays = maxRelays,
            require = require,
            connectMs = RelaySeekFeature.connectMs,
            clock = SystemClock::elapsedRealtime,
        )
        val attempt = RelaySeekAttempt(contactPrefix, transport, rings, selectors, now + RelaySeek.ATTEMPT_MS, epoch)
        if (!RelayDutyReservation.shared.tryAcquire(attempt.reservationOwner)) return Start.NotPossible("duty_held")
        // A dial that never started (a permission or Wi-Fi question left open) must not hold the duty: past the give-up
        // time plus a margin the attempt is over whatever happened.
        kotlin.concurrent.thread(name = "RelaySeekAttemptWatchdog", isDaemon = true) {
            try {
                Thread.sleep(RelaySeek.ATTEMPT_MS + WATCHDOG_MARGIN_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!attempt.finished) attempt.finish(found = false, backoff = false)
        }
        val peer = attempt.next(mayWait = false)
        if (peer == null) {
            attempt.finish(found = false)
            return Start.NotPossible("no_request")
        }
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + RelaySeekLog.line("caller", "started") +
                " | hops=${hops.size} | selectors=${selectors.size} | max_requests=${rings.maxRequests}",
        )
        return Start.Search(attempt, peer)
    }

    /** The ordered first hops (revision 3, caller rule 3): last helper that reached the contact, then signal. */
    private fun firstHops(contactPrefix: String, transport: BluetoothTransportOption, epoch: Long): List<ByteArray> {
        val now = SystemClock.elapsedRealtime()
        val offers = RelayedCallsRuntime.seekOffers.since(now - RelaySeek.OFFER_FRESH_AT_DIAL_MS)
            .filter { it.helper && it.announcement.transportOption() == transport && !isContact(it, contactPrefix) }
            .groupBy { it.rdi.prefixHex() }
            .map { (_, sightings) -> sightings.maxByOrNull { it.lastSeenMs }!! }
        val reached = memory.reachedBy(contactPrefix, epoch)
        val bySignal = if (RelaySeekFeature.farFirst) offers.sortedBy { it.rssi } else offers.sortedByDescending { it.rssi }
        return bySignal.sortedByDescending { it.rdi.prefixHex() == reached }
            .take(RelaySeekFeature.firstHops)
            .map { it.rdi.copyOf() }
    }

    /** The freshest sighting of the first hop [rdi]; one older than 10 s waits up to 1 s for a newer one, or is skipped. */
    fun freshHop(rdi: ByteArray, transport: BluetoothTransportOption, mayWait: Boolean): SeenOffer<BleL2capPeer>? {
        val until = SystemClock.elapsedRealtime() + if (mayWait) RelaySeek.REFRESH_WAIT_MS else 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val offer = RelayedCallsRuntime.seekOffers.since(now - RelaySeek.OFFER_FRESH_AT_DIAL_MS)
                .filter { it.rdi.contentEquals(rdi) && it.helper && it.announcement.transportOption() == transport }
                .maxByOrNull { it.lastSeenMs }
            if (offer != null || now >= until) return offer
            Thread.sleep(100L)
        }
    }

    /** The contact's own call offer heard in the last 5 s (a version 5 offer recognised as exactly this contact). */
    fun directPeerFor(contactPrefix: String, transport: BluetoothTransportOption): BleL2capPeer? {
        if (RelayedCallsFeature.noDirectSightings) return null
        val now = SystemClock.elapsedRealtime()
        return RelayedCallsRuntime.seekOffers.since(now - RelaySeek.OPPORTUNISTIC_CACHE_MS)
            .filter { it.announcement.transportOption() == transport && isContact(it, contactPrefix) }
            .maxByOrNull { it.lastSeenMs }?.peer
    }

    private fun directRecognitionEpoch(contactPrefix: String): Long? {
        val now = SystemClock.elapsedRealtime()
        return RelayedCallsRuntime.seekOffers.since(now - RelaySeek.OPPORTUNISTIC_CACHE_MS)
            .flatMap { offer -> RotatingDiscovery.recognize(offer.announcement) }
            .firstOrNull { it.contact.toString().startsWith(contactPrefix) }?.epoch
    }

    private fun isContact(offer: SeenOffer<BleL2capPeer>, contactPrefix: String): Boolean =
        RotatingDiscovery.recognize(offer.announcement).singleOrNull()?.contact?.toString()?.startsWith(contactPrefix) == true
}
