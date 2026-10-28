package com.example.bluetoothtest

import android.content.Context
import android.content.SharedPreferences

/**
 * "Hide phone name" on by default (design decision 2026-10-27, DECISIONS "Hide phone name on by default"): the
 * user's answer to the question "Hide your phone's name" (an onboarding page for a new install, a one-time
 * prompt for an update from a build where the option was off by default) and how the Settings switch shows it.
 *
 * The default ([NeutralNameOption.spec] `defaultEnabled`) is on, but the adapter is never renamed before the user
 * was asked: renaming changes the name every Bluetooth device sees (headphones, cars), so the question is the
 * rename consent. A stored choice, either way, always wins; a benchmark launch ignores both (its own extra).
 * Pure rules over [SharedPreferences]; the Activities only call them.
 */
object NeutralNameChoice {
    /** The app's settings file (MainActivity.APP_PREFERENCES), where [NeutralNameOption.PREF] lives. */
    fun settings(context: Context): SharedPreferences =
        context.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)

    /** True while a normal launch still has to ask: nothing stored yet. A benchmark launch never asks. */
    fun needsAsking(stored: Boolean?, benchmarkLaunch: Boolean): Boolean = !benchmarkLaunch && stored == null

    fun needsAsking(settings: SharedPreferences, benchmarkLaunch: Boolean): Boolean =
        needsAsking(storedChoice(settings), benchmarkLaunch)

    /** The user's explicit choice, null when they were never asked and never touched the switch. */
    fun storedChoice(settings: SharedPreferences): Boolean? =
        if (settings.contains(NeutralNameOption.PREF)) settings.getBoolean(NeutralNameOption.PREF, false) else null

    /** Records an answer ("Hide name" = true, "Keep my name" = false); from then on it wins over the default. */
    fun record(settings: SharedPreferences, hide: Boolean) {
        settings.edit().putBoolean(NeutralNameOption.PREF, hide).apply()
    }

    /**
     * Whether the adapter should carry the neutral name now: the resolved option ([FeatureCatalog.resolve]), except
     * that a normal launch without a stored choice keeps the phone's own name until the user has answered.
     */
    fun renameOn(resolved: Boolean, stored: Boolean?, benchmarkLaunch: Boolean): Boolean =
        resolved && !needsAsking(stored, benchmarkLaunch)

    /** What the Settings switch shows: on only while the name can actually be hidden, otherwise off with a reason. */
    enum class Shown(val checked: Boolean) {
        ON(true),
        OFF(false),

        /** The option is on but the rename permission (Nearby devices) is missing. */
        OFF_NO_PERMISSION(false),

        /** The option is on but Android refused the rename. */
        OFF_REFUSED(false),
    }

    fun shown(optionOn: Boolean, canRename: Boolean, refused: Boolean): Shown = when {
        !optionOn -> Shown.OFF
        !canRename -> Shown.OFF_NO_PERMISSION
        refused -> Shown.OFF_REFUSED
        else -> Shown.ON
    }
}
