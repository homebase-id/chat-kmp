package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.SendReadReceiptResultStatus
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ToggleReactionResult
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ReactionContent
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.widget.EmojiReaction
import kotlin.uuid.Uuid

class ChatMessageActionService(
    private val conversationService: ConversationService,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val credentialsManager: CredentialsManager,
    private val operationsProvider: DriveFileOperationsProvider,
    private val fileProvider: DriveFileProvider,
    private val dbm: DatabaseManager,
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun markAsReadLatestFileCreated(conversationId: Uuid, messageIds: List<Uuid>) {

        Logger.d { "Attempting mark-as-read for messageIds: ${messageIds.size}" }

        val batch = chatMessageStream.getMessages(messageIds)
        val domain = credentialsManager.requireActiveDomain()
        val newReadTime = UnixTimeUtc.now().addMilliseconds(1)

        Logger.d { "Attempting mark-as-read for batch count: ${batch.records.size}" }

        val unreadRecords = batch.records
            .filter {
                it.localReadTimestamp == null &&
                        !it.isDeleted &&
                        !it.isPendingSend
                         && !it.isAuthoredBy(domain)
            }

        if (unreadRecords.isEmpty()) {

            // even if there are no unread record not sent by me
            // lets see if there
            dbm.chatReadCount.upsertLastReadTime(conversationId, newReadTime)
            conversationStream.updateUnreadCounts()

            return
        }


        Logger.d { "Calling mark-as-read for unread-records count: ${unreadRecords.size}" }

        //TODO no need to group
        unreadRecords
            .groupBy { it.conversationId }
            .forEach { (conversationId, records) ->

                val endTime =
                    records.maxOf { it.created }

                operationsProvider.sendReadReceiptBatch(
                    driveId = chatDrive,
                    fileType = ChatProtocol.MessageFileType,
                    dataType = 0,
                    groupId = conversationId,
                    endTime = UnixTimeUtc(endTime.toEpochMilliseconds()).addMilliseconds(1) //add a millisecond to include the most recent file
                )

                Logger.d { "Upserting chatReadCount->lastReadTime: ${conversationId}" }

                dbm.chatReadCount.upsertLastReadTime(conversationId, newReadTime)
            }

        conversationStream.updateUnreadCounts()
    }

    suspend fun markAsReadByFiles(messageIds: List<Uuid>) {

        Logger.d { "Attempting mark-as-read for messageIds: ${messageIds.size}" }

        val batch = chatMessageStream.getMessages(messageIds)
        val domain = credentialsManager.requireActiveDomain()

        Logger.d { "Attempting mark-as-read for batch count: ${batch.records.size}" }

        val unreadRecords = batch.records
            .filter {
                it.localReadTimestamp == null &&
                        !it.isDeleted &&
                        !it.isPendingSend &&
                        !it.isAuthoredBy(domain)
            }

        val newReadTime = UnixTimeUtc.now().addMilliseconds(1)

        Logger.d { "Calling mark-as-read for unread-records count: ${unreadRecords.size}" }

        unreadRecords
            .map { it.fileId }
            .chunked(50)
            .forEach { chunk ->

                val result = operationsProvider.sendReadReceiptBatch(
                    driveId = chatDrive,
                    fileIds = chunk
                )


                val successfulFileIds = result.results
                    .filter { file ->
                        file.status.any { it.status == SendReadReceiptResultStatus.Enqueued }
                    }
                    .map { it.fileId }
                    .toSet()

                unreadRecords
                    .filter { it.fileId in successfulFileIds }
                    .distinctBy { it.conversationId }
                    .forEach {

                        Logger.d { "Upserting chatReadCount->lastReadTime: count: ${it.conversationId}" }

                        dbm.chatReadCount.upsertLastReadTime(it.conversationId, newReadTime)
                    }

                conversationStream.updateUnreadCounts()
            }
    }

    suspend fun addReaction(conversationId: Uuid, messageId: Uuid, emoji: String) {
        if (!isValidEmoji(emoji)) return

        val content = ReactionContent(emoji = emoji)
        reactionProvider.addReaction(
            driveId = chatDrive,
            fileId = requireFileId(messageId),
            reaction = OdinSystemSerializer.serialize(content),
            recipients = getRecipients(conversationId)
        )
    }

    suspend fun toggleReaction(conversationId: Uuid, messageId: Uuid, emoji: String):
            ToggleReactionResult {
        if (!isValidEmoji(emoji)) return ToggleReactionResult(
            resultType = ToggleReactionResultType.None
        )

        val content = ReactionContent(emoji = emoji)

        return reactionProvider.toggleReaction(
            driveId = chatDrive,
            fileId = requireFileId(messageId),
            reaction = OdinSystemSerializer.serialize(content),
            recipients = getRecipients(conversationId)
        )
    }

    suspend fun deleteReaction(conversationId: Uuid, messageId: Uuid, emoji: String) {
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

        fileProvider.softDeleteFile(driveId = chatDrive, fileId = fileId, recipients = recipients)
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
        val d =
            fetchFileByUid(listOf(messageId)).firstOrNull() ?: throw Exception("invalid message id")
        return d.fileId
    }

    suspend fun fetchFileByUid(uidList: List<Uuid>): List<HomebaseFile> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result = queryBatch.queryBatchAsync(
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

    private fun isValidEmoji(input: String?): Boolean = !input.isNullOrBlank() && input.length <= 8

    private suspend fun getRecipients(conversationId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials()
        val conversation = conversationService.requireConversation(conversationId)
        val recipients =
            conversation.participants.filterNot { odinId -> odinId == credentials.domain }
        return recipients
    }

    suspend fun getPayloadBytes(
        fileId: Uuid, payloadKey: String, keyHeader: KeyHeader
    ): ByteArray? {
        val response = fileProvider.getPayloadBytesDecrypted(
            chatTargetDrive.alias, fileId, payloadKey, keyHeader
        )
        return response?.bytes
    }
}
