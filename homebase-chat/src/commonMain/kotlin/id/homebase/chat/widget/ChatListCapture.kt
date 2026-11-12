package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.NO_CONVERSATION
import id.homebase.chat.conversationlist.RecipientModel
import id.homebase.chat.conversationlist.SendToPicker
import id.homebase.chat.conversationlist.isChatListCapture
import id.homebase.chat.conversationlist.matching
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.camera.CameraModes
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.rememberCameraManager
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.chat_send_to
import id.homebase.resources.menu_back
import id.homebase.resources.remove
import id.homebase.resources.search
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

private val SendToMaxWidth = 640.dp

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SendToScreen(
    picker: SendToPicker,
    sending: Boolean,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { onUiAction(ConversationListUiAction.CloseSendTo) }
    val youLabel = stringResource(MR.string.you)
    val query = picker.search.text.toString()
    val visible = remember(picker.recipients, query, youLabel) { picker.recipients.matching(query, youLabel) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_send_to)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ConversationListUiAction.CloseSendTo) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            SendToBar(selected = picker.selected, youLabel = youLabel, sending = sending, onUiAction = onUiAction)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = SendToMaxWidth).fillMaxSize()) {
                StyledSearchTextField(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    textFieldState = picker.search,
                    placeHolderText = stringResource(MR.string.search),
                )
                RecipientsSelectorList(
                    modifier = Modifier.weight(1f),
                    recipientGroups = visible,
                    selectedRecipients = picker.selected,
                    onRecipientSelected = { onUiAction(ConversationListUiAction.SendToSelectRecipient(it)) },
                )
            }
        }
    }
}

@Composable
private fun SendToBar(
    selected: List<RecipientModel>,
    youLabel: String,
    sending: Boolean,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    AnimatedVisibility(
        visible = selected.isNotEmpty(),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.widthIn(max = SendToMaxWidth).fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(selected) { recipient ->
                            RecipientChip(recipient, youLabel) {
                                onUiAction(ConversationListUiAction.SendToSelectRecipient(recipient))
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    FloatingActionButton(
                        onClick = { if (!sending) onUiAction(ConversationListUiAction.ConfirmSendTo) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(MR.string.chat_send_message_button),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientChip(recipient: RecipientModel, youLabel: String, onRemove: () -> Unit) {
    val avatarOptions = AvatarOptions(size = InputChipDefaults.AvatarSize, fontSize = 10.sp)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = {
            Text(
                text = when (recipient) {
                    is RecipientModel.Contact -> recipient.name
                    is RecipientModel.Conversation -> recipient.conversation.getDisplayName(youLabel)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        },
        avatar = {
            when (recipient) {
                is RecipientModel.Contact -> IdentityAvatar(
                    odinId = recipient.contact.odinId,
                    displayName = recipient.contact.name,
                    options = avatarOptions,
                )
                is RecipientModel.Conversation -> ConversationAvatar(
                    avatarModel = recipient.conversation.conversation.avatarModel,
                    options = avatarOptions,
                )
            }
        },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(MR.string.remove),
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
    )
}
