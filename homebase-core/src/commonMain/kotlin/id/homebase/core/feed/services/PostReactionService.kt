package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.api.util.codePointCount
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.widget.EmojiReaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

// Reactions target the file by (driveId, fileId) — a local fileId, no globalTransitId.
class PostReactionService(
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val optimisticWriter: OptimisticWriter,
    private val outboxSync: OutboxSync,
    private val credentialsManager: CredentialsManager,
) {

    companion object {
        private const val TAG = "PostReactionService"
        private const val MaxEmojiCodePoints = 12
    }


    suspend fun toggleReaction(post: FeedPostItem, emoji: String): ToggleReactionResult =
        toggleInternal(
            driveId = post.driveId,
            targetUniqueId = post.id,
            fileId = post.fileId,
            // toFeedPostItem falls back to the globalTransitId when the file carries no uniqueId — a followed post.
            hasUniqueId = post.id != post.globalTransitId,
            authorOdinId = post.authorOdinId,
            emoji = emoji,
        )

    suspend fun toggleReaction(comment: PostCommentItem, emoji: String): ToggleReactionResult =
        toggleInternal(
            driveId = comment.driveId,
            targetUniqueId = comment.id,
            fileId = comment.fileId,
            // `toCommentItem` drops a comment file with no uniqueId, so this id is always one.
            hasUniqueId = true,
            authorOdinId = comment.authorOdinId,
            emoji = emoji,
        )

    // A followed post has no uniqueId, so the optimistic write can never resolve its row. That miss must
    // not block the send, which only needs (driveId, fileId). Throws on send failure so the caller can
    // undo the optimistic UI flip it made before calling.
    private suspend fun toggleInternal(
        driveId: Uuid,
        targetUniqueId: Uuid,
        fileId: Uuid,
        hasUniqueId: Boolean,
        authorOdinId: OdinId?,
        emoji: String,
    ): ToggleReactionResult {
        require(isValidEmoji(emoji)) { "not a reaction emoji" }

        val reactionJson = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))

        val (resultType, original) = optimisticWriter.writeReactionToggle(
            driveId, targetUniqueId, reactionJson,
        )
        if (original == null && hasUniqueId) return ToggleReactionResult(resultType = resultType)

        try {
            val recipients = resolveRecipients(authorOdinId)
            val enqueued = outboxSync.tryEnqueue(
                request = ToggleReactionOutboxRequest(
                    driveId = driveId,
                    fileId = original?.fileId ?: fileId,
                    reaction = reactionJson,
                    recipients = recipients,
                ),
            )
            check(enqueued.enqueued) { "outbox enqueue -> $enqueued" }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) { "toggleReaction failed to enqueue: ${t.message}" }
            original?.let { runCatching { optimisticWriter.rollbackWrite(driveId, it) } }
            throw t
        }

        return ToggleReactionResult(resultType = resultType)
    }


    suspend fun reactionSummary(post: FeedPostItem): PostReactionSummary =
        summaryFor(post.driveId, post.fileId)

    suspend fun reactionSummary(comment: PostCommentItem): PostReactionSummary =
        summaryFor(comment.driveId, comment.fileId)

    // Read fresh rather than off the header's reactionPreview, which never reflects reactions that landed
    // after the post was aggregated into the feed.
    suspend fun liveReactionSummary(post: FeedPostItem): ReactionSummary {
        val response = reactionProvider.getReactionSummary(post.driveId, post.fileId)
        return ReactionSummary(
            reactions = response.reactions.mapValues { (raw, count) ->
                ReactionEntry(key = raw, count = count, reactionContent = raw)
            },
        )
    }

    private suspend fun summaryFor(driveId: Uuid, fileId: Uuid): PostReactionSummary {
        val response = reactionProvider.getReactionSummary(driveId, fileId)
        // Provider keys the count map by the raw reaction JSON; decode each key to its emoji glyph.
        val byEmoji = response.reactions.entries.mapNotNull { (raw, count) ->
            decodeReactionEmoji(raw)?.let { it to count }
        }.toMap()
        return PostReactionSummary(byEmoji = byEmoji, total = response.total)
    }

    // The only source: LocalAppMetadata.localReactions is written only by the local optimistic writer and is
    // null on every post header, so without this the like button can never render active.
    suspend fun ownReactions(post: FeedPostItem): List<String> {
        val self = credentialsManager.getActiveCredentials()?.domain ?: return emptyList()
        return listReactors(post, null).filter { it.odinId == self }.map { it.emoji }.distinct()
    }

    // Complete only for a post we host ourselves: the group-reactions endpoint is addressed at our own
    // identity, so on a followed post it sees only the rows we sent. Callers must flag that as partial.
    suspend fun listReactors(post: FeedPostItem, emoji: String? = null): List<EmojiReaction> =
        reactorsFor(post.driveId, post.fileId, post.id, emoji)

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


    // Code points, not UTF-16 units: a ZWJ sequence is 8 code points but 11 units, and a length cap
    // rejected 16 of the 1949 emoji outright.
    private fun isValidEmoji(input: String?): Boolean =
        !input.isNullOrBlank() && input.codePointCount() <= MaxEmojiCodePoints

    // Callers must pass authorOdinId (originalAuthor ?: senderOdinId): the server STRIPS senderOdinId on a
    // follower's copy, so resolving from it alone queues the reaction for nobody.
    private suspend fun resolveRecipients(authorOdinId: OdinId?): List<OdinId> {
        val self = credentialsManager.requireActiveCredentials().domain
        return listOfNotNull(authorOdinId).filterNot { it == self }
    }
}

data class PostReactionSummary(
    val byEmoji: Map<String, Int>,
    val total: Int,
)

// The header preview is server-maintained and kept in step by the author's distribution even on a followed
// post — unlike listReactors, which only sees our own identity's rows.
fun ReactionSummary?.emojiCounts(): Map<String, Int> {
    val entries = this?.reactions?.values ?: return emptyMap()
    val counts = mutableMapOf<String, Int>()
    entries.forEach { entry ->
        val glyph = decodeReactionEmoji(entry.reactionContent)?.takeUnless { it.startsWith('_') }
            ?: return@forEach
        counts[glyph] = (counts[glyph] ?: 0) + entry.count
    }
    return counts
}

// Costs one roster read per post, so it is gated three ways: a post with no reactions is skipped outright
// (~80% of the timeline), only the first `limit` posts are considered, and a resolved post is pinned to the
// header snapshot it came from so re-emission never refetches. Not thread-safe — drive from one dispatcher.
class PostOwnReactionResolver(private val reactions: PostReactionService) {

    private companion object {
        private const val TAG = "PostOwnReactionResolver"
    }

    private val _ownReactions = MutableStateFlow<Map<Uuid, List<String>>>(emptyMap())

    val ownReactions: StateFlow<Map<Uuid, List<String>>> = _ownReactions.asStateFlow()

    private val resolvedFrom = mutableMapOf<Uuid, ReactionSummary?>()

    private val inFlight = mutableSetOf<Uuid>()

    suspend fun resolve(posts: List<FeedPostItem>, limit: Int) {
        val pending = posts.take(limit).filter(::needsResolve)
        if (pending.isEmpty()) return
        pending.forEach { inFlight += it.fileId }
        try {
            for (post in pending) {
                val own = runCatching { reactions.ownReactions(post) }
                    .onFailure {
                        Logger.w(throwable = it, tag = TAG) {
                            "own-reaction read failed for post=${post.id}: ${it.message}"
                        }
                    }
                    .getOrNull() ?: continue
                resolvedFrom[post.fileId] = post.reactionPreview
                _ownReactions.update { it + (post.fileId to own) }
            }
        } finally {
            pending.forEach { inFlight -= it.fileId }
        }
    }

    // [post] must be the RAW timeline item, not one already overlaid by [withOwnReactions] — the pinned
    // snapshot has to match what [needsResolve] compares against or the next emission undoes the flip.
    fun applyLocalToggle(post: FeedPostItem, emoji: String) {
        val current = _ownReactions.value[post.fileId].orEmpty()
        val next = if (emoji in current) current - emoji else current + emoji
        resolvedFrom[post.fileId] = post.reactionPreview
        _ownReactions.update { it + (post.fileId to next) }
    }

    private fun needsResolve(post: FeedPostItem): Boolean =
        !post.reactionPreview?.reactions.isNullOrEmpty() &&
            post.fileId !in inFlight &&
            resolvedFrom[post.fileId] != post.reactionPreview
}

// One-directional: never lowers a count, never blanks a row, so an unresolved post ([own] null) renders as before.
fun FeedPostItem.withOwnReactions(own: List<String>?): FeedPostItem {
    if (own.isNullOrEmpty()) return this
    val header = reactionPreview
    val listed = header?.reactions?.values
        ?.mapNotNull { decodeReactionEmoji(it.reactionContent) }
        .orEmpty()
        .toSet()
    val missing = own.filterNot { it in listed }.associate { glyph ->
        val raw = OdinSystemSerializer.serialize(ReactionContent(emoji = glyph))
        raw to ReactionEntry(key = raw, count = 1, reactionContent = raw)
    }
    return copy(
        ownReactions = own,
        reactionPreview = if (missing.isEmpty()) {
            header
        } else {
            (header ?: ReactionSummary()).copy(reactions = header?.reactions.orEmpty() + missing)
        },
    )
}
