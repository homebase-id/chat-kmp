@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.model

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.contacts.ContactBirthday
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactLocation
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.contacts.resolveDisplayName
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.util.initials
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Origin markers stored in [ContactContent.source], round-tripped to the server. */
object ContactBookSource {
    const val CONNECTION = "public"   // synced from a Homebase identity profile
    const val IMPORTED = "contact"    // imported from the device address book
    const val MANUAL = "user"         // created by hand in the contact manager
}

/**
 * A contact in the contact-manager UI. Built from one file on the (mandatory)
 * Contacts drive by deserializing its header [ContactContent]. The drive is the
 * read source; writes go through the api-layer ContactsProvider (see
 * [id.homebase.core.ui.screens.contactbook.ContactBookService]).
 */
@Immutable
data class ContactBookEntry(
    val uniqueId: Uuid,
    val fileId: Uuid,
    val versionTag: Uuid?,
    val odinId: String? = null,
    val displayName: String,
    val givenName: String? = null,
    val additionalName: String? = null,
    val surname: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val city: String? = null,
    val country: String? = null,
    val birthday: String? = null,
    val source: String? = null,
    /** Pending (optimistic, not yet confirmed by the drive). */
    val isPending: Boolean = false,
    // Image-display fields (carried so a stored avatar can render without a
    // second drive read). Null when the contact has no uploaded photo.
    val driveId: Uuid? = null,
    val keyHeader: KeyHeader? = null,
    val isEncrypted: Boolean = false,
    val previewThumbnail: EmbeddedThumb? = null,
    val imagePayload: PayloadDescriptor? = null,
) {
    /** Has a Homebase identity behind it (vs a plain phone/email contact). */
    val hasOdinId: Boolean get() = !odinId.isNullOrBlank()

    /** Public avatar endpoint for identity contacts; null for plain contacts. */
    val avatarUrl: String? get() = odinId?.takeIf { it.isNotBlank() }?.let { "https://$it/pub/image" }

    val avatarInitials: String get() = displayName.initials()

    /** Case-insensitive key for A–Z grouping/sorting. */
    val sortKey: String get() = displayName.trim().lowercase()

    /** First non-blank section character (A–Z, else '#'). */
    val sectionKey: String
        get() {
            val c = displayName.trim().firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z') c.toString() else "#"
        }

    /** Secondary line under the name in list rows. */
    val subtitle: String? get() = odinId ?: phone ?: email

    val location: String?
        get() = listOfNotNull(city?.ifBlank { null }, country?.ifBlank { null })
            .joinToString(", ")
            .ifBlank { null }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return displayName.lowercase().contains(q) ||
            odinId?.lowercase()?.contains(q) == true ||
            phone?.lowercase()?.contains(q) == true ||
            email?.lowercase()?.contains(q) == true
    }

    /**
     * Builds the [HomebaseImageData] for this contact's stored avatar payload
     * (`prfl_pic`), or null if there is none / it isn't decodable yet. Mirrors
     * `VaultEntry.imageDataFor`: the IV is per-payload, the AES key is the file's.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun profileImageData(): HomebaseImageData? {
        val descriptor = imagePayload ?: return null
        val kh = keyHeader ?: return null
        val drive = driveId ?: return null
        val iv = descriptor.iv?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        return HomebaseImageData(
            driveId = drive,
            fileId = fileId,
            payloadKey = descriptor.key,
            previewThumbnail = previewThumbnail,
            loadFullPayload = false,
            isEncrypted = isEncrypted,
            lastModified = descriptor.lastModified,
            payloadContentType = descriptor.contentType,
            keyHeader = KeyHeader(iv = iv, aesKey = kh.aesKey),
        )
    }

    /** Rebuild the write payload from the current fields (used by edit + delete-merge). */
    fun toContactContent(): ContactContent = ContactContent(
        odinId = odinId?.ifBlank { null },
        name = ContactName(
            displayName = displayName.ifBlank { null },
            givenName = givenName?.ifBlank { null },
            additionalName = additionalName?.ifBlank { null },
            surname = surname?.ifBlank { null },
        ),
        source = source,
        location = if (city.isNullOrBlank() && country.isNullOrBlank()) null
        else ContactLocation(city = city?.ifBlank { null }, country = country?.ifBlank { null }),
        phone = phone?.ifBlank { null }?.let { ContactPhone(number = it) },
        email = email?.ifBlank { null }?.let { ContactEmail(email = it) },
        birthday = birthday?.ifBlank { null }?.let { ContactBirthday(date = it) },
    )
}

/**
 * Maps a Contacts-drive [HomebaseFile] to a [ContactBookEntry], or null if the
 * header content is absent (e.g. a large contact spilled into a payload — rare;
 * imported/manual contacts always embed) or cannot be parsed.
 */
fun HomebaseFile.toContactBookEntry(): ContactBookEntry? {
    val uniqueId = fileMetadata.appData.uniqueId ?: return null
    val contentJson = fileMetadata.appData.content ?: return null
    val content = try {
        OdinSystemSerializer.deserialize<ContactContent>(contentJson)
    } catch (_: Exception) {
        return null
    }

    val name = content.name
    // Shared with the chat read path (DriveContactService) via the single resolver so the two
    // can't drift. Null → nothing renderable, skip.
    val display = name.resolveDisplayName(
        odinId = content.odinId,
        phone = content.phone?.number,
        email = content.email?.email,
    ) ?: return null

    val imagePayload = fileMetadata.payloads
        ?.firstOrNull { it.key == ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY }

    return ContactBookEntry(
        uniqueId = uniqueId,
        fileId = fileId,
        versionTag = fileMetadata.versionTag,
        odinId = content.odinId,
        displayName = display,
        givenName = name?.givenName,
        additionalName = name?.additionalName,
        surname = name?.surname,
        phone = content.phone?.number,
        email = content.email?.email,
        city = content.location?.city,
        country = content.location?.country,
        birthday = content.birthday?.date,
        source = content.source,
        driveId = driveId,
        keyHeader = keyHeader,
        isEncrypted = fileMetadata.isEncrypted,
        previewThumbnail = fileMetadata.appData.previewThumbnail,
        imagePayload = imagePayload,
    )
}
