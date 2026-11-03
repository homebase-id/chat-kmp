package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.kindLabel
import id.homebase.chat.viewonce.ViewOnceIcon
import id.homebase.chat.viewonce.ViewOnceOpenedIcon
import id.homebase.resources.MR
import id.homebase.resources.chat_message_audio
import id.homebase.resources.chat_message_deleted
import id.homebase.resources.chat_message_file
import id.homebase.resources.chat_message_gif
import id.homebase.resources.chat_message_image
import id.homebase.resources.chat_message_link
import id.homebase.resources.chat_message_location
import id.homebase.resources.chat_message_multiple_media
import id.homebase.resources.chat_message_video
import id.homebase.resources.chat_message_voice_duration
import id.homebase.resources.chat_preview_sticker
import id.homebase.core.widget.formatAudioTime
import org.jetbrains.compose.resources.StringResource
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
    is MessageContent.Poll -> ContentLabel(messageContent.displayLabel, Icons.Default.BarChart)
    is MessageContent.Event -> ContentLabel(messageContent.displayLabel, Icons.Default.Event)
    is MessageContent.DiceRoll -> ContentLabel(messageContent.displayLabel, Icons.Default.Casino)
    is MessageContent.Groodle -> ContentLabel(messageContent.displayLabel, Icons.Default.CalendarMonth)
    is MessageContent.CallEvent -> ContentLabel(messageContent.displayLabel, Icons.Default.Call)
    is MessageContent.ContactCard -> ContentLabel(messageContent.displayLabel, Icons.Outlined.ContactPage)
    is MessageContent.Location -> ContentLabel(messageContent.displayLabel, Icons.Default.LocationOn)
    is MessageContent.ViewOnce -> null
    is MessageContent.Unknown -> ContentLabel(messageContent.displayLabel, Icons.AutoMirrored.Outlined.HelpOutline)
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
    // Null reads the opened variant off a missing payload: a received view-once copy loses its media once spent.
    viewOnceOpened: Boolean? = null,
): ContentLabel? {
    // Before the deleted check: a spent view-once item keeps its view-once label instead of reading as deleted; "Media" only when this device never stored the live row.
    if (messageContent is MessageContent.ViewOnce) {
        val word = stringResource(messageContent.descriptor.kindLabel())
        return ContentLabel(word, if (viewOnceOpened ?: (firstPayload == null)) ViewOnceOpenedIcon else ViewOnceIcon)
    }
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

    val kind = payloadPreviewKind(firstPayload, hasMultiplePayloads) ?: return null
    return when (kind) {
        PayloadPreviewKind.MultipleMedia -> ContentLabel(stringResource(kind.labelRes()), Icons.Default.PhotoLibrary)
        PayloadPreviewKind.Link -> ContentLabel(stringResource(kind.labelRes()), Icons.Default.Description)
        PayloadPreviewKind.Location -> ContentLabel(stringResource(kind.labelRes()), Icons.Default.LocationOn)
        PayloadPreviewKind.Sticker -> ContentLabel(stringResource(kind.labelRes()), Icons.AutoMirrored.Filled.StickyNote2)
        // GIF indicator is the 👾 emoji baked into the string (like the deleted-message
        // 🚫), so no Material icon — keeps the playful marker without an extra glyph.
        PayloadPreviewKind.Gif -> ContentLabel(stringResource(kind.labelRes()), icon = null)
        PayloadPreviewKind.Image -> ContentLabel(stringResource(kind.labelRes()), Icons.Default.Image)
        PayloadPreviewKind.Video -> ContentLabel(stringResource(kind.labelRes()), Icons.Default.PlayArrow)
        PayloadPreviewKind.Audio -> {
            // Only the in-app recorder measures a length; picked audio files carry 0.
            val voiceNoteSeconds = remember(firstPayload) { firstPayload?.audioLengthSeconds() }
            if (voiceNoteSeconds != null) {
                ContentLabel(
                    text = stringResource(MR.string.chat_message_voice_duration, formatAudioTime(voiceNoteSeconds)),
                    icon = Icons.Default.Mic
                )
            } else {
                ContentLabel(stringResource(kind.labelRes()), Icons.Default.PlayArrow)
            }
        }
        PayloadPreviewKind.File -> ContentLabel(
            text = firstPayload?.descriptorContent?.takeIf { it.isNotBlank() }
                ?: stringResource(kind.labelRes()),
            icon = Icons.Default.Description
        )
    }
}

enum class PayloadPreviewKind { MultipleMedia, Link, Location, Sticker, Gif, Image, Video, Audio, File }

fun PayloadPreviewKind.labelRes(): StringResource = when (this) {
    PayloadPreviewKind.MultipleMedia -> MR.string.chat_message_multiple_media
    PayloadPreviewKind.Link -> MR.string.chat_message_link
    PayloadPreviewKind.Location -> MR.string.chat_message_location
    PayloadPreviewKind.Sticker -> MR.string.chat_preview_sticker
    PayloadPreviewKind.Gif -> MR.string.chat_message_gif
    PayloadPreviewKind.Image -> MR.string.chat_message_image
    PayloadPreviewKind.Video -> MR.string.chat_message_video
    PayloadPreviewKind.Audio -> MR.string.chat_message_audio
    PayloadPreviewKind.File -> MR.string.chat_message_file
}

fun payloadPreviewKind(firstPayload: PayloadDescriptor?, hasMultiplePayloads: Boolean): PayloadPreviewKind? = when {
    hasMultiplePayloads -> PayloadPreviewKind.MultipleMedia
    firstPayload == null -> null
    // Specific payload keys first: a Location carries a map *image* and a Link
    // carries a preview *image*, so they'd otherwise match the generic image/
    // branch below and mis-label as "Image". The key identifies the kind exactly.
    firstPayload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> PayloadPreviewKind.Link
    firstPayload.key == ChatProtocol.PAYLOAD_KEY_LOCATION -> PayloadPreviewKind.Location
    // A solo transparent cut-out image carries DescriptorContent.ImageFile(isSticker=true).
    // hasMultiplePayloads is already false here, so this is the single-payload case the
    // sticker bubble (MediaMessage) recognises — surface "Sticker" instead of "Image".
    firstPayload.isImage() &&
        (firstPayload.descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true -> PayloadPreviewKind.Sticker
    firstPayload.contentType == "image/gif" -> PayloadPreviewKind.Gif
    firstPayload.isImage() -> PayloadPreviewKind.Image
    firstPayload.isVideo() -> PayloadPreviewKind.Video
    firstPayload.isAudio() -> PayloadPreviewKind.Audio
    else -> PayloadPreviewKind.File
}
