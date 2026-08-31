package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import id.homebase.api.util.findMentionRanges
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes

/**
 * Tint behind a mention chip, alpha-blended onto the bubble content colour — the same
 * bubble-relative trick the inline-code chip uses, one step stronger so the chip still reads once
 * the bold weight has already darkened the run.
 */
private const val MENTION_BG_ALPHA = 0.18f

/**
 * How an `@mention` is painted inside a chat bubble: the bubble's own content [color], bold, on a
 * faint tint of that same colour.
 *
 * Derived from [color] rather than a fixed `MaterialTheme.colorScheme` role for the reason the rest
 * of [ChatMarkdown] is — a sent bubble paints `primary` and a received one `surface`, so any fixed
 * role legible on one is washed out on the other. [color] is itself the M3 role the bubble chose
 * (`onPrimary` / `onSurface`), so the chip is guaranteed-contrast on both.
 *
 * Deliberately NOT the link style (bold + underline, no fill): a mention and a URL are different
 * things and a reader should be able to tell them apart at a glance. Deliberately not monospace
 * either, which is what separates it from the inline-code chip.
 */
internal fun mentionSpanStyle(style: TextStyle, color: Color): SpanStyle =
    style.copy(color = color, fontWeight = FontWeight.Bold)
        .toSpanStyle()
        .copy(background = color.copy(alpha = MENTION_BG_ALPHA))

/** The library's own no-op annotator: default handling for every node. */
private val NoMentionAnnotator: MarkdownAnnotator = markdownAnnotator()

/**
 * Parents whose `TEXT` children are not prose. `@alice.example.test` inside a code span is a
 * literal the author asked us to show verbatim, and inside a link label it is already spoken for by
 * the link annotation — decorating either would be wrong.
 *
 * Fenced and indented code need no entry here: mikepenz renders those through `MarkdownCodeFence` /
 * `MarkdownCodeBlock`, which lift the raw text and never run the annotator at all.
 */
private val nonProseParents: Set<IElementType> = setOf(
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.LINK_TEXT,
    MarkdownElementTypes.LINK_LABEL,
    MarkdownElementTypes.LINK_DESTINATION,
    MarkdownElementTypes.LINK_TITLE,
)

/**
 * A [MarkdownAnnotator] that paints [mentionSpanStyle] over the mentions in [parsedContent].
 *
 * mikepenz calls an annotator for every AST child on the way to building an `AnnotatedString`, and
 * both of [ChatMarkdown]'s markdown shapes route through that: the inline path passes this to
 * `annotatorSettings`, and the block path hands it to `Markdown()`, which publishes it for
 * paragraphs, headings, list items, quotes and table cells alike. One hook, both shapes, no custom
 * component and no second parse.
 *
 * [parsedContent] must be the exact string handed to the parser (i.e. post
 * [id.homebase.api.util.withChatHardLineBreaks], which shifts offsets) — the ranges are offsets
 * into it, and the annotator slices the node's own span out of it.
 *
 * Scanning is `remember`ed on the content: it is a pure, linear pass, but so is
 * `markdownHasBlockElements`, and the same rule applies — a body re-scanned on every recomposition
 * is work done once per scrolled frame instead of once per message. A body with no mention gets the
 * library's own default annotator back, so the common case adds nothing at all.
 */
@Composable
internal fun rememberMentionAnnotator(
    parsedContent: String,
    mentionStyle: SpanStyle,
): MarkdownAnnotator = remember(parsedContent, mentionStyle) {
    val ranges = findMentionRanges(parsedContent)
    if (ranges.isEmpty()) NoMentionAnnotator else mentionAnnotator(ranges, mentionStyle)
}

internal fun mentionAnnotator(
    ranges: List<IntRange>,
    mentionStyle: SpanStyle,
): MarkdownAnnotator = markdownAnnotator { content, child ->
    if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
    if (child.parent?.type in nonProseParents) return@markdownAnnotator false

    val start = child.startOffset
    val end = child.endOffset
    if (start >= end || end > content.length) return@markdownAnnotator false
    // A backslash escape makes the drawn text shorter than its source span, sliding every offset
    // after it. Hand those nodes back to the default path, which unescapes them properly.
    val escape = content.indexOf('\\', start)
    if (escape in start until end) return@markdownAnnotator false

    var cursor = start
    for (range in ranges) {
        val from = maxOf(range.first, start)
        val to = minOf(range.last + 1, end)
        if (from >= to) continue
        if (from > cursor) append(content.substring(cursor, from))
        pushStyle(mentionStyle)
        append(content.substring(from, to))
        pop()
        cursor = to
    }
    if (cursor == start) return@markdownAnnotator false

    if (cursor < end) append(content.substring(cursor, end))
    true
}
