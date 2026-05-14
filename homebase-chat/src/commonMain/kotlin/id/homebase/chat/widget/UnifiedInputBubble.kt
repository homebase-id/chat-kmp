package id.homebase.chat.widget

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.getOdinIdColor
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_attachment_options
import id.homebase.resources.chat_message_edit_message
import id.homebase.resources.chat_send_message_button
import org.jetbrains.compose.resources.stringResource

@Composable
fun UnifiedInputBubble(
    replyToMessage: MessageUiModel?,
    onDismissReply: () -> Unit,
    editExistingMode: Boolean,
    showSendButton: Boolean,
    isRecordingActive: Boolean,
    isSendingMessage: Boolean = false,
    onSendMessage: () -> Unit,
    onCancelEdit: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (editExistingMode) {
            IconButton(
                onClick = onCancelEdit,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.testTag("cancel_fab"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.cancel),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .animateContentSize(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                if (replyToMessage != null) {
                    val resolvedAccent = if (accentColor == Color.Unspecified) {
                        val odinColor = getOdinIdColor(
                            replyToMessage.originalAuthor?.domainName ?: "",
                        )
                        if (isSystemInDarkTheme()) odinColor.darkTheme else odinColor.lightTheme
                    } else {
                        accentColor
                    }
                    ReplyPreviewBar(
                        message = replyToMessage,
                        onDismiss = onDismissReply,
                        modifier = Modifier.padding(6.dp),
                        accentColor = resolvedAccent,
                    )
                }

                if (editExistingMode) {
                    Row(
                        modifier = Modifier
                            .padding(start = 12.dp, top = 8.dp, end = 12.dp)
                            .testTag("edit_message_label"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = stringResource(MR.string.chat_message_edit_message),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                content()
            }
        }

        if (!isRecordingActive) {
            Spacer(modifier = Modifier.width(8.dp))
            when {
                editExistingMode -> {
                    BlueBackgroundIconButton(
                        onClick = onSendMessage,
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(MR.string.chat_send_message_button),
                        enabled = !isSendingMessage,
                        testTag = "confirm_fab",
                    )
                }
                showSendButton -> {
                    BlueBackgroundIconButton(
                        onClick = onSendMessage,
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(MR.string.chat_send_message_button),
                        enabled = !isSendingMessage,
                        testTag = "send_fab",
                    )
                }
                else -> {
                    BlueBackgroundIconButton(
                        onClick = onAddAttachmentClick,
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.string.chat_message_attachment_options),
                        testTag = "attachment_fab",
                    )
                }
            }
        }
    }
}
