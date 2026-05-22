package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class ConversationWithLastMessage(
    val conversation: HomebaseFile,
    val message: HomebaseFile?,
    /**
     * Authoritative `DriveMainIndex.userDate` (epoch ms) of the last message,
     * straight from the SQL column. Null when there's no last message yet.
     *
     * Carried separately from `message` because `ChatMessageStream.mapToMessageData`
     * clamps the in-memory `MessageUiModel.userDate` to `metadata.transitCreated`
     * for display safety, which can drop below the SQL value when peers ship
     * messages with a later `appData.userDate`. Read-bookkeeping (lastRead /
     * unread-counter) compares against the SQL column, so callers must use
     * this field, not `message.fileMetadata.appData.userDate` or the mapped
     * `MessageUiModel.userDate`.
     */
    val msgUserDateMs: Long?,
)

data class ConversationUnreadCount(
    val conversationId: Uuid,
    val unreadCount: Long,
    /** Stored ChatReadCount.lastReadTime (epoch ms), or null when no row exists yet. */
    val lastReadTime: Long?,
)

/**
 * A conversation that has live messages in the local index but no conversation
 * header (fileType=8888) — see `ChatReadCount.sq:selectOrphanedAtRestConversations`.
 */
data class OrphanedAtRestConversation(
    val conversationId: Uuid,
    val messageCount: Long,
    /**
     * Most recent non-self message author for this group, or null when every
     * message is self-authored (counterparty unrecoverable — would become a
     * "1:1 repair" group). Used to rebuild a real 1:1 on recovery.
     */
    val counterpartyAuthor: String?,
)

/**
 * Wrapper for ChatReadCount database operations following the DriveMainIndexWrapper pattern
 * Handles conversion between jsonHeader -> HomebaseFile -> Message/Conversation
 */
class ChatReadCountWrapper(
    private val driver: SqlDriver,
    private val chatReadCountAdapter: ChatReadCount.Adapter,
    private val driveMainIndexAdapter: DriveMainIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = ChatReadCountQueries(
        driver,
        driveMainIndexAdapter,
        chatReadCountAdapter
    )

    /**
     * Select all conversations (fileType 8888) from DriveMainIndex
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    fun selectAllConversations(identityId: Uuid): List<HomebaseFile> {
        val list = delegate.selectAllCoversations(identityId).executeAsList()
        return list.mapNotNull {
            try {
                OdinSystemSerializer.deserialize<HomebaseFile>(it)
            } catch (e: Exception) {
                logger.e(e) { "Skipping corrupt conversation row: ${it.take(200)}" }
                null
            }
        }
    }

    /**
     * Groups that have live messages but no conversation header — the orphaned-at-rest
     * set. Cheap (index-backed, empty in the normal case); intended to run once per cold
     * start after the chat drive's initial sync settles.
     */
    fun selectOrphanedAtRestConversations(
        identityId: Uuid,
        selfDomain: String,
    ): List<OrphanedAtRestConversation> {
        return delegate.selectOrphanedAtRestConversations(identityId = identityId, selfDomain = selfDomain)
            .executeAsList()
            .map {
                OrphanedAtRestConversation(
                    conversationId = it.groupId,
                    messageCount = it.messageCount,
                    counterpartyAuthor = it.counterpartyAuthor,
                )
            }
    }

    private val logger = Logger.withTag("ConversationQueries")

    /**
     * Select all conversations with their last message
     * Returns ConversationWithLastMessage objects
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */

    fun selectAllConversationPlusLastMessage(identityId: Uuid): List<ConversationWithLastMessage> {

        val start = Clock.System.now().toEpochMilliseconds()

        val list = delegate.selectAllConversationPlusLastMessage(identityId).executeAsList()

        logger.d { "Fetched rows=${list.size} in ${Clock.System.now().toEpochMilliseconds() - start}ms" }

        val result = list.mapIndexed { index, it ->

            try {
//                logger.d {
//                    "Mapping row[$index] | convSize=${it.convJsonHeader.length} " +
//                            "hasMsg=${it.msgJsonHeader != null}"
//                }

                val conversation =
                    OdinSystemSerializer.deserialize<HomebaseFile>(it.convJsonHeader)

                val message =
                    it.msgJsonHeader?.let { msgJsonHeader ->
                        OdinSystemSerializer.deserialize<HomebaseFile>(msgJsonHeader)
                    }

                ConversationWithLastMessage(
                    conversation = conversation,
                    message = message,
                    msgUserDateMs = it.msgUserDate,
                )

            } catch (t: Throwable) {

                logger.e(t) {
                    "FAILED row[$index] | " +
                            "convSize=${it.convJsonHeader.length} " +
                            "msgSize=${it.msgJsonHeader?.length} " +
                            "hasMsg=${it.msgJsonHeader != null}"
                }

                throw t // preserve original behavior (fail fast)
            }
        }

        logger.d {
            "Completed mapping ${result.size} rows in ${Clock.System.now().toEpochMilliseconds() - start}ms"
        }

        return result
    }

    /**
     * Get unread message count for a specific conversation
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    fun selectUnreadCountForConversation(
        identityId: Uuid,
        groupId: Uuid,
        selfDomain: OdinId,
    ): Long {
        val result = delegate.selectUnreadCountForConversation(
            identityId, groupId, selfDomain.domainName
        ).executeAsOneOrNull()

        if (result == null)
            return 0

        return result
    }

    /**
     * Get all conversation read counts
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    suspend fun selectAllUnreadCount(identityId: Uuid, originalAuthor: OdinId): List<ConversationUnreadCount> {
        val list = delegate.selectAllUnreadCount(identityId, originalAuthor.domainName).executeAsList()
        return list.map {
            ConversationUnreadCount(
                conversationId = it.groupId,
                unreadCount = it.unreadCount,
                lastReadTime = it.lastReadTime,
            )
        }
    }

    /** Raw stored lastReadTime (epoch ms) for a conversation, or null if none. */
    fun selectLastReadTimeMs(groupId: Uuid): Long? {
        return delegate.selectLastReadTime(groupId).executeAsOneOrNull()
    }

    /**
     * Upsert last read time for a conversation
     */
    suspend fun upsertLastReadTime(groupId: Uuid, lastReadTime: UnixTimeUtc): Boolean {
        return databaseManager.withWriteValue { db ->
            db.chatReadCountQueries.upsertLastReadTime(groupId, lastReadTime.milliseconds).value > 0
        }
    }

    /**
     * Bulk variant of [upsertLastReadTime] — runs the upsert for every entry in
     * a single SQLite transaction. Used by the cold-load reconcile pass that
     * mirrors each conversation file's `localAppData.lastReadTime` into
     * `ChatReadCount` when this device's row lags behind the file-of-record.
     *
     * The per-row `MAX(excluded.lastReadTime, lastReadTime)` clause on the
     * upsert keeps any concurrent advance from being clobbered.
     */
    suspend fun bulkUpsertLastReadTimes(rows: List<Pair<Uuid, UnixTimeUtc>>) {
        if (rows.isEmpty()) return
        databaseManager.withWriteTransaction { db ->
            for ((groupId, lastReadTime) in rows) {
                db.chatReadCountQueries.upsertLastReadTime(groupId, lastReadTime.milliseconds)
            }
        }
    }

    /**
     * Delete read count entry for a conversation
     */
    suspend fun deleteByGroupId(groupId: Uuid): Boolean {
        return databaseManager.withWriteValue { db ->
            db.chatReadCountQueries.deleteByGroupId(groupId).value > 0
        }
    }

    /**
     * Delete all read count entries (used during logout to ensure no data survives)
     */
    suspend fun deleteAll(): Boolean {
        return databaseManager.withWriteValue { db ->
            db.chatReadCountQueries.deleteAll().value > 0
        }
    }
}