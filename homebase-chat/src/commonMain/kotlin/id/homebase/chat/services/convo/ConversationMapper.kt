package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.toBase64
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
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
    private val dbm: DatabaseManager,
) {

    private val logger = Logger.withTag("ConversationMapper")
    private val chatDrive = chatTargetDrive.alias

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

            val conversationId = appData.uniqueId ?: error("Missing uniqueId")

            val isDeleted = conversationFile.fileState == FileState.Deleted
                    || appData.archivalStatus == ArchivalStatus.Removed

            if (isDeleted) {
                return mapDeletedConversation(conversationFile, lastMsg, domain)
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

            val isGroup = appData.tags?.contains(ChatProtocol.ConversationGroupTag) == true
            val isLegacyGroup = !isGroup && participants.size > 2
            val isAnyGroup = isGroup || isLegacyGroup
            val displayNames = participants.map { it.domainName }

            val title =
                if (conversationId == ChatProtocol.ConversationWithYourselfId) {
                    "" // Display name resolved via string resource at UI layer
                } else if (isAnyGroup) {
                    conversationData.title?.takeIf { it.isNotBlank() } ?: displayNames.joinToString(", ")
                } else {
                    val other = participants.first { it != domain }
                    other.domainName
                }

            val admins: Set<OdinId> =
                if (isAnyGroup) {
                    queryAdmins(conversationId)
                    // Backward compat: fall back to conversation content, then originalAuthor
                        ?: (conversationData.adminData?.admins?.toSet())
                        ?: setOf(
                            metadata.originalAuthor
                                ?: metadata.senderOdinId
                                ?: domain
                        )
                } else {
                    emptySet()
                }

            val avatarModel = buildConversationAvatarModel(
                conversationFile,
                participants,
                domain,
                conversationId
            )

            val localTags = metadata.localAppData?.tags ?: emptyList()
            val isArchivedByTag = localTags.contains(ChatProtocol.ConversationArchivedTag)
            val isLeftByTag = localTags.contains(ChatProtocol.ConversationLeftTag)
            val isPinnedByTag = localTags.contains(ChatProtocol.ConversationPinnedTag)

            val conversationState = when {
                isLeftByTag && participants.contains(domain) -> ConversationState.RejoinPending
                isLeftByTag -> ConversationState.Left
                isAnyGroup && !participants.contains(domain) -> ConversationState.Removed
                isArchivedByTag -> ConversationState.Archived
                else -> ConversationState.Active
            }

            val exitedAt = if (conversationState == ConversationState.Left
                || conversationState == ConversationState.Removed
            ) {
                localAppData?.lastExitedAt?.toInstant()
            } else null

            var ui =
                ConversationUiModel(
                    id = conversationId,
                    name = title,
                    lastMessage = " ",
                    latestMessageTimestamp = UnixTimeUtc.ZeroTime.toInstant(),
                    unreadCount = 0,
                    avatarTiny = appData.previewThumbnail,
                    avatarInitials = "",
                    avatarUrl = "",
                    participants = participants,
                    isPinned = isPinnedByTag,
                    lastRead = localAppData?.lastReadTime?.toInstant()
                        ?: UnixTimeUtc(0).toInstant(),
                    avatarModel = avatarModel,
                    admins = admins,
                    conversationState = conversationState,
                    isGroup = isGroup,
                    isLegacyGroup = isLegacyGroup,
                    exitedAt = exitedAt
                )

            if (lastMsg != null) {
                ChatMessageStream.mapToMessageData(lastMsg, credentialsManager) { homebaseFile ->
                    homebaseFile.fileMetadata.originalAuthor?.domainName ?: ""

                }?.let {
                    ui = ui.updateWithLatestMessage(it, domain)
                }
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
                latestMessageTimestamp = UnixTimeUtc(0).toInstant(),
                unreadCount = 0,
                avatarInitials = "",
                avatarUrl = "",
                avatarTiny = null,
                participants = emptyList(),
                lastRead = UnixTimeUtc(0).toInstant(),
                avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                admins = emptySet(),
                conversationState = ConversationState.Invalid,
                isGroup = false
            )
        }
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
            latestMessageTimestamp = metadata.created.toInstant(),
            unreadCount = 0,
            avatarTiny = appData.previewThumbnail,
            avatarInitials = "",
            avatarUrl = "",
            participants = emptyList(),
            lastRead = UnixTimeUtc(0).toInstant(),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = setOf(domain),
            conversationState = ConversationState.Deleted,
            isGroup = appData.tags?.contains(ChatProtocol.ConversationGroupTag) == true
        )

        if (lastMsg != null) {
            ChatMessageStream.mapToMessageData(lastMsg, credentialsManager) {
                it.fileMetadata.originalAuthor?.domainName ?: ""
            }?.let {
                m.updateWithLatestMessage(it, domain)
            }
        }

        return m
    }

    private suspend fun queryAdmins(conversationId: Uuid): Set<OdinId>? {
        val c = credentialsManager.requireActiveCredentials()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

        val file = dbm.driveMainIndex.selectHomebaseFileByUnique(
            c.getIdentityId(), chatDrive, adminUniqueId
        ) ?: return null
        val content = file.fileMetadata.appData.content
        if (content.isNullOrEmpty()) return null

        return try {
            val adminInfo = OdinSystemSerializer.deserialize<ConversationAdminInfo>(content)
            adminInfo.admins?.toSet()
        } catch (e: Exception) {
            logger.w("Failed to deserialize admin file for $conversationId: ${e.message}")
            null
        }
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