package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.kindLabel
import id.homebase.chat.widget.PayloadPreviewKind
import id.homebase.chat.widget.mediaPayloads
import id.homebase.chat.widget.payloadPreviewKind
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.notifications.NotificationMessagePreview
import id.homebase.core.notifications.NotificationMessageResolver
import id.homebase.resources.MR
import id.homebase.resources.chat_message_audio
import id.homebase.resources.chat_message_file
import id.homebase.resources.chat_message_gif
import id.homebase.resources.chat_message_image
import id.homebase.resources.chat_message_link
import id.homebase.resources.chat_message_location
import id.homebase.resources.chat_message_multiple_media
import id.homebase.resources.chat_message_video
import id.homebase.resources.chat_preview_sticker
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

// Leaves the rest of NotificationService's 4 s resolve budget for the text when the thumb is slow.
private const val IMAGE_LOAD_TIMEOUT_MS = 2_500L

/**
 * Bridges [NotificationMessageResolver] (homebase-common) to the chat message pipeline (#859):
 * resolves the referenced message via [MessageLookup] (local, or a single-header server fetch
 * when it hasn't synced yet) and returns its already-formatted preview — typed kinds (Poll,
 * Event, …) included, since [MessageUiModel.content] is the same text the conversation list
 * shows — or, for a media-only message, the conversation list's media label. Adds the group name
 * from [conversation] and the message's own thumbnail from [loadImage].
 */
class ChatNotificationMessageResolver(
    private val messageLookup: MessageLookup,
    private val conversation: suspend (Uuid) -> ConversationUiModel? = { null },
    private val loadImage: suspend (HomebaseImageData) -> ByteArray? = { null },
) : NotificationMessageResolver {
    override suspend fun resolvePreview(
        conversationId: Uuid,
        messageId: Uuid,
    ): NotificationMessagePreview? {
        val message = messageLookup.resolveForNotification(conversationId, messageId) ?: return null
        val viewOnce = message.messageContent as? MessageContent.ViewOnce
        val media = message.payloads.mediaPayloads()
        val kind = if (viewOnce != null) null else payloadPreviewKind(media.firstOrNull(), media.size > 1)
        val preview = message.content.takeIf { it.isNotBlank() }
            ?: viewOnce?.let { TranslationUtil.getString(it.descriptor.kindLabel()) }
            ?: kind?.let { TranslationUtil.getString(it.label()) }
            ?: return null
        val group = conversation(conversationId)?.takeIf { it.isGroupConversation }
        val image = if (kind?.isVisual == true) {
            media.firstOrNull { it.isVisualMedia() }?.let { payload ->
                withTimeoutOrNull(IMAGE_LOAD_TIMEOUT_MS) { notificationImage(message, payload)?.let { loadImage(it) } }
            }
        } else null
        return NotificationMessagePreview(
            senderName = message.displayName.takeIf { it.isNotBlank() },
            preview = preview,
            isGroup = group != null,
            groupTitle = group?.name,
            imageBytes = image,
        )
    }
}

internal fun notificationImage(message: MessageUiModel, payload: PayloadDescriptor): HomebaseImageData? {
    val iv = payload.iv?.bytes ?: return null
    return HomebaseImageData.from(
        driveId = chatTargetDrive.alias,
        fileId = message.fileId,
        descriptor = payload,
        keyHeader = KeyHeader(iv = iv, aesKey = message.keyHeader.aesKey),
    )
}

private fun PayloadPreviewKind.label(): StringResource = when (this) {
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
