package id.homebase.chat.contactcard

import id.homebase.chat.services.content.MessageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the bubble is allowed to render. The cap is the load-bearing part: a card may legally
 * carry [ContactCardDescriptor.MAX_VALUES_PER_KIND] phones AND that many emails, and the bubble
 * used to render every one of them in an unbounded column.
 */
class ContactCardValuesTest {

    private fun card(
        displayName: String = "Ada Vance",
        organization: String = "",
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
    ) = ContactCardDescriptor(
        displayName = displayName,
        organization = organization,
        phones = phones,
        emails = emails,
    )

    @Test
    fun `a maximal card is capped in the bubble and the rest is deferred to the detail view`() {
        val phones = List(ContactCardDescriptor.MAX_VALUES_PER_KIND) { "+1415555" + (100 + it) }
        val emails = List(ContactCardDescriptor.MAX_VALUES_PER_KIND) { "ada$it@example.com" }
        val descriptor = card(phones = phones, emails = emails)

        assertTrue(descriptor.isValid(), "20 values is inside the wire limits — the cap is a UI concern.")

        val bubble = descriptor.bubbleValues()

        assertEquals(ContactCardBubbleRowLimit, bubble.rows.size)
        assertEquals(20 - ContactCardBubbleRowLimit, bubble.hiddenCount)
        assertEquals(20, descriptor.allValues().size, "The detail view still sees every value.")
    }

    @Test
    fun `a card that fits shows no overflow hint`() {
        val bubble = card(phones = listOf("+14155550123"), emails = listOf("ada@example.com")).bubbleValues()

        assertEquals(2, bubble.rows.size)
        assertEquals(0, bubble.hiddenCount)
    }

    @Test
    fun `phones come before emails so the rows the bubble keeps are the callable ones`() {
        val bubble = card(
            phones = listOf("+14155550123", "+14155550124"),
            emails = listOf("ada@example.com"),
        ).bubbleValues()

        assertEquals(
            listOf(ContactValueKind.Phone, ContactValueKind.Phone),
            bubble.rows.map { it.kind },
        )
        assertEquals(1, bubble.hiddenCount)
    }

    @Test
    fun `a nameless card does not repeat the value it borrowed as its title`() {
        val descriptor = card(displayName = "", phones = listOf("+14155550123"))

        assertEquals("+14155550123", descriptor.summaryLine())
        assertEquals(emptyList(), descriptor.bubbleValues().rows)
        assertEquals(0, descriptor.bubbleValues().hiddenCount)
        assertEquals(1, descriptor.allValues().size, "The detail view still lists it, actionably.")
    }

    // Fixtures with an organization carry a value too: isValid() needs a name, phone or email, and
    // ContactCardBubble returns the unparseable chip before any of these run, so an
    // organization-only card would pin a shape the bubble never reaches.
    private fun orgCard(organization: String) =
        card(displayName = "", organization = organization, phones = listOf("+14155550123"))

    @Test
    fun `an organization-only title is not repeated as the subtitle`() {
        val nameless = orgCard("Acme")

        assertTrue(nameless.isValid())
        assertEquals("Acme", nameless.summaryLine())
        assertEquals("", nameless.subtitleLine())
        assertEquals("Acme", card(displayName = "Ada Vance", organization = "Acme").subtitleLine())
    }

    @Test
    fun `initials come from the name, never from a phone number`() {
        assertEquals("AV", card().avatarInitials())
        assertEquals("A", orgCard("Acme").avatarInitials())
        assertEquals(
            "",
            card(displayName = "", phones = listOf("+14155550123")).avatarInitials(),
            "A phone-only card falls back to the icon rather than showing '+1'.",
        )
    }

    @Test
    fun `initials fall back to the organization when the card has no personal name`() {
        assertEquals("ZN", card(displayName = "Zoë Nakamura").avatarInitials())
        assertEquals("AC", orgCard("Acme Corp").avatarInitials())
    }

    @Test
    fun `an organization keeps the first value as a row rather than donating it to the title`() {
        val bubble = orgCard("Acme").bubbleValues()

        assertEquals(listOf("+14155550123"), bubble.rows.map { it.value })
    }

    @Test
    fun `an unparseable card carries no descriptor, so the bubble has nothing to act on`() {
        val content = MessageContent.ContactCard(null)

        assertEquals(null, content.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_CONTACT_LABEL, content.displayLabel)
    }

    @Test
    fun `a contact card states its own action surface instead of inheriting the locked-down default`() {
        val actions = MessageContent.ContactCard(card()).actions

        assertTrue(actions.allowReply, "\"is this the right number?\" is the point of the card.")
        assertTrue(actions.allowForward, "Passing a contact on is the canonical thing you do with one.")
        assertTrue(actions.allowInlineReactions)
        assertTrue(actions.allowReactionDetails, "Reactions are on, so who-reacted must be too.")
        assertFalse(actions.allowEdit, "You don't edit somebody else's details.")
        assertFalse(actions.allowShare, "The detail view copies each value on its own.")
    }

    @Test
    fun `a descriptor that fails validation is treated as unparseable`() {
        val overLong = ContactCardDescriptor(
            displayName = "a".repeat(ContactCardDescriptor.MAX_NAME_CODEPOINTS + 1),
        )
        val tooManyPhones = ContactCardDescriptor(
            displayName = "Ada Vance",
            phones = List(ContactCardDescriptor.MAX_VALUES_PER_KIND + 1) { "+1415555012$it" },
        )

        assertFalse(overLong.isValid())
        assertFalse(tooManyPhones.isValid())
    }

    @Test
    fun `tel targets strip formatting a legacy card may carry`() {
        assertEquals("+14155550123", "+1 (415) 555-0123".dialable())
        assertEquals("02079460018", "0207 946 0018".dialable())
        assertEquals("+14155550123,42", "+14155550123,42".dialable())
    }

    @Test
    fun `tel targets keep only ASCII digits, not every Unicode digit`() {
        // Char.isDigit() is true for these; a dialer cannot parse them.
        assertEquals("", "٠١٢٣٤٥٦٧٨٩".dialable(), "Arabic-Indic digits must not reach tel:.")
        assertEquals("", "०१२३४५६७८९".dialable(), "Devanagari digits must not reach tel:.")
        assertEquals("+1", "+١٤١٥1".dialable(), "A mixed number keeps only the ASCII part.")
        assertEquals("*#", "*#".dialable(), "DTMF separators survive.")
    }

    @Test
    fun `a number with no ASCII digit offers no call action`() {
        // dialable() would return "", and "tel:" with nothing after it is not a call. The detail
        // view drops the affordance rather than launching it — the same shape as Desktop, where
        // there is no dialer at all.
        val arabicIndic = card(phones = listOf("٠١٢٣٤٥٦٧٨٩")).phones.first()

        assertTrue(arabicIndic.dialable().isBlank())
        assertTrue("+1 (415) 555-0123".dialable().isNotBlank())
    }

    @Test
    fun `a blank value on a card from another client is not rendered as a row`() {
        val descriptor = ContactCardDescriptor(
            displayName = "Ada Vance",
            phones = listOf("", "+14155550123"),
            emails = listOf(" "),
        )

        assertTrue(descriptor.isValid(), "It is within the wire limits; rendering is our problem.")
        assertEquals(listOf("+14155550123"), descriptor.renderablePhones())
        assertEquals(emptyList(), descriptor.renderableEmails())
        assertEquals(1, descriptor.allValues().size)
    }

    @Test
    fun `a card whose only values are blank has no summary of its own`() {
        // Reached only from a client that shipped a blank TEL. summaryLine() returns "" so the UI
        // can substitute a localized label; it must never bake an English one into the model.
        val descriptor = ContactCardDescriptor(displayName = "", phones = listOf(""))

        assertEquals("", descriptor.summaryLine())
        assertEquals(
            MessageContent.UNPARSEABLE_CONTACT_LABEL,
            MessageContent.ContactCard(descriptor).displayLabel,
            "The conversation-list preview still has something to show.",
        )
    }

    @Test
    fun `a plain address survives the mailto encoding unchanged`() {
        assertEquals("ada@example.com", "ada@example.com".mailtoTarget())
        assertEquals("ada.v+chat@example.co.uk", "ada.v+chat@example.co.uk".mailtoTarget())
        assertEquals("ada@example.com", "  ada@example.com  ".mailtoTarget())
    }

    @Test
    fun `an address cannot inject mailto headers`() {
        // Unescaped, everything after the ? is parsed as mailto headers — subject, cc, even body.
        val injected = "victim@example.com?subject=Hi&body=Transfer%20now"

        val target = injected.mailtoTarget()

        assertFalse(target.contains('?'), "A literal ? would start the header section.")
        assertFalse(target.contains('&'), "A literal & would separate a second header.")
        assertEquals(
            "victim@example.com%3Fsubject%3DHi%26body%3DTransfer%2520now",
            target,
        )
    }

    @Test
    fun `a non-ASCII address is percent-encoded as UTF-8`() {
        assertEquals("z%C3%B6e@example.com", "zöe@example.com".mailtoTarget())
    }
}
