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
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
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
 * Per-moment view of comments, modeled on [MomentsFeedService] but scoped to
 * one moment at a time. First call to [commentsFor] for a given `momentId`
 * lazily kicks off a cold-load (`QueryBatch` filtered by `groupIdAnyOf =
 * [momentId]` + `filetypesAnyOf = [MomentCommentFileType]`) and starts a
 * single shared event-bus subscription that routes incoming comment files
 * to the right per-moment state by `groupId`.
 *
 * Memory-bounded: comments for moments the user has never opened in this
 * process are *not* indexed — they're picked up via the cold-load on first
 * open. Edits naturally upsert via `byId[uniqueId] = item`; soft-deletes
 * remove on sight.
 */
class MomentCommentsService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "MomentCommentsService"
    }

    private val drive = momentsLabeledDrive.drive.alias

    private class PerMomentState {
        val byId = mutableMapOf<Uuid, MomentCommentItem>()
        val flow = MutableStateFlow<List<MomentCommentItem>>(emptyList())
    }

    private val perMoment = mutableMapOf<Uuid, PerMomentState>()

    @Volatile
    private var subscriptionStarted = false

    fun commentsFor(momentId: Uuid): StateFlow<List<MomentCommentItem>> {
        val (state, isFirstObserver) = stateFor(momentId)
        if (isFirstObserver) {
            scope.launch { coldLoad(momentId, state) }
            ensureSubscription()
        }
        return state.flow.asStateFlow()
    }

    @Synchronized
    private fun stateFor(momentId: Uuid): Pair<PerMomentState, Boolean> {
        val existing = perMoment[momentId]
        if (existing != null) return existing to false
        val fresh = PerMomentState()
        perMoment[momentId] = fresh
        return fresh to true
    }

    @Synchronized
    private fun ensureSubscription() {
        if (subscriptionStarted) return
        subscriptionStarted = true
        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DriveEvent || event.driveId != drive) return@collect
                if (event !is BackendEvent.DriveEvent.BatchReceived) return@collect
                processIncrementalBatch(event.batchData)
            }
        }
    }

    private suspend fun coldLoad(momentId: Uuid, state: PerMomentState) {
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
                filetypesAnyOf = listOf(MomentsProtocol.MomentCommentFileType),
                groupIdAnyOf = listOf(momentId),
            )

            // Don't clear — incremental batches may have already populated
            // entries between PerMomentState creation and the cold-load
            // landing. Upsert instead.
            for (file in result.records) {
                if (file.isSoftDeleted()) continue
                val item = file.toCommentItem() ?: continue
                state.byId[item.id] = item
            }
            emitSorted(state)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "Cold-load failed for moment=$momentId: ${e.message}"
            }
        }
    }

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val comments = files.filter {
            it.fileMetadata.appData.fileType == MomentsProtocol.MomentCommentFileType
        }
        if (comments.isEmpty()) return

        for (file in comments) {
            val momentId = file.fileMetadata.appData.groupId ?: continue
            val state = perMoment[momentId] ?: continue // moment never opened — skip
            val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
            var changed = false
            if (file.isSoftDeleted()) {
                if (state.byId.remove(uniqueId) != null) changed = true
            } else {
                val item = file.toCommentItem() ?: continue
                state.byId[uniqueId] = item
                changed = true
            }
            if (changed) emitSorted(state)
        }
    }

    private fun emitSorted(state: PerMomentState) {
        state.flow.value = state.byId.values.sortedByDescending { it.userDateMs }
    }
}

/**
 * Everything a comment list/item needs from a single comment file. Drive-level
 * fields stay raw so media widgets can render encrypted payloads directly.
 */
data class MomentCommentItem(
    val id: Uuid,
    val momentId: Uuid,
    val senderOdinId: OdinId?,
    val body: String,
    val userDateMs: Long,
    val fileId: Uuid,
    val driveId: Uuid,
    val keyHeader: KeyHeader,
    val payloads: List<PayloadDescriptor>,
    val previewThumbnail: EmbeddedThumb?,
    /**
     * Carried so callers can submit edits via
     * [MomentsPostSenderService.updateComment] without re-fetching the file
     * header. Null until the comment has a server-confirmed version (e.g.
     * still in the optimistic-write window) — UI should hide the edit
     * affordance while null.
     */
    val versionTag: Uuid?,
    /**
     * Embedded reaction summary on the comment file. Drives the per-emoji
     * count pill under the comment body.
     */
    val reactionPreview: ReactionSummary?,
)

private fun HomebaseFile.toCommentItem(): MomentCommentItem? {
    val appData = fileMetadata.appData
    val uniqueId = appData.uniqueId ?: return null
    val groupId = appData.groupId ?: return null
    val content = appData.content?.let { raw ->
        runCatching {
            OdinSystemSerializer.deserialize<MomentCommentContent>(raw)
        }.getOrNull()
    }
    return MomentCommentItem(
        id = uniqueId,
        momentId = groupId,
        senderOdinId = fileMetadata.senderOdinId,
        body = content?.body.orEmpty(),
        userDateMs = sqlUserDateMs(),
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        payloads = fileMetadata.payloads.orEmpty(),
        previewThumbnail = appData.previewThumbnail,
        versionTag = fileMetadata.versionTag,
        reactionPreview = fileMetadata.reactionPreview,
    )
}
