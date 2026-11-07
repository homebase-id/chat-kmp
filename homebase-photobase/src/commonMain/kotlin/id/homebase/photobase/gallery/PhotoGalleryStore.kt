package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtcRange
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSync
import id.homebase.api.sync.DriveSyncPolicy
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * How far back the device holds every photo. Records can arrive out of order, because a freshly
 * backed-up 2015 photo syncs down straight away, so "oldest local record" says nothing about what's
 * in between. The gallery only shows the unbroken stretch down to [floorMs], and each server page
 * moves it further back.
 */
@Serializable
internal data class GalleryCoverage(
    val floorMs: Long? = null,
    val serverCursor: String? = null,
    val reachedTheEnd: Boolean = false,
)

internal class LocalPhotoPage(val files: List<HomebaseFile>, val hasMore: Boolean, val cursor: String)

internal class ServerPhotoPage(val files: List<HomebaseFile>, val hasMore: Boolean, val cursor: String?)

/** Everything the store needs from outside, so its paging rules are testable by themselves. */
internal interface PhotoGalleryBackend {
    suspend fun coverage(): GalleryCoverage

    suspend fun saveCoverage(coverage: GalleryCoverage)

    suspend fun localPage(cursor: String?, floorMs: Long?, limit: Int): LocalPhotoPage

    suspend fun serverPage(cursor: String?, limit: Int): ServerPhotoPage

    suspend fun save(files: List<HomebaseFile>)
}

/**
 * Newest first comes from the device, fetching older pages from the server as scrolling reaches the
 * end of what's held. Oldest first would need the far end of the library, so it stays on the server.
 */
class PhotoGalleryStore internal constructor(
    private val backend: PhotoGalleryBackend,
    private val oldestFirstSource: PhotoGalleryPageSource,
) : PhotoGalleryPageSource {

    override suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage {
        if (!newestFirst) return oldestFirstSource.page(cursor, newestFirst = false)
        return withContext(Dispatchers.Default) {
            val limit = if (cursor == null) FIRST_PAGE_SIZE else PAGE_SIZE
            var coverage = backend.coverage()
            while (true) {
                val floor = if (coverage.reachedTheEnd) null else coverage.floorMs
                val local = if (coverage.floorMs == null && !coverage.reachedTheEnd) null else backend.localPage(cursor, floor, limit)
                if (local != null && local.files.isNotEmpty()) {
                    val items = local.files.mapNotNull { it.toGalleryItem() }
                    return@withContext PhotoGalleryPage(
                        items,
                        nextCursor = local.cursor.takeIf { local.hasMore || !coverage.reachedTheEnd },
                    )
                }
                if (coverage.reachedTheEnd) return@withContext PhotoGalleryPage(emptyList(), null)
                coverage = fetchOlder(coverage, limit)
            }
            @Suppress("UNREACHABLE_CODE")
            PhotoGalleryPage(emptyList(), null)
        }
    }

    /** Brings the next server page onto the device, and moves the unbroken stretch back to cover it. */
    private suspend fun fetchOlder(coverage: GalleryCoverage, limit: Int): GalleryCoverage {
        val page = backend.serverPage(coverage.serverCursor, limit)
        backend.save(page.files)
        val oldest = page.files.minOfOrNull { it.sqlUserDateMs() }
        val updated = GalleryCoverage(
            floorMs = listOfNotNull(coverage.floorMs, oldest).minOrNull(),
            serverCursor = page.cursor ?: coverage.serverCursor,
            reachedTheEnd = !page.hasMore,
        )
        backend.saveCoverage(updated)
        return updated
    }

    private companion object {
        const val FIRST_PAGE_SIZE = 40
        const val PAGE_SIZE = 100
    }
}

fun photoGalleryStore(
    db: DatabaseManager,
    credentials: CredentialsManager,
    queryProvider: DriveQueryProvider,
    oldestFirstSource: PhotoGalleryPageSource,
) = PhotoGalleryStore(
    DrivePhotoGalleryBackend(db, { credentials.requireActiveCredentials().getIdentityId() }, queryProvider),
    oldestFirstSource,
)

/** The real backend: the local index, the drive's server query, and coverage in the key-value store. */
internal class DrivePhotoGalleryBackend(
    private val db: DatabaseManager,
    private val identityId: suspend () -> Uuid,
    private val queryProvider: DriveQueryProvider,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoGalleryBackend {
    private val fileProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(db)

    override suspend fun coverage(): GalleryCoverage =
        db.keyValue.selectByKey(COVERAGE_KEY) { _, data -> data }?.let { bytes ->
            runCatching { OdinSystemSerializer.deserialize<GalleryCoverage>(bytes.decodeToString()) }.getOrNull()
        } ?: GalleryCoverage()

    override suspend fun saveCoverage(coverage: GalleryCoverage) {
        db.keyValue.upsertValue(COVERAGE_KEY, OdinSystemSerializer.serialize(coverage).encodeToByteArray())
    }

    override suspend fun localPage(cursor: String?, floorMs: Long?, limit: Int): LocalPhotoPage {
        val result = QueryBatch(identityId()).queryBatchAsync(
            dbm = db,
            driveId = photosDriveId,
            noOfItems = limit,
            cursor = cursor?.let { QueryBatchCursor.fromJson(it) },
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            fileState = FileStateFilter.Active,
            filetypesAnyOf = listOf(PhotobaseProtocol.PhotoFileType),
            userDateSpan = floorMs?.let { UnixTimeUtcRange(UnixTimeUtc(it), UnixTimeUtc(Long.MAX_VALUE)) },
        )
        return LocalPhotoPage(result.records, result.hasMoreRows, result.cursor.toJson())
    }

    override suspend fun serverPage(cursor: String?, limit: Int): ServerPhotoPage {
        val response = queryProvider.queryBatch(
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
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.UserDate,
                ),
            ),
        )
        return ServerPhotoPage(response.searchResults, response.hasMoreRows, response.cursorState)
    }

    // cursor = null, so fetching old photos never moves the sync cursor.
    override suspend fun save(files: List<HomebaseFile>) {
        if (files.isEmpty()) return
        fileProcessor.baseUpsertEntryZapZap(identityId(), photosDriveId, files, cursor = null)
    }

    private companion object {
        val COVERAGE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000b0a03")
    }
}

/**
 * Keeps the device's Photos records current. On purpose this isn't a mounted drive: mounting would
 * join every background wake's `syncAll` and the WebSocket, so it runs only when the gallery asks.
 */
class PhotoGallerySync(
    private val db: DatabaseManager,
    private val credentials: CredentialsManager,
    private val queryProvider: DriveQueryProvider,
    private val eventBus: EventBus,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) {
    suspend fun syncNow() {
        val sync = DriveSync(
            identityId = credentials.requireActiveCredentials().getIdentityId(),
            driveId = photosDriveId,
            driveQueryProvider = queryProvider,
            databaseManager = db,
            eventBus = eventBus,
            // Only changes from now on: older photos arrive by fetching pages. A day's overlap
            // covers a device clock running ahead of the server's.
            policy = DriveSyncPolicy(fullSyncWindow = 1.days),
        )
        val job = sync.sync()
        if (job == null) Logger.i(tag = "PhotoGallerySync") { "sync already running" }
        job?.join()
    }
}
