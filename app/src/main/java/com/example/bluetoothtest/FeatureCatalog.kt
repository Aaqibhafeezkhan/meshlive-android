package com.example.bluetoothtest

/**
 * The shared catalog of promoted features (2026-10-11, user request to bring the experimental
 * features into the normal app). Pure Kotlin: no Android types, so every rule is JVM-tested.
 *
 * One [FeatureSpec] per feature carries the stable id, the preference key of the user's explicit
 * choice, the ONE default constant, the DEBUG benchmark extra and the Settings texts. The rules are
 * the same for every feature ([FeatureCatalog.resolve] is the one resolve rule):
 *
 * - A benchmark launch (a DEBUG build started with any `test_` extra) reads only the feature's own
 *   extra, default off ([FeatureSpec.benchmarkDefault]). A stored choice or a flipped default never
 *   reaches a measured case, so case IDs and results stay valid.
 * - Every other launch uses the user's explicit choice when there is one, otherwise the spec's
 *   default. Neither the "Manual settings" toggle nor [BuildConfig.DEBUG] takes part, except where a
 *   spec says so explicitly ([FeatureSpec.advancedOnly], [FeatureSpec.extraInAnyDebugLaunch]).
 *   Flipping a feature on after its phone test is one line (the default constant), and it reaches
 *   everyone who never touched the switch; an explicit choice sticks.
 * - A constant ([FeatureSpec.prefKey] null) has no stored choice and no Settings row: a user launch
 *   always uses its default.
 * - A feature with [FeatureSpec.dependsOn] is on only while every feature it names is on, and its
 *   Settings row is greyed out while one of them is off.
 *
 * Adding a feature: declare a spec, add it to [AppFeatures.registerAll] (an identical re-registration
 * is a no-op), resolve it with [resolve] or read the snapshot, and either show it in the generated
 * Settings block (`FeatureSettingsBlock`, one group per card) or bind your own layout to
 * [FeatureSpec.prefKey]. [current] always holds the snapshot of the last [refresh].
 */
data class FeatureSpec(
    /** Stable snake_case id; logged and used by [FeatureSnapshot]. Never reused for another feature. */
    val id: String,
    /**
     * SharedPreferences key of the user's explicit choice; no stored value means "follow [defaultEnabled]". Null makes the
     * feature a constant ([constant]): nothing is stored or read, and it has no Settings row.
     */
    val prefKey: String?,
    /** The one default for users who never touched the switch. Flip it after the feature's phone test. */
    val defaultEnabled: Boolean,
    /** DEBUG launch extra of benchmark runs. Null: benchmark launches always use [benchmarkDefault]. */
    val benchmarkExtra: String?,
    /** `R.string` id of the switch title (0 for none: the feature has no generated row). */
    val titleRes: Int = 0,
    /** `R.string` id of the explanation under the switch. */
    val descriptionRes: Int = 0,
    /** `R.drawable` id of the icon before the title (0 for none). */
    val iconRes: Int = 0,
    /** The Settings card this feature belongs to ("connectivity", "calls", ...). */
    val group: String = GROUP_GENERAL,
    /**
     * Ids of the features that must also be on, registered first: the parent of a sub-option (automatic sync under
     * Messages) or a run-time requirement (rotating IDs under Find PCs). The row is greyed out while one of them is off.
     */
    val dependsOn: List<String> = emptyList(),
    /** The phone test that must pass before [defaultEnabled] may become true (documentation only). */
    val phoneTest: String? = null,
    /** Earlier names of [benchmarkExtra]; any of them set to true switches the feature on, so old launch lines keep working. */
    val extraAliases: List<String> = emptyList(),
    /** A benchmark launch without the extra. Off, except for a feature whose measured cases ran with it on. */
    val benchmarkDefault: Boolean = false,
    /** The extra decides in every DEBUG launch that carries it, also a person's launch (`test_user_launch`). Never in release. */
    val extraInAnyDebugLaunch: Boolean = false,
    /** The stored choice counts only while Manual settings (Advanced) is on, where its switch is shown; otherwise the default. */
    val advancedOnly: Boolean = false,
) {
    /** No stored choice and no Settings row: a user launch always uses [defaultEnabled]. */
    val constant: Boolean get() = prefKey == null

    /** [benchmarkExtra] and its [extraAliases]. */
    val extraNames: List<String> get() = listOfNotNull(benchmarkExtra) + extraAliases

    init {
        require(ID_PATTERN.matches(id)) { "Feature id must be snake_case: $id" }
        require(prefKey == null || prefKey.isNotBlank()) { "Feature $id needs a preference key or none" }
        require(id !in dependsOn) { "Feature $id cannot depend on itself" }
        require(!(prefKey == null && titleRes != 0)) { "Constant feature $id cannot have a Settings row" }
        require(!(prefKey == null && advancedOnly)) { "Constant feature $id has no stored choice to gate" }
        require(extraAliases.isEmpty() || benchmarkExtra != null) { "Feature $id has aliases but no extra" }
    }

    companion object {
        const val GROUP_GENERAL = "general"
        private val ID_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}

/** The effective values of all registered features for one launch. */
class FeatureSnapshot internal constructor(private val values: Map<String, Boolean>) {
    /** False for an id that is not registered. */
    fun isOn(id: String): Boolean = values[id] == true

    fun isOn(spec: FeatureSpec): Boolean = isOn(spec.id)

    /** `id=on|off` in registration order, for one log line. */
    fun label(): String = values.entries.joinToString(" | ") { "${it.key}=${if (it.value) "on" else "off"}" }

    override fun equals(other: Any?): Boolean = other is FeatureSnapshot && other.values == values

    override fun hashCode(): Int = values.hashCode()

    companion object {
        val EMPTY = FeatureSnapshot(emptyMap())
    }
}

object FeatureCatalog {
    private val specs = LinkedHashMap<String, FeatureSpec>()

    @Volatile
    var current: FeatureSnapshot = FeatureSnapshot.EMPTY
        private set

    /**
     * Adds [spec]. Registering the same spec twice is a no-op (an activity can be recreated). A
     * different spec under a used id or preference key is a programming error.
     */
    @Synchronized
    fun register(spec: FeatureSpec) {
        val existing = specs[spec.id]
        if (existing != null) {
            require(existing == spec) { "Feature ${spec.id} is already registered with different settings" }
            return
        }
        spec.prefKey?.let { key ->
            require(specs.values.none { it.prefKey == key }) {
                "Preference key $key is already used by ${specs.values.first { it.prefKey == key }.id}"
            }
        }
        spec.dependsOn.forEach { parent ->
            require(parent in specs) { "Feature ${spec.id} depends on $parent, which must be registered first" }
        }
        specs[spec.id] = spec
    }

    @Synchronized
    fun all(): List<FeatureSpec> = specs.values.toList()

    @Synchronized
    fun spec(id: String): FeatureSpec? = specs[id]

    @Synchronized
    fun group(group: String): List<FeatureSpec> = specs.values.filter { it.group == group }

    /** Test hook: forgets every registration. */
    @Synchronized
    internal fun clearForTest() {
        specs.clear()
        current = FeatureSnapshot.EMPTY
        lastLaunch = Launch()
    }

    /**
     * The one resolve rule for one feature, ignoring [FeatureSpec.dependsOn] (the caller already knows the parents).
     *
     * [extra] returns a launch extra, null when the launch does not carry it (a caller that cannot tell returns false: then
     * [FeatureSpec.benchmarkDefault] and [FeatureSpec.extraInAnyDebugLaunch] cannot see an absent extra). [stored] returns the
     * user's explicit choice, null when the user never decided. [advancedSettings] is whether Manual settings (Advanced) is
     * on; only [FeatureSpec.advancedOnly] reads it. [debugBuild] is [BuildConfig.DEBUG]; only
     * [FeatureSpec.extraInAnyDebugLaunch] reads it (a benchmark launch exists only in a DEBUG build).
     *
     * - Benchmark launch: the extra (any alias set to true counts), else [FeatureSpec.benchmarkDefault].
     * - [FeatureSpec.extraInAnyDebugLaunch] in a DEBUG build: an extra the launch carries decides.
     * - Constant, or [FeatureSpec.advancedOnly] with Advanced off: [FeatureSpec.defaultEnabled].
     * - Otherwise the stored choice, else [FeatureSpec.defaultEnabled].
     */
    fun resolve(spec: FeatureSpec, benchmarkLaunch: Boolean, extra: (String) -> Boolean?, stored: (String) -> Boolean?): Boolean =
        resolve(spec, benchmarkLaunch, extra, stored, advancedSettings = false)

    /** [resolve] with the Advanced toggle and the build type stated (the 4-argument form keeps a trailing `stored` lambda). */
    fun resolve(
        spec: FeatureSpec,
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean?,
        stored: (String) -> Boolean?,
        advancedSettings: Boolean = false,
        debugBuild: Boolean = BuildConfig.DEBUG,
    ): Boolean {
        fun fromExtra(): Boolean? {
            val values = spec.extraNames.map(extra)
            return if (values.all { it == null }) null else values.any { it == true }
        }
        val prefKey = spec.prefKey
        if (benchmarkLaunch) return fromExtra() ?: spec.benchmarkDefault
        if (spec.extraInAnyDebugLaunch && debugBuild) fromExtra()?.let { return it }
        return when {
            prefKey == null || (spec.advancedOnly && !advancedSettings) -> spec.defaultEnabled
            else -> stored(prefKey) ?: spec.defaultEnabled
        }
    }

    /** Every registered feature, parents before the sub-options that depend on them. */
    fun resolveAll(benchmarkLaunch: Boolean, extra: (String) -> Boolean?, stored: (String) -> Boolean?): FeatureSnapshot =
        resolveAll(benchmarkLaunch, extra, stored, advancedSettings = false)

    /** [resolveAll] with the Advanced toggle and the build type stated. */
    fun resolveAll(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean?,
        stored: (String) -> Boolean?,
        advancedSettings: Boolean = false,
        debugBuild: Boolean = BuildConfig.DEBUG,
    ): FeatureSnapshot {
        val registered = all()
        val values = LinkedHashMap<String, Boolean>()
        registered.forEach { spec ->
            val own = resolve(spec, benchmarkLaunch, extra, stored, advancedSettings, debugBuild)
            values[spec.id] = own && spec.dependsOn.all { values[it] == true }
        }
        return FeatureSnapshot(values)
    }

    /** What a launch told the last [refresh]: its benchmark flag, the extras it carried then and the Advanced toggle. */
    private data class Launch(
        val benchmark: Boolean = false,
        val extras: Map<String, Boolean?> = emptyMap(),
        val advancedSettings: Boolean = false,
        val debugBuild: Boolean = BuildConfig.DEBUG,
    )

    @Volatile private var lastLaunch = Launch()

    /** [resolveAll] and remember the result as [current]. Returns it. */
    fun refresh(benchmarkLaunch: Boolean, extra: (String) -> Boolean?, stored: (String) -> Boolean?): FeatureSnapshot =
        refresh(benchmarkLaunch, extra, stored, advancedSettings = false)

    /** [refresh] with the Advanced toggle and the build type stated. */
    fun refresh(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean?,
        stored: (String) -> Boolean?,
        advancedSettings: Boolean = false,
        debugBuild: Boolean = BuildConfig.DEBUG,
    ): FeatureSnapshot {
        // Extras are kept only in a DEBUG build: a benchmark launch reads them for every feature, any other DEBUG launch only
        // for the specs that let an extra decide there. A release build keeps none.
        val extras = if (benchmarkLaunch || debugBuild) all().flatMap { it.extraNames }.associateWith(extra) else emptyMap()
        lastLaunch = Launch(benchmarkLaunch, extras, advancedSettings, debugBuild)
        return resolveAll(benchmarkLaunch, { extras[it] }, stored, advancedSettings, debugBuild).also { current = it }
    }

    /**
     * Re-resolves after a stored choice changed, keeping the launch of the last [refresh] (switch audit 2026-10-27, A8: a
     * screen without the launch intent refreshed with no extras, which turned every benchmark extra off for the rest of a
     * DEBUG run).
     */
    fun refreshStoredChoices(stored: (String) -> Boolean?): FeatureSnapshot {
        val launch = lastLaunch
        return refresh(launch.benchmark, { launch.extras[it] }, stored, launch.advancedSettings, launch.debugBuild)
    }

    fun isEnabled(id: String): Boolean = current.isOn(id)
}

/** The stored "Manual settings" (Advanced) toggle; [FeatureSpec.advancedOnly] features read their choice only while it is on. */
internal const val PREF_ADVANCED_SETTINGS = "advanced_settings"

/**
 * Every feature spec, registered in one place and in dependency order (parents first). MainActivity calls it before each
 * catalog refresh and [MeshLiveApplication] at process start, so a receiver, service or provider that runs before any
 * screen reads the same snapshot a user launch would (switch audit B15).
 */
internal object AppFeatures {
    fun registerAll(updaterAvailable: Boolean = BuildConfig.UPDATER_AVAILABLE) {
        CallFeatureSpecs.registerAll()
        ConnectivityFeatures.registerAll()
        ViewerFeatures.registerAll()
        UpdateFeatures.registerAll(updaterAvailable)
        NamingFeatures.registerAll()
        listOf(
            MessagingV2Feature.spec,
            MessagingV2ChatFeature.spec,
            RelayedCallsFeature.spec,
            NeutralNameOption.spec,
            CallReachabilityFeature.spec,
            ReachableWhileBrowsingFeature.spec,
            CallResolutionLadderFeature.spec,
            AutoQualityFeature.spec,
            GpuScaleFeature.spec,
            VideoCodecFeatures.hevcSpec,
            VideoCodecFeatures.h264Spec,
        ).forEach(FeatureCatalog::register)
    }
}
