package id.homebase.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.chat.data.Contact
import id.homebase.chat.data.Message
import id.homebase.core.ui.assets.FeatherEdit
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.core.util.isMobile
import id.homebase.core.widget.AvatarImage
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_select_a_conversation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
        onAction = viewModel::onAction,
        onDetailPaneVisibilityChanged = onDetailPaneVisibilityChanged
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatListUi(
    uiState: ChatListUiState,
    onAction: (ChatListUiAction) -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange

    // Create custom directive with no spacer between panes
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val defaultDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val customDirective = PaneScaffoldDirective(
        maxHorizontalPartitions = defaultDirective.maxHorizontalPartitions,
        horizontalPartitionSpacerSize = 0.dp, // Remove the white border
        maxVerticalPartitions = defaultDirective.maxVerticalPartitions,
        verticalPartitionSpacerSize = defaultDirective.verticalPartitionSpacerSize,
        defaultPanePreferredWidth = 360.dp, // Slightly wider default for chat list
        excludedBounds = defaultDirective.excludedBounds
    )

    // Detect if detail pane is visible and list pane is hidden (compact view showing only detail)
    val isListPaneHidden =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    val isDetailPaneVisible =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
    val showingOnlyDetail = isListPaneHidden && isDetailPaneVisible

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
        directive = customDirective,
        scaffoldState = scaffoldNavigator.scaffoldState,
        listPane = {
            AnimatedPane(
                modifier = Modifier
            ) {
                if (uiState.showingNewChatPane) {
                    NewChatPane(
                        contacts = uiState.contacts,
                        searchQuery = uiState.searchQuery,
                        onBackClick = { onAction(ChatListUiAction.BackToListClicked) },
                        onContactClick = { contact ->
                            onAction(ChatListUiAction.ContactClicked(contact))
                        },
                        onSearchQueryChanged = { query ->
                            onAction(ChatListUiAction.SearchQueryChanged(query))
                        }
                    )
                } else {
                    //val isDetailPaneVisible =
                    //   scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden

                    ChatListPane(
                        conversations = uiState.conversations,
                        selectedConversationId = scaffoldNavigator.currentDestination?.contentKey,
                        onConversationClick = { conversationId ->
                            onAction(ChatListUiAction.ConversationClicked(conversationId))
                            scope.launch {
                                scaffoldNavigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail,
                                    conversationId
                                )
                            }
                        },
                        onNewChatClick = { onAction(ChatListUiAction.NewChatClicked) }
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                scaffoldNavigator.currentDestination?.contentKey?.let { conversationId ->
                    val conversation = uiState.conversations.find { it.id == conversationId }
                    if (conversation != null) {
                        ChatDetailPane(
                            conversation = conversation,
                            messages = uiState.currentConversationMessages,
                            onBackClick = {
                                scope.launch {
                                    scaffoldNavigator.navigateBack(backNavigationBehavior)
                                }
                            },
                            onSendMessage = { content ->
                                onAction(ChatListUiAction.SendMessage(conversationId, content))
                            },
                            showBackButton = scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
                        )
                    } else {
                        EmptyDetailPane()
                    }
                } ?: EmptyDetailPane()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListPane(
    conversations: ImmutableList<Conversation>,
    onConversationClick: (String) -> Unit,
    onNewChatClick: () -> Unit,
    selectedConversationId: String? = null,
) {
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
                        IconButton(onClick = onNewChatClick) {
                            Icon(
                                imageVector = FeatherEdit,
                                contentDescription = "New conversation"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                )
                // Search field below title
                OutlinedTextField(
                    value = "",
                    onValueChange = { },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
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
            items(conversations.toList()) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    onClick = { onConversationClick(conversation.id) },
                    isSelected = conversation.id == selectedConversationId
                )
            }
        }
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    isSelected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = conversation.avatarUrl,
            avatarInitials = conversation.avatarInitials,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTimestamp(conversation.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conversation.unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))

                    Badge(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ) {
                        Text(
                            modifier = Modifier.padding(2.dp),
                            text = conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailPane(
    conversation: Conversation,
    messages: ImmutableList<Message>,
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
                        conversationId = conversation.id,
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

                items(messages.toList()) { message ->
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

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun SentMessageBubble(
    message: Message,
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Spacer(modifier = Modifier.width(24.dp))
        Column(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalAlignment = Alignment.End
        ) {
            Row {
                Column {
                    if (isHovered) {
                        IconButton(onClick = {
                            showMenu = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                            )
                        }
                    }
                    SentMessageMenu(
                        showMenu = showMenu,
                        messageId = message.id,
                        onDelete = { showMenu = false },
                        dismissMenu = { showMenu = false }
                    )
                }
                ChatBubble(
                    modifier = Modifier
                        .heightIn(min = 48.dp),
                    text = message.content,
                    timestamp = formatTimestamp(message.timestamp),
                    sentByYou = true,
                    onLongClick = {
                        showMenu = true
                    }
                )
            }
        }
    }
}

@Composable
fun ReceivedMessageBubble(
    message: Message,
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).hoverable(interactionSource),
            horizontalAlignment = Alignment.Start,
        ) {
            Row {
                ChatBubble(
                    modifier = Modifier
                        .heightIn(min = 48.dp),
                    text = message.content,
                    timestamp = formatTimestamp(message.timestamp),
                    sentByYou = false,
                    onLongClick = {
                        showMenu = true
                    }
                )
                Column {
                    if (isHovered) {
                        IconButton(onClick = {
                            showMenu = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                            )
                        }
                    }
                    ReceivedMessageMenu(
                        showMenu = showMenu,
                        messageId = message.id,
                        onDelete = { showMenu = false },
                        dismissMenu = { showMenu = false }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(24.dp))
    }
}

@Composable
fun ChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    timestamp: String,
    sentByYou: Boolean,
    onLongClick: () -> Unit,
    ) {
    // We store the result of the text layout to know where the last line ends
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val backgroundColor =
        if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (sentByYou) HomebaseTheme.extendedColors.bubbleSentOnSurface else MaterialTheme.colorScheme.onSurface

    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (!sentByYou) 4.dp else 18.dp,
        bottomEnd = if (sentByYou) 4.dp else 18.dp,
    )
    Surface(
        modifier = modifier
            .clip(shape)
            .ifTrue(isMobile()) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
            },
        shape = shape,
        color = backgroundColor,
    ) {
        Layout(
            modifier = Modifier.padding(12.dp),
            content = {
                Text(
                    text = text,
                    onTextLayout = { textLayoutResult = it },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        ) { measurables, constraints ->
            val textPlaceable = measurables[0].measure(constraints)
            val timePlaceable = measurables[1].measure(constraints)

            val layoutResult = textLayoutResult
            var totalWidth: Int
            var totalHeight: Int
            var timeX: Int
            var timeY: Int

            if (layoutResult == null) {
                // Fallback if layout isn't ready yet
                totalWidth = textPlaceable.width
                totalHeight = textPlaceable.height
                timeX = 0
                timeY = 0
            } else {
                val lastLineIndex = layoutResult.lineCount - 1
                val lastLineRight = layoutResult.getLineRight(lastLineIndex)

                // Determine if timestamp fits on the last line
                // We add a small gap (8dp converted to px) between text and time
                val horizontalGap = 8.dp.toPx()
                val fitsOnLastLine =
                    (constraints.maxWidth - lastLineRight) > (timePlaceable.width + horizontalGap)

                if (fitsOnLastLine) {
                    // Fits on the same line
                    totalWidth = maxOf(
                        textPlaceable.width,
                        (lastLineRight + horizontalGap + timePlaceable.width).toInt()
                    )
                    totalHeight = textPlaceable.height
                    timeX = totalWidth - timePlaceable.width
                    timeY = totalHeight - timePlaceable.height
                } else {
                    // Needs a new line
                    totalWidth = maxOf(textPlaceable.width, timePlaceable.width)
                    totalHeight = textPlaceable.height + timePlaceable.height
                    timeX = totalWidth - timePlaceable.width
                    timeY = totalHeight - timePlaceable.height
                }
            }

            layout(totalWidth, totalHeight) {
                textPlaceable.placeRelative(0, 0)
                timePlaceable.placeRelative(timeX, timeY)
            }
        }
    }
}

@Composable
fun EmptyDetailPane() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(MR.string.chat_select_a_conversation),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Choose a conversation from the list to view messages",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatPane(
    contacts: ImmutableList<Contact>,
    searchQuery: String,
    onBackClick: () -> Unit,
    onContactClick: (Contact) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
) {
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "New Chat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search contacts") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            // Contacts section
            Text(
                text = "CONTACTS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredContacts.toList()) { contact ->
                    ContactItem(
                        contact = contact,
                        onClick = { onContactClick(contact) }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactItem(
    contact: Contact,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = contact.avatarUrl,
            avatarInitials = contact.avatarInitials,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = contact.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ConversationMenu(
    showMenu: Boolean,
    conversationId: String,
    onDelete: (conversationId: String) -> Unit,
    dismissMenu: () -> Unit,
) {
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Menu above the fold") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null
                )
            }
        )

        HorizontalDivider()


        DropdownMenuItem(
            onClick = {
                onDelete(conversationId)
                dismissMenu()
            },
            text = { Text(text = "Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Block") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null
                )
            }
        )

    }
}

@Composable
fun ReceivedMessageMenu(
    showMenu: Boolean,
    messageId: String,
    onDelete: (messageId: String) -> Unit,
    dismissMenu: () -> Unit,
) {
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Menu above the fold") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null
                )
            }
        )

        HorizontalDivider()


        DropdownMenuItem(
            onClick = {
                onDelete(messageId)
                dismissMenu()
            },
            text = { Text(text = "Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Block") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null
                )
            }
        )

    }
}

@Composable
fun SentMessageMenu(
    showMenu: Boolean,
    messageId: String,
    onDelete: (messageId: String) -> Unit,
    dismissMenu: () -> Unit,
) {
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Menu above the fold") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null
                )
            }
        )

        HorizontalDivider()


        DropdownMenuItem(
            onClick = {
                onDelete(messageId)
                dismissMenu()
            },
            text = { Text(text = "Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Block") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null
                )
            }
        )

    }
}

@Preview
@Composable
fun ChatListUiPreview() {
    HomebaseTheme {
        ChatListUi(
            uiState = ChatListUiState(),
            onAction = {}
        )
    }
}
