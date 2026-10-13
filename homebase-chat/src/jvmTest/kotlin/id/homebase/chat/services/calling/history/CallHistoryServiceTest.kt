@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.services.calling.history

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallLogDirection
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.services.ChatMessageSenderServiceTestFixture
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.calling.CallsPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CallHistoryServiceTest {
    private val base = 1_700_000_000_000L

    private fun TestScope.service(
        f: ChatMessageSenderServiceTestFixture,
        pageSize: Int = 100,
        nowMs: () -> Long = { base + 1_000_000 },
    ): CallHistoryService = CallHistoryService(
        credentialsManager = f.credentialsManager,
        dbm = f.dbm,
        eventBus = f.eventBus,
        conversations = f.conversationLookup,
        preferences = CallsPreferences(f.dbm),
        scope = backgroundScope,
        nowMs = nowMs,
        pageSize = pageSize,
    ).also { runCurrent() }

    private fun descriptor(outcome: CallOutcome, video: Boolean = false, seconds: Long = 0) =
        OdinSystemSerializer.serialize(CallEventDescriptor(outcome, video, seconds))

    private fun header(
        f: ChatMessageSenderServiceTestFixture,
        conversationId: Uuid,
        userDateMs: Long,
        content: String,
        author: String,
        uniqueId: Uuid = Uuid.random(),
        dataType: Int = ChatProtocol.ChatCallEventMessageDataType,
        fileState: String = "active",
        fileId: Uuid = Uuid.random(),
        updatedMs: Long = userDateMs,
    ): HomebaseFile = OdinSystemSerializer.deserialize(
        """{
            "fileId": "$fileId",
            "driveId": "${f.chatDriveId}",
            "fileState": "$fileState",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {"iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0], "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}},
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $userDateMs, "updated": $updatedMs, "transitCreated": $userDateMs, "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$author", "originalAuthor": "$author",
                "appData": {
                    "uniqueId": "$uniqueId", "tags": null,
                    "fileType": ${ChatProtocol.MessageFileType}, "dataType": $dataType,
                    "groupId": "$conversationId", "userDate": $userDateMs,
                    "content": ${JsonPrimitive(content)},
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

    private suspend fun seed(f: ChatMessageSenderServiceTestFixture, header: HomebaseFile) {
        val record = MainIndexMetaHelpers.HomebaseFileProcessor(f.dbm)
            .convertFileHeaderToDriveMainIndexRecord(f.testIdentityId, f.chatDriveId, header)
        MainIndexMetaHelpers.upsertDriveMainIndex(f.dbm, record)
    }

    private suspend fun seedCall(
        f: ChatMessageSenderServiceTestFixture,
        conversationId: Uuid,
        atMs: Long,
        outcome: CallOutcome = CallOutcome.Completed,
        own: Boolean = false,
        peer: String = "alice.test",
        video: Boolean = false,
        seconds: Long = 0,
        fileState: String = "active",
    ): Uuid {
        val id = Uuid.random()
        seed(f, header(f, conversationId, atMs, descriptor(outcome, video, seconds), if (own) f.testDomain else peer, id, fileState = fileState))
        return id
    }

    @Test
    fun newestFirst_onlyCallEvents_skipsDeletedUnparseableAndGroups() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val c1 = f.seedConversation(others = listOf("alice.test"))
            val c2 = f.seedConversation(others = listOf("bob.test"))
            val c3 = f.seedConversation(others = listOf("carol.test"))
            val group = f.seedConversation(others = listOf("dave.test", "erin.test"))

            val a = seedCall(f, c1, base + 1_000, peer = "alice.test")
            val b = seedCall(f, c2, base + 5_000, own = true, outcome = CallOutcome.NoAnswer)
            val c = seedCall(f, c3, base + 3_000, peer = "carol.test", video = true)
            seedCall(f, c1, base + 4_000, own = true, fileState = "deleted")
            seedCall(f, group, base + 6_000, peer = "dave.test")
            seed(f, header(f, c2, base + 7_000, "not json", "bob.test"))
            seed(f, header(f, c1, base + 8_000, """{"message":"hi","deliveryStatus":20,"isEdited":false,"version":1}""", "alice.test", dataType = 0))

            val svc = service(f)
            svc.refresh()

            assertEquals(listOf(b, c, a), svc.entries.value.map { it.messageId })
            assertEquals(listOf("bob.test", "carol.test", "alice.test"), svc.entries.value.map { it.peer.domainName })
            assertTrue(svc.entries.value[1].video)
            assertFalse(svc.hasMore.value)
        }
    }

    @Test
    fun classification_everyOutcomeOwnAndPeer() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            val expected = mutableMapOf<Uuid, Triple<CallLogDirection, Boolean, CallOutcome>>()
            var t = base
            for (outcome in CallOutcome.entries) for (own in listOf(true, false)) {
                val seconds = if (outcome == CallOutcome.Completed) 42L else 0L
                val id = seedCall(f, convo, t++, outcome, own, seconds = seconds)
                val missed = !own && (outcome == CallOutcome.NoAnswer || outcome == CallOutcome.Cancelled)
                expected[id] = Triple(if (own) CallLogDirection.Outgoing else CallLogDirection.Incoming, missed, outcome)
            }

            val svc = service(f)
            svc.refresh()

            assertEquals(expected.size, svc.entries.value.size)
            for (e in svc.entries.value) {
                val (direction, missed, outcome) = expected.getValue(e.messageId)
                assertEquals(direction, e.direction, "$outcome")
                assertEquals(missed, e.missed, "$outcome $direction")
                assertEquals(outcome, e.outcome)
                assertEquals(OdinId("alice.test"), e.peer)
                assertEquals(convo, e.conversationId)
            }
            assertEquals(CallOutcome.Completed, svc.entries.value.first { it.durationSeconds == 42L }.outcome)
        }
    }

    @Test
    fun paging_cursorReturnsTheRest() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            val ids = (0 until 5).map { seedCall(f, convo, base + it * 1_000L) }

            val svc = service(f, pageSize = 2)
            svc.refresh()
            assertEquals(ids.reversed().take(2), svc.entries.value.map { it.messageId })
            assertTrue(svc.hasMore.value)

            svc.loadMore()
            assertEquals(ids.reversed().take(4), svc.entries.value.map { it.messageId })
            svc.loadMore()
            assertEquals(ids.reversed(), svc.entries.value.map { it.messageId })
            assertFalse(svc.hasMore.value)
        }
    }

    @Test
    fun live_batchPrependsAndDeletionRemoves() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            val old = seedCall(f, convo, base)
            val svc = service(f)
            svc.refresh()

            val newId = Uuid.random()
            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 9_000, descriptor(CallOutcome.NoAnswer), "alice.test", newId),
            )))
            assertEquals(listOf(newId, old), svc.entries.first { it.size == 2 }.map { it.messageId })

            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 9_000, descriptor(CallOutcome.NoAnswer), "alice.test", newId, fileState = "deleted"),
            )))
            assertEquals(listOf(old), svc.entries.first { it.size == 1 }.map { it.messageId })

            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 9_500, """{"message":"x","deliveryStatus":20,"isEdited":false,"version":1}""", "alice.test", dataType = 0),
            )))
            runCurrent()
            assertEquals(1, svc.entries.value.size)
        }
    }

    @Test
    fun optimisticSendShowsWithoutReload() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            val sender = f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            val svc = service(f)
            svc.refresh()
            assertTrue(svc.entries.value.isEmpty())

            sender.sendNewTypedMessage(
                messageUniqueId = Uuid.random(),
                conversationId = convo,
                content = MessageContent.CallEvent(CallEventDescriptor(CallOutcome.Completed, video = true, durationSeconds = 12)),
                previousMessageUniqueId = null,
            )

            val entry = svc.entries.first { it.isNotEmpty() }.single()
            assertEquals(CallLogDirection.Outgoing, entry.direction)
            assertEquals(OdinId("alice.test"), entry.peer)
            assertTrue(entry.video)
            assertEquals(12, entry.durationSeconds)
        }
    }

    @Test
    fun missedCount_ownNeverCounts_markSeenClears_newerCountsAgain() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            seedCall(f, convo, base + 1_000, CallOutcome.NoAnswer, own = true)
            seedCall(f, convo, base + 2_000, CallOutcome.NoAnswer)
            seedCall(f, convo, base + 3_000, CallOutcome.Cancelled)
            seedCall(f, convo, base + 4_000, CallOutcome.Completed)
            seedCall(f, convo, base + 5_000, CallOutcome.Declined)

            val svc = service(f, nowMs = { base + 100_000 })
            svc.refresh()
            assertEquals(2, svc.missedCount.first { it == 2 })

            svc.markSeen()
            assertEquals(0, svc.missedCount.first { it == 0 })

            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 200_000, descriptor(CallOutcome.NoAnswer), "alice.test"),
            )))
            assertEquals(1, svc.missedCount.first { it == 1 })
        }
    }

    @Test
    fun callsBadge_followsMissedCount_clearsOnTabOpened_andOnMissedArrivingWhileOpen() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            var now = base + 100_000
            val svc = service(f, nowMs = { now })
            svc.refresh()
            assertFalse(callsBadgeVisible(svc.missedCount.value))

            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 1_000, descriptor(CallOutcome.NoAnswer), f.testDomain),
            )))
            runCurrent()
            assertEquals(0, svc.missedCount.value)

            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 2_000, descriptor(CallOutcome.NoAnswer), "alice.test"),
            )))
            assertEquals(1, svc.missedCount.first { it == 1 })
            assertTrue(callsBadgeVisible(svc.missedCount.value))

            svc.markSeen()
            assertEquals(0, svc.missedCount.first { it == 0 })
            assertFalse(callsBadgeVisible(svc.missedCount.value))

            now = base + 300_000
            f.eventBus.emit(BackendEvent.DataEvent.BatchReceived(f.chatDriveId, listOf(
                header(f, convo, base + 250_000, descriptor(CallOutcome.NoAnswer), "alice.test"),
            )))
            assertEquals(1, svc.missedCount.first { it == 1 })
            svc.markSeen()
            assertEquals(0, svc.missedCount.first { it == 0 })
        }
    }

    @Test
    fun silentDriveSync_stoppedRequeriesAddsMissedAndDropsSoftDeleted() = runTest {
        ChatMessageSenderServiceTestFixture().use { f ->
            f.build(backgroundScope)
            val convo = f.seedConversation(others = listOf("alice.test"))
            val kept = seedCall(f, convo, base + 1_000, CallOutcome.Completed)
            val doomedFile = Uuid.random()
            val doomed = Uuid.random()
            seed(f, header(f, convo, base + 2_000, descriptor(CallOutcome.Completed), "alice.test", doomed, fileId = doomedFile))
            val svc = service(f, nowMs = { base + 100_000 })
            svc.refresh()
            assertEquals(2, svc.entries.value.size)
            assertEquals(0, svc.missedCount.value)

            val missed = seedCall(f, convo, base + 3_000, CallOutcome.NoAnswer)
            val doomedHeader = header(f, convo, base + 2_000, descriptor(CallOutcome.Completed), "alice.test", doomed, fileState = "deleted", fileId = doomedFile, updatedMs = base + 50_000)
            seed(f, doomedHeader)
            f.eventBus.emit(BackendEvent.DriveEvent.Stopped(f.chatDriveId, 2, BackendEvent.DriveResult.Completed))

            assertEquals(listOf(missed, kept), svc.entries.first { l -> l.any { it.messageId == missed } }.map { it.messageId })
            assertEquals(1, svc.missedCount.first { it == 1 })
        }
    }
}
