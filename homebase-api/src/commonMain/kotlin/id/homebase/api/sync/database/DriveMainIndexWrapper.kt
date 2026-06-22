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

    // All reads below run on DatabaseManager's read lane (readValue → readDispatcher), NOT
    // on the caller's thread. This is the single-row counterpart to PR #600's list-read
    // routing: a synchronous SQLite read here on the Main thread (e.g. a header lookup from
    // a viewModelScope/Main.immediate coroutine) would block in
    // SQLiteConnectionPool.waitForConnection behind a long cold-load read and wedge the UI —
    // the proven cold-boot "tap does nothing until green" cause. Row→model deserialization is
    // done OUTSIDE readValue so the lane's slot (and SlowDbRead sql= timing) cover only SQL.
    suspend fun <T : Any> selectByIdentityAndDriveAndFile(
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
            fileState: Long,
            historyStatus: Long,
            userDate: Long,
            created: Long,
            modified: Long,
            fileSystemType: Long,
            jsonHeader: String,
        ) -> T,
    ): T? = databaseManager.readValue("selectByIdentityAndDriveAndFile(mapper)") {
        delegate.selectByIdentityAndDriveAndFile(identityId, driveId, fileId, mapper)
            .executeAsOneOrNull()
    }

    suspend fun selectByIdentityAndDriveAndFile(
        identityId: Uuid,
        driveId: Uuid,
        fileId: Uuid,
    ): DriveMainIndex? = databaseManager.readValue("selectByIdentityAndDriveAndFile") {
        delegate.selectByIdentityAndDriveAndFile(identityId, driveId, fileId).executeAsOneOrNull()
    }

    suspend fun selectByIdentityAndDriveAndUnique(
        identityId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid,
    ): DriveMainIndex? = databaseManager.readValue("selectByIdentityAndDriveAndUnique") {
        delegate.selectByIdentityAndDriveAndUnique(identityId, driveId, uniqueId).executeAsOneOrNull()
    }

    suspend fun selectHomebaseFileByUnique(
        identityId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid,
    ): HomebaseFile? {
        val row = selectByIdentityAndDriveAndUnique(identityId, driveId, uniqueId) ?: return null
        return OdinSystemSerializer.deserialize<HomebaseFile>(row.jsonHeader)
    }

    suspend fun selectByIdentityAndDriveAndUniqueIds(
        identityId: Uuid,
        driveId: Uuid,
        uniqueIds: Collection<Uuid>,
    ): List<DriveMainIndex> {
        if (uniqueIds.isEmpty()) return emptyList()
        return databaseManager.readValue("selectByIdentityAndDriveAndUniqueIds") {
            delegate.selectByIdentityAndDriveAndUniqueIds(identityId, driveId, uniqueIds)
                .executeAsList()
        }
    }

    suspend fun selectHomebaseFilesByUniqueIds(
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

    suspend fun selectByIdentityAndDriveAndGlobal(
        identityId: Uuid,
        driveId: Uuid,
        globalTransitId: Uuid,
    ): DriveMainIndex? = databaseManager.readValue("selectByIdentityAndDriveAndGlobal") {
        delegate.selectByIdentityAndDriveAndGlobal(identityId, driveId, globalTransitId)
            .executeAsOneOrNull()
    }


    suspend fun <T : Any> selectAll(
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
            fileState: Long,
            historyStatus: Long,
            userDate: Long,
            created: Long,
            modified: Long,
            fileSystemType: Long,
            jsonHeader: String,
        ) -> T,
    ): List<T> = databaseManager.readValue("selectAll(mapper)") {
        delegate.selectAll(mapper).executeAsList()
    }

    suspend fun selectAll(): List<DriveMainIndex> =
        databaseManager.readValue("selectAll") { delegate.selectAll().executeAsList() }

    suspend fun countAll(): Long =
        databaseManager.readValue("countAll") { delegate.countAll().executeAsOne() }

    suspend fun countByIdentityAndDrive(identityId: Uuid, driveId: Uuid): Long =
        databaseManager.readValue("countByIdentityAndDrive") {
            delegate.countByIdentityAndDrive(identityId, driveId).executeAsOne()
        }

    /**
     * Row shape for the Defragmenter's streaming scan — carries enough to
     *  - call [HomebaseFile.isSoftDeleted] from the deserialised jsonHeader,
     *  - page forward by rowId,
     *  - compare what's stored on the SQL side ([userDate], [archivalStatus])
     *    against what the deserialised header says, so the classifier can
     *    detect SQL/header drift without a second read.
     */
    data class PagedScanRow(
        val rowId: Long,
        val fileId: Uuid,
        val userDate: Long,
        val archivalStatus: Long,
        val jsonHeader: String,
    )

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
    ) { rowId, fileId, userDate, archivalStatus, jsonHeader ->
        PagedScanRow(
            rowId = rowId,
            fileId = fileId,
            userDate = userDate,
            archivalStatus = archivalStatus,
            jsonHeader = jsonHeader,
        )
    }.executeAsList()

    /**
     * Defragmenter: re-project archivalStatus on a single row. Used by the
     * repair pass to correct rows where the SQL projection has drifted from
     * the header's soft-delete state.
     */
    suspend fun repairArchivalStatusByRowId(rowId: Long, archivalStatus: Long): Boolean {
        return databaseManager.withWriteValue { db ->
            db.driveMainIndexQueries
                .repairArchivalStatusByRowId(archivalStatus, rowId)
                .value > 0
        }
    }

    /**
     * Defragmenter: re-project userDate on a single row from the header's
     * metadata.created fallback. Used by the repair pass to correct
     * LegacyUserDateZero rows.
     */
    suspend fun repairUserDateByRowId(rowId: Long, userDate: Long): Boolean {
        return databaseManager.withWriteValue { db ->
            db.driveMainIndexQueries
                .repairUserDateByRowId(userDate, rowId)
                .value > 0
        }
    }

    /**
     * Defragmenter: rewrite a row's jsonHeader text after the
     * LegacyUserDateZero repair has patched `appData.userDate` in the
     * parsed header. Local-only.
     */
    suspend fun repairJsonHeaderByRowId(rowId: Long, jsonHeader: String): Boolean {
        return databaseManager.withWriteValue { db ->
            db.driveMainIndexQueries
                .repairJsonHeaderByRowId(jsonHeader, rowId)
                .value > 0
        }
    }

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
        fileState: Long,
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
                fileState,
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
}