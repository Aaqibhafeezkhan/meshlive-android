package com.example.bluetoothtest

/**
 * Design CX stage 0: the pure carrier-cutover state machine for the Classic upgrade (default off,
 * not wired into the app; PROTOCOL.md "Cutover and fallback: lossy by design, never fatal" and
 * "Implementation stage 0", DECISIONS.md "Design CX" and "Stage 0 model findings").
 *
 * One instance is one phone's view of one session: it has two carriers, the old link [OLD] and at
 * most one new link at a time (an id from 1 to 62, a per-session attempt counter that never
 * recurs; the owner maps it to the 8-byte upgrade ticket that `SWITCH` and `RESUME` carry on the
 * wire), and it covers both directions: the sender state `sendOn` for what this phone sends and
 * the receiver state `accept` for what it opens. There are no Android types and no threads (the
 * owner serialises all calls on the single consumer thread; carrier-up and carrier-died events
 * enter the same ordered queue as the records, in reader order relative to that carrier's
 * content): every input carries the injected clock (`nowMs`) and returns the actions the carrier
 * owner must perform. The inputs are [onSealed], [onArrive], [onCarrierPending], [onCarrierUp],
 * [onCarrierDied], [onCallActive] and [onTick]; the owner also injects two facts about its writer
 * with the inputs that can start a switch: `oldQueueAgeMs` (how long the oldest record still
 * waiting for the old link has waited) and `atFrameBoundary` (the next sealed record starts a
 * frame). The actions are [Action.Write], [Action.Deliver], [Action.Drop],
 * [Action.CloseCarrier], [Action.ReassertState], [Action.ForceLocalKeyframe],
 * [Action.RequestPeerKeyframe], [Action.ResetSendFragments], [Action.SuppressFragments] and
 * [Action.EndSession]. The owner starts a new attempt ([onCarrierPending]) only while no earlier
 * one is alive in its view.
 *
 * Root invariant: a race may lose records but must never end a live session. Records are dropped,
 * never thrown at the opener: anything not strictly above the last opened sequence is stale. No
 * rule closes a carrier that is the last one alive.
 *
 * What the model test (`ClassicCutoverModelTest`) and the reviews found wrong in the round 6
 * PROTOCOL draft is kept switchable in [Config] ([Config.asFirstDraft] turns every correction
 * back) so that the counterexamples stay reproducible; the defaults are the corrected behaviour.
 */
internal class ClassicCutover(private val config: Config = Config()) {

    enum class HoldExpiry {
        /** Fixed: a stuck `SWITCH` makes the receiver take the new carrier; no carrier is closed. */
        PROMOTE,

        /** First draft: drop the held records and close the new carrier (can end a live session). */
        CLOSE_NEW,
    }

    data class Config(
        /** Ceiling for early new-carrier records before their `SWITCH` (PROTOCOL: 1 s). */
        val holdMs: Long = 1_000L,
        /** Ceiling for the same records in bytes (PROTOCOL: 5 MiB). */
        val holdBytes: Long = 5L * 1024 * 1024,
        /** The call must have been active this long before the sender switches (PROTOCOL: 3 s). */
        val activeBeforeSwitchMs: Long = 3_000L,
        /** After a failure of the new carrier no new switch starts for this long (PROTOCOL: 30 s). */
        val cooldownMs: Long = 30_000L,
        /** A switch starts only with fewer fallbacks than this in the session (PROTOCOL: 2). */
        val maxFallbacks: Int = 2,
        /** An upgrade attempt that has not become ready after this long is closed (PROTOCOL deadline table: 5 s). */
        val pendingMaxMs: Long = 5_000L,
        val holdExpiry: HoldExpiry = HoldExpiry.PROMOTE,
        /** Fixed: losing [OLD] while a new carrier is still being set up waits for it. */
        val pendingKeepsSession: Boolean = true,
        /** Fixed: a `SWITCH` that arrives while this side's new carrier is still pending is kept. */
        val switchWaitsForPendingNew: Boolean = true,
        /** Fixed: `SWITCH` and `RESUME` name the new-carrier instance they are about; [ANY] is burned. */
        val markersNameCarrier: Boolean = true,
        /**
         * Fixed: `SWITCH` waits in the old link's ordinary media FIFO, behind the records sealed
         * before the boundary. The draft let control lanes overtake, which at a normal switch let
         * the new carrier's records pass the old link's backlog and made the backlog stale.
         */
        val switchThroughMediaFifo: Boolean = true,
        /** Fixed: no normal switch while the old link's oldest queued record has waited `holdMs / 2` or more. */
        val gateSwitchOnOldQueue: Boolean = true,
        /** Fixed: `RESUME.next` is kept as the floor of what the old link may deliver afterwards. */
        val resumeSetsOldFloor: Boolean = true,
        /**
         * Half-open reduction (b): a new carrier that dies while `O` lives always gets a `RESUME`
         * naming it, whatever `SEND_ON` was, and the receiver closes the named carrier even when it
         * is not (yet) accepting it.
         */
        val resumeAlwaysWritten: Boolean = true,
        /**
         * Half-open reduction (a): a `SWITCH` that arrives on the new carrier proves that the
         * sender closed the old link, so this phone closes it too instead of waiting for its own timeout.
         */
        val oldDeadHint: Boolean = true,
    ) {
        /** The behaviour exactly as the first PROTOCOL draft (round 6) described it. */
        fun asFirstDraft(): Config = copy(
            pendingMaxMs = 10_000L,
            holdExpiry = HoldExpiry.CLOSE_NEW,
            pendingKeepsSession = false,
            switchWaitsForPendingNew = false,
            markersNameCarrier = false,
            switchThroughMediaFifo = false,
            gateSwitchOnOldQueue = false,
            resumeSetsOldFloor = false,
            resumeAlwaysWritten = false,
            oldDeadHint = false,
        )
    }

    /** One sealed record: the sender's sequence, its size and an opaque owner tag. */
    class Rec(val seq: Long, val bytes: Int, val tag: Int) {
        override fun toString() = "rec#$seq"
    }

    /** What travels on a carrier: media or state content, or one of the two in-band markers. */
    sealed class Wire {
        class Content(val rec: Rec) : Wire() {
            override fun toString() = rec.toString()
        }

        /** [carrier] is the new-carrier instance the marker is about, or [ANY] in the first draft. */
        class Switch(val gen: Long, val boundary: Long, val carrier: Int) : Wire() {
            override fun toString() = "SWITCH(g$gen,b$boundary,${name(carrier)})"
        }

        class Resume(val gen: Long, val next: Long, val carrier: Int) : Wire() {
            override fun toString() = "RESUME(g$gen,next$next,${name(carrier)})"
        }
    }

    enum class DropReason { CLOSED_CARRIER, STALE, FENCED, STALE_MARKER, UNKNOWN_CARRIER, NO_CARRIER }

    enum class CloseReason {
        /** The first draft's hold expiry: a policy decision, not a carrier failure. */
        HOLD_EXPIRED,

        /** The peer wrote `RESUME`: its new carrier is gone, so clean up this end. */
        PEER_RESUMED,

        /** The upgrade attempt did not become ready in time. */
        HANDSHAKE_TIMEOUT,

        /** The peer's `SWITCH` arrived on the new carrier: it closed the old link, so this end is dead too. */
        PEER_OLD_DEAD,
    }

    sealed class Action {
        /** [priority]: the owner may serve this write before queued media (only the first draft's `SWITCH`). */
        class Write(val carrier: Int, val wire: Wire, val priority: Boolean = false) : Action() {
            override fun toString() = "write $wire on ${name(carrier)}${if (priority) " (priority)" else ""}"
        }

        class Deliver(val rec: Rec, val carrier: Int) : Action() {
            override fun toString() = "deliver $rec from ${name(carrier)}"
        }

        class Drop(val reason: DropReason, val wire: Wire?, val carrier: Int) : Action() {
            override fun toString() = "drop $wire from ${name(carrier)} ($reason)"
        }

        class CloseCarrier(val carrier: Int, val reason: CloseReason) : Action() {
            override fun toString() = "close ${name(carrier)} ($reason)"
        }

        /** Seal the current value of every indispensable state under fresh sequences. */
        object ReassertState : Action() {
            override fun toString() = "reassert state"
        }

        /** This phone's own encoder must produce a keyframe next (its direction moved carrier). */
        object ForceLocalKeyframe : Action() {
            override fun toString() = "force local keyframe"
        }

        /** Ask the peer's encoder for a keyframe (this phone's receiving direction has a gap). */
        object RequestPeerKeyframe : Action() {
            override fun toString() = "request peer keyframe"
        }

        /** Drop the unsent fragments of the frame being sent; the next frame starts at fragment 0. */
        object ResetSendFragments : Action() {
            override fun toString() = "reset send fragments"
        }

        /** Drop the fragment reassembler's partial frame and skip fragments until offset 0. */
        object SuppressFragments : Action() {
            override fun toString() = "suppress fragments"
        }

        class EndSession(val reason: String) : Action() {
            override fun toString() = "END SESSION ($reason)"
        }
    }

    // Local view of the carriers.
    private var oldAlive = true
    private var newId = NONE
    private var pendingId = NONE
    private var pendingSinceMs = 0L
    private var deadMask = 0L

    var ended = false
        private set

    // Sender (what this phone writes).
    var sendOn = OLD
        private set
    private var gen = 0L
    private var lastSwitchGen = 0L
    private var lastSwitchBoundary = 0L
    private var lastSealed = -1L
    private var fallbacks = 0
    private var cooldownUntilMs = 0L
    private var activeSinceMs = NOT_ACTIVE

    // Receiver (what this phone opens).
    var accept = OLD
        private set
    private var boundary = 0L

    /** After a `RESUME` the old link delivers only records from this sequence on. */
    private var oldFrom = 0L
    var lastOpened = -1L
        private set
    private var maxGen = 0L
    private var seenSwitchGen = 0L
    private var seenSwitchBoundary = 0L
    private var pendingSwitchBoundary = -1L
    private var pendingSwitchCarrier = NONE
    private var held = ArrayList<Rec>()
    private var heldBytes = 0L
    private var holdSinceMs = 0L

    private var out = ArrayList<Action>()

    /** True while the machine waits for a timer (hold, upgrade attempt) that [onTick] will act on. */
    val hasTimer: Boolean get() = held.isNotEmpty() || pendingId != NONE

    /** True while a timer or a still-possible switch can change the carriers by itself. */
    val canStillChangeByTime: Boolean
        get() = !ended && (hasTimer || sendOn == OLD && oldAlive && newId != NONE && fallbacks < config.maxFallbacks)

    val oldIsAlive: Boolean get() = oldAlive
    val newCarrier: Int get() = newId
    val pendingCarrier: Int get() = pendingId
    val heldCount: Int get() = held.size
    val fallbackCount: Int get() = fallbacks

    /** How often the hold ceiling was reached (statistics only). */
    var holdExhaustions = 0
        private set

    /** Statistics hook for the model test's coverage report; never used by the app. */
    var onStat: ((String) -> Unit)? = null

    private fun stat(key: String) {
        onStat?.invoke(key)
    }

    fun copy(): ClassicCutover {
        val c = ClassicCutover(config)
        c.oldAlive = oldAlive; c.newId = newId; c.pendingId = pendingId
        c.pendingSinceMs = pendingSinceMs; c.deadMask = deadMask; c.ended = ended
        c.sendOn = sendOn; c.gen = gen; c.lastSwitchGen = lastSwitchGen
        c.lastSwitchBoundary = lastSwitchBoundary; c.lastSealed = lastSealed
        c.fallbacks = fallbacks; c.cooldownUntilMs = cooldownUntilMs; c.activeSinceMs = activeSinceMs
        c.accept = accept; c.boundary = boundary; c.oldFrom = oldFrom; c.lastOpened = lastOpened; c.maxGen = maxGen
        c.seenSwitchGen = seenSwitchGen; c.seenSwitchBoundary = seenSwitchBoundary
        c.pendingSwitchBoundary = pendingSwitchBoundary; c.pendingSwitchCarrier = pendingSwitchCarrier
        c.held = ArrayList(held); c.heldBytes = heldBytes; c.holdSinceMs = holdSinceMs
        c.holdExhaustions = holdExhaustions
        c.onStat = onStat
        return c
    }

    /** Mixes the whole state into [h] (model checking: equal states have equal hashes). */
    fun hashInto(h: LongArray) {
        fun add(v: Long) {
            h[0] = (h[0] xor v) * 1_099_511_628_211L
        }
        add(if (oldAlive) 1 else 0); add(newId.toLong()); add(pendingId.toLong())
        add(pendingSinceMs); add(deadMask); add(if (ended) 1 else 0)
        add(sendOn.toLong()); add(gen); add(lastSwitchGen); add(lastSwitchBoundary); add(lastSealed)
        add(fallbacks.toLong()); add(cooldownUntilMs); add(activeSinceMs)
        add(accept.toLong()); add(boundary); add(oldFrom); add(lastOpened); add(maxGen); add(seenSwitchGen)
        add(seenSwitchBoundary); add(pendingSwitchBoundary); add(pendingSwitchCarrier.toLong()); add(heldBytes); add(holdSinceMs)
        add(held.size.toLong())
        for (r in held) add(r.seq)
    }

    // ---------------------------------------------------------------- inputs

    /** The call became (or is) active: starts the clock for the 3 s rule. */
    fun onCallActive(nowMs: Long, oldQueueAgeMs: Long = 0L, atFrameBoundary: Boolean = true): List<Action> {
        if (activeSinceMs == NOT_ACTIVE) activeSinceMs = nowMs
        maybeSwitch(nowMs, oldQueueAgeMs, atFrameBoundary)
        return drain()
    }

    /** The owner sealed [rec] (its sequence is above every earlier one); it goes to `sendOn`. */
    fun onSealed(rec: Rec, nowMs: Long): List<Action> {
        if (ended) return drain()
        require(rec.seq > lastSealed) { "sequences must increase: ${rec.seq} after $lastSealed" }
        lastSealed = rec.seq
        if (sendOn == NONE) out.add(Action.Drop(DropReason.NO_CARRIER, Wire.Content(rec), NONE))
        else out.add(Action.Write(sendOn, Wire.Content(rec)))
        return drain()
    }

    /** An upgrade attempt has started (the new socket exists, `BIND` and `READY` are not done). */
    fun onCarrierPending(carrier: Int, nowMs: Long): List<Action> {
        require(carrier in 1..62) { "attempt ids are small per-session counters" }
        if (ended || isDead(carrier)) return drain()
        pendingId = carrier
        pendingSinceMs = nowMs
        pendingSwitchBoundary = -1L
        return drain()
    }

    /** The new carrier finished `BIND` and `READY` on this side. */
    fun onCarrierUp(carrier: Int, nowMs: Long, oldQueueAgeMs: Long = 0L, atFrameBoundary: Boolean = true): List<Action> {
        require(carrier != OLD)
        if (ended || isDead(carrier)) return drain()
        if (pendingId == carrier) pendingId = NONE
        newId = carrier
        if (!oldAlive) {
            onlyNewCarrierLeft()
        } else {
            if (pendingSwitchBoundary >= 0 && accept == OLD && (pendingSwitchCarrier == carrier || pendingSwitchCarrier == ANY)) {
                // The peer's SWITCH came on the old link before this side's READY exchange ended;
                // its new-carrier records follow the peer's READY, so they will not be held.
                accept = carrier
                boundary = pendingSwitchBoundary
                stat("switch-applied-at-ready")
                out.add(Action.SuppressFragments)
            }
            pendingSwitchBoundary = -1L
            maybeSwitch(nowMs, oldQueueAgeMs, atFrameBoundary)
        }
        return drain()
    }

    /** A carrier failed or was closed by the peer, as seen by this phone (a local detection). */
    fun onCarrierDied(carrier: Int, nowMs: Long): List<Action> {
        if (!ended) carrierGone(carrier, nowMs)
        return drain()
    }

    /** One record or marker was read from [carrier] and queued in order for the single consumer. */
    fun onArrive(carrier: Int, wire: Wire, nowMs: Long): List<Action> {
        if (ended) return drain()
        if (isDead(carrier)) {
            out.add(Action.Drop(DropReason.CLOSED_CARRIER, wire, carrier))
            return drain()
        }
        when (wire) {
            is Wire.Content -> arriveContent(carrier, wire.rec, nowMs)
            is Wire.Switch -> arriveSwitch(carrier, wire, nowMs)
            is Wire.Resume -> arriveResume(carrier, wire, nowMs)
        }
        return drain()
    }

    /** Timers: the hold ceiling, the upgrade-attempt ceiling and the sender's switch conditions. */
    fun onTick(nowMs: Long, oldQueueAgeMs: Long = 0L, atFrameBoundary: Boolean = true): List<Action> {
        if (ended) return drain()
        if (held.isNotEmpty() && nowMs - holdSinceMs >= config.holdMs) holdExhausted(nowMs)
        if (!ended && pendingId != NONE && nowMs - pendingSinceMs >= config.pendingMaxMs) {
            val c = pendingId
            out.add(Action.CloseCarrier(c, CloseReason.HANDSHAKE_TIMEOUT))
            carrierGone(c, nowMs)
        }
        if (!ended) maybeSwitch(nowMs, oldQueueAgeMs, atFrameBoundary)
        return drain()
    }

    // ---------------------------------------------------------------- sender rows

    /** Sender row 1: the new carrier is ready and the conditions hold, so switch. */
    private fun maybeSwitch(nowMs: Long, oldQueueAgeMs: Long, atFrameBoundary: Boolean) {
        if (ended || sendOn != OLD || !oldAlive || newId == NONE) return
        if (activeSinceMs == NOT_ACTIVE || nowMs - activeSinceMs < config.activeBeforeSwitchMs) return
        if (nowMs < cooldownUntilMs || fallbacks >= config.maxFallbacks) return
        if (!atFrameBoundary) {
            stat("switch-waits-for-frame")
            return
        }
        // A backlog on the old link must drain behind the SWITCH marker's own FIFO position; a
        // long one would make the receiver's hold run out and abandon the old link's tail.
        if (config.gateSwitchOnOldQueue && oldQueueAgeMs >= config.holdMs / 2) {
            stat("switch-waits-for-old-queue")
            return
        }
        gen += 1
        lastSwitchGen = gen
        lastSwitchBoundary = lastSealed + 1
        out.add(Action.Write(OLD, Wire.Switch(gen, lastSwitchBoundary, marker(newId)), priority = !config.switchThroughMediaFifo))
        sendOn = newId
        out.add(Action.ReassertState)
    }

    /** Sender row 3 and receiver row 4: the old link is gone and a new carrier is ready. */
    private fun onlyNewCarrierLeft() {
        if (sendOn != newId) {
            gen += 1
            lastSwitchGen = gen
            lastSwitchBoundary = lastSealed + 1
            // The boundary may fall inside a frame: its unsent fragments belong to the dead link.
            out.add(Action.ResetSendFragments)
        }
        // Whether on the old link before or already on the new one, the same generation and
        // boundary are (re)written: the earlier SWITCH may have been lost with the old link.
        sendOn = newId
        out.add(Action.Write(newId, Wire.Switch(lastSwitchGen, lastSwitchBoundary, marker(newId))))
        out.add(Action.ReassertState)
        if (accept != newId) {
            accept = newId
            boundary = lastOpened + 1
            out.add(Action.SuppressFragments)
            out.add(Action.RequestPeerKeyframe)
            releaseHeld()
        }
        pendingSwitchBoundary = -1L
    }

    /** Whatever carrier [carrier] was, this phone no longer has it. Shared by every way of dying. */
    private fun carrierGone(carrier: Int, nowMs: Long) {
        if (isDead(carrier)) return
        val known = carrier == OLD || carrier == newId || carrier == pendingId
        deadMask = deadMask or (1L shl carrier)
        if (!known) return
        when (carrier) {
            OLD -> {
                oldAlive = false
                if (newId != NONE) {
                    stat("old-died-new-left")
                    onlyNewCarrierLeft()
                } else if (pendingId != NONE && config.pendingKeepsSession) {
                    stat("old-died-waiting-for-pending")
                    // The upgrade attempt may still become the carrier: wait for it, bounded by
                    // the attempt's own ceiling. Nothing can be written or opened until then.
                    sendOn = NONE
                    accept = NONE
                    held.clear(); heldBytes = 0
                } else {
                    end("no carrier left")
                }
            }
            pendingId -> {
                pendingId = NONE
                pendingSwitchBoundary = -1L
                if (!oldAlive) end("no carrier left")
            }
            else -> newCarrierGone(carrier, nowMs)
        }
    }

    /** Sender row 2 and receiver row 5: the new carrier failed or was closed. */
    private fun newCarrierGone(carrier: Int, nowMs: Long) {
        newId = NONE
        held.clear(); heldBytes = 0
        pendingSwitchBoundary = -1L
        if (!oldAlive) {
            end("no carrier left")
            return
        }
        if (sendOn == carrier) {
            stat("new-died-fell-back")
            sendOn = OLD
            gen += 1
            fallbacks += 1
            out.add(Action.Write(OLD, Wire.Resume(gen, lastSealed + 1, marker(carrier))))
            out.add(Action.ResetSendFragments)
            out.add(Action.ForceLocalKeyframe)
            out.add(Action.ReassertState)
        } else if (config.resumeAlwaysWritten) {
            // This direction never moved (or moved back already), but the peer may still be
            // accepting or holding the dead carrier: tell it on the live link.
            stat("new-died-resume-notice")
            gen += 1
            out.add(Action.Write(OLD, Wire.Resume(gen, lastSealed + 1, marker(carrier))))
        }
        cooldownUntilMs = nowMs + config.cooldownMs
        if (accept == carrier) {
            accept = OLD
            boundary = 0
            out.add(Action.SuppressFragments)
            out.add(Action.RequestPeerKeyframe)
        }
    }

    // ---------------------------------------------------------------- receiver rows

    private fun arriveContent(carrier: Int, rec: Rec, nowMs: Long) {
        val wire = Wire.Content(rec)
        if (carrier == OLD) {
            when {
                accept != OLD && accept != NONE && rec.seq >= boundary ->
                    out.add(Action.Drop(DropReason.FENCED, wire, carrier))
                rec.seq < oldFrom || rec.seq <= lastOpened -> out.add(Action.Drop(DropReason.STALE, wire, carrier))
                else -> deliver(rec, carrier)
            }
        } else if (carrier == newId) {
            if (accept == carrier) {
                if (rec.seq < boundary || rec.seq <= lastOpened) {
                    out.add(Action.Drop(DropReason.STALE, wire, carrier))
                } else {
                    deliver(rec, carrier)
                }
            } else {
                // Early new-carrier record: its SWITCH is still on the old link. Hold it.
                if (held.isEmpty()) holdSinceMs = nowMs
                held.add(rec)
                heldBytes += rec.bytes
                if (heldBytes > config.holdBytes) holdExhausted(nowMs)
            }
        } else {
            out.add(Action.Drop(DropReason.UNKNOWN_CARRIER, wire, carrier))
        }
    }

    private fun arriveSwitch(carrier: Int, sw: Wire.Switch, nowMs: Long) {
        // A SWITCH on the new carrier is only written after the sender closed the old link: that
        // closes it for this phone as well, whatever the generation says.
        if (config.oldDeadHint && carrier != OLD && carrier == newId && oldAlive &&
            (sw.carrier == carrier || sw.carrier == ANY && !config.markersNameCarrier)
        ) {
            stat("old-dead-hint")
            out.add(Action.CloseCarrier(OLD, CloseReason.PEER_OLD_DEAD))
            carrierGone(OLD, nowMs)
            if (ended) return
        }
        if (sw.gen < maxGen || sw.gen == maxGen && (sw.gen != seenSwitchGen || sw.boundary != seenSwitchBoundary)) {
            out.add(Action.Drop(DropReason.STALE_MARKER, sw, carrier)) // logged, never fatal
            stat("switch-stale")
            return
        }
        if (sw.gen == maxGen) {
            stat("switch-repeat")
            return // a repeat of the same SWITCH
        }
        maxGen = sw.gen
        seenSwitchGen = sw.gen
        seenSwitchBoundary = sw.boundary
        if (sw.carrier == ANY && config.markersNameCarrier) {
            stat("switch-unnamed-burned")
            return // a marker that names no carrier instance is never acted on
        }
        // Which new-carrier instance is the marker about? A marker delayed on the old link can be
        // about an attempt that is long dead while a later attempt is already up: it must not
        // move the receiver onto that later carrier.
        val target = if (sw.carrier == ANY) (if (newId != NONE) newId else pendingId) else sw.carrier
        if (target == NONE || target != newId && target != pendingId) {
            stat("switch-burned")
            return // burned
        }
        if (target == pendingId) {
            stat("switch-kept-for-pending")
            if (config.switchWaitsForPendingNew && carrier == OLD) {
                pendingSwitchBoundary = sw.boundary
                pendingSwitchCarrier = target
            }
            return // this side's READY exchange is still running
        }
        if (accept == newId) {
            stat("switch-already-accepted")
            return // already taken (hold promotion or old link lost)
        }
        accept = newId
        boundary = sw.boundary
        stat(if (carrier == newId) "switch-accepted-from-new" else "switch-accepted-from-old")
        if (carrier == newId) {
            // The sender found the old link dead: its tail is lost, so resynchronise.
            out.add(Action.SuppressFragments)
            out.add(Action.RequestPeerKeyframe)
        }
        releaseHeld()
    }

    private fun arriveResume(carrier: Int, rs: Wire.Resume, nowMs: Long) {
        if (rs.gen <= maxGen) {
            out.add(Action.Drop(DropReason.STALE_MARKER, null, carrier))
            return
        }
        maxGen = rs.gen
        if (rs.carrier == ANY && config.markersNameCarrier) {
            stat("resume-unnamed-burned")
            return // a marker that names no carrier instance is never acted on
        }
        val target = if (rs.carrier == ANY) accept else rs.carrier
        val closes = if (config.resumeAlwaysWritten) {
            target != NONE && (target == newId || target == pendingId)
        } else {
            accept != OLD && accept != NONE && accept == target
        }
        if (closes) {
            // The peer's new carrier is gone: take the old link again and clean up this end.
            stat(if (accept == target) "resume-closed-new" else "resume-closed-new-not-accepted")
            if (config.resumeSetsOldFloor) oldFrom = maxOf(oldFrom, rs.next)
            out.add(Action.CloseCarrier(target, CloseReason.PEER_RESUMED))
            carrierGone(target, nowMs)
        } else {
            stat("resume-burned") // ignored (not that carrier); the generation is burned
        }
    }

    /** The hold ceiling (time or bytes) was reached with the `SWITCH` still missing. */
    private fun holdExhausted(nowMs: Long) {
        holdExhaustions += 1
        stat("hold-exhausted")
        when (config.holdExpiry) {
            HoldExpiry.PROMOTE -> {
                accept = newId
                boundary = lastOpened + 1
                out.add(Action.SuppressFragments)
                out.add(Action.RequestPeerKeyframe)
                releaseHeld()
            }
            HoldExpiry.CLOSE_NEW -> {
                val n = newId
                for (r in held) out.add(Action.Drop(DropReason.STALE, Wire.Content(r), n))
                held.clear(); heldBytes = 0
                out.add(Action.CloseCarrier(n, CloseReason.HOLD_EXPIRED))
                carrierGone(n, nowMs)
            }
        }
    }

    private fun releaseHeld() {
        val from = newId
        for (r in held) {
            if (r.seq > lastOpened && r.seq >= boundary) deliver(r, from)
            else out.add(Action.Drop(DropReason.STALE, Wire.Content(r), from))
        }
        held.clear()
        heldBytes = 0
    }

    private fun deliver(rec: Rec, carrier: Int) {
        lastOpened = rec.seq
        out.add(Action.Deliver(rec, carrier))
    }

    private fun end(reason: String) {
        if (ended) return
        ended = true
        out.add(Action.EndSession(reason))
    }

    private fun marker(carrier: Int) = if (config.markersNameCarrier) carrier else ANY

    private fun isDead(carrier: Int) = carrier >= 0 && (deadMask shr carrier) and 1L == 1L

    private fun drain(): List<Action> {
        if (out.isEmpty()) return emptyList()
        val r = out
        out = ArrayList()
        return r
    }

    companion object {
        /** The old (LE) link. */
        const val OLD = 0

        /** No carrier. */
        const val NONE = -1

        /** A marker that names no carrier instance (the first draft). */
        const val ANY = -2

        private const val NOT_ACTIVE = -1L

        fun name(carrier: Int) = when (carrier) {
            OLD -> "O"
            NONE -> "-"
            ANY -> "*"
            else -> "N$carrier"
        }
    }
}
