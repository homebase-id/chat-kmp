package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.resources.MR
import id.homebase.resources.chat_message_audio
import id.homebase.resources.chat_message_deleted
import id.homebase.resources.chat_message_file
import id.homebase.resources.chat_message_image
import id.homebase.resources.chat_message_link
import id.homebase.resources.chat_message_multiple_media
import id.homebase.resources.chat_message_video
import org.jetbrains.compose.resources.stringResource

/**
 * Represents a content-type label for a message preview (e.g., "Image", "Video").
 */
data class ContentLabel(val text: String, val icon: ImageVector?)

/**
 * Determines the content-type label for a message based on its payload descriptors.
 *
 * Used by both ConversationItem (conversation list preview) and ReplyPreviewBar
 * (reply input preview) to avoid duplicating content-type detection logic.
 *
 * @return ContentLabel with text and icon, or null if the message has text content to display directly
 */
@Composable
fun messageContentLabel(
    textContent: String,
    isDeleted: Boolean,
    firstPayload: PayloadDescriptor?,
    hasMultiplePayloads: Boolean,
): ContentLabel? {
    if (isDeleted) {
        return ContentLabel(
            text = stringResource(MR.string.chat_message_deleted),
            icon = Icons.Default.Block
        )
    }

    if (textContent.isNotBlank()) {
        return null
    }

    if (hasMultiplePayloads) {
        return ContentLabel(
            text = stringResource(MR.string.chat_message_multiple_media),
            icon = Icons.Default.PhotoLibrary
        )
    }

    if (firstPayload != null) {
        return when {
            firstPayload.contentType?.startsWith("image/") == true -> ContentLabel(
                text = stringResource(MR.string.chat_message_image),
                icon = Icons.Default.Image
            )
            firstPayload.contentType?.startsWith("video/") == true ||
                firstPayload.contentType == "application/vnd.apple.mpegurl" -> ContentLabel(
                text = stringResource(MR.string.chat_message_video),
                icon = Icons.Default.PlayArrow
            )
            firstPayload.contentType?.startsWith("audio/") == true -> ContentLabel(
                text = stringResource(MR.string.chat_message_audio),
                icon = Icons.Default.PlayArrow
            )
            firstPayload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> ContentLabel(
                text = stringResource(MR.string.chat_message_link),
                icon = Icons.Default.Description
            )
            else -> ContentLabel(
                text = stringResource(MR.string.chat_message_file),
                icon = Icons.Default.Description
            )
        }
    }

    return null
}
