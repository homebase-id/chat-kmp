package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.uuid.Uuid

class DriveLocalTagIndexWrapper(
    driver: SqlDriver,
    driveLocalTagIndexAdapter: DriveLocalTagIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = DriveLocalTagIndexQueries(driver, driveLocalTagIndexAdapter)

    fun <T : Any> selectByFile(
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
    ): List<T> = delegate.selectByFile(identityId, driveId, fileId, mapper).executeAsList()

    fun selectByFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): List<DriveLocalTagIndex> = delegate.selectByFile(identityId, driveId, fileId).executeAsList()

    fun countAll(): Long = delegate.countAll().executeAsOne()

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