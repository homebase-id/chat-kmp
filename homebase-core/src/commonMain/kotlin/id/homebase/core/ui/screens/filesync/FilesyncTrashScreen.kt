package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import id.homebase.resources.filesync_role_folder_returns
import id.homebase.resources.filesync_selected
import id.homebase.resources.filesync_trash
import id.homebase.resources.filesync_trash_empty
import id.homebase.resources.filesync_trash_failed
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

// Deleting one for good lasts only once no computer syncs it: one that does makes it again.
@Composable
private fun roleFoldersReturn(items: List<FilesyncTrashItem>, ids: Set<Uuid>? = null): String {
    val names = items.mapNotNull { item -> item.node.takeIf { it.role != null && (ids == null || it.nodeId in ids) }?.name }
    return if (names.isEmpty()) "" else "\n\n" + pluralStringResource(MR.plurals.filesync_role_folder_returns, names.size, names.size, names.joinToString(", "))
}

/** Recently deleted as a trash can: what each delete took, newest first, to restore or delete for good. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun FilesyncTrashScreen(viewModel: FilesyncTrashViewModel, places: FilesyncPlaces, onBack: () -> Unit) {
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
            } + roleFoldersReturn(uiState.items, ids),
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
            text = stringResource(MR.string.filesync_empty_trash_confirm) + roleFoldersReturn(uiState.items),
            confirmLabel = stringResource(MR.string.filesync_empty_trash),
            destructive = true,
            onConfirm = {
                askEmpty = false
                viewModel.onAction(FilesyncTrashAction.EmptyTrash)
            },
            onDismiss = { askEmpty = false },
        )
    }

    val title = if (selecting) {
        pluralStringResource(MR.plurals.filesync_selected, uiState.selected.size, uiState.selected.size)
    } else {
        stringResource(MR.string.filesync_trash)
    }
    val actions: @Composable RowScope.() -> Unit = {
        if (selecting) {
            RestoreButton(enabled = !uiState.busy) { viewModel.onAction(FilesyncTrashAction.Restore(uiState.selected)) }
            IconButton(onClick = { askDelete = uiState.selected }, enabled = !uiState.busy) {
                Icon(
                    Icons.Outlined.DeleteForever,
                    contentDescription = stringResource(MR.string.filesync_delete_forever),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            TextButton(
                onClick = { askEmpty = true },
                enabled = uiState.items.isNotEmpty() && !uiState.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(MR.string.filesync_empty_trash))
            }
        }
    }
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.items.isNotEmpty(),
                onRefresh = { viewModel.onAction(FilesyncTrashAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                FilesyncListBody(
                    isEmpty = uiState.items.isEmpty(),
                    isLoading = uiState.isLoading,
                    loadFailed = uiState.loadFailed,
                    failedMessage = MR.string.filesync_trash_failed,
                    emptyMessage = MR.string.filesync_trash_empty,
                    onRetry = { viewModel.onAction(FilesyncTrashAction.Refresh) },
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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

    FilesyncPlaceScaffold(
        place = FilesyncPlace.Trash,
        places = places,
        snackbarHostState = snackbarHostState,
        title = title,
        compactTopBar = {
            TopAppBar(
                title = { Text(title) },
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
                actions = actions,
            )
        },
        actions = actions,
        content = body,
    )
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
