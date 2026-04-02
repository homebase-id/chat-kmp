package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
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
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.chat.ChatMessageSizer
import id.homebase.chat.data.ConversationState
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
    private val optimisticWriter: OptimisticWriter,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val shareSuggestionDonor: ShareSuggestionDonor = ShareSuggestionDonor(),
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun writePlaceholderMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        messageText: String,
        payloadDescriptors: List<PayloadDescriptor>?,
        replyPreview: ReplyPreview? = null,
    ) {
        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationStream.getRecipients(conversationId)
        val isLocalOnly = recipients.isEmpty()

        val messageData = MessageAppData(
            replyId = null,
            replyPreview = replyPreview,
            message = JsonPrimitive(messageText),
            deliveryStatus = ChatDeliveryStatus.Sent.value,
        ).copy(version = ChatProtocol.MessageVersionNumberOne)

        val content = OdinSystemSerializer.serialize(messageData)

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = messageUniqueId,
                groupId = conversationId,
                fileType = ChatProtocol.MessageFileType,
                dataType = 0,
                userDate = UnixTimeUtc.now().milliseconds,
                content = content,
                previewThumbnail = null,
            )
        )

        optimisticWriter.writeNewFile(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = unencryptedMetadata,
            originalRecipientCount = recipients.size,
            fileSystemType = FileSystemType.Standard,
            payloadDescriptors = payloadDescriptors,
        )
    }

    suspend fun sendNewMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        messageText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?
    ): SendMessageResult {
        val messageData = MessageAppData(
            replyId = null,
            replyPreview = null,
            message = JsonPrimitive(messageText),
            deliveryStatus = ChatDeliveryStatus.Sent.value
        )

        val built = buildMessageContentAndBundle(
            messageData,
            payloadBundle,
            fileOperationsProvider
        )

        return sendMessageInternal(
            messageUniqueId,
            conversationId,
            built.headerContent,
            "You have a new message",
            previousMessageUniqueId,
            built.payloadBundle
        )
    }


    suspend fun replyToMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        replyTo: ReplyPreview,
        messageText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?
    ): SendMessageResult {
        val messageData = MessageAppData(
            replyPreview = replyTo,
            message = JsonPrimitive(messageText),
            deliveryStatus = ChatDeliveryStatus.Sent.value
        )

        val built = buildMessageContentAndBundle(
            messageData,
            payloadBundle,
            fileOperationsProvider
        )

        return sendMessageInternal(
            messageUniqueId,
            conversationId,
            built.headerContent,
            "You have a new reply",
            previousMessageUniqueId,
            built.payloadBundle
        )
    }


    suspend fun sendStatusMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        statusMessage: StatusMessageData,
        previousMessageUniqueId: Uuid? = null,
        payloadBundle: PayloadBundle? = null
    ): SendMessageResult = sendMessageInternal(
        messageUniqueId = messageUniqueId,
        conversationId = conversationId,
        content = OdinSystemSerializer.serialize(statusMessage),
        notificationText = "",
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = payloadBundle,
        isStatusMessage = true
    )

    private suspend fun sendMessageInternal(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        content: String,
        notificationText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        isStatusMessage: Boolean = false
    ): SendMessageResult {

        val conversation = conversationStream.getConversationById(conversationId)
        if (!isStatusMessage && (conversation?.conversationState == ConversationState.Left
            || conversation?.conversationState == ConversationState.RejoinPending
            || conversation?.conversationState == ConversationState.Removed)
        ) {
            error("Cannot send messages to a group you have left")
        }

        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationStream.getRecipients(conversationId)
        val isLocalOnly = recipients.isEmpty() // self-conversation: no distribution

        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(
            messageUniqueId, payloadBundle, keyHeader.aesKey, scope = scope
        )

        val unecryptedMetadata =
            UploadFileMetadata(
                allowDistribution = !isLocalOnly,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = messageUniqueId,
                    groupId = conversationId,
                    fileType = ChatProtocol.MessageFileType,
                    dataType = if (isStatusMessage) ChatProtocol.ChatStatusMessageDataType else 0,
                    userDate = UnixTimeUtc.now().milliseconds,
                    content = content,
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
                useAppNotification = !isStatusMessage && !isLocalOnly,
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

            // optimistic write first — message appears in UI before any network work
            @OptIn(ExperimentalEncodingApi::class)
            val payloadDescriptors = encryptedBundle.payloads.map { payload ->
                PayloadDescriptor(
                    key = payload.key,
                    contentType = payload.contentType.ifEmpty { null },
                    iv = payload.iv?.let { Base64.encode(it) },
                    descriptorContent = payload.descriptorContent,
                    previewThumbnail = payload.previewThumbnail?.let {
                        ThumbnailDescriptor(
                            pixelWidth = it.pixelWidth,
                            pixelHeight = it.pixelHeight,
                            contentType = it.contentType,
                            content = it.content,
                        )
                    },
                )
            }.ifEmpty { null }
            optimisticWriter.writeNewFile(
                driveId = chatDrive,
                keyHeader = keyHeader,
                unecryptedMetadata = unecryptedMetadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )

            val enqueued = outboxSync.tryEnqueue(
                request,
                priority = 1,
                dependencyUniqueId = previousMessageUniqueId,
            )

            if (!enqueued) {
                error("Failed to send chat message")
            }

            // Donate share suggestion so this conversation appears in OS share sheet
            try {
                val conversation = conversationStream.getConversationById(conversationId)
                if (conversation != null) {
                    shareSuggestionDonor.donateAfterSend(
                        conversationId = conversationId,
                        conversationName = conversation.getDisplayName(),
                        isGroup = conversation.isGroupConversation,
                        participantNames = recipients.map { it.domainName },
                    )
                }
            } catch (_: Exception) {
                // Non-critical — don't fail the send
            }

            return SendMessageResult(uniqueId = messageUniqueId)
        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
            try {
                optimisticWriter.removeOptimisticFile(chatDrive, messageUniqueId)
            } catch (_: Exception) {
                // best-effort rollback
            }
        }

        error("Failed to send chat message")
    }

    suspend fun updateMessage(
        messageId: Uuid,
        versionTag: Uuid,
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
        val isLocalOnly = recipients.isEmpty() // self-conversation: no distribution

        val messageData = msg.messageAppData.copy(
            deliveryStatus = ChatDeliveryStatus.Sending.value,
            message = JsonPrimitive(content),
            isEdited = true
        )

        val built = buildMessageContentAndBundle(
            preVersionedMessageData = messageData,
            payloadBundle = null,
            fileOperationsProvider = fileOperationsProvider
        )

        val payloads = built.payloadBundle?.payloads ?: emptyList()

        val toDeletePayloads =
            if (payloads.isEmpty())
                listOf(PayloadDeleteKey(ChatProtocol.DefaultPayloadKey))
            else
                emptyList()

        val unecryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = messageId,
                groupId = msg.conversationId,
                fileType = ChatProtocol.MessageFileType,
                dataType = 0,
                userDate = msg.userDate.toEpochMilliseconds(),
                content = built.headerContent,
                previewThumbnail = msg.previewThumbnail
            )
        )

        val manifest =
            UpdateManifest.build(
                payloads = payloads,
                toDeletePayloads = toDeletePayloads,
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
            payloads = payloads,
            thumbnails = emptyList()
        )

        try {

            val enqueued = outboxSync.tryEnqueue(
                request,
                priority = 1,
                dependencyUniqueId = null,
            )

            if (!enqueued) {
                error("Failed to update chat message")
            }

            optimisticWriter.writeUpdate(
                driveId = chatDrive,
                keyHeader = keyHeader,
                unecryptedMetadata = unecryptedMetadata
            )

            return UpdateMessageResult(uniqueId = messageId)

        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to update chat message")
    }


    @OptIn(ExperimentalEncodingApi::class)
    suspend fun forwardMessage(
        sourceMessageUniqueId: Uuid,
        targetConversationIds: List<Uuid>
    ): List<SendMessageResult> {
        val sourceFile = chatMessageStream.getMessageFile(sourceMessageUniqueId)
            ?: throw IllegalArgumentException("source message not found: $sourceMessageUniqueId")

        val content = sourceFile.fileMetadata.appData.content
            ?: throw IllegalArgumentException("source message has no content")

        val messageAppData = OdinSystemSerializer.deserialize<MessageAppData>(content)

        val fullText = chatMessageStream.loadFullMessage(
            conversationId = sourceFile.fileMetadata.appData.groupId!!,
            messageId = sourceMessageUniqueId
        ) ?: messageAppData.getMessage()

        val forwardData = messageAppData.copy(
            replyPreview = null,
            message = JsonPrimitive(fullText),
            deliveryStatus = ChatDeliveryStatus.Sent.value,
            isEdited = false
        )

        val mediaBundle = buildMediaPayloadBundle(sourceFile)

        val built = buildMessageContentAndBundle(
            preVersionedMessageData = forwardData,
            payloadBundle = mediaBundle,
            fileOperationsProvider = fileOperationsProvider
        )

        return targetConversationIds.map { conversationId ->
            sendMessageInternal(
                messageUniqueId = Uuid.random(),
                conversationId = conversationId,
                content = built.headerContent,
                notificationText = "You have a new message",
                previousMessageUniqueId = null,
                payloadBundle = built.payloadBundle
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun buildMediaPayloadBundle(source: HomebaseFile): PayloadBundle? {
        val mediaDescriptors = source.fileMetadata.payloads
            ?.filter { !it.keyEquals(ChatProtocol.DefaultPayloadKey) }
            ?: return null

        if (mediaDescriptors.isEmpty()) return null

        val payloadFiles = mutableListOf<PayloadFile>()
        val thumbnailFiles = mutableListOf<ThumbnailFile>()
        val previewThumbs = mutableListOf<EmbeddedThumb>()

        for (descriptor in mediaDescriptors) {
            val ivBytes = descriptor.iv?.let { Base64.decode(it) } ?: continue
            val keyHeader = KeyHeader(iv = ivBytes, aesKey = source.keyHeader.aesKey)


            //TODO: optimization - if we share the location of the
            // temp file we don't have to rewrite it to disk?
            val response = driveFileProvider.getPayloadBytesDecrypted(
                driveId = chatDrive,
                fileId = source.fileId,
                key = descriptor.key,
                keyHeader = keyHeader
            ) ?: continue

            val tempPath = fileOperationsProvider.writeBytesToTempFile(
                bytes = response.bytes,
                prefix = "fwd_${descriptor.key}",
                suffix = ""
            )

            payloadFiles += PayloadFile(
                key = descriptor.key,
                filePath = tempPath,
                contentType = descriptor.contentType ?: "",
                descriptorContent = descriptor.descriptorContent,
                previewThumbnail = descriptor.previewThumbnail?.toEmbeddedThumb()
            )

            descriptor.previewThumbnail?.toEmbeddedThumb()?.let { previewThumbs += it }

            descriptor.thumbnails?.forEach { thumb ->
                val width = thumb.pixelWidth ?: return@forEach
                val height = thumb.pixelHeight ?: return@forEach
                val thumbResponse = driveFileProvider.getThumbBytesDecrypted(
                    driveId = chatDrive,
                    fileId = source.fileId,
                    payloadKey = descriptor.key,
                    keyHeader = keyHeader,
                    width = width,
                    height = height
                ) ?: return@forEach
                thumbnailFiles += ThumbnailFile(
                    pixelWidth = width,
                    pixelHeight = height,
                    thumbnailBytes = thumbResponse.bytes,
                    key = descriptor.key,
                    contentType = thumbResponse.contentType
                )
            }
        }

        if (payloadFiles.isEmpty()) return null

        return PayloadBundle(
            payloads = payloadFiles,
            thumbnails = thumbnailFiles,
            previewThumbs = previewThumbs
        )
    }

    suspend fun buildMessageContentAndBundle(
        preVersionedMessageData: MessageAppData,
        payloadBundle: PayloadBundle?,
        fileOperationsProvider: FileOperationsProvider
    ): MessageBuildResult {

        val messageData = preVersionedMessageData.copy(
            version = ChatProtocol.MessageVersionNumberOne
        )

        val fullJson = OdinSystemSerializer.serialize(messageData)

        val canUseHeaderOnly = ChatMessageSizer.shouldEmbedInHeader(fullJson)

        if (canUseHeaderOnly) {
            return MessageBuildResult(
                headerContent = fullJson,
                payloadBundle = payloadBundle
            )
        }

        val previewData = messageData.copy(
            message = JsonPrimitive(
                ChatMessageSizer.preview(messageData.getMessage())
            )
        )

        val headerContent = OdinSystemSerializer.serialize(previewData)

        val payloadBytes = ChatMessageSizer.payloadBytes(messageData)

        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            bytes = payloadBytes,
            prefix = "chat_msg",
            suffix = ".json"
        )

        val payload = PayloadFile(
            key = ChatProtocol.DefaultPayloadKey,
            filePath = tempPath,
            contentType = "application/json"
        )

        val updatedBundle =
            (payloadBundle ?: PayloadBundle(emptyList(), emptyList(), emptyList()))
                .copy(payloads = payloadBundle?.payloads.orEmpty() + payload)

        return MessageBuildResult(
            headerContent = headerContent,
            payloadBundle = updatedBundle
        )
    }

}

