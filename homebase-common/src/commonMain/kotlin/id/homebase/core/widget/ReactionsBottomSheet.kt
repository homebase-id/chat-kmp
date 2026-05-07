package id.homebase.core.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.chat_message_reaction
import id.homebase.resources.chat_reactions_all
import id.homebase.resources.chat_reactions_tap_to_remove
import id.homebase.resources.chat_reactions_title
import id.homebase.resources.you
import org.jetbrains.compose.resources.stringResource

@Immutable
data class ReactionDisplayItem(
    val odinId: String,
    val displayName: String,
    val emoji: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsBottomSheet(
    reactions: List<ReactionDisplayItem>,
    isLoading: Boolean,
    ownerOdinId: String?,
    onContactClick: (odinId: String) -> Unit,
    onAddReaction: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
    ) {
        BoxWithConstraints {
            val sheetMaxHeight = maxHeight * 0.87f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetMaxHeight)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(MR.string.chat_reactions_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }

                ReactionsContent(
                    reactions = reactions,
                    ownerOdinId = ownerOdinId,
                    onContactClick = onContactClick,
                    onAddEmoji = onAddReaction?.let { { showEmojiPicker = true } },
                    onRemoveReaction = onAddReaction,
                )
            }
        }
    }

    if (showEmojiPicker) {
        EmojiSelectorDialog(
            onDismiss = { showEmojiPicker = false },
            onEmojiSelected = { emoji ->
                showEmojiPicker = false
                onAddReaction?.invoke(emoji)
            },
        )
    }
}

@Composable
private fun ColumnScope.ReactionsContent(
    reactions: List<ReactionDisplayItem>,
    ownerOdinId: String?,
    onContactClick: (odinId: String) -> Unit,
    onAddEmoji: (() -> Unit)? = null,
    onRemoveReaction: ((String) -> Unit)? = null,
) {
    val grouped = remember(reactions) {
        reactions.groupBy { it.emoji }
    }
    val emojiKeys = remember(grouped) { grouped.keys.toList() }
    val showAllTab = emojiKeys.size > 1
    val ownerEmojis = remember(reactions, ownerOdinId) {
        reactions.filter { it.odinId == ownerOdinId }.map { it.emoji }.toSet()
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val filteredReactions = remember(selectedTabIndex, reactions, grouped, emojiKeys, showAllTab, ownerOdinId) {
        val base = if (!showAllTab || selectedTabIndex == 0) {
            reactions
        } else {
            val emojiIndex = if (showAllTab) selectedTabIndex - 1 else selectedTabIndex
            val emoji = emojiKeys.getOrNull(emojiIndex) ?: return@remember reactions
            grouped[emoji] ?: emptyList()
        }
        base.sortedByDescending { it.odinId == ownerOdinId }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onAddEmoji != null) {
            AddEmojiChip(onClick = onAddEmoji)
        }
        if (showAllTab) {
            EmojiFilterChip(
                selected = selectedTabIndex == 0,
                label = "${stringResource(MR.string.chat_reactions_all)} ${reactions.size}",
                onClick = { selectedTabIndex = 0 },
            )
        }
        emojiKeys.forEachIndexed { index, emoji ->
            val tabIndex = if (showAllTab) index + 1 else index
            val count = grouped[emoji]?.size ?: 0
            val isOwnReaction = emoji in ownerEmojis
            EmojiFilterChip(
                selected = selectedTabIndex == tabIndex,
                isOwnReaction = isOwnReaction,
                label = "$emoji $count",
                onClick = { selectedTabIndex = tabIndex },
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    LazyColumn(modifier = Modifier.weight(1f)) {
        items(
            items = filteredReactions,
            key = { "${it.odinId}_${it.emoji}" }
        ) { item ->
            val isOwner = item.odinId == ownerOdinId
            ReactionRow(
                item = item,
                isOwner = isOwner,
                ownerOdinId = ownerOdinId,
                onClick = if (isOwner && onRemoveReaction != null) {
                    { onRemoveReaction(item.emoji) }
                } else if (!isOwner) {
                    { onContactClick(item.odinId) }
                } else null,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun EmojiFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    isOwnReaction: Boolean = false,
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isOwnReaction -> Color(0xFF1E88E5).copy(alpha = 0.20f)
            selected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isOwnReaction -> Color(0xFF1565C0)
            selected -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AddEmojiChip(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Default.AddReaction,
            contentDescription = stringResource(MR.string.chat_message_reaction),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReactionRow(
    item: ReactionDisplayItem,
    isOwner: Boolean,
    ownerOdinId: String?,
    onClick: (() -> Unit)?,
) {
    val youLabel = stringResource(MR.string.you)
    val tapToRemoveLabel = stringResource(MR.string.chat_reactions_tap_to_remove)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarOptions = AvatarOptions(size = 40.dp)
        if (isOwner && ownerOdinId != null) {
            OwnerAvatar(
                odinId = OdinId(ownerOdinId),
                profileImageData = null,
                initials = item.displayName.initials(),
                options = avatarOptions,
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
            )
        } else {
            PublicAvatar(
                odinId = OdinId(item.odinId),
                initials = item.displayName.initials(),
                options = avatarOptions,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isOwner) youLabel else item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isOwner && onClick != null) {
                Text(
                    text = tapToRemoveLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = item.emoji,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
        )
    }
}
