package id.homebase.core.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
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
    hasOwnReaction: Boolean = false,
) {
    val allReactions = remember(reactionSummary) {
        reactionSummary.reactions.entries.mapNotNull { entry ->
            extractEmoji(entry.value.reactionContent)?.let { emoji -> emoji to entry.value.count }
        }
    }
    if (allReactions.isEmpty()) return

    val displayEmojis = remember(allReactions) { allReactions.take(3) }
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
            color = if (hasOwnReaction) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (hasOwnReaction) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            else null,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                displayEmojis.forEachIndexed { index, (emoji, _) ->
                    val emojiScale = remember(emoji) { Animatable(0f) }
                    LaunchedEffect(emoji) {
                        delay(index * 50L)
                        emojiScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        )
                    }
                    Text(
                        text = emoji,
                        fontSize = 16.sp,
                        modifier = Modifier.scale(emojiScale.value),
                    )
                }
                if (totalCount > 1) {
                    val countScale = remember { Animatable(1f) }
                    LaunchedEffect(totalCount) {
                        countScale.snapTo(0.6f)
                        countScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            )
                        )
                    }
                    Text(
                        text = totalCount.toString(),
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp).scale(countScale.value),
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
 * @param userDefaultReactions List of default reactions to show in the menu
 * @param onSelect Callback invoked when user selects an emoji reaction
 * @param onShowAllEmojis Callback invoked when emoji full selector should be shown
 */
@Composable
fun ReactionMenu(
    modifier: Modifier = Modifier,
    userDefaultReactions : ImmutableList<String> = persistentListOf(),
    onSelect: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
) {
    val userReactions = userDefaultReactions.take(6)
    val defaultReactions = listOf("❤️", "👍", "👎", "😂", "😮", "😢").filter { !userReactions.contains(it) }
    val reactions = userReactions + defaultReactions.take(6 - minOf(6, userDefaultReactions.size))
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
                IconButton(
                    onClick = { onSelect(emoji) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text(
                        text = emoji,
                        fontSize = 24.sp
                    )
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