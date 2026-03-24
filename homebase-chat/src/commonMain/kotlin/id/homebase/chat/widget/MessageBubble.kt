package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.MessageSent
import id.homebase.core.ui.assets.MessageSentAndDelivered
import id.homebase.core.ui.assets.MessageSentAndRead
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getOdinIdColor
import id.homebase.core.util.isDesktop
import id.homebase.core.util.isEmojiContentOnly
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.core.widget.ReactionList
import id.homebase.resources.MR
import id.homebase.resources.chat_message_options
import id.homebase.resources.chat_message_reaction
import id.homebase.resources.chat_message_reply
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

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
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    onMessageInfo: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    onClickMessageId: (Uuid) -> Unit,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    onAddReaction: ((messageId: Uuid, reaction: String) -> Unit)? = null,
    onShowReactions: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    downloadingFiles: Set<String>,
    onShowMore: (() -> Unit)? = null,
    uploadStatus: UploadStatus? = null,
) {
    var popupMode by remember { mutableStateOf(MessagePopupMode.None) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        Row(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                if (onMessageInfo != null && isDesktop()) {
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
                if (onReply != null && isDesktop()) {
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
                if (onAddReaction != null && isDesktop()) {
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
                if (popupMode != MessagePopupMode.None) {
                    SentMessagePopup(
                        mode = popupMode,
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
                        onReply = {
                            popupMode = MessagePopupMode.None
                            onReply?.invoke()
                        },
                        onForward = {
                            popupMode = MessagePopupMode.None
                            onForward?.invoke()
                        },
                        onCopy = {
                            popupMode = MessagePopupMode.None
                            scope.launch {
                                clipboardManager.setClipEntry(clipEntryOf(message.content))
                            }
                        },
                        onEdit = {
                            popupMode = MessagePopupMode.None
                            onEdit?.invoke()
                        },
                        onDelete = {
                            popupMode = MessagePopupMode.None
                            onDelete()
                        },
                        onShare = {
                            popupMode = MessagePopupMode.None
                            onShare()
                        })
                }
            }
            if (showEmojiPicker) {
                EmojiSelectorDialog(onDismiss = { showEmojiPicker = false }, onEmojiSelected = {
                    showEmojiPicker = false
                    onAddReaction?.invoke(message.id, it)
                })
            }

            Box {
                MessageBubbleRaw(
                    modifier = Modifier.padding(bottom = if (message.reactionPreview == null) 0.dp else 26.dp),
                    message = message,
                    decryptedFiles = decryptedFiles,
                    sentByYou = true,
                    onLongClick = {
                        if (onMessageInfo != null) {
                            popupMode = MessagePopupMode.All
                        }
                    },
                    onMediaClick = onMediaClick,
                    onClickMessageId = onClickMessageId,
                    onRequestDecryptedFile = onRequestDecryptedFile,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    downloadingFiles = downloadingFiles,
                    onShowMoreClick = onShowMore,
                    isPendingSend = message.isPendingSend,
                    uploadStatus = uploadStatus,
                )
                message.reactionPreview?.let { reactionSummary ->
                    ReactionList(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp),
                        reactionSummary = reactionSummary,
                        onClick = { onAddReaction?.invoke(message.id, it) },
                        onLongClick = { onShowReactions() },
                    )
                }
            }
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
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    renderAuthorName: Boolean = false,
    onMessageInfo: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onMarkAsRead: () -> Unit,
    onAddReaction: ((messageId: Uuid, reaction: String) -> Unit)? = null,
    onShowReactions: () -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    onClickMessageId: (Uuid) -> Unit,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    downloadingFiles: Set<String>,
    onShowMore: (() -> Unit)? = null,
) {
    var popupMode by remember { mutableStateOf(MessagePopupMode.None) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
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

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                val authorNameTxt = message.displayName
                val authorOdinColor = getOdinIdColor(message.originalAuthor?.domainName ?: "")
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
                    )
                }
                Box {
                    MessageBubbleRaw(
                        modifier = Modifier.padding(
                            bottom = if (message.reactionPreview == null) 0.dp
                            else 26.dp
                        ),
                        message = message,
                        decryptedFiles = decryptedFiles,
                        sentByYou = false,
                        authorName = if (renderAuthorName && hasVisibleBackground) authorNameTxt
                        else null,
                        authorColor = if (renderAuthorName && hasVisibleBackground) finalAuthorColor
                        else null,
                        onLongClick = {
                            if (onMessageInfo != null) {
                                popupMode = MessagePopupMode.All
                            }
                        },
                        onMediaClick = onMediaClick,
                        onClickMessageId = onClickMessageId,
                        onRequestDecryptedFile = onRequestDecryptedFile,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        downloadingFiles = downloadingFiles,
                        onShowMoreClick = onShowMore
                    )
                    message.reactionPreview?.let { reactionSummary ->
                        ReactionList(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp),
                            reactionSummary = reactionSummary,
                            onClick = { onAddReaction?.invoke(message.id, it) },
                            onLongClick = { onShowReactions() },
                        )
                    }
                }
            }
            Row {
                if (onAddReaction != null && isDesktop()) {
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
                if (onReply != null && isDesktop()) {
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
                if (onMessageInfo != null && isDesktop()) {
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

                if (popupMode != MessagePopupMode.None) {
                    ReceivedMessagePopup(
                        mode = popupMode,
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
                        onReply = {
                            popupMode = MessagePopupMode.None
                            onReply?.invoke()
                        },
                        onForward = {
                            popupMode = MessagePopupMode.None
                            onForward?.invoke()
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
}

val DELIVERY_ICON_SIZE = 12.dp

@Composable
fun DeliveryStatus(isPendingSend: Boolean, deliveryStatus: Int) {
    if (isPendingSend) {
        Icon(
            Icons.Default.Alarm,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
    } else {
        when (deliveryStatus) {
            ChatDeliveryStatus.Read.value -> {
                Icon(
                    HomebaseIcons.MessageSentAndRead,
                    contentDescription = null,
                    modifier = Modifier.height(DELIVERY_ICON_SIZE)
                )
            }

            ChatDeliveryStatus.Delivered.value -> {
                Icon(
                    HomebaseIcons.MessageSentAndDelivered,
                    contentDescription = null,
                    modifier = Modifier.height(DELIVERY_ICON_SIZE)
                )
            }

            ChatDeliveryStatus.Sent.value -> {
                Icon(
                    HomebaseIcons.MessageSent,
                    contentDescription = null,
                    modifier = Modifier.height(DELIVERY_ICON_SIZE)
                )
            }
        }
    }
}

fun String.hasContent(): Boolean {
    if (this.isBlank()) return false
    if (this.lines().all { it.isBlank() }) return false
    if (this == "<br>") return false
    return true
}

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
    onClick: () -> Unit
) {
    val accentColor = if (sentByYou) {
        HomebaseTheme.extendedColors.bubbleSentOnSurface.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val contentColor = if (sentByYou) {
        HomebaseTheme.extendedColors.bubbleSentOnSurface.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }

    // Decode preview thumbnail if available
    val thumbnailBitmap = remember(replyPreview.previewThumbnail) {
        replyPreview.previewThumbnail?.content?.let { base64Content ->
            try {
                val bytes = Base64.decode(base64Content)
                bytes.decodeToImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Row(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical accent bar
        Box(
            modifier = Modifier.width(3.dp).heightIn(min = 24.dp)
                .background(color = accentColor, shape = RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = replyPreview.authorOdinId,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 1
            )
            Text(
                text = replyPreview.message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                maxLines = 2
            )
        }
        // Thumbnail image if available
        thumbnailBitmap?.let { bitmap ->
            Spacer(modifier = Modifier.width(8.dp))
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}
