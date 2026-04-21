package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.util.formatFileSize
import id.homebase.core.util.formatShortDate
import id.homebase.resources.MR
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_rename_action
import id.homebase.resources.vault_share
import id.homebase.resources.vault_upload_failed
import id.homebase.resources.vault_upload_preparing
import id.homebase.resources.vault_more_options
import id.homebase.resources.vault_upload_retry
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

@Composable
fun VaultFileListContent(
    files: List<VaultFileItem>,
    onFileClick: (VaultFileItem) -> Unit,
    onRename: (VaultFileItem) -> Unit,
    onShare: (VaultFileItem) -> Unit,
    onDelete: (VaultFileItem) -> Unit,
    onRetry: (VaultFileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(files, key = { it.fileId }) { file ->
            VaultFileRow(
                file = file,
                onFileClick = onFileClick,
                onRename = onRename,
                onShare = onShare,
                onDelete = onDelete,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun VaultFileRow(
    file: VaultFileItem,
    onFileClick: (VaultFileItem) -> Unit,
    onRename: (VaultFileItem) -> Unit,
    onShare: (VaultFileItem) -> Unit,
    onDelete: (VaultFileItem) -> Unit,
    onRetry: (VaultFileItem) -> Unit,
) {
    val uploadStatus = file.uploadStatus

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !file.isPending) { onFileClick(file) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            FileThumbnail(file = file)

            Spacer(modifier = Modifier.width(12.dp))

            // Name + subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                val subtitle = when {
                    uploadStatus is VaultUploadStatus.Preparing ->
                        stringResource(MR.string.vault_upload_preparing)

                    uploadStatus is VaultUploadStatus.Uploading ->
                        "${(uploadStatus.progress * 100).toInt()}%"

                    uploadStatus is VaultUploadStatus.Failed ->
                        stringResource(MR.string.vault_upload_failed)

                    else -> formatFileInfo(file.sizeBytes, file.createdAt)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uploadStatus is VaultUploadStatus.Failed)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Trailing actions
            when {
                uploadStatus is VaultUploadStatus.Failed -> {
                    TextButton(onClick = { onRetry(file) }) {
                        Text(
                            text = stringResource(MR.string.vault_upload_retry),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                !file.isPending -> {
                    VaultFileDropdownMenu(
                        file = file,
                        onRename = onRename,
                        onShare = onShare,
                        onDelete = onDelete,
                    )
                }
            }
        }

        // Progress bar for uploading items
        if (uploadStatus is VaultUploadStatus.Uploading) {
            LinearProgressIndicator(
                progress = { uploadStatus.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun FileThumbnail(file: VaultFileItem) {
    val thumbnailShape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(thumbnailShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file.isImage && file.isPending && file.pendingFileUri != null) {
            HomebaseImage(
                imageData = HomebaseImageData.pending(fileUri = file.pendingFileUri),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                contentDescription = file.fileName,
            )
        } else if (file.isImage && !file.isPending) {
            HomebaseImage(
                imageData = HomebaseImageData(
                    driveId = file.driveId,
                    fileId = file.fileId,
                    payloadKey = file.payloadKey,
                    previewThumbnail = file.previewThumbnail,
                    requestedSize = ImageSize.THUMB_SMALL,
                    isEncrypted = file.isEncrypted,
                    keyHeader = file.payloadKeyHeader,
                ),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                contentDescription = file.fileName,
            )
        } else {
            Icon(
                imageVector = fileTypeIcon(file.contentType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        // Upload status overlay
        when (val status = file.uploadStatus) {
            is VaultUploadStatus.Preparing -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            thumbnailShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            is VaultUploadStatus.Uploading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            thumbnailShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${(status.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            is VaultUploadStatus.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                            thumbnailShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = stringResource(MR.string.vault_upload_failed),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            else -> Unit
        }
    }
}

@Composable
internal fun VaultFileDropdownMenu(
    file: VaultFileItem,
    onRename: (VaultFileItem) -> Unit,
    onShare: (VaultFileItem) -> Unit,
    onDelete: (VaultFileItem) -> Unit,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
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

internal fun fileTypeIcon(contentType: String): ImageVector = when {
    contentType.startsWith("image/") -> Icons.Outlined.Image
    contentType.startsWith("video/") -> Icons.Outlined.VideoFile
    contentType.startsWith("audio/") -> Icons.Outlined.AudioFile
    contentType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

internal fun formatFileInfo(sizeBytes: Long, createdAt: Long): String {
    val size = sizeBytes.formatFileSize()
    val date = formatShortDate(Instant.fromEpochMilliseconds(createdAt))
    return "$size · $date"
}
