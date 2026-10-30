package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.filesync_load_failed
import id.homebase.resources.filesync_move_here
import id.homebase.resources.filesync_move_title
import id.homebase.resources.filesync_move_title_many
import id.homebase.resources.filesync_no_folders
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Move to…: walk the folders, then move here. A folder being moved is shown but can't be opened. */
@Composable
internal fun FilesyncMoveDialog(picker: FilesyncMovePicker, onAction: (FilesyncUiAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(FilesyncUiAction.DismissMove) },
        title = {
            Text(
                if (picker.nodes.size == 1) {
                    stringResource(MR.string.filesync_move_title, picker.nodes.single().name)
                } else {
                    pluralStringResource(MR.plurals.filesync_move_title_many, picker.nodes.size, picker.nodes.size)
                }
            )
        },
        text = {
            Column {
                Breadcrumbs(picker.path, onCrumb = { onAction(FilesyncUiAction.MovePickerToCrumb(it)) })
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    FilesyncListBody(
                        isEmpty = picker.folders.isEmpty(),
                        isLoading = picker.loading,
                        loadFailed = picker.loadFailed,
                        failedMessage = MR.string.filesync_load_failed,
                        emptyMessage = MR.string.filesync_no_folders,
                        onRetry = { onAction(FilesyncUiAction.MovePickerToCrumb(picker.path.lastIndex)) },
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(picker.folders, key = { it.nodeId.toString() }) { folder ->
                                val moving = picker.isMoving(folder.nodeId)
                                ListItem(
                                    headlineContent = { NodeName(folder, ringColor = MaterialTheme.colorScheme.surfaceContainerHigh) },
                                    leadingContent = { FileTypeTile(folder, size = 32.dp) },
                                    modifier = Modifier
                                        .alpha(if (moving) 0.38f else 1f)
                                        .clickable(enabled = !moving) { onAction(FilesyncUiAction.MovePickerOpen(folder)) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(FilesyncUiAction.ConfirmMove) }, enabled = picker.canMoveHere) {
                Text(stringResource(MR.string.filesync_move_here))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(FilesyncUiAction.DismissMove) }) { Text(stringResource(MR.string.cancel)) }
        },
    )
}
