@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.services.calling.history

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.calling.history.CallLogDeleter
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.testChatMessageStream
import id.homebase.core.calling.CallsPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CallLogDeleteIntegrationTest {
    private val atMs = 1_700_000_000_000L

    private fun callHeader(f: ChatMessageActionServiceTestFixture, conversationId: Uuid, id: Uuid, fileId: Uuid): HomebaseFile =
        OdinSystemSerializer.deserialize(
            """{
                "fileId": "$fileId", "driveId": "${f.chatDriveId}", "fileState": "active",
                "fileSystemType": "standard", "serverFileIsEncrypted": false,
                "keyHeader": {"iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0], "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}},
                "fileMetadata": {
                    "globalTransitId": "${Uuid.random()}",
                    "created": $atMs, "updated": $atMs, "transitCreated": $atMs, "transitUpdated": 0,
                    "isEncrypted": false, "senderOdinId": "alice.test", "originalAuthor": "alice.test",
                    "appData": {
                        "uniqueId": "$id", "tags": null,
                        "fileType": ${ChatProtocol.MessageFileType}, "dataType": ${ChatProtocol.ChatCallEventMessageDataType},
                        "groupId": "$conversationId", "userDate": $atMs,
                        "content": ${JsonPrimitive(OdinSystemSerializer.serialize(CallEventDescriptor(CallOutcome.Completed, false, 42)))},
                        "previewThumbnail": null, "archivalStatus": 0
                    },
                    "localAppData": null, "referencedFile": null, "reactionPreview": null,
                    "versionTag": "${Uuid.random()}", "payloads": [], "dataSource": null
                },
                "serverMetadata": {
                    "accessControlList": {"requiredSecurityGroup": "owner", "circleIdList": null, "odinIdList": null},
                    "doNotIndex": false, "allowDistribution": true, "fileSystemType": "standard",
                    "fileByteCount": 100, "originalRecipientCount": 0, "transferHistory": null
                },
                "priority": 300, "fileByteCount": 100
            }""",
        )

    @Test
    fun deleteFromHistory_removesTheRowEverywhereAndQueuesAForMeDelete() = runTest {
        ChatMessageActionServiceTestFixture().use { f ->
            val actions = f.build(scope = this)
            val convo = f.seedOneOnOneConversation(other = "alice.test")
            val id = Uuid.random()
            val fileId = Uuid.random()
            f.seedMessage(conversationId = convo, senderDomain = "alice.test", userDateMs = atMs, id = id, fileId = fileId)
            val record = MainIndexMetaHelpers.HomebaseFileProcessor(f.dbm)
                .convertFileHeaderToDriveMainIndexRecord(f.testIdentityId, f.chatDriveId, callHeader(f, convo, id, fileId))
            MainIndexMetaHelpers.upsertDriveMainIndex(f.dbm, record)

            val history = CallHistoryService(
                credentialsManager = f.credentialsManager,
                dbm = f.dbm,
                eventBus = f.eventBus,
                conversations = f.participantLookup,
                preferences = CallsPreferences(f.dbm),
                scope = backgroundScope,
                pageSize = 100,
            )
            history.refresh()
            runCurrent()
            assertEquals(listOf(id), history.entries.value.map { it.messageId })

            val stream = testChatMessageStream(
                dbm = f.dbm,
                credentialsManager = f.credentialsManager,
                eventBus = f.eventBus,
                optimisticWriter = OptimisticWriter(f.credentialsManager, f.dbm, f.eventBus, f.outboxSync),
                scope = backgroundScope,
            )
            stream.loadConversation(convo)
            advanceUntilIdle()
            assertTrue(stream.isMessageInWindow(convo, id), "the call event is a message in its chat before the delete")

            CallLogDeleter { messageId, everyone -> actions.deleteMessage(messageId, everyone) }.delete(id, deleteForEveryone = false)
            advanceUntilIdle()

            assertTrue(history.entries.first { it.isEmpty() }.isEmpty())
            val identityId = f.credentialsManager.requireActiveCredentials().getIdentityId()
            assertTrue(
                f.dbm.driveMainIndex.selectHomebaseFileByUnique(identityId, f.chatDriveId, id)?.isSoftDeleted() == true,
                "DriveMainIndex row is soft-deleted",
            )
            val inChat = stream.messageInWindow(convo, id)
            assertTrue(inChat == null || inChat.isDeleted, "the chat window drops the message or keeps only a deleted tombstone")
            val deletes = f.drainOutbox().filter { it.uploadType == DriveOutboxUploader.DeleteFile }
            val payload = OdinSystemSerializer.deserialize<id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest>(
                deletes.single().json.decodeToString(),
            )
            assertNull(payload.recipients, "for-me delete carries no recipients")
        }
    }
}
