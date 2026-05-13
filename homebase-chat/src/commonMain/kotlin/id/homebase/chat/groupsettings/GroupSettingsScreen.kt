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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
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
import id.homebase.resources.chat_group_admin_peer_error
import id.homebase.resources.chat_group_admin_peer_loading
import id.homebase.resources.chat_group_admin_peer_missing
import id.homebase.resources.chat_group_admin_peer_mine
import id.homebase.resources.chat_group_admin_peer_other_author
import id.homebase.resources.chat_group_choose_new_admin
import id.homebase.api.client.connections.IntroductionPreflightStatus
import id.homebase.resources.chat_group_heal
import id.homebase.resources.chat_group_heal_admin_resent
import id.homebase.resources.chat_introduce_preflight_reason_not_configured
import id.homebase.resources.chat_introduce_preflight_reason_not_connected
import id.homebase.resources.chat_introduce_preflight_reason_not_permitted
import id.homebase.resources.chat_introduce_preflight_reason_rejected
import id.homebase.resources.chat_introduce_preflight_reason_requires_upgrade
import id.homebase.resources.chat_introduce_preflight_reason_unknown
import id.homebase.resources.chat_introduce_preflight_reason_unreachable
import id.homebase.resources.chat_group_heal_already_in_sync
import id.homebase.resources.chat_group_heal_checking_peers
import id.homebase.resources.chat_group_heal_main_resent
import id.homebase.resources.chat_group_heal_progress_asking_cleanup_admin_file
import id.homebase.resources.chat_group_heal_progress_asking_cleanup_group_file
import id.homebase.resources.chat_group_heal_progress_sending_admin_file
import id.homebase.resources.chat_group_heal_progress_sending_group_file
import id.homebase.resources.chat_group_heal_progress_still_queued
import id.homebase.resources.chat_group_heal_progress_subtitle_finished
import id.homebase.resources.chat_group_heal_progress_subtitle_running
import id.homebase.resources.chat_group_heal_progress_title
import id.homebase.resources.chat_group_heal_request_sent
import id.homebase.resources.chat_group_main_peer_error
import id.homebase.resources.chat_group_main_peer_loading
import id.homebase.resources.chat_group_main_peer_missing
import id.homebase.resources.chat_group_main_peer_mine
import id.homebase.resources.chat_group_main_peer_other_author
import id.homebase.resources.chat_group_member_sync_status
import id.homebase.resources.chat_group_summary_all_ok
import id.homebase.resources.chat_group_summary_problem
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
    val healAlreadyInSyncMessage = stringResource(MR.string.chat_group_heal_already_in_sync)

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
            val parts = buildList {
                if (event.mainRecipientCount > 0) {
                    add(
                        pluralStringResource(
                            MR.plurals.chat_group_heal_main_resent,
                            event.mainRecipientCount,
                            event.mainRecipientCount,
                        )
                    )
                }
                if (event.adminRecipientCount > 0) {
                    add(
                        pluralStringResource(
                            MR.plurals.chat_group_heal_admin_resent,
                            event.adminRecipientCount,
                            event.adminRecipientCount,
                        )
                    )
                }
                if (event.healMessageRecipientCount > 0) {
                    add(
                        pluralStringResource(
                            MR.plurals.chat_group_heal_request_sent,
                            event.healMessageRecipientCount,
                            event.healMessageRecipientCount,
                        )
                    )
                }
            }
            // Counts all zero ⇒ either every peer was already PresentAuthoredByMe
            // or there were no peers to begin with; canHeal would have blocked
            // the not-author case before the click reached us.
            val message = if (parts.isNotEmpty()) parts.joinToString(" · ") else healAlreadyInSyncMessage
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
                        val preflight = uiState.introductionPreflight?.get(contact.odinId)
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
                            mainPeerExists = uiState.mainFileExists?.get(contact.odinId),
                            adminPeerExists = uiState.adminFileExists?.get(contact.odinId),
                            showMainPeerExistsColumn = uiState.mainFileExists != null,
                            showAdminPeerExistsColumn = uiState.adminFileExists != null,
                            errorText = introductionPreflightInlineLabel(preflight, contact.name),
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
                            val preflight = uiState.introductionPreflight?.get(contact.odinId)
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
                                mainPeerExists = uiState.mainFileExists?.get(contact.odinId),
                                adminPeerExists = uiState.adminFileExists?.get(contact.odinId),
                                showMainPeerExistsColumn = uiState.mainFileExists != null,
                                showAdminPeerExistsColumn = uiState.adminFileExists != null,
                                errorText = introductionPreflightInlineLabel(preflight, contact.name),
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
                    if (uiState.filesDiagnostic != null) {
                        item {
                            HorizontalDivider()
                            GroupFilesDiagnosticBlock(
                                diagnostic = uiState.filesDiagnostic,
                                selfDomain = uiState.currentOdinId?.domainName,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
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

                        MemberSyncStatusSection(
                            mainPeerExists = uiState.mainFileExists?.get(contactInfo.odinId),
                            adminPeerExists = uiState.adminFileExists?.get(contactInfo.odinId),
                            showMainPeerExistsColumn = uiState.mainFileExists != null,
                            showAdminPeerExistsColumn = uiState.adminFileExists != null,
                        )

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

        is GroupSettingsUiSheet.HealProgress -> {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                // Block dismiss until the heal call returns so the user can see
                // every line transition. After [finished] flips true, the Close
                // button (rendered below) is the explicit dismiss path.
                onDismissRequest = { if (sheet.finished) onSheetClosed() },
                sheetState = sheetState,
            ) {
                HealProgressSheetContent(
                    items = sheet.items,
                    finished = sheet.finished,
                    onClose = { onSheetClosed() },
                )
            }
        }
    }
}

@Composable
private fun HealProgressSheetContent(
    items: List<HealProgressItem>,
    finished: Boolean,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(MR.string.chat_group_heal_progress_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (finished) MR.string.chat_group_heal_progress_subtitle_finished
                else MR.string.chat_group_heal_progress_subtitle_running
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (items.isEmpty()) {
            // Two distinct empty states:
            //  - !finished → we're still probing peers (retryErrorPeersIfAny is
            //    in flight before the plan is built); rows haven't been seeded yet.
            //  - finished → heal completed with zero actions; either everyone is
            //    PresentAuthoredByMe or remaining peers were unreachable. The
            //    "already in sync" wording is honest for the common case.
            Text(
                text = stringResource(
                    if (finished) MR.string.chat_group_heal_already_in_sync
                    else MR.string.chat_group_heal_checking_peers
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            items.forEach { item ->
                HealProgressRow(item)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onClose,
            enabled = finished,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 16.dp)
        ) {
            Text(text = stringResource(MR.string.ok))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun HealProgressRow(item: HealProgressItem) {
    val labelRes = when (item.kind) {
        HealActionKind.GroupFile -> MR.string.chat_group_heal_progress_sending_group_file
        HealActionKind.AdminFile -> MR.string.chat_group_heal_progress_sending_admin_file
        HealActionKind.HealRequestGroupFile -> MR.string.chat_group_heal_progress_asking_cleanup_group_file
        HealActionKind.HealRequestAdminFile -> MR.string.chat_group_heal_progress_asking_cleanup_admin_file
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (item.state) {
                HealProgressState.Pending -> Icon(
                    imageVector = Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp),
                )
                HealProgressState.InFlight -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.8.dp,
                )
                HealProgressState.Done -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                HealProgressState.Failed -> Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                HealProgressState.Skipped -> Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp),
                )
                HealProgressState.StillQueued -> Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = when (item.state) {
                HealProgressState.StillQueued -> stringResource(
                    MR.string.chat_group_heal_progress_still_queued,
                    item.peer.domainName,
                )
                else -> stringResource(labelRes, item.peer.domainName)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (item.state) {
                HealProgressState.Skipped -> LocalContentColor.current.copy(alpha = 0.55f)
                HealProgressState.Failed -> MaterialTheme.colorScheme.error
                HealProgressState.StillQueued -> LocalContentColor.current.copy(alpha = 0.7f)
                else -> LocalContentColor.current
            },
        )
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
    mainPeerExists: MemberFileExistsStatus?,
    adminPeerExists: MemberFileExistsStatus?,
    showMainPeerExistsColumn: Boolean,
    showAdminPeerExistsColumn: Boolean,
    /** Optional one-line reason rendered in error color under [subTitle]. Source
     *  of truth = [introductionPreflightInlineLabel]. Null = no extra line.
     *  Independent of the summary icon — preflight problems are not file-sync
     *  problems and the cloud icon must not turn red for them. */
    errorText: String? = null,
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
                val anyIconColumn = showMainColumn || showAdminColumn ||
                    showMainPeerExistsColumn || showAdminPeerExistsColumn
                if (anyIconColumn) {
                    Spacer(modifier = Modifier.width(8.dp))
                    GroupParticipantSummaryIcon(
                        mainStatus = mainStatus,
                        adminStatus = adminStatus,
                        mainPeerExists = mainPeerExists,
                        adminPeerExists = adminPeerExists,
                        showMainColumn = showMainColumn,
                        showAdminColumn = showAdminColumn,
                        showMainPeerExistsColumn = showMainPeerExistsColumn,
                        showAdminPeerExistsColumn = showAdminPeerExistsColumn,
                    )
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
            errorText?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
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

/**
 * 12dp status icon for a single peer's file-exists outcome. Reused by the
 * per-member status section and (next) by the overall-group summary row.
 *
 * Tints:
 *  - `PresentAuthoredByMe` → neutral (in-sync; nothing to do).
 *  - `Missing` / `PresentAuthoredByOther` → tertiary cloud (both need a
 *    heal action, just different ones — Missing wants a push; ByOther
 *    wants a heal-request first).
 *  - `Error` → error tint.
 */
@Composable
private fun FileExistsStatusIcon(
    status: MemberFileExistsStatus?,
    contentDescription: String,
) {
    val size = 12.dp
    when (status) {
        null, MemberFileExistsStatus.Loading -> CircularProgressIndicator(
            modifier = Modifier.size(size),
            strokeWidth = 1.5.dp,
            color = LocalContentColor.current.copy(alpha = 0.55f),
        )
        is MemberFileExistsStatus.PresentAuthoredByMe -> Icon(
            imageVector = Icons.Default.CloudDone,
            contentDescription = contentDescription,
            tint = LocalContentColor.current.copy(alpha = 0.55f),
            modifier = Modifier.size(size),
        )
        MemberFileExistsStatus.Missing -> Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(size),
        )
        MemberFileExistsStatus.PresentAuthoredByOther -> Icon(
            imageVector = Icons.Default.SyncProblem,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(size),
        )
        MemberFileExistsStatus.Error -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * One consolidated status icon for a participant row — replaces the four
 * 12dp icons (main transfer + admin transfer + main peer-exists + admin
 * peer-exists) we used to render side-by-side.
 *
 * - **All present signals OK** (transfer Delivered, peer-exists
 *   PresentAuthoredByMe) → green check.
 * - **Any signal still resolving** (transfer history null entry, peer-exists
 *   Loading) → spinner.
 * - **Anything else** (Problem, Missing, PresentAuthoredByOther, Error) →
 *   red-dashed cloud (`Icons.Default.CloudOff`), tinted with the error color.
 *
 * A column is "present" iff its `show…Column` flag is true (i.e. the caller
 * is the author of that file and the relevant data has been loaded). Columns
 * that aren't shown are excluded from the conjunction — a non-author still
 * gets a green check on a peer's row when their *visible* signals are all OK,
 * matching how the four-icon block hid those columns before.
 *
 * Tap the row → opens the member bottom sheet which still enumerates each
 * underlying signal in plain text via [MemberSyncStatusSection].
 */
@Composable
private fun GroupParticipantSummaryIcon(
    mainStatus: RecipientFileStatus?,
    adminStatus: RecipientFileStatus?,
    mainPeerExists: MemberFileExistsStatus?,
    adminPeerExists: MemberFileExistsStatus?,
    showMainColumn: Boolean,
    showAdminColumn: Boolean,
    showMainPeerExistsColumn: Boolean,
    showAdminPeerExistsColumn: Boolean,
) {
    val size = 16.dp

    // Loading wins over OK/problem so we don't flash a red cloud while the
    // peer-exists check is still in flight.
    val anyLoading =
        (showMainColumn && mainStatus == null) ||
        (showAdminColumn && adminStatus == null) ||
        (showMainPeerExistsColumn && mainPeerExists is MemberFileExistsStatus.Loading) ||
        (showAdminPeerExistsColumn && adminPeerExists is MemberFileExistsStatus.Loading)

    val allOk =
        (!showMainColumn || mainStatus is RecipientFileStatus.Ok) &&
        (!showAdminColumn || adminStatus is RecipientFileStatus.Ok) &&
        (!showMainPeerExistsColumn || mainPeerExists?.isInSync() == true) &&
        (!showAdminPeerExistsColumn || adminPeerExists?.isInSync() == true)

    when {
        anyLoading -> CircularProgressIndicator(
            modifier = Modifier.size(size),
            strokeWidth = 1.8.dp,
            color = LocalContentColor.current.copy(alpha = 0.55f),
        )
        allOk -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(MR.string.chat_group_summary_all_ok),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size),
        )
        else -> Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = stringResource(MR.string.chat_group_summary_problem),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * Plain-text legend for the file-exists icons. One row per visible
 * peer-exists column ("Group file…" + "Admin file…"). Rendered inside the
 * member bottom sheet so the user can read what each icon means rather
 * than memorise them.
 *
 * The same per-file row is reused for the overall-group summary header
 * via [PeerFileExistsStatusRow] — that's why the row composable is its
 * own building block.
 */
@Composable
private fun MemberSyncStatusSection(
    mainPeerExists: MemberFileExistsStatus?,
    adminPeerExists: MemberFileExistsStatus?,
    showMainPeerExistsColumn: Boolean,
    showAdminPeerExistsColumn: Boolean,
) {
    if (!showMainPeerExistsColumn && !showAdminPeerExistsColumn) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = stringResource(MR.string.chat_group_member_sync_status),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (showMainPeerExistsColumn) {
            PeerFileExistsStatusRow(status = mainPeerExists, forAdminFile = false)
        }
        if (showAdminPeerExistsColumn) {
            PeerFileExistsStatusRow(status = adminPeerExists, forAdminFile = true)
        }
    }
}

/**
 * One status line + icon for a single (file, peer-outcome) pair. The
 * `forAdminFile` flag picks the main-vs-admin string variant; everything
 * else (icon mapping, label mapping) keys off [status] alone. Reused by
 * the per-member sheet and the upcoming overall-summary row, so the two
 * surfaces always disagree if they disagree about the input — never about
 * the rendering of it.
 */
@Composable
private fun PeerFileExistsStatusRow(
    status: MemberFileExistsStatus?,
    forAdminFile: Boolean,
) {
    val text = peerFileExistsLabel(status, forAdminFile)
    SyncStatusRow(text = text) {
        FileExistsStatusIcon(status = status, contentDescription = text)
    }
}

/**
 * The single source of truth for the per-status label. Pure function on
 * `(status, forAdminFile)`; reused by [PeerFileExistsStatusRow] and (next)
 * by the overall-group summary row. Anything that needs a human-readable
 * label for a [MemberFileExistsStatus] should go through here.
 */
@Composable
internal fun peerFileExistsLabel(
    status: MemberFileExistsStatus?,
    forAdminFile: Boolean,
): String {
    val key = when (status) {
        null, MemberFileExistsStatus.Loading -> if (forAdminFile)
            MR.string.chat_group_admin_peer_loading else MR.string.chat_group_main_peer_loading
        MemberFileExistsStatus.Missing -> if (forAdminFile)
            MR.string.chat_group_admin_peer_missing else MR.string.chat_group_main_peer_missing
        is MemberFileExistsStatus.PresentAuthoredByMe -> if (forAdminFile)
            MR.string.chat_group_admin_peer_mine else MR.string.chat_group_main_peer_mine
        MemberFileExistsStatus.PresentAuthoredByOther -> if (forAdminFile)
            MR.string.chat_group_admin_peer_other_author else MR.string.chat_group_main_peer_other_author
        MemberFileExistsStatus.Error -> if (forAdminFile)
            MR.string.chat_group_admin_peer_error else MR.string.chat_group_main_peer_error
    }
    return stringResource(key)
}

/**
 * Inline per-row label for a member's introduction-preflight status. Returns
 * null for `Ready` and for `null` (not yet loaded) so callers can no-op
 * cleanly. The non-Ready branches reuse the *same* strings the create-group
 * preflight dialog renders, so the two surfaces stay in lockstep.
 *
 * Mapped from `IntroductionPreflightStatus` — see
 * `homebase-api/.../IntroductionPreflightStatus.kt`.
 */
@Composable
internal fun introductionPreflightInlineLabel(
    status: IntroductionPreflightStatus?,
    peerName: String,
): String? {
    val key = when (status) {
        null, IntroductionPreflightStatus.Ready -> return null
        IntroductionPreflightStatus.NotConnected -> MR.string.chat_introduce_preflight_reason_not_connected
        IntroductionPreflightStatus.RecipientNotConfigured -> MR.string.chat_introduce_preflight_reason_not_configured
        IntroductionPreflightStatus.RecipientRequiresUpgrade -> MR.string.chat_introduce_preflight_reason_requires_upgrade
        IntroductionPreflightStatus.IntroductionsNotPermitted -> MR.string.chat_introduce_preflight_reason_not_permitted
        IntroductionPreflightStatus.RecipientRejected -> MR.string.chat_introduce_preflight_reason_rejected
        IntroductionPreflightStatus.Unreachable -> MR.string.chat_introduce_preflight_reason_unreachable
        IntroductionPreflightStatus.UnknownError -> MR.string.chat_introduce_preflight_reason_unknown
    }
    return stringResource(key, peerName)
}

@Composable
private fun SyncStatusRow(text: String, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
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