package com.example.bluetoothtest

/**
 * A user dial keeps its person across a rotating-ID epoch boundary (default on; DEBUG control arm
 * `test_dial_follows_rotation=false`, which matches only the exact rotating key, as before).
 *
 * 2026-10-20 edge-case suite (asus-s22-fb0e step 3): the ASUS dialled the S22's rotating ID 70e1a0000001 1.2 s after the
 * S22's epoch boundary. The S22 now announced 70e2b0000002 from the same advertising address (`rotid_address_reuse ...
 * previousNode=70e1a0000001`), but the dial waited for the old key only and failed after 10 s (`refresh_timeout`). The
 * DEBUG dial already followed the person (DebugDialWait.matchesPerson); the user dial did not.
 *
 * A sighting continues the pending dial when it carries the same key, or (switch on) when its rotating ID is recognised
 * as the same contact, or when it comes from the same advertising address (the stack kept the address across the
 * epoch). Ending the dial still needs the full handshake: the key check of the call decides who answers.
 */
internal object DialPersonMatch {
    enum class By { KEY, CONTACT, ADDRESS }

    @Volatile var enabled: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** What the dial remembers of the row the user tapped. */
    data class Target(val key: String, val contactPrefix: String?, val address: String?)

    fun matches(
        target: Target?,
        sightingKey: String,
        sightingContactPrefix: String?,
        sightingAddress: String?,
        on: Boolean = enabled,
    ): By? {
        if (target == null) return null
        if (sightingKey == target.key) return By.KEY
        if (!on) return null
        if (target.contactPrefix != null && sightingContactPrefix != null &&
            target.contactPrefix.equals(sightingContactPrefix, ignoreCase = true)
        ) {
            return By.CONTACT
        }
        if (target.address != null && sightingAddress != null && target.address.equals(sightingAddress, ignoreCase = true)) {
            return By.ADDRESS
        }
        return null
    }
}
