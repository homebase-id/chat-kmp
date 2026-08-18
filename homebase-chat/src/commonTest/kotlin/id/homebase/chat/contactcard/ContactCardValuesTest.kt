package id.homebase.chat.contactcard

import id.homebase.api.common.OdinId
import id.homebase.chat.services.content.MessageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // One more phone than fits, so the email is only kept if ordering fails to push it out.
        val bubble = card(
            phones = List(ContactCardBubbleRowLimit + 1) { "+1415555012$it" },
            emails = listOf("ada@example.com"),
        ).bubbleValues()

        assertEquals(
            List(ContactCardBubbleRowLimit) { ContactValueKind.Phone },
            bubble.rows.map { it.kind },
        )
        assertEquals(2, bubble.hiddenCount)
    }

    @Test
    fun `a nameless card does not repeat the value it borrowed as its title`() {
        val descriptor = card(displayName = "", phones = listOf("+14155550123"))

        assertEquals("+14155550123", descriptor.summaryLine())
        assertEquals(emptyList(), descriptor.bubbleValues().rows)
        assertEquals(0, descriptor.bubbleValues().hiddenCount)
        // allValues() is what the detail dialog renders, so this is the assertion that the row
        // — and with it Call, Message and Copy — survives where the actions live.
        assertEquals(1, descriptor.allValues().size)
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

    // RFC 6068 makes ',' a recipient separator in the to component, and Outlook-family clients
    // read ';' the same way — so leaving either intact silently CCs whoever the card names.
    @Test
    fun `an address cannot smuggle in a second recipient`() {
        val target = "victim@example.com,attacker@evil.tld".mailtoTarget()

        assertFalse(target.contains(','), "A literal , would address the second recipient too.")
        assertEquals("victim@example.com%2Cattacker@evil.tld", target)

        val semicolon = "victim@example.com;attacker@evil.tld".mailtoTarget()
        assertFalse(semicolon.contains(';'))
    }

    // RFC 5724 gives sms: a comma-separated recipient list; tel: does not, and there ',' is a
    // DTMF pause worth keeping.
    @Test
    fun `sms drops the recipient separator that tel keeps`() {
        assertEquals("+15551234", "+1555,1234".smsTarget())
        assertEquals("+1555,1234", "+1555,1234".telTarget())
    }

    // RFC 3966: bare '#' is the fragment delimiter, so the dialer stops reading there.
    @Test
    fun `a hash in a number is escaped rather than truncating it`() {
        assertEquals("*21%23", "*21#".telTarget())
        assertEquals("*21%23", "*21#".smsTarget())
    }

    @Test
    fun `an address that cannot be mailed is not offered as one`() {
        assertTrue("ada@example.com".looksLikeEmail())
        assertTrue(" ada@example.co.uk ".looksLikeEmail())

        assertFalse("ada at example.com".looksLikeEmail())
        assertFalse("ada@example".looksLikeEmail())
        assertFalse("@example.com".looksLikeEmail())
        assertFalse("ada@ex.".looksLikeEmail())
        assertFalse("ada@a@b.com".looksLikeEmail())
        assertFalse("ada @example.com".looksLikeEmail())
    }

    // `identity()` is a parser, not a gate: a bare hostname is exactly what a well-formed odinId
    // looks like, so an attacker's own domain parses fine. What stops the fetch is
    // [avatarIdentity]'s author check, pinned below — this only fixes the shape.
    @Test
    fun `identity parses a hostname and rejects everything that is not one`() {
        assertNull(card().copy(odinId = "not a domain").identity())
        assertNull(card().copy(odinId = "https://evil.example.com/pub/image").identity())
        assertNull(card().copy(odinId = "").identity())
        assertNull(card().copy(odinId = "   ").identity())

        assertEquals(
            "samwise.gamgee.demo.rocks",
            assertNotNull(card().copy(odinId = " samwise.gamgee.demo.rocks ").identity()).domainName,
        )
        // The payload a syntax check cannot catch, spelled out so nobody mistakes the above for one.
        assertNotNull(card().copy(odinId = "tracker.evil.tld").identity())
    }

    // Rendering the avatar dials the identity's host, so a card someone else sent naming a third
    // party is a read receipt they get for free — the case that must stay shut.
    @Test
    fun `a card someone else sent does not fetch a third party's avatar`() {
        val card = card().copy(odinId = "samwise.gamgee.demo.rocks")

        assertNull(
            card.avatarIdentity(author = "tracker.evil.tld"),
            "A card may name any identity; fetching it would beacon a third party.",
        )
        assertNull(card.avatarIdentity(author = null), "No author means no way to vouch for it.")
        assertNull(card.avatarIdentity(author = ""))
        assertNull(card().copy(odinId = "").avatarIdentity(author = "samwise.gamgee.demo.rocks"))
    }

    @Test
    fun `a card sent by its own subject fetches, because they already know it arrived`() {
        val card = card().copy(odinId = "samwise.gamgee.demo.rocks")

        assertEquals(
            "samwise.gamgee.demo.rocks",
            assertNotNull(card.avatarIdentity(author = "samwise.gamgee.demo.rocks")).domainName,
        )
        // Hosts are case-insensitive, and the envelope's casing is not the card author's to match.
        assertEquals(
            "samwise.gamgee.demo.rocks",
            assertNotNull(card.avatarIdentity(author = " Samwise.Gamgee.Demo.Rocks ")).domainName,
        )
    }

    @Test
    fun `a card naming someone in your book fetches whoever sent it`() {
        val card = card().copy(odinId = "todd.mitchell.demo.rocks")
        val book = setOf(OdinId("todd.mitchell.demo.rocks"))

        assertEquals(
            "todd.mitchell.demo.rocks",
            assertNotNull(card.avatarIdentity(author = "me.demo.rocks", savedContacts = book))
                .domainName,
        )
        assertNull(
            card.avatarIdentity(author = "me.demo.rocks", savedContacts = emptySet()),
            "Off your book, the same card must not resolve however it got here.",
        )
        assertNull(
            card().copy(odinId = "not a domain")
                .avatarIdentity(author = null, savedContacts = book),
            "Having them in your book does not make a non-identity into a host.",
        )
    }

    @Test
    fun `an over-long identity makes the whole card unrenderable rather than truncating the host`() {
        val tooLong = "a".repeat(ContactCardDescriptor.MAX_VALUE_CODEPOINTS + 1) + ".example.com"

        assertFalse(card().copy(odinId = tooLong).isValid())
    }

    // The card is authored remotely: *21*<number># is a call-forwarding MMI, and a phone number
    // never starts with * or #.
    @Test
    fun `a control code is not offered as a callable number`() {
        assertTrue("*21*+15555550000#".isControlCode())
        assertTrue("#31#+15555550000".isControlCode())
        assertFalse("+14155550123".isControlCode())
        // Mid-number # is a legitimate extension terminator and still escapes rather than truncating.
        assertFalse("+14155550123,,99#".isControlCode())
        assertEquals("+14155550123,,99%23", "+14155550123,,99#".telTarget())
    }

    @Test
    fun `a nameless card is titled by its identity, and does not repeat it as a row`() {
        val card = ContactCardDescriptor(displayName = "", odinId = "samwise.gamgee.demo.rocks")

        assertEquals("samwise.gamgee.demo.rocks", card.summaryLine())
        assertTrue(
            card.bubbleValues().rows.none { it.kind == ContactValueKind.Identity },
            "In the compact bubble the title already shows it.",
        )
        // But the detail dialog must still get it: it carries Open profile and Copy, and this card
        // has nothing else at all.
        assertEquals(
            listOf(ContactValueKind.Identity),
            card.allValues().map { it.kind },
        )
    }

    @Test
    fun `a card carrying only a structured name is not titled by its phone number`() {
        val card = ContactCardDescriptor(
            displayName = "",
            givenName = "Ada",
            surname = "Vance",
            phones = listOf("+14155550123"),
        )

        assertEquals("Ada Vance", card.summaryLine())
        assertEquals("AV", card.avatarInitials())
        assertEquals(
            listOf("+14155550123"),
            card.bubbleValues().rows.map { it.value },
            "The name is its own title, so the phone stays a row.",
        )
    }

    @Test
    fun `an identity leads the values, ahead of phones and emails`() {
        val card = ContactCardDescriptor(
            displayName = "Todd",
            odinId = "samwise.gamgee.demo.rocks",
            phones = listOf("+14155550123"),
            emails = listOf("todd@example.com"),
        )

        assertEquals(
            listOf(ContactValueKind.Identity, ContactValueKind.Phone, ContactValueKind.Email),
            card.allValues().map { it.kind },
        )
    }

    // ContactBookEntry.displayName is resolved *from* the odinId when a connection has no profile
    // name, so both fields are set and identical — a field-presence check would keep the row and
    // print the same line twice.
    @Test
    fun `a card whose name was resolved from its identity does not print it twice`() {
        val card = ContactCardDescriptor(
            displayName = "samwise.gamgee.demo.rocks",
            odinId = "samwise.gamgee.demo.rocks",
        )

        assertEquals("samwise.gamgee.demo.rocks", card.summaryLine())
        assertEquals(emptyList(), card.bubbleValues().rows)
    }

    @Test
    fun `a phone-named card does not print its number twice either`() {
        val card = ContactCardDescriptor(
            displayName = "+14155550123",
            phones = listOf("+14155550123"),
        )

        assertEquals(emptyList(), card.bubbleValues().rows)
    }

    @Test
    fun `a real name is kept alongside its values`() {
        val card = ContactCardDescriptor(
            displayName = "Todd Mitchell",
            odinId = "samwise.gamgee.demo.rocks",
            phones = listOf("+14155550123"),
        )

        assertEquals(
            listOf(ContactValueKind.Identity, ContactValueKind.Phone),
            card.bubbleValues().rows.map { it.kind },
            "Nothing was borrowed for the title, so nothing is dropped.",
        )
    }

    @Test
    fun `a control code hiding behind a pause or a plus is still refused`() {
        assertTrue(",*21*15555550000#".isControlCode())
        assertTrue("+*21*15555550000#".isControlCode())
        assertTrue(",,#31#15555550000".isControlCode())
        assertFalse("+14155550123".isControlCode())
        assertFalse("+14155550123,,99#".isControlCode())
    }

    // FN is arbitrary text a vCard exporter chooses; when it equals the email rather than the first
    // value, a position-based drop leaves the title printed twice.
    @Test
    fun `a title matching a value that is not the first is still not repeated`() {
        val card = ContactCardDescriptor(
            displayName = "ada@example.com",
            phones = listOf("+14155550123"),
            emails = listOf("ada@example.com"),
        )

        assertEquals("ada@example.com", card.summaryLine())
        assertEquals(
            listOf("+14155550123"),
            card.bubbleValues().rows.map { it.value },
        )
        assertEquals(0, card.bubbleValues().hiddenCount)
    }

    @Test
    fun `the title is matched without regard to case`() {
        val card = ContactCardDescriptor(
            displayName = "Ada@Example.com",
            emails = listOf("ada@example.com"),
        )

        assertEquals(emptyList(), card.bubbleValues().rows)
        assertEquals(
            "",
            ContactCardDescriptor(displayName = "acme", organization = "Acme").subtitleLine(),
            "The subtitle must use the same comparison as the row drop, or both render.",
        )
    }

    @Test
    fun `a bidi override in a name never reaches the title`() {
        val hostile = card(displayName = "Ada\u202E moc.live")

        assertEquals("Ada moc.live", hostile.summaryLine())
    }

    @Test
    fun `zero-width and control characters are dropped from rendered values`() {
        val hostile = card(
            displayName = "Ada",
            phones = listOf("+1415\u200B5550123"),
            emails = listOf("ada\u0000@example.com"),
        )

        assertEquals(listOf("+14155550123"), hostile.renderablePhones())
        assertEquals(listOf("ada@example.com"), hostile.renderableEmails())
    }

    @Test
    fun `ordinary text is untouched by the scrub`() {
        assertEquals("Ada Lovelace \u00C9amonn \uD83C\uDF89", "Ada Lovelace \u00C9amonn \uD83C\uDF89".scrubbed())
    }

    // The bubble's avatar gate stays; saving is deliberately not gated.
    @Test
    fun `the avatar of a card someone else sent is not fetched`() {
        val hostile = card().copy(odinId = "tracker.evil.tld")

        assertNull(hostile.avatarIdentity(author = "friend.demo.rocks"))
        assertNull(hostile.avatarIdentity(author = null))
    }

    @Test
    fun `the avatar is fetched for an identity the envelope vouches for`() {
        val own = card().copy(odinId = "samwise.gamgee.demo.rocks")

        assertEquals(
            "samwise.gamgee.demo.rocks",
            own.avatarIdentity(author = "samwise.gamgee.demo.rocks")?.domainName,
        )
        assertEquals(
            "samwise.gamgee.demo.rocks",
            own.avatarIdentity(
                author = null,
                savedContacts = setOf(OdinId("samwise.gamgee.demo.rocks")),
            )?.domainName,
        )
    }

    @Test
    fun `an unattested identity still reaches the editor, so nothing is dropped silently`() {
        val hostile = card(phones = listOf("+14155550123"), emails = listOf("ada@example.com"))
            .copy(odinId = "tracker.evil.tld")

        assertEquals("tracker.evil.tld", hostile.identity()?.domainName)
    }

    @Test
    fun `a garbage identity never reaches the editor claiming to be one`() {
        assertNull(card().copy(odinId = "not a host!!").identity())
        assertNull(card().copy(odinId = "   ").identity())
    }
}
