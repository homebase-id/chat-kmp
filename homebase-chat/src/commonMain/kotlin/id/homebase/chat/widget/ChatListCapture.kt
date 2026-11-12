package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.NO_CONVERSATION
import id.homebase.chat.conversationlist.SendToPicker
import id.homebase.chat.conversationlist.isChatListCapture
import id.homebase.chat.conversationlist.matching
import id.homebase.core.camera.CameraModes
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.chat_send_to
import id.homebase.resources.menu_back
import id.homebase.resources.you
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.stringResource

internal class ChatListCapture(
    val openCamera: () -> Unit,
    val addImages: () -> Unit,
    val addFile: () -> Unit,
)

@Composable
internal fun rememberChatListCapture(onUiAction: (ConversationListUiAction) -> Unit): ChatListCapture {
    val gallery = rememberFilePickerLauncher(type = FileKitType.ImageAndVideo, mode = FileKitMode.Multiple()) { files ->
        files?.takeIf { it.isNotEmpty() }?.let {
            onUiAction(ConversationListUiAction.AttachPlatformFile(NO_CONVERSATION, it, isImage = true))
        }
    }
    val file = rememberFilePickerLauncher { picked ->
        picked?.let { onUiAction(ConversationListUiAction.AttachPlatformFile(NO_CONVERSATION, listOf(it))) }
    }
    val camera = rememberCameraManager(
        modes = CameraModes.PhotoAndVideo,
        onOpenGallery = { gallery.launch() },
        awaitResultShown = true,
    ) { captured ->
        captured?.let {
            onUiAction(ConversationListUiAction.AttachPlatformFile(NO_CONVERSATION, listOf(it), isImage = true))
        }
    }
    return remember(gallery, file, camera) {
        ChatListCapture(openCamera = { camera.launch() }, addImages = { gallery.launch() }, addFile = { file.launch() })
    }
}

@Composable
fun ChatListCaptureOverlay(
    messagesUiState: MessageListUiState,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    val editor = (messagesUiState.fullScreenOverlay as? FullScreenOverlay.AttachmentData)
        ?.takeIf { it.isChatListCapture } ?: return
    val capture = rememberChatListCapture(onUiAction)
    var page by remember { mutableIntStateOf(0) }
    val caption = remember { RichTextState().applyDefaultStyling() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            AttachmentEditorHost(
                data = editor,
                mediaQuality = messagesUiState.mediaQuality,
                textFieldState = caption,
                revealed = true,
                currentPage = page,
                onPageChanged = { page = it },
                onAddFile = capture.addFile,
                onAddImage = capture.addImages,
                onCameraClick = capture.openCamera,
                onUiAction = onUiAction,
                onSend = { text, viewOnce -> onUiAction(ConversationListUiAction.OpenSendTo(text, viewOnce)) },
            )
            messagesUiState.sendTo?.let { picker ->
                SendToScreen(picker = picker, sending = messagesUiState.isSendingMessage, onUiAction = onUiAction)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SendToScreen(
    picker: SendToPicker,
    sending: Boolean,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { onUiAction(ConversationListUiAction.CloseSendTo) }
    val youLabel = stringResource(MR.string.you)
    val query = picker.search.text.toString()
    val visible = remember(picker.recipients, query, youLabel) { picker.recipients.matching(query, youLabel) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onUiAction(ConversationListUiAction.CloseSendTo) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                }
            }
            RecipientPicker(
                title = stringResource(MR.string.chat_send_to),
                search = picker.search,
                recipientGroups = visible,
                selectedRecipients = picker.selected,
                listModifier = Modifier.weight(1f),
                onRecipientSelected = { onUiAction(ConversationListUiAction.SendToSelectRecipient(it)) },
            )
            if (picker.selected.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = picker.selected.joinToString { it.name }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        BlueBackgroundIconButton(
                            onClick = { onUiAction(ConversationListUiAction.ConfirmSendTo) },
                            enabled = !sending,
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(MR.string.chat_send_message_button),
                        )
                    }
                }
            }
        }
    }
}
