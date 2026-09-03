package id.homebase.api.sync.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val MESSAGE_FILE_TYPE = 7878L
private const val STATUS_DATA_TYPE = 202L

/**
 * Pins [DriveMainIndexWrapper.selectHomebaseFilesByFileTypeAndDataTypeSince] — the keyset scan
 * that lets a post-sync pass replay rows DriveSync wrote with no event (a status message that
 * arrived while offline never reaches the live BatchReceived dispatcher).
 *
 * The cursor is what keeps the scan cheap: without it every pass would re-walk the drive's whole
 * status-message history.
 */
class DriveMainIndexStatusScanTest {

    private val identityId = Uuid.fromLongs(0L, 1L)
    private val driveId = Uuid.fromLongs(0L, 2L)
    private val otherDriveId = Uuid.fromLongs(0L, 3L)

    @Test
    fun scan_returnsOnlyMatchingFileTypeAndDataTypeOnTheDrive() = runBlocking {
        val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
        seed(dbm, seq = 1, dataType = STATUS_DATA_TYPE)
        seed(dbm, seq = 2, dataType = 0L)                       // a normal chat message
        seed(dbm, seq = 3, dataType = STATUS_DATA_TYPE)
        seed(dbm, seq = 4, dataType = STATUS_DATA_TYPE, drive = otherDriveId)
        seed(dbm, seq = 5, dataType = STATUS_DATA_TYPE, fileType = 1234L)

        val found = scan(dbm, sinceRowId = 0L)

        assertEquals(listOf(1, 3), found.map { senderSeq(it.file) })
    }

    @Test
    fun scan_cursorSkipsRowsAlreadySeen() = runBlocking {
        val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
        seed(dbm, seq = 1, dataType = STATUS_DATA_TYPE)
        seed(dbm, seq = 2, dataType = STATUS_DATA_TYPE)
        seed(dbm, seq = 3, dataType = STATUS_DATA_TYPE)

        val first = scan(dbm, sinceRowId = 0L)
        assertEquals(3, first.size)

        // Nothing new since the last pass — the common steady-state case.
        assertEquals(emptyList(), scan(dbm, sinceRowId = first.last().rowId))

        seed(dbm, seq = 4, dataType = STATUS_DATA_TYPE)
        val second = scan(dbm, sinceRowId = first.last().rowId)
        assertEquals(listOf(4), second.map { senderSeq(it.file) })
    }

    @Test
    fun scan_respectsTheLimitAndReturnsRowIdOrder() = runBlocking {
        val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
        for (seq in 1..5) seed(dbm, seq = seq, dataType = STATUS_DATA_TYPE)

        val page = scan(dbm, sinceRowId = 0L, limit = 2)
        assertEquals(listOf(1, 2), page.map { senderSeq(it.file) })

        val next = scan(dbm, sinceRowId = page.last().rowId, limit = 2)
        assertEquals(listOf(3, 4), next.map { senderSeq(it.file) })
    }

    @Test
    fun scan_skipsAnUndeserializableHeaderWithoutDroppingTheBatch() = runBlocking {
        val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
        seed(dbm, seq = 1, dataType = STATUS_DATA_TYPE)
        seed(dbm, seq = 2, dataType = STATUS_DATA_TYPE, jsonHeaderOverride = "not json {{{")
        seed(dbm, seq = 3, dataType = STATUS_DATA_TYPE)

        val found = scan(dbm, sinceRowId = 0L)
        assertEquals(listOf(1, 3), found.map { senderSeq(it.file) })
    }

    private suspend fun scan(
        dbm: DatabaseManager,
        sinceRowId: Long,
        limit: Long = 100L,
    ) = dbm.driveMainIndex.selectHomebaseFilesByFileTypeAndDataTypeSince(
        identityId = identityId,
        driveId = driveId,
        fileType = MESSAGE_FILE_TYPE,
        dataType = STATUS_DATA_TYPE,
        sinceRowId = sinceRowId,
        limit = limit,
    )

    /** The seed number is carried in senderOdinId so assertions can name rows. */
    private fun senderSeq(file: id.homebase.api.client.drives.HomebaseFile): Int =
        file.fileMetadata.senderOdinId!!.domainName.substringBefore('.').removePrefix("peer").toInt()

    private suspend fun seed(
        dbm: DatabaseManager,
        seq: Int,
        dataType: Long,
        fileType: Long = MESSAGE_FILE_TYPE,
        drive: Uuid = driveId,
        jsonHeaderOverride: String? = null,
    ) {
        val fileId = Uuid.fromLongs(1L, seq.toLong())
        val uniqueId = Uuid.fromLongs(2L, seq.toLong())
        dbm.driveMainIndex.upsertDriveMainIndex(
            identityId = identityId,
            driveId = drive,
            fileId = fileId,
            uniqueId = uniqueId,
            globalTransitId = null,
            groupId = null,
            senderId = null,
            originalAuthor = null,
            fileType = fileType,
            dataType = dataType,
            archivalStatus = 0L,
            fileState = 1L,
            historyStatus = 0L,
            userDate = 1_700_000_000_000L + seq,
            created = 1_700_000_000_000L + seq,
            modified = 1_700_000_000_000L + seq,
            fileSystemType = 0L,
            jsonHeader = jsonHeaderOverride ?: headerJson(fileId, drive, uniqueId, seq, fileType, dataType),
        )
    }

    private fun headerJson(
        fileId: Uuid,
        drive: Uuid,
        uniqueId: Uuid,
        seq: Int,
        fileType: Long,
        dataType: Long,
    ): String = """
        {
            "fileId": "$fileId",
            "driveId": "$drive",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": null,
                "created": 1700000000000,
                "updated": 1700000000000,
                "transitCreated": 1700000000000,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "peer$seq.test",
                "originalAuthor": "peer$seq.test",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": $fileType,
                    "dataType": $dataType,
                    "groupId": null,
                    "userDate": 1700000000000,
                    "content": "",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "00000000-0000-0000-0000-000000000000",
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
                "fileByteCount": 0,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 0,
            "fileByteCount": 0
        }
    """.trimIndent()
}
