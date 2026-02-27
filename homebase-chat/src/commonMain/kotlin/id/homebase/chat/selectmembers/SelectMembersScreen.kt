package id.homebase.chat.selectmembers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.chat.createconversation.ContactItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_group_select_members
import id.homebase.resources.chat_group_selected_members
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.chat_no_contacts_found
import id.homebase.resources.chat_search_result_empty
import id.homebase.resources.contacts
import id.homebase.resources.menu_back
import id.homebase.resources.next
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SelectMembersScreen(
    viewModel: SelectMembersViewModel,
    onNavigateBack: () -> Unit,
    onMembersSelected: (contactIds: List<String>) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is SelectMembersUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is SelectMembersUiEvent.ShowErrorMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is SelectMembersUiEvent.MembersSelected -> {
                viewModel.eventConsumed()
                onMembersSelected(event.contactIds)
            }
        }
    }

    SelectMembersUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectMembersUi(
    snackbarHostState: SnackbarHostState,
    uiState: SelectMembersUiState,
    searchTextState: TextFieldState,
    onUiAction: (SelectMembersUiAction) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.selectedContacts.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                MR.plurals.chat_group_selected_members,
                                uiState.selectedContacts.size,
                                uiState.selectedContacts.size
                            )
                        )
                    } else {
                        Text(stringResource(MR.string.chat_group_select_members))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(SelectMembersUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Button(
                onClick = { onUiAction(SelectMembersUiAction.NextClicked) },
                modifier = Modifier.defaultMinSize(minWidth = 56.dp),
                enabled = uiState.selectedContacts.size >= 2,
                shape = CircleShape

            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(MR.string.next)
                )
            }
        }
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
            LazyRow(
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp),

                ) {
                items(uiState.selectedContacts) { contact ->
                    InputChip(
                        modifier = Modifier.padding(end = 8.dp),
                        onClick = {
                            // onUiAction(NewConversationUiAction.ContactClicked(contact))
                        },
                        label = { Text(text = contact.name) },
                        selected = true,
                        leadingIcon = {
                            ContactAvatar(
                                odinId = contact.odinId,
                                profileImageData = null,
                                initials = contact.avatarInitials,
                                options = AvatarOptions(
                                    size = 28.dp,
                                    fontSize = 12.sp,
                                ),
                                sharedTransitionScope = null,
                                animatedVisibilityScope = null
                            )
                        },
                        trailingIcon = {
                            Icon(
                                modifier = Modifier.clickable {
                                    onUiAction(SelectMembersUiAction.ContactClicked(contact))
                                },
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                            )
                        }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
            ) {
                if (uiState.displayItems.isEmpty()) {
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
                } else {
                    item {
                        Text(
                            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 16.dp),
                            text = stringResource(MR.string.contacts),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                uiState.displayItems.forEach { item ->
                    stickyHeader {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = item.initial,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    items(item.contacts) { contact ->
                        ContactItem(
                            name = contact.name,
                            subTitle = contact.odinId.domainName,
                            selectionMode = true,
                            isSelected = uiState.selectedContacts.contains(contact),
                            odinId = contact.odinId,
                            avatarInitials = contact.avatarInitials,
                            onContactClick = {
                                onUiAction(
                                    SelectMembersUiAction.ContactClicked(contact)
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
