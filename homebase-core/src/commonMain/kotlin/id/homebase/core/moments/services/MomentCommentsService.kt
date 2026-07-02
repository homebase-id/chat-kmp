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
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    private val contactService: ContactService,
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

    private val lock = SynchronizedObject()
    private val perMoment = mutableMapOf<Uuid, PerMomentState>()
    private var subscriptionStarted = false

    fun commentsFor(momentId: Uuid): StateFlow<List<MomentCommentItem>> {
        val (state, isFirstObserver) = stateFor(momentId)
        if (isFirstObserver) {
            Logger.i(tag = TAG) {
                "commentsFor: first observer for moment=$momentId — starting cold-load + subscription"
            }
            scope.launch { coldLoad(momentId, state) }
            ensureSubscription()
        }
        return state.flow.asStateFlow()
    }

    private fun stateFor(momentId: Uuid): Pair<PerMomentState, Boolean> = synchronized(lock) {
        val existing = perMoment[momentId]
        if (existing != null) return@synchronized existing to false
        val fresh = PerMomentState()
        perMoment[momentId] = fresh
        fresh to true
    }

    private fun ensureSubscription() {
        synchronized(lock) {
            if (subscriptionStarted) return
            subscriptionStarted = true
        }
        Logger.i(tag = TAG) {
            "ensureSubscription: starting EventBus collector for moments drive=$drive " +
                "(activeMoments=${perMoment.size})"
        }
        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DataEvent || event.driveId != drive) return@collect
                if (event !is BackendEvent.DataEvent.BatchReceived) return@collect
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
                val item = toCommentItem(file) ?: continue
                state.byId[item.id] = item
            }
            emitSorted(state)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "Cold-load failed for moment=$momentId: ${e.message}"
            }
        }
    }

    private suspend fun processIncrementalBatch(files: List<HomebaseFile>) {
        // Always log the batch shape so a comment that never lands here at all
        // is visibly distinguishable from one that lands but gets filtered.
        // The receiver-side complaint "comment didn't show on detail" splits
        // exactly along this line: was the file delivered to the local index
        // (and to this collector) or not?
        val fileTypeCounts = files.groupingBy {
            it.fileMetadata.appData.fileType
        }.eachCount()
        Logger.d(tag = TAG) {
            "processIncrementalBatch: drive=$drive files=${files.size} " +
                "byFileType=$fileTypeCounts activeMoments=${perMoment.keys}"
        }
        val comments = files.filter {
            it.fileMetadata.appData.fileType == MomentsProtocol.MomentCommentFileType
        }
        if (comments.isEmpty()) return

        for (file in comments) {
            val momentId = file.fileMetadata.appData.groupId
            val uniqueId = file.fileMetadata.appData.uniqueId
            if (momentId == null) {
                Logger.w(tag = TAG) {
                    "processIncrementalBatch: comment uniqueId=$uniqueId missing groupId — dropped"
                }
                continue
            }
            val state = perMoment[momentId]
            if (state == null) {
                Logger.d(tag = TAG) {
                    "processIncrementalBatch: comment uniqueId=$uniqueId groupId=$momentId — " +
                        "moment not currently observed (activeMoments=${perMoment.keys}), dropped"
                }
                continue
            }
            if (uniqueId == null) {
                Logger.w(tag = TAG) {
                    "processIncrementalBatch: comment for moment=$momentId missing uniqueId — dropped"
                }
                continue
            }
            var changed = false
            if (file.isSoftDeleted()) {
                if (state.byId.remove(uniqueId) != null) {
                    changed = true
                    Logger.d(tag = TAG) {
                        "processIncrementalBatch: soft-deleted comment=$uniqueId removed from moment=$momentId"
                    }
                }
            } else {
                val item = toCommentItem(file) ?: run {
                    Logger.w(tag = TAG) {
                        "processIncrementalBatch: toCommentItem returned null for uniqueId=$uniqueId moment=$momentId"
                    }
                    continue
                }
                val isUpdate = state.byId.containsKey(uniqueId)
                state.byId[uniqueId] = item
                changed = true
                Logger.i(tag = TAG) {
                    "processIncrementalBatch: ${if (isUpdate) "updated" else "added"} comment=$uniqueId " +
                        "moment=$momentId sender=${item.senderOdinId?.domainName ?: "self"} " +
                        "totalCommentsForMoment=${state.byId.size}"
                }
            }
            if (changed) emitSorted(state)
        }
    }

    private fun emitSorted(state: PerMomentState) {
        state.flow.value = state.byId.values.sortedByDescending { it.userDateMs }
    }

    /**
     * Map a comment file into [MomentCommentItem], resolving the sender's
     * display name against the contact list at mapping time. Mirrors
     * `ChatMessageStream.resolveDisplayName` so both screens render identity
     * the same way — contact name when known, raw `domainName` otherwise,
     * empty when the sender is null (own-side files where the server hasn't
     * stamped a senderOdinId).
     */
    private suspend fun toCommentItem(file: HomebaseFile): MomentCommentItem? {
        val appData = file.fileMetadata.appData
        val uniqueId = appData.uniqueId ?: return null
        val groupId = appData.groupId ?: return null
        val content = appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentCommentContent>(raw)
            }.getOrNull()
        }
        val sender = file.fileMetadata.senderOdinId
        val displayName = sender?.let { contactService.resolveByOdinId(it).name }.orEmpty()
        return MomentCommentItem(
            id = uniqueId,
            momentId = groupId,
            senderOdinId = sender,
            displayName = displayName,
            body = content?.body.orEmpty(),
            userDateMs = file.sqlUserDateMs(),
            fileId = file.fileId,
            driveId = file.driveId,
            keyHeader = file.keyHeader,
            payloads = file.fileMetadata.payloads.orEmpty(),
            previewThumbnail = appData.previewThumbnail,
            versionTag = file.fileMetadata.versionTag,
            reactionPreview = file.fileMetadata.reactionPreview,
        )
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
    /**
     * Pre-resolved sender display name baked in at sync/cold-load time —
     * mirrors `MessageUiModel.displayName` on chat. Contact-list name when
     * known, raw `domainName` otherwise, empty string when senderOdinId is
     * null (own-side copies of the file where the server doesn't stamp a
     * sender). Resolved once at mapping, not on every UI recomposition.
     */
    val displayName: String,
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

