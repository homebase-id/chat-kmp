package id.homebase.core.ui.screens.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAddAlt1
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestEvent
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import id.homebase.resources.connections_empty_incoming_subtitle
import id.homebase.resources.connections_empty_incoming_title
import id.homebase.resources.connections_empty_outgoing_subtitle
import id.homebase.resources.connections_empty_outgoing_title
import id.homebase.resources.connections_introduced_by
import id.homebase.resources.connections_new_request
import id.homebase.resources.connections_received_timestamp
import id.homebase.resources.connections_refresh
import id.homebase.resources.connections_sent_timestamp
import id.homebase.resources.connections_tab_incoming
import id.homebase.resources.connections_tab_outgoing
import id.homebase.resources.menu_back
import id.homebase.resources.settings_connections
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onBackClick: () -> Unit,
    onShowConversation: (kotlin.uuid.Uuid) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectState by connectRequestViewModel.state.collectAsStateWithLifecycle()
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

    ConnectRequestBottomSheet(
        viewModel = connectRequestViewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToConversation = onShowConversation,
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
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.settings_connections)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                            contentDescription = stringResource(MR.string.connections_refresh),
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
                    contentDescription = stringResource(MR.string.connections_new_request)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = {
                        Text(
                            stringResource(
                                MR.string.connections_tab_incoming,
                                uiState.incomingRequests.size.toString(),
                            )
                        )
                    },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            stringResource(
                                MR.string.connections_tab_outgoing,
                                uiState.outgoingRequests.size.toString(),
                            )
                        )
                    },
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> IncomingTabContent(
                        requests = uiState.incomingRequests,
                        identities = uiState.identities,
                        isLoading = uiState.isLoading,
                        onManageClick = { onAction(ConnectionsUiAction.OpenOwnerConsoleClicked) },
                    )
                    1 -> OutgoingTabContent(
                        requests = uiState.outgoingRequests,
                        identities = uiState.identities,
                        isLoading = uiState.isLoading,
                        onManageClick = { onAction(ConnectionsUiAction.OpenOwnerConsoleClicked) },
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingTabContent(
    requests: List<IncomingConnectionRequestResponse>,
    identities: Map<OdinId, PublicIdentity>,
    isLoading: Boolean,
    onManageClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && requests.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (requests.isEmpty()) {
            TabEmptyState(
                title = stringResource(MR.string.connections_empty_incoming_title),
                subtitle = stringResource(MR.string.connections_empty_incoming_subtitle),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = requests,
                    key = { it.senderOdinId.domainName },
                ) { request ->
                    ConnectionRequestRow(
                        odinId = request.senderOdinId,
                        identity = identities[request.senderOdinId],
                        timestampLabel = stringResource(
                            MR.string.connections_received_timestamp,
                            formatTimestamp(request.receivedTimestampMilliseconds),
                        ),
                        onClick = onManageClick,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutgoingTabContent(
    requests: List<OutgoingConnectionRequestResponse>,
    identities: Map<OdinId, PublicIdentity>,
    isLoading: Boolean,
    onManageClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && requests.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (requests.isEmpty()) {
            TabEmptyState(
                title = stringResource(MR.string.connections_empty_outgoing_title),
                subtitle = stringResource(MR.string.connections_empty_outgoing_subtitle),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = requests,
                    key = { it.recipient.domainName },
                ) { request ->
                    val introducerOdinId = request.introducerOdinId
                    val introLabel = introducerOdinId?.let {
                        val introducerName = identities[it]?.displayNameOrDomain() ?: it.domainName
                        stringResource(MR.string.connections_introduced_by, introducerName)
                    }
                    ConnectionRequestRow(
                        odinId = request.recipient,
                        identity = identities[request.recipient],
                        timestampLabel = stringResource(
                            MR.string.connections_sent_timestamp,
                            formatTimestamp(request.receivedTimestampMilliseconds),
                        ),
                        introLabel = introLabel,
                        messagePreview = request.message?.takeIf { it.isNotBlank() },
                        onClick = onManageClick,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ConnectionRequestRow(
    odinId: OdinId,
    identity: PublicIdentity?,
    timestampLabel: String,
    introLabel: String? = null,
    messagePreview: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(
            odinId = odinId,
            profileImageData = null,
            initials = identity?.initials(),
            options = AvatarOptions(size = 36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = identity?.displayNameOrDomain() ?: odinId.domainName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = timestampLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (identity?.displayName?.isNotBlank() == true) {
                Text(
                    text = odinId.domainName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (introLabel != null) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonAddAlt1,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = introLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            messagePreview?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TabEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
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
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
