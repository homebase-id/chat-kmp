package id.homebase.core.ui.screens.filesync

import id.homebase.resources.storage_full_upload_failed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.common.util.formatBytes
import id.homebase.core.filesync.FilesyncRoleFolder
import id.homebase.core.ui.screens.vault.components.fileKindIcon
import id.homebase.core.util.FileKind
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.formatMediumDateNoYear
import id.homebase.core.util.formatTime
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.filesync_cancel_download
import id.homebase.resources.filesync_changed_elsewhere
import id.homebase.resources.filesync_restore_try_again
import id.homebase.resources.filesync_damaged
import id.homebase.resources.filesync_day_time
import id.homebase.resources.filesync_delete_failed
import id.homebase.resources.filesync_deleted
import id.homebase.resources.filesync_deleted_forever
import id.homebase.resources.filesync_download_failed
import id.homebase.resources.filesync_executable
import id.homebase.resources.filesync_not_on_computers
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import id.homebase.resources.filesync_folder_failed
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_open_failed
import id.homebase.resources.filesync_restore
import id.homebase.resources.filesync_restore_failed
import id.homebase.resources.filesync_restored
import id.homebase.resources.filesync_restored_to_recovered
import id.homebase.resources.filesync_retry
import id.homebase.resources.filesync_download
import id.homebase.resources.filesync_save_failed
import id.homebase.resources.filesync_share_failed
import id.homebase.resources.filesync_too_large
import id.homebase.resources.filesync_upload_failed
import id.homebase.resources.sign_in_again_failed
import id.homebase.resources.filesync_archive_cancel
import id.homebase.resources.filesync_archive_damaged
import id.homebase.resources.filesync_archive_listing
import id.homebase.resources.filesync_archive_progress
import id.homebase.resources.filesync_clear_selection
import id.homebase.resources.filesync_file_count
import id.homebase.resources.filesync_folder_count
import id.homebase.resources.filesync_selected
import id.homebase.resources.filesync_totals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// M3's disabled emphasis: a deleted item is shown but is no longer part of the folder.
private const val DELETED_ALPHA = 0.38f

/** Hands finished downloads to the OS and shows every message a Filesync screen can get. */
@Composable
internal fun FilesyncEventsEffect(
    events: Flow<FilesyncUiEvent>,
    snackbarHostState: SnackbarHostState,
    onOpenWebDrop: () -> Unit = {},
    onSendToChat: (path: String, name: String) -> Unit = { _, _ -> },
    onOpenSignIn: (String) -> Unit = {},
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
                is FilesyncUiEvent.ShareFile -> fileSystemHandler.shareFile(
                    Path(event.path),
                    onError = { show(MR.string.filesync_share_failed) },
                )
                FilesyncUiEvent.OpenWebDrop -> onOpenWebDrop()
                is FilesyncUiEvent.SendToChat -> onSendToChat(event.path, event.name)
                is FilesyncUiEvent.OpenSignIn -> onOpenSignIn(event.url)
                is FilesyncUiEvent.OpenOwnerConsole -> fileSystemHandler.openUrl(event.url)
                is FilesyncUiEvent.Error -> show(
                    when (event.error) {
                        FilesyncError.DownloadFailed -> MR.string.filesync_download_failed
                        FilesyncError.Damaged -> MR.string.filesync_damaged
                        FilesyncError.UploadFailed -> MR.string.filesync_upload_failed
                        FilesyncError.TooLarge -> MR.string.filesync_too_large
                        FilesyncError.StorageFull -> MR.string.storage_full_upload_failed
                        FilesyncError.FolderFailed -> MR.string.filesync_folder_failed
                        FilesyncError.RestoreFailed -> MR.string.filesync_restore_failed
                        FilesyncError.DeleteFailed -> MR.string.filesync_delete_failed
                        FilesyncError.SignInAgainFailed -> MR.string.sign_in_again_failed
                    }
                )
                is FilesyncUiEvent.Notice -> show(
                    when (event.notice) {
                        FilesyncNotice.Restored -> MR.string.filesync_restored
                        FilesyncNotice.RestoredToRecovered -> MR.string.filesync_restored_to_recovered
                        FilesyncNotice.DeletedForever -> MR.string.filesync_deleted_forever
                        FilesyncNotice.ChangedElsewhere -> MR.string.filesync_changed_elsewhere
                        FilesyncNotice.TryAgainSoon -> MR.string.filesync_restore_try_again
                    }
                )
                is FilesyncUiEvent.ArchiveDamaged -> scope.launch {
                    snackbarHostState.showSnackbar(getString(MR.string.filesync_archive_damaged, event.name))
                }
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
    // A checkbox in the icon's place while picking rows; null shows the icon.
    checked: Boolean? = null,
    trailing: @Composable () -> Unit = {},
) {
    val dim = if (dimDeleted && node.removedMs != null) Modifier.alpha(DELETED_ALPHA) else Modifier
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    ListItem(
        modifier = if (checked == null) modifier else modifier.semantics { toggleableState = ToggleableState(checked) },
        colors = ListItemDefaults.colors(containerColor = container),
        headlineContent = { NodeName(node, ringColor = container, modifier = dim, fontWeight = if (node.isFolder) FontWeight.SemiBold else null, maxLines = 2) },
        supportingContent = { if (details.isNotEmpty()) Text(details, modifier = dim) },
        leadingContent = {
            if (checked == null) {
                FileTypeTile(node, modifier = dim)
            } else {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { SelectBox(ToggleableState(checked)) }
            }
        },
        trailingContent = trailing,
    )
}

@Composable
internal fun NodeName(
    node: FilesyncNode,
    ringColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = node.name,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            fontWeight = fontWeight,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (node.executable) NameBadge(stringResource(MR.string.filesync_executable))
        if (node.notOnComputers) NameBadge(stringResource(MR.string.filesync_not_on_computers))
        if (node.tags.isNotEmpty()) TagDots(node.tags, ringColor)
    }
}

@Composable
internal fun FileTypeTile(node: FilesyncNode, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = MaterialTheme.colorScheme
    val kind = node.kind
    val (container, content) = when {
        node.isFolder -> colors.primaryContainer to colors.primary
        kind == FileKind.Image || kind == FileKind.Video || kind == FileKind.Audio -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = node.role?.let(::roleFolderIcon) ?: node.kind?.let(::fileKindIcon) ?: Icons.Outlined.Folder,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

private fun roleFolderIcon(role: FilesyncRoleFolder): ImageVector = when (role) {
    FilesyncRoleFolder.Documents -> Icons.AutoMirrored.Outlined.LibraryBooks
    FilesyncRoleFolder.Pictures -> Icons.Outlined.PhotoLibrary
    FilesyncRoleFolder.Music -> Icons.Outlined.LibraryMusic
    FilesyncRoleFolder.Videos -> Icons.Outlined.VideoLibrary
    FilesyncRoleFolder.Downloads -> Icons.Outlined.Download
}

// Each dot is ringed in [ringColor] so overlapping neighbours stay apart.
@Composable
private fun TagDots(tags: List<FilesyncTag>, ringColor: Color) {
    val names = tags.joinToString(", ") { it.name }
    Box(modifier = Modifier.semantics { contentDescription = names }) {
        tags.forEachIndexed { index, tag ->
            TagDot(tag, Modifier.padding(start = 7.dp * index).border(2.dp, ringColor, CircleShape).padding(2.dp), size = 10.dp)
        }
    }
}

@Composable
internal fun TagDot(tag: FilesyncTag, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val colour = tag.colour?.let { FinderTagColors.of(it) }
    val dot = Modifier.size(size).clip(CircleShape)
    Box(
        modifier = modifier.then(
            if (colour != null) dot.background(colour) else dot.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    )
}

@Composable
internal fun TagChip(tag: FilesyncTag, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagDot(tag)
            Text(tag.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NameBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A file's size and date, nothing for a folder; a deleted node says when it was deleted instead. */
@Composable
internal fun nodeDetails(node: FilesyncNode): String {
    val removedMs = node.removedMs
    if (removedMs != null) return deletedAt(formatTimestamp(Instant.fromEpochMilliseconds(removedMs)))
    if (node.isFolder) return ""
    return listOfNotNull(
        node.sizeBytes?.let { formatBytes(it) },
        node.modifiedMs?.let { formatDayTime(it) },
    ).joinToString(" · ")
}

@Composable
internal fun formatDayTime(ms: Long): String {
    val instant = Instant.fromEpochMilliseconds(ms)
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    val date = instant.toLocalDateTime(zone).date
    return when (today.toEpochDays() - date.toEpochDays()) {
        0L -> stringResource(MR.string.filesync_day_time, stringResource(MR.string.time_today), formatTime(instant))
        1L -> stringResource(MR.string.filesync_day_time, stringResource(MR.string.time_yesterday), formatTime(instant))
        else -> if (date.year == today.year) formatMediumDateNoYear(instant) else formatMediumDate(instant)
    }
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
        CancelTransferButton(progress, onCancel)
    } else {
        IconButton(onClick = onSave) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(MR.string.filesync_download))
        }
    }
}

@Composable
internal fun CancelTransferButton(progress: Float, onCancel: () -> Unit) {
    val cancelLabel = stringResource(MR.string.filesync_cancel_download)
    IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = cancelLabel }) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun RestoreButton(enabled: Boolean = true, onRestore: () -> Unit) {
    IconButton(onClick = onRestore, enabled = enabled) {
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

/** A list's first load, failed load and empty states; [content] once there's something to show. */
@Composable
internal fun FilesyncListBody(
    isEmpty: Boolean,
    isLoading: Boolean,
    loadFailed: Boolean,
    failedMessage: StringResource,
    emptyMessage: StringResource,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        isEmpty && isLoading -> CenteredBox { CircularProgressIndicator() }
        isEmpty && loadFailed -> RetryMessage(failedMessage, onRetry)
        isEmpty -> CenteredBox {
            Text(
                text = stringResource(emptyMessage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> content()
    }
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

/** Finder's tag colours by filesync's `c`, 1 grey … 7 orange; they're data, so the same in light and dark. */
internal object FinderTagColors {
    private val colours = listOf(
        Color(0xFF8E8E93), // grey
        Color(0xFF34C759), // green
        Color(0xFFAF52DE), // purple
        Color(0xFF007AFF), // blue
        Color(0xFFFFCC00), // yellow
        Color(0xFFFF3B30), // red
        Color(0xFFFF9500), // orange
    )

    fun of(c: Int): Color? = colours.getOrNull(c - 1)
}

/** A checkbox without the 48dp touch minimum, so it fits a row's icon place; with no [onClick] the row does the picking. */
@Composable
internal fun SelectBox(state: ToggleableState, description: String? = null, onClick: (() -> Unit)? = null) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TriStateCheckbox(
            state = state,
            onClick = onClick,
            modifier = if (description == null) Modifier else Modifier.semantics { contentDescription = description },
        )
    }
}

/** The phone's top bar while rows are picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesyncSelectionTopBar(count: Int, onClear: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        title = { Text(pluralStringResource(MR.plurals.filesync_selected, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.filesync_clear_selection))
            }
        },
        actions = actions,
    )
}

@Composable
internal fun totalsLabel(totals: FilesyncTotals): String = stringResource(
    MR.string.filesync_totals,
    pluralStringResource(MR.plurals.filesync_folder_count, totals.folders, totals.folders),
    pluralStringResource(MR.plurals.filesync_file_count, totals.files, totals.files),
    formatBytes(totals.bytes),
)

/** A ZIP being made: how far it has got, and a way to stop it. */
@Composable
internal fun FilesyncArchiveBar(transfer: FilesyncTransfer, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val total = transfer.totalBytes
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (total == null) {
                    stringResource(MR.string.filesync_archive_listing)
                } else {
                    stringResource(MR.string.filesync_archive_progress, formatBytes((transfer.progress * total).toLong()), formatBytes(total))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (total == null || total == 0L) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { transfer.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.filesync_archive_cancel))
        }
    }
}
