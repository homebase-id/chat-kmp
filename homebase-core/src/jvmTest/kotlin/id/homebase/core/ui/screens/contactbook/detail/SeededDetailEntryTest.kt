package id.homebase.core.ui.screens.contactbook.detail

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactName
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SeededDetailEntryTest {
    private val id = Uuid.random()
    private val contacts = listOf(
        Contact(
            uniqueId = Uuid.random(),
            versionTag = null,
            content = ContactContent(name = ContactName(displayName = "Frodo Baggins")),
        ),
        Contact(
            uniqueId = id,
            versionTag = Uuid.random(),
            content = ContactContent(odinId = "sam.dotyou.cloud", name = ContactName(displayName = "Sam Gamgee")),
        ),
    )

    @Test
    fun theDetailOpensOnTheEntryTheListAlreadyHolds() {
        val seeded = seededDetailEntry(contacts, id.toString(), emptyMap())
        assertEquals(id, seeded?.uniqueId)
        assertEquals("Sam Gamgee", seeded?.displayName)
    }

    @Test
    fun theSeedCarriesTheUsersOverrideLikeTheCollectorDoes() {
        val seeded = seededDetailEntry(contacts, id.toString(), mapOf(id to ContactFieldOverlay(givenName = "Samwise")))
        assertEquals("Samwise", seeded?.givenName)
    }

    @Test
    fun aContactNotInTheRepositoryStillWaitsForTheCollector() {
        assertNull(seededDetailEntry(contacts, Uuid.random().toString(), emptyMap()))
    }
}
