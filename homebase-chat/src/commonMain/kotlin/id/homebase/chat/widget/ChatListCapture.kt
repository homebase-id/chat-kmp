package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.NO_CONVERSATION
import id.homebase.chat.conversationlist.RecipientModel
import id.homebase.chat.conversationlist.RecipientType
import id.homebase.chat.conversationlist.SendToPicker
import id.homebase.chat.conversationlist.isChatListCapture
import id.homebase.chat.conversationlist.matching
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.camera.CameraModes
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.cd_file_attachment
import id.homebase.resources.cd_image_attachment
import id.homebase.resources.cd_not_selected
import id.homebase.resources.cd_selected
import id.homebase.resources.cd_video_thumbnail
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.chat_send_to
import id.homebase.resources.chat_send_to_count
import id.homebase.resources.chat_send_to_hint
import id.homebase.resources.clear_input
import id.homebase.resources.contacts
import id.homebase.resources.groups
import id.homebase.resources.menu_back
import id.homebase.resources.recents
import id.homebase.resources.remove
import id.homebase.resources.search
import id.homebase.resources.you
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject


internal class ChatListCapture(
    val openCamera: () -> Unit,
    val cameraResultShown: () -> Unit,
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
        ChatListCapture(openCamera = { camera.launch() }, cameraResultShown = camera::resultShown, addImages = { gallery.launch() }, addFile = { file.launch() })
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
                onContentShown = capture.cameraResultShown,
                onUiAction = onUiAction,
                onSend = { text, viewOnce -> onUiAction(ConversationListUiAction.OpenSendTo(text, viewOnce)) },
            )
            messagesUiState.sendTo?.let { picker ->
                SendToScreen(
                    picker = picker,
                    attachments = editor.attachments,
                    sending = messagesUiState.isSendingMessage,
                    onUiAction = onUiAction,
                )
            }
        }
    }
}

private val SendToMaxWidth = 640.dp
private val RowShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SendToScreen(
    picker: SendToPicker,
    attachments: List<AttachmentPendingFile>,
    sending: Boolean,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { onUiAction(ConversationListUiAction.CloseSendTo) }
    val youLabel = stringResource(MR.string.you)
    val query = picker.search.text.toString()
    val visible = remember(picker.recipients, query, youLabel) { picker.recipients.matching(query, youLabel) }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = SendToMaxWidth).fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_send_to)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ConversationListUiAction.CloseSendTo) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
            SendToSearchField(state = picker.search, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                visible.forEach { group ->
                    if (group.recipientType != RecipientType.You) {
                        item {
                            Text(
                                text = group.recipientType.header(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                    }
                    items(group.recipients) { recipient ->
                        SendToRecipientRow(
                            recipient = recipient,
                            youLabel = youLabel,
                            selected = recipient in picker.selected,
                            onToggle = { onUiAction(ConversationListUiAction.SendToSelectRecipient(recipient)) },
                        )
                    }
                }
            }
            SendToBar(
                selected = picker.selected,
                attachments = attachments,
                youLabel = youLabel,
                sending = sending,
                onUiAction = onUiAction,
            )
        }
    }
}

@Composable
private fun RecipientType.header(): String = when (this) {
    RecipientType.You -> stringResource(MR.string.you)
    RecipientType.Recents -> stringResource(MR.string.recents)
    RecipientType.Contacts -> stringResource(MR.string.contacts)
    RecipientType.Groups -> stringResource(MR.string.groups)
}

@Composable
private fun SendToSearchField(state: TextFieldState, modifier: Modifier) {
    TextField(
        state = state,
        modifier = modifier.height(56.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        lineLimits = TextFieldLineLimits.SingleLine,
        placeholder = { Text(stringResource(MR.string.search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (state.text.isNotEmpty()) {
                IconButton(onClick = { state.clearText() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.clear_input))
                }
            }
        },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

private fun RecipientModel.label(youLabel: String): String = when (this) {
    is RecipientModel.Contact -> name
    is RecipientModel.Conversation -> conversation.getDisplayName(youLabel)
}

@Composable
private fun RecipientAvatar(recipient: RecipientModel, options: AvatarOptions) {
    when (recipient) {
        is RecipientModel.Contact -> IdentityAvatar(
            odinId = recipient.contact.odinId,
            displayName = recipient.contact.name,
            options = options,
        )
        is RecipientModel.Conversation -> ConversationAvatar(
            avatarModel = recipient.conversation.conversation.avatarModel,
            options = options,
        )
    }
}

@Composable
private fun SendToRecipientRow(recipient: RecipientModel, youLabel: String, selected: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.secondaryContainer else Color.Transparent)
    val supporting = when (recipient) {
        is RecipientModel.Contact -> recipient.contact.odinId.domainName
        is RecipientModel.Conversation -> recipient.conversation.conversation.avatarModel.odinId?.domainName
    }
    ListItem(
        modifier = Modifier.clip(RowShape).toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        colors = ListItemDefaults.colors(containerColor = container),
        leadingContent = {
            val avatar = AvatarOptions(size = 40.dp, fontSize = 15.sp)
            // The default avatar fill is secondaryContainer, which would vanish into the selected row.
            RecipientAvatar(
                recipient,
                if (selected) avatar.copy(containerColor = colors.surfaceContainerLowest, contentColor = colors.onSecondaryContainer) else avatar,
            )
        },
        headlineContent = { Text(recipient.label(youLabel), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(MR.string.cd_selected), tint = colors.primary)
            } else {
                Icon(Icons.Outlined.Circle, contentDescription = stringResource(MR.string.cd_not_selected), tint = colors.outline)
            }
        },
    )
}

@Composable
private fun SendToBar(
    selected: List<RecipientModel>,
    attachments: List<AttachmentPendingFile>,
    youLabel: String,
    sending: Boolean,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AttachmentPreview(attachments)
            AnimatedContent(
                targetState = selected.isEmpty(),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentAlignment = Alignment.CenterStart,
            ) { empty ->
                if (empty) {
                    Text(
                        text = stringResource(MR.string.chat_send_to_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    RecipientChips(selected, youLabel, onUiAction)
                }
            }
            AnimatedVisibility(visible = selected.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!sending) onUiAction(ConversationListUiAction.ConfirmSendTo) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.padding(start = 8.dp),
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
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(MR.string.chat_send_to_count, selected.size))
                }
            }
        }
    }
}

@Composable
private fun RecipientChips(selected: List<RecipientModel>, youLabel: String, onUiAction: (ConversationListUiAction) -> Unit) {
    val state = rememberLazyListState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LazyRow(
        state = state,
        modifier = Modifier.fadeEdges(start = state.canScrollBackward, end = state.canScrollForward, rtl = rtl),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(selected) { recipient ->
            RecipientChip(recipient, youLabel) { onUiAction(ConversationListUiAction.SendToSelectRecipient(recipient)) }
        }
    }
}

private fun Modifier.fadeEdges(start: Boolean, end: Boolean, rtl: Boolean): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = 24.dp.toPx()
        val left = if (rtl) end else start
        val right = if (rtl) start else end
        if (left) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = fade), blendMode = BlendMode.DstIn)
        if (right) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun AttachmentPreview(attachments: List<AttachmentPendingFile>) {
    val first = attachments.firstOrNull() ?: return
    val imageLoader: ImageLoader = koinInject()
    val (icon, description, model) = when (first) {
        is AttachmentPendingFile.FileImage -> Triple(Icons.Outlined.Image, MR.string.cd_image_attachment, first.file)
        is AttachmentPendingFile.Gallery -> Triple(Icons.Outlined.Image, MR.string.cd_image_attachment, null)
        is AttachmentPendingFile.FileVideo -> Triple(Icons.Outlined.Videocam, MR.string.cd_video_thumbnail, first.thumbnailBytes)
        is AttachmentPendingFile.File -> Triple(Icons.AutoMirrored.Outlined.InsertDriveFile, MR.string.cd_file_attachment, null)
        is AttachmentPendingFile.Audio -> Triple(Icons.Outlined.Mic, MR.string.cd_file_attachment, null)
    }
    val count = attachments.size.toString()
    BadgedBox(
        badge = { if (attachments.size > 1) Badge { Text(count) } },
        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = stringResource(description), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model != null) {
                AsyncImage(
                    model = model,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
private fun RecipientChip(recipient: RecipientModel, youLabel: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = {
            Text(
                text = recipient.label(youLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        },
        avatar = { RecipientAvatar(recipient, AvatarOptions(size = InputChipDefaults.AvatarSize, fontSize = 10.sp)) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(MR.string.remove),
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
    )
}
