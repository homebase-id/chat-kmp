package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableMap
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Renders a sticker message exactly like an emoji-only message: a bare image floating
 * directly on the chat wallpaper, with NO bubble surface, NO background fill, NO media
 * chrome, and NO timestamp scrim.
 *
 * This is the sticker analogue of the emoji-only branch in [MessageBubbleRaw]. The
 * single transparent image is loaded through the SAME encrypted-image path
 * [MediaMessage]/[MediaItem] use (so the existing sticker-aware [MediaItem] already
 * drops the rounded clip and the opaque letterbox fill), but it is constrained to
 * [Dimens.Sticker.maxSize] with [androidx.compose.ui.layout.ContentScale.Fit].
 *
 * The timestamp + delivery status are tucked under the bottom-end corner of the image
 * via the same custom [Layout] idiom the emoji-only bubble uses for its `infoPlaceable`
 * — a subtle `labelSmall` row drawn with `contentColor.copy(alpha = 0.7f)`. There is no
 * [androidx.compose.foundation.background] anywhere in this component; in particular it
 * deliberately does NOT use `MediaTimestampOverlay`, which paints a `Color.Black` 0.6
 * gradient scrim over the bottom of the media.
 *
 * @param payloads The message payloads; the solo sticker image payload is rendered.
 * @param messageInfoText Preformatted timestamp (optionally prefixed with "edited").
 */
@Composable
fun StickerMessage(
    payloads: List<PayloadDescriptor>,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    keyHeader: KeyHeader,
    driveId: Uuid,
    fileId: Uuid,
    messageId: Uuid,
    previewThumbnail: EmbeddedThumb?,
    messageInfoText: String,
    sentByYou: Boolean,
    isPendingSend: Boolean,
    deliveryStatus: Int,
    contentColor: Color,
    pendingSince: Instant?,
    onMediaClick: (PayloadDescriptor) -> Unit,
    onMediaLongPress: () -> Unit,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    downloadingFiles: Set<String>,
    uploadStatus: UploadStatus? = null,
    showTimestamp: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (payloads.isEmpty()) return
    val payload = payloads[0]

    Layout(
        modifier = modifier,
        content = {
            // The bare sticker image. isSticker = true makes MediaItem drop the rounded
            // clip; we pass no background-bearing modifier, so transparent pixels show the
            // chat wallpaper through. ContentScale.Fit + a max-size sizeIn keeps it within
            // the sticker budget while preserving aspect ratio.
            MediaItem(
                payload = payload,
                fileId = fileId,
                driveId = driveId,
                previewThumbnail = previewThumbnail
                    ?: payload.previewThumbnail?.toEmbeddedThumb(),
                decryptedFiles = decryptedFiles,
                keyHeader = keyHeader,
                modifier = Modifier.sizeIn(maxWidth = Dimens.Sticker.maxSize, maxHeight = Dimens.Sticker.maxSize),
                imageSize = ImageSize.THUMB_MEDIUM,
                preserveAspectRatio = true,
                isSticker = true,
                onClick = { onMediaClick(payload) },
                onLongPress = { onMediaLongPress() },
                onRequestDecryptedFile = if (onRequestDecryptedFile != null) {
                    { onRequestDecryptedFile(payload) }
                } else {
                    null
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
                messageId = messageId,
                isUploading = uploadStatus != null,
            )

            // Subtle timestamp + delivery status — identical styling to the emoji-only
            // footer (labelSmall, contentColor @ 0.7 alpha), no scrim, no background.
            // #814: keep this Row child (measurables[1]) but hide its contents on
            // non-terminal cluster bubbles; the gap below collapses when it's empty.
            Row(verticalAlignment = Alignment.Bottom) {
                if (showTimestamp) {
                    Text(
                        text = messageInfoText,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                    if (sentByYou) {
                        Spacer(modifier = Modifier.width(4.dp))
                        DeliveryStatus(
                            isPendingSend = isPendingSend,
                            deliveryStatus = deliveryStatus,
                            contentColor = contentColor.copy(alpha = 0.7f),
                            pendingSince = pendingSince,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val imagePlaceable = measurables[0].measure(constraints)
        // The info row is never wider than the sticker; clamp so it can't expand the bubble.
        val infoPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = imagePlaceable.width)
        )

        val gap = if (infoPlaceable.height > 0) 4.dp.roundToPx() else 0
        val width = imagePlaceable.width
        val height = imagePlaceable.height + gap + infoPlaceable.height

        layout(width, height) {
            imagePlaceable.placeRelative(0, 0)
            // Tuck the timestamp under the bottom-end corner of the sticker.
            val infoX = (width - infoPlaceable.width).coerceAtLeast(0)
            infoPlaceable.placeRelative(infoX, imagePlaceable.height + gap)
        }
    }
}
