package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.resources.MR
import id.homebase.resources.chat_message_audio
import id.homebase.resources.chat_message_deleted
import id.homebase.resources.chat_message_file
import id.homebase.resources.chat_message_image
import id.homebase.resources.chat_message_link
import id.homebase.resources.chat_message_location
import id.homebase.resources.chat_message_multiple_media
import id.homebase.resources.chat_message_video
import id.homebase.resources.chat_preview_sticker
import org.jetbrains.compose.resources.stringResource

/**
 * Represents a content-type label for a message preview (e.g., "Image", "Video").
 */
data class ContentLabel(val text: String, val icon: ImageVector?)

/**
 * Content label for a typed message kind (poll, event, dice roll, groodle, or an
 * unrecognized newer kind) — the kind's icon plus its [MessageContent.displayLabel]
 * (poll question, event title, dice summary, …). Lets the conversation-list preview show
 * "<icon> <title>" instead of plain text for these messages.
 *
 * Pure (no composition) so it is unit-testable; the labels are message content, not UI
 * chrome, so they need no localization — matching the existing notification/search path
 * that also reads [MessageContent.displayLabel].
 *
 * When you add a new typed message kind, add its branch here so it gets a preview icon.
 */
fun typedMessageContentLabel(messageContent: MessageContent?): ContentLabel? = when (messageContent) {
    is MessageContent.Poll -> ContentLabel(messageContent.displayLabel, Icons.Default.HowToVote)
    is MessageContent.Event -> ContentLabel(messageContent.displayLabel, Icons.Default.Event)
    is MessageContent.DiceRoll -> ContentLabel(messageContent.displayLabel, Icons.Default.Casino)
    is MessageContent.Groodle -> ContentLabel(messageContent.displayLabel, Icons.Default.CalendarMonth)
    is MessageContent.Unknown -> ContentLabel(messageContent.displayLabel, Icons.AutoMirrored.Filled.HelpOutline)
    null -> null
}

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
    messageContent: MessageContent? = null,
): ContentLabel? {
    if (isDeleted) {
        return ContentLabel(
            text = stringResource(MR.string.chat_message_deleted),
            icon = Icons.Default.Block
        )
    }

    // Typed kinds (poll/event/dice/groodle) show their kind icon + title even though their
    // preview text is non-blank — so this runs before the text early-return below.
    typedMessageContentLabel(messageContent)?.let { return it }

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
            // A solo transparent cut-out image carries DescriptorContent.ImageFile(isSticker=true).
            // hasMultiplePayloads is already false here, so this is the single-payload case the
            // sticker bubble (MediaMessage) recognises — surface "Sticker" instead of "Image".
            firstPayload.contentType?.startsWith("image/") == true &&
                (firstPayload.descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true ->
                ContentLabel(
                    text = stringResource(MR.string.chat_preview_sticker),
                    icon = Icons.AutoMirrored.Filled.StickyNote2
                )
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
            firstPayload.key == ChatProtocol.PAYLOAD_KEY_LOCATION -> ContentLabel(
                text = stringResource(MR.string.chat_message_location),
                icon = Icons.Default.LocationOn
            )
            else -> ContentLabel(
                text = stringResource(MR.string.chat_message_file),
                icon = Icons.Default.Description
            )
        }
    }

    return null
}
