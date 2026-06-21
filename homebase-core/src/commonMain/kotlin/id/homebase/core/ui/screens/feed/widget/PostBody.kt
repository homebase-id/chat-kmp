package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.resources.MR
import id.homebase.resources.feed_post_read_less
import id.homebase.resources.feed_post_read_more
import org.jetbrains.compose.resources.stringResource

/** Code-point budget after which a post caption collapses behind a "More" toggle. */
private const val READ_MORE_LIMIT = 400

/**
 * Renders a post [caption] as Signal-style markdown with a "More"/"Less" read-more
 * toggle once the text exceeds [READ_MORE_LIMIT] code points (surrogate-safe).
 *
 * When the full text lives in a `pst_text` overflow payload, the caller passes
 * [onExpandFetchFullText]; it is invoked once on the first expand and its result
 * replaces the rendered text. When it is null, expanding simply reveals the
 * already-supplied [caption] in full.
 */
@Composable
fun PostBody(
    caption: String,
    modifier: Modifier = Modifier,
    onExpandFetchFullText: (suspend () -> String?)? = null,
) {
    var expanded by remember(caption) { mutableStateOf(false) }
    // Holds the full text once fetched from the pst_text overflow payload.
    var fetchedFullText by remember(caption) { mutableStateOf<String?>(null) }

    val overflows = remember(caption) { caption.codePointCount() > READ_MORE_LIMIT }
    val fullText = fetchedFullText ?: caption

    val displayed = remember(fullText, expanded, overflows) {
        if (expanded || !overflows) fullText
        else caption.truncateToCodePoints(READ_MORE_LIMIT)
    }

    // First expand of a pst_text post: pull the full body, then keep it.
    LaunchedEffect(expanded) {
        if (expanded && fetchedFullText == null && onExpandFetchFullText != null) {
            fetchedFullText = onExpandFetchFullText()
        }
    }

    if (caption.isBlank()) return

    Column(modifier = modifier) {
        ChatMarkdown(
            content = displayed,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (overflows) {
            Text(
                text = stringResource(
                    if (expanded) MR.string.feed_post_read_less
                    else MR.string.feed_post_read_more
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}
