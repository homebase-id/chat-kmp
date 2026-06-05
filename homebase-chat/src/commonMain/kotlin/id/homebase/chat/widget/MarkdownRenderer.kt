package id.homebase.chat.widget

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import id.homebase.api.util.markdownHasBlockElements
import id.homebase.api.util.markdownToPlainPreview

/**
 * THE single read-only markdown renderer for chat.
 *
 * Wraps mikepenz [Markdown] with M3-only styling: inline code as a monospace
 * chip on `surfaceVariant`, fenced code in a styled container, blockquotes with
 * an accent bar, headings on the M3 typography scale, and list spacing. Every
 * read-only block-display site (message bubble body, full-screen caption)
 * renders through here, so there is exactly one place styling and parser
 * behaviour live.
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
    if (!markdownHasBlockElements(content)) {
        // mikepenz's String overload parses (default GFM flavour, matching the
        // block Markdown() below), finds the single paragraph/inline run, and
        // applies the base style's SpanStyle — producing exactly the inline
        // styling the block path's paragraph would, including the LinkAnnotations
        // that make a plain Text handle taps natively.
        //
        // We build annotatorSettings explicitly rather than via the no-arg
        // annotatorSettings() default: that default reads LocalMarkdownTypography /
        // LocalMarkdownColors / LocalReferenceLinkHandler, which mikepenz only
        // provides INSIDE its Markdown() composable and which `error(...)` when
        // absent. Here we are outside Markdown(), so we supply the same M3 link
        // and inline-code styling the block path uses, no reference handler, and
        // let the default linkInteractionListener open URLs via LocalUriHandler.
        //
        // CRITICAL: pass ONLY `style` to TextLinkStyles — no hovered/focused/
        // pressed variant. Compose's TextLinkScope registers a hover-keyed
        // StyleAnnotation only to swap between those variants; with a single style
        // the re-applied AnnotatedString is structurally identical on hover, so
        // TextAnnotatedStringNode.updateText short-circuits (textChanged=false) and
        // `onTextLayout` does NOT re-fire on Desktop hover. That is what keeps this
        // single Text node stable inside the bubble's timestamp-tucking Layout
        // (same cursor-only hover behaviour the old richeditor renderer had). A
        // hovered/pressed variant here would mutate the text on hover, re-fire
        // onTextLayout mid-measure, and resurrect the reentrancy crash.
        val linkSpanStyle = TextLinkStyles(
            style = style.copy(
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            ).toSpanStyle(),
        )
        val inlineCodeStyle = style.copy(fontFamily = FontFamily.Monospace).toSpanStyle()
            .copy(background = MaterialTheme.colorScheme.surfaceVariant)
        val annotated = content.buildMarkdownAnnotatedString(
            style = style,
            annotatorSettings = annotatorSettings(
                linkTextSpanStyle = linkSpanStyle,
                codeSpanStyle = inlineCodeStyle,
                referenceLinkHandler = null,
            ),
        )
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
    val colors = markdownColor(
        text = color,
        // Inline code chip + fenced code container background.
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val typography = markdownTypography(
        // Headings: scaled down from mikepenz's display* defaults so they fit a
        // chat bubble while staying on the M3 typography scale.
        h1 = MaterialTheme.typography.headlineSmall,
        h2 = MaterialTheme.typography.titleLarge,
        h3 = MaterialTheme.typography.titleMedium,
        h4 = MaterialTheme.typography.titleSmall,
        h5 = MaterialTheme.typography.labelLarge,
        h6 = MaterialTheme.typography.labelMedium,
        text = style,
        paragraph = style,
        ordered = style,
        bullet = style,
        list = style,
        code = style.copy(fontFamily = FontFamily.Monospace),
        inlineCode = style.copy(fontFamily = FontFamily.Monospace),
        quote = style.copy(fontStyle = FontStyle.Italic),
    )

    Markdown(
        content = content,
        colors = colors,
        typography = typography,
        // Default is fillMaxSize(); a chat bubble must wrap its content.
        modifier = modifier.wrapContentSize(),
        dimens = markdownDimens(
            blockQuoteThickness = 3.dp,
            codeBackgroundCornerSize = 8.dp,
            dividerThickness = 1.dp,
        ),
        padding = markdownPadding(
            block = 4.dp,
            list = 4.dp,
            listIndent = 12.dp,
        ),
        imageTransformer = Coil3ImageTransformerImpl,
        // No custom paragraph component: the block path intentionally does NOT
        // surface onTextLayout. mikepenz's default MarkdownParagraph applies the
        // same `typography.paragraph` styling, and keeping the block subtree free
        // of the timestamp-tuck callback is what removes it from the bubble's
        // reentrant custom-Layout coupling on Desktop hover.
    )
}
