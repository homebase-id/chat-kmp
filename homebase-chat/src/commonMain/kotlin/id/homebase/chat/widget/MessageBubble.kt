package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.MessageClusterPosition
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.event.EventDateChip
import id.homebase.chat.event.rememberEventTimes
import id.homebase.chat.event.rememberViewerLocalDate
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyContext
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.content.ActionPolicy
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.MessageSent
import id.homebase.core.ui.assets.MessageSentAndDelivered
import id.homebase.core.ui.assets.MessageSentAndRead
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getOdinIdColor
import id.homebase.core.util.initials
import id.homebase.core.util.isDesktop
import id.homebase.core.util.isEmojiContentOnly
import id.homebase.core.util.isMobile
import id.homebase.core.util.stripComposerLineBreakArtifacts
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.core.widget.ReactionList
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_block
import id.homebase.resources.chat_message_block_confirm_body
import id.homebase.resources.chat_message_block_confirm_title
import id.homebase.resources.chat_message_options
import id.homebase.resources.chat_message_reaction
import id.homebase.resources.chat_message_reply
import id.homebase.resources.chat_message_report
import id.homebase.resources.chat_message_report_confirm_body
import id.homebase.resources.chat_message_report_confirm_title
import id.homebase.resources.media
import id.homebase.resources.cd_reply_thumbnail
import id.homebase.resources.message_delivered
import id.homebase.resources.message_read
import id.homebase.resources.message_send_failed
import id.homebase.resources.message_sending
import id.homebase.resources.message_sent
import id.homebase.resources.you
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val GroupMessageAvatarOptions = AvatarOptions(size = Dimens.Conversation.itemAvatarSize)

/**
 * Displays a message bubble for messages sent to other users.
 *
 * Shows the message content aligned to the right with appropriate styling for sent messages.
 * Includes a hover menu on desktop and long-press animation on mobile for message actions.
 *
 * @param message The message data to display.
 * @param onMessageInfo Callback invoked when user requests message info/details.
 * @param onReply Callback invoked when user wants to reply to this message.
 * @param onEdit Callback invoked when user wants to edit this message.
 * @param onDelete Callback invoked when user wants to delete this message
 * @param onMediaClick Callback invoked when user clicks on media attachment.
 * @param onAddReaction Callback invoked when user wants to add a reaction to this message.
 * @param onShowReactions Callback invoked when user wants to show a list of reactions.
 * @param sharedTransitionScope The shared transition scope for animations.
 * @param animatedVisibilityScope The animated visibility scope for animations.
 */
@Composable
fun SentMessageBubble(
    message: MessageUiModel,
    liveControls: LiveLocationBubbleControls? = null,
    userDefaultReactions: ImmutableList<String>,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    currentOdinId: String = "",
    clusterPosition: MessageClusterPosition = MessageClusterPosition.ALONE,
    onMessageInfo: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onBattle: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {},
    onMediaClick: (PayloadDescriptor) -> Unit,
    onClickMessageId: (Uuid) -> Unit,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    onAddReaction: ((messageId: Uuid, reaction: String) -> Unit)? = null,
    onShowReactions: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    downloadingFiles: Set<String>,
    onShowMore: (() -> Unit)? = null,
    uploadStatus: UploadStatus? = null,
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
    searchQuery: String = "",
    isCurrentSearchResult: Boolean = false,
    chainCap: Int? = null,
) {
    var popupMode by remember { mutableStateOf(MessagePopupMode.None) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Captures the bubble's measured width so the reaction pill can be capped to
    // it instead of widening the bubble for narrow messages (e.g. "."). Initial
    // value 0 means "no constraint yet" — pill renders unconstrained for one
    // frame then snaps to bubble width on the next composition.
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    val openReactionBar: (() -> Unit)? =
        if (onAddReaction != null && policy.allowInlineReactions && !message.isDeleted) {
            { popupMode = MessagePopupMode.Reaction }
        } else null

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .padding(top = clusterPosition.topSpacing(), bottom = clusterPosition.bottomSpacing()),
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        Row(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The bubble Box reserves 26dp at the bottom for the reaction pill
            // when reactions are present, which drags `CenterVertically` ~13dp
            // below the colored bubble's actual middle. Shift the hover-icons
            // row (and its popup anchor) back up by that half so the icons
            // align with the colored bubble's center, not the bubble+pill.
            val iconsRowYOffset = if (message.reactionPreview != null) (-13).dp else 0.dp
            Row(modifier = Modifier.offset(y = iconsRowYOffset)) {
                if (onMessageInfo != null && isDesktop() && !message.isDeleted) {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { popupMode = MessagePopupMode.Menu },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = stringResource(MR.string.chat_message_options),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                }
                if (onReply != null && isDesktop() && !message.isDeleted) {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { onReply.invoke() },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = stringResource(MR.string.chat_message_reply),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                }
                if (onAddReaction != null && isDesktop() && !message.isDeleted) {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { popupMode = MessagePopupMode.Reaction },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddReaction,
                            contentDescription = stringResource(MR.string.chat_message_reaction),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                }
                if (popupMode != MessagePopupMode.None && !message.isDeleted) {
                    SentMessagePopup(
                        mode = popupMode,
                        message = message,
                        userDefaultReactions = userDefaultReactions,
                        dismissMenu = { popupMode = MessagePopupMode.None },
                        onSelectEmoji = { reaction ->
                            popupMode = MessagePopupMode.None
                            onAddReaction?.invoke(message.id, reaction)
                        },
                        onShowAllEmojis = {
                            popupMode = MessagePopupMode.None
                            showEmojiPicker = true
                        },
                        onMessageInfo = {
                            popupMode = MessagePopupMode.None
                            onMessageInfo?.invoke()
                        },
                        onReply = onReply?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onBattle = onBattle?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onForward = onForward?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onCopy = {
                            popupMode = MessagePopupMode.None
                            scope.launch {
                                clipboardManager.setClipEntry(clipEntryOf(message.content))
                            }
                        },
                        onEdit = onEdit?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onDelete = {
                            popupMode = MessagePopupMode.None
                            onDelete()
                        },
                        onTogglePin = {
                            popupMode = MessagePopupMode.None
                            onTogglePin()
                        },
                        onShare = onShare?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        })
                }
            }
            if (showEmojiPicker) {
                EmojiSelectorDialog(onDismiss = { showEmojiPicker = false }, onEmojiSelected = {
                    showEmojiPicker = false
                    onAddReaction?.invoke(message.id, it)
                })
            }

            Box(
                // Mirror ReceivedMessageBubble's layout: on desktop the outer box
                // wraps the bubble's content width instead of filling, so the
                // hover-revealed icons row (which sits earlier in the parent Row)
                // ends up immediately left of the bubble under Arrangement.End,
                // and the action Popup anchored inside that row is adjacent to
                // the bubble — matching Signal-style behavior. Mobile keeps
                // fillMaxWidth so the combinedClickable target spans the row.
                modifier = if (isMobile()) Modifier.fillMaxWidth()
                else Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = if (isMobile()) {
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                if (onMessageInfo != null) {
                                    popupMode = MessagePopupMode.All
                                }
                            },
                            // Only reaches the padding around the bubble and the typed kinds
                            // that paint their own surface; MessageBubbleRaw owns the rest.
                            onDoubleClick = openReactionBar,
                        )
                    } else Modifier,
                ) {
                    MessageBubbleRaw(
                        modifier = Modifier
                            .padding(bottom = if (message.reactionPreview == null) 0.dp else 26.dp)
                            .onSizeChanged { bubbleWidthPx = it.width },
                        message = message,
                        decryptedFiles = decryptedFiles,
                        liveControls = liveControls,
                        sentByYou = true,
                        currentOdinId = currentOdinId,
                        clusterPosition = clusterPosition,
                        onLongClick = {
                            if (onMessageInfo != null) {
                                haptics.perform(HapticEvent.LongPress)
                                popupMode = MessagePopupMode.All
                            }
                        },
                        onDoubleClick = openReactionBar,
                        onMediaClick = onMediaClick,
                        onClickMessageId = onClickMessageId,
                        onRequestDecryptedFile = onRequestDecryptedFile,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        downloadingFiles = downloadingFiles,
                        onShowMoreClick = onShowMore,
                        isPendingSend = message.isPendingSend,
                        uploadStatus = uploadStatus,
                        replyMessages = replyMessages,
                        searchQuery = searchQuery,
                        isCurrentSearchResult = isCurrentSearchResult,
                        chainCap = chainCap,
                    )
                    message.reactionPreview?.let { reactionSummary ->
                        ReactionList(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 4.dp)
                                .let {
                                    if (bubbleWidthPx > 0)
                                        it.widthIn(max = with(density) { bubbleWidthPx.toDp() })
                                    else it
                                },
                            reactionSummary = reactionSummary,
                            onReactionClick = { onShowReactions?.invoke() },
                            onAddEmoji = onAddReaction?.let { { popupMode = MessagePopupMode.Reaction } },
                            ownReactions = message.ownReactions,
                        )
                    }
                    if (isMobile() && popupMode == MessagePopupMode.Reaction && !message.isDeleted) {
                        BubbleReactionPopup(
                            message = message,
                            userDefaultReactions = userDefaultReactions,
                            alignToBubbleEnd = true,
                            dismissMenu = { popupMode = MessagePopupMode.None },
                            onSelectEmoji = { reaction ->
                                popupMode = MessagePopupMode.None
                                onAddReaction?.invoke(message.id, reaction)
                            },
                            onShowAllEmojis = {
                                popupMode = MessagePopupMode.None
                                showEmojiPicker = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SentMessageBubbleDisplayOnly(
    modifier: Modifier = Modifier,
    message: MessageUiModel
) {
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier = modifier
    ) {
        MessageBubbleRaw(
            modifier = Modifier
                .padding(
                    bottom = if (message.reactionPreview == null) 0.dp
                    else 26.dp
                )
                .onSizeChanged { bubbleWidthPx = it.width },
            message = message,
            decryptedFiles = persistentMapOf(),
            sentByYou = true,
            onMediaClick = {},
            onClickMessageId = {},
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
            downloadingFiles = emptySet(),
            onRequestDecryptedFile = null,
            onLongClick = {},
            displayOnly = true,
        )
        message.reactionPreview?.let { reactionSummary ->
            ReactionList(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp)
                    .let {
                        if (bubbleWidthPx > 0)
                            it.widthIn(max = with(density) { bubbleWidthPx.toDp() })
                        else it
                    },
                reactionSummary = reactionSummary,
                onReactionClick = { },
                ownReactions = message.ownReactions,
            )
        }
    }
}

/**
 * Displays a message bubble for messages received from other users.
 *
 * Shows the message content aligned to the left with appropriate styling for received messages.
 * Includes a hover menu on desktop and long-press animation on mobile for message actions.
 *
 * @param message The message data to display.
 * @param onMessageInfo Callback invoked when user requests message info/details.
 * @param onReply Callback invoked when user wants to reply to this message.
 * @param onDelete Callback invoked when user wants to delete this message.
 * @param onMarkAsRead Callback invoked when user wants to mark this message as read.
 * @param onAddReaction Callback invoked when user wants to add a reaction to this message.
 * @param onShowReactions Callback invoked when user wants to show a list of reactions.
 * @param onMediaClick Callback invoked when user clicks on media attachment.
 * @param sharedTransitionScope The shared transition scope for animations.
 * @param animatedVisibilityScope The animated visibility scope for animations.
 */
@Composable
fun ReceivedMessageBubble(
    message: MessageUiModel,
    liveControls: LiveLocationBubbleControls? = null,
    userDefaultReactions: ImmutableList<String>,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    currentOdinId: String = "",
    renderAuthorName: Boolean = false,
    isGroupConversation: Boolean = false,
    clusterPosition: MessageClusterPosition = MessageClusterPosition.ALONE,
    onMessageInfo: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onBattle: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {},
    onMarkAsRead: () -> Unit,
    onAddReaction: ((messageId: Uuid, reaction: String) -> Unit)? = null,
    onShowReactions: (() -> Unit)? = null,
    onMediaClick: (PayloadDescriptor) -> Unit,
    onClickMessageId: (Uuid) -> Unit,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    downloadingFiles: Set<String>,
    onShowMore: (() -> Unit)? = null,
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
    onBlock: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    searchQuery: String = "",
    isCurrentSearchResult: Boolean = false,
    chainCap: Int? = null,
) {
    var popupMode by remember { mutableStateOf(MessagePopupMode.None) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showReportConfirm by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val filteredPayloads = message.payloads?.filter {
        !listOf(
            ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB,
            ChatProtocol.DefaultPayloadKey,
            ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY
        ).contains(it.key)
    }
    val hasMedia = !filteredPayloads.isNullOrEmpty()
    val mediaOnly = !message.content.hasContent() && hasMedia
    val emojiOnly = message.content.isEmojiContentOnly() && !hasMedia
    val hasVisibleBackground = !mediaOnly && !emojiOnly
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    val openReactionBar: (() -> Unit)? =
        if (onAddReaction != null && policy.allowInlineReactions && !message.isDeleted) {
            { popupMode = MessagePopupMode.Reaction }
        } else null

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = if (isGroupConversation) 8.dp else 16.dp, end = 16.dp)
            .padding(top = clusterPosition.topSpacing(), bottom = clusterPosition.bottomSpacing()),
    ) {
        if (isGroupConversation) {
            val showAvatar = clusterPosition == MessageClusterPosition.ALONE ||
                clusterPosition == MessageClusterPosition.END
            Box(
                modifier = Modifier
                    .align(Alignment.Bottom)
                    .padding(start = 4.dp, end = 8.dp)
                    .size(Dimens.Conversation.itemAvatarSize),
            ) {
                if (showAvatar && message.originalAuthor != null) {
                    val initials = remember(message.displayName) {
                        message.displayName.initials()
                    }
                    PublicAvatar(
                        odinId = message.originalAuthor,
                        initials = initials,
                        options = GroupMessageAvatarOptions,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = if (isMobile()) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            if (onMessageInfo != null) {
                                popupMode = MessagePopupMode.All
                            }
                        },
                        // See SentMessageBubble — MessageBubbleRaw owns the bubble surface.
                        onDoubleClick = openReactionBar,
                    )
                } else Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    val authorNameTxt = message.displayName
                    val authorOdinColor =
                        getOdinIdColor(message.originalAuthor?.domainName ?: "")
                    val isDark = isSystemInDarkTheme()
                    val finalAuthorColor =
                        if (isDark) authorOdinColor.darkTheme else authorOdinColor.lightTheme

                    if (renderAuthorName && !hasVisibleBackground) {
                        Text(
                            text = authorNameTxt,
                            style = MaterialTheme.typography.labelMedium,
                            color = finalAuthorColor,
                            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        MessageBubbleRaw(
                            modifier = Modifier
                                .padding(
                                    bottom = if (message.reactionPreview == null) 0.dp
                                    else 26.dp
                                )
                                .onSizeChanged { bubbleWidthPx = it.width },
                            message = message,
                            decryptedFiles = decryptedFiles,
                        liveControls = liveControls,
                            sentByYou = false,
                            currentOdinId = currentOdinId,
                            clusterPosition = clusterPosition,
                            authorName = if (renderAuthorName && hasVisibleBackground) authorNameTxt
                            else null,
                            authorColor = if (renderAuthorName && hasVisibleBackground) finalAuthorColor
                            else null,
                            onLongClick = {
                                if (onMessageInfo != null) {
                                    haptics.perform(HapticEvent.LongPress)
                                    popupMode = MessagePopupMode.All
                                }
                            },
                            onDoubleClick = openReactionBar,
                            onMediaClick = onMediaClick,
                            onClickMessageId = onClickMessageId,
                            onRequestDecryptedFile = onRequestDecryptedFile,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            downloadingFiles = downloadingFiles,
                            onShowMoreClick = onShowMore,
                            replyMessages = replyMessages,
                            searchQuery = searchQuery,
                            isCurrentSearchResult = isCurrentSearchResult,
                            chainCap = chainCap,
                        )
                        message.reactionPreview?.let { reactionSummary ->
                            ReactionList(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 4.dp)
                                    .let {
                                        if (bubbleWidthPx > 0)
                                            it.widthIn(max = with(density) { bubbleWidthPx.toDp() })
                                        else it
                                    },
                                reactionSummary = reactionSummary,
                                onReactionClick = { onShowReactions?.invoke() },
                                onAddEmoji = onAddReaction?.let { { popupMode = MessagePopupMode.Reaction } },
                                ownReactions = message.ownReactions,
                            )
                        }
                        if (isMobile() && popupMode == MessagePopupMode.Reaction &&
                            !message.isDeleted
                        ) {
                            BubbleReactionPopup(
                                message = message,
                                userDefaultReactions = userDefaultReactions,
                                alignToBubbleEnd = false,
                                dismissMenu = { popupMode = MessagePopupMode.None },
                                onSelectEmoji = { reaction ->
                                    popupMode = MessagePopupMode.None
                                    onAddReaction?.invoke(message.id, reaction)
                                },
                                onShowAllEmojis = {
                                    popupMode = MessagePopupMode.None
                                    showEmojiPicker = true
                                },
                            )
                        }
                    }
                }
            }
            // See SentMessageBubble for rationale — compensates the 26dp pill
            // reservation so hover icons stay centered on the colored bubble.
            val iconsRowYOffset = if (message.reactionPreview != null) (-13).dp else 0.dp
            Row(
                modifier = Modifier.wrapContentWidth().offset(y = iconsRowYOffset),
            ) {
                if (onAddReaction != null && isDesktop() && !message.isDeleted) {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { popupMode = MessagePopupMode.Reaction },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddReaction,
                            contentDescription = stringResource(MR.string.chat_message_reaction),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                }
                if (onReply != null && isDesktop() && !message.isDeleted) {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { onReply() },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = stringResource(MR.string.chat_message_reply),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                }
                if (onMessageInfo != null && isDesktop() && !message.isDeleted) {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { popupMode = MessagePopupMode.Menu },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = stringResource(MR.string.chat_message_options),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                }

                if (popupMode != MessagePopupMode.None && !message.isDeleted) {
                    ReceivedMessagePopup(
                        mode = popupMode,
                        message = message,
                        userDefaultReactions = userDefaultReactions,
                        dismissMenu = { popupMode = MessagePopupMode.None },
                        onSelectEmoji = { reaction ->
                            popupMode = MessagePopupMode.None
                            onAddReaction?.invoke(message.id, reaction)
                        },
                        onShowAllEmojis = {
                            popupMode = MessagePopupMode.None
                            showEmojiPicker = true
                        },
                        onMessageInfo = {
                            popupMode = MessagePopupMode.None
                            onMessageInfo?.invoke()
                        },
                        onReply = onReply?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onBattle = onBattle?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onForward = onForward?.let { orig ->
                            { popupMode = MessagePopupMode.None; orig() }
                        },
                        onCopy = {
                            popupMode = MessagePopupMode.None
                            scope.launch {
                                clipboardManager.setClipEntry(clipEntryOf(message.content))
                            }
                        },
                        onDelete = {
                            popupMode = MessagePopupMode.None
                            onDelete()
                        },
                        onTogglePin = {
                            popupMode = MessagePopupMode.None
                            onTogglePin()
                        },
                        onBlock = {
                            popupMode = MessagePopupMode.None
                            showBlockConfirm = true
                        },
                        onReport = {
                            popupMode = MessagePopupMode.None
                            showReportConfirm = true
                        },
                        )
                }
            }
            if (showEmojiPicker) {
                EmojiSelectorDialog(onDismiss = { showEmojiPicker = false }, onEmojiSelected = {
                    showEmojiPicker = false
                    onAddReaction?.invoke(message.id, it)
                })
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(stringResource(MR.string.chat_message_block_confirm_title)) },
            text = { Text(stringResource(MR.string.chat_message_block_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirm = false
                    onBlock?.invoke()
                }) {
                    Text(stringResource(MR.string.chat_message_block))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            }
        )
    }

    if (showReportConfirm) {
        AlertDialog(
            onDismissRequest = { showReportConfirm = false },
            title = { Text(stringResource(MR.string.chat_message_report_confirm_title)) },
            text = { Text(stringResource(MR.string.chat_message_report_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showReportConfirm = false
                    onReport?.invoke()
                }) {
                    Text(stringResource(MR.string.chat_message_report))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportConfirm = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ReceivedMessageBubbleDisplayOnly(
    modifier: Modifier = Modifier,
    message: MessageUiModel
) {
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier = modifier
    ) {
        MessageBubbleRaw(
            modifier = Modifier
                .padding(
                    bottom = if (message.reactionPreview == null) 0.dp
                    else 26.dp
                )
                .onSizeChanged { bubbleWidthPx = it.width },
            message = message,
            decryptedFiles = persistentMapOf(),
            sentByYou = false,
            onMediaClick = {},
            onClickMessageId = {},
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
            downloadingFiles = emptySet(),
            onRequestDecryptedFile = null,
            onLongClick = {},
            displayOnly = true,
        )
        message.reactionPreview?.let { reactionSummary ->
            ReactionList(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp)
                    .let {
                        if (bubbleWidthPx > 0)
                            it.widthIn(max = with(density) { bubbleWidthPx.toDp() })
                        else it
                    },
                reactionSummary = reactionSummary,
                onReactionClick = { },
                ownReactions = message.ownReactions,
            )
        }
    }
}

val DELIVERY_ICON_SIZE = 12.dp

/**
 * The shared message footer: the (edited-aware) send time, then — for the sender's own, non-deleted
 * message — the [DeliveryStatus] icon. The single source of truth so text, media, and location bubbles
 * render the time + delivery status identically. [contentColor] is the bubble's full content color; it
 * is muted (alpha 0.7) internally for both the text and the icon, matching the regular bubble.
 */
@Composable
fun MessageTimestampFooter(
    infoText: String,
    contentColor: Color,
    showDeliveryStatus: Boolean,
    isPendingSend: Boolean,
    deliveryStatus: Int,
    pendingSince: Instant?,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    if (!visible) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = infoText,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
        )
        if (showDeliveryStatus) {
            Spacer(modifier = Modifier.width(4.dp))
            DeliveryStatus(
                isPendingSend = isPendingSend,
                deliveryStatus = deliveryStatus,
                contentColor = contentColor.copy(alpha = 0.7f),
                pendingSince = pendingSince,
            )
        }
    }
}

@Composable
fun DeliveryStatus(
    isPendingSend: Boolean,
    deliveryStatus: Int,
    contentColor: Color,
    pendingSince: Instant? = null,
) {
    val warning = deliveryFailureTint()
    if (isPendingSend) {
        val stale = pendingSince != null && rememberPendingStale(pendingSince)
        Icon(
            Icons.Default.Alarm,
            contentDescription = stringResource(MR.string.message_sending),
            modifier = Modifier.size(16.dp),
            tint = if (stale) warning else contentColor,
        )
    } else {
        when (deliveryStatus) {
            ChatDeliveryStatus.Failed.value -> {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = stringResource(MR.string.message_send_failed),
                    modifier = Modifier.height(DELIVERY_ICON_SIZE),
                    tint = warning,
                )
            }

            ChatDeliveryStatus.Read.value -> {
                Icon(
                    HomebaseIcons.MessageSentAndRead,
                    contentDescription = stringResource(MR.string.message_read),
                    modifier = Modifier.height(DELIVERY_ICON_SIZE),
                    tint = contentColor,
                )
            }

            ChatDeliveryStatus.Delivered.value -> {
                Icon(
                    HomebaseIcons.MessageSentAndDelivered,
                    contentDescription = stringResource(MR.string.message_delivered),
                    modifier = Modifier.height(DELIVERY_ICON_SIZE),
                    tint = contentColor,)
            }

            ChatDeliveryStatus.Sent.value -> {
                Icon(
                    HomebaseIcons.MessageSent,
                    contentDescription = stringResource(MR.string.message_sent),
                    modifier = Modifier.height(DELIVERY_ICON_SIZE),
                    tint = contentColor,
                )
            }
        }
    }
}

/**
 * True once the message has been pending (un-sent) for at least [threshold]. Computes the
 * answer once for the current [since] value, then schedules a single delay to flip at the
 * threshold — no polling ticker, so it leaves the Compose dispatcher idle while waiting.
 */
@Composable
private fun rememberPendingStale(since: Instant, threshold: Duration = 1.minutes): Boolean {
    var stale by remember(since) { mutableStateOf(Clock.System.now() - since >= threshold) }
    LaunchedEffect(since) {
        if (!stale) {
            val remaining = threshold - (Clock.System.now() - since)
            if (remaining > Duration.ZERO) delay(remaining)
            stale = true
        }
    }
    return stale
}

/**
 * Whether this message body has anything to render as text. Empty, all-blank-lines, and
 * richeditor's `<br>` empty-paragraph artifacts (e.g. a bare `"<br>"` or `"\n<br>"` from an older
 * or other-platform sender that predates the composer normalization) all count as no content, so a
 * media-only or reply-only bubble doesn't paint a stray break (#1104).
 */
fun String.hasContent(): Boolean = stripComposerLineBreakArtifacts().isNotEmpty()

/**
 * Displays a compact preview of the message being replied to, shown inline within the message
 * bubble.
 *
 * This appears at the top of a message bubble when the message is a reply to another message. Shows
 * a vertical accent bar followed by the author's odinId and a truncated preview of the original
 * message content. If a preview thumbnail exists, it displays a small image on the right.
 *
 * @param replyPreview The reply preview data containing author and message details
 * @param sentByYou Whether this reply was sent by the current user (affects theming)
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun InlineReplyPreview(
    replyPreview: ReplyPreview,
    sentByYou: Boolean,
    onClick: () -> Unit,
    replyMessage: MessageUiModel? = null,
    driveId: Uuid? = null,
) {
    val currentOdinId = LocalCurrentOdinId.current
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    // Build HomebaseImageData from the original message's first visual payload (image or video)
    val imageData: HomebaseImageData? = remember(replyPreview, replyMessage, driveId) {
        if (replyMessage == null || driveId == null) return@remember null
        val firstVisualPayload = replyMessage.payloads?.firstOrNull {
            val ct = it.contentType ?: ""
            ct.startsWith("image/") || ct.startsWith("video/") ||
                ct == "application/vnd.apple.mpegurl"
        } ?: return@remember null
        val payloadIv = try {
            firstVisualPayload.iv?.let { Base64.decode(it) }
        } catch (_: Exception) {
            null
        } ?: return@remember null
        HomebaseImageData(
            driveId = driveId,
            fileId = replyMessage.fileId,
            payloadKey = firstVisualPayload.key,
            previewThumbnail = firstVisualPayload.previewThumbnail?.toEmbeddedThumb()
                ?: replyMessage.previewThumbnail
                ?: replyPreview.previewThumbnail,
            requestedSize = ImageSize.THUMB_SMALL,
            isEncrypted = true,
            // Real payload type so a GIF reply preview loads the animated
            // original instead of a never-generated server thumbnail (its
            // preview thumb is WebP).
            payloadContentType = firstVisualPayload.contentType,
            keyHeader = KeyHeader(iv = payloadIv, aesKey = replyMessage.keyHeader.aesKey),
        )
    }

    // Fallback: decode embedded base64 thumbnail if we can't build HomebaseImageData
    val thumbnailBitmap = remember(replyPreview.previewThumbnail, imageData) {
        if (imageData != null) return@remember null
        replyPreview.previewThumbnail?.content?.let { base64Content ->
            try {
                val bytes = Base64.decode(base64Content)
                bytes.decodeToImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    val hasThumb = imageData != null || thumbnailBitmap != null
    val hasImage = hasThumb || replyPreview.previewThumbnail != null

    // Content-type label for media replies (reuses shared logic with ReplyPreviewBar)
    val mediaPayloads = remember(replyMessage?.payloads) {
        replyMessage?.payloads?.filter { payload ->
            payload.key != ChatProtocol.DefaultPayloadKey &&
                !payload.key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
        } ?: emptyList()
    }
    // Strip richeditor's `<br>` empty-paragraph artifacts from the quoted body so a reply to a
    // legacy `<br>` message shows its real text, not a stray break / blank quote (#1104).
    val replyText = remember(replyPreview.message) { replyPreview.message.stripComposerLineBreakArtifacts() }
    val contentLabel = messageContentLabel(
        textContent = replyText,
        isDeleted = replyMessage?.isDeleted ?: false,
        firstPayload = mediaPayloads.firstOrNull(),
        hasMultiplePayloads = mediaPayloads.size > 1,
    )
    // Dispatch on the typed ReplyContext carried on the wire — that's how
    // the renderer knows it's an event reply without looking up the parent.
    // Pre-context senders leave it null; we fall back to a parent-message
    // lookup so old replies still get the chip when the parent is in
    // memory. Future kinds parse as Unknown → default reply preview, no
    // crash.
    val eventStartLocal = when (val ctx = ReplyContext.fromJson(replyPreview.context)) {
        is ReplyContext.Event -> rememberViewerLocalDate(ctx.startUtcMs)
        ReplyContext.Unknown -> null
        null -> {
            val eventDescriptor = (replyMessage?.messageContent as? MessageContent.Event)?.descriptor
            eventDescriptor?.let { rememberEventTimes(it).viewerStartLocal }
        }
    }
    val displayMessage = resolveReplyContentText(
        replyText = replyText,
        contentLabelText = contentLabel?.text,
        hasThumbnail = hasThumb,
        hasMedia = hasImage,
        mediaFallbackLabel = stringResource(MR.string.media),
    )
    val showContentIcon = shouldShowContentIcon(hasThumb, contentLabel?.text)

    // Mirror the link-preview / Signal QuoteView bounded-block pattern: the body
    // is already capped at 80 codepoints on the header (payload-free), so this is
    // a pure presentational choice. Desktop has the horizontal room for a second
    // line; on mobile we keep a single line to stay compact. The author name stays
    // single-line on every platform.
    val replyPreviewMaxLines = if (isDesktop()) 2 else 1

    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 4.dp))
            .background(backgroundColor)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Vertical accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color = contentColor, shape = RoundedCornerShape(2.dp))
        )
        Column(
            modifier = Modifier.weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
                val authorDisplayName = resolveReplyAuthorName(
                    authorOdinId = replyPreview.authorOdinId,
                    currentOdinId = currentOdinId,
                    resolvedDisplayName = replyMessage?.displayName,
                    youLabel = stringResource(MR.string.you),
                )
                Text(
                    text = authorDisplayName,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (displayMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    // Top-align: with the 2-line desktop body the leading
                    // content icon must sit against the first text line, not
                    // center against the whole block. Single-line mobile is
                    // unaffected (Top == Center for one line).
                    Row(verticalAlignment = Alignment.Top) {
                        if (showContentIcon) {
                            contentLabel?.icon?.let { icon ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = contentColor.copy(alpha = 0.7f),
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                        }
                        Text(
                            text = displayMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                            maxLines = replyPreviewMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT),
                        )
                    }
                }
            }
        // Event date badge takes the trailing slot for event replies; it spans
        // the full bubble height and runs flush to the right edge — the parent
        // Row's clip(RoundedCornerShape(...)) carves the chip's right corners
        // to match the bubble, while RectangleShape here keeps the left side
        // flat so the chip reads as part of the bubble.
        if (eventStartLocal != null) {
            EventDateChip(
                local = eventStartLocal,
                compact = true,
                fillHeight = true,
                shape = RectangleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        } else if (imageData != null) {
            HomebaseImage(
                imageData = imageData,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
                contentDescription = stringResource(MR.string.cd_reply_thumbnail),
            )
        } else {
            thumbnailBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(MR.string.cd_reply_thumbnail),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

internal fun MessageClusterPosition.topSpacing(): Dp = when (this) {
    MessageClusterPosition.ALONE -> 6.dp
    MessageClusterPosition.START -> 6.dp
    MessageClusterPosition.MIDDLE -> 1.dp
    MessageClusterPosition.END -> 1.dp
}

internal fun MessageClusterPosition.bottomSpacing(): Dp = when (this) {
    MessageClusterPosition.ALONE -> 6.dp
    MessageClusterPosition.END -> 6.dp
    MessageClusterPosition.START -> 1.dp
    MessageClusterPosition.MIDDLE -> 1.dp
}

@Preview(widthDp = 480, heightDp = 440, )
@Composable
fun SentMessageBubblePreview() {
    HomebaseTheme {
        Surface {
            Column {
                ReceivedMessageBubbleDisplayOnly(
                    message = testMessageUiModel("g"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SentMessageBubbleDisplayOnly(
                        message = testMessageUiModel("Message 😀"),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                ReceivedMessageBubbleDisplayOnly(
                    message = testMessageUiModel("A much longer message that spans multiple lines of text in the bubble"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SentMessageBubbleDisplayOnly(
                        message = testMessageUiModel("A much longer message that spans multiple lines of text in the bubble"),
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 480, heightDp = 240, )
@Composable
fun SentMessageBubblePreviewDark() {
    HomebaseTheme(darkTheme = true) {
        Surface {
            Column {
                ReceivedMessageBubbleDisplayOnly(
                    message = testMessageUiModel("Message somewhat longer 😀"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SentMessageBubbleDisplayOnly(
                        message = testMessageUiModel("Message 😀"),
                    )
                }
            }
        }
    }
}

private fun testMessageUiModel(message: String): MessageUiModel {
    return MessageUiModel(
        id = Uuid.generateV4(),
        globalTransitId = null,
        fileId = Uuid.generateV4(),
        conversationId = Uuid.generateV4(),
        content = message,
        userDate = Clock.System.now(),
        modified = null,
        created = Clock.System.now(),
        originalAuthor = null,
        sender = null,
        displayName = "John Doe",
        keyHeader = KeyHeader(
            iv = ByteArray(16),
            aesKey = SecureByteArray(ByteArray(16))
        ),
        messageAppData = MessageAppData(
            replyPreview = ReplyPreview(
                replyUniqueId = Uuid.generateV4(),
                authorOdinId = "frodo.baggins.demo.rocks",
                message = "Hello World!",
                previewThumbnail = EmbeddedThumb(
                    pixelWidth = 100,
                    pixelHeight = 100,
                    contentType = "image/png",
                    content = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12P4//8/AAMCAQAYnY20AAAAAE5ErkJggg==",
                )
            )
        ),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        hasMore = false,
        versionTag = Uuid.generateV4(),
        isPendingSend = false,
    )
}


