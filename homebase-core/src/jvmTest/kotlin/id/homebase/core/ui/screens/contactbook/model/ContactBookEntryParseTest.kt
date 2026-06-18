@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.model

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.contacts.ContactBirthday
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactLocation
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 0 safety net (see CONTACT_STACK_CONSOLIDATION.md): pins the behavior of the contact-book
 * read path [HomebaseFile.toContactBookEntry] so the model/parse consolidation in later phases can
 * prove parity instead of guessing. Several cases document *current* behavior that motivates the
 * migration (the synced-contact "None" symptom, the spilled-body gap) rather than asserting it's
 * ideal.
 */
class ContactBookEntryParseTest {

    private fun fileFor(
        content: ContactContent?,
        uniqueId: Uuid? = Uuid.random(),
        payloads: List<PayloadDescriptor>? = null,
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(1_700_000_000_000L),
            isEncrypted = true,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = ContactsProvider.CONTACT_FILE_TYPE,
                content = content?.let { OdinSystemSerializer.serialize(it) },
            ),
            payloads = payloads,
            versionTag = Uuid.random(),
        ),
        serverMetadata = ServerMetadata(
            accessControlList = AccessControlList(requiredSecurityGroup = "owner"),
        ),
    )

    @Test
    fun fullContact_mapsEveryField() {
        val entry = fileFor(
            ContactContent(
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
            ),
        ).toContactBookEntry()

        assertNotNull(entry)
        assertEquals("sam.dotyou.cloud", entry.odinId)
        assertEquals("Sam Q. Public", entry.displayName)
        assertEquals("Sam", entry.givenName)
        assertEquals("Public", entry.surname)
        assertEquals("+1-555-0100", entry.phone)
        assertEquals("sam@dotyou.cloud", entry.email)
        assertEquals("Springfield", entry.city)
        assertEquals("US", entry.country)
        assertEquals("1990-01-01", entry.birthday)
        assertEquals("user", entry.source)
    }

    /**
     * The connection/profile-synced shape: only `displayName` is set; given/surname and all the
     * secondary fields are absent. This is exactly why the detail screen's "Contact details"
     * section (which renders only phone/email/location/birthday) shows "None" for such contacts —
     * the name lives in `displayName`. Pinned so the consolidation/normalization can change it
     * deliberately.
     */
    @Test
    fun syncedContact_displayNameOnly_hasNoSecondaryFields() {
        val entry = fileFor(
            ContactContent(
                odinId = "samwise.gamgee.demo.rocks",
                source = "public",
                name = ContactName(displayName = "Samwise Gamgee"),
            ),
        ).toContactBookEntry()

        assertNotNull(entry)
        assertEquals("Samwise Gamgee", entry.displayName)
        assertNull(entry.givenName)
        assertNull(entry.surname)
        assertNull(entry.phone)
        assertNull(entry.email)
        assertNull(entry.city)
        assertNull(entry.country)
        assertNull(entry.birthday)
    }

    @Test
    fun displayName_derivedFromGivenAndSurnameWhenAbsent() {
        val entry = fileFor(
            ContactContent(name = ContactName(givenName = "Frodo", surname = "Baggins")),
        ).toContactBookEntry()

        assertNotNull(entry)
        assertEquals("Frodo Baggins", entry.displayName)
    }

    @Test
    fun displayName_fallsBackToOdinIdThenPhoneThenEmail() {
        assertEquals(
            "merry.demo.rocks",
            fileFor(ContactContent(odinId = "merry.demo.rocks")).toContactBookEntry()?.displayName,
        )
        assertEquals(
            "+1-555-9999",
            fileFor(ContactContent(phone = ContactPhone(number = "+1-555-9999")))
                .toContactBookEntry()?.displayName,
        )
        assertEquals(
            "pippin@demo.rocks",
            fileFor(ContactContent(email = ContactEmail(email = "pippin@demo.rocks")))
                .toContactBookEntry()?.displayName,
        )
    }

    @Test
    fun imagePayload_detectedByProfilePicKey() {
        val entry = fileFor(
            content = ContactContent(name = ContactName(displayName = "Has Photo")),
            payloads = listOf(
                PayloadDescriptor(
                    key = ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY,
                    contentType = "image/jpeg",
                    bytesWritten = 1024L,
                ),
            ),
        ).toContactBookEntry()

        assertNotNull(entry)
        assertEquals(ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY, entry.imagePayload?.key)
    }

    @Test
    fun returnsNull_whenUniqueIdMissing() {
        assertNull(
            fileFor(ContactContent(name = ContactName(displayName = "x")), uniqueId = null)
                .toContactBookEntry(),
        )
    }

    /**
     * Header content absent. This is also the **spilled-body** case the chat write path can
     * produce (oversized contact body written to a `dflt_key` payload, header content null) — the
     * contact-book reader only reads header content, so such a contact maps to null. Pinned here
     * because Phase 2's unified parser must handle the spilled payload.
     */
    @Test
    fun returnsNull_whenHeaderContentMissing() {
        assertNull(fileFor(content = null).toContactBookEntry())
    }

    @Test
    fun returnsNull_whenContentIsInvalidJson() {
        val file = fileFor(ContactContent(name = ContactName(displayName = "x"))).let { f ->
            f.copy(
                fileMetadata = f.fileMetadata.copy(
                    appData = f.fileMetadata.appData.copy(content = "not valid json {{{"),
                ),
            )
        }
        assertNull(file.toContactBookEntry())
    }
}
