package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ReactionContent
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.widget.EmojiReaction
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

class ChatMessageActionService(
    private val conversationService: ConversationService,
    private val chatMessageStream: ChatMessageStream,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val credentialsManager: CredentialsManager,
    private val operationsProvider: DriveFileOperationsProvider,
    private val fileProvider: DriveFileProvider,
    private val outboxSync: OutboxSync,
    private val dbm: DatabaseManager,
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun markAsRead(
        messageIds: List<Uuid>
    ) {
        operationsProvider.sendReadReceiptBatch(
            driveId = chatDrive,
            fileIds = fetchFileByUid(messageIds).mapNotNull { d -> d.fileId }
        )
    }

    suspend fun addReaction(
        conversationId: Uuid,
        messageId: Uuid,
        emoji: String
    ) {
        if (!isValidEmoji(emoji)) return

        val content = ReactionContent(emoji = emoji)
        reactionProvider.addReaction(
            driveId = chatDrive,
            fileId = requireFileId(messageId),
            reaction = OdinSystemSerializer.serialize(content),
            recipients = getRecipients(conversationId)
        )
    }

    suspend fun deleteReaction(
        conversationId: Uuid,
        messageId: Uuid,
        emoji: String
    ) {
        if (!isValidEmoji(emoji)) return

        val content = ReactionContent(emoji = emoji)

        reactionProvider.deleteReaction(
            driveId = chatDrive,
            fileId = requireFileId(messageId),
            reaction = OdinSystemSerializer.serialize(content),
            recipients = getRecipients(conversationId)
        )
    }

// -------------------- DELETE --------------------

    suspend fun deleteMessageProper(
        messageId: Uuid,
        deleteForEveryone: Boolean
    ) {
        val msg = chatMessageStream.getMessage(messageId) ?: return
        val conversation = conversationService.getConversation(msg.conversationId) ?: return
        val fileId = requireFileId(messageId)

        if (conversation.isWithSelf) {
            fileProvider.hardDeleteFile(chatDrive, fileId)
            return
        }

        val recipients = if (deleteForEveryone) {
            val domain = credentialsManager.requireActiveCredentials().domain
            conversation.participants.filter { it != domain }
        } else {
            emptyList()
        }

        fileProvider.softDeleteFile(
            driveId = chatDrive,
            fileId = fileId,
            recipients = recipients
        )
    }

    suspend fun deleteMessageClassic(
        messageId: Uuid,
        deleteForEveryone: Boolean
    ) {
        val msg = chatMessageStream.getMessage(messageId)
            ?: throw IllegalArgumentException("message not found")

        val conversation = conversationService.getConversation(msg.conversationId)
            ?: return

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = msg.keyHeader.aesKey
        )

        if (conversation.isWithSelf) {
            val fileId = requireFileId(messageId)
            fileProvider.hardDeleteFile(chatDrive, fileId)
            return
        }

        val recipients = if (deleteForEveryone) {
            val domain = credentialsManager.requireActiveCredentials().domain
            conversation.participants.filter { it != domain }
        } else {
            emptyList()
        }

        val msgContent = msg.messageAppData.copy(
            message = JsonPrimitive("")
        )

        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = msg.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = messageId.toString(),
                groupId = msg.conversationId.toString(),
                fileType = ChatProtocol.MessageFileType,
                userDate = UnixTimeUtc.now().milliseconds,
                content = OdinSystemSerializer.serialize(msgContent),
                previewThumbnail = msg.previewThumbnail,
                archivalStatus = ArchivalStatus.Archived
            ),
//            accessControlList =
        )

        val manifest =
            UpdateManifest.build(
                payloads = null,
                toDeletePayloads = msg.payloads?.map { PayloadDeleteKey(it.key) },
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

            return;

        } catch (t: Throwable) {
            Logger.e("ChatMessageActionService", t)
        }

        error("Failed to delete chat message")
    }

    suspend fun getReactions(messageId: Uuid): List<EmojiReaction> {
        val fileId = requireFileId(messageId)
        val response = reactionProvider.listReactions(chatDrive, fileId)

        return response.reactions.map {
            EmojiReaction(
                messageId = messageId,
                odinId = it.odinId,
                created = UnixTimeUtc(it.created),
                emoji = OdinSystemSerializer.deserialize<ReactionContent>(it.reactionContent).emoji
            )
        }
    }

    suspend fun requireFileId(messageId: Uuid): Uuid {
        val d = fetchFileByUid(listOf(messageId)).firstOrNull()
            ?: throw Exception("invalid message id")
        return d.fileId
    }

    suspend fun fetchFileByUid(uidList: List<Uuid>): List<HomebaseFile> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = 1000,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                uniqueIdAnyOf = uidList
            )

        return result.records
    }

    private fun isValidEmoji(input: String?): Boolean =
        !input.isNullOrBlank() && input.length <= 8

    private suspend fun getRecipients(conversationId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials();
        val conversation = conversationService.requireConversation(conversationId)
        val recipients = conversation
            .participants.filterNot { odinId -> odinId == credentials.domain }
        return recipients
    }

}
