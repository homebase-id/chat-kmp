@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
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
    )

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
    fun `organization stays blank because ContactBookEntry has no such field`() {
        val descriptor = assertNotNull(ContactCardImport.toDescriptor(entry(phone = "+14155550123")))

        assertEquals("", descriptor.organization)
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
}
