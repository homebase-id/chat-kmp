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
import id.homebase.api.util.Mention
import id.homebase.api.util.findMentions
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes

/**
 * Tint behind a mention chip, alpha-blended onto the bubble content colour — the same
 * bubble-relative trick the inline-code chip uses, one step stronger so the chip still reads once
 * the bold weight has already darkened the run.
 */
private const val MENTION_BG_ALPHA = 0.18f

/** Same tint, denser: the fallback a mention of YOU gets where an accent hue cannot be used. */
private const val SELF_MENTION_BG_ALPHA = 0.35f

/**
 * Who the reader is, and what the mentions in this body should say — everything the chip needs
 * that the renderer cannot know on its own.
 *
 * Passed explicitly rather than read from a CompositionLocal inside [ChatMarkdown]: the renderer
 * also draws feed posts and feed comments, which must not self-highlight and must not swap an
 * odinId for a name. Those call sites simply do not pass one.
 *
 * [names] is keyed by LOWERCASED odinId; see [rememberMentionNames].
 */
@Immutable
data class MentionContext(
    val selfOdinId: String? = null,
    val names: ImmutableMap<String, String> = persistentMapOf(),
    /** A sent bubble is painted `primary`, where the `tertiary` accent washes out. */
    val sentBubble: Boolean = false,
)

private fun chipStyle(style: TextStyle, textColor: Color, background: Color): SpanStyle =
    style.copy(color = textColor, fontWeight = FontWeight.Bold)
        .toSpanStyle()
        .copy(background = background)

/**
 * How an `@mention` of someone else is painted inside a chat bubble: the bubble's own content
 * [color], bold, on a faint tint of that same colour.
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
    chipStyle(style, color, color.copy(alpha = MENTION_BG_ALPHA))

/**
 * How an `@mention` of the CURRENT user is painted — stronger than [mentionSpanStyle], because
 * spotting "this message is asking me something" while scrolling is the whole point of the feature.
 *
 * On a received bubble it takes a real M3 accent, `onTertiaryContainer` on `tertiaryContainer`:
 * `tertiary` is the role reserved for an attention-drawing highlight that is not competing with
 * `primary` (which the sent bubble owns and chat links deliberately avoid) and does not read as
 * `error`. That is affordable here in a way it is not for the general chip, because a self-mention
 * on a SENT bubble only happens if you mention yourself in your own message — and on that
 * primary-painted bubble any fixed hue washes out, so it falls back to the bubble-relative chip at
 * a denser tint instead.
 */
@Composable
internal fun selfMentionSpanStyle(style: TextStyle, color: Color, sentBubble: Boolean): SpanStyle =
    if (sentBubble) {
        chipStyle(style, color, color.copy(alpha = SELF_MENTION_BG_ALPHA))
    } else {
        chipStyle(
            style,
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
        )
    }

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
 * A [MarkdownAnnotator] that paints [mentionSpanStyle] — or [selfMentionStyle], for a mention of
 * [context]'s own identity — over the mentions in [parsedContent], substituting resolved display
 * names where [context] knows one.
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
 * library's own default annotator back, so the common case adds nothing at all. [context] carries
 * only already-resolved data, so nothing here reaches for a contact.
 */
@Composable
internal fun rememberMentionAnnotator(
    parsedContent: String,
    mentionStyle: SpanStyle,
    selfMentionStyle: SpanStyle = mentionStyle,
    context: MentionContext? = null,
): MarkdownAnnotator = remember(parsedContent, mentionStyle, selfMentionStyle, context) {
    val mentions = findMentions(parsedContent)
    if (mentions.isEmpty()) NoMentionAnnotator
    else mentionAnnotator(mentions, mentionStyle, selfMentionStyle, context)
}

internal fun mentionAnnotator(
    mentions: List<Mention>,
    mentionStyle: SpanStyle,
    selfMentionStyle: SpanStyle = mentionStyle,
    context: MentionContext? = null,
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
    for (mention in mentions) {
        val from = maxOf(mention.range.first, start)
        val to = minOf(mention.range.last + 1, end)
        if (from >= to) continue
        if (from > cursor) append(content.substring(cursor, from))
        val isSelf = context?.selfOdinId
            ?.let { mention.identity.equals(it, ignoreCase = true) } == true
        pushStyle(if (isSelf) selfMentionStyle else mentionStyle)
        append(mention.chipText(content, from, to, context?.names))
        pop()
        cursor = to
    }
    if (cursor == start) return@markdownAnnotator false

    if (cursor < end) append(content.substring(cursor, end))
    true
}

/**
 * What the chip draws: `@<resolved name>` when one is known, the source verbatim otherwise — so a
 * name that never resolves degrades to the raw `@odinId` rather than to a blank.
 *
 * Only the identity is swapped, never the whole range, so a token that reaches past it keeps its
 * tail (`@alice.example.test/inbox` → `@Alice Smith/inbox`). A mention split across inline nodes by
 * emphasis is drawn verbatim rather than half-substituted. Offsets downstream are unaffected: the
 * annotator is CONSTRUCTING the output string, not mapping into it.
 */
private fun Mention.chipText(
    content: String,
    from: Int,
    to: Int,
    names: Map<String, String>?,
): String {
    val verbatim = content.substring(from, to)
    if (names.isNullOrEmpty() || from != range.first || to != range.last + 1) return verbatim
    val name = names[identity.lowercase()]?.takeIf { it.isNotBlank() } ?: return verbatim
    return "@" + name + content.substring(range.first + 1 + identity.length, to)
}
