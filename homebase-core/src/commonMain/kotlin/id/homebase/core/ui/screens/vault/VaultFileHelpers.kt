package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.core.util.formatFileSize
import id.homebase.core.util.formatShortDate
import id.homebase.resources.MR
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_more_options
import id.homebase.resources.vault_rename_action
import id.homebase.resources.vault_share
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Returns the appropriate icon for a given MIME content type.
 */
internal fun fileTypeIcon(contentType: String): ImageVector = when {
    contentType.startsWith("image/") -> Icons.Outlined.Image
    contentType.startsWith("video/") -> Icons.Outlined.VideoFile
    contentType.startsWith("audio/") -> Icons.Outlined.AudioFile
    contentType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/**
 * Formats a file's size and creation date into a short display string.
 */
internal fun formatFileInfo(sizeBytes: Long, createdAt: Long): String {
    val size = sizeBytes.formatFileSize()
    val date = formatShortDate(Instant.fromEpochMilliseconds(createdAt))
    return "$size · $date"
}

/**
 * Three-dot dropdown menu for vault file actions (rename, share, delete).
 */
@Composable
internal fun VaultFileDropdownMenu(
    file: VaultFileItem,
    onRename: (VaultFileItem) -> Unit,
    onShare: (VaultFileItem) -> Unit,
    onDelete: (VaultFileItem) -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(MR.string.vault_more_options),
                tint = iconTint,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.vault_rename_action)) },
                onClick = {
                    expanded = false
                    onRename(file)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.vault_share)) },
                onClick = {
                    expanded = false
                    onShare(file)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(MR.string.vault_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete(file)
                },
            )
        }
    }
}
