package id.homebase.api.util

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Convert a markdown body to a single-line plain-text preview.
 *
 * This is the ONE strip path the whole app uses for previews/notifications. It
 * parses the markdown with the same CommonMark engine the chat renderer
 * (mikepenz) and the editor (richeditor) sit on, then walks the AST collecting
 * the text the renderer would actually draw — emphasis/heading/quote/list
 * markers, code fences and link/image URLs are dropped; link and image *alt*
 * text is kept. The result is whitespace-collapsed to a single line and
 * truncated on a code-point boundary so emoji and other surrogate pairs are
 * never split.
 *
 * Replaces the two divergent regex strip paths (`ChatMessageSizer.preview` and
 * `String.stripMarkdownForPreview`) whose `[*_`#>\-]` class mangled legitimate
 * hyphens/underscores in the middle of plain words and dropped trailing
 * punctuation.
 *
 * @param markdown the raw markdown body.
 * @param maxCodePoints maximum number of code points to keep.
 */
fun markdownToPlainPreview(markdown: String, maxCodePoints: Int): String {
    if (markdown.isEmpty()) return ""

    val tree = try {
        MarkdownParser(CommonMarkFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    } catch (_: Throwable) {
        // The CommonMark parser is robust, but never let a preview crash the
        // caller — fall back to a whitespace-collapsed copy of the raw source.
        return markdown.collapseWhitespace().truncateToCodePoints(maxCodePoints)
    }

    val sb = StringBuilder()
    appendPlainText(tree, markdown, sb)
    return sb.toString().collapseWhitespace().truncateToCodePoints(maxCodePoints)
}

/** Markup-only leaf tokens that must never appear in the rendered plain text. */
private val markupOnlyLeafTokens: Set<IElementType> = setOf(
    MarkdownTokenTypes.EMPH,                 // * or _
    MarkdownTokenTypes.BACKTICK,             // ` for inline code
    MarkdownTokenTypes.ESCAPED_BACKTICKS,
    MarkdownTokenTypes.LIST_BULLET,          // - / * / + at list start
    MarkdownTokenTypes.LIST_NUMBER,          // 1. at ordered-list start
    MarkdownTokenTypes.BLOCK_QUOTE,          // > marker
    MarkdownTokenTypes.ATX_HEADER,           // # ## ### markers
    MarkdownTokenTypes.SETEXT_1,
    MarkdownTokenTypes.SETEXT_2,
    MarkdownTokenTypes.CODE_FENCE_START,     // ``` open
    MarkdownTokenTypes.CODE_FENCE_END,       // ``` close
    MarkdownTokenTypes.FENCE_LANG,           // language tag on a fence
    MarkdownTokenTypes.HORIZONTAL_RULE,
)

/** Whitespace-like leaf tokens that collapse to a single space. */
private val whitespaceLeafTokens: Set<IElementType> = setOf(
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.WHITE_SPACE,
    MarkdownTokenTypes.HARD_LINE_BREAK,
)

/**
 * Composite link node types: keep only the visible label, drop the URL. NOTE:
 * IMAGE is intentionally NOT here — an IMAGE node wraps `! INLINE_LINK`, so we
 * just recurse into it (dropping the `!` marker) and let the inner INLINE_LINK
 * extract its own label.
 */
private val linkLikeNodes: Set<IElementType> = setOf(
    MarkdownElementTypes.INLINE_LINK,
    MarkdownElementTypes.FULL_REFERENCE_LINK,
    MarkdownElementTypes.SHORT_REFERENCE_LINK,
)

/** Node subtrees to drop entirely (URLs, titles, link definitions). */
private val dropSubtreeNodes: Set<IElementType> = setOf(
    MarkdownElementTypes.LINK_DESTINATION,
    MarkdownElementTypes.LINK_TITLE,
    MarkdownElementTypes.LINK_DEFINITION,
)

/**
 * Depth-first walk that appends the visible text of [node] to [out].
 *
 * Strategy is a *denylist*, not an allowlist: every leaf contributes its literal
 * text unless it is a known markup-only marker. This keeps ordinary punctuation
 * (`!`, `.`, `,`, parentheses in prose) intact — an allowlist silently dropped
 * trailing `!` and broke "Hello there!" previews. Link/image nodes recurse only
 * into their label; their destination/title subtrees are skipped.
 */
private fun appendPlainText(node: ASTNode, source: String, out: StringBuilder) {
    val type = node.type

    if (type in dropSubtreeNodes) return

    if (type == MarkdownElementTypes.IMAGE) {
        // IMAGE wraps `! INLINE_LINK`. Drop the bang marker, recurse the rest
        // (the inner INLINE_LINK extracts its label, the URL is dropped there).
        for (child in node.children) {
            if (child.type == MarkdownTokenTypes.EXCLAMATION_MARK) continue
            appendPlainText(child, source, out)
        }
        return
    }

    if (type in linkLikeNodes) {
        // Keep the label only. For inline links the label lives under LINK_TEXT;
        // for reference links / images the same. Recurse into LINK_TEXT children,
        // and if there is none (short reference link) fall back to LINK_LABEL.
        val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            ?: node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
        if (label != null) {
            appendLinkLabel(label, source, out)
        }
        return
    }

    val children = node.children
    if (children.isEmpty()) {
        when (type) {
            in markupOnlyLeafTokens -> return
            in whitespaceLeafTokens -> out.append(' ')
            // LINK_LABEL/LINK_TEXT brackets are handled by appendLinkLabel; any
            // stray bracket tokens reaching here are literal text, keep them.
            else -> out.append(node.getTextInNode(source))
        }
        return
    }

    for (child in children) {
        appendPlainText(child, source, out)
    }
}

/**
 * Append the visible text of a LINK_TEXT / LINK_LABEL node, stripping the
 * surrounding `[` `]` bracket tokens but keeping the inner text (which may itself
 * contain emphasis we further strip via the normal walk).
 */
private fun appendLinkLabel(label: ASTNode, source: String, out: StringBuilder) {
    for (child in label.children) {
        when (child.type) {
            MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET -> Unit
            else -> appendPlainText(child, source, out)
        }
    }
}

private val whitespaceRunForPlain = Regex("\\s+")

private fun String.collapseWhitespace(): String =
    replace(whitespaceRunForPlain, " ").trim()
