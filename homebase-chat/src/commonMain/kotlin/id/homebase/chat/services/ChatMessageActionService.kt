package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.SendReadReceiptByTimeOutboxRequest
import id.homebase.api.client.drives.files.SendReadReceiptResultStatus
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionResult
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.outbox.OptimisticWriter
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
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun markAsReadLatestFileCreated(conversationId: Uuid, messageIds: List<Uuid>) {

        val batch = chatMessageStream.getMessages(messageIds)
        val domain = credentialsManager.requireActiveDomain()
        val newReadTime = UnixTimeUtc.now().addMilliseconds(1)

        val isSelfConversation = conversationId == ChatProtocol.ConversationWithYourselfId
        val unreadRecords = batch.records
            .filter {
                it.localReadTimestamp == null &&
                        !it.isDeleted &&
                        !it.isPendingSend &&
                        (isSelfConversation || !it.isAuthoredBy(domain))
            }

        if (unreadRecords.isEmpty()) {
            dbm.chatReadCount.upsertLastReadTime(conversationId, newReadTime)
            conversationStream.updateUnreadCounts()
            return
        }

        // Use server-side 'created' timestamp for the read receipt endTime.
        // The server matches against 'created', not the client-side 'userDate'.
        val endTime = unreadRecords.maxOf { it.created }

        dbm.chatReadCount.upsertLastReadTime(conversationId, newReadTime)
        conversationStream.updateUnreadCounts()

        if (!isSelfConversation) {
            outboxSync.tryEnqueue(
                request = SendReadReceiptByTimeOutboxRequest(
                    driveId = chatDrive,
                    fileType = ChatProtocol.MessageFileType,
                    dataType = 0,
                    groupId = conversationId,
                    endTime = UnixTimeUtc(endTime.toEpochMilliseconds()).addMilliseconds(1)
                )
            )
        }
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

    suspend fun toggleReaction(conversationId: Uuid, messageId: Uuid, emoji: String):
            ToggleReactionResult {
        if (!isValidEmoji(emoji)) return ToggleReactionResult(
            resultType = ToggleReactionResultType.None
        )

        val reactionJson = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))
        val fileId = requireFileId(messageId)

        val (resultType, original) = optimisticWriter.writeReactionToggle(chatDrive, messageId, reactionJson)

        try {
            val enqueued = outboxSync.tryEnqueue(
                request = ToggleReactionOutboxRequest(
                    driveId = chatDrive,
                    fileId = fileId,
                    reaction = reactionJson,
                    recipients = getRecipients(conversationId),
                )
            )
            if (!enqueued && original != null) {
                optimisticWriter.rollbackWrite(chatDrive, original)
            }
        } catch (t: Throwable) {
            Logger.e("toggleReaction failed to enqueue", t)
            if (original != null) {
                try { optimisticWriter.rollbackWrite(chatDrive, original) } catch (_: Exception) {}
            }
        }

        return ToggleReactionResult(resultType = resultType)
    }

    // -------------------- DELETE --------------------

    suspend fun deleteMessage(
        messageId: Uuid,
        deleteForEveryone: Boolean
    ) {
        val msg = chatMessageStream.getMessage(messageId) ?: return
        val conversation = conversationService.getConversation(msg.conversationId) ?: return
        val fileId = requireFileId(messageId)

        // Soft-delete only for now — propagates to other clients via sync.
        // Hard-delete will be added as a second phase (user invokes delete again on soft-deleted msg).
        val hardDelete = false
        val recipients: List<OdinId>? = if (!hardDelete && deleteForEveryone) {
            val domain = credentialsManager.requireActiveCredentials().domain
            conversation.participants.filter { it != domain }
        } else {
            null
        }

        val original = optimisticWriter.writeDelete(chatDrive, messageId)

        try {
            val enqueued = outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = chatDrive,
                    fileIds = listOf(fileId),
                    recipients = recipients,
                    hardDelete = hardDelete,
                )
            )
            if (!enqueued && original != null) {
                optimisticWriter.rollbackWrite(chatDrive, original)
            }
        } catch (t: Throwable) {
            Logger.e("deleteMessage failed to enqueue", t)
            if (original != null) {
                try { optimisticWriter.rollbackWrite(chatDrive, original) } catch (_: Exception) {}
            }
        }
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
