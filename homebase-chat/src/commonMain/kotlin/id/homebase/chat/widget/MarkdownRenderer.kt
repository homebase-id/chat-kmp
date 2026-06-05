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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
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
 * ### Layout integration
 * [onTextLayout] is invoked with the [TextLayoutResult] of the body's last
 * rendered paragraph, which is what the bubble's custom Layout needs to tuck the
 * timestamp onto the final line. [maxLines] / [overflow] are honoured on the
 * highlighted single-Text path and exposed so the read-more (Task F) cap merges
 * cleanly; the block renderer cannot apply one global line cap across
 * heterogeneous blocks, so the two 1-line preview sites (conversation list,
 * search row) strip to plain via [markdownToPlainPreview] and use a plain [Text]
 * rather than this block renderer.
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

    // Default path: full mikepenz block renderer, M3-styled.
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
        // Custom paragraph component so we can surface the body's last text
        // layout to the caller (bubble timestamp-fit math). Mirrors mikepenz's
        // own MarkdownParagraph but uses the MarkdownText overload that reports
        // onTextLayout.
        components = markdownComponents(
            paragraph = { model ->
                val settings = annotatorSettings()
                val styled = buildAnnotatedString {
                    pushStyle(typography.paragraph.toSpanStyle())
                    buildMarkdownAnnotatedString(
                        content = model.content,
                        node = model.node,
                        annotatorSettings = settings,
                    )
                    pop()
                }
                MarkdownText(
                    content = styled,
                    node = model.node,
                    style = typography.paragraph,
                    onTextLayout = { result, _ -> onTextLayout(result) },
                    sourceContent = model.content,
                )
            },
        ),
    )
}
