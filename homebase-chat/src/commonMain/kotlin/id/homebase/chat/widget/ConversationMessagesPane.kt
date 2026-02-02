package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.chat.ConversationListUiAction
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.keyboardAsState
import id.homebase.core.util.rememberCameraManager
import id.homebase.core.widget.AvatarImage
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.resources.MR
import id.homebase.resources.chat_options
import id.homebase.resources.menu_back
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConversationMessagesPane(
    conversation: ConversationUiModel,
    messages: ImmutableList<MessageUiModel>,
    savedScrollPosition: ScrollPosition?,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    currentOdinId: String
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val isKeyboardVisible by keyboardAsState()
    val focusManager = LocalFocusManager.current

    val cameraLauncher = rememberCameraManager { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.SendFile(
                    conversation.id,
                    "",
                    listOf(file),
                )
            )
        }
    }
    val fileLauncher = rememberFilePickerLauncher { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.SendFile(
                    conversation.id,
                    "",
                    listOf(file),
                )
            )
        }
    }
    val galleryLauncher = rememberFilePickerLauncher(type = FileKitType.Image,) { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.SendFile(
                    conversation.id,
                    "",
                    listOf(file),
                )
            )
        }
    }

    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showConversationMenu by remember { mutableStateOf(false) }
    var wasKeyboardVisible by remember { mutableStateOf(isKeyboardVisible) }
    // Track previous message count to detect new messages where want to scroll to bottom and not restore scroll
    val previousMessageCount = remember(conversation.id) { mutableStateOf(-1) }
    // Flag to prevent saving scroll position during restoration
    var isRestoringScrollPosition by remember(conversation.id) { mutableStateOf(false) }
    // Flag to hide content until scroll position is set (prevents flash)
    var isScrollPositionReady by remember(conversation.id) { mutableStateOf(false) }
    // Group messages within day sections
    val groupedMessages = remember(messages) {
        val timezone = TimeZone.currentSystemDefault()
        messages.groupBy { message ->
            val date = message.created.toLocalDateTime(timezone).date
            date
        }.map { (date, msgs) ->
            MessageSectionItem(
                firstMessageTime = msgs.first().created, messages = msgs, date = date
            )
        }.sortedBy { it.date }
    }
    // Calculate total items including date headers
    val totalItems = remember(groupedMessages) {
        groupedMessages.sumOf { it.messages.size + 1 } + 2
    }

    // Initialize list state with saved position to prevent flash
    // Key by conversation.id so a new state is created when switching conversations
    val listState = remember(conversation.id) {
        val initialIndex = savedScrollPosition?.firstVisibleItemIndex ?: 0
        val initialOffset = savedScrollPosition?.firstVisibleItemScrollOffset ?: 0
        LazyListState(
            firstVisibleItemIndex = initialIndex,
            firstVisibleItemScrollOffset = initialOffset
        )
    }

    // Restore scroll position once when conversation changes and messages are loaded
    LaunchedEffect(conversation.id, messages.size) {
        if (messages.isNotEmpty()) {
            val isFirstLoad = previousMessageCount.value == -1
            val newMessagesAdded =
                previousMessageCount.value > 0 && messages.size > previousMessageCount.value

            if (isFirstLoad) {
                isRestoringScrollPosition = true
                // Show content first at initialized position
                isScrollPositionReady = true
                // Small delay to let composition happen with initialized state
                kotlinx.coroutines.delay(1)
                // On first load, scroll to saved position or bottom
                if (savedScrollPosition != null) {
                    println("Scroll to saved position: id=${conversation.id} -> ${savedScrollPosition.firstVisibleItemIndex}:${savedScrollPosition.firstVisibleItemScrollOffset}")
                    // Use scrollToItem (no animation) to prevent flash
                    listState.scrollToItem(
                        index = savedScrollPosition.firstVisibleItemIndex.coerceIn(
                            0,
                            totalItems - 1
                        ),
                        scrollOffset = savedScrollPosition.firstVisibleItemScrollOffset
                    )
                } else {
                    println("Scroll to bottom: id=${conversation.id}")
                    // Use scrollToItem for instant scroll to bottom
                    listState.scrollToItem(totalItems - 1)
                }
                // Wait a bit longer than the debounce time to ensure we don't save the restored position
                kotlinx.coroutines.delay(500)
                isRestoringScrollPosition = false
            } else if (newMessagesAdded) {
                // New messages added - scroll to bottom and allow saving this position
                println("New messages added, scrolling to bottom: id=${conversation.id}")
                coroutineScope.launch {
                    listState.animateScrollToItem(totalItems - 1)
                }
                // Don't set isRestoringScrollPosition flag here - we want to save this scroll position
            }
            previousMessageCount.value = messages.size
        } else {
            // Show empty state
            isScrollPositionReady = true
        }
    }

    // Save scroll position when it changes
    LaunchedEffect(conversation.id) {
        // Capture the current conversation ID to prevent race conditions
        val currentConversationId = conversation.id

        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .debounce(300) // Only save after 300ms of no scrolling
            .distinctUntilChanged()
            .collect { (index, offset) ->
                // Only save if we're still viewing the same conversation, messages are loaded, and not restoring
                if (currentConversationId == conversation.id &&
                    messages.isNotEmpty() &&
                    !isRestoringScrollPosition
                ) {
                    println("Scroll changed: id=${conversation.id} -> $index:$offset")
                    onUiAction(
                        ConversationListUiAction.SaveScrollPosition(
                            conversationId = conversation.id,
                            firstVisibleItemIndex = index,
                            firstVisibleItemScrollOffset = offset
                        )
                    )
                }
            }
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarImage(
                            avatarUrl = conversation.avatarUrl,
                            avatarInitials = conversation.avatarInitials,
                            size = 32.dp,
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = conversation.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
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
                    IconButton(onClick = {
                        showConversationMenu = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(MR.string.chat_options)
                        )
                    }
                    ConversationMenu(
                        showMenu = showConversationMenu,
                        dismissMenu = { showConversationMenu = false },
                        onConversationInfo = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.ShowConversationInfo(conversation.id)
                            )
                        },
                        onDelete = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.DeleteConversation(conversation.id)
                            )
                        },
                        onArchive = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.ArchiveConversation(conversation.id)
                            )
                        },
                        onClear = {
                            showConversationMenu = false
                            onUiAction(
                                ConversationListUiAction.ClearConversation(conversation.id)
                            )
                        },
                    )
                }, colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            if (isScrollPositionReady) {
                Box(
                    modifier = Modifier.weight(1f),
                ){
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        state = listState,
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    AvatarImage(
                                        avatarUrl = conversation.avatarUrl,
                                        avatarInitials = conversation.avatarInitials,
                                        size = 72.dp,
                                        fontSize = 24.sp,
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = conversation.name,
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                }
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        groupedMessages.forEach { section ->
                            item(key = "date_${section.date}") {
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    MessagesSection(
                                        text = getDateSectionLabel(section.firstMessageTime)
                                    )
                                }
                            }
                            items(
                                items = section.messages,
                                key = { message -> message.id }) { message ->
                                if (message.isCurrentUser(currentOdinId)) {
                                    SentMessageBubble(
                                        message = message,
                                        onMessageInfo = {
                                            onUiAction(
                                                ConversationListUiAction.ShowMessageInfo(message.id)
                                            )
                                        },
                                        onReply = {
                                            onUiAction(
                                                ConversationListUiAction.ReplyToMessage(message.id)
                                            )
                                        },
                                        onStar = {
                                            onUiAction(
                                                ConversationListUiAction.StarMessage(message.id)
                                            )
                                        },
                                        onEdit = {
                                            onUiAction(
                                                ConversationListUiAction.EditMessage(message.id)
                                            )
                                        },
                                        onDeleteForMe = {
                                            onUiAction(
                                                ConversationListUiAction.DeleteMessageForMe(
                                                    message.id
                                                )
                                            )
                                        },
                                        onDeleteForEveryone = {
                                            onUiAction(
                                                ConversationListUiAction.DeleteMessageForEveryone(
                                                    message.id
                                                )
                                            )
                                        },
                                        onMediaClick = { payload ->

                                        },
                                        onMediaLongPress = { payload, _ ->

                                        }
                                    )
                                } else {
                                    ReceivedMessageBubble(
                                        message = message,
                                        onMessageInfo = {
                                            onUiAction(
                                                ConversationListUiAction.ShowMessageInfo(message.id)
                                            )
                                        },
                                        onReply = {
                                            onUiAction(
                                                ConversationListUiAction.ReplyToMessage(message.id)
                                            )
                                        },
                                        onStar = {
                                            onUiAction(
                                                ConversationListUiAction.StarMessage(message.id)
                                            )
                                        },
                                        onDeleteForMe = {
                                            onUiAction(
                                                ConversationListUiAction.DeleteMessageForMe(
                                                    message.id
                                                )
                                            )
                                        },
                                        onMarkAsRead = {
                                            onUiAction(
                                                ConversationListUiAction.MarkAsRead(message.id)
                                            )
                                        },
                                        onAddReaction = { _, reaction ->
                                            onUiAction(
                                                ConversationListUiAction.AddReaction(
                                                    message.id,
                                                    reaction = reaction
                                                )
                                            )
                                        },
                                        onDeleteReaction = { _, reaction ->
                                            onUiAction(
                                                ConversationListUiAction.DeleteReaction(
                                                    message.id,
                                                    reaction = reaction
                                                )
                                            )
                                        },
                                        onMediaClick = { payload ->

                                        },
                                        onMediaLongPress = { payload, _ ->

                                        }
                                    )
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                    HomebaseVerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        state = listState
                    )
                }
            }
            Surface(
                shadowElevation = 8.dp, tonalElevation = 0.dp
            ) {
                Column {
                    MessageInputBar(
                        focusRequester = focusRequester,
                        onSendMessage = {
                            if (it.isNotBlank()) {
                                onUiAction(
                                    ConversationListUiAction.SendMessage(
                                        conversation.id, it
                                    )
                                )
                                // Scroll to bottom will happen automatically when the message is added to UI state
                            }
                        },
                        onAddAttachmentClick = {
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
                        onCameraClick = {
                            cameraLauncher.launch()
                        }
                    )

                    AttachmentOptionsDisplay(
                        visible = showAttachmentSheet && !isKeyboardVisible,
                    ) {
                        AttachmentGallery(
                            onImageSelected = {
                                showAttachmentSheet = false
                                // Handle image selection
                            },
                            onPermissionRequested = {
                                showAttachmentSheet = false
                                // Handle permission request
                            }
                        )
                        AttachmentOptions(
                            onGalleryClick = {
                                showAttachmentSheet = false
                                galleryLauncher.launch()
                            },
                            onFileClick = {
                                showAttachmentSheet = false
                                fileLauncher.launch()
                            },
                            onContactClick = {
                                showAttachmentSheet = false
                                // Handle camera
                            },
                            onLocationClick = {
                                showAttachmentSheet = false
                                // Handle location
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun getDateSectionLabel(timestamp: Instant): String {
    val timezone = TimeZone.currentSystemDefault()
    val messageDate = timestamp.toLocalDateTime(timezone).date
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

