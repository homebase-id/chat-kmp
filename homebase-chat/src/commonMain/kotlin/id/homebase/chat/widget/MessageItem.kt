package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.data.MessageUiModel

@Composable
fun MessageItem(
    message: MessageUiModel,
    currentOdinId: String,
    renderAuthorName: Boolean = false,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onUiAction: (ConversationListUiAction) -> Unit,
    downloadingFiles: Set<String>,
) {
    // TODO: currentOdinId is "" - is that supposed to be the case??
    val odinId: OdinId? = try {
        OdinId(currentOdinId)
    } catch (_: Exception) {
        null
    }

    // Memoize all callbacks with message.id as key
    val onMessageInfo =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShowMessageInfo(message)) } }
    val onReply =
        remember(message.id) { { onUiAction(ConversationListUiAction.ReplyToMessage(message)) } }
    val onShare =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShareMessage(message)) } }
    val onDelete =
        remember(message.id) { { onUiAction(ConversationListUiAction.DeleteMessage(message.id)) } }
    val onShowReactions =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShowReactionDetails(messageId = message.id)) } }
    val onMediaClick = remember(message.id) {
        { payload: PayloadDescriptor ->
            onUiAction(
                ConversationListUiAction.MediaClicked(
                    message,
                    payload.key
                )
            )
        }
    }
    val onAddReaction = remember(message.id) {
        { _: Any, reaction: String ->
            onUiAction(
                ConversationListUiAction.ToggleReaction(
                    message.conversationId,
                    message.id,
                    reaction
                )
            )
        }
    }
    val onShowMore =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShowMoreClicked(message.conversationId, message.id)) } }


    if (message.isCurrentUser(odinId)) {
        val onEdit = remember(message.id) {
            {
                onUiAction(
                    ConversationListUiAction.EditMessage(
                        messageId = message.id,
                        versionTag = message.versionTag,
                        ignoreDraft = false
                    )
                )
            }
        }

        SentMessageBubble(
            message = message,
            onMessageInfo = onMessageInfo,
            onReply = onReply,
            onEdit = onEdit,
            onShare = onShare,
            onDelete = onDelete,
            onMediaClick = onMediaClick,
            onAddReaction = onAddReaction,
            onShowReactions = onShowReactions,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedTransitionScope = sharedTransitionScope,
            downloadingFiles = downloadingFiles,
            onShowMore = onShowMore
        )
    } else {
        val onMarkAsRead =
            remember(message.id) { { onUiAction(ConversationListUiAction.MarkAsRead(message.id)) } }

        ReceivedMessageBubble(
            message = message,
            renderAuthorName = renderAuthorName,
            onMessageInfo = onMessageInfo,
            onReply = onReply,
            onShare = onShare,
            onDelete = onDelete,
            onMarkAsRead = onMarkAsRead,
            onAddReaction = onAddReaction,
            onShowReactions = onShowReactions,
            onMediaClick = onMediaClick,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedTransitionScope = sharedTransitionScope,
            downloadingFiles = downloadingFiles,
            onShowMore = onShowMore
        )
    }
}
