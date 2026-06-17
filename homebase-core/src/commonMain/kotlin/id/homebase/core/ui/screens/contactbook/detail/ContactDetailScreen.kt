@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAddAlt1
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.widget.ChatMediaFullScreenHost
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.ui.screens.contactbook.components.ContactBookAvatar
import id.homebase.core.ui.screens.contactbook.components.ContactEditSheet
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_action_blocked
import id.homebase.resources.contactbook_action_disconnected
import id.homebase.resources.contactbook_action_unblocked
import id.homebase.resources.contactbook_connected
import id.homebase.resources.contactbook_detail_block
import id.homebase.resources.contactbook_detail_block_message
import id.homebase.resources.contactbook_detail_block_title
import id.homebase.resources.contactbook_detail_blocked
import id.homebase.resources.contactbook_detail_connect
import id.homebase.resources.contactbook_detail_delete
import id.homebase.resources.contactbook_detail_delete_message
import id.homebase.resources.contactbook_detail_delete_title
import id.homebase.resources.contactbook_detail_disconnect
import id.homebase.resources.contactbook_detail_disconnect_message
import id.homebase.resources.contactbook_detail_disconnect_title
import id.homebase.resources.contactbook_detail_edit
import id.homebase.resources.contactbook_detail_message
import id.homebase.resources.contactbook_detail_not_connected
import id.homebase.resources.contactbook_detail_pending
import id.homebase.resources.contactbook_error_connection_forbidden
import id.homebase.resources.contactbook_error_delete
import id.homebase.resources.contactbook_error_delete_forbidden
import id.homebase.resources.contactbook_error_forbidden
import id.homebase.resources.contactbook_error_photo
import id.homebase.resources.contactbook_error_save
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onBack: () -> Unit,
    onOpenConversation: (Uuid) -> Unit,
    onSeeAllMedia: (conversationId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val errSave = stringResource(MR.string.contactbook_error_save)
    val errPhoto = stringResource(MR.string.contactbook_error_photo)
    val errForbidden = stringResource(MR.string.contactbook_error_forbidden)
    val errDelete = stringResource(MR.string.contactbook_error_delete)
    val errDeleteForbidden = stringResource(MR.string.contactbook_error_delete_forbidden)
    val errConnectionForbidden = stringResource(MR.string.contactbook_error_connection_forbidden)
    val msgBlocked = stringResource(MR.string.contactbook_action_blocked)
    val msgUnblocked = stringResource(MR.string.contactbook_action_unblocked)
    val msgDisconnected = stringResource(MR.string.contactbook_action_disconnected)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ContactDetailEvent.OpenConversation -> onOpenConversation(event.conversationId)
                is ContactDetailEvent.SeeAllMedia -> onSeeAllMedia(event.conversationId)
                ContactDetailEvent.Back -> onBack()
                ContactDetailEvent.Error -> snackbarHostState.showSnackbar(errSave)
                ContactDetailEvent.Forbidden -> snackbarHostState.showSnackbar(errForbidden)
                ContactDetailEvent.DeleteError -> snackbarHostState.showSnackbar(errDelete)
                ContactDetailEvent.DeleteForbidden ->
                    snackbarHostState.showSnackbar(errDeleteForbidden)
                ContactDetailEvent.ConnectionForbidden ->
                    snackbarHostState.showSnackbar(errConnectionForbidden)
                ContactDetailEvent.PhotoError -> snackbarHostState.showSnackbar(errPhoto)
                ContactDetailEvent.Blocked -> snackbarHostState.showSnackbar(msgBlocked)
                ContactDetailEvent.Unblocked -> snackbarHostState.showSnackbar(msgUnblocked)
                ContactDetailEvent.Disconnected -> snackbarHostState.showSnackbar(msgDisconnected)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { viewModel.onAction(ContactDetailAction.BackClicked) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onAction(ContactDetailAction.EditClicked) }) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(MR.string.contactbook_detail_edit),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val entry = uiState.entry
            when {
                entry == null && uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                entry == null -> {}

                else -> {
                    var detailsExpanded by rememberSaveable(entry.uniqueId) { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        DetailHeader(
                            uiState = uiState,
                            onAction = viewModel::onAction,
                            onConnect = {
                                uiState.entry?.odinId?.let { domain ->
                                    runCatching { OdinId(domain) }.getOrNull()?.let {
                                        connectRequestViewModel.onAction(
                                            ConnectRequestAction.OpenDialogWithRecipient(it)
                                        )
                                    }
                                }
                            },
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                        ContactFieldsSection(
                            entry = entry,
                            expanded = detailsExpanded,
                            onToggleMore = { detailsExpanded = !detailsExpanded },
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                        RecentMediaSection(
                            overview = uiState.overview,
                            onMediaClick = { viewModel.onAction(ContactDetailAction.OpenMedia(it)) },
                            onSeeAll = { viewModel.onAction(ContactDetailAction.SeeAllMediaClicked) },
                        )

                        // Circles + groups-in-common only apply to Homebase identities;
                        // for those they always show (with empty / not-connected hints).
                        if (uiState.hasOdinId) {
                            GroupsInCommonSection(
                                groups = uiState.groupsInCommon,
                                isConnected = uiState.isConnected,
                                onOpenGroup = { viewModel.onAction(ContactDetailAction.OpenGroup(it)) },
                            )

                            CirclesSection(
                                circles = uiState.circles,
                                isConnected = uiState.isConnected,
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        ManagementSection(uiState, viewModel::onAction)

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            ChatMediaFullScreenHost(
                item = uiState.fullScreenMedia,
                driveId = chatTargetDrive.alias,
                title = uiState.entry?.displayName.orEmpty(),
                snackbarHostState = snackbarHostState,
                onDismiss = { viewModel.onAction(ContactDetailAction.CloseMedia) },
            )

            if (uiState.actionInProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .pointerInput(Unit) {
                            // Swallow taps so the action can't be re-triggered while it runs.
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (uiState.editOpen) {
        ContactEditSheet(
            editing = uiState.entry,
            onSave = { draft, photo -> viewModel.onAction(ContactDetailAction.SaveContact(draft, photo)) },
            onDismiss = { viewModel.onAction(ContactDetailAction.CloseEdit) },
        )
    }

    // Connection-request dialog (sheet), opened by the "Send connection request" button.
    ConnectRequestBottomSheet(
        viewModel = connectRequestViewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToConversation = onOpenConversation,
    )

    uiState.confirm?.let { confirm ->
        ConfirmDialog(
            confirm = confirm,
            onConfirm = { viewModel.onAction(ContactDetailAction.ConfirmYes) },
            onDismiss = { viewModel.onAction(ContactDetailAction.ConfirmDismiss) },
        )
    }
}

@Composable
private fun DetailHeader(
    uiState: ContactDetailUiState,
    onAction: (ContactDetailAction) -> Unit,
    onConnect: () -> Unit,
) {
    val entry = uiState.entry ?: return
    val status = uiState.connectionStatus
    val connected = status == ConnectionStatus.Connected
    val blocked = status == ConnectionStatus.Blocked
    val pending = status == ConnectionStatus.Pending
    // Has a Homebase identity but no active connection (and isn't blocked/pending).
    val canConnect = uiState.hasOdinId && !connected && !blocked && !pending

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContactBookAvatar(entry = entry, size = 88.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        entry.odinId?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Connection status line (only for Homebase contacts).
        if (uiState.hasOdinId) {
            val statusColor = when {
                connected -> MaterialTheme.colorScheme.primary
                blocked -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = stringResource(
                    when {
                        connected -> MR.string.contactbook_connected
                        pending -> MR.string.contactbook_detail_pending
                        blocked -> MR.string.contactbook_detail_blocked
                        else -> MR.string.contactbook_detail_not_connected
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }

        when {
            connected -> {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { onAction(ContactDetailAction.MessageClicked) },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_message))
                }
            }
            canConnect -> {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_connect))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    confirm: ContactDetailConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message, action) = when (confirm) {
        ContactDetailConfirm.BLOCK -> Triple(
            MR.string.contactbook_detail_block_title,
            MR.string.contactbook_detail_block_message,
            MR.string.contactbook_detail_block,
        )
        ContactDetailConfirm.DISCONNECT -> Triple(
            MR.string.contactbook_detail_disconnect_title,
            MR.string.contactbook_detail_disconnect_message,
            MR.string.contactbook_detail_disconnect,
        )
        ContactDetailConfirm.DELETE -> Triple(
            MR.string.contactbook_detail_delete_title,
            MR.string.contactbook_detail_delete_message,
            MR.string.contactbook_detail_delete,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}
