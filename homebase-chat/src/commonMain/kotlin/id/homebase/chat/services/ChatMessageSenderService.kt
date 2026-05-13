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
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.data.ConversationState
import id.homebase.chat.services.chat.ChatMessageSizer
import id.homebase.chat.services.convo.ConversationParticipantLookup
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

class ChatMessageSenderService(
    private val outboxSync: OutboxSync,
    private val conversationStream: ConversationParticipantLookup,
    private val payloadBundleEncryptionService: PayloadBundleEncryptor,
    private val scope: CoroutineScope,
    private val chatMessageStream: MessageLookup,
    private val optimisticWriter: OptimisticWriter,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val shareSuggestionDonor: ShareSuggestionDonor = ShareSuggestionDonor(),
) : id.homebase.chat.services.convo.StatusMessageSender {
    companion object {
        private const val TAG = "ChatMessageSenderService"
    }

    private val chatDrive = chatTargetDrive.alias

    /**
     * In-memory tracker of the last outbox-enqueued message uniqueId per conversation.
     * Used to chain each new message behind the previous one when the caller doesn't
     * supply a [previousMessageUniqueId], so offline pile-ups release in send order
     * rather than fanning out in parallel the moment the conversation file drains.
     *
     * Lifetime: per service instance. After app restart, the map is empty — pending
     * outbox rows already carry their persisted deps, so the chain among already-
     * queued messages survives restart; only the link between pre-restart and
     * post-restart messages resets, which is acceptable (post-restart messages
     * fall back to chaining on the conversation file id, which the queued ones
     * also depended on transitively).
     */
    private val lastEnqueuedPerConversation = mutableMapOf<Uuid, Uuid>()
    private val lastEnqueuedMutex = Mutex()

    suspend fun sendNewMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        messageText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        userDate: UnixTimeUtc? = null,
        dataType: Int = 0,
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
            messageUniqueId = messageUniqueId,
            conversationId = conversationId,
            content = built.headerContent,
            notificationText = "You have a new message",
            previousMessageUniqueId = previousMessageUniqueId,
            payloadBundle = built.payloadBundle,
            dataType = dataType,
            userDate = userDate,
        )
    }


    suspend fun replyToMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        replyTo: ReplyPreview,
        messageText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        userDate: UnixTimeUtc? = null,
        dataType: Int = 0,
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
            messageUniqueId = messageUniqueId,
            conversationId = conversationId,
            content = built.headerContent,
            notificationText = "You have a new reply",
            previousMessageUniqueId = previousMessageUniqueId,
            payloadBundle = built.payloadBundle,
            dataType = dataType,
            userDate = userDate,
        )
    }


    override suspend fun sendStatusMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        statusMessage: StatusMessageData,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        additionalRecipients: List<OdinId>,
        recipientOverride: List<OdinId>?,
    ): SendMessageResult = sendMessageInternal(
        messageUniqueId = messageUniqueId,
        conversationId = conversationId,
        content = OdinSystemSerializer.serialize(statusMessage),
        notificationText = "",
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = payloadBundle,
        dataType = ChatProtocol.ChatStatusMessageDataType,
        isStatusMessage = true,
        additionalRecipients = additionalRecipients,
        recipientOverride = recipientOverride,
    )

    /**
     * Sends a typed rich-content message (event today; poll, doodle, dice when
     * those land). The content's JSON rides on the message header
     * (`appData.content` + `appData.dataType` from
     * [id.homebase.chat.services.content.MessageContentParser.dataTypeFor]) so
     * receivers see it without fetching a payload. The notification / chat-list
     * preview text is the content's `displayLabel`.
     *
     * Adding a new kind: implement [id.homebase.chat.services.content.MessageContent],
     * add a parser branch, and call this method. No new sender entry point.
     */
    suspend fun sendNewTypedMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        content: id.homebase.chat.services.content.MessageContent,
        previousMessageUniqueId: Uuid?,
    ): SendMessageResult = sendMessageInternal(
        messageUniqueId = messageUniqueId,
        conversationId = conversationId,
        content = id.homebase.chat.services.content.MessageContentParser.serialize(content),
        // notificationLabel is the privacy-aware wire value (defaults to
        // displayLabel; Event overrides it to a sentinel + start time so
        // the title never leaves the sender). See MessageContent.kt.
        notificationText = content.notificationLabel,
        previousMessageUniqueId = previousMessageUniqueId,
        payloadBundle = null,
        dataType = id.homebase.chat.services.content.MessageContentParser.dataTypeFor(content),
    )

    private suspend fun sendMessageInternal(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        content: String,
        notificationText: String,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        dataType: Int = 0,
        isStatusMessage: Boolean = false,
        additionalRecipients: List<OdinId> = emptyList(),
        userDate: UnixTimeUtc? = null,
        recipientOverride: List<OdinId>? = null,
    ): SendMessageResult {
        Logger.d(tag = TAG) { "sendMessageInternal: starting message=$messageUniqueId conversation=$conversationId" }

        val conversation =
            conversationStream.getConversationById(conversationId) ?: error("no conversation found")

        if (!isStatusMessage && (conversation.conversationState == ConversationState.Left
                    || conversation.conversationState == ConversationState.RejoinPending
                    || conversation.conversationState == ConversationState.Removed)
        ) {
            error("Cannot send messages to a group you have left")
        }

        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationStream.getRecipients(
            conversationId = conversationId,
            additionalRecipients = additionalRecipients,
            recipientOverride = recipientOverride,
        )
        val isLocalOnly = recipients.isEmpty() // self-conversation: no distribution

        val effectiveNotificationText = if (recipients.size > 1) {
            val groupName = conversation.name
            if (groupName.isNotBlank()) {
                "$notificationText in $groupName"
            } else {
                "$notificationText in a group chat"
            }
        } else {
            notificationText
        }

        Logger.d(tag = TAG) {
            "sendMessageInternal: encrypting message=$messageUniqueId " +
                "conversation=$conversationId " +
                "recipients=${recipients.size} " +
                "recipientIds=${recipients.map { it.domainName }} " +
                "isLocalOnly=$isLocalOnly " +
                "payloads=${payloadBundle?.payloads?.size ?: 0}"
        }
        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(
            messageUniqueId, payloadBundle, keyHeader.aesKey, scope = scope
        )

        val effectiveUserDate = userDate ?: UnixTimeUtc.now()
        val unecryptedMetadata =
            UploadFileMetadata(
                allowDistribution = !isLocalOnly,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = messageUniqueId,
                    groupId = conversationId,
                    fileType = ChatProtocol.MessageFileType,
                    dataType = dataType,
                    userDate = effectiveUserDate.milliseconds,
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
                sendContents = SendContents.All,
                useAppNotification = !isStatusMessage && !isLocalOnly,
                appNotificationOptions = PushNotificationOptions(
                    appId = ChatProtocol.ChatAppId.toString(),
                    typeId = conversationId.toString(),
                    tagId = messageUniqueId.toString(),
                    silent = false,
                    unEncryptedMessage = effectiveNotificationText
                )
            ),
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails
        )
        // Outbox enqueue is the success gate — once accepted, the message
        // will be delivered regardless of what happens after.
        //
        // Determine the outbox dependency:
        //   1. If the caller supplied an explicit previousMessageUniqueId, use it.
        //   2. Otherwise, chain on the last message we enqueued in THIS conversation
        //      (in-memory tracker) so messages typed while offline release in send
        //      order rather than fanning out in parallel the moment the conversation
        //      file drains.
        //   3. If this is the first message we enqueue in the conversation since the
        //      service started, fall back to the conversation file's uniqueId. For a
        //      brand-new conversation the conversation file is still in the outbox,
        //      so the message waits — closes the orphan-recovery race on the
        //      recipient side. For an active conversation the conversation file row
        //      is gone, so the dep resolves immediately via the `NOT EXISTS` guard
        //      in the outbox checkout SQL — no overhead.
        val effectiveDep = previousMessageUniqueId
            ?: lastEnqueuedMutex.withLock { lastEnqueuedPerConversation[conversationId] }
            ?: conversationId
        val enqueued = outboxSync.tryEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = effectiveDep,
        )

        if (!enqueued) {
            error("Failed to send chat message")
        }
        // Record this message as the new chain tail for the conversation. Done after
        // a successful enqueue so a failure doesn't leave a dangling pointer that
        // future messages would chain to a never-enqueued id (which `NOT EXISTS`
        // would resolve immediately, undoing the chain).
        lastEnqueuedMutex.withLock {
            lastEnqueuedPerConversation[conversationId] = messageUniqueId
        }
        Logger.d(tag = TAG) { "sendMessageInternal: outbox enqueued message=$messageUniqueId dep=$effectiveDep" }

        // Best-effort optimistic write — makes the message appear in the chat
        // stream immediately. If it fails, the outbox delivery + sync will
        // bring the message back.
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
        try {
            optimisticWriter.writeNewFile(
                driveId = chatDrive,
                keyHeader = keyHeader,
                unecryptedMetadata = unecryptedMetadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )
            Logger.d(tag = TAG) { "sendMessageInternal: optimistic write complete message=$messageUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "sendMessageInternal: optimistic write failed (non-fatal) message=$messageUniqueId" }
        }

        // Best-effort share suggestion
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
            // Non-critical
        }

        return SendMessageResult(uniqueId = messageUniqueId)
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
                generatePayloadIv = unecryptedMetadata.isEncrypted
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

            val enqueued = outboxSync.replaceEnqueue(
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

        val messageAppData = try {
            OdinSystemSerializer.deserialize<MessageAppData>(content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to deserialize source message content for $sourceMessageUniqueId: ${content.take(200)}", e)
        }

        val fullText = chatMessageStream.loadFullMessage(
            conversationId = sourceFile.fileMetadata.appData.groupId!!,
            messageId = sourceMessageUniqueId
        ) ?: messageAppData.getMessage()

        val forwardData = messageAppData.copy(
            replyPreview = null,
            message = JsonPrimitive(fullText),
            deliveryStatus = ChatDeliveryStatus.Sent.value,
            isEdited = false,
        )

        val mediaBundle = buildMediaPayloadBundle(sourceFile)

        val built = buildMessageContentAndBundle(
            preVersionedMessageData = forwardData,
            payloadBundle = mediaBundle,
            fileOperationsProvider = fileOperationsProvider
        )

        // Preserve the source's dataType so a forwarded Location stays
        // header-tagged as a Location (and any future payload-bearing kind
        // does the same automatically). Sources that pre-date the kind
        // tagging carry dataType=0; their forwards inherit that — accepting
        // the same backwards-incompatibility we accept on the original
        // wire-level messages.
        val forwardedDataType = sourceFile.fileMetadata.appData.dataType ?: 0

        return targetConversationIds.map { conversationId ->
            sendMessageInternal(
                messageUniqueId = Uuid.random(),
                conversationId = conversationId,
                content = built.headerContent,
                notificationText = "You have a new message",
                previousMessageUniqueId = null,
                payloadBundle = built.payloadBundle,
                dataType = forwardedDataType,
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

