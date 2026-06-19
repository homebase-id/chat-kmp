@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The fixed, server-shaped domain model for a contact, produced by [ContactRepository].
 *
 * [content] is the **literal** V2 server model ([ContactContent]) — the same type used to write —
 * so read and write are symmetric and there's no flatten/unflatten step to drift. The rest is the
 * file envelope the content rides on ([uniqueId], [versionTag]) plus the avatar payload reference
 * ([image]); the image is a drive payload, not part of [content].
 *
 * This is intentionally NOT a UI model. Consumers (the contact book, chat people-pickers, …) map
 * a [Contact] into whatever shape they need; pure-data helpers like
 * [ContactName.resolveDisplayName]/[ContactName.initials] are shared rather than baked in here.
 */
data class Contact(
    val uniqueId: Uuid,
    val versionTag: Uuid?,
    val content: ContactContent,
    val image: ContactImageRef? = null,
)

/** Everything needed to render a contact's stored avatar (`prfl_pic`) without a second drive read. */
data class ContactImageRef(
    val driveId: Uuid,
    val fileId: Uuid,
    val payload: PayloadDescriptor,
    val previewThumbnail: EmbeddedThumb?,
    val keyHeader: KeyHeader?,
    val isEncrypted: Boolean,
)

/**
 * Parses a Contacts-drive [HomebaseFile] into a [Contact], or null when there's no usable header
 * content (missing `uniqueId`/`content`, or unparseable JSON). The V2 controller always embeds the
 * content in the header (it never spills to a payload), so a null here means a malformed or
 * non-contact file — skip it. Unlike the old UI mappers this does NOT derive a display name or drop
 * odinId-less contacts; that's the consumer's choice.
 */
fun HomebaseFile.toContact(): Contact? {
    val uniqueId = fileMetadata.appData.uniqueId ?: return null
    val contentJson = fileMetadata.appData.content ?: return null
    val content = runCatching {
        OdinSystemSerializer.deserialize<ContactContent>(contentJson)
    }.getOrNull() ?: return null

    val image = fileMetadata.payloads
        ?.firstOrNull { it.key == ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY }
        ?.let { payload ->
            ContactImageRef(
                driveId = driveId,
                fileId = fileId,
                payload = payload,
                previewThumbnail = fileMetadata.appData.previewThumbnail,
                keyHeader = keyHeader,
                isEncrypted = fileMetadata.isEncrypted,
            )
        }

    return Contact(
        uniqueId = uniqueId,
        versionTag = fileMetadata.versionTag,
        content = content,
        image = image,
    )
}
