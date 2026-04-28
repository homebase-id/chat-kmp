package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize

private val CARD_WIDTH = 100.dp
private val CARD_HEIGHT = 120.dp
private val THUMBNAIL_HEIGHT = 88.dp
private val LABEL_HEIGHT = 32.dp
private val CARD_CORNER = 12.dp

@Composable
fun VaultEntryCard(
    file: VaultFileItem,
    sectionTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(CARD_CORNER)
    val topCornersShape = RoundedCornerShape(topStart = CARD_CORNER, topEnd = CARD_CORNER)
    val description = "${file.fileName}, $sectionTitle"

    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(
                onClickLabel = description,
                onClick = onClick,
            ),
    ) {
        // Thumbnail area — top 88dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(THUMBNAIL_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            // Stacked shadow layers (only for multi-page)
            if (file.hasMultiplePages) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 6.dp, end = 2.dp, top = 4.dp)
                        .graphicsLayer { rotationZ = -2f }
                        .clip(topCornersShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 2.dp, top = 2.dp)
                        .graphicsLayer { rotationZ = -1f }
                        .clip(topCornersShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                )
            }

            // Main thumbnail
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                        imageVector = vaultEntryFileTypeIcon(file.contentType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // Upload status overlay
                when (val status = file.uploadStatus) {
                    is VaultUploadStatus.Preparing -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    is VaultUploadStatus.Uploading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    else -> Unit
                }

                // Page count badge (only for multi-page)
                if (file.hasMultiplePages) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${file.pageCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }

        // Label area — bottom 32dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LABEL_HEIGHT)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun vaultEntryFileTypeIcon(contentType: String): ImageVector = when {
    contentType.startsWith("image/") -> Icons.Outlined.Image
    contentType.startsWith("video/") -> Icons.Outlined.VideoFile
    contentType.startsWith("audio/") -> Icons.Outlined.AudioFile
    contentType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
