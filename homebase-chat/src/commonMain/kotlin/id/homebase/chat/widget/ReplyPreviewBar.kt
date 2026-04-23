package id.homebase.chat.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.cancel_reply
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

/**
 * Signal-style reply preview bar displayed above the message input.
 *
 * Shows a rounded card with the author name, a content preview, and an optional
 * image/video thumbnail on the right side. A close button allows dismissing the reply.
 *
 * @param message The message being replied to.
 * @param onDismiss Callback invoked when user cancels the reply.
 * @param modifier Modifier for the composable.
 */
@Composable
fun ReplyPreviewBar(message: MessageUiModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Filter out non-media payloads (default payload key and payload descriptor keys)
    val mediaPayloads = remember(message.payloads) {
        message.payloads?.filter { payload ->
            payload.key != ChatProtocol.DefaultPayloadKey &&
                !payload.key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
        } ?: emptyList()
    }

    val firstPayload = mediaPayloads.firstOrNull()
    val hasMultiplePayloads = mediaPayloads.size > 1

    // Determine if the first payload is an image or video (eligible for thumbnail)
    val isVisualMedia = remember(firstPayload) {
        val ct = firstPayload?.contentType ?: ""
        ct.startsWith("image/") || ct.startsWith("video/") || ct == "application/vnd.apple.mpegurl"
    }

    // Build thumbnail data for visual media
    val chatDriveId = chatTargetDrive.alias
    val thumbnailData = remember(message.fileId, firstPayload?.key, firstPayload?.lastModified) {
        if (!isVisualMedia || firstPayload == null) return@remember null
        val payloadIv = try {
            firstPayload.iv?.let { Base64.decode(it) }
        } catch (_: Exception) {
            null
        } ?: return@remember null
        HomebaseImageData(
            driveId = chatDriveId,
            fileId = message.fileId,
            payloadKey = firstPayload.key,
            previewThumbnail = firstPayload.previewThumbnail?.toEmbeddedThumb()
                ?: message.previewThumbnail,
            requestedSize = ImageSize.THUMB_SMALL,
            lastModified = firstPayload.lastModified,
            isEncrypted = true,
            keyHeader = KeyHeader(iv = payloadIv, aesKey = message.keyHeader.aesKey),
        )
    }

    // Content label for media-only messages (no text)
    val contentLabel = messageContentLabel(
        textContent = message.content,
        isDeleted = message.isDeleted,
        firstPayload = firstPayload,
        hasMultiplePayloads = hasMultiplePayloads,
    )

    val previewText = contentLabel?.text ?: message.content.truncateToCodePoints(80)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Author + content preview
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.originalAuthor?.domainName ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    contentLabel?.icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Thumbnail if visual media
            if (thumbnailData != null) {
                Spacer(modifier = Modifier.width(8.dp))
                HomebaseImage(
                    imageData = thumbnailData,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                )
            }

            // Close button — always on the far right
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.cancel_reply),
                )
            }
        }
    }
}
