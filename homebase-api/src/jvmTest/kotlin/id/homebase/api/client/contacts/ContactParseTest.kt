@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins [HomebaseFile.toContact] — the single parser behind [ContactRepository]. It returns the
 * server model verbatim (no display-name derivation, no field flattening) plus the file envelope.
 */
class ContactParseTest {

    private val driveId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val versionTag = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun fileFor(
        content: ContactContent?,
        uniqueId: Uuid? = Uuid.parse("11111111-1111-1111-1111-111111111111"),
        payloads: List<PayloadDescriptor>? = null,
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.parse("99999999-9999-9999-9999-999999999999"),
        driveId = driveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        fileMetadata = FileMetadata(
            isEncrypted = true,
            versionTag = versionTag,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = ContactsProvider.CONTACT_FILE_TYPE,
                content = content?.let { OdinSystemSerializer.serialize(it) },
            ),
            payloads = payloads,
        ),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun fullContact_preservesServerContentVerbatim() {
        val content = ContactContent(
            odinId = "sam.dotyou.cloud",
            source = "user",
            name = ContactName(displayName = "Sam Q. Public", givenName = "Sam", surname = "Public"),
            location = ContactLocation(city = "Springfield", country = "US"),
            phone = ContactPhone(number = "+1-555-0100"),
            email = ContactEmail(email = "sam@dotyou.cloud"),
            birthday = ContactBirthday(date = "1990-01-01"),
        )

        val contact = fileFor(content).toContact()

        assertEquals(content, contact?.content)        // server model round-trips unchanged
        assertEquals(versionTag, contact?.versionTag)
        assertNull(contact?.image)
    }

    @Test
    fun syncedContact_displayNameOnly_keptAsIs() {
        // No display-name derivation here — that's a consumer concern.
        val content = ContactContent(
            odinId = "samwise.gamgee.demo.rocks",
            source = "public",
            name = ContactName(displayName = "Samwise Gamgee"),
        )
        val contact = fileFor(content).toContact()
        assertEquals("Samwise Gamgee", contact?.content?.name?.displayName)
        assertNull(contact?.content?.name?.givenName)
        assertNull(contact?.content?.phone)
    }

    @Test
    fun imagePayload_mappedToImageRef() {
        val contact = fileFor(
            content = ContactContent(name = ContactName(displayName = "Has Photo")),
            payloads = listOf(
                PayloadDescriptor(
                    key = ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY,
                    contentType = "image/jpeg",
                    bytesWritten = 1024L,
                ),
            ),
        ).toContact()

        assertEquals(ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY, contact?.image?.payload?.key)
        assertEquals(driveId, contact?.image?.driveId)
    }

    @Test
    fun noImagePayload_imageIsNull() {
        val contact = fileFor(ContactContent(name = ContactName(displayName = "No Photo"))).toContact()
        assertNull(contact?.image)
    }

    @Test
    fun returnsNull_whenUniqueIdMissing() {
        assertNull(fileFor(ContactContent(name = ContactName(displayName = "x")), uniqueId = null).toContact())
    }

    @Test
    fun returnsNull_whenContentMissing() {
        assertNull(fileFor(content = null).toContact())
    }

    @Test
    fun returnsNull_whenContentInvalidJson() {
        val file = fileFor(ContactContent(name = ContactName(displayName = "x"))).let { f ->
            f.copy(
                fileMetadata = f.fileMetadata.copy(
                    appData = f.fileMetadata.appData.copy(content = "not valid json {{{"),
                ),
            )
        }
        assertNull(file.toContact())
    }
}
