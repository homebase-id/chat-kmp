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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Live view of every moment post on the moments drive. Modeled on
 * `ChatMessageStream`:
 *
 *   1. On [start], do a one-shot cold-load via `QueryBatch.queryBatchAsync`
 *      with `filetypesAnyOf = [MomentPostFileType]` so the feed is populated
 *      immediately on app open from the local DB (no HTTP).
 *   2. Subscribe to `BackendEvent.DataEvent.BatchReceived` for the moments
 *      drive and apply each batch incrementally — these come from live
 *      WebSocket pushes (per-file events from `DriveWebSocketUpsertWorker`).
 *      Soft-deleted files are removed from the in-memory state on sight.
 *   3. Subscribe to `BackendEvent.DriveEvent.Stopped(totalCount > 0)` and
 *      re-cold-load — bulk `DriveSync.performSync` is *silent* (no per-batch
 *      `BatchReceived`; see DriveSync.kt:199-204), so a freshly-mounted drive's
 *      initial historical pull only surfaces via Stopped. Without this branch,
 *      enabling Moments at runtime mounts the drive, the catch-up writes land
 *      in `DriveMainIndex`, but the in-memory feed stays empty until next
 *      app launch. Gate on `totalCount > 0` to match `ChatMessageStream`.
 */
class MomentsFeedService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val userStateStore: MomentsUserStateStore,
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

    /**
     * Count of moments received from other identities that are newer than the
     * user's last-viewed watermark — the unseen-moments nav badge. Derived from
     * the always-on [feed] and the synced watermark, so it is live without the
     * Moments screen being open. Own posts (`senderOdinId == null`) never count.
     * A null watermark (never viewed) treats everything received as unseen.
     */
    val unseenCount: StateFlow<Int> =
        combine(feed, userStateStore.lastViewedMs) { items, watermark ->
            val since = watermark ?: Long.MIN_VALUE
            items.count { it.senderOdinId != null && it.createdMs > since }
        }.stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Advance the feed "last viewed" watermark. Called when the user views the
     * feed (see `MomentsScreen`). Delegates to [MomentsUserStateStore.markViewed]
     * — monotonic, optimistic + outbox, cross-device synced.
     */
    suspend fun markViewed(newestReceivedCreatedMs: Long) =
        userStateStore.markViewed(newestReceivedCreatedMs)

    private var started = false

    fun start() {
        if (started) return
        started = true

        Logger.i(tag = TAG) {
            "start: cold-loading + subscribing to EventBus for moments drive=$drive"
        }

        scope.launch { coldLoad() }

        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    // Logout: drop the previous identity's moments feed.
                    is BackendEvent.SessionEnded -> reset()
                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId != drive) return@collect
                        processIncrementalBatch(event.batchData)
                    }
                    is BackendEvent.DriveEvent.Stopped -> {
                        if (event.driveId != drive) return@collect
                        // Silent-DriveSync contract: bulk catch-up just landed
                        // `totalCount` rows in DriveMainIndex without emitting
                        // BatchReceived. Re-read the DB to converge in-memory
                        // state — this is what makes "enable Moments at runtime
                        // → historical posts appear without restart" work.
                        // Gate on totalCount > 0 (mirrors ChatMessageStream):
                        // Stopped(Aborted, totalCount > 0) still means earlier
                        // batches landed; totalCount == 0 means cursor at HEAD,
                        // nothing to refresh.
                        if (event.totalCount > 0) {
                            Logger.d(tag = TAG) {
                                "DriveEvent.Stopped(totalCount=${event.totalCount}, " +
                                    "result=${event.result}) — re-cold-loading moments"
                            }
                            scope.launch { coldLoad() }
                        }
                    }
                    is BackendEvent.OutboxEvent.OptimisticRollback -> {
                        // Fired when OptimisticWriter.removeOptimisticFile
                        // deletes the local row — typically because the user
                        // tapped Delete on a permanently-failed moment that
                        // never reached the server. Mirrors ChatMessageStream.
                        if (event.driveId != drive) return@collect
                        if (byId.remove(event.uniqueId) != null) {
                            Logger.d(tag = TAG) {
                                "OptimisticRollback: removed moment=${event.uniqueId}"
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
            var skippedSoftDeleted = 0
            var skippedUnparseable = 0
            for (file in result.records) {
                if (file.isSoftDeleted()) {
                    skippedSoftDeleted++
                    continue
                }
                val item = file.toFeedItem()
                if (item == null) {
                    skippedUnparseable++
                    continue
                }
                byId[item.id] = item
            }
            Logger.i(tag = TAG) {
                "coldLoad: rows=${result.records.size} loaded=${byId.size} " +
                    "skippedSoftDeleted=$skippedSoftDeleted skippedUnparseable=$skippedUnparseable"
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
        // Per-batch histogram mirrors MomentCommentsService: lets us tell at
        // a glance whether a batch even reached this collector and what was
        // in it. A "remote moment didn't appear" report splits cleanly along
        // this line — if no `processIncrementalBatch` entry fires after a
        // remote send, the WS push isn't delivering to this client.
        val fileTypeCounts = files.groupingBy {
            it.fileMetadata.appData.fileType
        }.eachCount()
        Logger.d(tag = TAG) {
            "processIncrementalBatch: drive=$drive files=${files.size} " +
                "byFileType=$fileTypeCounts currentFeedSize=${byId.size}"
        }
        val moments = files.filter {
            it.fileMetadata.appData.fileType == MomentsProtocol.MomentPostFileType
        }
        if (moments.isEmpty()) return

        var changed = false
        for (file in moments) {
            val uniqueId = file.fileMetadata.appData.uniqueId
            if (uniqueId == null) {
                Logger.w(tag = TAG) {
                    "processIncrementalBatch: moment fileId=${file.fileId} missing uniqueId — dropped"
                }
                continue
            }
            if (file.isSoftDeleted()) {
                if (byId.remove(uniqueId) != null) {
                    changed = true
                    Logger.d(tag = TAG) {
                        "processIncrementalBatch: soft-deleted moment=$uniqueId removed (feedSize=${byId.size})"
                    }
                }
                continue
            }
            val item = file.toFeedItem() ?: run {
                Logger.w(tag = TAG) {
                    "processIncrementalBatch: toFeedItem returned null for uniqueId=$uniqueId fileId=${file.fileId}"
                }
                continue
            }
            val isUpdate = byId.containsKey(uniqueId)
            byId[uniqueId] = item
            changed = true
            Logger.i(tag = TAG) {
                "processIncrementalBatch: ${if (isUpdate) "updated" else "added"} moment=$uniqueId " +
                    "sender=${item.senderOdinId?.domainName ?: "self"} " +
                    "recipients=${item.recipients.size} feedSize=${byId.size}"
            }
        }
        if (changed) emitSorted()
    }

    private fun emitSorted() {
        _feed.value = byId.values.sortedByDescending { it.userDateMs }
    }

    /**
     * Logout: drop the previous identity's moments. `started` is intentionally
     * left set — the app-scoped eventBus collector stays alive and repopulates
     * from the next session's moments-drive sync (DriveEvent.Stopped).
     */
    fun reset() {
        byId.clear()
        _feed.value = emptyList()
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
    /**
     * Server-side creation timestamp from the file metadata — i.e. when the
     * post was published. Distinct from [userDateMs], which falls back to the
     * EXIF "captured at" time when available. Drives the Timeline sort order
     * (newest *posted* first), versus the Album sort which uses userDate.
     */
    val createdMs: Long,
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
     * Identity that authored the post. Unlike [senderOdinId] this is populated
     * on the author's *own* drive copy too (the optimistic writer stamps self,
     * and the server preserves it across transfer), so it is the reliable
     * signal of "whose face to show" for the feed avatar — for the user's own
     * moments [senderOdinId] comes back null after sync but [originalAuthor]
     * still resolves to self. Null only on legacy posts that pre-date the field.
     */
    val originalAuthor: OdinId?,
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
    /**
     * Author's choice to allow commenting on this moment. Defaults to true for
     * legacy posts whose `MomentPostContent` pre-dates the field. The detail
     * screen hides the entire comments section (header, list, composer) when
     * this is false.
     */
    val commentsEnabled: Boolean,
    /**
     * Current header version tag, needed to submit an in-place edit (e.g. a
     * description edit via [MomentsPostSenderService.updateMoment], which
     * guards against a stale write by rejecting a mismatched tag). Null on a
     * still-optimistic local write that hasn't been assigned a tag yet.
     */
    val versionTag: Uuid?,
    /**
     * Emojis the current user has already reacted with on this moment. Read
     * from `fileMetadata.localAppData.localReactions` (the per-user mirror
     * the [id.homebase.chat.services.outbox.OptimisticWriter] maintains) and
     * decoded to bare emoji glyphs.
     *
     * Drives:
     *  - "I've reacted" indication on the detail screen's heart / flame
     *    action buttons (filled vs outlined).
     *  - The feed card's double / triple-tap reaction overlay so it can
     *    play a *remove* animation when the toggle takes the reaction away
     *    instead of adding it.
     */
    val ownReactions: List<String>,
)

private fun HomebaseFile.toFeedItem(): MomentFeedItem? {
    val appData = fileMetadata.appData
    val uniqueId = appData.uniqueId ?: return null
    val content = appData.content?.let { raw ->
        runCatching {
            OdinSystemSerializer.deserialize<MomentPostContent>(raw)
        }.getOrNull()
    }
    // localReactions stores the per-user reaction JSON strings the
    // OptimisticWriter maintains. Decode each to a bare emoji so the UI can
    // do `emoji in ownReactions` checks without re-deserialising.
    val ownReactions = fileMetadata.localAppData?.localReactions
        ?.mapNotNull { raw -> decodeOwnReactionEmoji(raw) }
        .orEmpty()
    // Diagnostic for the missing-avatar investigation: senderOdinId is expected
    // to be null on the author's own drive copy (server strips it), but should
    // be present on inbound transfers. Log the null case alongside originalAuthor
    // so we can tell an expected own-post null apart from an inbound moment that
    // unexpectedly arrived without a sender. Drop once the data is understood.
    val senderOdinId = fileMetadata.senderOdinId
    if (senderOdinId == null) {
        Logger.d(tag = "MomentsFeedService") {
            "toFeedItem: null senderOdinId uniqueId=$uniqueId " +
                "originalAuthor=${fileMetadata.originalAuthor} " +
                "recipients=${content?.recipients?.size ?: 0} source=${content?.source}"
        }
    }
    return MomentFeedItem(
        id = uniqueId,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        payloads = fileMetadata.payloads.orEmpty(),
        description = content?.description.orEmpty(),
        userDateMs = sqlUserDateMs(),
        createdMs = fileMetadata.created.milliseconds,
        previewThumbnail = appData.previewThumbnail,
        reactionPreview = fileMetadata.reactionPreview,
        senderOdinId = senderOdinId,
        originalAuthor = fileMetadata.originalAuthor,
        source = content?.source,
        recipients = content?.recipients.orEmpty(),
        // Default true when content failed to parse OR when an older post
        // didn't include the field — matches the @Serializable default and
        // keeps legacy moments commentable.
        commentsEnabled = content?.commentsEnabled ?: true,
        versionTag = fileMetadata.versionTag,
        ownReactions = ownReactions,
    )
}

private fun decodeOwnReactionEmoji(reactionContent: String): String? = runCatching {
    OdinSystemSerializer
        .deserialize<id.homebase.api.client.drives.files.reactions.ReactionContent>(reactionContent)
        .emoji
}.getOrNull()
