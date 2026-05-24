package id.homebase.api.sync.database

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Coverage for the [FileStateFilter] parameter on `QueryBatch.queryBatchAsync`:
 *
 *  - The default is [FileStateFilter.Active] — omitted callers get
 *    only active rows.
 *  - [FileStateFilter.Deleted] returns only soft-deleted rows.
 *  - [FileStateFilter.All] is the audit / diagnostic escape hatch
 *    that returns both states.
 *  - The upsert `ON CONFLICT … DO UPDATE` branches refresh the column
 *    when the same row is re-inserted with a different state (the
 *    classic active → soft-delete transition).
 *  - `MainIndexMetaHelpers.convertFileHeaderToDriveMainIndexRecord`
 *    projects `HomebaseFile.fileState` into the SQL column with the
 *    right int representation.
 */
class FileStateFilterTest {

    private val identityId: Uuid = Uuid.random()
    private val driveId: Uuid = Uuid.random()

    @Test
    fun queryBatch_defaultFilterIsActive() = runTest {
        newTestDatabaseManager().use { dbm ->
            val activeId = Uuid.random()
            val deletedId = Uuid.random()
            seed(dbm, activeId, fileState = "active")
            seed(dbm, deletedId, fileState = "deleted")

            // Omitting the filter must surface ONLY the active row.
            val defaulted = queryBatchWithDefault(dbm)
            assertEquals(1, defaulted.size, "default must surface only the active row")
            assertEquals(activeId, defaulted.single().fileMetadata.appData.uniqueId)

            // Explicit Active matches the default.
            val active = queryBatchFor(dbm, FileStateFilter.Active)
            assertEquals(1, active.size)
            assertEquals(activeId, active.single().fileMetadata.appData.uniqueId)

            // Explicit Deleted inverts the result.
            val deleted = queryBatchFor(dbm, FileStateFilter.Deleted)
            assertEquals(1, deleted.size)
            assertEquals(deletedId, deleted.single().fileMetadata.appData.uniqueId)
        }
    }

    @Test
    fun queryBatch_filterAll_returnsBothStates() = runTest {
        newTestDatabaseManager().use { dbm ->
            val activeId = Uuid.random()
            val deletedId = Uuid.random()
            seed(dbm, activeId, fileState = "active")
            seed(dbm, deletedId, fileState = "deleted")

            val all = queryBatchFor(dbm, FileStateFilter.All)

            assertEquals(2, all.size, "All filter must surface both rows")
            val ids = all.map { it.fileMetadata.appData.uniqueId }.toSet()
            assertEquals(setOf(activeId, deletedId), ids)
        }
    }

    @Test
    fun queryBatch_reflectsTransition_activeToDeleted_viaUpsert() = runTest {
        newTestDatabaseManager().use { dbm ->
            val uniqueId = Uuid.random()
            val fileId = Uuid.random()
            val t0 = Clock.System.now().epochSeconds * 1000L

            // Insert active.
            seed(dbm, uniqueId, fileId = fileId, fileState = "active", modifiedMs = t0)

            assertEquals(
                1, queryBatchFor(dbm, FileStateFilter.Active).size,
                "active filter sees the row before transition",
            )
            assertTrue(
                queryBatchFor(dbm, FileStateFilter.Deleted).isEmpty(),
                "deleted filter excludes the row before transition",
            )

            // Re-insert the same uniqueId/fileId as deleted with a newer modified
            // timestamp — the upsert ON CONFLICT path must refresh the fileState
            // column. Without `fileState = excluded.fileState` in BOTH conflict
            // branches the SQL column would lag the JSON header and the filter
            // below would return the now-stale "still active" view.
            seed(dbm, uniqueId, fileId = fileId, fileState = "deleted", modifiedMs = t0 + 1000)

            assertTrue(
                queryBatchFor(dbm, FileStateFilter.Active).isEmpty(),
                "active filter excludes the row after deletion",
            )
            assertEquals(
                1, queryBatchFor(dbm, FileStateFilter.Deleted).size,
                "deleted filter sees the row after deletion",
            )
        }
    }

    @Test
    fun convertFileHeader_projectsFileStateIntoSqlColumn() {
        val activeRecord = MainIndexMetaHelpers.HomebaseFileProcessor(
            newTestDatabaseManager()
        ).convertFileHeaderToDriveMainIndexRecord(
            identityId, driveId, parseHeader(Uuid.random(), Uuid.random(), "active"),
        )
        assertEquals(FileState.Active.value.toLong(), activeRecord.fileState)

        val deletedRecord = MainIndexMetaHelpers.HomebaseFileProcessor(
            newTestDatabaseManager()
        ).convertFileHeaderToDriveMainIndexRecord(
            identityId, driveId, parseHeader(Uuid.random(), Uuid.random(), "deleted"),
        )
        assertEquals(FileState.Deleted.value.toLong(), deletedRecord.fileState)
    }

    // ---------- helpers ----------

    /** Mirrors a call site that omits the filter — exercises the default. */
    private suspend fun queryBatchWithDefault(dbm: DatabaseManager): List<HomebaseFile> =
        QueryBatch(identityId).queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 100,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
        ).records

    private suspend fun queryBatchFor(
        dbm: DatabaseManager,
        fileState: FileStateFilter,
    ): List<HomebaseFile> = QueryBatch(identityId).queryBatchAsync(
        dbm = dbm,
        driveId = driveId,
        noOfItems = 100,
        sortOrder = QueryBatchSortOrder.NewestFirst,
        sortField = QueryBatchSortField.UserDate,
        fileSystemType = 0,
        fileState = fileState,
    ).records

    private suspend fun seed(
        dbm: DatabaseManager,
        uniqueId: Uuid,
        fileId: Uuid = Uuid.random(),
        fileState: String = "active",
        modifiedMs: Long = Clock.System.now().epochSeconds * 1000L,
    ) {
        val header = parseHeader(uniqueId, fileId, fileState, modifiedMs)
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(identityId, driveId, header)
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }

    private fun parseHeader(
        uniqueId: Uuid,
        fileId: Uuid,
        fileState: String,
        modifiedMs: Long = Clock.System.now().epochSeconds * 1000L,
    ): HomebaseFile {
        val createdMs = modifiedMs
        val json = """{
            "fileId": "$fileId",
            "driveId": "$driveId",
            "fileState": "$fileState",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $createdMs,
                "updated": $modifiedMs,
                "transitCreated": 0,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "test.sender",
                "originalAuthor": "test.sender",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": 1,
                    "dataType": 0,
                    "groupId": null,
                    "userDate": $createdMs,
                    "content": "test",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "${Uuid.random()}",
                "payloads": [],
                "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {
                    "requiredSecurityGroup": "owner",
                    "circleIdList": null,
                    "odinIdList": null
                },
                "doNotIndex": false,
                "allowDistribution": true,
                "fileSystemType": "standard",
                "fileByteCount": 100,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 0,
            "fileByteCount": 100
        }"""
        return OdinSystemSerializer.deserialize(json)
    }
}
