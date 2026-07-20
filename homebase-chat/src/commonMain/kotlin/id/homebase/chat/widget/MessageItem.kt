package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.MessageClusterPosition
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.dice.DiceRollDescriptor
import id.homebase.chat.dice.canBattle
import id.homebase.chat.services.content.ActionPolicy
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.util.isMobile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
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
    clusterPosition: MessageClusterPosition = MessageClusterPosition.ALONE,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onUiAction: (ConversationListUiAction) -> Unit,
    downloadingFiles: Set<String>,
    uploadStatus: UploadStatus? = null,
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
    allDiceDescriptors: ImmutableList<DiceRollDescriptor> = persistentListOf(),
    searchQuery: String = "",
    isCurrentSearchResult: Boolean = false,
    chainCap: Int? = null,
    ownLiveShareUntilMs: Long? = null,
) {
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

    // Live-location controls for the location bubble (ignored by non-location media). State (LIVE/
    // ENDED) is read from the message descriptor in the bubble; this only carries side + actions.
    val sentByYou = message.isAuthoredBy(odinId)
    val liveLocationControls = remember(message.id, sentByYou, ownLiveShareUntilMs) {
        LiveLocationBubbleControls(
            sentByYou = sentByYou,
            onStart = { durationMs ->
                onUiAction(ConversationListUiAction.StartLiveLocationShare(message.id, durationMs))
            },
            onStop = { onUiAction(ConversationListUiAction.StopLiveLocationShare(message.id)) },
            onStartShareBack = { durationMs ->
                onUiAction(ConversationListUiAction.ShareLiveLocationBack(message.id, durationMs))
            },
            onOpenMap = { onUiAction(ConversationListUiAction.OpenLiveLocationMap) },
            ownShareUntilMs = ownLiveShareUntilMs,
        )
    }

    // Memoize all callbacks with message.id as key. Disabled actions get
    // null callbacks; the bubble subcomposables already gate on non-null.
    val onMessageInfo =
        remember(message.id) { { onUiAction(ConversationListUiAction.ShowMessageInfo(message)) } }
    val onReply =
        if (policy.allowReply) remember(message.id) { { onUiAction(ConversationListUiAction.ReplyToMessage(message)) } } else null
    // Battle is dice-roll-specific. Show only when the message is a valid dice
    // roll AND the chain has room AND the current user isn't already in it.
    val battleDescriptor = (message.messageContent as? MessageContent.DiceRoll)?.descriptor
    val onBattle = if (battleDescriptor != null && battleDescriptor.isValid() &&
        canBattle(battleDescriptor, odinId, allDiceDescriptors)
    ) {
        remember(message.id) { { onUiAction(ConversationListUiAction.BattleDiceRoll(message)) } }
    } else null
    val onForward =
        if (policy.allowForward) remember(message.id) { { onUiAction(ConversationListUiAction.ForwardMessage(message)) } } else null
    val onShare =
        if (policy.allowShare) remember(message.id) { { onUiAction(ConversationListUiAction.ShareMessage(message)) } } else null
    val onDelete =
        remember(message.id) { { onUiAction(ConversationListUiAction.DeleteMessage(message.id)) } }
    val onTogglePin =
        remember(message.id) { { onUiAction(ConversationListUiAction.TogglePinMessage(message.id)) } }
    val onShowReactions =
        if (policy.allowReactionDetails) remember(message.id) { { onUiAction(ConversationListUiAction.ShowReactionDetails(messageId = message.id)) } } else null
    val onDecryptFile =
        remember(message.id) { { payload: PayloadDescriptor -> onUiAction(ConversationListUiAction.DecryptFile(messageId = message.id, payloadKey = payload.key)) } }
    // Tapping the inline reply-preview chip pinned above a bubble. We route
    // through OpenReplyTarget so the handler can branch on content kind: an
    // Event target opens the detail dialog directly; everything else falls
    // through to ScrollToMessageId.
    val onClickMessageId =
        remember(message.id) { { messageId: Uuid -> onUiAction(ConversationListUiAction.OpenReplyTarget(messageId)) } }
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
                liveControls = liveLocationControls,
                userDefaultReactions = userDefaultReactions,
                decryptedFiles = decryptedFiles,
                currentOdinId = currentOdinId,
                clusterPosition = clusterPosition,
                onMessageInfo = onMessageInfo,
                onReply = onReply,
                onBattle = onBattle,
                onForward = onForward,
                onEdit = onEdit,
                onShare = onShare,
                onDelete = onDelete,
                onTogglePin = onTogglePin,
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
                chainCap = chainCap,
            )
        }
    } else {
        // Keep `latest` tracking the freshest model: a swipe-induced
        // mark-read sets localReadTimestamp upstream and the model
        // recomposes with the new value. Without rememberUpdatedState
        // here, the lambda would close over the stale model (the one
        // present when this lambda was first remember'd, keyed by
        // message.id), and a rapid second swipe would still see
        // localReadTimestamp == null and re-enqueue a duplicate receipt.
        val latest by rememberUpdatedState(message)
        val onMarkAsRead = remember {
            {
                onUiAction(
                    ConversationListUiAction.MarkAsRead(
                        latest.conversationId,
                        listOf(latest),
                    )
                )
            }
        }

        SwipeableMessageWrapper(
            enabled = isMobile() && !message.isDeleted,
            onSwipeRight = onReply,
            onSwipeLeft = onMessageInfo,
        ) {
            ReceivedMessageBubble(
                message = message,
                liveControls = liveLocationControls,
                userDefaultReactions = userDefaultReactions,
                decryptedFiles = decryptedFiles,
                currentOdinId = currentOdinId,
                renderAuthorName = renderAuthorName,
                isGroupConversation = isGroupConversation,
                clusterPosition = clusterPosition,
                onMessageInfo = onMessageInfo,
                onReply = onReply,
                onBattle = onBattle,
                onForward = onForward,
                onDelete = onDelete,
                onTogglePin = onTogglePin,
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
                chainCap = chainCap,
            )
        }
    }
}
