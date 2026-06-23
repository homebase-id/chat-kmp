package id.homebase.core.ui.screens.vault.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
import id.homebase.core.util.formatFileSize
import id.homebase.core.util.formatShortDate
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_gallery_delete_all
import id.homebase.resources.vault_gallery_delete_file
import id.homebase.resources.vault_more_options
import id.homebase.resources.vault_move_to_section
import id.homebase.resources.vault_move_to_section_target
import id.homebase.resources.vault_rename_action
import id.homebase.resources.vault_share
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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
@OptIn(ExperimentalUuidApi::class)
@Composable
fun VaultFileDropdownMenu(
    file: VaultEntry,
    onShare: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit,
    onDeletePage: (() -> Unit)? = null,
    sections: List<VaultSection> = emptyList(),
    onMoveToSection: ((Uuid) -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember { mutableStateOf(false) }
    var moveExpanded by remember { mutableStateOf(false) }
    // Sections other than the one this file lives in — the valid move targets.
    val otherSections = remember(sections, file.groupId) {
        sections.filter { it.sectionId != file.groupId }
    }

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
            if (onMoveToSection != null && otherSections.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.vault_move_to_section)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        moveExpanded = true
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

    // Section picker dialog, shown after "Move to section…" is chosen. A simple
    // tap-to-move list (M3 basic dialog) — sections are few, so a dialog is lighter
    // and more focused than a nested menu or a bottom sheet.
    if (moveExpanded) {
        AlertDialog(
            onDismissRequest = { moveExpanded = false },
            title = { Text(stringResource(MR.string.vault_move_to_section)) },
            text = {
                Column {
                    otherSections.forEach { section ->
                        val itemLabel = stringResource(
                            MR.string.vault_move_to_section_target,
                            section.title,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    moveExpanded = false
                                    onMoveToSection?.invoke(section.sectionId)
                                }
                                .semantics { contentDescription = itemLabel }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveExpanded = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }
}
