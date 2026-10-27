package com.example.bluetoothtest

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** In-memory byte pipe: reads block until data or close; close fails both ends like a dropped link. */
internal class SfPipe(private val capacity: Int = Int.MAX_VALUE) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val buffer = java.util.ArrayDeque<Byte>()
    @Volatile var closed = false

    val input = object : InputStream() {
        override fun read(): Int = lock.withLock {
            while (buffer.isEmpty()) {
                if (closed) throw IOException("Pipe closed")
                available.await()
            }
            buffer.removeFirst().toInt().and(0xff).also { available.signalAll() }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = lock.withLock {
            if (len == 0) return 0
            while (buffer.isEmpty()) {
                if (closed) throw IOException("Pipe closed")
                available.await()
            }
            var count = 0
            while (count < len && buffer.isNotEmpty()) b[off + count++] = buffer.removeFirst()
            available.signalAll()
            count
        }
    }

    val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = lock.withLock {
            while (!closed && buffer.size + len > capacity && buffer.isNotEmpty()) available.await()
            if (closed) throw IOException("Pipe closed")
            for (i in off until off + len) buffer.addLast(b[i])
            available.signalAll()
        }
    }

    fun close() = lock.withLock {
        closed = true
        available.signalAll()
    }
}

/**
 * Test hosts only. The exchange watchdogs (10 s without a chunk, 120 s in total) run on the real clock, so a machine that is
 * saturated by other builds could end a correct exchange with TIMEOUT. Limits that are still the production defaults get ten
 * times the headroom; a test that sets its own value (a watchdog test) keeps it untouched.
 */
internal fun StoreForwardLimits.withLoadHeadroom(): StoreForwardLimits {
    val production = StoreForwardLimits()
    return copy(
        progressTimeoutMs = if (progressTimeoutMs == production.progressTimeoutMs) 10 * progressTimeoutMs else progressTimeoutMs,
        sessionTimeoutMs = if (sessionTimeoutMs == production.sessionTimeoutMs) 10 * sessionTimeoutMs else sessionTimeoutMs,
    )
}

/** What a finished pair of sessions reported. */
internal class SfPair(val initiator: StoreForwardSyncResult, val responder: StoreForwardSyncResult)

/** A pair of live sessions on one in-memory link, for tests that act while the exchange runs. */
internal class SfLive(
    val sessionA: StoreForwardLinkSession,
    val sessionB: StoreForwardLinkSession,
    private val threadA: Thread,
    private val threadB: Thread,
    private val resultA: () -> StoreForwardSyncResult?,
    private val resultB: () -> StoreForwardSyncResult?,
    private val errorB: () -> Throwable?,
    val close: () -> Unit,
) {
    fun join(timeoutMs: Long = 120_000): SfPair {
        threadA.join(timeoutMs)
        threadB.join(timeoutMs)
        // A hung exchange names where each thread is stuck, so a failure on a slow machine is diagnosable.
        val stuck = listOf(threadA, threadB).filter { it.isAlive }.joinToString(", ") { t ->
            "${t.name}: " + t.stackTrace.take(12).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        }
        if (stuck.isNotEmpty()) println("EXCHANGE STILL RUNNING AFTER ${timeoutMs} ms: $stuck | A: ${sessionA.debugState()} | B: ${sessionB.debugState()}")
        close()
        errorB()?.let { throw AssertionError("Responder failed", it) }
        return SfPair(
            requireNotNull(resultA()) { "Initiator did not finish" },
            requireNotNull(resultB()) { "Responder did not finish" },
        )
    }
}

/** A hand-driven peer for hostile-input tests: the real session runs as the responder on the other end of the link. */
internal class SfScripted(val peerLink: SecureBluetoothSession, private val close: () -> Unit, private val done: () -> StoreForwardSyncResult?, private val thread: Thread) {
    fun send(message: StoreForwardMessage) {
        peerLink.writer.write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(message))
        peerLink.writer.flush()
    }

    fun sendRaw(payload: ByteArray) {
        peerLink.writer.write(SecureRecordType.STORE_FORWARD, payload)
        peerLink.writer.flush()
    }

    /** Next raw BSF1 record from the session under test; null when the link closed. */
    fun receiveRecord(): ByteArray? = try {
        peerLink.reader.read().payload
    } catch (error: IOException) {
        null
    }

    /** Next message from the session under test, decoded with the v2 forms allowed; null when the link closed. */
    fun receive(): StoreForwardMessage? = try {
        StoreForwardWire.decode(peerLink.reader.read().payload, v2 = true)
    } catch (error: IOException) {
        null
    }

    /** Reads until [predicate] matches or the link closes; returns everything read. */
    fun receiveUntil(predicate: (StoreForwardMessage) -> Boolean): List<StoreForwardMessage> {
        val seen = ArrayList<StoreForwardMessage>()
        while (true) {
            val message = receive() ?: return seen
            seen += message
            if (predicate(message)) return seen
        }
    }

    /** The link dies under the session (a radio loss): both directions close at once. */
    fun drop() = close()

    fun finish(timeoutMs: Long = 120_000): StoreForwardSyncResult {
        thread.join(timeoutMs)
        close()
        return requireNotNull(done()) { "The session under test did not finish" }
    }
}

/** Shared fixtures for the Messaging v2 tests: temp phones, a controllable wall clock and in-memory links. */
internal class SfRig {
    val roots = ArrayList<File>()
    val clockMs = AtomicLong(1_790_000_000_000L)
    val clock: () -> Long = { clockMs.get() }
    private val aes = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }

    fun cleanUp() = roots.forEach { it.deleteRecursively() }

    inner class Phone(
        val v2: Boolean = true,
        val v2Limits: MessagingV2Limits = MessagingV2Limits(),
        limits: StoreForwardLimits = StoreForwardLimits(),
        root: File? = null,
        identityKeys: java.security.KeyPair? = null,
        mailboxKey: SoftwareMailboxKey? = null,
        val freeSpace: () -> Long = { Long.MAX_VALUE },
        /** This phone offers a chat session (HELLO flag CHAT). */
        val chat: Boolean = false,
        /** Keep the responder cooldown and hourly cap of [limits] (tests of the admission rules); the default switches them off. */
        val keepCooldown: Boolean = false,
        /** Give the default exchange watchdogs [withLoadHeadroom]; false for a test that waits for the production deadline on a virtual clock. */
        val loadHeadroom: Boolean = true,
    ) {
        val dir: File = root ?: Files.createTempDirectory("sf2-test").toFile().also(roots::add)
        val keys = identityKeys ?: generateEcKeyPair()
        val identity = KeyPairSigningIdentity(keys)
        val mailbox = mailboxKey ?: SoftwareMailboxKey.generate()
        val effectiveLimits = (if (keepCooldown) limits else limits.copy(exchangeCooldownMs = 0, maxExchangesPerHour = Int.MAX_VALUE))
            .let { if (loadHeadroom) it.withLoadHeadroom() else it }
        val node: StoreForwardNode = StoreForwardNode.open(
            dir, identity, mailbox, aes, effectiveLimits, clock,
            messagingV2 = if (v2) {
                MessagingV2Setup(
                    if (keepCooldown) v2Limits else v2Limits.copy(contactExchangeCooldownMs = 0, contactMaxExchangesPerHour = Int.MAX_VALUE),
                    freeSpace,
                )
            } else null,
        )
        val nodeId get() = identity.nodeId
        val engine: MessagingV2Engine get() = requireNotNull(node.v2) { "Messaging v2 is off on this phone" }

        fun reopen(): Phone = Phone(v2, v2Limits, limits.copy(), dir, keys, mailbox, freeSpace, chat, keepCooldown, loadHeadroom)
        private val limits = limits

        fun knows(other: Phone) = node.contacts.addAccepted(other.node.ownCard, "peer")
    }

    /** Two phones that accepted each other's cards. */
    fun contacts(a: Phone, b: Phone) {
        a.knows(b)
        b.knows(a)
    }

    private fun caps() = NodeCapabilities(NodeCapabilities.TRANSPORT_CLASSIC, 0, 0, 0, 0)

    fun open(
        a: Phone,
        b: Phone,
        onAChunk: (StoreForwardLinkSession, Int, () -> Unit) -> Unit = { _, _, _ -> },
        onBChunk: (StoreForwardLinkSession, Int, () -> Unit) -> Unit = { _, _, _ -> },
        logs: MutableList<String>? = null,
        monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    ): SfLive {
        val aToB = SfPipe()
        val bToA = SfPipe()
        val close = { aToB.close(); bToA.close() }
        val keyAB = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyBA = ByteArray(32).also(SecureRandom()::nextBytes)
        val linkA = SecureBluetoothSession(b.nodeId, caps(), SecureRecordReader(bToA.input, keyBA), SecureRecordWriter(aToB.output, keyAB))
        val linkB = SecureBluetoothSession(a.nodeId, caps(), SecureRecordReader(aToB.input, keyAB), SecureRecordWriter(bToA.output, keyBA))
        linkA.reader.acceptStoreForward = true
        linkB.reader.acceptStoreForward = true
        fun sink(prefix: String): (String) -> Unit = { line ->
            println("$prefix: $line")
            logs?.let { synchronized(it) { it += "$prefix: $line" } }
        }
        lateinit var sessionA: StoreForwardLinkSession
        lateinit var sessionB: StoreForwardLinkSession
        sessionA = StoreForwardLinkSession(linkA, a.node, initiator = true, closeLink = close, log = sink("A"),
            afterChunkWritten = { onAChunk(sessionA, it, close) }, chatCapable = a.chat, monotonicMs = monotonicMs)
        sessionB = StoreForwardLinkSession(linkB, b.node, initiator = false, closeLink = close, log = sink("B"),
            afterChunkWritten = { onBChunk(sessionB, it, close) }, chatCapable = b.chat, monotonicMs = monotonicMs)
        var resultA: StoreForwardSyncResult? = null
        var resultB: StoreForwardSyncResult? = null
        var errorB: Throwable? = null
        val started = CountDownLatch(1)
        val threadB = thread(name = "sf-test-responder") {
            try {
                started.countDown()
                resultB = sessionB.run()
            } catch (error: Throwable) {
                errorB = error
            }
        }
        started.await()
        val threadA = thread(name = "sf-test-initiator") { resultA = sessionA.run() }
        return SfLive(sessionA, sessionB, threadA, threadB, { resultA }, { resultB }, { errorB }, close)
    }

    /**
     * A session clock that runs [LOAD_SLOWDOWN] times slower than real time, for tests of a long transfer that are not about the
     * watchdogs: the exchange deadlines (progress, liveness, total) are measured on it, so a host saturated by other builds
     * cannot end a correct exchange with TIMEOUT, while a stuck exchange still ends at the test's own real-time limit.
     */
    fun slowClock(): () -> Long {
        val start = System.nanoTime()
        return { (System.nanoTime() - start) / 1_000_000 / LOAD_SLOWDOWN }
    }

    fun sync(
        a: Phone,
        b: Phone,
        onAChunk: (StoreForwardLinkSession, Int, () -> Unit) -> Unit = { _, _, _ -> },
        onBChunk: (StoreForwardLinkSession, Int, () -> Unit) -> Unit = { _, _, _ -> },
        logs: MutableList<String>? = null,
        monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    ): SfPair = open(a, b, onAChunk, onBChunk, logs, monotonicMs).join()

    /** The real session of [phone] as the responder, with the other end of the link driven by the test. */
    fun scripted(
        phone: Phone,
        peerNode: NodeId,
        monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
        /** Bytes the session under test may write before it blocks on a peer that does not read. */
        capacity: Int = Int.MAX_VALUE,
    ): SfScripted {
        val aToB = SfPipe()
        val bToA = SfPipe(capacity)
        val close = { aToB.close(); bToA.close() }
        val keyAB = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyBA = ByteArray(32).also(SecureRandom()::nextBytes)
        val peerLink = SecureBluetoothSession(phone.nodeId, caps(), SecureRecordReader(bToA.input, keyBA), SecureRecordWriter(aToB.output, keyAB))
        peerLink.reader.acceptStoreForward = true
        val underTest = SecureBluetoothSession(peerNode, caps(), SecureRecordReader(aToB.input, keyAB), SecureRecordWriter(bToA.output, keyBA))
        underTest.reader.acceptStoreForward = true
        val session = StoreForwardLinkSession(underTest, phone.node, initiator = false, closeLink = close, chatCapable = phone.chat, monotonicMs = monotonicMs)
        var result: StoreForwardSyncResult? = null
        val t = thread(name = "sf-test-under-test") { result = session.run() }
        return SfScripted(peerLink, close, { result }, t)
    }

    fun hello(version: Int = 2, flags: Int = StoreForwardHelloFlags.FILES) =
        StoreForwardMessage.Hello(version, 0, 1_000_000, ByteArray(8), if (version == 2) flags else 0)

    fun bytes(size: Int, seed: Int = 1): ByteArray = ByteArray(size).also { java.util.Random(seed.toLong()).nextBytes(it) }
}

private const val LOAD_SLOWDOWN = 50L
