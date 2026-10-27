package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Why a Classic candidate is tried for an announced peer; decides its order and attempt budget.
 *
 * Discovery runs over BLE, but RFCOMM must page the peer's BR/EDR public address. Android hides a
 * phone's own address from apps (BluetoothAdapter.getAddress returns 02:00:00:00:00:00 since 6.0) and
 * LE advertising uses resolvable private addresses that only a bond's IRK resolves, so the address
 * can come only from a bond, an earlier authenticated Classic link ([LEARNED], experimental), a DEBUG
 * override, or a Classic inquiry of a discoverable phone ([INQUIRY], experimental). [resolved] kinds
 * are expected to be the announced peer; the others are guesses that BMH2 must confirm.
 */
internal enum class ClassicCandidateKind(val resolved: Boolean, val label: String) {
    DEBUG(true, "debug"),
    LEARNED(true, "learned"),
    BONDED_ADDRESS(true, "bonded-address"),
    BONDED_NAME(true, "bonded-name"),
    BONDED_FALLBACK(false, "bonded-fallback"),
    DISCOVERED(false, "discovered"),
    INQUIRY(false, "inquiry"),
}

internal data class ClassicCandidate<T>(val device: T, val kind: ClassicCandidateKind)

/**
 * Orders Classic RFCOMM candidates for an announced peer. Exact address/name matches come first.
 * The bonded fallback is limited to devices that can host the app (phones/computers/unknown class),
 * preferring ones whose cached SDP records already list the app service: every candidate costs up
 * to its attempt timeout, and paging earbuds, cars or watches spent the whole connection budget.
 *
 * [debugOverride] and [learned] (addresses already bound to the announced node prefix) go first.
 * The discovered BLE address goes last and is dropped when [discoveredIsRandomAddress] proves it is
 * a random LE address, which no BR/EDR page can ever reach.
 */
internal fun <T> classicCandidatePlan(
    discovered: T,
    bonded: List<T>,
    address: (T) -> String,
    name: (T) -> String?,
    canHostApp: (T) -> Boolean = { true },
    advertisesAppService: (T) -> Boolean = { false },
    learned: T? = null,
    debugOverride: T? = null,
    discoveredIsRandomAddress: Boolean = false,
): List<ClassicCandidate<T>> {
    val addressMatch = bonded.firstOrNull { address(it).equals(address(discovered), ignoreCase = true) }
    val discoveredName = name(discovered)?.trim()?.takeIf(String::isNotEmpty)
    val nameMatch = discoveredName?.let { requested ->
        bonded.filter { name(it)?.trim().equals(requested, ignoreCase = true) }.singleOrNull()
    }
    val fallback = bonded.filter(canHostApp).sortedByDescending(advertisesAppService)
    val ordered = buildList<ClassicCandidate<T>> {
        debugOverride?.let { add(ClassicCandidate(it, ClassicCandidateKind.DEBUG)) }
        learned?.let { add(ClassicCandidate(it, ClassicCandidateKind.LEARNED)) }
        addressMatch?.let { add(ClassicCandidate(it, ClassicCandidateKind.BONDED_ADDRESS)) }
        nameMatch?.let { add(ClassicCandidate(it, ClassicCandidateKind.BONDED_NAME)) }
        fallback.forEach { add(ClassicCandidate(it, ClassicCandidateKind.BONDED_FALLBACK)) }
        if (!discoveredIsRandomAddress) add(ClassicCandidate(discovered, ClassicCandidateKind.DISCOVERED))
    }
    return ordered.distinctBy { address(it.device).uppercase() }
}

/**
 * The per-role Classic plan used before 2026-10-09, still the default while the bounded connect
 * path is experimental (ClassicFeatureSettings.usesBoundedConnect off): the stream sender tried
 * the class-filtered candidates; the call relay tried the resolved device, then every bonded device,
 * then the BLE address; calls, the stream relay and the forwarder tried one resolved device.
 */
internal fun <T> legacyClassicCandidatePlan(
    role: ClassicRole,
    discovered: T,
    bonded: List<T>,
    address: (T) -> String,
    name: (T) -> String?,
    canHostApp: (T) -> Boolean = { true },
    advertisesAppService: (T) -> Boolean = { false },
): List<ClassicCandidate<T>> {
    if (role == ClassicRole.STREAM_SENDER) {
        return classicCandidatePlan(discovered, bonded, address, name, canHostApp, advertisesAppService)
    }
    val addressMatch = bonded.firstOrNull { address(it).equals(address(discovered), ignoreCase = true) }
    val discoveredName = name(discovered)?.trim()?.takeIf(String::isNotEmpty)
    val nameMatch = if (addressMatch == null && discoveredName != null) {
        bonded.filter { name(it)?.trim().equals(discoveredName, ignoreCase = true) }.singleOrNull()
    } else {
        null
    }
    val resolved: ClassicCandidate<T> = when {
        addressMatch != null -> ClassicCandidate(addressMatch, ClassicCandidateKind.BONDED_ADDRESS)
        nameMatch != null -> ClassicCandidate(nameMatch, ClassicCandidateKind.BONDED_NAME)
        else -> ClassicCandidate(discovered, ClassicCandidateKind.DISCOVERED)
    }
    if (role != ClassicRole.CALL_RELAY) return listOf(resolved)
    return (listOf(resolved) + bonded.map { ClassicCandidate<T>(it, ClassicCandidateKind.BONDED_FALLBACK) } +
        ClassicCandidate<T>(discovered, ClassicCandidateKind.DISCOVERED))
        .distinctBy { address(it.device).uppercase() }
}

/** Legacy budgets: the stream sender's 12 s/3 s; every other role's connect() had no deadline. */
internal fun legacyClassicAttemptTimeoutMs(role: ClassicRole, kind: ClassicCandidateKind): Long = when {
    role != ClassicRole.STREAM_SENDER -> Long.MAX_VALUE
    kind == ClassicCandidateKind.BONDED_ADDRESS || kind == ClassicCandidateKind.DISCOVERED -> CLASSIC_RESOLVED_ATTEMPT_MS
    else -> CLASSIC_BONDED_GUESS_ATTEMPT_MS
}

/** The device order of [classicCandidatePlan] without learned/debug entries. */
internal fun <T> classicConnectionCandidates(
    discovered: T,
    bonded: List<T>,
    address: (T) -> String,
    name: (T) -> String?,
    canHostApp: (T) -> Boolean = { true },
    advertisesAppService: (T) -> Boolean = { false },
): List<T> = classicCandidatePlan(discovered, bonded, address, name, canHostApp, advertisesAppService)
    .map(ClassicCandidate<T>::device)

/**
 * Why an authenticated Classic link is not the requested peer, or null when it is. A direct route
 * must reach the announced identity. A relayed route (the relay advertises its endpoint's prefix)
 * must not reach that endpoint itself: a direct link would silently replace the requested relay
 * route, which is a failed request, not a pass.
 *
 * Review 2026-10-27 (finding 5): a link peer that presented a BMH3 throwaway ([peerPseudonymous]) has no node ID to
 * compare; the callee's `REVEAL` binds it to the announced prefix after Accept (PROTOCOL "Phase 2", version 2 offers), as
 * on LE. Comparing the throwaway failed every Classic call to a phone in pseudonym mode and made the caller forget a
 * correct learned address.
 */
internal fun classicLinkIdentityProblem(
    linkNodeId: ByteArray,
    announcedPrefix: ByteArray?,
    directRoute: Boolean,
    peerPseudonymous: Boolean = false,
    /**
     * The candidate is the advertiser itself ([ClassicCandidateKind.resolved]: a learned, bonded or debug address). A
     * throwaway answer from a guessed one (a bonded fallback, a discovered or inquiry address) is refused: nothing would
     * show which phone answered, so another phone could ring and its user reveal itself (review 2026-10-27, finding 3).
     */
    resolvedCandidate: Boolean = true,
): String? {
    if (announcedPrefix == null) return null
    if (peerPseudonymous) {
        return if (resolvedCandidate) null else "A guessed Classic address answered with a one-time key"
    }
    val matches = linkNodeId.copyOf(announcedPrefix.size).contentEquals(announcedPrefix)
    return when {
        directRoute && !matches -> "Peer identity changed between discovery and connection"
        !directRoute && matches -> "A relayed route reached its endpoint directly"
        else -> null
    }
}

/** An authenticated Classic link reached someone other than the requested peer ([classicLinkIdentityProblem]). */
internal class ClassicLinkIdentityException(problem: String) : IllegalArgumentException(problem)

/**
 * A learned address is forgotten only when a real identity proved it belongs to someone else: never for a throwaway key,
 * a timeout or another failure ([ClassicLinkIdentityException] only).
 */
internal fun classicLearnedAddressDisproved(kind: ClassicCandidateKind, error: Throwable): Boolean =
    kind == ClassicCandidateKind.LEARNED && error is ClassicLinkIdentityException

/**
 * The candidates that are the advertising device itself: its exact bonded address or unique bonded
 * name, or its discovered address. Bonded fallbacks, learned hints and inquiry results are guesses
 * that may reach a different phone.
 */
internal fun <T> classicExactDevicePlan(plan: List<ClassicCandidate<T>>): List<ClassicCandidate<T>> =
    plan.filter {
        it.kind == ClassicCandidateKind.DEBUG || it.kind == ClassicCandidateKind.BONDED_ADDRESS ||
            it.kind == ClassicCandidateKind.BONDED_NAME || it.kind == ClassicCandidateKind.DISCOVERED
    }

/** True when no candidate is expected to be the announced peer: only guesses remain. */
internal fun <T> classicPlanIsUnresolved(plan: List<ClassicCandidate<T>>): Boolean =
    plan.none { it.kind.resolved }

/**
 * Per-attempt budget. An attempt covers page, SDP, RFCOMM and the BMH2 link handshake. An exact
 * address keeps the 12 s the stream sender has always given the resolved peer; a bonded phone that
 * only matches by name or is a fallback guess keeps its 3 s (2cbdafb1: paging seven bonded devices
 * spent the whole connection budget).
 *
 * The discovered BLE address used to get the resolved 12 s even when no bond resolved it. On current
 * phones that address is a resolvable private address, which pages nothing; it can only work for a
 * peer that advertises with its public address. One page timeout (5.12 s, the HCI default of
 * 0x2000 slots) plus SDP/RFCOMM fits in [CLASSIC_GUESS_ATTEMPT_MS]. In the Classic S24 -> M31 and
 * M31 -> ASUS workflow cases (media-workflow-7396b46e-setup) the candidate list outlasted the
 * broadcaster's 12 s connection timeout, so the run ended in a bare "timed out" with no reason.
 */
internal fun classicAttemptTimeoutMs(kind: ClassicCandidateKind): Long = when (kind) {
    ClassicCandidateKind.DEBUG,
    ClassicCandidateKind.LEARNED,
    ClassicCandidateKind.BONDED_ADDRESS -> CLASSIC_RESOLVED_ATTEMPT_MS
    ClassicCandidateKind.BONDED_NAME,
    ClassicCandidateKind.BONDED_FALLBACK -> CLASSIC_BONDED_GUESS_ATTEMPT_MS
    ClassicCandidateKind.DISCOVERED,
    ClassicCandidateKind.INQUIRY -> CLASSIC_GUESS_ATTEMPT_MS
}

internal const val CLASSIC_RESOLVED_ATTEMPT_MS = 12_000L
internal const val CLASSIC_BONDED_GUESS_ATTEMPT_MS = 3_000L
internal const val CLASSIC_GUESS_ATTEMPT_MS = 7_000L

/** The broadcaster closes a receiver connection still pending after 12 s; finish first with a reason. */
internal const val CLASSIC_STREAM_SENDER_BUDGET_MS = 11_500L

/** Calls, relays and forwarders have no outer connection timeout; this bounds a long bonded list. */
internal const val CLASSIC_SESSION_BUDGET_MS = 30_000L

/** The peer has no known BR/EDR address on this phone; retrying cannot help without a bond or first contact. */
internal class ClassicPeerUnresolvedException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun classicUnresolvedPeerMessage(bondFree: Boolean): String = if (bondFree) {
    "This phone has not met the other phone over Classic yet, and Android reveals a phone's Classic " +
        "address only after pairing or a first contact. Make the other phone visible (its Bluetooth " +
        "settings screen or the visibility prompt) and retry, pair the phones once, or use BLE GATT."
} else {
    "This phone does not know the other phone's Classic address: Android reveals it only to paired " +
        "phones. Pair the phones once in Android Bluetooth settings, turn on Connect without pairing " +
        "(Settings > Features) on both phones, or use BLE GATT."
}

/**
 * Connects candidates in order until one authenticates as the announced peer. A candidate's
 * address/name is only a hint; verification must finish before returning it. Each attempt gets
 * min([attemptTimeoutMs], what is left of [totalBudgetMs]); its deadline closes the attempt.
 */
internal fun <T, C : Closeable, S : Closeable> connectAuthenticatedClassicCandidate(
    candidates: List<T>,
    attemptTimeoutMs: (T) -> Long,
    isCancelled: () -> Boolean,
    open: (T) -> C,
    onOpened: (C) -> Unit,
    authenticate: (C) -> S,
    verify: (S) -> Unit,
    onCandidateFailed: (T, Exception) -> Unit = { _, _ -> },
    totalBudgetMs: Long = Long.MAX_VALUE,
    nanoTime: () -> Long = System::nanoTime,
): Pair<C, S> {
    var lastFailure: Exception? = null
    val startedNs = nanoTime()
    for (candidate in candidates) {
        if (isCancelled()) throw IOException("Classic connection cancelled")
        val remainingMs = if (totalBudgetMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            totalBudgetMs - (nanoTime() - startedNs) / 1_000_000L
        }
        if (remainingMs <= 0L) {
            throw IOException("Classic connection budget of $totalBudgetMs ms used up", lastFailure)
        }
        var connection: C? = null
        var session: S? = null
        val completed = CountDownLatch(1)
        val attemptFinished = AtomicBoolean(false)
        try {
            val timeoutMs = minOf(attemptTimeoutMs(candidate), remainingMs)
            val active = open(candidate).also { connection = it }
            onOpened(active)
            if (isCancelled()) throw IOException("Classic connection cancelled")
            thread(name = "ClassicConnectionDeadline", isDaemon = true) {
                if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS) &&
                    attemptFinished.compareAndSet(false, true)
                ) {
                    runCatching { active.close() }
                }
            }
            val authenticated = authenticate(active).also { session = it }
            verify(authenticated)
            if (isCancelled()) throw IOException("Classic connection cancelled")
            if (!attemptFinished.compareAndSet(false, true)) {
                throw IOException("Classic connection or authentication timed out")
            }
            return active to authenticated
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            lastFailure = error
            attemptFinished.set(true)
            runCatching { onCandidateFailed(candidate, error) }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            if (isCancelled()) throw IOException("Classic connection cancelled", error)
        } finally {
            completed.countDown()
        }
    }
    throw IOException("No paired Classic receiver matched the discovered identity", lastFailure)
}
