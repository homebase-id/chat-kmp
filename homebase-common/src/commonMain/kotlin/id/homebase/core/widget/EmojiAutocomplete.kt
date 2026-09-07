package id.homebase.core.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.core.emoji.EmojiShortcodes
import id.homebase.core.emoji.EmojiSuggestion
import id.homebase.core.emoji.emojiSuggestions
import id.homebase.core.ui.theme.withEmojiFont
import id.homebase.core.util.isDesktopOrWeb

private const val EmojiTriggerId = "emoji-shortcode"

/**
 * `:shortcode` typeahead for a composer. Desktop/web only — mobile has a system emoji keyboard one
 * tap away and the app ships [EmojiSelection] on every platform.
 *
 * Placement and key routing follow [ComposerAutocomplete]: a `Box` around the editor alone, and the
 * editor's `onPreviewKeyEvent` running through the controller first.
 */
@Composable
fun EmojiAutocomplete(
    state: RichTextState,
    controller: ComposerAutocompleteController,
    modifier: Modifier = Modifier,
    enabled: Boolean = isDesktopOrWeb(),
) {
    ComposerAutocomplete(
        state = state,
        controller = controller,
        triggerId = EmojiTriggerId,
        triggerChar = ':',
        suggestionsFor = { query -> emojiSuggestions(query, EmojiShortcodes.index()) },
        replacementFor = { it.emoji },
        modifier = modifier,
        enabled = enabled,
    ) { suggestion, selected ->
        EmojiSuggestionRow(suggestion = suggestion, selected = selected)
    }
}

@Composable
private fun EmojiSuggestionRow(suggestion: EmojiSuggestion, selected: Boolean) {
    val shortcode = ":${suggestion.shortcode}:"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = suggestion.emoji.withEmojiFont(),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = shortcode,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
