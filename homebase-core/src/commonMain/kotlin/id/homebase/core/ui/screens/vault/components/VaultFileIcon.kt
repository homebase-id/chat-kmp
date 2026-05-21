package id.homebase.core.ui.screens.vault.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
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
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
import id.homebase.core.util.formatFileSize
import id.homebase.core.util.formatShortDate
import id.homebase.resources.MR
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_gallery_delete_all
import id.homebase.resources.vault_gallery_delete_file
import id.homebase.resources.vault_more_options
import id.homebase.resources.vault_rename_action
import id.homebase.resources.vault_share
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Returns the appropriate icon for a given MIME content type.
 */
fun fileTypeIcon(contentType: String): ImageVector = when {
    contentType.startsWith("image/") -> Icons.Outlined.Image
    contentType.startsWith("video/") -> Icons.Outlined.VideoFile
    contentType.startsWith("audio/") -> Icons.Outlined.AudioFile
    contentType == "application/pdf" -> Icons.Outlined.PictureAsPdf

    contentType == "application/json" ||
        contentType == "application/xml" ||
        contentType == "application/javascript" ||
        contentType == "application/x-sh" ||
        contentType == "application/x-yaml" ||
        contentType.startsWith("text/x-") -> Icons.Outlined.Code

    contentType == "text/csv" ||
        contentType == "application/vnd.ms-excel" ||
        contentType.contains("spreadsheetml") -> Icons.Outlined.TableChart

    contentType == "application/vnd.ms-powerpoint" ||
        contentType.contains("presentationml") -> Icons.Outlined.Slideshow

    contentType == "application/msword" ||
        contentType.contains("wordprocessingml") ||
        contentType == "application/vnd.oasis.opendocument.text" ||
        contentType == "application/rtf" -> Icons.AutoMirrored.Outlined.Article

    contentType == "application/zip" ||
        contentType == "application/x-tar" ||
        contentType == "application/gzip" ||
        contentType == "application/x-rar-compressed" ||
        contentType == "application/x-7z-compressed" -> Icons.Outlined.FolderZip

    contentType == CONTENT_TYPE_MARKDOWN -> Icons.AutoMirrored.Outlined.NoteAdd

    contentType.startsWith("text/") -> Icons.Outlined.Description

    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/**
 * Formats a file's size and creation date into a short display string.
 */
fun formatFileInfo(sizeBytes: Long, createdAt: Long): String {
    val size = sizeBytes.formatFileSize()
    val date = formatShortDate(Instant.fromEpochMilliseconds(createdAt))
    return "$size · $date"
}

/**
 * Three-dot dropdown menu for vault file actions (share, delete).
 */
@Composable
fun VaultFileDropdownMenu(
    file: VaultEntry,
    onShare: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit,
    onDeletePage: (() -> Unit)? = null,
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
            if (onDeletePage == null) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.vault_share)) },
                    onClick = {
                        expanded = false
                        onShare(file)
                    },
                )
            }
            if (onDeletePage != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(MR.string.vault_gallery_delete_file),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        expanded = false
                        onDeletePage()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (onDeletePage != null) {
                            stringResource(MR.string.vault_gallery_delete_all)
                        } else {
                            stringResource(MR.string.vault_delete_confirm_action)
                        },
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
