package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.filesync_delete_forever
import id.homebase.resources.filesync_delete_forever_confirm
import id.homebase.resources.filesync_delete_forever_confirm_many
import id.homebase.resources.filesync_empty_trash
import id.homebase.resources.filesync_empty_trash_confirm
import id.homebase.resources.filesync_folder_items
import id.homebase.resources.filesync_from_folder
import id.homebase.resources.filesync_more
import id.homebase.resources.filesync_restore
import id.homebase.resources.filesync_selected
import id.homebase.resources.filesync_trash
import id.homebase.resources.filesync_trash_empty
import id.homebase.resources.filesync_trash_failed
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/** Recently deleted as a trash can: what each delete took, newest first, to restore or delete for good. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun FilesyncTrashScreen(viewModel: FilesyncTrashViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FilesyncEventsEffect(viewModel.events, snackbarHostState, onSaveHandedOff = {})

    val selecting = uiState.selected.isNotEmpty()
    BackHandler(enabled = selecting) { viewModel.onAction(FilesyncTrashAction.ClearSelection) }

    var askDelete by remember { mutableStateOf<Set<Uuid>?>(null) }
    var askEmpty by remember { mutableStateOf(false) }
    askDelete?.let { ids ->
        val name = uiState.items.firstOrNull { it.node.nodeId in ids }?.node?.name.orEmpty()
        FilesyncConfirmDialog(
            text = if (ids.size == 1) {
                stringResource(MR.string.filesync_delete_forever_confirm, name)
            } else {
                pluralStringResource(MR.plurals.filesync_delete_forever_confirm_many, ids.size, ids.size)
            },
            confirmLabel = stringResource(MR.string.filesync_delete_forever),
            destructive = true,
            onConfirm = {
                askDelete = null
                viewModel.onAction(FilesyncTrashAction.DeleteForever(ids))
            },
            onDismiss = { askDelete = null },
        )
    }
    if (askEmpty) {
        FilesyncConfirmDialog(
            title = stringResource(MR.string.filesync_empty_trash),
            text = stringResource(MR.string.filesync_empty_trash_confirm),
            confirmLabel = stringResource(MR.string.filesync_empty_trash),
            destructive = true,
            onConfirm = {
                askEmpty = false
                viewModel.onAction(FilesyncTrashAction.EmptyTrash)
            },
            onDismiss = { askEmpty = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) {
                            pluralStringResource(MR.plurals.filesync_selected, uiState.selected.size, uiState.selected.size)
                        } else {
                            stringResource(MR.string.filesync_trash)
                        }
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { viewModel.onAction(FilesyncTrashAction.ClearSelection) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.menu_back))
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { viewModel.onAction(FilesyncTrashAction.Restore(uiState.selected)) }, enabled = !uiState.busy) {
                            Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(MR.string.filesync_restore))
                        }
                        IconButton(onClick = { askDelete = uiState.selected }, enabled = !uiState.busy) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = stringResource(MR.string.filesync_delete_forever),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        TextButton(onClick = { askEmpty = true }, enabled = uiState.items.isNotEmpty() && !uiState.busy) {
                            Text(
                                stringResource(MR.string.filesync_empty_trash),
                                color = if (uiState.items.isNotEmpty() && !uiState.busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.items.isNotEmpty(),
                onRefresh = { viewModel.onAction(FilesyncTrashAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.items.isEmpty() && uiState.isLoading -> CenteredBox { CircularProgressIndicator() }
                    uiState.items.isEmpty() && uiState.loadFailed -> RetryMessage(MR.string.filesync_trash_failed) {
                        viewModel.onAction(FilesyncTrashAction.Refresh)
                    }
                    uiState.items.isEmpty() -> CenteredBox {
                        Text(
                            text = stringResource(MR.string.filesync_trash_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.items, key = { it.node.nodeId.toString() }) { item ->
                            TrashRow(
                                item = item,
                                folder = folderName(item.node.parentId, uiState.folderNames),
                                selected = item.node.nodeId in uiState.selected,
                                enabled = !uiState.busy,
                                onToggle = { viewModel.onAction(FilesyncTrashAction.ToggleSelected(item.node.nodeId)) },
                                onRestore = { viewModel.onAction(FilesyncTrashAction.Restore(setOf(item.node.nodeId))) },
                                onDelete = { askDelete = setOf(item.node.nodeId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashRow(
    item: FilesyncTrashItem,
    folder: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val details = listOfNotNull(
        stringResource(MR.string.filesync_from_folder, folder),
        nodeDetails(item.node),
        item.count.takeIf { it > 0 }?.let { pluralStringResource(MR.plurals.filesync_folder_items, it, it) },
    ).joinToString(" · ")
    FilesyncNodeRow(
        node = item.node,
        details = details,
        modifier = Modifier.clickable(enabled = enabled, onClick = onToggle),
        selected = selected,
        dimDeleted = false,
        trailing = {
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.filesync_more))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.filesync_restore)) },
                        leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.filesync_delete_forever), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            expanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}
