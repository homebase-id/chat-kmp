package id.homebase.chat.widget

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.formatMessageTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_message_options
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

@Composable
fun SentMessageBubble(
        message: MessageUiModel,
        onMessageInfo: (messageId: Uuid) -> Unit,
        onReply: (messageId: Uuid) -> Unit,
        onStar: (messageId: Uuid) -> Unit,
        onEdit: (messageId: Uuid) -> Unit,
        onDeleteForMe: (messageId: Uuid) -> Unit,
        onDeleteForEveryone: (messageId: Uuid) -> Unit,
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
                            onDeleteForMe = {
                                showMenu = false
                                onDeleteForMe(message.id)
                            },
                            onDeleteForEveryone = {
                                showMenu = false
                                onDeleteForEveryone(message.id)
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
                )
            }
        }
    }
}

@Composable
fun ReceivedMessageBubble(
    message: MessageUiModel,
    onMessageInfo: (messageId: Uuid) -> Unit,
    onReply: (messageId: Uuid) -> Unit,
    onStar: (messageId: Uuid) -> Unit,
    onDeleteForMe: (messageId: Uuid) -> Unit,
    onMarkAsRead: (messageId: Uuid) -> Unit,
    onAddReaction: (messageId: Uuid, reaction: String) -> Unit,
    onDeleteReaction: (messageId: Uuid, reaction: String) -> Unit,
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
                        text = message.content,
                        timestamp = formatMessageTimestamp(message.created),
                        sentByYou = false,
                        payloads = message.payloads,
                        fileId = message.fileId,
                        previewThumbnail = message.previewThumbnail,
                        onLongClick = { showMenu = true },
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
                        onDeleteForMe = {
                            showMenu = false
                            onDeleteForMe(message.id)
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

@Composable
fun MessageBubble(
        modifier: Modifier = Modifier,
        text: String,
        timestamp: String,
        sentByYou: Boolean,
        payloads: List<PayloadDescriptor>? = null,
        fileId: Uuid,
        previewThumbnail: EmbeddedThumb? = null,
        onLongClick: () -> Unit,
        onMediaClick: ((PayloadDescriptor) -> Unit)? = null,
        onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)? = null,
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

    val textState = RichTextState()
    textState.config.listIndent = 0
    textState.setHtml(text)

    val shape =
            RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (!sentByYou) 4.dp else 18.dp,
                    bottomEnd = if (sentByYou) 4.dp else 18.dp,
            )
    Surface(
            modifier =
                    modifier.clip(shape).ifTrue(isMobile()) {
                        Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                    },
            shape = shape,
            color = backgroundColor,
    ) {
        Column {
            // Render media attachments if present
            if (hasMedia) {
                MediaMessage(
                        payloads = filteredPayloads,
                        fileId = fileId,
                        driveId = chatTargetDrive.alias,
                        previewThumbnail = previewThumbnail,
                        onMediaClick = onMediaClick,
                        onMediaLongPress = onMediaLongPress,
                )
            }

            // Render text content with timestamp
            Layout(
                    modifier = Modifier.padding(12.dp),
                    content = {
                        SelectionContainer {
                            Row {
                                RichText(
                                        state = textState,
                                        onTextLayout = { textLayoutResult = it },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = contentColor
                                )
                            }
                        }
                        Text(
                                modifier = Modifier.padding(top = 16.dp),
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
