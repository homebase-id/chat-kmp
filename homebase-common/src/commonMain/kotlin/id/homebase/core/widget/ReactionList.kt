package id.homebase.core.widget

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.emoji.EmojiNormalization.containsEmoji
import id.homebase.core.emoji.EmojiNormalization.distinctByEmoji
import id.homebase.resources.MR
import id.homebase.resources.chat_message_emoji_options
import id.homebase.resources.chat_message_reaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun ReactionList(
    modifier: Modifier = Modifier,
    reactionSummary: ReactionSummary,
    onReactionClick: () -> Unit,
    onAddEmoji: (() -> Unit)? = null,
    /**
     * Emojis the current user has reacted with on this message (from
     * `MessageUiModel.ownReactions`). Each emoji slice rendered in the merged pill
     * gets a tinted background when its emoji matches one of these — others'
     * reactions stay neutral. Pass an empty list to disable highlighting.
     */
    ownReactions: ImmutableList<String> = persistentListOf(),
) {
    val allReactions = remember(reactionSummary) {
        reactionSummary.reactions.entries.mapNotNull { entry ->
            extractEmoji(entry.value.reactionContent)?.let { emoji -> emoji to entry.value.count }
        }
    }
    if (allReactions.isEmpty()) return

    val totalCount = remember(allReactions) { allReactions.sumOf { it.second } }

    var animatePop by remember { mutableStateOf(false) }
    val prevCount = remember { mutableIntStateOf(totalCount) }
    val scaleValue by animateFloatAsState(
        targetValue = if (animatePop) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        finishedListener = { animatePop = false },
    )
    LaunchedEffect(totalCount) {
        if (totalCount != prevCount.intValue) {
            animatePop = true
        }
        prevCount.intValue = totalCount
    }

    // Cap at 7 emojis with a fixed `take`. Earlier revisions read `BoxWithConstraints.maxWidth`
    // during composition to compute a dynamic `fitCount` keyed into a `remember(...)` slice — that
    // pattern produced an ANR on 1.3.1388 when measure-time width feedback caused a layout
    // invalidation loop. A static cap removes the parent-width feedback edge entirely; on
    // bubbles too narrow for the full pill, the Surface clips visually, which is acceptable.
    val displayEmojis = remember(allReactions) { allReactions.take(7) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .scale(scaleValue)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onReactionClick),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                displayEmojis.forEach { (emoji, _) ->
                    val isOwn = ownReactions.containsEmoji(emoji)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isOwn) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 16.sp,
                            color = if (isOwn) MaterialTheme.colorScheme.onPrimaryContainer
                            else Color.Unspecified,
                        )
                    }
                }
                if (totalCount > 1) {
                    Text(
                        text = totalCount.toString(),
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
        if (onAddEmoji != null) {
            AddReactionChip(onClick = onAddEmoji)
        }
    }
}

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
 * @param userDefaultReactions Recent reactions, shown first.
 * @param ownReactions Emojis the current user has already reacted to this message with.
 *                     Matching chips are tinted to indicate a tap will remove that reaction.
 * @param onSelect Callback invoked when user selects an emoji reaction
 * @param onShowAllEmojis Callback invoked when emoji full selector should be shown
 */
@Composable
fun ReactionMenu(
    modifier: Modifier = Modifier,
    userDefaultReactions: ImmutableList<String> = persistentListOf(),
    ownReactions: ImmutableList<String> = persistentListOf(),
    onSelect: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
) {
    val baseDefaults = listOf("❤️", "👍", "👎", "😂", "😮", "😢")
    val reactions = (userDefaultReactions + baseDefaults).distinctByEmoji().take(6)
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier
            .wrapContentWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            reactions.forEach { emoji ->
                val isOwn = ownReactions.containsEmoji(emoji)
                Surface(
                    shape = CircleShape,
                    color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onSelect(emoji) }
                        .let { if (isOwn) it.testTag("reaction_chip_own") else it },
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                        )
                    }
                }
            }
            IconButton(
                onClick = onShowAllEmojis,
                modifier = Modifier.size(40.dp).testTag("emoji_options_button")
            ) {
                Icon(
                    Icons.Default.MoreHoriz,
                    contentDescription = stringResource(MR.string.chat_message_emoji_options)
                )
            }
        }
    }
}


@Composable
fun ReactionIcon(
    emoji: String,
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
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

@Composable
fun AddReactionChip(
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Icon(
            imageVector = Icons.Default.AddReaction,
            contentDescription = stringResource(MR.string.chat_message_reaction),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}