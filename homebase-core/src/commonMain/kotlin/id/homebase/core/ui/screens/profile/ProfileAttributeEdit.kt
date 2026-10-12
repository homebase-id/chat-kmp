package id.homebase.core.ui.screens.profile

import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ProfileField {
    GIVEN_NAME, SURNAME, ADDITIONAL_NAME,
    NICKNAME, STATUS, BIRTHDAY, BIO,
    EMAIL, EMAIL_LABEL,
    PHONE, PHONE_LABEL,
    ADDRESS_LABEL, ADDRESS1, ADDRESS2, POSTCODE, CITY, COUNTRY,
    TWITTER, FACEBOOK, INSTAGRAM, TIKTOK, LINKEDIN,
}

internal val PROFILE_TYPE_FIELDS: Map<String, List<Pair<ProfileField, String>>> = mapOf(
    ProfileAttributeTypes.NAME to listOf(
        ProfileField.GIVEN_NAME to ProfileAttributeTypes.KEY_GIVEN_NAME,
        ProfileField.SURNAME to ProfileAttributeTypes.KEY_SURNAME,
        ProfileField.ADDITIONAL_NAME to ProfileAttributeTypes.KEY_ADDITIONAL_NAME,
    ),
    ProfileAttributeTypes.NICKNAME to listOf(
        ProfileField.NICKNAME to ProfileAttributeTypes.KEY_NICKNAME,
    ),
    ProfileAttributeTypes.STATUS to listOf(
        ProfileField.STATUS to ProfileAttributeTypes.KEY_STATUS,
    ),
    ProfileAttributeTypes.BIRTHDAY to listOf(
        ProfileField.BIRTHDAY to ProfileAttributeTypes.KEY_BIRTHDAY,
    ),
    ProfileAttributeTypes.EMAIL to listOf(
        ProfileField.EMAIL to ProfileAttributeTypes.KEY_EMAIL,
        ProfileField.EMAIL_LABEL to ProfileAttributeTypes.KEY_LABEL,
    ),
    ProfileAttributeTypes.PHONE to listOf(
        ProfileField.PHONE to ProfileAttributeTypes.KEY_PHONE,
        ProfileField.PHONE_LABEL to ProfileAttributeTypes.KEY_LABEL,
    ),
    ProfileAttributeTypes.ADDRESS to listOf(
        ProfileField.ADDRESS_LABEL to ProfileAttributeTypes.KEY_LABEL,
        ProfileField.ADDRESS1 to ProfileAttributeTypes.KEY_ADDRESS1,
        ProfileField.ADDRESS2 to ProfileAttributeTypes.KEY_ADDRESS2,
        ProfileField.POSTCODE to ProfileAttributeTypes.KEY_POSTCODE,
        ProfileField.CITY to ProfileAttributeTypes.KEY_CITY,
        ProfileField.COUNTRY to ProfileAttributeTypes.KEY_COUNTRY,
    ),
    ProfileAttributeTypes.BIO_SUMMARY to listOf(
        ProfileField.BIO to ProfileAttributeTypes.KEY_SHORT_BIO,
    ),
    ProfileAttributeTypes.TWITTER to listOf(
        ProfileField.TWITTER to ProfileAttributeTypes.KEY_TWITTER,
    ),
    ProfileAttributeTypes.FACEBOOK to listOf(
        ProfileField.FACEBOOK to ProfileAttributeTypes.KEY_FACEBOOK,
    ),
    ProfileAttributeTypes.INSTAGRAM to listOf(
        ProfileField.INSTAGRAM to ProfileAttributeTypes.KEY_INSTAGRAM,
    ),
    ProfileAttributeTypes.TIKTOK to listOf(
        ProfileField.TIKTOK to ProfileAttributeTypes.KEY_TIKTOK,
    ),
    ProfileAttributeTypes.LINKEDIN to listOf(
        ProfileField.LINKEDIN to ProfileAttributeTypes.KEY_LINKEDIN,
    ),
)

/** One attribute that actually changed, with its full replacement `data` and its audience. */
internal data class AttributeEdit(
    val type: String,
    val data: JsonObject,
    val audience: ProfileAudience,
)

/**
 * Pure computation of whether [type] needs saving given [updates] and [audience]. Null when
 * neither the data nor the audience changed against [existing] / [loadedAudience] —
 * critically, an untouched legacy record keeps its stored visibility on the server.
 */
internal fun computeAttributeEdit(
    existing: ProfileAttribute?,
    type: String,
    updates: Map<String, String>,
    audience: ProfileAudience,
    loadedAudience: ProfileAudience?,
): AttributeEdit? {
    val merged = mergeData(existing?.data, updates)
    val dataChanged = if (existing == null) merged.isNotEmpty() else merged != existing.data
    val audienceChanged = existing != null && audience != loadedAudience
    if (!dataChanged && !audienceChanged) return null

    // Drop the server-derived displayName so it is recomputed from the changed name parts;
    // a deliberate explicitDisplayName override is left untouched.
    val toSend = if (type == ProfileAttributeTypes.NAME && dataChanged) {
        JsonObject(merged - ProfileAttributeTypes.KEY_DISPLAY_NAME)
    } else {
        merged
    }
    return AttributeEdit(type, toSend, audience)
}

private fun mergeData(existing: JsonObject?, updates: Map<String, String>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    existing?.let { map.putAll(it) }
    for ((key, raw) in updates) {
        val value = raw.trim()
        if (value.isEmpty()) map.remove(key) else map[key] = JsonPrimitive(value)
    }
    return JsonObject(map)
}

/**
 * The record the editor writes per type: the Public one if there is one, else the first other.
 * Leftovers of the same type split by data: [duplicates] equal the edited record's (safe to delete
 * on save) and [conflicts] differ (the user decides). Links are many-per-profile, so they come back as [links].
 */
internal class LoadedProfileAttributes(
    val byType: Map<String, ProfileAttribute>,
    val duplicates: Map<String, List<ProfileAttribute>>,
    val conflicts: Map<String, List<ProfileAttribute>>,
    val links: List<ProfileAttribute>,
) {
    companion object {
        fun from(attributes: List<ProfileAttribute>): LoadedProfileAttributes {
            val single = attributes.filter { it.type != ProfileAttributeTypes.LINK }
            val byType = single.groupBy { it.type }.mapValues { (_, attrs) ->
                attrs.firstOrNull { it.visibility == ProfileVisibility.ANONYMOUS } ?: attrs.first()
            }
            val leftovers = single.groupBy { it.type }
                .mapValues { (type, attrs) -> attrs.filter { it.id != byType[type]?.id } }
                .filterValues { it.isNotEmpty() }
            return LoadedProfileAttributes(
                byType = byType,
                duplicates = leftovers.mapValues { (type, attrs) -> attrs.filter { it.data == byType[type]?.data } }.filterValues { it.isNotEmpty() },
                conflicts = leftovers.mapValues { (type, attrs) -> attrs.filter { it.data != byType[type]?.data } }.filterValues { it.isNotEmpty() },
                links = attributes.filter { it.type == ProfileAttributeTypes.LINK }.sortedBy { it.priority },
            )
        }
    }
}

internal fun profileNameValue(values: Map<ProfileField, String>): String? = listOfNotNull(
    values[ProfileField.GIVEN_NAME]?.ifBlank { null },
    values[ProfileField.ADDITIONAL_NAME]?.ifBlank { null },
    values[ProfileField.SURNAME]?.ifBlank { null },
).joinToString(" ").ifBlank { null }

// The single-line format ContactBookEntry.location uses for contact addresses.
internal fun profileAddressValue(values: Map<ProfileField, String>): String? = listOfNotNull(
    values[ProfileField.ADDRESS1]?.ifBlank { null },
    values[ProfileField.ADDRESS2]?.ifBlank { null },
    listOfNotNull(values[ProfileField.POSTCODE]?.ifBlank { null }, values[ProfileField.CITY]?.ifBlank { null })
        .joinToString(" ").ifBlank { null },
    values[ProfileField.COUNTRY]?.ifBlank { null },
).joinToString(", ").ifBlank { null }

