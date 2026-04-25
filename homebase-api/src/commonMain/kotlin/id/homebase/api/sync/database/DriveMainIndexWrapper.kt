package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlCursor
import kotlin.Any
import kotlin.Long
import kotlin.compareTo
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid

class DriveMainIndexWrapper(
    driver: SqlDriver,
    driveMainIndexAdapter: DriveMainIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = DriveMainIndexQueries(driver, driveMainIndexAdapter)

    fun <T : Any> selectByIdentityAndDriveAndFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
        mapper: (
            rowId: Long,
            identityId: Uuid,
            driveId: Uuid,
            fileId: Uuid?,
            uniqueId: Uuid?,
            globalTransitId: Uuid?,
            senderId: String?,
            originalAuthor: String?,
            groupId: Uuid?,
            fileType: Long,
            dataType: Long,
            archivalStatus: Long,
            historyStatus: Long,
            userDate: Long,
            created: Long,
            modified: Long,
            fileSystemType: Long,
            jsonHeader: String,
        ) -> T,
    ): T? = delegate.selectByIdentityAndDriveAndFile(identityId, driveId, fileId, mapper)
        .executeAsOneOrNull()

    fun selectByIdentityAndDriveAndFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): DriveMainIndex? =
        delegate.selectByIdentityAndDriveAndFile(identityId, driveId, fileId).executeAsOneOrNull()

    fun selectByIdentityAndDriveAndUnique(
        identityId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid,
    ): DriveMainIndex? = delegate.selectByIdentityAndDriveAndUnique(identityId, driveId, uniqueId)
        .executeAsOneOrNull()

    fun selectHomebaseFileByUnique(
        identityId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid,
    ): HomebaseFile? {
        val row = selectByIdentityAndDriveAndUnique(identityId, driveId, uniqueId) ?: return null
        return OdinSystemSerializer.deserialize<HomebaseFile>(row.jsonHeader)
    }

    fun selectByIdentityAndDriveAndUniqueIds(
        identityId: Uuid,
        driveId: Uuid,
        uniqueIds: Collection<Uuid>,
    ): List<DriveMainIndex> {
        if (uniqueIds.isEmpty()) return emptyList()
        return delegate.selectByIdentityAndDriveAndUniqueIds(identityId, driveId, uniqueIds)
            .executeAsList()
    }

    fun selectHomebaseFilesByUniqueIds(
        identityId: Uuid,
        driveId: Uuid,
        uniqueIds: Collection<Uuid>,
    ): List<HomebaseFile> {
        if (uniqueIds.isEmpty()) return emptyList()
        val rows = selectByIdentityAndDriveAndUniqueIds(identityId, driveId, uniqueIds)
        val result = ArrayList<HomebaseFile>(rows.size)
        for (row in rows) {
            result.add(OdinSystemSerializer.deserialize<HomebaseFile>(row.jsonHeader))
        }
        return result
    }

    fun selectByIdentityAndDriveAndGlobal(
        identityId: Uuid,
        driveId: Uuid,
        globalTransitId: Uuid,
    ): DriveMainIndex? =
        delegate.selectByIdentityAndDriveAndGlobal(identityId, driveId, globalTransitId)
            .executeAsOneOrNull()


    fun <T : Any> selectAll(
        mapper: (
            rowId: Long,
            identityId: Uuid,
            driveId: Uuid,
            fileId: Uuid?,
            uniqueId: Uuid?,
            globalTransitId: Uuid?,
            senderId: String?,
            originalAuthor: String?,
            groupId: Uuid?,
            fileType: Long,
            dataType: Long,
            archivalStatus: Long,
            historyStatus: Long,
            userDate: Long,
            created: Long,
            modified: Long,
            fileSystemType: Long,
            jsonHeader: String,
        ) -> T,
    ): List<T> = delegate.selectAll(mapper).executeAsList()

    fun selectAll(): List<DriveMainIndex> = delegate.selectAll().executeAsList()

    fun countAll(): Long = delegate.countAll().executeAsOne()

    fun countByIdentityAndDrive(identityId: Uuid, driveId: Uuid): Long =
        delegate.countByIdentityAndDrive(identityId, driveId).executeAsOne()

    /**
     * Row shape for the Defragmenter's streaming scan — carries enough to call
     * [HomebaseFile.isSoftDeleted] and to page forward by rowId.
     */
    data class PagedScanRow(val rowId: Long, val fileId: Uuid, val jsonHeader: String)

    /**
     * Keyset-paged scan of a drive's rows. Caller passes `sinceRowId = 0` on
     * the first call and then `sinceRowId = lastRowIdOfPreviousChunk` on each
     * subsequent call until an empty list is returned.
     */
    fun selectFileIdAndJsonByDriveSince(
        identityId: Uuid,
        driveId: Uuid,
        sinceRowId: Long,
        limit: Long,
    ): List<PagedScanRow> = delegate.selectFileIdAndJsonByDriveSince(
        identityId,
        driveId,
        sinceRowId,
        limit,
    ) { rowId, fileId, jsonHeader ->
        PagedScanRow(rowId = rowId, fileId = fileId, jsonHeader = jsonHeader)
    }.executeAsList()

    suspend fun upsertDriveMainIndex(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
        uniqueId: Uuid?,
        globalTransitId: Uuid?,
        groupId: Uuid?,
        senderId: String?,
        originalAuthor: String?,
        fileType: Long,
        dataType: Long,
        archivalStatus: Long,
        historyStatus: Long,
        userDate: Long,
        created: Long,
        modified: Long,
        fileSystemType: Long,
        jsonHeader: String,
    ): Boolean {
        return databaseManager.withWriteValue {
            delegate.upsertDriveMainIndex(
                identityId,
                driveId,
                fileId,
                uniqueId,
                globalTransitId,
                groupId,
                senderId,
                originalAuthor,
                fileType,
                dataType,
                archivalStatus,
                historyStatus,
                userDate,
                created,
                modified,
                fileSystemType,
                jsonHeader
            ).value > 0
        }
    }

    suspend fun deleteAll(): Boolean {
        return databaseManager.withWriteValue { delegate.deleteAll().value > 0 }
    }

    suspend fun deleteBy(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): Boolean {
        return databaseManager.withWriteValue {
            delegate.deleteBy(
                identityId,
                driveId,
                fileId
            ).value > 0
        }
    }

    // Returns -1 if unable to read the version
    suspend fun getSchemaVersion(): Long {
        val sqlQuery =
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'DriveMainIndex'"
        val createStmt = databaseManager.executeReadQuery(
            null,
            sqlQuery,
            mapper = { cursor: SqlCursor ->
                if (cursor.next().value) {
                    QueryResult.Value(cursor.getString(0))
                } else {
                    QueryResult.Value(null)
                }
            },
            parameters = 0,
            binders = null
        ).value

        if (createStmt == null) return -1

        val commentRegex = Regex("-- Version: (\\d+)")
        val match = commentRegex.find(createStmt)

        val result = match?.groups?.get(1)?.value?.toLong()

        if (result == null)
            return -1
        else
            return result
    }
}