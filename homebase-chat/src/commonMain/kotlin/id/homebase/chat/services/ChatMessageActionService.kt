package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.SendReadReceiptByFileIdsOutboxRequest
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
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.chat.services.convo.ConversationParticipantLookup
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.LocalLastReadUpdater
import id.homebase.chat.services.convo.UnreadCountEnricher
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.widget.EmojiReaction
import kotlin.uuid.Uuid

class ChatMessageActionService(
    private val conversationService: ConversationService,
    private val participantLookup: ConversationParticipantLookup,
    private val localLastReadUpdater: LocalLastReadUpdater,
    private val unreadCountEnricher: UnreadCountEnricher,
    private val messageLookup: MessageLookup,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val credentialsManager: CredentialsManager,
    private val fileProvider: DriveFileProvider,
    private val dbm: DatabaseManager,
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {
    private val chatDrive = chatTargetDrive.alias


    suspend fun markAsReadByFiles(conversationId: Uuid, messageIds: List<Uuid>) {

        Logger.d(tag = TAG) { "enter convo=$conversationId messageIds=${messageIds.size}" }

        val batch = messageLookup.getMessages(messageIds)
        val domain = credentialsManager.requireActiveDomain()

        Logger.d(tag = TAG) {
            "lookup matched=${batch.records.size}/${messageIds.size} domain=$domain"
        }

        val unreadRecords = batch.records
            .filter {
                it.localReadTimestamp == null &&
                        !it.isDeleted &&
                        !it.isPendingSend &&
                        !it.isAuthoredBy(domain)
            }

        Logger.d(tag = TAG) {
            val excluded = batch.records.size - unreadRecords.size
            "filter eligible=${unreadRecords.size} excluded=$excluded " +
                    "(excluded reasons: self-authored | already-read | deleted | pending-send)"
        }

        // Local lastReadTime should advance to cover any non-deleted, non-pending message
        // the user just viewed — even self-authored or already-receipted ones (e.g. Note-to-Self
        // has no peer-eligible messages, but the user has clearly "read up to here").
        val viewedRecords = batch.records.filter { !it.isDeleted && !it.isPendingSend }
        if (viewedRecords.isEmpty()) {
            Logger.d(tag = TAG) {
                "no viewed records — early return; convo=$conversationId no outbox row, no local advance"
            }
            return
        }

        val newReadTime = viewedRecords.maxOf { it.userDate }
        Logger.d(tag = TAG) {
            "newReadTime(ms)=${newReadTime.toEpochMilliseconds()} " +
                    "(max userDate over ${viewedRecords.size} viewed records, " +
                    "${unreadRecords.size} receipt-eligible)"
        }

        // Send a read receipt only if there are receipt-eligible records. For
        // Note-to-Self / all-self-authored views, we skip the outbox but still advance local state.
        val enqueued = if (unreadRecords.isNotEmpty()) {
            val fileIds = unreadRecords.map { it.fileId }
            val ok = outboxSync.tryEnqueue(
                request = SendReadReceiptByFileIdsOutboxRequest(
                    driveId = chatDrive,
                    fileIds = fileIds,
                )
            )
            Logger.d(tag = TAG) {
                "enqueue receipt: enqueued=$ok drive=$chatDrive fileIdsCount=${fileIds.size}"
            }
            ok
        } else {
            Logger.d(tag = TAG) {
                "no receipt-eligible records — skipping outbox; advancing local read state only"
            }
            true
        }

        if (!enqueued) {
            Logger.w(tag = TAG) {
                "outbox.tryEnqueue returned false — skipped DB upsert + enrich; convo=$conversationId"
            }
            return
        }

        // Gate the local-state work on the in-memory lastRead. The conversation
        // file's appdata.lastReadTime (mirrored as ConversationUiModel.lastRead)
        // is the source of truth here; ChatReadCount is just its SQL-queryable
        // index. Re-entering the same conversation typically lands here with
        // newReadTime == priorLastRead — skipping spares us a SQL upsert, an
        // appdata round-trip, and a COUNT-based enrich on every visit.
        val priorLastRead = participantLookup.getConversationById(conversationId)?.lastRead
        if (priorLastRead != null && newReadTime <= priorLastRead) {
            Logger.d(tag = TAG) {
                "newReadTime(ms)=${newReadTime.toEpochMilliseconds()} <= priorLastRead(ms)=${priorLastRead.toEpochMilliseconds()} — skipping upsert + enrich; convo=$conversationId"
            }
            return
        }

        // Optimistic local upsert — the read-receipt send is fire-and-forget via the outbox,
        // so we can't gate this on a per-file server status. Local read state reflects what
        // the user read locally; the outbox retries server-side receipt delivery independently.
        dbm.chatReadCount.upsertLastReadTime(conversationId, UnixTimeUtc(newReadTime))

        try {
            localLastReadUpdater.updateLocalLastReadTime(
                conversationId,
                UnixTimeUtc(newReadTime)
            )
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) {
                "localLastReadUpdater THREW — unreadCountEnricher will NOT run, UI may stay stale"
            }
            throw t
        }

        // Synchronously patch in-memory lastRead + unreadCount so the UI
        // updates without waiting for the BatchReceived round-trip.
        unreadCountEnricher.applyLocalAdvance(conversationId, newReadTime)
    }

    private companion object {
        const val TAG = "MarkAsRead"
    }

    suspend fun toggleReaction(conversationId: Uuid, messageId: Uuid, emoji: String):
            ToggleReactionResult {
        if (!isValidEmoji(emoji)) return ToggleReactionResult(
            resultType = ToggleReactionResultType.None
        )

        val reactionJson = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))
        val fileId = requireFileId(messageId)

        val (resultType, original) = optimisticWriter.writeReactionToggle(
            chatDrive,
            messageId,
            reactionJson
        )

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
                try {
                    optimisticWriter.rollbackWrite(chatDrive, original)
                } catch (_: Exception) {
                }
            }
        }

        return ToggleReactionResult(resultType = resultType)
    }

    // -------------------- DELETE --------------------

    suspend fun deleteMessage(
        messageId: Uuid,
        deleteForEveryone: Boolean
    ) {
        val msg = messageLookup.getMessage(messageId) ?: return
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
                try {
                    optimisticWriter.rollbackWrite(chatDrive, original)
                } catch (_: Exception) {
                }
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
