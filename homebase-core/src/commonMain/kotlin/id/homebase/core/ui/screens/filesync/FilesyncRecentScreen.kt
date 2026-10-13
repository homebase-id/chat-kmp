package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.common.util.formatBytes
import id.homebase.core.widget.SettingsTopBar
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** What arrived lately next to what was deleted lately: a timeline of single files, by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesyncRecentScreen(viewModel: FilesyncRecentViewModel, places: FilesyncPlaces, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FilesyncEventsEffect(viewModel.events, snackbarHostState) { viewModel.onAction(FilesyncRecentAction.SaveHandedOff(it)) }

    val title = stringResource(MR.string.filesync_recent)
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
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
                FilesyncListBody(
                    isEmpty = files.isEmpty(),
                    isLoading = uiState.isLoading,
                    loadFailed = uiState.loadFailed,
                    failedMessage = MR.string.filesync_recent_failed,
                    emptyMessage = if (deletedTab) MR.string.filesync_recent_deleted_empty else MR.string.filesync_recent_added_empty,
                    onRetry = { viewModel.onAction(FilesyncRecentAction.Refresh) },
                ) {
                    RecentList(files, deletedTab, uiState, viewModel::onAction)
                }
            }
        }
    }
    FilesyncPlaceScaffold(
        place = FilesyncPlace.Recent,
        places = places,
        snackbarHostState = snackbarHostState,
        title = title,
        compactTopBar = { SettingsTopBar(title = title, onBack = onBack) },
        content = body,
    )
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
                val ms = timeOf(node)
                val time = remember(ms) { formatTime(Instant.fromEpochMilliseconds(ms)) }
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
                                progress = uiState.transfer.progressOf(node.nodeId),
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
