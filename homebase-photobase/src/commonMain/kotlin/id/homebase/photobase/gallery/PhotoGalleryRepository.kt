package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.backup.PhotoContent
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

data class PhotoGalleryItem(
    val fileId: Uuid,
    val driveId: Uuid,
    val capturedAtMs: Long,
    val keyHeader: KeyHeader,
    /** The still, or the video. A Live Photo's paired video stays out of the gallery until playback is built. */
    val payload: PayloadDescriptor,
    val tinyPreview: EmbeddedThumb?,
    val name: String?,
)

data class PhotoGalleryPage(val items: List<PhotoGalleryItem>, val nextCursor: String?)

fun interface PhotoGalleryPageSource {
    suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage
}

/** The one server query for the library. Network only, and it serves oldest first directly: the device can't. */
class PhotoGalleryRepository(
    private val queryProvider: DriveQueryProvider,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoGalleryPageSource {
    internal suspend fun query(cursor: String?, limit: Int, ordering: QueryBatchSortOrder) = queryProvider.queryBatch(
        photosDriveId,
        QueryBatchRequest(
            queryParams = FileQueryParams(
                fileType = listOf(PhotobaseProtocol.PhotoFileType),
                fileState = listOf(FileState.Active),
            ),
            resultOptionsRequest = QueryBatchResultOptionsRequest(
                cursorState = cursor,
                maxRecords = limit,
                includeMetadataHeader = true,
                ordering = ordering,
                sorting = QueryBatchSortField.UserDate,
            ),
        ),
    )

    // Parsing and decrypting a page of headers doesn't belong on the view model's Main.
    override suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage = withContext(Dispatchers.Default) {
        val querying = TimeSource.Monotonic.markNow()
        val ordering = if (newestFirst) QueryBatchSortOrder.NewestFirst else QueryBatchSortOrder.OldestFirst
        val response = query(cursor, galleryPageSize(cursor), ordering)
        val queryTook = querying.elapsedNow()
        val parsing = TimeSource.Monotonic.markNow()
        val items = response.searchResults.mapNotNull { it.toGalleryItem() }
        Logger.i(tag = "PhotoGallery") {
            "page query=$queryTook parse=${parsing.elapsedNow()} newestFirst=$newestFirst first=${items.firstOrNull()?.capturedAtMs} last=${items.lastOrNull()?.capturedAtMs} count=${items.size}"
        }
        PhotoGalleryPage(
            items = items,
            nextCursor = response.cursorState?.takeIf { response.hasMoreRows && it != cursor },
        )
    }
}

// A phone shows about 24 tiles, and the first page is what the first screen waits on.
internal fun galleryPageSize(cursor: String?) = if (cursor == null) 40 else 100

internal fun HomebaseFile.toGalleryItem(): PhotoGalleryItem? {
    val appData = fileMetadata.appData
    if (appData.fileType != PhotobaseProtocol.PhotoFileType) return null
    val payloads = fileMetadata.payloads.orEmpty()
    val payload = payloads.firstOrNull { it.key != PhotobaseProtocol.LivePhotoVideoPayloadKey } ?: return null
    val content = appData.content?.let { raw -> runCatching { OdinSystemSerializer.deserialize<PhotoContent>(raw) }.getOrNull() }
    return PhotoGalleryItem(
        fileId = fileId,
        driveId = driveId,
        capturedAtMs = appData.userDate ?: fileMetadata.created.milliseconds,
        keyHeader = keyHeader,
        payload = payload,
        tinyPreview = content?.tiny?.let { EmbeddedThumb(content.tinyWidth ?: 0, content.tinyHeight ?: 0, content.tinyType, it) },
        name = content?.name,
    )
}
