package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * A simulated two-way GATT call like regress-ff0e24e1 ASUS->S24 (900 kbps requests). Both
 * directions share one link (total capacity +/-10% per 100 ms, served alternately in 250-byte
 * slices while both have data), each behind its own Bluetooth stack buffer. The large direction's
 * records cross in 770 kbps arrival trains, so the train estimate overstates what it sustains.
 * Each receiver plays out like a call (250 ms start, grows to cover lateness, at most 800 ms,
 * saturated while late media arrived within 2 s, calm shrink after 10 s), counts whole media
 * records with their arrival time and reports every second. A report travels back behind the
 * reverse direction's stack queue, plus an optional extra return delay (the 2026-10-09 review probe).
 * Every direction runs its own controller with the arm's settings.
 */
class DeliveryRateControlSimulationTest {
    private class Result(val decreases: List<Pair<Long, Int>>, val targets: List<Pair<Long, Int>>, val lateFrames: List<Long>, val lateRuns: Int) {
        fun targetsBetween(fromMs: Long, toMs: Long) = targets.filter { it.first in fromMs until toMs }.map { it.second }
        fun lateFramesAfter(ms: Long) = lateFrames.count { it >= ms }
    }

    private class Memory(start: Int) : SustainedRateMemory {
        val values = mutableMapOf("peer" to start)
        override fun startBps(peer: String): Int? = values[peer]
        override fun record(peer: String, bps: Int) {
            values[peer] = bps
        }
    }

    /** A media record, or (with [report]) a report about the reverse direction riding this stack. */
    private class Record(val captureMs: Long, val video: Boolean, val size: Int, val report: LinkCapacityReport? = null) {
        var written = 0
        var delivered = 0
    }

    private class Spec(
        val requestBps: Int = 900_000,
        val startBps: Int = 458_000,
        /** The camera scene limits what the encoder emits (the S24 sent 100-160 kbps of a 900 request). */
        val contentLimitBps: Int = Int.MAX_VALUE,
        val stackBufferBytes: Int = 48_000,
        val trainKbps: Int = 770,
    )

    private inner class Direction(val spec: Spec, deliveryReports: Boolean, probing: Boolean, val random: Random) {
        val audioBps = 16_000
        val controller = LinkRateController(
            spec.requestBps,
            audioBps = audioBps,
            memory = Memory(spec.startBps),
            frameRateLadder = FrameRateLadder(15, 5, FrameRateLadder.floorBpsFor(426, 240, 15)),
            deliveryProbing = probing,
            rememberCuts = !deliveryReports,
        )
        val reports = CallDeliveryReports(deliveryReports).also { it.onPeerHeartbeat(null) }
        var targetBps = controller.onSample("call", LinkRateSample(1, 0, 0, 0, false, peer = "peer"), 0L) ?: controller.effectiveVideoBps
        val appQueue = ArrayDeque<Record>()
        val stack = ArrayDeque<Record>()
        var stackMediaBytes = 0
        var nextFrameMs = 0L
        var nextAudioMs = 0L
        var offeredWindow = 0L
        var sentWindow = 0L
        // Sender's view of its child.
        val estimate = PeerDeliveryEstimate()
        val trend = PeerLatenessTrend()
        var childLateUntilMs = Long.MIN_VALUE
        var measuredKbps = 0
        // The far end receiving this direction (its clock runs 5 s behind the sender's).
        val delivery = LinkDeliveryCounter({ (nowMs - 5_000L) * 1_000_000L })
        var playoutMs = 250L
        var lastLateMs = Long.MIN_VALUE
        var windowLateMaxMs = 0L
        var lateRuns = 0
        var inLateRun = false
        val lateFrames = mutableListOf<Long>()
        val decreases = mutableListOf<Pair<Long, Int>>()
        val targets = mutableListOf<Pair<Long, Int>>()

        fun encode(now: Long) {
            if (now >= nextFrameMs) {
                val rate = minOf(targetBps, spec.contentLimitBps)
                val bytes = (rate / 8 / 15 * (0.85 + 0.3 * random.nextDouble())).toInt().coerceAtLeast(1)
                appQueue.addLast(Record(now, true, bytes))
                offeredWindow += bytes
                nextFrameMs += 1_000L / 15
            }
            if (now >= nextAudioMs) {
                appQueue.addLast(Record(now, false, audioBps / 8 / 50))
                offeredWindow += audioBps / 8 / 50
                nextAudioMs += 20L
            }
        }

        /** GATT writes return once the stack accepted the bytes; a record's write completes with its last byte. */
        fun write(now: Long) {
            while (appQueue.isNotEmpty() && stackMediaBytes < spec.stackBufferBytes) {
                val head = appQueue.first()
                val moved = minOf(head.size - head.written, spec.stackBufferBytes - stackMediaBytes)
                if (head.written == 0) stack.addLast(head)
                head.written += moved
                stackMediaBytes += moved
                sentWindow += moved
                if (head.written == head.size) {
                    appQueue.removeFirst()
                    estimate.onSent(head.size, now * 1_000_000L)
                }
            }
        }

        /** The far end took [record] off the link at [now]. */
        fun arrived(record: Record, now: Long) {
            delivery.add(record.size)
            if (!record.video) return
            val delayMs = now - record.captureMs
            if (delayMs > playoutMs) {
                val lateMs = delayMs - playoutMs
                windowLateMaxMs = maxOf(windowLateMaxMs, lateMs)
                lateFrames += now
                lastLateMs = now
                if (!inLateRun && lateMs >= 100) {
                    lateRuns++
                    inLateRun = true
                }
                playoutMs = minOf(800L, delayMs + 50L)
            } else if (now - lastLateMs > 1_000L) {
                inLateRun = false
            }
        }

        fun calm(now: Long) {
            if (now - lastLateMs > 10_000L && playoutMs > 250L) playoutMs = maxOf(250L, playoutMs - 10L)
        }

        /** The far end's report about this direction (it rides the reverse direction's stack). */
        fun farEndReport(now: Long): LinkCapacityReport {
            val saturated = playoutMs >= 800L && now - lastLateMs <= 2_000L
            val added = maxOf(playoutMs - 250L, windowLateMaxMs).toInt()
            windowLateMaxMs = 0L
            val base = LinkCapacityReport(spec.trainKbps, saturated, added)
            return base.copy(delivery = reports.deliveryFor(callKnown = true, relayHops = 0) { delivery.snapshot() })
        }

        fun onReport(report: LinkCapacityReport, now: Long) {
            measuredKbps = report.arrivalKbps
            if (trend.onReport(report, now * 1_000_000L)) childLateUntilMs = now + 1_500L
            reports.accepted(report)?.let { estimate.onReport(it) }
        }

        fun sample(now: Long) {
            val backlogAgeMs = appQueue.firstOrNull()?.let { (now - it.captureMs).toInt() } ?: 0
            val changed = controller.onSample(
                "call",
                LinkRateSample(
                    windowNs = 1_000_000_000L,
                    offeredBytes = offeredWindow,
                    sentBytes = sentWindow,
                    backlogAgeMs = backlogAgeMs,
                    largeFrameGrace = false,
                    measuredLinkKbps = measuredKbps,
                    childLate = now < childLateUntilMs,
                    peer = "peer",
                    deliveredKbps = estimate.deliveredKbps(),
                    peerQueueMs = estimate.queueMs(),
                ),
                now * 1_000_000L,
            )
            offeredWindow = 0L
            sentWindow = 0L
            if (changed != null) {
                if (changed < targetBps) decreases += now to changed
                targetBps = changed
            }
            targets += now to targetBps
        }

        fun result() = Result(decreases, targets, lateFrames, lateRuns)
    }

    private var nowMs = 0L

    /** Runs both directions; returns (large direction A, reverse direction B). */
    private fun simulate(
        deliveryReports: Boolean,
        deliveryProbing: Boolean,
        a: Spec = Spec(),
        b: Spec = Spec(contentLimitBps = 150_000, trainKbps = 0),
        linkKbpsAt: (Long) -> Int = { 800 },
        extraReturnDelayToA: (Long) -> Long = { 0L },
        durationMs: Long = 180_000L,
        seed: Long = 7L,
    ): Pair<Result, Result> {
        val random = Random(seed)
        val dirA = Direction(a, deliveryReports, deliveryProbing, Random(seed + 1))
        val dirB = Direction(b, deliveryReports, deliveryProbing, Random(seed + 2))
        val delayedToA = ArrayDeque<Pair<Long, LinkCapacityReport>>()
        var credit = 0.0
        var linkKbps = linkKbpsAt(0).toDouble()
        var turnA = true
        nowMs = 0L
        while (nowMs < durationMs) {
            val now = nowMs
            dirA.encode(now)
            dirB.encode(now)
            dirA.write(now)
            dirB.write(now)
            if (now % 100L == 0L) linkKbps = linkKbpsAt(now) * (0.9 + 0.2 * random.nextDouble())
            val tick = linkKbps * 1_000 / 8 / 100
            credit = if (dirA.stack.isEmpty() && dirB.stack.isEmpty()) tick else credit + tick
            // One shared link: alternate 250-byte slices while both directions have data.
            while (credit >= 1.0 && (dirA.stack.isNotEmpty() || dirB.stack.isNotEmpty())) {
                val dir = when {
                    dirA.stack.isEmpty() -> dirB
                    dirB.stack.isEmpty() -> dirA
                    turnA -> dirA
                    else -> dirB
                }
                turnA = dir === dirB
                val head = dir.stack.first()
                val take = minOf(head.written - head.delivered, 250, credit.toInt())
                if (take <= 0) break
                head.delivered += take
                if (head.report == null) dir.stackMediaBytes -= take
                credit -= take
                if (head.delivered < head.size) continue
                dir.stack.removeFirst()
                val report = head.report
                if (report == null) {
                    dir.arrived(head, now)
                } else if (dir === dirB) {
                    // Phone 2's report about A came back through B's queue.
                    delayedToA.addLast(now + extraReturnDelayToA(now) to report)
                } else {
                    dirB.onReport(report, now)
                }
            }
            while (delayedToA.isNotEmpty() && delayedToA.first().first <= now) dirA.onReport(delayedToA.removeFirst().second, now)
            dirA.calm(now)
            dirB.calm(now)
            if (now % 1_000L == 0L && now > 0L) {
                // Each far end reports on its own writer: behind its outgoing stack, not its app queue.
                dirB.stack.addLast(Record(now, false, 50, dirA.farEndReport(now)).also { it.written = 50 })
                dirA.stack.addLast(Record(now, false, 50, dirB.farEndReport(now)).also { it.written = 50 })
            }
            if (now % 1_000L == 500L) {
                dirA.sample(now)
                dirB.sample(now)
            }
            nowMs += 10L
        }
        return dirA.result() to dirB.result()
    }

    private val audioKbps = 16

    /** 0.85 x what the link delivers - audio: where one delivery-referenced cut lands. */
    private fun cutFor(goodputKbps: Double) = ((goodputKbps * LinkRateController.LINK_HEADROOM - audioKbps) * 1_000).toInt()

    private fun cascades(result: Result) = result.decreases.zipWithNext().count { (x, y) -> y.first - x.first <= 4_000L }

    @Test
    fun withoutDeliveryReportsTheSharedLinkCascadesBelowItself() {
        // The control arm and older peers: the measured regression (650 -> 620 -> 564 -> 461 -> 316
        // kbps) reproduced. Cuts reference the trains or what the stack accepted, land above the link,
        // cascade once lateness outlives the queue, and cycle.
        for (seed in listOf(7L, 11L)) {
            val (old, _) = simulate(deliveryReports = false, deliveryProbing = false, seed = seed)
            assertTrue("seed $seed ${old.decreases}", cascades(old) >= 4)
            assertTrue("seed $seed", old.targets.minOf { it.second } < cutFor(640.0 * 0.85))
            assertTrue("seed $seed late runs ${old.lateRuns}", old.lateRuns >= 3)
        }
    }

    @Test
    fun deliveryReportsCutOnceToWhatArrivedAndNeverCascade() {
        for (seed in listOf(7L, 11L)) for (stack in listOf(12_000, 48_000, 96_000)) {
            val (old, _) = simulate(false, false, a = Spec(stackBufferBytes = stack), seed = seed)
            val (fixed, reverse) = simulate(true, false, a = Spec(stackBufferBytes = stack), seed = seed)
            val label = "seed $seed stack $stack ${fixed.decreases}"
            // The large direction gets ~634 kbps of the 800 kbps link next to the 150 kbps reverse
            // direction: every cut lands at 0.85 x that (+/-10% link noise), never below it.
            assertTrue(label, fixed.decreases.all { it.second in cutFor(560.0)..cutFor(720.0) })
            assertEquals(label, 0, cascades(fixed))
            assertTrue(label, fixed.lateRuns <= 1)
            assertEquals(label, 0, reverse.decreases.size)
            // Late video after the first minute: never worse, and a third or less once the stack
            // buffers enough to hide the queue from the app (the measured GATT case).
            val factor = if (stack >= 48_000) 3 else 1
            assertTrue("$label late ${fixed.lateFramesAfter(60_000L)} vs ${old.lateFramesAfter(60_000L)}",
                fixed.lateFramesAfter(60_000L) * factor <= old.lateFramesAfter(60_000L))
        }
    }

    @Test
    fun deliveryProbingConvergesBelowTheLinkWithoutALimitCycle() {
        for (seed in listOf(7L, 11L)) for (stack in listOf(12_000, 48_000, 96_000)) {
            val (result, _) = simulate(true, true, a = Spec(stackBufferBytes = stack), seed = seed)
            val label = "seed $seed stack $stack late ${result.lateRuns}/${result.lateFramesAfter(60_000L)} 60+ ${result.targetsBetween(60_000L, 180_000L).let { "${it.min()}-${it.max()}" }} ${result.decreases}"
            // After convergence only undone probes (back to the cap just below the link) repeat; at most
            // two full cuts follow the first one. An undo and the cut right after it (the peer was
            // already late) are one episode; episodes stay at least 15 s apart.
            val settled = result.decreases.filter { it.first >= 20_000L }
            assertTrue(label, settled.count { it.second < cutFor(640.0 * 0.9 / 0.85) } <= 2)
            val episodes = result.decreases.filter { it.first >= 60_000L }.map { it.first }
                .fold(mutableListOf<Long>()) { starts, t -> starts.apply { if (isEmpty() || t - last() > 4_000L) add(t) } }
            assertTrue(label, episodes.zipWithNext().all { (x, y) -> y - x >= 15_000L })
            // A probe confirmed on link noise may release the ceiling once (one climb past the link
            // and one cut); it never becomes a cycle and never a late run.
            assertTrue(label, result.targetsBetween(60_000L, 180_000L).all { it in 480_000..760_000 })
            assertEquals(label, 0, result.lateRuns)
            assertTrue("$label late ${result.lateFramesAfter(60_000L)}", result.lateFramesAfter(60_000L) <= 20)
        }
    }

    @Test
    fun aSlowReturnPathIsNotForwardQueue() {
        // Review 2026-10-09: a steady 400-600 ms return delay collapsed this link to 185/80 kbps when
        // the queue was "written at report arrival minus received".
        for (probing in listOf(false, true)) for (extra in listOf(400L, 600L)) {
            val (result, _) = simulate(true, probing, extraReturnDelayToA = { extra }, durationMs = 120_000L)
            val label = "probing $probing return +$extra ms ${result.decreases}"
            assertTrue(label, result.targets.minOf { it.second } >= cutFor(640.0 * 0.85))
            assertTrue(label, result.targetsBetween(30_000L, 120_000L).average() >= 560_000.0)
            assertTrue(label, result.lateRuns <= 1)
        }
    }

    @Test
    fun aLightDirectionWithARisingReturnDelayNeverCuts() {
        // Review 2026-10-09: 200 kbps on a 2000 kbps link, the return delay rising 300 -> 1500 ms, cut
        // to the 26.7 kbps floor.
        val rising = { t: Long -> if (t < 30_000L) 300L + t * 1_200L / 30_000L else 1_500L }
        for (probing in listOf(false, true)) {
            val (result, _) = simulate(
                true, probing, a = Spec(requestBps = 200_000, startBps = 200_000, stackBufferBytes = 12_000),
                linkKbpsAt = { 2_000 }, extraReturnDelayToA = rising, durationMs = 90_000L,
            )
            assertEquals("probing $probing ${result.decreases}", 0, result.decreases.size)
        }
    }

    @Test
    fun twoLoadedDirectionsShareTheLinkWithoutCascadingToTheFloor() {
        val (oldA, oldB) = simulate(false, false, b = Spec(trainKbps = 770))
        val (a, b) = simulate(true, false, b = Spec(trainKbps = 770))
        for ((fixed, old) in listOf(a to oldA, b to oldB)) {
            // ~400 kbps each: never below 0.85 x 0.9 of that, against 210-225 kbps with the old rules.
            assertTrue("${fixed.decreases}", fixed.targets.minOf { it.second } >= cutFor(400.0 * 0.85))
            assertTrue("${fixed.lateFramesAfter(60_000L)} vs ${old.lateFramesAfter(60_000L)}",
                fixed.lateFramesAfter(60_000L) * 3 <= old.lateFramesAfter(60_000L) * 2)
        }
        val (pa, pb) = simulate(true, true, b = Spec(trainKbps = 770))
        for (probed in listOf(pa, pb)) {
            assertTrue("${probed.decreases}", probed.targetsBetween(60_000L, 180_000L).all { it >= 300_000 })
            assertTrue(probed.lateRuns <= 2)
            assertTrue("${probed.lateFramesAfter(60_000L)}", probed.lateFramesAfter(60_000L) <= 20)
        }
    }

    @Test
    fun aLinkThatDropsAndRecoversIsFollowedDownWithoutACascadeAndBackUp() {
        val link = { t: Long -> if (t in 60_000L until 120_000L) 560 else 800 }
        for (probing in listOf(false, true)) for (seed in listOf(7L, 11L)) {
            val (result, _) = simulate(true, probing, linkKbpsAt = link, seed = seed)
            val label = "probing $probing seed $seed ${result.decreases}"
            // ~394 kbps left for the large direction: down within 5 s to about 0.85 x that, no lower
            // than the noise allows, and back above the old cut point after the recovery.
            assertTrue(label, result.decreases.any { it.first in 60_000L..65_000L && it.second <= cutFor(440.0) })
            assertTrue(label, result.targetsBetween(60_000L, 120_000L).all { it >= 270_000 })
            assertTrue(label, result.targetsBetween(120_000L, 180_000L).max() >= 520_000)
        }
    }
}
