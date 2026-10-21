package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleListTest {
    private fun person(key: String, name: String) =
        PeopleItem.Person(key = key, name = name, reach = "Nearby", relayed = false, avatarColor = 0, showVoice = true)

    private val contactKeys = setOf("aaaaaaaaaaaa", "bbbbbbbbbbbb")

    private fun names(items: List<PeopleItem>) = items.map { (it as PeopleItem.Person).name }

    @Test
    fun contactsInRangeComeFirstThenStrangers() {
        val rows = nearbyRows(
            listOf(
                person("cccccccccccc", "Someone nearby"),
                person("bbbbbbbbbbbb", "Sam"),
                person("aaaaaaaaaaaa", "Priya"),
            ),
            contactKeys,
        )
        assertEquals(listOf("Priya", "Sam", "Someone nearby"), names(rows))
    }

    @Test
    fun everyPhoneInRangeAppearsOnce() {
        val people = listOf(person("aaaaaaaaaaaa", "Priya"), person("cccccccccccc", "Someone nearby"))
        assertEquals(people.size, nearbyRows(people, contactKeys).size)
    }

    @Test
    fun sessionOrderSurvivesScanReorderingRenamesAndDisappearance() {
        val session = NearbyPeopleSession()
        val first = person("c", "Zoe")
        val second = person("d", "Alex")
        assertEquals(listOf("Zoe", "Alex"), names(session.rows(listOf(first, second), emptySet())))
        assertEquals(listOf("Zoe", "Alex"), names(session.rows(listOf(second, first), emptySet())))
        session.rows(listOf(second), emptySet())
        assertEquals(listOf("Renamed", "Alex"), names(session.rows(listOf(second, first.copy(name = "Renamed")), emptySet())))
        assertEquals(listOf("Alex", "Zoe"), names(session.rows(listOf(first, second), setOf("d"))))
    }

    @Test
    fun searchFiltersNamesWithoutLosingOrderOrHidingAnActiveQuery() {
        val session = NearbyPeopleSession()
        val people = (1..9).map { person(it.toString(), "Person $it") }
        assertTrue(session.showSearch(people, ""))
        assertEquals(listOf("Person 2"), names(session.rows(people, emptySet(), "PERSON 2")))
        assertTrue(session.rows(people, emptySet(), "nobody").isEmpty())
        assertTrue(session.showSearch(emptyList(), "nobody"))
        assertEquals(false, session.showSearch(people.take(8), ""))
        assertEquals(people.map { it.name }, names(session.rows(people.reversed(), emptySet(), "")))
    }

    @Test
    fun nobodyNearbyMeansNoRows() {
        assertTrue(nearbyRows(emptyList(), contactKeys).isEmpty())
    }
}
