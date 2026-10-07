package id.homebase.chat.viewonce

import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.content.MessageContent
import id.homebase.upload.PayloadBundle
import id.homebase.upload.withoutThumbnails
import kotlin.uuid.Uuid

fun viewOnceKindOf(attachment: AttachmentInput): String? = when {
    attachment.forceSticker -> null
    attachment.contentType.startsWith("image/") -> ViewOnceDescriptor.KIND_IMAGE
    attachment.contentType.startsWith("video/") -> ViewOnceDescriptor.KIND_VIDEO
    else -> null
}

suspend fun buildViewOnceBundle(
    attachment: AttachmentInput,
    fileOperationsProvider: FileOperationsProvider,
    mediaQuality: MediaQuality,
): PayloadBundle = MessageAttachmentBuilder.buildSingle(
    attachment = attachment,
    fileOperationsProvider = fileOperationsProvider,
    payloadKey = VIEW_ONCE_PAYLOAD_KEY,
    mediaQuality = mediaQuality,
).withoutThumbnails()

suspend fun ChatMessageSenderService.sendAttachmentsMessage(
    viewOnce: Boolean,
    messageId: Uuid,
    conversationId: Uuid,
    text: String,
    attachments: List<AttachmentInput>,
    replyTo: ReplyPreview?,
    sentAt: UnixTimeUtc,
    fileOperationsProvider: FileOperationsProvider,
    mediaQuality: MediaQuality,
) {
    if (viewOnce) {
        require(replyTo == null) { "view-once media cannot be a reply" }
        val attachment = attachments.singleOrNull()
            ?: throw IllegalArgumentException("view-once media takes exactly one attachment")
        val kind = viewOnceKindOf(attachment)
            ?: throw IllegalArgumentException("view-once media must be an image or a video")
        sendNewTypedMessage(
            messageUniqueId = messageId,
            conversationId = conversationId,
            content = MessageContent.ViewOnce(ViewOnceDescriptor(kind)),
            previousMessageUniqueId = null,
            payloadBundle = buildViewOnceBundle(attachment, fileOperationsProvider, mediaQuality),
            userDate = sentAt,
        )
        return
    }
    val bundle = MessageAttachmentBuilder.build(
        attachments = attachments,
        fileOperationsProvider = fileOperationsProvider,
        mediaQuality = mediaQuality,
        payloadKeyFactory = { index, _ -> "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index" },
    )
    if (replyTo != null) {
        replyToMessage(
            messageUniqueId = messageId,
            conversationId = conversationId,
            replyTo = replyTo,
            messageText = text,
            previousMessageUniqueId = null,
            payloadBundle = bundle,
            userDate = sentAt,
        )
    } else {
        sendNewMessage(
            messageUniqueId = messageId,
            conversationId = conversationId,
            messageText = text,
            previousMessageUniqueId = null,
            payloadBundle = bundle,
            userDate = sentAt,
        )
    }
}
