package id.homebase.api.client.contacts

import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What a surface can draw. A pre-auth surface has no drive keys, so it can't decrypt a stored photo. */
enum class AvatarCapability { Any, UnencryptedOnly }

/** Stored contact photo, then public avatar; [initials] is always the floor. */
sealed interface AvatarSource {
    val initials: String

    data class StoredPhoto(override val initials: String, val image: ContactImageRef) : AvatarSource

    data class PublicPhoto(override val initials: String) : AvatarSource
}

class AvatarResolver(private val contacts: StateFlow<List<Contact>>) {

    fun resolve(odinId: OdinId, capability: AvatarCapability, displayName: String? = null): AvatarSource =
        resolveAvatar(odinId, contacts.value, capability, displayName)

    fun observe(odinId: OdinId, capability: AvatarCapability, displayName: String? = null): Flow<AvatarSource> =
        contacts
            .map { resolveAvatar(odinId, it, capability, displayName) }
            .distinctUntilChanged()
}

internal fun List<Contact>.matching(odinId: OdinId): List<Contact> =
    filter { it.content.odinId?.trim().equals(odinId.domainName, ignoreCase = true) }

internal fun resolveAvatar(
    odinId: OdinId,
    contacts: List<Contact>,
    capability: AvatarCapability,
    displayName: String? = null,
): AvatarSource {
    val matches = contacts.matching(odinId)
    val initials = matches.firstNotNullOfOrNull { it.content.name.initials().takeUnless { i -> i == "?" } }
        ?: displayName?.let { ContactName(displayName = it).initials().takeUnless { i -> i == "?" } }
        ?: odinId.domainName.truncateToCodePoints(1).uppercase()

    // Skia drops SVG <text>, and a photo-less peer's synced prfl_pic is the server's SVG initials.
    val image = matches.firstNotNullOfOrNull { it.image?.takeUnless { img -> img.isSvg } }
        ?.takeIf { capability == AvatarCapability.Any || !it.isEncrypted }

    return if (image != null) AvatarSource.StoredPhoto(initials, image) else AvatarSource.PublicPhoto(initials)
}
