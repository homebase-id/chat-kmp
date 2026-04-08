package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.isMobile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlin.uuid.Uuid

@Composable
fun MessageItem(
    message: MessageUiModel,
    userDefaultReactions: ImmutableList<String>,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    currentOdinId: String,
    renderAuthorName: Boolean = false,
    isGroupConversation: Boolean = false,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onUiAction: (ConversationListUiAction) -> Unit,
    downloadingFiles: Set<String>,
    uploadStatus: UploadStatus? = null,
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
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
    val onForward =
        remember(message.id) { { onUiAction(ConversationListUiAction.ForwardMessage(message)) } }
    val onShare =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShareMessage(message)) } }
    val onDelete =
        remember(message.id) { { onUiAction(ConversationListUiAction.DeleteMessage(message.id)) } }
    val onShowReactions =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShowReactionDetails(messageId = message.id)) } }
    val onDecryptFile =
        remember(message.id) { { payload: PayloadDescriptor -> onUiAction(ConversationListUiAction.DecryptFile(messageId = message.id, payloadKey = payload.key)) } }
    val onClickMessageId =
        remember(message.id) { { messageId: Uuid -> onUiAction(ConversationListUiAction.ScrollToMessageId(messageId)) } }
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
    val onBlock = if (isGroupConversation) {
        remember(message.id) {
            message.originalAuthor?.let { author ->
                { onUiAction(ConversationListUiAction.BlockUser(author)) }
            }
        }
    } else null
    val onReport =
        remember(message.id) { { onUiAction(ConversationListUiAction.ReportContent) } }

    if (message.isAuthoredBy(odinId)) {
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

        SwipeableMessageWrapper(
            enabled = isMobile() && !message.isDeleted,
            onSwipeRight = onReply,
            onSwipeLeft = onMessageInfo,
        ) {
            SentMessageBubble(
                message = message,
                userDefaultReactions = userDefaultReactions,
                decryptedFiles = decryptedFiles,
                onMessageInfo = onMessageInfo,
                onReply = onReply,
                onForward = onForward,
                onEdit = onEdit,
                onShare = onShare,
                onDelete = onDelete,
                onMediaClick = onMediaClick,
                onClickMessageId = onClickMessageId,
                onRequestDecryptedFile = onDecryptFile,
                onAddReaction = onAddReaction,
                onShowReactions = onShowReactions,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedTransitionScope = sharedTransitionScope,
                downloadingFiles = downloadingFiles,
                onShowMore = onShowMore,
                uploadStatus = uploadStatus,
                replyMessages = replyMessages,
            )
        }
    } else {
        val onMarkAsRead =
            remember(message.id) { { onUiAction(ConversationListUiAction.MarkAsRead(message.conversationId, listOf(message.id))) } }

        SwipeableMessageWrapper(
            enabled = isMobile() && !message.isDeleted,
            onSwipeRight = onReply,
            onSwipeLeft = onMessageInfo,
        ) {
            ReceivedMessageBubble(
                message = message,
                userDefaultReactions = userDefaultReactions,
                decryptedFiles = decryptedFiles,
                renderAuthorName = renderAuthorName,
                onMessageInfo = onMessageInfo,
                onReply = onReply,
                onForward = onForward,
                onDelete = onDelete,
                onMarkAsRead = onMarkAsRead,
                onAddReaction = onAddReaction,
                onShowReactions = onShowReactions,
                onMediaClick = onMediaClick,
                onClickMessageId = onClickMessageId,
                onRequestDecryptedFile = onDecryptFile,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedTransitionScope = sharedTransitionScope,
                downloadingFiles = downloadingFiles,
                onShowMore = onShowMore,
                replyMessages = replyMessages,
                onBlock = onBlock,
                onReport = onReport,
            )
        }
    }
}
