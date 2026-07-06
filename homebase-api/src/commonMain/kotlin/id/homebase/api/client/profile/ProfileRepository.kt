@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.api.client.profile

import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Thrown by [ProfileRepository.uploadPhoto] when the server rejects the photo for exceeding its
 *  size cap (400 `maxContentLengthExceeded`) — callers should prompt for a smaller photo rather
 *  than show a generic upload failure. Mirrors `ContactAppDataTooLargeException`. */
class ProfilePhotoTooLargeException(message: String) : Exception(message)

/**
 * The blur-up preview thumb for [ProfileRepository.uploadPhoto] — [bytes] is the *tiny* rendition
 * (~20px WebP, plaintext), but [naturalPixelWidth]/[naturalPixelHeight] must be the **source**
 * image's dimensions, not this tiny rendition's own resized size (a deliberate server/odin-js
 * convention — see [SetPhotoAttributeRequest.previewThumbnail]). Kept as its own type rather than
 * reusing [ThumbnailFile] so that distinction can't be missed at the call site.
 */
data class PreviewThumbnail(
    val bytes: ByteArray,
    val naturalPixelWidth: Int,
    val naturalPixelHeight: Int,
    val contentType: String = "image/webp",
)

/**
 * Read + write source of truth for the owner's standard-profile attributes.
 *
 * READ goes straight to the ProfileDrive via [DriveQueryProvider.queryBatch] (`fileType = 77`) — a
 * one-shot, on-demand query (the ProfileDrive is intentionally NOT in `mandatorySyncDrives`, so
 * there's no local index to read). The query response already decrypts each file's content, so
 * parsing is plain JSON. WRITE goes through [ProfileProvider]; on a 409 (stale versionTag) this
 * re-reads the attribute and resends, bounded by `maxAttempts`.
 *
 * Mirrors the [id.homebase.api.client.contacts.ContactRepository] / `ContactsProvider` split.
 */
class ProfileRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val profileProvider: ProfileProvider,
) {
    private val profileDriveId: Uuid = SystemDriveConstants.profileDrive.alias

    /**
     * Reads every standard-profile attribute the app can see, newest file per attribute. Files that
     * fail to parse (corrupt content, missing id/versionTag) are skipped, not fatal.
     */
    suspend fun loadAttributes(): List<ProfileAttribute> {
        val response = driveQueryProvider.queryBatch(
            driveId = profileDriveId,
            request = QueryBatchRequest(
                queryParams = FileQueryParams(
                    fileType = listOf(ProfileProvider.PROFILE_ATTRIBUTE_FILE_TYPE),
                ),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = 1000,
                    includeMetadataHeader = true,
                ),
            ),
        )

        return response.searchResults
            .mapNotNull { it.toProfileAttribute() }
            .distinctBy { it.id }
    }

    /**
     * Creates or edits the attribute of [type] holding [data].
     *
     * Pass [knownId] + [knownVersionTag] for an attribute the caller already read (goes straight to
     * EDIT); omit them to CREATE. On a 409 the stored versionTag was stale, so re-read the current
     * attribute (by id when known, else by type) and resend the same [data]. Bounded by [maxAttempts];
     * throws [IllegalStateException] if exhausted.
     */
    suspend fun save(
        type: String,
        data: JsonObject,
        visibility: ProfileVisibility,
        knownId: Uuid? = null,
        knownVersionTag: Uuid? = null,
        maxAttempts: Int = 3,
    ): ProfileWriteResponse {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        var id = knownId
        var versionTag = knownVersionTag

        repeat(maxAttempts) {
            val result = profileProvider.saveAttribute(
                type = type,
                id = id,
                expectedVersionTag = versionTag,
                visibility = visibility,
                data = data,
            )
            when (result) {
                is ProfileWriteResult.Ok -> return result.body
                ProfileWriteResult.Conflict -> {
                    // Stale tag — the server did not merge. Re-read the authoritative id/versionTag
                    // and resend the same data on the next iteration.
                    val fresh = reReadAttribute(type, id, visibility)
                        ?: error("profile attribute $type returned 409 but is no longer present")
                    id = fresh.id
                    versionTag = fresh.versionTag
                }
            }
        }

        error("profile attribute write contention exceeded $maxAttempts attempts for type $type")
    }

    /** DELETE the attribute; `false` if it no longer exists. */
    suspend fun delete(id: Uuid, versionTag: Uuid): Boolean =
        profileProvider.deleteAttribute(id, versionTag)

    /**
     * Creates or edits the owner's profile photo at [visibility]. [content] and every [thumbnails]
     * entry must be PLAINTEXT — [ProfileProvider.setPhotoAttribute] handles server-side encryption
     * for CONNECTED/OWNER. If a photo attribute already exists at this [visibility] it is edited in
     * place (not duplicated); a different [visibility] always creates a separate attribute, since
     * multiple photos can coexist by design. Same 409-retry contract as [save].
     *
     * [previewThumbnail], if provided, must also be PLAINTEXT bytes — pass its already-tiny (~20px)
     * WebP bytes and the *source* image's natural pixel size (not the tiny thumb's own resized
     * size); see [SetPhotoAttributeRequest.previewThumbnail] for why.
     */
    suspend fun uploadPhoto(
        contentType: String,
        content: ByteArray,
        thumbnails: List<ThumbnailFile>,
        visibility: ProfileVisibility,
        previewThumbnail: PreviewThumbnail? = null,
        maxAttempts: Int = 3,
    ): ProfileWriteResponse {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        var existing = photoAttribute(visibility)

        repeat(maxAttempts) {
            val result = try {
                profileProvider.setPhotoAttribute(
                    SetPhotoAttributeRequest(
                        id = existing?.id,
                        expectedVersionTag = existing?.versionTag,
                        visibility = visibility.photoWireValue,
                        contentType = contentType,
                        content = Base64.encode(content),
                        thumbnails = thumbnails.map {
                            PhotoThumbnailContent(
                                pixelWidth = it.pixelWidth,
                                pixelHeight = it.pixelHeight,
                                contentType = it.contentType,
                                content = Base64.encode(it.thumbnailBytes),
                            )
                        },
                        previewThumbnail = previewThumbnail?.let {
                            EmbeddedThumb(
                                pixelWidth = it.naturalPixelWidth,
                                pixelHeight = it.naturalPixelHeight,
                                contentType = it.contentType,
                                content = Base64.encode(it.bytes),
                            )
                        },
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                if (e.errorCode == OdinClientErrorCode.MaxContentLengthExceeded) {
                    throw ProfilePhotoTooLargeException(e.message ?: "Photo exceeds the size limit")
                }
                throw e
            }
            when (result) {
                is ProfileWriteResult.Ok -> return result.body
                ProfileWriteResult.Conflict -> {
                    existing = photoAttribute(visibility)
                        ?: error("profile photo returned 409 but is no longer present")
                }
            }
        }

        error("profile photo write contention exceeded $maxAttempts attempts")
    }

    /** Every [ProfileAttributeTypes.PHOTO] attribute the owner currently has, across all tiers. */
    suspend fun photoAttributes(): List<ProfileAttribute> =
        loadAttributes().filter { it.type == ProfileAttributeTypes.PHOTO }

    /** The current photo attribute at [visibility], if the owner already has one. */
    suspend fun photoAttribute(visibility: ProfileVisibility): ProfileAttribute? =
        photoAttributes().firstOrNull { it.visibility == visibility }

    /**
     * Finds the current attribute by id when known, else the first of [type] AT [visibility] — the
     * visibility match matters once a type can have more than one attribute (e.g. an Anonymous and a
     * Connected value): matching by type alone on a create-race 409 could otherwise lock onto the
     * wrong tier's row and corrupt it.
     */
    private suspend fun reReadAttribute(type: String, id: Uuid?, visibility: ProfileVisibility): ProfileAttribute? {
        val all = loadAttributes()
        return if (id != null) all.firstOrNull { it.id == id }
        else all.firstOrNull { it.type == type && it.visibility == visibility }
    }
}
