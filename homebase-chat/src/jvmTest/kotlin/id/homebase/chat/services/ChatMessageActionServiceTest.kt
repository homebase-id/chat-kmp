package id.homebase.chat.services

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SendReadReceiptByFileIdsOutboxRequest
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ChatMessageActionService.markAsReadByFiles].
 *
 * The load-bearing invariant: `newReadTime = unreadRecords.maxOf { it.userDate }`
 * must run **after** the filter (self-authored / already-read / deleted /
 * pending-send are excluded). A filtered-out record with a later userDate must
 * not leak into newReadTime or the upserted lastReadTime.
 */
class ChatMessageActionServiceTest {

    @Test
    fun markAsReadByFiles_enqueuesSingleOutboxRequestWithAllUnreadFileIds() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()

            val ids = (1..5).map {
                fixture.seedMessage(
                    conversationId = convoId,
                    senderDomain = "alice.test",
                    userDateMs = 100L * it,
                )
            }

            service.markAsReadByFiles(convoId, ids)

            val readReceiptRows = fixture.drainOutbox()
                .filter { it.uploadType == DriveOutboxUploader.SendReadReceiptByFileIds }
            assertEquals(1, readReceiptRows.size, "expected one outbox row")

            val payload = OdinSystemSerializer.deserialize<SendReadReceiptByFileIdsOutboxRequest>(
                readReceiptRows.single().json.decodeToString()
            )
            val expectedFileIds = fixture.messageLookup.records.map { it.fileId }.toSet()
            assertEquals(expectedFileIds, payload.fileIds.toSet())
        }
    }

    @Test
    fun markAsReadByFiles_pickMaxUserDate_simpleAscending() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()

            val ids = listOf(100L, 200L, 300L).map {
                fixture.seedMessage(
                    conversationId = convoId,
                    senderDomain = "alice.test",
                    userDateMs = it,
                )
            }

            service.markAsReadByFiles(convoId, ids)

            assertEquals(300L, fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId))
            assertEquals(300L, fixture.localLastReadUpdater.calls.single().newLastReadTime.milliseconds)
        }
    }

    @Test
    fun markAsReadByFiles_pickMaxUserDate_messageIdsInArbitraryOrder() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()

            val ids = listOf(100L, 200L, 300L).map {
                fixture.seedMessage(
                    conversationId = convoId,
                    senderDomain = "alice.test",
                    userDateMs = it,
                )
            }

            // Pass the ids in reverse — max-userDate resolution must not depend on input order.
            service.markAsReadByFiles(convoId, ids.reversed())

            assertEquals(300L, fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId))
        }
    }

    @Test
    fun markAsReadByFiles_pickMaxUserDate_ignoresFilteredOutRecordsEvenIfLater() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val peer = "alice.test"
            val self = fixture.testDomain

            // Peer-authored, unread — eligible. Max is 200.
            val m1 = fixture.seedMessage(conversationId = convoId, senderDomain = peer, userDateMs = 100L)
            val m2 = fixture.seedMessage(conversationId = convoId, senderDomain = peer, userDateMs = 200L)

            // Self-authored, userDate 500 — must NOT win.
            val mSelf = fixture.seedMessage(conversationId = convoId, senderDomain = self, userDateMs = 500L)

            // Peer-authored but already-read, userDate 400 — must NOT win.
            val mRead = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = peer,
                userDateMs = 400L,
                alreadyRead = true,
            )

            // Peer-authored but deleted, userDate 700 — must NOT win.
            val mDeleted = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = peer,
                userDateMs = 700L,
                isDeleted = true,
            )

            // Peer-authored but pending-send, userDate 800 — must NOT win.
            val mPending = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = peer,
                userDateMs = 800L,
                isPendingSend = true,
            )

            service.markAsReadByFiles(convoId, listOf(m1, m2, mSelf, mRead, mDeleted, mPending))

            assertEquals(
                200L,
                fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                "newReadTime must be the max over filtered-in records only, not the whole batch",
            )

            // The outbox payload must only contain the eligible (m1, m2) fileIds.
            val row = fixture.drainOutbox()
                .single { it.uploadType == DriveOutboxUploader.SendReadReceiptByFileIds }
            val payload = OdinSystemSerializer.deserialize<SendReadReceiptByFileIdsOutboxRequest>(
                row.json.decodeToString()
            )
            val eligibleFileIds = fixture.messageLookup.records
                .filter { it.id == m1 || it.id == m2 }
                .map { it.fileId }
                .toSet()
            assertEquals(eligibleFileIds, payload.fileIds.toSet())
        }
    }

    @Test
    fun markAsReadByFiles_sameNewReadTimeAppliedToAllDistinctConversations() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoA = Uuid.random()
            val convoB = Uuid.random()

            val mA = fixture.seedMessage(
                conversationId = convoA, senderDomain = "alice.test", userDateMs = 300L,
            )
            val mB = fixture.seedMessage(
                conversationId = convoB, senderDomain = "bob.test", userDateMs = 500L,
            )

            service.markAsReadByFiles(convoA, listOf(mA, mB))

            // Current behavior: newReadTime is the GLOBAL max across the batch (500)
            // and the same value is applied to every distinct conversation. Pinning
            // this so that a future per-conversation-max refactor is intentional.
            assertEquals(500L, fixture.dbm.chatReadCount.selectLastReadTimeMs(convoA))
            assertEquals(500L, fixture.dbm.chatReadCount.selectLastReadTimeMs(convoB))
        }
    }

    @Test
    fun markAsReadByFiles_enrichesTargetConversation() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val id = fixture.seedMessage(
                conversationId = convoId, senderDomain = "alice.test", userDateMs = 100L,
            )

            service.markAsReadByFiles(convoId, listOf(id))

            assertEquals(listOf(convoId), fixture.unreadCountEnricher.calls)
            assertEquals(listOf(convoId), fixture.localLastReadUpdater.calls.map { it.conversationId })
        }
    }

    @Test
    fun markAsReadByFiles_noUnreadRecords_throws() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()

            // Only self-authored — all filtered out, unreadRecords ends up empty.
            val id = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = fixture.testDomain,
                userDateMs = 100L,
            )

            // Pin the latent bug: `unreadRecords.maxOf { ... }` throws on empty input.
            // If/when markAsReadByFiles guards against empty lists, update this test
            // intentionally rather than letting the fix go unnoticed.
            assertFailsWith<NoSuchElementException> {
                service.markAsReadByFiles(convoId, listOf(id))
            }

            // The pre-filter state is unchanged — nothing enqueued, no enrichment.
            assertTrue(fixture.drainOutbox().isEmpty())
            assertTrue(fixture.unreadCountEnricher.calls.isEmpty())
            assertTrue(fixture.localLastReadUpdater.calls.isEmpty())
        }
    }
}
