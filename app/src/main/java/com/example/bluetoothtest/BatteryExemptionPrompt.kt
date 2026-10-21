package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Where the offer is made from. The wording and the state machine are the same for all three. */
internal enum class BatteryOfferTrigger {
    /** The onboarding step after the permissions. */
    ONBOARDING,

    /** The user opened the reliability card in Settings and asked for it. */
    SETTINGS,

    /** An always-on role (relaying, background availability) was just turned on. */
    ALWAYS_ON_ROLE,
}

/** What the app remembers about earlier offers; stored by [BatteryExemptionPrompt]. */
internal data class BatteryOfferRecord(val offers: Int, val lastOfferMs: Long)

/**
 * Re-offer rules of the battery-optimisation exemption prompt (design decision Q38; device-family backlog B-02),
 * a pure state machine:
 * - already exempt: never offered;
 * - onboarding: once (the step is skippable);
 * - Settings: always (the user asked);
 * - turning an always-on role on: offered again when it was never offered or the last offer is at least a day old,
 *   so toggling a role repeatedly does not nag.
 */
internal object BatteryExemptionPolicy {
    const val ROLE_REOFFER_MS = 24L * 60 * 60 * 1000

    fun shouldOffer(trigger: BatteryOfferTrigger, exempt: Boolean, record: BatteryOfferRecord, nowMs: Long): Boolean = when {
        exempt -> false
        else -> when (trigger) {
            BatteryOfferTrigger.ONBOARDING -> record.offers == 0
            BatteryOfferTrigger.SETTINGS -> true
            BatteryOfferTrigger.ALWAYS_ON_ROLE -> record.offers == 0 || nowMs - record.lastOfferMs >= ROLE_REOFFER_MS
        }
    }

    fun afterOffer(record: BatteryOfferRecord, nowMs: Long) = BatteryOfferRecord(record.offers + 1, nowMs)
}

/** Data-only description of the intent to launch (an [Intent] cannot be built in a JVM test). */
internal data class BatteryIntentSpec(val action: String, val dataUri: String?)

/**
 * The system's one-tap exemption prompt (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) and the list-screen fallback.
 * Needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission in the manifest (sideload builds; check the review risk
 * before any Play build ships it). The prompt shows "Let app always run in the background?" and returns without a
 * result we need: the state is re-read from [PowerManager.isIgnoringBatteryOptimizations].
 */
internal object BatteryExemptionPrompt {
    private const val KEY_OFFERS = "battery_exemption_offers"
    private const val KEY_LAST_OFFER = "battery_exemption_last_offer_ms"

    fun requestSpec(packageName: String): BatteryIntentSpec = FlavourBattery.requestSpec(packageName)

    fun directSpec(packageName: String): BatteryIntentSpec =
        BatteryIntentSpec(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName")

    val listSpec: BatteryIntentSpec = BatteryIntentSpec(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, null)

    /** The prompt when some activity resolves it, else the battery-optimisation list screen (some makers hide the prompt). */
    fun intent(context: Context): Intent {
        val request = toIntent(requestSpec(context.packageName))
        val resolvable = runCatching { request.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        return if (resolvable) request else toIntent(listSpec)
    }

    private fun toIntent(spec: BatteryIntentSpec): Intent =
        Intent(spec.action).apply { spec.dataUri?.let { data = Uri.parse(it) } }

    fun isExempt(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }
            .getOrDefault(false)

    fun record(context: Context): BatteryOfferRecord {
        val prefs = prefs(context)
        return BatteryOfferRecord(prefs.getInt(KEY_OFFERS, 0), prefs.getLong(KEY_LAST_OFFER, 0L))
    }

    fun shouldOffer(context: Context, trigger: BatteryOfferTrigger, nowMs: Long = System.currentTimeMillis()): Boolean =
        BatteryExemptionPolicy.shouldOffer(trigger, isExempt(context), record(context), nowMs)

    /** Remember that the offer was shown (the user's answer is read back from the system, not stored). */
    fun markOffered(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val next = BatteryExemptionPolicy.afterOffer(record(context), nowMs)
        prefs(context).edit().putInt(KEY_OFFERS, next.offers).putLong(KEY_LAST_OFFER, next.lastOfferMs).apply()
    }

    /** Opens the system prompt directly (the onboarding page and the Settings card button call this). */
    fun launch(activity: Activity): Boolean {
        markOffered(activity)
        FlavourBattery.explain(activity)
        if (runCatching { activity.startActivity(intent(activity)) }.isSuccess) return true
        // Some makers remove the prompt activity: the list screen is the documented fallback.
        return runCatching { activity.startActivity(toIntent(listSpec)) }.isSuccess
    }

    /**
     * Root hook for "an always-on role was turned on" (and any other place that wants the explanation first): shows the
     * reason and the cost in a dialog, then the system prompt on "Continue". Returns false (nothing shown) when the policy
     * says not to offer. Skippable.
     */
    fun offerWithExplanation(activity: Activity, trigger: BatteryOfferTrigger): Boolean {
        if (!shouldOffer(activity, trigger)) return false
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.battery_exemption_dialog_title)
            .setMessage(FlavourBattery.guidanceText(R.string.battery_exemption_dialog_text))
            .setPositiveButton(R.string.battery_exemption_dialog_allow) { _, _ -> launch(activity) }
            .setNegativeButton(R.string.battery_exemption_dialog_skip) { _, _ -> markOffered(activity) }
            .show()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(OnboardingActivity.PREFERENCES, Context.MODE_PRIVATE)
}
