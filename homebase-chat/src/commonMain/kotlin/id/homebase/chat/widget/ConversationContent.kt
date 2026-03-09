package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.RecordingData
import id.homebase.chat.data.ConversationUiModel
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.util.keyboardAsState
import id.homebase.core.util.programmaticBackspace
import id.homebase.core.util.rememberCameraManager
import id.homebase.core.widget.EmojiSelectorSheet
import id.homebase.core.widget.EmojiSummary
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.resources.MR
import id.homebase.resources.chat_no_messages
import id.homebase.resources.chat_options
import id.homebase.resources.menu_back
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConversationContent(
    conversation: ConversationUiModel,
    uiState: MessageListUiState,
    textFieldState: RichTextState,
    recordingData: RecordingData?,
    listState: LazyListState,
    isScrollPositionReady: Boolean,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var showConversationMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isKeyboardVisible by keyboardAsState()
    var wasKeyboardVisible by remember { mutableStateOf(isKeyboardVisible) }

    // Add this state to track keyboard height
    var keyboardHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime

    val imeVisible by remember { derivedStateOf { imeInsets.getBottom(density) > 0 } }

    // Add a LaunchedEffect to listen for keyboard changes
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            // Keyboard is shown
            keyboardHeight = with(density) { imeInsets.getBottom(density).toDp() }
            // Add any other logic you need when keyboard appears
        } else {
            // Keyboard is hidden
            // Add any logic you need when keyboard disappears
        }
    }

    DisposableEffect(conversation.id) {
        focusManager.clearFocus()
        keyboardController?.hide()

        onDispose {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LaunchedEffect(conversation.id) {
        kotlinx.coroutines.delay(50) // Small delay to ensure composition is complete
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    @Suppress("DEPRECATION")
    BackHandler(showEmojiSheet || showAttachmentSheet || isKeyboardVisible || uiState.isEditingMessageId != null) {
        showEmojiSheet = false
        showAttachmentSheet = false
        keyboardController?.hide()
        onUiAction(ConversationListUiAction.CancelEditMessage)
    }

    val cameraLauncher = rememberCameraManager { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.id,
                    files = listOf(file),
                    isImage = true,
                )
            )
        }
    }
    val fileLauncher = rememberFilePickerLauncher { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.id,
                    files = listOf(file),
                )
            )
        }
    }
    val galleryLauncher =
        rememberFilePickerLauncher(type = FileKitType.ImageAndVideo) { file ->
            file?.let {
                onUiAction(
                    ConversationListUiAction.AttachPlatformFile(
                        conversationId = conversation.id,
                        files = listOf(file),
                        isImage = true,
                    )
                )
            }
        }

    uiState.messageReactions?.let {
        EmojiSummary(it, onDismiss = { onUiAction(ConversationListUiAction.HideReactionDetails) })
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConversationAvatar(
                            modifier = Modifier.focusable(), // to avoid textfield focus
                            avatarModel = conversation.avatarModel,
                            options =
                                AvatarOptions(
                                    size = 32.dp,
                                    fontSize = 12.sp,
                                    onClick = {
                                        onUiAction(
                                            ConversationListUiAction.ShowConversationSettings(
                                                conversation
                                            )
                                        )
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = conversation.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }, navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = stringResource(MR.string.menu_back)
                            )
                        }
                    }
                }, actions = {
                    IconButton(onClick = { showConversationMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(MR.string.chat_options)
                        )
                    }
                    ConversationMenu(
                        showMenu = showConversationMenu,
                        dismissMenu = { showConversationMenu = false },
                        isGroup = conversation.isGroupConversation,
                        onConversationInfo = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.ShowConversationSettings(
                                    conversation
                                )
                            )
                        },
                        onDelete = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.DeleteConversation(
                                    conversation.id
                                )
                            )
                        },
                        onArchive = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.ArchiveConversation(
                                    conversation.id
                                )
                            )
                        },
                        onClear = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.ClearConversation(
                                    conversation.id
                                )
                            )
                        },
                        onIntroduceEveryone = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.IntroduceEveryone(
                                    conversation.id
                                )
                            )
                        }
                    )
                }, colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .consumeWindowInsets(innerPadding).imePadding()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            if (isScrollPositionReady) {
                Box(
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        state = listState,
                        contentPadding = PaddingValues(
                            top = 24.dp,
                            bottom = 24.dp,
                        )
                    ) {
                        item {
                            AvatarNameDisplay(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                                displayName = conversation.name,
                                avatarModel = conversation.avatarModel,
                            )
                        }
                        if (conversation.isGroupConversation) {
                            item {
                                GroupMemberNamesCard(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                        .padding(bottom = 16.dp),
                                    // TODO - how to get list of nice display names
                                    participantNames =
                                        conversation
                                            .participants
                                            .filter { it.domainName != uiState.ownerSession?.odinId?.domainName }
                                            .map { it.domainName }
                                            .toPersistentList(),
                                )
                            }
                        }
                        if (uiState.messages.isEmpty()) {
                            item { EmptyListItem(stringResource(MR.string.chat_no_messages)) }
                        }
                        items(uiState.messages, key = { message -> message.id }) { messageItem ->
                            when (messageItem) {
                                is MessageListContentModel.Section -> {
                                    MessagesSection(text = getDateSectionLabel(messageItem.date))
                                }

                                is MessageListContentModel.Message -> {
                                    MessageItem(
                                        message = messageItem.message,
                                        currentOdinId = uiState.ownerSession?.odinId?.domainName ?: "",
                                        renderAuthorName = conversation.isGroupConversation,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        sharedTransitionScope = sharedTransitionScope,
                                        onUiAction = onUiAction,
                                        downloadingFiles = uiState.downloadingFiles
                                    )
                                }
                            }
                        }
                    }
                    HomebaseVerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        state = listState
                    )
                }
            }
            Surface(shadowElevation = 8.dp, tonalElevation = 0.dp) {
                Column(modifier = Modifier.animateContentSize()) {
                    uiState.replyToMessage?.let { msg ->
                        ReplyPreviewBar(
                            message = msg, onDismiss = {
                                onUiAction(ConversationListUiAction.CancelReplyToMessage)
                            })
                    }
                    MessageInputBar(
                        textFieldState = textFieldState,
                        recordingData = recordingData,
                        focusRequester = focusRequester,
                        editExistingMode = uiState.isEditingMessageId != null,
                        showingEmojiSheet = showEmojiSheet,
                        onSendMessage = { text, linkPreview ->
                            if (text.isNotBlank()) {
                                if (uiState.isEditingMessageId != null) {
                                    onUiAction(
                                        ConversationListUiAction.EditMessageSave
                                    )
                                } else {
                                    onUiAction(
                                        ConversationListUiAction.SendMessage(
                                            conversationId = conversation.id,
                                            linkPreview = linkPreview,
                                        )
                                    )
                                }
                            }
                        },
                        onEmojiClick = {
                            showAttachmentSheet = false
                            if (showEmojiSheet && !isKeyboardVisible) {
                                showEmojiSheet = false
                                if (wasKeyboardVisible) {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            } else {
                                if (isKeyboardVisible) {
                                    wasKeyboardVisible = true
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                } else {
                                    wasKeyboardVisible = false
                                }
                                showEmojiSheet = true
                            }
                        },
                        onKeyboardClick = {
                            showEmojiSheet = false
                            showAttachmentSheet = false
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        onFocused = {
                            showEmojiSheet = false
                            showAttachmentSheet = false
                        },
                        onAddAttachmentClick = {
                            showEmojiSheet = false
                            if (showAttachmentSheet && !isKeyboardVisible) {
                                showAttachmentSheet = false
                                if (wasKeyboardVisible) {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            } else {
                                if (isKeyboardVisible) {
                                    wasKeyboardVisible = true
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                } else {
                                    wasKeyboardVisible = false
                                }
                                showAttachmentSheet = true
                            }
                        },
                        onCameraClick = { cameraLauncher.launch() },
                        onRecordingStarted = { onUiAction(ConversationListUiAction.StartRecording) },
                        onRecordingStopped = { onUiAction(ConversationListUiAction.StopRecording) },
                        onRecordingCancelled = { onUiAction(ConversationListUiAction.CancelRecording) },
                        onRecordingHelp = { onUiAction(ConversationListUiAction.ShowRecordingHelp) },
                        onCancelEdit = { onUiAction(ConversationListUiAction.CancelEditMessage) }
                    )

                    EmojiSelectorSheet(
                        modifier = Modifier.height(keyboardHeight.coerceAtLeast(300.dp)),
                        visible = showEmojiSheet,
                        onBackSpace = { textFieldState.programmaticBackspace() },
                        onEmojiSelected = { textFieldState.addTextAfterSelection(it) })

                    AttachmentOptionsDisplay(
                        modifier = Modifier.height(keyboardHeight.coerceAtLeast(300.dp)),
                        visible = showAttachmentSheet && !isKeyboardVisible,
                    ) {
                        AttachmentGallery(
                            onImageSelected = {
                                showAttachmentSheet = false
                                onUiAction(
                                    ConversationListUiAction.AttachGalleryItem(
                                        conversationId = conversation.id, files = listOf(it)
                                    )
                                )
                                // Handle image selection
                            },
                        )
                        AttachmentOptions(onGalleryClick = {
                            showAttachmentSheet = false
                            galleryLauncher.launch()
                        }, onFileClick = {
                            showAttachmentSheet = false
                            fileLauncher.launch()
                        }, onContactClick = {
                            showAttachmentSheet = false
                            // Handle camera
                        }, onLocationClick = {
                            showAttachmentSheet = false
                            // Handle location
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun getDateSectionLabel(messageDate: LocalDate): String {
    val timezone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timezone).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)

    return when (messageDate) {
        today -> stringResource(MR.string.time_today)
        yesterday -> stringResource(MR.string.time_yesterday)
        else -> {
            val format = LocalDate.Format {
                monthName(MonthNames.ENGLISH_ABBREVIATED)
                char(' ')
                day()
            }
            messageDate.format(format)
        }
    }
}
