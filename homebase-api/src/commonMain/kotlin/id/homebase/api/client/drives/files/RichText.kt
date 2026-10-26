package id.homebase.api.client.drives.files

import kotlinx.serialization.Serializable

/** Rich text node for formatted content. Ported from TypeScript RichTextNode interface. */
@Serializable
data class RichTextNode(
    val type: String? = null,
    val id: String? = null,
    val value: String? = null,/**/
    val text: String? = null,
    val children: List<RichTextNode>? = null,
    val url: String? = null,
    val bold: Boolean? = null,
    val strong: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val code: Boolean? = null,
    val kbd: Boolean? = null
)

/** Rich text type alias (list of RichTextNode). */
typealias RichText = List<RichTextNode>

/** Base reaction interface. */
@Serializable
data class ReactionBase(val authorOdinId: String? = null, val body: String)

/** Comment reaction with rich text support. */
@Serializable
data class CommentReaction(
    val authorOdinId: String? = null,
    val body: String,
    val bodyAsRichText: RichText? = null,
    val mediaPayloadKey: String? = null
)

/** Children's text joined by [separator], then the node's own text or value after a space. */
internal fun RichTextNode.plainText(separator: String): String {
    val nested = children.orEmpty().map { it.plainText(separator) }.filter { it.isNotEmpty() }.joinToString(separator)
    return listOf(nested, text ?: value).filter { !it.isNullOrEmpty() }.joinToString(" ")
}

fun getPlainTextFromRichText(message: RichText?, keepNewLines: Boolean = false): String? {
    if (message.isNullOrEmpty()) return null
    val separator = if (keepNewLines) "\n" else " "
    return message.map { it.plainText(separator) }.filter { it.isNotEmpty() }.joinToString(separator)
}
