package id.homebase.chat.archivedconversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ConversationItem
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.resources.MR
import id.homebase.resources.chat_archived_chats
import id.homebase.resources.chat_archived_chats_empty
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ArchivedConversationsScreen(
    viewModel: ArchivedConversationsViewModel,
    onNavigateBack: () -> Unit,
    onShowConversation: (conversationId: Uuid) -> Unit,
    onNavigateToConversationSettings: (conversationId: String) -> Unit,
    onNavigateToGroupSettings: (conversationId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is ArchivedConversationsUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is ArchivedConversationsUiEvent.Error -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.errorMessage) }
            }

            is ArchivedConversationsUiEvent.NavigateToConversation -> {
                viewModel.eventConsumed()
                onShowConversation(event.conversationId)
            }

            is ArchivedConversationsUiEvent.NavigateToConversationSettings -> {
                viewModel.eventConsumed()
                onNavigateToConversationSettings(event.conversationId)
            }

            is ArchivedConversationsUiEvent.NavigateToGroupSettings -> {
                viewModel.eventConsumed()
                onNavigateToGroupSettings(event.conversationId)
            }
        }
    }

    ArchivedConversationsUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedConversationsUi(
    snackbarHostState: SnackbarHostState,
    uiState: ArchivedConversationsUiState,
    selectedConversationId: Uuid? = null,
    onUiAction: (ArchivedConversationsUiAction) -> Unit,
) {
    val listState = rememberLazyListState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(MR.string.chat_archived_chats))
                },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ArchivedConversationsUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .consumeWindowInsets(padding)
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                if (uiState.conversations.isEmpty()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(stringResource(MR.string.chat_archived_chats_empty))
                } else {
                    Box {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                        ) {
                            items(uiState.conversations) { conversation ->
                                ConversationItem(
                                    enrichedData = conversation,
                                    onClick = {
                                        onUiAction(ArchivedConversationsUiAction.ShowConversation(conversation.conversation.id))
                                    },
                                    onContactClick = {
                                        onUiAction(ArchivedConversationsUiAction.ShowConversationSettings(conversation.conversation))
                                    },
                                    onArchiveClick = {
                                        onUiAction(ArchivedConversationsUiAction.UnarchiveConversation(conversation.conversation.id))
                                    },
                                    isSelected = selectedConversationId == conversation.conversation.id,
                                )
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
    }
}