package id.homebase.homebasekmppoc.prototype.lib.database

import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtc
import id.homebase.homebasekmppoc.lib.database.DriveLocalTagIndex
import id.homebase.homebasekmppoc.lib.database.DriveTagIndex
import id.homebase.homebasekmppoc.prototype.lib.drives.HomebaseFile
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer

import id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor
import id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals

import kotlin.time.Clock
import kotlin.uuid.Uuid

class MainIndexMetaTest {
    @Test
    fun testUpsertDriveMainIndexHelper() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Test data
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val currentTime = Clock.System.now().epochSeconds

            // Create JSON header with all required fields for SharedSecretEncryptedFileHeader
            val jsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "52a491ac-9870-4d0c-94a1-1bf667393015",
                    "created": ${currentTime}000,
                    "updated": ${currentTime}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "test-sender",
                    "originalAuthor": "test-sender",
                    "appData": {
                        "uniqueId": "55d2e47e-ec86-f9b8-1e3d-d7bdeeb0527b",
                        "tags": null,
                        "fileType": 1,
                        "dataType": 1,
                        "groupId": null,
                        "userDate": ${currentTime}000,
                        "content": "test message",
                        "previewThumbnail": null,
                        "archivalStatus": 1
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
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
                    "allowDistribution": false,
                    "fileSystemType": "standard",
                    "fileByteCount": 1000,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 300,
                "fileByteCount": 1000
            }"""

            // Deserialize JSON header to SharedSecretEncryptedFileHeader
            val header =
                OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)

            // Create FileMetadataProcessor instance to convert header to DriveMainIndex record
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
            val driveMainIndexRecord =
                processor.convertFileHeaderToDriveMainIndexRecord(identityId, driveId, header)

            // Test the helper function
            MainIndexMetaHelpers.upsertDriveMainIndex(dbm, driveMainIndexRecord)

            // Verify the record was inserted
            val retrievedRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            assertNotNull(retrievedRecord, "Record should exist after upsert")
            assertEquals(identityId, retrievedRecord.identityId)
            assertEquals(driveId, retrievedRecord.driveId)
            assertEquals(fileId, retrievedRecord.fileId)
            assertEquals("test-sender", retrievedRecord.senderId)
            // Note: byteCount is now consolidated in jsonHeader
        }
    }

    @Test
    @Ignore // michael will fix
    fun testBaseUpsertEntryZapZapWithTags() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Create isolated database manager for this test
            // Test data
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val uniqueId = Uuid.random()
            val globalId = Uuid.random()
            val currentTime = Clock.System.now().epochSeconds

            // Create JSON header with all required fields for SharedSecretEncryptedFileHeader
            val jsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "${globalId}",
                    "created": ${currentTime}000,
                    "updated": ${currentTime}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "test-sender",
                    "originalAuthor": "test-sender",
                    "appData": {
                        "uniqueId": "${uniqueId}",
                        "tags": [
                            "bdaef89a-f262-8bd2-554f-380c4537e0e5"
                        ],
                        "fileType": 1,
                        "dataType": 1,
                        "groupId": null,
                        "userDate": ${currentTime}000,
                        "content": "test content",
                        "archivalStatus": 1
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
                    "payloads": [
                        {
                            "iv": "3zvsfQ3qQbyup44mm1BAfw==",
                            "key": "pst_mdi0",
                            "contentType": "image/jpeg",
                            "bytesWritten": 5082368,
                            "lastModified": 1763710153486,
                            "descriptorContent": null,
                            "uid": 115586508618072064
                        }
                    ],
                    "dataSource": null
                },
                "serverMetadata": {
                    "accessControlList": {
                        "requiredSecurityGroup": "connected",
                        "circleIdList": null,
                        "odinIdList": null
                    },
                    "doNotIndex": false,
                    "allowDistribution": true,
                    "fileSystemType": "standard",
                    "fileByteCount": 5402950,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 300,
                "fileByteCount": 5402950
            }"""

            // Create tag records
            val tagId1 = Uuid.random()
            val tagId2 = Uuid.random()
            listOf(
                DriveTagIndex(
                    rowId = 1L,
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                    tagId = tagId1
                ),
                DriveTagIndex(
                    rowId = 2L,
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                    tagId = tagId2
                )
            )

            // Create local tag records
            val localTagId1 = Uuid.random()
            listOf(
                DriveLocalTagIndex(
                    rowId = 1L,
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                    tagId = localTagId1
                )
            )

            val originalCursor = QueryBatchCursor(
                paging = TimeRowCursor(
                    time = UnixTimeUtc(1704067200000L), // 2024-01-01 00:00:00 UTC
                    row = 12345L
                ),
                stop = TimeRowCursor(
                    time = UnixTimeUtc(1704153600000L), // 2024-01-02 00:00:00 UTC
                    row = 67890L
                ),
                next = TimeRowCursor(
                    time = UnixTimeUtc(1704240000000L), // 2024-01-03 00:00:00 UTC
                    row = 11111L
                )
            )

            // Deserialize JSON header to SharedSecretEncryptedFileHeader
            val header =
                OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)

            // Create FileMetadataProcessor instance to test BaseUpsertEntryZapZap
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            // Call BaseUpsertEntryZapZap function
            processor.baseUpsertEntryZapZap(
                identityId = identityId,
                driveId = driveId,
                fileHeader = header,
                cursor = originalCursor
            )

            // Verify the main record was inserted
            val retrievedRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            assertNotNull(retrievedRecord, "Record should exist after BaseUpsertEntryZapZap")
            assertEquals(identityId, retrievedRecord.identityId)
            assertEquals("test-sender", retrievedRecord.senderId)

            val cursorStorage = CursorStorage(dbm, driveId);
            val loadedCursor = cursorStorage.loadCursor()
            assertNotNull(loadedCursor, "Cursor should not be null")
            assertNotNull(loadedCursor.paging, "Paging cursor should not be null")
            assertNotNull(loadedCursor.stop, "Stop at boundary cursor should not be null")
            assertNotNull(loadedCursor.next, "Next boundary cursor should not be null")

            // Verify paging cursor fields
            assertEquals(
                originalCursor.paging!!.time,
                loadedCursor.paging.time,
                "Paging cursor time should match"
            )
            assertEquals(
                originalCursor.paging.row,
                loadedCursor.paging.row,
                "Paging cursor row ID should match"
            )

            // Verify stop at boundary cursor fields
            assertEquals(
                originalCursor.stop!!.time,
                loadedCursor.stop.time,
                "Stop at boundary cursor time should match"
            )
            assertEquals(
                originalCursor.stop.row,
                loadedCursor.stop.row,
                "Stop at boundary cursor row ID should match"
            )

            // Verify next boundary cursor fields
            assertEquals(
                originalCursor.next!!.time,
                loadedCursor.next.time,
                "Next boundary cursor time should match"
            )
            assertEquals(
                originalCursor.next.row,
                loadedCursor.next.row,
                "Next boundary cursor row ID should match"
            )

            processor.deleteEntryDriveMainIndex(identityId, driveId, fileId)

            assertEquals(dbm.driveMainIndex.countAll(), 0L)
            assertEquals(dbm.driveTagIndex.countAll(), 0L)
            assertEquals(dbm.driveLocalTagIndex.countAll(), 0L)
        }
    }


    @Test
    fun testBaseUpsertEntryZapZapWithNullCursor() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Test data
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val uniqueId = Uuid.random()
            val globalId = Uuid.random()
            val currentTime = Clock.System.now().epochSeconds

            // Create JSON header with all required fields for SharedSecretEncryptedFileHeader
            val jsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "52a491ac-9870-4d0c-94a1-1bf667393015",
                    "created": ${currentTime}000,
                    "updated": ${currentTime}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "test-sender",
                    "originalAuthor": "test-sender",
                    "appData": {
                        "uniqueId": "55d2e47e-ec86-f9b8-1e3d-d7bdeeb0527b",
                        "tags": null,
                        "fileType": 1,
                        "dataType": 1,
                        "groupId": null,
                        "userDate": ${currentTime}000,
                        "content": "test content",
                        "archivalStatus": 1
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
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
                    "allowDistribution": false,
                    "fileSystemType": "standard",
                    "fileByteCount": 1000,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 300,
                "fileByteCount": 1000
            }"""

            // Create tag records
            listOf<DriveTagIndex>()
            listOf<DriveLocalTagIndex>()

            // Create FileMetadataProcessor instance to test BaseUpsertEntryZapZap
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            // Deserialize JSON header to SharedSecretEncryptedFileHeader
            val header =
                OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)

            // Call BaseUpsertEntryZapZap function with null cursor
            processor.baseUpsertEntryZapZap(
                identityId = identityId,
                driveId = driveId,
                fileHeader = header,
                cursor = null
            )

            // Verify the main record was inserted
            val retrievedRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            assertNotNull(
                retrievedRecord,
                "Record should exist after BaseUpsertEntryZapZap with null cursor"
            )
            assertEquals(identityId, retrievedRecord.identityId)
            assertEquals("test-sender", retrievedRecord.senderId)
        }
    }

    @Test
    @Ignore // per michael, he will fix
    fun testBaseUpsertEntryZapZapWithExistingTags() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Test data
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val uniqueId = Uuid.random()
            val globalId = Uuid.random()
            val currentTime = Clock.System.now().epochSeconds

            // First, insert some existing tags to test deletion
            val existingTagId1 = Uuid.random()
            val existingTagId2 = Uuid.random()

            dbm.withWriteTransaction { db ->
                db.driveTagIndexQueries.insertTag(
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                    tagId = existingTagId1
                )

                db.driveTagIndexQueries.insertTag(
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                    tagId = existingTagId2
                )
            }

            // Create new tag records (different from existing)
            val newTagId1 = Uuid.random()
            val newTagId2 = Uuid.random()

            // Create JSON header with all required fields for SharedSecretEncryptedFileHeader
            val jsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "${globalId}",
                    "created": ${currentTime}000,
                    "updated": ${currentTime}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "test-sender",
                    "originalAuthor": "test-sender",
                    "appData": {
                        "uniqueId": "${uniqueId}",
                        "tags": ["${newTagId1}", "${newTagId2}"],
                        "fileType": 1,
                        "dataType": 1,
                        "groupId": null,
                        "userDate": ${currentTime}000,
                        "content": "test content",
                        "archivalStatus": 1
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
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
                    "allowDistribution": false,
                    "fileSystemType": "standard",
                    "fileByteCount": 1000,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 300,
                "fileByteCount": 1000
            }"""

            // Create FileMetadataProcessor instance to test BaseUpsertEntryZapZap
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            // Deserialize JSON header to SharedSecretEncryptedFileHeader
            val fileHeader =
                OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)

            processor.baseUpsertEntryZapZap(
                identityId = identityId,
                driveId = driveId,
                fileHeader = fileHeader,
                cursor = null
            )

            // Verify the main record was inserted
            val retrievedRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            assertNotNull(retrievedRecord, "Record should exist after BaseUpsertEntryZapZap")
            assertEquals(identityId, retrievedRecord.identityId)
            assertEquals("test-sender", retrievedRecord.senderId)

            // Verify that old tags were deleted and new tags were inserted
            val finalTags = dbm.driveTagIndex.selectByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            println("fileId: $fileId")
            println("finalTags: $finalTags")
            assertEquals(
                2,
                finalTags.size,
                "Should have exactly 2 tags after BaseUpsertEntryZapZap"
            )

            val finalTagIds = finalTags.map { it.tagId }.toSet()
            assertTrue(finalTagIds.contains(newTagId1), "Should contain new tag 1")
            assertTrue(finalTagIds.contains(newTagId2), "Should contain new tag 2")
            assertFalse(finalTagIds.contains(existingTagId1), "Should not contain old tag 1")
            assertFalse(finalTagIds.contains(existingTagId2), "Should not contain old tag 2")
        }
    }

    @Test
    fun testConvertDriveMainIndexRecordToFileHeader_RoundTrip() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Test data
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val currentTime = Clock.System.now().epochSeconds

            // Create JSON header with all required fields for SharedSecretEncryptedFileHeader
            val jsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "52a491ac-9870-4d0c-94a1-1bf667393015",
                    "created": ${currentTime}000,
                    "updated": ${currentTime}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "test-sender",
                    "originalAuthor": "test-sender",
                    "appData": {
                        "uniqueId": "55d2e47e-ec86-f9b8-1e3d-d7bdeeb0527b",
                        "tags": null,
                        "fileType": 1,
                        "dataType": 1,
                        "groupId": null,
                        "userDate": ${currentTime}000,
                        "content": "test content",
                        "archivalStatus": 1
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
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
                    "allowDistribution": false,
                    "fileSystemType": "standard",
                    "fileByteCount": 1000,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 300,
                "fileByteCount": 1000
            }"""

            // Create FileHeaderProcessor instance
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            // Deserialize JSON header to SharedSecretEncryptedFileHeader
            val originalHeader =
                OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)

            // Convert to DriveMainIndex record
            val driveMainIndexRecord = processor.convertFileHeaderToDriveMainIndexRecord(
                identityId,
                driveId,
                originalHeader
            )

            // Convert back to SharedSecretEncryptedFileHeader
            val reconstructedHeader =
                processor.convertDriveMainIndexRecordToFileHeader(driveMainIndexRecord)

            // Verify round-trip conversion preserves all data
            assertEquals(originalHeader.fileId, reconstructedHeader.fileId)
            assertEquals(originalHeader.driveId, reconstructedHeader.driveId)
            assertEquals(originalHeader.fileState, reconstructedHeader.fileState)
            assertEquals(originalHeader.fileSystemType, reconstructedHeader.fileSystemType)
            assertEquals(originalHeader.serverFileIsEncrypted, reconstructedHeader.serverFileIsEncrypted)
            assertContentEquals(
                originalHeader.keyHeader.iv,
                reconstructedHeader.keyHeader.iv
            )
            assertContentEquals(
                originalHeader.keyHeader.aesKey.unsafeBytes,
                reconstructedHeader.keyHeader.aesKey.unsafeBytes
            )

            // Verify file metadata
            assertEquals(
                originalHeader.fileMetadata.globalTransitId,
                reconstructedHeader.fileMetadata.globalTransitId
            )
            assertEquals(
                originalHeader.fileMetadata.created,
                reconstructedHeader.fileMetadata.created
            )
            assertEquals(
                originalHeader.fileMetadata.updated,
                reconstructedHeader.fileMetadata.updated
            )
            assertEquals(
                originalHeader.fileMetadata.isEncrypted,
                reconstructedHeader.fileMetadata.isEncrypted
            )
            assertEquals(
                originalHeader.fileMetadata.senderOdinId,
                reconstructedHeader.fileMetadata.senderOdinId
            )
            assertEquals(
                originalHeader.fileMetadata.originalAuthor,
                reconstructedHeader.fileMetadata.originalAuthor
            )

            // Verify app data
            assertEquals(
                originalHeader.fileMetadata.appData.uniqueId,
                reconstructedHeader.fileMetadata.appData.uniqueId
            )
            assertEquals(
                originalHeader.fileMetadata.appData.fileType,
                reconstructedHeader.fileMetadata.appData.fileType
            )
            assertEquals(
                originalHeader.fileMetadata.appData.dataType,
                reconstructedHeader.fileMetadata.appData.dataType
            )
            assertEquals(
                originalHeader.fileMetadata.appData.userDate,
                reconstructedHeader.fileMetadata.appData.userDate
            )
            assertEquals(
                originalHeader.fileMetadata.appData.content,
                reconstructedHeader.fileMetadata.appData.content
            )
            assertEquals(
                originalHeader.fileMetadata.appData.archivalStatus,
                reconstructedHeader.fileMetadata.appData.archivalStatus
            )

            // Verify server metadata
            assertEquals(
                originalHeader.serverMetadata.accessControlList?.requiredSecurityGroup,
                reconstructedHeader.serverMetadata.accessControlList?.requiredSecurityGroup
            )
            //assertEquals(originalHeader.serverMetadata.doNotIndex, reconstructedHeader.serverMetadata.doNotIndex)
            assertEquals(
                originalHeader.serverMetadata.allowDistribution,
                reconstructedHeader.serverMetadata.allowDistribution
            )
            assertEquals(
                originalHeader.serverMetadata.fileSystemType,
                reconstructedHeader.serverMetadata.fileSystemType
            )
            assertEquals(
                originalHeader.serverMetadata.fileByteCount,
                reconstructedHeader.serverMetadata.fileByteCount
            )
            assertEquals(
                originalHeader.serverMetadata.originalRecipientCount,
                reconstructedHeader.serverMetadata.originalRecipientCount
            )

            // Verify other fields
            assertEquals(originalHeader.priority, reconstructedHeader.priority)
            assertEquals(originalHeader.fileByteCount, reconstructedHeader.fileByteCount)
        }
    }

    @Test
    fun testBaseUpsertEntryZapZapOnConflictResolution() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Test data setup
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val currentTime = Clock.System.now().epochSeconds

            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            // Insert initial record
            val fileId = Uuid.random()
            val uniqueId1 = Uuid.random()
            val globalId1 = Uuid.random()

            val initialJsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "${globalId1}",
                    "created": ${currentTime}000,
                    "updated": ${currentTime}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "initial-sender",
                    "originalAuthor": "initial-sender",
                    "appData": {
                        "uniqueId": "${uniqueId1}",
                        "tags": null,
                        "fileType": 1,
                        "dataType": 1,
                        "groupId": null,
                        "userDate": ${currentTime}000,
                        "content": "initial content",
                        "archivalStatus": 1
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
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
                    "allowDistribution": false,
                    "fileSystemType": "standard",
                    "fileByteCount": 1000,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 300,
                "fileByteCount": 1000
            }"""

            val initialHeader = OdinSystemSerializer.deserialize<HomebaseFile>(initialJsonHeader)
            processor.baseUpsertEntryZapZap(identityId, driveId, initialHeader, null)

            // Verify initial record
            val initialRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(identityId, driveId, fileId)
            assertNotNull(initialRecord)
            assertEquals("initial-sender", initialRecord.senderId)
            assertEquals(uniqueId1, initialRecord.uniqueId)
            assertEquals(globalId1, initialRecord.globalTransitId)

            // ===== TEST 1: ON CONFLICT (identityId,driveId,fileId) =====
            // Now upsert with SAME fileId but different data (should update via fileId conflict)
            val updatedJsonHeader = """{
                "fileId": "${fileId}",
                "driveId": "${driveId}",
                "fileState": "active",
                "fileSystemType": "standard",
                "serverFileIsEncrypted":"true",
                "keyHeader" : {
                    "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                    "aesKey" : {
                      "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
                    }
                  },
                "fileMetadata": {
                    "globalTransitId": "${globalId1}",
                    "created": ${(currentTime + 1)}000,
                    "updated": ${(currentTime + 1)}000,
                    "transitCreated": 0,
                    "transitUpdated": 0,
                    "serverFileIsEncrypted": true,
                    "senderOdinId": "updated-sender",
                    "originalAuthor": "updated-sender",
                    "appData": {
                        "uniqueId": "${uniqueId1}",
                        "tags": null,
                        "fileType": 2,
                        "dataType": 2,
                        "groupId": null,
                        "userDate": ${(currentTime + 1)}000,
                        "content": "updated content",
                        "archivalStatus": 2
                    },
                    "localAppData": null,
                    "referencedFile": null,
                    "reactionPreview": null,
                    "versionTag": "2355aa19-2031-d800-403d-e8696a8be494",
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
                    "allowDistribution": false,
                    "fileSystemType": "standard",
                    "fileByteCount": 2000,
                    "originalRecipientCount": 0,
                    "transferHistory": null
                },
                "priority": 400,
                "fileByteCount": 2000
            }"""

            val updatedHeader = OdinSystemSerializer.deserialize<HomebaseFile>(updatedJsonHeader)
            processor.baseUpsertEntryZapZap(identityId, driveId, updatedHeader, null)

            // Verify fileId conflict updated the record
            val updatedRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(identityId, driveId, fileId)
            assertNotNull(updatedRecord)
            assertEquals("updated-sender", updatedRecord.senderId, "ON CONFLICT fileId should update senderId")
            assertEquals(2L, updatedRecord.fileType, "ON CONFLICT fileId should update fileType")
            assertEquals(uniqueId1, updatedRecord.uniqueId, "uniqueId should remain unchanged")
            assertEquals(globalId1, updatedRecord.globalTransitId, "globalTransitId should remain unchanged")

            // Count should still be 1 (updated, not inserted)
            val totalRecords = dbm.driveMainIndex.countAll()
            assertEquals(1L, totalRecords, "Should still have 1 record after fileId conflict update")
        }
    }

//    @Test
//    fun testBaseUpsertEntryZapZapUniqueIdConflict() = runTest {
//        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
//            // Test data setup
//            val identityId = Uuid.random()
//            val driveId = Uuid.random()
//            val currentTime = Clock.System.now().epochSeconds
//
//            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
//
//            // ===== TEST: ON CONFLICT (identityId,driveId,uniqueId) =====
//            // First, insert a record with a specific uniqueId, and NO fileId
//            val fileId1 = Uuid.random()
//            val sharedUniqueId = Uuid.random()
//            val globalId1 = Uuid.random()
//
//            val initialJsonHeader = """{
//                "fileId": "${fileId1}",
//                "driveId": "${driveId}",
//                "fileState": "active",
//                "fileSystemType": "standard",
//                "sharedSecretEncryptedKeyHeader": {
//                    "encryptionVersion": 1,
//                    "type": "aes",
//                    "iv": "fA2HYW8SoHnP3oMxgPcckA==",
//                    "encryptedAesKey": "lCGJ4kL+OC2I+Q1YIvkTVU/GUpmVHAMA+axkwZQJxu5tGHAQd2CLzEzGX0X2pcyE"
//                },
//                "fileMetadata": {
//                    "globalTransitId": "${globalId1}",
//                    "created": ${currentTime}000,
//                    "updated": ${currentTime}000,
//                    "transitCreated": 0,
//                    "transitUpdated": 0,
//                    "serverFileIsEncrypted": true,
//                    "senderOdinId": "initial-sender",
//                    "originalAuthor": "initial-sender",
//                    "appData": {
//                        "uniqueId": "${sharedUniqueId}",
//                        "tags": null,
//                        "fileType": 1,
//                        "dataType": 1,
//                        "groupId": null,
//                        "userDate": ${currentTime}000,
//                        "content": "initial content",
//                        "archivalStatus": 1
//                    },
//                    "localAppData": null,
//                    "referencedFile": null,
//                    "reactionPreview": null,
//                    "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
//                    "payloads": [],
//                    "dataSource": null
//                },
//                "serverMetadata": {
//                    "accessControlList": {
//                        "requiredSecurityGroup": "owner",
//                        "circleIdList": null,
//                        "odinIdList": null
//                    },
//                    "doNotIndex": false,
//                    "allowDistribution": false,
//                    "fileSystemType": "standard",
//                    "fileByteCount": 1000,
//                    "originalRecipientCount": 0,
//                    "transferHistory": null
//                },
//                "priority": 300,
//                "fileByteCount": 1000
//            }"""
//
//            val initialHeader = OdinSystemSerializer.deserialize<SharedSecretEncryptedFileHeader>(initialJsonHeader)
//            processor.baseUpsertEntryZapZap(identityId, driveId, initialHeader, null)
//
//            // Verify initial record
//            val initialRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndUnique(identityId, driveId, sharedUniqueId)
//            assertNotNull(initialRecord)
//            assertEquals("initial-sender", initialRecord.senderId)
//            assertEquals(sharedUniqueId, initialRecord.uniqueId)
//            assertEquals(globalId1, initialRecord.globalTransitId)
//
//            // Now upsert a record with the SAME uniqueId but DIFFERENT fileId
//            // This should trigger the uniqueId conflict and update the UniqueID reecord
//            // and keep the original fileId - but is this even what we want?!
//            val fileId2 = Uuid.random()
//            val conflictJsonHeader = """{
//                "fileId": "${fileId2}",
//                "driveId": "${driveId}",
//                "fileState": "active",
//                "fileSystemType": "standard",
//                "sharedSecretEncryptedKeyHeader": {
//                    "encryptionVersion": 1,
//                    "type": "aes",
//                    "iv": "fA2HYW8SoHnP3oMxgPcckA==",
//                    "encryptedAesKey": "lCGJ4kL+OC2I+Q1YIvkTVU/GUpmVHAMA+axkwZQJxu5tGHAQd2CLzEzGX0X2pcyE"
//                },
//                "fileMetadata": {
//                    "globalTransitId": "${Uuid.random()}",
//                    "created": ${(currentTime + 1)}000,
//                    "updated": ${(currentTime + 1)}000,
//                    "transitCreated": 0,
//                    "transitUpdated": 0,
//                    "serverFileIsEncrypted": true,
//                    "senderOdinId": "conflict-sender",
//                    "originalAuthor": "conflict-sender",
//                    "appData": {
//                        "uniqueId": "${sharedUniqueId}",
//                        "tags": null,
//                        "fileType": 2,
//                        "dataType": 2,
//                        "groupId": null,
//                        "userDate": ${(currentTime + 1)}000,
//                        "content": "conflict content",
//                        "archivalStatus": 2
//                    },
//                    "localAppData": null,
//                    "referencedFile": null,
//                    "reactionPreview": null,
//                    "versionTag": "2355aa19-2031-d800-403d-e8696a8be494",
//                    "payloads": [],
//                    "dataSource": null
//                },
//                "serverMetadata": {
//                    "accessControlList": {
//                        "requiredSecurityGroup": "owner",
//                        "circleIdList": null,
//                        "odinIdList": null
//                    },
//                    "doNotIndex": false,
//                    "allowDistribution": false,
//                    "fileSystemType": "standard",
//                    "fileByteCount": 2000,
//                    "originalRecipientCount": 0,
//                    "transferHistory": null
//                },
//                "priority": 400,
//                "fileByteCount": 2000
//            }"""
//
//            val conflictHeader = OdinSystemSerializer.deserialize<SharedSecretEncryptedFileHeader>(conflictJsonHeader)
//            processor.baseUpsertEntryZapZap(identityId, driveId, conflictHeader, null)
//
//            // Verify the existing record was updated (ON CONFLICT uniqueId updates existing record, doesn't create new one)
//            val updatedRecord = dbm.driveMainIndex.selectByIdentityAndDriveAndFile(identityId, driveId, fileId1)
//
//            assertNotNull(updatedRecord)
//            // The existing record should have been updated with the new data (since excluded.modified > current.modified)
//            assertEquals("conflict-sender", updatedRecord.senderId, "ON CONFLICT uniqueId should update senderId")
//            assertEquals(2L, updatedRecord.fileType, "ON CONFLICT uniqueId should update fileType")
//            assertEquals(sharedUniqueId, updatedRecord.uniqueId, "uniqueId should remain unchanged")
//            assertEquals(fileId1, updatedRecord.fileId, "fileId should remain unchanged")
//
//            // Count should still be 1 (updated, not inserted)
//            val totalRecords = dbm.driveMainIndex.countAll()
//            assertEquals(1L, totalRecords, "Should still have 1 record after uniqueId conflict update")
//        }
//    }
}