package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
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
    onUiAction: (ConversationListUiAction) -> Unit
) {
    // TODO: currentOdinId is "" - is that supposed to be the case??
    val odinId: OdinId? = try {
        OdinId(currentOdinId)
    } catch (_: Exception) {
        null
    }

    if (message.isCurrentUser(odinId)) {
        SentMessageBubble(
            message = message,
            onMessageInfo = { onUiAction(ConversationListUiAction.ShowMessageInfo(message)) },
            onReply = { onUiAction(ConversationListUiAction.ReplyToMessage(message)) },
            onEdit = { onUiAction(ConversationListUiAction.EditMessage(conversationId = message.conversationId, messageId = message.id)) },
            onDelete = { onUiAction(ConversationListUiAction.DeleteMessage(message.id)) },
            onMediaClick = { payload ->
                onUiAction(
                    ConversationListUiAction.MediaClicked(
                        message,
                        payload.key,
                    )
                )
            },
            onAddReaction = { _, reaction ->
                onUiAction(
                    ConversationListUiAction.AddReaction(
                        message.conversationId, message.id, reaction = reaction
                    )
                )
            },
            onShowReactions = {
                onUiAction(ConversationListUiAction.ShowReactionDetails(messageId = message.id))
            },
            animatedVisibilityScope = animatedVisibilityScope,
            sharedTransitionScope = sharedTransitionScope,
        )
    } else {
        ReceivedMessageBubble(
            message = message,
            renderAuthorName = renderAuthorName,
            onMessageInfo = { onUiAction(ConversationListUiAction.ShowMessageInfo(message)) },
            onReply = { onUiAction(ConversationListUiAction.ReplyToMessage(message)) },
            onDelete = { onUiAction(ConversationListUiAction.DeleteMessage(message.id)) },
            onMarkAsRead = { onUiAction(ConversationListUiAction.MarkAsRead(message.id)) },
            onAddReaction = { _, reaction ->
                onUiAction(
                    ConversationListUiAction.AddReaction(
                        message.conversationId, message.id, reaction = reaction
                    )
                )
            },
            onShowReactions = {
                onUiAction(ConversationListUiAction.ShowReactionDetails(messageId = message.id))
            },
            onMediaClick = { payload ->
                onUiAction(
                    ConversationListUiAction.MediaClicked(
                        message,
                        payload.key,
                    )
                )
            },
            animatedVisibilityScope = animatedVisibilityScope,
            sharedTransitionScope = sharedTransitionScope,
        )
    }
}
