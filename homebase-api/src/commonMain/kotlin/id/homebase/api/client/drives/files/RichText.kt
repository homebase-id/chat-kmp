package id.homebase.api.client.drives.files

import kotlinx.serialization.Serializable

/** Rich text node for formatted content. Ported from TypeScript RichTextNode interface. */
@Serializable
data class RichTextNode(
        val type: String? = null,
        val id: String? = null,
        val value: String? = null,
        val text: String? = null,
        val children: List<RichTextNode>? = null
)

/** Rich text type alias (list of RichTextNode). */
typealias RichText = List<RichTextNode>

/** Base reaction interface. */
@Serializable data class ReactionBase(val authorOdinId: String? = null, val body: String)

/** Comment reaction with rich text support. */
@Serializable
data class CommentReaction(
    val authorOdinId: String? = null,
    val body: String,
    val bodyAsRichText: RichText? = null,
    val mediaPayloadKey: String? = null
)

/** Emoji reaction. */
typealias EmojiReaction = ReactionBase
