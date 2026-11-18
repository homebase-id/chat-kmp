package id.homebase.api.sync.database

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatMentionIndexWrapperTest {
    private val identityId = Uuid.random()
    private val driveId = Uuid.random()
    private val groupId = Uuid.random()
    private val self = "test.self"

    private fun message(
        uniqueId: Uuid,
        userDateMs: Long,
        author: String = "test.sender",
        fileState: String = "active",
        archivalStatus: Int = 0,
        updatedMs: Long = userDateMs,
    ): HomebaseFile = OdinSystemSerializer.deserialize<HomebaseFile>(
        """{
            "driveId": "$driveId", "fileId": "${Uuid.random()}", "fileState": "$fileState",
            "fileSystemType": "standard", "serverFileIsEncrypted": true,
            "keyHeader": {"iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0], "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}},
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}", "created": $userDateMs, "updated": $updatedMs,
                "transitCreated": 0, "transitUpdated": 0, "serverFileIsEncrypted": true,
                "senderOdinId": "$author", "originalAuthor": "$author",
                "appData": {
                    "uniqueId": "$uniqueId", "tags": null, "fileType": 7878, "dataType": 0,
                    "groupId": "$groupId", "userDate": $userDateMs, "content": "hi",
                    "previewThumbnail": null, "archivalStatus": $archivalStatus
                },
                "localAppData": null, "referencedFile": null, "reactionPreview": null,
                "versionTag": "${Uuid.random()}", "payloads": [], "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {"requiredSecurityGroup": "owner", "circleIdList": null, "odinIdList": null},
                "doNotIndex": false, "allowDistribution": false, "fileSystemType": "standard",
                "fileByteCount": 1000, "originalRecipientCount": 0, "transferHistory": null
            },
            "priority": 300, "fileByteCount": 1000
        }"""
    )

    private suspend fun DatabaseManager.insert(file: HomebaseFile) {
        val record = MainIndexMetaHelpers.HomebaseFileProcessor(this)
            .convertFileHeaderToDriveMainIndexRecord(identityId, driveId, file)
        MainIndexMetaHelpers.upsertDriveMainIndex(this, record)
    }

    private fun scanned(uniqueId: Uuid, userDateMs: Long, mentionsMe: Boolean) =
        ScannedMessage(driveId, uniqueId, groupId, userDateMs, mentionsMe)

    @Test
    fun unscannedUnread_skipsSelfReadAndAlreadyScanned() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val fresh = Uuid.random()
            val scannedAlready = Uuid.random()
            val own = Uuid.random()
            val read = Uuid.random()
            dbm.insert(message(fresh, 3000))
            dbm.insert(message(scannedAlready, 4000))
            dbm.insert(message(own, 5000, author = self))
            dbm.insert(message(read, 1000))
            dbm.chatReadCount.upsertLastReadTime(groupId, UnixTimeUtc(2000))
            dbm.chatMentionIndex.record(identityId, listOf(scanned(scannedAlready, 4000, false)))

            val rows = dbm.chatMentionIndex.selectUnscannedUnread(identityId, self)

            assertEquals(listOf(fresh), rows.map { it.uniqueId })
        }
    }

    @Test
    fun unreadMentionCounts_followLastReadAndIgnoreNonMentions() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val oldMention = Uuid.random()
            val newMention = Uuid.random()
            val plain = Uuid.random()
            listOf(oldMention to 1000L, newMention to 3000L, plain to 4000L).forEach { (id, t) ->
                dbm.insert(message(id, t))
            }
            dbm.chatMentionIndex.record(
                identityId,
                listOf(scanned(oldMention, 1000, true), scanned(newMention, 3000, true), scanned(plain, 4000, false)),
            )

            assertEquals(mapOf(groupId to 2), dbm.chatMentionIndex.selectUnreadMentionCounts(identityId))

            dbm.chatReadCount.upsertLastReadTime(groupId, UnixTimeUtc(2000))
            assertEquals(mapOf(groupId to 1), dbm.chatMentionIndex.selectUnreadMentionCounts(identityId))

            dbm.chatReadCount.upsertLastReadTime(groupId, UnixTimeUtc(5000))
            assertEquals(emptyMap(), dbm.chatMentionIndex.selectUnreadMentionCounts(identityId))
        }
    }

    @Test
    fun deletedMessage_stopsCounting() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val id = Uuid.random()
            dbm.insert(message(id, 1000))
            dbm.chatMentionIndex.record(identityId, listOf(scanned(id, 1000, true)))
            assertEquals(1, dbm.chatMentionIndex.selectUnreadMentionCounts(identityId)[groupId])

            dbm.insert(message(id, 1000, fileState = "deleted", updatedMs = 9000))
            val after = dbm.chatMentionIndex.selectUnreadMentionCounts(identityId)[groupId]
            assertEquals(null, after)
        }
    }

    @Test
    fun oldestMentionAfter_walksForwardOldestFirst() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val a = Uuid.random()
            val b = Uuid.random()
            val c = Uuid.random()
            listOf(a to 1000L, b to 2000L, c to 3000L).forEach { (id, t) -> dbm.insert(message(id, t)) }
            dbm.chatMentionIndex.record(
                identityId,
                listOf(scanned(a, 1000, true), scanned(b, 2000, false), scanned(c, 3000, true)),
            )

            val index = dbm.chatMentionIndex
            assertEquals(a, index.selectOldestMentionAfter(identityId, groupId, 0)?.uniqueId)
            assertEquals(c, index.selectOldestMentionAfter(identityId, groupId, 1000)?.uniqueId)
            assertNull(index.selectOldestMentionAfter(identityId, groupId, 3000))
        }
    }
}
