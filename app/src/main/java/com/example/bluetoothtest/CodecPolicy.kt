package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.GOOGLE_OPEN_SOURCE
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * Codec policy (encoder/decoder selection, lifecycle and recovery, bitrate adjusters, layering
 * capability). Owner: codec workstream.
 *
 * Entries here join DevicePolicyRegistry.entries (one registry). Use DevicePolicyRegistry matchers
 * and constants freely, but never DevicePolicyRegistry.entries (initialisation order).
 *
 * Upstream references are pinned: AndroidX Media3 at 8c6678b657ede1e7883fc164ef73ed483c7796c3
 * (2026-09-08) and WebRTC at 42995c384b59f0da002a6078d4a0320af39a16b8 (2026-08-13, the project's
 * reference-repos/webrtc checkout).
 */
internal object CodecPolicy {
    private const val REVIEWED = "2026-10-10"
    private const val MEDIA3 = "https://github.com/androidx/media/blob/8c6678b657ede1e7883fc164ef73ed483c7796c3/" +
        "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/"
    private const val WEBRTC = "https://webrtc.googlesource.com/src/+/42995c384b59f0da002a6078d4a0320af39a16b8/"

    private val MEDIA3_INIT_FALLBACK = PolicySource(
        GOOGLE_OPEN_SOURCE,
        MEDIA3 + "mediacodec/MediaCodecRenderer.java#L1295-L1360",
        "maybeInitCodecWithFallback: a decoder that throws in create/configure/start is released and the next " +
            "decoder for the format is tried; the first failure stays the reported cause. Media3 makes it opt-in " +
            "(setEnableDecoderFallback) because a file player can show an error; a live call cannot.",
    )
    private val MEDIA3_RECOVERABLE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        MEDIA3 + "mediacodec/MediaCodecRenderer.java#L994-L1003",
        "a MediaCodec.CodecException with isRecoverable() releases the codec so the next render re-creates it",
    )
    private val CODEC_EXCEPTION_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodec.CodecException#isRecoverable()",
        "isRecoverable(): the codec cannot continue but can be recovered by stop(), configure() and start(); " +
            "isTransient(): retry later; neither: release and create a new codec",
    )
    private val WEBRTC_ADJUSTER_CHOICE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        WEBRTC + "sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java#254",
        "createBitrateAdjuster: names starting OMX.Exynos. get DynamicBitrateAdjuster for VP8 and " +
            "FramerateBitrateAdjuster for VP9/H.264; every other encoder gets BaseBitrateAdjuster (no correction)",
    )
    private val WEBRTC_FRAMERATE_ADJUSTER = PolicySource(
        GOOGLE_OPEN_SOURCE,
        WEBRTC + "sdk/android/src/java/org/webrtc/FramerateBitrateAdjuster.java#14",
        "'Used with hardware codecs that assume the framerate never changes': the codec keeps its configured " +
            "frame rate and the bitrate is scaled by configured / current FPS, so it corrects UNDERshoot after a " +
            "frame-rate drop",
    )

    /** Media3-style decoder initialisation fallback (every phone). */
    val DECODER_INIT_FALLBACK = DevicePolicyEntry(
        id = "decoder-init-fallback",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Decoder initialisation falls back to the next decoder for the format",
        matcher = DeviceMatcher(),
        effects = listOf(DecoderInitFallback),
        behaviour = "User sessions without an explicit decoder request: when the chosen video decoder throws while it " +
            "is created, configured or started (for example hardware instances exhausted), release it and try the next " +
            "decoder that supports the format (hardware first; at most 3 attempts). A policy-forced software decoder " +
            "falls back only to other software decoders. DEBUG test_decoder requests and benchmark launches never " +
            "fall back. Every start logs BENCHMARK decoder requested=/effective=/reason=.",
        sources = listOf(MEDIA3_INIT_FALLBACK),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no local decoder initialisation failure recorded; failure path only"),
        confidence = MEDIUM,
        confidenceNote = "Media3 mechanism; nothing changes when the first decoder starts",
        reviewed = REVIEWED,
        origin = "Compatibility audit 2026-10-10 (VideoStreamDecoder tried one decoder and failed the stream)",
        fallback = "Off, or any benchmark launch: the former single choice (requested decoder if it supports the " +
            "format, else the framework's) and the former failure.",
    )

    /** Recoverable CodecException: re-create the call video decoder in place (every phone). */
    val RECOVERABLE_CODEC_EXCEPTION = DevicePolicyEntry(
        id = "codec-exception-recoverable-reinit",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Recoverable decoder CodecException re-creates the call decoder",
        matcher = DeviceMatcher(),
        effects = listOf(RecoverableDecoderReinit(maxAttempts = 2)),
        behaviour = "A private-call video decoder that fails with a CodecException whose isRecoverable() is true is " +
            "closed and re-created at the next keyframe (at most twice per call), as c2.android.vp9.decoder " +
            "already is; audio and the call stay up.",
        sources = listOf(MEDIA3_RECOVERABLE, CODEC_EXCEPTION_DOC),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: only c2.android.vp9.decoder failures were recorded (three_phone_2026-10-01)"),
        confidence = MEDIUM,
        confidenceNote = "documented API contract; stream receivers still end on a decoder failure",
        reviewed = REVIEWED,
        origin = "Compatibility audit 2026-10-10 (generalises c2-android-vp9-decoder-in-place-recovery)",
        fallback = "Off: only the component rule c2-android-vp9-decoder-in-place-recovery recovers; others end the call.",
    )

    /**
     * WebRTC's FramerateBitrateAdjuster as a default-off A/B arm (DEBUG
     * `test_encoder_framerate_adjuster=true`). A proposal: inert in the resolver, the switch alone
     * enables it within the exact WebRTC scope ([framerateAdjusterScope]).
     */
    val EXYNOS_FRAMERATE_ADJUSTER_ARM = DevicePolicyEntry(
        id = "omx-exynos-framerate-adjuster-arm",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "OMX.Exynos VP9/H.264: WebRTC FramerateBitrateAdjuster A/B arm",
        matcher = DeviceMatcher(),
        component = CodecComponentMatcher(prefixes = setOf("OMX.Exynos.")),
        effects = emptyList(),
        behaviour = "With DEBUG test_encoder_framerate_adjuster=true, an OMX.Exynos. VP9 or H.264 encoder keeps its " +
            "configured FPS and is asked for target x configured / delivered FPS (WebRTC FramerateBitrateAdjuster; " +
            "delivered = encoded frames in the last second of output timestamps, re-applied at most once per second) " +
            "instead of the measured EncoderBitrateAdjuster.",
        sources = listOf(WEBRTC_ADJUSTER_CHOICE, WEBRTC_FRAMERATE_ADJUSTER),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "docs/dev/decisions/README.md row 73f517c5 -> b5ebe110 (OMX.Exynos.VP9 291-481 kbps for 300: overshoot)",
            "benchmarks/runs/xpair-s24m31-431d40dc (M31 OMX.Exynos.VP9 720 kbps for a 401-515 kbps target)",
        ),
        confidence = LOW,
        confidenceNote = "WebRTC scope is exact; it targets undershoot after FPS drops, while our measured M31 " +
            "problem is overshoot at a fixed FPS (KEY_MAX_FPS_TO_ENCODER already caps the cadence), so the measured " +
            "DynamicBitrateAdjuster port stays the default",
        reviewed = REVIEWED,
        origin = "Compatibility audit 2026-10-10 (A/B arm for the proposal omx-exynos-vp9-framerate-bitrate-adjuster)",
        proposed = true,
        fallback = "Off (default): EncoderBitrateAdjuster (DynamicBitrateAdjuster port) as today.",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        DECODER_INIT_FALLBACK,
        RECOVERABLE_CODEC_EXCEPTION,
        EXYNOS_FRAMERATE_ADJUSTER_ARM,
    ) + DecoderPolicyEntries.entries

    /** At most this many decoders are tried for one stream/call start (the first choice plus two fallbacks). */
    const val MAX_DECODER_INIT_ATTEMPTS = 3

    /**
     * Decoder names to try, in order (Media3 maybeInitCodecWithFallback). [supporting] lists every
     * non-secure decoder that supports the format, in MediaCodecList order (aliases included).
     *
     * With [fallback] false (entry off, or a benchmark launch) this is exactly the former choice: the
     * [requested] decoder when it supports the format (aliases allowed), else the framework's
     * [frameworkChoice]; the BENCHMARK decoder line exposes any substitution and the scorer fails it.
     * With [fallback] true, aliases are skipped and the list depends on [request]:
     * - EXPLICIT (test_decoder): only the requested decoder; no substitution, it fails visibly.
     * - SOFTWARE_PROTECTION (a policy-forced software decoder): the requested decoder, then other
     *   software-only decoders; never a hardware decoder.
     * - NONE: the framework's choice, then the other decoders, hardware first.
     * At most [MAX_DECODER_INIT_ATTEMPTS] names.
     *
     * [lowLatencyFirst] (Experimental `test_decoder_low_latency_ladder`, request NONE only): hardware decoders
     * whose [DecoderOption.lowLatency] is set come before the framework's choice (Moonlight findKnownSafeDecoder:
     * Exynos OMX before C2, Qualcomm `.low_latency` variants listed after the standard component). With
     * [fallback] false it replaces the single choice by the first such decoder. Default false: unchanged.
     */
    fun decoderInitCandidates(
        requested: String?,
        request: DecoderRequest,
        frameworkChoice: String?,
        supporting: List<DecoderOption>,
        fallback: Boolean,
        lowLatencyFirst: Boolean = false,
    ): List<String> {
        val requestedMatch = requested?.let { name -> supporting.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val lowLatencyHardware = if (lowLatencyFirst && request == DecoderRequest.NONE && requestedMatch == null) {
            supporting.filter { !it.alias && it.hardware && it.lowLatency }.map { it.name }
        } else {
            emptyList()
        }
        if (!fallback) return listOfNotNull(requestedMatch?.name ?: lowLatencyHardware.firstOrNull() ?: frameworkChoice)
        val usable = supporting.filterNot { it.alias }
        val ordered = when (request) {
            DecoderRequest.EXPLICIT -> listOfNotNull(requestedMatch?.name)
            DecoderRequest.SOFTWARE_PROTECTION ->
                listOfNotNull(requestedMatch?.name) + usable.filter { it.softwareOnly }.map { it.name }
            DecoderRequest.NONE ->
                lowLatencyHardware + listOfNotNull(frameworkChoice) + usable.sortedBy { if (it.hardware) 0 else 1 }.map { it.name }
        }
        val distinct = ArrayList<String>()
        for (name in ordered) if (distinct.none { it.equals(name, ignoreCase = true) }) distinct += name
        return distinct.take(MAX_DECODER_INIT_ATTEMPTS)
    }

    /** The `reason=` token of the BENCHMARK decoder line. */
    fun decoderSelectionReason(
        requested: String?,
        request: DecoderRequest,
        effective: String?,
        attempt: Int,
        /** The Experimental low-latency order put a decoder before the framework's choice and it was used. */
        lowLatencyReordered: Boolean = false,
    ): String = when {
        effective == null -> "all-failed"
        requested != null && effective.equals(requested, ignoreCase = true) -> "requested"
        requested != null && attempt == 0 -> "requested-unsupported"
        request == DecoderRequest.SOFTWARE_PROTECTION -> "software-fallback"
        attempt == 0 && lowLatencyReordered -> "low-latency-first"
        attempt == 0 -> "framework"
        else -> "fallback"
    }

    /**
     * decoder-init-fallback applies to user sessions only: off when the entry is switched off and in
     * any DEBUG benchmark launch ([DebugTunables.benchmarkLaunch]), which keeps the former selection.
     */
    fun decoderInitFallbackEnabled(traits: DeviceTraits? = null, benchmarkLaunch: Boolean = DebugTunables.benchmarkLaunch): Boolean =
        !benchmarkLaunch && effectsFor<DecoderInitFallback>(traits).isNotEmpty()

    /**
     * True when recovery must release and re-create [decoderName] instead of flushing it
     * (`c2-mtk-release-instead-of-flush`, Media3 b/362904942). Decoder components only: encoders never come here.
     */
    fun releaseInsteadOfFlush(decoderName: String, traits: DeviceTraits? = null): Boolean =
        decoderName.contains(".decoder", ignoreCase = true) &&
            DeviceFamilyPolicy.componentEffects<ReleaseCodecInsteadOfFlush>(decoderName, traits).isNotEmpty()

    /** `decoder-no-output-watchdog` on (default): stalls are logged; recovery is [DecoderSwitches.stallRecovery]. */
    fun stallWatchdogEnabled(traits: DeviceTraits? = null): Boolean = effectsFor<DecoderStallWatchdog>(traits).isNotEmpty()

    /** `decoder-bounded-release` on (default): the bounded wait in ms, else null (inline release as before). */
    fun boundedReleaseTimeoutMs(traits: DeviceTraits? = null): Long? =
        effectsFor<BoundedDecoderRelease>(traits).minOfOrNull { it.timeoutMs }

    /** True when a call decoder failure with this CodecException may re-create the decoder. */
    fun recoverableReinitAllowed(recoverable: Boolean, attempts: Int, traits: DeviceTraits? = null): Boolean {
        if (!recoverable) return false
        return effectsFor<RecoverableDecoderReinit>(traits).any { attempts < it.maxAttempts }
    }

    /**
     * WebRTC HardwareVideoEncoderFactory.createBitrateAdjuster scope for FramerateBitrateAdjuster:
     * an OMX.Exynos. encoder for VP9 or H.264 (VP8 gets the dynamic adjuster there).
     */
    fun framerateAdjusterScope(encoderName: String, mimeType: String): Boolean =
        encoderName.startsWith("OMX.Exynos.", ignoreCase = true) &&
            (mimeType.equals("video/x-vnd.on2.vp9", ignoreCase = true) || mimeType.equals("video/avc", ignoreCase = true))

    /** FramerateBitrateAdjuster: target x configured FPS / current FPS (the codec keeps its configured FPS). */
    fun framerateAdjustedBps(targetBps: Int, configuredFps: Int, currentFps: Int): Int =
        if (configuredFps <= 0 || currentFps <= 0) {
            targetBps
        } else {
            (targetBps.toLong() * configuredFps / currentFps).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }

    /** Device-level effects for [traits], or (no traits: pure JVM) entries with a universal matcher. */
    private inline fun <reified T : DevicePolicyEffect> effectsFor(traits: DeviceTraits?): List<T> {
        if (traits != null) return DeviceFamilyPolicy.resolve(traits).effects()
        val off = DeviceFamilyPolicy.switchedOff
        return DeviceFamilyPolicy.entries.filter { entry ->
            !entry.proposed && entry.component == null && entry.matcher.universal && "all" !in off && entry.id !in off
        }.flatMap { it.effects }.filterIsInstance<T>()
    }
}

/**
 * Frames delivered in the last [windowUs] of presentation time (the FramerateBitrateAdjuster arm's
 * current FPS). Null until a full window has been seen; a timestamp that does not advance restarts it.
 */
internal class DeliveredFrameRate(private val windowUs: Long = 1_000_000L) {
    private val timestamps = ArrayDeque<Long>()
    private var startUs = Long.MIN_VALUE

    fun record(ptsUs: Long): Int? {
        if (timestamps.isNotEmpty() && ptsUs <= timestamps.last()) {
            timestamps.clear()
            startUs = Long.MIN_VALUE
        }
        if (startUs == Long.MIN_VALUE) startUs = ptsUs
        timestamps.addLast(ptsUs)
        while (timestamps.first() <= ptsUs - windowUs) timestamps.removeFirst()
        return if (ptsUs - startUs >= windowUs) timestamps.size else null
    }
}

/** Why a decoder was named for a VideoStreamDecoder. */
enum class DecoderRequest(val label: String) {
    NONE("none"),
    /** DEBUG test_decoder: the case measures that decoder, so it is never substituted. */
    EXPLICIT("explicit"),
    /** A registry entry forced a software decoder (qcom-legacy-call-vp9-software-decoder). */
    SOFTWARE_PROTECTION("software-protection"),
}

/** One decoder that supports the format (MediaCodecInfo flags, API 29+). */
internal data class DecoderOption(
    val name: String,
    val alias: Boolean,
    val softwareOnly: Boolean,
    val hardware: Boolean,
    /** FEATURE_LowLatency is declared; read only when the Experimental low-latency order is on. */
    val lowLatency: Boolean = false,
)

/** Decoder creation tries the next decoder for the format when one fails ([CodecPolicy.decoderInitCandidates]). */
internal data object DecoderInitFallback : DevicePolicyEffect {
    override fun describe(): String = "decoder init falls back to the next decoder for the format"
}

/** A recoverable CodecException re-creates the call video decoder up to [maxAttempts] times. */
internal data class RecoverableDecoderReinit(val maxAttempts: Int) : DevicePolicyEffect {
    override fun describe(): String = "re-create the call video decoder after a recoverable CodecException (<= $maxAttempts)"
}
