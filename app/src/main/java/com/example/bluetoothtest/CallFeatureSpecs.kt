package com.example.bluetoothtest

/**
 * Describes the promoted call and message features ([CallFeature], CallFeatureSettings.kt) to the
 * [FeatureCatalog], so one registry lists every promoted feature, its preference key, its default
 * and its benchmark extra. The specs are derived from the [CallFeature] entries (their prefKey,
 * extra and default), so there is exactly one default per feature: `CallFeatureDefaults`.
 * The calls card is still drawn and bound by `CallFeatureSettingsBinder`
 * (res/layout/settings_features_calls.xml), not by `FeatureSettingsBlock`.
 *
 * `CallFeatureResolver.enabled` and `FeatureCatalog.resolve` apply the same rule; a unit test
 * (`FeatureCatalogTest`) keeps them equal. "Show caller names on the lock screen" is not listed: it is
 * a privacy preference stored by `NotificationPrivacy`, with no benchmark extra and no default flip.
 */
internal object CallFeatureSpecs {
    const val GROUP = "calls"

    private fun idOf(feature: CallFeature): String = when (feature) {
        CallFeature.VOICE_CALLS -> "voice_calls"
        CallFeature.RINGBACK -> "call_ringback"
        CallFeature.EAR_CAMERA_PAUSE -> "call_ear_camera_pause"
        CallFeature.LOCK_SCREEN_CALLS -> "call_lock_screen"
        CallFeature.CALL_NOTIFICATIONS -> "call_notifications"
        CallFeature.MESSAGES -> "messages"
        CallFeature.AUTO_SYNC -> "messages_auto_sync"
        CallFeature.NAMES_AND_TITLES -> "names_and_titles"
    }

    private fun titleOf(feature: CallFeature): Int = when (feature) {
        CallFeature.VOICE_CALLS -> R.string.feature_voice_calls
        CallFeature.RINGBACK -> R.string.feature_ringback
        CallFeature.EAR_CAMERA_PAUSE -> R.string.feature_ear_camera_pause
        CallFeature.LOCK_SCREEN_CALLS -> R.string.feature_lock_screen_calls
        CallFeature.CALL_NOTIFICATIONS -> R.string.feature_call_notifications
        CallFeature.MESSAGES -> R.string.feature_messages
        CallFeature.AUTO_SYNC -> R.string.feature_auto_sync
        CallFeature.NAMES_AND_TITLES -> R.string.feature_names
    }

    private fun descriptionOf(feature: CallFeature): Int = when (feature) {
        CallFeature.VOICE_CALLS -> R.string.feature_voice_calls_description
        CallFeature.RINGBACK -> R.string.feature_ringback_description
        CallFeature.EAR_CAMERA_PAUSE -> R.string.feature_ear_camera_pause_description
        CallFeature.LOCK_SCREEN_CALLS -> R.string.feature_lock_screen_calls_description
        CallFeature.CALL_NOTIFICATIONS -> R.string.feature_call_notifications_description
        CallFeature.MESSAGES -> R.string.feature_messages_description
        CallFeature.AUTO_SYNC -> R.string.feature_auto_sync_description
        CallFeature.NAMES_AND_TITLES -> R.string.feature_names_description
    }

    private fun phoneTestOf(feature: CallFeature): String = when (feature) {
        CallFeature.VOICE_CALLS, CallFeature.RINGBACK, CallFeature.EAR_CAMERA_PAUSE -> "T1"
        CallFeature.LOCK_SCREEN_CALLS, CallFeature.CALL_NOTIFICATIONS -> "T2"
        CallFeature.MESSAGES -> "T3"
        CallFeature.AUTO_SYNC -> "T4"
        CallFeature.NAMES_AND_TITLES -> "T5"
    }

    /** In [CallFeature] order, which lists Messages before the automatic sync that depends on it. */
    val all: List<FeatureSpec> = CallFeature.entries.map { feature ->
        FeatureSpec(
            id = idOf(feature),
            prefKey = feature.prefKey,
            defaultEnabled = feature.defaultOn,
            benchmarkExtra = feature.benchmarkExtra,
            titleRes = titleOf(feature),
            descriptionRes = descriptionOf(feature),
            group = GROUP,
            dependsOn = if (feature == CallFeature.AUTO_SYNC) idOf(CallFeature.MESSAGES) else null,
            phoneTest = phoneTestOf(feature),
        )
    }

    fun specOf(feature: CallFeature): FeatureSpec = all.first { it.id == idOf(feature) }

    /** Idempotent. */
    fun registerAll() = all.forEach(FeatureCatalog::register)
}
