package com.example.bluetoothtest

import android.content.Context

/**
 * "You appear as Gold Fox · changes every 15 min" (user request 2026-10-21): people did not know the made-up name others
 * see for them, which was only in the status sheet ("Nearby you appear as"). Shown on Calls > Nearby, under Settings >
 * Your name when no name is set, on the incoming call screen and on Add contact, so two people meeting can match up.
 *
 * The name is the one the status sheet already shows: the generated name of this phone's current role 0 rotating ID, the
 * value a scanner computes from the same public bytes, so it changes at every rotating-ID boundary. With rotating IDs
 * off there is no generated name for this phone (generated names come only from rotating IDs or throwaway keys, by
 * design): people who have not added you see "Someone nearby", which does not change. With generated names off, rotating
 * rows read "Nearby phone".
 */
internal object OwnNearbyName {
    data class Shown(val name: String, val rotates: Boolean)

    /** What a nearby phone that has not added you shows for you now, or null when this phone shows nothing. */
    fun shown(
        rotatingOn: Boolean,
        generatedNamesOn: Boolean,
        ownRdi: ByteArray?,
        derive: (ByteArray) -> String?,
        nearbyPhone: String,
        someoneNearby: String,
    ): Shown? = when {
        !rotatingOn -> Shown(someoneNearby, rotates = false)
        !generatedNamesOn -> Shown(nearbyPhone, rotates = false)
        ownRdi == null -> null
        else -> derive(ownRdi)?.let { Shown(it, rotates = true) }
    }

    /**
     * Review 2026-10-27 (finding 16): the role 0 ID this phone actually advertises (the newest entry the opening pins
     * hold, [OpeningPins]), not one computed from the epoch clock: those differ around a boundary and while no call offer
     * is on air, so the screen could name an ID nobody nearby sees. Null when nothing was on air in the last minute.
     */
    internal fun onAir(pinned: List<OpeningEntry>): ByteArray? = pinned.firstOrNull()?.rdi

    /** The current value for this phone (null when it is not call-ready or no ID is on air yet). */
    fun current(context: Context): Shown? {
        val prefs = context.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_REACHABLE_FOR_CALLS, true)) return null
        return shown(
            rotatingOn = RotatingDiscoveryFeature.enabled,
            generatedNamesOn = NamingFeatures.generatedNamesOn(),
            ownRdi = runCatching { onAir(RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_CALL_OFFER)) }.getOrNull(),
            derive = { rdi -> StrangerNames.derive(StrangerNames.Input.rdi(rdi))?.text },
            nearbyPhone = context.getString(R.string.people_rotating_phone),
            someoneNearby = context.getString(R.string.people_someone_nearby),
        )
    }

    /** "You appear as X · changes every 15 min", or without the change note when the name is stable. */
    fun line(context: Context, shown: Shown): String =
        context.getString(if (shown.rotates) R.string.appear_as_rotating else R.string.appear_as, shown.name)

    fun currentLine(context: Context): String? = current(context)?.let { line(context, it) }

    /** Settings > Your name when no name is set. */
    fun nameNotSet(context: Context): String =
        context.getString(if (RotatingDiscoveryFeature.enabled) R.string.settings_name_not_set_rotating else R.string.settings_name_not_set)
}
