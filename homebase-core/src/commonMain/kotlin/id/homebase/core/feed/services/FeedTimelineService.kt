package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
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
 *   - On [start], a one-shot cold-load via `QueryBatch.queryBatchAsync`
 *     (`filetypesAnyOf = [PostFileType]`, NewestFirst / UserDate) over each drive populates the
 *     feed immediately from the local DB (no HTTP).
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
        private const val ColdLoadPageSize = 1000
    }

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    /** The two drives this timeline aggregates. */
    private val sourceDrives = setOf(feedDrive, channelDrive)

    // `byId` is mutated from two coroutines on Dispatchers.Default — coldLoad (its own launch) and
    // the event collector (incremental batch / rollback / reset) — so every read/write/iteration of
    // it (including the emitSorted snapshot) is guarded by `lock`.
    private val lock = SynchronizedObject()

    // Keyed by uniqueId for O(1) upsert/remove + automatic dedup across drives.
    private val byId = mutableMapOf<Uuid, FeedPostItem>()

    private val _timeline = MutableStateFlow<List<FeedPostItem>>(emptyList())
    val timeline: StateFlow<List<FeedPostItem>> = _timeline.asStateFlow()

    private var started = false

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
     * One-shot fetch of every post on both source drives in the local DB, newest first.
     * Clears the in-memory map first so a re-cold-load (after Stopped) converges exactly.
     */
    private suspend fun coldLoad() {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            // Query (suspend) outside the lock; collect every drive's records into a local, then
            // apply the rebuild atomically so the event collector never observes a half-cleared map.
            val scanned = mutableListOf<FeedPostItem>()
            for (drive in sourceDrives) {
                val result = QueryBatch(identityId).queryBatchAsync(
                    dbm = databaseManager,
                    driveId = drive,
                    noOfItems = ColdLoadPageSize,
                    cursor = null,
                    sortOrder = QueryBatchSortOrder.NewestFirst,
                    sortField = QueryBatchSortField.UserDate,
                    fileSystemType = 0,
                    filetypesAnyOf = listOf(FeedProtocol.PostFileType),
                )
                result.records
                    .filterNot { it.isSoftDeleted() }
                    .mapNotNullTo(scanned) { it.toFeedPostItem() }
            }

            val size = synchronized(lock) {
                byId.clear()
                for (item in scanned) {
                    // Dedup across drives by uniqueId; keep whichever copy is newer-published.
                    val existing = byId[item.id]
                    if (existing == null || item.createdMs >= existing.createdMs) {
                        byId[item.id] = item
                    }
                }
                byId.size
            }
            Logger.i(tag = TAG) { "coldLoad: feedSize=$size (scanned=${scanned.size})" }
            emitSorted()
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed: ${e.message}" }
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
                val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
                if (file.isSoftDeleted()) {
                    if (byId.remove(uniqueId) != null) dirty = true
                    continue
                }
                val item = file.toFeedPostItem() ?: continue
                byId[uniqueId] = item
                dirty = true
            }
            dirty
        }
        if (changed) emitSorted()
    }

    private fun emitSorted() {
        val sorted = synchronized(lock) { byId.values.sortedByDescending { it.createdMs } }
        _timeline.value = sorted
    }

    /**
     * Pull older posts. v1 keeps the full local set in memory after [coldLoad] (a single drive
     * scan is cheap), so there is nothing further to page — kept on the interface so the UI can
     * call it on scroll without branching, and so a future cursored variant can slot in here.
     */
    suspend fun loadMore() {
        // No-op for v1: cold-load already holds the full local timeline. The Stopped re-cold-load
        // path keeps it current as sync drains the inbox.
    }

    /** Logout: drop the previous identity's feed. `started` stays set (app-scoped collector). */
    fun reset() {
        synchronized(lock) { byId.clear() }
        _timeline.value = emptyList()
    }
}
