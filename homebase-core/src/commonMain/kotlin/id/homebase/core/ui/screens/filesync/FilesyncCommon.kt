package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.common.util.formatBytes
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.filesync_cancel_download
import id.homebase.resources.filesync_changed_elsewhere
import id.homebase.resources.filesync_damaged
import id.homebase.resources.filesync_delete_failed
import id.homebase.resources.filesync_deleted
import id.homebase.resources.filesync_deleted_forever
import id.homebase.resources.filesync_download_failed
import id.homebase.resources.filesync_folder
import id.homebase.resources.filesync_folder_failed
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_open_failed
import id.homebase.resources.filesync_restore
import id.homebase.resources.filesync_restore_failed
import id.homebase.resources.filesync_restored
import id.homebase.resources.filesync_retry
import id.homebase.resources.filesync_save
import id.homebase.resources.filesync_save_failed
import id.homebase.resources.filesync_too_large
import id.homebase.resources.filesync_upload_failed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

// M3's disabled emphasis: a deleted item is shown but is no longer part of the folder.
private const val DELETED_ALPHA = 0.38f

/** Hands finished downloads to the OS and shows every message a Filesync screen can get. */
@Composable
internal fun FilesyncEventsEffect(
    events: Flow<FilesyncUiEvent>,
    snackbarHostState: SnackbarHostState,
    onSaveHandedOff: (String) -> Unit,
) {
    val fileSystemHandler = getUriHandler()
    val scope = rememberCoroutineScope()
    fun show(message: StringResource) {
        scope.launch { snackbarHostState.showSnackbar(getString(message)) }
    }
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is FilesyncUiEvent.OpenFile -> fileSystemHandler.openFile(
                    Path(event.path),
                    showChooser = false,
                    onError = { show(MR.string.filesync_open_failed) },
                )
                is FilesyncUiEvent.SaveFile -> fileSystemHandler.saveFile(
                    Path(event.path),
                    event.name,
                    onSuccess = { onSaveHandedOff(event.path) },
                    onError = {
                        onSaveHandedOff(event.path)
                        show(MR.string.filesync_save_failed)
                    },
                )
                is FilesyncUiEvent.Error -> show(
                    when (event.error) {
                        FilesyncError.DownloadFailed -> MR.string.filesync_download_failed
                        FilesyncError.Damaged -> MR.string.filesync_damaged
                        FilesyncError.UploadFailed -> MR.string.filesync_upload_failed
                        FilesyncError.TooLarge -> MR.string.filesync_too_large
                        FilesyncError.FolderFailed -> MR.string.filesync_folder_failed
                        FilesyncError.RestoreFailed -> MR.string.filesync_restore_failed
                        FilesyncError.DeleteFailed -> MR.string.filesync_delete_failed
                    }
                )
                is FilesyncUiEvent.Notice -> show(
                    when (event.notice) {
                        FilesyncNotice.Restored -> MR.string.filesync_restored
                        FilesyncNotice.DeletedForever -> MR.string.filesync_deleted_forever
                        FilesyncNotice.ChangedElsewhere -> MR.string.filesync_changed_elsewhere
                    }
                )
            }
        }
    }
}

/** One node in any Filesync list; among live ones a deleted node is dimmed, but its [trailing] action is not. */
@Composable
internal fun FilesyncNodeRow(
    node: FilesyncNode,
    details: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dimDeleted: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val dim = if (dimDeleted && node.removal != null) Modifier.alpha(DELETED_ALPHA) else Modifier
    ListItem(
        modifier = modifier,
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = dim) },
        supportingContent = { if (details.isNotEmpty()) Text(details, modifier = dim) },
        leadingContent = {
            Icon(
                imageVector = if (node.isFolder) Icons.Outlined.Folder else fileTypeIcon(null, node.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = dim,
            )
        },
        trailingContent = trailing,
    )
}

/** "Folder", or a file's size and date; a deleted node says when it was deleted instead. */
@Composable
internal fun nodeDetails(node: FilesyncNode): String {
    val removal = node.removal
    if (removal != null) return deletedAt(formatTimestamp(Instant.fromEpochMilliseconds(removal.atMs)))
    if (node.isFolder) return stringResource(MR.string.filesync_folder)
    return listOfNotNull(
        node.sizeBytes?.let { formatBytes(it) },
        node.modifiedMs?.let { formatMediumDate(Instant.fromEpochMilliseconds(it)) },
    ).joinToString(" · ")
}

@Composable
internal fun deletedAt(time: String): String =
    stringResource(MR.string.filesync_deleted) + " · " + time

/** The name of the folder [parentId], or of the top level. */
@Composable
internal fun folderName(parentId: Uuid, names: Map<Uuid, String>): String =
    names[parentId] ?: stringResource(MR.string.filesync_label)

/** Save to device, or the download's progress, which cancels it when tapped. */
@Composable
internal fun SaveButton(progress: Float?, onSave: () -> Unit, onCancel: () -> Unit) {
    if (progress != null) {
        val cancelLabel = stringResource(MR.string.filesync_cancel_download)
        IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = cancelLabel }) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
        }
    } else {
        IconButton(onClick = onSave) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(MR.string.filesync_save))
        }
    }
}

@Composable
internal fun RestoreButton(onRestore: () -> Unit) {
    IconButton(onClick = onRestore) {
        Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(MR.string.filesync_restore))
    }
}

/** A yes/no question; [destructive] paints the confirm button in the error colour. */
@Composable
internal fun FilesyncConfirmDialog(
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
internal fun RetryMessage(message: StringResource, onRetry: () -> Unit) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(message))
            TextButton(onClick = onRetry) { Text(stringResource(MR.string.filesync_retry)) }
        }
    }
}

@Composable
internal fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
