package com.example.bluetoothtest


/**
 * The one function that names a person on every screen (Design G, G3; nearby list, watch list, ring screen, in-call header,
 * call log, notifications, Contacts, Verify). First match wins, in this order:
 *
 * | Rank | State | Title | Mark |
 * | --- | --- | --- | --- |
 * | 1 KEY_CHANGED | a phone has a verified contact's prefix but another key (existing `MISMATCH`) | "Not Mum" | warning |
 * | 2 BLOCKED | the full fingerprint is on the block list | the label, else the handle | blocked |
 * | 3 VERIFIED | the session key equals a verified fingerprint | YOUR label | shield (outline when only checked by code) |
 * | 4 RECOGNISED | a presence lease matched an advert; no connection | YOUR label, "seen nearby, unconfirmed" | none |
 * | 5 SELF_CLAIMED | a verified `BNL1` kind 1 label from an unverified peer | their name, "calls itself" | tilde |
 * | 6 GENERATED | rotating advert or stranger throwaway | "Blue Otter" | none |
 * | 7 HANDLE | fixed-prefix advert, Classic, Visible-by-handle window (or generated names switched off) | the full handle | none |
 *
 * A screen draws [DisplayIdentity.claimedName] in its own styled element and the [mark] beside it; the mark is never
 * concatenated into the title text (review F13). Words always accompany a glyph (F17): [speech] is what a screen reader says.
 * Lock-screen notifications use no name (existing decision Q16) and never call this function.
 *
 * The contact's own current name is never the title of rank 3 or 4: it lives in the contact's detail and in one note when it
 * changes ([ContactClaims]). A name claim that imitates a contact is a [warning], not a rank (the claimant is a stranger).
 *
 * Pure: no Android types. English text lives in [DisplayIdentityText]; a screen may substitute a localised string for each
 * [DisplayWarning] or [DisplayRank] without changing the decision.
 */
internal enum class DisplayRank(val level: Int) {
    KEY_CHANGED(1),
    BLOCKED(2),
    VERIFIED(3),
    RECOGNISED(4),
    SELF_CLAIMED(5),
    GENERATED(6),
    HANDLE(7),
}

internal enum class DisplayMark { NONE, WARNING, BLOCKED, SHIELD, SHIELD_OUTLINE, TILDE }

/** How a verified contact was verified: scanned (shield) or only confirmed by the safety code ("Checked by code", outline). */
internal enum class VerificationBasis { SCANNED, CHECKED_BY_CODE }

internal sealed class DisplayWarning {
    /** Hard key change: refused by default. */
    data class KeyChanged(val contactLabel: String) : DisplayWarning()

    /** A stranger claims exactly or nearly a contact's name, after a reveal with a different key (or never revealing). */
    data class NotYourContact(val contactLabel: String) : DisplayWarning()

    /** The same claim before the reveal: a real contact whose lease expired arrives as a stranger, so the text is neutral. */
    data class SaysTheyAre(val contactLabel: String) : DisplayWarning()

    /** Neutral notes (never alarms): the claim equals your own name, a generated name, or [count] visible rows. */
    data object SameAsYourName : DisplayWarning()
    data object LooksLikeGeneratedName : DisplayWarning()
    data class SeveralCallThemselves(val count: Int, val name: String) : DisplayWarning()

    val isAlarm: Boolean get() = this is KeyChanged || this is NotYourContact
}

internal data class DisplayIdentity(
    val rank: DisplayRank,
    /** Rank 5: the claimed name itself (draw it in its own element, with [mark] and the words in [titleNote]). */
    val title: String,
    /** "calls itself" for rank 5, "seen nearby, unconfirmed" for rank 4, else null. Always shown with the title. */
    val titleNote: String?,
    val subtitle: String?,
    val mark: DisplayMark,
    val warning: DisplayWarning?,
    /** What a screen reader says for the whole row. */
    val speech: String,
) {
    /** The claimed name, only for rank 5, for screens that draw it in its own element. */
    val claimedName: String? get() = title.takeIf { rank == DisplayRank.SELF_CLAIMED }

    /** One plain string with no marks, for logs-free contexts that need a single line (never a lock screen). */
    val plain: String get() = if (titleNote == null) title else "$title, $titleNote"
}

internal data class DisplayInputs(
    /** The full 10-symbol handle. Always available. */
    val handle: String,
    /** The row's generated name (already disambiguated by [GeneratedNameRows]); null: not derivable (fixed-prefix, Classic). */
    val generatedName: String? = null,
    val generatedNamesEnabled: Boolean = true,
    /** The label of the verified contact whose prefix this phone carries with another key; non-null means rank 1. */
    val keyChangedContactLabel: String? = null,
    val blocked: Boolean = false,
    /** Your label for the contact whose verified fingerprint equals this session's key (rank 3). */
    val verifiedLabel: String? = null,
    val verification: VerificationBasis = VerificationBasis.SCANNED,
    /** Your label when only a presence lease matched the advert (rank 4). */
    val recognisedLabel: String? = null,
    /** A verified `BNL1` kind 1 name from an unverified peer (rank 5). */
    val claimedName: String? = null,
    /** What [ImitationCheck] found for [claimedName], if anything. */
    val imitation: DisplayWarning? = null,
    /** Label shown for a blocked person, if you named them before blocking. */
    val blockedLabel: String? = null,
)

/**
 * The hook for list rows and call screens: a [PeerLabel] (handle, your contact label or the peer's signed claim) becomes the
 * one [DisplayIdentity]. Rows pass what only they know: the row's [generatedName] (from [GeneratedNameRows]), whether a
 * presence lease [recognised] it, a hard key change, a block, and the [imitation] finding. Nothing else builds a name.
 */
internal fun PeerLabel.display(
    generatedName: String? = null,
    generatedNamesEnabled: Boolean = NamingFeatures.generatedNamesOn(),
    recognised: Boolean = false,
    blocked: Boolean = false,
    keyChangedContactLabel: String? = null,
    verification: VerificationBasis = VerificationBasis.SCANNED,
    imitation: DisplayWarning? = null,
): DisplayIdentity = DisplayIdentityResolver.resolve(
    DisplayInputs(
        handle = handle,
        generatedName = generatedName,
        generatedNamesEnabled = generatedNamesEnabled,
        keyChangedContactLabel = keyChangedContactLabel,
        blocked = blocked,
        verifiedLabel = name.takeIf { nameKind == PeerLabel.NameKind.CONTACT && !recognised },
        verification = verification,
        recognisedLabel = name.takeIf { nameKind == PeerLabel.NameKind.CONTACT && recognised },
        claimedName = name.takeIf { nameKind == PeerLabel.NameKind.SELF_NAMED },
        imitation = imitation,
        blockedLabel = name.takeIf { nameKind == PeerLabel.NameKind.CONTACT },
    ),
)

internal object DisplayIdentityText {
    const val CALLS_ITSELF = "calls itself"
    const val NOT_VERIFIED = "not verified"
    const val SEEN_NEARBY = "seen nearby, unconfirmed"
    const val NEARBY_PHONE = "Nearby phone"
    const val BLOCKED = "Blocked"
    const val CHECKED_BY_CODE = "Checked by code"

    fun warningText(warning: DisplayWarning): String = when (warning) {
        is DisplayWarning.KeyChanged ->
            "This phone claims to be ${warning.contactLabel} but its key is different from the one you checked. " +
                "Someone may be pretending to be ${warning.contactLabel}."
        is DisplayWarning.NotYourContact ->
            "Not your contact ${warning.contactLabel}. If ${warning.contactLabel} has a new phone, scan their code again in person."
        is DisplayWarning.SaysTheyAre ->
            "Says they are ${warning.contactLabel}. The app will check when you answer."
        DisplayWarning.SameAsYourName -> "This name is the same as yours."
        DisplayWarning.LooksLikeGeneratedName -> "This name looks like a generated name."
        is DisplayWarning.SeveralCallThemselves -> "${warning.count} people nearby call themselves ${warning.name}"
    }
}

internal object DisplayIdentityResolver {
    fun resolve(input: DisplayInputs): DisplayIdentity {
        val fallbackName = input.generatedName.takeIf { input.generatedNamesEnabled } ?: input.handle

        input.keyChangedContactLabel?.let { label ->
            return DisplayIdentity(
                rank = DisplayRank.KEY_CHANGED,
                title = "Not $label",
                titleNote = null,
                subtitle = input.handle,
                mark = DisplayMark.WARNING,
                warning = DisplayWarning.KeyChanged(label),
                speech = "Warning. Not $label. This phone claims to be $label but has a different key.",
            )
        }
        if (input.blocked) {
            val title = input.blockedLabel?.takeIf { it.isNotBlank() } ?: input.verifiedLabel?.takeIf { it.isNotBlank() } ?: input.handle
            return DisplayIdentity(
                rank = DisplayRank.BLOCKED,
                title = title,
                titleNote = null,
                subtitle = DisplayIdentityText.BLOCKED,
                mark = DisplayMark.BLOCKED,
                warning = null,
                speech = "$title, blocked",
            )
        }
        input.verifiedLabel?.let { label ->
            val byCode = input.verification == VerificationBasis.CHECKED_BY_CODE
            return DisplayIdentity(
                rank = DisplayRank.VERIFIED,
                title = label,
                titleNote = null,
                subtitle = if (byCode) DisplayIdentityText.CHECKED_BY_CODE else null,
                mark = if (byCode) DisplayMark.SHIELD_OUTLINE else DisplayMark.SHIELD,
                warning = null,
                speech = if (byCode) "$label, checked by code, not scanned in person" else "$label, verified in person",
            )
        }
        input.recognisedLabel?.let { label ->
            return DisplayIdentity(
                rank = DisplayRank.RECOGNISED,
                title = label,
                titleNote = DisplayIdentityText.SEEN_NEARBY,
                subtitle = null,
                mark = DisplayMark.NONE,
                warning = null,
                speech = "$label, ${DisplayIdentityText.SEEN_NEARBY}",
            )
        }
        input.claimedName?.takeIf { it.isNotBlank() }?.let { claimed ->
            return DisplayIdentity(
                rank = DisplayRank.SELF_CLAIMED,
                title = claimed,
                titleNote = DisplayIdentityText.CALLS_ITSELF,
                subtitle = "$fallbackName - ${DisplayIdentityText.NOT_VERIFIED}",
                mark = DisplayMark.TILDE,
                warning = input.imitation,
                speech = "${DisplayIdentityText.CALLS_ITSELF} $claimed, ${DisplayIdentityText.NOT_VERIFIED}",
            )
        }
        if (input.generatedNamesEnabled && input.generatedName != null) {
            return DisplayIdentity(
                rank = DisplayRank.GENERATED,
                title = input.generatedName,
                titleNote = null,
                subtitle = DisplayIdentityText.NEARBY_PHONE,
                mark = DisplayMark.NONE,
                warning = null,
                speech = "${input.generatedName}, ${DisplayIdentityText.NEARBY_PHONE}, ${DisplayIdentityText.NOT_VERIFIED}",
            )
        }
        return DisplayIdentity(
            rank = DisplayRank.HANDLE,
            title = input.handle,
            titleNote = null,
            subtitle = null,
            mark = DisplayMark.NONE,
            warning = null,
            speech = input.handle,
        )
    }
}

/**
 * Look-alike handling for a self-claimed name (Design G, "Confusables and imitation"; review F12, F14). An alarm that fires
 * constantly teaches people to ignore it, so the finding is chosen by kind:
 *
 * - the same text or the same UTS #39 skeleton as a CONTACT label or that contact's last claimed name: an alarm
 *   ([DisplayWarning.NotYourContact]) once the reveal gave a different key or the stranger never reveals; before the reveal
 *   the neutral [DisplayWarning.SaysTheyAre], because a real contact whose lease expired arrives as a stranger;
 * - the same as your own name, a generated name, or another visible row: a neutral note, never a warning.
 *
 * Comparison is case- and skeleton-insensitive. Nothing is blocked or rewritten and nothing is stored.
 */
internal object ImitationCheck {
    data class ContactNames(val label: String, val lastClaimedName: String? = null)

    fun evaluate(
        claimed: String,
        contacts: Collection<ContactNames>,
        ownName: String?,
        generatedNames: Collection<String>,
        otherVisibleClaims: Collection<String>,
        afterReveal: Boolean,
        table: ConfusableTable = Confusables.table,
    ): DisplayWarning? {
        if (claimed.isBlank()) return null
        contacts.firstOrNull { c ->
            NameTextRules.sameReading(claimed, c.label, table) ||
                (c.lastClaimedName != null && NameTextRules.sameReading(claimed, c.lastClaimedName, table))
        }?.let { c ->
            return if (afterReveal) DisplayWarning.NotYourContact(c.label) else DisplayWarning.SaysTheyAre(c.label)
        }
        if (ownName != null && NameTextRules.sameReading(claimed, ownName, table)) return DisplayWarning.SameAsYourName
        if (generatedNames.any { NameTextRules.sameReading(claimed, it, table) } ||
            NameTextRules.looksLikeGeneratedName(claimed, table = table)
        ) {
            return DisplayWarning.LooksLikeGeneratedName
        }
        val same = otherVisibleClaims.count { NameTextRules.sameReading(claimed, it, table) }
        // [otherVisibleClaims] holds the other rows; this row makes one more.
        if (same >= 1) return DisplayWarning.SeveralCallThemselves(same + 1, claimed)
        return null
    }
}

/**
 * Contact labels are forced unique (Meshenger; Design G): "Save contact as" and rename refuse a label that equals, or has
 * the same UTS #39 skeleton as, another contact's label, compared case-insensitively, so two contacts can never look the
 * same in a list. [excluding] is the label being renamed (its own current label is not a conflict).
 */
internal object ContactLabelUniqueness {
    /** The existing label that [candidate] collides with, or null. */
    fun conflict(
        candidate: String,
        existing: Collection<String>,
        excluding: String? = null,
        table: ConfusableTable = Confusables.table,
    ): String? {
        if (candidate.isBlank()) return null
        return existing.firstOrNull { other -> other != excluding && NameTextRules.sameReading(candidate, other, table) }
    }
}
