package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            fileId = post.fileId,
            // `toFeedPostItem` falls back to the globalTransitId when the file carries no uniqueId,
            // which is exactly the case for a followed identity's post on the feed drive.
            hasUniqueId = post.id != post.globalTransitId,
            authorOdinId = post.senderOdinId,
            emoji = emoji,
        )

    /** Toggle the current user's [emoji] reaction on [comment]. */
    suspend fun toggleReaction(comment: PostCommentItem, emoji: String): ToggleReactionResult =
        toggleInternal(
            driveId = comment.driveId,
            targetUniqueId = comment.id,
            fileId = comment.fileId,
            // `toCommentItem` drops a comment file with no uniqueId, so this id is always one.
            hasUniqueId = true,
            authorOdinId = comment.senderOdinId,
            emoji = emoji,
        )

    /**
     * @param targetUniqueId addresses the row for the OPTIMISTIC local write only.
     * @param fileId addresses the file for the actual send, and is what the outbox request carries.
     * @param hasUniqueId false when [targetUniqueId] is really a globalTransitId standing in for an
     *   absent uniqueId.
     *
     * A followed identity's post is aggregated onto the feed drive with no `uniqueId` at all, so
     * [OptimisticWriter.writeReactionToggle] — which resolves the row by uniqueId — can never find
     * it. That miss is expected and must not block the send, which only ever needed
     * `(driveId, fileId)`; returning early on it made every reaction on a followed post a silent
     * no-op. A miss on a post that DOES have a uniqueId still declines: the row is genuinely gone.
     */
    private suspend fun toggleInternal(
        driveId: Uuid,
        targetUniqueId: Uuid,
        fileId: Uuid,
        hasUniqueId: Boolean,
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
            if (!enqueued.enqueued) {
                Logger.w(tag = TAG) { "outbox enqueue -> $enqueued; rolling back optimistic write" }
                original?.let { optimisticWriter.rollbackWrite(driveId, it) }
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) { "toggleReaction failed to enqueue: ${t.message}" }
            original?.let { runCatching { optimisticWriter.rollbackWrite(driveId, it) } }
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

    /**
     * Live reaction summary for [post] shaped as the header [ReactionSummary] the feed UI already
     * renders. Read fresh through the shared group-reactions provider — the same live path chat uses
     * ([id.homebase.chat.services.ChatMessageActionService.getReactions]) — rather than the stale
     * `reactionPreview` snapshot on the header, which never reflects reactions that landed after the
     * post was aggregated into the feed. The provider keys its count map by the raw reactionContent
     * JSON, which is exactly what [ReactionEntry.reactionContent] and the facepile decode, so it
     * passes straight through. Comment previews aren't part of this (the detail streams the full
     * thread separately), so `comments`/`totalCommentCount` stay empty here.
     */
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

    /**
     * The bare emoji glyphs the signed-in identity holds on [post], read from the group-reactions
     * roster.
     *
     * There is no cheaper source. The header's [ReactionSummary] carries per-emoji counts but no
     * per-identity data, and [id.homebase.api.client.drives.files.LocalAppMetadata.localReactions]
     * — the mirror chat's `ownReactions` reads — is written ONLY by the local optimistic writer, so
     * it is null on every post header (it never survives a round trip and never arrives from
     * another device). Without this read the like button can never render active.
     */
    suspend fun ownReactions(post: FeedPostItem): List<String> {
        val self = credentialsManager.getActiveCredentials()?.domain ?: return emptyList()
        return listReactors(post, null).filter { it.odinId == self }.map { it.emoji }.distinct()
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

/**
 * Resolves and caches "which of these reactions are mine" for the posts on the home timeline.
 *
 * The header's [ReactionSummary] stays the tally source — it is server-maintained and, for a
 * followed identity's post, kept in step by the author's feed distribution — so this never
 * overwrites it. What the header cannot express is which reaction is *ours*, and that costs one
 * roster read per post, so the read is gated three ways:
 *
 *  1. **Nobody reacted ⇒ we didn't either.** A post whose header shows no reactions is skipped
 *     outright and costs nothing, ever. On live data that is ~80% of the timeline.
 *  2. **Window.** Only the first `limit` posts are considered; the caller widens that as the user
 *     pages, so a post far below the scroll position stays free until it is reached.
 *  3. **Cache.** A resolved post is pinned to the exact header snapshot it was resolved from, so
 *     the timeline re-emitting (every sync batch does) never refetches — only a header whose
 *     reactions actually changed is read again.
 *
 * A failed read records nothing: the row keeps its header snapshot rather than blanking, and the
 * post is retried on the next emission.
 *
 * Not thread-safe — drive it from a single dispatcher (the owning ViewModel's `viewModelScope`).
 */
class PostOwnReactionResolver(private val reactions: PostReactionService) {

    private companion object {
        private const val TAG = "PostOwnReactionResolver"
    }

    private val _ownReactions = MutableStateFlow<Map<Uuid, List<String>>>(emptyMap())

    /** `fileId → the bare emoji glyphs this identity holds`, for the posts resolved so far. */
    val ownReactions: StateFlow<Map<Uuid, List<String>>> = _ownReactions.asStateFlow()

    /** `fileId → the header snapshot its cached entry was resolved from`; a change invalidates it. */
    private val resolvedFrom = mutableMapOf<Uuid, ReactionSummary?>()

    private val inFlight = mutableSetOf<Uuid>()

    /** Resolve every not-yet-cached post in the first [limit] of [posts]. */
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

    /**
     * Flip our own glyph on [post] locally. The send goes through the outbox and the tally only
     * moves once the author's server redistributes the header, so without this the like button
     * would stay dark for as long as that takes.
     *
     * [post] must be the RAW timeline item, not one already overlaid by [withOwnReactions] — the
     * pinned snapshot has to match what [needsResolve] will compare against, or the next emission
     * re-reads and undoes the flip before the toggle has landed.
     */
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

/**
 * Overlay the resolved [own] glyphs onto a post for rendering.
 *
 * The header tally is kept as-is; a glyph we hold that the header does not list yet — our own
 * toggle is enqueued but the author has not redistributed the preview — is added with a count of 1
 * so the reaction we just made is visible. The merge is one-directional: it never lowers a count
 * and never blanks a row, so an unresolved or failed post ([own] null) renders exactly as before.
 */
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
