package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid

/**
 * Everything the feed list and detail screens need from a single post, with the on-disk
 * descriptor already deserialised. Mirrors [id.homebase.core.moments.services.MomentFeedItem]:
 * drive-level fields ([keyHeader], [payloads]) stay raw so the media widgets can render
 * encrypted payloads directly, while the [PostContent] fields the UI reads are lifted out.
 *
 * Envelope identity ([senderOdinId], [originalAuthor], [createdMs]) comes from the
 * [HomebaseFile]; never from the descriptor (see [PostContent]).
 */
data class FeedPostItem(
    val id: Uuid,
    val fileId: Uuid,
    val driveId: Uuid,
    val keyHeader: KeyHeader,
    val payloads: List<PayloadDescriptor>,
    // ---- deserialized PostContent fields the UI needs ----
    val caption: String,
    val type: PostType,
    val channelId: String,
    val slug: String,
    val reactAccess: ReactAccess,
    val embeddedPost: EmbeddedPost?,
    // ---- envelope fields ----
    val userDateMs: Long,
    /**
     * Server-side creation timestamp — when the post was published. Drives the timeline
     * sort (newest posted first), distinct from [userDateMs] which may be backdated.
     */
    val createdMs: Long,
    val previewThumbnail: EmbeddedThumb?,
    val reactionPreview: ReactionSummary?,
    /** Original sender on a receiving drive; null on the author's own drive copy. */
    val senderOdinId: OdinId?,
    /** Identity that authored the post — populated on the author's own copy too. */
    val originalAuthor: OdinId?,
    /** Header version tag, required to submit an in-place edit. Null on an optimistic write. */
    val versionTag: Uuid?,
    /** Bare emoji glyphs the current user has reacted with (from `localReactions`). */
    val ownReactions: List<String>,
    /** Comment count from the embedded reaction/comment preview. */
    val commentCount: Int,
    /**
     * Author whose drive hosts this post when it's a followed-identity reference on the feed
     * drive — its media bytes are remote and fetched over peer (with [globalTransitId]). Null for
     * the user's own posts, whose media is local.
     */
    val remoteOdinId: OdinId? = null,
    /** Cross-identity global id of the post, used with [remoteOdinId] for the over-peer media fetch. */
    val globalTransitId: Uuid? = null,
)

/**
 * Everything a comment list/item needs from a single comment file (`fileType = 801`).
 * Mirrors [id.homebase.core.moments.services.MomentCommentItem]; threading is strictly
 * one level — a reply carries its parent comment id in [replyToId].
 */
data class PostCommentItem(
    val id: Uuid,
    /** The post this comment belongs to (the file's `groupId` for a top-level comment). */
    val postId: Uuid,
    val senderOdinId: OdinId?,
    val originalAuthor: OdinId?,
    val body: String,
    val mediaPayloadKey: String?,
    /** Parent comment id when this is a one-level reply; null for a top-level comment. */
    val replyToId: Uuid?,
    val userDateMs: Long,
    val createdMs: Long,
    val fileId: Uuid,
    val driveId: Uuid,
    val keyHeader: KeyHeader,
    val payloads: List<PayloadDescriptor>,
    val previewThumbnail: EmbeddedThumb?,
    val versionTag: Uuid?,
    val reactionPreview: ReactionSummary?,
    val ownReactions: List<String>,
)

/**
 * Map a post file into a [FeedPostItem]. Returns null when the file has no uniqueId — the
 * post can't be addressed without one. A failed [PostContent] parse yields a best-effort item
 * (empty caption, Tweet type) rather than dropping the post entirely. Mirrors
 * `MomentsFeedService.toFeedItem`.
 */
fun HomebaseFile.toFeedPostItem(): FeedPostItem? {
    val appData = fileMetadata.appData
    // Own-drive posts carry a uniqueId. Posts from followed identities are aggregated onto the
    // feed drive as references that carry only a globalTransitId (no uniqueId) — fall back to it
    // so followed/public posts surface in the timeline instead of being dropped. Both values
    // stably identify the post for dedup; they never collide (own posts aren't feed references).
    val isReference = appData.uniqueId == null
    val id = appData.uniqueId ?: fileMetadata.globalTransitId ?: return null
    val content = appData.content?.let { raw ->
        runCatching { OdinSystemSerializer.deserialize<PostContent>(raw) }
            .onFailure { Logger.w(tag = "FeedModels") { "PostContent parse failed: ${it.message}" } }
            .getOrNull()
    }
    val ownReactions = fileMetadata.localAppData?.localReactions
        ?.mapNotNull { raw -> decodeOwnReactionEmoji(raw) }
        .orEmpty()
    return FeedPostItem(
        id = id,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        payloads = fileMetadata.payloads.orEmpty(),
        caption = content?.caption.orEmpty(),
        type = content?.type ?: PostType.Tweet,
        channelId = content?.channelId.orEmpty(),
        slug = content?.slug.orEmpty(),
        reactAccess = content?.reactAccess ?: ReactAccess.All,
        embeddedPost = content?.embeddedPost,
        userDateMs = sqlUserDateMs(),
        createdMs = fileMetadata.created.milliseconds,
        previewThumbnail = appData.previewThumbnail,
        reactionPreview = fileMetadata.reactionPreview,
        senderOdinId = fileMetadata.senderOdinId,
        originalAuthor = fileMetadata.originalAuthor,
        versionTag = fileMetadata.versionTag,
        ownReactions = ownReactions,
        commentCount = fileMetadata.reactionPreview?.totalCommentCount ?: 0,
        // A reference (no uniqueId) is a followed identity's post: its media lives on the author's
        // drive and is fetched over peer. senderOdinId is the author who hosts it.
        remoteOdinId = if (isReference) (fileMetadata.senderOdinId ?: fileMetadata.originalAuthor) else null,
        globalTransitId = fileMetadata.globalTransitId,
    )
}

/**
 * Map a comment file into a [PostCommentItem]. Returns null when the file has no uniqueId or
 * no groupId (a comment with no parent post is unaddressable). The parent post id is the file's
 * `groupId` for a top-level comment; for a reply the `groupId` is the parent comment id, which
 * is then exposed as [PostCommentItem.replyToId]. The caller knows which a given query returned.
 *
 * @param topLevelPostId the post the surrounding query was scoped to. When the file's `groupId`
 *   differs from this, the file is treated as a reply and `groupId` becomes [replyToId].
 */
fun HomebaseFile.toCommentItem(topLevelPostId: Uuid): PostCommentItem? {
    val appData = fileMetadata.appData
    val uniqueId = appData.uniqueId ?: return null
    val groupId = appData.groupId ?: return null
    val content = appData.content?.let { raw ->
        runCatching { OdinSystemSerializer.deserialize<PostCommentContent>(raw) }.getOrNull()
    }
    val isReply = groupId != topLevelPostId
    val ownReactions = fileMetadata.localAppData?.localReactions
        ?.mapNotNull { raw -> decodeOwnReactionEmoji(raw) }
        .orEmpty()
    return PostCommentItem(
        id = uniqueId,
        postId = topLevelPostId,
        senderOdinId = fileMetadata.senderOdinId,
        originalAuthor = fileMetadata.originalAuthor,
        body = content?.body.orEmpty(),
        mediaPayloadKey = content?.mediaPayloadKey,
        replyToId = if (isReply) groupId else null,
        userDateMs = sqlUserDateMs(),
        createdMs = fileMetadata.created.milliseconds,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        payloads = fileMetadata.payloads.orEmpty(),
        previewThumbnail = appData.previewThumbnail,
        versionTag = fileMetadata.versionTag,
        reactionPreview = fileMetadata.reactionPreview,
        ownReactions = ownReactions,
    )
}

/** Decode a stored `localReactions` JSON entry to its bare emoji glyph, or null on failure. */
internal fun decodeOwnReactionEmoji(reactionContent: String): String? = runCatching {
    OdinSystemSerializer.deserialize<ReactionContent>(reactionContent).emoji
}.getOrNull()
