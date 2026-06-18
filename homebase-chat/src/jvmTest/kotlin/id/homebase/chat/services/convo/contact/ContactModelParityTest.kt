package id.homebase.chat.services.convo.contact

import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.common.OdinId
import id.homebase.api.client.contacts.ContactBirthday as ApiBirthday
import id.homebase.api.client.contacts.ContactEmail as ApiEmail
import id.homebase.api.client.contacts.ContactLocation as ApiLocation
import id.homebase.api.client.contacts.ContactName as ApiName
import id.homebase.api.client.contacts.ContactPhone as ApiPhone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 0 safety net (see CONTACT_STACK_CONSOLIDATION.md): the contact-book stack parses a stored
 * contact file as [ContactContent] (homebase-api) while the chat stack parses the *same* file as
 * [ContactServerFile] (homebase-chat) — two parallel model families over one drive. These tests
 * pin that the two are **wire-compatible** today, so the Phase 2 consolidation onto a single model
 * is provably safe, and any future drift between the families fails here instead of silently in
 * production (the kind of mismatch behind the contact-detail "None" symptom).
 *
 * Both go through [OdinSystemSerializer], so we serialize one family and deserialize as the other.
 */
class ContactModelParityTest {

    @Test
    fun apiContactContent_deserializesAsChatContactServerFile_allFieldsMatch() {
        val api = ContactContent(
            odinId = "sam.dotyou.cloud",
            source = "user",
            name = ApiName(
                displayName = "Sam Q. Public",
                givenName = "Sam",
                additionalName = "Q",
                surname = "Public",
            ),
            location = ApiLocation(city = "Springfield", country = "US"),
            phone = ApiPhone(number = "+1-555-0100"),
            email = ApiEmail(email = "sam@dotyou.cloud"),
            birthday = ApiBirthday(date = "1990-01-01"),
        )

        val json = OdinSystemSerializer.serialize(api)
        val chat = OdinSystemSerializer.deserialize<ContactServerFile>(json)

        assertEquals(api.odinId, chat.odinId?.toString())
        assertEquals(api.source, chat.source)
        assertEquals(api.name?.displayName, chat.name.displayName)
        assertEquals(api.name?.givenName, chat.name.givenName)
        assertEquals(api.name?.additionalName, chat.name.additionalName)
        assertEquals(api.name?.surname, chat.name.surname)
        assertEquals(api.location?.city, chat.location?.city)
        assertEquals(api.location?.country, chat.location?.country)
        assertEquals(api.phone?.number, chat.phone?.number)
        assertEquals(api.email?.email, chat.email?.email)
        assertEquals(api.birthday?.date, chat.birthday?.date)
    }

    @Test
    fun chatContactServerFile_deserializesAsApiContactContent_allFieldsMatch() {
        val chat = ContactServerFile(
            odinId = OdinId("frodo.baggins.demo.rocks"),
            source = "public",
            name = ContactName(
                displayName = "Frodo Baggins",
                givenName = "Frodo",
                additionalName = null,
                surname = "Baggins",
            ),
            location = ContactLocation(city = "Hobbiton", country = "Shire"),
            phone = ContactPhone(number = "+1-555-7777"),
            email = ContactEmail(email = "frodo@demo.rocks"),
            birthday = ContactBirthday(date = "2980-09-22"),
            image = null,
        )

        val json = OdinSystemSerializer.serialize(chat)
        val api = OdinSystemSerializer.deserialize<ContactContent>(json)

        assertEquals(chat.odinId?.toString(), api.odinId)
        assertEquals(chat.source, api.source)
        assertEquals(chat.name.displayName, api.name?.displayName)
        assertEquals(chat.name.givenName, api.name?.givenName)
        assertEquals(chat.name.surname, api.name?.surname)
        assertEquals(chat.location?.city, api.location?.city)
        assertEquals(chat.location?.country, api.location?.country)
        assertEquals(chat.phone?.number, api.phone?.number)
        assertEquals(chat.email?.email, api.email?.email)
        assertEquals(chat.birthday?.date, api.birthday?.date)
    }

    /**
     * The synced-contact shape (displayName only). Pinned across both families because it's the
     * exact data that surfaces the detail-screen "None" symptom; the consolidation must preserve
     * how `displayName` round-trips when given/surname are absent.
     */
    @Test
    fun displayNameOnly_roundTripsAcrossFamilies() {
        val api = ContactContent(
            odinId = "samwise.gamgee.demo.rocks",
            source = "public",
            name = ApiName(displayName = "Samwise Gamgee"),
        )
        val chat = OdinSystemSerializer.deserialize<ContactServerFile>(
            OdinSystemSerializer.serialize(api),
        )

        assertEquals("Samwise Gamgee", chat.name.displayName)
        assertEquals(null, chat.name.givenName)
        assertEquals(null, chat.name.surname)
    }
}
