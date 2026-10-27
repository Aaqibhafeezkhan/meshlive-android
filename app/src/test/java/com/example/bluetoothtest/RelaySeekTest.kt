package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design OR (DEBUG prototype, DECISIONS "Design OR" revision 3): the search a caller and a helper run. */
class RelaySeekTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }

    private val nonce = bytes("0102030405060708")
    private val target = bytes("974ae410ea91")

    private fun seek(budget: Int = 2, skip: List<ByteArray> = listOf(bytes("44e6")), remaining: Int = 20_000) =
        RelaySetupMessage.Seek(budget, remaining, bytes("1112131415161718"), nonce, RelaySeek.token(nonce, target), skip)

    // --------------------------------------------------------------------------------------------- wire

    @Test
    fun theTokenIsTheDomainSeparatedHashOfNonceAndId() {
        assertEquals("f997a00c2596", hex(RelaySeek.token(nonce, target)))
        // Another nonce gives an unrelated token for the same ID (no simple equality across requests).
        assertFalse(RelaySeek.token(bytes("0102030405060709"), target).contentEquals(RelaySeek.token(nonce, target)))
    }

    @Test
    fun seekEncodesToTheDraftVectorAndRoundTrips() {
        val encoded = RelaySetupCodec.encode(seek())
        assertEquals("42525331050200004e2011121314151617180102030405060708f997a00c25960144e6", hex(encoded))
        for (count in 0..3) {
            val skip = List(count) { byteArrayOf(0x10, (0x20 + it).toByte()) }
            val bytes = RelaySetupCodec.encode(seek(skip = skip))
            assertEquals(33 + 2 * count, bytes.size)
            val decoded = RelaySetupCodec.decode(bytes, acceptSeek = true) as RelaySetupMessage.Seek
            assertEquals(2, decoded.budget)
            assertEquals(20_000, decoded.remainingMs)
            assertArrayEquals(nonce, decoded.nonce)
            assertEquals(skip.map(::hex), decoded.skip.map(::hex))
        }
    }

    @Test
    fun aReaderWithTheSwitchesOffRejectsMessageFiveAsUnknown() {
        val encoded = RelaySetupCodec.encode(seek())
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(encoded) }
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(encoded, acceptRdi = true) }
    }

    @Test
    fun theDecoderRejectsEveryOutOfRangeField() {
        fun mutated(index: Int, value: Int): ByteArray = RelaySetupCodec.encode(seek()).also { it[index] = value.toByte() }
        // budget 3 (cap of two relays), budget 0
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(mutated(5, 3), acceptSeek = true) }
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(mutated(5, 0), acceptSeek = true) }
        // remaining 20,001 ms
        assertThrows(IllegalArgumentException::class.java) {
            RelaySetupCodec.decode(RelaySetupCodec.encode(seek()).also { it[9] = 0x21 }, acceptSeek = true)
        }
        // an all-zero nonce
        assertThrows(IllegalArgumentException::class.java) {
            RelaySetupCodec.decode(RelaySetupCodec.encode(seek()).also { for (i in 18 until 26) it[i] = 0 }, acceptSeek = true)
        }
        // a skip count of 4, a count that disagrees with the length, a trailing byte, duplicate skip prefixes
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(mutated(32, 4), acceptSeek = true) }
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(mutated(32, 2), acceptSeek = true) }
        assertThrows(IllegalArgumentException::class.java) {
            RelaySetupCodec.decode(RelaySetupCodec.encode(seek()) + byteArrayOf(0), acceptSeek = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelaySetupCodec.encode(seek(skip = listOf(bytes("44e6"), bytes("44e6"))))
        }
    }

    // --------------------------------------------------------------------------------------------- selectors

    private val ids = mapOf(99L to bytes("aaaaaaaaaa99"), 100L to bytes("aaaaaaaaa100"), 101L to bytes("aaaaaaaaa101"))

    @Test
    fun thePrimarySelectorIsTheCentreAndTheNeighbourOnlyInsideTheBoundaryWindow() {
        val window = RelaySeekSelectors.Window(100L, stale = false, ids)
        val middle = RelaySeekSelectors.selectors(window, null, msUntilBoundary = 400_000, msSinceBoundary = 500_000, epochSeconds = 900)
        assertEquals(listOf(100L), middle.map { it.epoch })
        val beforeBoundary = RelaySeekSelectors.selectors(window, null, msUntilBoundary = 60_000, msSinceBoundary = 840_000, epochSeconds = 900)
        assertEquals(listOf(100L, 101L), beforeBoundary.map { it.epoch })
        assertEquals(RelaySeekSelectors.Kind.NEIGHBOUR, beforeBoundary[1].kind)
        val afterBoundary = RelaySeekSelectors.selectors(window, null, msUntilBoundary = 840_000, msSinceBoundary = 60_000, epochSeconds = 900)
        assertEquals(listOf(100L, 99L), afterBoundary.map { it.epoch })
        // A stale lease (recognition widens to +-8) gets the primary only.
        val stale = RelaySeekSelectors.Window(100L, stale = true, ids)
        assertEquals(listOf(100L), RelaySeekSelectors.selectors(stale, null, 60_000, 840_000, 900).map { it.epoch })
        // A direct recognition in this epoch decides the primary.
        assertEquals(101L, RelaySeekSelectors.selectors(window, 101L, 400_000, 500_000, 900).first().epoch)
        // No ID for the centre (the lease lacks that day): no search.
        assertTrue(RelaySeekSelectors.selectors(RelaySeekSelectors.Window(200L, false, ids), null, 1, 2, 900).isEmpty())
    }

    @Test
    fun theBoundaryWindowKeepsItsFractionUnderTheDebugShortEpoch() {
        assertEquals(120_000L, RelaySeekSelectors.boundaryWindowMs(900))
        assertEquals(8_000L, RelaySeekSelectors.boundaryWindowMs(60))
    }

    // --------------------------------------------------------------------------------------------- duty and budgets

    @Test
    fun theRelayDutyIsOnePhoneWideReservation() {
        val duty = RelayDutyReservation()
        val caller = Any()
        val helper = Any()
        assertTrue(duty.tryAcquire(caller))
        assertFalse("a phone on duty refuses a second request", duty.tryAcquire(helper))
        assertTrue(duty.tryAcquire(caller))
        duty.release(helper)
        assertTrue(duty.held)
        duty.release(caller)
        assertFalse(duty.held)
        assertTrue(duty.tryAcquire(helper))
    }

    private class Clock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun budgetsChargeOnlyWorkDoneCreditReadyAndShortenToTheDutyLeft() {
        val clock = Clock()
        val budgets = RelaySeekBudgets(clock, scale = { 1 })
        val first = Any()
        val admitted = budgets.admit(1, 20_000) as RelaySeekBudgets.Admission.Accept
        assertEquals(20_000L, admitted.allowanceMs)
        budgets.chargeScan(3_000, first)
        clock.now += 20_000
        budgets.finish(first, clock.now - 20_000, ready = false)
        // Pacing: 10 s between the end of one search and the next.
        assertTrue(budgets.admit(1, 20_000) is RelaySeekBudgets.Admission.Refuse)
        clock.now += 10_000
        // Two more failed 20 s searches leave 0 s of the 60 s duty: refused before any radio work.
        repeat(2) {
            val search = Any()
            assertTrue(budgets.admit(1, 20_000) is RelaySeekBudgets.Admission.Accept)
            clock.now += 20_000
            budgets.finish(search, clock.now - 20_000, ready = false)
            clock.now += 10_000
        }
        assertEquals(0L, budgets.dutyLeftMs())
        assertEquals("duty", (budgets.admit(1, 20_000) as RelaySeekBudgets.Admission.Refuse).reason)
        assertTrue(budgets.exhausted())
        // A search that ends in READY is credited back (duty and scanning).
        val clock2 = Clock()
        val fresh = RelaySeekBudgets(clock2, scale = { 1 })
        val ok = Any()
        fresh.chargeScan(3_000, ok)
        clock2.now += 15_000
        fresh.finish(ok, clock2.now - 15_000, ready = true)
        assertEquals(60_000L, fresh.dutyLeftMs())
        assertEquals(30_000L, fresh.scanLeftMs())
        // Shortened, not refused: 12 s of duty left still fits a budget-1 search (minimum 9 s).
        val clock3 = Clock()
        val tight = RelaySeekBudgets(clock3, scale = { 1 })
        val used = Any()
        tight.finish(used, clock3.now - 48_000, ready = false)
        clock3.now += 10_000
        assertEquals(12_000L, (tight.admit(1, 20_000) as RelaySeekBudgets.Admission.Accept).allowanceMs)
        assertTrue("budget 2 needs 14 s", tight.admit(2, 20_000) is RelaySeekBudgets.Admission.Refuse)
        // The window rolls: ten minutes later the duty is back.
        clock3.now += RelaySeekBudgets.WINDOW_MS
        assertEquals(60_000L, tight.dutyLeftMs())
    }

    @Test
    fun dialsAndHandshakesAreCappedAndScaleWithTheDebugFactor() {
        val clock = Clock()
        val budgets = RelaySeekBudgets(clock, scale = { 1 })
        repeat(12) { budgets.chargeDial(null) }
        assertEquals("dials", (budgets.admit(1, 20_000) as RelaySeekBudgets.Admission.Refuse).reason)
        assertFalse(budgets.exhaustedFor(60_000))
        clock.now += 60_000
        assertTrue("exhausted for a minute: the helper flag is withdrawn", budgets.exhaustedFor(60_000))
        val scaled = RelaySeekBudgets(clock, scale = { 10 })
        repeat(12) { scaled.chargeHandshake(null) }
        assertEquals(108, scaled.handshakesLeft())
        assertEquals(600_000L, scaled.dutyLeftMs())
    }

    @Test
    fun onlyFailAnswersAreCachedKeyedOnTheWholeSearchForSixtySeconds() {
        val clock = Clock()
        val cache = RelaySeekFailCache(clock, capacity = 2)
        cache.store(seek(), RelaySetupFailure.NO_ROUTE)
        assertEquals(RelaySetupFailure.NO_ROUTE, cache.lookup(seek()))
        // Another skip list or budget is another search.
        assertNull(cache.lookup(seek(skip = emptyList())))
        assertNull(cache.lookup(seek(budget = 1)))
        clock.now += 60_000
        assertNull(cache.lookup(seek()))
        cache.store(seek(budget = 1), RelaySetupFailure.NO_ROUTE)
        cache.store(seek(budget = 2), RelaySetupFailure.TIMEOUT)
        cache.store(seek(skip = emptyList()), RelaySetupFailure.BUSY)
        assertEquals(2, cache.size)
    }

    // --------------------------------------------------------------------------------------------- caller rings

    private val hops = listOf(bytes("a1a1a1a1a1a1"), bytes("b2b2b2b2b2b2"), bytes("c3c3c3c3c3c3"))
    private val primary = RelaySeekSelectors.Selector(100, bytes("aaaaaaaaa100"), RelaySeekSelectors.Kind.PRIMARY)
    private val neighbour = RelaySeekSelectors.Selector(101, bytes("aaaaaaaaa101"), RelaySeekSelectors.Kind.NEIGHBOUR)

    private fun rings(clock: Clock, selectors: List<RelaySeekSelectors.Selector> = listOf(primary), cap: Int = 2, require: Int = 0) =
        RelaySeekRings(clock.now + RelaySeek.ATTEMPT_MS, selectors, hops, cap, require, 4_000L, clock)

    @Test
    fun ringsAskEveryFirstHopForOneRelayFirstThenTwoWithTheMissesSkipped() {
        val clock = Clock()
        val plan = rings(clock, listOf(primary, neighbour))
        val asked = mutableListOf<String>()
        while (true) {
            val request = plan.next() ?: break
            asked += "${request.ring}:${hex(request.hop).take(2)}:${request.budget}:${request.selector.kind}:${request.skip.joinToString { hex(it) }}"
            assertTrue(request.remainingMs <= 20_000)
            plan.record(request, RelaySeekRings.Outcome.NO_ROUTE, 1)
        }
        assertEquals(
            listOf(
                "ONE:a1:1:PRIMARY:", "ONE:b2:1:PRIMARY:", "ONE:c3:1:PRIMARY:",
                "ONE_B:a1:1:NEIGHBOUR:", "ONE_B:b2:1:NEIGHBOUR:", "ONE_B:c3:1:NEIGHBOUR:",
                "TWO:a1:2:PRIMARY:a1a1, b2b2, c3c3", "TWO:b2:2:PRIMARY:a1a1, b2b2, c3c3", "TWO:c3:2:PRIMARY:a1a1, b2b2, c3c3",
            ),
            asked,
        )
        // The bound: N(S + 1) requests and two dials each.
        assertEquals(9, plan.maxRequests)
        assertEquals(18, plan.maxDials)
        assertEquals(9, plan.requests)
    }

    @Test
    fun busyRefusedUnboundOrSilentHopsAreNotAskedAgainAndReadyStops() {
        val clock = Clock()
        val plan = rings(clock)
        val first = plan.next()!!
        plan.record(first, RelaySeekRings.Outcome.BUSY_OR_REFUSED, 1)
        val second = plan.next()!!
        plan.record(second, RelaySeekRings.Outcome.AMBIGUOUS, 2)
        val third = plan.next()!!
        assertEquals("c3", hex(third.hop).take(2))
        plan.record(third, RelaySeekRings.Outcome.NO_ROUTE, 1)
        val ringTwo = plan.next()!!
        assertEquals(RelaySeekRings.Ring.TWO, ringTwo.ring)
        assertEquals("only the one hop that answered is asked again", "c3", hex(ringTwo.hop).take(2))
        assertEquals(listOf("c3c3"), ringTwo.skip.map(::hex))
        plan.record(ringTwo, RelaySeekRings.Outcome.READY, 1)
        assertNull(plan.next())
        assertEquals(5, plan.dials)
    }

    @Test
    fun aRequiredChainAndTheRelayCapChooseTheRings() {
        val clock = Clock()
        assertEquals(RelaySeekRings.Ring.TWO, rings(clock, require = 2).next()!!.ring)
        val oneOnly = rings(clock, cap = 1)
        repeat(3) { oneOnly.record(oneOnly.next()!!, RelaySeekRings.Outcome.NO_ROUTE, 1) }
        assertNull("no ring 2 under a cap of one relay", oneOnly.next())
        val requireOne = rings(clock, require = 1)
        repeat(3) { requireOne.record(requireOne.next()!!, RelaySeekRings.Outcome.NO_ROUTE, 1) }
        assertNull(requireOne.next())
    }

    @Test
    fun aRequestIsSentOnlyWhenItsMinimumAllowanceFitsAfterTheConnection() {
        val clock = Clock()
        val plan = rings(clock)
        // 45 s attempt: after 30 s, 13 s remain (45 - 30 - 2): budget 1 needs 9 s after a 4 s connection -> just fits.
        clock.now += 30_000
        val request = plan.next()!!
        assertEquals(13_000, request.remainingMs)
        plan.record(request, RelaySeekRings.Outcome.NO_ROUTE, 1)
        clock.now += 1
        assertNull("12.999 s less 4 s is below the 9 s minimum: the attempt is over", plan.next())
        assertTrue(plan.done)
    }

    // --------------------------------------------------------------------------------------------- helper search

    private fun announcement(rdi: ByteArray, consent: Boolean = true, helper: Boolean = false) = DiscoveryAnnouncement(
        rdi,
        DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
            (if (helper) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0),
        NodeCapabilities.CODEC_VP9 or (if (consent) RelayedCalls.CONSENT_BIT else 0),
        0, 0, 0, version = DiscoveryAnnouncement.ROTATING_VERSION,
    )

    private class Sighting(val address: String, val announcement: DiscoveryAnnouncement, val rssi: Int, val atMs: Long, val count: Int = 1)

    /** Fake radio: sightings appear at their time; dials answer from scripts; the clock moves with the scan and each dial. */
    private class Radio(val clock: Clock) : RelaySeekSearch.Effects<String> {
        val sightings = mutableListOf<Sighting>()
        val targetDials = mutableListOf<String>()
        val childDials = mutableListOf<Pair<String, Int>>()
        var targetResult: (String) -> RelaySeekSearch.DialResult = { RelaySeekSearch.DialResult.LINKED }
        var childResult: (String) -> RelaySeekSearch.ChildResult = { RelaySeekSearch.ChildResult.Fail(RelaySetupFailure.NO_ROUTE) }
        var dialMs = 2_000L
        var scans = 0
        var ownIds = listOf<ByteArray>()

        override fun offers(sinceMs: Long): List<SeenOffer<String>> = sightings
            .filter { it.atMs in sinceMs..clock.now }
            .map { SeenOffer(it.address, it.address, it.announcement, it.rssi, it.atMs, it.atMs, it.count) }

        override fun scan(untilMs: Long, done: () -> Boolean): Long {
            scans++
            val start = clock.now
            while (clock.now < untilMs && !done()) clock.now += 100
            return clock.now - start
        }

        override fun isOwnId(rdi: ByteArray): Boolean = ownIds.any { it.contentEquals(rdi) }
        override fun stopped(): Boolean = false

        override fun dialTarget(offer: SeenOffer<String>, allowRetry: Boolean, attempts: (Int) -> Unit): RelaySeekSearch.DialResult {
            targetDials += offer.address
            clock.now += dialMs
            attempts(1)
            return targetResult(offer.address)
        }

        override fun dialChild(offer: SeenOffer<String>, remainingMs: Int, allowRetry: Boolean, attempts: (Int) -> Unit): RelaySeekSearch.ChildResult {
            childDials += offer.address to remainingMs
            clock.now += dialMs
            attempts(1)
            return childResult(offer.address)
        }
    }

    private fun search(radio: Radio, budget: Int = 1, deadlineMs: Long = 20_000, skip: List<ByteArray> = emptyList(), plainId: ByteArray? = null) =
        RelaySeekSearch(
            budget,
            if (plainId == null) nonce else null,
            if (plainId == null) RelaySeek.token(nonce, target) else null,
            plainId,
            skip,
            radio.clock.now + deadlineMs,
            radio.clock,
            RelaySeekSearch.Config(scanMs = 3_000, fanout = 2, connectMs = 4_000),
            radio,
        )

    @Test
    fun aTargetHeardInTheLastFiveSecondsIsUsedWithoutAScan() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.sightings += Sighting("T1", announcement(target), -60, clock.now - 2_000)
        val outcome = search(radio).run()
        assertTrue(outcome is RelaySeekSearch.Outcome.TargetLinked)
        assertEquals(0, radio.scans)
        assertEquals(listOf("T1"), radio.targetDials)
    }

    @Test
    fun theScanCollectsUpToThreeAddressesForOneSecondAfterTheFirstMatchSoACopyCannotEclipseTheTarget() {
        val clock = Clock()
        val radio = Radio(clock)
        val start = clock.now
        // A copy of the target's ID is heard first; the genuine phone (heard twice) 600 ms later.
        radio.sightings += Sighting("COPY", announcement(target), -40, start + 300)
        radio.sightings += Sighting("REAL", announcement(target), -70, start + 900, count = 2)
        radio.sightings += Sighting("LATE", announcement(target), -50, start + 1_500)
        radio.targetResult = { address -> if (address == "COPY") RelaySeekSearch.DialResult.IDENTITY else RelaySeekSearch.DialResult.LINKED }
        val runner = search(radio)
        val outcome = runner.run()
        // Collection stopped about 1 s after the first match: LATE (1.5 s) was not waited for.
        assertTrue(runner.scannedMs in 1_300L..1_400L)
        assertEquals("heard twice first, then signal", listOf("REAL"), radio.targetDials)
        assertTrue(outcome is RelaySeekSearch.Outcome.TargetLinked)
    }

    @Test
    fun anIdentityFailureMovesToTheNextAddressAndOnlyFailuresOfIdentityAnswerChildAuthentication() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.sightings += Sighting("A", announcement(target), -40, clock.now - 1_000)
        radio.sightings += Sighting("B", announcement(target), -50, clock.now - 1_000)
        radio.targetResult = { RelaySeekSearch.DialResult.IDENTITY }
        val outcome = search(radio).run() as RelaySeekSearch.Outcome.Failed
        assertEquals(listOf("A", "B"), radio.targetDials)
        assertEquals(RelaySetupFailure.CHILD_AUTHENTICATION, outcome.reason)
    }

    @Test
    fun ownIdsAndOffersWithoutConsentNeverMatchAndBudgetOneThenAnswersNoRoute() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.ownIds = listOf(target)
        radio.sightings += Sighting("SELF", announcement(target), -40, clock.now - 1_000)
        assertEquals(RelaySetupFailure.NO_ROUTE, (search(radio).run() as RelaySeekSearch.Outcome.Failed).reason)
        val radio2 = Radio(Clock())
        radio2.sightings += Sighting("NOCONSENT", announcement(target, consent = false), -40, radio2.clock.now - 1_000)
        assertEquals(RelaySetupFailure.NO_ROUTE, (search(radio2).run() as RelaySeekSearch.Outcome.Failed).reason)
        assertTrue(radio2.targetDials.isEmpty())
        assertEquals("no match: a full 3 s scan", 1, radio2.scans)
    }

    @Test
    fun budgetTwoTriesHelpersFromTheScanHonouringTheSkipListAndMovesOnFromAnyChildFailure() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.sightings += Sighting("SIB", announcement(bytes("44e6aaaaaaaa"), helper = true), -30, clock.now - 1_000)
        radio.sightings += Sighting("H1", announcement(bytes("1111aaaaaaaa"), helper = true), -50, clock.now - 1_000)
        radio.sightings += Sighting("H2", announcement(bytes("2222aaaaaaaa"), helper = true), -60, clock.now - 1_000)
        radio.childResult = { address ->
            if (address == "H1") RelaySeekSearch.ChildResult.Fail(RelaySetupFailure.BUSY) else RelaySeekSearch.ChildResult.Ready(1)
        }
        val outcome = search(radio, budget = 2, skip = listOf(bytes("44e6"))).run()
        assertEquals(listOf("H1", "H2"), radio.childDials.map { it.first })
        assertEquals(1, (outcome as RelaySeekSearch.Outcome.ChildReady).relays)
        // Each child gets what is left of the one deadline less the per-hop reserve, never more than 20 s.
        assertTrue(radio.childDials.all { it.second in RelayedCalls.MIN_REMAINING_MS..20_000 })
        assertTrue(radio.childDials[1].second < radio.childDials[0].second)
    }

    @Test
    fun theFinalReasonFollowsHelperRuleTen() {
        // Any child answering no route -> no route.
        val clock = Clock()
        val radio = Radio(clock)
        radio.sightings += Sighting("H1", announcement(bytes("1111aaaaaaaa"), helper = true), -50, clock.now - 1_000)
        radio.sightings += Sighting("H2", announcement(bytes("2222aaaaaaaa"), helper = true), -60, clock.now - 1_000)
        radio.childResult = { address ->
            RelaySeekSearch.ChildResult.Fail(if (address == "H1") RelaySetupFailure.NO_ROUTE else RelaySetupFailure.BUSY)
        }
        assertEquals(RelaySetupFailure.NO_ROUTE, (search(radio, budget = 2).run() as RelaySeekSearch.Outcome.Failed).reason)
        // Children reached, none said no route: the last child's reason.
        val radio2 = Radio(Clock())
        radio2.sightings += Sighting("H1", announcement(bytes("1111aaaaaaaa"), helper = true), -50, radio2.clock.now - 1_000)
        radio2.childResult = { RelaySeekSearch.ChildResult.Fail(RelaySetupFailure.REFUSED) }
        assertEquals(RelaySetupFailure.REFUSED, (search(radio2, budget = 2).run() as RelaySeekSearch.Outcome.Failed).reason)
        // No child reached (no RELAY_SEEK, a close): no route.
        val radio3 = Radio(Clock())
        radio3.sightings += Sighting("H1", announcement(bytes("1111aaaaaaaa"), helper = true), -50, radio3.clock.now - 1_000)
        radio3.childResult = { RelaySeekSearch.ChildResult.NoSeek }
        assertEquals(RelaySetupFailure.NO_ROUTE, (search(radio3, budget = 2).run() as RelaySeekSearch.Outcome.Failed).reason)
    }

    @Test
    fun aChildIsDialledOnlyWhenTheMinimumAllowanceStillFitsAndTheDialCapHolds() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.dialMs = 5_000
        radio.sightings += Sighting("H1", announcement(bytes("1111aaaaaaaa"), helper = true), -50, clock.now - 1_000)
        radio.sightings += Sighting("H2", announcement(bytes("2222aaaaaaaa"), helper = true), -60, clock.now - 1_000)
        radio.childResult = { RelaySeekSearch.ChildResult.Fail(RelaySetupFailure.REFUSED) }
        // 20 s: the scan takes 3 s (to 17 s left), H1 is dialled (12 s left after it), then 12 - 4 < 9: no H2, timeout.
        val outcome = search(radio, budget = 2, deadlineMs = 20_000).run() as RelaySeekSearch.Outcome.Failed
        assertEquals(listOf("H1"), radio.childDials.map { it.first })
        assertEquals(RelaySetupFailure.TIMEOUT, outcome.reason)
        // Dial cap: three targets that fail to connect use all three dials; no helper is dialled.
        val radio2 = Radio(Clock())
        radio2.dialMs = 100
        listOf("A", "B", "C").forEach { radio2.sightings += Sighting(it, announcement(target), -50, radio2.clock.now - 500) }
        radio2.sightings += Sighting("H1", announcement(bytes("1111aaaaaaaa"), helper = true), -50, radio2.clock.now - 500)
        radio2.targetResult = { RelaySeekSearch.DialResult.FAILED }
        val runner = search(radio2, budget = 2)
        runner.run()
        assertEquals(3, runner.dials)
        assertTrue(radio2.childDials.isEmpty())
    }

    @Test
    fun aStaleHelperIsSkippedAtDialTime() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.sightings += Sighting("OLD", announcement(bytes("1111aaaaaaaa"), helper = true), -50, clock.now - 9_000)
        // The 3 s scan makes the 9 s old sighting 12 s old at dial time: skipped.
        val outcome = search(radio, budget = 2).run() as RelaySeekSearch.Outcome.Failed
        assertTrue(radio.childDials.isEmpty())
        assertEquals(RelaySetupFailure.NO_ROUTE, outcome.reason)
    }

    @Test
    fun aPlainSetupHandledAsASearchComparesTheIdDirectlyAndTooLittleTimeIsATimeout() {
        val clock = Clock()
        val radio = Radio(clock)
        radio.sightings += Sighting("T", announcement(target), -50, clock.now - 500)
        assertTrue(search(radio, plainId = target).run() is RelaySeekSearch.Outcome.TargetLinked)
        val radio2 = Radio(Clock())
        assertEquals(
            RelaySetupFailure.TIMEOUT,
            (search(radio2, deadlineMs = 2_999).run() as RelaySeekSearch.Outcome.Failed).reason,
        )
    }

    @Test
    fun aCopiedAddressThatFailedTheOpeningCostsOneDialPerEpoch() {
        val bad = RelaySeekBadAddresses(capacity = 2)
        bad.record("COPY", 100)
        assertTrue(bad.bad("COPY", 100))
        assertFalse("a new epoch forgets it", bad.bad("COPY", 101))
        bad.record("B", 100)
        bad.record("C", 100)
        assertFalse("bounded", bad.bad("COPY", 100))
    }

    // --------------------------------------------------------------------------------------------- caller memory

    @Test
    fun theCallerBacksOffAfterCantFindAndRemembersMissesForAMinute() {
        val clock = Clock()
        val memory = RelaySeekCallerMemory(clock)
        assertEquals(0L, memory.backoffMs("c1", disabled = false))
        memory.failed("c1")
        assertEquals(30_000L, memory.backoffMs("c1", disabled = false))
        assertEquals("the attack cell's caller", 0L, memory.backoffMs("c1", disabled = true))
        memory.failed("c1")
        assertEquals(60_000L, memory.backoffMs("c1", disabled = false))
        memory.failed("c1")
        memory.failed("c1")
        assertEquals(120_000L, memory.backoffMs("c1", disabled = false))
        memory.reset("c1")
        assertEquals(0L, memory.backoffMs("c1", disabled = false))
        memory.rememberNoRoute(hops[0], primary.rdi, 1)
        assertTrue(memory.recentlyNoRoute(hops[0], primary.rdi, 1))
        assertFalse(memory.recentlyNoRoute(hops[0], primary.rdi, 2))
        clock.now += 60_000
        assertFalse(memory.recentlyNoRoute(hops[0], primary.rdi, 1))
        memory.rememberReached("c1", 100, hops[1])
        assertEquals(hex(hops[1]), memory.reachedBy("c1", 100))
        assertNull("only for the epoch it reached in", memory.reachedBy("c1", 101))
    }

    // --------------------------------------------------------------------------------------------- capability

    @Test
    fun relaySeekIsTypeFiveWithOneFeatureByteAndFitsBesideTheLargestBlock() {
        val area = Bmh3Extensions(relaySeek = RelaySeek.FEATURE_SEEK_V1).encodeArea()
        assertEquals(RelaySeek.FEATURE_SEEK_V1, Bmh3Extensions.parse(area).relaySeek)
        assertNull(Bmh3Extensions.parse(Bmh3Extensions.NONE.encodeArea()).relaySeek)
        val full = Bmh3Extensions(opening = ByteArray(87).also { it[0] = 2 }, buildTag = BuildTag(6, false), relaySeek = 1)
        assertEquals(1, Bmh3Extensions.parse(full.encodeArea()).relaySeek)
        fun area(vararg tail: Int): ByteArray {
            val pad = Bmh3Wire.EXTENSION_BYTES - 3 - tail.size
            return byteArrayOf(0, (pad ushr 8).toByte(), pad.toByte()) + ByteArray(pad) + ByteArray(tail.size) { tail[it].toByte() }
        }
        assertEquals("relay seek malformed", assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_RELAY_SEEK, 0, 2, 1, 0))
        }.reason)
        // Reserved bits are carried, not rejected: an older reader of bit 0 ignores bits 1 and 2.
        assertEquals(7, Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_RELAY_SEEK, 0, 1, 7)).relaySeek)
    }
}
