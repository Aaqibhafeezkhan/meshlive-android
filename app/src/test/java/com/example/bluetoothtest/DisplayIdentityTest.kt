package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The single display-name precedence function (Design G, G3): every row, ties and look-alike handling. */
class DisplayIdentityTest {
    private val handle = "M6SC7-N75YR"
    private val table = NamingTestSupport.bundledTable

    @Before
    fun setUp() {
        StrangerNames.installForTest(NamingTestSupport.lists)
    }

    @After
    fun tearDown() {
        StrangerNames.installForTest(null)
    }

    private fun resolve(block: (DisplayInputs) -> DisplayInputs): DisplayIdentity =
        DisplayIdentityResolver.resolve(block(DisplayInputs(handle = handle, generatedName = "Blue Otter")))

    @Test
    fun rank7TheHandleIsShownWhenNothingElseIsKnown() {
        val row = DisplayIdentityResolver.resolve(DisplayInputs(handle = handle))
        assertEquals(DisplayRank.HANDLE, row.rank)
        assertEquals(handle, row.title)
        assertEquals(DisplayMark.NONE, row.mark)
        assertNull(row.claimedName)
    }

    @Test
    fun rank6AGeneratedNameIsTheTitleOfARotatingRow() {
        val row = resolve { it }
        assertEquals(DisplayRank.GENERATED, row.rank)
        assertEquals("Blue Otter", row.title)
        assertEquals("Nearby phone", row.subtitle)
        assertEquals(DisplayMark.NONE, row.mark)
        assertTrue(row.speech.contains("not verified"))
    }

    @Test
    fun theGeneratedNamesSwitchOffShowsTheHandleLikeTheEarlierBuild() {
        val row = resolve { it.copy(generatedNamesEnabled = false) }
        assertEquals(DisplayRank.HANDLE, row.rank)
        assertEquals(handle, row.title)
    }

    @Test
    fun rank5ASelfClaimedNameKeepsTheTildeAsAMarkAndNeverInTheText() {
        val row = resolve { it.copy(claimedName = "Rahul") }
        assertEquals(DisplayRank.SELF_CLAIMED, row.rank)
        assertEquals("Rahul", row.title)
        assertEquals("Rahul", row.claimedName)
        assertEquals("calls itself", row.titleNote)
        assertEquals("Blue Otter - not verified", row.subtitle)
        assertEquals(DisplayMark.TILDE, row.mark)
        assertFalse(row.title.contains('~') || row.plain.contains('~') || row.speech.contains('~'))
        assertEquals("calls itself Rahul, not verified", row.speech)
    }

    @Test
    fun aSelfClaimedNameFallsBackToTheHandleBelowWhenThereIsNoGeneratedName() {
        val row = DisplayIdentityResolver.resolve(DisplayInputs(handle = handle, claimedName = "Rahul"))
        assertEquals("$handle - not verified", row.subtitle)
    }

    @Test
    fun rank4ARecognisedContactIsYourLabelWithTheUnconfirmedNote() {
        val row = resolve { it.copy(recognisedLabel = "Mum", claimedName = "Sunita") }
        assertEquals(DisplayRank.RECOGNISED, row.rank)
        assertEquals("Mum", row.title)
        assertEquals("seen nearby, unconfirmed", row.titleNote)
        assertEquals(DisplayMark.NONE, row.mark)
    }

    @Test
    fun rank3AVerifiedContactIsYourLabelEvenWhenTheyClaimAnotherNameAndGetsAShield() {
        val row = resolve { it.copy(verifiedLabel = "Mum", claimedName = "medic", recognisedLabel = "Other") }
        assertEquals(DisplayRank.VERIFIED, row.rank)
        assertEquals("Mum", row.title)
        assertEquals(DisplayMark.SHIELD, row.mark)
        assertNull("the contact's own name is never the title", row.claimedName)
        assertEquals("Mum, verified in person", row.speech)
    }

    @Test
    fun aContactCheckedOnlyByCodeGetsTheOutlineNotTheShield() {
        val row = resolve { it.copy(verifiedLabel = "Mum", verification = VerificationBasis.CHECKED_BY_CODE) }
        assertEquals(DisplayRank.VERIFIED, row.rank)
        assertEquals(DisplayMark.SHIELD_OUTLINE, row.mark)
        assertTrue(row.subtitle.orEmpty().isNotBlank())
        assertTrue(row.speech.contains("not scanned in person"))
    }

    @Test
    fun rank2BlockedBeatsAVerifiedLabelAndRecognition() {
        val row = resolve { it.copy(blocked = true, verifiedLabel = "Mum", recognisedLabel = "Mum", claimedName = "Mum") }
        assertEquals(DisplayRank.BLOCKED, row.rank)
        assertEquals("Mum", row.title)
        assertEquals(DisplayMark.BLOCKED, row.mark)
        assertEquals("Blocked", row.subtitle)
    }

    @Test
    fun aBlockedStrangerShowsTheHandleNeverAGeneratedName() {
        val row = DisplayIdentityResolver.resolve(DisplayInputs(handle = handle, generatedName = "Blue Otter", blocked = true))
        assertEquals(DisplayRank.BLOCKED, row.rank)
        assertEquals(handle, row.title)
    }

    @Test
    fun rank1AKeyChangeBeatsEverythingIncludingBlocked() {
        val row = resolve {
            it.copy(keyChangedContactLabel = "Mum", blocked = true, verifiedLabel = "Mum", claimedName = "Mum")
        }
        assertEquals(DisplayRank.KEY_CHANGED, row.rank)
        assertEquals("Not Mum", row.title)
        assertEquals(DisplayMark.WARNING, row.mark)
        assertTrue(row.warning is DisplayWarning.KeyChanged)
        assertTrue(DisplayIdentityText.warningText(row.warning!!).contains("Someone may be pretending to be Mum"))
    }

    @Test
    fun theRankOrderIsTheTableOrder() {
        assertEquals(
            listOf(
                DisplayRank.KEY_CHANGED, DisplayRank.BLOCKED, DisplayRank.VERIFIED, DisplayRank.RECOGNISED,
                DisplayRank.SELF_CLAIMED, DisplayRank.GENERATED, DisplayRank.HANDLE,
            ),
            DisplayRank.entries.sortedBy { it.level },
        )
    }

    @Test
    fun anImitationWarningTravelsWithTheSelfClaimedRowButChangesNoRank() {
        val warning = DisplayWarning.NotYourContact("Mum")
        val row = resolve { it.copy(claimedName = "Mum", imitation = warning) }
        assertEquals(DisplayRank.SELF_CLAIMED, row.rank)
        assertEquals(warning, row.warning)
        assertTrue(row.warning!!.isAlarm)
    }

    // --- look-alike handling by kind (review F12, F14) ---

    private fun check(
        claimed: String,
        contacts: List<ImitationCheck.ContactNames> = emptyList(),
        own: String? = null,
        generated: List<String> = emptyList(),
        others: List<String> = emptyList(),
        afterReveal: Boolean = true,
    ) = ImitationCheck.evaluate(claimed, contacts, own, generated, others, afterReveal, table)

    @Test
    fun aStrangerClaimingExactlyAContactLabelIsAnAlarmAfterTheReveal() {
        val finding = check("Mum", contacts = listOf(ImitationCheck.ContactNames("Mum")))
        assertEquals(DisplayWarning.NotYourContact("Mum"), finding)
        assertEquals(DisplayWarning.NotYourContact("Mum"), check("mum", contacts = listOf(ImitationCheck.ContactNames("Mum"))))
    }

    @Test
    fun beforeTheRevealTheSameClaimIsNeutralBecauseALeaseMayHaveExpired() {
        val finding = check("Mum", contacts = listOf(ImitationCheck.ContactNames("Mum")), afterReveal = false)
        assertEquals(DisplayWarning.SaysTheyAre("Mum"), finding)
        assertFalse(finding!!.isAlarm)
        assertTrue("the neutral claim names the claimed name", DisplayIdentityText.warningText(finding).contains("Mum"))
    }

    @Test
    fun aSkeletonMatchWithAContactIsAlsoAnAlarm() {
        // Cyrillic a and o in "Mum" stay Latin; use "Аlice" with a Cyrillic capital A.
        val finding = check("Аlice", contacts = listOf(ImitationCheck.ContactNames("Alice")))
        assertEquals(DisplayWarning.NotYourContact("Alice"), finding)
    }

    @Test
    fun theContactsLastClaimedNameCountsToo() {
        val finding = check("Sunita K", contacts = listOf(ImitationCheck.ContactNames("Mum", lastClaimedName = "Sunita K")))
        assertEquals(DisplayWarning.NotYourContact("Mum"), finding)
    }

    @Test
    fun theSameNameAsYoursAGeneratedNameOrAnotherRowIsANeverAnAlarm() {
        assertEquals(DisplayWarning.SameAsYourName, check("Rahul", own = "rahul"))
        assertEquals(DisplayWarning.LooksLikeGeneratedName, check("Blue Otter", generated = listOf("Blue Otter")))
        assertEquals(DisplayWarning.LooksLikeGeneratedName, check("BlueOtter"))
        val several = check("Rahul", others = listOf("Rahul"))
        assertEquals(DisplayWarning.SeveralCallThemselves(2, "Rahul"), several)
        assertEquals("2 people nearby call themselves Rahul", DisplayIdentityText.warningText(several!!))
        listOf(DisplayWarning.SameAsYourName, DisplayWarning.LooksLikeGeneratedName, several).forEach { assertFalse(it.isAlarm) }
    }

    @Test
    fun anOrdinaryUniqueNameRaisesNothing() {
        assertNull(check("Rahul", contacts = listOf(ImitationCheck.ContactNames("Mum")), own = "Asha", others = listOf("Dev")))
    }

    // --- the PeerLabel hook ---

    @Test
    fun aPeerLabelBecomesTheSameDisplayAsTheDirectInputs() {
        val stranger = PeerLabel(handle)
        assertEquals(DisplayRank.GENERATED, stranger.display(generatedName = "Blue Otter", generatedNamesEnabled = true).rank)
        assertEquals(DisplayRank.HANDLE, stranger.display(generatedName = "Blue Otter", generatedNamesEnabled = false).rank)
        val claimed = PeerLabel(handle, "Rahul", PeerLabel.NameKind.SELF_NAMED)
        assertEquals(DisplayRank.SELF_CLAIMED, claimed.display(generatedName = "Blue Otter", generatedNamesEnabled = true).rank)
        val contact = PeerLabel(handle, "Mum", PeerLabel.NameKind.CONTACT)
        assertEquals(DisplayRank.VERIFIED, contact.display(generatedNamesEnabled = true).rank)
        assertEquals(DisplayRank.RECOGNISED, contact.display(generatedNamesEnabled = true, recognised = true).rank)
        assertEquals(DisplayRank.BLOCKED, contact.display(generatedNamesEnabled = true, blocked = true).rank)
        assertEquals("Mum", contact.display(generatedNamesEnabled = true, blocked = true).title)
        assertEquals(DisplayRank.KEY_CHANGED, stranger.display(generatedNamesEnabled = true, keyChangedContactLabel = "Mum").rank)
    }

    // --- forced-unique contact labels ---

    @Test
    fun contactLabelsAreForcedUniqueCaseAndSkeletonInsensitive() {
        val existing = listOf("Mum", "Alice")
        assertEquals("Mum", ContactLabelUniqueness.conflict("mum", existing, table = table))
        assertEquals("Alice", ContactLabelUniqueness.conflict("Аlice", existing, table = table))
        assertEquals("Alice", ContactLabelUniqueness.conflict("A1ice ", existing, table = table))
        assertNull(ContactLabelUniqueness.conflict("Dev", existing, table = table))
        assertNull("renaming a contact to its own label is not a conflict", ContactLabelUniqueness.conflict("Mum", existing, excluding = "Mum", table = table))
    }
}
