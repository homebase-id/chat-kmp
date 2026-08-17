package id.homebase.chat.contactcard

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VCardParserTest {

    @Test
    fun `parses a single vCard 3_0 contact`() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            N:Vance;Ada;;;
            FN:Ada Vance
            ORG:Homebase;Engineering
            TEL;TYPE=CELL:+1 (415) 555-0123
            EMAIL;TYPE=INTERNET:ada@example.com
            END:VCARD
        """.trimIndent()

        val contact = assertNotNull(VCardParser.parseFirst(card))

        assertEquals("Ada Vance", contact.displayName)
        assertEquals("Ada", contact.givenName)
        assertEquals("Vance", contact.surname)
        assertEquals("Homebase", contact.organization)
        assertEquals(listOf("+1 (415) 555-0123"), contact.phones)
        assertEquals(listOf("ada@example.com"), contact.emails)
    }

    @Test
    fun `takes the first block of a multi-contact vcf and does not crash on the rest`() {
        val cards = """
            BEGIN:VCARD
            VERSION:3.0
            FN:First Person
            TEL:+14155550001
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Second Person
            TEL:+14155550002
            END:VCARD
        """.trimIndent()

        val all = VCardParser.parse(cards)

        assertEquals(2, all.size, "Both blocks must parse — a multi-contact file must not crash.")
        assertEquals("First Person", VCardParser.parseFirst(cards)?.displayName)
    }

    @Test
    fun `malformed input yields nothing so the caller falls back to the raw file`() {
        assertFalse(VCardParser.looksLikeVCard("just some shared text"))
        assertNull(VCardParser.parseFirst("just some shared text"))
        // Has the marker but no renderable property — still nothing to build a card from.
        assertNull(VCardParser.parseFirst("BEGIN:VCARD\nVERSION:2.1\nEND:VCARD"))
        assertNull(VCardParser.parseFirst(""))
    }

    @Test
    fun `keeps a non-E164 legacy phone verbatim rather than dropping it`() {
        val card = """
            BEGIN:VCARD
            VERSION:2.1
            FN:Legacy Larry
            TEL;HOME:0207 946 0018
            END:VCARD
        """.trimIndent()

        val contact = assertNotNull(VCardParser.parseFirst(card))

        assertEquals(listOf("0207 946 0018"), contact.phones)
    }

    @Test
    fun `an emoji in the name survives intact`() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Zoë 🚀 Nakamura
            END:VCARD
        """.trimIndent()

        val contact = assertNotNull(VCardParser.parseFirst(card))

        assertEquals("Zoë 🚀 Nakamura", contact.displayName)
    }

    @Test
    fun `capping a long name never splits a surrogate pair`() {
        // 80 ASCII chars = the cap, then an emoji: a naive substring(0, 81) would keep the
        // high surrogate and drop the low one, producing an unrenderable lone surrogate.
        val longName = "a".repeat(79) + "🚀" + "tail"
        val card = "BEGIN:VCARD\nVERSION:3.0\nFN:$longName\nEND:VCARD"

        val name = assertNotNull(VCardParser.parseFirst(card)).displayName

        assertEquals(79 + 2, name.length, "The emoji must be kept whole (2 UTF-16 units).")
        assertFalse(name.last().isHighSurrogate(), "A lone high surrogate would break rendering.")
        assertTrue(name.endsWith("🚀"))
    }

    @Test
    fun `unfolds continuation lines and decodes value escapes`() {
        // RFC 6350 folding: the leading space continues the previous line. The ORG value
        // escapes a comma, and the second ORG component is a department we ignore.
        val card =
            "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "FN:Bartholomew Featherstonehaugh\r\n" +
                " -Smythe\r\n" +
                "ORG:Acme\\, Inc.;R&D\r\n" +
                "END:VCARD"

        val contact = assertNotNull(VCardParser.parseFirst(card))

        assertEquals("Bartholomew Featherstonehaugh-Smythe", contact.displayName)
        assertEquals("Acme, Inc.", contact.organization)
    }

    @Test
    fun `strips the apple group prefix and de-duplicates repeated values`() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Grouped Gina
            item1.TEL;type=pref:+14155550123
            item2.TEL:+14155550123
            item3.EMAIL;type=INTERNET:gina@example.com
            END:VCARD
        """.trimIndent()

        val contact = assertNotNull(VCardParser.parseFirst(card))

        assertEquals(listOf("+14155550123"), contact.phones)
        assertEquals(listOf("gina@example.com"), contact.emails)
    }

    @Test
    fun `descriptor round-trips through the message content parser`() {
        val descriptor = ContactCardDescriptor(
            displayName = "Ada Vance",
            givenName = "Ada",
            surname = "Vance",
            organization = "Homebase",
            phones = listOf("+14155550123"),
            emails = listOf("ada@example.com"),
        )

        val json = MessageContentParser.serialize(MessageContent.ContactCard(descriptor))
        val parsed = MessageContentParser.parse(ChatProtocol.ChatContactCardMessageDataType, json)

        assertEquals(MessageContent.ContactCard(descriptor), parsed)
    }

    @Test
    fun `a broken descriptor parses to a null-descriptor card, never to null`() {
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatContactCardMessageDataType,
            """{"displayName":}""",
        )

        assertEquals(MessageContent.ContactCard(null), parsed)
        assertEquals(
            MessageContent.UNPARSEABLE_CONTACT_LABEL,
            (parsed as MessageContent.ContactCard).displayLabel,
        )
    }

    @Test
    fun `an empty descriptor fails validation and renders the chip`() {
        val json = """{"displayName":"","phones":[],"emails":[]}"""

        val parsed = MessageContentParser.parse(ChatProtocol.ChatContactCardMessageDataType, json)

        assertEquals(MessageContent.ContactCard(null), parsed)
    }

    @Test
    fun `the notification label never carries the contact's name`() {
        val content = MessageContent.ContactCard(
            ContactCardDescriptor(displayName = "Ada Vance", phones = listOf("+14155550123")),
        )

        assertEquals("Ada Vance", content.displayLabel)
        assertEquals(
            MessageContent.UNPARSEABLE_CONTACT_LABEL,
            content.notificationLabel,
            "A shared contact's name is third-party PII and must not reach the push provider.",
        )
    }
}
