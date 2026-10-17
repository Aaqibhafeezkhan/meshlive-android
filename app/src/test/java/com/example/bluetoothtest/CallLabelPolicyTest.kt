package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a name and a subject are sent (Design G, G1): audience x contact/stranger x pseudonym x timing, and the callee rule. */
class CallLabelPolicyTest {
    private fun caller(
        audience: NamesAudience = NamesAudience.EVERYONE,
        negotiated: Boolean = true,
        name: Boolean = true,
        subject: Boolean = true,
        beforeAnswer: Boolean = true,
        awaitsReveal: Boolean = false,
        relayed: Boolean = false,
        verifiedContact: Boolean = false,
    ) = CallLabelPolicy.callerPlan(
        CallerLabelContext(negotiated, name, subject, audience, beforeAnswer, awaitsReveal, relayed, verifiedContact),
    )

    private fun callee(
        audience: NamesAudience = NamesAudience.EVERYONE,
        negotiated: Boolean = true,
        name: Boolean = true,
        ownPseudonymous: Boolean = false,
        callerVerified: Boolean = false,
    ) = CallLabelPolicy.calleeMoment(CalleeLabelContext(negotiated, name, audience, ownPseudonymous, callerVerified))

    // --- caller ---

    @Test
    fun aPeerWithoutBit5GetsNothingFromEitherSide() {
        // A 0.2.0-beta phone with names off (its default) never advertises bit 5: nothing is sent, nothing breaks.
        assertEquals(CallerLabelPlan(LabelSendMoment.NEVER, LabelSendMoment.NEVER), caller(negotiated = false))
        assertEquals(LabelSendMoment.NEVER, callee(negotiated = false))
    }

    @Test
    fun aStrangerSessionWithShowMyNameBeforeTheyAnswerSendsTheNameNowAndHoldsTheSubject() {
        val plan = caller(awaitsReveal = true, beforeAnswer = true)
        assertEquals(LabelSendMoment.NOW, plan.name)
        assertEquals(LabelSendMoment.AFTER_REVEAL, plan.subject)
    }

    @Test
    fun theHeldArmKeepsTheDesignFRuleForBoth() {
        val plan = caller(awaitsReveal = true, beforeAnswer = false)
        assertEquals(LabelSendMoment.AFTER_REVEAL, plan.name)
        assertEquals(LabelSendMoment.AFTER_REVEAL, plan.subject)
    }

    @Test
    fun theSubjectIsAlwaysHeldUntilTheRevealInEveryAudienceAndArm() {
        for (audience in NamesAudience.entries) for (before in listOf(true, false)) {
            assertEquals(
                "$audience/$before",
                LabelSendMoment.AFTER_REVEAL,
                caller(audience = audience, beforeAnswer = before, awaitsReveal = true).subject,
            )
        }
    }

    @Test
    fun aRealKeySessionSendsBothNow() {
        val plan = caller()
        assertEquals(CallerLabelPlan(LabelSendMoment.NOW, LabelSendMoment.NOW), plan)
    }

    @Test
    fun aRelayedCalleeWaitsForItsOpeningAsBefore() {
        val plan = caller(relayed = true)
        assertEquals(CallerLabelPlan(LabelSendMoment.AFTER_OPENING, LabelSendMoment.AFTER_OPENING), plan)
    }

    @Test
    fun nothingIsSentWhenThereIsNothingToSend() {
        assertEquals(CallerLabelPlan(LabelSendMoment.NEVER, LabelSendMoment.NEVER), caller(name = false, subject = false))
        assertEquals(LabelSendMoment.NOW, caller(name = false).subject)
        assertEquals(LabelSendMoment.NEVER, caller(name = false).name)
    }

    @Test
    fun audienceNobodySendsNoNameButStillAllowsASubject() {
        val plan = caller(audience = NamesAudience.NOBODY)
        assertEquals(LabelSendMoment.NEVER, plan.name)
        assertEquals(LabelSendMoment.NOW, plan.subject)
        assertEquals(LabelSendMoment.NEVER, caller(audience = NamesAudience.NOBODY, awaitsReveal = true).name)
    }

    @Test
    fun audienceVerifiedOnlyGivesTheNameToAVerifiedContactAndToNoStranger() {
        assertEquals(LabelSendMoment.NOW, caller(audience = NamesAudience.VERIFIED_ONLY, verifiedContact = true).name)
        assertEquals(LabelSendMoment.NEVER, caller(audience = NamesAudience.VERIFIED_ONLY, verifiedContact = false).name)
        // A pseudonymous callee might be a contact whose lease expired: decided only after its verified reveal.
        assertEquals(
            LabelSendMoment.AFTER_REVEAL_IF_VERIFIED,
            caller(audience = NamesAudience.VERIFIED_ONLY, awaitsReveal = true).name,
        )
        assertEquals(
            LabelSendMoment.AFTER_OPENING,
            caller(audience = NamesAudience.VERIFIED_ONLY, relayed = true, verifiedContact = true).name,
        )
    }

    @Test
    fun theNameBeforeAnswerSettingChangesNothingForAVerifiedOrRealKeyCall() {
        for (before in listOf(true, false)) {
            assertEquals(LabelSendMoment.NOW, caller(beforeAnswer = before).name)
            assertEquals(LabelSendMoment.NOW, caller(audience = NamesAudience.VERIFIED_ONLY, verifiedContact = true, beforeAnswer = before).name)
        }
    }

    // --- callee ---

    @Test
    fun theCalleeKeepsTheExistingRuleForEveryone() {
        assertEquals(LabelSendMoment.AT_RINGING, callee(callerVerified = true))
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(callerVerified = false))
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(ownPseudonymous = true))
    }

    @Test
    fun withVerifiedOnlyAStrangerCallerGetsNoNameAndAPseudonymSessionWaitsForTheCallersReveal() {
        // A real key, not verified: a stranger. Nothing is ever sent.
        assertEquals(LabelSendMoment.NEVER, callee(NamesAudience.VERIFIED_ONLY, callerVerified = false))
        // A real-key verified contact: at Ringing.
        assertEquals(LabelSendMoment.AT_RINGING, callee(NamesAudience.VERIFIED_ONLY, callerVerified = true))
        // The callee presented a throwaway (lease-expired contacts look like strangers): decided at the caller's reveal.
        assertEquals(
            LabelSendMoment.AFTER_REVEAL_IF_VERIFIED,
            callee(NamesAudience.VERIFIED_ONLY, ownPseudonymous = true, callerVerified = false),
        )
    }

    @Test
    fun theCalleeSendsNothingForNobodyOrWithoutAName() {
        assertEquals(LabelSendMoment.NEVER, callee(NamesAudience.NOBODY, callerVerified = true))
        assertEquals(LabelSendMoment.NEVER, callee(name = false, callerVerified = true))
    }

    @Test
    fun decliningOrLettingItRingRevealsNothingBecauseNoMomentIsBeforeAcceptForAStranger() {
        // The only moment before Accept is AT_RINGING, which is reachable only with callerIsVerifiedContact.
        for (audience in NamesAudience.entries) for (pseudo in listOf(true, false)) {
            assertTrue(callee(audience, ownPseudonymous = pseudo, callerVerified = false) != LabelSendMoment.AT_RINGING)
        }
    }

    // --- audience / settings storage ---

    @Test
    fun audienceDefaultsToEveryoneAndParsesTheStoredValue() {
        assertEquals(NamesAudience.EVERYONE, NamesAudience.fromPref(null))
        assertEquals(NamesAudience.EVERYONE, NamesAudience.fromPref("garbage"))
        assertEquals(NamesAudience.VERIFIED_ONLY, NamesAudience.fromPref("verified"))
        assertEquals(NamesAudience.NOBODY, NamesAudience.fromPref("nobody"))
        for (a in NamesAudience.entries) assertEquals(a, NamesAudience.fromPref(a.prefValue))
    }

    @Test
    fun aBenchmarkLaunchReadsOnlyItsExtrasAndKeepsTheMeasuredBehaviour() {
        // Stored choices never reach a measured case; the default of the timing switch there is the Design F arm (off).
        val storedAudience = { _: String -> "nobody" }
        assertEquals(NamesAudience.EVERYONE, NamingSettings.audience(true, { null }, storedAudience))
        assertEquals(NamesAudience.NOBODY, NamingSettings.audience(true, { "nobody" }, { null }))
        assertFalse(NamingSettings.nameBeforeAnswer(true, { false }, { true }))
        assertTrue(NamingSettings.nameBeforeAnswer(true, { it == NamingSettings.EXTRA_BEFORE_ANSWER }, { false }))
    }

    @Test
    fun aUserLaunchUsesTheStoredChoiceElseTheDefaultOn() {
        assertTrue(NamingSettings.nameBeforeAnswer(false, { false }, { null }))
        assertFalse(NamingSettings.nameBeforeAnswer(false, { false }, { false }))
        assertEquals(NamesAudience.VERIFIED_ONLY, NamingSettings.audience(false, { null }, { "verified" }))
        assertEquals(NamesAudience.EVERYONE, NamingSettings.audience(false, { null }, { null }))
    }

    // --- the labels switch is split from the public title pages ---

    @Test
    fun theTitlePagesSwitchIsItsOwnAndOnByDefault() {
        assertTrue(NamingFeatureDefaults.TITLE_PAGES)
        assertTrue(NamingFeatures.resolveTitlePages(false, { false }, { null }))
        assertTrue(NamingFeatures.resolveTitlePages(false, { false }, { if (it == NamingFeatures.TITLE_PAGES.prefKey) true else null }))
        // The old combined extra of benchmark launches still switches the pages, so measured title-page cases stay valid.
        assertTrue(NamingFeatures.resolveTitlePages(true, { it == PeerLabelFeature.EXTRA }, { null }))
        assertTrue(NamingFeatures.resolveTitlePages(true, { it == NamingFeatures.TITLE_PAGES.benchmarkExtra }, { null }))
        assertFalse(NamingFeatures.resolveTitlePages(true, { false }, { true }))
    }

    @Test
    fun anExplicitOldChoiceCarriesOverOnceAndAnExplicitNewChoiceWins() {
        assertEquals(true, NamingMigration.titlePagesFromLegacy(hasNewChoice = false, legacyNamesChoice = true))
        assertEquals(false, NamingMigration.titlePagesFromLegacy(hasNewChoice = false, legacyNamesChoice = false))
        assertNull(NamingMigration.titlePagesFromLegacy(hasNewChoice = true, legacyNamesChoice = true))
        assertNull(NamingMigration.titlePagesFromLegacy(hasNewChoice = false, legacyNamesChoice = null))
    }

    @Test
    fun theInSessionNamesSwitchKeepsItsOldKeyAndDefaultOff() {
        assertEquals("experimental_peer_labels_v2", CallFeature.NAMES_AND_TITLES.prefKey)
        assertEquals("test_peer_labels", CallFeature.NAMES_AND_TITLES.benchmarkExtra)
        assertTrue(CallFeatureDefaults.NAMES_AND_TITLES)
    }
}
