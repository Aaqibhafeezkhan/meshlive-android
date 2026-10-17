package com.example.bluetoothtest

import com.example.bluetoothtest.ClassicCutover.Action
import com.example.bluetoothtest.ClassicCutover.CloseReason
import com.example.bluetoothtest.ClassicCutover.Rec
import com.example.bluetoothtest.ClassicCutover.Wire
import java.util.ArrayDeque

/**
 * Two-endpoint world for the Design CX cutover model test (T-CX-M). It owns the ground truth:
 * two carrier slots per pair (the old link and up to [Cfg.maxAttempts] numbered new links used one
 * after another), a FIFO queue per sender and carrier with arbitrary delay between carriers,
 * carrier death at any step on either side with each side detecting it up to
 * [Cfg.detectMaxTicks] later (the half-open case), optionally a reader queue between the carriers
 * and the single consumer, the producers of media and of indispensable state, and the
 * invariants. The machine under test only ever sees what a phone would see.
 *
 * All nondeterminism is one integer action code, so a run is a list of codes: an exhaustive search
 * enumerates [enabled], a random run picks codes, and a counterexample is a code list that
 * [replayCutover] re-executes and [shrinkCutover] shrinks.
 */
internal class CutoverSim(val cfg: Cfg) {

    data class Cfg(
        val tickMs: Long,
        val machine: ClassicCutover.Config,
        /** Ticks in which anything may happen; the run then quiesces. */
        val horizonTicks: Int,
        /** Ticks of undisturbed time after quiescence (covers every machine timer). */
        val quietTicks: Int,
        /** A fault is detected by each side at most this many ticks later (the 2 s of PROTOCOL). */
        val detectMaxTicks: Int,
        /** Media records each endpoint seals, one per tick. */
        val mediaPerEndpoint: Int,
        val mediaBytes: Int = 1,
        /** State changes each endpoint makes at arbitrary times. */
        val stateChangesPerEndpoint: Int,
        /** Environment faults in the run: a carrier dies, or a side's watchdog closes it. */
        val faultBudget: Int,
        /** Numbered new carriers that may be attempted one after another. */
        val maxAttempts: Int,
        /** Chaos beyond the transport contract: duplicate or drop whole records on a live carrier. */
        val duplicates: Boolean = false,
        val drops: Boolean = false,
        /** Indispensable states per endpoint (lifecycle, microphone, camera); fewer shrinks the search. */
        val keys: Int = KEYS,
        /** Size of media record (endpoint, sequence) for runs with varied sizes; null: [mediaBytes]. */
        val mediaBytesOf: ((Int, Long) -> Int)? = null,
        /** Exhaustive search: an in-flight record must be delivered within this many ticks (-1: no bound). */
        val maxDelayTicks: Int = -1,
        /** Exhaustive search: false lets the run quiesce only at the end of the horizon. */
        val quiesceAnywhere: Boolean = true,
        /** A side's watchdog may close a healthy carrier (the same as a fault detected at once by that side). */
        val closeBy: Boolean = true,
        /** Exhaustive search: the first upgrade attempt starts by this tick. */
        val attemptByTick: Int = Int.MAX_VALUE,
        /** Exhaustive search: faults are injected only up to this tick. */
        val faultByTick: Int = Int.MAX_VALUE,
        /** Exhaustive search: `BIND`/`READY` complete on both sides the moment the attempt starts. */
        val upImmediately: Boolean = false,
        /** Exhaustive search: the world quiesces by itself when the horizon is reached. */
        val autoQuiesce: Boolean = false,
        /** Machine configuration of phone B when it differs from A (e.g. B never switches its own direction). */
        val machineB: ClassicCutover.Config? = null,
        /**
         * Reader threads and the single consumer are separate steps: a record read from a carrier
         * waits in the ordered content queue while the machine still handles carrier events, so the
         * consumer can meet records of a carrier that was closed in the meantime.
         */
        val readerQueue: Boolean = false,
        /** Exhaustive search: a queued record is consumed within this many ticks of being read (-1: no bound). */
        val maxConsumeLagTicks: Int = -1,
        /** Every n-th sealed record starts a frame: the sender may switch only there (1: every record). */
        val frameEvery: Int = 1,
        /** Invariant 8: a run without faults, duplicates or drops loses no media record. */
        val requireLossless: Boolean = false,
        /** A fault (or watchdog close) discards what is still in flight on that carrier, both ways. */
        val faultDropsInFlight: Boolean = false,
    )

    // ------------------------------------------------------------ world state
    var tick = 0
    var phase2 = false
    var quiesceTick = 0
    val m: Array<ClassicCutover> = Array(2) { ClassicCutover(if (it == 1) cfg.machineB ?: cfg.machine else cfg.machine) }
    private val slots = 1 + cfg.maxAttempts
    private val nextSeq = LongArray(2)
    private val mediaSealed = IntArray(2)
    private val stateChanges = IntArray(2)
    private val stateCur = Array(2) { IntArray(cfg.keys) }
    private val stateView = Array(2) { IntArray(cfg.keys) } // [receiver][key]: peer's state as opened here
    private val lastDelivered = longArrayOf(-1, -1)
    val ended = BooleanArray(2)
    private class Pkt(val wire: Wire, val sentTick: Int)

    /** An entry of the single consumer's queue: a record (ev 0) or a carrier event in reader order (1 died, 2 up, 3 pending). */
    private class Inb(val carrier: Int, val wire: Wire?, val readTick: Int, val ev: Int = 0)

    private val readerStopped = Array(2) { BooleanArray(slots) }

    private val inbound = Array(2) { ArrayDeque<Inb>() } // [receiver]: the ordered content queue

    private val chan = Array(2) { Array(slots) { ArrayDeque<Pkt>() } } // [sender][carrier]
    private val fault = IntArray(slots) { -1 }
    private val created = BooleanArray(slots).also { it[0] = true }
    private val pending = Array(2) { BooleanArray(slots) }
    private val up = Array(2) { BooleanArray(slots) }
    private val localDead = Array(2) { BooleanArray(slots) }
    private var faultsUsed = 0
    private var attempts = 0
    var violation: String? = null
        private set

    // ------------------------------------------------------------ measurements and trace
    private val sealedTag = Array(2) { ArrayList<Int>() }
    private val sealedAtTick = Array(2) { ArrayList<Int>() }
    private val deliveredMedia = Array(2) { ArrayList<Long>() } // [sender]: media seqs opened by the peer
    var droppedStale = 0
    var droppedOther = 0
    var voidWrites = 0
    var keyframes = 0
    var log: ArrayList<String>? = null

    /** When set, every applied action code is appended (to shrink a counterexample later). */
    var record: ArrayList<Int>? = null
    var switchWrites = 0
    var resumeWrites = 0

    /** Search coverage: how often each interesting machine action happened (shared by copies). */
    var cov: HashMap<String, Long>? = null

    private fun hit(key: String, n: Long = 1) {
        cov?.merge(key, n, Long::plus)
    }

    private var covSink: ((String) -> Unit)? = null

    fun enableCoverage(map: HashMap<String, Long>, sink: ((String) -> Unit)? = null) {
        cov = map
        val shared = sink ?: { key: String -> map.merge(key, 1L, Long::plus); Unit }
        covSink = shared
        for (x in 0..1) m[x].onStat = shared
    }

    private fun wireType(w: Wire) = when (w) {
        is Wire.Content -> 0
        is Wire.Switch -> 1
        is Wire.Resume -> 2
    }

    /** Media record sizes (endpoint, sequence); null means [Cfg.mediaBytes]. */
    var mediaBytesFn: ((Int, Long) -> Int)? = cfg.mediaBytesOf

    init {
        for (x in 0..1) run(x, m[x].onCallActive(0))
    }

    fun copy(): CutoverSim {
        val s = CutoverSim(cfg)
        s.tick = tick; s.phase2 = phase2; s.quiesceTick = quiesceTick
        for (x in 0..1) {
            s.m[x] = m[x].copy()
            s.nextSeq[x] = nextSeq[x]; s.mediaSealed[x] = mediaSealed[x]
            s.stateChanges[x] = stateChanges[x]
            System.arraycopy(stateCur[x], 0, s.stateCur[x], 0, cfg.keys)
            System.arraycopy(stateView[x], 0, s.stateView[x], 0, cfg.keys)
            s.lastDelivered[x] = lastDelivered[x]; s.ended[x] = ended[x]
            s.inbound[x].addAll(inbound[x])
            for (c in 0 until slots) {
                s.chan[x][c].addAll(chan[x][c])
                s.pending[x][c] = pending[x][c]; s.up[x][c] = up[x][c]
                s.localDead[x][c] = localDead[x][c]
                s.readerStopped[x][c] = readerStopped[x][c]
            }
        }
        for (c in 0 until slots) { s.fault[c] = fault[c]; s.created[c] = created[c] }
        s.faultsUsed = faultsUsed; s.attempts = attempts
        if (cov != null) s.enableCoverage(cov!!, covSink)
        for (x in 0..1) {
            s.sealedTag[x].addAll(sealedTag[x]); s.sealedAtTick[x].addAll(sealedAtTick[x])
            s.deliveredMedia[x].addAll(deliveredMedia[x])
        }
        return s
    }

    // ------------------------------------------------------------ actions
    companion object {
        private val WRITE_KEYS = listOf("content", "switch", "resume").map { t ->
            listOf("old", "new").map { c -> listOf("write-$t-on-$c", "write-$t-on-$c-into-dead") }
        }
        private val DROP_KEYS = ClassicCutover.DropReason.values().map { "drop-" + it.name.lowercase() }
        private val CLOSE_KEYS = CloseReason.values().map { "close-" + it.name.lowercase() }

        const val KEYS = 3 // 0 stream lifecycle (0 active, 1 paused, 2 ended), 1 microphone, 2 camera
        const val TICK = 0
        const val FAULT = 1 // a = carrier
        const val CLOSE_BY = 2 // a = endpoint (its watchdog closes), b = carrier
        const val DETECT = 3 // a = endpoint, b = carrier
        const val DELIVER = 4 // a = sender, b = carrier
        const val STATE_CHANGE = 5 // a = endpoint, b = key
        const val NEW_ATTEMPT = 6
        const val NEW_UP = 7 // a = endpoint
        const val QUIESCE = 8
        const val DUPLICATE = 9 // a = sender, b = carrier
        const val DROP = 10 // a = sender, b = carrier
        const val CONSUME = 11 // a = endpoint: the consumer takes the next queued record

        fun code(kind: Int, a: Int = 0, b: Int = 0) = kind * 64 + a * 8 + b
        fun kind(code: Int) = code / 64
        fun a(code: Int) = code / 8 % 8
        fun b(code: Int) = code % 8

        fun describe(code: Int): String = when (kind(code)) {
            TICK -> "tick"
            FAULT -> "fault: ${ClassicCutover.name(a(code))} dies"
            CLOSE_BY -> "watchdog of phone ${'A' + a(code)} closes ${ClassicCutover.name(b(code))}"
            DETECT -> "phone ${'A' + a(code)} detects ${ClassicCutover.name(b(code))} dead"
            DELIVER -> "deliver next ${ClassicCutover.name(b(code))} record from ${'A' + a(code)}"
            STATE_CHANGE -> "phone ${'A' + a(code)} changes state key ${b(code)}"
            NEW_ATTEMPT -> "upgrade attempt starts (new socket pending on both)"
            NEW_UP -> "phone ${'A' + a(code)} finishes BIND/READY on the new carrier"
            QUIESCE -> "quiesce (no new faults or changes)"
            DUPLICATE -> "duplicate next ${ClassicCutover.name(b(code))} record from ${'A' + a(code)}"
            DROP -> "drop next ${ClassicCutover.name(b(code))} record from ${'A' + a(code)}"
            CONSUME -> "consumer at ${'A' + a(code)} takes the next queued record"
            else -> "?"
        }
    }

    private fun latestNew() = attempts

    /** How long the oldest record still waiting for the old link has waited (the writer-queue fact the owner injects). */
    private fun queueAgeMs(x: Int): Long = chan[x][0].peek()?.let { (tick - it.sentTick) * cfg.tickMs } ?: 0L

    private fun atBoundary(x: Int): Boolean = cfg.frameEvery <= 1 || nextSeq[x] % cfg.frameEvery == 0L

    private fun discardInFlight(c: Int) {
        if (cfg.faultDropsInFlight) for (f in 0..1) chan[f][c].clear()
    }

    val attemptsStarted: Int get() = attempts

    fun canDeliver(from: Int, carrier: Int) = deliverable(from, carrier)

    fun headAgeTicks(from: Int, carrier: Int): Int = chan[from][carrier].peek()?.let { tick - it.sentTick } ?: 0

    private fun groundLive(c: Int): Boolean = created[c] && fault[c] < 0

    private fun anyGroundLive(except: Int = -1): Boolean = (0 until slots).any { it != except && groundLive(it) }

    private fun forcedDetection(): Boolean {
        for (c in 0 until slots) {
            if (fault[c] < 0) continue
            for (x in 0..1) {
                if (!ended[x] && !readerStopped[x][c] && tick + 1 - fault[c] > cfg.detectMaxTicks) return true
            }
        }
        return false
    }

    private fun forcedDelivery(): Boolean {
        if (cfg.maxDelayTicks < 0) return false
        for (c in 0 until slots) for (f in 0..1) {
            val head = chan[f][c].peek() ?: continue
            if (deliverable(f, c) && tick + 1 - head.sentTick > cfg.maxDelayTicks) return true
        }
        return false
    }

    private fun forcedConsume(): Boolean {
        if (!cfg.readerQueue || cfg.maxConsumeLagTicks < 0) return false
        for (x in 0..1) {
            val head = inbound[x].peek() ?: continue
            if (!ended[x] && tick + 1 - head.readTick > cfg.maxConsumeLagTicks) return true
        }
        return false
    }

    fun canConsume(x: Int) = cfg.readerQueue && !ended[x] && inbound[x].isNotEmpty()

    private fun deliverable(from: Int, c: Int): Boolean {
        val to = 1 - from
        return chan[from][c].isNotEmpty() && !readerStopped[to][c] && !ended[to] && (c == 0 || up[to][c])
    }

    private fun newUpEnabled(x: Int): Boolean {
        val c = latestNew()
        return c > 0 && !ended[x] && pending[x][c] && !up[x][c] && fault[c] < 0 && !readerStopped[x][c]
    }

    /** Appends every enabled action code to [outCodes] (cleared first). */
    fun enabled(outCodes: ArrayList<Int>) {
        outCodes.clear()
        var drain = false
        for (c in 0 until slots) for (f in 0..1) if (deliverable(f, c)) {
            outCodes.add(code(DELIVER, f, c)); drain = true
            if (cfg.duplicates) outCodes.add(code(DUPLICATE, f, c))
            if (cfg.drops) outCodes.add(code(DROP, f, c))
        }
        for (c in 0 until slots) for (x in 0..1) {
            if (fault[c] >= 0 && !readerStopped[x][c] && !ended[x] && (c == 0 || pending[x][c] || up[x][c])) {
                outCodes.add(code(DETECT, x, c))
            }
        }
        for (x in 0..1) if (newUpEnabled(x)) { outCodes.add(code(NEW_UP, x)); drain = true }
        for (x in 0..1) if (canConsume(x)) { outCodes.add(code(CONSUME, x)); drain = true }
        if (!phase2) {
            if (faultsUsed < cfg.faultBudget && tick <= cfg.faultByTick) {
                for (c in 0 until slots) if (created[c] && fault[c] < 0) {
                    outCodes.add(code(FAULT, c))
                    if (cfg.closeBy) for (x in 0..1) if (!ended[x] && !readerStopped[x][c] && (c == 0 || pending[x][c] || up[x][c])) {
                        outCodes.add(code(CLOSE_BY, x, c))
                    }
                }
            }
            for (x in 0..1) if (!ended[x] && stateChanges[x] < cfg.stateChangesPerEndpoint) {
                for (k in 0 until cfg.keys) if (nextValue(k, stateCur[x][k]) >= 0) outCodes.add(code(STATE_CHANGE, x, k))
            }
            if (attempts < cfg.maxAttempts && fault[0] < 0 && (attempts > 0 || tick <= cfg.attemptByTick) && (1..attempts).all { localDead[0][it] && localDead[1][it] }) {
                outCodes.add(code(NEW_ATTEMPT))
            }
            if (!cfg.autoQuiesce && (cfg.quiesceAnywhere || tick >= cfg.horizonTicks)) outCodes.add(code(QUIESCE))
        }
        if (phase2 && outCodes.isEmpty() && (0..1).none { !ended[it] && m[it].canStillChangeByTime }) return // settled
        val limit = if (phase2) quiesceTick + cfg.quietTicks else cfg.horizonTicks
        val tickOk = tick < limit && !forcedDetection() && !forcedDelivery() && !forcedConsume() && (!phase2 || !drain)
        if (tickOk) outCodes.add(code(TICK))
    }

    fun isEnabled(c: Int): Boolean {
        val tmp = ArrayList<Int>()
        enabled(tmp)
        return c in tmp
    }

    private fun nextValue(k: Int, v: Int): Int = if (k == 0) (if (v < 2) v + 1 else -1) else 1 - v

    // ------------------------------------------------------------ applying an action
    private val now get() = tick * cfg.tickMs

    fun apply(code: Int) {
        if (violation != null) return
        record?.add(code)
        log?.add("t=$tick ${describe(code)}")
        val a = a(code)
        val b = b(code)
        when (kind(code)) {
            TICK -> {
                tick += 1
                if (cfg.autoQuiesce && !phase2 && tick >= cfg.horizonTicks) { phase2 = true; quiesceTick = tick }
                for (x in 0..1) if (!ended[x]) run(x, m[x].onTick(now, queueAgeMs(x), atBoundary(x)))
                if (!phase2) for (x in 0..1) {
                    if (!ended[x] && mediaSealed[x] < cfg.mediaPerEndpoint) {
                        mediaSealed[x] += 1
                        seal(x, 0, mediaBytesFn?.invoke(x, nextSeq[x]) ?: cfg.mediaBytes)
                    }
                }
            }
            FAULT -> { fault[a] = tick; faultsUsed += 1; discardInFlight(a) }
            CLOSE_BY -> {
                fault[b] = tick; faultsUsed += 1; discardInFlight(b)
                readerStopped[a][b] = true
                if (cfg.readerQueue) inbound[a].add(Inb(b, null, tick, 1)) else { localDead[a][b] = true; run(a, m[a].onCarrierDied(b, now)) }
            }
            DETECT -> {
                readerStopped[a][b] = true
                if (cfg.readerQueue) inbound[a].add(Inb(b, null, tick, 1)) else { localDead[a][b] = true; run(a, m[a].onCarrierDied(b, now)) }
            }
            DELIVER -> {
                val w = chan[a][b].poll()!!.wire
                if (cfg.readerQueue) inbound[1 - a].add(Inb(b, w, tick)) else run(1 - a, m[1 - a].onArrive(b, w, now))
            }
            DUPLICATE -> {
                val w = chan[a][b].peek()!!.wire
                if (cfg.readerQueue) inbound[1 - a].add(Inb(b, w, tick)) else run(1 - a, m[1 - a].onArrive(b, w, now))
                // the original stays at the head: the transport "repeated" it
            }
            CONSUME -> {
                val item = inbound[a].poll()!!
                when (item.ev) {
                    0 -> run(a, m[a].onArrive(item.carrier, item.wire!!, now))
                    1 -> { localDead[a][item.carrier] = true; run(a, m[a].onCarrierDied(item.carrier, now)) }
                    2 -> run(a, m[a].onCarrierUp(item.carrier, now, queueAgeMs(a), atBoundary(a)))
                    else -> run(a, m[a].onCarrierPending(item.carrier, now))
                }
            }
            DROP -> chan[a][b].poll()
            STATE_CHANGE -> {
                stateChanges[a] += 1
                stateCur[a][b] = nextValue(b, stateCur[a][b])
                seal(a, 1 + b * 4 + stateCur[a][b], 1)
            }
            NEW_ATTEMPT -> {
                attempts += 1
                created[attempts] = true
                for (x in 0..1) {
                    pending[x][attempts] = true
                    if (cfg.readerQueue) inbound[x].add(Inb(attempts, null, tick, 3)) else run(x, m[x].onCarrierPending(attempts, now))
                }
                if (cfg.upImmediately) for (x in 0..1) {
                    up[x][attempts] = true; pending[x][attempts] = false
                    if (cfg.readerQueue) inbound[x].add(Inb(attempts, null, tick, 2)) else run(x, m[x].onCarrierUp(attempts, now, queueAgeMs(x), atBoundary(x)))
                }
            }
            NEW_UP -> {
                val c = latestNew()
                up[a][c] = true; pending[a][c] = false
                if (cfg.readerQueue) inbound[a].add(Inb(c, null, tick, 2)) else run(a, m[a].onCarrierUp(c, now, queueAgeMs(a), atBoundary(a)))
            }
            QUIESCE -> { phase2 = true; quiesceTick = tick }
        }
    }

    private fun seal(x: Int, tag: Int, bytes: Int) {
        if (ended[x]) return
        val seq = nextSeq[x]++
        sealedTag[x].add(tag)
        sealedAtTick[x].add(tick)
        run(x, m[x].onSealed(Rec(seq, bytes, tag), now))
    }

    private fun fail(msg: String) {
        if (violation == null) {
            violation = msg
            log?.add("VIOLATION: $msg")
        }
    }

    private fun run(x: Int, actions: List<Action>) {
        for (a in actions) {
            log?.add("    ${'A' + x}: $a")
            when (a) {
                is Action.Write -> {
                    val c = a.carrier
                    if (c < 0) continue
                    if (localDead[x][c]) fail("7 write on a carrier this phone already closed: ${'A' + x} $a")
                    else if (fault[c] >= 0) voidWrites += 1 // written into a dead socket: lost
                    else if (a.priority) chan[x][c].addFirst(Pkt(a.wire, tick))
                    else chan[x][c].add(Pkt(a.wire, tick))
                    if (a.wire is Wire.Switch) switchWrites += 1
                    if (a.wire is Wire.Resume) resumeWrites += 1
                    if (cov != null) {
                        hit(WRITE_KEYS[wireType(a.wire)][if (c == 0) 0 else 1][if (fault[c] >= 0) 1 else 0])
                    }
                }
                is Action.Deliver -> {
                    val p = 1 - x
                    val seq = a.rec.seq
                    if (localDead[x][a.carrier]) fail("6 closed carrier delivered ${a.rec} from ${ClassicCutover.name(a.carrier)} at ${'A' + x}")
                    if (seq <= lastDelivered[x]) fail("2 opener at ${'A' + x} fed ${a.rec} not above ${lastDelivered[x]}")
                    if (seq >= nextSeq[p] || sealedTag[p][seq.toInt()] != a.rec.tag) fail("3 ${a.rec} delivered at ${'A' + x} was never sealed like that")
                    lastDelivered[x] = seq
                    hit(if (a.carrier == 0) "deliver-from-old" else "deliver-from-new")
                    val tag = a.rec.tag
                    if (tag == 0) deliveredMedia[p].add(seq) else stateView[x][(tag - 1) / 4] = (tag - 1) % 4
                }
                is Action.Drop -> {
                    hit(DROP_KEYS[a.reason.ordinal])
                    if (a.reason == ClassicCutover.DropReason.STALE || a.reason == ClassicCutover.DropReason.FENCED) droppedStale += 1 else droppedOther += 1
                }
                is Action.CloseCarrier -> {
                    val c = a.carrier
                    hit(CLOSE_KEYS[a.reason.ordinal])
                    if (a.reason == CloseReason.HOLD_EXPIRED && !anyGroundLive(except = c)) {
                        fail("1 phone ${'A' + x} closed carrier ${ClassicCutover.name(c)} by policy ($a) while every other carrier was already dead")
                    }
                    if (a.reason == CloseReason.PEER_OLD_DEAD && fault[c] < 0) {
                        fail("1 phone ${'A' + x} closed a healthy ${ClassicCutover.name(c)} on the peer's hint ($a)")
                    }
                    localDead[x][c] = true
                    readerStopped[x][c] = true
                    if (fault[c] < 0) { fault[c] = tick; discardInFlight(c) }
                }
                Action.ReassertState -> {
                    hit("reassert")
                    for (k in 0 until cfg.keys) seal(x, 1 + k * 4 + stateCur[x][k], 1)
                }
                Action.ForceLocalKeyframe, Action.RequestPeerKeyframe -> keyframes += 1
                Action.ResetSendFragments -> hit("reset-send-fragments")
                Action.SuppressFragments -> hit("suppress-fragments")
                is Action.EndSession -> {
                    hit("end-session")
                    ended[x] = true
                    if (anyGroundLive()) {
                        val live = (0 until slots).filter { groundLive(it) }.joinToString { ClassicCutover.name(it) }
                        fail("1 phone ${'A' + x} ended the session ($a) while carrier(s) $live lived")
                    }
                }
            }
            if (violation != null) return
        }
    }

    // ------------------------------------------------------------ final checks, hashing, replay
    /** Quiescent final state: invariants 4 and 5 (and 1 again). */
    fun checkFinal() {
        if (violation != null) return
        val live = (0 until slots).filter { groundLive(it) }
        if (live.isEmpty()) {
            for (x in 0..1) if (!ended[x]) fail("1b all carriers dead but phone ${'A' + x} has not ended after the quiet period")
            return
        }
        for (x in 0..1) if (ended[x]) fail("1 phone ${'A' + x} ended while carrier(s) lived")
        if (cfg.requireLossless && faultsUsed == 0 && !cfg.duplicates && !cfg.drops) {
            for (x in 0..1) {
                val st = mediaStats(x)
                if (st.opened != st.sealed) fail("8 normal switch lost media: phone ${'A' + x} sealed ${st.sealed}, the peer opened ${st.opened}")
            }
        }
        if (cfg.drops) return // beyond the transport contract only the safety invariants are meaningful
        for (x in 0..1) {
            val y = 1 - x
            val s = m[x].sendOn
            val r = m[y].accept
            if (s != r || s < 0 || !groundLive(s)) {
                fail("5 no convergence ${'A' + x}->${'A' + y}: sendOn=${ClassicCutover.name(s)} acceptFrom=${ClassicCutover.name(r)} live=${live.joinToString { ClassicCutover.name(it) }}")
            }
            if (!cfg.drops) for (k in 0 until cfg.keys) {
                if (stateView[y][k] != stateCur[x][k]) {
                    fail("4 state key $k: ${'A' + x} holds ${stateCur[x][k]} but ${'A' + y} opened ${stateView[y][k]}")
                }
            }
        }
    }

    fun hash(): Long {
        val h = longArrayOf(-3750763034362895579L)
        fun add(v: Long) { h[0] = (h[0] xor v) * 1_099_511_628_211L }
        add(tick.toLong()); add(if (phase2) quiesceTick + 1L else 0L)
        add(faultsUsed.toLong()); add(attempts.toLong())
        for (x in 0..1) {
            m[x].hashInto(h)
            add(nextSeq[x]); add(mediaSealed[x].toLong()); add(stateChanges[x].toLong())
            add(lastDelivered[x]); add(if (ended[x]) 1 else 0)
            if (cfg.requireLossless) add(deliveredMedia[x].size.toLong()) // invariant 8 depends on history
            for (k in 0 until cfg.keys) { add(stateCur[x][k].toLong()); add(stateView[x][k].toLong()) }
            for (c in 0 until slots) {
                add((if (pending[x][c]) 1 else 0) + (if (up[x][c]) 2 else 0) + (if (localDead[x][c]) 4 else 0) + (if (readerStopped[x][c]) 8 else 0).toLong())
                add(chan[x][c].size.toLong())
                for (pkt in chan[x][c]) {
                    val w = pkt.wire
                    if (cfg.maxDelayTicks >= 0) add(minOf(tick - pkt.sentTick, cfg.maxDelayTicks + 1).toLong())
                    when (w) {
                        is Wire.Content -> { add(1); add(w.rec.seq); add(w.rec.tag.toLong()) }
                        is Wire.Switch -> { add(2); add(w.gen); add(w.boundary); add(w.carrier.toLong()) }
                        is Wire.Resume -> { add(3); add(w.gen); add(w.next); add(w.carrier.toLong()) }
                    }
                }
            }
        }
        for (x in 0..1) {
            add(inbound[x].size.toLong())
            for (q in inbound[x]) {
                if (cfg.maxConsumeLagTicks >= 0) add(minOf(tick - q.readTick, cfg.maxConsumeLagTicks + 1).toLong())
                add(q.carrier.toLong()); add(q.ev.toLong())
                when (val w = q.wire) {
                    null -> add(0)
                    is Wire.Content -> { add(1); add(w.rec.seq); add(w.rec.tag.toLong()) }
                    is Wire.Switch -> { add(2); add(w.gen); add(w.boundary); add(w.carrier.toLong()) }
                    is Wire.Resume -> { add(3); add(w.gen); add(w.next); add(w.carrier.toLong()) }
                }
            }
        }
        for (c in 0 until slots) { add(fault[c].toLong()); add(if (created[c]) 1 else 0) }
        return h[0]
    }

    /** Media records sealed by [sender] / opened by the peer, and the longest gap between two opened ones. */
    fun mediaStats(sender: Int): MediaStats {
        val opened = deliveredMedia[sender]
        var prevTick = -1
        var maxGap = 0
        for (seq in opened) {
            val t = sealedAtTick[sender][seq.toInt()]
            if (prevTick >= 0) maxGap = maxOf(maxGap, t - prevTick)
            prevTick = t
        }
        val sealedMedia = sealedTag[sender].count { it == 0 }
        return MediaStats(sealedMedia, opened.size, maxGap)
    }

    class MediaStats(val sealed: Int, val opened: Int, val maxGapTicks: Int)

    /** Runs the deterministic completion: quiesce, then the first enabled action until none. */
    fun complete() {
        val buf = ArrayList<Int>()
        if (!phase2) apply(code(QUIESCE))
        while (violation == null) {
            enabled(buf)
            if (buf.isEmpty()) break
            // prefer draining and detecting over passing time
            val pick = buf.firstOrNull { kind(it) == DELIVER || kind(it) == CONSUME || kind(it) == DETECT || kind(it) == NEW_UP } ?: buf.first()
            apply(pick)
        }
        checkFinal()
    }

    fun codesEnabledNone(): Boolean {
        val buf = ArrayList<Int>()
        enabled(buf)
        return buf.isEmpty()
    }
}

/** Replays [codes] on a fresh world; null when an action was not enabled (an invalid trace). */
internal fun replayCutover(cfg: CutoverSim.Cfg, codes: List<Int>, trace: Boolean = false): CutoverSim? {
    val s = CutoverSim(cfg)
    if (trace) s.log = ArrayList()
    for (c in codes) {
        if (s.violation != null) break
        if (!s.isEnabled(c)) return null
        s.apply(c)
    }
    if (s.violation == null) s.complete()
    return s
}

/** Delta-debugging shrink: drop chunks of actions while the same invariant family keeps failing. */
internal fun shrinkCutover(cfg: CutoverSim.Cfg, codes: List<Int>, family: String): List<Int> {
    var cur = codes
    var chunk = maxOf(1, cur.size / 2)
    while (true) {
        var changed = false
        var i = 0
        while (i < cur.size) {
            val cand = cur.subList(0, i) + cur.subList(minOf(cur.size, i + chunk), cur.size)
            val r = replayCutover(cfg, cand)
            if (r != null && r.violation?.startsWith(family) == true) {
                cur = cand; changed = true
            } else {
                i += chunk
            }
        }
        if (chunk == 1 && !changed) return cur
        if (chunk > 1) chunk = maxOf(1, chunk / 2)
    }
}

internal class LongSet {
    private var table = LongArray(1 shl 16) // 0 marks an empty slot
    var size = 0L
        private set

    fun add(value: Long): Boolean {
        val v = if (value == 0L) 1L else value
        if (size * 2 >= table.size) grow()
        return insert(table, v).also { if (it) size += 1 }
    }

    private fun insert(t: LongArray, v: Long): Boolean {
        var i = (v xor (v ushr 29)).toInt() and (t.size - 1)
        while (t[i] != 0L) {
            if (t[i] == v) return false
            i = (i + 1) and (t.size - 1)
        }
        t[i] = v
        return true
    }

    private fun grow() {
        val old = table
        table = LongArray(old.size * 2)
        for (v in old) if (v != 0L) insert(table, v)
    }
}

internal class Explored(
    val states: Long,
    val terminals: Long,
    val violation: String?,
    val trace: List<Int>?,
    val capped: Boolean = false,
    val coverage: Map<String, Long> = emptyMap(),
)

internal fun exploreCutover(cfg: CutoverSim.Cfg, maxStates: Long = 6_000_000L): Explored {
    val seen = LongSet()
    val path = ArrayList<Int>()
    var terminals = 0L
    var found: String? = null
    var foundTrace: List<Int>? = null
    var capped = false
    val coverage = HashMap<String, Long>()

    fun dfs(s: CutoverSim) {
        if (found != null || capped) return
        if (!seen.add(s.hash())) return
        if (seen.size > maxStates) { capped = true; return }
        val codes = ArrayList<Int>()
        s.enabled(codes)
        if (codes.isEmpty()) {
            terminals += 1
            s.checkFinal()
            if (s.violation != null) { found = s.violation; foundTrace = ArrayList(path) }
            return
        }
        for (c in codes) {
            val child = s.copy()
            child.apply(c)
            path.add(c)
            if (child.violation != null) {
                found = child.violation; foundTrace = ArrayList(path)
                return
            }
            dfs(child)
            if (found != null) return
            path.removeAt(path.size - 1)
        }
    }

    var failure: Throwable? = null
    val t = Thread(null, {
        try { dfs(CutoverSim(cfg).also { it.enableCoverage(coverage) }) } catch (e: Throwable) { failure = e }
    }, "cx-dfs", 512L * 1024 * 1024)
    t.start(); t.join()
    failure?.let { throw it }
    return Explored(seen.size, terminals, found, foundTrace, capped, coverage)
}
