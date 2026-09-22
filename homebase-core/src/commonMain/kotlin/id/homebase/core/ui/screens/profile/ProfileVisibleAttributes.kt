package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.aclMostRestrictiveFirst
import id.homebase.api.client.drives.isVisibleTo
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility

private val mostRestrictiveThenPriority: Comparator<ProfileAttribute> =
    compareBy(aclMostRestrictiveFirst, ProfileAttribute::acl).thenBy { it.priority }

/**
 * The [type] attribute a [tier] viewer sees, as odin-js picks it: among those whose ACL lets the
 * viewer read them, the most restrictive first, then the lowest priority. A record with none of
 * [keys] set is skipped, so "connected where set, else public" holds for a cleared record too.
 */
internal fun List<ProfileAttribute>.visibleAttribute(
    type: String,
    tier: ProfileVisibility,
    keys: Collection<String>,
): ProfileAttribute? =
    filter { it.type == type && it.acl.isVisibleTo(tier) }
        .sortedWith(mostRestrictiveThenPriority)
        .firstOrNull { attribute -> keys.any { !attribute.string(it).isNullOrBlank() } }

internal fun List<ProfileAttribute>.visibleValues(tier: ProfileVisibility): Map<ProfileField, String> =
    ProfileEditViewModel.TYPE_FIELDS.flatMap { (type, fields) ->
        val attribute = visibleAttribute(type, tier, fields.map { it.second })
        fields.map { (field, key) -> field to attribute?.string(key).orEmpty() }
    }.toMap()

internal fun List<ProfileAttribute>.visiblePhoto(tier: ProfileVisibility): ProfileAttribute? =
    visibleAttribute(ProfileAttributeTypes.PHOTO, tier, listOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE))

internal fun List<ProfileAttribute>.visibleBio(tier: ProfileVisibility): String? =
    visibleAttribute(ProfileAttributeTypes.BIO_SUMMARY, tier, listOf(ProfileAttributeTypes.KEY_SHORT_BIO))
        ?.string(ProfileAttributeTypes.KEY_SHORT_BIO)

/** Links are many per profile, so a tier sees every one it can read, in odin-js `useLinks` order. */
internal fun List<ProfileAttribute>.visibleLinks(tier: ProfileVisibility): List<ProfileAttribute> =
    filter { it.type == ProfileAttributeTypes.LINK && it.acl.isVisibleTo(tier) }
        .sortedWith(compareBy<ProfileAttribute> { it.priority }.thenBy(aclMostRestrictiveFirst) { it.acl })
