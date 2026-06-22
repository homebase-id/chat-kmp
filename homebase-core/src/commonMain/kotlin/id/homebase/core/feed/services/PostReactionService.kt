package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionResult
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.widget.EmojiReaction
import kotlin.uuid.Uuid

/**
 * Emoji reactions on feed posts and comments. Mirrors
 * [id.homebase.core.moments.services.MomentActionService]:
 *
 *  - [toggleReaction] does an optimistic local toggle of the file's `reactionPreview` via
 *    [OptimisticWriter.writeReactionToggle] (so the UI updates instantly), then enqueues a
 *    [ToggleReactionOutboxRequest] — NOT a direct provider toggle — rolling back the optimistic
 *    write on enqueue failure.
 *  - [reactionSummary] / [listReactors] read through [DriveFileGroupReactionProvider]
 *    (`getReactionSummary` / `listReactions`) keyed by the post/comment's `(driveId, fileId)`.
 *
 * Reactions target the file by `(driveId, fileId)` — a local fileId, no globalTransitId (Task 0).
 * Recipients for a post reaction are the post author minus self; the comment equivalents read the
 * comment file's parent post for audience.
 */
class PostReactionService(
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val optimisticWriter: OptimisticWriter,
    private val outboxSync: OutboxSync,
    private val credentialsManager: CredentialsManager,
) {

    companion object {
        private const val TAG = "PostReactionService"
    }

    // -------------------- TOGGLE --------------------

    /** Toggle the current user's [emoji] reaction on [post]. */
    suspend fun toggleReaction(post: FeedPostItem, emoji: String): ToggleReactionResult =
        toggleInternal(
            driveId = post.driveId,
            targetUniqueId = post.id,
            authorOdinId = post.senderOdinId,
            emoji = emoji,
        )

    /** Toggle the current user's [emoji] reaction on [comment]. */
    suspend fun toggleReaction(comment: PostCommentItem, emoji: String): ToggleReactionResult =
        toggleInternal(
            driveId = comment.driveId,
            targetUniqueId = comment.id,
            authorOdinId = comment.senderOdinId,
            emoji = emoji,
        )

    private suspend fun toggleInternal(
        driveId: Uuid,
        targetUniqueId: Uuid,
        authorOdinId: OdinId?,
        emoji: String,
    ): ToggleReactionResult {
        if (!isValidEmoji(emoji)) {
            return ToggleReactionResult(resultType = ToggleReactionResultType.None)
        }

        val reactionJson = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))

        val (resultType, original) = optimisticWriter.writeReactionToggle(
            driveId, targetUniqueId, reactionJson,
        )
        if (original == null) return ToggleReactionResult(resultType = resultType)

        try {
            val recipients = resolveRecipients(authorOdinId)
            val enqueued = outboxSync.tryEnqueue(
                request = ToggleReactionOutboxRequest(
                    driveId = driveId,
                    fileId = original.fileId,
                    reaction = reactionJson,
                    recipients = recipients,
                ),
            )
            if (!enqueued.enqueued) {
                Logger.w(tag = TAG) { "outbox enqueue -> $enqueued; rolling back optimistic write" }
                optimisticWriter.rollbackWrite(driveId, original)
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) { "toggleReaction failed to enqueue: ${t.message}" }
            runCatching { optimisticWriter.rollbackWrite(driveId, original) }
        }

        return ToggleReactionResult(resultType = resultType)
    }

    // -------------------- SUMMARY / LIST --------------------

    /** Per-emoji counts on [post]. */
    suspend fun reactionSummary(post: FeedPostItem): PostReactionSummary =
        summaryFor(post.driveId, post.fileId)

    /** Per-emoji counts on [comment]. */
    suspend fun reactionSummary(comment: PostCommentItem): PostReactionSummary =
        summaryFor(comment.driveId, comment.fileId)

    private suspend fun summaryFor(driveId: Uuid, fileId: Uuid): PostReactionSummary {
        val response = reactionProvider.getReactionSummary(driveId, fileId)
        // Provider keys the count map by the raw reaction JSON; decode each key to its emoji glyph.
        val byEmoji = response.reactions.entries.mapNotNull { (raw, count) ->
            decodeReactionEmoji(raw)?.let { it to count }
        }.toMap()
        return PostReactionSummary(byEmoji = byEmoji, total = response.total)
    }

    /** The individual reactors on [post], optionally filtered to a single [emoji]. */
    suspend fun listReactors(post: FeedPostItem, emoji: String? = null): List<EmojiReaction> =
        reactorsFor(post.driveId, post.fileId, post.id, emoji)

    /** The individual reactors on [comment], optionally filtered to a single [emoji]. */
    suspend fun listReactors(comment: PostCommentItem, emoji: String? = null): List<EmojiReaction> =
        reactorsFor(comment.driveId, comment.fileId, comment.id, emoji)

    private suspend fun reactorsFor(
        driveId: Uuid,
        fileId: Uuid,
        targetUniqueId: Uuid,
        emoji: String?,
    ): List<EmojiReaction> {
        val response = reactionProvider.listReactions(driveId, fileId)
        return response.reactions.mapNotNull {
            val glyph = decodeReactionEmoji(it.reactionContent) ?: return@mapNotNull null
            if (emoji != null && glyph != emoji) return@mapNotNull null
            EmojiReaction(
                messageId = targetUniqueId,
                odinId = it.odinId,
                created = UnixTimeUtc(it.created),
                emoji = glyph,
            )
        }
    }

    // -------------------- HELPERS --------------------

    private fun isValidEmoji(input: String?): Boolean =
        !input.isNullOrBlank() && input.length <= 8

    /** Reaction recipients: the post/comment author minus self; empty for the user's own content. */
    private suspend fun resolveRecipients(authorOdinId: OdinId?): List<OdinId> {
        val self = credentialsManager.requireActiveCredentials().domain
        return listOfNotNull(authorOdinId).filterNot { it == self }
    }
}

/** Per-emoji reaction counts on a post or comment, decoded to bare glyphs. */
data class PostReactionSummary(
    val byEmoji: Map<String, Int>,
    val total: Int,
)
