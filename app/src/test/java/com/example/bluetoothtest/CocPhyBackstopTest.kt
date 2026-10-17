package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * CocPhyAfterConnect with the Android 13 readPhy backstop (CONN-09) and the LL procedure gate (BACKLOG P-01). Real
 * waits are a few milliseconds long; the plans are shrunk accordingly.
 */
class CocPhyBackstopTest {
    private val lines = mutableListOf<String>()
    private val tiny = PhyWaitPlan(firstPollMs = 5, pollIntervalMs = 5, deadlineMs = 30)

    private fun waiter(
        reads: AtomicInteger,
        onRead: (CocPhyAfterConnect, Int) -> Unit = { _, _ -> },
        gate: LlProcedureGate? = null,
        onRequest: (CocPhyAfterConnect) -> Unit = {},
    ): CocPhyAfterConnect {
        lateinit var self: CocPhyAfterConnect
        self = CocPhyAfterConnect(
            requestPhy = { onRequest(self); true },
            readPhy = { val n = reads.incrementAndGet(); onRead(self, n); true },
            nowMs = System::currentTimeMillis,
            log = { lines += it },
            gate = gate,
        )
        return self
    }

    @Test
    fun aReadThatStillShowsTheOldPhyDoesNotEndTheWaitAndALaterOneDoes() {
        val reads = AtomicInteger()
        val result = waiter(reads, onRead = { w, n ->
            // The controller switches between the second and the third look.
            w.onPhy(txPhy = if (n >= 3) 2 else 1, status = 0, fromRead = true)
        }).run("stream", "2M", "after_connect", readWaitMs = 20, plan = tiny)
        assertEquals(3, reads.get())
        assertEquals("2M", result.effective)
        assertEquals("read", result.source)
        assertFalse(result.unconfirmed)
        assertEquals(3, result.polls)
        assertTrue(result.line("stream", "after_connect").endsWith("| polls=3"))
    }

    @Test
    fun aPhyThatNeverShowsIsReportedUnconfirmedAfterTheLastPoll() {
        val reads = AtomicInteger()
        val result = waiter(reads, onRead = { w, _ -> w.onPhy(txPhy = 1, status = 0, fromRead = true) })
            .run("stream", "2M", "after_connect", readWaitMs = 20, plan = tiny)
        // Polls at 5 ms and then every 5 ms: the plan allows pollOffsets().size polls.
        assertEquals(tiny.pollOffsets().size, reads.get())
        assertEquals("1M", result.effective)
        assertTrue(result.unconfirmed)
        assertTrue(result.line("stream", "after_connect").endsWith("| unconfirmed=true"))
    }

    @Test
    fun anUpdateDuringThePollsEndsThemWithoutFurtherReads() {
        val reads = AtomicInteger()
        val result = waiter(reads, onRead = { w, n ->
            if (n == 2) w.onPhy(txPhy = 2, status = 0, fromRead = false)
            w.onPhy(txPhy = 1, status = 0, fromRead = true)
        }).run("stream", "2M", "after_connect", readWaitMs = 20, plan = tiny)
        assertEquals(2, reads.get())
        assertEquals("update", result.source)
        assertEquals("2M", result.effective)
        assertFalse(result.unconfirmed)
    }

    @Test
    fun nordicsPlanReadsOnceAndAnExplicitUpdateWaitOverridesThePlan() {
        val reads = AtomicInteger()
        waiter(reads).run("stream", "2M", "after_connect", updateWaitMs = 5, readWaitMs = 5, plan = PhyWaitPlan.ANDROID_13)
        assertEquals("an explicit wait keeps the old single read", 1, reads.get())
    }

    @Test
    fun aLabelWithNoKnownPhyNeverPollsMoreThanOnce() {
        val reads = AtomicInteger()
        waiter(reads).run("stream", "fast", "after_connect", readWaitMs = 5, plan = tiny)
        assertEquals(1, reads.get())
    }

    // ---- gate ----

    private fun gate(time: FakeTime, enabled: Boolean) =
        LlProcedureGate("coc", LeLinkRole.CENTRAL, { time.now }, time::schedule, { }, enabled = { enabled }, onLinkLost = { _, _, _, _ -> })

    @Test
    fun withAGateThatIsOffTheRequestGoesStraightOut() {
        val reads = AtomicInteger()
        val time = FakeTime()
        val result = waiter(reads, gate = gate(time, false), onRequest = { it.onPhy(2, 0, false) })
            .run("stream", "2M", "after_connect", updateWaitMs = 5, readWaitMs = 5)
        assertEquals("2M", result.effective)
        assertEquals(0, reads.get())
    }

    @Test
    fun withAnEnabledGateAnIdleGateSendsTheRequestAtOnce() {
        val reads = AtomicInteger()
        val time = FakeTime()
        val g = gate(time, true)
        val result = waiter(reads, gate = g, onRequest = { it.onPhy(2, 0, false) })
            .run("stream", "2M", "after_connect", updateWaitMs = 5, readWaitMs = 5)
        assertEquals("2M", result.effective)
        assertEquals(0, reads.get())
    }

    @Test
    fun aGateThatCannotQueueTheRequestMakesItNotQueued() {
        val reads = AtomicInteger()
        val time = FakeTime()
        val g = gate(time, true)
        g.onLinkClosed(8)
        val result = waiter(reads, gate = g).run("stream", "2M", "after_connect", updateWaitMs = 5, readWaitMs = 5)
        assertEquals("not_queued", result.effective)
        assertEquals(0, reads.get())
    }
}
