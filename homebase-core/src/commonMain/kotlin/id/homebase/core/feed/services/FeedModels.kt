package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.CommentPreview
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid

// Envelope identity ([senderOdinId], [originalAuthor], [createdMs]) comes from the HomebaseFile, never the
// descriptor. [keyHeader] and [payloads] stay raw so the media widgets can render encrypted payloads directly.
data class FeedPostItem(
    val id: Uuid,
    val fileId: Uuid,
    val globalTransitId: Uuid?,
    val driveId: Uuid,
    val keyHeader: KeyHeader,
    val payloads: List<PayloadDescriptor>,
    val caption: String,
    val type: PostType,
    val channelId: String,
    val slug: String,
    val reactAccess: ReactAccess,
    val embeddedPost: EmbeddedPost?,
    val userDateMs: Long,
    /** Drives the timeline sort; [userDateMs] can be backdated. */
    val createdMs: Long,
    val previewThumbnail: EmbeddedThumb?,
    val reactionPreview: ReactionSummary?,
    /** Null on the author's own drive copy. */
    val senderOdinId: OdinId?,
    val originalAuthor: OdinId?,
    /** Required to submit an in-place edit. Null on an optimistic write. */
    val versionTag: Uuid?,
    val ownReactions: List<String>,
    val commentCount: Int,
    val isEncrypted: Boolean,
    val acl: AccessControlList?,
)

// originalAuthor is set on the author's own copy; a post aggregated onto someone else's feed drive carries
// senderOdinId instead.
val FeedPostItem.authorOdinId: OdinId? get() = originalAuthor ?: senderOdinId

val PostCommentItem.authorOdinId: OdinId? get() = originalAuthor ?: senderOdinId

fun FeedPostItem.isAuthoredBy(self: OdinId?): Boolean = self != null && authorOdinId == self

fun PostCommentItem.isAuthoredBy(self: OdinId?): Boolean = self != null && authorOdinId == self

// Mirrors the web's AclSummary: unknown/blank security groups fall back to [Owner], the narrowest reading, so
// a post is never labelled more public than it is.
enum class PostAudience {
    Public,
    Authenticated,
    AutoConnected,
    Connections,
    Circles,
    Owner,
}

val PostAudience.isRestricted: Boolean
    get() = this != PostAudience.Public && this != PostAudience.Authenticated

// A file with no ACL at all is public — that's how the server represents an anonymous-readable file.
// Deliberately not routed through [SecurityGroupType.fromString], which defaults unknown values to Anonymous
// and would label a post public that we can't actually classify.
fun AccessControlList?.toPostAudience(): PostAudience {
    if (this == null) return PostAudience.Public
    return when (requiredSecurityGroup?.lowercase()) {
        SecurityGroupType.Anonymous.value -> PostAudience.Public
        SecurityGroupType.Authenticated.value -> PostAudience.Authenticated
        SecurityGroupType.AutoConnected.value -> PostAudience.AutoConnected
        SecurityGroupType.Connected.value ->
            if (circleIdList.isNullOrEmpty()) PostAudience.Connections else PostAudience.Circles
        else -> PostAudience.Owner
    }
}

// Threading is strictly one level — a reply carries its parent comment id in [replyToId].
data class PostCommentItem(
    val id: Uuid,
    val postId: Uuid,
    val senderOdinId: OdinId?,
    val originalAuthor: OdinId?,
    val body: String,
    val mediaPayloadKey: String?,
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

// Returns null only when the file carries neither a uniqueId nor a globalTransitId. A failed [PostContent]
// parse yields a best-effort item rather than dropping the post entirely.
fun HomebaseFile.toFeedPostItem(): FeedPostItem? {
    val appData = fileMetadata.appData
    // Posts from followed identities are aggregated onto the feed drive as references carrying only a
    // globalTransitId. Both values stably identify the post for dedup; they never collide.
    val uniqueId = appData.uniqueId ?: fileMetadata.globalTransitId ?: return null
    val content = appData.content?.let { raw ->
        runCatching { OdinSystemSerializer.deserialize<PostContent>(raw) }
            .onFailure { Logger.w(tag = "FeedModels") { "PostContent parse failed: ${it.message}" } }
            .getOrNull()
    }
    val ownReactions = fileMetadata.localAppData?.localReactions
        ?.mapNotNull { raw -> decodeReactionEmoji(raw) }
        .orEmpty()
    return FeedPostItem(
        id = uniqueId,
        fileId = fileId,
        globalTransitId = fileMetadata.globalTransitId,
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
        isEncrypted = fileMetadata.isEncrypted,
        acl = serverMetadata.accessControlList,
    )
}

// The parent post id is the file's groupId for a top-level comment; for a reply the groupId is the parent
// comment id, exposed as [replyToId]. [topLevelPostId] is the post the surrounding query was scoped to.
fun HomebaseFile.toCommentItem(topLevelPostId: Uuid): PostCommentItem? {
    val appData = fileMetadata.appData
    val uniqueId = appData.uniqueId ?: return null
    val groupId = appData.groupId ?: return null
    val content = appData.content?.let { raw ->
        runCatching { OdinSystemSerializer.deserialize<PostCommentContent>(raw) }.getOrNull()
    }
    val isReply = groupId != topLevelPostId
    val ownReactions = fileMetadata.localAppData?.localReactions
        ?.mapNotNull { raw -> decodeReactionEmoji(raw) }
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

/** Decode a reaction's stored `reactionContent` JSON to its bare emoji glyph, or null on failure. */
internal fun decodeReactionEmoji(reactionContent: String): String? = runCatching {
    OdinSystemSerializer.deserialize<ReactionContent>(reactionContent).emoji
}.getOrNull()

/** [CommentPreview.content] is raw [PostCommentContent] JSON, not text — parse out the body. */
fun CommentPreview.previewBody(): String {
    if (isEncrypted || content.isBlank()) return ""
    return runCatching {
        OdinSystemSerializer.deserialize<PostCommentContent>(content).body
    }.getOrDefault("")
}
