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
    fun nobodyNearbyMeansNoRows() {
        assertTrue(nearbyRows(emptyList(), contactKeys).isEmpty())
    }
}
