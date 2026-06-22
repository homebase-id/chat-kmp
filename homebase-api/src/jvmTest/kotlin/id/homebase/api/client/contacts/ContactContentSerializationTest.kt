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
    fun sourceRoundTripsAndIsOmittedWhenNull() {
        assertEquals(
            """{"source":"public"}""",
            OdinSystemSerializer.serialize(ContactContent(source = "public")),
        )
        // Absent when not set.
        assertFalse(OdinSystemSerializer.serialize(ContactContent(odinId = "x")).contains("source"))
    }

    @Test
    fun isEmergencyContact_omittedWhenFalse_emittedWhenTrue() {
        // @EncodeDefault(NEVER): the false default is omitted so an UPDATE doesn't clobber a stored
        // flag, while an explicit true is written. Reading an absent flag decodes back to false.
        assertFalse(
            OdinSystemSerializer.serialize(ContactContent(odinId = "x")).contains("isEmergencyContact"),
        )
        assertEquals(
            """{"isEmergencyContact":true}""",
            OdinSystemSerializer.serialize(ContactContent(isEmergencyContact = true)),
        )
        assertFalse(
            OdinSystemSerializer.deserialize<ContactContent>("""{"odinId":"x"}""").isEmergencyContact,
        )
    }

    @Test
    fun createRequestWrapsContentUnderContentKey() {
        val json = OdinSystemSerializer.serialize(
            CreateContactRequest(ContactContent(odinId = "sam.dotyou.cloud")),
        )
        assertEquals("""{"content":{"odinId":"sam.dotyou.cloud"}}""", json)
    }
}
