package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
) {
        if (payloads.isEmpty()) return

        val cornerShape = RoundedCornerShape(Dimens.Message.cornerRadius)

        Box(modifier = modifier.width(Dimens.Album.totalWidth).clip(cornerShape)) {
                when (payloads.size) {
                        1 -> {
                                // Single item - delegate to MediaItem directly
                                MediaItem(
                                        payload = payloads[0],
                                        fileId = fileId,
                                        driveId = driveId,
                                        keyHeader = keyHeader,
                                        previewThumbnail = previewThumbnail
                                                        ?: payloads[0].previewThumbnail
                                                                ?.toEmbeddedThumb(),
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .height(Dimens.MediaBubble.maxHeight),
                                        imageSize = ImageSize.THUMB_LARGE,
                                        onClick = { onMediaClick?.invoke(payloads[0]) },
                                        onLongPress = { offset ->
                                                onMediaLongPress?.invoke(payloads[0], offset)
                                        },
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
                                )
                        3 ->
                                ThreeImageLayout(
                                        payloads = payloads,
                                        fileId = fileId,
                                        driveId = driveId,
                                        keyHeader = keyHeader,
                                        onMediaClick = onMediaClick,
                                        onMediaLongPress = onMediaLongPress,
                                )
                        else ->
                                FourPlusImageLayout(
                                        payloads = payloads,
                                        fileId = fileId,
                                        driveId = driveId,
                                        keyHeader = keyHeader,
                                        onMediaClick = onMediaClick,
                                        onMediaLongPress = onMediaLongPress,
                                )
                }
        }
}

/** Layout for 2 images: side by side with equal width. */
@Composable
private fun TwoImageLayout(
        payloads: List<PayloadDescriptor>,
        fileId: Uuid,
        driveId: Uuid,
        keyHeader: KeyHeader,
        onMediaClick: ((PayloadDescriptor) -> Unit)?,
        onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
) {
        Row(
                modifier = Modifier.fillMaxWidth().height(Dimens.Album.twoTotalHeight),
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
                                onLongPress = { offset ->
                                        onMediaLongPress?.invoke(payload, offset)
                                },
                        )
                }
        }
}

/** Layout for 3 images: 2 on top side-by-side, 1 full width below. */
@Composable
private fun ThreeImageLayout(
        payloads: List<PayloadDescriptor>,
        fileId: Uuid,
        driveId: Uuid,
        keyHeader: KeyHeader,
        onMediaClick: ((PayloadDescriptor) -> Unit)?,
        onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
                // Top row: 2 images side by side
                Row(
                        modifier = Modifier.fillMaxWidth().height(Dimens.Album.threeCellSizeSmall),
                        horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
                ) {
                        payloads.take(2).forEach { payload ->
                                MediaItem(
                                        payload = payload,
                                        fileId = fileId,
                                        driveId = driveId,
                                        previewThumbnail =
                                                payload.previewThumbnail?.toEmbeddedThumb(),
                                        keyHeader = keyHeader,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        imageSize = ImageSize.THUMB_SMALL,
                                        shape = RectangleShape,
                                        onClick = { onMediaClick?.invoke(payload) },
                                        onLongPress = { offset ->
                                                onMediaLongPress?.invoke(payload, offset)
                                        },
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
                        modifier = Modifier.fillMaxWidth().height(Dimens.Album.threeCellSizeSmall),
                        imageSize = ImageSize.THUMB_MEDIUM,
                        shape = RectangleShape,
                        onClick = { onMediaClick?.invoke(payloads[2]) },
                        onLongPress = { offset -> onMediaLongPress?.invoke(payloads[2], offset) },
                )
        }
}

/** Layout for 4+ images: 2×2 grid with overlay on 4th showing remaining count. */
@Composable
private fun FourPlusImageLayout(
        payloads: List<PayloadDescriptor>,
        fileId: Uuid,
        driveId: Uuid,
        keyHeader: KeyHeader,
        onMediaClick: ((PayloadDescriptor) -> Unit)?,
        onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
) {
        val remainingCount = payloads.size - 4

        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
                // Top row: 2 images
                Row(
                        modifier = Modifier.fillMaxWidth().height(Dimens.Album.fourCellSize),
                        horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
                ) {
                        payloads.take(2).forEach { payload ->
                                MediaItem(
                                        payload = payload,
                                        fileId = fileId,
                                        driveId = driveId,
                                        keyHeader = keyHeader,
                                        previewThumbnail =
                                                payload.previewThumbnail?.toEmbeddedThumb(),
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        imageSize = ImageSize.THUMB_SMALL,
                                        shape = RectangleShape,
                                        onClick = { onMediaClick?.invoke(payload) },
                                        onLongPress = { offset ->
                                                onMediaLongPress?.invoke(payload, offset)
                                        },
                                )
                        }
                }

                // Bottom row: 2 images, with overlay on 4th if more than 4
                Row(
                        modifier = Modifier.fillMaxWidth().height(Dimens.Album.fourCellSize),
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
                                onLongPress = { offset ->
                                        onMediaLongPress?.invoke(payloads[2], offset)
                                },
                        )

                        // Fourth image with optional overlay
                        Box(
                                modifier =
                                        Modifier.weight(1f).fillMaxSize().clickable {
                                                onMediaClick?.invoke(payloads[3])
                                        },
                        ) {
                                MediaItem(
                                        payload = payloads[3],
                                        fileId = fileId,
                                        driveId = driveId,
                                        keyHeader = keyHeader,
                                        previewThumbnail =
                                                payloads[3].previewThumbnail?.toEmbeddedThumb(),
                                        modifier = Modifier.fillMaxSize(),
                                        imageSize = ImageSize.THUMB_SMALL,
                                        shape = RectangleShape,
                                        onClick = null, // Handled by parent Box
                                        onLongPress = { offset ->
                                                onMediaLongPress?.invoke(payloads[3], offset)
                                        },
                                )

                                // Overlay showing remaining count
                                if (remainingCount > 0) {
                                        Box(
                                                modifier =
                                                        Modifier.fillMaxSize()
                                                                .background(
                                                                        Color.Black.copy(
                                                                                alpha = 0.5f
                                                                        )
                                                                ),
                                                contentAlignment = Alignment.Center,
                                        ) {
                                                Text(
                                                        text = "+$remainingCount",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineMedium,
                                                        color = Color.White,
                                                )
                                        }
                                }
                        }
                }
        }
}
