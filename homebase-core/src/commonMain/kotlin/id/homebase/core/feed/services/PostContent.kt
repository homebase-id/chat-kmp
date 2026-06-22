package id.homebase.core.feed.services

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
@Serializable(with = ReactAccessSerializer::class)
enum class ReactAccess {
    All,
    EmojiOnly,
    CommentOnly,
    None,
}

/**
 * dotyoucore-js serializes `reactAccess` as `true | false | 'comment' | 'emoji'` (a boolean/string
 * union) — NOT the Kotlin enum names. Posts with a boolean `reactAccess` were therefore failing to
 * parse and silently dropping out of the feed (incl. most followed identities' posts). This maps
 * the web wire form ↔ [ReactAccess] (tolerating the native enum names too) and writes the web form
 * back so our posts/reposts round-trip to the web. Semantics mirror web PostInteracts:
 * `true`→All, `false`→None, `'comment'`→CommentOnly (emoji off), `'emoji'`→EmojiOnly (comment off).
 */
object ReactAccessSerializer : KSerializer<ReactAccess> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReactAccess", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ReactAccess {
        val json = decoder as? JsonDecoder ?: return ReactAccess.All
        val prim = json.decodeJsonElement() as? JsonPrimitive ?: return ReactAccess.All
        prim.booleanOrNull?.let { return if (it) ReactAccess.All else ReactAccess.None }
        return when (prim.content) {
            "comment", "CommentOnly" -> ReactAccess.CommentOnly
            "emoji", "EmojiOnly" -> ReactAccess.EmojiOnly
            "None" -> ReactAccess.None
            else -> ReactAccess.All
        }
    }

    override fun serialize(encoder: Encoder, value: ReactAccess) {
        val element: JsonElement = when (value) {
            ReactAccess.All -> JsonPrimitive(true)
            ReactAccess.None -> JsonPrimitive(false)
            ReactAccess.CommentOnly -> JsonPrimitive("comment")
            ReactAccess.EmojiOnly -> JsonPrimitive("emoji")
        }
        (encoder as? JsonEncoder)?.encodeJsonElement(element) ?: encoder.encodeString(value.name)
    }
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
    // All envelope-ish fields default so a single missing key (e.g. dotyoucore-js posts
    // carry no `version`) can't fail the whole parse and blank out the caption.
    val version: Int = 0,
    val id: String = "",
    val channelId: String = "",
    val type: PostType = PostType.Tweet,
    /** User-entered caption / status text. */
    val caption: String = "",
    val slug: String = "",
    /**
     * Optional rich-text for the caption. On the wire this is a rich-text tree (a JSON
     * array/object), NOT a string — type it [JsonElement] so a present value doesn't fail the
     * whole parse and blank out [caption]. We render [caption] (plain text); this is kept only
     * so a round-trip preserves it.
     */
    val captionRichText: JsonElement? = null,
    /** Payload key of the lead media for media posts (see [FeedProtocol.mediaPayloadKey]). */
    val primaryMediaKey: String? = null,
    val reactAccess: ReactAccess = ReactAccess.All,
    /** A quoted / reposted source post, if this is a repost. */
    val embeddedPost: EmbeddedPost? = null,
    /** Canonical external URL for an article post. */
    val sourceUrl: String? = null,
    /** Short summary for an article post. */
    val abstract: String? = null,
    /**
     * Long-form article body. On the wire this is a rich-text tree (JSON array/object), not a
     * string — [JsonElement] so it parses without throwing (it threw at `$.body` and blanked
     * every caption). Article rendering reads this later; for now it's parse-tolerance only.
     */
    val body: JsonElement? = null,
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
    /**
     * Inline preview thumbnail for the embed. On the wire this is a thumbnail OBJECT
     * (`{ pixelWidth, pixelHeight, contentType, ... }`), NOT a string — type it [JsonElement] for
     * parse-tolerance, since a `{...}` value was failing the whole repost parse and dropping it.
     */
    val previewThumbnail: JsonElement? = null,
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
