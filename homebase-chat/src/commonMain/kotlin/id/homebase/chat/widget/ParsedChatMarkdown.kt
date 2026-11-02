package id.homebase.chat.widget

import androidx.compose.runtime.compositionLocalOf
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import id.homebase.api.util.ChatMarkdownFlavour
import id.homebase.api.util.Mention
import id.homebase.api.util.findMentions
import id.homebase.api.util.markdownTreeHasBlockElements
import id.homebase.api.util.normalizeChatNewlines
import id.homebase.api.util.normalizeUrlScheme
import id.homebase.api.util.withParagraphSoftBreaksPromoted
import id.homebase.core.util.URL_REGEX
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.parser.MarkdownParser

/** A bare URL in [ParsedChatMarkdown.text]; [url] has a lowercase scheme and always one. */
class UrlRange(val range: IntRange, val url: String)

/** [text] is the content with soft breaks promoted to hard ones; [ast] and [mentionRanges] index into it. */
class ParsedChatMarkdown(
    val text: String,
    val ast: ASTNode?,
    val hasBlockElements: Boolean,
    val mentionRanges: List<Mention>,
    val urlRanges: List<UrlRange> = emptyList(),
) {
    val inlineNode: ASTNode? by lazy(LazyThreadSafetyMode.NONE) {
        ast?.children?.firstOrNull {
            it.type == MarkdownTokenTypes.TEXT || it.type == MarkdownElementTypes.PARAGRAPH
        }
    }

    val blockState: State.Success? by lazy(LazyThreadSafetyMode.NONE) {
        val root = ast ?: return@lazy null
        val handler = ReferenceLinkHandlerImpl().also { handler ->
            val links = LinkedHashMap<String, String>()
            collectLinkDefinitions(links, root, text)
            links.forEach { (k, v) -> handler.store(k, v) }
        }
        State.Success(root, text, true, handler)
    }
}

// Mirrors mikepenz 0.40.2 lookupLinkDefinition(recursive = true, onlyDefinitions = false), which is internal.
private fun collectLinkDefinitions(out: MutableMap<String, String>, node: ASTNode, text: String) {
    var isAutoLink = false
    val label = when (node.type) {
        MarkdownElementTypes.LINK_DEFINITION ->
            node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(text)
        MarkdownElementTypes.INLINE_LINK ->
            node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getUnescapedTextInNode(text)
        MarkdownElementTypes.AUTOLINK -> {
            isAutoLink = true
            (node.children.firstOrNull { it.type.name == MarkdownElementTypes.AUTOLINK.name } ?: node)
                .getUnescapedTextInNode(text)
        }
        else -> null
    }
    if (label != null) {
        val destination = if (isAutoLink) {
            label
        } else {
            node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(text)
        }
        if (destination != null) out[label] = destination
    }
    for (child in node.children) collectLinkDefinitions(out, child, text)
}

internal fun parseChatMarkdown(content: String): ParsedChatMarkdown {
    val normalized = content.normalizeChatNewlines()
    val parser = MarkdownParser(ChatMarkdownFlavour)
    val first = try {
        parser.buildMarkdownTreeFromString(normalized)
    } catch (_: Throwable) {
        return ParsedChatMarkdown(normalized, null, false, emptyList())
    }
    val hasBlock = markdownTreeHasBlockElements(first)
    val promoted = normalized.withParagraphSoftBreaksPromoted(first)
    val tree = if (promoted == normalized) first else parser.buildMarkdownTreeFromString(promoted)
    return ParsedChatMarkdown(promoted, tree, hasBlock, findMentions(promoted), findUrlRanges(promoted, tree))
}

private val urlExcludedTypeNames = setOf(
    "CODE_SPAN", "CODE_FENCE", "CODE_BLOCK", "INLINE_LINK", "FULL_REFERENCE_LINK",
    "SHORT_REFERENCE_LINK", "AUTOLINK", "GFM_AUTOLINK", "LINK_DEFINITION", "IMAGE", "HTML_BLOCK",
)

private fun collectUrlExclusions(text: String, node: ASTNode, out: MutableList<IntRange>) {
    if (node.type.name in urlExcludedTypeNames) {
        out.add(node.startOffset until node.endOffset)
        return
    }
    if (node.children.isEmpty() && text.indexOf('\\', node.startOffset).let { it >= 0 && it < node.endOffset }) {
        out.add(node.startOffset until node.endOffset)
        return
    }
    for (child in node.children) collectUrlExclusions(text, child, out)
}

private fun String.trimUrlEnd(): String {
    var end = length
    while (end > 0) {
        val c = this[end - 1]
        val unbalancedParen = c == ')' && count { it == ')' } > count { it == '(' }
        if (c in ".,:?&=%~_" || unbalancedParen) end-- else break
    }
    return substring(0, end)
}

internal fun findUrlRanges(text: String, ast: ASTNode): List<UrlRange> {
    if (!text.contains("://", ignoreCase = true) && !text.contains("www.", ignoreCase = true)) return emptyList()
    val excluded = ArrayList<IntRange>()
    collectUrlExclusions(text, ast, excluded)
    val out = ArrayList<UrlRange>()
    for (m in URL_REGEX.findAll(text)) {
        val start = m.range.first
        if (start > 0 && text[start - 1].isLetterOrDigit()) continue
        val value = m.value.trimUrlEnd()
        if (value.isEmpty()) continue
        val range = start until start + value.length
        if (excluded.any { it.first <= range.last && range.first <= it.last }) continue
        out.add(UrlRange(range, normalizeUrlScheme(value)))
    }
    return out
}

/** Single-threaded by contract: touched from composition only. */
class ChatMarkdownParseCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    internal val parse: (String) -> ParsedChatMarkdown = ::parseChatMarkdown,
) {
    init {
        require(maxEntries > 0)
    }

    private val entries = LinkedHashMap<String, ParsedChatMarkdown>()

    internal val size: Int get() = entries.size

    fun get(content: String): ParsedChatMarkdown {
        entries.remove(content)?.let {
            entries[content] = it
            return it
        }
        val parsed = parse(content)
        entries[content] = parsed
        if (entries.size > maxEntries) entries.remove(entries.keys.first())
        return parsed
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

val LocalChatMarkdownParseCache = compositionLocalOf { ChatMarkdownParseCache() }
