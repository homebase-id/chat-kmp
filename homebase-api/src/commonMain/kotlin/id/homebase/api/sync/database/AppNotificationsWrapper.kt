package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.uuid.Uuid

class AppNotificationsWrapper(
    driver: SqlDriver,
    appNotificationsAdapter: AppNotifications.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = AppNotificationsQueries(driver, appNotificationsAdapter)

    fun <T : Any> selectByNotificationId(
        identityId: Uuid,
        notificationId: Uuid,
        mapper: (
            rowId: Long,
            identityId: Uuid,
            notificationId: Uuid,
            unread: Long,
            senderId: String?,
            timestamp: Long,
            data: ByteArray?,
            created: Long,
            modified: Long,
        ) -> T,
    ): T? = delegate.selectByNotificationId(identityId, notificationId, mapper).executeAsOneOrNull()

    fun selectByNotificationId(
        identityId: Uuid,
        notificationId: Uuid,
    ): AppNotifications? = delegate.selectByNotificationId(identityId, notificationId).executeAsOneOrNull()

    fun <T : Any> selectFirstPage(
        identityId: Uuid,
        limit: Long,
        mapper: (
            rowId: Long,
            identityId: Uuid,
            notificationId: Uuid,
            unread: Long,
            senderId: String?,
            timestamp: Long,
            data: ByteArray?,
            created: Long,
            modified: Long,
        ) -> T,
    ): List<T> = delegate.selectFirstPage(identityId, limit, mapper).executeAsList()

    fun selectFirstPage(
        identityId: Uuid,
        limit: Long,
    ): List<AppNotifications> = delegate.selectFirstPage(identityId, limit).executeAsList()

    fun <T : Any> selectNextPage(
        identityId: Uuid,
        rowId: Long,
        limit: Long,
        mapper: (
            rowId: Long,
            identityId: Uuid,
            notificationId: Uuid,
            unread: Long,
            senderId: String?,
            timestamp: Long,
            data: ByteArray?,
            created: Long,
            modified: Long,
        ) -> T,
    ): List<T> = delegate.selectNextPage(identityId, rowId, limit, mapper).executeAsList()

    fun selectNextPage(
        identityId: Uuid,
        rowId: Long,
        limit: Long,
    ): List<AppNotifications> = delegate.selectNextPage(identityId, rowId, limit).executeAsList()

    suspend fun insertNotification(
        identityId: Uuid,
        notificationId: Uuid,
        unread: Long,
        senderId: String?,
        timestamp: Long,
        data: ByteArray?,
        created: Long,
        modified: Long,
    ): Long {
        return databaseManager.withWriteValue { db ->
            delegate.insertNotification(identityId, notificationId, unread, senderId, timestamp, data, created, modified).value
        }
    }

    suspend fun deleteAll(
        identityId: Uuid,
    ): Long {
        return databaseManager.withWriteValue { db -> delegate.deleteAll(identityId).value }
    }

    suspend fun deleteByNotificationId(
        identityId: Uuid,
        notificationId: Uuid,
    ): Long {
        return databaseManager.withWriteValue { db ->
            delegate.deleteByNotificationId(identityId, notificationId).value
        }
    }

    suspend fun deleteAllRows(): Long {
        return databaseManager.withWriteValue { db -> delegate.deleteAllRows().value }
    }
}
