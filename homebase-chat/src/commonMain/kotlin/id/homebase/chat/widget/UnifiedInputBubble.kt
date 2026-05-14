package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getOdinIdColor
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_attachment_options
import id.homebase.resources.chat_message_edit_message
import id.homebase.resources.chat_send_message_button
import org.jetbrains.compose.resources.stringResource

private enum class BubbleFabAction { Confirm, Send, Attach }

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
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = if (editExistingMode || replyToMessage != null)
            Alignment.Bottom else Alignment.CenterVertically,
    ) {
        // Left FAB: Cancel — only in edit mode
        AnimatedVisibility(
            visible = editExistingMode,
            enter = signalExpandHorizontally,
            exit = signalShrinkHorizontally,
        ) {
            Row {
                IconButton(
                    onClick = onCancelEdit,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("cancel_fab"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.cancel),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        // Bubble surface — 20dp radius matching Signal
        Surface(
            modifier = Modifier
                .weight(1f)
                .animateContentSize(
                    animationSpec = tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing),
                ),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column {
                // Reply preview — height reveal matching Signal's ValueAnimator
                AnimatedVisibility(
                    visible = replyToMessage != null,
                    enter = expandVertically(tween(SIGNAL_TRANSITION_MS)),
                    exit = shrinkVertically(tween(SIGNAL_TRANSITION_MS)),
                ) {
                    if (replyToMessage != null) {
                        val odinColor = getOdinIdColor(
                            replyToMessage.originalAuthor?.domainName ?: "",
                        )
                        val resolvedAccent =
                            if (isSystemInDarkTheme()) odinColor.darkTheme else odinColor.lightTheme
                        ReplyPreviewBar(
                            message = replyToMessage,
                            onDismiss = onDismissReply,
                            modifier = Modifier.padding(6.dp),
                            accentColor = resolvedAccent,
                        )
                    }
                }

                // Edit message label
                AnimatedVisibility(
                    visible = editExistingMode,
                    enter = signalFadeIn,
                    exit = signalFadeOut,
                ) {
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

                // Input content slot
                content()
            }
        }

        // Right FAB — fades out during recording, cross-fades between icons
        AnimatedVisibility(
            visible = !isRecordingActive,
            enter = signalFadeIn,
            exit = signalFadeOut,
        ) {
            Row {
                Spacer(modifier = Modifier.width(8.dp))

                val fabAction = when {
                    editExistingMode -> BubbleFabAction.Confirm
                    showSendButton -> BubbleFabAction.Send
                    else -> BubbleFabAction.Attach
                }
                val fabClick = if (fabAction == BubbleFabAction.Attach)
                    onAddAttachmentClick else onSendMessage
                val fabEnabled = when (fabAction) {
                    BubbleFabAction.Attach -> true
                    else -> !isSendingMessage
                }
                val fabTestTag = when (fabAction) {
                    BubbleFabAction.Confirm -> "confirm_fab"
                    BubbleFabAction.Send -> "send_fab"
                    BubbleFabAction.Attach -> "attachment_fab"
                }
                val fabDescription = when (fabAction) {
                    BubbleFabAction.Attach -> stringResource(MR.string.chat_message_attachment_options)
                    else -> stringResource(MR.string.chat_send_message_button)
                }
                IconButton(
                    onClick = fabClick,
                    enabled = fabEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .testTag(fabTestTag),
                ) {
                    AnimatedContent(
                        targetState = fabAction,
                        transitionSpec = { signalToggleIn togetherWith signalToggleOut },
                        label = "fab_icon_toggle",
                    ) { action ->
                        Icon(
                            imageVector = when (action) {
                                BubbleFabAction.Confirm -> Icons.Filled.Check
                                BubbleFabAction.Send -> Icons.AutoMirrored.Filled.Send
                                BubbleFabAction.Attach -> Icons.Default.Add
                            },
                            contentDescription = fabDescription,
                        )
                    }
                }
            }
        }
    }
}
