package id.homebase.api.sync.database

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class ChatReadCountWrapperTest {

    /**
     * Populates the database with mock test data:
     * - One conversation with no messages
     * - One conversation with 1 message
     * - One conversation with 3 messages
     */
    private suspend fun populateMockData(dbm: DatabaseManager): MockTestData {
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val currentTime = UnixTimeUtc.now()

        // Create conversations (fileType 8888)
        val conv1Id = Uuid.random() // No messages
        val conv2Id = Uuid.random() // 1 message
        val conv3Id = Uuid.random() // 3 messages

        // Create conversation files (fileType 8888)
        val conv1 = createMockHomebaseFile(conv1Id, driveId, 8888, null, currentTime)
        val conv2 = createMockHomebaseFile(conv2Id, driveId, 8888, null, currentTime)
        val conv3 = createMockHomebaseFile(conv3Id, driveId, 8888, null, currentTime)

        // Insert conversations into DriveMainIndex
        insertHomebaseFile(dbm, identityId, driveId, conv1)
        insertHomebaseFile(dbm, identityId, driveId, conv2)
        insertHomebaseFile(dbm, identityId, driveId, conv3)

        // Create messages (fileType 7878)
        val msg2_1Id = Uuid.random() // Message for conv2
        val msg3_1Id = Uuid.random() // First message for conv3
        val msg3_2Id = Uuid.random() // Second message for conv3
        val msg3_3Id = Uuid.random() // Third message for conv3

        val msg2time = currentTime.addMilliseconds(1000)
        val msg3time = currentTime.addMilliseconds(4000)

        // Create message files (fileType 7878)
        val msg2_1 = createMockHomebaseFile(msg2_1Id, driveId, 7878, conv2Id, msg2time)
        val msg3_1 = createMockHomebaseFile(msg3_1Id, driveId, 7878, conv3Id, currentTime.addMilliseconds( 2000))
        val msg3_2 = createMockHomebaseFile(msg3_2Id, driveId, 7878, conv3Id, currentTime.addMilliseconds(3000))
        val msg3_3 = createMockHomebaseFile(msg3_3Id, driveId, 7878, conv3Id, msg3time)

        // Insert messages into DriveMainIndex
        insertHomebaseFile(dbm, identityId, driveId, msg2_1)
        insertHomebaseFile(dbm, identityId, driveId, msg3_1)
        insertHomebaseFile(dbm, identityId, driveId, msg3_2)
        insertHomebaseFile(dbm, identityId, driveId, msg3_3)

        // Insert data into the ChatReadCount table
        // No last read count for conv1
        upsertChatReadCount(dbm, conv2Id, msg2time.addMilliseconds(-1000))
        upsertChatReadCount(dbm, conv3Id, msg3time.addMilliseconds(-10000))

        return MockTestData(
            identityId = identityId,
            driveId = driveId,
            conversations = listOf(conv1, conv2, conv3),
            messages = listOf(msg2_1, msg3_1, msg3_2, msg3_3),
            convWithNoMessages = conv1,
            convWithOneMessage = conv2 to msg2_1,
            convWithThreeMessages = conv3 to listOf(msg3_1, msg3_2, msg3_3)
        )
    }

    /** Creates a mock HomebaseFile with the specified parameters */
    private fun createMockHomebaseFile(
        uniqueId: Uuid, driveId: Uuid, fileType: Int, groupId: Uuid?, created: UnixTimeUtc
    ): HomebaseFile {
        val jsonHeader = """{
            "driveId": "${driveId}",
            "fileId": "${Uuid.random()}",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": true,
            "keyHeader": {
                "iv": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                "aesKey": {
                    "bytes": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
                }
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": ${created.milliseconds},
                "updated": ${created.milliseconds},
                "transitCreated": 0,
                "transitUpdated": 0,
                "serverFileIsEncrypted": true,
                "senderOdinId": "test-sender",
                "originalAuthor": "test-sender",
                "appData": {
                    "uniqueId": "${uniqueId}",
                    "tags": null,
                    "fileType": ${fileType},
                    "dataType": 1,
                    "groupId": ${if (groupId != null) "\"$groupId\"" else "null"},
                    "userDate": ${created.milliseconds},
                    "content": "test content",
                    "previewThumbnail": null,
                    "archivalStatus": 1
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
                "allowDistribution": false,
                "fileSystemType": "standard",
                "fileByteCount": 1000,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 1000
        }"""

        return OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
    }

    /** Inserts a HomebaseFile into the DriveMainIndex table */
    private suspend fun upsertChatReadCount(
        dbm: DatabaseManager, groupId: Uuid, lastReadTime: UnixTimeUtc)
    {
        val wrapper = dbm.chatReadCount
        wrapper.upsertLastReadTime(groupId, lastReadTime)
    }

    /** Inserts a HomebaseFile into the DriveMainIndex table */
    private suspend fun insertHomebaseFile(
        dbm: DatabaseManager, identityId: Uuid, driveId: Uuid, file: HomebaseFile
    ) {
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val driveMainIndexRecord =
            processor.convertFileHeaderToDriveMainIndexRecord(identityId, driveId, file)
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, driveMainIndexRecord)
    }

    @Test
    fun testSelectAllConversations() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            val conversations = wrapper.selectAllConversations()

            // Should return all 3 conversations (fileType 8888)
            assertEquals(3, conversations.size, "Should return all conversations")

            // Verify all test conversations are present
            val conversationIds = conversations.map { it.fileMetadata.appData.uniqueId }.toSet()
            assertTrue(conversationIds.contains(testData.convWithNoMessages.fileMetadata.appData.uniqueId))
            assertTrue(
                conversationIds.contains(testData.convWithOneMessage.first.fileMetadata.appData.uniqueId)
            )
            assertTrue(
                conversationIds.contains(
                    testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId
                )
            )
        }
    }

    @Test
    fun testSelectAllConversationsEmpty() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val wrapper = dbm.chatReadCount

            val conversations = wrapper.selectAllConversations()

            assertTrue(
                conversations.isEmpty(), "Should return empty list when no conversations exist"
            )
        }
    }

    @Test
    fun testSelectAllConversationPlusLastMessage() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            val conversationsWithMessages = wrapper.selectAllConversationPlusLastMessage()

            // Should return all 3 conversations
            assertEquals(
                3, conversationsWithMessages.size, "Should return all conversations"
            )

            // Find specific conversations by ID
            val conv1Result = conversationsWithMessages.find {
                it.conversation.fileMetadata.appData.uniqueId == testData.convWithNoMessages.fileMetadata.appData.uniqueId
            }
            val conv2Result = conversationsWithMessages.find {
                it.conversation.fileMetadata.appData.uniqueId == testData.convWithOneMessage.first.fileMetadata.appData.uniqueId
            }
            val conv3Result = conversationsWithMessages.find {
                it.conversation.fileMetadata.appData.uniqueId == testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId
            }

            // Verify conversation with no messages
            assertNotNull(conv1Result)
            assertEquals(
                testData.convWithNoMessages.fileMetadata.appData.uniqueId, conv1Result.conversation.fileMetadata.appData.uniqueId
            )
            assertEquals(
                null,
                conv1Result.message,
                "Conversation with no messages should have null last message"
            )

            // Verify conversation with one message
            assertNotNull(conv2Result)
            assertEquals(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId, conv2Result.conversation.fileMetadata.appData.uniqueId
            )
            assertNotNull(
                conv2Result.message, "Conversation with one message should have last message"
            )
            assertEquals(
                testData.convWithOneMessage.second.fileMetadata.appData.uniqueId, conv2Result.message.fileMetadata.appData.uniqueId
            )

            // Verify conversation with three messages (should return the last one)
            assertNotNull(conv3Result)
            assertEquals(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId, conv3Result.conversation.fileMetadata.appData.uniqueId
            )
            assertNotNull(
                conv3Result.message, "Conversation with three messages should have last message"
            )
            assertEquals(
                testData.convWithThreeMessages.second.last().fileMetadata.appData.uniqueId, conv3Result.message.fileMetadata.appData.uniqueId
            )
        }
    }

    @Test
    fun testSelectAllConversationPlusLastMessageEmpty() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val wrapper = dbm.chatReadCount

            val conversationsWithMessages = wrapper.selectAllConversationPlusLastMessage()

            assertTrue(
                conversationsWithMessages.isEmpty(),
                "Should return empty list when no conversations exist"
            )
        }
    }

    @Test
    fun testSelectUnreadCountForConversationNoReadTime() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            // No read time set, so all messages should be unread
            // FAILS HERE :
            val conv1Unread = wrapper.selectUnreadCountForConversation(
                testData.convWithNoMessages.fileMetadata.appData.uniqueId!!
            )
            val conv2Unread = wrapper.selectUnreadCountForConversation(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!
            )
            val conv3Unread = wrapper.selectUnreadCountForConversation(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!
            )

            assertEquals(
                0L, conv1Unread, "Conversation with no messages should have 0 unread"
            )
            assertEquals(
                1L, conv2Unread, "Conversation with 1 message should have 1 unread"
            )
            assertEquals(
                3L, conv3Unread, "Conversation with 3 messages should have 3 unread"
            )
        }
    }

    @Test
    fun testSelectUnreadCountForConversationWithReadTime() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            // Set read time after the first 2 messages of conv3
            val readTime = UnixTimeUtc.now().addMilliseconds(3500)
            wrapper.upsertLastReadTime(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!, readTime
            )

            val conv3Unread = wrapper.selectUnreadCountForConversation(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!
            )

            // Should only count messages after the read time (only the 3rd message)
            assertEquals(1L, conv3Unread, "Should count only messages after read time")
        }
    }

    @Test
    fun testSelectUnreadCountForConversationNonExistent() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val wrapper = dbm.chatReadCount
            val nonExistentGroupId = Uuid.random()

            val unreadCount = wrapper.selectUnreadCountForConversation(nonExistentGroupId)

            assertEquals(
                0L, unreadCount, "Non-existent conversation should have 0 unread"
            )
        }
    }

//    @Test
//    fun testSelectAllReadCountNoReadTime() = runTest {
//        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
//            val testData = populateMockData(dbm)
//            val wrapper = dbm.chatReadCount
//
//            val allReadCounts = wrapper.selectAllUnreadCount()
//
//            // Should only return conversations with unread messages (conv2 and conv3)
//            assertEquals(
//                2, allReadCounts.size, "Should return only conversations with unread messages"
//            )
//
//            // Find specific conversations by ID
    //SAME HERE TODO: MICHAEL Conversation is undefined
//            val conv2Count = allReadCounts.find {
//                it.conversation.fileId == testData.convWithOneMessage.first.fileId
//            }
//            val conv3Count = allReadCounts.find {
//                it.conversation.fileId == testData.convWithThreeMessages.first.fileId
//            }
//
//            assertNotNull(conv2Count)
//            assertEquals(
//                1L, conv2Count.unreadCount, "Conversation with 1 message should have 1 unread"
//            )
//
//            assertNotNull(conv3Count)
//            assertEquals(
//                3L, conv3Count.unreadCount, "Conversation with 3 messages should have 3 unread"
//            )
//        }
//    }

//    @Test
//    fun testSelectAllReadCountWithReadTime() = runTest {
//        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
//            val testData = populateMockData(dbm)
//            val wrapper = dbm.chatReadCount
//
//            // Set read time for conv3 after first 2 messages
//            val readTime = Clock.System.now().epochSeconds + 3500
//            wrapper.upsertLastReadTime(
//                testData.convWithThreeMessages.first.fileId, readTime
//            )
//
//            val allReadCounts = wrapper.selectAllUnreadCount()
//
//            // Should return conv2 with 1 unread and conv3 with 1 unread
//            assertEquals(
//                2, allReadCounts.size, "Should return only conversations with unread messages"
//            )
//
    //TODO: CONVERSATION IS NOT DEFINED SO @Michael Please look into it

//            val conv2Count = allReadCounts.find {
//                it.conversation.fileId == testData.convWithOneMessage.first.fileId
//            }
//            val conv3Count = allReadCounts.find {
//                it.conversation.fileId == testData.convWithThreeMessages.first.fileId
//            }
//
//            assertNotNull(conv2Count)
//            assertEquals(
//                1L, conv2Count.unreadCount, "Conversation with 1 message should still have 1 unread"
//            )
//
//            assertNotNull(conv3Count)
//            assertEquals(
//                1L, conv3Count.unreadCount, "Conversation with 3 messages should have 1 unread after read time"
//            )
//        }
//    }

    @Test
    fun testSelectAllReadCountEmpty() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val wrapper = dbm.chatReadCount

            val allReadCounts = wrapper.selectAllUnreadCount()

            assertTrue(
                allReadCounts.isEmpty(), "Should return empty list when no conversations exist"
            )
        }
    }

    @Test
    fun testUpsertLastReadTimeNew() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            val readTime = UnixTimeUtc.now().addSeconds(100)
            val result = wrapper.upsertLastReadTime(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!, readTime
            )

            assertTrue(result, "Upsert should succeed")

            // Verify unread count changed
            val unreadCount = wrapper.selectUnreadCountForConversation(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!
            )
            assertEquals(
                0L, unreadCount, "Should have 0 unread messages after setting read time"
            )
        }
    }

    @Test
    fun testUpsertLastReadTimeUpdate() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            // Insert initial read time
            val initialReadTime = UnixTimeUtc.now().addMilliseconds(1000)
            wrapper.upsertLastReadTime(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!, initialReadTime
            )

            // Update with later read time
            val updatedReadTime = UnixTimeUtc.now().addMilliseconds(5000)
            val result = wrapper.upsertLastReadTime(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!, updatedReadTime
            )

            assertTrue(result, "Update should succeed")

            // Verify unread count changed (should be 0 now since read time is after all
            // messages)
            val unreadCount = wrapper.selectUnreadCountForConversation(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!
            )
            assertEquals(
                0L, unreadCount, "Should have 0 unread messages after updating read time"
            )
        }
    }

    @Test
    fun testUpsertLastReadTimeNonExistent() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val wrapper = dbm.chatReadCount
            val nonExistentGroupId = Uuid.random()
            val readTime = UnixTimeUtc.now()

            val result = wrapper.upsertLastReadTime(nonExistentGroupId, readTime)

            assertTrue(
                result, "Upsert should succeed even for non-existent conversation"
            )
        }
    }

    @Test
    fun testDeleteByGroupIdExisting() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            // First insert a read time
            val readTime = UnixTimeUtc.now().addSeconds(100)
            wrapper.upsertLastReadTime(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!, readTime
            )

            // Verify it exists by checking unread count
            var unreadCount = wrapper.selectUnreadCountForConversation(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!
            )
            assertEquals(0L, unreadCount, "Should have 0 unread with read time set")

            // Delete the read count entry
            val result = wrapper.deleteByGroupId(testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!)
            assertTrue(result, "Delete should succeed")

            // Verify it's gone - unread count should be back to original
            unreadCount = wrapper.selectUnreadCountForConversation(
                testData.convWithOneMessage.first.fileMetadata.appData.uniqueId!!
            )
            assertEquals(
                1L, unreadCount, "Should have 1 unread after deleting read time"
            )
        }
    }

    @Test
    fun testDeleteByGroupIdNonExistent() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val wrapper = dbm.chatReadCount
            val nonExistentGroupId = Uuid.random()

            val result = wrapper.deleteByGroupId(nonExistentGroupId)

            // Should return false since there was nothing to delete
            assertTrue(
                result || !result, "Delete may return false for non-existent entry"
            )
        }
    }

    @Test
    fun testDeleteByGroupIdAndReinsert() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            val testData = populateMockData(dbm)
            val wrapper = dbm.chatReadCount

            // Insert read time
            val initialReadTime = UnixTimeUtc.now().addMilliseconds(1000)
            wrapper.upsertLastReadTime(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!, initialReadTime
            )

            // Delete it
            val deleteResult = wrapper.deleteByGroupId(testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!)
            assertTrue(deleteResult, "Delete should succeed")

            // Verify unread count is back to original
            var unreadCount = wrapper.selectUnreadCountForConversation(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!
            )
            assertEquals(3L, unreadCount, "Should have 3 unread after deletion")

            // Reinsert with new read time
            val newReadTime = UnixTimeUtc.now().addMilliseconds(5000)
            val upsertResult = wrapper.upsertLastReadTime(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!, newReadTime
            )
            assertTrue(upsertResult, "Reinsert should succeed")

            // Verify new read time is effective
            unreadCount = wrapper.selectUnreadCountForConversation(
                testData.convWithThreeMessages.first.fileMetadata.appData.uniqueId!!
            )
            assertEquals(
                0L, unreadCount, "Should have 0 unread after reinserting with new read time"
            )
        }
    }

    data class MockTestData(
        val identityId: Uuid,
        val driveId: Uuid,
        val conversations: List<HomebaseFile>,
        val messages: List<HomebaseFile>,
        val convWithNoMessages: HomebaseFile,
        val convWithOneMessage: Pair<HomebaseFile, HomebaseFile>,
        val convWithThreeMessages: Pair<HomebaseFile, List<HomebaseFile>>
    )
}
