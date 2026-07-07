package id.homebase.chat.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import id.homebase.api.util.markdownHasBlockElements
import id.homebase.api.util.markdownToPlainPreview
import id.homebase.api.util.withChatHardLineBreaks

/**
 * THE single read-only markdown renderer for chat.
 *
 * Wraps mikepenz [Markdown] with M3-only styling. Every read-only
 * block-display site (message bubble body, full-screen caption) renders
 * through here, so there is exactly one place styling and parser behaviour
 * live.
 *
 * ### Bubble-aware decorations
 * A chat bubble is NOT the surface — a SENT bubble paints `primary` (blue) and
 * passes a light [color] (white) as the body content colour, while a RECEIVED
 * bubble paints `surface` and passes a dark [color]. Every markdown decoration
 * is therefore derived from [color] (the bubble's content colour), not from
 * fixed surface roles like `surfaceVariant` / `outlineVariant`: those read fine
 * on a received bubble but produce a light-grey, low-contrast chip on the blue
 * sent bubble. The derived tints — inline-code/fenced-code backgrounds, divider,
 * blockquote bar, code text, links — all stay readable on BOTH bubbles because
 * they're alpha-blended onto, or equal to, the bubble's own content colour.
 *
 * Links in particular do NOT use a fixed `MaterialTheme.colorScheme.primary`:
 * that hue vanishes against the primary-coloured sent bubble. Instead a link is
 * the bubble [color] itself, bold + underlined — guaranteed-contrast on both
 * bubbles (it equals the content colour the rest of the body uses), with the
 * underline carrying the affordance that the hue no longer can.
 *
 * Parser robustness: mikepenz renders through `org.jetbrains:markdown`
 * (CommonMark), which is why the old richeditor `setMarkdown` try/catch +
 * `fixProblematicMarkdownText` workaround is retired — the documented
 * "space after a blank line" crash does not occur with this parser.
 *
 * ### Search highlight
 * When [searchQuery] is non-empty and matches the body, we render a single
 * highlighted plain-text [Text] built from the markdown's rendered plain form
 * (via [markdownToPlainPreview]). The highlight offsets are therefore computed
 * against *exactly* the string being drawn, which structurally removes the
 * stale-offset `String.subSequence` crash the old separate fork hit when
 * richeditor reassembled its annotated string to a different length. On a search
 * surface the match is what matters, so dropping inline emphasis there is the
 * correct trade-off, and the two styling worlds now share one [AnnotatedString].
 *
 * ### Layout integration — inline vs block
 * The bubble's custom Layout (in `MessageBubbleRaw`) tucks the timestamp onto the
 * last text line by reading the body's [TextLayoutResult] *during its own measure
 * pass* and force-remeasuring siblings to converge the bubble width. That coupling
 * is only safe for a SINGLE, STABLE text node. The mikepenz block `Markdown()`
 * Column is a multi-node, interactive (link/hover) subtree that re-fires layout on
 * Desktop hover — re-measuring it from inside the parent's layout pass throws
 * `IllegalStateException: layout state is not idle before measure starts`.
 *
 * So this renderer has three shapes:
 *
 *  1. **Search active** → one highlighted plain [Text] (single stable node).
 *  2. **Inline-only body** (no block elements: bold/italic/strike/inline-code/
 *     links only) → one styled [Text] built from mikepenz
 *     [buildMarkdownAnnotatedString] + [annotatorSettings]. Links are native
 *     Compose [androidx.compose.ui.text.LinkAnnotation]s, handled by the Text
 *     itself — no custom `pointerInput` reading a layout result, so no
 *     reentrancy. This path reports [onTextLayout] and is the one the bubble's
 *     custom Layout wraps for the last-line timestamp tuck.
 *  3. **Block body** (heading/list/quote/code/table/rule/html) → the full
 *     mikepenz block [Markdown] Column. It deliberately does NOT report
 *     [onTextLayout]; the caller must place it OUTSIDE the timestamp-tucking
 *     Layout (`MessageBubbleRaw` routes block bodies to a plain Column with the
 *     timestamp below). The block path cannot apply one global line cap across
 *     heterogeneous blocks, so the two 1-line preview sites (conversation list,
 *     search row) strip to plain via [markdownToPlainPreview] rather than using
 *     this renderer.
 *
 * Use [markdownHasBlockElements] to decide between (2) and (3) — the SAME helper
 * the caller uses to choose the layout container — so the rendering shape and the
 * container never disagree.
 *
 * [maxLines] / [overflow] are honoured on the single-Text paths (1 and 2) and
 * exposed so the read-more (Task F) cap merges cleanly.
 */
@Composable
fun ChatMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    searchQuery: String? = null,
    isCurrentSearchResult: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    // Search-active path: render the rendered-plain form with highlight folded
    // in. One AnnotatedString, drawn as-is — offsets can never go stale.
    val query = searchQuery?.takeIf { it.isNotEmpty() }
    if (query != null) {
        val plain = markdownToPlainPreview(content, maxCodePoints = Int.MAX_VALUE)
        val highlightColor =
            if (isCurrentSearchResult)
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
            else
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
        val highlighted = buildSearchHighlightedText(
            plain = plain,
            searchQuery = query,
            highlightColor = highlightColor,
        )
        Text(
            text = highlighted ?: AnnotatedString(plain),
            modifier = modifier,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = onTextLayout,
        )
        return
    }

    // Inline-only path: no block elements, so the whole body is one paragraph of
    // inline spans. Render it as a SINGLE [Text] over the annotated string mikepenz
    // would have produced for that paragraph — bold/italic/strike/inline-code/links
    // included. Links ride as native Compose LinkAnnotations (emitted by
    // buildMarkdownAnnotatedString via annotatorSettings' linkInteractionListener,
    // which opens the URL through LocalUriHandler), so a plain Text handles taps
    // without any pointerInput reading the layout result. This single stable node
    // is what keeps the bubble's last-line timestamp tuck reentrancy-free.
    // Memoize the inline-vs-block decision: markdownHasBlockElements runs a full
    // MarkdownParser tree build, a pure function of `content`. Recomputing it on every
    // recomposition (e.g. each scroll-in) is wasted work AND lets the chosen render path
    // momentarily differ across frames, which shows up as a height bounce as a rich-text
    // bubble re-enters view. Keying on `content` makes the path stable.
    val isBlock = remember(content) { markdownHasBlockElements(content) }
    if (!isBlock) {
        // Single stable Text over the inline annotated string (bold/italic/strike/
        // inline-code + native Compose link annotations, with chat soft breaks
        // promoted to hard breaks). See [buildChatInlineAnnotatedString] for the full
        // rationale — why the link TextLinkStyles carries a single style (Desktop-hover
        // reentrancy safety) and why annotatorSettings is built explicitly outside
        // Markdown(). Keeping this as one stable node is what makes the bubble's
        // last-line timestamp tuck reentrancy-free.
        val annotated = buildChatInlineAnnotatedString(content, style, color)
        Text(
            text = annotated,
            modifier = modifier,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = onTextLayout,
        )
        return
    }

    // Block path: full mikepenz block renderer, M3-styled. Deliberately does NOT
    // report onTextLayout — the caller must keep this multi-node Column out of the
    // bubble's timestamp-tucking custom Layout.
    //
    // All decorations derive from the bubble [color] (see kdoc), not surface roles:
    //  - inlineCodeBackground: faint [color] tint behind inline-code chips.
    //  - codeBackground: faint [color] tint behind fenced-code containers (the
    //    custom codeFence/codeBlock components below add a [color] border on top).
    //  - dividerColor: a stronger [color] tint for `---` rules.
    val colors = markdownColor(
        text = color,
        codeBackground = color.copy(alpha = FENCED_CODE_BG_ALPHA),
        inlineCodeBackground = color.copy(alpha = INLINE_CODE_BG_ALPHA),
        dividerColor = color.copy(alpha = DIVIDER_ALPHA),
        // Table grid lines already use [dividerColor] (bubble-aware); the cell fill
        // defaults to a surface role that turns into a grey box on the primary-coloured
        // sent bubble. Tint it off the bubble [color] like the code surfaces so the
        // table reads on BOTH bubbles.
        tableBackground = color.copy(alpha = FENCED_CODE_BG_ALPHA),
    )
    val typography = markdownTypography(
        // Heading ladder. The M3 title/headline scale is too compressed to give a
        // legible six-level hierarchy inside a chat bubble (titleLarge 22 → titleMedium
        // 16 is a cliff, headlineSmall 24 → titleLarge 22 barely steps), so each level
        // is a weighted, bubble-coloured step scaled off the body [style] instead. h1–h3
        // bold for a clear top hierarchy; h4–h6 semibold. Sizes scale with the base body
        // size so they track the chat type scale rather than being absolute.
        h1 = style.bubbleHeading(color, scale = 1.5f, weight = FontWeight.Bold),
        h2 = style.bubbleHeading(color, scale = 1.3f, weight = FontWeight.Bold),
        h3 = style.bubbleHeading(color, scale = 1.15f, weight = FontWeight.Bold),
        h4 = style.bubbleHeading(color, scale = 1.05f, weight = FontWeight.SemiBold),
        h5 = style.bubbleHeading(color, scale = 1.0f, weight = FontWeight.SemiBold),
        h6 = style.bubbleHeading(color, scale = 0.95f, weight = FontWeight.SemiBold),
        text = style,
        paragraph = style,
        ordered = style,
        bullet = style,
        list = style,
        // code/inlineCode carry an explicit `color = color` so the monospace glyphs
        // are the bubble content colour (not the surface onBackground default the
        // mikepenz typography would otherwise fall back to on the chip tint).
        code = style.copy(color = color, fontFamily = FontFamily.Monospace),
        inlineCode = style.copy(color = color, fontFamily = FontFamily.Monospace),
        // quote `color = color` drives BOTH the blockquote text and the accent bar:
        // MarkdownBlockQuote draws the bar with style.color when specified.
        quote = style.copy(color = color, fontStyle = FontStyle.Italic),
        // Links: bubble [color], bold + underlined — readable on sent AND received
        // bubbles (it equals the content colour), matching the inline path.
        textLink = TextLinkStyles(
            style = style.copy(
                color = color,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            ).toSpanStyle(),
        ),
    )

    // Parse the markdown SYNCHRONOUSLY (immediate = true) so the block bubble measures
    // at its final height on the first frame. The default async parse renders an empty
    // loading placeholder for ~400ms then jumps to full height, reflowing the message
    // list — the "bubble shifts down" bug (#938). Chat bodies are size-capped (7 KB
    // header) and the parse is remember(content)-memoized, so the synchronous cost is
    // small; the library's "blocks composition" warning targets large documents.
    val markdownState = rememberMarkdownState(
        content = content.withChatHardLineBreaks(),
        immediate = true,
    )
    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        // Default is fillMaxSize(); a chat bubble must wrap its content.
        modifier = modifier.wrapContentSize(),
        dimens = markdownDimens(
            // A slightly thicker quote bar reads as a deliberate accent (Notion-style)
            // rather than a hairline.
            blockQuoteThickness = 4.dp,
            codeBackgroundCornerSize = 10.dp,
            dividerThickness = 1.dp,
        ),
        padding = markdownPadding(
            // Vertical rhythm between blocks — the single biggest lever on the
            // "document" feel. 8.dp gives paragraphs/headings/lists room to breathe
            // without ballooning the bubble.
            block = 8.dp,
            list = 4.dp,
            // Space between consecutive list items so multi-item lists don't read as
            // one dense run.
            listItemBottom = 4.dp,
            // Wider indent so nested bullets/numbers clearly step in (was a cramped 12).
            listIndent = 20.dp,
            // Generous internal padding so fenced code looks like a real code card.
            codeBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            // Offset the quote text from its accent bar.
            blockQuoteText = PaddingValues(start = 12.dp, top = 2.dp, bottom = 2.dp),
        ),
        imageTransformer = Coil3ImageTransformerImpl,
        // Custom code components: same default body, but the container is a [color]
        // tint with a subtle [color] border so the fenced block reads as a distinct
        // surface on both bubbles. Keep the M3 checkbox (the Markdown() default
        // overrides only `checkbox`; supplying our own components map drops that, so
        // re-supply it). No custom paragraph component: the block path intentionally
        // does NOT surface onTextLayout — keeping the block subtree free of the
        // timestamp-tuck callback removes it from the bubble's reentrant custom-Layout
        // coupling on Desktop hover.
        components = markdownComponents(
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            codeFence = {
                MarkdownCodeFence(it.content, it.node, style = it.typography.code) { code, language, codeStyle ->
                    BubbleCodeContainer(code = code, language = language, style = codeStyle, color = color)
                }
            },
            codeBlock = {
                MarkdownCodeBlock(it.content, it.node, style = it.typography.code) { code, language, codeStyle ->
                    BubbleCodeContainer(code = code, language = language, style = codeStyle, color = color)
                }
            },
        ),
    )
}

/**
 * Builds the single-paragraph inline [AnnotatedString] the inline render path draws:
 * bold/italic/strike/inline-code plus native Compose link annotations, with chat soft
 * breaks promoted to hard breaks (so a message typed with single Enters keeps its
 * lines — see [withChatHardLineBreaks]). Extracted and `internal` so the soft-break and
 * autolink behaviour are unit-testable without a bubble. Pixel-equivalent to what the
 * block path renders for the same inline content.
 *
 * mikepenz's String overload parses with the default GFM flavour (matching the block
 * `Markdown()` path), finds the single paragraph/inline run, and applies the base
 * [style]'s SpanStyle — including the [androidx.compose.ui.text.LinkAnnotation]s that
 * let a plain Text handle taps natively (opened via `LocalUriHandler` by the default
 * link interaction listener).
 *
 * annotatorSettings is built explicitly (not the no-arg composable default, which reads
 * LocalMarkdownTypography / LocalMarkdownColors / LocalReferenceLinkHandler — only
 * present INSIDE mikepenz's `Markdown()`), supplying the same M3 link + inline-code
 * styling the block path uses, no reference handler.
 *
 * CRITICAL: the link [TextLinkStyles] carries ONLY `style` — no hovered/focused/pressed
 * variant. Compose's TextLinkScope registers a hover-keyed StyleAnnotation only to swap
 * between those variants; with a single style the re-applied AnnotatedString is
 * structurally identical on hover, so `TextAnnotatedStringNode.updateText`
 * short-circuits and `onTextLayout` does NOT re-fire on Desktop hover — which is what
 * keeps the single Text node stable inside the bubble's timestamp-tucking Layout. A
 * hovered/pressed variant would mutate the text on hover, re-fire onTextLayout
 * mid-measure, and resurrect the reentrancy crash. Link = bubble [color], bold +
 * underlined (readable on sent AND received bubbles); inline code = monospace [color]
 * on a faint [color] tint chip.
 */
@Composable
internal fun buildChatInlineAnnotatedString(
    content: String,
    style: TextStyle,
    color: Color,
): AnnotatedString {
    val linkSpanStyle = TextLinkStyles(
        style = style.copy(
            color = color,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
        ).toSpanStyle(),
    )
    val inlineCodeStyle = style.copy(
        color = color,
        fontFamily = FontFamily.Monospace,
    ).toSpanStyle().copy(background = color.copy(alpha = INLINE_CODE_BG_ALPHA))
    // annotatorSettings is @Composable (it can read CompositionLocals), so it stays in
    // composition; its output here is fully determined by the stable link/code styles.
    val settings = annotatorSettings(
        linkTextSpanStyle = linkSpanStyle,
        codeSpanStyle = inlineCodeStyle,
        referenceLinkHandler = null,
    )
    // The actual parse (buildMarkdownAnnotatedString) is a pure, non-composable string
    // build over `content` + `style` + the bubble `color`-derived styles. Memoize it so
    // a scroll-in recomposition doesn't re-parse the body each frame — pure work whose
    // result is identical for unchanged inputs, and re-parsing it is part of the
    // rich-text scroll-in bounce.
    return remember(content, style, color) {
        content.withChatHardLineBreaks().buildMarkdownAnnotatedString(
            style = style,
            annotatorSettings = settings,
        )
    }
}

/** Faint tint behind an inline-code chip, alpha-blended onto the bubble content colour. */
private const val INLINE_CODE_BG_ALPHA = 0.15f

/** Slightly softer tint behind a fenced-code container (it also carries a border). */
private const val FENCED_CODE_BG_ALPHA = 0.12f

/** Border around a fenced-code container — a touch stronger than its fill. */
private const val FENCED_CODE_BORDER_ALPHA = 0.25f

/** Divider (`---`) tint — stronger than the code fills so the rule stays visible. */
private const val DIVIDER_ALPHA = 0.3f

/**
 * One rung of the bubble heading ladder: the body [this] style re-weighted to [weight]
 * and recoloured to the bubble content [color], with its font size multiplied by [scale]
 * (line height derived at 1.3× for headline-tight leading). Scaled off the body style —
 * not a fixed M3 role — because the M3 type scale has no clean six-level heading ladder
 * that stays chat-bubble-appropriate; scaling keeps the headings tracking the body type
 * size. Falls back to a 16sp base if the body size is unspecified.
 */
private fun TextStyle.bubbleHeading(color: Color, scale: Float, weight: FontWeight): TextStyle {
    val baseSp = if (fontSize.isSpecified) fontSize.value else 16f
    val sizeSp = baseSp * scale
    return copy(
        color = color,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.3f).sp,
    )
}

/**
 * Fenced/code-block container for a chat bubble: a faint [color] tint with a subtle
 * [color] border, both alpha-blended onto the bubble content colour so the block
 * reads as a distinct surface on BOTH a sent (primary) and received (surface) bubble.
 * Mirrors mikepenz's own private `MarkdownCode` body (rounded [MarkdownCodeBackground]
 * + horizontally-scrollable monospace text) but swaps the surface-role fill for the
 * bubble-relative tint and adds the border.
 */
@Composable
private fun BubbleCodeContainer(
    code: String,
    language: String?,
    style: TextStyle,
    color: Color,
) {
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    MarkdownCodeBackground(
        color = color.copy(alpha = FENCED_CODE_BG_ALPHA),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = FENCED_CODE_BORDER_ALPHA)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = AnnotatedString(code),
            style = style,
            color = color,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(codeBlockPadding),
        )
    }
}
