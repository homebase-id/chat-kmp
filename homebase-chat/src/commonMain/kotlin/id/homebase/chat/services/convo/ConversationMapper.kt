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
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService
) {

    suspend fun mapToConversationUi(
        conversationFile: HomebaseFile,
        lastMsg: HomebaseFile?
    ): ConversationUiModel {

        return try {

            val domain = credentialsManager.requireActiveDomain()
            val metadata = conversationFile.fileMetadata
            val appData = metadata.appData

            if (appData.fileType != ChatProtocol.ConversationFileType) {
                throw IllegalArgumentException("Not a conversation file")
            }

            val isDeleted = conversationFile.fileState == FileState.Deleted
            if (isDeleted) {
                val m = ConversationUiModel(
                    id = appData.uniqueId ?: error("Missing uniqueId"),
                    name = "Deleted conversation",
                    lastMessage = " ",
                    timestamp = conversationFile.fileMetadata.created.toInstant(),
                    unreadCount = 0,
                    avatarTiny = appData.previewThumbnail,
                    avatarInitials = "",
                    avatarUrl = "",
                    participants = emptyList(),
                    lastRead = UnixTimeUtc(0).toInstant(),
                    avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                    admins = setOf(domain)
                )

                if (lastMsg != null) {
                    ChatMessageStream.mapToMessageData(lastMsg, ::resolveDisplayName)?.let {
                        m.updateWithLatestMessage(it, domain)
                    }
                }

                return m
            }

            val isArchived = appData.archivalStatus == ArchivalStatus.Removed
            if (isArchived) {
                val m = ConversationUiModel(
                    id = appData.uniqueId ?: error("Missing uniqueId"),
                    name = "ArchivedConversation",
                    lastMessage = " ",
                    timestamp = conversationFile.fileMetadata.created.toInstant(),
                    unreadCount = 0,
                    avatarTiny = appData.previewThumbnail,
                    avatarInitials = "",
                    avatarUrl = "",
                    participants = emptyList(),
                    lastRead = UnixTimeUtc(0).toInstant(),
                    avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                    admins = setOf(domain)
                )
                if (lastMsg != null) {
                    ChatMessageStream.mapToMessageData(lastMsg, ::resolveDisplayName)?.let {
                        m.updateWithLatestMessage(it, domain)
                    }
                }

                return m
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

            val displayNames =
                participants.map { odinId ->
                    contactService.resolveByOdinId(odinId)?.name ?: odinId.domainName
                }

            val others = participants.filterNot { it == domain }
            val isGroup = others.size > 1
            val title =
                if (isGroup) {
                    conversationData.title ?: displayNames.joinToString(", ")
                } else {
                    val other = participants.first { it != domain }
                    contactService.resolveByOdinId(other)?.name ?: other.domainName
                }

            // only try for groups;
            val admins: Set<OdinId> =
                if (isGroup) {
                    (conversationData.admins ?: listOf(
                        conversationFile.fileMetadata.originalAuthor
                            ?: conversationFile.fileMetadata.senderOdinId
                            ?: domain
                    )).toSet()
                } else {
                    emptySet<OdinId>()
                }

            val avatarModel = buildConversationAvatarModel(conversationFile)

            val ui =
                ConversationUiModel(
                    id = appData.uniqueId ?: error("Missing uniqueId"),
                    name = title,
                    lastMessage = " ",
                    timestamp = conversationFile.fileMetadata.created.toInstant(),
                    unreadCount = 0,
                    avatarTiny = appData.previewThumbnail,
                    avatarInitials = "",
                    avatarUrl = "",
                    participants = participants,
                    lastRead = localAppData?.lastReadTime?.toInstant()
                        ?: UnixTimeUtc(0).toInstant(),
                    avatarModel = avatarModel,
                    admins = admins
                )

            if (lastMsg != null) {
                ChatMessageStream.mapToMessageData(lastMsg, ::resolveDisplayName)?.let {
                    ui.updateWithLatestMessage(it, domain)
                }
            }

            ui

        } catch (t: Throwable) {

            Logger.e(
                throwable = t,
                tag = "ConversationMapper"
            ) { "Failed mapping conversation UI - ${t.message} fileId: ${conversationFile.fileId} | Uid: ${conversationFile.fileMetadata.appData.uniqueId}" }

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
                admins = emptySet()
            )
        }
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
}