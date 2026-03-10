package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
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
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive

class ChatMessageSenderService(
    private val outboxSync: OutboxSync,
    private val conversationStream: ConversationStream,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val scope: CoroutineScope,
    private val chatMessageStream: ChatMessageStream,
    private val optimisticWriter: OptimisticWriter
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun sendNewMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        messageText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?
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

    suspend fun sendStatusMessage(
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
        notificationText = "",
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = payloadBundle,
        isStatusMessage = true
    )

    private suspend fun sendMessageInternal(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        content: MessageAppData,
        notificationText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        isStatusMessage: Boolean = false
    ): SendMessageResult {

        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationStream.getRecipients(conversationId)

        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(
            messageUniqueId, payloadBundle, keyHeader.aesKey, scope = scope
        )

        val unecryptedMetadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = messageUniqueId,
                    groupId = conversationId,
                    fileType = ChatProtocol.MessageFileType,
                    dataType = if (isStatusMessage) ChatProtocol.ChatStatusMessageDataType else null,
                    userDate = UnixTimeUtc.now().milliseconds,
                    content = OdinSystemSerializer.serialize(content),
                    previewThumbnail = encryptedBundle.previewThumbs.minByOrNull {
                        it.pixelWidth
                    })
            )

        val request = UploadFileRequest(
            driveId = chatDrive,
            keyHeader = keyHeader,
            metadata = unecryptedMetadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = recipients,
                useAppNotification = !isStatusMessage,
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

            val enqueued = outboxSync.tryEnqueue(
                request,
                priority = 1,
                dependencyUniqueId = previousMessageUniqueId,
            )

            if (enqueued) {
                // optimistic write after we know it will be sent
                optimisticWriter.writeNewFile(
                    driveId = chatDrive,
                    keyHeader = keyHeader,
                    unecryptedMetadata = unecryptedMetadata,
                    originalRecipientCount = recipients.size,
                    fileSystemType = FileSystemType.Standard
                )
            }

            outboxSync.send()

            return SendMessageResult(uniqueId = messageUniqueId)
        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to send chat message")
    }

    suspend fun updateMessage(
        messageId: Uuid,
        versionTag: Uuid, // the version of message you're currently editing
        content: String,
    ): UpdateMessageResult {

        // grab the existing message
        val msg = chatMessageStream.getMessage(messageId)
            ?: throw IllegalArgumentException("message not found")

        if (msg.versionTag != versionTag) {
            error("VersionTag mismatch")
        }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = msg.keyHeader.aesKey
        )

        val recipients = conversationStream.getRecipients(msg.conversationId)

        val msgContent = msg.messageAppData.copy(
            deliveryStatus = ChatDeliveryStatus.Sending.value,
            message = JsonPrimitive(content)
        )

        val unecryptedMetadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = versionTag,
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
            metadata = unecryptedMetadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList()
        )

        try {
            val enqueued = outboxSync.tryEnqueue(
                request,
                priority = 1,
                dependencyUniqueId = null,
            )

            if (enqueued) {

                optimisticWriter.writeUpdate(
                    driveId = chatDrive,
                    keyHeader = keyHeader,
                    unecryptedMetadata = unecryptedMetadata
                )

                outboxSync.send()
            }


            return UpdateMessageResult(uniqueId = messageId)

        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to update chat message")
    }
}