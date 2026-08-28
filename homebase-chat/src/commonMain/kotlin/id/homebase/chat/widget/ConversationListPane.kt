package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.auth.initials
import id.homebase.chat.conversationlist.ConversationListContentModel
import id.homebase.chat.conversationlist.ConversationListContentState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiState
import id.homebase.chat.data.ConversationState
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.ui.assets.FeatherEdit
import id.homebase.core.util.isExpandedLayout
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_archived_chats
import id.homebase.resources.chat_filter_by_unread_clear_button
import id.homebase.resources.chat_filter_by_unread_description
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_options
import id.homebase.resources.chat_search_empty_description
import id.homebase.resources.chat_search_placeholder
import id.homebase.resources.chat_search_result_empty
import id.homebase.resources.loading
import id.homebase.resources.menu_back
import id.homebase.resources.remove
import id.homebase.resources.search
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConversationListPane(
    uiState: ConversationListUiState,
    selectedConversationId: Uuid? = null,
    searchTextState: TextFieldState,
    onProfileClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    onConversationSelected: (conversationId: Uuid) -> Unit,
) {
    val twoPaneWindow = isExpandedLayout()
    val paneContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val paneEdgeColor = MaterialTheme.colorScheme.outlineVariant
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState)
    // Read inside the draw lambdas below, so a row sliding under the flat bar repaints the
    // hairline without recomposing the bar.
    val barOverlapped by remember { derivedStateOf { topBarState.overlappedFraction > 0f } }
    val barUnderline = Modifier.drawWithContent {
        drawContent()
        if (!barOverlapped) return@drawWithContent
        val stroke = 1.dp.toPx()
        val y = size.height - stroke / 2f
        drawLine(
            color = paneEdgeColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = stroke,
        )
    }
    val listState = rememberLazyListState()
    val focusRequesterNone = remember { FocusRequester() }
    val focusRequesterSearch = remember { FocusRequester() }
    var showMenu by remember { mutableStateOf(false) }

    // Request focus on box element to prevent soft keyboard popping up
    LaunchedEffect(Unit) { focusRequesterNone.requestFocus() }
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            focusRequesterSearch.requestFocus()
        }
    }

    @Suppress("DEPRECATION") BackHandler(enabled = uiState.isSearchActive) {
        onUiAction(ConversationListUiAction.SearchBackClicked)
        searchTextState.clearText()
    }

    BoxWithConstraints(modifier = Modifier.focusRequester(focusRequesterNone).focusable()) {
        val iconOnlyMode by derivedStateOf { maxWidth <= 96.dp }
        Scaffold(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .drawWithContent {
                    drawContent()
                    if (!twoPaneWindow) return@drawWithContent
                    val stroke = 1.dp.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) stroke / 2f
                    else size.width - stroke / 2f
                    drawLine(
                        color = paneEdgeColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                    )
                },
            topBar = {
                if (uiState.showArchived) {
                    TopAppBar(
                        modifier = barUnderline,
                        scrollBehavior = scrollBehavior,
                        title = {
                            Text(stringResource(MR.string.chat_archived_chats))
                        },
                        navigationIcon = {
                            IconButton(onClick = { onUiAction(ConversationListUiAction.ArchiveBackClicked) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(MR.string.menu_back)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = paneContainerColor,
                            scrolledContainerColor = paneContainerColor,
                        ),
                    )
                } else if (!iconOnlyMode) {
                    TopAppBar(title = {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Title row - keep it in place but fade out
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AnimatedVisibility(
                                    visible = !uiState.isSearchActive,
                                    enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
                                    exit = fadeOut(animationSpec = tween(150))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        uiState.ownerSession?.let { session ->
                                            OwnerAvatar(
                                                odinId = session.odinId,
                                                profileImageData = null,
                                                initials = session.initials(),
                                                connectionStatus = uiState.connectionStatus,
                                                driveIsSyncing = uiState.driveIsSyncing,
                                                hasDriveError = uiState.hasDriveError,
                                                options = AvatarOptions(
                                                    size = 32.dp, fontSize = 12.sp, onClick = {
                                                        onProfileClick()
                                                    }),
                                                animatedVisibilityScope = this@AnimatedVisibility,
                                                sharedTransitionScope = null,
                                                cacheBustKey = session.profileImageLastModified,
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Text(
                                            text = stringResource(MR.string.app_name),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false),
                                            autoSize = TextAutoSize.StepBased(
                                                minFontSize = 14.sp,
                                                maxFontSize = 22.sp,
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                }
                            }
                            // Search field - positioned absolutely on top
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
                                            ConversationListUiAction.SearchBackClicked
                                        )
                                        searchTextState.clearText()
                                    })
                            }
                        }
                    }, actions = {
                        if (twoPaneWindow) {
                            FilledTonalIconButton(
                                onClick = {
                                    onUiAction(
                                        ConversationListUiAction.NewConversationClicked
                                    )
                                },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) {
                                Icon(
                                    imageVector = FeatherEdit,
                                    contentDescription = stringResource(
                                        MR.string.chat_new_conversation
                                    ),
                                    // Feather artwork runs to the edge of its 24dp viewport;
                                    // Material glyphs keep a keyline, so match their ink, not
                                    // their nominal size.
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (!uiState.isSearchActive) {
                            // One pin, either direction (#1012): I'm sharing with anyone OR anyone
                            // is sharing with me. Tapping opens the live map.
                            LiveShareIndicator(
                                untilMs = uiState.liveSharePinAnyUntilMs,
                                onClick = {
                                    onUiAction(ConversationListUiAction.OpenLiveLocationMap)
                                },
                            )
                            IconButton(
                                onClick = {
                                    onUiAction(
                                        ConversationListUiAction.SearchClicked
                                    )
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(MR.string.search),
                                )
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(
                                            MR.string.chat_options
                                        )
                                    )
                                }
                                ConversationListMenu(
                                    showMenu = showMenu,
                                    dismissMenu = { showMenu = false },
                                    isFilteringUnread = uiState.filterByUnread,
                                    onMarkAllAsRead = {
                                        // TODO
                                        showMenu = false
                                    },
                                    onFilterUnread = {
                                        onUiAction(
                                            ConversationListUiAction.FilterByUnreadClicked
                                        )
                                        showMenu = false
                                    },
                                    onClearFilterUnread = {
                                        onUiAction(
                                            ConversationListUiAction.ClearFilterByUnreadClicked
                                        )
                                        showMenu = false
                                    },
                                    onSettings = {
                                        onProfileClick()
                                        showMenu = false
                                    })
                            }
                        }
                    }, modifier = barUnderline, scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = paneContainerColor,
                            scrolledContainerColor = paneContainerColor,
                        ))
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        IconButton(
                            onClick = {
                                onUiAction(ConversationListUiAction.NewConversationClicked)
                            }) {
                            Icon(
                                imageVector = FeatherEdit,
                                contentDescription = stringResource(MR.string.chat_new_conversation)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            },
            floatingActionButton = {
                if (!iconOnlyMode && !twoPaneWindow) {
                    FloatingActionButton(
                        onClick = {
                            onUiAction(ConversationListUiAction.NewConversationClicked)
                        }) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(MR.string.chat_new_conversation)
                        )
                    }
                }
            },
            containerColor = paneContainerColor,
        ) { innerPadding ->
            Box {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    state = listState,
                ) {
                    if (uiState.filterByUnread) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                InputChip(
                                    onClick = {
                                        onUiAction(
                                            ConversationListUiAction.ClearFilterByUnreadClicked
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(
                                                MR.string.chat_filter_by_unread_description
                                            )
                                        )
                                    },
                                    selected = true,
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(MR.string.remove),
                                            // Modifier.size(InputChipDefaults.AvatarSize)
                                        )
                                    },
                                )
                            }
                        }
                    }
                    when (uiState.conversationsContent) {
                        is ConversationListContentState.Loading -> {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(
                                            MR.string.loading
                                        ),
                                        modifier = Modifier.padding(24.dp),
                                    )
                                }
                            }
                        }

                        is ConversationListContentState.Empty -> {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = stringResource(
                                            MR.string.chat_search_empty_description
                                        ),
                                        modifier = Modifier.padding(24.dp),
                                    )
                                }
                            }
                        }

                        is ConversationListContentState.EmptySearch -> {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = stringResource(
                                            MR.string.chat_search_result_empty,
                                            uiState.conversationsContent.query
                                        )
                                    )
                                }
                            }
                        }

                        is ConversationListContentState.Items -> {
                            items(
                                uiState.conversationsContent.list,
                                key = { listItem ->
                                    when (listItem) {
                                        is ConversationListContentModel.Conversation ->
                                            listItem.conversation.conversation.id

                                        is ConversationListContentModel.Message ->
                                            listItem.message.id

                                        is ConversationListContentModel.Header ->
                                            listItem.resource.key
                                    }
                                },
                                // Conversation rows, message-search hits and headers are three
                                // different shapes; without a contentType the list tries to reuse
                                // one as another and rebuilds the composition instead. See the
                                // matching note on the message list in ConversationContent.kt.
                                contentType = { listItem ->
                                    when (listItem) {
                                        is ConversationListContentModel.Conversation -> "conversation"
                                        is ConversationListContentModel.Message -> "message"
                                        is ConversationListContentModel.Header -> "header"
                                    }
                                }
                            ) { listItem ->
                                ConversationLisContentItem(
                                    listItem = listItem,
                                    selectedConversationId = selectedConversationId,
                                    iconOnlyMode = iconOnlyMode,
                                    // Live search text drives the highlight in
                                    // message-search rows; empty when not searching.
                                    searchQuery = if (uiState.isSearchActive)
                                        searchTextState.text.toString()
                                    else "",
                                    onUiAction = onUiAction,
                                    onConversationSelected = onConversationSelected,
                                )
                            }
                        }
                    }

                    if (uiState.archivedCount > 0 && !uiState.isSearchActive) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(
                                    onClick = {
                                        onUiAction(ConversationListUiAction.ShowArchivedMessagesClicked)
                                    },
                                ) {
                                    Text(text = stringResource(MR.string.chat_archived_chats) + " (${uiState.archivedCount})")
                                }
                            }
                        }
                    }

                    if (uiState.filterByUnread) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                ElevatedButton(
                                    onClick = {
                                        onUiAction(
                                            ConversationListUiAction.ClearFilterByUnreadClicked
                                        )
                                    },
                                    colors = ButtonDefaults.elevatedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(
                                            MR.string.chat_filter_by_unread_clear_button
                                        )
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
                HomebaseVerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    state = listState
                )
            }
        }
    }
}

@Composable
fun ConversationLisContentItem(
    listItem: ConversationListContentModel,
    selectedConversationId: Uuid?,
    iconOnlyMode: Boolean,
    searchQuery: String,
    onUiAction: (ConversationListUiAction) -> Unit,
    onConversationSelected: (conversationId: Uuid) -> Unit,
) {
    when (listItem) {
        is ConversationListContentModel.Header -> {
            Text(
                text = stringResource(listItem.resource),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 12.sp,
                    maxFontSize = 16.sp,
                ),
            )
        }

        is ConversationListContentModel.Conversation -> {
            if (iconOnlyMode) {
                ConversationAvatarItem(
                    conversation = listItem.conversation.conversation,
                    onClick = {
                        onUiAction(
                            ConversationListUiAction.ConversationClicked(
                                listItem.conversation.conversation.id, null
                            )
                        )
                        onConversationSelected(listItem.conversation.conversation.id)
                    },
                    isSelected = listItem.conversation.conversation.id == selectedConversationId,
                )
            } else {
                ConversationItem(
                    enrichedData = listItem.conversation,
                    onClick = {
                        onUiAction(
                            ConversationListUiAction.ConversationClicked(
                                listItem.conversation.conversation.id,
                                null
                            )
                        )
                        onConversationSelected(listItem.conversation.conversation.id)
                    },
                    onContactClick = {
                        onUiAction(ConversationListUiAction.ShowConversationSettings(listItem.conversation.conversation))
                    },
                    onArchiveClick = {
                        val convo = listItem.conversation.conversation
                        if (convo.conversationState == ConversationState.Archived) {
                            onUiAction(ConversationListUiAction.UnarchiveConversation(convo.id))
                        } else {
                            onUiAction(ConversationListUiAction.ArchiveConversation(convo.id))
                        }
                    },
                    onTogglePinClick = {
                        onUiAction(ConversationListUiAction.TogglePinConversation(listItem.conversation.conversation.id))
                    },
                    onMarkAsReadClick = {
                        onUiAction(
                            ConversationListUiAction.MarkAsRead(
                                listItem.conversation.conversation.id,
                                messages = null,
                            )
                        )
                    },

                    isSelected = listItem.conversation.conversation.id == selectedConversationId,
                )
            }
        }

        is ConversationListContentModel.Message -> {
            MessageSearchItem(
                memberName = listItem.message.displayName,
                message = listItem.message.content,
                searchQuery = searchQuery,
                contactOdinId = listItem.message.originalAuthor,
                timestamp = listItem.message.userDate,
                onClick = {
                    onUiAction(
                        ConversationListUiAction.ConversationClicked(
                            listItem.message.conversationId, listItem.message.id
                        )
                    )
                    onConversationSelected(listItem.message.conversationId)
                },
                onContactClick = { odinId ->
                    onUiAction(ConversationListUiAction.ShowContactInfo(odinId.domainName))
                },
            )
        }
    }
}
