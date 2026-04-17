package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.profile.PublicProfileProvider
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiSheet
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.RecipientGroupModel
import id.homebase.chat.conversationlist.RecipientModel
import id.homebase.chat.conversationlist.RecipientType
import id.homebase.chat.conversationlist.RecordingData
import id.homebase.chat.createconversation.ContactItem
import id.homebase.chat.createconversation.GroupOrConversationItem
import id.homebase.chat.data.ConversationState
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.OneOnOneConnectionStatus
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.util.isDesktop
import id.homebase.core.util.isWeb
import id.homebase.core.util.keyboardAsState
import id.homebase.core.util.programmaticBackspace
import id.homebase.core.util.rememberCameraManager
import id.homebase.core.util.rememberVideoRecorderManager
import id.homebase.core.widget.EmojiSelectorSheet
import id.homebase.core.widget.EmojiSummary
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_group_not_connected_disclaimer
import id.homebase.resources.chat_group_rejoin_accept
import id.homebase.resources.chat_group_rejoin_decline
import id.homebase.resources.chat_group_rejoin_pending_description
import id.homebase.resources.chat_group_you_left
import id.homebase.resources.chat_group_you_were_removed
import id.homebase.resources.chat_message_block
import id.homebase.resources.chat_message_block_confirm_body
import id.homebase.resources.chat_message_block_confirm_title
import id.homebase.resources.chat_message_forward_to
import id.homebase.resources.chat_message_search_no_results
import id.homebase.resources.chat_message_search_result_count
import id.homebase.resources.chat_next_result
import id.homebase.resources.chat_no_messages
import id.homebase.resources.chat_not_connected_description
import id.homebase.resources.chat_not_connected_incoming_description
import id.homebase.resources.chat_not_connected_outgoing_description
import id.homebase.resources.chat_not_connected_review_request
import id.homebase.resources.chat_not_connected_send_request
import id.homebase.resources.chat_not_connected_view_request
import id.homebase.resources.chat_note_to_self
import id.homebase.resources.chat_options
import id.homebase.resources.chat_previous_result
import id.homebase.resources.chat_scroll_to_bottom
import id.homebase.resources.chat_search_placeholder
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.connect
import id.homebase.resources.contacts
import id.homebase.resources.groups
import id.homebase.resources.menu_back
import id.homebase.resources.recents
import id.homebase.resources.search
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import id.homebase.resources.you
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConversationContent(
    conversation: EnrichedConversationUiModel,
    uiState: MessageListUiState,
    textFieldState: RichTextState,
    searchTextState: TextFieldState,
    recordingData: RecordingData?,
    listState: LazyListState,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val focusRequester = remember { FocusRequester() }
    val focusRequesterSearch = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var showConversationMenu by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isKeyboardVisible by keyboardAsState()
    var wasKeyboardVisible by remember { mutableStateOf(isKeyboardVisible) }
    val coroutineScope = rememberCoroutineScope()
    var showScrollToBottom by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            focusRequesterSearch.requestFocus()
        }
    }

    LaunchedEffect(uiState.currentSearchResultIndex, uiState.searchResultMessageIds) {
        val focusedId = uiState.searchResultMessageIds.getOrNull(uiState.currentSearchResultIndex)
            ?: return@LaunchedEffect
        val idx = uiState.messages.indexOfFirst {
            it is MessageListContentModel.Message && it.message.id == focusedId
        }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) return@snapshotFlow false
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@snapshotFlow false
            lastVisibleIndex < totalItems - 1
        }.collect { showScrollToBottom = it }
    }

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

    // On iOS, UITextView can auto-become first responder during initial
    // composition, briefly flashing the keyboard and shifting the layout.
    // Block focus until the first frame has been laid out.  On Android/Desktop
    // the flag starts true so behaviour is unchanged.
    val needsFocusGuard = !isDesktop() && !isWeb()   // only mobile (iOS + Android)
    var inputFocusable by remember(conversation.conversation.id) {
        mutableStateOf(!needsFocusGuard)
    }

    if (needsFocusGuard) {
        LaunchedEffect(conversation.conversation.id) {
            // One frame is enough for Compose to finish layout; 150 ms covers
            // the iOS first-responder race without a noticeable typing delay.
            kotlinx.coroutines.delay(150)
            inputFocusable = true
        }
    }

    DisposableEffect(conversation.conversation.id) {
        focusManager.clearFocus()

        onDispose {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // Build a lookup map of reply target ID -> MessageUiModel for reply image thumbnails.
    // Only includes messages that are actually referenced by a reply, to avoid O(n) map of all messages.
    val replyMessages = remember(uiState.messages) {
        val allMessages = uiState.messages.filterIsInstance<MessageListContentModel.Message>()
        val replyTargetIds = allMessages.mapNotNullTo(mutableSetOf()) {
            it.message.messageAppData.replyPreview?.replyUniqueId
        }
        if (replyTargetIds.isEmpty()) {
            persistentMapOf()
        } else {
            allMessages.filter { it.message.id in replyTargetIds }
                .associate { it.message.id to it.message }
                .toPersistentMap()
        }
    }

    @Suppress("DEPRECATION") BackHandler(showEmojiSheet || showAttachmentSheet || isKeyboardVisible || uiState.isEditingMessageId != null) {
        showEmojiSheet = false
        showAttachmentSheet = false
        keyboardController?.hide()
        onUiAction(ConversationListUiAction.CancelEditMessage)
    }

    val cameraLauncher = rememberCameraManager { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                    isImage = true,
                )
            )
        }
    }
    val videoRecorderLauncher = rememberVideoRecorderManager { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                    isImage = false,
                )
            )
        }
    }
    val fileLauncher = rememberFilePickerLauncher { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                )
            )
        }
    }
    val galleryLauncher = rememberFilePickerLauncher(type = FileKitType.ImageAndVideo) { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                    isImage = true,
                )
            )
        }
    }

    ConversationContentSheets(
        uiState = uiState,
        onUiAction = onUiAction,
    )

    uiState.messageReactions?.let {
        EmojiSummary(it, onDismiss = { onUiAction(ConversationListUiAction.HideReactionDetails) })
    }

    if (showBlockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            title = { Text(stringResource(MR.string.chat_message_block_confirm_title)) },
            text = { Text(stringResource(MR.string.chat_message_block_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirmDialog = false
                    conversation.conversation.participants.firstOrNull()?.let { participant ->
                        onUiAction(ConversationListUiAction.BlockUser(participant))
                    }
                }) {
                    Text(stringResource(MR.string.chat_message_block))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = !uiState.isSearchActive,
                            enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
                            exit = fadeOut(animationSpec = tween(150))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(
                                    interactionSource = MutableInteractionSource(),
                                    indication = null
                                ) {
                                    onUiAction(
                                        ConversationListUiAction.ShowConversationSettings(
                                            conversation.conversation
                                        )
                                    )
                                }
                            ) {
                                Spacer(modifier = Modifier.width(8.dp))
                                ConversationAvatar(
                                    modifier = Modifier.focusable(), // to avoid textfield focus
                                    avatarModel = conversation.conversation.avatarModel,
                                    options = AvatarOptions(size = 32.dp, fontSize = 12.sp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = if (conversation.conversation.isWithSelf) stringResource(
                                            MR.string.chat_note_to_self
                                        )
                                        else conversation.getDisplayName(youLabel = stringResource(MR.string.you)),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth()
                                .padding(end = 16.dp),
                            visible = uiState.isSearchActive,
                            enter = fadeIn(animationSpec = tween(200)) + expandHorizontally(
                                animationSpec = tween(300), expandFrom = Alignment.End
                            ),
                            exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(
                                animationSpec = tween(250), shrinkTowards = Alignment.End
                            )
                        ) {
                            MinimalSearchTextField(
                                textFieldState = searchTextState,
                                modifier = Modifier.fillMaxWidth()
                                    .focusRequester(focusRequesterSearch),
                                placeHolderText = stringResource(
                                    MR.string.chat_search_placeholder
                                ),
                                showBackButton = true,
                                onBackButtonClick = {
                                    onUiAction(
                                        ConversationListUiAction.SearchMessagesBackClicked
                                    )
                                    searchTextState.clearText()
                                })
                        }
                    }
                },
                navigationIcon = {
                    if (showBackButton && !uiState.isSearchActive) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = stringResource(MR.string.menu_back)
                            )
                        }
                    }
                },
                actions = {
                    if (!uiState.isSearchActive) {
                        IconButton(onClick = { showConversationMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(MR.string.chat_options)
                            )
                        }
                        ConversationMenu(
                            showMenu = showConversationMenu,
                            dismissMenu = { showConversationMenu = false },
                            isGroup = conversation.conversation.isGroupConversation,
                            isArchived = conversation.conversation.conversationState == ConversationState.Archived,
                            isPinned = conversation.conversation.isPinned,
                            onConversationInfo = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.ShowConversationSettings(
                                        conversation.conversation
                                    )
                                )
                            },
                            onSearch = {
                                showConversationMenu = false
                                onUiAction(ConversationListUiAction.SearchMessagesClicked)
                            },
                            onDelete = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.DeleteConversation(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onTogglePin = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.TogglePinConversation(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onArchive = {
                                showConversationMenu = false
                                if (conversation.conversation.conversationState == ConversationState.Archived) {
                                    onUiAction(
                                        ConversationListUiAction.UnarchiveConversation(
                                            conversation.conversation.id
                                        )
                                    )
                                } else {
                                    onUiAction(
                                        ConversationListUiAction.ArchiveConversation(
                                            conversation.conversation.id
                                        )
                                    )
                                }
                            },
                            onClear = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.ClearConversation(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onIntroduceEveryone = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.IntroduceEveryone(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onBlock = if (!conversation.conversation.isGroupConversation && !conversation.conversation.isWithSelf) {
                                {
                                    showConversationMenu = false
                                    showBlockConfirmDialog = true
                                }
                            } else null,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .clipToBounds()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .offset {
                        val imeHeight = imeInsets.getBottom(this)
                        val sheetOffset = if (imeHeight > 0) {
                            imeHeight
                        } else if (showEmojiSheet || showAttachmentSheet) {
                            keyboardHeight.coerceAtLeast(300.dp).roundToPx()
                        } else {
                            0
                        }
                        IntOffset(0, -sheetOffset)
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
            if (conversation.conversation.isGroupConversation && conversation.missingConnections.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)
                ) {
                    Text(
                        stringResource(MR.string.chat_group_not_connected_disclaimer),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ElevatedButton(
                        onClick = {
                            onUiAction(ConversationListUiAction.ConnectIdentities(conversation.missingConnections))
                        }) {
                        Text(stringResource(MR.string.connect))
                    }
                }
            }

            val realMessageIds = remember(uiState.messages) {
                uiState.messages
                    .filterIsInstance<MessageListContentModel.Message>()
                    .mapTo(HashSet()) { it.message.id }
            }
            val pendingForConvo = remember(uiState.pendingOutgoing, realMessageIds, conversation.conversation.id) {
                uiState.pendingOutgoing.filter {
                    it.conversationId == conversation.conversation.id &&
                            it.id !in realMessageIds
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (!uiState.isLoadingMessages) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        state = listState,
                        contentPadding = PaddingValues(
                            top = 24.dp,
                            bottom = 24.dp,
                        )
                    ) {
                        items(uiState.messages, key = { message -> message.id }) { messageItem ->
                            when (messageItem) {
                                is MessageListContentModel.Header -> {
                                    Column {
                                        AvatarNameDisplay(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .padding(bottom = 16.dp),
                                            displayName = if (conversation.conversation.isWithSelf) stringResource(
                                                MR.string.chat_note_to_self
                                            ) else conversation.getDisplayName(youLabel = stringResource(MR.string.you)),
                                            avatarModel = conversation.conversation.avatarModel,
                                            onClick = {
                                                onUiAction(
                                                    ConversationListUiAction.ShowConversationSettings(
                                                        conversation.conversation
                                                    )
                                                )
                                            }
                                        )

                                        if (conversation.conversation.isGroupConversation) {
                                            GroupMemberNamesCard(
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(horizontal = 16.dp)
                                                    .padding(bottom = 16.dp),
                                                // TODO - how to get list of nice display names
                                                participantNames =
                                                    conversation
                                                        .participants
                                                        .filter { it.odinId != uiState.ownerSession?.odinId }
                                                        .map { it.name }
                                                        .toPersistentList(),
                                            )
                                        }
                                    }
                                }

                                is MessageListContentModel.Section -> {
                                    MessagesSection(text = getDateSectionLabel(messageItem.date))
                                }

                                is MessageListContentModel.System -> {
                                    MessagesSystemMessage(text = messageItem.text)
                                }

                                is MessageListContentModel.Message -> {
                                    val isFocused = uiState.searchResultMessageIds.getOrNull(
                                        uiState.currentSearchResultIndex
                                    ) == messageItem.message.id
                                    MessageItem(
                                        message = messageItem.message,
                                        userDefaultReactions = uiState.userDefaultReactions,
                                        decryptedFiles = uiState.decryptedFiles,
                                        currentOdinId = uiState.ownerSession?.odinId?.domainName
                                            ?: "",
                                        renderAuthorName = conversation.conversation.isGroupConversation,
                                        isGroupConversation = conversation.conversation.isGroupConversation,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        sharedTransitionScope = sharedTransitionScope,
                                        onUiAction = onUiAction,
                                        downloadingFiles = uiState.downloadingFiles,
                                        uploadStatus = uiState.uploadProgress[messageItem.message.id],
                                        replyMessages = replyMessages,
                                        searchQuery = uiState.searchQuery,
                                        isCurrentSearchResult = isFocused,
                                    )
                                }
                            }
                        }
                        items(pendingForConvo, key = { "pending-${it.id}" }) { pending ->
                            PendingMessageBubble(
                                message = pending,
                                uploadStatus = uiState.uploadProgress[pending.id],
                            )
                        }
                        // If only one message item (the header) show no messages info
                        if (uiState.messages.size == 1 && pendingForConvo.isEmpty()) {
                            item { EmptyListItem(stringResource(MR.string.chat_no_messages)) }
                        }
                    }
                    HomebaseVerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        state = listState
                    )

                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp),
                    ) {
                        AnimatedVisibility(
                            visible = showScrollToBottom,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(MR.string.chat_scroll_to_bottom),
                                )
                            }
                        }
                    }

                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            AnimatedVisibility(
                visible = uiState.isSearchActive,
                enter = fadeIn() + expandVertically(animationSpec = tween(200)),
                exit = fadeOut() + shrinkVertically(animationSpec = tween(150)),
            ) {
                Surface(
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val resultCount = uiState.searchResultMessageIds.size
                        Text(
                            text = if (uiState.searchQuery.isEmpty()) {
                                ""
                            } else if (resultCount == 0) {
                                stringResource(MR.string.chat_message_search_no_results)
                            } else {
                                stringResource(
                                    MR.string.chat_message_search_result_count,
                                    resultCount - uiState.currentSearchResultIndex,
                                    resultCount,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onUiAction(ConversationListUiAction.SearchMessagesNavigatePrevious) },
                            enabled = uiState.currentSearchResultIndex > 0,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(MR.string.chat_previous_result),
                            )
                        }
                        IconButton(
                            onClick = { onUiAction(ConversationListUiAction.SearchMessagesNavigateNext) },
                            enabled = uiState.currentSearchResultIndex < uiState.searchResultMessageIds.size - 1,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(MR.string.chat_next_result),
                            )
                        }
                    }
                }
            }

            Surface(shadowElevation = 8.dp, tonalElevation = 0.dp) {
                if (conversation.conversation.conversationState == ConversationState.Left) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_group_you_left),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (conversation.conversation.conversationState == ConversationState.Removed) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_group_you_were_removed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (conversation.conversation.conversationState == ConversationState.RejoinPending) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_group_rejoin_pending_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ElevatedButton(onClick = {
                                onUiAction(ConversationListUiAction.AcceptRejoin(conversation.conversation.id))
                            }) {
                                Text(stringResource(MR.string.chat_group_rejoin_accept))
                            }
                            ElevatedButton(onClick = {
                                onUiAction(ConversationListUiAction.DeclineRejoin(conversation.conversation.id))
                            }) {
                                Text(stringResource(MR.string.chat_group_rejoin_decline))
                            }
                        }
                    }
                } else if (conversation.oneOnOneConnectionStatus is OneOnOneConnectionStatus.NotConnected) {
                    val status = conversation.oneOnOneConnectionStatus
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_not_connected_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ElevatedButton(onClick = {
                            onUiAction(
                                ConversationListUiAction.OpenSendConnectionRequestDialog(
                                    status.otherOdinId
                                )
                            )
                        }) {
                            Text(stringResource(MR.string.chat_not_connected_send_request))
                        }
                    }
                } else if (conversation.oneOnOneConnectionStatus is OneOnOneConnectionStatus.OutgoingRequestPending) {
                    val status = conversation.oneOnOneConnectionStatus
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_not_connected_outgoing_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ElevatedButton(onClick = {
                            onUiAction(
                                ConversationListUiAction.OpenConnectionRequestInOwnerConsole(
                                    status.otherOdinId
                                )
                            )
                        }) {
                            Text(stringResource(MR.string.chat_not_connected_view_request))
                        }
                    }
                } else if (conversation.oneOnOneConnectionStatus is OneOnOneConnectionStatus.IncomingRequestPending) {
                    val status = conversation.oneOnOneConnectionStatus
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_not_connected_incoming_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ElevatedButton(onClick = {
                            onUiAction(
                                ConversationListUiAction.OpenConnectionRequestInOwnerConsole(
                                    status.otherOdinId
                                )
                            )
                        }) {
                            Text(stringResource(MR.string.chat_not_connected_review_request))
                        }
                    }
                } else if (!uiState.isSearchActive) {
                    Column(
                        modifier = Modifier.animateContentSize()
                            .focusProperties { canFocus = inputFocusable }) {
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
                            isSendingMessage = uiState.isSendingMessage,
                            onSendMessage = { text, linkPreview ->
                                if (text.isNotBlank()) {
                                    if (uiState.isEditingMessageId != null) {
                                        onUiAction(
                                            ConversationListUiAction.EditMessageSave
                                        )
                                    } else {
                                        onUiAction(
                                            ConversationListUiAction.SendMessage(
                                                conversationId = conversation.conversation.id,
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
                            onVideoRecordClick = { videoRecorderLauncher.launch() },
                            onRecordingStarted = {
                                onUiAction(
                                    ConversationListUiAction.StartRecording(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onRecordingStopped = { onUiAction(ConversationListUiAction.StopRecording) },
                            onRecordingCancelled = { onUiAction(ConversationListUiAction.CancelRecording) },
                            onRecordingHelp = { onUiAction(ConversationListUiAction.ShowRecordingHelp) },
                            onPasteImage = { imageBytes ->
                                onUiAction(
                                    ConversationListUiAction.AttachClipboardImage(
                                        conversationId = conversation.conversation.id,
                                        imageBytes = imageBytes,
                                    )
                                )
                            },
                            onCancelEdit = { onUiAction(ConversationListUiAction.CancelEditMessage) })
                    }
                } // else (not Left)
            }
            }

            // Sheets live outside the offset Column so they sit flush at the screen
            // bottom without double-counting the offset.
            // Each sheet applies its modifier to its inner Column, not to the root
            // AnimatedVisibility, so we wrap in a Box to anchor them at the bottom.
            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                EmojiSelectorSheet(
                    modifier = Modifier.fillMaxWidth()
                        .height(keyboardHeight.coerceAtLeast(300.dp)),
                    visible = showEmojiSheet,
                    onBackSpace = { textFieldState.programmaticBackspace() },
                    onEmojiSelected = { textFieldState.addTextAfterSelection(it) })
            }

            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                AttachmentOptionsDisplay(
                    modifier = Modifier.fillMaxWidth()
                        .height(keyboardHeight.coerceAtLeast(300.dp)),
                    visible = showAttachmentSheet && !isKeyboardVisible,
                ) {
                AttachmentGallery(
                    onImageSelected = {
                        showAttachmentSheet = false
                        onUiAction(
                            ConversationListUiAction.AttachGalleryItem(
                                conversationId = conversation.conversation.id,
                                files = listOf(it)
                            )
                        )
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
                }, onLocationClick = {
                    showAttachmentSheet = false
                })
                }
            } // AttachmentOptionsDisplay wrapper Box
        } // Box (clipToBounds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationContentSheets(
    uiState: MessageListUiState,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    val youLabel = stringResource(MR.string.you)
    when (val sheet = uiState.uiSheet) {
        null -> {}
        is MessageListUiSheet.ConnectIdentities -> {
            val sheetState = rememberModalBottomSheetState()
            val scrollState = rememberScrollState()

            ModalBottomSheet(
                onDismissRequest = { onUiAction(ConversationListUiAction.DismissSheet) },
                sheetState = sheetState
            ) {
                // Bottom sheet content
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(scrollState)
                ) {
                    sheet.identities.forEach { odinId ->
                        val profileProvider = koinInject<PublicProfileProvider>()
                        var resolvedName by remember(odinId) { mutableStateOf(odinId.domainName) }

                        LaunchedEffect(odinId) {
                            try {
                                resolvedName = profileProvider.getPublicProfile(odinId).name
                            } catch (_: Exception) {
                            }
                        }

                        ContactItem(
                            name = resolvedName,
                            odinId = odinId,
                            avatarInitials = resolvedName.take(2).uppercase(),
                            onContactClick = {
                                onUiAction(ConversationListUiAction.ConnectToIdentity(odinId))
                            },
                        )
                    }
                }
            }
        }

        is MessageListUiSheet.ForwardMessage -> {
            val sheetState = rememberModalBottomSheetState()
            val isSearching = sheet.searchTextState.text.isNotEmpty()
            val scope = rememberCoroutineScope()

            val filteredRecipients = remember(sheet.searchTextState.text) {
                if (isSearching) {
                    val query = sheet.searchTextState.text.toString()
                    sheet.recipients.mapNotNull { group ->
                        val matchingRecipients = group.recipients.filter { recipient ->
                            when (recipient) {
                                is RecipientModel.Contact -> recipient.contact.name.contains(
                                    query,
                                    ignoreCase = true
                                )
                                        || recipient.contact.odinId.domainName.contains(
                                    query,
                                    ignoreCase = true
                                )

                                is RecipientModel.Conversation -> recipient.conversation.getDisplayName(youLabel = youLabel)
                                    .contains(query, ignoreCase = true)
                            }
                        }
                        if (matchingRecipients.isEmpty()) null
                        else group.copy(recipients = matchingRecipients)
                    }
                } else {
                    sheet.recipients
                }
            }

            ModalBottomSheet(
                onDismissRequest = { onUiAction(ConversationListUiAction.DismissSheet) },
                sheetState = sheetState,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(MR.string.chat_message_forward_to),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    StyledSearchTextField(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        textFieldState = sheet.searchTextState,
                        showSearchIcon = false,
                        placeHolderText = stringResource(MR.string.search),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    RecipientsSelectorList(
                        modifier = Modifier.weight(1f),
                        recipientGroups = filteredRecipients,
                        selectedRecipients = sheet.selectedRecipients,
                        onRecipientSelected = {
                            onUiAction(ConversationListUiAction.ForwardMessageSelectRecipient(it))
                            scope.launch {
                                sheetState.expand()
                            }
                        }
                    )
                    AnimatedVisibility(
                        visible = sheet.selectedRecipients.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sheet.selectedRecipients.joinToString { it.name },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                BlueBackgroundIconButton(
                                    onClick = {
                                        onUiAction(
                                            ConversationListUiAction.ForwardMessageSend(
                                                sheet.message,
                                                sheet.selectedRecipients
                                            )
                                        )
                                    },
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(MR.string.chat_send_message_button),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecipientsSelectorList(
    modifier: Modifier = Modifier,
    recipientGroups: List<RecipientGroupModel>,
    selectedRecipients: List<RecipientModel>,
    onRecipientSelected: (RecipientModel) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
    ) {
        recipientGroups.forEach { group ->
            if (group.recipientType != RecipientType.You) {
                item {
                    Text(
                        text = group.recipientType.translatedName(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
            items(group.recipients) { recipient ->
                RecipientItem(
                    recipientModel = recipient,
                    isSelected = selectedRecipients.contains(recipient),
                    onRecipientSelected = {
                        onRecipientSelected(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun RecipientType.translatedName(): String {
    return when (this) {
        RecipientType.You -> ""
        RecipientType.Recents -> stringResource(MR.string.recents)
        RecipientType.Contacts -> stringResource(MR.string.contacts)
        RecipientType.Groups -> stringResource(MR.string.groups)
    }
}

@Composable
fun RecipientItem(
    recipientModel: RecipientModel,
    isSelected: Boolean,
    onRecipientSelected: (RecipientModel) -> Unit,
) {
    when (recipientModel) {
        is RecipientModel.Contact -> {
            ContactItem(
                name = recipientModel.contact.name,
                subTitle = recipientModel.contact.odinId.domainName,
                selectionMode = true,
                isSelected = isSelected,
                odinId = recipientModel.contact.odinId,
                avatarInitials = recipientModel.contact.avatarInitials,
                onContactClick = {
                    onRecipientSelected(recipientModel)
                },
            )
        }

        is RecipientModel.Conversation -> {
            GroupOrConversationItem(
                avatarModel = recipientModel.conversation.conversation.avatarModel,
                name = recipientModel.conversation.getDisplayName(youLabel = stringResource(MR.string.you)),
                selectionMode = true,
                isSelected = isSelected,
                onContactClick = {
                    onRecipientSelected(recipientModel)
                },
            )
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
