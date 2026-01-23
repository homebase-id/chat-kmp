package id.homebase.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.ui.assets.FeatherEdit
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.AvatarImage
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_search_placeholder
import id.homebase.resources.chat_select_a_conversation
import id.homebase.resources.chat_select_a_conversation_subtitle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onNavigateBack: () -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ChatListUiEvent.NavigateToMessages -> {}
                ChatListUiEvent.NavigateBack -> onNavigateBack()
            }
        }
    }

    ChatListUi(
        uiState = uiState,
        onUiAction = viewModel::onAction,
        onDetailPaneVisibilityChanged = onDetailPaneVisibilityChanged
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatListUi(
    uiState: ChatListUiState,
    onUiAction: (ChatListUiAction) -> Unit,
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
    conversationViewModels: ImmutableList<ConversationUiModel>,
    onConversationClick: (Uuid) -> Unit,
    onNewChatClick: () -> Unit,
    selectedConversationId: Uuid? = null,
) {
    var filterByUnread by remember { mutableStateOf(false) }
    var selectedFilterConversationId by remember { mutableStateOf<String?>(null) }
    val filteredConversations = remember(conversations, filterByUnread) {
        if (filterByUnread) {
            conversations.filter { it.unreadCount > 0 || it.id == selectedFilterConversationId }
        } else {
            conversations
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(MR.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
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
                // Search field below title
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = { },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(MR.string.chat_search_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
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
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
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
    var messageText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        placeholder = {
                            Text("Message")
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )

                    FloatingActionButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                onSendMessage(messageText)
                                messageText = ""
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
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

                items(messageViewModels.toList()) { message ->
                    if (message.isCurrentUser) {
                        SentMessageBubble(
                            messageViewModel = message,
                        )
                    } else {
                        ReceivedMessageBubble(
                            messageViewModel = message,
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Preview
@Composable
fun ChatListUiPreview() {
    HomebaseTheme {
        ChatListUi(
            uiState = ChatListUiState(),
            onUiAction = {}
        )
    }
}
