package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.event.EventDateChip
import id.homebase.chat.event.rememberEventTimes
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.util.stripComposerLineBreakArtifacts
import id.homebase.resources.MR
import id.homebase.resources.cancel_reply
import id.homebase.resources.cd_reply_thumbnail
import id.homebase.resources.you
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

private val QuoteCardShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp,
    bottomStart = 10.dp, bottomEnd = 10.dp,
)

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
fun ReplyPreviewBar(
    message: MessageUiModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val currentOdinId = LocalCurrentOdinId.current
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

    // Strip richeditor's `<br>` empty-paragraph artifacts so replying to a legacy `<br>` message
    // shows its real text in the composer bar, not a stray break / blank preview (#1104).
    val replyBarText = remember(message.content) { message.content.stripComposerLineBreakArtifacts() }
    // Content label for media-only messages (no text)
    val contentLabel = messageContentLabel(
        textContent = replyBarText,
        isDeleted = message.isDeleted,
        firstPayload = firstPayload,
        hasMultiplePayloads = hasMultiplePayloads,
    )

    val previewText = contentLabel?.text ?: replyBarText.trim().truncateToCodePoints(80)

    val eventDescriptor = (message.messageContent as? MessageContent.Event)?.descriptor
    val eventStartLocal = eventDescriptor?.let { rememberEventTimes(it).viewerStartLocal }

    // Signal's signal_colorTransparent3: white overlay, heavier in light theme.
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val quoteCardAlpha = if (isDark) 0.10f else 0.50f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("reply_preview_bar"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(QuoteCardShape)
                .background(Color.White.copy(alpha = quoteCardAlpha))
                .padding(end = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor, RoundedCornerShape(2.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            ) {
                Text(
                    text = resolveReplyAuthorName(
                        authorOdinId = message.originalAuthor?.domainName ?: "",
                        currentOdinId = currentOdinId,
                        resolvedDisplayName = message.displayName,
                        youLabel = stringResource(MR.string.you),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    contentLabel?.icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (eventStartLocal != null) {
                EventDateChip(local = eventStartLocal)
                Spacer(modifier = Modifier.width(8.dp))
            } else if (thumbnailData != null) {
                HomebaseImage(
                    imageData = thumbnailData,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                    contentDescription = stringResource(MR.string.cd_reply_thumbnail),
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .testTag("reply_dismiss"),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.cancel_reply),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
