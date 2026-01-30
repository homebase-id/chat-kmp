package id.homebase.chat.services

import id.homebase.api.client.drives.files.DriveFileReactionProvider
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid

class ChatMessageActionService(
    private val reactionProvider: DriveFileReactionProvider,
    private val operationsProvider: DriveFileOperationsProvider,
    private val fileProvider: DriveFileProvider
) {
    private val chatDrive = chatTargetDrive.alias

    // -------------------- READ RECEIPTS --------------------

    suspend fun markAsRead(
        messageIds: List<Uuid>
    ) {
        operationsProvider.sendReadReceiptBatch(
            driveId = chatDrive,
            fileIds = messageIds
        )
    }

    // -------------------- REACTIONS --------------------

    suspend fun addReaction(
        messageId: Uuid,
        reaction: String
    ) {
        reactionProvider.addReaction(
            driveId = chatDrive,
            fileId = messageId,
            reaction = reaction
        )
    }

    suspend fun deleteReaction(
        messageId: Uuid,
        reaction: String
    ) {
        reactionProvider.deleteReaction(
            driveId = chatDrive,
            fileId = messageId,
            reaction = reaction
        )
    }

    suspend fun removeAllReactions(
        messageId: Uuid
    ) {
        reactionProvider.deleteAllReactions(
            driveId = chatDrive,
            fileId = messageId
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
            fileId = messageId
        )
    }
}
