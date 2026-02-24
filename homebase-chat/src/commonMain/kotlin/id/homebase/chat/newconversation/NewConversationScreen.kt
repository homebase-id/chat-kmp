package id.homebase.chat.newconversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.widget.AvatarImage
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_new_conversation_new_group
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun NewConversationScreen(
    viewModel: NewConversationViewModel,
    onNavigateBack: () -> Unit,
    onShowConversation: (conversationId: Uuid) -> Unit,
    onShowCreateGroup: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    when (val event = uiState.uiEvent) {
        null -> {}
        is NewConversationUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        is NewConversationUiEvent.ShowErrorMessage -> {
            viewModel.eventConsumed()
            scope.launch { snackbarHostState.showSnackbar(message = event.message) }
        }

        is NewConversationUiEvent.LoadConversation -> {
            viewModel.eventConsumed()
            onShowConversation(event.conversationId)
        }

        NewConversationUiEvent.ShowCreateGroupScreen -> {
            viewModel.eventConsumed()
            onShowCreateGroup()
        }
    }

    ContactInfoUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoUi(
    snackbarHostState: SnackbarHostState,
    uiState: NewConversationUiState,
    searchTextState: TextFieldState,
    onUiAction: (NewConversationUiAction) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(MR.string.chat_new_conversation))
                },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(NewConversationUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues)
        ) {
            StyledSearchTextField(
                modifier = Modifier.padding(16.dp).fillMaxWidth().focusRequester(focusRequester),
                textFieldState = searchTextState,
                showSearchIcon = false,
                placeHolderText = stringResource(MR.string.chat_new_conversation_search_placeholder),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
            ) {
                uiState.items.forEach { item ->
                    when (item) {
                        is NewConversationListItem.Contacts -> {
                            item.contactGroups.forEach { contactGroup ->
                                stickyHeader {
                                    Text(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 8.dp
                                        ),
                                        text = contactGroup.initial,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                items(contactGroup.contacts) { contact ->
                                    ContactItem(
                                        name = contact.name,
                                        avatarUrl = contact.avatarUrl,
                                        avatarInitials = contact.avatarInitials,
                                        contactOdinId = contact.odinId,
                                        onContactClick = {
                                            onUiAction(
                                                NewConversationUiAction.CreateConversation(it)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        is NewConversationListItem.NewGroup -> {
                            item {
                                ListItemAction(
                                    imageVector = Icons.Default.Group,
                                    text = stringResource(MR.string.chat_new_conversation_new_group),
                                    onClick = { onUiAction(NewConversationUiAction.CreateNewGroup) }
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
fun ListItemAction(
    imageVector: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)   ,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ContactItem(
    name: String,
    avatarUrl: String,
    avatarInitials: String,
    contactOdinId: OdinId,
    onContactClick: (odinId: OdinId) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = {
                onContactClick(contactOdinId)
            })
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = avatarUrl,
            avatarInitials = avatarInitials,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}