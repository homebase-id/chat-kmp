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

// Aggregates the FeedDrive (where followed identities' posts land) and the user's own public-channel drive,
// deduped by uniqueId and sorted newest-published-first. All-local: posts arrive with their payloads, so no
// peer payload reads are needed.
class FeedTimelineService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "FeedTimelineService"

        // Rows per drive per page. Well clear of the screen's 4-item load-more threshold, so the trigger has slack.
        private const val PageSize = 30

        // Pages a post-sync top-up walks before giving up on an overlap. Only a sync landing more than
        // 5 x PageSize new posts ahead of the head can reach it; pull-to-refresh closes any gap it leaves.
        private const val TopUpPageCap = 5
    }

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private val sourceDrives = setOf(feedDrive, channelDrive)

    // `byId` and the paging state are mutated from two coroutines on Dispatchers.Default, so every read,
    // write and iteration of them (including the emitSorted snapshot) is guarded by `lock`.
    private val lock = SynchronizedObject()

    // Keyed by uniqueId for O(1) upsert/remove + automatic dedup across drives.
    private val byId = mutableMapOf<Uuid, FeedPostItem>()

    // drive → cursor for the next page (null = its first). A drive is dropped once QueryBatch reports no more
    // rows, so an empty map means every source drive is exhausted.
    private val nextPage = mutableMapOf<Uuid, QueryBatchCursor?>()

    // The scroll trigger fires repeatedly, and a second fetch would read the same cursors and re-append the page.
    private var loadingMore = false

    // Bumped by every rebuild. A page fetch that started before the bump and lands after it is discarded, so it
    // can neither resurrect dropped posts nor write its stale, deeper cursor over the rewound one.
    private var generation = 0

    // True once loadMore has appended a page; a completed sync then tops up from the head rather than
    // cold-loading, which would throw those pages away.
    private var pagedPastFirstPage = false

    private val _timeline = MutableStateFlow<List<FeedPostItem>>(emptyList())
    val timeline: StateFlow<List<FeedPostItem>> = _timeline.asStateFlow()

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    // A failed cold load is still swallowed (an exception must not kill the event collector), but the UI needs
    // the message: without it the ViewModel can't tell "the read blew up" from "you follow nobody".
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var started = false

    init {
        rewindPaging()
    }

    /** Caller must hold [lock] (except at construction). */
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
                            // A cold load rewinds every cursor — what a first sync into a near-empty index
                            // needs — but drops every page past the first. Once the user has paged, merge instead.
                            val paged = synchronized(lock) { pagedPastFirstPage }
                            Logger.d(tag = TAG) {
                                "DriveEvent.Stopped(drive=${event.driveId}, " +
                                    "totalCount=${event.totalCount}) — " +
                                    if (paged) "topping up feed" else "re-cold-loading feed"
                            }
                            scope.launch {
                                if (paged) topUpFromHead(event.driveId) else coldLoad()
                            }
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

    // Rewinds the per-drive cursors so a re-cold-load converges exactly instead of keeping the paged depth.
    private suspend fun coldLoad() {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            val gen = synchronized(lock) { ++generation }

            // Query outside the lock, then apply the rebuild atomically so the collector never sees a half-cleared map.
            val pages = sourceDrives.associateWith { queryPage(identityId, it, cursor = null) }

            val size = synchronized(lock) {
                if (generation != gen) return@synchronized null
                byId.clear()
                nextPage.clear()
                pagedPastFirstPage = false
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
            _loadError.value = null
            emitSorted()
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed: ${e.message}" }
            _loadError.value = e.message ?: e::class.simpleName ?: "cold-load failed"
        }
    }

    // Walks down from the head until it meets a post already held — that overlap keeps the merged set contiguous
    // with the pages the user scrolled through — and leaves every cursor untouched.
    private suspend fun topUpFromHead(driveId: Uuid) {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()
            val gen = synchronized(lock) { generation }

            var cursor: QueryBatchCursor? = null
            repeat(TopUpPageCap) {
                val page = queryPage(identityId, driveId, cursor)
                if (page.records.isEmpty()) return
                val (stale, overlaps) = synchronized(lock) {
                    val overlap = page.records.any { feedIdOf(it)?.let(byId::containsKey) == true }
                    (generation != gen) to overlap
                }
                // A refresh rewound everything while this walk was in flight; its pages win.
                if (stale) return
                processIncrementalBatch(page.records)
                if (overlaps || !page.hasMoreRows) return
                cursor = page.cursor
            }
        } catch (e: Exception) {
            // Log-only: nothing was discarded and the user asked for nothing. A user-initiated load uses [loadError].
            Logger.e(throwable = e, tag = TAG) { "Top-up after sync failed: ${e.message}" }
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

    /** Caller must hold [lock]. Keeps the newer-published copy. */
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

    suspend fun refresh() = coldLoad()

    // A followed identity's post lands on the feed drive as a reference with NO uniqueId, only a globalTransitId.
    // Keying on uniqueId alone dropped every one, so live pushes for followed posts never applied incrementally.
    private fun feedIdOf(file: HomebaseFile): Uuid? =
        file.fileMetadata.appData.uniqueId ?: file.fileMetadata.globalTransitId

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val posts = files.filter {
            it.fileMetadata.appData.fileType == FeedProtocol.PostFileType
        }
        if (posts.isEmpty()) return

        val changed = synchronized(lock) {
            var dirty = false
            for (file in posts) {
                val id = feedIdOf(file) ?: continue
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

    // Safe to call repeatedly from the scroll trigger: a fetch already in flight makes the call a no-op, so each
    // cursor is consumed once and only moves forward.
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
                pagedPastFirstPage = true
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

    // `started` stays set (app-scoped collector).
    fun reset() {
        synchronized(lock) {
            byId.clear()
            rewindPaging()
            pagedPastFirstPage = false
            // Discard any page fetch still in flight for the identity being logged out.
            generation++
        }
        _loadError.value = null
        emitSorted()
    }
}
