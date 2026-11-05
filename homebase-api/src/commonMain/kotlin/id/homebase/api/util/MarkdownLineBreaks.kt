package id.homebase.api.util

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

fun String.normalizeChatNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

/**
 * Paragraph soft breaks, which the renderer draws as a space, become hard breaks, so a message
 * typed with single Enters keeps its lines. Newlines in code and HTML blocks are content and stay.
 * [this] must be the exact string [tree] was parsed from.
 */
fun String.withParagraphSoftBreaksPromoted(tree: ASTNode): String {
    val softBreakOffsets = HashSet<Int>().also { collectParagraphSoftBreaks(tree, insideParagraph = false, out = it) }
    if (softBreakOffsets.isEmpty()) return this
    val sb = StringBuilder(length + softBreakOffsets.size * 2)
    for (i in indices) {
        if (i in softBreakOffsets) {
            val alreadyHard = i >= 2 && this[i - 1] == ' ' && this[i - 2] == ' '
            if (!alreadyHard) sb.append("  ")
        }
        sb.append(this[i])
    }
    return sb.toString()
}

/** Code/HTML subtrees whose internal newlines are content, not soft breaks. */
private val protectedBlockTypes = setOf(
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.HTML_BLOCK,
)

private fun collectParagraphSoftBreaks(node: ASTNode, insideParagraph: Boolean, out: MutableSet<Int>) {
    if (node.type in protectedBlockTypes) return
    val nowParagraph = insideParagraph || node.type == MarkdownElementTypes.PARAGRAPH
    if (nowParagraph && node.type == MarkdownTokenTypes.EOL) {
        out.add(node.startOffset)
    }
    for (child in node.children) collectParagraphSoftBreaks(child, nowParagraph, out)
}
