package id.homebase.core.ui.screens.location

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.createconversation.ContactItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.ui.screens.contactbook.CircleAddFailureReason
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.chat_search_result_empty
import id.homebase.resources.circle_member_add_drive_access_denied
import id.homebase.resources.circle_member_add_generic_failed
import id.homebase.resources.contacts
import id.homebase.resources.location_emergency_add_already_member
import id.homebase.resources.location_emergency_add_none_eligible
import id.homebase.resources.location_emergency_add_succeeded
import id.homebase.resources.location_emergency_add_title
import id.homebase.resources.menu_back
import id.homebase.resources.remove
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun EmergencyContactPickerScreen(
    viewModel: EmergencyContactPickerViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val addedLabel = stringResource(MR.string.location_emergency_add_succeeded)
    val alreadyMemberLabel = stringResource(MR.string.location_emergency_add_already_member)
    val driveAccessDeniedLabel = stringResource(MR.string.circle_member_add_drive_access_denied)
    val genericFailedLabel = stringResource(MR.string.circle_member_add_generic_failed)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                EmergencyContactPickerUiEvent.Back -> onNavigateBack()
                is EmergencyContactPickerUiEvent.AddCompleted -> {
                    // Failures lead with the actual reason — a bare "Failed: 1" told the user
                    // nothing happened worth acting on. Only DriveAccessDenied and a real 400's
                    // message are genuinely explanatory; an opaque 403 gets a generic message
                    // rather than surfacing internal detail as if it were actionable.
                    val parts = buildList {
                        if (event.added > 0) add("$addedLabel: ${event.added}")
                        if (event.alreadyMember > 0) add("$alreadyMemberLabel: ${event.alreadyMember}")
                        event.failures.forEach { f ->
                            val reason = when (val r = f.reason) {
                                is CircleAddFailureReason.Raw -> r.message
                                CircleAddFailureReason.DriveAccessDenied -> driveAccessDeniedLabel
                                CircleAddFailureReason.OpaqueForbidden -> genericFailedLabel
                            }
                            add("${f.name}: $reason")
                        }
                    }
                    if (parts.isNotEmpty()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = parts.joinToString(" · "),
                                duration = if (event.failures.isNotEmpty()) SnackbarDuration.Long
                                else SnackbarDuration.Short,
                            )
                        }
                    }
                }
            }
        }
    }

    EmergencyContactPickerUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmergencyContactPickerUi(
    snackbarHostState: SnackbarHostState,
    uiState: EmergencyContactPickerUiState,
    searchTextState: TextFieldState,
    onUiAction: (EmergencyContactPickerUiAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.location_emergency_add_title)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(EmergencyContactPickerUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.selectedContacts.isNotEmpty()) {
                Button(
                    onClick = { onUiAction(EmergencyContactPickerUiAction.AddClicked) },
                    modifier = Modifier.defaultMinSize(minWidth = 56.dp),
                    enabled = !uiState.submitting,
                    shape = CircleShape,
                ) {
                    if (uiState.submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = stringResource(MR.string.location_emergency_add_title))
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            StyledSearchTextField(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                textFieldState = searchTextState,
                showSearchIcon = false,
                placeHolderText = stringResource(MR.string.chat_new_conversation_search_placeholder),
            )
            if (uiState.selectedContacts.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)) {
                    items(uiState.selectedContacts) { contact ->
                        InputChip(
                            modifier = Modifier.widthIn(max = 200.dp).padding(end = 8.dp),
                            onClick = {},
                            label = {
                                Text(text = contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            selected = true,
                            leadingIcon = {
                                ContactAvatar(
                                    odinId = contact.odinId,
                                    profileImageData = null,
                                    initials = contact.avatarInitials,
                                    options = AvatarOptions(size = 28.dp, fontSize = 12.sp),
                                    sharedTransitionScope = null,
                                    animatedVisibilityScope = null,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    modifier = Modifier.clickable {
                                        onUiAction(EmergencyContactPickerUiAction.ContactClicked(contact))
                                    },
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(MR.string.remove),
                                )
                            },
                        )
                    }
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
                            Text(
                                text = if (searchTextState.text.isNotEmpty()) {
                                    stringResource(MR.string.chat_search_result_empty, searchTextState.text.toString())
                                } else {
                                    stringResource(MR.string.location_emergency_add_none_eligible)
                                },
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            modifier = Modifier.padding(bottom = 16.dp, start = 16.dp),
                            text = stringResource(MR.string.contacts),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                uiState.displayItems.forEach { group ->
                    stickyHeader {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(text = group.initial, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    items(group.contacts, key = { it.odinId.domainName }) { contact ->
                        ContactItem(
                            name = contact.name,
                            subTitle = contact.odinId.domainName,
                            selectionMode = true,
                            isSelected = uiState.selectedContacts.contains(contact),
                            odinId = contact.odinId,
                            avatarInitials = contact.avatarInitials,
                            onContactClick = {
                                onUiAction(EmergencyContactPickerUiAction.ContactClicked(contact))
                            },
                        )
                    }
                }
            }
        }
    }
}
