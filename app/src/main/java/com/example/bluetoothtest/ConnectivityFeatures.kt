package com.example.bluetoothtest

/**
 * The shipped defaults of the connectivity features promoted on 2026-10-11 (docs/SWITCHES.md register,
 * "Experimental, alternative and optional behaviour"). Each feature has exactly one constant here.
 * Every one stays OFF until the phone test named beside it passes; turning a feature on for users
 * who never touched its switch is then a one-line change. The privacy features BMH3 and BMH3_PSEUDONYMS
 * were switched on for 0.2.0-beta (design decision 2026-10-12, after T14/T15 passed). A user's explicit choice always wins, and
 * a DEBUG benchmark launch (any `test_` extra) never reads these constants (see [FeatureCatalog]).
 */
internal object ConnectivityFeatureDefaults {
    /** LE CoC to GATT safety net on the A15s class of stack (T6: CoC exhaustion, automatic GATT). */
    const val COC_AUTO_GATT = false

    /** Classic RFCOMM without Android pairing, both phones (T7). */
    const val CLASSIC_WITHOUT_PAIRING = false

    /** Compact (single-UUID) announcements of iPhones in discovery (T8: masked scan filter on the six phones). */
    const val IPHONE_DISCOVERY = false

    /** Help > Share app nearby over Wi-Fi Direct (T9). Flipping it makes the Help entry a normal one. */
    const val SHARE_APP_NEARBY = false

    /** Native libopus call audio: VOIP mode, in-band FEC, PLC (T10: A/B against MediaCodec). */
    const val NATIVE_OPUS = false

    /** Dual listening: a CoC call endpoint also answers over BLE GATT and says so in one announcement bit (T12). */
    const val DUAL_LISTENING = false

    /** Hidden identities: the BMH3 handshake hides both phones' identity keys from a sniffer (T14). On since 0.2.0-beta. */
    const val BMH3 = true

    /** Pseudonymous stranger identities on top of BMH3 and rotating IDs (T15). On since 0.2.0-beta. */
    const val BMH3_PSEUDONYMS = true
}

/**
 * Specs of the connectivity features in Settings > Features. The old Advanced preference keys and
 * `test_*` extras are kept, so a stored choice of an earlier build still counts and benchmark cases
 * keep their exact switches.
 */
internal object ConnectivityFeatures {
    const val GROUP = "connectivity"

    val cocAutoGatt = FeatureSpec(
        id = "coc_auto_gatt",
        prefKey = CocAutoGattSettings.PREF_ENABLED,
        defaultEnabled = ConnectivityFeatureDefaults.COC_AUTO_GATT,
        benchmarkExtra = CocAutoGattSettings.EXTRA_ENABLED,
        titleRes = R.string.feat_coc_auto_gatt,
        descriptionRes = R.string.feat_coc_auto_gatt_description,
        iconRes = R.drawable.ic_refresh,
        group = GROUP,
        phoneTest = "T6",
    )

    val classicWithoutPairing = FeatureSpec(
        id = "classic_without_pairing",
        prefKey = ClassicFeatureSettings.PREF_BOND_FREE,
        defaultEnabled = ConnectivityFeatureDefaults.CLASSIC_WITHOUT_PAIRING,
        benchmarkExtra = ClassicFeatureSettings.EXTRA_BOND_FREE,
        titleRes = R.string.feat_classic_bond_free,
        descriptionRes = R.string.feat_classic_bond_free_description,
        iconRes = R.drawable.ic_bluetooth,
        group = GROUP,
        phoneTest = "T7",
    )

    val iphoneDiscovery = FeatureSpec(
        id = "iphone_discovery",
        prefKey = CompactDiscoveryFeature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.IPHONE_DISCOVERY,
        benchmarkExtra = CompactDiscoveryFeature.EXTRA,
        titleRes = R.string.feat_iphone_discovery,
        descriptionRes = R.string.feat_iphone_discovery_description,
        iconRes = R.drawable.ic_visibility,
        group = GROUP,
        phoneTest = "T8",
    )

    val shareAppNearby = FeatureSpec(
        id = "share_app_nearby",
        prefKey = OfflineAppShareFeature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.SHARE_APP_NEARBY,
        benchmarkExtra = OfflineAppShareFeature.EXTRA,
        titleRes = R.string.feat_share_app_nearby,
        descriptionRes = R.string.feat_share_app_nearby_description,
        iconRes = R.drawable.ic_group,
        group = GROUP,
        phoneTest = "T9",
    )

    val nativeOpus = FeatureSpec(
        id = "native_opus",
        prefKey = NativeOpusSettings.PREF_ENABLED,
        defaultEnabled = ConnectivityFeatureDefaults.NATIVE_OPUS,
        benchmarkExtra = NativeOpusSettings.EXTRA_ENABLED,
        titleRes = R.string.feat_native_opus,
        descriptionRes = R.string.feat_native_opus_description,
        iconRes = R.drawable.ic_mic,
        group = GROUP,
        phoneTest = "T10",
    )

    val dualListening = FeatureSpec(
        id = "dual_listening",
        prefKey = DualListeningFeature.PREF_ENABLED,
        defaultEnabled = ConnectivityFeatureDefaults.DUAL_LISTENING,
        benchmarkExtra = DualListeningFeature.EXTRA_ENABLED,
        titleRes = R.string.feat_dual_listening,
        descriptionRes = R.string.feat_dual_listening_description,
        iconRes = R.drawable.ic_relay,
        group = GROUP,
        phoneTest = "T12",
    )

    val bmh3 = FeatureSpec(
        id = "bmh3",
        prefKey = Bmh3Feature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.BMH3,
        benchmarkExtra = Bmh3Feature.EXTRA,
        titleRes = R.string.feat_bmh3,
        descriptionRes = R.string.feat_bmh3_description,
        iconRes = R.drawable.ic_lock,
        group = GROUP,
        phoneTest = "T14",
    )

    /** Needs BMH3 (it is greyed out and off while "Hidden identities" is off); it also needs rotating IDs at run time. */
    val bmh3Pseudonyms = FeatureSpec(
        id = "bmh3_pseudonyms",
        prefKey = Bmh3PseudonymFeature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.BMH3_PSEUDONYMS,
        benchmarkExtra = Bmh3PseudonymFeature.EXTRA,
        titleRes = R.string.feat_bmh3_pseudonyms,
        descriptionRes = R.string.feat_bmh3_pseudonyms_description,
        iconRes = R.drawable.ic_lock,
        group = GROUP,
        dependsOn = "bmh3",
        phoneTest = "T15",
    )

    /** In Settings order. */
    val all: List<FeatureSpec> = listOf(
        classicWithoutPairing, cocAutoGatt, dualListening, iphoneDiscovery, nativeOpus, shareAppNearby, bmh3, bmh3Pseudonyms,
    )

    /** Idempotent: an activity can be recreated. */
    fun registerAll() = all.forEach(FeatureCatalog::register)
}
