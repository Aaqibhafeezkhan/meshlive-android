package com.example.bluetoothtest

/**
 * When a name or subject is sent in a call, and to whom (Design G, G1; PROTOCOL "Naming v2 / Label timing and audience").
 * No new bytes: `BNL1`, the `LABELS` container, bit 5 and the receiver checks are unchanged. What changes is the moment a
 * sender queues them, so this file is the single decision and the call code only carries it out. Pure Kotlin.
 *
 * - Audience ("Who sees my name"): [NamesAudience.EVERYONE] (default), [NamesAudience.VERIFIED_ONLY], [NamesAudience.NOBODY].
 *   It decides whether the kind 1 name is sent at all; a subject is per call and is not governed by it.
 * - Caller, stranger session (the callee presented a throwaway key): with "Show my name before they answer" the name is
 *   queued before `INVITE`, signed by the attempt pseudonym, so the ring screen shows "~Rahul"; without it (the Design F
 *   arm, kept as the control) it waits for the callee's verified `REVEAL`. The **subject is always held** until the reveal
 *   in both arms: "medical emergency at tent 4" is more sensitive than a name, and an advert can be copied (review F15).
 * - Callee: unchanged for "Everyone I call or answer" (name at Ringing to a verified contact, else with Accept). For
 *   "Only people I verified" the callee cannot tell a stranger from a contact whose lease expired before the caller's
 *   reveal, so its name is queued only after that reveal is verified and found to be a verified contact (review F11).
 *   Declining or letting it ring reveals nothing.
 * - A peer without bit 5 (a 0.2.0-beta phone with names off, which is its default) gets nothing; nothing breaks.
 */
internal enum class NamesAudience(val prefValue: String) {
    EVERYONE("everyone"),
    VERIFIED_ONLY("verified"),
    NOBODY("nobody"),
    ;

    companion object {
        fun fromPref(value: String?): NamesAudience = entries.firstOrNull { it.prefValue == value } ?: EVERYONE
    }
}

/** When one label is queued. */
internal enum class LabelSendMoment {
    /** Never sent in this call. */
    NEVER,

    /** Immediately: before `INVITE` (caller) or as soon as the session allows. */
    NOW,

    /** After a relayed callee proved it owns the offer by its `OPENING` (existing behaviour for relays). */
    AFTER_OPENING,

    /** After the callee's `REVEAL` is verified (the Design F rule). */
    AFTER_REVEAL,

    /** After the peer's `REVEAL` is verified AND it is a verified contact; otherwise never. */
    AFTER_REVEAL_IF_VERIFIED,

    /** Callee: with Ringing (the caller is already known to be a verified contact). */
    AT_RINGING,

    /** Callee: with Accept. */
    AT_ACCEPT,
}

internal data class CallerLabelPlan(val name: LabelSendMoment, val subject: LabelSendMoment)

internal data class CallerLabelContext(
    /** Both endpoint hellos carry bit 5. */
    val labelsNegotiated: Boolean,
    val hasName: Boolean,
    val hasSubject: Boolean,
    val audience: NamesAudience,
    /** "Show my name before they answer". */
    val nameBeforeAnswer: Boolean,
    /** The callee presented a throwaway key and its reveal is still awaited. */
    val calleeAwaitsReveal: Boolean,
    /** A relayed callee that is proven only by its `OPENING`, which follows the Invite. */
    val relayedAwaitsOpening: Boolean,
    /** The callee's real key is known now and equals a verified contact's. */
    val calleeIsVerifiedContact: Boolean,
)

internal data class CalleeLabelContext(
    val labelsNegotiated: Boolean,
    val hasName: Boolean,
    val audience: NamesAudience,
    /** This phone presented a throwaway key: the caller's identity is unknown until its reveal. */
    val ownPseudonymous: Boolean,
    /** The caller's real key is known now and equals a verified contact's (a real-key session). */
    val callerIsVerifiedContact: Boolean,
)

internal object CallLabelPolicy {
    fun callerPlan(c: CallerLabelContext): CallerLabelPlan {
        if (!c.labelsNegotiated) return CallerLabelPlan(LabelSendMoment.NEVER, LabelSendMoment.NEVER)
        val subject = when {
            !c.hasSubject -> LabelSendMoment.NEVER
            c.calleeAwaitsReveal -> LabelSendMoment.AFTER_REVEAL
            c.relayedAwaitsOpening -> LabelSendMoment.AFTER_OPENING
            else -> LabelSendMoment.NOW
        }
        val name = when {
            !c.hasName || c.audience == NamesAudience.NOBODY -> LabelSendMoment.NEVER
            c.audience == NamesAudience.VERIFIED_ONLY -> when {
                c.calleeAwaitsReveal -> LabelSendMoment.AFTER_REVEAL_IF_VERIFIED
                !c.calleeIsVerifiedContact -> LabelSendMoment.NEVER
                c.relayedAwaitsOpening -> LabelSendMoment.AFTER_OPENING
                else -> LabelSendMoment.NOW
            }
            c.calleeAwaitsReveal -> if (c.nameBeforeAnswer) LabelSendMoment.NOW else LabelSendMoment.AFTER_REVEAL
            c.relayedAwaitsOpening -> LabelSendMoment.AFTER_OPENING
            else -> LabelSendMoment.NOW
        }
        return CallerLabelPlan(name, subject)
    }

    fun calleeMoment(c: CalleeLabelContext): LabelSendMoment {
        if (!c.labelsNegotiated || !c.hasName) return LabelSendMoment.NEVER
        return when (c.audience) {
            NamesAudience.NOBODY -> LabelSendMoment.NEVER
            NamesAudience.EVERYONE -> if (c.callerIsVerifiedContact) LabelSendMoment.AT_RINGING else LabelSendMoment.AT_ACCEPT
            NamesAudience.VERIFIED_ONLY -> when {
                c.callerIsVerifiedContact -> LabelSendMoment.AT_RINGING
                c.ownPseudonymous -> LabelSendMoment.AFTER_REVEAL_IF_VERIFIED
                else -> LabelSendMoment.NEVER
            }
        }
    }
}

/**
 * The stored settings of Naming v2 labels. The audience is a three-way choice (Settings > Your ID, shown only when a name is
 * set) and so is not a [FeatureSpec]; "Show my name before they answer" is a switch under it.
 *
 * A DEBUG benchmark launch (any `test_` extra) reads only its extras: `test_names_audience=everyone|verified|nobody`
 * (default everyone) and `test_names_before_answer` (default off, which is the Design F behaviour the measured cases used).
 */
internal object NamingSettings {
    const val PREF_AUDIENCE = "names_audience"
    const val PREF_BEFORE_ANSWER = "feature_names_before_answer"
    const val EXTRA_AUDIENCE = "test_names_audience"
    const val EXTRA_BEFORE_ANSWER = "test_names_before_answer"

    fun audience(benchmarkLaunch: Boolean, extra: (String) -> String?, stored: (String) -> String?): NamesAudience =
        NamesAudience.fromPref(if (benchmarkLaunch) extra(EXTRA_AUDIENCE) else stored(PREF_AUDIENCE))

    /** The spec's one default ([NamingFeatureDefaults.NAMES_BEFORE_ANSWER], design decision G-Q1) by the shared rule. */
    fun nameBeforeAnswer(benchmarkLaunch: Boolean, extra: (String) -> Boolean, stored: (String) -> Boolean?): Boolean =
        FeatureCatalog.resolve(NamingFeatures.NAMES_BEFORE_ANSWER, benchmarkLaunch, extra, stored)
}
