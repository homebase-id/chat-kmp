package id.homebase.chat.entephotos

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.core.config.EntePhotosDrive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.uuid.Uuid

/** A photo or video in the owner's Ente library. */
@Immutable
data class EntePhoto(
    val fileId: Uuid,
    val uniqueId: Uuid?,
    val title: String?,
    val takenMicros: Long?,
    val original: PayloadDescriptor,
    val payloadKey: KeyHeader,
    val isVideo: Boolean = false,
    val durationSeconds: Long? = null,
    /** Ente's visibility: [ENTE_VISIBLE], archived (kept out of the whole library) or hidden (shown nowhere). */
    val visibility: Int = ENTE_VISIBLE,
) {
    /** The stored (encrypted) size, at most 16 bytes over the original's. */
    val sizeBytes: Long? get() = original.bytesWritten
    val hasThumbnails: Boolean get() = !original.thumbnails.isNullOrEmpty()
    val tooBigToSend: Boolean get() = isVideo && (sizeBytes ?: 0) > MAX_ENTE_VIDEO_BYTES
}

@Immutable
data class EnteAlbum(
    /** The album file's uniqueId: each of its memberships has it as groupId. */
    val id: Uuid,
    val name: String,
    val type: String,
    val hidden: Boolean,
)

class EntePhotosPage(val photos: List<EntePhoto>, val cursor: String?)

const val MAX_ENTE_VIDEO_BYTES = 200L * 1000 * 1000

/** What the picker reads from the owner's Ente library. */
interface EnteLibrary {
    suspend fun isEnteInstalled(): Boolean

    suspend fun openDriveKey(): Boolean

    suspend fun photos(cursor: String?): EntePhotosPage

    suspend fun albums(): List<EnteAlbum>

    suspend fun albumMembers(album: Uuid): List<Uuid>

    suspend fun photosByUniqueId(ids: List<Uuid>): List<EntePhoto>

    /** The original, decrypted on this device into a swept temp file; null when it's gone. */
    suspend fun exportOriginal(photo: EntePhoto, onProgress: (Float) -> Unit = {}): String?

    companion object {
        const val PAGE_SIZE = 90

        // The proxy reads at most this many albums, and memberships per album.
        const val MAX_RECORDS = 1000
    }
}

/** Ente Photos' drive, read over the network and never synced: Chat only ever reads it. */
class EntePhotosRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val payloadDownloadService: PayloadDownloadService,
    private val securityContextProvider: SecurityContextProvider,
) : EnteLibrary {
    private val drive = Uuid.parse(EntePhotosDrive.ALIAS)

    override suspend fun isEnteInstalled(): Boolean = driveQueryProvider.isAppRegistered(EntePhotosDrive.APP_SLUG)

    /** Whether this device holds the drive's key, fetching it when a grant has just brought one. */
    override suspend fun openDriveKey(): Boolean {
        if (ZkKeyStore.driveKey(drive) != null) return true
        securityContextProvider.getSecurityContext()?.let { ZkKeyStore.openDriveKeys(it) }
        return ZkKeyStore.driveKey(drive) != null
    }

    /** The whole library, newest first. */
    override suspend fun photos(cursor: String?): EntePhotosPage {
        val page = query(FileQueryParams(fileType = listOf(EntePhotosDrive.PHOTO_FILE_TYPE), fileState = listOf(FileState.Active), archivalStatus = NOT_REMOVED), cursor, EnteLibrary.PAGE_SIZE)
        return EntePhotosPage(
            photos = page.searchResults.mapNotNull { it.toEntePhoto() },
            cursor = page.cursorState?.takeIf { page.hasMoreRows && it != cursor },
        )
    }

    /** Every album, hidden ones included so their photos can be kept out of the library. */
    override suspend fun albums(): List<EnteAlbum> =
        query(FileQueryParams(fileType = listOf(EntePhotosDrive.ALBUM_FILE_TYPE), fileState = listOf(FileState.Active), archivalStatus = NOT_REMOVED), null, EnteLibrary.MAX_RECORDS)
            .searchResults.mapNotNull { it.toEnteAlbum() }

    /** The uniqueIds of an album's photos, most recently added first. */
    override suspend fun albumMembers(album: Uuid): List<Uuid> =
        query(
            FileQueryParams(fileType = listOf(EntePhotosDrive.MEMBERSHIP_FILE_TYPE), fileState = listOf(FileState.Active), groupId = listOf(album)),
            null,
            EnteLibrary.MAX_RECORDS,
        ).searchResults.mapNotNull { it.memberOf(album) }.distinct()

    /** The photos with these uniqueIds, in the order given. */
    override suspend fun photosByUniqueId(ids: List<Uuid>): List<EntePhoto> {
        if (ids.isEmpty()) return emptyList()
        val found = query(
            FileQueryParams(
                fileType = listOf(EntePhotosDrive.PHOTO_FILE_TYPE),
                fileState = listOf(FileState.Active),
                archivalStatus = NOT_REMOVED,
                clientUniqueIdAtLeastOne = ids,
            ),
            null,
            ids.size,
        ).searchResults.mapNotNull { it.toEntePhoto() }.associateBy { it.uniqueId }
        return ids.mapNotNull { found[it] }
    }

    override suspend fun exportOriginal(photo: EntePhoto, onProgress: (Float) -> Unit): String? =
        payloadDownloadService.exportToTemp(
            driveId = drive,
            fileId = photo.fileId,
            key = EntePhotosDrive.PHOTO_PAYLOAD_KEY,
            keyHeader = photo.payloadKey,
            destination = ExportDestination.UploadTemp("ente_", extensionFor(photo.original.contentType, photo.title)),
            onProgress = onProgress,
        )

    private suspend fun query(params: FileQueryParams, cursor: String?, maxRecords: Int) =
        driveQueryProvider.queryBatch(
            driveId = drive,
            request = QueryBatchRequest(
                queryParams = params,
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    cursorState = cursor,
                    maxRecords = maxRecords,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.CreatedDate,
                ),
            ),
        )
}

private const val TAG = "EntePhotos"

// Ente's metadata.fileType: 0 image, 1 video, 2 live photo.
private const val ENTE_IMAGE = 0
private const val ENTE_VIDEO = 1

// Ente's album magic metadata: subType 1 is the hidden album. Albums and photos share visibility.
private const val ENTE_SUBTYPE_DEFAULT_HIDDEN = 1
const val ENTE_VISIBLE = 0
const val ENTE_ARCHIVED = 1
const val ENTE_HIDDEN = 2

// Odin's archivalStatus 2 marks a photo in Ente's trash, or a deleted album (ente-homebase-proxy EnteDrive.Removed).
private val NOT_REMOVED = listOf(0)

private fun HomebaseFile.contentJson(): JsonObject? = runCatching {
    OdinSystemSerializer.json.parseToJsonElement(fileMetadata.appData.content.orEmpty()).jsonObject
}.getOrNull()

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun HomebaseFile.toEntePhoto(): EntePhoto? {
    if (fileMetadata.appData.fileType != EntePhotosDrive.PHOTO_FILE_TYPE) return null
    val original = fileMetadata.getPayloadDescriptor(EntePhotosDrive.PHOTO_PAYLOAD_KEY)
    val iv = original?.iv?.bytes
    if (!serverFileIsEncrypted || iv == null) {
        Logger.w(tag = TAG) { "skipping $fileId: not a Homebase-encrypted photo" }
        return null
    }
    val details = contentJson()
    val metadata = details?.obj("metadata")
    if (metadata == null) {
        Logger.w(tag = TAG) { "skipping $fileId: unreadable details" }
        return null
    }
    val contentType = original.contentType.orEmpty()
    val isVideo = when (metadata.int("fileType")) {
        ENTE_IMAGE -> if (contentType.startsWith("image/")) false else return null
        ENTE_VIDEO -> if (contentType.startsWith("video/")) true else return null
        else -> return null
    }
    return EntePhoto(
        fileId = fileId,
        uniqueId = fileMetadata.appData.uniqueId,
        title = metadata.string("title"),
        takenMicros = metadata.long("creationTime"),
        original = original,
        payloadKey = KeyHeader(iv, keyHeader.aesKey),
        isVideo = isVideo,
        durationSeconds = if (isVideo) metadata.long("duration")?.takeIf { it > 0 } else null,
        visibility = details.obj("magicMetadata")?.obj("data")?.int("visibility") ?: ENTE_VISIBLE,
    )
}

internal fun HomebaseFile.toEnteAlbum(): EnteAlbum? {
    if (fileMetadata.appData.fileType != EntePhotosDrive.ALBUM_FILE_TYPE) return null
    val id = fileMetadata.appData.uniqueId ?: return null
    val details = contentJson()
    val name = details?.string("name")
    val type = details?.string("type")
    if (name == null || type == null) {
        Logger.w(tag = TAG) { "skipping album $fileId: unreadable details" }
        return null
    }
    val settings = details.obj("magicMetadata")?.obj("data")
    val hidden = settings?.int("subType") == ENTE_SUBTYPE_DEFAULT_HIDDEN || settings?.int("visibility") == ENTE_HIDDEN
    return EnteAlbum(id = id, name = name, type = type, hidden = hidden)
}

/** A membership's photo: its tags are the photo's uniqueId and the album's. */
internal fun HomebaseFile.memberOf(album: Uuid): Uuid? {
    if (fileMetadata.appData.fileType != EntePhotosDrive.MEMBERSHIP_FILE_TYPE || fileMetadata.appData.groupId != album) return null
    return fileMetadata.appData.tags?.firstOrNull { it != album }
}

/** The albums to offer: none hidden, Favorites first, Uncategorized last, the rest by name. */
internal fun List<EnteAlbum>.offered(): List<EnteAlbum> =
    filterNot { it.hidden }.sortedWith(
        compareBy<EnteAlbum> {
            when (it.type) {
                "favorites" -> 0
                "uncategorized" -> 2
                else -> 1
            }
        }.thenBy { it.name.lowercase() },
    )

// The send path reads an attachment's type off its file name.
internal fun extensionFor(contentType: String?, title: String?): String {
    val fromTitle = title?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }?.let { ".${it.lowercase()}" }
    return when (contentType?.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        "image/avif" -> ".avif"
        "video/mp4" -> ".mp4"
        "video/quicktime" -> ".mov"
        "video/webm" -> ".webm"
        "video/x-matroska" -> ".mkv"
        "video/x-m4v" -> ".m4v"
        "video/3gpp" -> ".3gp"
        else -> fromTitle ?: if (contentType?.startsWith("video/") == true) ".mp4" else ".jpg"
    }
}
