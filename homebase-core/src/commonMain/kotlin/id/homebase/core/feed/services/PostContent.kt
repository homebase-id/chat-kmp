package id.homebase.core.feed.services

import id.homebase.api.client.drives.files.PayloadDescriptor
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

@Serializable
enum class PostType {
    Tweet,
    Media,
    Article,
}

fun PostType.toDataType(): Int = when (this) {
    PostType.Tweet -> FeedProtocol.TweetDataType
    PostType.Media -> FeedProtocol.MediaDataType
    PostType.Article -> FeedProtocol.ArticleDataType
}

// Defaults to [All] so legacy posts that pre-date this field stay fully interactive.
@Serializable(with = ReactAccessSerializer::class)
enum class ReactAccess {
    All,
    EmojiOnly,
    CommentOnly,
    None,
}

// dotyoucore-js serializes reactAccess as `true | false | 'comment' | 'emoji'`, NOT the Kotlin enum names —
// posts with a boolean were failing to parse and silently dropping out of the feed. Writes the web form back
// so our posts round-trip. true→All, false→None, 'comment'→CommentOnly, 'emoji'→EmojiOnly.
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

// Envelope fields (author odinId, created/userDate timestamps) are NOT duplicated here — they come from the
// HomebaseFile this descriptor rides on. Nullable fields default so older and newer clients tolerate each other.
@Serializable
data class PostContent(
    val version: Int = 0,
    val id: String = "",
    val channelId: String = "",
    val type: PostType = PostType.Tweet,
    val caption: String = "",
    val slug: String = "",
    /** A rich-text tree on the wire, not a string — a String type fails the parse and blanks [caption]. */
    val captionRichText: JsonElement? = null,
    val primaryMediaKey: String? = null,
    val reactAccess: ReactAccess = ReactAccess.All,
    val embeddedPost: EmbeddedPost? = null,
    val sourceUrl: String? = null,
    val abstract: String? = null,
    /** A rich-text tree on the wire, not a string — a String type blanks every caption. */
    val body: JsonElement? = null,
)

// Nesting is one level only — the web strips an embed's own embed on upload.
@Serializable
data class EmbeddedPost(
    /** Wire key is `authorOdinId`, not `author` — ignoreUnknownKeys blanks the card silently. */
    val authorOdinId: String? = null,
    val caption: String? = null,
    val type: PostType? = null,
    /** The drive its payloads live on, over on the author's identity. */
    val channelId: String? = null,
    val fileId: String? = null,
    val globalTransitId: String? = null,
    val permalink: String? = null,
    val userDate: Long? = null,
    /** Web caps these at 6 when the header runs tight. */
    val payloads: List<PayloadDescriptor>? = null,
    /** A thumbnail object on the wire, not a string — a `{...}` value fails the whole parse. */
    val previewThumbnail: JsonElement? = null,
)

@Serializable
data class ChannelDefinition(
    val name: String,
    val slug: String,
    val description: String = "",
    val showOnHomePage: Boolean = true,
    val templateId: Int? = null,
    val isCollaborative: Boolean = false,
)
