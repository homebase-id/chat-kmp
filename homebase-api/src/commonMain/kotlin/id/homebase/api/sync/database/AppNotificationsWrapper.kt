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

    suspend fun <T : Any> selectByNotificationId(
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
    ): T? = databaseManager.readValue("appNotifications.selectByNotificationId(mapper)") {
        delegate.selectByNotificationId(identityId, notificationId, mapper).executeAsOneOrNull()
    }

    suspend fun selectByNotificationId(
        identityId: Uuid,
        notificationId: Uuid,
    ): AppNotifications? =
        databaseManager.readValue("appNotifications.selectByNotificationId") {
            delegate.selectByNotificationId(identityId, notificationId).executeAsOneOrNull()
        }

    suspend fun <T : Any> selectFirstPage(
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
    ): List<T> = databaseManager.readValue("appNotifications.selectFirstPage(mapper)") {
        delegate.selectFirstPage(identityId, limit, mapper).executeAsList()
    }

    suspend fun selectFirstPage(
        identityId: Uuid,
        limit: Long,
    ): List<AppNotifications> =
        databaseManager.readValue("appNotifications.selectFirstPage") {
            delegate.selectFirstPage(identityId, limit).executeAsList()
        }

    suspend fun <T : Any> selectNextPage(
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
    ): List<T> = databaseManager.readValue("appNotifications.selectNextPage(mapper)") {
        delegate.selectNextPage(identityId, rowId, limit, mapper).executeAsList()
    }

    suspend fun selectNextPage(
        identityId: Uuid,
        rowId: Long,
        limit: Long,
    ): List<AppNotifications> =
        databaseManager.readValue("appNotifications.selectNextPage") {
            delegate.selectNextPage(identityId, rowId, limit).executeAsList()
        }

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
