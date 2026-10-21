package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UpdateInstallLifecycleTest {
    @Test fun anotherScreenOrStartupSweepCannotOverwriteAnActiveDownload() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val download = Thread {
            UpdateFileWork.run({ error("unexpected overlap") }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                writes.incrementAndGet()
            }
        }
        download.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(3) {
                assertEquals("busy", UpdateFileWork.run({ "busy" }) { writes.incrementAndGet(); "overwritten" })
            }
            assertEquals(0, writes.get())
        } finally { release.countDown(); download.join(5000) }
        assertEquals(1, writes.get())
        assertEquals("ready", UpdateFileWork.run({ "busy" }) { "ready" })
    }
    @Test fun cancellationOrStorageFailureReleasesTheNextAttempt() {
        try { UpdateFileWork.run({ fail("busy") }) { throw java.io.IOException("disk full") } }
        catch (_: java.io.IOException) {}
        assertTrue(UpdateFileWork.run({ false }) { true })
    }
    @Test fun mediaAndPendingConfirmationBothRefuseAnotherInstall() {
        assertTrue(UpdateInstallPolicy.allowed(false, false))
        assertFalse(UpdateInstallPolicy.allowed(true, false))
        assertFalse(UpdateInstallPolicy.allowed(false, true))
        assertFalse(UpdateInstallPolicy.allowed(true, true))
    }
}
