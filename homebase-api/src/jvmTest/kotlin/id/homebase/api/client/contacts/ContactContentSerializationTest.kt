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
            source = "user",
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
    fun fullAddress_usesCamelCaseWireKeysAndRoundTrips() {
        val original = ContactContent(
            location = ContactLocation(
                label = "Home",
                addressLine1 = "123 Main St",
                addressLine2 = "Apt 4",
                postcode = "12345",
                city = "Springfield",
                country = "US",
            ),
            phone = ContactPhone(label = "Mobile", number = "+1-555-0100"),
            email = ContactEmail(label = "Personal", email = "sam@dotyou.cloud"),
        )

        val json = OdinSystemSerializer.serialize(original)
        // Wire keys must be the server's camelCase form (odin-js calls these address1/address2).
        assertTrue(json.contains("\"addressLine1\""), json)
        assertTrue(json.contains("\"addressLine2\""), json)
        assertTrue(json.contains("\"postcode\""), json)
        assertTrue(json.contains("\"label\""), json)

        assertEquals(original, OdinSystemSerializer.deserialize<ContactContent>(json))
    }

    @Test
    fun sourceRoundTripsAndIsOmittedWhenNull() {
        assertEquals(
            """{"source":"public"}""",
            OdinSystemSerializer.serialize(ContactContent(source = "public")),
        )
        // Absent when not set.
        assertFalse(OdinSystemSerializer.serialize(ContactContent(odinId = "x")).contains("source"))
    }

    @Test
    fun createRequestWrapsContentUnderContentKey() {
        val json = OdinSystemSerializer.serialize(
            CreateContactRequest(ContactContent(odinId = "sam.dotyou.cloud")),
        )
        assertEquals("""{"content":{"odinId":"sam.dotyou.cloud"}}""", json)
    }
}
