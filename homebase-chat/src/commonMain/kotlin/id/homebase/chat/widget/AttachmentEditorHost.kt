package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.image.MediaQuality
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiAction.CloseFullScreenOverlay
import id.homebase.chat.conversationlist.ConversationListUiAction.SaveFile
import id.homebase.chat.conversationlist.ConversationListUiAction.UnAttachFile
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.isChatListCapture
import id.homebase.chat.viewonce.ViewOnceIntroSheet
import id.homebase.chat.viewonce.ViewOnceToast
import id.homebase.chat.viewonce.rememberViewOnceComposerState
import id.homebase.core.util.toMessageMarkdown
import id.homebase.resources.MR
import id.homebase.resources.cd_send_to
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AttachmentEditorHost(
    data: FullScreenOverlay.AttachmentData,
    mediaQuality: MediaQuality,
    textFieldState: RichTextState,
    revealed: Boolean,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    onAddFile: () -> Unit,
    onAddImage: () -> Unit,
    onCameraClick: () -> Unit,
    onContentShown: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    onSend: (caption: String, viewOnce: Boolean) -> Unit,
) {
    var captionEmojiPickerOpen by remember { mutableStateOf(false) }
    val viewOnceState = rememberViewOnceComposerState()
    val viewOnceEligible = isViewOnceEligible(data.attachments)
    LaunchedEffect(viewOnceEligible) { viewOnceState.onEligibilityChanged(viewOnceEligible) }
    val viewOnce = viewOnceState.requested && viewOnceEligible
    val viewOnceIsVideo = data.attachments.singleOrNull() is AttachmentPendingFile.FileVideo
    MediaAttachmentEditor(
        attachments = data.attachments,
        currentPage = currentPage,
        revealed = revealed,
        onContentShown = onContentShown,
        onPageChanged = onPageChanged,
        onSaveFile = { onUiAction(SaveFile(it)) },
        mediaQuality = mediaQuality,
        onSetMediaQuality = {
            onUiAction(
                ConversationListUiAction.SetMediaQuality(it)
            )
        },
        centerImageInPage = true,
        addMoreEnabled = !viewOnce,
        onAddFile = onAddFile,
        onAddImage = onAddImage,
        onCameraClick = onCameraClick,
        onRemoveFile = { attachmentId ->
            onUiAction(UnAttachFile(data.conversationId, attachmentId))
        },
        onDismiss = { onUiAction(CloseFullScreenOverlay) },
        onCropImage = { attachmentId ->
            onUiAction(
                ConversationListUiAction.RequestCropAttachment(
                    data.conversationId, attachmentId,
                )
            )
        },
        onDrawImage = { attachmentId ->
            onUiAction(
                ConversationListUiAction.RequestDrawAttachment(
                    data.conversationId, attachmentId,
                )
            )
        },
        onTrimChange = { attachmentId, startMs, endMs ->
            onUiAction(
                ConversationListUiAction.ApplyTrimResult(
                    data.conversationId, attachmentId, startMs, endMs,
                )
            )
        },
        aboveStripOverlay = {
            ViewOnceToast(message = viewOnceState.toast, modifier = Modifier.align(Alignment.BottomCenter))
        },
        pagerTopEndSlot = {
            if (!data.isChatListCapture) Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .fillMaxWidth(0.6f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(MR.string.cd_send_to, data.conversationTitle),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = data.conversationTitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        collapseSecondaryChrome = captionEmojiPickerOpen,
        bottomBar = {
            MessageTextFieldForAttachment(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                state = textFieldState,
                onSendMessage = {
                    onSend(textFieldState.toMessageMarkdown(), viewOnce)
                },
                viewOnceToggle = remember(data.attachments, viewOnce, viewOnceState, viewOnceIsVideo, viewOnceEligible) {
                    viewOnceToggleFor(data.attachments, viewOnce) {
                        viewOnceState.toggle(viewOnceIsVideo, viewOnceEligible)
                    }
                },
                onEmojiPickerVisibilityChanged = { captionEmojiPickerOpen = it },
                onPasteImage = { imageBytes ->
                    onUiAction(
                        ConversationListUiAction.AttachClipboardImage(
                            data.conversationId, imageBytes,
                        )
                    )
                },
            )
        },
    )
    if (viewOnceState.showIntro) {
        ViewOnceIntroSheet(isVideo = viewOnceIsVideo, onDismiss = viewOnceState::dismissIntro)
    }
}
