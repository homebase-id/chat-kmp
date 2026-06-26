package id.homebase.api.client.contacts

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactExtDataTest {

    private fun parse(json: String) = OdinSystemSerializer.deserialize<ContactExtData>(json)

    @Test
    fun parsesExperienceWithSnakeCaseFieldsAndRichTextFullBio() {
        val ext = parse(
            """
            {"attributes":{"65635623682c2fadd2767d424f53690f":{
                "short_bio":"experience-title",
                "full_bio":[{"type":"p","id":"NF4tmpq6sK","children":[{"text":"experience description"}]}],
                "experience_link":"https://experince.link",
                "experience_image":"xprnc_key"
            }}}
            """.trimIndent(),
        )

        val exp = ext.experience
        assertEquals("experience-title", exp?.title)
        assertEquals("https://experince.link", exp?.link)
        assertEquals("xprnc_key", exp?.imageKey)
        assertEquals("experience description", exp?.fullBioText)
    }

    @Test
    fun bioShortBioIsRichText_notAPlainString_disambiguatedByTypeId() {
        // Experience's short_bio is a plain string; Bio's short_bio is a rich-text array. The only
        // thing that tells them apart is the attribute type id (the map key), never the field name.
        val ext = parse(
            """
            {"attributes":{"2cd30a58568dc333237944481aeb9ff1":{
                "short_bio":[{"type":"paragraph","id":"NXHtACYXHc","children":[{"text":"born born"}]}]
            }}}
            """.trimIndent(),
        )

        assertEquals("born born", ext.bio?.shortBioText)
        assertNull(ext.experience, "Bio attribute must not surface as an Experience")
    }

    @Test
    fun toleratesUnknownTypeIdsAndUnknownInnerFields() {
        // Forward-compatible: an unknown attribute id and an unknown inner field on a known type must
        // not blow up parsing — they are simply ignored.
        val ext = parse(
            """
            {"attributes":{
                "ffffffffffffffffffffffffffffffff":{"whatever":123},
                "65635623682c2fadd2767d424f53690f":{"short_bio":"t","future_field":{"nested":true}}
            }}
            """.trimIndent(),
        )

        assertEquals("t", ext.experience?.title)
        assertTrue(ext.attributes.containsKey("ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun missingAttributesParsesAsEmpty() {
        val ext = parse("""{"attributes":{}}""")
        assertNull(ext.experience)
        assertNull(ext.bio)
        assertTrue(ext.attributes.isEmpty())
    }
}
