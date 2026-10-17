package com.example.bluetoothtest

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The audio-only source under concurrency: the encoder thread produces and sends, a listener's writer thread
 * drains its queue, heartbeats are acknowledged, a second listener joins mid-stream. Regression for the field
 * failure where every UI-started broadcast ended within about a second with a bare `NoSuchElementException`
 * (Kotlin's `toList()` of the sender's queue racing the writer's `take`).
 */
class AudioLivePipelineTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val log = CopyOnWriteArrayList<Triple<Int, String, Throwable?>>()
    private var savedEmit: ((Int, String, Throwable?) -> Unit)? = null

    @Before
    fun captureLog() {
        savedEmit = AudioLiveLog.emit
        AudioLiveLog.emit = { level, line, error -> log += Triple(level, line, error) }
    }

    @After
    fun restoreLog() {
        savedEmit?.let { AudioLiveLog.emit = it }
    }

    private companion object {
        /** No test depends on this: it only bounds how long a hung test waits before failing, far above any loaded run. */
        const val WAIT_BOUND_MS = 120_000L
    }

    private class Entry(val flags: Int, val pts: Long, val bytes: ByteArray, val queuedAtNs: Long)

    /** A listener as the broadcaster's sender sees it: the queue, the shedding and a writer thread that drains it. */
    private class FakeListener(
        val name: String,
        writerPauseNs: Long,
        /** The writer takes nothing until this opens (a listener that has stalled); null: it drains from the start. */
        startGate: CountDownLatch? = null,
    ) {
        val queue = LinkedBlockingQueue<Entry>()
        val sendQueue = AudioLiveSendQueue(
            queue,
            sheddable = { AudioLiveShed.isSourceRecordSheddable(it.flags) },
            queuedAtNs = { it.queuedAtNs },
            sizeOf = { it.bytes.size },
            ageBound = true,
        )
        val written = AtomicInteger()
        val shed = AtomicInteger()
        val withdrawn = AtomicReference<AudioLiveChildHealth.Reason?>()
        private val running = AtomicBoolean(true)
        private val writer = Thread({
            try {
                startGate?.await()
                // After close() the writer still empties what was queued, so a count taken after close() does not depend on
                // how soon the scheduler ran this thread (it used to be interrupted with records still queued).
                while (running.get() || queue.isNotEmpty()) {
                    queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    written.incrementAndGet()
                    if (writerPauseNs > 0) LockSupport.parkNanos(writerPauseNs)
                }
            } catch (_: InterruptedException) {
            }
        }, "fake-writer-$name").also { it.isDaemon = true; it.start() }

        /** The sender's `send`: enqueue, shed, judge. [nowNs] is the test's virtual clock (200 ms per record). */
        fun send(record: AudioLiveSignedRecord, nowNs: Long) {
            val entry = Entry(record.flags, record.presentationTimeUs, record.payload, nowNs)
            queue.put(entry)
            val outcome = sendQueue.afterEnqueue(entry, nowNs)
            shed.addAndGet(outcome.removed)
            outcome.verdict?.let { withdrawn.compareAndSet(null, it) }
        }

        fun close() {
            running.set(false)
            writer.join(WAIT_BOUND_MS)
            assertFalse("the writer of $name did not finish draining", writer.isAlive)
        }
    }

    /** The broadcaster as the source sees it, with heartbeats acknowledged on their own thread. */
    private class FakeBroadcaster(
        /**
         * Healthy listeners only: the virtual clock stops while a listener's writer is this many records behind. The clock
         * runs 200 ms per record, so a writer that the scheduler starves for a few records would otherwise look like a
         * listener seconds behind and be shed and withdrawn: a property of the machine, not of the code under test.
         */
        private val maxBacklog: Int = Int.MAX_VALUE,
    ) : AudioLiveSink {
        val listeners = CopyOnWriteArrayList<FakeListener>()
        private var config: AudioLiveSignedRecord? = null
        private val lock = Any()
        private var virtualNs = 1_000_000_000_000L
        val heartbeats = AtomicInteger()
        val firstHeartbeat = CountDownLatch(1)
        private val running = AtomicBoolean(true)
        private val acker = Thread({
            while (running.get()) {
                // The listener's heartbeat arrives and is acknowledged: touches the same broadcaster state.
                heartbeats.incrementAndGet()
                firstHeartbeat.countDown()
                synchronized(lock) { listeners.forEach { it.sendQueue.oldestAgeMs(virtualNs) } }
                LockSupport.parkNanos(200_000)
            }
        }, "fake-acker").also { it.isDaemon = true; it.start() }

        override fun sendAudioLiveRecord(record: AudioLiveSignedRecord) {
            if (maxBacklog != Int.MAX_VALUE) {
                val deadline = System.nanoTime() + WAIT_BOUND_MS * 1_000_000
                for (listener in listeners) {
                    while (listener.queue.size > maxBacklog) {
                        check(System.nanoTime() < deadline) { "the writer of ${listener.name} made no progress" }
                        LockSupport.parkNanos(50_000)
                    }
                }
            }
            synchronized(lock) {
                if (record.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) config = record else virtualNs += 200_000_000L
                listeners.forEach { it.send(record, virtualNs) }
            }
        }

        override fun discardQueuedAudio(): Int = listeners.sumOf { it.sendQueue.discardSheddable().size }

        /** admitSender: the joining listener first gets the remembered configuration. */
        fun join(listener: FakeListener) {
            synchronized(lock) {
                config?.let { listener.send(it, virtualNs) }
                listeners += listener
            }
        }

        fun close() {
            running.set(false)
            acker.join(WAIT_BOUND_MS)
            listeners.forEach(FakeListener::close)
        }
    }

    /** Waits for an event without a time assumption (a slow machine only waits longer); fails after a generous bound. */
    private fun spinUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + WAIT_BOUND_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < end) { "the awaited event never happened" }
            LockSupport.parkNanos(100_000)
        }
    }

    private val opusHead = ByteArray(19).also {
        "OpusHead".forEachIndexed { index, c -> it[index] = c.code.toByte() }
        it[8] = 1
        it[9] = 1
    }

    /** An encoder that produces valid 20 ms frames as fast as asked, like the native loop. */
    private class FakeEncoder(
        private val frames: Int,
        private val recordSleepMs: Long,
        private val opusHead: ByteArray,
        private val onConfig: (ByteArray) -> Unit,
        private val onFrame: (ByteArray) -> Unit,
        private val onError: (String) -> Unit,
        val done: CountDownLatch,
    ) : AudioLiveEncoderPort {
        override val encoderName = "fake"
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null

        override fun start() {
            running.set(true)
            thread = Thread({
                try {
                    onConfig(opusHead)
                    var sent = 0
                    while (running.get() && sent < frames) {
                        onFrame(ByteArray(60) { 3 }.also { it[0] = 0xf8.toByte() })
                        sent++
                        if (recordSleepMs > 0 && sent % AudioLive.DEFAULT_BATCH_FRAMES == 0) Thread.sleep(recordSleepMs)
                    }
                } catch (error: Throwable) {
                    // What the real encoder does: log the stack, report the message (or the class name).
                    AudioLiveLog.failure("encoder:fake", error)
                    onError(error.message ?: error.javaClass.simpleName)
                } finally {
                    done.countDown()
                }
            }, "fake-encoder").also { it.isDaemon = true; it.start() }
        }

        override fun close() {
            running.set(false)
            thread?.join(2_000)
        }
    }

    private fun source(
        frames: Int,
        recordSleepMs: Long,
        errors: MutableList<String>,
        done: CountDownLatch,
    ): AudioLiveSource {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        val signer = AudioLiveSigner(identity, java.util.UUID.randomUUID(), AudioLive.PRESET_VOICE)
        return AudioLiveSource.forTest(
            AudioLiveSendConfig(signer),
            AudioLiveEncoderFactory { _, _, onConfig, onFrame, _, onError, _ ->
                FakeEncoder(frames, recordSleepMs, opusHead, onConfig, onFrame, onError, done)
            },
            onError = { errors += it },
        )
    }

    @Test
    fun aBroadcastWithAListenerDrainingItsQueueNeverFailsAndASecondListenerCanJoin() {
        val errors = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        // 30,000 frames = 3,000 records produced flat out; the writer drains instantly, so the queue is empty or
        // holds one record, which is exactly when Kotlin's toList() raced the writer's take.
        val source = source(frames = 30_000, recordSleepMs = 0, errors = errors, done = done)
        val broadcaster = FakeBroadcaster(maxBacklog = 6)
        val first = FakeListener("first", writerPauseNs = 0)
        broadcaster.join(first)
        source.attach(broadcaster)
        // Mid-stream means after the first record reached the first listener (not after a wall-clock guess).
        spinUntil { first.written.get() > 0 }
        val second = FakeListener("second", writerPauseNs = 0)
        broadcaster.join(second) // joins mid-stream and gets the configuration first
        assertTrue("encoder did not finish", done.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS))
        assertTrue("no heartbeat was acknowledged", broadcaster.firstHeartbeat.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS))
        source.stop()
        broadcaster.close()

        assertTrue("errors: $errors ${log.filter { it.first == AudioLiveLog.ERROR }.map { it.second }}", errors.isEmpty())
        assertTrue(first.written.get() >= 3_000)
        assertTrue(second.written.get() in 1..first.written.get())
        assertNull(first.withdrawn.get())
        assertNull(second.withdrawn.get())
        assertTrue(broadcaster.heartbeats.get() > 0)
    }

    @Test
    fun aSlowListenerIsShedAndWithdrawnWithoutFailingTheSource() {
        val errors = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        // Virtual time runs 200 ms per record while the writer takes a moment per record: the queue ages past 3 s.
        val source = source(frames = 20_000, recordSleepMs = 0, errors = errors, done = done)
        val broadcaster = FakeBroadcaster()
        // The writer is stalled until the source has produced everything, so the backlog (in virtual time) cannot depend on
        // how fast the machine runs the producer against a writer that pauses 2 ms per record.
        val gate = CountDownLatch(1)
        val slow = FakeListener("slow", writerPauseNs = 2_000_000, startGate = gate)
        broadcaster.join(slow)
        source.attach(broadcaster)
        assertTrue(done.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS))
        gate.countDown()
        source.stop()
        broadcaster.close()
        assertTrue("errors: $errors", errors.isEmpty())
        assertTrue("nothing was shed", slow.shed.get() > 0)
        assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, slow.withdrawn.get())
    }

    @Test
    fun theSendQueueSurvivesAProducerShedderAndWriterRacingOnAQueueOfOne() {
        val queue = LinkedBlockingQueue<Entry>()
        val sendQueue = AudioLiveSendQueue(queue, { true }, { it.queuedAtNs }, { it.bytes.size }, ageBound = true)
        val failure = AtomicReference<Throwable?>()
        val stop = AtomicBoolean(false)
        val writer = Thread {
            try {
                while (!stop.get()) queue.poll(1, TimeUnit.MILLISECONDS)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val discarder = Thread {
            try {
                while (!stop.get()) sendQueue.discardSheddable()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        writer.start()
        discarder.start()
        try {
            repeat(300_000) {
                val entry = Entry(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR, it.toLong(), ByteArray(8), System.nanoTime())
                queue.put(entry)
                sendQueue.afterEnqueue(entry, System.nanoTime())
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        } finally {
            stop.set(true)
            writer.join(2_000)
            discarder.join(2_000)
        }
        assertNull("racing threads threw ${failure.get()}", failure.get())
    }

    // --- Failure logging --------------------------------------------------------------------------------------------

    @Test
    fun everyFailureIsLoggedWithItsStackAndABenchmarkLineNamingTheClassAndTheTopFrame() {
        val error = runCatching { java.util.ArrayDeque<Int>().removeFirst() }.exceptionOrNull()!!
        AudioLiveLog.failure("encoder:fake", error)
        val errorLog = log.single { it.first == AudioLiveLog.ERROR }
        assertNotNull("the stack is attached to the Log.e call", errorLog.third)
        val line = log.single { it.first == AudioLiveLog.INFO }.second
        assertTrue(line, line.startsWith("BENCHMARK audio_live_failure: where=encoder:fake | class=java.util.NoSuchElementException | topFrame="))
        assertTrue(line, line.contains("java.util.ArrayDeque.removeFirst"))
        // A failure without an exception (a stage that only has a message) still produces the line.
        AudioLiveLog.failure("source", null, "Android did not allow background microphone use")
        assertTrue(log.last().second.contains("where=source | class=none | topFrame=none"))
        assertFalse(AudioLiveLog.benchmarkLine("x", null, "a|b").contains("a|b"))
    }

    @Test
    fun theSourceReportsAFailureOfItsOwnStagesWithTheStack() {
        val errors = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val signer = AudioLiveSigner(KeyPairSigningIdentity(generateEcKeyPair()), java.util.UUID.randomUUID(), AudioLive.PRESET_VOICE)
        val source = AudioLiveSource.forTest(
            AudioLiveSendConfig(signer),
            AudioLiveEncoderFactory { _, _, onConfig, onFrame, _, onError, _ ->
                // A stereo frame: the source stops with a visible error instead of sending what receivers reject.
                object : AudioLiveEncoderPort {
                    override val encoderName = "fake"
                    override fun start() {
                        onConfig(opusHead)
                        onFrame(ByteArray(60) { 3 }.also { it[0] = 0xfc.toByte() })
                        done.countDown()
                    }
                    override fun close() = Unit
                }
            },
            onError = { errors += it },
        )
        val broadcaster = FakeBroadcaster()
        broadcaster.join(FakeListener("l", 0))
        source.attach(broadcaster)
        assertTrue(done.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS))
        broadcaster.close()
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("not one 20 ms mono frame"))
        assertTrue(log.any { it.first == AudioLiveLog.INFO && it.second.startsWith("BENCHMARK audio_live_failure: where=source") })
    }
}
