package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableMap
import kotlin.uuid.Uuid

/**
 * Media message component that decides between single item or gallery view.
 *
 * - Single payload: Renders MediaItem with max 50% width, preserving aspect ratio
 * - Multiple payloads: Renders MediaGallery with fixed grid dimensions
 *
 * @param payloads List of payload descriptors to display
 * @param fileId The file ID on the Homebase drive
 * @param driveId The drive ID where the file is stored
 * @param previewThumbnail Optional embedded preview thumbnail
 * @param modifier Modifier for the container
 * @param onMediaClick Callback when a media item is clicked
 * @param onMediaLongPress Callback when a media item is long-pressed
 * @param uploadStatus Current upload status to show progress overlay, or null when not uploading
 */
@Composable
fun MediaMessage(
    payloads: List<PayloadDescriptor>,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    keyHeader: KeyHeader,
    preserveAspectRatio: Boolean = true,
    modifier: Modifier = Modifier,
    onMediaClick: ((PayloadDescriptor) -> Unit)? = null,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)? = null,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    shape: Shape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius
    ),
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    uploadStatus: UploadStatus? = null,
) {
    if (payloads.isEmpty()) return

    Box {
        when (payloads.size) {
            1 -> {
                // Single media item - constrain to max 50% width (~210dp), preserve aspect
                // ratio
                val widthModifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                MediaItem(
                    payload = payloads[0],
                    fileId = fileId,
                    driveId = driveId,
                    previewThumbnail = previewThumbnail
                        ?: payloads[0].previewThumbnail?.toEmbeddedThumb(),
                    decryptedFiles = decryptedFiles,
                    keyHeader = keyHeader,
                    modifier = widthModifier.heightIn(
                        min = Dimens.MediaBubble.minHeight,
                        max = Dimens.MediaBubble.maxHeight
                    ),
                    imageSize = ImageSize.THUMB_MEDIUM,
                    preserveAspectRatio = preserveAspectRatio,
                    onClick = { onMediaClick?.invoke(payloads[0]) },
                    onLongPress = { offset -> onMediaLongPress?.invoke(payloads[0], offset) },
                    onRequestDecryptedFile = if (onRequestDecryptedFile != null) {
                        { onRequestDecryptedFile(payloads[0]) }
                    } else {
                        null
                    },
                    shape = shape,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payloads[0].key}")
                )
            }

            else -> {
                // Multiple media items - show gallery with fixed dimensions
                MediaGallery(
                    payloads = payloads,
                    fileId = fileId,
                    driveId = driveId,
                    previewThumbnail = previewThumbnail,
                    keyHeader = keyHeader,
                    modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    onMediaClick = onMediaClick,
                    onMediaLongPress = onMediaLongPress,
                    shape = shape,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    messageId = messageId,
                    downloadingFiles = downloadingFiles
                )
            }
        }

        if (uploadStatus != null) {
            UploadProgressOverlay(
                status = uploadStatus,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun UploadProgressOverlay(status: UploadStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (status) {
                is UploadStatus.Processing -> {
                    CircularProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                    Text(
                        text = "${(status.progress * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "Processing…",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                is UploadStatus.Uploading -> {
                    if (status.progress >= 1f) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Text(
                            text = "Finalizing…",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "Uploading…",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                UploadStatus.Completed -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = "Done",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
