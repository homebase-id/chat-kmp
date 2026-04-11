package id.homebase.chat.groupsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalUriHandler
import id.homebase.chat.createconversation.ContactItem
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.chat.widget.AvatarNameDisplay
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.core.widget.ListItemAction
import id.homebase.core.widget.ListItemActionNormalIcon
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.connect
import id.homebase.resources.not_connected
import id.homebase.resources.chat_group_add_members
import id.homebase.resources.chat_group_legacy_banner
import id.homebase.resources.chat_group_admin
import id.homebase.resources.chat_group_choose_new_admin
import id.homebase.resources.chat_group_choose_new_admin_disclaimer
import id.homebase.resources.chat_group_leave
import id.homebase.resources.chat_group_leave_disclaimer
import id.homebase.resources.chat_group_make_admin
import id.homebase.resources.chat_group_make_admin_dislaimer
import id.homebase.resources.chat_group_remove
import id.homebase.resources.chat_group_remove_admin
import id.homebase.resources.chat_group_remove_admin_dislaimer
import id.homebase.resources.chat_group_remove_member
import id.homebase.resources.chat_group_selected_members
import id.homebase.resources.chat_message_edit
import id.homebase.resources.leave
import id.homebase.resources.menu_back
import id.homebase.resources.ok
import id.homebase.resources.remove
import id.homebase.resources.you
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    viewModel: GroupSettingsViewModel,
    onNavigateBack: () -> Unit,
    onShowContactInfo: (odinId: String) -> Unit,
    onAddMembers: (conversationId: String) -> Unit,
    onEditGroup: (conversationId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    when (val event = uiState.uiEvent) {
        is GroupSettingsUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        is GroupSettingsUiEvent.Error -> {
            viewModel.eventConsumed()
            scope.launch { snackbarHostState.showSnackbar(message = event.errorMessage) }
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

        is GroupSettingsUiEvent.OpenUrl -> {
            viewModel.eventConsumed()
            uriHandler.openUri(event.url)
        }

        null -> {}
    }

    GroupSettingsDialogs(
        uiState = uiState,
        onUiAction = viewModel::onUiAction,
        onDialogClosed = viewModel::dialogClosed
    )

    GroupSettingsSheets(
        uiState = uiState,
        onUiAction = viewModel::onUiAction,
        onSheetClosed = viewModel::bottomSheetDismissed
    )

    GroupSettingsUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsUi(
    snackbarHostState: SnackbarHostState,
    uiState: GroupSettingsUiState,
    onUiAction: (GroupSettingsUiAction) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    if (uiState.isCurrentUserGroupAdmin && !uiState.isLegacyGroup) {
                        IconButton(onClick = { onUiAction(GroupSettingsUiAction.EditGroupClicked) }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(MR.string.chat_message_edit)
                            )
                        }
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
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    item {
                        AvatarNameDisplay(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            displayName = conversation.name,
                            avatarModel = conversation.avatarModel,
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                    if (uiState.isLegacyGroup) {
                        item {
                            Text(
                                text = stringResource(MR.string.chat_group_legacy_banner),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                    item {
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
                    }
                    if (uiState.isCurrentUserGroupAdmin && !uiState.isLegacyGroup) {
                        item {
                            ListItemAction(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                imageVector = Icons.Default.Add,
                                text = stringResource(MR.string.chat_group_add_members),
                                onClick = {
                                    onUiAction(GroupSettingsUiAction.AddMembersClicked)
                                }
                            )
                        }
                    }
                    uiState.currentOdinId?.let { odinId ->
                        item {
                            ContactItem(
                                name = stringResource(MR.string.you),
                                subTitle = odinId.domainName,
                                annotation = if (uiState.isCurrentUserGroupAdmin) stringResource(MR.string.chat_group_admin) else null,
                                odinId = odinId,
                                avatarInitials = "HU",
                                onContactClick = {},
                            )
                        }
                    }
                    val (connectedContacts, notConnectedContacts) = uiState.contacts
                        .distinctBy { it.odinId }
                        .partition { contact ->
                            contact.connectionState == ContactConnectionState.Connected ||
                                    contact.connectionState == ContactConnectionState.Unknown
                        }

                    items(connectedContacts, key = { it.odinId.domainName }) { contact ->
                        ContactItem(
                            name = contact.name,
                            subTitle = contact.odinId.domainName,
                            annotation = if (conversation.isCurrentUserAdmin(contact.odinId)) stringResource(
                                MR.string.chat_group_admin
                            ) else null,
                            avatarInitials = contact.avatarInitials,
                            odinId = contact.odinId,
                            onContactClick = {
                                onUiAction(
                                    GroupSettingsUiAction.ShowMemberSheet(contact)
                                )
                            },
                        )
                    }

                    if (notConnectedContacts.isNotEmpty()) {
                        item {
                            Text(
                                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 8.dp),
                                text = stringResource(MR.string.not_connected),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        items(notConnectedContacts, key = { it.odinId.domainName }) { contact ->
                            ContactItem(
                                name = contact.name,
                                subTitle = contact.odinId.domainName,
                                annotation = stringResource(MR.string.connect),
                                annotationColor = MaterialTheme.colorScheme.primary,
                                avatarInitials = contact.avatarInitials,
                                odinId = contact.odinId,
                                onContactClick = {
                                    onUiAction(
                                        GroupSettingsUiAction.ConnectToIdentity(contact.odinId)
                                    )
                                },
                            )
                        }
                    }
                    item {
                        HorizontalDivider()
                        ListItemAction(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            text = stringResource(MR.string.chat_group_leave),
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                onUiAction(GroupSettingsUiAction.LeaveGroupClicked)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GroupSettingsDialogs(
    uiState: GroupSettingsUiState,
    onUiAction: (GroupSettingsUiAction) -> Unit,
    onDialogClosed: () -> Unit,
) {

    when (val dialog = uiState.uiDialog) {
        null -> {}
        is GroupSettingsUiDialog.ConfirmLeave -> {
            Dialog(onDismissRequest = { onDialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.leave),
                            onPrimaryClick = {
                                onUiAction(GroupSettingsUiAction.LeaveGroupConfirm)
                                onDialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = { onDialogClosed() },
                        )
                    }) {
                    DialogTitle(
                        text = stringResource(MR.string.chat_group_leave),
                    )
                    DialogText(
                        text = stringResource(MR.string.chat_group_leave_disclaimer),
                    )
                }
            }
        }

        is GroupSettingsUiDialog.LeaveChooseAdmin -> {
            Dialog(onDismissRequest = { onDialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.ok),
                            onPrimaryClick = { onDialogClosed() },
                        )
                    }) {
                    DialogTitle(
                        text = stringResource(MR.string.chat_group_choose_new_admin),
                    )
                    DialogText(
                        text = stringResource(MR.string.chat_group_choose_new_admin_disclaimer),
                    )
                }
            }
        }

        is GroupSettingsUiDialog.MakeAdmin -> {
            Dialog(onDismissRequest = { onDialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.chat_group_make_admin),
                            onPrimaryClick = {
                                onUiAction(GroupSettingsUiAction.MakeAdmin(dialog.contact, true))
                                onDialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = { onDialogClosed() },
                        )
                    }) {
                    DialogText(
                        text = stringResource(
                            MR.string.chat_group_make_admin_dislaimer,
                            dialog.contact.name
                        ),
                    )
                }
            }
        }

        is GroupSettingsUiDialog.RemoveAdmin -> {
            Dialog(onDismissRequest = { onDialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.chat_group_remove_admin),
                            onPrimaryClick = {
                                onUiAction(GroupSettingsUiAction.RemoveAdmin(dialog.contact, true))
                                onDialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = { onDialogClosed() },
                        )
                    }) {
                    DialogText(
                        text = stringResource(
                            MR.string.chat_group_remove_admin_dislaimer,
                            dialog.contact.name
                        ),
                    )
                }
            }
        }

        is GroupSettingsUiDialog.RemoveFromGroup -> {
            Dialog(onDismissRequest = { onDialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.remove),
                            onPrimaryClick = {
                                onUiAction(
                                    GroupSettingsUiAction.RemoveFromGroup(
                                        dialog.contact,
                                        true
                                    )
                                )
                                onDialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = { onDialogClosed() },
                        )
                    }) {
                    DialogText(
                        text = stringResource(
                            MR.string.chat_group_remove_member,
                            dialog.contact.name
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsSheets(
    uiState: GroupSettingsUiState,
    onUiAction: (GroupSettingsUiAction) -> Unit,
    onSheetClosed: () -> Unit,
) {
    when (val sheet = uiState.uiSheet) {
        null -> {}
        is GroupSettingsUiSheet.Member -> {
            val sheetState = rememberModalBottomSheetState()
            val contact by remember(uiState.contacts) { mutableStateOf(uiState.contacts.firstOrNull { it.id == sheet.contactId }) }

            ModalBottomSheet(
                onDismissRequest = { onSheetClosed() },
                sheetState = sheetState
            ) {
                // Bottom sheet content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    contact?.let { contactInfo ->
                        AvatarNameDisplay(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            displayName = contactInfo.name,
                            avatarModel = ConversationAvatarModel(
                                type = ConversationAvatarModel.Type.Connection,
                                odinId = contactInfo.odinId
                            ),
                            onClick = {
                                onUiAction(GroupSettingsUiAction.ShowContactInfo(contactInfo))
                                onSheetClosed()
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        if (uiState.isCurrentUserGroupAdmin && !uiState.isLegacyGroup) {
                            if (uiState.conversation?.isCurrentUserAdmin(contactInfo.odinId) == true) {
                                ListItemActionNormalIcon(
                                    modifier = Modifier.fillMaxWidth(),
                                    imageVector = Icons.Default.KeyOff,
                                    text = stringResource(MR.string.chat_group_remove_admin),
                                    onClick = {
                                        onUiAction(GroupSettingsUiAction.RemoveAdmin(contactInfo))
                                    }
                                )
                            } else {
                                ListItemActionNormalIcon(
                                    modifier = Modifier.fillMaxWidth(),
                                    imageVector = Icons.Default.Key,
                                    text = stringResource(MR.string.chat_group_make_admin),
                                    onClick = {
                                        onUiAction(GroupSettingsUiAction.MakeAdmin(contactInfo))
                                    }
                                )
                            }
                            ListItemActionNormalIcon(
                                modifier = Modifier.fillMaxWidth(),
                                imageVector = Icons.Default.DoNotDisturbOn,
                                text = stringResource(MR.string.chat_group_remove),
                                onClick = {
                                    onUiAction(GroupSettingsUiAction.RemoveFromGroup(contactInfo))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}