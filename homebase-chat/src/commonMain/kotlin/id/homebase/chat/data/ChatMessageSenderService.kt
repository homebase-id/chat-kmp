package id.homebase.chat.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
<<<<<<< HEAD
import id.homebase.core.config.chatTargetDrive
=======
import id.homebase.chat.config.chatTargetDrive
>>>>>>> d3de184e1c771e8ee19034483d913fb2e005d325
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class ChatMessageSenderService(
    private val credentialsManager: CredentialsManager,
    private val driveUploadProvider: DriveUploadProvider,
    private val conversationService: ConversationService,
    private val scope: CoroutineScope
) {

    private val CHAT_APP_ID = Uuid.parse("2d781401-3804-4b57-b4aa-d8e4e2ef39f4")

    private val chatDrive = chatTargetDrive.alias

    suspend fun sendNewMessage(
        conversationId: Uuid,
        messageText: String
    ): SendMessageResult {

        val uniqueId = Uuid.random()

        val content = MessageAppData(
            replyId = null,
            replyPreview = null,
            message = messageText,
            deliveryStatus = ChatDeliveryStatus.Sent.value
        )

        val metadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = uniqueId.toString(),
                        groupId = conversationId.toString(),
                        fileType = CHAT_MESSAGE_FILE_TYPE,
                        userDate = UnixTimeUtc.now().milliseconds,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail = null
                    )
            )

        val keyHeader = KeyHeader.newRandom16()

        val recipients = conversationService.getRecipients(conversationId)

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
                                appId = CHAT_APP_ID.toString(),
                                typeId = conversationId.toString(),
                                tagId = uniqueId.toString(),
                                silent = false,
                                unEncryptedMessage = "You have a new message"
                            )
                    )
            )

        val result =
            driveUploadProvider.uploadFile(request)
                ?: error("Failed to upload chat message")

        return SendMessageResult(
            fileId = result.fileId,
            uniqueId = uniqueId,
            versionTag = result.newVersionTag
        )
    }

    suspend fun replyToMessage(
        conversationId: Uuid,
        replyTo: ReplyPreview,
        messageText: String
    ): SendMessageResult {

        val uniqueId = Uuid.random()

        val content = MessageAppData(
            replyId = replyTo.replyUniqueId,
            replyPreview = replyTo,
            message = messageText,
            deliveryStatus = ChatDeliveryStatus.Sent.value
        )

        val metadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = uniqueId.toString(),
                        groupId = conversationId.toString(),
                        fileType = CHAT_MESSAGE_FILE_TYPE,
                        userDate = UnixTimeUtc.now().milliseconds,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail = null
                    )
            )

        val keyHeader = KeyHeader.newRandom16()

        val recipients = conversationService.getRecipients(conversationId)

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
                                appId = CHAT_APP_ID.toString(),
                                typeId = conversationId.toString(),
                                tagId = uniqueId.toString(),
                                silent = false,
                                unEncryptedMessage = "You have a new reply"
                            )
                    )
            )

        val result =
            driveUploadProvider.uploadFile(request)
                ?: error("Failed to upload reply message")

        return SendMessageResult(
            fileId = result.fileId,
            uniqueId = uniqueId,
            versionTag = result.newVersionTag
        )
    }

}
