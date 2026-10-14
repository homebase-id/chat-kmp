package id.homebase.chat.calling.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.call_button_video
import id.homebase.resources.call_button_voice
import id.homebase.resources.calls_contact_not_connected
import id.homebase.resources.calls_new_call
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.chat_no_contacts_found
import id.homebase.resources.chat_search_result_empty
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun NewCallScreen(
    viewModel: NewCallViewModel,
    onBack: () -> Unit,
    onStartCall: (peer: OdinId, video: Boolean) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is NewCallEvent.StartCall -> {
                    onStartCall(event.peer, event.video)
                    onBack()
                }
            }
        }
    }
    NewCallContent(state, viewModel.searchTextState, viewModel::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCallContent(
    state: NewCallUiState,
    searchTextState: TextFieldState,
    onAction: (NewCallAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable (NewCallRow) -> Unit = { row -> NewCallAvatar(row) },
) {
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.calls_new_call)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            StyledSearchTextField(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                textFieldState = searchTextState,
                showSearchIcon = false,
                placeHolderText = stringResource(MR.string.chat_new_conversation_search_placeholder),
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.groups.isEmpty()) {
                    item {
                        Text(
                            if (searchTextState.text.isNotEmpty()) {
                                stringResource(MR.string.chat_search_result_empty, searchTextState.text.toString())
                            } else {
                                stringResource(MR.string.chat_no_contacts_found)
                            },
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                state.groups.forEach { group ->
                    stickyHeader(key = "initial_${group.initial}") {
                        Text(
                            group.initial,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(group.rows, key = { it.contact.odinId.domainName }) { row ->
                        NewCallRowItem(row, state.callable, onAction, avatar)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewCallRowItem(
    row: NewCallRow,
    callable: Boolean,
    onAction: (NewCallAction) -> Unit,
    avatar: @Composable (NewCallRow) -> Unit,
) {
    val enabled = row.connected && callable
    val contentAlpha = if (row.connected) 1f else 0.6f
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar(row)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.contact.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (row.connected) row.contact.odinId.domainName else stringResource(MR.string.calls_contact_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(enabled = enabled, onClick = { onAction(NewCallAction.Start(row.contact, video = false)) }) {
            Icon(Icons.Default.Call, contentDescription = stringResource(MR.string.call_button_voice))
        }
        IconButton(enabled = enabled, onClick = { onAction(NewCallAction.Start(row.contact, video = true)) }) {
            Icon(Icons.Default.Videocam, contentDescription = stringResource(MR.string.call_button_video))
        }
    }
}

@Composable
private fun NewCallAvatar(row: NewCallRow) {
    ContactAvatar(
        odinId = row.contact.odinId,
        profileImageData = null,
        initials = row.contact.avatarInitials,
        options = AvatarOptions(size = 40.dp, fontSize = 16.sp),
    )
}
