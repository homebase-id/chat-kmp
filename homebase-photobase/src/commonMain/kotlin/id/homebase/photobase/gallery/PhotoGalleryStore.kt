package id.homebase.photobase.gallery

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtcRange
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSync
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val fetching = Mutex()
    private var fetchInFlight: Job? = null

    // Only changes while holding [fetching], so a fetch that waited sees it once it gets the lock.
    private var generation = 0

    /** Before the local database is wiped: returns once no fetch can write into the next account's. */
    suspend fun stop() {
        fetchInFlight?.cancel()
        fetching.withLock { generation++ }
    }

    override suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage =
        if (newestFirst) withContext(Dispatchers.Default) { newestFirstPage(cursor) } else oldestFirstSource.page(cursor, newestFirst = false)

    private suspend fun newestFirstPage(cursor: String?): PhotoGalleryPage {
        val limit = galleryPageSize(cursor)
        var coverage = backend.coverage()
        while (true) {
            if (coverage.floorMs != null || coverage.reachedTheEnd) {
                val floor = if (coverage.reachedTheEnd) null else coverage.floorMs
                val local = backend.localPage(cursor, floor, limit)
                if (local.files.isNotEmpty()) {
                    return PhotoGalleryPage(
                        local.files.mapNotNull { it.toGalleryItem() },
                        nextCursor = local.cursor.takeIf { local.hasMore || !coverage.reachedTheEnd },
                    )
                }
            }
            if (coverage.reachedTheEnd) return PhotoGalleryPage(emptyList(), null)
            coverage = fetchOlder(coverage, limit)
        }
    }

    /**
     * Brings the next server page onto the device and moves the unbroken stretch back to cover it.
     * The lock is because the grid and the prefetcher both page: whoever waited re-reads coverage
     * and uses what the other fetched, rather than fetching it again and saving an older position.
     */
    private suspend fun fetchOlder(seen: GalleryCoverage, limit: Int): GalleryCoverage {
        val startedIn = generation
        return fetching.withLock {
            if (generation != startedIn) throw CancellationException("The gallery was stopped")
            fetchInFlight = currentCoroutineContext()[Job]
            try {
                fetchOlderLocked(seen, limit)
            } finally {
                fetchInFlight = null
            }
        }
    }

    private suspend fun fetchOlderLocked(seen: GalleryCoverage, limit: Int): GalleryCoverage {
        val coverage = backend.coverage()
        if (coverage != seen) return coverage
        val page = backend.serverPage(coverage.serverCursor, limit)
        currentCoroutineContext().ensureActive()
        backend.save(page.files)
        // The floor stops just above the oldest date on the page: the next page can hold more photos
        // from that same moment, and a local cursor that had passed them would never see them.
        val oldest = page.files.minOfOrNull { it.sqlUserDateMs() }
        val updated = GalleryCoverage(
            floorMs = listOfNotNull(coverage.floorMs, oldest?.plus(1)).minOrNull(),
            serverCursor = page.cursor ?: coverage.serverCursor,
            // A cursor that doesn't advance would ask for the same page forever.
            reachedTheEnd = !page.hasMore || page.cursor == null || page.cursor == coverage.serverCursor,
        )
        currentCoroutineContext().ensureActive()
        backend.saveCoverage(updated)
        return updated
    }
}

fun photoGalleryStore(
    db: DatabaseManager,
    credentials: CredentialsManager,
    repository: PhotoGalleryRepository,
) = PhotoGalleryStore(
    DrivePhotoGalleryBackend(db, { credentials.requireActiveCredentials().getIdentityId() }, repository),
    repository,
)

/** The real backend: the local index, the drive's server query, and coverage in the key-value store. */
internal class DrivePhotoGalleryBackend(
    private val db: DatabaseManager,
    private val identityId: suspend () -> Uuid,
    private val repository: PhotoGalleryRepository,
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
        val response = repository.query(cursor, limit, QueryBatchSortOrder.NewestFirst)
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
    private val mutex = Mutex()
    private var running: Job? = null
    private var roundScope: CoroutineScope? = null

    // A second caller joins the round in flight: cancelling a join leaves the round running, so a
    // new DriveSync here would crawl from the same saved cursor alongside it.
    suspend fun syncNow() {
        val job = mutex.withLock {
            running?.takeIf { it.isActive } ?: withContext(Dispatchers.Default) {
                seedPhotoSyncCursor(db, photosDriveId, UnixTimeUtc.now())
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { roundScope = it }
                DriveSync(
                    identityId = credentials.requireActiveCredentials().getIdentityId(),
                    driveId = photosDriveId,
                    driveQueryProvider = queryProvider,
                    databaseManager = db,
                    eventBus = eventBus,
                    scope = scope,
                ).sync()
            }.also { running = it }
        }
        job?.join()
    }

    /** Before the local database is wiped: a round still running would write into the next account's. */
    suspend fun stop() = mutex.withLock {
        // The scope and not just the round: its database writes are siblings of the round's job.
        roundScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        roundScope = null
        running = null
    }
}

/**
 * Only changes from now on: older photos arrive by fetching pages. Saved here because DriveSync only
 * saves a cursor with a batch of rows, so after a quiet first round the next would re-seed later and
 * skip whatever changed in between. A day's overlap covers a device clock running ahead of the server's.
 */
internal suspend fun seedPhotoSyncCursor(db: DatabaseManager, driveId: Uuid, now: UnixTimeUtc) {
    val storage = CursorStorage(db, driveId)
    if (storage.loadCursor() == null) {
        storage.saveCursor(QueryBatchCursor.fromStartPoint(now.addMilliseconds(-1.days.inWholeMilliseconds)))
    }
}
