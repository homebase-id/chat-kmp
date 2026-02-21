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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import id.homebase.chat.conversationlist.ConversationListContentModel
import id.homebase.chat.conversationlist.ConversationListContentState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.core.ui.assets.FeatherEdit
import id.homebase.core.widget.AvatarImage
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_filter_by_unread_clear_button
import id.homebase.resources.chat_filter_by_unread_description
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_options
import id.homebase.resources.chat_search_empty_description
import id.homebase.resources.chat_search_placeholder
import id.homebase.resources.chat_search_result_empty
import id.homebase.resources.search
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListPane(
    listContent: ConversationListContentState,
    selectedConversationId: Uuid? = null,
    filterByUnread: Boolean,
    isSearchActive: Boolean,
    searchTextState: TextFieldState,
    onProfileClick: () -> Unit,
    onConversationClick: (conversationId: Uuid, messageId: Uuid?) -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val twoPaneWindow =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val listState = rememberLazyListState()
    val focusRequesterNone = remember { FocusRequester() }
    val focusRequesterSearch = remember { FocusRequester() }
    var showMenu by remember { mutableStateOf(false) }

    // Request focus on box element to prevent soft keyboard popping up
    LaunchedEffect(Unit) {
        focusRequesterNone.requestFocus()
    }
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequesterSearch.requestFocus()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .focusRequester(focusRequesterNone)
            .focusable()
    ) {
        val iconOnlyMode by derivedStateOf { maxWidth <= 96.dp }
        Scaffold(
            topBar = {
                if (!iconOnlyMode) {
                    TopAppBar(
                        title = {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                // Title row - keep it in place but fade out
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Spacer(modifier = Modifier.width(20.dp))
                                    AnimatedVisibility(
                                        visible = !isSearchActive,
                                        enter = fadeIn(
                                            animationSpec = tween(
                                                300,
                                                delayMillis = 200
                                            )
                                        ),
                                        exit = fadeOut(animationSpec = tween(150))
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AvatarImage(
                                                avatarUrl = null,
                                                avatarInitials = "CH",
                                                size = 32.dp,
                                                fontSize = 12.sp,
                                                onClick = {
                                                    onProfileClick()
                                                })
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(
                                                text = stringResource(MR.string.app_name),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                // Search field - positioned absolutely on top
                                AnimatedVisibility(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxWidth()
                                        .padding(end = 16.dp),
                                    visible = isSearchActive,
                                    enter = fadeIn(
                                        animationSpec = tween(200)
                                    ) + expandHorizontally(
                                        animationSpec = tween(300),
                                        expandFrom = Alignment.End
                                    ),
                                    exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(
                                        animationSpec = tween(250),
                                        shrinkTowards = Alignment.End
                                    )
                                ) {
                                    MinimalSearchTextField(
                                        textFieldState = searchTextState,
                                        modifier = Modifier.fillMaxWidth()
                                            .focusRequester(focusRequesterSearch),
                                        placeHolderText = stringResource(MR.string.chat_search_placeholder),
                                        showBackButton = true,
                                        onBackButtonClick = {
                                            onUiAction(ConversationListUiAction.SearchBackClicked)
                                            searchTextState.clearText()
                                        }
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!isSearchActive) {
                                IconButton(
                                    onClick = {
                                        onUiAction(ConversationListUiAction.SearchClicked)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = stringResource(MR.string.search),
                                    )
                                }
                                Box {
                                    IconButton(onClick = {
                                        showMenu = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource(MR.string.chat_options)
                                        )
                                    }
                                    ConversationListMenu(
                                        showMenu = showMenu,
                                        dismissMenu = { showMenu = false },
                                        isFilteringUnread = filterByUnread,
                                        onMarkAllAsRead = {
                                            // TODO
                                            showMenu = false
                                        },
                                        onFilterUnread = {
                                            onUiAction(ConversationListUiAction.FilterByUnreadClicked)
                                            showMenu = false
                                        },
                                        onClearFilterUnread = {
                                            onUiAction(ConversationListUiAction.ClearFilterByUnreadClicked)
                                            showMenu = false
                                        },
                                        onSettings = {
                                            onProfileClick()
                                            showMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        IconButton(onClick = {
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
                if (!iconOnlyMode) {
                    FloatingActionButton(onClick = {
                        onUiAction(ConversationListUiAction.NewConversationClicked)
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(MR.string.chat_new_conversation)
                        )
                    }
                }
            },
            containerColor = if (twoPaneWindow) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        ) { innerPadding ->
            Box {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    state = listState,
                ) {
                    if (filterByUnread) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                InputChip(
                                    onClick = {
                                        onUiAction(ConversationListUiAction.ClearFilterByUnreadClicked)
                                    },
                                    label = { Text(text = stringResource(MR.string.chat_filter_by_unread_description)) },
                                    selected = true,
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Localized description",
                                            //Modifier.size(InputChipDefaults.AvatarSize)
                                        )
                                    },
                                )
                            }
                        }
                    }
                    when (listContent) {
                        is ConversationListContentState.Loading -> {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator()
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
                                        text = stringResource(MR.string.chat_search_empty_description),
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
                                            listContent.query
                                        )
                                    )
                                }
                            }
                        }

                        is ConversationListContentState.Items -> {
                            items(listContent.list) { listItem ->
                                ConversationLisContentItem(
                                    listItem = listItem,
                                    selectedConversationId = selectedConversationId,
                                    iconOnlyMode = iconOnlyMode,
                                    onConversationClick = onConversationClick,
                                    onUiAction = onUiAction
                                )
                            }
                        }

                    }

                    if (filterByUnread) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                ElevatedButton(
                                    onClick = {
                                        onUiAction(ConversationListUiAction.ClearFilterByUnreadClicked)
                                    },
                                    colors = ButtonDefaults.elevatedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                ) {
                                    Text(text = stringResource(MR.string.chat_filter_by_unread_clear_button))
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
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
    onConversationClick: (conversationId: Uuid, messageId: Uuid?) -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    when (listItem) {
        is ConversationListContentModel.Header -> {
            Text(stringResource(listItem.resource), modifier = Modifier.padding(16.dp))
        }

        is ConversationListContentModel.Conversation -> {
            if (iconOnlyMode) {
                ConversationAvatarItem(
                    avatarUrl = listItem.conversation.avatarUrl,
                    avatarInitials = listItem.conversation.avatarInitials,
                    onClick = { onConversationClick(listItem.conversation.id, null) },
                    isSelected = listItem.conversation.id == selectedConversationId,
                )
            } else {
                ConversationItem(
                    groupName = listItem.conversation.name,
                    message = listItem.conversation.lastMessage,
                    unreadCount = listItem.conversation.unreadCount,
                    avatarUrl = listItem.conversation.avatarUrl,
                    avatarInitials = listItem.conversation.avatarInitials,
                    contactOdinId = listItem.conversation.participants.firstOrNull(),
                    timestamp = listItem.conversation.timestamp,
                    onClick = { onConversationClick(listItem.conversation.id, null) },
                    onContactClick = { odinId ->
                        onUiAction(ConversationListUiAction.ShowContactInfo(odinId.domainName))
                    },
                    isSelected = listItem.conversation.id == selectedConversationId,
                )
            }
        }

        is ConversationListContentModel.Message -> {
            // TODO - get message info for display
            ConversationItem(
                groupName = "how to get name from message",
                message = listItem.message.content,
                unreadCount = 0,
                avatarUrl = "",
                avatarInitials = "MS",
                contactOdinId = listItem.message.originalAuthor,
                timestamp = listItem.message.created,
                onClick = { onConversationClick(listItem.message.conversationId, listItem.message.id) },
                onContactClick = { odinId ->
                    onUiAction(ConversationListUiAction.ShowContactInfo(odinId.domainName))
                },
                isSelected = false,
            )
        }
    }
}