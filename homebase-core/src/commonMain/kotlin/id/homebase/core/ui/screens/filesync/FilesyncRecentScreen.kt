package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.common.util.formatBytes
import id.homebase.core.util.formatMomentDate
import id.homebase.core.util.formatTime
import id.homebase.resources.MR
import id.homebase.resources.filesync_from_folder
import id.homebase.resources.filesync_in_folder
import id.homebase.resources.filesync_recent
import id.homebase.resources.filesync_recent_added
import id.homebase.resources.filesync_recent_added_empty
import id.homebase.resources.filesync_recent_deleted
import id.homebase.resources.filesync_recent_deleted_empty
import id.homebase.resources.filesync_recent_failed
import id.homebase.resources.menu_back
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** What arrived lately next to what was deleted lately: a timeline of single files, by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesyncRecentScreen(viewModel: FilesyncRecentViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FilesyncEventsEffect(viewModel.events, snackbarHostState) { viewModel.onAction(FilesyncRecentAction.SaveHandedOff(it)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.filesync_recent)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilesyncRecentTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = tab == uiState.tab,
                        onClick = { viewModel.onAction(FilesyncRecentAction.SelectTab(tab)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = FilesyncRecentTab.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (tab) {
                                    FilesyncRecentTab.Added -> MR.string.filesync_recent_added
                                    FilesyncRecentTab.Deleted -> MR.string.filesync_recent_deleted
                                }
                            )
                        )
                    }
                }
            }
            val deletedTab = uiState.tab == FilesyncRecentTab.Deleted
            val files = if (deletedTab) uiState.deleted else uiState.added
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && files.isNotEmpty(),
                onRefresh = { viewModel.onAction(FilesyncRecentAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    files.isEmpty() && uiState.isLoading -> CenteredBox { CircularProgressIndicator() }
                    files.isEmpty() && uiState.loadFailed -> RetryMessage(MR.string.filesync_recent_failed) {
                        viewModel.onAction(FilesyncRecentAction.Refresh)
                    }
                    files.isEmpty() -> CenteredBox {
                        Text(
                            text = stringResource(if (deletedTab) MR.string.filesync_recent_deleted_empty else MR.string.filesync_recent_added_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> RecentList(files, deletedTab, uiState, viewModel::onAction)
                }
            }
        }
    }
}

@Composable
private fun RecentList(
    files: List<FilesyncNode>,
    deletedTab: Boolean,
    uiState: FilesyncRecentUiState,
    onAction: (FilesyncRecentAction) -> Unit,
) {
    val timeOf = { node: FilesyncNode -> node.removal?.atMs ?: node.addedMs }
    val days = remember(files) {
        val zone = TimeZone.currentSystemDefault()
        files.groupBy { Instant.fromEpochMilliseconds(timeOf(it)).toLocalDateTime(zone).date }.values.toList()
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        days.forEach { day ->
            item(key = "day-${timeOf(day.first())}") {
                Text(
                    text = formatMomentDate(Instant.fromEpochMilliseconds(timeOf(day.first()))),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(day, key = { it.nodeId.toString() }) { node ->
                val folder = folderName(node.parentId, uiState.folderNames)
                val time = formatTime(Instant.fromEpochMilliseconds(timeOf(node)))
                if (deletedTab) {
                    FilesyncNodeRow(
                        node = node,
                        details = stringResource(MR.string.filesync_from_folder, folder) + " · " + deletedAt(time),
                        dimDeleted = false,
                        trailing = { RestoreButton { onAction(FilesyncRecentAction.Restore(node.nodeId)) } },
                    )
                } else {
                    FilesyncNodeRow(
                        node = node,
                        details = listOfNotNull(
                            stringResource(MR.string.filesync_in_folder, folder),
                            node.sizeBytes?.let { formatBytes(it) },
                            time,
                        ).joinToString(" · "),
                        modifier = Modifier.clickable { onAction(FilesyncRecentAction.Open(node)) },
                        trailing = {
                            SaveButton(
                                progress = uiState.transfer?.takeIf { it.nodeId == node.nodeId }?.progress,
                                onSave = { onAction(FilesyncRecentAction.Save(node)) },
                                onCancel = { onAction(FilesyncRecentAction.CancelTransfer) },
                            )
                        },
                    )
                }
            }
        }
    }
}
