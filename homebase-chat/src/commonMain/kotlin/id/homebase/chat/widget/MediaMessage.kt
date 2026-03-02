package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
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
 */
@Composable
fun MediaMessage(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    keyHeader: KeyHeader,
    preserveAspectRatio: Boolean = true,
    modifier: Modifier = Modifier,
    onMediaClick: ((PayloadDescriptor) -> Unit)? = null,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)? = null,
    shape: Shape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius
    ),
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    if (payloads.isEmpty()) return

    when (payloads.size) {
        1 -> {
            // Single media item - constrain to max 50% width (~210dp), preserve aspect
            // ratio
            val widthModifier = modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            MediaItem(
                payload = payloads[0],
                fileId = fileId,
                driveId = driveId,
                previewThumbnail = previewThumbnail
                    ?: payloads[0].previewThumbnail?.toEmbeddedThumb(),
                keyHeader = keyHeader,

                modifier =
                    widthModifier.heightIn(
                        min = Dimens.MediaBubble.minHeight,
                        max = Dimens.MediaBubble.maxHeight
                    ),
                imageSize = ImageSize.THUMB_MEDIUM,
                preserveAspectRatio = preserveAspectRatio,
                onClick = { onMediaClick?.invoke(payloads[0]) },
                onLongPress = { offset ->
                    onMediaLongPress?.invoke(payloads[0], offset)
                },
                shape = shape,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
            )
        }
    }
}
