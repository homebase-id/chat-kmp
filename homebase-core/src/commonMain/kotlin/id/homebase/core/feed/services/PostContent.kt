package id.homebase.core.feed.services

import kotlinx.serialization.Serializable

/**
 * Kind of feed post — mirrors dotyoucore-js `PostType`. Maps to a [FeedProtocol] dataType
 * on the wire via [toDataType].
 */
@Serializable
enum class PostType {
    Tweet,
    Media,
    Article,
}

/** Wire dataType for this [PostType] (see [FeedProtocol.TweetDataType] etc.). */
fun PostType.toDataType(): Int = when (this) {
    PostType.Tweet -> FeedProtocol.TweetDataType
    PostType.Media -> FeedProtocol.MediaDataType
    PostType.Article -> FeedProtocol.ArticleDataType
}

/**
 * Who may react to / comment on a post — mirrors dotyoucore-js `ReactAccess`.
 * Defaults to [All] so legacy posts that pre-date this field stay fully interactive.
 */
@Serializable
enum class ReactAccess {
    All,
    EmojiOnly,
    CommentOnly,
    None,
}

/**
 * Descriptor for a feed post, serialized through
 * [id.homebase.api.serialization.OdinSystemSerializer] (camelCase, `ignoreUnknownKeys = true`,
 * `explicitNulls = false`). Ported from dotyoucore-js `PostContent`.
 *
 * Envelope fields (author odinId, created/userDate timestamps) are NOT duplicated here — they
 * come from the [id.homebase.api.client.drives.files.HomebaseFile] this descriptor rides on.
 * [caption] and [body] are user-entered text.
 *
 * Nullable fields default to forward-compat values so older clients tolerate newer posts and
 * vice-versa.
 */
@Serializable
data class PostContent(
    val version: Int,
    val id: String,
    val channelId: String,
    val type: PostType,
    /** User-entered caption / status text. */
    val caption: String,
    val slug: String,
    /** Optional rich-text (e.g. JSON rich-text tree) for the caption. */
    val captionRichText: String? = null,
    /** Payload key of the lead media for media posts (see [FeedProtocol.mediaPayloadKey]). */
    val primaryMediaKey: String? = null,
    val reactAccess: ReactAccess = ReactAccess.All,
    /** A quoted / reposted source post, if this is a repost. */
    val embeddedPost: EmbeddedPost? = null,
    /** Canonical external URL for an article post. */
    val sourceUrl: String? = null,
    /** Short summary for an article post. */
    val abstract: String? = null,
    /** Long-form article body (user text); may overflow to the `pst_text` payload. */
    val body: String? = null,
)

/**
 * A post embedded inside another post (repost / quote). Carries only what the embedding post
 * needs to render the quote inline; the full source post is read separately by its
 * [globalTransitId] / [fileId] when present.
 */
@Serializable
data class EmbeddedPost(
    /** odinId of the embedded post's author. */
    val author: String? = null,
    val caption: String? = null,
    val type: PostType? = null,
    val fileId: String? = null,
    val globalTransitId: String? = null,
    /** Author's userDate (epoch ms) of the embedded post. */
    val userDate: Long? = null,
    /** Inline preview thumbnail (e.g. data URI / payload reference) for the embed. */
    val previewThumbnail: String? = null,
)

/**
 * Definition of a channel drive (`fileType = 103`). Ported from dotyoucore-js
 * `ChannelDefinition`. Default channel = the public channel.
 */
@Serializable
data class ChannelDefinition(
    val name: String,
    val slug: String,
    val description: String = "",
    val showOnHomePage: Boolean = true,
    val templateId: Int? = null,
    val isCollaborative: Boolean = false,
)
