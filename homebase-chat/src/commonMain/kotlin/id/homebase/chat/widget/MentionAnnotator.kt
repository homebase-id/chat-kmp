package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import id.homebase.api.util.findMentions
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes

/** One step stronger than the inline-code chip, which the bold weight would otherwise swallow. */
private const val MENTION_BG_ALPHA = 0.18f

/** Same tint, denser: the fallback a mention of YOU gets where an accent hue cannot be used. */
private const val SELF_MENTION_BG_ALPHA = 0.35f

/** [names] is keyed by LOWERCASED odinId; see [LocalMentionNames]. */
@Immutable
data class MentionContext(
    val selfOdinId: String? = null,
    val names: ImmutableMap<String, String> = persistentMapOf(),
    val sentBubble: Boolean = false,
)

private fun chipStyle(style: TextStyle, textColor: Color, background: Color): SpanStyle =
    style.copy(color = textColor, fontWeight = FontWeight.Bold)
        .toSpanStyle()
        .copy(background = background)

/**
 * Derived from [color] rather than a fixed `MaterialTheme.colorScheme` role for the reason the rest
 * of [ChatMarkdown] is — a sent bubble paints `primary` and a received one `surface`, so any fixed
 * role legible on one is washed out on the other.
 */
internal fun mentionSpanStyle(style: TextStyle, color: Color): SpanStyle =
    chipStyle(style, color, color.copy(alpha = MENTION_BG_ALPHA))

/** A sent bubble paints `primary`, where the fixed tertiary accent washes out. */
internal fun selfMentionSpanStyle(
    style: TextStyle,
    color: Color,
    selfBackground: Color,
    selfContent: Color,
    sentBubble: Boolean,
): SpanStyle = if (sentBubble) {
    chipStyle(style, color, color.copy(alpha = SELF_MENTION_BG_ALPHA))
} else {
    chipStyle(style, selfContent, selfBackground)
}

/** The library's own no-op annotator: default handling for every node. */
private val NoMentionAnnotator: MarkdownAnnotator = markdownAnnotator()

/**
 * Parents whose `TEXT` children are not prose. `@alice.example.test` inside a code span is a
 * literal the author asked us to show verbatim, and inside a link label it is already spoken for by
 * the link annotation.
 *
 * Fenced and indented code need no entry here: mikepenz lifts those through `MarkdownCodeFence` /
 * `MarkdownCodeBlock` and never runs the annotator at all.
 */
private val nonProseParents: Set<IElementType> = setOf(
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.LINK_TEXT,
    MarkdownElementTypes.LINK_LABEL,
    MarkdownElementTypes.LINK_DESTINATION,
    MarkdownElementTypes.LINK_TITLE,
)

/** A mention with its chip and its final drawn text already decided — see [resolveMentions]. */
private class ResolvedMention(val range: IntRange, val style: SpanStyle, val text: String)

/**
 * mikepenz calls an annotator for every AST child on the way to building an `AnnotatedString`, and
 * both of [ChatMarkdown]'s markdown shapes route through that: the inline path passes this to
 * `annotatorSettings`, and the block path hands it to `Markdown()`, which publishes it for
 * paragraphs, headings, list items, quotes and table cells alike.
 *
 * [parsedContent] must be the exact string handed to the parser (i.e. post
 * [id.homebase.api.util.withChatHardLineBreaks], which shifts offsets) — the ranges are offsets
 * into it, and the annotator slices the node's own span out of it.
 *
 * The scan, the self-test and the name lookup all happen once per remembered key, never per AST
 * node and never per recomposition: the annotator lambda runs for every paragraph, list item and
 * table cell, and mikepenz's `MarkdownParagraph` rebuilds its `AnnotatedString` with no `remember`
 * of its own. Keyed on the two self colours rather than on the built `SpanStyle`s, whose structural
 * equality never short-circuits.
 */
@Composable
internal fun rememberMentionAnnotator(
    parsedContent: String,
    style: TextStyle,
    color: Color,
    context: MentionContext?,
): MarkdownAnnotator {
    val selfBackground = MaterialTheme.colorScheme.tertiaryContainer
    val selfContent = MaterialTheme.colorScheme.onTertiaryContainer
    return remember(parsedContent, style, color, selfBackground, selfContent, context) {
        val resolved = resolveMentions(
            content = parsedContent,
            style = style,
            color = color,
            selfBackground = selfBackground,
            selfContent = selfContent,
            context = context,
        )
        if (resolved.isEmpty()) NoMentionAnnotator else mentionAnnotator(resolved)
    }
}

private fun resolveMentions(
    content: String,
    style: TextStyle,
    color: Color,
    selfBackground: Color,
    selfContent: Color,
    context: MentionContext?,
): List<ResolvedMention> {
    val mentions = findMentions(content)
    if (mentions.isEmpty()) return emptyList()

    val mentionStyle = mentionSpanStyle(style, color)
    val selfStyle = selfMentionSpanStyle(
        style = style,
        color = color,
        selfBackground = selfBackground,
        selfContent = selfContent,
        sentBubble = context?.sentBubble == true,
    )
    val selfOdinId = context?.selfOdinId.orEmpty()
    val names = context?.names
    return mentions.map { mention ->
        val from = mention.range.first
        val to = mention.range.last + 1
        val name = names?.get(mention.identity.lowercase())?.takeIf { it.isNotBlank() }
        ResolvedMention(
            range = mention.range,
            style = if (mention.isIdentity(selfOdinId)) selfStyle else mentionStyle,
            text = if (name == null) {
                content.substring(from, to)
            } else {
                "@" + name + content.substring(from + 1 + mention.identity.length, to)
            },
        )
    }
}

private fun mentionAnnotator(mentions: List<ResolvedMention>): MarkdownAnnotator =
    markdownAnnotator { content, child ->
        if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
        if (child.parent?.type in nonProseParents) return@markdownAnnotator false

        val start = child.startOffset
        val end = child.endOffset
        if (start >= end || end > content.length) return@markdownAnnotator false
        // A backslash escape makes the drawn text shorter than its source span, sliding every
        // offset after it. Hand those nodes back to the default path, which unescapes properly.
        val escape = content.indexOf('\\', start)
        if (escape in start until end) return@markdownAnnotator false

        var cursor = start
        for (mention in mentions) {
            val from = maxOf(mention.range.first, start)
            val to = minOf(mention.range.last + 1, end)
            if (from >= to) continue
            if (from > cursor) append(content.substring(cursor, from))
            pushStyle(mention.style)
            // A mention clipped by an inline node boundary is drawn verbatim, never half-swapped.
            val whole = from == mention.range.first && to == mention.range.last + 1
            append(if (whole) mention.text else content.substring(from, to))
            pop()
            cursor = to
        }
        if (cursor == start) return@markdownAnnotator false

        if (cursor < end) append(content.substring(cursor, end))
        true
    }
