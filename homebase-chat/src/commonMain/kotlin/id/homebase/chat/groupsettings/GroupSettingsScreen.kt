package id.homebase.chat.groupsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.chat.createconversation.ContactItem
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.chat.widget.AvatarNameDisplay
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.widget.ContactName
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.core.widget.ListItemAction
import id.homebase.core.widget.ListItemActionNormalIcon
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.error_no_group_loaded
import id.homebase.resources.chat_group_add_members
import id.homebase.resources.chat_group_admin
import id.homebase.resources.chat_group_admin_file_delivered
import id.homebase.resources.chat_group_admin_file_problem
import id.homebase.resources.chat_group_choose_new_admin
import id.homebase.resources.chat_group_heal
import id.homebase.resources.chat_group_heal_completed
import id.homebase.resources.chat_group_heal_completed_nothing
import id.homebase.resources.chat_group_main_file_delivered
import id.homebase.resources.chat_group_main_file_problem
import id.homebase.resources.chat_group_choose_new_admin_disclaimer
import id.homebase.resources.chat_group_leave
import id.homebase.resources.chat_group_leave_disclaimer
import id.homebase.resources.chat_group_leave_legacy_admin_confirm
import id.homebase.resources.chat_group_leave_legacy_admin_disclaimer
import id.homebase.resources.chat_group_leaving_in_progress
import id.homebase.resources.chat_group_legacy_banner
import id.homebase.resources.chat_group_member_op_in_progress
import id.homebase.resources.chat_group_make_admin
import id.homebase.resources.chat_group_make_admin_dislaimer
import id.homebase.resources.chat_group_remove
import id.homebase.resources.chat_group_remove_admin
import id.homebase.resources.chat_group_remove_admin_dislaimer
import id.homebase.resources.chat_group_remove_member
import id.homebase.resources.chat_group_selected_members
import id.homebase.resources.chat_message_edit
import id.homebase.resources.connect
import id.homebase.resources.leave
import id.homebase.resources.menu_back
import id.homebase.resources.not_connected
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val healCompletedMessage = stringResource(MR.string.chat_group_heal_completed)
    val healCompletedNothingMessage = stringResource(MR.string.chat_group_heal_completed_nothing)

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

        is GroupSettingsUiEvent.HealCompleted -> {
            viewModel.eventConsumed()
            val message = if (event.mainHealed || event.adminHealed) {
                healCompletedMessage
            } else {
                healCompletedNothingMessage
            }
            scope.launch { snackbarHostState.showSnackbar(message = message) }
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

    Box(modifier = Modifier.fillMaxSize()) {
        GroupSettingsUi(
            snackbarHostState = snackbarHostState,
            uiState = uiState,
            onUiAction = viewModel::onUiAction
        )

        if (uiState.isLeaving) {
            LeavingGroupOverlay()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LeavingGroupOverlay() {
    // Swallow the system back gesture so the user can't navigate mid-leave —
    // matches the LogoutOverlay pattern in SettingsScreen.kt.
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { /* no-op */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            // Absorb every tap so the underlying screen can't receive any input
            // while the leave is in flight.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(MR.string.chat_group_leaving_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                    ErrorInfoItem(stringResource(MR.string.error_no_group_loaded))
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
                        GroupParticipantRow(
                            name = contact.name,
                            subTitle = contact.odinId.domainName,
                            annotation = if (conversation.isCurrentUserAdmin(contact.odinId)) stringResource(
                                MR.string.chat_group_admin
                            ) else null,
                            avatarInitials = contact.avatarInitials,
                            odinId = contact.odinId,
                            onClick = {
                                onUiAction(
                                    GroupSettingsUiAction.ShowMemberSheet(contact)
                                )
                            },
                            mainStatus = uiState.mainFileTransfer?.get(contact.odinId),
                            adminStatus = uiState.adminFileTransfer?.get(contact.odinId),
                            showMainColumn = uiState.mainFileTransfer != null,
                            showAdminColumn = uiState.adminFileTransfer != null,
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
                            GroupParticipantRow(
                                name = contact.name,
                                subTitle = contact.odinId.domainName,
                                annotation = stringResource(MR.string.connect),
                                annotationColor = MaterialTheme.colorScheme.primary,
                                avatarInitials = contact.avatarInitials,
                                odinId = contact.odinId,
                                onClick = {
                                    onUiAction(
                                        GroupSettingsUiAction.ConnectToIdentity(contact.odinId)
                                    )
                                },
                                mainStatus = uiState.mainFileTransfer?.get(contact.odinId),
                                adminStatus = uiState.adminFileTransfer?.get(contact.odinId),
                                showMainColumn = uiState.mainFileTransfer != null,
                                showAdminColumn = uiState.adminFileTransfer != null,
                            )
                        }
                    }
                    if (uiState.canHeal) {
                        item {
                            HorizontalDivider()
                            HealGroupButton(
                                isHealing = uiState.isHealing,
                                onClick = { onUiAction(GroupSettingsUiAction.HealGroupClicked) }
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

        is GroupSettingsUiDialog.LeaveLegacyAdminWarning -> {
            Dialog(onDismissRequest = { onDialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.chat_group_leave_legacy_admin_confirm),
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
                        text = stringResource(MR.string.chat_group_leave_legacy_admin_disclaimer),
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

                        // While a server op is in flight for this contact, swap the
                        // action rows for an inline spinner. The op-tracking is
                        // managed in the VM (runMemberOp helper).
                        val isPending = uiState.pendingMemberOps.contains(contactInfo.odinId)
                        if (isPending) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(MR.string.chat_group_member_op_in_progress),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (uiState.isCurrentUserGroupAdmin && !uiState.isLegacyGroup) {
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

/**
 * GroupSettings-only participant row. Mirrors the shared [ContactItem] layout but inlines
 * the per-file delivery indicators next to the name (the indicators are a diagnostic aid
 * specific to this screen and don't belong on the shared widget).
 */
@Composable
private fun GroupParticipantRow(
    name: String,
    subTitle: String?,
    annotation: String?,
    annotationColor: Color? = null,
    avatarInitials: String,
    odinId: OdinId,
    onClick: () -> Unit,
    mainStatus: RecipientFileStatus?,
    adminStatus: RecipientFileStatus?,
    showMainColumn: Boolean,
    showAdminColumn: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactName(
                    odinId = odinId,
                    knownName = name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (showMainColumn || showAdminColumn) {
                    Spacer(modifier = Modifier.width(8.dp))
                    if (showMainColumn) {
                        TransferStatusIcon(
                            mainStatus,
                            stringResource(MR.string.chat_group_main_file_delivered),
                            stringResource(MR.string.chat_group_main_file_problem),
                        )
                    }
                    if (showMainColumn && showAdminColumn) {
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    if (showAdminColumn) {
                        TransferStatusIcon(
                            adminStatus,
                            stringResource(MR.string.chat_group_admin_file_delivered),
                            stringResource(MR.string.chat_group_admin_file_problem),
                        )
                    }
                }
            }
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
        annotation?.let {
            Text(
                text = annotation,
                style = MaterialTheme.typography.labelSmall,
                color = annotationColor ?: LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TransferStatusIcon(
    status: RecipientFileStatus?,
    okDescription: String,
    problemDescription: String,
) {
    val size = 12.dp
    when (status) {
        RecipientFileStatus.Ok -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = okDescription,
            tint = LocalContentColor.current.copy(alpha = 0.55f),
            modifier = Modifier.size(size)
        )
        is RecipientFileStatus.Problem -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = problemDescription,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(size)
        )
        null -> Spacer(modifier = Modifier.size(size))
    }
}

@Composable
private fun HealGroupButton(
    isHealing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isHealing, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isHealing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Icon(
                imageVector = Icons.Default.Healing,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(MR.string.chat_group_heal),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}