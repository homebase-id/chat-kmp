package id.homebase.homebasekmppoc.prototype.lib.drives.files

import kotlinx.serialization.Serializable

/** Rich text node for formatted content. Ported from TypeScript RichTextNode interface. */
@Serializable
data class RichTextNode(
        val type: String? = null,
        val id: String? = null,
        val value: String? = null,
        val text: String? = null,
        val children: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.RichTextNode>? = null
)

/** Rich text type alias (list of RichTextNode). */
typealias RichText = List<id.homebase.homebasekmppoc.prototype.lib.drives.files.RichTextNode>

/** Base reaction interface. */
@Serializable data class ReactionBase(val authorOdinId: String? = null, val body: String)

/** Comment reaction with rich text support. */
@Serializable
data class CommentReaction(
    val authorOdinId: String? = null,
    val body: String,
    val bodyAsRichText: id.homebase.homebasekmppoc.prototype.lib.drives.files.RichText? = null,
    val mediaPayloadKey: String? = null
)

/** Emoji reaction. */
typealias EmojiReaction = id.homebase.homebasekmppoc.prototype.lib.drives.files.ReactionBase
