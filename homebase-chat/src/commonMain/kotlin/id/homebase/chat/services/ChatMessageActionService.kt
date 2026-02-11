package id.homebase.chat.services

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileReactionProvider
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ReactionContent
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid

class ChatMessageActionService(
    private val reactionProvider: DriveFileReactionProvider,
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
        messageId: Uuid,
        emoji: String
    ) {
        if (!isValidEmoji(emoji)) return

        val content = ReactionContent(emoji = emoji)
        reactionProvider.addReaction(
            driveId = chatDrive,
            fileId = requireFileId(messageId),
            reaction = OdinSystemSerializer.serialize(content)
        )
    }

    suspend fun deleteReaction(
        messageId: Uuid,
        emoji: String
    ) {
        if (!isValidEmoji(emoji)) return

        val content = ReactionContent(emoji = emoji)

        reactionProvider.deleteReaction(
            driveId = chatDrive,
            fileId = requireFileId(messageId),
            reaction = OdinSystemSerializer.serialize(content)
        )
    }

    suspend fun removeAllReactions(
        messageId: Uuid
    ) {
        reactionProvider.deleteAllReactions(
            driveId = chatDrive,
            fileId = requireFileId(messageId)
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
