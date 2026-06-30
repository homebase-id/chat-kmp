@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.profile

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One profile attribute as read from the owner's ProfileDrive.
 *
 * [id] and [versionTag] together address the attribute for an edit: every save must echo the latest
 * [versionTag] (a stale tag → 409, re-read and retry). [data] is the attribute's full value object,
 * kept as a raw [JsonObject] so unknown keys we don't model survive a round-trip (writes REPLACE,
 * not merge — see [ProfileProvider.saveAttribute]).
 */
data class ProfileAttribute(
    val id: Uuid,
    /** No-dash type GUID, normalized via [normalizeType]; matches a constant in [ProfileAttributeTypes]. */
    val type: String,
    val versionTag: Uuid,
    val visibility: ProfileVisibility,
    val data: JsonObject,
) {
    /** Reads a string-valued [data] key, or null if absent/non-string. */
    fun string(key: String): String? = (data[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Lower-cases and strips dashes so a dashed drive type matches the no-dash constants. */
        fun normalizeType(raw: String): String = raw.replace("-", "").lowercase()
    }
}

/**
 * PUT /api/v2/profile/attributes body. [id] and [expectedVersionTag] are omitted (not sent) when
 * null — omitting [id] CREATEs a new attribute; including it with [expectedVersionTag] EDITs the
 * existing one. Null fields drop out of the JSON because [id.homebase.api.serialization.OdinSystemSerializer]
 * has `explicitNulls = false`.
 */
@Serializable
data class SaveProfileAttributeRequest(
    val type: String,
    @Serializable(with = UuidSerializer::class) val id: Uuid? = null,
    @Serializable(with = UuidSerializer::class) val expectedVersionTag: Uuid? = null,
    val visibility: String,
    val data: JsonObject,
)

/** 200 OK body for a profile-attribute write. Keep [versionTag] for the next edit. */
@Serializable
data class ProfileWriteResponse(
    @Serializable(with = UuidSerializer::class) val id: Uuid,
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid,
)

/**
 * Typed outcome of a profile-attribute write. Unlike the contacts controller, the profile endpoint
 * does NOT merge on conflict — a 409 means the [SaveProfileAttributeRequest.expectedVersionTag] is
 * stale, so recovery must re-read the current attribute and resend (handled in [ProfileRepository]).
 * Transport/auth failures (403 = missing ManageProfile, 5xx, …) still throw from the provider.
 */
sealed interface ProfileWriteResult {
    data class Ok(val body: ProfileWriteResponse) : ProfileWriteResult
    data object Conflict : ProfileWriteResult
}
