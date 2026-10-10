package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecPolicyTest {
    private val phone = DeviceTraits("s5e9945", "erd9945", 36)

    private val hwVp9 = DecoderOption("c2.exynos.vp9.decoder", alias = false, softwareOnly = false, hardware = true)
    private val swVp9 = DecoderOption("c2.android.vp9.decoder", alias = false, softwareOnly = true, hardware = false)
    private val aliasVp9 = DecoderOption("OMX.google.vp9.decoder", alias = true, softwareOnly = true, hardware = false)
    private val otherSw = DecoderOption("c2.vendor.sw.vp9.decoder", alias = false, softwareOnly = true, hardware = false)
    private val supporting = listOf(swVp9, aliasVp9, hwVp9, otherSw)

    @Test
    fun fallbackOffKeepsTheFormerSingleChoiceExactly() {
        fun off(requested: String?, framework: String?) =
            CodecPolicy.decoderInitCandidates(requested, if (requested == null) DecoderRequest.NONE else DecoderRequest.EXPLICIT,
                framework, supporting, fallback = false)
        assertEquals(listOf("c2.exynos.vp9.decoder"), off(null, "c2.exynos.vp9.decoder"))
        assertEquals(listOf("c2.android.vp9.decoder"), off("C2.ANDROID.VP9.DECODER", "c2.exynos.vp9.decoder"))
        // The former lookup allowed aliases and silently took the framework's choice for an unusable request.
        assertEquals(listOf("OMX.google.vp9.decoder"), off("OMX.google.vp9.decoder", "c2.exynos.vp9.decoder"))
        assertEquals(listOf("c2.exynos.vp9.decoder"), off("c2.missing.decoder", "c2.exynos.vp9.decoder"))
        assertEquals(emptyList<String>(), off(null, null))
        assertEquals("requested-unsupported",
            CodecPolicy.decoderSelectionReason("c2.missing.decoder", DecoderRequest.EXPLICIT, "c2.exynos.vp9.decoder", 0))
    }

    @Test
    fun explicitRequestsAreNeverSubstituted() {
        assertEquals(listOf("c2.exynos.vp9.decoder"), CodecPolicy.decoderInitCandidates(
            "c2.exynos.vp9.decoder", DecoderRequest.EXPLICIT, "c2.android.vp9.decoder", supporting, fallback = true))
        assertEquals(emptyList<String>(), CodecPolicy.decoderInitCandidates(
            "c2.missing.decoder", DecoderRequest.EXPLICIT, "c2.exynos.vp9.decoder", supporting, fallback = true))
    }

    @Test
    fun softwareProtectionFallsBackOnlyToSoftwareDecoders() {
        val candidates = CodecPolicy.decoderInitCandidates(
            "c2.android.vp9.decoder", DecoderRequest.SOFTWARE_PROTECTION, "c2.exynos.vp9.decoder", supporting, fallback = true)
        assertEquals(listOf("c2.android.vp9.decoder", "c2.vendor.sw.vp9.decoder"), candidates)
        assertFalse("c2.exynos.vp9.decoder" in candidates)
        assertEquals("software-fallback", CodecPolicy.decoderSelectionReason(
            "c2.android.vp9.decoder", DecoderRequest.SOFTWARE_PROTECTION, "c2.vendor.sw.vp9.decoder", 1))
        // Requested decoder unusable and no other software decoder: nothing to try, the call fails visibly.
        assertEquals(emptyList<String>(), CodecPolicy.decoderInitCandidates(
            "c2.android.vp9.decoder", DecoderRequest.SOFTWARE_PROTECTION, "c2.exynos.vp9.decoder", listOf(hwVp9), fallback = true))
    }

    @Test
    fun unrequestedFallbackIsHardwareFirstWithoutAliasesAndBounded() {
        val candidates = CodecPolicy.decoderInitCandidates(
            null, DecoderRequest.NONE, "c2.exynos.vp9.decoder", supporting, fallback = true)
        assertEquals(listOf("c2.exynos.vp9.decoder", "c2.android.vp9.decoder", "c2.vendor.sw.vp9.decoder"), candidates)
        val many = (1..6).map { DecoderOption("c2.v$it.vp9.decoder", alias = false, softwareOnly = false, hardware = true) }
        assertEquals(CodecPolicy.MAX_DECODER_INIT_ATTEMPTS,
            CodecPolicy.decoderInitCandidates(null, DecoderRequest.NONE, null, many, fallback = true).size)
        assertEquals("framework", CodecPolicy.decoderSelectionReason(null, DecoderRequest.NONE, "c2.exynos.vp9.decoder", 0))
        assertEquals("fallback", CodecPolicy.decoderSelectionReason(null, DecoderRequest.NONE, "c2.android.vp9.decoder", 1))
        assertEquals("all-failed", CodecPolicy.decoderSelectionReason(null, DecoderRequest.NONE, null, 3))
    }

    @Test
    fun decoderFallbackIsForUserSessionsOnlyAndHasAnOffSwitch() {
        assertTrue(CodecPolicy.decoderInitFallbackEnabled(phone, benchmarkLaunch = false))
        assertFalse(CodecPolicy.decoderInitFallbackEnabled(phone, benchmarkLaunch = true))
        assertFalse(DeviceFamilyPolicy.resolve(phone, setOf("decoder-init-fallback")).has<DecoderInitFallback>())
    }

    @Test
    fun deliveredFrameRateCountsTheLastSecondOfTimestamps() {
        val rate = DeliveredFrameRate()
        var result: Int? = null
        for (frame in 0..30) result = rate.record(frame * 66_667L) // 15 FPS for 2 s
        assertEquals(15, result)
        for (step in 0..60) result = rate.record(3_000_000L + step * 33_334L) // then 30 FPS
        assertEquals(30, result)
        assertEquals(null, rate.record(0L)) // timestamps went back: a new window starts
    }

    @Test
    fun onlyRecoverableCodecExceptionsReinitAnyDecoderAndOnlyTwice() {
        assertTrue(CodecPolicy.recoverableReinitAllowed(recoverable = true, attempts = 0, traits = phone))
        assertTrue(CodecPolicy.recoverableReinitAllowed(recoverable = true, attempts = 1, traits = phone))
        assertFalse(CodecPolicy.recoverableReinitAllowed(recoverable = true, attempts = 2, traits = phone))
        assertFalse(CodecPolicy.recoverableReinitAllowed(recoverable = false, attempts = 0, traits = phone))
        val off = DeviceFamilyPolicy.resolve(phone, setOf("codec-exception-recoverable-reinit"))
        assertTrue(off.effects<RecoverableDecoderReinit>().isEmpty())
        // The c2.android.vp9.decoder component rule still recovers a non-recoverable failure.
        assertTrue(canRecoverPrivateCallVideoDecoderFailure("c2.android.vp9.decoder", 0, recoverable = false, traits = phone))
        assertFalse(canRecoverPrivateCallVideoDecoderFailure("c2.qti.vp9.decoder", 0, recoverable = false, traits = phone))
        assertTrue(canRecoverPrivateCallVideoDecoderFailure("c2.qti.vp9.decoder", 0, recoverable = true, traits = phone))
    }

    @Test
    fun framerateAdjusterUsesWebRtcScopeAndFormula() {
        assertTrue(CodecPolicy.framerateAdjusterScope("OMX.Exynos.VP9.Encoder", "video/x-vnd.on2.vp9"))
        assertTrue(CodecPolicy.framerateAdjusterScope("OMX.Exynos.AVC.Encoder", "video/avc"))
        assertFalse(CodecPolicy.framerateAdjusterScope("OMX.Exynos.VP8.Encoder", "video/x-vnd.on2.vp8"))
        assertFalse(CodecPolicy.framerateAdjusterScope("c2.exynos.vp9.encoder", "video/x-vnd.on2.vp9"))
        assertEquals(600_000, CodecPolicy.framerateAdjustedBps(300_000, configuredFps = 30, currentFps = 15))
        assertEquals(300_000, CodecPolicy.framerateAdjustedBps(300_000, configuredFps = 15, currentFps = 15))
        assertEquals(300_000, CodecPolicy.framerateAdjustedBps(300_000, configuredFps = 15, currentFps = 0))
        // The arm is a proposal: the resolver never activates it, only the DEBUG switch does.
        val m31 = DeviceTraits("exynos9611", "universal9611", 31, codecComponents = listOf("OMX.Exynos.VP9.Encoder"))
        assertTrue(DeviceFamilyPolicy.resolve(m31, emptySet()).matchingProposals.any { it.id == "omx-exynos-framerate-adjuster-arm" })
        assertFalse(DebugTunables.encoderFramerateAdjuster)
    }

    @Test
    fun codecAndYuvEntriesAreInTheOneRegistryWithTheirArea() {
        val ids = DeviceFamilyPolicy.entries.associateBy { it.id }
        for (id in listOf("decoder-init-fallback", "codec-exception-recoverable-reinit", "omx-exynos-framerate-adjuster-arm")) {
            assertEquals(id, PolicyArea.CODEC, ids.getValue(id).area)
        }
        assertEquals(PolicyArea.YUV, ids.getValue("yuv-image-crop-rect").area)
        assertEquals(PolicyValidation.EXPERIMENTAL, ids.getValue("omx-exynos-framerate-adjuster-arm").validation)
        for (id in listOf("decoder-init-fallback", "codec-exception-recoverable-reinit", "omx-exynos-framerate-adjuster-arm", "yuv-image-crop-rect")) {
            assertTrue(id, ids.getValue(id).fallback.isNotEmpty())
        }
    }
}
