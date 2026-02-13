package id.homebase.chat.services

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.reactions.DriveFileReactionProvider
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ReactionContent
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid

class ChatMessageActionService(
    private val conversationService: ConversationService,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val credentialsManager: CredentialsManager,
    private val operationsProvider: DriveFileOperationsProvider,
    private val fileProvider: DriveFileProvider,
    private val dbm: DatabaseManager,
) {
    private val chatDrive = chatTargetDrive.alias

    // -------------------- READ RECEIPTS --------------------

    suspend fun markAsRead(
        messageIds: List<Uuid>
    ) {
        operationsProvider.sendReadReceiptBatch(
            driveId = chatDrive,
            fileIds = fetchFileByUid(messageIds).mapNotNull { d -> d.fileId }
        )
    }

// -------------------- REACTIONS --------------------

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

    private suspend fun getRecipients(conversationId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials();
        val conversation = conversationService.requireConversation(conversationId)
        val recipients = conversation
            .participants.filterNot { odinId -> odinId == credentials.domain }
        return recipients
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

    suspend fun deleteMessage(
        messageId: Uuid,
        deleteForEveryone: Boolean
    ) {
        if (deleteForEveryone) {
            //TODO: need to look up the message and get the recipients
            error("Not implemented yet")
        }
        fileProvider.softDeleteFile(
            driveId = chatDrive,
            fileId = requireFileId(messageId)
        )
    }

    private fun isValidEmoji(input: String?): Boolean =
        !input.isNullOrBlank() && input.length <= 8

//    private fun getReactions(file: Uuid)
//    {
//        reactionProvider.listReactions(chatDrive, fileId)
//    }

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
}
