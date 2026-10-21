package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design GR option C (`GR1`) after review 1: recovery of a GATT value lost in the receiving host. */
class GattChunkRecoveryTest {
    private var now = 1_000_000_000L
    private val clock = { now }
    private val logs = mutableListOf<String>()

    private inner class Side(name: String, enabled: Boolean = true, payload: Int = 16) {
        val recovery: GattChunkRecovery? =
            if (enabled) GattChunkRecovery(name, nanoTime = clock, log = { logs += "$name: $it" }, startThread = false) else null
        val air = mutableListOf<ByteArray>()
        val failures = mutableListOf<String>()
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = payload
            override val recovery: GattChunkRecovery? = this@Side.recovery
            override fun sendPacket(value: ByteArray) {
                air += value.copyOf()
                // The stack may reuse the array it was given: the history must not care.
                value.fill(0x7f)
            }
        }
        val pipe = GattBytePipe(
            capacity = 8_192,
            gapFastFail = { true },
            nanoTime = clock,
            recovery = recovery,
            dropEvery = 0,
            debugLog = { logs += "$name: $it" },
            onFailure = failures::add,
        )

        fun start() {
            recovery?.start(output)
        }

        /** What the link's writer thread does when the data writer is idle. */
        fun flush() {
            output.sendPriority()
        }
    }

    private fun isData(value: ByteArray) = value[0] == 1.toByte()

    private fun seq(value: ByteArray): Int =
        ((value[1].toInt() and 0xff) shl 24) or ((value[2].toInt() and 0xff) shl 16) or
            ((value[3].toInt() and 0xff) shl 8) or (value[4].toInt() and 0xff)

    /** Delivers what [from] put on the air to [to], dropping the values [drop] selects (a host loss). */
    private fun pump(from: Side, to: Side, drop: (ByteArray) -> Boolean = { false }): List<ByteArray> {
        val values = from.air.toList()
        from.air.clear()
        val dropped = values.filter(drop)
        for (value in values) if (value !in dropped) handleIncoming(to.pipe, value)
        return dropped
    }

    private fun readAll(pipe: GattBytePipe): ByteArray {
        val out = ByteArrayOutputStream()
        while (pipe.available() > 0) {
            val buffer = ByteArray(pipe.available())
            val count = pipe.read(buffer, 0, buffer.size)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun connected(payload: Int = 16): Pair<Side, Side> {
        val a = Side("a", payload = payload)
        val b = Side("b", payload = payload)
        a.start()
        b.start()
        a.flush()
        b.flush()
        pump(a, b)
        pump(b, a)
        assertTrue(a.recovery!!.peerHello != null && b.recovery!!.peerHello != null)
        return a to b
    }

    private fun source(size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }

    private fun advance(ms: Long) {
        now += ms * 1_000_000L
    }

    @Test
    fun aSingleLostValueIsResentByteForByteAndTheCallContinues() {
        val (a, b) = connected()
        val data = source(64)
        a.output.write(data)
        assertEquals(1, pump(a, b) { isData(it) && seq(it) == 1 }.size)
        b.flush()
        pump(b, a)
        a.flush()
        pump(a, b)

        assertArrayEquals(data, readAll(b.pipe))
        assertTrue(b.failures.isEmpty())
        assertTrue(logs.any { it.startsWith("a: event=resend | seq=1") && "queuedAheadMs=" in it })
        assertTrue(logs.any { it.startsWith("b: event=recovered | seq=1") })
    }

    @Test
    fun theHistoryKeepsTheOriginalBytesWhenTheSentArrayIsReused() {
        val (a, b) = connected()
        // Three values: chunk 2 arrives behind the lost chunk 1, so the reader sees the gap at once. (With only two, chunk 1
        // is a tail loss that only the HIGH probe finds, which highFindsAValueLostJustBeforeTheSenderWentQuiet covers.)
        val data = source(48)
        a.output.write(data)
        val original = a.air.first { isData(it) && seq(it) == 1 }.copyOf()
        pump(a, b) { isData(it) && seq(it) == 1 }
        b.flush()
        pump(b, a)
        a.flush()
        val resend = a.air.single { isData(it) }
        assertArrayEquals(original, resend)
    }

    @Test
    fun aCallbackOnlyEnqueuesWhileTheSenderIsBlockedInASend() {
        val sendCalls = AtomicInteger()
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val link = GattChunkRecovery("server", nanoTime = clock, log = { logs += it }, startThread = false)
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = 16
            override val recovery: GattChunkRecovery? = link
            override fun sendPacket(value: ByteArray) {
                // The second data value waits for its "onNotificationSent", as a server notification does.
                if (sendCalls.incrementAndGet() == 3) {
                    blocked.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val pipe = GattBytePipe(recovery = link, nanoTime = clock, dropEvery = 0, debugLog = {}, onFailure = {})
        link.start(output)
        val writer = Thread { output.write(source(32)) }
        writer.start()
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        val callsBefore = sendCalls.get()

        val callback = Thread { handleIncoming(pipe, Gr1.nack(listOf(Gr1.SeqRange(0, 1)))) }
        callback.start()
        callback.join(500)

        assertFalse("the NACK callback blocked", callback.isAlive)
        assertEquals(callsBefore, sendCalls.get())
        release.countDown()
        writer.join(2_000)
        assertFalse(writer.isAlive)
        // The data writer drained the queued resend before its next value, or the idle path sends it now.
        output.sendPriority()
        assertTrue(sendCalls.get() > callsBefore)
    }

    @Test
    fun aLostNackAndThenALostResendRecoverInTheSecondAndThirdRounds() {
        val (a, b) = connected()
        val data = source(64)
        a.output.write(data)
        pump(a, b) { isData(it) && seq(it) == 1 }
        b.flush()
        pump(b, a) { Gr1.decode(it) is Gr1.Message.Nack }
        advance(250)
        b.recovery!!.tick()
        b.flush()
        pump(b, a)
        a.flush()
        pump(a, b) { isData(it) && seq(it) == 1 }
        advance(250)
        b.recovery!!.tick()
        b.flush()
        pump(b, a)
        a.flush()
        pump(a, b)

        assertArrayEquals(data, readAll(b.pipe))
        assertTrue(b.failures.isEmpty())
        assertTrue(logs.any { it.startsWith("b: event=nack | seq=1") && "round=3" in it })
    }

    @Test
    fun threeUnansweredRoundsEndTheStreamAtTheDeadline() {
        val (a, b) = connected()
        a.output.write(source(64))
        pump(a, b) { isData(it) && seq(it) == 1 }
        repeat(3) {
            b.flush()
            pump(b, a) { true }
            advance(250)
            b.recovery!!.tick()
        }
        advance(250)
        b.recovery!!.tick()

        assertEquals(listOf("GATT chunk 1 was lost (3 requests, 1000 ms)"), b.failures)
    }

    @Test
    fun goneEndsTheStreamAtOnce() {
        val (a, b) = connected()
        a.output.write(source(64))
        pump(a, b) { isData(it) && seq(it) == 1 }
        // The NACK reaches the sender after its 2 s history no longer holds chunk 1.
        advance(2_100)
        b.flush()
        pump(b, a)
        a.flush()
        pump(a, b)

        assertEquals(listOf("GATT chunk 1 was lost and the peer no longer holds it"), b.failures)
    }

    @Test
    fun highFindsAValueLostJustBeforeTheSenderWentQuiet() {
        val (a, b) = connected()
        val data = source(64)
        a.output.write(data)
        pump(a, b) { isData(it) && seq(it) == 3 }
        b.flush()
        assertTrue(b.air.none { Gr1.decode(it) is Gr1.Message.Nack })
        advance(200)
        a.recovery!!.tick()
        a.flush()
        pump(a, b)
        b.flush()
        pump(b, a)
        a.flush()
        pump(a, b)

        assertArrayEquals(data, readAll(b.pipe))
        assertTrue(b.failures.isEmpty())
    }

    @Test
    fun outOfRangeHighAndNackAreIgnored() {
        val (a, b) = connected()
        a.output.write(source(32))
        pump(a, b)
        readAll(b.pipe)
        handleIncoming(b.pipe, Gr1.high(2 + 5_000))
        b.flush()
        assertTrue(b.air.isEmpty())
        assertTrue(logs.any { it.startsWith("b: event=ignored | reason=high_out_of_range") })

        repeat(9) { handleIncoming(a.pipe, Gr1.nack(listOf(Gr1.SeqRange(100 + it, 1)))) }
        handleIncoming(a.pipe, Gr1.nack(listOf(Gr1.SeqRange(0, 1))))
        a.flush()
        assertTrue(a.air.isEmpty())
        assertTrue(logs.any { it.startsWith("a: event=nacks_ignored") })
        assertTrue(b.failures.isEmpty())
    }

    @Test
    fun anOversizedHelloIsClampedAndMalformedValuesAreIgnored() {
        val hello = Gr1.decode(Gr1.hello(values = 65_535, bytes = -1, ms = 65_535)) as Gr1.Message.Hello
        assertEquals(Gr1.Message.Hello(1_024, 262_144, 2_000), hello)

        val nack = Gr1.nack(listOf(Gr1.SeqRange(5, 2)))
        assertEquals(Gr1.Message.Nack(listOf(Gr1.SeqRange(5, 2))), Gr1.decode(nack))
        assertNull(Gr1.decode(nack.copyOf().also { it[4] = 0 }))
        assertNull(Gr1.decode(nack.copyOf().also { it[4] = 9 }))
        assertNull(Gr1.decode(nack.copyOf().also { it[9] = 0 }))
        assertNull(Gr1.decode(Gr1.hello().also { it[4] = 2 }))
        assertNull(Gr1.decode(Gr1.hello().copyOf(12)))
        assertNull(Gr1.decode(Gr1.gone(Gr1.SeqRange(1, 1)).also { it[8] = 0 }))
        assertNull(Gr1.decode(Gr1.high(1).copyOf(9)))
        assertNull(Gr1.decode(byteArrayOf(2, 0x47, 0x52, 9)))
        assertNull(Gr1.decode(byteArrayOf(2, 7)))
        assertEquals(Gr1.Message.Gone(Gr1.SeqRange(-2, 255)), Gr1.decode(Gr1.gone(Gr1.SeqRange(-2, 255))))
        assertEquals(Gr1.Message.High(Int.MIN_VALUE), Gr1.decode(Gr1.high(Int.MIN_VALUE)))
    }

    @Test
    fun withOneHelloLostOnlyTheInformedReaderWaitsAndTheOtherFailsFast() {
        val a = Side("a")
        val b = Side("b")
        a.start()
        b.start()
        a.flush()
        b.flush()
        pump(a, b) { Gr1.decode(it) is Gr1.Message.Hello }
        pump(b, a)
        assertNull(b.recovery!!.peerHello)

        // b -> a: a knows b's HELLO and asks; b keeps a history (its own switch is on) and answers.
        val toA = source(48)
        b.output.write(toA)
        pump(b, a) { isData(it) && seq(it) == 1 }
        a.flush()
        pump(a, b)
        b.flush()
        pump(b, a)
        assertArrayEquals(toA, readAll(a.pipe))

        // a -> b: b never heard a HELLO, so it keeps option A and sends no NACK.
        a.output.write(source(48))
        pump(a, b) { isData(it) && seq(it) == 1 }
        b.flush()
        assertTrue(b.air.none { Gr1.decode(it) is Gr1.Message.Nack })
        advance(1_000)
        a.output.write(source(16))
        pump(a, b)
        assertEquals(listOf("GATT chunk 1 was lost (2 later chunks waited 1000 ms)"), b.failures)
    }

    @Test
    fun aNackFloodGetsAtMostAQuarterOfTheMeasuredLinkBudget() {
        val (a, b) = connected(payload = 400)
        a.output.write(source(400 * 40))
        val sentBytes = a.air.filter { isData(it) }.sumOf { it.size }
        pump(a, b)
        handleIncoming(a.pipe, Gr1.nack(listOf(Gr1.SeqRange(0, 40))))
        a.flush()
        val resentBytes = a.air.filter { isData(it) }.sumOf { it.size }

        assertTrue(resentBytes > 0)
        assertTrue(resentBytes <= sentBytes / 4)
        assertTrue(logs.any { it.startsWith("a: event=capped") })
        assertTrue(b.failures.isEmpty())
    }

    @Test
    fun aBriefReorderCostsAtMostADuplicateAndKeepsTheBytes() {
        val (a, b) = connected()
        val data = source(64)
        a.output.write(data)
        val values = a.air.toList()
        a.air.clear()
        val order = listOf(0, 2, 1, 3).map { index -> values.filter(::isData)[index] }
        values.filterNot(::isData).forEach { handleIncoming(b.pipe, it) }
        order.forEach { handleIncoming(b.pipe, it) }
        b.flush()
        pump(b, a)
        a.flush()
        pump(a, b)

        assertArrayEquals(data, readAll(b.pipe))
        assertTrue(b.failures.isEmpty())
    }

    @Test
    fun twoLossesInOneWindowAreBothAskedForAndRecovered() {
        val (a, b) = connected()
        val data = source(96)
        a.output.write(data)
        pump(a, b) { isData(it) && (seq(it) == 1 || seq(it) == 3) }
        b.recovery!!.tick()
        b.flush()
        val nacks = b.air.mapNotNull { Gr1.decode(it) as? Gr1.Message.Nack }
        assertEquals(setOf(Gr1.SeqRange(1, 1), Gr1.SeqRange(3, 1)), nacks.flatMap { it.ranges }.toSet())
        pump(b, a)
        a.flush()
        pump(a, b)

        assertArrayEquals(data, readAll(b.pipe))
        assertTrue(b.failures.isEmpty())
    }

    @Test
    fun aResendAfterTheDeadlineIsIgnored() {
        val (a, b) = connected()
        a.output.write(source(64))
        pump(a, b) { isData(it) && seq(it) == 1 }
        b.flush()
        val nack = b.air.toList()
        b.air.clear()
        advance(1_000)
        b.recovery!!.tick()
        assertEquals(1, b.failures.size)
        nack.forEach { handleIncoming(a.pipe, it) }
        a.flush()
        val resend = a.air.single { isData(it) }

        assertFalse(handleIncoming(b.pipe, resend))
        assertEquals(1, b.failures.size)
    }

    @Test
    fun aLosslessSoakSendsNoRequestAndNoResend() {
        val (a, b) = connected()
        val ab = source(16 * 2_000)
        val ba = source(16 * 2_000).reversedArray()
        a.output.write(ab)
        b.output.write(ba)
        pump(a, b)
        pump(b, a)
        repeat(10) {
            advance(100)
            a.recovery!!.tick()
            b.recovery!!.tick()
        }

        assertArrayEquals(ab, readAll(b.pipe))
        assertArrayEquals(ba, readAll(a.pipe))
        assertTrue(logs.none { "event=nack" in it || "event=resend" in it || "event=failed" in it })
        assertTrue(a.failures.isEmpty() && b.failures.isEmpty())
    }

    @Test
    fun theHistoryStaysWithinItsBoundsOnEveryLink() {
        val a = Side("a", payload = 507)
        a.start()
        a.output.write(source(507 * 3_000))
        val (values, bytes) = a.recovery!!.historyState()
        assertTrue(values <= Gr1.HISTORY_VALUES)
        assertTrue(bytes <= Gr1.HISTORY_BYTES)
        advance(2_001)
        a.output.write(source(1))
        assertEquals(1, a.recovery!!.historyState().first)
    }

    @Test
    fun anOlderReaderIgnoresEveryGr1Value() {
        val pipe = GattBytePipe(capacity = 64, nanoTime = clock, dropEvery = 0, debugLog = {}, onFailure = { throw AssertionError(it) })
        listOf(Gr1.hello(), Gr1.nack(listOf(Gr1.SeqRange(0, 1))), Gr1.gone(Gr1.SeqRange(0, 1)), Gr1.high(0)).forEach {
            assertTrue(handleIncoming(pipe, it))
        }
        assertEquals(0, pipe.available())
    }

    @Test
    fun typeTwoValuesDoNotCountAsHearingThePeer() {
        val (a, _) = connected()
        handleIncoming(a.pipe, Gr1.high(0))
        handleIncoming(a.pipe, Gr1.hello())
        assertFalse(a.pipe.heardWithin(60_000))
    }

    @Test
    fun theDebugDropHookDropsEachChosenChunkOnce() {
        val dropped = mutableListOf<String>()
        val pipe = GattBytePipe(capacity = 64, gapFastFail = { false }, nanoTime = clock, dropEvery = 3, debugLog = { dropped += it }, onFailure = {})
        val values = mutableListOf<ByteArray>()
        object : ChunkedGattOutputStream() {
            override val payloadBytes = 1
            override fun sendPacket(value: ByteArray) {
                values += value.copyOf()
            }
        }.write(source(6))
        values.forEach { handleIncoming(pipe, it) }
        assertEquals(2, readAll(pipe).size)
        assertEquals(listOf("BENCHMARK gatt_debug_drop: seq=2 | every=3", "BENCHMARK gatt_debug_drop: seq=5 | every=3"), dropped)
        handleIncoming(pipe, values[2])
        handleIncoming(pipe, values[5])
        assertEquals(4, readAll(pipe).size)
    }
}
