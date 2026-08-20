package id.homebase.core.ui.screens.feed.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.decodeReactionEmoji
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.core.widget.ReactionMenu
import id.homebase.resources.MR
import id.homebase.resources.feed_post_comment
import id.homebase.resources.feed_post_comment_count
import id.homebase.resources.feed_post_react
import id.homebase.resources.feed_post_repost
import id.homebase.resources.feed_post_show_reactors
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

// Write affordances follow BOTH the author's [reactAccess] and the viewer's [permission]. A null [permission]
// means "not resolved yet": the affordance stays visible, and the write is still authorised server-side.
@Composable
fun PostInteracts(
    reactionSummary: ReactionSummary?,
    ownReactions: List<String>,
    commentCount: Int,
    reactAccess: ReactAccess,
    onToggleReaction: (String) -> Unit,
    onOpenComments: () -> Unit,
    onShowReactors: () -> Unit,
    modifier: Modifier = Modifier,
    onRepost: (() -> Unit)? = null,
    permission: CanReact? = null,
) {
    val canReact = (reactAccess == ReactAccess.All || reactAccess == ReactAccess.EmojiOnly) &&
        (permission?.allowsEmoji ?: true)
    val canComment = (reactAccess == ReactAccess.All || reactAccess == ReactAccess.CommentOnly) &&
        (permission?.allowsComment ?: true)
    val canRepost = reactAccess != ReactAccess.None

    var showQuickMenu by remember { mutableStateOf(false) }
    var showFullPicker by remember { mutableStateOf(false) }

    val ownImmutable = remember(ownReactions) { ownReactions.toImmutableList() }
    val commentLabel = stringResource(MR.string.feed_post_comment_count, commentCount)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canReact) {
            Box {
                LikeButton(
                    ownReactions = ownReactions,
                    onClick = { showQuickMenu = !showQuickMenu },
                )
                DropdownMenu(
                    expanded = showQuickMenu,
                    onDismissRequest = { showQuickMenu = false },
                ) {
                    ReactionMenu(
                        userDefaultReactions = QUICK_REACTIONS,
                        ownReactions = ownImmutable,
                        onSelect = { emoji ->
                            showQuickMenu = false
                            onToggleReaction(emoji)
                        },
                        onShowAllEmojis = {
                            showQuickMenu = false
                            showFullPicker = true
                        },
                    )
                }
            }
        }

        // Only the Like button is gated on the viewer's react permission. Keeping the summary inside the
        // `canReact` branch left a post you may not react to with no tally AND no way into the roster.
        reactionSummary?.let { summary ->
            PostReactionSummary(summary = summary, onClick = onShowReactors)
        }

        // Left-grouped, no weight spacer: a far-right split left a large empty middle that read as unbalanced
        // when a post had no reactions.
        if (onRepost != null && canRepost) {
            FeedActionButton(
                icon = Icons.Outlined.Repeat,
                contentDescription = stringResource(MR.string.feed_post_repost),
                onClick = onRepost,
            )
        }

        if (canComment) {
            FeedActionButton(
                icon = Icons.AutoMirrored.Outlined.Comment,
                contentDescription = stringResource(MR.string.feed_post_comment),
                onClick = onOpenComments,
                trailing = if (commentCount > 0) {
                    {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = commentLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }

    if (canReact && showFullPicker) {
        EmojiSelectorDialog(
            onDismiss = { showFullPicker = false },
            onEmojiSelected = { emoji ->
                showFullPicker = false
                onToggleReaction(emoji)
            },
        )
    }
}

// MaterialTheme.motionScheme is internal in JetBrains material3 1.9.0, so the spring is tuned here.
@Composable
private fun FeedActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.18f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "feed-action-scale",
    )
    val tint by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "feed-action-tint",
    )

    val glyph: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.scale(scale),
        )
    }

    if (trailing != null) {
        TextButton(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = modifier,
        ) {
            glyph()
            trailing()
        }
    } else {
        IconButton(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = modifier,
        ) {
            glyph()
        }
    }
}

// Once you've reacted it shows your own reaction emoji in place of the heart, so the control doubles as your
// reaction state.
@Composable
private fun LikeButton(
    ownReactions: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.18f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "like-scale",
    )
    val tint by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "like-tint",
    )
    val own = ownReactions.firstOrNull()?.takeUnless { it.startsWith('_') }
    IconButton(onClick = onClick, interactionSource = interactionSource, modifier = modifier) {
        if (own == null) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(MR.string.feed_post_react),
                tint = tint,
                modifier = Modifier.scale(scale),
            )
        } else {
            Text(text = own, fontSize = 20.sp, modifier = Modifier.scale(scale))
        }
    }
}

// Renders nothing when there are no human reactions — machine reactions (a leading `_` code) are skipped,
// matching the shared ReactionList.
@Composable
private fun PostReactionSummary(
    summary: ReactionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emojiCounts = remember(summary) {
        summary.reactions.values.mapNotNull { entry ->
            decodeReactionEmoji(entry.reactionContent)
                ?.takeUnless { it.startsWith('_') }
                ?.let { it to entry.count }
        }
    }
    if (emojiCounts.isEmpty()) return
    val total = remember(emojiCounts) { emojiCounts.sumOf { it.second } }
    // Up to 5 distinct glyphs, matching the web feed.
    val topEmojis = remember(emojiCounts) {
        emojiCounts.sortedByDescending { it.second }.map { it.first }.distinct().take(5)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            // Without onClickLabel the facepile is an unlabelled clickable and the roster is unreachable non-visually.
            .clickable(onClickLabel = stringResource(MR.string.feed_post_show_reactors)) { onClick() }
            .heightIn(min = 36.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Negative spacing overlaps each disc; the surface-coloured border separates them.
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            topEmojis.forEach { emoji ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
                ) {
                    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        Text(text = emoji, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = total.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val QUICK_REACTIONS = listOf("❤️", "😆", "😥").toImmutableList()
