package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.PendingOutgoingMessage
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.ui.theme.withEmojiFont

import id.homebase.resources.MR
import id.homebase.resources.pending_attachment_many
import id.homebase.resources.pending_attachment_one
import id.homebase.resources.upload_preparing
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Placeholder bubble shown between "tap send" and the real optimistic-write bubble appearing.
 *
 * For messages with local attachment previews (video thumbnails / image files), we synthesize
 * [PayloadDescriptor]s and defer to [MediaMessage] — the same composable the real bubble uses.
 * This guarantees the placeholder and the real bubble render identically at identical sizes,
 * so there's no pop/resize/flicker when the real bubble takes over.
 */
@Composable
fun PendingMessageBubble(
    message: PendingOutgoingMessage,
    uploadStatus: UploadStatus? = null,
    modifier: Modifier = Modifier,
) {
    val store = koinInject<LocalAttachmentContextStore>()
    val contexts by store.observeAll(message.id)
        .collectAsStateWithLifecycle(initialValue = store.getAll(message.id))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (contexts.isNotEmpty()) {
            MediaPlaceholderBubble(
                message = message,
                uploadStatus = uploadStatus,
                contexts = contexts,
            )
        } else {
            GenericPendingBubble(message = message)
        }
    }
}

@Composable
private fun MediaPlaceholderBubble(
    message: PendingOutgoingMessage,
    uploadStatus: UploadStatus?,
    contexts: Map<String, LocalAttachmentContext>,
) {
    val hasText = message.text.isNotEmpty()
    val bubbleShape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius,
        bottomStart = Dimens.Message.cornerRadius,
        bottomEnd = if (hasText) 4.dp else Dimens.Message.cornerRadius,
    )

    // Synthesize payload descriptors from the local contexts, in payload-key order so the
    // ordering matches what MessageAttachmentBuilder produces and what MediaMessage/Gallery
    // will show once the real bubble lands.
    val syntheticPayloads = remember(contexts) {
        contexts.entries
            .sortedBy { it.key }
            .map { (payloadKey, ctx) ->
                val contentType = when (ctx) {
                    is LocalAttachmentContext.Video -> "video/mp4"
                    is LocalAttachmentContext.Image -> "image/jpeg"
                }
                // Fabricate a previewThumbnail so MediaItem can compute the aspect ratio
                // (it reads pixelWidth/pixelHeight off the descriptor). Real pixel values
                // don't matter — only the ratio does.
                val aspectDescriptor = ctx.aspectRatio?.let { ratio ->
                    val w = 1000
                    val h = (w / ratio).toInt().coerceAtLeast(1)
                    ThumbnailDescriptor(pixelWidth = w, pixelHeight = h)
                }
                PayloadDescriptor(
                    key = payloadKey,
                    contentType = contentType,
                    iv = null,
                    previewThumbnail = aspectDescriptor,
                )
            }
    }
    val fakeKeyHeader = remember {
        KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(32)))
    }

    Surface(
        modifier = Modifier.clip(bubbleShape),
        shape = bubbleShape,
        color = HomebaseTheme.extendedColors.bubbleSentSurface,
    ) {
        // Mirror the real bubble's media+caption layout so the placeholder matches it at
        // the same size (no pop on takeover): the gallery renders full-bleed and the
        // caption below is clamped to the media's width. Without the clamp a caption wider
        // than the album width stretches the bubble while the gallery stays pinned to the
        // album width — the #964 blue gap beside the images, in the pending state.
        Layout(
            content = {
                message.replyPreview?.let { reply ->
                    InlineReplyPreview(
                        replyPreview = reply,
                        sentByYou = true,
                        onClick = {},
                        replyMessage = null,
                        driveId = chatTargetDrive.alias,
                    )
                }
                MediaMessage(
                    payloads = syntheticPayloads,
                    decryptedFiles = persistentMapOf(),
                    fileId = message.id,
                    driveId = chatTargetDrive.alias,
                    keyHeader = fakeKeyHeader,
                    shape = if (hasText || message.replyPreview != null) {
                        RoundedCornerShape(0.dp)
                    } else {
                        bubbleShape
                    },
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                    messageId = message.id,
                    downloadingFiles = emptySet(),
                    uploadStatus = uploadStatus ?: UploadStatus.Preparing,
                )

                if (hasText) {
                    Text(
                        text = message.text.withEmojiFont(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            },
        ) { measurables, constraints ->
            val hasReply = message.replyPreview != null
            var i = 0
            val replyIndex = if (hasReply) i++ else -1
            val mediaIndex = i++
            val captionIndex = if (hasText) i else -1

            // Media drives the width (the gallery's discrete album width); reply and
            // caption are clamped to it so neither can widen the bubble past the images.
            val mediaPlaceable = measurables[mediaIndex].measure(constraints)
            val width = mediaPlaceable.width
            val clamped = constraints.copy(minWidth = width, maxWidth = width)
            val replyPlaceable = if (replyIndex >= 0) measurables[replyIndex].measure(clamped) else null
            val captionPlaceable = if (captionIndex >= 0) measurables[captionIndex].measure(clamped) else null

            val height = (replyPlaceable?.height ?: 0) + mediaPlaceable.height + (captionPlaceable?.height ?: 0)
            layout(width, height) {
                var y = 0
                replyPlaceable?.let { it.placeRelative(0, y); y += it.height }
                mediaPlaceable.placeRelative(0, y); y += mediaPlaceable.height
                captionPlaceable?.placeRelative(0, y)
            }
        }
    }
}

@Composable
private fun GenericPendingBubble(
    message: PendingOutgoingMessage,
) {
    val shape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius,
        bottomStart = Dimens.Message.cornerRadius,
        bottomEnd = 4.dp,
    )
    val backgroundColor = HomebaseTheme.extendedColors.bubbleSentSurface
    val contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface

    Surface(
        modifier = Modifier.clip(shape),
        shape = shape,
        color = backgroundColor,
    ) {
        Column {
            message.replyPreview?.let { reply ->
                InlineReplyPreview(
                    replyPreview = reply,
                    sentByYou = true,
                    onClick = {},
                    replyMessage = null,
                    driveId = chatTargetDrive.alias,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.attachmentCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (message.attachmentCount == 1)
                                stringResource(MR.string.pending_attachment_one)
                            else
                                stringResource(MR.string.pending_attachment_many, message.attachmentCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.75f),
                        )
                    }
                }
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text.withEmojiFont(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(MR.string.upload_preparing),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
