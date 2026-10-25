package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.common.util.formatBytes
import id.homebase.core.util.FileKind
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.filesync_deleted
import id.homebase.resources.filesync_folder
import id.homebase.resources.filesync_column_kind
import id.homebase.resources.filesync_column_modified
import id.homebase.resources.filesync_column_name
import id.homebase.resources.filesync_column_size
import id.homebase.resources.info
import id.homebase.resources.filesync_info_added
import id.homebase.resources.filesync_info_checked
import id.homebase.resources.filesync_info_created
import id.homebase.resources.filesync_info_encrypted
import id.homebase.resources.filesync_info_modified
import id.homebase.resources.filesync_info_size
import id.homebase.resources.filesync_info_size_exact
import id.homebase.resources.filesync_info_uploaded
import id.homebase.resources.filesync_info_where
import id.homebase.resources.filesync_kind_app
import id.homebase.resources.filesync_kind_archive
import id.homebase.resources.filesync_kind_audio
import id.homebase.resources.filesync_kind_code
import id.homebase.resources.filesync_kind_file
import id.homebase.resources.filesync_kind_image
import id.homebase.resources.filesync_kind_pdf
import id.homebase.resources.filesync_kind_presentation
import id.homebase.resources.filesync_kind_size
import id.homebase.resources.filesync_kind_spreadsheet
import id.homebase.resources.filesync_kind_text
import id.homebase.resources.filesync_kind_video
import id.homebase.resources.filesync_kind_word
import id.homebase.resources.filesync_open
import id.homebase.resources.filesync_restore
import id.homebase.resources.save
import id.homebase.resources.filesync_send_webdrop
import id.homebase.resources.share
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val FilesyncSortKey.label: StringResource
    get() = when (this) {
        FilesyncSortKey.Name -> MR.string.filesync_column_name
        FilesyncSortKey.Modified -> MR.string.filesync_column_modified
        FilesyncSortKey.Size -> MR.string.filesync_column_size
        FilesyncSortKey.Kind -> MR.string.filesync_column_kind
    }

@Composable
internal fun kindLabel(node: FilesyncNode): String = stringResource(
    when (node.kind) {
        null -> MR.string.filesync_folder
        FileKind.Archive -> MR.string.filesync_kind_archive
        FileKind.Pdf -> MR.string.filesync_kind_pdf
        FileKind.Word -> MR.string.filesync_kind_word
        FileKind.Spreadsheet -> MR.string.filesync_kind_spreadsheet
        FileKind.Presentation -> MR.string.filesync_kind_presentation
        FileKind.Text -> MR.string.filesync_kind_text
        FileKind.Code -> MR.string.filesync_kind_code
        FileKind.Audio -> MR.string.filesync_kind_audio
        FileKind.Video -> MR.string.filesync_kind_video
        FileKind.Image -> MR.string.filesync_kind_image
        FileKind.Apk -> MR.string.filesync_kind_app
        FileKind.Generic -> MR.string.filesync_kind_file
    }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilesyncInfoContent(
    node: FilesyncNode,
    where: List<String>,
    onAction: (FilesyncUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FileTypeTile(node, size = 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val size = node.sizeBytes
                Text(
                    text = if (size == null) kindLabel(node) else stringResource(MR.string.filesync_kind_size, kindLabel(node), formatBytes(size)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (node.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.tags.forEach { TagChip(it) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (node.removedMs != null) {
                Button(onClick = { onAction(FilesyncUiAction.Restore(node.nodeId)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(MR.string.filesync_restore))
                }
            } else {
                Button(onClick = { onAction(FilesyncUiAction.NodeClicked(node)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(MR.string.filesync_open))
                }
                if (!node.isFolder) {
                    OutlinedButton(onClick = { onAction(FilesyncUiAction.SaveClicked(node)) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(MR.string.save))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            node.sizeBytes?.let { InfoRow(MR.string.filesync_info_size, stringResource(MR.string.filesync_info_size_exact, formatBytes(it), it.toString())) }
            node.modifiedMs?.let { InfoRow(MR.string.filesync_info_modified, formatDayTime(it)) }
            node.createdMs?.let { InfoRow(MR.string.filesync_info_created, formatDayTime(it)) }
            node.addedMs.takeIf { it > 0 }?.let { InfoRow(MR.string.filesync_info_added, formatDayTime(it)) }
            node.uploadedMs?.let { InfoRow(MR.string.filesync_info_uploaded, formatDayTime(it)) }
            node.removedMs?.let { InfoRow(MR.string.filesync_deleted, formatDayTime(it)) }
            InfoRow(MR.string.filesync_info_where, where.joinToString(" › "))
        }

        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(Icons.Outlined.Lock, stringResource(MR.string.filesync_info_encrypted))
            node.hashAlgorithm?.let { Fact(Icons.Outlined.VerifiedUser, stringResource(MR.string.filesync_info_checked, it.displayName)) }
        }
    }
}

private val PayloadHashAlgorithm.displayName: String
    get() = when (this) {
        PayloadHashAlgorithm.Blake3 -> "BLAKE3"
        PayloadHashAlgorithm.Sha256 -> "SHA-256"
    }

@Composable
private fun InfoRow(label: StringResource, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FilesyncNodeMenu(
    node: FilesyncNode,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (FilesyncUiAction) -> Unit,
    // Phones open on a tap and show Info here; wide layouts select on a click, so the pane is the info and Open goes here.
    onInfo: (() -> Unit)?,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        if (onInfo == null && node.removedMs == null) MenuItem(MR.string.filesync_open, onDismiss) { onAction(FilesyncUiAction.NodeClicked(node)) }
        onInfo?.let { MenuItem(MR.string.info, onDismiss, it) }
        if (node.removedMs != null) {
            MenuItem(MR.string.filesync_restore, onDismiss) { onAction(FilesyncUiAction.Restore(node.nodeId)) }
        } else if (!node.isFolder) {
            MenuItem(MR.string.save, onDismiss) { onAction(FilesyncUiAction.SaveClicked(node)) }
            // Elsewhere the platform's share is a save, which Save already is.
            if (isMobile()) MenuItem(MR.string.share, onDismiss) { onAction(FilesyncUiAction.ShareClicked(node)) }
            MenuItem(MR.string.filesync_send_webdrop, onDismiss) { onAction(FilesyncUiAction.WebDropClicked(node)) }
        }
    }
}

@Composable
private fun MenuItem(label: StringResource, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            action()
        },
    )
}
