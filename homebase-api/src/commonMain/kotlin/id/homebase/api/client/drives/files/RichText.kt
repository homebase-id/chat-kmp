package id.homebase.api.client.drives.files

import kotlinx.serialization.Serializable

/** Rich text node for formatted content. Ported from TypeScript RichTextNode interface. */
@Serializable
data class RichTextNode(
    val type: String? = null,
    val id: String? = null,
    val value: String? = null,/**/
    val text: String? = null,
    val children: List<RichTextNode>? = null
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

fun getTextRootsRecursive(children: RichText?, keepNewLines: Boolean = false): List<String> {
    if (children.isNullOrEmpty()) return emptyList()

    return children.flatMap { child ->
        val childTexts = mutableListOf<String>()
        if (!child.children.isNullOrEmpty()) {
            val nestedText =
                getTextRootsRecursive(child.children, keepNewLines)
                    .joinToString(if (keepNewLines) "\n" else " ")
            if (nestedText.isNotEmpty()) {
                childTexts.add(nestedText)
            }
        }
        val directText = child.text ?: child.value
        if (!directText.isNullOrEmpty()) {
            childTexts.add(directText)
        }

        val joined = childTexts.filter { it.isNotEmpty() }.joinToString(" ")
        if (joined.isNotEmpty()) listOf(joined) else emptyList()
    }
}

fun getPlainTextFromRichText(message: RichText?, keepNewLines: Boolean = false): String? {
    if (message.isNullOrEmpty()) return null
    return getTextRootsRecursive(message, keepNewLines)
        .joinToString(if (keepNewLines) "\n" else " ")
}
