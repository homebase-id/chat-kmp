package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.convo.ConversationStreamService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid

class ChatMessageSenderService(
    private val driveUploadProvider: DriveUploadProvider,
    private val conversationService: ConversationStreamService,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun sendNewMessage(
        conversationId: Uuid,
        messageText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult =
        deliverMessage(
            conversationId = conversationId,
            content =
                MessageAppData(
                    replyId = null,
                    replyPreview = null,
                    message = messageText,
                    deliveryStatus = ChatDeliveryStatus.Sent.value
                ),
            notificationText = "You have a new message",
            payloadBundle = payloadBundle
        )

    suspend fun replyToMessage(
        conversationId: Uuid,
        replyTo: ReplyPreview,
        messageText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult =
        deliverMessage(
            conversationId = conversationId,
            content =
                MessageAppData(
                    replyPreview = replyTo,
                    message = messageText,
                    deliveryStatus = ChatDeliveryStatus.Sent.value
                ),
            notificationText = "You have a new reply",
            payloadBundle = payloadBundle
        )

    private suspend fun deliverMessage(
        conversationId: Uuid,
        content: MessageAppData,
        notificationText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult {

        // distribute the conversation file if needed
//        conversationWriterService.updateConversationRecipients(conversationId, )

        val result = sendMessageInternal(conversationId, content, notificationText, payloadBundle)
        return result;
    }

    private suspend fun sendMessageInternal(
        conversationId: Uuid,
        content: MessageAppData,
        notificationText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult {

        val uniqueId = Uuid.random()
        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationService.getRecipients(conversationId)

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(payloadBundle, keyHeader)
        val encryptedPayloads = encryptedBundle.payloads
        val encryptedThumbnails = encryptedBundle.thumbnails

        val metadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = uniqueId.toString(),
                        groupId = conversationId.toString(),
                        fileType = ChatProtocol.MessageFileType,
                        userDate = UnixTimeUtc.now().milliseconds,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail =
                            encryptedBundle
                                .previewThumbs
                                .minByOrNull { it.pixelWidth }
                    )
            )

        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions =
                    TransitOptions(
                        recipients = recipients,
                        useAppNotification = true,
                        appNotificationOptions =
                            PushNotificationOptions(
                                appId = ChatProtocol.ChatAppId.toString(),
                                typeId = conversationId.toString(),
                                tagId = uniqueId.toString(),
                                silent = false,
                                unEncryptedMessage = notificationText
                            )
                    ),
                payloads = encryptedPayloads,
                thumbnails = encryptedThumbnails
            )
        try {
            val result =
                driveUploadProvider.uploadFile(request)
                    ?: error("Failed to upload chat message")
            return SendMessageResult(
                fileId = result.fileId,
                uniqueId = uniqueId,
                versionTag = result.newVersionTag
            )
        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to send chat message")
    }
}

data class EncryptedFileResult(
    val filePath: String,
    val iv: ByteArray
)

