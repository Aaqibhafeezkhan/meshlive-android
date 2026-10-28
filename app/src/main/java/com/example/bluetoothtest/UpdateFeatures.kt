package com.example.bluetoothtest

/**
 * Shipped defaults of the update features (SWITCHES register, "Experimental, alternative and optional
 * behaviour"; design decision 2026-10-11). One constant per feature, as in [ConnectivityFeatureDefaults].
 */
internal object UpdateFeatureDefaults {
    /**
     * "Check for updates" (github flavour only). Off until the user answers the one-time question
     * "Check for updates when online?"; a yes stores an explicit choice. No request is ever made
     * while it is off.
     */
    const val CHECK_FOR_UPDATES = false

    /**
     * "Tell me about newer versions nearby": send this build's release number in the link hello and
     * show a quiet notice when a connected phone has a newer one (PROTOCOL "Build tag in the link
     * hello"). On by default, design decision 2026-10-11; phone test T13 confirms it.
     */
    const val NEWER_VERSIONS_NEARBY = true
}

/** Keys of the update features: benchmark launch extras and the user's stored choices. */
object UpdateSettings {
    /** The app's settings file (MainActivity.APP_PREFERENCES is private; UpdateFeaturesTest keeps them equal). */
    const val PREFERENCES_FILE = "bluetooth_video_settings"

    const val EXTRA_CHECK_FOR_UPDATES = "test_check_for_updates"
    const val PREF_CHECK_FOR_UPDATES = "feature_check_for_updates"
    const val EXTRA_NEWER_VERSIONS_NEARBY = "test_newer_versions_nearby"
    const val PREF_NEWER_VERSIONS_NEARBY = "feature_newer_versions_nearby"

    /** DEBUG only: pretend this build is release N, so debug phones (versionCode 1) can take part in T13. */
    const val EXTRA_DEBUG_RELEASE_NUMBER = "test_update_release_number"

    /** DEBUG only: "true" makes a debug build send the project-release-key bit. */
    const val EXTRA_DEBUG_RELEASE_KEY = "test_update_release_key"

    /** DEBUG only: an http(s) URL replacing the GitHub `releases/latest` URL (T13 uses a local server). */
    const val EXTRA_DEBUG_LATEST_URL = "test_update_latest_url"

    /** DEBUG only: "true" makes this debug-signed build count as signed with the pinned release key. */
    const val EXTRA_DEBUG_PIN_OWN_SIGNER = "test_update_pin_own_signer"
}

/** Specs of the update features in Settings > Features. */
internal object UpdateFeatures {
    const val GROUP = "updates"

    val checkForUpdates = FeatureSpec(
        id = "check_for_updates",
        prefKey = UpdateSettings.PREF_CHECK_FOR_UPDATES,
        defaultEnabled = UpdateFeatureDefaults.CHECK_FOR_UPDATES,
        benchmarkExtra = UpdateSettings.EXTRA_CHECK_FOR_UPDATES,
        titleRes = R.string.feat_check_for_updates,
        descriptionRes = R.string.feat_check_for_updates_description,
        iconRes = R.drawable.ic_refresh,
        group = GROUP,
        phoneTest = "T13",
    )

    val newerVersionsNearby = FeatureSpec(
        id = "newer_versions_nearby",
        prefKey = UpdateSettings.PREF_NEWER_VERSIONS_NEARBY,
        defaultEnabled = UpdateFeatureDefaults.NEWER_VERSIONS_NEARBY,
        benchmarkExtra = UpdateSettings.EXTRA_NEWER_VERSIONS_NEARBY,
        titleRes = R.string.feat_newer_versions_nearby,
        descriptionRes = R.string.feat_newer_versions_nearby_description,
        iconRes = R.drawable.ic_group,
        group = GROUP,
        phoneTest = "T13",
    )

    /**
     * In Settings order. The F-Droid flavour has no updater: its only row is the nearby notice, and
     * the online row does not exist (so nothing there can ever make a request).
     */
    fun all(updaterAvailable: Boolean): List<FeatureSpec> =
        if (updaterAvailable) listOf(checkForUpdates, newerVersionsNearby) else listOf(newerVersionsNearby)

    /** Idempotent: an activity can be recreated. */
    fun registerAll(updaterAvailable: Boolean) = all(updaterAvailable).forEach(FeatureCatalog::register)
}
