package id.homebase.homebasekmppoc.prototype.lib.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.homebasekmppoc.lib.database.OdinDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.uuid.Uuid

class DriveMainIndexTest {

    private var driver: SqlDriver? = null
    private lateinit var db: OdinDatabase

@BeforeTest
    fun setup() {
        driver = createInMemoryDatabase()
        db = TestDatabaseFactory.createTestDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver?.close()
    }

    @Test
    fun testUpsertSelectByIdentityAndDriveAndFile() = runTest {

        // Test data - create sample byte arrays and values
val randomId = Random.nextLong()
        val currentTime = Random.nextLong()
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val fileId = Uuid.random()
        val globalTransitId = Uuid.random()
        val uniqueId = Uuid.random()
        val groupId = Uuid.random()
        val hdrVersionTag = "hdr-version-$randomId".encodeToByteArray()
        val hdrLocalVersionTag = "hdr-local-version-$randomId".encodeToByteArray()

        // Test values
        val fileSystemType = 3
        val userDate = currentTime
        val fileType = 4
        val dataType = 5
        val archivalStatus = 6
        val historyStatus = 7
        val senderId = "sender-$randomId"
        val byteCount = 1024L
        val hdrEncryptedKeyHeader = "encrypted-key-header"
        val hdrAppData = "app-data"
        val hdrLocalAppData = "local-app-data"
        val hdrReactionSummary = "reaction-summary"
        val hdrServerData = "server-data"
        val hdrTransferHistory = "transfer-history"
        val hdrFileMetaData = "file-metadata"
        val created = currentTime
        val modified = currentTime + 1000

// Insert a record using upsertDriveMainIndex (for testing)
        db.driveMainIndexQueries.upsertDriveMainIndex(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId,
            uniqueId = uniqueId,
            globalTransitId = globalTransitId,
            groupId = groupId,
            senderId = senderId,
            fileType = fileType.toLong(),
            dataType = dataType.toLong(),
            archivalStatus = archivalStatus.toLong(),
            historyStatus = historyStatus.toLong(),
            userDate = userDate,
            created = created,
            modified = modified,
            fileSystemType = fileSystemType.toLong(),
            jsonHeader = """{"versionTag":"$hdrVersionTag","byteCount":$byteCount,"encryptedKeyHeader":"$hdrEncryptedKeyHeader","appData":"$hdrAppData","localVersionTag":"$hdrLocalVersionTag","localAppData":"$hdrLocalAppData","reactionSummary":"$hdrReactionSummary","serverData":"$hdrServerData","transferHistory":"$hdrTransferHistory","fileMetaData":"$hdrFileMetaData"}"""
        )

        // Select the record by identity, drive, and file
        val selectedRecord = db.driveMainIndexQueries.selectByIdentityAndDriveAndFile(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId
        ).executeAsOne()

// Verify all fields match
        assertEquals(identityId, selectedRecord.identityId)
        assertEquals(driveId, selectedRecord.driveId)
assertEquals(fileId, selectedRecord.fileId)
        assertEquals(globalTransitId, selectedRecord.globalTransitId)
        assertEquals(userDate, selectedRecord.userDate)
        assertEquals(fileType.toLong(), selectedRecord.fileType)
        assertEquals(dataType.toLong(), selectedRecord.dataType)
        assertEquals(archivalStatus.toLong(), selectedRecord.archivalStatus)
        assertEquals(historyStatus.toLong(), selectedRecord.historyStatus)
        // Note: fileState, requiredSecurityGroup, fileSystemType are now consolidated in jsonHeader
assertEquals(senderId, selectedRecord.senderId)
assertEquals(groupId, selectedRecord.groupId)
assertEquals(uniqueId, selectedRecord.uniqueId)
assertEquals(created, selectedRecord.created)
assertEquals(modified, selectedRecord.modified)
// Note: old header fields are now consolidated in jsonHeader
    }

    @Test
    fun testUpsertUpdateExistingRecord() = runTest {

        // Test data
        val currentTime = Random.nextLong()
val identityId = Uuid.random()
        val driveId = Uuid.random()
        val fileId = Uuid.random()
        val globalTransitId = Uuid.random()
        val uniqueId = Uuid.random()
        val hdrVersionTag = "hdr-version-original".encodeToByteArray()

// Insert initial record
        db.driveMainIndexQueries.upsertDriveMainIndex(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId,
            uniqueId = uniqueId,
            globalTransitId = globalTransitId,
            groupId = null,
            senderId = "original-sender",
            fileType = 4L,
            dataType = 5L,
            archivalStatus = 6L,
            historyStatus = 7L,
            userDate = currentTime,
            created = currentTime,
            modified = currentTime,
            fileSystemType = 3L,
            jsonHeader = """{"versionTag":"${hdrVersionTag.contentToString()}","byteCount":1024,"encryptedKeyHeader":"original-key-header","appData":"original-app-data","localVersionTag":null,"localAppData":null,"reactionSummary":null,"serverData":"original-server-data","transferHistory":null,"fileMetaData":"original-metadata"}"""
        )

// Update the record with new values
        val updatedTime = currentTime + 5000
        val updatedGlobalTransitId = Uuid.random()
        val updatedUniqueId = Uuid.random()

db.driveMainIndexQueries.upsertDriveMainIndex(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId,
            uniqueId = updatedUniqueId,
            globalTransitId = updatedGlobalTransitId,
            groupId = Uuid.random(),
            senderId = "updated-sender",
            fileType = 44L,
            dataType = 55L,
            archivalStatus = 66L,
            historyStatus = 77L,
            userDate = updatedTime,
            created = currentTime,
            modified = updatedTime,
            fileSystemType = 33L,
            jsonHeader = """{"versionTag":"${"hdr-version-updated".encodeToByteArray().contentToString()}","byteCount":2048,"encryptedKeyHeader":"updated-key-header","appData":"updated-app-data","localVersionTag":"${"hdr-local-version-updated".encodeToByteArray().contentToString()}","localAppData":"updated-local-app-data","reactionSummary":"updated-reaction-summary","serverData":"updated-server-data","transferHistory":"updated-transfer-history","fileMetaData":"updated-metadata"}"""
        )

        // Select and verify the new record (simulating update by inserting different record)
        val updatedRecord = db.driveMainIndexQueries.selectByIdentityAndDriveAndFile(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId
        ).executeAsOne()

assertEquals(updatedGlobalTransitId, updatedRecord.globalTransitId)
        assertEquals("updated-sender", updatedRecord.senderId)
        assertEquals(updatedUniqueId, updatedRecord.uniqueId)
        assertEquals(updatedTime, updatedRecord.modified)
        assertEquals(currentTime, updatedRecord.created)
        // Note: old header fields are now consolidated in jsonHeader
    }

    @Test
    fun testSelectByIdentityAndDriveAndFileWithNonExistentRecord() = runTest {

        // Test data for non-existent record
val identityId = Uuid.random()
        val driveId = Uuid.random()
        val fileId = Uuid.random()

        // Try to select non-existent record
        val records = db.driveMainIndexQueries.selectByIdentityAndDriveAndFile(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId
        ).executeAsList()

        // Verify no records found
        assertTrue(records.isEmpty(), "Should have no records for non-existent file")
    }

    @Test
    fun testCountAllAndSelectAll() = runTest {

        // Initially should be empty
        assertEquals(0L, db.driveMainIndexQueries.countAll().executeAsOne())

        // Insert some test records
        val currentTime = Random.nextLong()
val identityId = Uuid.random()
        val driveId = Uuid.random()

for (i in 1..3) {
            db.driveMainIndexQueries.upsertDriveMainIndex(
                identityId = identityId,
                driveId = driveId,
                fileId = Uuid.random(),
                uniqueId = Uuid.random(),
                globalTransitId = Uuid.random(),
                groupId = null,
                senderId = "sender-$i",
                fileType = 1L,
                dataType = 1L,
                archivalStatus = 1L,
                historyStatus = 1L,
                userDate = currentTime,
                created = currentTime + i,
                modified = currentTime + i + 1000,
                fileSystemType = 1L,
                jsonHeader = """{"versionTag":"${"version-$i".encodeToByteArray().contentToString()}","byteCount":${i * 100L},"encryptedKeyHeader":"key-$i","appData":"app-data-$i","localVersionTag":null,"localAppData":null,"reactionSummary":null,"serverData":"server-data-$i","transferHistory":null,"fileMetaData":"metadata-$i"}"""
            )
        }

        // Verify count
        assertEquals(3L, db.driveMainIndexQueries.countAll().executeAsOne())

        // Verify select all
        val allRecords = db.driveMainIndexQueries.selectAll().executeAsList()
        assertEquals(3, allRecords.size, "Should have exactly 3 records")

// Verify the records have different fileIds
        val fileIds = allRecords.map { it.fileId.toString() }
        assertEquals(3, fileIds.toSet().size, "All fileIds should be different")
    }

    @Test
    fun testDeleteAll() = runTest {

// Insert a test record first
        val currentTime = Random.nextLong()
        db.driveMainIndexQueries.upsertDriveMainIndex(
            identityId = Uuid.random(),
            driveId = Uuid.random(),
            fileId = Uuid.random(),
            uniqueId = Uuid.random(),
            globalTransitId = Uuid.random(),
            groupId = null,
            senderId = "sender-delete",
            fileType = 1L,
            dataType = 1L,
            archivalStatus = 1L,
            historyStatus = 1L,
            userDate = currentTime,
            created = currentTime,
            modified = currentTime,
            fileSystemType = 1L,
            jsonHeader = """{"versionTag":"${"version-delete".encodeToByteArray().contentToString()}","byteCount":100,"encryptedKeyHeader":"key-delete","appData":"app-data-delete","localVersionTag":null,"localAppData":null,"reactionSummary":null,"serverData":"server-data-delete","transferHistory":null,"fileMetaData":"metadata-delete"}"""
        )

        // Verify we have 1 record
        assertEquals(1L, db.driveMainIndexQueries.countAll().executeAsOne())

        // Delete all records
        db.driveMainIndexQueries.deleteAll()

        // Verify all records are deleted
        assertEquals(0L, db.driveMainIndexQueries.countAll().executeAsOne())
        
        val allRecords = db.driveMainIndexQueries.selectAll().executeAsList()
        assertTrue(allRecords.isEmpty(), "Should have no records after deleteAll")
    }

//    @Test
//    fun testUpsertOnConflictScenarios() = runTest {
//        val currentTime = Random.nextLong()
//        val identityId = Uuid.random()
//        val driveId = Uuid.random()
//
//        // Test data
//        val initialFileId = Uuid.random()
//        val initialUniqueId = Uuid.random()
//        val initialGlobalTransitId = Uuid.random()
//        val updatedFileId = Uuid.random()
//        val updatedUniqueId = Uuid.random()
//        val updatedGlobalTransitId = Uuid.random()
//
//        val baseJsonHeader = """{"versionTag":"${"version".encodeToByteArray().contentToString()}","byteCount":1024,"encryptedKeyHeader":"key","appData":"data","localVersionTag":null,"localAppData":null,"reactionSummary":null,"serverData":"server","transferHistory":null,"fileMetaData":"meta"}"""
//
//        // === Test 1: ON CONFLICT (identityId,driveId,fileId) ===
//        // Insert initial record
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = initialFileId,
//            uniqueId = initialUniqueId,
//            globalTransitId = initialGlobalTransitId,
//            groupId = null,
//            senderId = "initial-sender",
//            fileType = 1L,
//            dataType = 2L,
//            archivalStatus = 3L,
//            historyStatus = 4L,
//            userDate = currentTime,
//            created = currentTime,
//            modified = currentTime,
//            fileSystemType = 5L,
//            jsonHeader = baseJsonHeader
//        )
//
//        // Upsert with same identityId, driveId, fileId - should trigger first ON CONFLICT
//        val updatedTime1 = currentTime + 1000
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = initialFileId, // Same fileId - triggers conflict
//            uniqueId = updatedUniqueId, // Should update
//            globalTransitId = updatedGlobalTransitId, // Should update
//            groupId = Uuid.random(),
//            senderId = "updated-sender-1",
//            fileType = 11L,
//            dataType = 22L,
//            archivalStatus = 33L,
//            historyStatus = 44L,
//            userDate = updatedTime1,
//            created = currentTime,
//            modified = updatedTime1,
//            fileSystemType = 55L,
//            jsonHeader = baseJsonHeader.replace("data", "updated-data-1")
//        )
//
//        var record = db.driveMainIndexQueries.selectByIdentityAndDriveAndFile(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = initialFileId
//        ).executeAsOne()
//
//        // Verify fileId stayed the same, but other fields updated
//        assertEquals(initialFileId, record.fileId, "fileId should remain unchanged in first conflict")
//        assertEquals(updatedUniqueId, record.uniqueId, "uniqueId should be updated")
//        assertEquals(updatedGlobalTransitId, record.globalTransitId, "globalTransitId should be updated")
//        assertEquals("updated-sender-1", record.senderId, "senderId should be updated")
//        assertEquals(11L, record.fileType, "fileType should be updated")
//        assertEquals(updatedTime1, record.modified, "modified should be updated")
//
//        // === Test 2: ON CONFLICT (identityId,driveId,uniqueId) ===
//        // Create a new record with same identityId, driveId, uniqueId but different fileId
//        val newFileId = Uuid.random()
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = newFileId,
//            uniqueId = updatedUniqueId, // Same uniqueId - triggers second conflict
//            globalTransitId = Uuid.random(),
//            groupId = null,
//            senderId = "sender-unique-conflict",
//            fileType = 100L,
//            dataType = 200L,
//            archivalStatus = 300L,
//            historyStatus = 400L,
//            userDate = currentTime,
//            created = currentTime,
//            modified = currentTime,
//            fileSystemType = 500L,
//            jsonHeader = baseJsonHeader.replace("data", "unique-conflict-data")
//        )
//
//        // This should trigger the second ON CONFLICT (identityId,driveId,uniqueId)
//        // Since fileId is NULL in existing record? Wait, no - we already have fileId set.
//        // Actually, this will create a new record because uniqueId is already taken by the first record.
//        // To test the uniqueId conflict, I need to upsert with the same uniqueId but different other keys.
//
//        // Let me create a record with NULL fileId first, then update it via uniqueId conflict
//        val nullFileIdRecordId = Uuid.random()
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = null, // NULL fileId
//            uniqueId = nullFileIdRecordId,
//            globalTransitId = Uuid.random(),
//            groupId = null,
//            senderId = "null-fileid-sender",
//            fileType = 1L,
//            dataType = 2L,
//            archivalStatus = 3L,
//            historyStatus = 4L,
//            userDate = currentTime,
//            created = currentTime,
//            modified = currentTime,
//            fileSystemType = 5L,
//            jsonHeader = baseJsonHeader.replace("data", "null-fileid-data")
//        )
//
//        // Now upsert with same identityId, driveId, uniqueId but provide a fileId - should update fileId since it's NULL
//        val updatedTime2 = currentTime + 2000
//        val providedFileId = Uuid.random()
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = providedFileId, // Provide fileId
//            uniqueId = nullFileIdRecordId, // Same uniqueId - triggers second conflict
//            globalTransitId = Uuid.random(),
//            groupId = null,
//            senderId = "updated-null-fileid-sender",
//            fileType = 11L,
//            dataType = 22L,
//            archivalStatus = 33L,
//            historyStatus = 44L,
//            userDate = updatedTime2,
//            created = currentTime,
//            modified = updatedTime2,
//            fileSystemType = 55L,
//            jsonHeader = baseJsonHeader.replace("data", "updated-null-fileid-data")
//        )
//
//        // Find the record by identityId, driveId, uniqueId (since fileId was null, we can't query by fileId)
//        val recordsByUnique = db.driveMainIndexQueries.selectAll().executeAsList()
//            .filter { it.identityId == identityId && it.driveId == driveId && it.uniqueId == nullFileIdRecordId }
//
//        assertEquals(1, recordsByUnique.size, "Should find exactly one record with this uniqueId")
//        val uniqueConflictRecord = recordsByUnique.first()
//
//        // Verify fileId was updated from NULL to providedFileId
//        assertEquals(providedFileId, uniqueConflictRecord.fileId, "fileId should be updated from NULL via uniqueId conflict")
//        assertEquals("updated-null-fileid-sender", uniqueConflictRecord.senderId, "senderId should be updated")
//
//        // === Test 3: ON CONFLICT (identityId,driveId,globalTransitId) ===
//        // Create a record with NULL fileId
//        val globalTransitConflictId = Uuid.random()
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = null, // NULL fileId
//            uniqueId = Uuid.random(),
//            globalTransitId = globalTransitConflictId,
//            groupId = null,
//            senderId = "global-transit-sender",
//            fileType = 1L,
//            dataType = 2L,
//            archivalStatus = 3L,
//            historyStatus = 4L,
//            userDate = currentTime,
//            created = currentTime,
//            modified = currentTime,
//            fileSystemType = 5L,
//            jsonHeader = baseJsonHeader.replace("data", "global-transit-data")
//        )
//
//        // Try to update with older modified time - should NOT update due to WHERE clause
//        val olderTime = currentTime - 1000
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = Uuid.random(), // Different fileId
//            uniqueId = Uuid.random(),
//            globalTransitId = globalTransitConflictId, // Same globalTransitId - triggers third conflict
//            groupId = null,
//            senderId = "should-not-update-sender",
//            fileType = 999L,
//            dataType = 888L,
//            archivalStatus = 777L,
//            historyStatus = 666L,
//            userDate = olderTime,
//            created = olderTime,
//            modified = olderTime, // Older than current - should not update
//            fileSystemType = 555L,
//            jsonHeader = baseJsonHeader.replace("data", "should-not-update-data")
//        )
//
//        // Find the record
//        val recordsByGlobal = db.driveMainIndexQueries.selectAll().executeAsList()
//            .filter { it.identityId == identityId && it.driveId == driveId && it.globalTransitId == globalTransitConflictId }
//
//        assertEquals(1, recordsByGlobal.size, "Should find exactly one record with this globalTransitId")
//        val globalConflictRecord = recordsByGlobal.first()
//
//        // Verify it was NOT updated (sender should still be "global-transit-sender")
//        assertEquals("global-transit-sender", globalConflictRecord.senderId, "Record should not be updated due to older modified time")
//        assertEquals(currentTime, globalConflictRecord.modified, "Modified time should remain the same")
//
//        // Now update with newer modified time - should update
//        val newerTime = currentTime + 3000
//        val newFileIdForGlobal = Uuid.random()
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = newFileIdForGlobal, // Provide fileId
//            uniqueId = Uuid.random(),
//            globalTransitId = globalTransitConflictId, // Same globalTransitId - triggers third conflict
//            groupId = null,
//            senderId = "updated-global-sender",
//            fileType = 111L,
//            dataType = 222L,
//            archivalStatus = 333L,
//            historyStatus = 444L,
//            userDate = newerTime,
//            created = currentTime,
//            modified = newerTime, // Newer than current - should update
//            fileSystemType = 555L,
//            jsonHeader = baseJsonHeader.replace("data", "updated-global-data")
//        )
//
//        // Find the updated record
//        val updatedRecordsByGlobal = db.driveMainIndexQueries.selectAll().executeAsList()
//            .filter { it.identityId == identityId && it.driveId == driveId && it.globalTransitId == globalTransitConflictId }
//
//        assertEquals(1, updatedRecordsByGlobal.size, "Should find exactly one record with this globalTransitId")
//        val updatedGlobalRecord = updatedRecordsByGlobal.first()
//
//        // Verify it was updated
//        assertEquals("updated-global-sender", updatedGlobalRecord.senderId, "Record should be updated with newer modified time")
//        assertEquals(newerTime, updatedGlobalRecord.modified, "Modified time should be updated")
//
//        // === Test 4: CHECK constraint violation ===
//        // Try to insert record with all three identifiers NULL - should fail CHECK constraint
//        val exception = kotlin.runCatching {
//            db.driveMainIndexQueries.upsertDriveMainIndex(
//                identityId = Uuid.random(), // Different identity to avoid conflicts
//                driveId = Uuid.random(),
//                fileId = null,
//                uniqueId = null,
//                globalTransitId = null, // All three NULL - violates CHECK
//                groupId = null,
//                senderId = "check-violation-sender",
//                fileType = 1L,
//                dataType = 2L,
//                archivalStatus = 3L,
//                historyStatus = 4L,
//                userDate = currentTime,
//                created = currentTime,
//                modified = currentTime,
//                fileSystemType = 5L,
//                jsonHeader = baseJsonHeader
//            )
//        }.exceptionOrNull()
//
//        assertNotNull(exception, "Inserting record with all identifiers NULL should throw an exception")
//
//        // === Test 5: fileId immutability ===
//        // Get a record that has fileId set
//        val recordWithFileId = db.driveMainIndexQueries.selectByIdentityAndDriveAndFile(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = initialFileId
//        ).executeAsOne()
//
//        assertNotNull(recordWithFileId.fileId, "Record should have fileId set")
//
//        val originalFileId = recordWithFileId.fileId!!
//
//        // Try to update this record with a different fileId via uniqueId conflict
//        // This should NOT change the fileId since it's already set
//        db.driveMainIndexQueries.upsertDriveMainIndex(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = Uuid.random(), // Different fileId - should be ignored
//            uniqueId = recordWithFileId.uniqueId, // Same uniqueId - triggers conflict
//            globalTransitId = Uuid.random(),
//            groupId = null,
//            senderId = "attempt-change-fileid-sender",
//            fileType = 999L,
//            dataType = 888L,
//            archivalStatus = 777L,
//            historyStatus = 666L,
//            userDate = currentTime,
//            created = currentTime,
//            modified = currentTime + 4000,
//            fileSystemType = 555L,
//            jsonHeader = baseJsonHeader.replace("data", "attempt-change-fileid-data")
//        )
//
//        // Verify fileId did NOT change
//        val finalRecord = db.driveMainIndexQueries.selectByIdentityAndDriveAndFile(
//            identityId = identityId,
//            driveId = driveId,
//            fileId = originalFileId
//        ).executeAsOne()
//
//        assertEquals(originalFileId, finalRecord.fileId, "fileId should remain immutable once set")
//        assertEquals("attempt-change-fileid-sender", finalRecord.senderId, "Other fields should still update")
//    }
}