@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.model

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactBirthday
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactLocation
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.api.client.contacts.ContactSocialNetwork
import id.homebase.api.client.contacts.resolveDisplayName
import id.homebase.api.client.contacts.socialHandles
import id.homebase.core.contactbook.iCanLocate
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
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
 * A contact in the contact-manager UI — the flat projection of the api
 * [id.homebase.api.client.contacts.Contact] domain model produced by
 * [id.homebase.api.client.contacts.ContactRepository] (see [Contact.toContactBookEntry]). Both
 * reads and writes flow through that repository.
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
    /** Free-form name for the address, e.g. "Home" / "Work"; used as the field label when present. */
    val locationLabel: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val birthday: String? = null,
    /** Free-text status/tagline (NOT connection state); rendered under the odinId in the header. */
    val status: String? = null,
    /** Short header tagline (~<=160 chars); rendered in its own "Bio" section. */
    val shortBio: String? = null,
    /** Known social/gaming handles in render order, resolved from [ContactContent.social]. */
    val socialHandles: List<Pair<ContactSocialNetwork, String>> = emptyList(),
    /** Owner-only flag: we can locate this contact in an emergency (they designated us). */
    val iCanLocate: Boolean = false,
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
    /**
     * Present when a user override has been applied (see [withOverride]): holds the *synced*
     * (peer-profile) original of each overridden field, so the UI can show "their profile says …".
     * Null when this entry carries no override.
     */
    val syncedOverlay: ContactFieldOverlay? = null,
    /** Extra, app-local phone numbers / emails the user added beyond the single canonical slot. */
    val additionalPhones: List<String> = emptyList(),
    val additionalEmails: List<String> = emptyList(),
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

    /**
     * The full postal address formatted for display, one component per line:
     * street lines, then "postcode city", then country. Null when nothing is set.
     */
    val location: String?
        get() = listOfNotNull(
            addressLine1?.ifBlank { null },
            addressLine2?.ifBlank { null },
            listOfNotNull(postcode?.ifBlank { null }, city?.ifBlank { null })
                .joinToString(" ").ifBlank { null },
            country?.ifBlank { null },
        ).joinToString("\n").ifBlank { null }

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
        location = listOfNotNull(
            locationLabel, addressLine1, addressLine2, postcode, city, country,
        ).any { it.isNotBlank() }.let { hasAny ->
            if (!hasAny) null
            else ContactLocation(
                label = locationLabel?.ifBlank { null },
                addressLine1 = addressLine1?.ifBlank { null },
                addressLine2 = addressLine2?.ifBlank { null },
                postcode = postcode?.ifBlank { null },
                city = city?.ifBlank { null },
                country = country?.ifBlank { null },
            )
        },
        phone = phone?.ifBlank { null }?.let { ContactPhone(number = it) },
        email = email?.ifBlank { null }?.let { ContactEmail(email = it) },
        birthday = birthday?.ifBlank { null }?.let { ContactBirthday(date = it) },
    )
}

/**
 * Projects the server-shaped [Contact] domain model (from `ContactRepository`) into the flat
 * contact-manager UI model. The display name is resolved via the shared
 * [id.homebase.api.client.contacts.resolveDisplayName] so it can't drift from other consumers;
 * null when nothing is renderable. Image-display fields come from [Contact.image].
 */
fun Contact.toContactBookEntry(): ContactBookEntry? {
    val name = content.name
    val display = name.resolveDisplayName(
        odinId = content.odinId,
        phone = content.phone?.number,
        email = content.email?.email,
    ) ?: return null

    return ContactBookEntry(
        uniqueId = uniqueId,
        fileId = image?.fileId ?: uniqueId,
        versionTag = versionTag,
        odinId = content.odinId,
        displayName = display,
        givenName = name?.givenName,
        additionalName = name?.additionalName,
        surname = name?.surname,
        phone = content.phone?.number,
        email = content.email?.email,
        locationLabel = content.location?.label,
        addressLine1 = content.location?.addressLine1,
        addressLine2 = content.location?.addressLine2,
        postcode = content.location?.postcode,
        city = content.location?.city,
        country = content.location?.country,
        birthday = content.birthday?.date,
        status = content.status,
        shortBio = content.shortBio,
        socialHandles = content.socialHandles(),
        iCanLocate = iCanLocate(),
        source = content.source,
        driveId = image?.driveId,
        keyHeader = image?.keyHeader,
        isEncrypted = image?.isEncrypted ?: false,
        previewThumbnail = image?.previewThumbnail,
        imagePayload = image?.payload,
    )
}
