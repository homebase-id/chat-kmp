package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.util.formatFileSize
import id.homebase.resources.MR
import id.homebase.resources.vault_upload_failed
import id.homebase.resources.vault_upload_preparing
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultFileGridContent(
    files: List<VaultFileItem>,
    onFileClick: (VaultFileItem) -> Unit,
    onRename: (VaultFileItem) -> Unit,
    onShare: (VaultFileItem) -> Unit,
    onDelete: (VaultFileItem) -> Unit,
    onRetry: (VaultFileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files, key = { it.fileId }) { file ->
            VaultFileCard(
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
private fun VaultFileCard(
    file: VaultFileItem,
    onFileClick: (VaultFileItem) -> Unit,
    onRename: (VaultFileItem) -> Unit,
    onShare: (VaultFileItem) -> Unit,
    onDelete: (VaultFileItem) -> Unit,
    onRetry: (VaultFileItem) -> Unit,
) {
    val uploadStatus = file.uploadStatus
    val cardShape = RoundedCornerShape(12.dp)
    val topCornersShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

    val borderModifier = when {
        uploadStatus is VaultUploadStatus.Uploading ->
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, cardShape)

        uploadStatus is VaultUploadStatus.Failed ->
            Modifier.border(2.dp, MaterialTheme.colorScheme.error, cardShape)

        else -> Modifier
    }

    Column(
        modifier = Modifier
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, cardShape)
            .then(borderModifier)
            .clickable(enabled = !file.isPending) { onFileClick(file) },
    ) {
        // Thumbnail area — 4:3 aspect ratio, clipped to top corners only
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(topCornersShape)
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
                        requestedSize = ImageSize.THUMB_MEDIUM,
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
                    modifier = Modifier.size(48.dp),
                )
            }

            // Upload overlays
            when (val status = uploadStatus) {
                is VaultUploadStatus.Preparing -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }

                is VaultUploadStatus.Uploading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(
                                text = "${(status.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        )
                    }
                }

                is VaultUploadStatus.Failed -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = stringResource(MR.string.vault_upload_failed),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                else -> Unit
            }
        }

        // Card body
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitleText = when {
                    uploadStatus is VaultUploadStatus.Preparing ->
                        stringResource(MR.string.vault_upload_preparing)

                    uploadStatus is VaultUploadStatus.Uploading ->
                        "${(uploadStatus.progress * 100).toInt()}%"

                    uploadStatus is VaultUploadStatus.Failed ->
                        stringResource(MR.string.vault_upload_failed)

                    else -> file.sizeBytes.formatFileSize()
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uploadStatus is VaultUploadStatus.Failed)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!file.isPending) {
                VaultFileDropdownMenu(
                    file = file,
                    onRename = onRename,
                    onShare = onShare,
                    onDelete = onDelete,
                )
            }
        }
    }
}
