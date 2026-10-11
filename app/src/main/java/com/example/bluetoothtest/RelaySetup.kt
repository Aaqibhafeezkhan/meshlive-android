package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.security.SecureRandom

/** Why a hop refused or could not finish a relay setup (`FAIL` reason byte). */
enum class RelaySetupFailure(val wire: Int, val label: String, val userText: String) {
    NO_ROUTE(1, "no route", "A phone on the way could not reach them any more"),
    BUSY(2, "busy", "A phone on the way is busy with something else"),
    CHILD_AUTHENTICATION(3, "child authentication failed", "A phone on the way could not verify the next phone"),
    TIMEOUT(4, "timeout", "Connecting through other phones took too long"),
    REFUSED(5, "refused", "A phone on the way is not helping right now"),
    LOOP(6, "loop", "The route loops back on itself");

    companion object {
        fun fromWire(value: Int): RelaySetupFailure? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * `BRS1` relay setup messages (hop type 12), PROTOCOL "Experimental relayed calls and multi-hop
 * discovery". Every message is regenerated per hop; the setup ID is random per hop and only binds a
 * reply to its request on one authenticated link.
 */
internal sealed class RelaySetupMessage {
    class Setup(
        val target: ByteArray,
        val budget: Int,
        val remainingMs: Int,
        val setupId: ByteArray,
        /** Message 4 (`SETUP_RDI`): [target] is a role-0 RDI of the current epoch, not a fixed prefix. */
        val rotating: Boolean = false,
    ) : RelaySetupMessage()

    class Ready(val setupId: ByteArray, val relays: Int) : RelaySetupMessage()

    class Fail(val setupId: ByteArray, val reason: RelaySetupFailure) : RelaySetupMessage()
}

internal object RelaySetupCodec {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    const val SETUP_BYTES = 24
    const val REPLY_BYTES = 14
    const val ID_BYTES = 8
    const val SETUP = 1
    const val READY = 2
    const val FAIL = 3

    /** Rotating IDs: SETUP with a role-0 RDI target; unknown to a reader that did not opt in. */
    const val SETUP_RDI = 4

    fun encode(message: RelaySetupMessage): ByteArray = when (message) {
        is RelaySetupMessage.Setup -> {
            require(message.target.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES) { "Setup target" }
            require(message.target.any { it.toInt() != 0 }) { "Setup target is the zero prefix" }
            require(message.budget in 1..PrivateCallRelayChain.MAX_RELAYS) { "Setup budget" }
            require(message.remainingMs in RelayedCalls.MIN_REMAINING_MS..RelayedCalls.SETUP_DEADLINE_MS) { "Setup deadline" }
            require(message.setupId.size == ID_BYTES) { "Setup ID" }
            ByteBuffer.allocate(SETUP_BYTES).put(MAGIC).put((if (message.rotating) SETUP_RDI else SETUP).toByte()).put(message.target)
                .put(message.budget.toByte()).putInt(message.remainingMs).put(message.setupId).array()
        }
        is RelaySetupMessage.Ready -> {
            require(message.setupId.size == ID_BYTES) { "Setup ID" }
            require(message.relays in 1..PrivateCallRelayChain.MAX_RELAYS) { "Ready relays" }
            ByteBuffer.allocate(REPLY_BYTES).put(MAGIC).put(READY.toByte()).put(message.setupId)
                .put(message.relays.toByte()).array()
        }
        is RelaySetupMessage.Fail -> {
            require(message.setupId.size == ID_BYTES) { "Setup ID" }
            ByteBuffer.allocate(REPLY_BYTES).put(MAGIC).put(FAIL.toByte()).put(message.setupId)
                .put(message.reason.wire.toByte()).array()
        }
    }

    /**
     * Throws IllegalArgumentException for anything that is not exactly one valid message. [acceptRdi] is true
     * only for a phone with rotating IDs and relayed calls both on; otherwise message 4 is unknown, as before.
     */
    fun decode(bytes: ByteArray, acceptRdi: Boolean = false): RelaySetupMessage {
        require(bytes.size >= 5 && bytes.copyOf(4).contentEquals(MAGIC)) { "Relay setup magic" }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(4)
        return when (val type = buffer.get().toInt() and 0xff) {
            SETUP, SETUP_RDI -> {
                require(type == SETUP || acceptRdi) { "Relay setup message $type" }
                require(bytes.size == SETUP_BYTES) { "Relay setup length" }
                val target = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES).also(buffer::get)
                val budget = buffer.get().toInt() and 0xff
                val remaining = buffer.int
                val id = ByteArray(ID_BYTES).also(buffer::get)
                require(target.any { it.toInt() != 0 }) { "Setup target is the zero prefix" }
                require(budget in 1..PrivateCallRelayChain.MAX_RELAYS) { "Setup budget" }
                require(remaining in RelayedCalls.MIN_REMAINING_MS..RelayedCalls.SETUP_DEADLINE_MS) { "Setup deadline" }
                RelaySetupMessage.Setup(target, budget, remaining, id, rotating = type == SETUP_RDI)
            }
            READY -> {
                require(bytes.size == REPLY_BYTES) { "Relay ready length" }
                val id = ByteArray(ID_BYTES).also(buffer::get)
                val relays = buffer.get().toInt() and 0xff
                require(relays in 1..PrivateCallRelayChain.MAX_RELAYS) { "Ready relays" }
                RelaySetupMessage.Ready(id, relays)
            }
            FAIL -> {
                require(bytes.size == REPLY_BYTES) { "Relay fail length" }
                val id = ByteArray(ID_BYTES).also(buffer::get)
                val reason = RelaySetupFailure.fromWire(buffer.get().toInt() and 0xff)
                    ?: throw IllegalArgumentException("Relay fail reason")
                RelaySetupMessage.Fail(id, reason)
            }
            else -> throw IllegalArgumentException("Relay setup message $type")
        }
    }

    fun newSetupId(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(ID_BYTES).also(random::nextBytes)
}

/** Why a helper refused a SETUP before touching the radio, in the order PROTOCOL lists the checks. */
internal data class RelaySetupInputs(
    val featureOn: Boolean,
    val helperAllowed: Boolean,
    /** Pause by battery, Battery Saver, thermal state or Bluetooth (RelayHelperPolicy). */
    val paused: Boolean,
    /** Own call, ringing call, message exchange, stream, or already relaying. */
    val busy: Boolean,
    val rateLimited: Boolean,
    val parentIsSelf: Boolean,
    val parentIsTarget: Boolean,
    val targetIsSelf: Boolean,
    val budget: Int,
    val maxRelays: Int,
    /** A fresh route to the target within the budget exists in the table. */
    val routeAvailable: Boolean,
)

internal object RelaySetupAdmission {
    /** Null accepts; otherwise the reason to send in FAIL. Cheap checks first, table last. */
    fun refusal(inputs: RelaySetupInputs): RelaySetupFailure? = when {
        !inputs.featureOn || !inputs.helperAllowed || inputs.paused -> RelaySetupFailure.REFUSED
        inputs.busy -> RelaySetupFailure.BUSY
        inputs.rateLimited -> RelaySetupFailure.REFUSED
        inputs.parentIsSelf || inputs.parentIsTarget || inputs.targetIsSelf -> RelaySetupFailure.LOOP
        inputs.budget < 1 || inputs.budget > inputs.maxRelays -> RelaySetupFailure.REFUSED
        !inputs.routeAvailable -> RelaySetupFailure.NO_ROUTE
        else -> null
    }
}

/**
 * SETUP rate limits: at most [perParent] per authenticated parent identity per minute and [overall]
 * per minute for the phone. Identities are free (an attacker can mint them), so the per-identity cap
 * only slows honest retries; the overall cap and one-call-at-a-time are the bounds that hold.
 */
internal class RelaySetupRateLimiter(
    private val clock: () -> Long,
    private val perParent: Int = 3,
    private val overall: Int = 10,
    private val windowMs: Long = 60_000L,
) {
    private val byParent = HashMap<String, ArrayDeque<Long>>()
    private val all = ArrayDeque<Long>()

    /** Parent identities currently tracked (bounded; for tests). */
    val trackedParents: Int
        @Synchronized get() = byParent.size

    @Synchronized
    fun tryAdmit(parentId: String): Boolean {
        val now = clock()
        trim(all, now)
        val own = byParent.getOrPut(parentId) { ArrayDeque() }
        trim(own, now)
        if (byParent.size > MAX_TRACKED_PARENTS) {
            byParent.values.forEach { trim(it, now) }
            byParent.entries.removeAll { it.value.isEmpty() && it.key != parentId }
            // Hard bound: identities are free, so forget the least recently active ones. The overall cap still holds.
            while (byParent.size > MAX_TRACKED_PARENTS) {
                val oldest = byParent.entries.filter { it.key != parentId }
                    .minByOrNull { it.value.lastOrNull() ?: 0L } ?: break
                byParent.remove(oldest.key)
            }
        }
        if (all.size >= overall || own.size >= perParent) return false
        all.addLast(now)
        own.addLast(now)
        return true
    }

    private fun trim(queue: ArrayDeque<Long>, now: Long) {
        while (queue.isNotEmpty() && now - queue.first() >= windowMs) queue.removeFirst()
    }

    private companion object {
        const val MAX_TRACKED_PARENTS = 64
    }
}

/** The caller (or previous helper) could not set up a relayed route; [message] is shown to the user. */
class RelaySetupFailedException(val reason: RelaySetupFailure?, message: String) : Exception(message)

internal object RelaySetupExchange {
    /**
     * Reads exactly one setup reply for [setupId] from [session] (hop type 12 is accepted only for
     * this read). [closeLink] runs if no reply arrives within [timeoutMs], which ends the blocked read.
     * Throws IllegalArgumentException for a reply that is not the single matching READY or FAIL.
     */
    fun awaitReply(
        session: SecureBluetoothSession,
        setupId: ByteArray,
        timeoutMs: Long,
        closeLink: () -> Unit,
    ): RelaySetupMessage {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = kotlin.concurrent.thread(name = "RelaySetupDeadline", isDaemon = true) {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!done.get()) runCatching { closeLink() }
        }
        session.reader.acceptRelaySetup = true
        try {
            val record = session.reader.read()
            require(record.type == SecureRecordType.RELAY_SETUP) { "Unexpected setup reply record ${record.type}" }
            val reply = RelaySetupCodec.decode(record.payload)
            val replyId = when (reply) {
                is RelaySetupMessage.Ready -> reply.setupId
                is RelaySetupMessage.Fail -> reply.setupId
                is RelaySetupMessage.Setup -> throw IllegalArgumentException("Setup reply expected")
            }
            require(replyId.contentEquals(setupId)) { "Setup reply for another request" }
            return reply
        } finally {
            session.reader.acceptRelaySetup = false
            done.set(true)
            watchdog.interrupt()
        }
    }

    fun writeMessage(session: SecureBluetoothSession, message: RelaySetupMessage) {
        session.writer.write(SecureRecordType.RELAY_SETUP, RelaySetupCodec.encode(message))
        session.writer.flush()
    }

    /**
     * The caller's side of a relayed route: after the first hop's link handshake, sends SETUP and returns
     * the relay count of READY. The hop must be the helper that was selected and must have signed the
     * helper marks; anything else is a failed candidate, not a call.
     */
    fun requestRoute(
        link: SecureBluetoothSession,
        route: RelayRoute,
        remainingMs: Int,
        /** The helper's link `BDO1` entries (rotating route): they must open the helper offer that was chosen. */
        helperOpening: List<OpeningEntry>? = null,
        closeLink: () -> Unit,
    ): Int {
        if (remainingMs < RelayedCalls.MIN_REMAINING_MS) {
            throw RelaySetupFailedException(RelaySetupFailure.TIMEOUT, RelaySetupFailure.TIMEOUT.userText)
        }
        val helperIsChosen = if (route.rotating) {
            DiscoverySelectionCheck.check(
                RelayedCalls.helperOfferFor(route.helperPrefix),
                RotatingDiscoveryIds.ROLE_CALL_OFFER,
                link.peerNodeId.toByteArray(),
                helperOpening,
            ) == DiscoverySelectionCheck.Result.PASS
        } else {
            link.peerNodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(route.helperPrefix)
        }
        if (!helperIsChosen) {
            throw RelaySetupFailedException(
                RelaySetupFailure.CHILD_AUTHENTICATION,
                "The phone that answered is not the helper that was chosen",
            )
        }
        if (!RelayedCalls.linkAcceptsSetup(link.peerCapabilities)) {
            throw RelaySetupFailedException(RelaySetupFailure.REFUSED, RelaySetupFailure.REFUSED.userText)
        }
        val id = RelaySetupCodec.newSetupId()
        writeMessage(link, RelaySetupMessage.Setup(route.target, route.relays, remainingMs, id, route.rotating))
        val reply = try {
            awaitReply(link, id, remainingMs + SETUP_REPLY_GRACE_MS, closeLink)
        } catch (error: java.io.IOException) {
            throw RelaySetupFailedException(RelaySetupFailure.TIMEOUT, RelaySetupFailure.TIMEOUT.userText)
        } catch (error: IllegalArgumentException) {
            throw RelaySetupFailedException(null, "The helping phone sent an invalid answer")
        }
        return when (reply) {
            is RelaySetupMessage.Ready -> {
                if (reply.relays > route.relays) {
                    throw RelaySetupFailedException(null, "The helping phone reported more relays than were asked for")
                }
                reply.relays
            }
            is RelaySetupMessage.Fail -> throw RelaySetupFailedException(reply.reason, reply.reason.userText)
            is RelaySetupMessage.Setup -> throw RelaySetupFailedException(null, "The helping phone sent an invalid answer")
        }
    }

    /** Reply grace after the setup deadline: cleanup time for the last hop, not extra setup time. */
    const val SETUP_REPLY_GRACE_MS = 2_000L
}
