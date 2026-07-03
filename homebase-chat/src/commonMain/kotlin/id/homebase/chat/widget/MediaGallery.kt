package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import kotlin.uuid.Uuid

/** Spacing between gallery cells. */
private val GALLERY_CELL_SPACING = 2.dp

/**
 * Composable that displays a grid of media items (2-4+).
 *
 * Layouts:
 * - 2 images: Side by side (equal width)
 * - 3 images: 2 on top, 1 full width below
 * - 4+ images: 2×2 grid with "+N" overlay on 4th showing remaining count
 *
 * @param payloads List of payload descriptors to display
 * @param fileId The file ID on the Homebase drive
 * @param driveId The drive ID where the file is stored
 * @param previewThumbnail Optional embedded preview thumbnail
 * @param modifier Modifier for the container
 * @param onMediaClick Callback when a media item is clicked
 * @param onMediaLongPress Callback when a media item is long-pressed
 */
@Composable
fun MediaGallery(
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
    /**
     * When true the gallery fills the width it is given (rather than pinning to a
     * discrete album width) and derives its cell heights from that laid-out width.
     * The caption bubble path (#964) sets this so a 2/3/4-up album stretches to the
     * bubble instead of leaving a blue gap beside a wider caption. Defaults to false
     * so the media-only / reply gallery keeps its original bounded album width.
     */
    fillWidth: Boolean = false,
) {
    if (payloads.isEmpty()) return

    BoxWithConstraints(modifier = modifier.clip(shape)) {
        // Signal-style breakpoints: scale album width with available space.
        val albumWidth = when {
            maxWidth >= 400.dp -> 300.dp
            maxWidth >= 360.dp -> 252.dp
            else -> Dimens.Album.totalWidth
        }
        // Cell heights are derived from the actual laid-out width: the given width
        // when filling, else the discrete album width.
        val layoutWidth = if (fillWidth) maxWidth else albumWidth
        val widthModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(albumWidth)

        Box(modifier = widthModifier) {
            when (payloads.size) {
                1 -> {
                    MediaItem(
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
                        albumWidth = layoutWidth,
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
                        albumWidth = layoutWidth,
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
                        albumWidth = layoutWidth,
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
}

/** Layout for 2 images: side by side with equal width. */
@Composable
private fun TwoImageLayout(
    albumWidth: Dp,
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
        modifier = Modifier.fillMaxWidth().height(albumWidth / 2),
        horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        payloads.take(2).forEach { payload ->
            MediaItem(
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

/** Layout for 3 images: 2 on top side-by-side, 1 full width below. */
@Composable
private fun ThreeImageLayout(
    albumWidth: Dp,
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
    val rowHeight = (albumWidth * 2 / 3 - GALLERY_CELL_SPACING) / 2

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        // Top row: 2 images side by side
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            payloads.take(2).forEach { payload ->
                MediaItem(
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

        // Bottom row: 1 full width image
        MediaItem(
            payload = payloads[2],
            fileId = fileId,
            driveId = driveId,
            keyHeader = keyHeader,
            previewThumbnail = payloads[2].previewThumbnail?.toEmbeddedThumb(),
            modifier = Modifier.fillMaxWidth().height(rowHeight),
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

/** Layout for 4+ images: 2x2 grid with overlay on 4th showing remaining count. */
@Composable
private fun FourPlusImageLayout(
    albumWidth: Dp,
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
    val cellHeight = (albumWidth - GALLERY_CELL_SPACING) / 2

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        // Top row: 2 images
        Row(
            modifier = Modifier.fillMaxWidth().height(cellHeight),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            payloads.take(2).forEach { payload ->
                MediaItem(
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

        // Bottom row: 2 images, with overlay on 4th if more than 4
        Row(
            modifier = Modifier.fillMaxWidth().height(cellHeight),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            // Third image
            MediaItem(
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

            // Fourth image with optional overlay
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                MediaItem(
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

                // Overlay showing remaining count
                if (remainingCount > 0) {
                    val remainingCount = "+$remainingCount"
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = remainingCount,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
