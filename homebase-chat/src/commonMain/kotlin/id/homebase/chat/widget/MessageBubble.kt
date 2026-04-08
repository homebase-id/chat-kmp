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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyPreview
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.time.Clock
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
    userDefaultReactions: ImmutableList<String>,
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
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
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
                    replyMessages = replyMessages,
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

@Composable
fun SentMessageBubbleDisplayOnly(
    modifier: Modifier = Modifier,
    message: MessageUiModel
) {
    Box(
        modifier = modifier
    ) {
        MessageBubbleRaw(
            modifier = Modifier.padding(
                bottom = if (message.reactionPreview == null) 0.dp
                else 26.dp
            ),
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
        )
        message.reactionPreview?.let { reactionSummary ->
            ReactionList(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp),
                reactionSummary = reactionSummary,
                onClick = { },
                onLongClick = { },
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
    userDefaultReactions: ImmutableList<String>,
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
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
    onBlock: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
) {
    var popupMode by remember { mutableStateOf(MessagePopupMode.None) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showReportConfirm by remember { mutableStateOf(false) }
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
                        onShowMoreClick = onShowMore,
                        replyMessages = replyMessages,
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
    Box(
        modifier = modifier
    ) {
        MessageBubbleRaw(
            modifier = Modifier.padding(
                bottom = if (message.reactionPreview == null) 0.dp
                else 26.dp
            ),
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
        )
        message.reactionPreview?.let { reactionSummary ->
            ReactionList(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp),
                reactionSummary = reactionSummary,
                onClick = { },
                onLongClick = { },
            )
        }
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
    onClick: () -> Unit,
    replyMessage: MessageUiModel? = null,
    driveId: Uuid? = null,
) {
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    // Build HomebaseImageData from the original message's first image payload
    val imageData: HomebaseImageData? = remember(replyPreview, replyMessage, driveId) {
        if (replyMessage == null || driveId == null) return@remember null
        val firstImagePayload = replyMessage.payloads?.firstOrNull {
            it.contentType?.startsWith("image/") == true
        } ?: return@remember null
        val payloadIv = try {
            firstImagePayload.iv?.let { Base64.decode(it) }
        } catch (_: Exception) {
            null
        } ?: return@remember null
        HomebaseImageData(
            driveId = driveId,
            fileId = replyMessage.fileId,
            payloadKey = firstImagePayload.key,
            previewThumbnail = firstImagePayload.previewThumbnail?.toEmbeddedThumb()
                ?: replyMessage.previewThumbnail
                ?: replyPreview.previewThumbnail,
            requestedSize = ImageSize.THUMB_SMALL,
            isEncrypted = true,
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

    val hasImage = imageData != null || replyPreview.previewThumbnail != null
    val message = if (hasImage && replyPreview.message.isEmpty()) stringResource(MR.string.media) else replyPreview.message

    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 15.dp, bottomEnd = 4.dp, bottomStart = 4.dp))
            .background(backgroundColor)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Vertical accent bar
        Row {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(color = Color.White, shape = RoundedCornerShape(2.dp))
            )
            Column(
                modifier = Modifier.weight(1f, fill = false)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(
                    text = replyPreview.authorOdinId,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 2
                )
            }
        }
        // Thumbnail image — prefer HomebaseImage, fall back to embedded bitmap
        if (imageData != null) {
            Row {
                Spacer(modifier = Modifier.width(8.dp))
                HomebaseImage(
                    imageData = imageData,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        } else {
            thumbnailBitmap?.let { bitmap ->
                Row {
                    Spacer(modifier = Modifier.width(8.dp))
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
    }
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


