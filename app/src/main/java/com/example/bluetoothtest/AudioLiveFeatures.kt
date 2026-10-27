package com.example.bluetoothtest

/** Keys of the audio-only live feature: the benchmark launch extra and the remembered Go live choices. */
object AudioLiveSettings {
    const val EXTRA_ENABLED = "test_audio_live"

    /** Remembered Go live choice, quality and first-use disclosure (plain preferences, not a feature switch). */
    const val PREF_LAST_CHOICE = "audio_live_last_choice"
    const val PREF_PRESET = "audio_live_preset"
    const val PREF_DISCLOSED = "audio_live_disclosed"

    const val CHOICE_VIDEO = "video"
    const val CHOICE_AUDIO = "audio"
}

/**
 * Spec of the audio-only live stream. On for everyone ([AudioLiveFeatureDefaults.ENABLED], 2026-10-17) and no
 * longer shown in Settings: a constant with no stored choice (user request 2026-10-17; its `feature_audio_live_v2`
 * key was read but never written). A benchmark launch reads only `test_audio_live`, so `test_audio_live=false`
 * remains the off arm for comparisons.
 */
internal object AudioLiveFeatures {
    val audioLive = FeatureSpec(
        id = "audio_live",
        prefKey = null,
        defaultEnabled = AudioLiveFeatureDefaults.ENABLED,
        benchmarkExtra = AudioLiveSettings.EXTRA_ENABLED,
        titleRes = 0,
        descriptionRes = 0,
        iconRes = 0,
        group = ViewerFeatures.GROUP,
        phoneTest = "T-AL1 to T-AL11",
    )

    /** Whether Go live offers audio only and Watch lists audio-only streams (the last catalog refresh). */
    val enabled: Boolean get() = FeatureCatalog.isEnabled(audioLive.id)
}
