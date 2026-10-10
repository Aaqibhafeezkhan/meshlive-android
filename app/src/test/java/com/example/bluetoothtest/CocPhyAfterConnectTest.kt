package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CocPhyAfterConnectTest {
    private val clock = AtomicInteger(0)
    private val lines = mutableListOf<String>()

    private fun waiter(
        requestQueued: Boolean = true,
        onRequest: (CocPhyAfterConnect) -> Unit = {},
        onRead: (CocPhyAfterConnect) -> Unit = {},
        reads: AtomicInteger = AtomicInteger(),
    ): CocPhyAfterConnect {
        lateinit var self: CocPhyAfterConnect
        self = CocPhyAfterConnect(
            requestPhy = { onRequest(self); requestQueued },
            readPhy = { reads.incrementAndGet(); onRead(self); true },
            nowMs = { clock.get().toLong() },
            log = { lines += it },
        )
        return self
    }

    @Test
    fun anUpdateDuringTheRequestEndsTheWaitWithoutAReadFallback() {
        val reads = AtomicInteger()
        val result = waiter(onRequest = { it.onPhy(txPhy = 2, status = 0, fromRead = false) }, reads = reads)
            .run("stream", "2M", "after_connect", updateWaitMs = 50, readWaitMs = 50)
        assertEquals("2M", result.effective)
        assertEquals("update", result.source)
        assertFalse(result.readFallback)
        assertEquals(0, reads.get())
        assertEquals(
            "BENCHMARK link_phy: link=stream | requested=2M | effective=2M | reason=after_connect | " +
                "source=update | readFallback=false | waitedMs=0",
            lines.single(),
        )
    }

    @Test
    fun noUpdateFallsBackToReadPhyAsNordicDoes() {
        // Some stacks never call onPhyUpdate (Nordic 46d17813): the read reports the PHY instead.
        val reads = AtomicInteger()
        val result = waiter(onRead = { it.onPhy(txPhy = 1, status = 0, fromRead = true) }, reads = reads)
            .run("call", "2M", "after_connect", updateWaitMs = 10, readWaitMs = 50)
        assertEquals(1, reads.get())
        assertTrue(result.readFallback)
        assertEquals("1M", result.effective)
        assertEquals("read", result.source)
    }

    @Test
    fun aLateReadDoesNotOverwriteAReportedUpdate() {
        val phy = waiter()
        phy.onPhy(txPhy = 2, status = 0, fromRead = false)
        phy.onPhy(txPhy = 1, status = 0, fromRead = true)
        val result = phy.run("relay", "2M", "after_connect", updateWaitMs = 10, readWaitMs = 10)
        assertEquals("2M", result.effective)
        assertEquals("update", result.source)
    }

    @Test
    fun aFailedUpdateIsReportedWithItsStatus() {
        val result = waiter(onRequest = { it.onPhy(txPhy = 1, status = 42, fromRead = false) })
            .run("stream", "2M", "after_connect", updateWaitMs = 10, readWaitMs = 10)
        assertEquals("failed(status 42)", result.effective)
    }

    @Test
    fun aRequestThatWasNotQueuedNeitherWaitsNorReads() {
        val reads = AtomicInteger()
        val result = waiter(requestQueued = false, reads = reads)
            .run("stream", "2M", "after_connect", updateWaitMs = 5_000, readWaitMs = 5_000)
        assertEquals("not_queued", result.effective)
        assertEquals("none", result.source)
        assertEquals(0, reads.get())
    }

    @Test
    fun silenceFromBothCallbacksIsUnknownNotAPass() {
        val result = waiter().run("stream", "2M", "after_connect", updateWaitMs = 5, readWaitMs = 5)
        assertEquals("unknown", result.effective)
        assertTrue(result.readFallback)
    }

    @Test
    fun theExperimentIsOffByDefault() {
        assertFalse(DebugTunables.cocPhyAfterConnect)
        assertFalse(CocPhyAfterConnect.enabled())
    }
}
