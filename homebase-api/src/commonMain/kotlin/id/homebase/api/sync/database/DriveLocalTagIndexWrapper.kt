package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.uuid.Uuid

class DriveLocalTagIndexWrapper(
    driver: SqlDriver,
    driveLocalTagIndexAdapter: DriveLocalTagIndex.Adapter,
    driveMainIndexAdapter: DriveMainIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    // DriveMainIndex adapter is required because selectFilesByLocalTagInGroup joins it.
    private val delegate =
        DriveLocalTagIndexQueries(driver, driveLocalTagIndexAdapter, driveMainIndexAdapter)

    suspend fun <T : Any> selectByFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
        mapper: (
            rowId: Long,
            identityId: Uuid,
            driveId: Uuid,
            fileId: Uuid,
            tagId: Uuid,
        ) -> T,
    ): List<T> = databaseManager.readValue("driveLocalTagIndex.selectByFile(mapper)") {
        delegate.selectByFile(identityId, driveId, fileId, mapper).executeAsList()
    }

    suspend fun selectByFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): List<DriveLocalTagIndex> = databaseManager.readValue("driveLocalTagIndex.selectByFile") {
        delegate.selectByFile(identityId, driveId, fileId).executeAsList()
    }

    /**
     * jsonHeaders of active message files in [groupId] carrying [tagId], newest-first.
     * Powers the pinned-messages bar (tagId = ChatProtocol.MessagePinnedTag).
     */
    suspend fun selectJsonHeadersByLocalTagInGroup(
        identityId: Uuid,
        driveId: Uuid,
        tagId: Uuid,
        groupId: Uuid,
    ): List<String> = databaseManager.readValue("driveLocalTagIndex.selectFilesByLocalTagInGroup") {
        delegate.selectFilesByLocalTagInGroup(identityId, driveId, tagId, groupId).executeAsList()
    }

    suspend fun countAll(): Long = databaseManager.readValue("driveLocalTagIndex.countAll") {
        delegate.countAll().executeAsOne()
    }

    suspend fun insertLocalTag(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
        tagId: Uuid,
    ): Long {
        return databaseManager.withWriteValue { delegate.insertLocalTag(identityId, driveId, fileId, tagId).value }
    }

    suspend fun deleteByFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): Long
    {
        return databaseManager.withWriteValue { delegate.deleteByFile(identityId, driveId, fileId).value }
    }

    suspend fun deleteAll(): Long {
        return databaseManager.withWriteValue { delegate.deleteAll().value }
    }
}