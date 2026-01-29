package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.keyboardAsState
import id.homebase.core.widget.AvatarImage
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.resources.MR
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
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
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConversationMessagesPane(
    conversation: ConversationUiModel,
    messages: ImmutableList<MessageUiModel>,
    savedScrollPosition: ScrollPosition?,
    onBackClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onScrollPositionChanged: (conversationId: Uuid, index: Int, offset: Int) -> Unit,
    showBackButton: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    var showConversationMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showAttachmentSheet by remember { mutableStateOf(false) }
    val isKeyboardVisible by keyboardAsState()
    var wasKeyboardVisible by remember { mutableStateOf(isKeyboardVisible) }
    val focusManager = LocalFocusManager.current

    // Track previous message count to detect new messages where want to scroll to bottom and not restore scroll
    val previousMessageCount = remember(conversation.id) { mutableStateOf(messages.size) }
    // Group messages within day sections
    val groupedMessages = remember(messages) {
        val timezone = TimeZone.currentSystemDefault()
        messages.groupBy { message ->
            val date = message.timestamp.toLocalDateTime(timezone).date
            date
        }.map { (date, msgs) ->
            MessageSectionItem(
                firstMessageTime = msgs.first().timestamp, messages = msgs, date = date
            )
        }.sortedBy { it.date }
    }
    // Calculate total items including date headers
    val totalItems = remember(groupedMessages) {
        groupedMessages.sumOf { it.messages.size + 1 } + 2
    }

    // Restore scroll position once when conversation changes and messages are loaded
    LaunchedEffect(conversation.id, messages.size) {
        if (messages.isNotEmpty() && previousMessageCount.value == messages.size) {
            if (savedScrollPosition != null) {
                listState.scrollToItem(
                    index = savedScrollPosition.firstVisibleItemIndex.coerceIn(
                        0,
                        messages.size - 1
                    ),
                    scrollOffset = savedScrollPosition.firstVisibleItemScrollOffset
                )
            } else {
                coroutineScope.launch {
                    listState.animateScrollToItem(totalItems - 1)
                }
            }
        }
        previousMessageCount.value = messages.size
    }

    // Save scroll position when it changes
    LaunchedEffect(listState, conversation.id) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.debounce(300) // Only save after 300ms of no scrolling
            .distinctUntilChanged().collect { (index, offset) ->
                onScrollPositionChanged(conversation.id, index, offset)
            }
    }

    Scaffold(topBar = {
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
                            imageVector = Icons.Default.ChevronLeft, contentDescription = "Back"
                        )
                    }
                }
            }, actions = {
                IconButton(onClick = {
                    showConversationMenu = true
                }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                ConversationMenu(
                    showMenu = showConversationMenu,
                    conversationId = conversation.id,
                    onDelete = { showConversationMenu = false },
                    dismissMenu = { showConversationMenu = false })
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            )
        )
    }, bottomBar = {
        Surface(
            shadowElevation = 8.dp, tonalElevation = 0.dp
        ) {
            Column {
                MessageInputBar(
                    focusRequester = focusRequester,
                    onSendMessage = {
                        if (it.isNotBlank()) {
                            onSendMessage(it)
                            // Scroll to bottom after sending
                            coroutineScope.launch {
                                listState.animateScrollToItem(totalItems - 1)
                            }
                        }
                    },
                    onAddAttachmentClick = {
                        if (showAttachmentSheet) {
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
                    }
                )

                AttachmentOptionsDisplay(
                    visible = showAttachmentSheet,
                    height = 400.dp,
                ) {
                    AttachmentOptions(
                        onImageClick = {
                            showAttachmentSheet = false
                            // Handle image selection
                        },
                        onFileClick = {
                            showAttachmentSheet = false
                            // Handle file selection
                        },
                        onCameraClick = {
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
    }) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize(1f).padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
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
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MessagesSection(
                                text = getDateSectionLabel(section.firstMessageTime)
                            )
                        }
                    }
                    items(
                        items = section.messages, key = { message -> message.id }) { message ->
                        if (message.isCurrentUser) {
                            SentMessageBubble(
                                message = message,
                            )
                        } else {
                            ReceivedMessageBubble(
                                message = message,
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

