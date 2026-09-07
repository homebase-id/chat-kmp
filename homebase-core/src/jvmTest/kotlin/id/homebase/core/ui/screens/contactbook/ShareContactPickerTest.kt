@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.util.codePointCount
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The outbound half of the contact card: a contact book row picked inside a chat becomes the
 * same wire descriptor the inbound vCard share produces.
 */
class ShareContactPickerTest {

    private fun entry(
        displayName: String = "Ada Vance",
        givenName: String? = "Ada",
        surname: String? = "Vance",
        phone: String? = null,
        additionalPhones: List<String> = emptyList(),
        email: String? = null,
        additionalEmails: List<String> = emptyList(),
        odinId: String? = null,
        imagePayload: PayloadDescriptor? = null,
    ) = ContactBookEntry(
        uniqueId = Uuid.random(),
        fileId = Uuid.random(),
        versionTag = null,
        odinId = odinId,
        displayName = displayName,
        givenName = givenName,
        surname = surname,
        phone = phone,
        email = email,
        additionalPhones = additionalPhones,
        additionalEmails = additionalEmails,
        imagePayload = imagePayload,
    )

    private fun review(entry: ContactBookEntry): ContactCardReview =
        ContactCardReview.from(entry, assertNotNull(ContactCardImport.toDescriptor(entry)))

    private fun ContactCardReview.toggleValue(value: String): ContactCardReview =
        toggle(fields.indexOfFirst { it.value == value })

    @Test
    fun `collapses the primary and additional slots into one list each`() {
        val descriptor = assertNotNull(
            ContactCardImport.toDescriptor(
                entry(
                    phone = "+14155550123",
                    additionalPhones = listOf("+14155550124", "+14155550125"),
                    email = "ada@example.com",
                    additionalEmails = listOf("ada.vance@work.example"),
                )
            )
        )

        assertEquals(
            listOf("+14155550123", "+14155550124", "+14155550125"),
            descriptor.phones,
            "Primary first, then the additional slots in order.",
        )
        assertEquals(listOf("ada@example.com", "ada.vance@work.example"), descriptor.emails)
        assertEquals("Ada Vance", descriptor.displayName)
        assertEquals("Ada", descriptor.givenName)
        assertEquals("Vance", descriptor.surname)
    }

    @Test
    fun `normalizes a formatted number to E164 on the way out`() {
        val descriptor = assertNotNull(
            ContactCardImport.toDescriptor(entry(phone = "+1 (415) 555-0123"))
        )

        assertEquals(listOf("+14155550123"), descriptor.phones)
        assertTrue(ContactFieldValidation.isValidPhone(descriptor.phones.first()))
    }

    @Test
    fun `a legacy non-E164 number is carried verbatim rather than dropped`() {
        val descriptor = assertNotNull(
            ContactCardImport.toDescriptor(entry(phone = "0207 946 0018"))
        )

        assertEquals(listOf("02079460018"), descriptor.phones)
        assertFalse(ContactFieldValidation.isValidPhone(descriptor.phones.first()))
    }

    @Test
    fun `duplicate and blank slots collapse away`() {
        val descriptor = assertNotNull(
            ContactCardImport.toDescriptor(
                entry(
                    phone = "+14155550123",
                    additionalPhones = listOf("+1 415 555 0123", "   ", "+14155550124"),
                    email = "ada@example.com",
                    additionalEmails = listOf("ada@example.com", ""),
                )
            )
        )

        assertEquals(listOf("+14155550123", "+14155550124"), descriptor.phones)
        assertEquals(listOf("ada@example.com"), descriptor.emails)
    }

    @Test
    fun `values are capped at MAX_VALUES_PER_KIND`() {
        val many = (1..(ContactCardDescriptor.MAX_VALUES_PER_KIND + 5))
            .map { "+1415555%04d".format(it) }
        val descriptor = assertNotNull(
            ContactCardImport.toDescriptor(
                entry(
                    phone = many.first(),
                    additionalPhones = many.drop(1),
                    additionalEmails = (1..(ContactCardDescriptor.MAX_VALUES_PER_KIND + 3))
                        .map { "user$it@example.com" },
                )
            )
        )

        assertEquals(ContactCardDescriptor.MAX_VALUES_PER_KIND, descriptor.phones.size)
        assertEquals(ContactCardDescriptor.MAX_VALUES_PER_KIND, descriptor.emails.size)
        assertTrue(descriptor.isValid())
    }

    @Test
    fun `an over-long name is truncated rather than rejected`() {
        val long = "Ada".repeat(ContactCardDescriptor.MAX_NAME_CODEPOINTS)
        val descriptor = assertNotNull(
            ContactCardImport.toDescriptor(
                entry(displayName = long, givenName = long, surname = long, phone = "+14155550123")
            )
        )

        assertEquals(ContactCardDescriptor.MAX_NAME_CODEPOINTS, descriptor.displayName.length)
        assertTrue(descriptor.isValid())
    }

    @Test
    fun `an entry with nothing shareable produces no descriptor`() {
        assertNull(ContactCardImport.toDescriptor(entry(displayName = "", givenName = null, surname = null)))
    }

    @Test
    fun `organization is carried when the entry has one, and blank when it does not`() {
        val plain = assertNotNull(ContactCardImport.toDescriptor(entry(phone = "+14155550123")))
        assertEquals("", plain.organization)

        // It lives only in the override blob, so the picker must apply that before building a card.
        val withOrg = entry(phone = "+14155550123")
            .withOverride(ContactFieldOverlay(organization = "Vance Labs"))

        assertEquals("Vance Labs", assertNotNull(ContactCardImport.toDescriptor(withOrg)).organization)
    }

    @Test
    fun `an override's extras and edited primary all reach the descriptor`() {
        val overridden = entry(phone = "+14155550123", email = "ada@example.com").withOverride(
            ContactFieldOverlay(
                phone = "+14155550999",
                additionalPhones = listOf("+14155550124"),
                additionalEmails = listOf("ada.vance@work.example"),
            ),
        )

        val descriptor = assertNotNull(shareContactCandidates(listOf(overridden), "").single().descriptor)

        assertEquals(listOf("+14155550999", "+14155550124"), descriptor.phones)
        assertEquals(listOf("ada@example.com", "ada.vance@work.example"), descriptor.emails)
    }

    @Test
    fun `a contact whose only values are additional is shareable`() {
        val overridden = entry(displayName = "", givenName = null, surname = null)
            .withOverride(ContactFieldOverlay(additionalPhones = listOf("+14155550124")))

        assertTrue(shareContactCandidates(listOf(overridden), "").single().shareable)
    }

    @Test
    fun `a name-only entry is shareable`() {
        // displayName alone satisfies isValid() — the receiver still gets someone to save.
        val descriptor = assertNotNull(ContactCardImport.toDescriptor(entry()))

        assertEquals("Ada Vance", descriptor.displayName)
        assertTrue(descriptor.phones.isEmpty())
    }

    @Test
    fun `candidates are name-sorted and carry the descriptor they would send`() {
        val candidates = shareContactCandidates(
            entries = listOf(
                entry(displayName = "Zoe Nakamura", phone = "+819012345678"),
                entry(displayName = "ada vance", phone = "+14155550123"),
            ),
            query = "",
        )

        assertEquals(listOf("ada vance", "Zoe Nakamura"), candidates.map { it.entry.displayName })
        assertTrue(candidates.all { it.shareable })
        assertEquals(listOf("+14155550123"), candidates.first().descriptor?.phones)
    }

    @Test
    fun `an unshareable contact is listed but marked, not hidden`() {
        val candidates = shareContactCandidates(
            entries = listOf(entry(displayName = "", givenName = null, surname = null)),
            query = "",
        )

        assertEquals(1, candidates.size, "It must stay visible so it doesn't read as missing.")
        assertFalse(candidates.single().shareable)
        assertNull(candidates.single().descriptor)
    }

    @Test
    fun `the query filters on name, phone, email and odinId`() {
        val entries = listOf(
            entry(displayName = "Ada Vance", phone = "+14155550123", odinId = "ada.example.com"),
            entry(displayName = "Zoe Nakamura", email = "zoe@example.com"),
        )

        assertEquals(1, shareContactCandidates(entries, "ada").size)
        assertEquals(1, shareContactCandidates(entries, "zoe@example").size)
        assertEquals(1, shareContactCandidates(entries, "4155550123").size)
        assertEquals(1, shareContactCandidates(entries, "ada.example.com").size)
        assertEquals(2, shareContactCandidates(entries, "").size)
        assertEquals(0, shareContactCandidates(entries, "nobody").size)
    }

    @Test
    fun `a review with everything checked sends exactly what the picker would have sent`() {
        val entry = entry(
            phone = "+14155550123",
            email = "ada@example.com",
            odinId = "ada.example.com",
        ).withOverride(
            ContactFieldOverlay(
                organization = "Vance Labs",
                additionalPhones = listOf("+14155550124"),
            ),
        )
        val asPickedToday = assertNotNull(ContactCardImport.toDescriptor(entry))

        assertEquals(asPickedToday, review(entry).toDescriptor())
    }

    @Test
    fun `deselecting one phone drops only that value and keeps the rest in order`() {
        val entry = entry(
            phone = "+14155550123",
            additionalPhones = listOf("+14155550124", "+14155550125"),
        )

        val descriptor = review(entry).toggleValue("+14155550124").toDescriptor()

        assertEquals(listOf("+14155550123", "+14155550125"), descriptor.phones)
    }

    @Test
    fun `deselecting the organization and one email clears just those`() {
        val entry = entry(phone = "+14155550123", email = "ada@example.com").withOverride(
            ContactFieldOverlay(
                organization = "Vance Labs",
                additionalEmails = listOf("ada.vance@work.example"),
            ),
        )

        val descriptor = review(entry)
            .toggleValue("Vance Labs")
            .toggleValue("ada@example.com")
            .toDescriptor()

        assertEquals("", descriptor.organization)
        assertEquals(listOf("ada.vance@work.example"), descriptor.emails)
        assertEquals(listOf("+14155550123"), descriptor.phones)
    }

    @Test
    fun `deselecting the identity drops it from the card`() {
        val entry = entry(phone = "+14155550123", odinId = "ada.example.com")

        val descriptor = review(entry).toggleValue("ada.example.com").toDescriptor()

        assertEquals("", descriptor.odinId)
        assertNull(descriptor.identity())
    }

    @Test
    fun `nothing checked cannot be sent`() {
        val entry = entry(phone = "+14155550123", email = "ada@example.com")
        val nothing = review(entry).let { start ->
            start.fields.indices.fold(start) { acc, index -> acc.toggle(index) }
        }

        assertEquals(0, nothing.selectedCount)
        assertFalse(nothing.canSend, "A card with no values renders as bare initials on the receiver.")
        assertTrue(review(entry).canSend)
    }

    @Test
    fun `a name-only contact stays sendable because there is nothing to deselect`() {
        val review = review(entry())

        assertTrue(review.fields.isEmpty())
        assertTrue(review.canSend)
    }

    @Test
    fun `an edited name reaches the card and leaves the contact book row alone`() {
        val entry = entry(phone = "+14155550123")
        val descriptor = review(entry).copy(displayName = "Mum").toDescriptor()

        assertEquals("Mum", descriptor.displayName)
        // Otherwise the receiver saves "Ada Vance" from the structured name and the rename is lost.
        assertEquals("", descriptor.givenName)
        assertEquals("", descriptor.surname)
        assertEquals("Ada Vance", entry.displayName)
        assertEquals("Ada", entry.givenName)
    }

    @Test
    fun `an emoji name over the cap truncates on a code point boundary`() {
        val entry = entry(phone = "+14155550123")
        val long = "\uD83D\uDE00".repeat(ContactCardDescriptor.MAX_NAME_CODEPOINTS + 20)

        val descriptor = review(entry).copy(displayName = long).toDescriptor()

        assertEquals(ContactCardDescriptor.MAX_NAME_CODEPOINTS, descriptor.displayName.codePointCount())
        assertEquals("\uD83D\uDE00".repeat(ContactCardDescriptor.MAX_NAME_CODEPOINTS), descriptor.displayName)
        assertFalse(descriptor.displayName.last().isSurrogate() && descriptor.displayName.last().isHighSurrogate())
        assertTrue(descriptor.isValid())
    }

    @Test
    fun `the photo rides along by default and can be left off`() {
        val withPhoto = entry(phone = "+14155550123", imagePayload = PayloadDescriptor(key = "prfl_pic"))
        val review = review(withPhoto)

        assertTrue(review.hasPhoto)
        assertTrue(review.includePhoto)
        assertFalse(review.copy(includePhoto = false).includePhoto)
        assertFalse(review(entry(phone = "+14155550123")).hasPhoto)
    }
}
