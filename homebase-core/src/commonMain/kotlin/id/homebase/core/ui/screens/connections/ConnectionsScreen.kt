package id.homebase.core.ui.screens.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import id.homebase.api.client.connections.IncomingConnectionRequestResponse
import id.homebase.api.client.connections.OutgoingConnectionRequestResponse
import id.homebase.api.client.identity.PublicIdentity
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.client.identity.initials
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import id.homebase.resources.cancel
import id.homebase.resources.connections_already_sent_text
import id.homebase.resources.connections_already_sent_title
import id.homebase.resources.menu_back
import id.homebase.resources.settings_connections
import id.homebase.resources.settings_open_owner_console
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = getUriHandler()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            ConnectionsUiEvent.SendSuccess -> {
                snackbarHostState.showSnackbar("Connection request sent")
                viewModel.eventConsumed()
            }
            is ConnectionsUiEvent.SendError -> {
                snackbarHostState.showSnackbar(event.message)
                viewModel.eventConsumed()
            }
            is ConnectionsUiEvent.ActionError -> {
                snackbarHostState.showSnackbar(event.message)
                viewModel.eventConsumed()
            }
            is ConnectionsUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                uriHandler.openUrl(event.url)
            }
        }
    }

    ConnectionsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsUi(
    uiState: ConnectionsUiState,
    onAction: (ConnectionsUiAction) -> Unit,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.settings_connections)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onAction(ConnectionsUiAction.Refresh) },
                        enabled = !uiState.isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAction(ConnectionsUiAction.OpenComposeDialog) }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New connection request"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                SectionHeader(
                    title = "Incoming",
                    count = uiState.incomingRequests.size,
                )
                if (uiState.incomingRequests.isEmpty() && !uiState.isLoading) {
                    EmptyState(text = "No incoming requests")
                } else {
                    uiState.incomingRequests.forEach { req ->
                        IncomingRequestCard(
                            request = req,
                            identity = uiState.identities[req.senderOdinId],
                            isPending = req.senderOdinId in uiState.pendingOdinIds,
                            onAccept = { onAction(ConnectionsUiAction.AcceptIncoming(req.senderOdinId)) },
                            onReject = { onAction(ConnectionsUiAction.RejectIncoming(req.senderOdinId)) },
                        )
                    }
                }

                val introductionCount = uiState.outgoingRequests.count {
                    it.connectionRequestOrigin.equals("introduction", ignoreCase = true)
                }
                val visibleOutgoing = if (uiState.showIntroductionOutgoing) {
                    uiState.outgoingRequests
                } else {
                    uiState.outgoingRequests.filterNot {
                        it.connectionRequestOrigin.equals("introduction", ignoreCase = true)
                    }
                }

                SectionHeader(
                    title = "Outgoing",
                    count = visibleOutgoing.size,
                )
                if (introductionCount > 0) {
                    IntroductionToggleRow(
                        hiddenCount = introductionCount,
                        checked = uiState.showIntroductionOutgoing,
                        onCheckedChange = {
                            onAction(ConnectionsUiAction.SetShowIntroductionOutgoing(it))
                        },
                    )
                }
                if (visibleOutgoing.isEmpty() && !uiState.isLoading) {
                    EmptyState(text = "No outgoing requests")
                } else {
                    visibleOutgoing.forEach { req ->
                        OutgoingRequestCard(
                            request = req,
                            identity = uiState.identities[req.recipient],
                            isPending = req.recipient in uiState.pendingOdinIds,
                            onCancel = { onAction(ConnectionsUiAction.CancelOutgoing(req.recipient)) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(80.dp)) // room for FAB
            }

            if (uiState.isLoading &&
                uiState.incomingRequests.isEmpty() &&
                uiState.outgoingRequests.isEmpty()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                )
            }
        }
    }

    uiState.alreadySentRecipient?.let { recipient ->
        AlertDialog(
            onDismissRequest = { onAction(ConnectionsUiAction.DismissAlreadySentDialog) },
            title = { Text(stringResource(MR.string.connections_already_sent_title)) },
            text = {
                Text(
                    stringResource(
                        MR.string.connections_already_sent_text,
                        recipient.domainName,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(ConnectionsUiAction.OpenOwnerConsoleClicked) }) {
                    Text(stringResource(MR.string.settings_open_owner_console))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(ConnectionsUiAction.DismissAlreadySentDialog) }) {
                    Text(stringResource(MR.string.cancel))
                }
            }
        )
    }

    if (uiState.showComposeDialog) {
        ComposeRequestDialog(
            recipient = uiState.composeRecipient,
            message = uiState.composeMessage,
            resolution = uiState.recipientResolution,
            isSending = uiState.isSending,
            onRecipientChange = { onAction(ConnectionsUiAction.ComposeRecipientChanged(it)) },
            onMessageChange = { onAction(ConnectionsUiAction.ComposeMessageChanged(it)) },
            onSend = { onAction(ConnectionsUiAction.SendClicked) },
            onDismiss = { onAction(ConnectionsUiAction.CloseComposeDialog) },
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IntroductionToggleRow(
    hiddenCount: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Show introductions",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (checked) "Showing $hiddenCount auto-sent by introductions"
                else "Hiding $hiddenCount auto-sent by introductions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun IdentityHeader(
    odinId: OdinId,
    identity: PublicIdentity?,
    timestampLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(
            odinId = odinId,
            profileImageData = null,
            initials = identity?.initials(),
            options = AvatarOptions(size = 48.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = identity?.displayNameOrDomain() ?: odinId.domainName,
                style = MaterialTheme.typography.titleSmall,
            )
            if (identity?.displayName?.isNotBlank() == true) {
                Text(
                    text = odinId.domainName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = timestampLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IncomingRequestCard(
    request: IncomingConnectionRequestResponse,
    identity: PublicIdentity?,
    isPending: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdentityHeader(
                odinId = request.senderOdinId,
                identity = identity,
                timestampLabel = "Received ${formatTimestamp(request.receivedTimestampMilliseconds)}",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    enabled = !isPending,
                    modifier = Modifier.weight(1f),
                ) { Text("Reject") }
                Button(
                    onClick = onAccept,
                    enabled = !isPending,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
private fun OutgoingRequestCard(
    request: OutgoingConnectionRequestResponse,
    identity: PublicIdentity?,
    isPending: Boolean,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IdentityHeader(
                odinId = request.recipient,
                identity = identity,
                timestampLabel = "Sent ${formatTimestamp(request.receivedTimestampMilliseconds)}",
            )
            request.introducerOdinId?.let {
                Text(
                    text = "Introduced by ${it.domainName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            request.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = onCancel,
                enabled = !isPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Cancel request")
                }
            }
        }
    }
}

@Composable
private fun RecipientResolutionIndicator(resolution: RecipientResolution) {
    when (resolution) {
        RecipientResolution.Idle,
        RecipientResolution.InvalidFormat -> {
            // Stay quiet while the user is still typing a partial OdinId.
        }
        RecipientResolution.Resolving -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Checking identity…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RecipientResolution.NotFound -> {
            Text(
                text = "This isn't a valid Homebase identity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is RecipientResolution.Resolved -> {
            val identity = resolution.identity
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContactAvatar(
                    odinId = identity.odinId,
                    profileImageData = null,
                    initials = identity.initials(),
                    options = AvatarOptions(size = 40.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = identity.displayNameOrDomain(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (identity.displayName?.isNotBlank() == true) {
                        Text(
                            text = identity.odinId.domainName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    identity.status?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeRequestDialog(
    recipient: String,
    message: String,
    resolution: RecipientResolution,
    isSending: Boolean,
    onRecipientChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canSend = resolution is RecipientResolution.Resolved
    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text("New connection request") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val isError = resolution is RecipientResolution.NotFound
                OutlinedTextField(
                    value = recipient,
                    onValueChange = onRecipientChange,
                    label = { Text("Recipient OdinId") },
                    placeholder = { Text("example.dotyou.cloud") },
                    singleLine = true,
                    isError = isError,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                )
                RecipientResolutionIndicator(resolution = resolution)
                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChange,
                    label = { Text("Message (optional)") },
                    enabled = !isSending,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSend,
                enabled = !isSending && canSend,
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Send")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalTime::class)
private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "—"
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val diff = nowMs - millis
    if (diff < 0) return "just now"
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 30 -> "${days}d ago"
        else -> "${days / 30}mo ago"
    }
}
