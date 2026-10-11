package com.example.bluetoothtest

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * One generated Settings > Features card: a switch and an explanation for every [FeatureSpec] of a
 * group, bound to the user's explicit choice in [prefs] (see [FeatureCatalog]). The card follows the
 * catalog rules, so a feature added to the catalog appears here without layout work:
 *
 * - The switch shows the effective value: the user's explicit choice, else the shipped default.
 * - Touching it stores an explicit choice, which then wins over later changes of the default.
 * - A benchmark launch shows the launch options and cannot be changed (its extras are the only switch).
 * - A feature with [FeatureSpec.dependsOn] is greyed out while its parent is off.
 */
internal class FeatureSettingsBlock(
    private val container: ViewGroup,
    private val specs: List<FeatureSpec>,
    private val prefs: SharedPreferences,
    private val host: Host,
) {
    interface Host {
        /** True in a DEBUG benchmark launch: the switches show the extras and cannot be changed. */
        val benchmarkLaunch: Boolean

        /** A launch extra, read only in a benchmark launch. */
        fun extra(name: String): Boolean

        /**
         * Called before a feature is switched ON. Return true when the host takes over (shows an
         * explanation) and will call [proceed] once the user agrees; the switch stays off until then.
         * Return false to switch on at once. Switching off is never intercepted.
         */
        fun confirmEnable(spec: FeatureSpec, proceed: () -> Unit): Boolean = false

        /** A choice was stored: apply it to the running app. */
        fun onChanged(spec: FeatureSpec, enabled: Boolean)
    }

    private class Row(val spec: FeatureSpec, val switch: MaterialSwitch)

    private val rows = ArrayList<Row>()
    private var binding = false

    /** Builds the rows once and shows the current values. */
    fun bind() {
        if (rows.isEmpty()) {
            val inflater = LayoutInflater.from(container.context)
            specs.forEachIndexed { index, spec ->
                val view = inflater.inflate(R.layout.item_feature_switch, container, false)
                val switch = view.findViewById<MaterialSwitch>(R.id.featureRowSwitch)
                switch.setText(spec.titleRes)
                if (spec.iconRes != 0) {
                    // Same look as the calls card: icon, 16 dp gap, secondary tint.
                    switch.setCompoundDrawablesRelativeWithIntrinsicBounds(spec.iconRes, 0, 0, 0)
                    switch.compoundDrawablePadding = (16 * switch.resources.displayMetrics.density).toInt()
                    TextViewCompat.setCompoundDrawableTintList(
                        switch,
                        ContextCompat.getColorStateList(switch.context, R.color.text_secondary),
                    )
                }
                view.findViewById<TextView>(R.id.featureRowDescription).setText(spec.descriptionRes)
                if (index == 0) {
                    (switch.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
                }
                switch.setOnCheckedChangeListener { _, checked -> if (!binding) onUserToggle(spec, switch, checked) }
                container.addView(view)
                rows += Row(spec, switch)
            }
        }
        refresh()
    }

    /** Re-reads the values (after a change from elsewhere, or when the screen opens). */
    fun refresh() {
        val snapshot = FeatureCatalog.resolveAll(host.benchmarkLaunch, host::extra, ::stored)
        binding = true
        rows.forEach { row ->
            val parentOn = row.spec.dependsOn?.let(snapshot::isOn) ?: true
            row.switch.isChecked = snapshot.isOn(row.spec)
            row.switch.isEnabled = !host.benchmarkLaunch && parentOn
        }
        binding = false
    }

    private fun onUserToggle(spec: FeatureSpec, switch: MaterialSwitch, checked: Boolean) {
        if (host.benchmarkLaunch) {
            refresh()
            return
        }
        if (checked) {
            // Shown off until the explanation is agreed to; an agreement re-checks it through commit().
            binding = true
            switch.isChecked = false
            binding = false
            if (host.confirmEnable(spec) { commit(spec, true) }) return
        }
        commit(spec, checked)
    }

    private fun commit(spec: FeatureSpec, enabled: Boolean) {
        prefs.edit().putBoolean(spec.prefKey, enabled).apply()
        refresh()
        host.onChanged(spec, enabled)
    }

    private fun stored(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
}
