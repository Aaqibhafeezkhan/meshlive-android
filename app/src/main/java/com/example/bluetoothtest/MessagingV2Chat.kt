package com.example.bluetoothtest

/*
 * Messaging v2, phase 1d: the thread link ("chat session", DECISIONS Design M6). The link rules themselves (repeated SUMMARY,
 * held DONE, PING, deadlines) live in StoreForwardLinkSession; this file is the policy around them: when a phone offers
 * to keep an exchange open, who dials, when a session ends, and how failures back off. Pure Kotlin on purpose, so every
 * rule has a JVM test; the Android wiring (discovery, the call endpoint, the DEBUG controls) only feeds it facts.
 */

/** A send was refused because the contact's newer keys wait for the user's comparison (M1): nothing is sent until then. */
class MessagingV2KeysChangedException(message: String) : IllegalStateException(message)

/**
 * Sub-switch `test_mv2_chat_session` (DECISIONS Design M "Switches"): on inside the feature; `false` is the control arm, an
 * exchange per message as v1 does. Only meaningful while the master switch `test_messaging_v2` is on.
 */
object MessagingV2ChatFeature {
    const val EXTRA = "test_mv2_chat_session"
    const val PREF = "feature_mv2_chat_session"

    @Volatile var enabled = true

    /** Needs the master switch. A DEBUG benchmark launch reads only its own extra (default on inside the feature). */
    internal fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String, Boolean) -> Boolean,
        stored: (String) -> Boolean?,
        masterOn: Boolean,
    ): Boolean = masterOn && if (benchmarkLaunch) extra(EXTRA, true) else stored(PREF) ?: true
}

/** The numbers of the thread link policy (M6). The link's own timers (20 s ping, 60 s dead, 30 min maximum) are in [MessagingV2Limits]. */
data class ChatSessionPolicy(
    /** The thread was left: the session stays this long (a quick look away does not close it), then ends with DONE(IDLE). */
    val leftGraceMs: Long = 120_000,
    /** With both phones' threads open the lower node ID dials at once; the higher waits this long for that dial before it dials itself. */
    val higherIdWaitMs: Long = 4_000,
    /**
     * A session that ended at its 30 minute maximum (or cleanly) is re-opened this long after, if the thread is still open. Both
     * this and [retryBaseMs] stay above the responder's relaxed 5 s cooldown, so a quick redial is never refused for it.
     */
    val reopenDelayMs: Long = 5_500,
    /** Dial retry after a failure: base * 1.5^failures, capped, with a jitter of +-15%. */
    val retryBaseMs: Long = 5_500,
    val retryMaxMs: Long = 60_000,
    val jitter: Double = 0.15,
    /** The peer did not offer a chat session (or the responder cooldown applied): ask again no sooner than the v1 cooldown plus margin. */
    val noChatBackoffMs: Long = 65_000,
    /** A dial with no result after this long is treated as lost. */
    val dialTimeoutMs: Long = 60_000,
    /** A phone whose app is in the foreground (any screen) joins a session the contact opens, although its own thread is not open. */
    val joinWhenForeground: Boolean = true,
)

enum class ChatInterest {
    /** The thread of this contact is on screen now. */
    OPEN,

    /** The thread was left less than [ChatSessionPolicy.leftGraceMs] ago. */
    LINGER,

    /** The app is in the foreground, on another screen. */
    FOREGROUND,
    NONE,
}

/**
 * Which contact threads are on screen. The conversation screen (phase 1c) calls [threadOpened] in `onResume` and
 * [threadClosed] in `onPause`; the DEBUG controls `sf2_chat_open` and `sf2_chat_close` call the same. All times are on the
 * monotonic clock [nowMs].
 */
class ChatPresence(private val nowMs: () -> Long, private val policy: ChatSessionPolicy = ChatSessionPolicy()) {
    private class Entry(var open: Boolean, var openedMs: Long, var leftMs: Long)

    private val entries = HashMap<String, Entry>()
    @Volatile private var foreground = false
    @Volatile private var foregroundLostMs = 0L

    /** The app has a visible screen (set from the activity lifecycle). */
    fun setAppForeground(value: Boolean) {
        if (foreground && !value) foregroundLostMs = nowMs()
        foreground = value
    }

    val appForeground: Boolean get() = foreground

    @Synchronized
    fun threadOpened(peer: NodeId) {
        val now = nowMs()
        val entry = entries.getOrPut(peer.toString()) { Entry(false, now, 0) }
        if (!entry.open) entry.openedMs = now
        entry.open = true
    }

    @Synchronized
    fun threadClosed(peer: NodeId) {
        val entry = entries[peer.toString()] ?: return
        if (entry.open) {
            entry.open = false
            entry.leftMs = nowMs()
        }
    }

    @Synchronized
    fun openPeers(): List<NodeId> = entries.filterValues { it.open }.keys.map { NodeId.fromBytes(hexToBytes(it)) }

    /** Since when the thread has been continuously open (monotonic ms), or null when it is not open. */
    @Synchronized
    fun openedAtMs(peer: NodeId): Long? = entries[peer.toString()]?.takeIf { it.open }?.openedMs

    /** The last moment this phone had a reason to keep a chat session with [peer] open (now while a thread or screen is up). */
    @Synchronized
    fun lastInterestMs(peer: NodeId, foregroundCounts: Boolean = true): Long {
        val now = nowMs()
        val entry = entries[peer.toString()]
        if (entry?.open == true) return now
        // The foreground screen is a reason to stay only for the phone that was called; the phone that dialed needs its own thread.
        val screenCounts = policy.joinWhenForeground && foregroundCounts
        if (screenCounts && foreground) return now
        val thread = entry?.leftMs ?: 0L
        val screen = if (screenCounts) foregroundLostMs else 0L
        return maxOf(thread, screen)
    }

    @Synchronized
    fun interest(peer: NodeId): ChatInterest {
        val now = nowMs()
        val entry = entries[peer.toString()]
        if (entry?.open == true) return ChatInterest.OPEN
        if (policy.joinWhenForeground && foreground) return ChatInterest.FOREGROUND
        val last = lastInterestMs(peer)
        return if (last > 0 && now - last <= policy.leftGraceMs) ChatInterest.LINGER else ChatInterest.NONE
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

/** What stops this phone from dialling a chat session right now (the live call and stream always win, M6). */
data class ChatGate(
    val callActive: Boolean = false,
    val liveMedia: Boolean = false,
    val exchangeRunning: Boolean = false,
    val endpointBusy: Boolean = false,
    val thermalBlocked: Boolean = false,
) {
    fun blockedBy(): String? = when {
        callActive -> "call"
        liveMedia -> "media"
        exchangeRunning -> "exchange_running"
        endpointBusy -> "endpoint_busy"
        thermalBlocked -> "thermal"
        else -> null
    }
}

sealed interface ChatDial {
    data object Start : ChatDial
    data class Wait(val untilMs: Long, val why: String) : ChatDial
    data class Skip(val why: String) : ChatDial
}

/**
 * The policy for the thread link. One per node ([StoreForwardRuntime.chat]); thread-safe.
 *
 * - [chatCapable] decides the HELLO `CHAT` flag of an exchange with a peer.
 * - [dialDecision] decides whether to start an exchange to a contact whose thread is open (the lower node ID dials first).
 * - [tick] ends sessions whose reason to exist lapsed (thread left for 2 minutes), through the link's `endChat`.
 * - [onExchangeResult] and [onConnectFailed] keep the retry state.
 */
class ChatSessionController(
    private val node: StoreForwardNode,
    val presence: ChatPresence,
    private val policy: ChatSessionPolicy = ChatSessionPolicy(),
    private val nowMs: () -> Long,
    private val random: () -> Double = Math::random,
    private val enabled: () -> Boolean = { MessagingV2Feature.enabled && MessagingV2ChatFeature.enabled },
    private val log: (String) -> Unit = {},
) {
    private class PeerState {
        var failures = 0
        var lastEndMs = 0L
        var nextAllowedMs = 0L
        var dialStartedMs = 0L
        var dialing = false
        var chatSessions = 0
    }

    private val peers = HashMap<String, PeerState>()

    private fun state(peer: NodeId) = peers.getOrPut(peer.toString()) { PeerState() }

    /**
     * Whether this phone offers a chat session (HELLO flag CHAT) in an exchange with [peer]: the feature is on, the peer is a
     * contact or a person the user verified in person, and this phone has a reason to stay (that thread is on screen or was
     * left less than 2 minutes ago; an answering phone also stays while its app is in the foreground, a dialling one does not).
     */
    fun chatCapable(peer: NodeId, dialer: Boolean = false): Boolean {
        if (!enabled() || node.v2 == null) return false
        if (!node.isTrustedPeer(peer)) return false
        if (node.contacts.get(peer)?.keysChanged == true) return false
        val interest = presence.interest(peer)
        // The phone that starts the exchange needs a reason of its own: a plain sync between two phones that merely have the
        // app open (the DEBUG sf_sync, auto sync) must still end when everything is exchanged. A phone that is called may
        // also join because its app is in the foreground.
        return if (dialer) interest == ChatInterest.OPEN || interest == ChatInterest.LINGER else interest != ChatInterest.NONE
    }

    /** Ends the chat sessions nobody wants any more. Returns the peers whose session was asked to end. */
    fun tick(): List<NodeId> {
        val now = nowMs()
        val ended = ArrayList<NodeId>()
        for (session in node.chatSessions()) {
            val peer = session.peerId
            // Design M6: the session lives while the opener's thread is on screen or was left less than 2 minutes ago. A foreground
            // app keeps the session alive only on the answering phone; on the phone that dialed it is no reason (lane3 finding:
            // the opener left the thread and the session stayed 8+ minutes because its app was in the foreground).
            val lapsed = !enabled() || now - presence.lastInterestMs(peer, foregroundCounts = !session.isInitiator) > policy.leftGraceMs
            if (lapsed) {
                session.endChat()
                ended += peer
                log("BENCHMARK message_v2 chat end peer=${peer.shortLabel()} why=${if (enabled()) "thread_left" else "disabled"}")
            }
        }
        return ended
    }

    /** True when [peer]'s thread is open and a dial would be allowed by everything except time (the wait is in [dialDecision]). */
    @Synchronized
    fun dialDecision(peer: NodeId, gate: ChatGate): ChatDial {
        if (!enabled() || node.v2 == null) return ChatDial.Skip("disabled")
        val contact = node.contacts.get(peer)?.takeIf { it.state == ContactState.ACCEPTED } ?: return ChatDial.Skip("not_contact")
        if (contact.keysChanged) return ChatDial.Skip("keys_changed")
        if (presence.interest(peer) != ChatInterest.OPEN) return ChatDial.Skip("thread_closed")
        gate.blockedBy()?.let { return ChatDial.Skip(it) }
        if (node.chatSessionActive(peer)) return ChatDial.Skip("session_running")
        val now = nowMs()
        val st = state(peer)
        if (st.dialing) {
            if (now - st.dialStartedMs < policy.dialTimeoutMs) return ChatDial.Skip("dial_in_flight")
            st.dialing = false
            st.failures++
            st.nextAllowedMs = now + retryDelayMs(st.failures)
        }
        val higher = node.nodeId.toString() > peer.toString()
        val startMs = maxOf(presence.openedAtMs(peer) ?: 0L, st.lastEndMs)
        val due = maxOf(st.nextAllowedMs, startMs + if (higher) policy.higherIdWaitMs else 0L)
        return if (now >= due) ChatDial.Start else ChatDial.Wait(due, if (higher) "tie_break_or_backoff" else "backoff")
    }

    /** The dial is starting (the connect begins); until a result or the timeout no second dial is made. */
    @Synchronized
    fun onDialStarted(peer: NodeId) {
        val st = state(peer)
        st.dialing = true
        st.dialStartedMs = nowMs()
    }

    /** The connection could not be opened (not responding, refused, collision with the contact's own dial). */
    @Synchronized
    fun onConnectFailed(peer: NodeId) {
        val st = state(peer)
        st.dialing = false
        st.failures++
        st.nextAllowedMs = nowMs() + retryDelayMs(st.failures)
    }

    /** Any finished exchange with [result].peer, either role. [chat] says whether it ran as a chat session. */
    @Synchronized
    fun onExchangeResult(result: StoreForwardSyncResult) {
        val st = state(result.peer)
        val now = nowMs()
        val wasDialing = st.dialing
        st.dialing = false
        st.lastEndMs = now
        when (result.outcome) {
            StoreForwardSyncOutcome.COMPLETE -> {
                st.failures = 0
                // A plain exchange the thread asked for: the contact did not offer a chat session, so the responder's normal
                // cooldown applies to the next one.
                st.nextAllowedMs = now + if (result.chat) policy.reopenDelayMs else if (wasDialing) policy.noChatBackoffMs else 0L
            }
            StoreForwardSyncOutcome.SUSPENDED_LOCAL, StoreForwardSyncOutcome.SUSPENDED_BY_PEER -> {
                // A call or stream took the radio: the gate holds the next dial until it is over. Not a failure.
                st.nextAllowedMs = now + policy.reopenDelayMs
            }
            StoreForwardSyncOutcome.RATE_LIMITED -> {
                st.failures++
                st.nextAllowedMs = now + policy.noChatBackoffMs
            }
            StoreForwardSyncOutcome.TIMEOUT, StoreForwardSyncOutcome.INTERRUPTED, StoreForwardSyncOutcome.FAILED -> {
                st.failures++
                st.nextAllowedMs = now + retryDelayMs(st.failures)
            }
        }
        if (result.chat) st.chatSessions++
    }

    private fun retryDelayMs(failures: Int): Long {
        val grown = policy.retryBaseMs * Math.pow(1.5, failures.coerceIn(0, 32).toDouble())
        val capped = grown.coerceAtMost(policy.retryMaxMs.toDouble())
        return (capped * (1.0 + policy.jitter * (2 * random() - 1))).toLong()
    }

    @Synchronized fun failures(peer: NodeId): Int = peers[peer.toString()]?.failures ?: 0
}

/** The verified-contact store seen as [VerifiedIdentityDirectory] (M1: one contact system, this store is the source of truth). */
class VerifiedStoreDirectory(private val store: VerifiedContactStore) : VerifiedIdentityDirectory {
    override fun verifiedLabel(identityKey: ByteArray): String? {
        if (identityKey.isEmpty()) return null
        val fingerprint = IdentityFingerprint.fromPublicKey(identityKey)
        return store.all().firstOrNull { it.fingerprint == fingerprint }?.displayName
    }

    override fun isVerifiedNode(node: NodeId): Boolean = store.stateForNodeId(node) == VerificationState.VERIFIED

    override val readable: Boolean get() = !store.isUnreadable()
}

/** DEBUG `sf2_thread:PREFIX12`: finds the saved contact, from the cached list first and from the verified store when the cache misses. */
internal object DebugContactLookup {
    class Result(val entry: ContactEntry?, val fromStore: Boolean, val why: String)

    fun find(prefix: String, cached: List<ContactEntry>, fresh: () -> List<ContactEntry>): Result {
        val wanted = prefix.lowercase(java.util.Locale.US)
        if (wanted.length < 4 || !wanted.all { it in '0'..'9' || it in 'a'..'f' }) return Result(null, false, "bad_prefix")
        fun pick(list: List<ContactEntry>): ContactEntry? = list.filter { it.nodeIdHex.startsWith(wanted) }.singleOrNull()
        pick(cached)?.let { return Result(it, false, "") }
        val list = fresh()
        pick(list)?.let { return Result(it, true, "") }
        val matches = list.count { it.nodeIdHex.startsWith(wanted) }
        return Result(null, false, if (matches > 1) "ambiguous_prefix" else if (list.isEmpty()) "no_verified_contacts" else "not_verified")
    }
}
