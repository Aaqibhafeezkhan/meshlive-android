package com.example.bluetoothtest

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSimdSafeModeTest {
    private class MemoryStore(
        var marker: NativeSimdSafeMode.Marker? = null,
        var state: NativeSimdSafeMode.State = NativeSimdSafeMode.State(),
    ) : NativeSimdSafeMode.Store {
        override fun readMarker() = marker
        override fun writeMarker(marker: NativeSimdSafeMode.Marker) {
            this.marker = marker
        }

        override fun clearMarker() {
            marker = null
        }

        override fun readState() = state
        override fun writeState(state: NativeSimdSafeMode.State) {
            this.state = state
        }
    }

    private val marker = NativeSimdSafeMode.Marker(startedAtMs = 100_000, pid = 4242)
    private fun crash(afterMs: Long, signal: Int = NativeSimdSafeMode.SIGILL, reason: Int = ApplicationExitInfo.REASON_CRASH_NATIVE) =
        NativeSimdSafeMode.ExitFacts(reason, signal, marker.startedAtMs + afterMs)

    @Test
    fun theMaskKeepsNeonAndCrc32AndClearsEverythingElse() {
        assertEquals(0x3, NativeSimdSafeMode.CAPS_MASK)
        // HAS_NEON = 1, HAS_ARM_CRC32 = 2, HAS_NEON_DOTPROD = 4, HAS_NEON_I8MM = 8, HAS_SVE = 16, HAS_SVE2 = 32 (aom_ports/arm.h).
        for (cleared in listOf(4, 8, 16, 32)) assertEquals(0, NativeSimdSafeMode.CAPS_MASK and cleared)
        assertEquals(1, NativeSimdSafeMode.CAPS_MASK and 1)
    }

    @Test
    fun aNativeCrashSoonAfterEncoderInitEntersSafeMode() {
        val s = NativeSimdSafeMode.State()
        for (signal in listOf(NativeSimdSafeMode.SIGILL, NativeSimdSafeMode.SIGSEGV, NativeSimdSafeMode.SIGBUS)) {
            assertEquals(
                NativeSimdSafeMode.Decision.ENTER_SAFE,
                NativeSimdSafeMode.decide(true, true, s, marker, crash(2_000, signal)),
            )
        }
    }

    @Test
    fun crashesThatAreNotEvidenceAgainstSimdLeaveItAlone() {
        val s = NativeSimdSafeMode.State()
        // Late crash, Java crash, ANR, abort, user stop, crash before the marker: all normal.
        val normal = listOf(
            crash(NativeSimdSafeMode.CRASH_WINDOW_MS + 1),
            crash(2_000, reason = ApplicationExitInfo.REASON_CRASH),
            crash(2_000, reason = ApplicationExitInfo.REASON_ANR),
            crash(2_000, signal = 6),
            crash(2_000, reason = ApplicationExitInfo.REASON_USER_REQUESTED),
            crash(-1),
        )
        normal.forEach {
            assertEquals(NativeSimdSafeMode.Decision.NORMAL, NativeSimdSafeMode.decide(true, true, s, marker, it))
        }
        assertEquals(NativeSimdSafeMode.Decision.NORMAL, NativeSimdSafeMode.decide(true, true, s, null, null))
    }

    @Test
    fun aMarkerWithoutExitInformationIsUnverifiableAndNeverActs() {
        assertEquals(
            NativeSimdSafeMode.Decision.UNVERIFIABLE,
            NativeSimdSafeMode.decide(true, true, NativeSimdSafeMode.State(), marker, null),
        )
        assertFalse(NativeSimdSafeMode.safeModeActive(NativeSimdSafeMode.Decision.UNVERIFIABLE))
    }

    @Test
    fun offSwitchAndNon64BitAreInert() {
        assertEquals(NativeSimdSafeMode.Decision.OFF, NativeSimdSafeMode.decide(false, true, NativeSimdSafeMode.State(), marker, crash(1_000)))
        assertEquals(NativeSimdSafeMode.Decision.OFF, NativeSimdSafeMode.decide(true, false, NativeSimdSafeMode.State(safe = true), marker, crash(1_000)))
    }

    @Test
    fun safeModeStaysUntilACrashInSafeModeExoneratesSimd() {
        val safe = NativeSimdSafeMode.State(safe = true)
        assertEquals(NativeSimdSafeMode.Decision.STAY_SAFE, NativeSimdSafeMode.decide(true, true, safe, null, null))
        assertEquals(NativeSimdSafeMode.Decision.EXONERATED, NativeSimdSafeMode.decide(true, true, safe, marker, crash(1_000)))
        val exonerated = NativeSimdSafeMode.stateAfter(NativeSimdSafeMode.Decision.EXONERATED, safe)
        assertEquals(NativeSimdSafeMode.State(safe = false, exonerated = true), exonerated)
        // After exoneration the same crash pattern does not re-enter safe mode.
        assertEquals(NativeSimdSafeMode.Decision.NORMAL, NativeSimdSafeMode.decide(true, true, exonerated, marker, crash(1_000)))
    }

    @Test
    fun prepareAppliesTheMaskPersistsTheStateAndClearsTheMarker() {
        val store = MemoryStore(marker = marker)
        val env = mutableMapOf<String, String>()
        val decision = NativeSimdSafeMode.prepare(store, true, true, { crash(3_000) }, { k, v -> env[k] = v })
        assertEquals(NativeSimdSafeMode.Decision.ENTER_SAFE, decision)
        assertEquals(mapOf("AOM_SIMD_CAPS_MASK" to "0x3"), env)
        assertNull(store.marker)
        assertTrue(store.state.safe)
        // The next start without a crash stays safe and sets the mask again.
        env.clear()
        assertEquals(NativeSimdSafeMode.Decision.STAY_SAFE, NativeSimdSafeMode.prepare(store, true, true, { null }, { k, v -> env[k] = v }))
        assertEquals("0x3", env["AOM_SIMD_CAPS_MASK"])
    }

    @Test
    fun prepareWithTheSwitchOffTouchesNothing() {
        val store = MemoryStore(marker = marker, state = NativeSimdSafeMode.State(safe = true))
        val env = mutableMapOf<String, String>()
        assertEquals(NativeSimdSafeMode.Decision.OFF, NativeSimdSafeMode.prepare(store, false, true, { crash(1_000) }, { k, v -> env[k] = v }))
        assertTrue(env.isEmpty())
        assertEquals(marker, store.marker)
        assertTrue(store.state.safe)
    }

    @Test
    fun aThrowingExitLookupOrEnvironmentSetterNeverPropagates() {
        val store = MemoryStore(marker = marker)
        val d = NativeSimdSafeMode.prepare(store, true, true, { error("no exit info") }, { _, _ -> })
        assertEquals(NativeSimdSafeMode.Decision.UNVERIFIABLE, d)
        val store2 = MemoryStore(marker = marker)
        val d2 = NativeSimdSafeMode.prepare(store2, true, true, { crash(500) }, { _, _ -> error("setenv failed") })
        assertEquals(NativeSimdSafeMode.Decision.ENTER_SAFE, d2)
    }

    @Test
    fun markersAreWrittenOnlyWithTheSwitchAndClearedWhenStable() {
        val store = MemoryStore()
        NativeSimdSafeMode.markInit(store, false, 5, 1)
        assertNull(store.marker)
        NativeSimdSafeMode.markInit(store, true, 5, 1)
        assertEquals(NativeSimdSafeMode.Marker(5, 1), store.marker)
        NativeSimdSafeMode.markStable(store)
        assertNull(store.marker)
    }

    @Test
    fun fileStoreRoundTrips() {
        val dir = java.nio.file.Files.createTempDirectory("simd-store").toFile()
        try {
            val store = NativeSimdSafeMode.FileStore(dir)
            assertNull(store.readMarker())
            assertEquals(NativeSimdSafeMode.State(), store.readState())
            store.writeMarker(marker)
            assertEquals(marker, store.readMarker())
            store.writeState(NativeSimdSafeMode.State(safe = true, exonerated = false))
            assertEquals(NativeSimdSafeMode.State(safe = true), store.readState())
            store.clearMarker()
            assertNull(store.readMarker())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun logLineAndSwitchDefault() {
        assertFalse(ThermalSwitches.simdSafeMode)
        assertEquals(
            "BENCHMARK native_simd_safe_mode: decision=enter_safe | safeMode=true | persistedSafe=true | exonerated=false | maskEnv=AOM_SIMD_CAPS_MASK=0x3",
            NativeSimdSafeMode.logLine(NativeSimdSafeMode.Decision.ENTER_SAFE, NativeSimdSafeMode.State(safe = true), masked = true),
        )
    }
}
