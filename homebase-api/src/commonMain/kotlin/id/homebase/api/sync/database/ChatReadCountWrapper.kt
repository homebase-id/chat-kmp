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
    val message: HomebaseFile?
)

data class ConversationUnreadCount(
    val conversationId: Uuid,
    val unreadCount: Long
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
    fun selectAllConversations(): List<HomebaseFile> {
        val list = delegate.selectAllCoversations().executeAsList()
        return list.map { OdinSystemSerializer.deserialize<HomebaseFile>(it) }
    }

    private val logger = Logger.withTag("ConversationQueries")

    /**
     * Select all conversations with their last message
     * Returns ConversationWithLastMessage objects
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */

    fun selectAllConversationPlusLastMessage(): List<ConversationWithLastMessage> {

        val start = Clock.System.now().toEpochMilliseconds()

        val list = delegate.selectAllConversationPlusLastMessage().executeAsList()

        logger.d { "Fetched rows=${list.size} in ${Clock.System.now().toEpochMilliseconds() - start}ms" }

        val result = list.mapIndexed { index, it ->

            try {
                logger.d {
                    "Mapping row[$index] | convSize=${it.convJsonHeader.length} " +
                            "hasMsg=${it.msgJsonHeader != null}"
                }

                val conversation =
                    OdinSystemSerializer.deserialize<HomebaseFile>(it.convJsonHeader)

                val message =
                    it.msgJsonHeader?.let { msgJsonHeader ->
                        OdinSystemSerializer.deserialize<HomebaseFile>(msgJsonHeader)
                    }

                ConversationWithLastMessage(
                    conversation = conversation,
                    message = message
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
    fun selectUnreadCountForConversation(groupId: Uuid): Long {
        val result = delegate.selectUnreadCountForConversation(groupId).executeAsOneOrNull()

        if (result == null)
            return 0

        return result
    }

    /**
     * Get all conversation read counts
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    suspend fun selectAllUnreadCount(originalAuthor: OdinId): List<ConversationUnreadCount> {
        val list = delegate.selectAllUnreadCount(originalAuthor.domainName).executeAsList()
        return list.map {
            ConversationUnreadCount(
                conversationId = it.groupId,
                unreadCount = it.unreadCount
            )
        }
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
     * Delete read count entry for a conversation
     */
    suspend fun deleteByGroupId(groupId: Uuid): Boolean {
        return databaseManager.withWriteValue { db ->
            db.chatReadCountQueries.deleteByGroupId(groupId).value > 0
        }
    }
}