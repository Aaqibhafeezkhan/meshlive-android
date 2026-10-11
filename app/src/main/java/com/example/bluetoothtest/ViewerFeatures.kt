package com.example.bluetoothtest

/**
 * Shipped defaults of the stream-viewer features (README register, "Experimental, alternative and
 * optional behaviour"). One constant per feature, in the same pattern as [ConnectivityFeatureDefaults]:
 * it stays OFF until the phone test named beside it passes; turning the feature on for users who never
 * touched its switch is then a one-line change. A user's explicit choice always wins, and a DEBUG
 * benchmark launch (any `test_` extra) never reads this constant (see [FeatureCatalog]).
 */
internal object ViewerFeatureDefaults {
    /**
     * Stale-picture overlay, honest "last update" label and `viewer_frame_age` log for stream viewers
     * (T11: relay loss and rejoin on phones; README register).
     */
    const val FRAME_AGE = false
}

/** Keys of the viewer frame-age feature: the benchmark launch extra and the user's stored choice. */
object ViewerFrameAgeSettings {
    const val EXTRA_ENABLED = "test_viewer_frame_age"
    const val PREF_ENABLED = "feature_viewer_frame_age"

    /** The shared rule ([FeatureCatalog.resolve]): a benchmark launch reads only its extra, default off. */
    internal fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): Boolean = FeatureCatalog.resolve(ViewerFeatures.frameAge, benchmarkLaunch, extra, stored)
}

/** Specs of the viewer features in Settings > Features. */
internal object ViewerFeatures {
    const val GROUP = "viewer"

    val frameAge = FeatureSpec(
        id = "viewer_frame_age",
        prefKey = ViewerFrameAgeSettings.PREF_ENABLED,
        defaultEnabled = ViewerFeatureDefaults.FRAME_AGE,
        benchmarkExtra = ViewerFrameAgeSettings.EXTRA_ENABLED,
        titleRes = R.string.feat_viewer_frame_age,
        descriptionRes = R.string.feat_viewer_frame_age_description,
        iconRes = R.drawable.ic_visibility,
        group = GROUP,
        phoneTest = "T11",
    )

    /** In Settings order. */
    val all: List<FeatureSpec> = listOf(frameAge)

    /** Idempotent: an activity can be recreated. */
    fun registerAll() = all.forEach(FeatureCatalog::register)
}
