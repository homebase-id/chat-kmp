package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
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
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.chat.services.convo.ConversationParticipantLookup
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.LocalLastReadUpdater
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
    private val messageLookup: MessageLookup,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val credentialsManager: CredentialsManager,
    private val fileProvider: DriveFileProvider,
    private val dbm: DatabaseManager,
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {
    private val chatDrive = chatTargetDrive.alias


    /**
     * Mark a set of [messages] as read in [conversationId]. The caller (the
     * scroll-visibility watcher in `ConversationMessagesPane`, or the
     * per-message swipe) is the *only* place that triggers this — and at the
     * point it fires, those exact `MessageUiModel`s are already loaded in the
     * conversation's in-memory window. So we use them directly: no DB
     * round-trip to re-fetch what we just rendered. The model already carries
     * every field this function reads (`localReadTimestamp`, `isDeleted`,
     * `isPendingSend`, `fileId`, `userDate`, `isAuthoredBy(domain)`).
     *
     * The bulk "mark every message in the conversation" path is a different
     * action that routes to [markAllAsRead]; this function is only the
     * per-message variant.
     */
    suspend fun markAsReadByFiles(conversationId: Uuid, messages: List<MessageUiModel>) {

        // Empty list is a no-op — don't bother with the log spam or the
        // suppressed-advance arc below. Defensive: the visibility watcher
        // already filters out a pure-empty emit upstream, but a caller could
        // pass an empty list (e.g. all-self-authored visible set collapsed
        // before reaching here in a future caller), and we want a quiet exit.
        if (messages.isEmpty()) return

        Logger.d(tag = TAG) { "enter convo=$conversationId messages=${messages.size}" }

        val domain = credentialsManager.requireActiveDomain()

        val unreadRecords = messages
            .filter {
                it.localReadTimestamp == null &&
                        !it.isDeleted &&
                        !it.isPendingSend &&
                        !it.isAuthoredBy(domain)
            }

        Logger.d(tag = TAG) {
            val excluded = messages.size - unreadRecords.size
            "filter eligible=${unreadRecords.size} excluded=$excluded " +
                    "(excluded reasons: self-authored | already-read | deleted | pending-send)"
        }

        // Local lastReadTime should advance to cover any non-deleted, non-pending message
        // the user just viewed — even self-authored or already-receipted ones (e.g. Note-to-Self
        // has no peer-eligible messages, but the user has clearly "read up to here").
        val viewedRecords = messages.filter { !it.isDeleted && !it.isPendingSend }
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
            val result = outboxSync.tryEnqueue(
                request = SendReadReceiptByFileIdsOutboxRequest(
                    driveId = chatDrive,
                    fileIds = fileIds,
                )
            )
            Logger.d(tag = TAG) {
                "enqueue receipt: result=$result drive=$chatDrive fileIdsCount=${fileIds.size}"
            }
            if (!result.enqueued) {
                Logger.w(tag = TAG) {
                    "outbox.tryEnqueue → $result — skipped DB upsert + enrich; convo=$conversationId"
                }
            }
            result.enqueued
        } else {
            Logger.d(tag = TAG) {
                "no receipt-eligible records — skipping outbox; advancing local read state only"
            }
            true
        }

        if (!enqueued) {
            return
        }

        // Hot-path short-circuit. The entity-owned gate
        // [ConversationUiModel.resolveLastReadAdvance] runs again inside
        // the setter, but checking here spares us a SQL upsert, the
        // appdata round-trip, and the COUNT-based enrich on every re-entry
        // into a fully-read conversation.
        val convo = participantLookup.getConversationById(conversationId)
        if (convo != null && convo.resolveLastReadAdvance(newReadTime) == null) {
            Logger.d(tag = TAG) {
                "convo=$conversationId resolveLastReadAdvance suppressed " +
                    "(currentMs=${convo.lastRead.toEpochMilliseconds()} " +
                    "latestMs=${convo.latestMessageTimestamp.toEpochMilliseconds()} " +
                    "newMs=${newReadTime.toEpochMilliseconds()}) — skipping upsert + enrich"
            }
            return
        }

        // The setter owns the only-increases rule, the ChatReadCount upsert,
        // the in-memory advance (lastRead + dirty + unreadCount), AND the
        // debounced outbox enqueue. The read-receipt send above is
        // fire-and-forget; local read state reflects what the user read
        // locally regardless of receipt delivery.
        localLastReadUpdater.updateLocalLastReadTime(
            conversationId,
            UnixTimeUtc(newReadTime)
        )
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
        val newReadTime = convo.resolveLastReadAdvance(convo.latestMessageTimestamp) ?: return

        Logger.d(tag = TAG) {
            "markAllAsRead convo=$conversationId advancing to ms=${newReadTime.toEpochMilliseconds()}"
        }
        // The setter advances the in-memory model (lastRead + dirty + unread)
        // as well as persisting and scheduling the writeback.
        localLastReadUpdater.updateLocalLastReadTime(
            conversationId,
            UnixTimeUtc(newReadTime),
        )

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

        // Run the optimistic write first so the bubble updates immediately.
        // Previously a `requireFileId(messageId)` call ran ahead of this and
        // did a QueryBatch(1000, NewestFirst) over the whole chat drive just
        // to look up the fileId for the outbox payload — easily ~500 ms on a
        // busy drive, all of it spent gating the optimistic update behind
        // outbox bookkeeping. The optimistic writer already does its own
        // selectHomebaseFileByUnique and hands the original file (which
        // carries fileId) back to us; reuse that for the outbox row.
        val (resultType, original) = optimisticWriter.writeReactionToggle(
            chatDrive,
            messageId,
            reactionJson
        )
        if (original == null) return ToggleReactionResult(resultType = resultType)

        try {
            val result = outboxSync.tryEnqueue(
                request = ToggleReactionOutboxRequest(
                    driveId = chatDrive,
                    fileId = original.fileId,
                    reaction = reactionJson,
                    recipients = getRecipients(conversationId),
                )
            )
            if (!result.enqueued) {
                Logger.w("toggleReaction: outbox enqueue → $result — rolling back optimistic write")
                optimisticWriter.rollbackWrite(chatDrive, original)
            }
        } catch (t: Throwable) {
            Logger.e("toggleReaction failed to enqueue", t)
            try {
                optimisticWriter.rollbackWrite(chatDrive, original)
            } catch (_: Exception) {
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

        // What's queued for this message decides whether the server even knows
        // about it. A pending *create* (UploadNewFile) means it never reached the
        // server; a pending *edit* (UpdateFile) means it did. We can't use
        // msg.isPendingSend for this — an edit re-stamps isPendingSendTag, so a
        // sent-then-edited message looks "pending" yet still needs a server delete.
        val pendingUploadType =
            dbm.outbox.selectByDriveAndUnique(chatDrive, messageId)?.uploadType

        if (pendingUploadType == DriveOutboxUploader.UploadNewFile) {
            // Never reached the server: cancel the queued send and drop it
            // locally. No server delete — recipients never received it, and there
            // is no remote file to remove. (Narrow race: if the send confirms
            // between the lookup and here, removeOptimisticFile self-guards on
            // isPendingSendTag and no-ops; a second delete then takes the path
            // below.)
            optimisticWriter.removeOptimisticFile(chatDrive, messageId)
            return
        }

        val conversation = conversationService.getConversation(msg.conversationId) ?: return
        // msg.fileId is already populated by messageLookup.getMessage above —
        // an extra requireFileId(messageId) here would issue a second
        // selectHomebaseFileByUnique for the exact same row.
        val fileId = msg.fileId

        // Soft-delete only for now — propagates to other clients via sync.
        // Hard-delete will be added as a second phase (user invokes delete again on soft-deleted msg).
        val hardDelete = false
        val recipients: List<OdinId>? = if (!hardDelete && deleteForEveryone) {
            val domain = credentialsManager.requireActiveCredentials().domain
            conversation.participants.filter { it != domain }
        } else {
            null
        }

        // Cancel any queued *edit* before deleting — its content is moot, and
        // leaving it would race the delete against the server. No-op when nothing
        // is queued (a normally-sent message).
        dbm.outbox.deleteBy(chatDrive, messageId)

        val original = optimisticWriter.writeDelete(chatDrive, messageId)

        try {
            val result = outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = chatDrive,
                    fileIds = listOf(fileId),
                    recipients = recipients,
                    hardDelete = hardDelete,
                )
            )
            if (!result.enqueued && original != null) {
                Logger.w("deleteMessage: outbox enqueue → $result — rolling back optimistic write")
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
        // In-memory tier first: if the message's conversation is currently
        // open and loaded, the MessageUiModel already carries fileId — no
        // DB round-trip needed. Hit rate is effectively 100% for the
        // remaining caller (getReactions on a visible message).
        messageLookup.findCachedFileId(messageId)?.let { return it }

        // Cache miss → indexed primary-key lookup.
        val credentials = credentialsManager.requireActiveCredentials()
        val file = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), chatDrive, messageId
        ) ?: throw Exception("invalid message id $messageId")
        return file.fileId
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
