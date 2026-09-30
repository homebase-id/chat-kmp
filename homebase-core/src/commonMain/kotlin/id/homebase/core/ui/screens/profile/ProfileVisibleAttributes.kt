package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.aclMostRestrictiveFirst
import id.homebase.api.client.drives.isVisibleTo
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility

private val mostRestrictiveThenPriority: Comparator<ProfileAttribute> =
    compareBy(aclMostRestrictiveFirst, ProfileAttribute::acl).thenBy { it.priority }

internal typealias AclFilter = (AccessControlList) -> Boolean

internal fun ProfileVisibility.aclFilter(): AclFilter = { it.isVisibleTo(this) }

/**
 * The [type] attribute a viewer sees, as odin-js picks it: among those whose ACL [canSee] lets the
 * viewer read them, the most restrictive first, then the lowest priority. A record with none of
 * [keys] set is skipped, so "connected where set, else public" holds for a cleared record too.
 */
internal fun List<ProfileAttribute>.visibleAttribute(
    type: String,
    canSee: AclFilter,
    keys: Collection<String>,
): ProfileAttribute? =
    filter { it.type == type && canSee(it.acl) }
        .sortedWith(mostRestrictiveThenPriority)
        .firstOrNull { attribute -> keys.any { !attribute.string(it).isNullOrBlank() } }

internal fun List<ProfileAttribute>.visibleValues(canSee: AclFilter): Map<ProfileField, String> =
    ProfileEditViewModel.TYPE_FIELDS.flatMap { (type, fields) ->
        val attribute = visibleAttribute(type, canSee, fields.map { it.second })
        fields.map { (field, key) -> field to attribute?.string(key).orEmpty() }
    }.toMap()

internal fun List<ProfileAttribute>.visibleValues(tier: ProfileVisibility): Map<ProfileField, String> =
    visibleValues(tier.aclFilter())

internal fun List<ProfileAttribute>.visiblePhoto(canSee: AclFilter): ProfileAttribute? =
    visibleAttribute(ProfileAttributeTypes.PHOTO, canSee, listOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE))

internal fun List<ProfileAttribute>.visiblePhoto(tier: ProfileVisibility): ProfileAttribute? = visiblePhoto(tier.aclFilter())

internal fun List<ProfileAttribute>.visibleBio(canSee: AclFilter): String? =
    visibleAttribute(ProfileAttributeTypes.BIO_SUMMARY, canSee, listOf(ProfileAttributeTypes.KEY_SHORT_BIO))
        ?.string(ProfileAttributeTypes.KEY_SHORT_BIO)

internal fun List<ProfileAttribute>.visibleBio(tier: ProfileVisibility): String? = visibleBio(tier.aclFilter())

/** Links are many per profile, so a viewer sees every one it can read, in odin-js `useLinks` order. */
internal fun List<ProfileAttribute>.visibleLinks(canSee: AclFilter): List<ProfileAttribute> =
    filter { it.type == ProfileAttributeTypes.LINK && canSee(it.acl) }
        .sortedWith(compareBy<ProfileAttribute> { it.priority }.thenBy(aclMostRestrictiveFirst) { it.acl })
