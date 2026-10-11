package com.example.bluetoothtest

/**
 * The shared catalog of promoted features (2026-10-11, user request to bring the experimental
 * features into the normal app). Pure Kotlin: no Android types, so every rule is JVM-tested.
 *
 * One [FeatureSpec] per feature carries the stable id, the preference key of the user's explicit
 * choice, the ONE default constant, the DEBUG benchmark extra and the Settings texts. The rules are
 * the same for every feature:
 *
 * - A benchmark launch (a DEBUG build started with any `test_` extra) reads only the feature's own
 *   extra, default off. A stored choice or a flipped default never reaches a measured case, so case
 *   IDs and results stay valid.
 * - Every other launch uses the user's explicit choice when there is one, otherwise the spec's
 *   default. Neither the "Manual settings" toggle nor [BuildConfig.DEBUG] takes part. Flipping a
 *   feature on after its phone test is one line (the default constant), and it reaches everyone who
 *   never touched the switch; an explicit choice sticks.
 * - A feature with [FeatureSpec.dependsOn] is on only while the feature it names is on.
 *
 * Adding a feature (for example the call and message switches of `CallFeatureSettings`): declare a
 * spec, [register] it once at start-up (an identical re-registration is a no-op), resolve it with
 * [resolve] or [resolveAll], and either show it in the generated Settings block
 * (`FeatureSettingsBlock`, one group per card) or bind your own layout to [FeatureSpec.prefKey].
 * [current] always holds the snapshot of the last [refresh].
 */
data class FeatureSpec(
    /** Stable snake_case id; logged and used by [FeatureSnapshot]. Never reused for another feature. */
    val id: String,
    /** SharedPreferences key of the user's explicit choice. Absent means "follow [defaultEnabled]". */
    val prefKey: String,
    /** The one default for users who never touched the switch. Flip it after the feature's phone test. */
    val defaultEnabled: Boolean,
    /** DEBUG launch extra of benchmark runs (default off). Null: benchmark launches always read off. */
    val benchmarkExtra: String?,
    /** `R.string` id of the switch title (0 for none: the feature has no generated row). */
    val titleRes: Int = 0,
    /** `R.string` id of the explanation under the switch. */
    val descriptionRes: Int = 0,
    /** `R.drawable` id of the icon before the title (0 for none). */
    val iconRes: Int = 0,
    /** The Settings card this feature belongs to ("connectivity", "calls", ...). */
    val group: String = GROUP_GENERAL,
    /** Id of the feature that must also be on (a sub-option such as automatic sync under Messages). */
    val dependsOn: String? = null,
    /** The phone test that must pass before [defaultEnabled] may become true (documentation only). */
    val phoneTest: String? = null,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Feature id must be snake_case: $id" }
        require(prefKey.isNotBlank()) { "Feature $id needs a preference key" }
        require(dependsOn != id) { "Feature $id cannot depend on itself" }
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
        require(specs.values.none { it.prefKey == spec.prefKey }) {
            "Preference key ${spec.prefKey} is already used by ${specs.values.first { it.prefKey == spec.prefKey }.id}"
        }
        spec.dependsOn?.let { parent ->
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
    }

    /**
     * One feature, ignoring [FeatureSpec.dependsOn] (the caller already knows the parent). A benchmark
     * launch reads only [FeatureSpec.benchmarkExtra]; any other launch reads [stored] (null: the user
     * never decided) and falls back to [FeatureSpec.defaultEnabled].
     */
    fun resolve(
        spec: FeatureSpec,
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): Boolean = if (benchmarkLaunch) {
        spec.benchmarkExtra?.let(extra) ?: false
    } else {
        stored(spec.prefKey) ?: spec.defaultEnabled
    }

    /** Every registered feature, parents before the sub-options that depend on them. */
    fun resolveAll(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): FeatureSnapshot {
        val registered = all()
        val values = LinkedHashMap<String, Boolean>()
        registered.forEach { spec ->
            val own = resolve(spec, benchmarkLaunch, extra, stored)
            val parent = spec.dependsOn
            values[spec.id] = own && (parent == null || values[parent] == true)
        }
        return FeatureSnapshot(values)
    }

    /** [resolveAll] and remember the result as [current]. Returns it. */
    fun refresh(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): FeatureSnapshot = resolveAll(benchmarkLaunch, extra, stored).also { current = it }

    fun isEnabled(id: String): Boolean = current.isOn(id)
}
