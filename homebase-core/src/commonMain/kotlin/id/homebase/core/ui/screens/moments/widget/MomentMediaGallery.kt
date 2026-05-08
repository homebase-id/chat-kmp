package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlin.uuid.Uuid

private val GALLERY_CELL_SPACING = 2.dp

/**
 * Moments-specific clone of `id.homebase.chat.widget.MediaGallery`.

 * Sizing is aspect-ratio driven so the gallery always fills the parent
 * container's width — unlike the chat version, which clamps to a fixed
 * chat-bubble width and height. Per-count layouts:
 *
 *  - **1**: full-width cell whose aspect ratio matches the payload's preview
 *    thumbnail (falls back to 1:1 when no thumbnail metadata is available).
 *  - **2**: row of two square cells (overall 2:1 wide rectangle).
 *  - **3**: row of two squares on top, one full-width 2:1 cell below
 *    (overall ~1:1 square).
 *  - **4+**: 2×2 grid of squares (overall ~1:1 square). When `payloads.size`
 *    is greater than 4, the bottom-right cell carries a `+N` overlay.
 *
 * Default `shape` is [RectangleShape] — the parent (e.g. moment post card) is
 * expected to clip its own outer rounded corners. Pass a [Shape] explicitly
 * if the gallery is the only clipper.
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
    shape: Shape = RectangleShape,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean = false,
) {
    if (payloads.isEmpty()) return

    Box(modifier = modifier.fillMaxWidth().clip(shape)) {
        when (payloads.size) {
            1 -> SingleImageLayout(
                payload = payloads[0],
                fileId = fileId,
                driveId = driveId,
                keyHeader = keyHeader,
                previewThumbnail = previewThumbnail
                    ?: payloads[0].previewThumbnail?.toEmbeddedThumb(),
                onMediaClick = onMediaClick,
                onMediaLongPress = onMediaLongPress,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                messageId = messageId,
                downloadingFiles = downloadingFiles,
                isUploading = isUploading,
            )

            2 -> TwoImageLayout(
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

            3 -> ThreeImageLayout(
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

            else -> FourPlusImageLayout(
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
private fun SingleImageLayout(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb?,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
) {
    // Compute aspect from the payload's thumbnail metadata so the cell sizes
    // before the (possibly remote, encrypted) full image is decoded. Falls
    // back to 1:1 when no thumbnail data is present.
    val aspect = aspectRatioFor(payload) ?: 1f

    MomentMediaItem(
        payload = payload,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        previewThumbnail = previewThumbnail,
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        imageSize = ImageSize.THUMB_LARGE,
        // Aspect set on the modifier — let the image fill it (Crop is a no-op
        // when source aspect matches the box).
        preserveAspectRatio = false,
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
    ) {
        payloads.take(2).forEach { payload ->
            SquareCell(
                payload = payload,
                fileId = fileId,
                driveId = driveId,
                keyHeader = keyHeader,
                modifier = Modifier.weight(1f),
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            payloads.take(2).forEach { payload ->
                SquareCell(
                    payload = payload,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    modifier = Modifier.weight(1f),
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

        // Bottom row: full-width 2:1 cell so its height matches the top row's
        // half-width squares — total gallery is approximately 1:1.
        MomentMediaItem(
            payload = payloads[2],
            fileId = fileId,
            driveId = driveId,
            keyHeader = keyHeader,
            previewThumbnail = payloads[2].previewThumbnail?.toEmbeddedThumb(),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f),
            imageSize = ImageSize.THUMB_MEDIUM,
            preserveAspectRatio = false,
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            payloads.take(2).forEach { payload ->
                SquareCell(
                    payload = payload,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    modifier = Modifier.weight(1f),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_CELL_SPACING),
        ) {
            SquareCell(
                payload = payloads[2],
                fileId = fileId,
                driveId = driveId,
                keyHeader = keyHeader,
                modifier = Modifier.weight(1f),
                onMediaClick = onMediaClick,
                onMediaLongPress = onMediaLongPress,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                messageId = messageId,
                downloadingFiles = downloadingFiles,
                isUploading = isUploading,
            )

            Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                MomentMediaItem(
                    payload = payloads[3],
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    previewThumbnail = payloads[3].previewThumbnail?.toEmbeddedThumb(),
                    modifier = Modifier.fillMaxSize(),
                    imageSize = ImageSize.THUMB_MEDIUM,
                    preserveAspectRatio = false,
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+$remainingCount",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SquareCell(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    modifier: Modifier = Modifier,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
) {
    MomentMediaItem(
        payload = payload,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
        modifier = modifier.aspectRatio(1f),
        imageSize = ImageSize.THUMB_MEDIUM,
        // Default crop is correct: square cell, image cropped to fill.
        preserveAspectRatio = false,
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

/**
 * Best-effort aspect ratio (`width / height`) from a payload's thumbnail
 * metadata. Returns `null` when no thumbnail with sane dimensions is
 * available — caller decides the fallback.
 */
private fun aspectRatioFor(payload: PayloadDescriptor): Float? {
    val thumb = payload.previewThumbnail ?: payload.thumbnails?.lastOrNull()
    val w = thumb?.pixelWidth
    val h = thumb?.pixelHeight
    if (w == null || h == null || w <= 0 || h <= 0) return null
    return w.toFloat() / h.toFloat()
}
