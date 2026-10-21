package com.example.bluetoothtest

/**
 * A call that arrives while the user is dialling (F14). Phone run ql-b57d0eaf-20261019 m31-xiaomi (Xiaomi clock 21:32): the
 * Xiaomi's user tapped the M31 in Contacts (`call_invite: refreshing_address`); 5 s later the A15s's call arrived, rang,
 * its call screen stopped the scan, and the user's own dial expired silently (`failed | reason=peer_not_seen`).
 *
 * Rule (no wire change: DECLINE with the existing reason BUSY = 2):
 * - The user chose to call someone, so a call from anyone else while that dial is still looking for its person is
 *   declined as busy at once (no ring), and the dial goes on. The caller is told "busy".
 * - A call from the very person being dialled is that conversation: it rings as usual and the user's own dial stops
 *   ("<name> is calling you"). This is the deterministic answer to two phones dialling each other: whichever Invite
 *   arrives first while the other is still looking becomes the call, instead of both giving up.
 * - With no dial pending nothing changes.
 *
 * The caller is matched by its real 12-hex node prefix (after BMH3, a contact presents its real key; a stranger's
 * throwaway never matches a dial target, so a stranger calling while the user dials is declined as busy).
 */
internal data class OwnDial(
    /** The advert key the dial waits for (a rotating ID or a fixed prefix, 12 hex). */
    val advertKey: String,
    /** The person's real 12-hex prefix when known (a contact, or a recognised rotating ID); null otherwise. */
    val targetPrefix: String?,
    /** What the user tapped, for the notice. */
    val name: String,
    /** A fixed-prefix (version 2) advert: its key is the person's prefix. */
    val fixedPrefix: Boolean = false,
)

internal object DialCollision {
    enum class Action { RING, DECLINE_BUSY }

    /** The user dial pending right now, read on the call's reader thread; set by MainActivity. Null: none. */
    @Volatile var ownDial: () -> OwnDial? = { null }

    fun decide(dial: OwnDial?, callerPrefix: String?): Action {
        if (dial == null) return Action.RING
        val caller = callerPrefix?.lowercase() ?: return Action.DECLINE_BUSY
        val isTarget = dial.targetPrefix?.lowercase() == caller || (dial.fixedPrefix && dial.advertKey.lowercase() == caller)
        return if (isTarget) Action.RING else Action.DECLINE_BUSY
    }

    /** `call_collision: action= | dialling= | callerKnown=` (no identifiers). */
    fun line(action: Action, callerKnown: Boolean): String =
        "call_collision: action=${if (action == Action.RING) "their_call_rings" else "declined_busy"} | dialling=true | " +
            "callerKnown=$callerKnown"
}
