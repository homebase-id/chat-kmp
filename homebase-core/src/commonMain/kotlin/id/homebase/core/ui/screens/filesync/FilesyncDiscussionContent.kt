package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.withEmojiFont
import id.homebase.core.widget.AddReactionChip
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.core.widget.ReactionMenu
import id.homebase.core.widget.composerKeyHandler
import id.homebase.core.settings.rememberEnterSendsMessage
import id.homebase.resources.MR
import id.homebase.resources.action_retry
import id.homebase.resources.filesync_comment_hint
import id.homebase.resources.filesync_comments
import id.homebase.resources.filesync_comments_none
import id.homebase.resources.filesync_discussion_failed
import id.homebase.resources.filesync_reactions
import id.homebase.resources.filesync_reactions_limit
import id.homebase.resources.send
import id.homebase.resources.you
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

// Fits the header's content budget once encrypted, whatever the characters.
private const val COMMENT_MAX_CHARS = 1000

@Composable
internal fun FilesyncDiscussionContent(
    node: FilesyncNode,
    discussion: FilesyncDiscussion?,
    commentDraft: TextFieldState,
    onAction: (FilesyncUiAction) -> Unit,
) {
    val shown = discussion?.takeIf { it.nodeId == node.nodeId }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            shown == null || shown.loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            shown.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(MR.string.filesync_discussion_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onAction(FilesyncUiAction.ShowDiscussion(node.nodeId)) }) { Text(stringResource(MR.string.action_retry)) }
            }
            else -> {
                Text(stringResource(MR.string.filesync_reactions), style = MaterialTheme.typography.titleSmall)
                Reactions(shown, onAction)
                Text(stringResource(MR.string.filesync_comments), style = MaterialTheme.typography.titleSmall)
                if (shown.comments.isEmpty()) {
                    Text(
                        text = stringResource(MR.string.filesync_comments_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shown.comments.forEach { CommentRow(it) }
                CommentField(commentDraft, sending = shown.sending, onSend = { onAction(FilesyncUiAction.SendComment) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reactions(discussion: FilesyncDiscussion, onAction: (FilesyncUiAction) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var pickingAll by remember { mutableStateOf(false) }
    val toggle = { emoji: String -> onAction(FilesyncUiAction.ToggleReaction(emoji)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        discussion.reactions.forEach { reaction ->
            val mine = reaction.emoji in discussion.mine
            val label = "${reaction.emoji} ${reaction.count}".withEmojiFont()
            FilterChip(
                selected = mine,
                enabled = mine || discussion.canAddReaction,
                onClick = { toggle(reaction.emoji) },
                label = { Text(label) },
            )
        }
        if (discussion.canAddReaction) {
            Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                AddReactionChip(onClick = { picking = true })
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    ReactionMenu(
                        ownReactions = discussion.mine.toImmutableList(),
                        onSelect = { emoji ->
                            picking = false
                            toggle(emoji)
                        },
                        onShowAllEmojis = {
                            picking = false
                            pickingAll = true
                        },
                    )
                }
            }
        }
    }
    if (!discussion.canAddReaction) {
        Text(
            text = stringResource(MR.string.filesync_reactions_limit, MAX_OWN_REACTIONS),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pickingAll) {
        EmojiSelectorDialog(onDismiss = { pickingAll = false }, onEmojiSelected = toggle)
    }
}

@Composable
private fun CommentRow(comment: FilesyncComment) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (comment.mine) stringResource(MR.string.you) else comment.author?.domainName.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = formatDayTime(comment.createdMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(comment.body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CommentField(draft: TextFieldState, sending: Boolean, onSend: () -> Unit) {
    val hasText by remember(draft) { derivedStateOf { draft.text.isNotBlank() } }
    val canSend = !sending && hasText
    OutlinedTextField(
        state = draft,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("filesyncCommentField")
            .composerKeyHandler(enterSendsMessage = rememberEnterSendsMessage(), onSend = { if (canSend) onSend() }),
        enabled = !sending,
        placeholder = { Text(stringResource(MR.string.filesync_comment_hint)) },
        inputTransformation = InputTransformation.maxLength(COMMENT_MAX_CHARS),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 5),
        trailingIcon = {
            IconButton(onClick = onSend, enabled = canSend) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(MR.string.send))
                }
            }
        },
    )
}
