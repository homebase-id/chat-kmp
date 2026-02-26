package id.homebase.chat.groupsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.createconversation.ContactItem
import id.homebase.chat.widget.AvatarNameDisplay
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.core.widget.ListItemAction
import id.homebase.resources.MR
import id.homebase.resources.chat_group_add_members
import id.homebase.resources.chat_group_selected_members
import id.homebase.resources.chat_message_edit
import id.homebase.resources.menu_back
import id.homebase.resources.you
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun GroupSettingsScreen(
    viewModel: GroupSettingsViewModel,
    onNavigateBack: () -> Unit,
    onShowContactInfo: (odinId: String) -> Unit,
    onAddMembers: (conversationId: String) -> Unit,
    onEditGroup: (conversationId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val event = uiState.uiEvent) {
        is GroupSettingsUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        is GroupSettingsUiEvent.ShowContactInfo -> {
            viewModel.eventConsumed()
            onShowContactInfo(event.odinId)
        }

        is GroupSettingsUiEvent.ShowAddMembers -> {
            viewModel.eventConsumed()
            onAddMembers(event.conversationId)
        }

        is GroupSettingsUiEvent.ShowEditGroup -> {
            viewModel.eventConsumed()
            onEditGroup(event.conversationId)
        }

        null -> {}
    }

    GroupSettingsUi(
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsUi(
    uiState: GroupSettingsUiState,
    onUiAction: (GroupSettingsUiAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onUiAction(GroupSettingsUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onUiAction(GroupSettingsUiAction.EditGroupClicked) }) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(MR.string.chat_message_edit)
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding)
        ) {
            if (uiState.conversation == null) {
                if (uiState.isLoading) {
                    LoadingListItem()
                } else {
                    ErrorInfoItem("No group could be loaded")
                }
            }

            uiState.conversation?.let { conversation ->
                AvatarNameDisplay(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    displayName = conversation.name,
                    avatarUrl = conversation.avatarUrl,
                    avatarInitials = conversation.avatarInitials,
                    avatarTiny = conversation.avatarTiny,
                    isGroupConversation = true,
                )
                Spacer(modifier = Modifier.height(32.dp))
                HorizontalDivider()
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp).padding(top = 32.dp),
                    text = pluralStringResource(
                        MR.plurals.chat_group_selected_members,
                        uiState.contacts.size + 1,
                        uiState.contacts.size + 1
                    ),
                    style = MaterialTheme.typography.titleLarge
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    item {
                        ListItemAction(
                            imageVector = Icons.Default.Add,
                            text = stringResource(MR.string.chat_group_add_members),
                            onClick = {
                                onUiAction(GroupSettingsUiAction.AddMembersClicked)
                            }
                        )
                    }
                    item {
                        ContactItem(
                            name = stringResource(MR.string.you),
                            subTitle = uiState.currentOdinId,
                            avatarUrl = "",
                            avatarInitials = "HU",
                            onContactClick = {},
                        )
                    }
                    items(uiState.contacts) { contact ->
                        ContactItem(
                            name = contact.name,
                            subTitle = contact.odinId.domainName,
                            avatarUrl = contact.avatarUrl,
                            avatarInitials = contact.avatarInitials,
                            onContactClick = {
                                onUiAction(
                                    GroupSettingsUiAction.ShowContactInfo(contact)
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}