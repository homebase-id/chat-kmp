package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.AclViewer
import id.homebase.api.client.drives.aclMostRestrictiveFirst
import id.homebase.api.client.drives.isVisibleTo
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes

private val mostRestrictiveThenPriority: Comparator<ProfileAttribute> =
    compareBy(aclMostRestrictiveFirst, ProfileAttribute::acl).thenBy { it.priority }

/**
 * The [type] attribute a viewer sees, as odin-js picks it: among those whose ACL lets [viewer]
 * read them, the most restrictive first, then the lowest priority. A record with none of
 * [keys] set is skipped, so "connected where set, else public" holds for a cleared record too.
 */
internal fun List<ProfileAttribute>.visibleAttribute(
    type: String,
    viewer: AclViewer,
    keys: Collection<String>,
): ProfileAttribute? =
    filter { it.type == type && it.acl.isVisibleTo(viewer) }
        .sortedWith(mostRestrictiveThenPriority)
        .firstOrNull { attribute -> keys.any { !attribute.string(it).isNullOrBlank() } }

internal fun List<ProfileAttribute>.visibleValues(viewer: AclViewer): Map<ProfileField, String> =
    PROFILE_TYPE_FIELDS.flatMap { (type, fields) ->
        val attribute = visibleAttribute(type, viewer, fields.map { it.second })
        fields.map { (field, key) -> field to attribute?.string(key).orEmpty() }
    }.toMap()

internal fun List<ProfileAttribute>.visiblePhoto(viewer: AclViewer): ProfileAttribute? =
    visibleAttribute(ProfileAttributeTypes.PHOTO, viewer, listOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE))

internal fun List<ProfileAttribute>.visibleBio(viewer: AclViewer): String? =
    visibleAttribute(ProfileAttributeTypes.BIO_SUMMARY, viewer, listOf(ProfileAttributeTypes.KEY_SHORT_BIO))
        ?.string(ProfileAttributeTypes.KEY_SHORT_BIO)

/** Links are many per profile, so a viewer sees every one it can read, in odin-js `useLinks` order. */
internal fun List<ProfileAttribute>.visibleLinks(viewer: AclViewer): List<ProfileAttribute> =
    filter { it.type == ProfileAttributeTypes.LINK && it.acl.isVisibleTo(viewer) }
        .sortedWith(compareBy<ProfileAttribute> { it.priority }.thenBy(aclMostRestrictiveFirst) { it.acl })
