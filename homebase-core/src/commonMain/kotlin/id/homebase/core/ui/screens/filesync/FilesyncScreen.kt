package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.fileDropTarget
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.create
import id.homebase.resources.filesync_check_failed
import id.homebase.resources.filesync_desktop_download
import id.homebase.resources.filesync_dismiss
import id.homebase.resources.filesync_folder_deleted
import id.homebase.resources.filesync_folder_empty
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_load_failed
import id.homebase.resources.filesync_more
import id.homebase.resources.filesync_new_folder
import id.homebase.resources.filesync_new_folder_name
import id.homebase.resources.filesync_recent
import id.homebase.resources.filesync_restore
import id.homebase.resources.filesync_restore_confirm
import id.homebase.resources.filesync_retry
import id.homebase.resources.filesync_show_deleted
import id.homebase.resources.filesync_trash
import id.homebase.resources.filesync_upload
import id.homebase.resources.filesync_upload_failed_row
import id.homebase.resources.filesync_uploading
import id.homebase.resources.menu_back
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun FilesyncScreen(viewModel: FilesyncViewModel, onOpenTrash: () -> Unit, onOpenRecent: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentRemoved = uiState.path.last().removed

    FilesyncEventsEffect(viewModel.events, snackbarHostState) { viewModel.onAction(FilesyncUiAction.SaveHandedOff(it)) }

    BackHandler(enabled = uiState.path.size > 1) { viewModel.onAction(FilesyncUiAction.NavigateUp) }

    val onFilesChosen: (List<PlatformFile>) -> Unit = { viewModel.onAction(FilesyncUiAction.FilesPicked(it)) }
    val filePicker = rememberFilePickerLauncher(type = FileKitType.File(), mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) onFilesChosen(files)
    }
    var showNewFolder by remember { mutableStateOf(false) }
    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name ->
                showNewFolder = false
                viewModel.onAction(FilesyncUiAction.CreateFolder(name))
            },
            onDismiss = { showNewFolder = false },
        )
    }
    var askRestore by remember { mutableStateOf<FilesyncNode?>(null) }
    askRestore?.let { node ->
        FilesyncConfirmDialog(
            text = stringResource(MR.string.filesync_restore_confirm, node.name),
            confirmLabel = stringResource(MR.string.filesync_restore),
            onConfirm = {
                askRestore = null
                viewModel.onAction(FilesyncUiAction.Restore(node.nodeId))
            },
            onDismiss = { askRestore = null },
        )
    }

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
                actions = {
                    if (uiState.path.size == 1) {
                        IconButton(onClick = onOpenTrash) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.string.filesync_trash))
                        }
                    }
                    if (!currentRemoved) {
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(MR.string.filesync_new_folder))
                        }
                    }
                    OverflowMenu(
                        showDeleted = uiState.showDeleted,
                        onToggleShowDeleted = { viewModel.onAction(FilesyncUiAction.ToggleShowDeleted) },
                        onOpenTrash = onOpenTrash,
                        onOpenRecent = onOpenRecent,
                    )
                },
            )
        },
        floatingActionButton = {
            // Whatever lands in a deleted folder is out of sight on every device.
            if (!currentRemoved) {
                FloatingActionButton(onClick = { filePicker.launch() }) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = stringResource(MR.string.filesync_upload))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val uploads by viewModel.uploads.collectAsStateWithLifecycle()
        val currentFolder = uiState.path.last().nodeId
        val uploadsHere = uploads.filter { it.parentId == currentFolder }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .fileDropTarget(enabled = !currentRemoved, onDragPreviewChanged = {}, onFilesDropped = onFilesChosen),
        ) {
            if (uiState.path.size > 1) {
                Breadcrumbs(path = uiState.path, onCrumb = { viewModel.onAction(FilesyncUiAction.NavigateToCrumb(it)) })
            }
            if (currentRemoved) {
                ListItem(
                    headlineContent = { Text(stringResource(MR.string.filesync_folder_deleted)) },
                    trailingContent = {
                        TextButton(onClick = { viewModel.onAction(FilesyncUiAction.Restore(uiState.path.last().nodeId)) }) {
                            Text(stringResource(MR.string.filesync_restore))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.nodes.isNotEmpty(),
                onRefresh = { viewModel.onAction(FilesyncUiAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                val nothingToShow = uiState.nodes.isEmpty() && uploadsHere.isEmpty()
                when {
                    nothingToShow && uiState.isLoading -> CenteredBox { CircularProgressIndicator() }
                    nothingToShow && uiState.loadFailed -> RetryMessage(MR.string.filesync_load_failed) {
                        viewModel.onAction(FilesyncUiAction.Refresh)
                    }
                    nothingToShow -> CenteredBox {
                        Text(
                            text = stringResource(MR.string.filesync_folder_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Bottom padding keeps the last row's buttons clear of the upload button.
                    else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(uploadsHere, key = { "upload-${it.id}" }) { upload ->
                            UploadRow(
                                upload = upload,
                                onRetry = { viewModel.onAction(FilesyncUiAction.RetryUpload(upload.id)) },
                                onDismiss = { viewModel.onAction(FilesyncUiAction.DismissUpload(upload.id)) },
                            )
                        }
                        items(uiState.nodes, key = { it.nodeId.toString() }) { node ->
                            val removed = node.removal != null
                            FilesyncNodeRow(
                                node = node,
                                details = nodeDetails(node),
                                modifier = Modifier.clickable {
                                    if (removed && !node.isFolder) askRestore = node else viewModel.onAction(FilesyncUiAction.NodeClicked(node))
                                },
                                trailing = {
                                    when {
                                        removed -> RestoreButton { viewModel.onAction(FilesyncUiAction.Restore(node.nodeId)) }
                                        node.isFolder -> Unit
                                        else -> SaveButton(
                                            progress = uiState.transfer?.takeIf { it.nodeId == node.nodeId }?.progress,
                                            onSave = { viewModel.onAction(FilesyncUiAction.SaveClicked(node)) },
                                            onCancel = { viewModel.onAction(FilesyncUiAction.CancelTransfer) },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadRow(
    upload: FilesyncUpload,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(upload.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(if (upload.failed) MR.string.filesync_upload_failed_row else MR.string.filesync_uploading),
                color = if (upload.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            if (upload.failed) {
                Row {
                    TextButton(onClick = onRetry) { Text(stringResource(MR.string.filesync_retry)) }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.filesync_dismiss))
                    }
                }
            } else {
                val fraction = upload.progress
                if (fraction == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(24.dp))
                }
            }
        },
    )
}

@Composable
private fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.filesync_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(MR.string.filesync_new_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(MR.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
private fun OverflowMenu(
    showDeleted: Boolean,
    onToggleShowDeleted: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenRecent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = getUriHandler()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.filesync_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.filesync_show_deleted)) },
                leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                trailingIcon = { Checkbox(checked = showDeleted, onCheckedChange = null) },
                onClick = {
                    expanded = false
                    onToggleShowDeleted()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.filesync_recent)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenRecent()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.filesync_trash)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenTrash()
                },
            )
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
fun FilesyncCheckingScreen(failed: Boolean, onRetry: () -> Unit) {
    if (failed) RetryMessage(MR.string.filesync_check_failed, onRetry) else CenteredBox { CircularProgressIndicator() }
}
