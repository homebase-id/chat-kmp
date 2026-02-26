package id.homebase.chat.createconversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Circle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.widget.ListItemAction
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.chat_new_conversation_new_group
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.chat_no_contacts_found
import id.homebase.resources.chat_search_result_empty
import id.homebase.resources.contacts
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun CreateConversationScreen(
    viewModel: CreateConversationViewModel,
    onNavigateBack: () -> Unit,
    onShowConversation: (conversationId: Uuid) -> Unit,
    onShowCreateGroup: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is CreateConversationUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is CreateConversationUiEvent.ShowErrorMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is CreateConversationUiEvent.LoadConversation -> {
                viewModel.eventConsumed()
                onShowConversation(event.conversationId)
            }

            CreateConversationUiEvent.ShowCreateGroupScreen -> {
                viewModel.eventConsumed()
                onShowCreateGroup()
            }
        }
    }

    CreateConversationUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateConversationUi(
    snackbarHostState: SnackbarHostState,
    uiState: CreateConversationUiState,
    searchTextState: TextFieldState,
    onUiAction: (CreateConversationUiAction) -> Unit,
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
                    IconButton(onClick = { onUiAction(CreateConversationUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues)
        ) {
            StyledSearchTextField(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .focusRequester(focusRequester),
                textFieldState = searchTextState,
                showSearchIcon = false,
                placeHolderText = stringResource(MR.string.chat_new_conversation_search_placeholder),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
            ) {
                uiState.displayItems.forEach { item ->
                    when (item) {
                        is CreateConversationListItem.Contacts -> {
                            if (item.contactGroups.isEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        if (searchTextState.text.isNotEmpty()) {
                                            Text(
                                                text = stringResource(
                                                    MR.string.chat_search_result_empty,
                                                    searchTextState.text.toString()
                                                )
                                            )
                                        } else {
                                            Text(text = stringResource(MR.string.chat_no_contacts_found))
                                        }
                                    }
                                }
                            } else if (searchTextState.text.isNotEmpty()) {
                                item {
                                    Text(
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                            .padding(top = 16.dp),
                                        text = stringResource(MR.string.contacts),
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            }
                            item.contactGroups.forEach { contactGroup ->
                                stickyHeader {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = contactGroup.initial,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                                items(contactGroup.contacts) { contact ->
                                    ContactItem(
                                        name = contact.name,
                                        subTitle = contact.odinId.domainName,
                                        odinId = contact.odinId,
                                        avatarInitials = contact.avatarInitials,
                                        onContactClick = {
                                            onUiAction(
                                                CreateConversationUiAction.ContactClicked(contact)
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        is CreateConversationListItem.NewGroup -> {
                            item {
                                ListItemAction(
                                    imageVector = Icons.Default.Group,
                                    text = stringResource(MR.string.chat_new_conversation_new_group),
                                    onClick = { onUiAction(CreateConversationUiAction.CreateNewGroup) }
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
fun ContactItem(
    name: String,
    subTitle: String? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    odinId: OdinId,
    avatarInitials: String,
    onContactClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = {
                onContactClick()
            })
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            odinId = odinId,
            profileImageData = null,
            initials = avatarInitials,
            options = AvatarOptions(
                size = 28.dp,
                fontSize = 12.sp,
            ),
            sharedTransitionScope = null,
            animatedVisibilityScope = null
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subTitle?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selectionMode) {
            if (isSelected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
            } else {
                Icon(Icons.Outlined.Circle, contentDescription = null)
            }
        }
    }
}