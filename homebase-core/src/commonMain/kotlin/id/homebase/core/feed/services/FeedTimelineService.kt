package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.feedLabeledDrive
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Live view of the user's home timeline. Aggregates two local drives:
 *
 *  1. The **FeedDrive** ([feedLabeledDrive]) — where followed identities' posts land via the
 *     server's follower/inbox system (drained for free during query-batch; see Task 0 findings).
 *  2. The user's **own** public-channel drive ([SystemDriveConstants.publicPostChannelDrive]) —
 *     so the author sees their own posts in their home feed too.
 *
 * Modeled on [id.homebase.core.moments.services.MomentsFeedService]:
 *   - On [start], a cold-load via `QueryBatch.queryBatchAsync`
 *     (`filetypesAnyOf = [PostFileType]`, NewestFirst / UserDate) reads the *first page* of each
 *     drive from the local DB (no HTTP); [loadMore] walks each drive's cursor for older pages.
 *   - `BatchReceived` for either drive is applied incrementally (live WebSocket pushes).
 *   - `DriveEvent.Stopped(totalCount > 0)` re-cold-loads — bulk `DriveSync.performSync` is silent.
 *
 * Posts are deduped by uniqueId (a post that exists on both the author's channel and their feed
 * drive is shown once) and sorted newest-published-first by [FeedPostItem.createdMs].
 *
 * v1 is ALL-LOCAL: posts arrive with their payloads, so no peer payload reads are needed.
 */
class FeedTimelineService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "FeedTimelineService"

        // Rows per drive per page. Well clear of the screen's 4-item load-more threshold, so one
        // page is several screens of (tall) post cards and the trigger has slack, while a cold-load
        // deserializes at most 2 x PageSize headers instead of the whole local index.
        private const val PageSize = 30
    }

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    /** The two drives this timeline aggregates. */
    private val sourceDrives = setOf(feedDrive, channelDrive)

    // `byId` and the paging state are mutated from two coroutines on Dispatchers.Default —
    // coldLoad / loadMore (their own launches) and the event collector (incremental batch /
    // rollback / reset) — so every read/write/iteration of them (including the emitSorted
    // snapshot) is guarded by `lock`.
    private val lock = SynchronizedObject()

    // Keyed by uniqueId for O(1) upsert/remove + automatic dedup across drives.
    private val byId = mutableMapOf<Uuid, FeedPostItem>()

    // drive → cursor for the page to read next (null = its first page). A drive is dropped once
    // QueryBatch reports no more rows, so an empty map means every source drive is exhausted.
    private val nextPage = mutableMapOf<Uuid, QueryBatchCursor?>()

    // Set for the duration of a loadMore fetch. The scroll trigger fires repeatedly, and a second
    // fetch would read the same cursors and append the same page again.
    private var loadingMore = false

    // Bumped by every rebuild (coldLoad, reset). A page fetch that started before the bump and
    // lands after it is discarded, so it can neither resurrect posts the rebuild dropped nor write
    // its stale, deeper cursor over the one the rebuild just rewound.
    private var generation = 0

    private val _timeline = MutableStateFlow<List<FeedPostItem>>(emptyList())
    val timeline: StateFlow<List<FeedPostItem>> = _timeline.asStateFlow()

    /** True once every source drive's cursor is depleted — the UI then stops calling [loadMore]. */
    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    private var started = false

    init {
        rewindPaging()
    }

    /** Caller must hold [lock] (except at construction). Puts every drive back on its first page. */
    private fun rewindPaging() {
        nextPage.clear()
        for (drive in sourceDrives) nextPage[drive] = null
    }

    fun start() {
        if (started) return
        started = true

        Logger.i(tag = TAG) {
            "start: cold-loading + subscribing for feedDrive=$feedDrive channelDrive=$channelDrive"
        }

        scope.launch { coldLoad() }

        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.SessionEnded -> reset()
                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId !in sourceDrives) return@collect
                        processIncrementalBatch(event.batchData)
                    }
                    is BackendEvent.DriveEvent.Stopped -> {
                        if (event.driveId !in sourceDrives) return@collect
                        if (event.totalCount > 0) {
                            Logger.d(tag = TAG) {
                                "DriveEvent.Stopped(drive=${event.driveId}, " +
                                    "totalCount=${event.totalCount}) — re-cold-loading feed"
                            }
                            scope.launch { coldLoad() }
                        }
                    }
                    is BackendEvent.OutboxEvent.OptimisticRollback -> {
                        if (event.driveId !in sourceDrives) return@collect
                        val removed = synchronized(lock) { byId.remove(event.uniqueId) != null }
                        if (removed) {
                            Logger.d(tag = TAG) {
                                "OptimisticRollback: removed post=${event.uniqueId}"
                            }
                            emitSorted()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Read the first page of each source drive in the local DB, newest first, and replace the
     * in-memory map with it — rewinding the per-drive cursors so a re-cold-load (after Stopped, or
     * a pull-to-refresh) converges exactly instead of keeping whatever depth the user paged to.
     */
    private suspend fun coldLoad() {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            val gen = synchronized(lock) { ++generation }

            // Query (suspend) outside the lock; collect every drive's page into a local, then
            // apply the rebuild atomically so the event collector never observes a half-cleared map.
            val pages = sourceDrives.associateWith { queryPage(identityId, it, cursor = null) }

            val size = synchronized(lock) {
                if (generation != gen) return@synchronized null
                byId.clear()
                nextPage.clear()
                for ((drive, page) in pages) {
                    if (page.hasMoreRows) nextPage[drive] = page.cursor
                    mergeLocked(page.records)
                }
                byId.size
            } ?: return

            Logger.i(tag = TAG) {
                "coldLoad: feedSize=$size (scanned=${pages.values.sumOf { it.records.size }}, " +
                    "drivesWithMore=${pages.count { it.value.hasMoreRows }})"
            }
            emitSorted()
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed: ${e.message}" }
        }
    }

    private suspend fun queryPage(identityId: Uuid, drive: Uuid, cursor: QueryBatchCursor?) =
        QueryBatch(identityId).queryBatchAsync(
            dbm = databaseManager,
            driveId = drive,
            noOfItems = PageSize,
            cursor = cursor,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(FeedProtocol.PostFileType),
        )

    /** Caller must hold [lock]. Dedups across drives by uniqueId, keeping the newer-published copy. */
    private fun mergeLocked(records: List<HomebaseFile>) {
        for (file in records) {
            if (file.isSoftDeleted()) continue
            val item = file.toFeedPostItem() ?: continue
            val existing = byId[item.id]
            if (existing == null || item.createdMs >= existing.createdMs) {
                byId[item.id] = item
            }
        }
    }

    /**
     * Pull-to-refresh: re-run the cold load so the local index is re-read. The UI ViewModel calls
     * this; the same [coldLoad] also runs automatically on a `DriveEvent.Stopped` with new files.
     */
    suspend fun refresh() = coldLoad()

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val posts = files.filter {
            it.fileMetadata.appData.fileType == FeedProtocol.PostFileType
        }
        if (posts.isEmpty()) return

        val changed = synchronized(lock) {
            var dirty = false
            for (file in posts) {
                // Same id fallback the cold-load mapper uses: a followed identity's post lands on
                // the feed drive as a reference with NO uniqueId, only a globalTransitId. Keying on
                // uniqueId alone dropped every one of them here, so a live push for a followed post
                // never applied incrementally — it only appeared on the next cold load.
                val id = file.fileMetadata.appData.uniqueId
                    ?: file.fileMetadata.globalTransitId
                    ?: continue
                if (file.isSoftDeleted()) {
                    if (byId.remove(id) != null) dirty = true
                    continue
                }
                val item = file.toFeedPostItem() ?: continue
                byId[id] = item
                dirty = true
            }
            dirty
        }
        if (changed) emitSorted()
    }

    private fun emitSorted() {
        val (sorted, exhausted) = synchronized(lock) {
            byId.values.sortedByDescending { it.createdMs } to nextPage.isEmpty()
        }
        _timeline.value = sorted
        _endReached.value = exhausted
    }

    /**
     * Pull older posts: one more page from every source drive that still has rows, appended to the
     * in-memory map. Safe to call repeatedly from the scroll trigger — a fetch already in flight
     * makes the call a no-op, so each drive's cursor is consumed once and only ever moves forward.
     * Returns immediately once every drive is depleted ([endReached]).
     */
    suspend fun loadMore() {
        val active = credentialsManager.getActiveCredentials() ?: return
        val identityId = active.getIdentityId()

        val claim = synchronized(lock) {
            if (loadingMore || nextPage.isEmpty()) {
                null
            } else {
                loadingMore = true
                generation to nextPage.toMap()
            }
        } ?: return
        val (gen, pending) = claim

        try {
            val pages = pending.mapValues { (drive, cursor) -> queryPage(identityId, drive, cursor) }

            val applied = synchronized(lock) {
                if (generation != gen) return@synchronized false
                for ((drive, page) in pages) {
                    if (page.hasMoreRows) nextPage[drive] = page.cursor else nextPage.remove(drive)
                    mergeLocked(page.records)
                }
                true
            }
            if (applied) {
                emitSorted()
                Logger.d(tag = TAG) {
                    "loadMore: paged ${pending.keys.size} drive(s), " +
                        "fetched=${pages.values.sumOf { it.records.size }} " +
                        "feedSize=${_timeline.value.size} endReached=${_endReached.value}"
                }
            }
        } finally {
            synchronized(lock) { loadingMore = false }
        }
    }

    /**
     * Logout: drop the previous identity's feed and rewind paging to the first page.
     * `started` stays set (app-scoped collector).
     */
    fun reset() {
        synchronized(lock) {
            byId.clear()
            rewindPaging()
            // Discard any page fetch still in flight for the identity being logged out.
            generation++
        }
        emitSorted()
    }
}
