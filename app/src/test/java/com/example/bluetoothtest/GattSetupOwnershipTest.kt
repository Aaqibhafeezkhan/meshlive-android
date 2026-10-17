package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GattSetupOwnershipTest {
    private class SetupResources {
        val input = GattBytePipe(onFailure = {})
        var outputClosed = false
        var transportCloses = 0
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true }
        }
        val connection = GattVideoConnection(input, output, { "test" }, input::queueState) {
            transportCloses++
        }
        fun closeFailedSetup() {
            input.close()
            output.close()
            transportCloses++
        }
        fun assertClosed() {
            assertEquals(-1, input.read())
            assertTrue(outputClosed)
            assertEquals(1, transportCloses)
        }
    }

    @Test(timeout = 5_000L)
    fun interruptingSetupClosesTransportAndBothStreams() {
        val resources = SetupResources()
        val waiting = CountDownLatch(1)
        val completion = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                awaitGattSetup(
                    { waiting.countDown(); completion.await(120, TimeUnit.SECONDS) },
                    { resources.connection }, { null }, resources::closeFailedSetup,
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        assertTrue(waiting.await(120, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is InterruptedException)
        assertEquals(1, resources.transportCloses)
        resources.assertClosed()
    }

    @Test
    fun setupTimeoutClosesEveryOwnedResource() {
        val resources = SetupResources()

        val error = assertThrows(IOException::class.java) {
            awaitGattSetup({ false }, { resources.connection }, { null }, resources::closeFailedSetup)
        }

        assertEquals("Timed out connecting GATT video", error.message)
        resources.assertClosed()
    }

    @Test
    fun failedCompletionClosesTheAttemptAndPreservesItsFailure() {
        val resources = SetupResources()

        val error = assertThrows(IOException::class.java) {
            awaitGattSetup({ true }, { null }, { "status 133" }, resources::closeFailedSetup)
        }

        assertEquals("status 133", error.message)
        resources.assertClosed()
    }

    @Test
    fun exceptionWhileStartingConnectionClosesLocallyOwnedStreams() {
        val resources = SetupResources()

        assertThrows(IllegalStateException::class.java) {
            awaitGattSetup({ throw IllegalStateException("connect failed") },
                { null }, { null }, resources::closeFailedSetup)
        }

        resources.assertClosed()
    }

    @Test
    fun successfulConnectionRemainsReadableUntilTheActiveOwnerClosesIt() {
        val resources = SetupResources()

        val accepted = awaitGattSetup({ true }, { resources.connection }, { null }, resources::closeFailedSetup)

        assertSame(resources.connection, accepted)
        assertEquals(0, resources.transportCloses)
        assertFalse(resources.outputClosed)
        assertTrue(resources.input.offer(byteArrayOf(42)))
        assertEquals(42, accepted.inputStream.read())
        accepted.close()
        accepted.close()
        resources.assertClosed()
    }

    @Test
    fun stalledMtuExchangeIsRetriedOnAFreshConnection() {
        assertTrue(isRetryableGattVideoFailure(GATT_MTU_STALLED_MESSAGE))
        assertFalse(isRetryableGattVideoFailure("GATT video service discovery failed (status 129)"))
    }
}
