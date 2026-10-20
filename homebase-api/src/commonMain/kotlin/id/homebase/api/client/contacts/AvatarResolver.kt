package id.homebase.api.client.contacts

import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What a surface can draw. A pre-auth surface has no drive keys, so it can't decrypt a stored photo. */
enum class AvatarCapability { Any, UnencryptedOnly }

/**
 * The picture to draw for an identity, best candidate first; [initials] is always the floor. Initials
 * come from the saved contact's name, else the caller's display name, else the domain.
 */
sealed interface AvatarSource {
    val odinId: OdinId
    val initials: String

    /** The photo saved on the identity's contact (`prfl_pic`). */
    data class StoredPhoto(
        override val odinId: OdinId,
        override val initials: String,
        val image: ContactImageRef,
    ) : AvatarSource

    /** The identity's published public avatar; renders [initials] when it never published one. */
    data class PublicPhoto(override val odinId: OdinId, override val initials: String) : AvatarSource
}

/**
 * The one place that decides which picture an identity gets: stored contact photo, then public
 * avatar, then initials. Every avatar surface resolves through here so they all pick the same one.
 */
class AvatarResolver(private val contacts: StateFlow<List<Contact>>) {

    fun resolve(odinId: OdinId, capability: AvatarCapability, displayName: String? = null): AvatarSource =
        resolveAvatar(odinId, contacts.value, capability, displayName)

    fun observe(odinId: OdinId, capability: AvatarCapability, displayName: String? = null): Flow<AvatarSource> =
        contacts
            .map { resolveAvatar(odinId, it, capability, displayName) }
            .distinctUntilChanged()
}

internal fun resolveAvatar(
    odinId: OdinId,
    contacts: List<Contact>,
    capability: AvatarCapability,
    displayName: String? = null,
): AvatarSource {
    val matches = contacts.filter { it.content.odinId?.trim().equals(odinId.domainName, ignoreCase = true) }
    val initials = matches.firstNotNullOfOrNull { it.content.name.initials().takeUnless { i -> i == "?" } }
        ?: displayName?.let { ContactName(displayName = it).initials().takeUnless { i -> i == "?" } }
        ?: odinId.domainName.truncateToCodePoints(1).uppercase()

    val image = matches.firstNotNullOfOrNull { it.image }
        ?.takeIf { capability == AvatarCapability.Any || !it.isEncrypted }

    return if (image != null) {
        AvatarSource.StoredPhoto(odinId, initials, image)
    } else {
        AvatarSource.PublicPhoto(odinId, initials)
    }
}
