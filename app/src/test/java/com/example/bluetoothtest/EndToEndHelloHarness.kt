package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals

/**
 * Real link (BMH2/BMS1) and end-to-end call hellos between two endpoints, directly or through opaque relays that copy
 * records unchanged (moved out of CallCodecFallbackRelayTest so the Design HC negotiation cases share it).
 */
internal object EndToEndHelloHarness {
    /**
     * caller -> [relays] -> callee with real link and end-to-end handshakes; returns the end-to-end
     * capabilities each endpoint authenticated for the other (caller's view, callee's view).
     */
    fun negotiate(
        relays: Int,
        caller: NodeCapabilities,
        callee: NodeCapabilities,
        relayLinkCaps: NodeCapabilities,
    ): Pair<NodeCapabilities, NodeCapabilities> {
        val callerIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val links = List(relays + 1) { linkPair() }
        val calleeSaw = AtomicReference<NodeCapabilities?>()
        val failure = AtomicReference<Throwable?>()
        val workers = mutableListOf<Thread>()
        workers += thread {
            runCatching {
                val (input, output) = links.last().second
                val link = SecureBluetoothHandshake.respond(input, output, calleeIdentity, callee)
                calleeSaw.set(EndToEndVideoHandshake.respond(link, calleeIdentity, callee).peerCapabilities)
            }.onFailure(failure::set)
        }
        for (index in relays downTo 1) {
            val upstream = links[index - 1].second
            val downstream = links[index].first
            workers += thread {
                runCatching {
                    val relayIdentity = KeyPairSigningIdentity(generateEcKeyPair())
                    val child = SecureBluetoothHandshake.initiate(downstream.first, downstream.second, relayIdentity, relayLinkCaps)
                    val parent = SecureBluetoothHandshake.respond(upstream.first, upstream.second, relayIdentity, relayLinkCaps)
                    val back = thread { copyRecords(child, parent) }
                    copyRecords(parent, child)
                    back.join(5_000)
                }
            }
        }
        val (input, output) = links.first().first
        val link = SecureBluetoothHandshake.initiate(input, output, callerIdentity, caller)
        if (relays > 0) assertEquals(relayLinkCaps, link.peerCapabilities) // the hop-local hello is the relay's
        val endpoint = EndToEndVideoHandshake.initiate(link, callerIdentity, caller)
        workers.first().join(120_000)
        links.forEach { (a, b) -> listOf(a, b).forEach { (i, o) -> i.close(); o.close() } }
        workers.forEach { it.join(120_000) }
        failure.get()?.let { throw it }
        assertEquals(calleeIdentity.nodeId, endpoint.peerNodeId)
        return endpoint.peerCapabilities to requireNotNull(calleeSaw.get())
    }

    private fun copyRecords(source: SecureBluetoothSession, destination: SecureBluetoothSession) {
        runCatching {
            while (true) {
                val record = source.reader.read()
                destination.writer.write(record.type, record.payload)
                destination.writer.flush()
            }
        }
    }

    private fun linkPair(): Pair<Pair<InputStream, OutputStream>, Pair<InputStream, OutputStream>> {
        val aToB = QueuePipe()
        val bToA = QueuePipe()
        return (bToA.input to aToB.output) to (aToB.input to bToA.output)
    }

    private class QueuePipe {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw java.io.IOException("closed")
                if (len > 0) chunks.put(b.copyOfRange(off, off + len))
            }
            override fun close() {
                closed = true
                chunks.put(ByteArray(0))
            }
        }
        val input = object : InputStream() {
            private var current = ByteArray(0)
            private var offset = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (offset == current.size) {
                    if (closed && chunks.isEmpty()) return -1
                    val next = chunks.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (next.isEmpty()) {
                        chunks.put(next)
                        return -1
                    }
                    current = next
                    offset = 0
                }
                val count = minOf(len, current.size - offset)
                System.arraycopy(current, offset, b, off, count)
                offset += count
                return count
            }
            override fun close() {
                closed = true
                chunks.put(ByteArray(0))
            }
        }
    }
}
