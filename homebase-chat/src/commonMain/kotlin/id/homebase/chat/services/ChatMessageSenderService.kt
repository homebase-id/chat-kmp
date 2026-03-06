package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.convo.ConversationDistributor
import id.homebase.chat.services.convo.ConversationRepository
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive

class ChatMessageSenderService(
    private val outboxSync: OutboxSync,
    private val conversationRepository: ConversationRepository,
    private val conversationDistributor: ConversationDistributor,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val scope: CoroutineScope,
    private val chatMessageStream: ChatMessageStream
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun sendNewMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        messageText: String,
        previousMessageUniqueId: Uuid? = null,
        payloadBundle: PayloadBundle? = null
    ): SendMessageResult = sendMessageInternal(
        messageUniqueId = messageUniqueId,
        conversationId = conversationId,
        content = MessageAppData(
            replyId = null,
            replyPreview = null,
            message = JsonPrimitive(messageText),
            deliveryStatus = ChatDeliveryStatus.Sent.value
        ),
        notificationText = "You have a new message",
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = payloadBundle
    )

    suspend fun sendSystemMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        messageText: String,
        previousMessageUniqueId: Uuid? = null,
        payloadBundle: PayloadBundle? = null
    ): SendMessageResult = sendMessageInternal(
        messageUniqueId = messageUniqueId,
        conversationId = conversationId,
        content = MessageAppData(
            replyId = null,
            replyPreview = null,
            message = JsonPrimitive(messageText),
            deliveryStatus = ChatDeliveryStatus.Sent.value
        ),
        notificationText = "You have a new message",
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = payloadBundle,
        isSystemMessage = true
    )

    suspend fun replyToMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        replyTo: ReplyPreview,
        messageText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?
    ): SendMessageResult = sendMessageInternal(
        messageUniqueId = messageUniqueId,
        conversationId = conversationId,
        content = MessageAppData(
            replyPreview = replyTo,
            message = JsonPrimitive(messageText),
            deliveryStatus = ChatDeliveryStatus.Sent.value
        ),
        notificationText = "You have a new reply",
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = payloadBundle
    )


    private suspend fun sendMessageInternal(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        content: MessageAppData,
        notificationText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        isSystemMessage: Boolean = false
    ): SendMessageResult {

        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationRepository.getRecipients(conversationId)

        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(
            messageUniqueId, payloadBundle, keyHeader.aesKey, scope = scope
        )

        val metadata =
            UploadFileMetadata(
                allowDistribution = true, isEncrypted = true, appData = UploadAppFileMetaData(
                    uniqueId = messageUniqueId,
                    groupId = conversationId,
                    dataType = if (isSystemMessage) ChatProtocol.SystemMessageDataType else null,
                    fileType = ChatProtocol.MessageFileType,
                    userDate = UnixTimeUtc.now().milliseconds,
                    content = OdinSystemSerializer.serialize(content),
                    previewThumbnail = encryptedBundle.previewThumbs.minByOrNull {
                        it.pixelWidth
                    })
            )

        val request = UploadFileRequest(
            driveId = chatDrive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = recipients,
                useAppNotification = true,
                appNotificationOptions = PushNotificationOptions(
                    appId = ChatProtocol.ChatAppId.toString(),
                    typeId = conversationId.toString(),
                    tagId = messageUniqueId.toString(),
                    silent = false,
                    unEncryptedMessage = notificationText
                )
            ),
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails
        )

        try {
            conversationDistributor.ensureRecipientsHaveConversation(conversationId)

            val enqueued = outboxSync.tryEnqueue(
                request.driveId,
                messageUniqueId,
                dependencyUniqueId = previousMessageUniqueId,
                priority = 1,
                uploadType = DriveOutboxUploader.UploadNewFile,
                json = OdinSystemSerializer.serialize(request),
            )

            if (enqueued) {
                outboxSync.send()
            }

            return SendMessageResult(uniqueId = messageUniqueId)
        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to send chat message")
    }

    suspend fun updateMessage(
        messageId: Uuid,
        content: String,
    ): UpdateMessageResult {

        // grab the existing message
        val msg = chatMessageStream.getMessage(messageId)
            ?: throw IllegalArgumentException("message not found")

        if (msg.isSystemMessage) {
            throw IllegalArgumentException("Cannot delete system message")
        }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = msg.keyHeader.aesKey
        )

        val recipients = conversationRepository.getRecipients(msg.conversationId)

        val msgContent = msg.messageAppData.copy(
            message = JsonPrimitive(content)
        )

        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = msg.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = messageId,
                groupId = msg.conversationId,
                fileType = ChatProtocol.MessageFileType,
                userDate = UnixTimeUtc.now().milliseconds,
                content = OdinSystemSerializer.serialize(msgContent),
                previewThumbnail = msg.previewThumbnail
            )
        )

        val manifest =
            UpdateManifest.build(
                payloads = null,
                toDeletePayloads = null,
                thumbnails = null,
                generatePayloadIv = false
            )

        val request = UpdateFileByUniqueIdRequest(
            driveId = chatDrive,
            uniqueId = messageId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = manifest,
                useAppNotification = false,
                appNotificationOptions = null
            ),
            metadata = metadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList()
        )

        try {

            conversationDistributor.ensureRecipientsHaveConversation(msg.conversationId)

            if (outboxSync.tryEnqueue(
                    request.driveId,
                    messageId,
                    dependencyUniqueId = null,
                    priority = 1,
                    uploadType = DriveOutboxUploader.UpdateFile,
                    json = OdinSystemSerializer.serialize(request),
                )
            ) {
                outboxSync.send()
            }

            return UpdateMessageResult(uniqueId = messageId)

        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to update chat message")
    }
}