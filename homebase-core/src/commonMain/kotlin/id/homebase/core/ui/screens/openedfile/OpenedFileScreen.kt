@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.openedfile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.preview.FilePreviewContent
import id.homebase.common.util.formatBytes
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.ui.screens.filesync.Breadcrumbs
import id.homebase.core.ui.screens.filesync.FilesyncCrumb
import id.homebase.core.ui.screens.filesync.FilesyncListBody
import id.homebase.core.ui.screens.filesync.FilesyncNode
import id.homebase.core.ui.screens.filesync.FileTypeTile
import id.homebase.core.ui.screens.filesync.NodeName
import id.homebase.core.ui.screens.filesync.message
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_load_failed
import id.homebase.resources.filesync_no_folders
import id.homebase.resources.filesync_too_large
import id.homebase.resources.menu_back
import id.homebase.resources.openedfile_no_preview
import id.homebase.resources.openedfile_open_failed
import id.homebase.resources.openedfile_open_with
import id.homebase.resources.openedfile_save
import id.homebase.resources.openedfile_save_failed
import id.homebase.resources.openedfile_save_here
import id.homebase.resources.openedfile_save_to
import id.homebase.resources.openedfile_saved
import id.homebase.resources.openedfile_show
import id.homebase.resources.openedfile_sign_in_to_save
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun OpenedFileScreen(
    viewModel: OpenedFileViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSetUpFilesync: () -> Unit,
    onShowInFilesync: (List<FilesyncCrumb>) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = getUriHandler()
    val request = state.request

    BackHandler(enabled = state.saving) {}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(OpenedFileAction.Resumed) }

    LaunchedEffect(viewModel) {
        // A snackbar suspends until dismissed: it must not hold back the navigation events behind it.
        viewModel.events.collect { event ->
            when (event) {
                OpenedFileEvent.OpenSignIn -> onSignIn()
                OpenedFileEvent.OpenFilesyncSetup -> onSetUpFilesync()
                is OpenedFileEvent.Error -> scope.launch { snackbar.showSnackbar(getString(event.error.message())) }
                OpenedFileEvent.SaveFailed -> scope.launch { snackbar.showSnackbar(getString(MR.string.openedfile_save_failed)) }
                is OpenedFileEvent.Saved -> scope.launch {
                    val folder = event.folderName ?: getString(MR.string.filesync_label)
                    val result = snackbar.showSnackbar(
                        message = getString(MR.string.openedfile_saved, folder),
                        actionLabel = getString(MR.string.openedfile_show),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) onShowInFilesync(event.path)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(request.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.onAction(OpenedFileAction.SaveClicked) },
                        enabled = !state.tooLarge && !state.saving && !state.checking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (state.signedIn) MR.string.openedfile_save else MR.string.openedfile_sign_in_to_save))
                    }
                    if (state.tooLarge) {
                        Text(
                            stringResource(MR.string.filesync_too_large),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            uriHandler.openFile(Path(request.path), showChooser = true, onError = {
                                scope.launch { snackbar.showSnackbar(getString(MR.string.openedfile_open_failed)) }
                            })
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(MR.string.openedfile_open_with))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        var failed by remember(request.path) { mutableStateOf(false) }
        val kind = state.kind
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (kind == null || failed) {
                NoPreviewCard(request)
            } else {
                FilePreviewContent(request.path, request.name, kind, contentType = request.mimeType, onFailed = { failed = true })
            }
        }
    }

    state.picker?.let { picker ->
        SaveFolderSheet(
            picker = picker,
            onAction = viewModel::onAction,
        )
    }
}

@Composable
private fun NoPreviewCard(request: OpenedFileRequest) {
    val tile = remember(request.name, request.sizeBytes) {
        FilesyncNode(Uuid.random(), FilesyncProtocol.RootNodeId, isFolder = false, name = request.name, modifiedMs = null, sizeBytes = request.sizeBytes)
    }
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FileTypeTile(tile, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(request.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(formatBytes(request.sizeBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(MR.string.openedfile_no_preview), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SaveFolderSheet(picker: SavePicker, onAction: (OpenedFileAction) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onAction(OpenedFileAction.PickerDismiss) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(MR.string.openedfile_save_to),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Breadcrumbs(picker.path, onCrumb = { onAction(OpenedFileAction.PickerToCrumb(it)) })
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                FilesyncListBody(
                    isEmpty = picker.folders.isEmpty(),
                    isLoading = picker.loading,
                    loadFailed = picker.loadFailed,
                    failedMessage = MR.string.filesync_load_failed,
                    emptyMessage = MR.string.filesync_no_folders,
                    onRetry = { onAction(OpenedFileAction.PickerToCrumb(picker.path.lastIndex)) },
                ) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(picker.folders, key = { it.nodeId.toString() }) { folder ->
                            ListItem(
                                headlineContent = { NodeName(folder, ringColor = MaterialTheme.colorScheme.surfaceContainerLow) },
                                leadingContent = { FileTypeTile(folder, size = 32.dp) },
                                modifier = Modifier.clickable { onAction(OpenedFileAction.PickerOpen(folder)) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { onAction(OpenedFileAction.PickerConfirm) },
                enabled = !picker.loading && !picker.loadFailed,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Text(stringResource(MR.string.openedfile_save_here))
            }
        }
    }
}
