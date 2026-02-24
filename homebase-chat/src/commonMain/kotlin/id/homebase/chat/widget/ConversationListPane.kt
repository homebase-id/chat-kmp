package id.homebase.chat.widget

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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import id.homebase.chat.ConversationListUiAction
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.widget.requests.ConnectionRequestBanner
import id.homebase.core.ui.assets.FeatherEdit
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.AvatarImage
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_filter_by_unread_button
import id.homebase.resources.chat_filter_by_unread_clear_button
import id.homebase.resources.chat_filter_by_unread_description
import id.homebase.resources.chat_filter_by_unread_empty_description
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_search_placeholder
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListPane(
    conversations: ImmutableList<ConversationUiModel>,
    selectedConversationId: Uuid? = null,
    onProfileClick: () -> Unit,
    onConversationClick: (Uuid) -> Unit,
    onConnectionRequestsClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    incomingRequests: List<IncomingConnectionRequestUiModel>
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val twoPaneWindow =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val searchState = rememberTextFieldState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var filterByUnread by remember { mutableStateOf(false) }
    var selectedFilterConversationId by remember { mutableStateOf<Uuid?>(null) }
    val filteredConversations = remember(conversations, filterByUnread) {
        if (filterByUnread) {
            conversations.filter { it.unreadCount > 0 || it.id == selectedFilterConversationId }
        } else {
            conversations
        }
    }

    // Request focus on box element to prevent soft keyboard popping up
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
    ) {
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
                                        })
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = stringResource(MR.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        )

                        ConnectionRequestBanner(
                            incomingRequests = incomingRequests,
                            onClick = onConnectionRequestsClick
                        )
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MinimalSearchTextField(
                                textFieldState = searchState,
                                modifier = Modifier.weight(1f),
                                placeHolderText = stringResource(MR.string.chat_search_placeholder)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(
                                onClick = {
                                    filterByUnread = !filterByUnread
                                    if (!filterByUnread) {
                                        selectedFilterConversationId = null
                                    }
                                }, colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (filterByUnread) HomebaseTheme.extendedColors.bubbleSentSurface else Color.Unspecified
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = stringResource(MR.string.chat_filter_by_unread_button),
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
                                onUiAction(ConversationListUiAction.NewChatClicked)
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
            floatingActionButton = {
                if (!iconOnlyMode) {
                    FloatingActionButton(onClick = {
                        onUiAction(ConversationListUiAction.NewChatClicked)
                    }) {
                        Icon(
                            imageVector = FeatherEdit,
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
                            Text(
                                text = stringResource(MR.string.chat_filter_by_unread_description),
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                    items(filteredConversations.toList()) { conversation ->
                        if (iconOnlyMode) {
                            ConversationAvatarItem(
                                conversation = conversation, onClick = {
                                    if (filterByUnread) {
                                        selectedFilterConversationId = conversation.id
                                    }
                                    onConversationClick(conversation.id)
                                }, isSelected = conversation.id == selectedConversationId
                            )
                        } else {
                            ConversationItem(
                                conversation = conversation, onClick = {
                                    if (filterByUnread) {
                                        selectedFilterConversationId = conversation.id
                                    }
                                    onConversationClick(conversation.id)
                                }, isSelected = conversation.id == selectedConversationId
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
                                            text = stringResource(MR.string.chat_filter_by_unread_empty_description),
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
                                        Text(text = stringResource(MR.string.chat_filter_by_unread_clear_button))
                                    }
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