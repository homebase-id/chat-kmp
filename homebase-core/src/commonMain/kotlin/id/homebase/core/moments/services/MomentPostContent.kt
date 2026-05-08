package id.homebase.core.moments.services

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class MomentPostContent(
    val version: Int,
    val description: String,
    val recipients: List<OdinId> = emptyList(),
    val source: MomentSource? = null,
    /**
     * Per-payload image metadata, keyed by payload key (e.g. `mmt_0000`).
     * Populated only for image payloads where the user has elected to include
     * the relevant fields. Older clients ignore this field — kotlinx.serialization
     * is configured with `ignoreUnknownKeys = true`.
     */
    val mediaInfo: Map<String, MediaInfo>? = null,
)

/**
 * Extracted-from-EXIF photo metadata that travels alongside the moment post.
 *
 * Wire format is intentionally primitive-only (strings, doubles, ints) for
 * forward-compat — both ends serialize through OdinSystemSerializer's JSON,
 * which drops null fields (`explicitNulls = false`) so an empty MediaInfo is
 * effectively `{}`.
 *
 * The capture timestamp is split: [capturedAtLocal] is the camera's wall-clock
 * (no timezone); [captureUtcOffsetSeconds] is the offset if the camera also
 * wrote OffsetTimeOriginal. When the offset is null, the absolute moment of
 * capture is unknowable from EXIF alone.
 */
@Serializable
data class MediaInfo(
    /** ISO-8601 local datetime, e.g. "2018-07-02T11:33:55". */
    val capturedAtLocal: String? = null,
    val captureUtcOffsetSeconds: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

/**
 * Where the moment originated. Discriminated union so future origins (group,
 * circle, …) can be added as new subtypes without breaking forward-compat —
 * `@SerialName` is the JSON `type` discriminator.
 */
@Serializable
sealed interface MomentSource {
    @Serializable
    @SerialName("conversation")
    data class Conversation(
        @Serializable(with = UuidSerializer::class)
        val conversationId: Uuid,
    ) : MomentSource

    /**
     * Composite audience: zero or more moments groups plus any standalone
     * individuals that weren't picked via a group. The full distribution
     * recipient list still lives on `MomentPostContent.recipients` (flattened);
     * this variant preserves the breakdown so UI can render "Posted to Family
     * + Carol" and the queryable `appData.tags` entries cover every selected
     * group.
     */
    @Serializable
    @SerialName("audience")
    data class Audience(
        val groupIds: List<@Serializable(with = UuidSerializer::class) Uuid> = emptyList(),
        val individuals: List<OdinId> = emptyList(),
    ) : MomentSource
}
