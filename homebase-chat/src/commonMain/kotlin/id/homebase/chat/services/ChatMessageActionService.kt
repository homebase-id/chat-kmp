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

/** Server-enforced cap on reactions per user per message. The client mirrors
 *  the rule so we don't enqueue a request the server will reject with
 *  UnhandledScenario / "Too many Reactions". */
const val MAX_REACTIONS_PER_USER_PER_MESSAGE = 5

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

        // The MessageUiModel.userDate carried here is the *clamped* value from
        // ChatMessageStream.mapToMessageData (`min(appData.userDate, transitCreated)`).
        // selectAllUnreadCount filters on the *un-clamped* DriveMainIndex.userDate,
        // so capping newReadTime at `viewedRecords.maxOf { userDate }` can leave
        // the badge stuck on a row whose appData.userDate exceeded transitCreated.
        // The conversation's `latestMessageTimestamp` (sourced from the SQL column
        // since `enrichWithLastMessages` was fixed) is authoritative — use it as
        // a floor when it's ahead of the per-file value.
        val viewedMax = viewedRecords.maxOf { it.userDate }
        val convoLatest = participantLookup.getConversationById(conversationId)?.latestMessageTimestamp
        val newReadTime = if (convoLatest != null && convoLatest > viewedMax) convoLatest else viewedMax
        Logger.d(tag = TAG) {
            "newReadTime(ms)=${newReadTime.toEpochMilliseconds()} " +
                    "(viewedMax=${viewedMax.toEpochMilliseconds()} " +
                    "convoLatest=${convoLatest?.toEpochMilliseconds()} " +
                    "viewed=${viewedRecords.size} receipt-eligible=${unreadRecords.size})"
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

    /**
     * Bulk "mark all as read" for an entire conversation. Advances local
     * lastReadTime to the conversation's latest message timestamp.
     *
     * Deliberately does NOT enqueue read receipts — receipts are an
     * "I actually read this" signal, and a bulk-dismiss action shouldn't
     * impersonate that. The advance still propagates to other devices via
     * `localLastReadUpdater.updateLocalLastReadTime` (which writes the
     * conversation file's localAppData and the outbox syncs that).
     *
     * No-op if the conversation isn't in the in-memory list, or if its
     * lastRead is already at or past the latest message.
     */
    suspend fun markAllAsRead(conversationId: Uuid) {
        val convo = participantLookup.getConversationById(conversationId) ?: return
        val newReadTime = convo.latestMessageTimestamp
        if (newReadTime <= convo.lastRead) return

        Logger.d(tag = TAG) {
            "markAllAsRead convo=$conversationId advancing to ms=${newReadTime.toEpochMilliseconds()}"
        }
        dbm.chatReadCount.upsertLastReadTime(conversationId, UnixTimeUtc(newReadTime))
        try {
            localLastReadUpdater.updateLocalLastReadTime(
                conversationId,
                UnixTimeUtc(newReadTime),
            )
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) {
                "markAllAsRead localLastReadUpdater THREW — applyLocalAdvance will NOT run, " +
                        "UI may stay stale; convo=$conversationId"
            }
            throw t
        }
        unreadCountEnricher.applyLocalAdvance(conversationId, newReadTime)

        // Sanity check: after advancing lastRead to the conversation's latest
        // message timestamp, the unread count should be 0. If not, there's a
        // SQL/in-memory clock divergence — e.g. a message whose appData.userDate
        // is greater than what enrichWithLastMessages reported as latestMessage-
        // Timestamp (the in-memory mapper falls back to authorSpecificDate when
        // appData.userDate is null, which can drift from the SQL d.userDate
        // column). Logging it loudly so we can investigate.
        val after = participantLookup.getConversationById(conversationId)
        if (after != null && after.unreadCount > 0) {
            Logger.w(tag = TAG) {
                "markAllAsRead convo=$conversationId left unreadCount=${after.unreadCount} " +
                        "after advancing lastRead to latestMessageTimestamp(ms)=" +
                        "${newReadTime.toEpochMilliseconds()} — likely SQL/in-memory userDate divergence"
            }
        }
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
