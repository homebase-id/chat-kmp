package id.homebase.core.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.resources.MR
import id.homebase.resources.chat_message_emoji_options
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

@Composable
fun ReactionList(
    modifier: Modifier = Modifier,
    reactionSummary: ReactionSummary,
    onClick: (reaction: String) -> Unit,
    onLongClick: () -> Unit
) {
    LazyRow(
        modifier = modifier
    ) {
        val reactions = reactionSummary.reactions.entries.toList()
        items(reactions) { entry ->
            val emoji = extractEmoji(entry.value.reactionContent)
            if (emoji != null) {
                ReactionIcon(
                    emoji = emoji,
                    count = entry.value.count,
                    onClick = { onClick(emoji) },
                    onLongClick = onLongClick
                )
            }
        }
    }
}

@Serializable
data class ReactionContent(val emoji: String)

private fun extractEmoji(reactionContent: String): String? {
    return try {
        OdinSystemSerializer.deserialize<ReactionContent>(reactionContent).emoji
    } catch (_: Exception) {
        null
    }
}

/**
 * Horizontal reaction menu displaying common emoji reactions.
 *
 * Shows a scrollable row of emoji buttons that can be selected to add a reaction to a message.
 * Automatically dismisses when an emoji is selected.
 *
 * @param onSelect Callback invoked when user selects an emoji reaction
 * @param onShowAllEmojis Callback invoked when emoji full selector should be shown
 */
@Composable
fun ReactionMenu(
    onSelect: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
) {
    val reactions = listOf("👍", "❤️", "😂", "😮", "😢")
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .wrapContentWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            reactions.forEach { emoji ->
                IconButton(
                    onClick = { onSelect(emoji) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text(
                        text = emoji,
                        fontSize = 20.sp
                    )
                }
            }
            IconButton(
                onClick = onShowAllEmojis,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.MoreHoriz,
                    contentDescription = stringResource(MR.string.chat_message_emoji_options)
                )
            }
        }
    }
}


/**
 * Displays a Signal-style circular reaction icon with emoji and optional count.
 *
 * Shows the emoji and count (if > 1) in a rounded pill shape with a subtle border.
 * Supports long-press interaction for managing reactions.
 *
 * @param emoji The emoji character to display
 * @param count The number of reactions (count is only shown if > 1)
 * @param onClick Callback invoked on press for managing the reaction
 */
@Composable
fun ReactionIcon(
    emoji: String,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = emoji,
                fontSize = 14.sp,
                style = MaterialTheme.typography.bodyMedium
            )
            if (count > 1) {
                Text(
                    text = count.toString(),
                    fontSize = 12.sp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}