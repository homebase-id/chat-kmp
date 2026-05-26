package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.Long
import kotlin.compareTo
import kotlin.uuid.Uuid

class DriveTagIndexWrapper(
    driver: SqlDriver,
    driveTagIndexAdapter: DriveTagIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = DriveTagIndexQueries(driver, driveTagIndexAdapter)

//    fun <T : Any> selectByFile(
//        identityId: Uuid,
//        driveId: Uuid,
//        fileId: Uuid,
//        mapper: (
//            rowId: Long,
//            identityId: Uuid,
//            driveId: Uuid,
//            fileId: Uuid,
//            tagId: Uuid,
//        ) -> T,
//    ): Query<T> = delegate.selectByFile(identityId, driveId, fileId, mapper)

    suspend fun selectByFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): List<DriveTagIndex> = databaseManager.readValue("driveTagIndex.selectByFile") {
        delegate.selectByFile(identityId, driveId, fileId).executeAsList()
    }

    suspend fun countAll(): Long = databaseManager.readValue("driveTagIndex.countAll") {
        delegate.countAll().executeAsOne()
    }

    suspend fun insertTag(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
        tagId: Uuid,
    ): Long {
        return databaseManager.withWriteValue { delegate.insertTag(identityId, driveId, fileId, tagId).value }
    }

    suspend fun deleteByFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): Boolean
    {
        return databaseManager.withWriteValue { delegate.deleteByFile(identityId, driveId, fileId).value > 0 }
    }

    suspend fun deleteAll(): Long {
        return databaseManager.withWriteValue { delegate.deleteAll().value }
    }
}