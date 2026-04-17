package id.homebase.chat.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.homebase.api.image.toImageBitmap
import id.homebase.chat.conversationlist.PendingOutgoingMessage
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.services.LocalVideoContextStore
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.chat_message_video_thumbnail
import id.homebase.resources.pending_attachment_many
import id.homebase.resources.pending_attachment_one
import id.homebase.resources.upload_preparing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun PendingMessageBubble(
    message: PendingOutgoingMessage,
    uploadStatus: UploadStatus? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (message.isVideo) {
            VideoPendingBubble(message = message, uploadStatus = uploadStatus)
        } else {
            GenericPendingBubble(message = message)
        }
    }
}

@Composable
private fun VideoPendingBubble(
    message: PendingOutgoingMessage,
    uploadStatus: UploadStatus?,
) {
    val store = koinInject<LocalVideoContextStore>()
    val ctx = remember(message.id) { store.get(message.id) }
    val bitmap = remember(ctx?.thumbnailBytes) { ctx?.thumbnailBytes?.toImageBitmap() }

    val hasText = message.text.isNotEmpty()
    val shape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius,
        bottomStart = Dimens.Message.cornerRadius,
        bottomEnd = if (hasText) 4.dp else Dimens.Message.cornerRadius,
    )
    val mediaShape = if (hasText) {
        RoundedCornerShape(
            topStart = Dimens.Message.cornerRadius,
            topEnd = Dimens.Message.cornerRadius,
        )
    } else {
        shape
    }

    Surface(
        modifier = Modifier.clip(shape),
        shape = shape,
        color = HomebaseTheme.extendedColors.bubbleSentSurface,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .width(Dimens.MediaBubble.maxWidth)
                    .heightIn(min = Dimens.MediaBubble.minHeight, max = Dimens.MediaBubble.maxHeight)
                    .clip(mediaShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                val aspectModifier = ctx?.aspectRatio?.let { ratio ->
                    Modifier.fillMaxWidth().then(Modifier.aspectRatio(ratio))
                } ?: Modifier.fillMaxSize()

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                        modifier = aspectModifier,
                        contentScale = ContentScale.Crop,
                    )
                }

                if (uploadStatus != null) {
                    UploadProgressOverlay(
                        status = uploadStatus,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            if (hasText) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun GenericPendingBubble(message: PendingOutgoingMessage) {
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
                    text = message.text,
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
