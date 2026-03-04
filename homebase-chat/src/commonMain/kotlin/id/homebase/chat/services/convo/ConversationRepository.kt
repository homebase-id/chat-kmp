package id.homebase.chat.services.convo

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.common.OdinId
import kotlin.uuid.Uuid
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import kotlin.io.encoding.Base64

class ConversationRepository(
    private val credentialsManager: CredentialsManager,
    private val introductionProvider: ConnectionIntroductionProvider,
    private val contactService: ContactService,
    private val outboxSync: OutboxSync,
    private val dbm: DatabaseManager

) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun createConversationFile(conversationId: Uuid, request: UploadFileRequest) {
        val enqueued = outboxSync.tryEnqueue(
            request.driveId,
            conversationId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(request),
        )

        if (enqueued) {
            outboxSync.send()
        }
    }

    suspend fun updateConversationFile(conversationId: Uuid, request: UpdateFileByUniqueIdRequest) {
        val enqueued = outboxSync.tryEnqueue(
            request.driveId,
            conversationId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = OdinSystemSerializer.serialize(request),
        )

        if (enqueued) {
            outboxSync.send()
        }

    }

    suspend fun requireConversation(conversationId: Uuid): ConversationUiModel {
        return getConversation(conversationId)
            ?: throw IllegalStateException("No conversation for Id")
    }

    suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile? {

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

    suspend fun getRecipients(conversationId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials()
        val conversation = requireConversation(conversationId)

        return conversation.participants.filterNot { odinId ->
            odinId == credentials.domain
        }
    }

    suspend fun getConversation(conversationId: Uuid): ConversationUiModel? {
        val file = getConversationHomebaseFile(conversationId) ?: return null
        return mapToConversationUi(file, null)
    }


    private suspend fun trySendIntroductions(
        recipients: List<OdinId>,
        message: String
    ) {
        try {
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
                contactService.resolveByOdinId(other)?.name ?: other.domainName
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
                lastRead =
                    localAppData?.lastReadTime?.toInstant()
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
            metadata.payloads?.firstOrNull {
                it.key == ChatProtocol.ConversationImageKey
            }

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
                    lastModified = imagePayload.lastModified
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

        return ConversationAvatarModel(
            type = ConversationAvatarModel.Type.GroupFallback
        )
    }
}

