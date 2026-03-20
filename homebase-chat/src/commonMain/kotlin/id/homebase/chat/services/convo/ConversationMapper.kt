package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

class ConversationMapper(
    private val credentialsManager: CredentialsManager
) {

    private val logger = Logger.withTag("ConversationMapper")

    suspend fun mapToConversationUi(
        conversationFile: HomebaseFile,
        lastMsg: HomebaseFile?
    ): ConversationUiModel {

        logger.d {
            "START map | fileId=${conversationFile.fileId} uid=${conversationFile.fileMetadata.appData.uniqueId} state=${conversationFile.fileState}"
        }

        return try {

            val domain = credentialsManager.requireActiveDomain()
            val metadata = conversationFile.fileMetadata
            val appData = metadata.appData

            if (appData.fileType != ChatProtocol.ConversationFileType) {
                throw IllegalArgumentException("Not a conversation file")
            }

            val conversationId = appData.uniqueId ?: error("Missing uniqueId")

            val isDeleted = conversationFile.fileState == FileState.Deleted
            if (isDeleted) {
                return mapDeletedConversation(conversationFile, lastMsg, domain)
            }

            val isArchived = appData.archivalStatus == ArchivalStatus.Removed
            if (isArchived) {
                return mapArchivedConversation(conversationFile, lastMsg, domain)
            }

            val conversationData =
                OdinSystemSerializer.deserialize<ConversationAppDataJson>(
                    appData.content ?: error("Conversation appData missing")
                )

            val localAppData =
                metadata.localAppData?.content?.let {
                    OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(it)
                }

            val participants =
                conversationData.recipients.filterNotNull().distinct()

            require(participants.isNotEmpty()) { "Conversation has no valid participants" }

            val others = participants.filterNot { it == domain }
            val isGroup = others.size > 1

            val displayNames = participants.map { it.domainName }

            val title =
                if (conversationId == ChatProtocol.ConversationWithYourselfId) {
                    "" // Display name resolved via string resource at UI layer
                } else if (isGroup) {
                    conversationData.title ?: displayNames.joinToString(", ")
                } else {
                    val other = participants.first { it != domain }
                    other.domainName
                }

            val admins: Set<OdinId> =
                if (isGroup) {
                    (conversationData.admins ?: listOf(
                        metadata.originalAuthor
                            ?: metadata.senderOdinId
                            ?: domain
                    )).toSet()
                } else {
                    emptySet()
                }

            val avatarModel = buildConversationAvatarModel(
                conversationFile,
                participants,
                domain,
                conversationId
            )

            val ui =
                ConversationUiModel(
                    id = conversationId,
                    name = title,
                    lastMessage = " ",
                    timestamp = metadata.created.toInstant(),
                    unreadCount = 0,
                    avatarTiny = appData.previewThumbnail,
                    avatarInitials = "",
                    avatarUrl = "",
                    participants = participants,
                    lastRead = localAppData?.lastReadTime?.toInstant()
                        ?: UnixTimeUtc(0).toInstant(),
                    avatarModel = avatarModel,
                    admins = admins,
                    conversationState = ConversationState.Active
                )

            if (lastMsg != null) {
                ChatMessageStream.mapToMessageData(lastMsg) { homebaseFile ->
                    homebaseFile.fileMetadata.originalAuthor?.domainName ?: ""

                }?.let {
                    ui.updateWithLatestMessage(it, domain)
                }
            }

            logger.d {
                "SUCCESS map | id=${ui.id} participants=${ui.participants.size}"
            }

            ui

        } catch (t: Throwable) {

            logger.e(t) {
                "FAILED map | fileId=${conversationFile.fileId}"
            }

            ConversationUiModel(
                id = conversationFile.fileMetadata.appData.uniqueId ?: Uuid.random(),
                name = "",
                lastMessage = "Failure loading conversation",
                timestamp = UnixTimeUtc(0).toInstant(),
                unreadCount = 0,
                avatarInitials = "",
                avatarUrl = "",
                avatarTiny = null,
                participants = emptyList(),
                lastRead = UnixTimeUtc(0).toInstant(),
                avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                admins = emptySet(),
                conversationState = ConversationState.Invalid
            )
        }
    }

    private suspend fun mapArchivedConversation(
        conversationFile: HomebaseFile,
        lastMsg: HomebaseFile?,
        domain: OdinId
    ): ConversationUiModel {

        val metadata = conversationFile.fileMetadata
        val appData = metadata.appData

        val m = ConversationUiModel(
            id = appData.uniqueId ?: error("Missing uniqueId"),
            name = "Archived Conversation",
            lastMessage = " ",
            timestamp = metadata.created.toInstant(),
            unreadCount = 0,
            avatarTiny = appData.previewThumbnail,
            avatarInitials = "",
            avatarUrl = "",
            participants = emptyList(),
            lastRead = UnixTimeUtc(0).toInstant(),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = setOf(domain),
            conversationState = ConversationState.Archived
        )

        if (lastMsg != null) {
            ChatMessageStream.mapToMessageData(lastMsg) { it.fileMetadata.originalAuthor?.domainName ?: "" }?.let {
                m.updateWithLatestMessage(it, domain)
            }
        }

        return m
    }

    private suspend fun mapDeletedConversation(
        conversationFile: HomebaseFile,
        lastMsg: HomebaseFile?,
        domain: OdinId
    ): ConversationUiModel {

        val metadata = conversationFile.fileMetadata
        val appData = metadata.appData

        val m = ConversationUiModel(
            id = appData.uniqueId ?: error("Missing uniqueId"),
            name = "Deleted conversation",
            lastMessage = " ",
            timestamp = metadata.created.toInstant(),
            unreadCount = 0,
            avatarTiny = appData.previewThumbnail,
            avatarInitials = "",
            avatarUrl = "",
            participants = emptyList(),
            lastRead = UnixTimeUtc(0).toInstant(),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = setOf(domain),
            conversationState = ConversationState.Deleted
        )

        if (lastMsg != null) {
            ChatMessageStream.mapToMessageData(lastMsg) { it.fileMetadata.originalAuthor?.domainName ?: "" }?.let {
                m.updateWithLatestMessage(it, domain)
            }
        }

        return m
    }

    private suspend fun buildConversationAvatarModel(
        conversation: HomebaseFile,
        participants: List<OdinId>,
        domain: OdinId,
        conversationId: Uuid
    ): ConversationAvatarModel {

        val metadata = conversation.fileMetadata
        val appData = metadata.appData

        val imagePayload =
            metadata.payloads?.firstOrNull { it.key == ChatProtocol.ConversationImageKey }

        if (imagePayload != null) {

            val imageData =
                HomebaseImageData(
                    driveId = chatTargetDrive.alias,
                    fileId = conversation.fileId,
                    payloadKey = imagePayload.key,
                    isEncrypted = metadata.isEncrypted,
                    previewThumbnail = imagePayload.previewThumbnail?.toEmbeddedThumb()
                        ?: appData.previewThumbnail,
                    keyHeader =
                        KeyHeader(
                            iv = Base64.decode(
                                imagePayload.iv ?: error("encrypted payload requires key header")
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

        if (conversationId == ChatProtocol.ConversationWithYourselfId) {
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
}