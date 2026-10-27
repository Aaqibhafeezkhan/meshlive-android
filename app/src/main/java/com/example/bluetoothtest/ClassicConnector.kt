package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One Classic RFCOMM connect path for every role (stream sender, forwarder, private call, call
 * relay, stream relay). Before 2026-10-09 only the stream sender had the class-filtered candidate
 * order and per-attempt deadlines; the call relay paged every bonded device (earbuds, cars) with an
 * unbounded connect(), and calls, relays and forwarders paged one guessed device with no deadline.
 */
internal object ClassicConnector {
    class Connected(
        val socket: BluetoothSocket,
        val session: SecureBluetoothSession,
        val kind: ClassicCandidateKind,
    )

    /** Inquiry lasts about 10.24 s in the controller; Android reports DISCOVERY_FINISHED after name lookups. */
    private const val INQUIRY_MS = 13_000L
    private const val INQUIRY_CANDIDATES = 4

    /**
     * Opens and authenticates a Classic link to [discovered], the device a BLE announcement came from.
     * [announcedPrefix] null means a direct DEBUG connection to exactly [discovered]. With
     * [verifyLinkPrefix] the BMH2 link identity must match the announcement. False means the
     * announcement came from a relay that advertises its endpoint's prefix: then addresses remembered
     * for that endpoint are not tried, and reaching the endpoint itself is refused, because a direct
     * link would silently replace the requested relay route. [handshake] performs the
     * BMH2 link handshake on the connected socket; [verify] adds role checks. [onSocket] exposes each
     * attempt's socket so a concurrent close() can abort it.
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        adapter: BluetoothAdapter,
        discovered: BluetoothDevice,
        announcedPrefix: ByteArray?,
        role: ClassicRole,
        verifyLinkPrefix: Boolean,
        totalBudgetMs: Long,
        allowInquiry: Boolean,
        isCancelled: () -> Boolean,
        onSocket: (BluetoothSocket) -> Unit,
        handshake: (BluetoothSocket) -> SecureBluetoothSession,
        verify: (SecureBluetoothSession) -> Unit = {},
        onStatus: (String) -> Unit = {},
        /**
         * Only the discovered device itself or its exact bonded match (classicExactDevicePlan): no
         * bonded fallback and no inquiry. A call relay's relayed child must be the phone whose
         * advertisement it chose, not whichever bonded phone answers first.
         */
        exactDeviceOnly: Boolean = false,
    ): Connected {
        // Pairing requests and bond changes are only logged, for the whole connect (classic-pairing-request-logging).
        val watch = if (CocPolicy.current().has<ClassicPairingRequestLogging>()) {
            ClassicPairingWatch.start(context, role.label)
        } else {
            null
        }
        try {
            return connectPlanned(
                context, adapter, discovered, announcedPrefix, role, verifyLinkPrefix, totalBudgetMs, allowInquiry,
                isCancelled, onSocket, handshake, verify, onStatus, exactDeviceOnly,
            )
        } finally {
            watch?.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectPlanned(
        context: Context,
        adapter: BluetoothAdapter,
        discovered: BluetoothDevice,
        announcedPrefix: ByteArray?,
        role: ClassicRole,
        verifyLinkPrefix: Boolean,
        totalBudgetMs: Long,
        allowInquiry: Boolean,
        isCancelled: () -> Boolean,
        onSocket: (BluetoothSocket) -> Unit,
        handshake: (BluetoothSocket) -> SecureBluetoothSession,
        verify: (SecureBluetoothSession) -> Unit,
        onStatus: (String) -> Unit,
        exactDeviceOnly: Boolean,
    ): Connected {
        val bondFree = ClassicFeatures.current.bondFree
        val legacy = !ClassicFeatures.current.usesBoundedConnect
        val relayedRoute = announcedPrefix != null && !verifyLinkPrefix
        val bonded = runCatching { adapter.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
        val fullPlan = if (announcedPrefix == null) {
            listOf(ClassicCandidate(discovered, ClassicCandidateKind.DEBUG))
        } else if (legacy) {
            legacyClassicCandidatePlan(
                role,
                discovered,
                bonded,
                BluetoothDevice::getAddress,
                { runCatching { it.name }.getOrNull() },
                ::classicCandidateCanHostApp,
                { candidate ->
                    runCatching { candidate.uuids }.getOrNull().orEmpty()
                        .any { it.uuid == ClassicTransport.APP_SERVICE.serviceUuid }
                },
            )
        } else {
            classicCandidatePlan(
                discovered,
                bonded,
                BluetoothDevice::getAddress,
                { runCatching { it.name }.getOrNull() },
                ::classicCandidateCanHostApp,
                { candidate ->
                    runCatching { candidate.uuids }.getOrNull().orEmpty()
                        .any { it.uuid == ClassicTransport.APP_SERVICE.serviceUuid }
                },
                learned = if (relayedRoute) null else ClassicAddressBook.lookup(announcedPrefix)?.let { remoteDevice(adapter, it) },
                debugOverride = if (relayedRoute) null else {
                    ClassicAddressBook.debugOverride(announcedPrefix)?.let { remoteDevice(adapter, it) }
                },
                discoveredIsRandomAddress = classicScanAddressIsRandom(discovered),
            )
        }
        val plan = if (exactDeviceOnly) classicExactDevicePlan(fullPlan) else fullPlan
        val unresolved = !legacy && announcedPrefix != null && classicPlanIsUnresolved(plan)
        Log.i(
            "BluetoothVideo",
            "Classic connect plan | role=${role.label} | secure=${classicRoleUsesSecureRfcomm(role, bondFree)} | " +
                "bondFree=$bondFree | legacy=$legacy | unresolved=$unresolved | candidates=" +
                plan.joinToString(",") { "${it.kind.label}:...${it.device.address.takeLast(5)}" },
        )
        // At most 6 distinct RFCOMM peers per phone (classic-rfcomm-peer-cap): refuse a new one as capacity.
        ClassicRfcommCap.admitOrThrow(plan.map { it.device.address }) { Log.i("BluetoothVideo", it) }
        val startedNs = System.nanoTime()
        var failure: Exception? = null
        if (plan.isNotEmpty()) {
            try {
                return attempt(
                    plan, adapter, announcedPrefix, role, verifyLinkPrefix,
                    // Legacy calls checked identity only at the endpoint handshake.
                    checkLinkIdentity = !(legacy && role == ClassicRole.PRIVATE_CALL),
                    timeoutMs = { if (legacy) legacyClassicAttemptTimeoutMs(role, it.kind) else classicAttemptTimeoutMs(it.kind) },
                    totalBudgetMs = if (legacy) Long.MAX_VALUE else totalBudgetMs,
                    isCancelled = isCancelled, onSocket = onSocket, handshake = handshake, verify = verify,
                )
            } catch (error: Exception) {
                if (isCancelled()) throw error
                failure = error
            }
        }
        if (unresolved && bondFree && allowInquiry && !exactDeviceOnly && !isCancelled()) {
            onStatus("Looking for nearby Classic phones; the other phone must be visible...")
            val tried = plan.map { it.device.address.uppercase() }.toSet()
            val found = ClassicInquiry.findAppHosts(context, adapter, INQUIRY_MS, isCancelled)
                .filter { it.address.uppercase() !in tried }
                .take(INQUIRY_CANDIDATES)
                .map { ClassicCandidate(it, ClassicCandidateKind.INQUIRY) }
            Log.i("BluetoothVideo", "Classic inquiry found ${found.size} candidate phone(s) for role=${role.label}")
            if (found.isNotEmpty() && !isCancelled()) {
                try {
                    return attempt(
                        found, adapter, announcedPrefix, role, verifyLinkPrefix, checkLinkIdentity = true,
                        timeoutMs = { classicAttemptTimeoutMs(it.kind) }, totalBudgetMs = Long.MAX_VALUE,
                        isCancelled = isCancelled, onSocket = onSocket, handshake = handshake, verify = verify,
                    )
                } catch (error: Exception) {
                    if (isCancelled()) throw error
                    failure = error
                }
            }
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        Log.i(
            "BluetoothVideo",
            "Classic connect failed | role=${role.label} | unresolved=$unresolved | elapsedMs=$elapsedMs | " +
                "error=${failure?.let { "${it.javaClass.simpleName}: ${it.message} | ${CocSocketErrors.describe(it)}" } ?: "no candidate"}",
        )
        if (unresolved) throw ClassicPeerUnresolvedException(classicUnresolvedPeerMessage(bondFree), failure)
        if (legacy && role == ClassicRole.STREAM_SENDER && bonded.isEmpty()) {
            throw IOException(
                "Pair these phones in Android Bluetooth settings, or turn on Connect without pairing " +
                    "(Settings > Features) on both phones, then retry Classic",
                failure,
            )
        }
        throw failure as? IOException ?: IOException(failure?.message ?: "Classic connection failed", failure)
    }

    @SuppressLint("MissingPermission")
    private fun attempt(
        candidates: List<ClassicCandidate<BluetoothDevice>>,
        adapter: BluetoothAdapter,
        announcedPrefix: ByteArray?,
        role: ClassicRole,
        verifyLinkPrefix: Boolean,
        checkLinkIdentity: Boolean,
        timeoutMs: (ClassicCandidate<BluetoothDevice>) -> Long,
        totalBudgetMs: Long,
        isCancelled: () -> Boolean,
        onSocket: (BluetoothSocket) -> Unit,
        handshake: (BluetoothSocket) -> SecureBluetoothSession,
        verify: (SecureBluetoothSession) -> Unit,
    ): Connected {
        var openedKind = ClassicCandidateKind.DISCOVERED
        val bonded = runCatching { adapter.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet() }
            .getOrDefault(emptySet())
        // The socket security of this whole connect is fixed now: no bond-free connect turns secure midway.
        val bondFree = ClassicFeatures.current.bondFree
        val guardRuntime = CocPolicy.current().has<ClassicConnectRuntimeGuard>()
        val (socket, session) = ClassicConnectProgress.track(candidates.map { it.device.address }) {
            connectAuthenticatedClassicCandidate(
                candidates,
                timeoutMs,
                isCancelled,
                { candidate ->
                    openedKind = candidate.kind
                    ClassicSockets.create(candidate.device, role, bondFree)
                },
                onSocket,
                { candidateSocket ->
                    if (guardRuntime) {
                        // A framework RuntimeException (Briar: NPE on Huawei Android 9) is a failed attempt.
                        ClassicConnectGuard.connect(role.label, { Log.i("BluetoothVideo", it) }) { candidateSocket.connect() }
                    } else {
                        candidateSocket.connect()
                    }
                    handshake(candidateSocket)
                },
                { candidateSession ->
                    if (checkLinkIdentity) {
                        classicLinkIdentityProblem(
                            candidateSession.peerNodeId.toByteArray(),
                            announcedPrefix,
                            verifyLinkPrefix,
                            peerPseudonymous = candidateSession.peerPseudonymous,
                            resolvedCandidate = openedKind.resolved,
                        )?.let { problem -> throw ClassicLinkIdentityException(problem) }
                    }
                    verify(candidateSession)
                },
                { candidate, error ->
                    Log.i(
                        "BluetoothVideo",
                        "Classic candidate failed | role=${role.label} | kind=${candidate.kind.label} | " +
                            "address=...${candidate.device.address.takeLast(5)} | " +
                            "bonded=${candidate.device.address.uppercase() in bonded} | " +
                            "error=${error.javaClass.simpleName}: ${error.message} | ${CocSocketErrors.describe(error)}",
                    )
                    // An address that authenticated as someone else is wrong, not just out of reach.
                    if (announcedPrefix != null && classicLearnedAddressDisproved(candidate.kind, error)) {
                        ClassicAddressBook.forget(announcedPrefix, candidate.device.address)
                    }
                },
                totalBudgetMs,
            )
        }
        ClassicRfcommCap.note(socket)
        learnClassicPeerAddress(socket, session.peerNodeId, "outgoing", session.peerPseudonymous)
        Log.i(
            "BluetoothVideo",
            "Classic connected | role=${role.label} | kind=${openedKind.label} | address=...${socket.remoteDevice.address.takeLast(5)}",
        )
        return Connected(socket, session, openedKind)
    }

    private fun remoteDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice? =
        runCatching { adapter.getRemoteDevice(address) }.getOrNull()
}

/**
 * Experimental bond-free first contact: a Classic inquiry for phones/computers in discoverable mode,
 * strongest first. A phone is discoverable only while its Bluetooth settings screen is open or after
 * the user accepts Android's visibility prompt, and inquiry costs about 10 s of radio time, so it
 * runs only after every other candidate failed for an unresolved peer.
 */
internal object ClassicInquiry {
    @SuppressLint("MissingPermission")
    fun findAppHosts(
        context: Context,
        adapter: BluetoothAdapter,
        timeoutMs: Long,
        isCancelled: () -> Boolean,
    ): List<BluetoothDevice> {
        val found = LinkedHashMap<String, Pair<BluetoothDevice, Int>>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.bluetoothDeviceExtra() ?: return
                        val major = intent.bluetoothClassExtra()?.majorDeviceClass
                        if (!classicMajorClassCanHostApp(major)) return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        synchronized(found) { found[device.address.uppercase()] = device to rssi }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> started.countDown()
                    // A FINISHED from an earlier, cancelled inquiry must not end this one.
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (started.count == 0L) finished.countDown()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val appContext = context.applicationContext
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        // These actions are protected system broadcasts; no other app can send them.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            EncoderBenchGuard.check("ClassicConnector.discovery")
            val startedDiscovery = runCatching { adapter.startDiscovery() }.getOrDefault(false)
            if (!startedDiscovery) {
                Log.i("BluetoothVideo", "Classic inquiry could not start (permission, location or radio state)")
                return emptyList()
            }
            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
            while (!isCancelled() && System.nanoTime() < deadlineNs) {
                if (finished.await(200, TimeUnit.MILLISECONDS)) break
            }
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        return synchronized(found) { found.values.sortedByDescending { it.second }.map { it.first } }
    }

    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private fun Intent.bluetoothClassExtra(): BluetoothClass? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
    }
}
