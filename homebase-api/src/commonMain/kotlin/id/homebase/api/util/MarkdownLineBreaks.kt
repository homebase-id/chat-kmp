package id.homebase.api.util

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser

/**
 * Convert paragraph-internal single newlines (CommonMark "soft breaks", which the
 * renderer draws as a SPACE) into hard line breaks, so a chat message typed with
 * single Enters keeps its line structure — matching Slack/Discord/WhatsApp.
 *
 * Precise by construction: only [MarkdownTokenTypes.EOL] tokens that sit inside a
 * [MarkdownElementTypes.PARAGRAPH] subtree are converted. Blank-line paragraph
 * breaks (`\n\n`) live BETWEEN paragraphs (children of the file root), so they are
 * never touched; newlines inside fenced/indented code and HTML blocks are skipped
 * because those subtrees are pruned. This is the structural equivalent of
 * markdown-it's `breaks: true`.
 *
 * Defensive: any parser failure returns the (CRLF-normalised) input unchanged so a
 * transform can never crash a render.
 */
fun String.withChatHardLineBreaks(): String {
    if (isEmpty()) return this
    val text = normalizeChatNewlines()
    val tree = try {
        MarkdownParser(ChatMarkdownFlavour).buildMarkdownTreeFromString(text)
    } catch (_: Throwable) {
        return text
    }
    return text.withParagraphSoftBreaksPromoted(tree)
}

fun String.normalizeChatNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

/** [this] must be the exact string [tree] was parsed from. */
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
