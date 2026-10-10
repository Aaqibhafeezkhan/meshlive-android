package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Opaque full-duplex forwarding for a private call. Endpoint encryption stays caller-to-callee. */
class BluetoothPrivateCallRelay(
    private val onStatus: (String) -> Unit,
    private val onStopped: () -> Unit,
) : Closeable {
    private data class TransportConnection(
        val input: InputStream,
        val output: OutputStream,
        val closeConnection: () -> Unit,
        val socket: BluetoothSocket? = null,
        /** Current LE PHY summary of this hop ("PHY TX 2M / RX 2M"); empty when unknown. */
        val phySummary: () -> String = { "" },
    ) : Closeable {
        override fun close() = closeConnection()
    }

    /** PHY this relay requested on its callee hop (it is central there); 2M unless remembered bad. */
    @Volatile private var calleeLinkPhy: BlePhyPreference = BlePhyPreference.TWO_M
    /** System.nanoTime when both hops authenticated and forwarding started (0 = not yet). */
    @Volatile private var forwardingStartNs = 0L
    @Volatile private var lastCallerProgressNs = 0L
    @Volatile private var lastCalleeProgressNs = 0L
    @Volatile private var callRelayTransport: BluetoothTransportOption = BluetoothTransportOption.BLE_GATT

    private val running = AtomicBoolean(false)
    private val closeNotBeforeNanos = AtomicLong(0L)
    @Volatile private var worker: Thread? = null
    @Volatile private var reverseWorker: Thread? = null
    /** Classic: the RFCOMM server socket; LE CoC: a lease on the role's long-lived listener. */
    @Volatile private var upstreamServerSocket: java.io.Closeable? = null
    @Volatile private var downstreamControl: BleL2capControlConnection? = null
    @Volatile private var upstreamControl: BleL2capControlServer? = null
    @Volatile private var upstreamAdvertiser: BleDiscoveryAdvertiser? = null
    @Volatile private var upstreamGattServer: GattVideoServer? = null
    @Volatile private var downstream: TransportConnection? = null
    @Volatile private var upstream: TransportConnection? = null
    @Volatile private var downstreamSession: SecureBluetoothSession? = null
    @Volatile private var upstreamSession: SecureBluetoothSession? = null

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        adapter: BluetoothAdapter,
        callee: BleL2capPeer,
        transport: BluetoothTransportOption,
        /** Relay chains (PrivateCallRelayChain); false keeps the one-relay rule exactly. */
        chainEnabled: Boolean = DebugTunables.callRelayChain,
    ) {
        check(running.compareAndSet(false, true)) { "Private-call relay is already running" }
        callRelayTransport = transport
        worker = thread(name = "BluetoothPrivateCallRelay") {
            try {
                require(callee.announcement.isPrivateCallOffer()) {
                    "Selected phone is not available for a private call"
                }
                require(callee.announcement.transportOption() == transport) {
                    "Selected callee is not using ${transport.shortLabel}"
                }
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val localNodeId = identity.nodeId.toByteArray()
                require(
                    PrivateCallRelayChain.admitsOffer(
                        callee.announcement,
                        localNodeId.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                        chainEnabled,
                    ),
                ) { "Private-call offer is outside the relay chain limit" }
                val capabilities = PrivateCallRelayChain.relayCapabilities(
                    LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                    chainEnabled,
                )
                val checkChild = { session: SecureBluetoothSession ->
                    PrivateCallRelayChain.childProblem(
                        callee.announcement,
                        session.peerNodeId.toByteArray(),
                        session.peerCapabilities.maxChildren,
                        localNodeId,
                        chainEnabled,
                        session.peerCapabilities.transportMask,
                    )?.let { problem -> throw IllegalArgumentException(problem) }
                    Unit
                }
                val (childConnection, childSession) = if (
                    transport == BluetoothTransportOption.CLASSIC_RFCOMM
                ) {
                    connectAuthenticatedClassicCallee(
                        context, adapter, callee, identity, capabilities,
                        PrivateCallRelayChain.verifyChildLinkPrefix(callee.announcement, chainEnabled), checkChild,
                        PrivateCallRelayChain.classicExactChildOnly(callee.announcement, chainEnabled),
                    )
                } else {
                    // Adaptive PHY memory on the hop this relay opens as central: 1M from the start
                    // for a remembered-bad callee (or, for a chain, the relay advertising its offer).
                    calleeLinkPhy = AdaptivePhyMemory.phyFor(
                        transport,
                        callee.announcement.nodeIdPrefix,
                        BlePhyPreference.TWO_M,
                        "call-relay",
                        relayedOffer = callee.announcement.routeCost > 0,
                    )
                    val connection = connectCallee(context, callee.device, transport)
                    downstream = connection
                    connection to authenticateCallee(connection, identity, capabilities, checkChild).also { session ->
                        AdaptivePhyMemory.bind(
                            runCatching { callee.device.address }.getOrNull(),
                            transport,
                            session.peerNodeId.shortLabel(),
                        )
                    }
                }
                downstreamSession = childSession
                onStatus("Private-call relay authenticated callee ${childSession.peerNodeId.shortLabel()}.")
                if (callee.announcement.routeCost > 0) {
                    onStatus(
                        "Private-call relay chain: child relay ${childSession.peerNodeId.shortLabel()} reaches " +
                            "callee ${callee.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }} through " +
                            "${callee.announcement.routeCost} relay(s); this relay advertises " +
                            "${callee.announcement.routeCost + 1}.",
                    )
                }

                val callerConnection = acceptCaller(
                    context,
                    adapter,
                    transport,
                    callee.announcement,
                    stopClassicAdvertisingOnAccept = chainEnabled,
                    parentWaitMs = if (chainEnabled) PrivateCallRelayChain.PARENT_WAIT_MS else null,
                )
                upstream = callerConnection
                val callerSession = SecureBluetoothHandshake.respond(
                    BufferedInputStream(callerConnection.input, BUFFER_BYTES),
                    BufferedOutputStream(callerConnection.output, BUFFER_BYTES),
                    identity,
                    capabilities,
                )
                upstreamSession = callerSession
                require(callerSession.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                    "Caller did not authenticate ${transport.shortLabel} support"
                }
                PrivateCallRelayChain.parentProblem(
                    callerSession.peerNodeId.toByteArray(),
                    childSession.peerNodeId.toByteArray(),
                    callee.announcement.nodeIdPrefix,
                    localNodeId,
                    chainEnabled,
                )?.let { problem -> throw IllegalArgumentException(problem) }
                learnClassicPeerAddress(callerConnection.socket, callerSession.peerNodeId, "incoming")
                onStatus(
                    "Relaying an endpoint-encrypted private call between " +
                        "${callerSession.peerNodeId.shortLabel()} and ${childSession.peerNodeId.shortLabel()}; " +
                        "this relay cannot decrypt call media.",
                )

                forwardingStartNs = System.nanoTime()
                lastCallerProgressNs = forwardingStartNs
                lastCalleeProgressNs = forwardingStartNs
                reverseWorker = thread(name = "BluetoothPrivateCallRelayReverse") {
                    copyRecords(childSession, callerSession, "callee")
                }
                copyRecords(callerSession, childSession, "caller")
            } catch (error: Exception) {
                if (running.get()) {
                    onStatus("Private-call relay stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                running.set(false)
                closeResources()
                worker = null
                onStopped()
            }
        }
    }

    private fun copyRecords(
        source: SecureBluetoothSession,
        destination: SecureBluetoothSession,
        direction: String,
    ) {
        var forwardedRecord = false
        var reading = true
        try {
            while (running.get()) {
                reading = true
                val record = source.reader.read()
                reading = false
                markProgress(direction)
                require(
                    record.type == SecureRecordType.END_TO_END_HANDSHAKE ||
                        record.type == SecureRecordType.END_TO_END_CONTENT ||
                        record.type == SecureRecordType.LINK_HEARTBEAT,
                ) { "Unexpected $direction private-call record ${record.type}" }
                // Capacity reports describe one hop; across this relay only liveness is forwarded, as
                // a plain version-1 heartbeat (an empty payload is rejected and closed the call).
                if (record.type == SecureRecordType.LINK_HEARTBEAT) {
                    SecureLinkControlProtocol.writeHeartbeat(destination.writer)
                } else {
                    destination.writer.write(record.type, record.payload)
                }
                destination.writer.flush()
                markProgress(if (direction == "caller") "callee" else "caller")
                forwardedRecord = true
            }
        } catch (error: Exception) {
            if (forwardedRecord) armConnectionCloseDrain()
            if (running.get()) noteHopLoss(error, if (reading) source else destination, if (reading) direction else otherSide(direction))
            if (running.getAndSet(false)) {
                onStatus("Private-call $direction path closed: ${error.message ?: error.javaClass.simpleName}")
                closeConnections()
                worker?.interrupt()
                reverseWorker?.interrupt()
            }
        }
    }

    /** [side] ("caller" or "callee") is the hop a record was just read from or written to. */
    private fun markProgress(side: String) {
        if (side == "caller") lastCallerProgressNs = System.nanoTime() else lastCalleeProgressNs = System.nanoTime()
    }

    private fun otherSide(side: String) = if (side == "caller") "callee" else "caller"

    /**
     * A hop failed (IOException): log whether it was an early loss on 2M. The bad-2M memory records it
     * only with `test_relay_phy_guard=false` (relaycall-659efc38 enabled recording; relaycall-b9726176-
     * loop4-1 0004/0005 showed cascaded closes recording healthy S24, M31 and ASUS hops).
     */
    private fun noteHopLoss(error: Exception, session: SecureBluetoothSession, side: String) {
        if (error !is java.io.IOException || callRelayTransport == BluetoothTransportOption.CLASSIC_RFCOMM) return
        val hop = runCatching { session.peerNodeId.shortLabel() }.getOrNull() ?: return
        val connection = if (side == "callee") downstream else upstream
        val phy = runCatching { connection?.phySummary?.invoke() }.getOrNull().orEmpty()
        val requestedTwoM = if (side == "callee") calleeLinkPhy == BlePhyPreference.TWO_M else true
        val stalledAt = callLinkStalledAtNs(
            if (side == "callee") lastCalleeProgressNs else lastCallerProgressNs,
            if (side == "callee") lastCallerProgressNs else lastCalleeProgressNs,
            System.nanoTime(),
        )
        val early = CallRelayHopLoss.isEarly(
            forwardingStartNs, stalledAt, phy, requestedTwoM, NodeLinkLoad.sendLinks(callRelayTransport),
        )
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=call-relay-$side | node=$hop | early_loss=$early | " +
                "stalledAfterMs=${(stalledAt - forwardingStartNs) / 1_000_000} | phy=${phy.ifEmpty { "unknown" }} | " +
                "error=${error.message ?: error.javaClass.simpleName}",
        )
        if (!early) return
        // A relay's hops share its radio and it closes both when either fails, so a hop loss here is not
        // evidence about that pair's 2M ([RelayUpstream]; `test_relay_phy_guard=false` records it again).
        if (AdaptivePhyMemory.skipsRelayingHop(hopRelays = true, guard = DebugTunables.relayPhyGuard)) {
            android.util.Log.i(
                "BluetoothVideo",
                "BENCHMARK adaptive_phy: link=memory | node=$hop | bad2m=not_recorded | reason=early_loss | hop_relays=true",
            )
            return
        }
        AdaptivePhyMemory.recordBad(callRelayTransport, hop, "early_loss")
    }

    @SuppressLint("MissingPermission")
    private fun connectCallee(
        context: Context,
        device: BluetoothDevice,
        transport: BluetoothTransportOption,
    ): TransportConnection {
        return when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM ->
                error("Classic callees connect through connectAuthenticatedClassicCallee")
            BluetoothTransportOption.BLE_L2CAP -> {
                val socket = BleCocClient.connect(
                    context,
                    device,
                    onStatus,
                    calleeLinkPhy,
                    "call-relay-connect",
                ) { downstreamControl = it }
                socketConnection(socket) { downstreamControl?.phySummary.orEmpty() }
            }
            BluetoothTransportOption.BLE_GATT -> gattConnection(
                GattVideoClient.open(
                    context,
                    device,
                    calleeLinkPhy,
                    BleConnectionPriority.HIGH,
                    onStatus,
                    DeviceFamilyPolicy.current().has<CallRelayAcknowledgedGattWrites>(),
                ),
            )
        }
    }

    /**
     * The callee over Classic through the shared connector: class-filtered bonded candidates with
     * per-attempt deadlines. This relay used to page every bonded device (earbuds, cars) with an
     * unbounded secure connect().
     */
    @SuppressLint("MissingPermission")
    private fun connectAuthenticatedClassicCallee(
        context: Context,
        adapter: BluetoothAdapter,
        callee: BleL2capPeer,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        verifyLinkPrefix: Boolean,
        checkChild: (SecureBluetoothSession) -> Unit,
        exactDeviceOnly: Boolean,
    ): Pair<TransportConnection, SecureBluetoothSession> {
        val connected = ClassicConnector.connect(
            context,
            adapter,
            callee.device,
            callee.announcement.nodeIdPrefix,
            ClassicRole.CALL_RELAY,
            verifyLinkPrefix = verifyLinkPrefix,
            totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
            allowInquiry = !exactDeviceOnly,
            isCancelled = { !running.get() },
            onSocket = { socket -> downstream = socketConnection(socket) },
            handshake = { socket ->
                SecureBluetoothHandshake.initiate(
                    BufferedInputStream(socket.inputStream, BUFFER_BYTES),
                    BufferedOutputStream(socket.mediaOutputStream(), BUFFER_BYTES),
                    identity,
                    capabilities,
                )
            },
            verify = { session ->
                require(session.peerCapabilities.transportMask and capabilities.transportMask != 0) {
                    "Callee did not authenticate this transport"
                }
                checkChild(session)
            },
            onStatus = onStatus,
            exactDeviceOnly = exactDeviceOnly,
        )
        val connection = socketConnection(connected.socket)
        downstream = connection
        return connection to connected.session
    }

    private fun authenticateCallee(
        connection: TransportConnection,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        checkChild: (SecureBluetoothSession) -> Unit,
    ): SecureBluetoothSession {
        val session = SecureBluetoothHandshake.initiate(
            BufferedInputStream(connection.input, BUFFER_BYTES),
            BufferedOutputStream(connection.output, BUFFER_BYTES),
            identity,
            capabilities,
        )
        try {
            require(session.peerCapabilities.transportMask and capabilities.transportMask != 0) {
                "Callee did not authenticate this transport"
            }
            // Chain off: the child must be the announced callee, exactly as before. Chain on: a
            // route-cost-0 child still must be; a relayed offer's child must be a different relay.
            checkChild(session)
            // test_coc_phy_after_connect (default off): PHY only now, before media (no-op off CoC).
            downstreamControl?.requestPhyAfterConnect("call-relay")
            return session
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptCaller(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        callee: DiscoveryAnnouncement,
        stopClassicAdvertisingOnAccept: Boolean,
        /** Bounded wait for the one parent (chains on); null waits as before, without a deadline. */
        parentWaitMs: Long?,
    ): TransportConnection {
        val noParent = { cause: Throwable? ->
            IOException("No caller connected within ${(parentWaitMs ?: 0L) / 1_000L} s", cause)
        }
        val announcement = PrivateCallRelayChain.parentOffer(callee, transport.discoveryFlag)
        return when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                val server = ClassicSockets.listen(adapter, "Bluetooth Private Call Relay", ClassicRole.CALL_RELAY)
                upstreamServerSocket = server
                upstreamAdvertiser = BleDiscoveryAdvertiser(adapter, announcement, onStatus)
                    .also(BleDiscoveryAdvertiser::start)
                onStatus("Private-call relay is advertising for its caller.")
                val socket = try {
                    if (parentWaitMs == null) server.accept() else server.accept(parentWaitMs.toInt())
                } catch (error: IOException) {
                    if (parentWaitMs != null && running.get()) throw noParent(error)
                    throw error
                }
                socketConnection(socket).also {
                    // CoC and GATT already leave the air once their one parent connects. A Classic
                    // relay kept advertising, so a chain's caller could pick this hop and skip the
                    // relay above it.
                    if (stopClassicAdvertisingOnAccept) {
                        upstreamAdvertiser?.close()
                        upstreamAdvertiser = null
                    }
                }
            }
            BluetoothTransportOption.BLE_L2CAP -> {
                // One long-lived listener per role (CocListeners): Android 10 never frees a closed LE CoC server.
                val server = CocListeners.listen(adapter, "call-relay")
                upstreamServerSocket = server
                upstreamControl = BleL2capControlServer(
                    context,
                    adapter,
                    server.psm,
                    announcement,
                    onStatus,
                    BlePhyPreference.TWO_M,
                ).also(BleL2capControlServer::start)
                onStatus("Private-call CoC relay is advertising for its caller.")
                val socket = try {
                    if (parentWaitMs == null) server.accept() else server.accept(parentWaitMs)
                } catch (error: IOException) {
                    if (parentWaitMs != null && running.get()) throw noParent(error)
                    throw error
                }
                socketConnection(socket) { upstreamControl?.phySummary.orEmpty() }.also { upstreamControl?.stopAdvertising() }
            }
            BluetoothTransportOption.BLE_GATT -> {
                val connections = LinkedBlockingQueue<GattVideoConnection>(1)
                val server = GattVideoServer(
                    context,
                    adapter,
                    announcement,
                    BlePhyPreference.TWO_M,
                    onStatus,
                ) { connection ->
                    if (!connections.offer(connection)) connection.close()
                }
                upstreamGattServer = server
                server.start()
                onStatus("Private-call GATT relay is advertising for its caller.")
                gattConnection(
                    if (parentWaitMs == null) {
                        connections.take()
                    } else {
                        connections.poll(parentWaitMs, TimeUnit.MILLISECONDS) ?: throw noParent(null)
                    },
                )
            }
        }
    }

    private fun socketConnection(socket: BluetoothSocket, phySummary: () -> String = { "" }) = TransportConnection(
        socket.inputStream,
        socket.mediaOutputStream(),
        socket::close,
        socket,
        phySummary,
    )

    private fun gattConnection(connection: GattVideoConnection) = TransportConnection(
        connection.inputStream,
        connection.outputStream,
        connection::close,
        phySummary = connection.phySummary,
    )

    override fun close() {
        if (!running.getAndSet(false)) return
        closeResources()
        worker?.interrupt()
        reverseWorker?.interrupt()
    }

    private fun closeResources() {
        closeConnections()
        downstreamSession?.close()
        downstreamSession = null
        upstreamSession?.close()
        upstreamSession = null
        downstreamControl?.close()
        downstreamControl = null
        upstreamControl?.close()
        upstreamControl = null
        upstreamAdvertiser?.close()
        upstreamAdvertiser = null
        upstreamGattServer?.close()
        upstreamGattServer = null
        runCatching { upstreamServerSocket?.close() }
        upstreamServerSocket = null
        reverseWorker?.interrupt()
        reverseWorker = null
    }

    private fun closeConnections() {
        awaitConnectionCloseDrain()
        runCatching { downstream?.close() }
        downstream = null
        runCatching { upstream?.close() }
        upstream = null
    }

    private fun armConnectionCloseDrain() {
        closeNotBeforeNanos.compareAndSet(
            0L,
            System.nanoTime() + CONNECTION_CLOSE_DRAIN_MILLIS * 1_000_000L,
        )
    }

    private fun awaitConnectionCloseDrain() {
        while (true) {
            val remainingNanos = closeNotBeforeNanos.get() - System.nanoTime()
            if (remainingNanos <= 0L) return
            try {
                Thread.sleep(
                    remainingNanos / 1_000_000L,
                    (remainingNanos % 1_000_000L).toInt(),
                )
            } catch (_: InterruptedException) {
                // Preserve the short drain even if the other relay worker exits first.
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val CONNECTION_CLOSE_DRAIN_MILLIS = 400L
    }
}

private val BluetoothTransportOption.discoveryFlag: Int
    get() = when (this) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
        BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
        BluetoothTransportOption.BLE_L2CAP -> 0
    }
