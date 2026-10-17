package com.example.bluetoothtest

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCommandQueueTest {
    private val lines = CopyOnWriteArrayList<String>()
    private val queues = mutableListOf<AudioCommandQueue>()

    private fun queue(
        main: Boolean = false,
        offload: Boolean = false,
        watchdogMs: Long = 5_000,
        slowMs: Long = 100,
    ): AudioCommandQueue = AudioCommandQueue(
        watchdogMs = watchdogMs,
        slowMs = slowMs,
        isMainThread = { main },
        offloadEnabled = { offload },
        sink = { lines += it },
    ).also { queues += it }

    @After
    fun shutdown() {
        queues.forEach { it.shutdown() }
    }

    @Test
    fun anInlineCommandRunsOnTheCallerAndMainThreadCommandsAreAlwaysLogged() {
        val queue = queue(main = true)
        val callerThread = Thread.currentThread()
        var ranOn: Thread? = null
        val result = queue.run("probe") { ranOn = Thread.currentThread(); 42 }
        assertEquals(42, result)
        assertTrue(ranOn === callerThread)
        assertTrue(lines.toString(), lines.any { it.contains("name=probe") && it.contains("from=main") && it.contains("mode=inline") })
    }

    @Test
    fun aFastNonMainCommandWritesNoLine() {
        val queue = queue(main = false)
        queue.run("quiet") { 1 }
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun offloadRunsInlineWhileTheSwitchIsOff() {
        val queue = queue(main = true, offload = false)
        val callerThread = Thread.currentThread()
        var ranOn: Thread? = null
        queue.offload("teardown") { ranOn = Thread.currentThread() }
        assertTrue("off by default: the caller still does the work", ranOn === callerThread)
    }

    @Test
    fun offloadQueuesFromTheMainThreadWhenTheSwitchIsOn() {
        val queue = queue(main = true, offload = true)
        val done = CountDownLatch(1)
        var ranOn: Thread? = null
        queue.offload("teardown") { ranOn = Thread.currentThread(); done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNotEquals(Thread.currentThread(), ranOn)
        assertEquals("AudioCommands", ranOn?.name)
    }

    @Test
    fun offloadDoesNotQueueFromANonMainThreadEvenWhenOn() {
        val queue = queue(main = false, offload = true)
        val callerThread = Thread.currentThread()
        var ranOn: Thread? = null
        queue.offload("teardown") { ranOn = Thread.currentThread() }
        assertTrue(ranOn === callerThread)
    }

    @Test
    fun queuedCommandsRunInOrderOnOneThread() {
        val queue = queue()
        val order = CopyOnWriteArrayList<Int>()
        val threads = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(5)
        repeat(5) { index ->
            queue.post("c$index") {
                order += index
                threads += Thread.currentThread().name
                done.countDown()
            }
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1, 2, 3, 4), order.toList())
        assertEquals(1, threads.toSet().size)
    }

    @Test
    fun anInlineCommandWaitsForAQueuedOneSoTeardownAndStartNeverOverlap() {
        val queue = queue()
        val events = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        queue.post("teardown") {
            started.countDown()
            Thread.sleep(150)
            events += "teardown-done"
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        queue.run("start") { events += "start" }
        assertEquals(listOf("teardown-done", "start"), events.toList())
    }

    @Test
    fun aStalledCommandIsReportedByTheWatchdog() {
        val queue = queue(watchdogMs = 50, slowMs = 10)
        queue.run("slow") { Thread.sleep(300) }
        assertTrue(lines.toString(), lines.any { it.contains("name=slow") && it.contains("event=stalled") })
        assertTrue(lines.toString(), lines.any { it.contains("name=slow") && it.contains("event=done") && it.contains("slow=true") })
    }

    @Test
    fun aFinishedCommandCancelsItsWatchdog() {
        val queue = queue(watchdogMs = 100)
        queue.run("fast") { 1 }
        Thread.sleep(250)
        assertFalse(lines.toString(), lines.any { it.contains("event=stalled") })
    }

    @Test
    fun offloadForResultDeliversTheResultInlineOrQueued() {
        val inline = queue(main = true, offload = false)
        var inlineResult: Result<Int>? = null
        inline.offloadForResult("start", { 7 }) { inlineResult = it }
        assertEquals(7, inlineResult?.getOrNull())

        val failing = queue(main = true, offload = false)
        var failure: Result<Int>? = null
        failing.offloadForResult("start", { error("no mic") }) { failure = it }
        assertEquals("no mic", failure?.exceptionOrNull()?.message)

        val queued = queue(main = true, offload = true)
        val done = CountDownLatch(1)
        var queuedResult: Result<Int>? = null
        queued.offloadForResult("start", { 9 }) { queuedResult = it; done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(9, queuedResult?.getOrNull())
    }

    @Test
    fun aFailingQueuedCommandIsLoggedAndDoesNotKillTheQueue() {
        val queue = queue()
        val done = CountDownLatch(1)
        queue.post("bad") { error("boom") }
        queue.post("good") { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(lines.toString(), lines.any { it.contains("name=bad") && it.contains("event=failed") })
    }
}
