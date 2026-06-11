package id.homebase.api.client.contacts

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactContentSerializationTest {

    @Test
    fun omitsNullFields_soUpdateMergeLeavesThemAlone() {
        // Only displayName is set; every other field must be absent from the wire form, because the
        // server treats an absent field as "leave the stored value alone" on UPDATE.
        val json = OdinSystemSerializer.serialize(
            ContactContent(name = ContactName(displayName = "Sam")),
        )

        assertEquals("""{"name":{"displayName":"Sam"}}""", json)
        assertFalse(json.contains("odinId"))
        assertFalse(json.contains("givenName"))
        assertFalse(json.contains("location"))
    }

    @Test
    fun usesCamelCaseKeys() {
        val json = OdinSystemSerializer.serialize(
            ContactContent(name = ContactName(additionalName = "Q")),
        )
        assertTrue(json.contains("\"additionalName\""), "expected camelCase key, got: $json")
    }

    @Test
    fun roundTripsAllFields() {
        val original = ContactContent(
            odinId = "sam.dotyou.cloud",
            name = ContactName(
                displayName = "Sam Q. Public",
                givenName = "Sam",
                additionalName = "Q",
                surname = "Public",
            ),
            location = ContactLocation(city = "Springfield", country = "US"),
            phone = ContactPhone(number = "+1-555-0100"),
            email = ContactEmail(email = "sam@dotyou.cloud"),
            birthday = ContactBirthday(date = "1990-01-01"),
        )

        val json = OdinSystemSerializer.serialize(original)
        val decoded = OdinSystemSerializer.deserialize<ContactContent>(json)

        assertEquals(original, decoded)
    }

    @Test
    fun createRequestWrapsContentUnderContentKey() {
        val json = OdinSystemSerializer.serialize(
            CreateContactRequest(ContactContent(odinId = "sam.dotyou.cloud")),
        )
        assertEquals("""{"content":{"odinId":"sam.dotyou.cloud"}}""", json)
    }
}
