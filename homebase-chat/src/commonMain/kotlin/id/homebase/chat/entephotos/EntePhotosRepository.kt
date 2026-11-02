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

@Immutable
data class EntePhoto(
    val fileId: Uuid,
    val title: String?,
    val takenMicros: Long?,
    val original: PayloadDescriptor,
    val payloadKey: KeyHeader,
)

class EntePhotosPage(val photos: List<EntePhoto>, val cursor: String?)

/** Ente Photos' drive, read over the network and never synced: Chat only ever reads it. */
class EntePhotosRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val payloadDownloadService: PayloadDownloadService,
    private val securityContextProvider: SecurityContextProvider,
) {
    private val drive = Uuid.parse(EntePhotosDrive.ALIAS)

    suspend fun isEnteInstalled(): Boolean = driveQueryProvider.isAppRegistered(EntePhotosDrive.APP_SLUG)

    /** Whether this device holds the drive's key, fetching it when a grant has just brought one. */
    suspend fun openDriveKey(): Boolean {
        if (ZkKeyStore.driveKey(drive) != null) return true
        securityContextProvider.getSecurityContext()?.let { ZkKeyStore.openDriveKeys(it) }
        return ZkKeyStore.driveKey(drive) != null
    }

    suspend fun photos(cursor: String?): EntePhotosPage {
        val page = driveQueryProvider.queryBatch(
            driveId = drive,
            request = QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(EntePhotosDrive.PHOTO_FILE_TYPE), fileState = listOf(FileState.Active)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    cursorState = cursor,
                    maxRecords = PAGE_SIZE,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.CreatedDate,
                ),
            ),
        )
        return EntePhotosPage(
            photos = page.searchResults.mapNotNull { it.toEntePhoto() },
            cursor = page.cursorState?.takeIf { page.hasMoreRows && it != cursor },
        )
    }

    /** The original, decrypted on this device into a swept temp file; null when it's gone. */
    suspend fun exportOriginal(photo: EntePhoto): String? =
        payloadDownloadService.exportToTemp(
            driveId = drive,
            fileId = photo.fileId,
            key = EntePhotosDrive.PHOTO_PAYLOAD_KEY,
            keyHeader = photo.payloadKey,
            destination = ExportDestination.UploadTemp("ente_", extensionFor(photo.original.contentType, photo.title)),
        )

    private companion object {
        const val PAGE_SIZE = 90
    }
}

private const val TAG = "EntePhotos"

// Ente's metadata.fileType: 0 image, 1 video, 2 live photo.
private const val ENTE_IMAGE = 0

internal fun HomebaseFile.toEntePhoto(): EntePhoto? {
    if (fileMetadata.appData.fileType != EntePhotosDrive.PHOTO_FILE_TYPE) return null
    val original = fileMetadata.getPayloadDescriptor(EntePhotosDrive.PHOTO_PAYLOAD_KEY)
    val iv = original?.iv?.bytes
    if (!serverFileIsEncrypted || iv == null) {
        Logger.w(tag = TAG) { "skipping $fileId: not a Homebase-encrypted photo" }
        return null
    }
    val metadata = runCatching {
        OdinSystemSerializer.json.parseToJsonElement(fileMetadata.appData.content.orEmpty()).jsonObject["metadata"] as? JsonObject
    }.getOrNull()
    if (metadata == null) {
        Logger.w(tag = TAG) { "skipping $fileId: unreadable details" }
        return null
    }
    val enteType = (metadata["fileType"] as? JsonPrimitive)?.intOrNull
    if (enteType != ENTE_IMAGE || original.contentType?.startsWith("image/") != true) return null
    return EntePhoto(
        fileId = fileId,
        title = (metadata["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        takenMicros = (metadata["creationTime"] as? JsonPrimitive)?.longOrNull,
        original = original,
        payloadKey = KeyHeader(iv, keyHeader.aesKey),
    )
}

// The send path reads an attachment's type off its file name.
internal fun extensionFor(contentType: String?, title: String?): String =
    when (contentType?.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        "image/avif" -> ".avif"
        else -> title?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }?.let { ".${it.lowercase()}" } ?: ".jpg"
    }
