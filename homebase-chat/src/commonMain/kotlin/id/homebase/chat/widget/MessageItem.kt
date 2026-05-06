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
import id.homebase.chat.services.content.ActionPolicy
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
    searchQuery: String = "",
    isCurrentSearchResult: Boolean = false,
) {
    // TODO: currentOdinId is "" - is that supposed to be the case??
    val odinId: OdinId? = try {
        OdinId(currentOdinId)
    } catch (_: Exception) {
        null
    }

    // Per-content action policy. Plain text/media messages have null
    // messageContent → fall back to Standard (every action allowed). Typed
    // rich content (event today; poll/doodle/dice later) declares its own
    // policy on the sealed type — defaulting to StructuredOneShot which
    // disables edit/reply/forward/share/inline-reactions/reactor-details.
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard

    // Memoize all callbacks with message.id as key. Disabled actions get
    // null callbacks; the bubble subcomposables already gate on non-null.
    val onMessageInfo =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShowMessageInfo(message)) } }
    val onReply =
        if (policy.allowReply) remember(message.id) { { onUiAction(ConversationListUiAction.ReplyToMessage(message)) } } else null
    val onForward =
        if (policy.allowForward) remember(message.id) { { onUiAction(ConversationListUiAction.ForwardMessage(message)) } } else null
    val onShare =
        if (policy.allowShare) remember(message.id) { { onUiAction(ConversationListUiAction.ShareMessage(message)) } } else null
    val onDelete =
        remember(message.id) { { onUiAction(ConversationListUiAction.DeleteMessage(message.id)) } }
    val onShowReactions =
        if (policy.allowReactionDetails) remember(message.id) { { onUiAction(ConversationListUiAction.ShowReactionDetails(messageId = message.id)) } } else null
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
    val onAddReaction = if (policy.allowInlineReactions) {
        remember(message.id) {
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
    } else null
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
        val onEdit = if (policy.allowEdit) {
            remember(message.id) {
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
        } else null

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
                searchQuery = searchQuery,
                isCurrentSearchResult = isCurrentSearchResult,
            )
        }
    } else { val onMarkAsRead =
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
                searchQuery = searchQuery,
                isCurrentSearchResult = isCurrentSearchResult,
            )
        }
    }
}
