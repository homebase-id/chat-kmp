package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.util.compareStringUuId
import id.homebase.core.ui.screens.card.CardCircle
import kotlinx.serialization.json.JsonObject

/** Who sees one profile detail; it decides which cards show it. */
sealed interface ProfileAudience {
    data object Public : ProfileAudience
    data object OnlyMe : ProfileAudience

    /**
     * [ids] come from the Contacts-app circle list, never from the server's spelling. [otherIds]
     * are stored circles the editor has no chip for (user-made, other apps'); they ride along on
     * save so an edit never silently revokes them.
     */
    data class Circles(val ids: Set<String>, val otherIds: Set<String> = emptySet()) : ProfileAudience

    val isSavable: Boolean get() = this !is Circles || ids.isNotEmpty() || otherIds.isNotEmpty()

    /** With no Contacts circles to pick from, Circles means every connection, which the server stores as Connected with no circles. */
    fun isSavableWith(circles: List<CardCircle>): Boolean = isSavable || (this is Circles && circles.isEmpty())

    val visibility: ProfileVisibility
        get() = when (this) {
            Public -> ProfileVisibility.ANONYMOUS
            OnlyMe -> ProfileVisibility.OWNER
            is Circles -> ProfileVisibility.CONNECTED
        }

    val circleIds: List<String> get() = (this as? Circles)?.let { it.ids + it.otherIds }?.sorted().orEmpty()
}

/** Connected with no circles means every reviewed connection, so it reads as every Contacts circle selected. */
internal fun ProfileAttribute.audience(circles: List<CardCircle>): ProfileAudience = when (visibility) {
    ProfileVisibility.ANONYMOUS -> ProfileAudience.Public
    ProfileVisibility.OWNER -> ProfileAudience.OnlyMe
    ProfileVisibility.CONNECTED, ProfileVisibility.AUTHENTICATED -> {
        val stored = acl.circleIdList.orEmpty()
        val allIds = circles.map { it.id }.toSet()
        val everyConnection = stored.isEmpty()
        val other = stored.filter { s -> circles.none { compareStringUuId(s, it.id) } }.toSet()
        val known = circles.filter { c -> stored.any { compareStringUuId(it, c.id) } }.map { it.id }.toSet()
        ProfileAudience.Circles(if (everyConnection) allIds else known, other)
    }
}

internal fun ProfileAudience.toAcl(): AccessControlList =
    AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circleIds.takeIf { it.isNotEmpty() })

// priority rides along so a link keeps its place
internal suspend fun ProfileRepository.saveWithAudience(
    type: String,
    data: JsonObject,
    audience: ProfileAudience,
    existing: ProfileAttribute?,
): ProfileAttribute {
    val response = save(
        type = type,
        data = data,
        visibility = audience.visibility,
        knownId = existing?.id,
        knownVersionTag = existing?.versionTag,
        priority = existing?.priority,
        circleIds = audience.circleIds,
    )
    return (existing ?: ProfileAttribute(id = response.id, type = type, versionTag = response.versionTag, visibility = audience.visibility, data = data))
        .copy(
            id = response.id,
            versionTag = response.versionTag,
            visibility = audience.visibility,
            data = data,
            acl = audience.toAcl(),
        )
}

// Keeps previous's circle spelling and order, so an undo is byte-identical.
internal suspend fun ProfileRepository.restoreAcl(current: ProfileAttribute, previous: ProfileAttribute): ProfileAttribute {
    val response = save(
        type = current.type,
        data = current.data,
        visibility = previous.visibility,
        knownId = current.id,
        knownVersionTag = current.versionTag,
        priority = previous.priority,
        circleIds = previous.acl.circleIdList.orEmpty(),
    )
    return current.copy(versionTag = response.versionTag, visibility = previous.visibility, acl = previous.acl)
}

// Every circle gone: only the owner could see it, while Connected with no circles would mean every connection.
internal fun ProfileAudience.keepingOtherCircles(kept: (String) -> Boolean): ProfileAudience {
    if (this !is ProfileAudience.Circles) return this
    val other = otherIds.filter(kept).toSet()
    return if (ids.isEmpty() && other.isEmpty() && otherIds.isNotEmpty()) ProfileAudience.OnlyMe else copy(otherIds = other)
}

internal fun ProfileAttribute.keepingCircles(kept: (String) -> Boolean): ProfileAttribute {
    val stored = acl.circleIdList.orEmpty()
    val left = stored.filter(kept)
    return when {
        left.size == stored.size -> this
        left.isEmpty() -> copy(visibility = ProfileVisibility.OWNER, acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.OWNER.wireValue))
        else -> copy(acl = acl.copy(circleIdList = left))
    }
}
