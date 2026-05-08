package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
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
import id.homebase.api.video.VideoProcessingPhase
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.cd_upload_complete
import id.homebase.resources.upload_compressing
import id.homebase.resources.upload_done
import id.homebase.resources.upload_encrypting
import id.homebase.resources.upload_finalizing
import id.homebase.resources.upload_preparing
import id.homebase.resources.upload_segmenting
import id.homebase.resources.upload_sending
import id.homebase.resources.upload_uploading
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.stringResource
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

    Box(modifier = Modifier.animateContentSize()) {
        when (payloads.size) {
            1 -> {
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
                    isDownloading = downloadingFiles.contains("${messageId}_${payloads[0].key}"),
                    messageId = messageId,
                    isUploading = uploadStatus != null,
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
                    downloadingFiles = downloadingFiles,
                    isUploading = uploadStatus != null,
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
internal fun UploadProgressOverlay(status: UploadStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (status) {
                UploadStatus.Preparing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = stringResource(MR.string.upload_preparing),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                UploadStatus.Sending -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = stringResource(MR.string.upload_sending),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Processing -> {
                    if (status.progress > 0f) {
                        val progressText = "${(status.progress * 100).toInt()}%"
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                    }
                    Text(
                        text = when (status.phase) {
                            VideoProcessingPhase.THUMBNAIL -> stringResource(MR.string.upload_preparing)
                            VideoProcessingPhase.COMPRESSING -> stringResource(MR.string.upload_compressing)
                            VideoProcessingPhase.SEGMENTING -> stringResource(MR.string.upload_segmenting)
                            VideoProcessingPhase.ENCRYPTING -> stringResource(MR.string.upload_encrypting)
                            VideoProcessingPhase.COMPLETE -> stringResource(MR.string.upload_done)
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Uploading -> {
                    if (status.progress >= 1f) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Text(
                            text = stringResource(MR.string.upload_finalizing),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        val progressText = "${(status.progress * 100).toInt()}%"
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            text = stringResource(MR.string.upload_uploading),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                UploadStatus.Completed -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(MR.string.cd_upload_complete),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(MR.string.upload_done),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
