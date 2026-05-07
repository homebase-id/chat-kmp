package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import kotlin.uuid.Uuid

private val GALLERY_CELL_SPACING = 2.dp

/**
 * Moments-specific clone of `id.homebase.chat.widget.MediaGallery`.
 *
 * Displays a grid of media items (1, 2, 3, or 4+) using [MomentMediaItem] for each cell.
 * Forked from the chat gallery so it can diverge to fit Moments' UX.
 */
@Composable
fun MomentMediaGallery(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    keyHeader: KeyHeader,
    modifier: Modifier = Modifier,
    onMediaClick: ((PayloadDescriptor) -> Unit)? = null,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)? = null,
    shape: Shape =
        RoundedCornerShape(
            topStart = Dimens.Message.cornerRadius,
            topEnd = Dimens.Message.cornerRadius
        ),
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean = false,
) {
    if (payloads.isEmpty()) return

    Box(modifier = modifier.width(Dimens.Album.totalWidth).clip(shape)) {
        when (payloads.size) {
            1 -> {
                MomentMediaItem(
                    payload = payloads[0],
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    previewThumbnail = previewThumbnail
                        ?: payloads[0].previewThumbnail?.toEmbeddedThumb(),
                    modifier = Modifier.fillMaxWidth().height(Dimens.MediaBubble.maxHeight),
                    imageSize = ImageSize.THUMB_LARGE,
                    onClick = { onMediaClick?.invoke(payloads[0]) },
                    onLongPress = { offset -> onMediaLongPress?.invoke(payloads[0], offset) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payloads[0].key}"),
                    messageId = messageId,
                    isUploading = isUploading,
                )
            }

            2 ->
                TwoImageLayout(
                    payloads = payloads,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    onMediaClick = onMediaClick,
                    onMediaLongPress = onMediaLongPress,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    messageId = messageId,
                    downloadingFiles = downloadingFiles,
                    isUploading = isUploading,
                )

            3 ->
                ThreeImageLayout(
                    payloads = payloads,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    onMediaClick = onMediaClick,
                    onMediaLongPress = onMediaLongPress,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    messageId = messageId,
                    downloadingFiles = downloadingFiles,
                    isUploading = isUploading,
                )

            else ->
                FourPlusImageLayout(
                    payloads = payloads,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    onMediaClick = onMediaClick,
                    onMediaLongPress = onMediaLongPress,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    messageId = messageId,
                    downloadingFiles = downloadingFiles,
                    isUploading = isUploading,
                )
        }
    }
}

@Composable
private fun TwoImageLayout(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.Album.twoTotalHeight),
        horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        payloads.take(2).forEach { payload ->
            MomentMediaItem(
                payload = payload,
                fileId = fileId,
                driveId = driveId,
                keyHeader = keyHeader,
                previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                modifier = Modifier.weight(1f).fillMaxSize(),
                imageSize = ImageSize.THUMB_SMALL,
                shape = RectangleShape,
                onClick = { onMediaClick?.invoke(payload) },
                onLongPress = { offset -> onMediaLongPress?.invoke(payload, offset) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
                messageId = messageId,
                isUploading = isUploading,
            )
        }
    }
}

@Composable
private fun ThreeImageLayout(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(Dimens.Album.threeCellSizeSmall),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            payloads.take(2).forEach { payload ->
                MomentMediaItem(
                    payload = payload,
                    fileId = fileId,
                    driveId = driveId,
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                    keyHeader = keyHeader,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    imageSize = ImageSize.THUMB_SMALL,
                    shape = RectangleShape,
                    onClick = { onMediaClick?.invoke(payload) },
                    onLongPress = { offset -> onMediaLongPress?.invoke(payload, offset) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
                    messageId = messageId,
                    isUploading = isUploading,
                )
            }
        }

        MomentMediaItem(
            payload = payloads[2],
            fileId = fileId,
            driveId = driveId,
            keyHeader = keyHeader,
            previewThumbnail = payloads[2].previewThumbnail?.toEmbeddedThumb(),
            modifier = Modifier.fillMaxWidth().height(Dimens.Album.threeCellSizeSmall),
            imageSize = ImageSize.THUMB_MEDIUM,
            shape = RectangleShape,
            onClick = { onMediaClick?.invoke(payloads[2]) },
            onLongPress = { offset -> onMediaLongPress?.invoke(payloads[2], offset) },
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            isDownloading = downloadingFiles.contains("${messageId}_${payloads[2].key}"),
            messageId = messageId,
            isUploading = isUploading,
        )
    }
}

@Composable
private fun FourPlusImageLayout(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
) {
    val remainingCount = payloads.size - 4

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(Dimens.Album.fourCellSize),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            payloads.take(2).forEach { payload ->
                MomentMediaItem(
                    payload = payload,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    imageSize = ImageSize.THUMB_SMALL,
                    shape = RectangleShape,
                    onClick = { onMediaClick?.invoke(payload) },
                    onLongPress = { offset -> onMediaLongPress?.invoke(payload, offset) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
                    messageId = messageId,
                    isUploading = isUploading,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(Dimens.Album.fourCellSize),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            MomentMediaItem(
                payload = payloads[2],
                fileId = fileId,
                driveId = driveId,
                keyHeader = keyHeader,
                previewThumbnail = payloads[2].previewThumbnail?.toEmbeddedThumb(),
                modifier = Modifier.weight(1f).fillMaxSize(),
                imageSize = ImageSize.THUMB_SMALL,
                shape = RectangleShape,
                onClick = { onMediaClick?.invoke(payloads[2]) },
                onLongPress = { offset -> onMediaLongPress?.invoke(payloads[2], offset) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                isDownloading = downloadingFiles.contains("${messageId}_${payloads[2].key}"),
                messageId = messageId,
                isUploading = isUploading,
            )

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                MomentMediaItem(
                    payload = payloads[3],
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    previewThumbnail = payloads[3].previewThumbnail?.toEmbeddedThumb(),
                    modifier = Modifier.fillMaxSize(),
                    imageSize = ImageSize.THUMB_SMALL,
                    shape = RectangleShape,
                    onClick = { onMediaClick?.invoke(payloads[3]) },
                    onLongPress = { offset -> onMediaLongPress?.invoke(payloads[3], offset) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payloads[3].key}"),
                    messageId = messageId,
                    isUploading = isUploading,
                )

                if (remainingCount > 0) {
                    val remainingCountLabel = "+$remainingCount"
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = remainingCountLabel,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
