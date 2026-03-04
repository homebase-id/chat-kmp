package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.XorIdUtil
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope

class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val driveUploadProvider: DriveUploadProvider,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val dbm: DatabaseManager,
    private val contactService: ContactService,
    private val introductionProvider: ConnectionIntroductionProvider,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun createConversation(
        recipients: List<OdinId>,
        title: String?,
        payloadBundle: PayloadBundle?
    ): Uuid {

        val domain = credentialsManager.requireActiveDomain()
        val keyHeader = KeyHeader.newRandom16()
        val isGroup = recipients.size > 1

        val newConversationId: Uuid =
            if (isGroup) {
                Uuid.random()
            } else {
                XorIdUtil.getNewXorId(domain.domainName, recipients.first().domainName)
            }

        val existingConversation = getConversation(newConversationId)
        if (existingConversation != null) {
            return newConversationId
        }

        val normalizedRecipients = normalizeRecipients(recipients, domain)

        val content = buildConversationContent(title, normalizedRecipients)

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                newConversationId,
                payloadBundle,
                keyHeader.aesKey,
                scope
            )

        val previewThumb = selectPreviewThumb(encryptedBundle.previewThumbs)

        val metadata =
            buildConversationMetadata(
                newConversationId,
                content,
                previewThumb
            )

        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions =
                    TransitOptions(recipients = recipients, useAppNotification = false),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails
            )

        driveUploadProvider.uploadFile(request)

        if (isGroup) {
            trySendIntroductions(recipients, "$domain has added you to a group chat")

            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = Uuid.random(),
                conversationId = newConversationId,
                messageText = "$domain started this group titled $title"
            )
        }

        return newConversationId
    }

    suspend fun requireConversation(conversationId: Uuid): ConversationUiModel {
        return getConversation(conversationId)
            ?: throw IllegalStateException("No conversation for Id")
    }

    suspend fun getConversation(conversationId: Uuid): ConversationUiModel? {
        val file = getConversationHomebaseFile(conversationId) ?: return null
        return mapToConversationUi(file, null)
    }

    suspend fun ensureRecipientsHaveConversation(conversationId: Uuid) {

        val self = credentialsManager.requireActiveDomain()

        val conversation = getConversationHomebaseFile(conversationId) ?: return;

        val recipients =
            OdinSystemSerializer.deserialize<ConversationAppDataJson>(
                conversation.fileMetadata.appData.content ?: return
            ).recipients

        val filteredRecipients =
            recipients.filter { it != self }

        val serverMetadata = conversation.serverMetadata

        val anyRecipientMissingConversation =
            serverMetadata.originalRecipientCount !=
                    serverMetadata.transferHistory?.summary?.totalDelivered

        if (anyRecipientMissingConversation) {
            redistributeConversation(conversationId)
            if (filteredRecipients.size > 1) {
                trySendIntroductions(filteredRecipients, "$self has added you to a group chat")
            }
        }
    }

    suspend fun redistributeConversation(conversationId: Uuid) {

        val conversation = requireConversation(conversationId)

        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            recipients = conversation.participants
        )
    }

    suspend fun updateGroupMembers(
        conversationId: Uuid,
        add: List<OdinId> = emptyList(),
        remove: List<OdinId> = emptyList()
    ) {
        val conversation = requireConversation(conversationId)

        val domain = credentialsManager.requireActiveDomain()
        val current = conversation.participants.toMutableSet()

        val removed = current.intersect(remove.toSet())
        current.removeAll(remove)

        val added = add.filterNot { current.contains(it) }
        current.addAll(added)

        val normalized = (current + domain).distinct()

        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            recipients = normalized
        )

        trySendIntroductions(added, "$domain has added you to a group chat")

        var previousMessageId: Uuid? = null
        added.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain added ${user.domainName}",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        removed.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain removed ${user.domainName}",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }
    }

    suspend fun introduceEveryone(conversationId: Uuid, message: String?) {
        val conversation = requireConversation(conversationId)
        trySendIntroductions(conversation.participants, message ?: "")
    }

    suspend fun updateConversation(
        conversationId: Uuid,
        title: String?,
        payloadBundle: PayloadBundle? = null
    ) {

        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()

        var previousMessageId: Uuid? = null

        updateConversationInternal(
            conversationId = conversationId,
            title = title,
            recipients = conversation.participants,
            payloadBundle = payloadBundle
        )

        if (title != null && title != conversation.name) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain changed the conversation title to \"$title\"",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        if (payloadBundle != null) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain updated the conversation photo",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }
    }

    private suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        recipients: List<OdinId>,
        payloadBundle: PayloadBundle? = null
    ) {

        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

        val conversationFile =
            getConversationHomebaseFile(conversationId) ?: error("No conversation found")

        val normalizedRecipients = normalizeRecipients(recipients, domain)

        val keyHeader =
            KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = conversationFile.keyHeader.aesKey
            )

        val content = buildConversationContent(title, normalizedRecipients)

        val bundle =
            prepareUpdateBundle(
                conversationId,
                payloadBundle,
                keyHeader.aesKey,
                conversationFile.fileMetadata.appData.previewThumbnail
            )

        val manifest = bundle.manifest
        val payloads = bundle.payloads
        val thumbs = bundle.thumbnails
        val previewThumb = bundle.previewThumb

        val metadata =
            buildConversationMetadata(
                conversationId,
                content,
                previewThumb,
                conversationFile.fileMetadata.versionTag
            )

        val instructions =
            FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = manifest
            )

        val request =
            UpdateFileByUniqueIdRequest(
                driveId = chatDrive,
                uniqueId = conversationId,
                keyHeader = keyHeader,
                instructions = instructions,
                metadata = metadata.encryptContent(keyHeader),
                payloads = payloads,
                thumbnails = thumbs
            )

        driveUploadProvider.updateFileByUniqueId(
            request = request,
            onVersionConflict = {
                null
            }
        )
    }

    private suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile? {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = 1,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                uniqueIdAnyOf = listOf(conversationId),
                filetypesAnyOf = listOf(ChatProtocol.ConversationFileType),
            )

        return result.records.firstOrNull()
    }

    suspend fun mapToConversationUi(
        conversation: HomebaseFile,
        lastMsg: HomebaseFile?
    ): ConversationUiModel {

        val metadata = conversation.fileMetadata
        val appData = metadata.appData

        if (appData.fileType != ChatProtocol.ConversationFileType) {
            throw IllegalArgumentException("Not a conversation file")
        }

        val appDataObj =
            OdinSystemSerializer.deserialize<ConversationAppDataJson>(
                appData.content ?: error("Conversation appData missing")
            )

        val domain = credentialsManager.getActiveDomain() ?: error("No active domain")

        val localAppData =
            metadata.localAppData?.content?.let {
                OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(it)
            }

        val participants = appDataObj.recipients

        val displayNames =
            participants.map { odinId ->
                contactService.resolveByOdinId(odinId)?.name ?: odinId.domainName
            }

        val title =
            if (participants.size == 2) {
                val other = participants.first { it != domain }
                contactService.resolveByOdinId(other)?.name ?: "fracko"
            } else {
                appDataObj.title ?: displayNames.joinToString(", ")
            }

        val avatarModel = buildConversationAvatarModel(conversation)

        val ui =
            ConversationUiModel(
                id = appData.uniqueId ?: error("Missing uniqueId"),
                name = title,
                lastMessage = " ",
                timestamp = UnixTimeUtc(0).toInstant(),
                unreadCount = 0,
                avatarTiny = appData.previewThumbnail,
                avatarInitials = "",
                avatarUrl = "",
                participants = participants,
                lastRead = localAppData?.lastReadTime?.toInstant()
                    ?: UnixTimeUtc(0).toInstant(),
                avatarModel = avatarModel
            )

        if (lastMsg != null) {
            ChatMessageStream.mapToMessageData(lastMsg, ::resolveDisplayName)?.let {
                ui.updateWithLatestMessage(it, domain)
            }
        }

        return ui
    }

    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""
        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }

    private suspend fun buildConversationAvatarModel(
        conversation: HomebaseFile
    ): ConversationAvatarModel {

        val metadata = conversation.fileMetadata
        val appData = metadata.appData

        val domain = credentialsManager.getActiveDomain() ?: error("No active domain")

        val participants =
            OdinSystemSerializer.deserialize<ConversationAppDataJson>(
                appData.content ?: error("Missing content")
            ).recipients

        val uniqueId = appData.uniqueId ?: error("Missing uniqueId")

        val imagePayload =
            metadata.payloads?.firstOrNull { it.key == ChatProtocol.ConversationImageKey }

        if (imagePayload != null) {

            val imageData =
                HomebaseImageData(
                    driveId = chatDrive,
                    fileId = conversation.fileId,
                    payloadKey = imagePayload.key,
                    isEncrypted = metadata.isEncrypted,
                    previewThumbnail =
                        imagePayload.previewThumbnail?.toEmbeddedThumb()
                            ?: appData.previewThumbnail,
                    keyHeader =
                        KeyHeader(
                            iv =
                                Base64.decode(
                                    imagePayload.iv
                                        ?: throw IllegalStateException(
                                            "encrypted payload requires key header"
                                        )
                                ),
                            aesKey = conversation.keyHeader.aesKey
                        ),
                    requestedSize = ImageSize.THUMB_MEDIUM,
                    lastModified = imagePayload.lastModified,
                )

            return ConversationAvatarModel(
                type = ConversationAvatarModel.Type.ConversationImage,
                imageData = imageData
            )
        }

        if (uniqueId == ChatProtocol.ConversationWithYourselfId) {
            return ConversationAvatarModel(
                odinId = domain,
                type = ConversationAvatarModel.Type.Owner
            )
        }

        val others = participants.filter { it != domain }

        if (others.size == 1) {
            return ConversationAvatarModel(
                type = ConversationAvatarModel.Type.Connection,
                odinId = others.first()
            )
        }

        return ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback)
    }

    private fun normalizeRecipients(
        recipients: List<OdinId>,
        self: OdinId
    ): List<OdinId> =
        (recipients + self).distinct()

    private fun buildConversationContent(
        title: String?,
        recipients: List<OdinId>
    ) =
        ConversationAppDataJson(
            title = title ?: "",
            recipients = recipients,
            version = 1
        )

    private fun selectPreviewThumb(
        thumbs: List<EmbeddedThumb>
    ): EmbeddedThumb? =
        thumbs.minByOrNull { it.pixelWidth }

    private fun buildConversationMetadata(
        conversationId: Uuid,
        content: ConversationAppDataJson,
        previewThumb: EmbeddedThumb?,
        versionTag: Uuid? = null
    ): UploadFileMetadata =
        UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = versionTag,
            appData =
                UploadAppFileMetaData(
                    uniqueId = conversationId.toString(),
                    fileType = ChatProtocol.ConversationFileType,
                    content = OdinSystemSerializer.serialize(content),
                    previewThumbnail = previewThumb
                )
        )


    private suspend fun trySendIntroductions(
        recipients: List<OdinId>,
        message: String
    ) {
        try {
            // send introductions
            introductionProvider.sendIntroductions(
                group = IntroductionGroup(
                    recipients = recipients,
                    message = message
                )
            )
        } catch (t: Throwable) {
            Logger.e("Failed sending introductions", t)
        }
    }

    private suspend fun prepareUpdateBundle(
        conversationId: Uuid,
        payloadBundle: PayloadBundle?,
        aesKey: SecureByteArray,
        existingPreview: EmbeddedThumb?
    ): UpdateBundleResult {

        if (payloadBundle == null) {
            return UpdateBundleResult(
                manifest = UpdateManifest.build(
                    payloads = null,
                    toDeletePayloads = null,
                    thumbnails = null,
                    generatePayloadIv = false
                ),
                payloads = emptyList(),
                thumbnails = emptyList(),
                previewThumb = existingPreview
            )
        }

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                conversationId,
                payloadBundle,
                aesKey,
                scope
            )

        val payloads = encryptedBundle.payloads
        val thumbs = encryptedBundle.thumbnails

        return UpdateBundleResult(
            manifest =
                UpdateManifest.build(
                    payloads = payloads,
                    toDeletePayloads = null,
                    thumbnails = thumbs,
                    generatePayloadIv = false
                ),
            payloads = payloads,
            thumbnails = thumbs,
            previewThumb = selectPreviewThumb(encryptedBundle.previewThumbs)
        )
    }

    private data class UpdateBundleResult(
        val manifest: UpdateManifest,
        val payloads: List<PayloadFile>,
        val thumbnails: List<ThumbnailFile>,
        val previewThumb: EmbeddedThumb?
    )
}