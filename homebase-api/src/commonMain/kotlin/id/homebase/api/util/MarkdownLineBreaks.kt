package id.homebase.api.util

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
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
    val text = replace("\r\n", "\n").replace('\r', '\n')

    val softBreakOffsets: Set<Int> = try {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        HashSet<Int>().also { collectParagraphSoftBreaks(tree, insideParagraph = false, out = it) }
    } catch (_: Throwable) {
        return text
    }
    if (softBreakOffsets.isEmpty()) return text

    val sb = StringBuilder(text.length + softBreakOffsets.size * 2)
    for (i in text.indices) {
        if (i in softBreakOffsets) {
            val alreadyHard = i >= 2 && text[i - 1] == ' ' && text[i - 2] == ' '
            if (!alreadyHard) sb.append("  ")
        }
        sb.append(text[i])
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
