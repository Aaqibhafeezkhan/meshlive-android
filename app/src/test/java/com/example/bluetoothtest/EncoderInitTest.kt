package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch

class EncoderInitTest {
    private val allKeys = listOf(
        EncoderOptionalKey.COMPLEXITY,
        EncoderOptionalKey.LATENCY,
        EncoderOptionalKey.STATISTICS,
        EncoderOptionalKey.INTRA_REFRESH,
    )

    /** A fake codec: throws at the stage named by [failOn] for the listed attempts. */
    private class Fake(val failures: (EncoderInitAttempt) -> Throwable?) {
        val attempts = mutableListOf<EncoderInitAttempt>()
        val perform: (EncoderInitAttempt) -> Unit = { attempt ->
            attempts += attempt
            failures(attempt)?.let { throw it }
        }
    }

    private fun chain(candidates: List<String> = listOf("a.hw", "b.hw", "c.sw"), keys: List<EncoderOptionalKey> = allKeys, sleeps: MutableList<Long> = mutableListOf()) =
        EncoderInitChain(candidates, keys, sleep = { sleeps += it })

    @Test
    fun successOnTheFirstAttemptChangesNothing() {
        val fake = Fake { null }
        val outcome = chain().run(fake.perform)
        assertEquals(1, fake.attempts.size)
        assertEquals("a.hw", outcome.effective.encoder)
        assertTrue(outcome.effective.dropped.isEmpty())
        assertFalse(outcome.effective.lowPriority)
        assertEquals("requested", outcome.effective.reason)
    }

    @Test
    fun optionalKeysAreDroppedNewestFirstOneMoreEachAttempt() {
        // Fails while LATENCY or STATISTICS or INTRA_REFRESH are present; COMPLEXITY alone is fine.
        val fake = Fake { attempt ->
            if (attempt.encoder == "a.hw" && attempt.dropped.size < 3) IllegalStateException("configure failed") else null
        }
        val outcome = chain().run(fake.perform)
        assertEquals(4, fake.attempts.size)
        // Declaration order is the drop order: latency, intra refresh, statistics, complexity.
        assertEquals(
            listOf(
                emptySet(),
                setOf(EncoderOptionalKey.LATENCY),
                setOf(EncoderOptionalKey.LATENCY, EncoderOptionalKey.INTRA_REFRESH),
                setOf(EncoderOptionalKey.LATENCY, EncoderOptionalKey.INTRA_REFRESH, EncoderOptionalKey.STATISTICS),
            ),
            fake.attempts.map { it.dropped },
        )
        assertEquals("a.hw", outcome.effective.encoder)
        assertEquals("drop_statistics", outcome.effective.reason)
    }

    @Test
    fun resourceErrorsRetryAfterAPauseThenWithLowPriorityThenMoveToTheNextEncoderWithoutDroppingKeys() {
        val sleeps = mutableListOf<Long>()
        val fake = Fake { attempt ->
            if (attempt.encoder == "a.hw") IllegalStateException("start failed 0xfffffff4 NO_MEMORY") else null
        }
        val outcome = chain(sleeps = sleeps).run(fake.perform)
        assertEquals(
            listOf("requested", "retry_after_resource_error", "retry_priority_1", "next_encoder"),
            fake.attempts.map { it.reason },
        )
        assertEquals(listOf(false, false, true, false), fake.attempts.map { it.lowPriority })
        // Dropping keys cannot free a resource: the same full key set went to the alternate.
        assertTrue(fake.attempts.all { it.dropped.isEmpty() })
        assertEquals(listOf(EncoderInitChain.RESOURCE_PAUSE_MS, EncoderInitChain.RESOURCE_PAUSE_MS), sleeps)
        assertEquals("b.hw", outcome.effective.encoder)
    }

    @Test
    fun aResourceErrorThatClearsAfterOneRetryKeepsTheRequestedEncoder() {
        var calls = 0
        val outcome = chain().run({ attempt ->
            calls++
            if (calls == 1) throw IllegalStateException("insufficient resource")
        })
        assertEquals(2, calls)
        assertEquals("a.hw", outcome.effective.encoder)
        assertEquals("retry_after_resource_error", outcome.effective.reason)
    }

    @Test
    fun codecExceptionResourceCodeIsRecognised() {
        assertEquals(EncoderInitFailure.RESOURCE, EncoderInitFailure.classify(RuntimeException("x"), errorCode = 1100))
        assertEquals(EncoderInitFailure.RESOURCE, EncoderInitFailure.classify(RuntimeException("x"), errorCode = 1101))
        assertEquals(EncoderInitFailure.OTHER, EncoderInitFailure.classify(RuntimeException("x"), errorCode = 1))
        assertEquals(EncoderInitFailure.RESOURCE, EncoderInitFailure.classify(RuntimeException("outer", RuntimeException("err 0xfffffff4"))))
        assertEquals(EncoderInitFailure.TIMEOUT, EncoderInitFailure.classify(BoundedCall.TimedOut("configure", 10)))
    }

    @Test
    fun aTimeoutSkipsStraightToTheNextEncoder() {
        val fake = Fake { attempt -> if (attempt.encoder == "a.hw") BoundedCall.TimedOut("encoder-configure", 10_000) else null }
        val outcome = chain().run(fake.perform)
        assertEquals(listOf("a.hw", "b.hw"), fake.attempts.map { it.encoder })
        assertEquals("next_encoder", outcome.effective.reason)
    }

    @Test
    fun alternateEncodersTryTheFullFormatThenWithoutAnyOptionalKey() {
        val fake = Fake { attempt ->
            when {
                attempt.encoder == "a.hw" -> IllegalStateException("configure failed")
                attempt.encoder == "b.hw" && attempt.dropped.isEmpty() -> IllegalStateException("configure failed")
                else -> null
            }
        }
        val outcome = chain().run(fake.perform)
        val onB = fake.attempts.filter { it.encoder == "b.hw" }
        assertEquals(listOf(emptySet<EncoderOptionalKey>(), allKeys.toSet()), onB.map { it.dropped })
        assertEquals("b.hw", outcome.effective.encoder)
        assertEquals(allKeys.toSet(), outcome.effective.dropped)
    }

    @Test
    fun whenEverythingFailsTheFirstErrorIsTheReportedCause() {
        val first = IllegalStateException("first failure")
        var n = 0
        val fake = Fake { if (n++ == 0) first else IllegalStateException("later $n") }
        try {
            chain().run(fake.perform)
            fail("expected exhaustion")
        } catch (exhausted: EncoderInitExhausted) {
            assertSame(first, exhausted.cause)
            assertTrue(exhausted.records.isNotEmpty())
            assertTrue(exhausted.records.all { it.error != null })
            val line = EncoderInitChain.benchmarkLine("a.hw", allKeys, null, exhausted)
            assertTrue(line, line.startsWith("BENCHMARK encoder_init_fallback v1 ") && "\"result\":\"failed\"" in line)
        }
    }

    @Test
    fun attemptsAreBounded() {
        val fake = Fake { IllegalStateException("always") }
        try {
            chain(candidates = listOf("a", "b", "c", "d"), keys = EncoderOptionalKey.entries).run(fake.perform)
            fail("expected exhaustion")
        } catch (exhausted: EncoderInitExhausted) {
            assertTrue(fake.attempts.size <= EncoderInitChain.MAX_ATTEMPTS)
        }
    }

    @Test
    fun noOptionalKeysMeansOneAttemptPerEncoder() {
        val fake = Fake { attempt -> if (attempt.encoder == "a.hw") IllegalStateException("x") else null }
        val outcome = chain(keys = emptyList()).run(fake.perform)
        assertEquals(listOf("a.hw", "b.hw"), fake.attempts.map { it.encoder })
        assertEquals("b.hw", outcome.effective.encoder)
    }

    @Test
    fun theBenchmarkLineStatesRequestedVersusEffective() {
        val fake = Fake { attempt -> if (attempt.dropped.isEmpty()) IllegalStateException("configure failed") else null }
        val outcome = chain().run(fake.perform)
        val line = EncoderInitChain.benchmarkLine("a.hw", allKeys, outcome, null)
        assertTrue(line, "\"requested_encoder\":\"a.hw\"" in line && "\"effective_encoder\":\"a.hw\"" in line)
        assertTrue(line, "\"fell_back\":true" in line && "\"dropped_keys\":[\"latency\"]" in line)
        val clean = EncoderInitChain.benchmarkLine("a.hw", allKeys, chain().run(Fake { null }.perform), null)
        assertTrue(clean, "\"fell_back\":false" in clean)
    }

    @Test
    fun boundedCallReturnsRethrowsAndTimesOut() {
        assertEquals(5, BoundedCall.run("ok", 1_000) { 5 })
        try {
            BoundedCall.run("boom", 1_000) { throw IllegalStateException("boom") }
            fail()
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }
        val hang = CountDownLatch(1)
        try {
            BoundedCall.run("hang", 50) { hang.await() }
            fail()
        } catch (timeout: BoundedCall.TimedOut) {
            assertEquals("hang", timeout.name)
        } finally {
            hang.countDown()
        }
        val problems = mutableListOf<String>()
        val released = CountDownLatch(1)
        assertFalse(BoundedCall.runQuietly("hang2", 50, { released.await() }) { problems += it })
        released.countDown()
        assertEquals(1, problems.size)
        assertTrue(BoundedCall.runQuietly("fine", 1_000, {}))
    }

    @Test
    fun shutdownStopsTheCameraBeforeTheCodecAndEveryStepRunsEvenAfterAFailure() {
        val order = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val results = EncoderShutdown { messages += it }.run(
            EncoderShutdown.steps(
                stopRepeating = { order += "stopRepeating" },
                closeSession = { order += "closeSession"; throw IllegalStateException("session already closed") },
                closeCamera = { order += "closeCamera" },
                stopCodec = { order += "stopCodec" },
                releaseCodec = { order += "releaseCodec" },
            ),
        )
        assertEquals(listOf("stopRepeating", "closeSession", "closeCamera", "stopCodec", "releaseCodec"), order)
        assertEquals(listOf(true, false, true, true, true), results.map { it.ok })
        assertTrue(messages.isEmpty())
    }

    @Test
    fun shutdownRefusesACameraStepAfterACodecStep() {
        val steps = listOf(
            EncoderShutdown.Step("stopCodec", EncoderShutdown.Group.CODEC, null) {},
            EncoderShutdown.Step("closeCamera", EncoderShutdown.Group.CAMERA, null) {},
        )
        try {
            EncoderShutdown().run(steps)
            fail("the camera must stop feeding first")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("camera"))
        }
    }

    @Test
    fun aHungReleaseIsBoundedAndLogged() {
        val hang = CountDownLatch(1)
        val messages = mutableListOf<String>()
        val started = System.nanoTime()
        val results = EncoderShutdown { messages += it }.run(
            listOf(
                EncoderShutdown.Step("closeCamera", EncoderShutdown.Group.CAMERA, null) {},
                EncoderShutdown.Step("releaseCodec", EncoderShutdown.Group.CODEC, 100) { hang.await() },
            ),
        )
        hang.countDown()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(results.last().timedOut)
        assertTrue(messages.single(), messages.single().startsWith("BENCHMARK codec_release_timeout:"))
    }

    @Test
    fun exynosQpRangeSetsOnlyKeysTheComponentLists() {
        val listed = listOf(
            "vendor.sec-ext-enc-qp-range.I-minQP", "vendor.sec-ext-enc-qp-range.I-maxQP",
            "vendor.sec-ext-enc-qp-range.P-minQP", "vendor.sec-ext-enc-qp-range.P-maxQP:int32",
            "vendor.sec-ext-enc-drop-control.value", "vendor.rtc-ext-enc-low-latency.enable",
        )
        val params = ExynosQpRange.parameters(listed, 20..48)
        assertEquals(
            mapOf(
                "vendor.sec-ext-enc-qp-range.I-maxQP" to 48,
                "vendor.sec-ext-enc-qp-range.I-minQP" to 20,
                "vendor.sec-ext-enc-qp-range.P-maxQP" to 48,
                "vendor.sec-ext-enc-qp-range.P-minQP" to 20,
            ),
            params,
        )
        assertTrue(ExynosQpRange.parameters(listOf("vendor.rtc-ext-enc-low-latency.enable"), 20..48).isEmpty())
        assertTrue(ExynosQpRange.parameters(emptyList(), 20..48).isEmpty())
        assertTrue(ExynosQpRange.scopedComponent("OMX.Exynos.VP9.Encoder"))
        assertTrue(ExynosQpRange.scopedComponent("c2.exynos.vp9.encoder"))
        assertFalse(ExynosQpRange.scopedComponent("c2.qti.av1.encoder"))
        assertFalse(ExynosQpRange.scopedComponent("OMX.qcom.video.encoder.avc"))
    }

    @Test
    fun keyLatencyIsNeverForH264OrOmxOrBelowAndroid11() {
        assertTrue(encoderKeyLatencyAllowed("video/av01", 34, "c2.qti.av1.encoder"))
        assertTrue(encoderKeyLatencyAllowed("video/x-vnd.on2.vp9", 31, "c2.exynos.vp9.encoder"))
        assertFalse(encoderKeyLatencyAllowed("video/avc", 34, "c2.qti.avc.encoder"))
        assertFalse(encoderKeyLatencyAllowed("video/av01", 29, "c2.qti.av1.encoder"))
        assertFalse(encoderKeyLatencyAllowed("video/x-vnd.on2.vp9", 31, "OMX.Exynos.VP9.Encoder"))
    }

    @Test
    fun theDropOrderIsLatencyThenColourThenVendorQpThenTheOlderKeys() {
        assertEquals(
            listOf("latency", "colour", "vendor_qp", "intra_refresh", "qp_bounds", "statistics", "complexity", "temporal_layering"),
            EncoderOptionalKey.entries.map { it.label },
        )
    }
}
