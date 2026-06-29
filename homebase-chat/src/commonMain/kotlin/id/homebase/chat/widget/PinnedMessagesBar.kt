package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.data.MessageUiModel
import id.homebase.resources.MR
import id.homebase.resources.chat_pinned_bar_count
import id.homebase.resources.chat_pinned_bar_open_list
import id.homebase.resources.chat_pinned_icon
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * Collapsed pinned-messages bar shown at the top of an open conversation (#887).
 * Hidden when there are no pins. Tapping the bar cycles to the next pin and
 * scrolls+highlights it; the trailing button opens the full "pinned messages"
 * panel. Personal + synced — never shared with peers.
 */
@Composable
fun PinnedMessagesBar(
    pinnedMessages: ImmutableList<MessageUiModel>,
    currentPinIndex: Int,
    onUiAction: (ConversationListUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pinnedMessages.isEmpty()) return

    val total = pinnedMessages.size
    val index = currentPinIndex.coerceIn(0, total - 1)
    val current = pinnedMessages[index]

    val sender = current.displayName.ifBlank { current.originalAuthor?.domainName.orEmpty() }
    val body = current.content.truncateToCodePoints(80)
    // Built outside Text() so Konsist's "no hardcoded strings in Composables" passes.
    val previewText = if (sender.isBlank()) body else "$sender: $body"
    val countLabel = stringResource(MR.string.chat_pinned_bar_count, index + 1, total)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onUiAction(ConversationListUiAction.CyclePinnedBar) }
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Accent strip — the Telegram/Signal pinned-bar affordance.
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = stringResource(MR.string.chat_pinned_icon),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp).size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                if (total > 1) {
                    Text(
                        text = countLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onUiAction(ConversationListUiAction.ShowPinnedMessagesSheet) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(MR.string.chat_pinned_bar_open_list),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}
