package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.widget.ComposerAutocomplete
import id.homebase.core.widget.ComposerAutocompleteController

private const val MentionTriggerId = "mention"

private const val MaxMentionSuggestions = 5

/**
 * `@mention` typeahead over a group's members. A mention is plain text — `@<odinId> ` — because
 * that is what the web client sends and linkifies; nothing rides the message header.
 *
 * Ships on every platform, unlike the `:` emoji sibling — no soft keyboard can tell you who is in
 * this group. Empty [targets] leaves the trigger unregistered, and that is the 1:1 gate.
 */
@Composable
fun MentionAutocomplete(
    state: RichTextState,
    controller: ComposerAutocompleteController,
    targets: List<ContactUiModel>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerAutocomplete(
        state = state,
        controller = controller,
        triggerId = MentionTriggerId,
        triggerChar = '@',
        suggestionsFor = { query -> mentionSuggestions(query, targets) },
        replacementFor = { "@${it.odinId.domainName} " },
        modifier = modifier,
        enabled = enabled && targets.isNotEmpty(),
    ) { target, selected ->
        MentionSuggestionRow(target = target, selected = selected)
    }
}

/** [query] is the text after `@`. A fully typed handle is dropped so Enter sends instead of
 *  re-committing what is already there; a fully typed display name is not, because `@Sebastian`
 *  is not the wire form and Enter there would send an unresolvable mention. */
internal fun mentionSuggestions(
    query: String,
    targets: List<ContactUiModel>,
): List<ContactUiModel> {
    if (targets.isEmpty()) return emptyList()

    if (query.isEmpty()) {
        return targets.sortedBy { it.name.lowercase() }.take(MaxMentionSuggestions)
    }

    return targets
        .mapNotNull { target -> matchRank(query, target)?.let { target to it } }
        .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
        .map { it.first }
        .take(MaxMentionSuggestions)
}

private fun matchRank(query: String, target: ContactUiModel): Int? {
    val name = target.name
    val handle = target.odinId.domainName
    if (query.equals(handle, ignoreCase = true)) return null
    return when {
        name.startsWith(query, ignoreCase = true) -> 0
        handle.startsWith(query, ignoreCase = true) -> 1
        name.contains(query, ignoreCase = true) -> 2
        handle.contains(query, ignoreCase = true) -> 3
        else -> null
    }
}

/** Hoisted so the per-keystroke re-render of the list does not reallocate it per row. */
private val MentionAvatarOptions = AvatarOptions(size = 28.dp, fontSize = 12.sp)

@Composable
private fun MentionSuggestionRow(target: ContactUiModel, selected: Boolean) {
    val handle = target.odinId.domainName
    Row(
        // Tapped on mobile, not just arrowed through: 48dp is the M3 minimum touch target.
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContactAvatar(
            odinId = target.odinId,
            profileImageData = null,
            initials = target.avatarInitials,
            options = MentionAvatarOptions,
        )
        Text(
            text = target.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!target.name.equals(handle, ignoreCase = true)) {
            Text(
                text = handle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Weighted, or a long handle is measured first at full width and clips the name
                // that the user is actually reading down to "Wil…".
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
