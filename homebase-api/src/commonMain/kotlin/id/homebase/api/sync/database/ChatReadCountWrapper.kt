package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
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

    /**
     * Select all conversations with their last message
     * Returns ConversationWithLastMessage objects
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    fun selectAllConversationPlusLastMessage(): List<ConversationWithLastMessage> {
        val list = delegate.selectAllConversationPlusLastMessage().executeAsList()
        return list.map { 
            ConversationWithLastMessage(
                conversation = OdinSystemSerializer.deserialize<HomebaseFile>(it.convJsonHeader),
                message = it.msgJsonHeader?.let { msgJsonHeader -> OdinSystemSerializer.deserialize<HomebaseFile>(msgJsonHeader) }
            )
        }
    }

    /**
     * Get unread message count for a specific conversation
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    fun selectUnreadCountForConversation(groupId: Uuid): Long {
        val result = delegate.selectUnreadCountForConversation(groupId).executeAsOneOrNull()

        if (result == null)
            return 0

        return result.unreadCount
    }

    /**
     * Get all conversation read counts
     * Note: This implementation is simplified and would need the generated SQLDelight queries
     */
    suspend fun selectAllUnreadCount(): List<ConversationUnreadCount> {
        val list = delegate.selectAllUnreadCount().executeAsList()
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
    suspend fun upsertLastReadTime(groupId: Uuid, lastReadTime: Long): Boolean {
        return databaseManager.withWriteValue { db ->
            db.chatReadCountQueries.upsertLastReadTime(groupId, lastReadTime).value > 0
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