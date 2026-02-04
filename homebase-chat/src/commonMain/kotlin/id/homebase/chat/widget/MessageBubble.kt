package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.formatMessageTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_message_options
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
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
 * @param onStar Callback invoked when user wants to star/favorite this message.
 * @param onDelete Callback invoked when user wants to delete this message
 * @param onMediaClick Callback invoked when user clicks on media attachment.
 */
@Composable
fun SentMessageBubble(
    message: MessageUiModel,
    onMessageInfo: (messageId: Uuid) -> Unit,
    onReply: (messageId: Uuid) -> Unit,
    onStar: (messageId: Uuid) -> Unit,
    onEdit: (messageId: Uuid) -> Unit,
    onDelete: (messageId: Uuid) -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalAlignment = Alignment.End
        ) {
            Row {
                Column {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { showMenu = true },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = stringResource(MR.string.chat_message_options),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                    SentMessageMenu(
                        showMenu = showMenu,
                        dismissMenu = { showMenu = false },
                        onMessageInfo = {
                            showMenu = false
                            onMessageInfo(message.id)
                        },
                        onReply = {
                            showMenu = false
                            onReply(message.id)
                        },
                        onStar = {
                            showMenu = false
                            onStar(message.id)
                        },
                        onEdit = {
                            showMenu = false
                            onEdit(message.id)
                        },
                        onDelete = {
                            showMenu = false
                            onDelete(message.id)
                        },
                    )
                }
                MessageBubble(
                    modifier = Modifier.heightIn(min = 48.dp),
                    text = message.content,
                    timestamp = formatMessageTimestamp(message.created),
                    sentByYou = true,
                    payloads = message.payloads,
                    fileId = message.fileId,
                    previewThumbnail = message.previewThumbnail,
                    onLongClick = { showMenu = true },
                    keyHeader = message.keyHeader,
                    onMediaClick = onMediaClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
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
 * @param onStar Callback invoked when user wants to star/favorite this message.
 * @param onDelete Callback invoked when user wants to delete this message.
 * @param onMarkAsRead Callback invoked when user wants to mark this message as read.
 * @param onAddReaction Callback invoked when user wants to add a reaction to this message.
 * @param onDeleteReaction Callback invoked when user wants to remove a reaction from this message.
 * @param onMediaClick Callback invoked when user clicks on media attachment.
  */
@Composable
fun ReceivedMessageBubble(
    message: MessageUiModel,
    onMessageInfo: (messageId: Uuid) -> Unit,
    onReply: (messageId: Uuid) -> Unit,
    onStar: (messageId: Uuid) -> Unit,
    onDelete: (messageId: Uuid) -> Unit,
    onMarkAsRead: (messageId: Uuid) -> Unit,
    onAddReaction: (messageId: Uuid, reaction: String) -> Unit,
    onDeleteReaction: (messageId: Uuid, reaction: String) -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalAlignment = Alignment.Start,
        ) {
            Row {
                MessageBubble(
                    modifier = Modifier.heightIn(min = 48.dp),
                    text = message.content + "isread: ${message.isRead}",
                    timestamp = formatMessageTimestamp(message.created),
                    sentByYou = false,
                    payloads = message.payloads,
                    fileId = message.fileId,
                    keyHeader = message.keyHeader,
                    previewThumbnail = message.previewThumbnail,
                    onLongClick = { showMenu = true },
                    onMediaClick = onMediaClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                Column {
                    IconButton(
                        modifier = Modifier.alpha(if (isHovered) 1f else 0f),
                        onClick = { showMenu = true },
                        enabled = isHovered
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = stringResource(MR.string.chat_message_options),
                            tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                        )
                    }
                    ReceivedMessageMenu(
                        showMenu = showMenu,
                        dismissMenu = {
                            showMenu = false
                        },
                        onMessageInfo = {
                            showMenu = false
                            onMessageInfo(message.id)
                        },
                        onReply = {
                            showMenu = false
                            onReply(message.id)
                        },
                        onStar = {
                            showMenu = false
                            onStar(message.id)
                        },
                        onDelete = {
                            showMenu = false
                            onDelete(message.id)
                        },
                        onMarkAsRead = {
                            showMenu = false
                            onMarkAsRead(message.id)
                        },
                        onAddReaction = {
                            showMenu = false
                            onAddReaction(message.id, ":heart:")
                        },
                        onDeleteReaction = {
                            showMenu = false
                            onDeleteReaction(message.id, ":heart:")
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
    }
}

/**
 * Core message bubble composable that renders message content with smart layout.
 *
 * Features:
 * - Renders rich HTML text content with proper formatting
 * - Displays media attachments (images, videos, etc.)
 * - Smart timestamp positioning: fits on last line of text when space permits, otherwise creates new line
 * - Long-press animation with spring physics on mobile devices
 * - Gradient overlay on media-only messages for timestamp readability
 * - Different styling for sent vs received messages
 *
 * @param modifier Modifier to be applied to the message bubble surface.
 * @param text The message text content (can be HTML formatted).
 * @param timestamp The formatted timestamp string to display.
 * @param sentByYou Whether this message was sent by the current user (affects styling).
 * @param payloads Optional list of media/file attachments associated with the message.
 * @param fileId The unique identifier for the message file.
 * @param previewThumbnail Optional embedded thumbnail for media preview.
 * @param onLongClick Callback invoked when user performs a long-press on the bubble.
 * @param onMediaClick Callback invoked when user clicks on a media attachment.
 */
@Composable
fun MessageBubble(
    modifier: Modifier = Modifier,
    text: String,
    timestamp: String,
    sentByYou: Boolean,
    payloads: List<PayloadDescriptor>? = null,
    fileId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    keyHeader: KeyHeader,
    onLongClick: () -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val filteredPayloads =
        payloads?.filter {
            !listOf(
                ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB,
                ChatProtocol.DEFAULT_PAYLOAD_KEY,
                ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY
            )
                .contains(it.key)
        }
    val hasMedia = !filteredPayloads.isNullOrEmpty()
    // We store the result of the text layout to know where the last line ends
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val backgroundColor =
        if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (sentByYou) HomebaseTheme.extendedColors.bubbleSentOnSurface
        else MaterialTheme.colorScheme.onSurface

    val pressInteractionSource = remember { MutableInteractionSource() }
    val isPressed by pressInteractionSource.collectIsPressedAsState()

    // Animatable controls the applied scale
    val scaleAnim = remember { Animatable(1f) }
    val isAnimatingLongPress = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Use a spring for smoother, natural motion and avoid tiny abrupt tweens
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy, // less bounce on emulator
        stiffness = Spring.StiffnessLow
    )

    // Keep quick press feedback when not running the long-press animation
    LaunchedEffect(isPressed) {
        if (isAnimatingLongPress.value) return@LaunchedEffect
        if (isPressed) {
            scaleAnim.animateTo(0.96f, animationSpec = springSpec)
        } else {
            scaleAnim.animateTo(1f, animationSpec = springSpec)
        }
    }

    fun handleLongClick() {
        if (isAnimatingLongPress.value) return
        isAnimatingLongPress.value = true
        coroutineScope.launch {
            try {
                scaleAnim.animateTo(0.94f, animationSpec = springSpec)
                onLongClick()
                scaleAnim.animateTo(1f, animationSpec = springSpec)
            } finally {
                isAnimatingLongPress.value = false

            }
        }
    }

    val mediaOnly = text.isEmpty() && hasMedia
    val textState = RichTextState()
    textState.config.listIndent = 0
    textState.setHtml(text)

    val shape =
        RoundedCornerShape(
            topStart = Dimens.Message.cornerRadius,
            topEnd = Dimens.Message.cornerRadius,
            bottomStart = if (!sentByYou && !mediaOnly) 4.dp else Dimens.Message.cornerRadius,
            bottomEnd = if (sentByYou && !mediaOnly) 4.dp else Dimens.Message.cornerRadius,
        )
    Surface(
        modifier =
            modifier
                .clip(shape)
                .ifTrue(isMobile()) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { handleLongClick() },
                        interactionSource = pressInteractionSource,
                        indication = null
                    )
                }
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                },
        shape = shape,
        color = backgroundColor,
    ) {
        if (text.isEmpty() && hasMedia) {
            Box {
                MediaMessage(
                    payloads = filteredPayloads,
                    fileId = fileId,
                    keyHeader = keyHeader,
                    driveId = chatTargetDrive.alias,
                    previewThumbnail = previewThumbnail,
                    onMediaClick = onMediaClick,
                    onMediaLongPress = { _, _ ->
                        handleLongClick()
                    },
                    shape = RoundedCornerShape(Dimens.Message.cornerRadius),
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .align(Alignment.BottomEnd)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f)
                                )
                            )
                        ),
                ) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
        } else
            Column {
                Layout(
                    content = {
                        Column {
                            if (hasMedia) {
                                MediaMessage(
                                    payloads = filteredPayloads,
                                    fileId = fileId,
                                    driveId = chatTargetDrive.alias,
                                    previewThumbnail = previewThumbnail,
                                    onMediaClick = onMediaClick,
                                    keyHeader = keyHeader,
                                    onMediaLongPress = { _, _ ->
                                        handleLongClick()
                                    },
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                            }
                            SelectionContainer {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                ) {
                                    RichText(
                                        state = textState,
                                        onTextLayout = { textLayoutResult = it },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = contentColor
                                    )
                                }
                            }
                        }
                        Text(
                            modifier = Modifier.padding(
                                top = 12.dp,
                                bottom = 12.dp,
                                end = 12.dp,
                                start = 12.dp
                            ),
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }
                ) { measurables, constraints ->
                    val textPlaceable = measurables[0].measure(constraints)
                    val timePlaceable = measurables[1].measure(constraints)

                    val layoutResult = textLayoutResult
                    var totalWidth: Int
                    var totalHeight: Int
                    var timeX: Int
                    var timeY: Int

                    if (layoutResult == null) {
                        // Fallback if layout isn't ready yet
                        totalWidth = textPlaceable.width
                        totalHeight = textPlaceable.height
                        timeX = 0
                        timeY = 0
                    } else {
                        val lastLineIndex = layoutResult.lineCount - 1
                        val lastLineRight = layoutResult.getLineRight(lastLineIndex)

                        // Determine if timestamp fits on the last line
                        // We add a small gap (8dp converted to px) between text and time
                        val horizontalGap = 8.dp.toPx()
                        val fitsOnLastLine =
                            (constraints.maxWidth - lastLineRight) >
                                    (timePlaceable.width + horizontalGap)

                        if (fitsOnLastLine) {
                            // Fits on the same line
                            totalWidth =
                                maxOf(
                                    textPlaceable.width,
                                    (lastLineRight + horizontalGap + timePlaceable.width)
                                        .toInt()
                                )
                            totalHeight = textPlaceable.height
                            timeX = totalWidth - timePlaceable.width
                            timeY = totalHeight - timePlaceable.height
                        } else {
                            // Needs a new line
                            totalWidth = maxOf(textPlaceable.width, timePlaceable.width)
                            totalHeight = textPlaceable.height + timePlaceable.height
                            timeX = totalWidth - timePlaceable.width
                            timeY = totalHeight - timePlaceable.height
                        }
                    }

                    layout(totalWidth, totalHeight) {
                        textPlaceable.placeRelative(0, 0)
                        timePlaceable.placeRelative(timeX, timeY)
                    }
                }
            }
    }
}
