package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.widget.AddReactionChip
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.core.widget.ReactionList
import id.homebase.core.widget.ReactionMenu
import id.homebase.resources.MR
import id.homebase.resources.feed_post_comment
import id.homebase.resources.feed_post_comment_count
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * The interaction row beneath a post: the existing-reactions pill, an add-reaction
 * affordance (quick ❤️ 😆 😥 menu + full picker), and a comment button with its count.
 *
 * Visibility follows [reactAccess]:
 *  - [ReactAccess.None] hides both reactions and comments.
 *  - [ReactAccess.EmojiOnly] hides the comment button.
 *  - [ReactAccess.CommentOnly] hides the emoji affordances.
 *  - [ReactAccess.All] shows everything.
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
) {
    val canReact = reactAccess == ReactAccess.All || reactAccess == ReactAccess.EmojiOnly
    val canComment = reactAccess == ReactAccess.All || reactAccess == ReactAccess.CommentOnly

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
            reactionSummary?.let { summary ->
                ReactionList(
                    reactionSummary = summary,
                    ownReactions = ownImmutable,
                    onReactionClick = onShowReactors,
                )
            }
            // Anchor the quick-reaction menu in a DropdownMenu over the chip so it
            // floats over content (mirrors MomentDetail's AssistChip + DropdownMenu
            // shape) instead of an inline Box that pushes the card down on toggle.
            Box {
                AddReactionChip(onClick = { showQuickMenu = !showQuickMenu })
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

        Spacer(modifier = Modifier.weight(1f))

        if (canComment) {
            TextButton(onClick = onOpenComments) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Comment,
                    contentDescription = stringResource(MR.string.feed_post_comment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (commentCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = commentLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

/** Feed default quick reactions, shown first in the [ReactionMenu]. */
private val QUICK_REACTIONS = listOf("❤️", "😆", "😥").toImmutableList()
