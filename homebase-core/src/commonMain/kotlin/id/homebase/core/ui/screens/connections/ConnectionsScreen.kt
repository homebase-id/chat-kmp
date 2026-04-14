package id.homebase.core.ui.screens.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.api.client.connections.IncomingConnectionRequestResponse
import id.homebase.api.client.connections.OutgoingConnectionRequestResponse
import id.homebase.api.client.identity.PublicIdentity
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.client.identity.initials
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.connections.ConnectRequestDialogs
import id.homebase.core.connections.ConnectRequestEvent
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import id.homebase.resources.connections_empty_description
import id.homebase.resources.connections_empty_title
import id.homebase.resources.connections_manage_in_owner_console
import id.homebase.resources.connections_no_incoming
import id.homebase.resources.connections_no_outgoing
import id.homebase.resources.menu_back
import id.homebase.resources.settings_connections
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectState by connectRequestViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = getUriHandler()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
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

    // Refresh the list when the shared dialog reports a successful send.
    LaunchedEffect(connectState.uiEvent) {
        if (connectState.uiEvent is ConnectRequestEvent.SendSuccess) {
            viewModel.refresh()
        }
    }

    ConnectionsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onComposeClick = { connectRequestViewModel.onAction(ConnectRequestAction.OpenDialog) },
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    )

    ConnectRequestDialogs(
        viewModel = connectRequestViewModel,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsUi(
    uiState: ConnectionsUiState,
    onAction: (ConnectionsUiAction) -> Unit,
    onComposeClick: () -> Unit,
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
            FloatingActionButton(onClick = onComposeClick) {
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
            val showFullEmptyState = !uiState.isLoading &&
                    uiState.incomingRequests.isEmpty() &&
                    visibleOutgoing.isEmpty() &&
                    introductionCount == 0

            if (showFullEmptyState) {
                FullEmptyState(modifier = Modifier.align(Alignment.Center))
            } else {
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
                        EmptyState(text = stringResource(MR.string.connections_no_incoming))
                    } else {
                        uiState.incomingRequests.forEach { req ->
                            IncomingRequestCard(
                                request = req,
                                identity = uiState.identities[req.senderOdinId],
                                onManageClick = { onAction(ConnectionsUiAction.OpenOwnerConsoleClicked) },
                            )
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
                        EmptyState(text = stringResource(MR.string.connections_no_outgoing))
                    } else {
                        visibleOutgoing.forEach { req ->
                            OutgoingRequestCard(
                                request = req,
                                identity = uiState.identities[req.recipient],
                                onManageClick = { onAction(ConnectionsUiAction.OpenOwnerConsoleClicked) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(80.dp)) // room for FAB
                }
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
private fun FullEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(MR.string.connections_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(MR.string.connections_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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
    onManageClick: () -> Unit,
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
            ManageInOwnerConsoleLink(onClick = onManageClick)
        }
    }
}

@Composable
private fun OutgoingRequestCard(
    request: OutgoingConnectionRequestResponse,
    identity: PublicIdentity?,
    onManageClick: () -> Unit,
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
            ManageInOwnerConsoleLink(onClick = onManageClick)
        }
    }
}

@Composable
private fun ManageInOwnerConsoleLink(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(stringResource(MR.string.connections_manage_in_owner_console))
    }
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
