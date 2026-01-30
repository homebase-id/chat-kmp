package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import kotlin.uuid.Uuid

/**
 * Composable that renders a single media item based on the payload's content type.
 *
 * Supports:
 * - Images: Full rendering via HomebaseImage
 * - Videos: Placeholder icon (TODO: implement video player)
 * - Audio: Placeholder icon (TODO: implement audio player)
 * - Files: Placeholder icon (TODO: implement file viewer)
 *
 * @param payload The payload descriptor containing media metadata
 * @param fileId The file ID on the Homebase drive
 * @param driveId The drive ID where the file is stored
 * @param previewThumbnail Optional embedded preview thumbnail for instant display
 * @param modifier Modifier for the container
 * @param imageSize Desired image size for loading
 * @param preserveAspectRatio If true, use ContentScale.Fit to maintain aspect ratio; if false, use
 * Crop
 * @param onClick Callback when media is tapped
 * @param onLongPress Callback when media is long-pressed with position
 */
@Composable
fun MediaItem(
        payload: PayloadDescriptor,
        fileId: Uuid,
        driveId: Uuid,
        previewThumbnail: EmbeddedThumb? = null,
        modifier: Modifier = Modifier,
        imageSize: ImageSize? = ImageSize.THUMB_MEDIUM,
        preserveAspectRatio: Boolean = false,
        onClick: (() -> Unit)? = null,
        onLongPress: ((Offset) -> Unit)? = null,
) {
        val contentType = payload.contentType ?: ""
        val cornerShape = RoundedCornerShape(Dimens.Message.cornerRadius)
        val imageContentScale = if (preserveAspectRatio) ContentScale.Fit else ContentScale.Crop

        when {
                contentType.startsWith("image/") -> {
                        // Render image via HomebaseImage
                        val imageData =
                                HomebaseImageData(
                                        driveId = driveId,
                                        fileId = fileId,
                                        payloadKey = payload.key,
                                        previewThumbnail = previewThumbnail,
                                        requestedSize = imageSize,
                                        lastModified = payload.lastModified,
                                        isEncrypted = true,
                                )

                        HomebaseImage(
                                imageData = imageData,
                                modifier = modifier.clip(cornerShape),
                                contentScale = imageContentScale,
                                contentDescription = "Image attachment",
                                onClick = onClick,
                                onLongPress = onLongPress,
                        )
                }
                contentType.startsWith("video/") ||
                        contentType == "application/vnd.apple.mpegurl" -> {
                        // TODO: Implement video player/thumbnail
                        MediaPlaceholder(
                                emoji = "📹",
                                label = "Video",
                                modifier = modifier.clip(cornerShape),
                        )
                }
                contentType.startsWith("audio/") -> {
                        // TODO: Implement audio player
                        MediaPlaceholder(
                                emoji = "🎵",
                                label = "Audio",
                                modifier = modifier.clip(cornerShape),
                        )
                }
                contentType.startsWith("application/") -> {
                        // TODO: Implement file viewer/downloader
                        MediaPlaceholder(
                                emoji = "📄",
                                label = "File",
                                modifier = modifier.clip(cornerShape),
                        )
                }
                else -> {
                        // Unsupported media type
                        println("Unsupported media type: $contentType")
                        MediaPlaceholder(
                                emoji = "❓",
                                label = "Unknown",
                                modifier = modifier.clip(cornerShape),
                        )
                }
        }
}

/** Placeholder component for unsupported media types. Shows an emoji icon and label. */
@Composable
private fun MediaPlaceholder(
        emoji: String,
        label: String,
        modifier: Modifier = Modifier,
) {
        Box(
                modifier =
                        modifier.size(Dimens.MediaBubble.minWidthSolo)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
        ) {
                Text(
                        text = emoji,
                        style = MaterialTheme.typography.displayMedium,
                )
        }
}
