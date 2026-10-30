package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.fileDropTarget
import id.homebase.core.ui.screens.filesync.components.FilesyncDownloadButtons
import id.homebase.core.ui.screens.filesync.components.FilesyncInstallHere
import id.homebase.core.ui.screens.filesync.components.FilesyncPhoneAppButtons
import id.homebase.core.ui.screens.filesync.components.filesyncInstallHere
import id.homebase.core.util.isExpandedLayout
import id.homebase.core.util.getUriHandler
import id.homebase.core.privatedrive.PrivateDriveState
import id.homebase.core.ui.screens.privatedrive.PrivateDriveBanners
import id.homebase.core.ui.screens.privatedrive.SignInAgainLauncher
import id.homebase.core.ui.screens.privatedrive.rememberSignInAgainLauncher
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.filesync_get_title
import id.homebase.resources.filesync_install_computer
import id.homebase.resources.filesync_install_app
import id.homebase.resources.delete
import id.homebase.resources.cancel
import id.homebase.resources.close
import id.homebase.resources.create
import id.homebase.resources.filesync_check_failed
import id.homebase.resources.filesync_desktop_title
import id.homebase.resources.filesync_dismiss
import id.homebase.resources.filesync_files
import id.homebase.resources.filesync_folder_deleted
import id.homebase.resources.filesync_folder_empty
import id.homebase.resources.filesync_folders
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_make_private
import id.homebase.resources.filesync_sign_in_again_body
import id.homebase.resources.filesync_sign_in_again_title
import id.homebase.resources.filesync_load_failed
import id.homebase.resources.filesync_more
import id.homebase.resources.filesync_new_folder
import id.homebase.resources.filesync_new_folder_name
import id.homebase.resources.filesync_no_matches
import id.homebase.resources.filesync_recent
import id.homebase.resources.filesync_restore
import id.homebase.resources.filesync_remove_confirm
import id.homebase.resources.filesync_remove_confirm_many
import id.homebase.resources.filesync_restore_confirm
import id.homebase.resources.filesync_retry
import id.homebase.resources.filesync_search
import id.homebase.resources.filesync_select_all
import id.homebase.resources.filesync_show_deleted
import id.homebase.resources.filesync_sort_ascending
import id.homebase.resources.filesync_sort_descending
import id.homebase.resources.filesync_trash
import id.homebase.resources.filesync_upload
import id.homebase.resources.filesync_upload_failed_row
import id.homebase.resources.filesync_uploading
import id.homebase.resources.filesync_uploads_queued
import id.homebase.resources.menu_back
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Remembered once per screen so rows that get it can skip recomposing.
@Stable
internal class FilesyncBrowserActions(
    val onAction: (FilesyncUiAction) -> Unit,
    private val onAskRestore: (FilesyncNode) -> Unit,
    // Read when a menu opens, so rows needn't take the whole selection and redraw with every change to it.
    val current: () -> FilesyncUiState = { FilesyncUiState() },
) {
    fun click(node: FilesyncNode) {
        if (node.removedMs != null && !node.isFolder) onAskRestore(node) else onAction(FilesyncUiAction.NodeClicked(node))
    }
}

internal class FilesyncBrowserScope(
    val uiState: FilesyncUiState,
    val uploads: List<FilesyncUpload>,
    val search: TextFieldState,
    val snackbarHostState: SnackbarHostState,
    val actions: FilesyncBrowserActions,
    val onUpload: () -> Unit,
    val onFilesDropped: (List<PlatformFile>) -> Unit,
    val onNewFolder: () -> Unit,
    val places: FilesyncPlaces,
    val signIn: SignInAgainLauncher,
) {
    val onAction get() = actions.onAction
    val currentRemoved get() = uiState.path.last().removedMs != null
}

// Whatever lands in a deleted folder is out of sight on every device.
@Composable
internal fun Modifier.browserDropTarget(scope: FilesyncBrowserScope): Modifier =
    fileDropTarget(enabled = !scope.currentRemoved, onDragPreviewChanged = {}, onFilesDropped = scope.onFilesDropped)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FilesyncScreen(viewModel: FilesyncViewModel, places: FilesyncPlaces, onOpenWebDrop: () -> Unit, onSendToChat: (path: String, name: String) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val signIn = rememberSignInAgainLauncher(viewModel::onSignInCallback)
    FilesyncEventsEffect(
        viewModel.events,
        snackbarHostState,
        onOpenWebDrop,
        onSendToChat,
        onUndoRemove = { viewModel.onAction(FilesyncUiAction.Restore(it)) },
        onOpenSignIn = signIn::open,
    ) { viewModel.onAction(FilesyncUiAction.SaveHandedOff(it)) }

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
    uiState.confirmDelete?.let { nodes ->
        FilesyncConfirmDialog(
            text = if (nodes.size == 1) {
                stringResource(MR.string.filesync_remove_confirm, nodes.single().name)
            } else {
                pluralStringResource(MR.plurals.filesync_remove_confirm_many, nodes.size, nodes.size)
            } + roleFoldersReturn(nodes),
            confirmLabel = stringResource(MR.string.delete),
            destructive = true,
            onConfirm = { viewModel.onAction(FilesyncUiAction.ConfirmDelete) },
            onDismiss = { viewModel.onAction(FilesyncUiAction.DismissDelete) },
        )
    }
    uiState.movePicker?.let { FilesyncMoveDialog(it, viewModel::onAction) }
    uiState.shareChooser?.let { FilesyncShareSheet(it, viewModel::onAction) }
    var askRestore by remember { mutableStateOf<FilesyncNode?>(null) }
    askRestore?.let { node ->
        FilesyncConfirmDialog(
            text = stringResource(MR.string.filesync_restore_confirm, node.name),
            confirmLabel = stringResource(MR.string.filesync_restore),
            onConfirm = {
                askRestore = null
                viewModel.onAction(FilesyncUiAction.Restore(setOf(node.nodeId)))
            },
            onDismiss = { askRestore = null },
        )
    }

    val currentFolder = uiState.path.last().nodeId

    val actions = remember(viewModel) { FilesyncBrowserActions(viewModel::onAction, { askRestore = it }, { viewModel.uiState.value }) }
    val scope = FilesyncBrowserScope(
        uiState = uiState,
        uploads = uploads.filter { it.parentId == currentFolder },
        search = viewModel.search,
        snackbarHostState = snackbarHostState,
        actions = actions,
        onUpload = { filePicker.launch() },
        onFilesDropped = onFilesChosen,
        onNewFolder = { showNewFolder = true },
        places = places,
        signIn = signIn,
    )
    if (isExpandedLayout()) {
        FilesyncWideBrowser(scope)
    } else {
        FilesyncCompactBrowser(scope)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun FilesyncCompactBrowser(scope: FilesyncBrowserScope) {
    val uiState = scope.uiState
    // Open already when the wide layout left a search behind, so it's never applied out of sight.
    var searching by remember(uiState.path.last().nodeId) { mutableStateOf(uiState.query.isNotEmpty()) }
    val searchFocus = remember { FocusRequester() }
    val closeSearch = {
        searching = false
        scope.search.clearText()
    }
    BackHandler(enabled = searching, onBack = closeSearch)
    val selecting = uiState.selecting
    // After the search's, so Back clears a selection made in the results before it closes the search.
    BackHandler(enabled = selecting) { scope.onAction(FilesyncUiAction.ClearSelection) }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
    var infoFor by remember { mutableStateOf<FilesyncNode?>(null) }
    infoFor?.let { node ->
        AdaptiveSheet(onDismiss = { infoFor = null }) {
            FilesyncInfoContent(
                node = node,
                where = crumbNames(uiState.path),
                onAction = {
                    dismiss()
                    scope.onAction(it)
                },
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            )
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                FilesyncSelectionTopBar(uiState.selected.size, onClear = { scope.onAction(FilesyncUiAction.ClearSelection) }) {
                    // Material's three: the first action, select all, and the rest behind More.
                    val first = uiState.bulkActions.firstOrNull()
                    if (first != null) {
                        IconButton(onClick = { scope.onAction(FilesyncUiAction.Bulk(first)) }, enabled = uiState.isEnabled(first)) {
                            Icon(first.icon, contentDescription = stringResource(first.label))
                        }
                    }
                    IconButton(onClick = { scope.onAction(FilesyncUiAction.SelectAll) }) {
                        Icon(Icons.Default.SelectAll, contentDescription = stringResource(MR.string.filesync_select_all))
                    }
                    val rest = uiState.bulkActions.drop(1)
                    if (rest.isNotEmpty()) {
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { more = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.filesync_more))
                            }
                            FilesyncSelectionMenu(rest, expanded = more, onDismiss = { more = false }, onAction = scope.onAction, withClear = false)
                        }
                    }
                }
            } else {
                TopAppBar(
                    title = {
                        if (searching) {
                            MinimalSearchTextField(
                                modifier = Modifier.focusRequester(searchFocus),
                                textFieldState = scope.search,
                                showBackButton = true,
                                placeHolderText = stringResource(MR.string.filesync_search),
                                onBackButtonClick = closeSearch,
                            )
                        } else {
                            Text(
                                text = uiState.path.lastOrNull()?.name ?: stringResource(MR.string.filesync_label),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if (uiState.path.size > 1 && !searching) {
                            IconButton(onClick = { scope.onAction(FilesyncUiAction.NavigateUp) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                            }
                        }
                    },
                    actions = {
                        if (!searching) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(MR.string.filesync_search))
                            }
                            if (!scope.currentRemoved) {
                                IconButton(onClick = scope.onNewFolder) {
                                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(MR.string.filesync_new_folder))
                                }
                            }
                            OverflowMenu(
                                showDeleted = uiState.showDeleted,
                                onToggleShowDeleted = { scope.onAction(FilesyncUiAction.ToggleShowDeleted) },
                                places = scope.places,
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // Whatever lands in a deleted folder is out of sight on every device.
            if (!scope.currentRemoved && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = scope.onUpload,
                    icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                    text = { Text(stringResource(MR.string.filesync_upload)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(scope.snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .browserDropTarget(scope),
        ) {
            if (uiState.path.size > 1) {
                Breadcrumbs(path = uiState.path, onCrumb = { scope.onAction(FilesyncUiAction.NavigateToCrumb(it)) })
            }
            SortChips(
                sort = uiState.sort,
                tagFilter = uiState.tagFilter,
                onSort = { scope.onAction(FilesyncUiAction.SortBy(it)) },
                onClearTag = { name -> scope.onAction(FilesyncUiAction.FilterTag(name)) },
            )
            FilesyncPrivateDriveBanners(uiState.privateDrive, scope.signIn, scope.actions)
            if (scope.currentRemoved) DeletedFolderBanner(scope)
            uiState.transfer?.takeIf { it.isZip }?.let { FilesyncArchiveBar(it, onCancel = { scope.onAction(FilesyncUiAction.CancelTransfer) }) }
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.nodes.isNotEmpty(),
                onRefresh = { scope.onAction(FilesyncUiAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                BrowserListBody(scope) {
                    // Bottom padding keeps the last row's buttons clear of the upload button.
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                        if (scope.uploads.isNotEmpty()) {
                            item(key = "uploads") {
                                UploadStrip(scope.uploads, scope.onAction, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                        }
                        // arranged() puts folders first, so the split is a view, not a copy.
                        val folders = uiState.visible.subList(0, uiState.totals.folders)
                        val files = uiState.visible.subList(uiState.totals.folders, uiState.visible.size)
                        if (folders.isNotEmpty()) item(key = "folders") { SectionHeader(MR.string.filesync_folders) }
                        val checked: (FilesyncNode) -> Boolean? = { node -> if (selecting) node.nodeId in uiState.selection.ids else null }
                        items(folders, key = { it.nodeId.toString() }) { CompactRow(it, checked(it), uiState.transfer.progressOf(it.nodeId), scope.actions) { infoFor = it } }
                        if (files.isNotEmpty()) item(key = "files") { SectionHeader(MR.string.filesync_files) }
                        items(files, key = { it.nodeId.toString() }) { CompactRow(it, checked(it), uiState.transfer.progressOf(it.nodeId), scope.actions) { infoFor = it } }
                    }
                }
            }
        }
    }
}

/** [checked] is null unless rows are being picked: a long press starts it, and then a tap picks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactRow(node: FilesyncNode, checked: Boolean?, progress: Float?, actions: FilesyncBrowserActions, onInfo: (FilesyncNode) -> Unit) {
    val toggle = { actions.onAction(FilesyncUiAction.Select(node.nodeId, FilesyncSelectMode.Toggle)) }
    FilesyncNodeRow(
        node = node,
        details = nodeDetails(node),
        modifier = Modifier.combinedClickable(onLongClick = toggle, onClick = { if (checked != null) toggle() else actions.click(node) }),
        selected = checked == true,
        checked = checked,
        trailing = {
            if (checked != null) {
                // Picking rows: nothing beside them to tap by mistake.
            } else if (progress != null) {
                CancelTransferButton(progress) { actions.onAction(FilesyncUiAction.CancelTransfer) }
            } else {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.filesync_more))
                    }
                    FilesyncNodeMenu(
                        node = node,
                        expanded = expanded,
                        onDismiss = { expanded = false },
                        onAction = actions.onAction,
                        onInfo = { onInfo(node) },
                        canDelete = !actions.current().inDeletedFolder,
                    )
                }
            }
        },
    )
}

@Composable
internal fun BrowserListBody(scope: FilesyncBrowserScope, content: @Composable () -> Unit) {
    val uiState = scope.uiState
    FilesyncListBody(
        isEmpty = uiState.visible.isEmpty() && scope.uploads.isEmpty(),
        isLoading = uiState.isLoading,
        loadFailed = uiState.loadFailed,
        failedMessage = MR.string.filesync_load_failed,
        emptyMessage = if (uiState.nodes.isEmpty()) MR.string.filesync_folder_empty else MR.string.filesync_no_matches,
        onRetry = { scope.onAction(FilesyncUiAction.Refresh) },
        content = content,
    )
}

// Takes the parts, not the scope: the scope is rebuilt on every upload tick and keystroke.
@Composable
internal fun FilesyncPrivateDriveBanners(state: PrivateDriveState, signIn: SignInAgainLauncher, actions: FilesyncBrowserActions) {
    PrivateDriveBanners(
        state = state,
        makePrivateText = MR.string.filesync_make_private,
        signInTitle = MR.string.filesync_sign_in_again_title,
        signInBody = MR.string.filesync_sign_in_again_body,
        signIn = signIn,
        onMakePrivate = { actions.onAction(FilesyncUiAction.MakePrivate) },
        onSignInAgain = { actions.onAction(FilesyncUiAction.SignInAgain) },
    )
}

@Composable
internal fun DeletedFolderBanner(scope: FilesyncBrowserScope) {
    ListItem(
        headlineContent = { Text(stringResource(MR.string.filesync_folder_deleted)) },
        trailingContent = {
            TextButton(onClick = { scope.onAction(FilesyncUiAction.Restore(setOf(scope.uiState.path.last().nodeId))) }) {
                Text(stringResource(MR.string.filesync_restore))
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun SectionHeader(label: StringResource) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

// A tag filter set in the wide layout shows here too, so it can be cleared.
@Composable
private fun SortChips(sort: FilesyncSort, tagFilter: String?, onSort: (FilesyncSortKey) -> Unit, onClearTag: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tagFilter?.let { name ->
            InputChip(
                selected = true,
                onClick = { onClearTag(name) },
                label = { Text(name) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.filesync_dismiss), modifier = Modifier.size(16.dp)) },
            )
        }
        FilesyncSortKey.entries.forEach { key ->
            val active = sort.key == key
            FilterChip(
                selected = active,
                onClick = { onSort(key) },
                label = { Text(stringResource(key.label)) },
                leadingIcon = if (active) {
                    { SortArrow(sort.ascending, Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
internal fun SortArrow(ascending: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
        contentDescription = stringResource(if (ascending) MR.string.filesync_sort_ascending else MR.string.filesync_sort_descending),
        modifier = modifier,
    )
}

@Composable
internal fun UploadStrip(uploads: List<FilesyncUpload>, onAction: (FilesyncUiAction) -> Unit, modifier: Modifier = Modifier) {
    val (failed, pending) = uploads.partition { it.failed }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        failed.forEach { upload ->
            UploadCard {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column(modifier = Modifier.weight(1f)) {
                    Text(upload.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(MR.string.filesync_upload_failed_row), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onAction(FilesyncUiAction.RetryUpload(upload.id)) }) { Text(stringResource(MR.string.filesync_retry)) }
                IconButton(onClick = { onAction(FilesyncUiAction.DismissUpload(upload.id)) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.filesync_dismiss))
                }
            }
        }
        pending.firstOrNull()?.let { upload ->
            UploadCard {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(upload.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Text(
                            text = if (pending.size > 1) pluralStringResource(MR.plurals.filesync_uploads_queued, pending.size - 1, pending.size - 1) else stringResource(MR.string.filesync_uploading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val fraction = upload.progress
                    if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun UploadCard(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun crumbNames(path: List<FilesyncCrumb>): List<String> {
    val root = stringResource(MR.string.filesync_label)
    return path.map { it.name ?: root }
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
internal fun OverflowMenu(
    showDeleted: Boolean,
    onToggleShowDeleted: () -> Unit,
    // Null in the wide layout, whose sidebar already has Recent and Trash.
    places: FilesyncPlaces?,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    val installHere = remember { filesyncInstallHere() }
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
            if (places != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.filesync_recent)) },
                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    onClick = {
                        expanded = false
                        places.onRecent()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.filesync_trash)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        expanded = false
                        places.onTrash()
                    },
                )
            }
            when (val here = installHere) {
                // The phone app is Filesync's phone browser already.
                FilesyncInstallHere.InApp -> Unit
                is FilesyncInstallHere.Phone -> DropdownMenuItem(
                    text = { Text(stringResource(MR.string.filesync_install_app)) },
                    leadingIcon = { Icon(Icons.Outlined.PhoneIphone, contentDescription = null) },
                    onClick = {
                        expanded = false
                        uriHandler.openUrl(here.app.url)
                    },
                )
                is FilesyncInstallHere.Computer -> DropdownMenuItem(
                    text = { Text(stringResource(here.os?.installLabel ?: MR.string.filesync_install_computer)) },
                    leadingIcon = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                    onClick = {
                        expanded = false
                        showDownloads = true
                    },
                )
            }
        }
    }
    if (showDownloads) {
        AlertDialog(
            onDismissRequest = { showDownloads = false },
            title = { Text(stringResource(MR.string.filesync_get_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    FilesyncDownloadButtons()
                    FilesyncPhoneAppButtons()
                }
            },
            confirmButton = { TextButton(onClick = { showDownloads = false }) { Text(stringResource(MR.string.close)) } },
        )
    }
}

@Composable
internal fun Breadcrumbs(path: List<FilesyncCrumb>, onCrumb: (Int) -> Unit) {
    val names = crumbNames(path)
    LazyRow(
        modifier = Modifier.padding(horizontal = 8.dp),
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
            val last = index == path.lastIndex
            TextButton(onClick = { onCrumb(index) }, enabled = !last) {
                Text(
                    text = names[index],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (last) FontWeight.Bold else null,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun FilesyncCheckingScreen(failed: Boolean, onRetry: () -> Unit) {
    if (failed) RetryMessage(MR.string.filesync_check_failed, onRetry) else CenteredBox { CircularProgressIndicator() }
}
