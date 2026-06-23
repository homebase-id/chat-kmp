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
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.decodeReactionEmoji
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.core.widget.ReactionMenu
import id.homebase.resources.MR
import id.homebase.resources.feed_post_comment
import id.homebase.resources.feed_post_comment_count
import id.homebase.resources.feed_post_react
import id.homebase.resources.feed_post_repost
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * The interaction row beneath a post: an overlapping-glyph reaction summary (facepile + count), an
 * add-reaction affordance (quick ❤️ 😆 😥 menu + full picker), and a comment button with its count.
 *
 * Visibility follows [reactAccess]:
 *  - [ReactAccess.None] hides both reactions and comments.
 *  - [ReactAccess.EmojiOnly] hides the comment button.
 *  - [ReactAccess.CommentOnly] hides the emoji affordances.
 *  - [ReactAccess.All] shows everything.
 *
 * Expressive: action glyphs sit faint at rest ([androidx.compose.material3.ColorScheme.onSurfaceVariant])
 * and spring up — scaling and gaining colour emphasis — while pressed (see [FeedActionButton]),
 * so the bar reads quiet until touched.
 *
 * @param reactionSummary current server-side reaction tallies, or null when none.
 * @param ownReactions bare emoji the current user has reacted with (tints matching chips).
 * @param onToggleReaction add/remove the given emoji.
 * @param onOpenComments open the comment thread.
 * @param onShowReactors open the "who reacted" sheet.
 */
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
) {
    val canReact = reactAccess == ReactAccess.All || reactAccess == ReactAccess.EmojiOnly
    val canComment = reactAccess == ReactAccess.All || reactAccess == ReactAccess.CommentOnly
    // Repost is offered whenever the post is interactable at all — only a fully locked-down
    // (ReactAccess.None) post hides it.
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
        // LEFT (web PostInteracts parity): the Like button — a heart that opens the quick-reaction
        // menu and shows your own reaction emoji once you've reacted — followed by the reaction
        // summary facepile. The actions (repost, comment) are pushed to the right.
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
            reactionSummary?.let { summary ->
                PostReactionSummary(summary = summary, onClick = onShowReactors)
            }
        }

        // Left-grouped action cluster (like → reactions → repost → comment). No weight spacer:
        // a far-right split left a large empty middle that read as unbalanced when a post had no
        // reactions. Grouping them tight reads as an intentional toolbar (IG/stream style).
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

/**
 * An expressive feed action: a faint [androidx.compose.material3.ColorScheme.onSurfaceVariant] glyph
 * that springs up — scaling ~1.18× and brightening to `onSurface` — while held, then settles back.
 * The press feedback uses a bouncy spring so the pop reads as a deliberate, springy reaction.
 * (MaterialTheme.motionScheme is internal in JetBrains material3 1.9.0, so we tune a spring here.)
 *
 * When [trailing] is supplied the button renders as a [TextButton] (icon + label, e.g. the comment
 * count); otherwise it is a bare [IconButton].
 */
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

/**
 * The web feed's Like control ([LikeButton.tsx]): a faint heart that springs on press and opens the
 * quick-reaction menu. Once you've reacted it shows your own reaction emoji in place of the heart
 * (mirroring the web's `UIEmoji`), so the control doubles as your reaction state. Sits on the LEFT
 * of the interaction row, before the reaction summary.
 */
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

/**
 * Facebook/Instagram-style reaction summary: the top distinct emoji rendered as a stack of
 * overlapping circular glyphs (each disc outlined in the surface colour so the stack reads
 * cleanly), followed by the total reaction count. Tapping opens the "who reacted" roster.
 * Renders nothing when there are no human reactions (machine reactions — a leading `_` code —
 * are skipped, matching the shared ReactionList).
 */
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
    // Up to 5 distinct glyphs, matching the web feed's reaction summary.
    val topEmojis = remember(emojiCounts) {
        emojiCounts.sortedByDescending { it.second }.map { it.first }.distinct().take(5)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
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

/** Feed default quick reactions, shown first in the [ReactionMenu]. */
private val QUICK_REACTIONS = listOf("❤️", "😆", "😥").toImmutableList()
