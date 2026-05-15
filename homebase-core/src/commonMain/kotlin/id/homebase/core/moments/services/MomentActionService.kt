package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionResult
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.widget.EmojiReaction
import kotlin.uuid.Uuid

/**
 * Action surface for moments and their comments — currently reactions
 * (toggle / list). Named "Action" rather than "Reactions" to leave room for
 * other moment-level operations (delete, report, etc.) without needing a new
 * service.
 *
 * Design mirrors [id.homebase.chat.services.ChatMessageActionService]'s
 * reaction methods but routed at the moments drive and using the moments
 * audience model (sender ∪ post recipients ∪ comment groupId-resolution).
 *
 * Reaction storage is the same drive-file substrate chat uses:
 *  - The toggle is an in-place update of `fileMetadata.reactionPreview`
 *    on the target file (moment or comment), driven by [OptimisticWriter].
 *  - The persisted record lives in the server's per-file group-reactions
 *    table, mutated via [ToggleReactionOutboxRequest] and queried via
 *    [DriveFileGroupReactionProvider.listReactions].
 *
 * The two `MomentFeedItem.reactionPreview` / `MomentCommentItem.reactionPreview`
 * fields populated from `fileMetadata.reactionPreview` give the UI a live
 * count without an extra round-trip — the optimistic writer emits a
 * BatchReceived after each toggle and the existing feed/comments services
 * upsert in place.
 */
class MomentActionService(
    private val driveFileProvider: DriveFileProvider,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val optimisticWriter: OptimisticWriter,
    private val outboxSync: OutboxSync,
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
) {
    companion object {
        private const val TAG = "MomentActionService"
    }

    private val drive = momentsLabeledDrive.drive.alias

    // -------------------- TOGGLE --------------------

    /**
     * Toggle the current user's reaction on a moment. Recipients are the
     * moment's full audience minus self.
     */
    suspend fun toggleReactionOnMoment(momentId: Uuid, emoji: String): ToggleReactionResult =
        toggleReactionInternal(targetUniqueId = momentId, emoji = emoji) { original ->
            original.fileMetadata.appData.uniqueId ?: momentId
        }

    /**
     * Toggle the current user's reaction on a comment. The parent moment is
     * read from the comment file's `groupId` (already on the local row the
     * optimistic write touched); recipients are the parent moment's full
     * audience minus self (so reactions follow the same audience as the
     * comment thread itself).
     */
    suspend fun toggleReactionOnComment(commentId: Uuid, emoji: String): ToggleReactionResult =
        toggleReactionInternal(targetUniqueId = commentId, emoji = emoji) { original ->
            original.fileMetadata.appData.groupId
        }

    /**
     * Shared toggle implementation: optimistic write on the target file's
     * `reactionPreview` first (so the UI updates instantly via the
     * `BatchReceived` event the writer emits), then resolve the parent
     * moment id and recipients from the local DB, enqueue a
     * [ToggleReactionOutboxRequest], roll back the optimistic write on
     * failure. Mirrors `ChatMessageActionService.toggleReaction` — both the
     * old upfront `getFileHeaderByUid(target)` and the old `resolveAudience`
     * variant that called `getFileHeaderByUid(moment)` were network round-
     * trips that gated the optimistic UI update; the chat path moved them
     * out of the hot path for the same reason.
     *
     * `parentMomentFor` extracts the parent moment id from the *original*
     * (pre-toggle) file returned by the optimistic writer: for a moment
     * that's the uniqueId itself, for a comment that's `appData.groupId`.
     */
    private suspend fun toggleReactionInternal(
        targetUniqueId: Uuid,
        emoji: String,
        parentMomentFor: (original: HomebaseFile) -> Uuid?,
    ): ToggleReactionResult {
        if (!isValidEmoji(emoji)) {
            return ToggleReactionResult(resultType = ToggleReactionResultType.None)
        }

        val reactionJson = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))

        val (resultType, original) = optimisticWriter.writeReactionToggle(
            drive,
            targetUniqueId,
            reactionJson,
        )
        if (original == null) return ToggleReactionResult(resultType = resultType)

        try {
            val parentMomentId = parentMomentFor(original)
                ?: throw IllegalStateException("missing parent moment for $targetUniqueId")
            val recipients = resolveAudienceLocal(parentMomentId)
            val enqueued = outboxSync.tryEnqueue(
                request = ToggleReactionOutboxRequest(
                    driveId = drive,
                    fileId = original.fileId,
                    reaction = reactionJson,
                    recipients = recipients,
                ),
            )
            if (!enqueued) {
                optimisticWriter.rollbackWrite(drive, original)
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) { "toggleReaction failed to enqueue: ${t.message}" }
            runCatching { optimisticWriter.rollbackWrite(drive, original) }
        }

        return ToggleReactionResult(resultType = resultType)
    }

    // -------------------- LIST --------------------

    suspend fun getReactionsForMoment(momentId: Uuid): List<EmojiReaction> =
        listReactions(uniqueId = momentId)

    suspend fun getReactionsForComment(commentId: Uuid): List<EmojiReaction> =
        listReactions(uniqueId = commentId)

    private suspend fun listReactions(uniqueId: Uuid): List<EmojiReaction> {
        val target = driveFileProvider.getFileHeaderByUid(drive, uniqueId) ?: return emptyList()
        val response = reactionProvider.listReactions(drive, target.fileId)
        return response.reactions.map {
            EmojiReaction(
                messageId = uniqueId,
                odinId = it.odinId,
                created = UnixTimeUtc(it.created),
                emoji = OdinSystemSerializer.deserialize<ReactionContent>(it.reactionContent).emoji,
            )
        }
    }

    // -------------------- HELPERS --------------------

    private fun isValidEmoji(input: String?): Boolean =
        !input.isNullOrBlank() && input.length <= 8

    /**
     * Local-DB variant of audience resolution. The previous version called
     * `driveFileProvider.getFileHeaderByUid` (a server GET) — fine for
     * correctness but a network round-trip we don't want gating the reaction
     * outbox enqueue. The moment header is already in DriveMainIndex by the
     * time the user can see/tap a reaction chip, so we read it from there.
     */
    private suspend fun resolveAudienceLocal(momentId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials()
        val moment = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), drive, momentId,
        ) ?: throw IllegalArgumentException("moment not found locally: $momentId")

        val momentContent = moment.fileMetadata.appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentPostContent>(raw)
            }.getOrNull()
        } ?: throw IllegalStateException("moment $momentId content unreadable")

        val self = credentials.domain
        val audience = buildSet {
            moment.fileMetadata.senderOdinId?.let { add(it) }
            addAll(momentContent.recipients)
        }
        return (audience - self).toList()
    }
}
