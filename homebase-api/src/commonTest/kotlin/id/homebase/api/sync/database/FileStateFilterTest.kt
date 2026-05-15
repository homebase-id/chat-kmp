package id.homebase.api.sync.database

import id.homebase.api.client.drives.FileState
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
 * Coverage for the new SQL [DriveMainIndex.fileState] column wired in
 * "feat(db): promote fileState to a SQL column on DriveMainIndex":
 *
 *  - `QueryBatch.queryBatchAsync` filters by `fileStateAnyOf` end-to-end:
 *    inclusion when the row's state is in the list, exclusion when it
 *    isn't, and pass-through when the parameter is null (existing
 *    default).
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
    fun queryBatch_filtersByFileState_activeRow() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val uniqueId = Uuid.random()
            seed(dbm, uniqueId, fileState = "active")

            val results = queryBatchFor(dbm, fileStateAnyOf = null)
            assertEquals(1, results.size, "default (null filter) must return active row")

            val activeOnly = queryBatchFor(dbm, fileStateAnyOf = listOf(FileState.Active.value))
            assertEquals(1, activeOnly.size, "explicit Active filter must return the active row")
            assertEquals(uniqueId, activeOnly.single().fileMetadata.appData.uniqueId)

            val deletedOnly = queryBatchFor(dbm, fileStateAnyOf = listOf(FileState.Deleted.value))
            assertTrue(deletedOnly.isEmpty(), "Deleted filter must exclude the active row")
        }
    }

    @Test
    fun queryBatch_reflectsTransition_activeToDeleted_viaUpsert() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val uniqueId = Uuid.random()
            val fileId = Uuid.random()
            val t0 = Clock.System.now().epochSeconds * 1000L

            // Insert active.
            seed(dbm, uniqueId, fileId = fileId, fileState = "active", modifiedMs = t0)

            assertEquals(
                1, queryBatchFor(dbm, fileStateAnyOf = listOf(FileState.Active.value)).size,
                "active filter sees the row before transition",
            )
            assertTrue(
                queryBatchFor(dbm, fileStateAnyOf = listOf(FileState.Deleted.value)).isEmpty(),
                "deleted filter excludes the row before transition",
            )

            // Re-insert the same uniqueId/fileId as deleted with a newer modified
            // timestamp — the upsert ON CONFLICT path must refresh the fileState
            // column. Without `fileState = excluded.fileState` in BOTH conflict
            // branches the SQL column would lag the JSON header and the filter
            // below would return the now-stale "still active" view.
            seed(dbm, uniqueId, fileId = fileId, fileState = "deleted", modifiedMs = t0 + 1000)

            assertTrue(
                queryBatchFor(dbm, fileStateAnyOf = listOf(FileState.Active.value)).isEmpty(),
                "active filter excludes the row after deletion",
            )
            assertEquals(
                1, queryBatchFor(dbm, fileStateAnyOf = listOf(FileState.Deleted.value)).size,
                "deleted filter sees the row after deletion",
            )
        }
    }

    @Test
    fun convertFileHeader_projectsFileStateIntoSqlColumn() {
        val activeRecord = MainIndexMetaHelpers.HomebaseFileProcessor(
            DatabaseManager({ createInMemoryDatabase() })
        ).convertFileHeaderToDriveMainIndexRecord(
            identityId, driveId, parseHeader(Uuid.random(), Uuid.random(), "active"),
        )
        assertEquals(FileState.Active.value.toLong(), activeRecord.fileState)

        val deletedRecord = MainIndexMetaHelpers.HomebaseFileProcessor(
            DatabaseManager({ createInMemoryDatabase() })
        ).convertFileHeaderToDriveMainIndexRecord(
            identityId, driveId, parseHeader(Uuid.random(), Uuid.random(), "deleted"),
        )
        assertEquals(FileState.Deleted.value.toLong(), deletedRecord.fileState)
    }

    // ---------- helpers ----------

    private suspend fun queryBatchFor(
        dbm: DatabaseManager,
        fileStateAnyOf: List<Int>?,
    ): List<HomebaseFile> = QueryBatch(identityId).queryBatchAsync(
        dbm = dbm,
        driveId = driveId,
        noOfItems = 100,
        sortOrder = QueryBatchSortOrder.NewestFirst,
        sortField = QueryBatchSortField.UserDate,
        fileSystemType = 0,
        fileStateAnyOf = fileStateAnyOf,
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
