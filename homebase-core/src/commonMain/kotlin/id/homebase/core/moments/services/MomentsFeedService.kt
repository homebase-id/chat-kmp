package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Live view of every moment post on the moments drive. Modeled on
 * `ChatMessageStream`:
 *
 *   1. On [start], do a one-shot cold-load via `QueryBatch.queryBatchAsync`
 *      with `filetypesAnyOf = [MomentPostFileType]` so the feed is populated
 *      immediately on app open from the local DB (no HTTP).
 *   2. Subscribe to `BackendEvent.DriveEvent.BatchReceived` for the moments
 *      drive and apply each batch incrementally — the event carries the
 *      list of `HomebaseFile`s that just landed, so we never re-read the DB
 *      on a sync update. Soft-deleted files are removed from the in-memory
 *      state on sight.
 */
class MomentsFeedService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "MomentsFeedService"
    }

    private val drive = momentsLabeledDrive.drive.alias

    // Keyed by uniqueId for O(1) upsert/remove. Sorted into a list whenever
    // we emit. Newest-first ordering by userDate matches the chat-message
    // ordering and the spec's "vertical chronological feed."
    private val byId = mutableMapOf<Uuid, MomentFeedItem>()

    private val _feed = MutableStateFlow<List<MomentFeedItem>>(emptyList())
    val feed: StateFlow<List<MomentFeedItem>> = _feed.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch { coldLoad() }

        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DataEvent || event.driveId != drive) return@collect
                when (event) {
                    is BackendEvent.DataEvent.BatchReceived ->
                        processIncrementalBatch(event.batchData)
                }
            }
        }
    }

    /**
     * One-shot fetch of every moment in the local DB on the moments drive,
     * newest first. Mirrors `ChatMessageStream.fetchMessages` but at drive
     * scope (no group filter).
     */
    private suspend fun coldLoad() {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            val result = QueryBatch(identityId).queryBatchAsync(
                dbm = databaseManager,
                driveId = drive,
                noOfItems = 1000,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(MomentsProtocol.MomentPostFileType),
            )

            byId.clear()
            for (file in result.records) {
                if (file.isSoftDeleted()) continue
                val item = file.toFeedItem() ?: continue
                byId[item.id] = item
            }
            emitSorted()
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "Cold-load failed: ${e.message}"
            }
        }
    }

    /**
     * Incremental application of a sync batch. The event carries the
     * already-landed `HomebaseFile`s; we never re-read the DB here.
     */
    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val moments = files.filter {
            it.fileMetadata.appData.fileType == MomentsProtocol.MomentPostFileType
        }
        if (moments.isEmpty()) return

        var changed = false
        for (file in moments) {
            val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
            if (file.isSoftDeleted()) {
                if (byId.remove(uniqueId) != null) changed = true
                continue
            }
            val item = file.toFeedItem() ?: continue
            byId[uniqueId] = item
            changed = true
        }
        if (changed) emitSorted()
    }

    private fun emitSorted() {
        _feed.value = byId.values.sortedByDescending { it.userDateMs }
    }
}

/**
 * Everything the feed list and detail screens need from a single moment, with
 * the on-disk header already deserialised. Drive-level fields stay raw so the
 * media widgets ([MomentMediaItem] / [MomentMediaGallery]) can render encrypted
 * payloads directly.
 */
data class MomentFeedItem(
    val id: Uuid,
    val fileId: Uuid,
    val driveId: Uuid,
    val keyHeader: KeyHeader,
    val payloads: List<PayloadDescriptor>,
    val description: String,
    val userDateMs: Long,
    val previewThumbnail: EmbeddedThumb?,
    /**
     * Embedded reaction summary on the moment file. Drives the per-emoji
     * count chips on the detail screen. Updated in place by the optimistic
     * writer's reaction toggle and by sync replays — both come through the
     * BatchReceived stream this service already subscribes to.
     */
    val reactionPreview: ReactionSummary?,
    /**
     * Original sender on the receiving drive; null on the sender's own drive
     * (the server populates this only for inbound transfers). Same convention
     * as `MomentCommentItem.senderOdinId`. The detail screen uses this to
     * decide whether to offer "delete for everyone" (sender only) vs just
     * "delete for me" (recipient).
     */
    val senderOdinId: OdinId?,
    /**
     * Audience the post was originally addressed to — surfaced on the detail
     * screen's "Shared with" row. Null on legacy moments that pre-date the
     * source field, on local-only posts, or when the header fails to
     * deserialise.
     */
    val source: MomentSource?,
    /**
     * Flat list of OdinIds the sender addressed this moment to (everyone but
     * the sender themselves). Always populated by the writer alongside
     * [source]. The detail screen falls back to this when [source] is null
     * or empty — `MomentAudienceViewModel` deliberately drops the source
     * field on individuals-only posts ("no need to duplicate the recipient
     * list"), but the detail screen still needs something to render.
     */
    val recipients: List<OdinId>,
)

private fun HomebaseFile.toFeedItem(): MomentFeedItem? {
    val appData = fileMetadata.appData
    val uniqueId = appData.uniqueId ?: return null
    val content = appData.content?.let { raw ->
        runCatching {
            OdinSystemSerializer.deserialize<MomentPostContent>(raw)
        }.getOrNull()
    }
    return MomentFeedItem(
        id = uniqueId,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        payloads = fileMetadata.payloads.orEmpty(),
        description = content?.description.orEmpty(),
        userDateMs = sqlUserDateMs(),
        previewThumbnail = appData.previewThumbnail,
        reactionPreview = fileMetadata.reactionPreview,
        senderOdinId = fileMetadata.senderOdinId,
        source = content?.source,
        recipients = content?.recipients.orEmpty(),
    )
}
