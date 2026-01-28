package id.homebase.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.chat.data.Message
import id.homebase.chat.widget.ConversationAvatarItem
import id.homebase.chat.widget.ConversationItem
import id.homebase.chat.widget.ConversationMenu
import id.homebase.chat.widget.EmptyDetailPane
import id.homebase.chat.widget.MessageInputBar
import id.homebase.chat.widget.MessageSectionItem
import id.homebase.chat.widget.MessagesSection
import id.homebase.chat.widget.MinimalTextField
import id.homebase.chat.widget.NewConversationPane
import id.homebase.chat.widget.ReceivedMessageBubble
import id.homebase.chat.widget.SentMessageBubble
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.ui.assets.FeatherEdit
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.AvatarImage
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_select_a_conversation
import id.homebase.resources.chat_select_a_conversation_subtitle
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import kotlinx.collections.immutable.ImmutableList
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

@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettingsScreen: () -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        when (uiState.uiEvent) {
            ChatListUiEvent.NavigateBack -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            null -> {}
        }
    }

    ChatListUi(
        uiState = uiState,
        onUiAction = viewModel::onAction,
        onNavigateToSettingsScreen = onNavigateToSettingsScreen,
        onDetailPaneVisibilityChanged = onDetailPaneVisibilityChanged
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatListUi(
    uiState: ChatListUiState,
    onUiAction: (ChatListUiAction) -> Unit,
    onNavigateToSettingsScreen: () -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val defaultDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val isExpanded =
        windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(800)
    val scaffoldDirective = PaneScaffoldDirective(
        maxHorizontalPartitions = if (isExpanded) 2 else 1,
        horizontalPartitionSpacerSize = 0.dp, // Remove the white border
        maxVerticalPartitions = defaultDirective.maxVerticalPartitions,
        verticalPartitionSpacerSize = defaultDirective.verticalPartitionSpacerSize,
        defaultPanePreferredWidth = 360.dp, // Slightly wider default for chat list
        excludedBounds = defaultDirective.excludedBounds
    )
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<String>(
        scaffoldDirective = scaffoldDirective,
        initialDestinationHistory = if (scaffoldDirective.maxHorizontalPartitions > 1) {
            listOf(
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List),
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail)
            )
        } else {
            listOf(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List))
        }
    )
    val scope = rememberCoroutineScope()
    val backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange

    // Detect if detail pane is visible and list pane is hidden (compact view showing only detail)
    val isListPaneHidden =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    val isDetailPaneVisible =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
    val showingOnlyDetail = isListPaneHidden && isDetailPaneVisible

    LaunchedEffect(isExpanded) {
        if (!isExpanded && scaffoldNavigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail) {
            // Optional: If you want to force it back to list view when shrinking
            scaffoldNavigator.navigateBack()
        }
    }

    val partitions = scaffoldDirective.maxHorizontalPartitions
    LaunchedEffect(partitions) {
        if (partitions > 1) {
            // This ensures the Detail role is added to the active visible roles
            scaffoldNavigator.navigateTo(
                ListDetailPaneScaffoldRole.Detail,
                uiState.selectedConversationId,
            )
        }
    }

    // Notify parent about detail pane visibility in compact view
    LaunchedEffect(showingOnlyDetail) {
        onDetailPaneVisibilityChanged(showingOnlyDetail)
    }

    BackHandler(scaffoldNavigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
        scope.launch {
            scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
        }
    }

    ListDetailPaneScaffold(
        modifier = Modifier.fillMaxSize(),
        directive = scaffoldNavigator.scaffoldDirective,
        scaffoldState = scaffoldNavigator.scaffoldState,
        listPane = {
            AnimatedPane(
                modifier = Modifier
            ) {
                if (uiState.showingNewChatPane) {
                    NewConversationPane(
                        contacts = uiState.contacts,
                        searchQuery = uiState.searchQuery,
                        onBackClick = { onUiAction(ChatListUiAction.BackToListClicked) },
                        onContactClick = { contact ->
                            onUiAction(ChatListUiAction.ContactClicked(contact))
                        },
                        onSearchQueryChanged = { query ->
                            onUiAction(ChatListUiAction.SearchQueryChanged(query))
                        }
                    )
                } else {
                    ChatListPane(
                        conversationViewModels = uiState.conversationViewModels,
                        selectedConversationId = scaffoldNavigator.currentDestination?.contentKey,
                        onConversationClick = { conversationId ->
                            onUiAction(ChatListUiAction.ConversationClicked(conversationId))
                            scope.launch {
                                scaffoldNavigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail,
                                    conversationId
                                )
                            }
                        },
                        onProfileClick = onNavigateToSettingsScreen,
                        onUiAction = onUiAction,
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                uiState.selectedConversationId?.let { conversationId ->
                    val conversation = uiState.conversations.find { it.id == conversationId }
                    if (conversation != null) {
                        ChatDetailPane(
                            conversationViewModel = conversation,
                            messageViewModels = uiState.currentConversationMessageViewModels,
                            onBackClick = {
                                scope.launch {
                                    scaffoldNavigator.navigateBack(backNavigationBehavior)
                                }
                            },
                            onSendMessage = { content ->
                                onUiAction(
                                    ChatListUiAction.SendMessage(
                                        conversationId,
                                        content
                                    )
                                )
                            },
                            showBackButton = scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
                        )
                    } else {
                        EmptyDetailPane(
                            title = stringResource(MR.string.chat_select_a_conversation),
                            subtitle = stringResource(MR.string.chat_select_a_conversation_subtitle)
                        )
                    }
                } ?: EmptyDetailPane(
                    title = stringResource(MR.string.chat_select_a_conversation),
                    subtitle = stringResource(MR.string.chat_select_a_conversation_subtitle)
                )
            }
        },
        paneExpansionState =
            rememberPaneExpansionState(
                keyProvider = scaffoldNavigator.scaffoldValue,
                anchors = listOf(
                    PaneExpansionAnchor.Offset.fromStart(96.dp),
                    PaneExpansionAnchor.Offset.fromStart(280.dp),
                    PaneExpansionAnchor.Offset.fromStart(320.dp),
                    PaneExpansionAnchor.Offset.fromStart(360.dp),
                    PaneExpansionAnchor.Offset.fromStart(400.dp),
                    PaneExpansionAnchor.Offset.fromStart(440.dp),
                    PaneExpansionAnchor.Offset.fromStart(480.dp),
                ),
            ),
        paneExpansionDragHandle = { state ->
            val interactionSource = remember { MutableInteractionSource() }
            VerticalDragHandle(
                modifier =
                    Modifier.paneExpansionDraggable(
                        state,
                        LocalMinimumInteractiveComponentSize.current,
                        interactionSource
                    ), interactionSource = interactionSource
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListPane(
    conversations: ImmutableList<ConversationUiModel>,
    selectedConversationId: String? = null,
    onProfileClick: () -> Unit ,
    onConversationClick: (String) -> Unit,
    onUiAction: (ChatListUiAction) -> Unit
) {
    val searchState = rememberTextFieldState()
    val listState = rememberLazyListState()
    var filterByUnread by remember { mutableStateOf(false) }
    var selectedFilterConversationId by remember { mutableStateOf<String?>(null) }
    val filteredConversations = remember(conversations, filterByUnread) {
        if (filterByUnread) {
            conversations.filter { it.unreadCount > 0 || it.id == selectedFilterConversationId }
        } else {
            conversations
        }
    }
    BoxWithConstraints {
        val iconOnlyMode by derivedStateOf { maxWidth <= 96.dp }
        Scaffold(
            topBar = {
                Column {
                    if (!iconOnlyMode) {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AvatarImage(
                                        avatarUrl = null,
                                        avatarInitials = "CH",
                                        size = 32.dp,
                                        fontSize = 12.sp,
                                        onClick = {
                                            onProfileClick()
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = stringResource(MR.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    onUiAction(ChatListUiAction.NewChatClicked)
                                }) {
                                    Icon(
                                        imageVector = FeatherEdit,
                                        contentDescription = stringResource(MR.string.chat_new_conversation)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            )
                        )
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MinimalTextField(
                                textFieldState = searchState,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(
                                onClick = {
                                    filterByUnread = !filterByUnread
                                    if (!filterByUnread) {
                                        selectedFilterConversationId = null
                                    }
                                },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (filterByUnread) HomebaseTheme.extendedColors.bubbleSentSurface else Color.Unspecified
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filter by unread",
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))
                            IconButton(onClick = {
                                onUiAction(ChatListUiAction.NewChatClicked)
                            }) {
                                Icon(
                                    imageVector = FeatherEdit,
                                    contentDescription = stringResource(MR.string.chat_new_conversation)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { innerPadding ->
            Box {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    state = listState,
                ) {
                    if (filterByUnread) {
                        item {
                            Text(
                                text = "Filtered by unread",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                    items(filteredConversations.toList()) { conversation ->
                        if (iconOnlyMode) {
                            ConversationAvatarItem(
                                conversation = conversation,
                                onClick = {
                                    if (filterByUnread) {
                                        selectedFilterConversationId = conversation.id
                                    }
                                    onConversationClick(conversation.id)
                                },
                                isSelected = conversation.id == selectedConversationId
                            )
                        } else {
                            ConversationItem(
                                conversation = conversation,
                                onClick = {
                                    if (filterByUnread) {
                                        selectedFilterConversationId = conversation.id
                                    }
                                    onConversationClick(conversation.id)
                                },
                                isSelected = conversation.id == selectedConversationId
                            )
                        }
                    }
                    if (filterByUnread) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (filteredConversations.isEmpty()) {
                                        Text(
                                            text = "No unread chats",
                                            modifier = Modifier.padding(24.dp),
                                        )
                                    }
                                    ElevatedButton(
                                        onClick = {
                                            filterByUnread = false
                                        }, colors = ButtonDefaults.elevatedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                    ) {
                                        Text(text = "Clear filter")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                }
                HomebaseVerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                    state = listState
                )
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailPane(
    conversationViewModel: ConversationUiModel,
    messageViewModels: ImmutableList<MessageUiModel>,
    onBackClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    showBackButton: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val groupedMessages = remember(messages) {
        val timezone = TimeZone.currentSystemDefault()
        messages
            .groupBy { message ->
                val date = message.timestamp.toLocalDateTime(timezone).date
                date
            }
            .map { (date, msgs) ->
                MessageSectionItem(
                    firstMessageTime = msgs.first().timestamp,
                    messages = msgs,
                    date = date
                )
            }
            .sortedBy { it.date }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarImage(
                            avatarUrl = conversationViewModel.avatarUrl,
                            avatarInitials = conversationViewModel.avatarInitials,
                            size = 32.dp,
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = conversationViewModel.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showMenu = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                    }
                    ConversationMenu(
                        showMenu = showMenu,
                        conversationId = conversationViewModel.id,
                        onDelete = { showMenu = false },
                        dismissMenu = { showMenu = false }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 0.dp
            ) {
                MessageInputBar(
                    onSendMessage = {
                        if (it.isNotBlank()) {
                            onSendMessage(it)
                        }
                    },
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AvatarImage(
                                avatarUrl = conversationViewModel.avatarUrl,
                                avatarInitials = conversationViewModel.avatarInitials,
                                size = 72.dp,
                                fontSize = 24.sp,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = conversationViewModel.name,
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
                            modifier = Modifier
                                .fillMaxWidth()
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
                        key = { message -> message.id }
                    ) { message ->
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
                modifier = Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                state = listState
            )
        }
    }
}

@Preview
@Composable
fun ChatListUiPreview() {
    HomebaseTheme {
        ChatListUi(
            uiState = ChatListUiState(),
            onNavigateToSettingsScreen = {},
            onUiAction = {}
        )
    }
}
