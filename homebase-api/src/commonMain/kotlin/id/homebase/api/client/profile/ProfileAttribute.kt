@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.profile

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
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
 *
 * [fileId]/[driveId]/[keyHeader]/[payloads] are only populated for attributes read via
 * [ProfileRepository.loadAttributes] (they mirror the underlying `HomebaseFile`) — null when an
 * instance is hand-constructed elsewhere (e.g. caching a just-written text attribute). They exist
 * so a [ProfileAttributeTypes.PHOTO] attribute's image payload can be fetched for display; see
 * `ProfileAttribute.photoImageData()` in homebase-core.
 */
data class ProfileAttribute(
    val id: Uuid,
    /** No-dash type GUID, normalized via [normalizeType]; matches a constant in [ProfileAttributeTypes]. */
    val type: String,
    val versionTag: Uuid,
    val visibility: ProfileVisibility,
    val data: JsonObject,
    val fileId: Uuid? = null,
    val driveId: Uuid? = null,
    val keyHeader: KeyHeader? = null,
    val payloads: List<PayloadDescriptor>? = null,
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

/**
 * PUT /api/v2/profile/attributes/photo body. Unlike [SaveProfileAttributeRequest] there is no
 * `type`/`data` — the server owns the Photo attribute's type and sets `data.profileImageKey`
 * itself. [content] and every [thumbnails] entry are PLAINTEXT; the server encrypts at rest for
 * [visibility] CONNECTED/OWNER and stores as-is for ANONYMOUS/AUTHENTICATED. The server does not
 * resize — generate every rendition you want stored before calling (see
 * [id.homebase.api.image.createImageThumbnail]). [visibility] must be
 * [ProfileVisibility.photoWireValue] (PascalCase), not [ProfileVisibility.wireValue].
 *
 * [previewThumbnail] is the small blur-up placeholder (~20px WebP, capped under 1KB) — optional,
 * but without it the photo has no instant-paint preview until the real thumbnail loads. Always
 * PLAINTEXT regardless of [visibility] — unlike [content]/[thumbnails], the server never encrypts
 * it at rest, even for CONNECTED/OWNER. Report its `pixelWidth`/`pixelHeight` as the *source*
 * image's natural dimensions, not the tiny thumbnail's actual ~20×20 resized size — matches the
 * existing odin-js `getEmbeddedThumbOfThumbnailFile` convention ("on the previewThumb we use the
 * full pixelWidth & -height so the max size can be used").
 */
@Serializable
data class SetPhotoAttributeRequest(
    @Serializable(with = UuidSerializer::class) val id: Uuid? = null,
    val priority: Int = 0,
    val visibility: String,
    @Serializable(with = UuidSerializer::class) val expectedVersionTag: Uuid? = null,
    val contentType: String,
    val content: String,
    val thumbnails: List<PhotoThumbnailContent> = emptyList(),
    val previewThumbnail: EmbeddedThumb? = null,
)

@Serializable
data class PhotoThumbnailContent(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val contentType: String,
    val content: String,
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
