package com.example.bluetoothtest

/**
 * Experimental audio-only live stream ("radio"): the one constant that ships it. Design and promotion
 * criteria: docs/DECISIONS.md "Design AL"; the register of switches: docs/SWITCHES.md. Default ON since
 * 2026-10-17 (design decision after short phone checks; the remaining T-AL* tests stay open); users can still
 * turn it off in Settings > Features.
 */
object AudioLiveFeatureDefaults {
    const val ENABLED = true
}

/**
 * Benchmark switches of the audio-only live stream. Every arm other than the defaults is DEBUG-only (a launch
 * extra from another app must not change a release build's behaviour) and is set from the DEBUG launch extras
 * `test_audio_live_*`; the defaults are the shipped behaviour inside the feature.
 */
object AudioLiveTunables {
    /** `test_audio_live_kbps`: 16, 24 or 48 overrides the chosen preset (benchmark only). */
    @Volatile var kbpsOverride: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && AudioLive.presetForKbps(it) != null }
        }

    /** `test_audio_live_batch_frames`: frames per record, 1 to 20 (default 10 = 200 ms). */
    @Volatile var batchFrames: Int = AudioLive.DEFAULT_BATCH_FRAMES
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(AudioLive.MIN_BATCH_FRAMES, AudioLive.MAX_BATCH_FRAMES)
            else AudioLive.DEFAULT_BATCH_FRAMES
        }

    /** `test_audio_live_fec` (Experimental, default off): in-band FEC with the 10% loss hint. */
    @Volatile var fec: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_audio_live_dtx` (Experimental, default off): silent frames are not sent; changes the cadence. */
    @Volatile var dtx: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_audio_live_age_bound`: true inside the feature; false is the control arm (no shedding). */
    @Volatile var ageBound: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** `test_audio_live_max_children`: source cap on direct listeners (default 4 until the ladder test). */
    @Volatile var maxChildren: Int = AudioLive.DEFAULT_MAX_CHILDREN
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(1, 16) else AudioLive.DEFAULT_MAX_CHILDREN
        }

    /**
     * `test_audio_live_native_opus` (default true): libopus through the audio-only entry points; false forces
     * MediaCodec encode and decode. Independent of the Settings toggle "Call audio with loss recovery".
     */
    @Volatile var nativeOpus: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** `test_audio_live_digests` (DEBUG, default off): a per-unit hash line per record, on both ends. */
    @Volatile var unitDigests: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_audio_live_adaptive` (Experimental, default off, not implemented in this version). */
    @Volatile var adaptive: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_audio_live_link_priority=balanced` (Experimental, default off): the slice 3 relay/listener arm. */
    @Volatile var balancedLinkPriority: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_audio_live_relay_children` (1 in this version; two is phase 2). */
    @Volatile var relayChildren: Int = 1
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(1, 2) else 1
        }

    /**
     * Reads the DEBUG launch extras `test_audio_live_*` (release builds keep every default: the setters above
     * gate each experimental arm on DEBUG). [stringExtra] is only used for `test_audio_live_link_priority`.
     */
    fun applyExtras(
        boolExtra: (String, Boolean) -> Boolean,
        intExtra: (String, Int) -> Int,
        stringExtra: (String) -> String?,
    ) {
        kbpsOverride = intExtra("test_audio_live_kbps", 0).takeIf { it > 0 }
        batchFrames = intExtra("test_audio_live_batch_frames", AudioLive.DEFAULT_BATCH_FRAMES)
        unitDigests = boolExtra("test_audio_live_digests", false)
        fec = boolExtra("test_audio_live_fec", false)
        dtx = boolExtra("test_audio_live_dtx", false)
        ageBound = boolExtra("test_audio_live_age_bound", true)
        maxChildren = intExtra("test_audio_live_max_children", AudioLive.DEFAULT_MAX_CHILDREN)
        nativeOpus = boolExtra("test_audio_live_native_opus", true)
        adaptive = boolExtra("test_audio_live_adaptive", false)
        balancedLinkPriority = stringExtra("test_audio_live_link_priority") == "balanced"
        relayChildren = intExtra("test_audio_live_relay_children", 1)
    }

    /** The preset in force: the benchmark override when valid, else [chosen]. */
    fun effectivePreset(chosen: Int): Int = kbpsOverride?.let(AudioLive::presetForKbps) ?: chosen
}
