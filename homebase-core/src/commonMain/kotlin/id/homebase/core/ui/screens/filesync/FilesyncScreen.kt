package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.common.util.formatBytes
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.util.formatMediumDate
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import kotlinx.coroutines.flow.StateFlow
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.filesync_cancel_download
import id.homebase.resources.filesync_check_failed
import id.homebase.resources.filesync_damaged
import id.homebase.resources.filesync_desktop_download
import id.homebase.resources.filesync_download_failed
import id.homebase.resources.filesync_folder
import id.homebase.resources.filesync_folder_empty
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_load_failed
import id.homebase.resources.filesync_more
import id.homebase.resources.filesync_open_failed
import id.homebase.resources.filesync_save_failed
import id.homebase.resources.filesync_retry
import id.homebase.resources.filesync_save
import id.homebase.resources.menu_back
import kotlinx.io.files.Path
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun FilesyncScreen(viewModel: FilesyncViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val fileSystemHandler = getUriHandler()

    val scope = rememberCoroutineScope()
    fun showMessage(message: StringResource) {
        scope.launch { snackbarHostState.showSnackbar(getString(message)) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FilesyncUiEvent.OpenFile -> fileSystemHandler.openFile(
                    Path(event.path),
                    showChooser = false,
                    onError = { showMessage(MR.string.filesync_open_failed) },
                )
                is FilesyncUiEvent.SaveFile -> fileSystemHandler.saveFile(
                    Path(event.path),
                    event.name,
                    onSuccess = { viewModel.onAction(FilesyncUiAction.SaveHandedOff(event.path)) },
                    onError = {
                        viewModel.onAction(FilesyncUiAction.SaveHandedOff(event.path))
                        showMessage(MR.string.filesync_save_failed)
                    },
                )
                is FilesyncUiEvent.Error -> snackbarHostState.showSnackbar(
                    getString(
                        when (event.error) {
                            FilesyncError.DownloadFailed -> MR.string.filesync_download_failed
                            FilesyncError.Damaged -> MR.string.filesync_damaged
                        }
                    )
                )
            }
        }
    }

    BackHandler(enabled = uiState.path.size > 1) { viewModel.onAction(FilesyncUiAction.NavigateUp) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.path.lastOrNull()?.name ?: stringResource(MR.string.filesync_label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (uiState.path.size > 1) {
                        IconButton(onClick = { viewModel.onAction(FilesyncUiAction.NavigateUp) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                        }
                    }
                },
                actions = { OverflowMenu() },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.path.size > 1) {
                Breadcrumbs(path = uiState.path, onCrumb = { viewModel.onAction(FilesyncUiAction.NavigateToCrumb(it)) })
            }
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.nodes.isNotEmpty(),
                onRefresh = { viewModel.onAction(FilesyncUiAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.nodes.isEmpty() -> CenteredBox { CircularProgressIndicator() }
                    uiState.loadFailed && uiState.nodes.isEmpty() -> CenteredBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(MR.string.filesync_load_failed))
                            TextButton(onClick = { viewModel.onAction(FilesyncUiAction.Refresh) }) {
                                Text(stringResource(MR.string.filesync_retry))
                            }
                        }
                    }
                    uiState.nodes.isEmpty() -> CenteredBox {
                        Text(
                            text = stringResource(MR.string.filesync_folder_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.nodes, key = { it.nodeId.toString() }) { node ->
                            NodeRow(
                                node = node,
                                transferProgress = if (uiState.transferNodeId == node.nodeId) viewModel.transferProgress else null,
                                onClick = { viewModel.onAction(FilesyncUiAction.NodeClicked(node)) },
                                onSave = { viewModel.onAction(FilesyncUiAction.SaveClicked(node)) },
                                onCancel = { viewModel.onAction(FilesyncUiAction.CancelTransfer) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu() {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = getUriHandler()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.filesync_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.filesync_desktop_download)) },
                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                onClick = {
                    expanded = false
                    uriHandler.openUrl(FilesyncProtocol.DesktopDownloadUrl)
                },
            )
        }
    }
}

@Composable
private fun Breadcrumbs(path: List<FilesyncCrumb>, onCrumb: (Int) -> Unit) {
    val rootLabel = stringResource(MR.string.filesync_label)
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(path, key = { _, crumb -> crumb.nodeId.toString() }) { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            TextButton(onClick = { onCrumb(index) }, enabled = index < path.lastIndex) {
                Text(crumb.name ?: rootLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: FilesyncNode,
    transferProgress: StateFlow<Float>?,
    onClick: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val folderLabel = stringResource(MR.string.filesync_folder)
    val details = remember(node) {
        if (node.isFolder) {
            folderLabel
        } else {
            listOfNotNull(
                node.sizeBytes?.let { formatBytes(it) },
                node.modifiedMs?.let { formatMediumDate(Instant.fromEpochMilliseconds(it)) },
            ).joinToString(" · ")
        }
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (details.isNotEmpty()) Text(details) },
        leadingContent = {
            Icon(
                imageVector = if (node.isFolder) Icons.Outlined.Folder else fileTypeIcon(null, node.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            when {
                node.isFolder -> Unit
                transferProgress != null -> {
                    val cancelLabel = stringResource(MR.string.filesync_cancel_download)
                    val progress by transferProgress.collectAsStateWithLifecycle()
                    IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = cancelLabel }) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
                    }
                }
                else -> IconButton(onClick = onSave) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(MR.string.filesync_save))
                }
            }
        },
    )
}

@Composable
fun FilesyncCheckingScreen(failed: Boolean, onRetry: () -> Unit) {
    CenteredBox {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(MR.string.filesync_check_failed))
                TextButton(onClick = onRetry) { Text(stringResource(MR.string.filesync_retry)) }
            }
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
