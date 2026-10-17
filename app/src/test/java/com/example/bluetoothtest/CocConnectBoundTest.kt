package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-01 (bounded connect), C-02 (error codes), C-03 (Android 10 precondition) and the credit-stall classifier. */
class CocConnectBoundTest {
    /** A socket whose connect() blocks until close(), like BluetoothSocket.connect() on a silent peer. */
    private class BlockingSocket : Closeable {
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
        }
        fun connect() {
            if (!closed.await(30, TimeUnit.SECONDS)) throw AssertionError("watchdog never closed the socket")
            throw IOException("socket closed")
        }
    }

    private val lines = ArrayList<String>()
    private val traits34 = DeviceTraits("qcom", "taro", 34)

    @Test
    fun aConnectThatNeverAnswersIsClosedAtTheBoundAndReportedAsATimeout() {
        val socket = BlockingSocket()
        val started = System.nanoTime()
        val thrown = assertThrows(CocConnectTimeoutException::class.java) {
            CocConnectBound.run(socket, 150L, "test", { lines += it }) { it.connect() }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("closed once by the watchdog", socket.closes.get() == 1)
        assertTrue("took about the bound, not the 30 s guard: $elapsedMs", elapsedMs in 100L..5_000L)
        assertEquals(150L, thrown.boundMs)
        assertTrue(thrown.cause is IOException)
        assertTrue(lines.single(), "outcome=timeout" in lines.single() && "action=close_and_fallback" in lines.single())
    }

    @Test
    fun aHealthyConnectIsNotClosedAndLogsTheConnectedLine() {
        var connected = false
        val socket = BlockingSocket()
        CocConnectBound.run(socket, 5_000L, "test", { lines += it }) { connected = true }
        assertTrue(connected)
        assertEquals("a healthy connect is never closed by the watchdog", 0, socket.closes.get())
        assertTrue(lines.single(), "outcome=connected" in lines.single())
    }

    @Test
    fun aFailureBeforeTheBoundIsRethrownUnchangedAndLogged() {
        val failure = IOException("read failed; socket might closed or timeout, read ret: -1")
        val socket = BlockingSocket()
        val thrown = assertThrows(IOException::class.java) {
            CocConnectBound.run(socket, 5_000L, "test", { lines += it }) { throw failure }
        }
        assertSame("the original exception reaches the caller", failure, thrown)
        assertEquals(0, socket.closes.get())
        assertTrue(lines.single(), "outcome=failed" in lines.single() && "action=retry" in lines.single())
    }

    @Test
    fun aBoundOfZeroKeepsTheUnboundedBehaviour() {
        val socket = BlockingSocket()
        assertThrows(IOException::class.java) {
            CocConnectBound.run(socket, 0L, "test", { lines += it }) { throw IOException("x") }
        }
        assertEquals("no watchdog thread exists, so nothing closes the socket", 0, socket.closes.get())
    }

    @Test
    fun aConnectThatReturnsJustAfterTheWatchdogClosedItIsStillATimeout() {
        val socket = BlockingSocket()
        assertThrows(CocConnectTimeoutException::class.java) {
            CocConnectBound.run(socket, 80L, "test", { lines += it }) {
                assertTrue(it.closed.await(5, TimeUnit.SECONDS))
                // connect() raced the close and returned normally
            }
        }
    }

    @Test
    fun theBoundComesFromTheRegistryEntryAndItsOffSwitch() {
        val on = CocPolicy.resolve(traits34, emptySet())
        assertEquals(12_000L, CocConnectBound.boundMsFor(on, CocSwitches.DEFAULT_CONNECT_BOUND_MS))
        assertEquals(3_000L, CocConnectBound.boundMsFor(on, 3_000L))
        assertEquals("0 means unbounded", 0L, CocConnectBound.boundMsFor(on, 0L).coerceAtLeast(0L))
        val off = CocPolicy.resolve(traits34, setOf("coc-connect-bounded"))
        assertEquals("the registry off-switch removes the bound", 0L, CocConnectBound.boundMsFor(off, 12_000L))
        assertEquals(0L, CocConnectBound.boundMsFor(CocPolicy.resolve(traits34, setOf("all")), 12_000L))
        // Every SDK of the app gets it.
        for (sdk in listOf(29, 30, 31, 33, 34, 35, 36)) {
            val policy = CocPolicy.resolve(DeviceTraits("x", "y", sdk), emptySet())
            assertEquals("sdk $sdk", 12_000L, CocConnectBound.boundMsFor(policy, CocSwitches.DEFAULT_CONNECT_BOUND_MS))
        }
    }

    @Test
    fun errorCodesMapToFailureClasses() {
        assertEquals(CocFailureKind.STALE_PSM, CocSocketErrors.classify(13))
        assertEquals(CocFailureKind.CAPACITY, CocSocketErrors.classify(12))
        assertEquals(CocFailureKind.TIMEOUT, CocSocketErrors.classify(14))
        for (code in listOf(3, 4, 5, 6, 7)) assertEquals(CocFailureKind.SECURITY_MISMATCH, CocSocketErrors.classify(code))
        assertEquals(CocFailureKind.ACL_FAILURE, CocSocketErrors.classify(2))
        for (code in 15..21) assertEquals(CocFailureKind.LOCAL_STACK, CocSocketErrors.classify(code))
        assertEquals(CocFailureKind.OTHER, CocSocketErrors.classify(0))
        assertEquals(CocFailureKind.OTHER, CocSocketErrors.classify(1))
        assertEquals(CocFailureKind.OTHER, CocSocketErrors.classify(99))
        assertFalse("a stale PSM is never retried as is", CocFailureKind.STALE_PSM.retrySamePsm)
        assertFalse(CocFailureKind.SECURITY_MISMATCH.retrySamePsm)
        assertTrue(CocFailureKind.TIMEOUT.retrySamePsm)
    }

    @Test
    fun theErrorCodeIsFoundInTheCauseChainOnlyOnAndroid14Plus() {
        val coded = IOException("wrapped", IllegalStateException("mid", IOException("root")))
        val codeOf: (Throwable) -> Int? = { if (it.message == "root") 13 else null }
        assertEquals(13, CocSocketErrors.errorCodeOf(coded, sdk = 34, codeOf = codeOf))
        assertNull("before Android 14 the class does not exist", CocSocketErrors.errorCodeOf(coded, sdk = 33, codeOf = codeOf))
        assertNull(CocSocketErrors.errorCodeOf(IOException("plain"), sdk = 36, codeOf = codeOf))
        assertEquals("errorCode=none", CocSocketErrors.describe(IOException("plain"), sdk = 36, codeOf = codeOf))
        assertEquals("errorCode=13 | errorKind=stale_psm", CocSocketErrors.describe(coded, sdk = 35, codeOf = codeOf))
        // A cyclic cause chain cannot hang the lookup.
        val loop = IOException("a")
        loop.initCause(IOException("b", loop).let { IllegalStateException("c", it) })
        assertNull(CocSocketErrors.errorCodeOf(loop, sdk = 34, codeOf = { null }))
    }

    @Test
    fun theFailureLineNamesTheOutcomeAndTheNextStep() {
        val timeout = CocSocketErrors.failureLine("o", IOException("x"), 12_001L, 12_000L, timedOut = true)
        assertTrue(timeout, "outcome=timeout" in timeout && "action=close_and_fallback" in timeout && "boundMs=12000" in timeout)
        val plain = CocSocketErrors.failureLine("o", IOException("x"), 10L, 12_000L, timedOut = false)
        assertTrue(plain, "outcome=failed" in plain && "errorCode=none" in plain && "action=retry" in plain)
    }

    @Test
    fun androidTenChecksThatAGattConnectPrecededTheCocConnect() {
        val android10 = CocPolicy.resolve(DeviceTraits("qcom", "msmnile", 29), emptySet())
        assertTrue(CocAddressPrecondition.check(android10, true, "o") { lines += it })
        assertTrue(lines.single(), "gattOrScanFirst=true" in lines.single() && lines.single().endsWith("ok"))
        lines.clear()
        assertFalse("a refactor that skips the GATT read is flagged", CocAddressPrecondition.check(android10, false, "o") { lines += it })
        assertTrue(lines.single(), "VIOLATED" in lines.single())
        lines.clear()
        val android11 = CocPolicy.resolve(DeviceTraits("qcom", "msmnile", 30), emptySet())
        assertTrue("the AOSP fix is in Android 11: nothing is checked", CocAddressPrecondition.check(android11, false, "o") { lines += it })
        assertTrue(lines.isEmpty())
        val off = CocPolicy.resolve(DeviceTraits("qcom", "msmnile", 29), setOf("coc-android10-address-type-precondition"))
        assertTrue(CocAddressPrecondition.check(off, false, "o") { lines += it })
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aWriterWithNoAcknowledgedProgressFromTheFirstSecondIsACreditStall() {
        assertTrue(CocCreditStall.isStall(1_000, bytesWritten = 20_000, bytesAcknowledged = 0, writeBlockedMs = 1_000))
        assertFalse("too early", CocCreditStall.isStall(500, 20_000, 0, 500))
        assertFalse("the peer acknowledged something", CocCreditStall.isStall(5_000, 20_000, 512, 5_000))
        assertFalse("nothing written yet", CocCreditStall.isStall(5_000, 0, 0, 5_000))
        assertFalse("the write is not blocked", CocCreditStall.isStall(5_000, 20_000, 0, 10))
        assertTrue("action=gatt_fallback" in CocCreditStall.logLine("o", 1_200, 20_000, 1_100))
    }
}
