package id.homebase.chat.services

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.drives.files.DeleteFilesBatchRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SendReadReceiptByFileIdsOutboxRequest
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ChatMessageActionService.markAsReadByFiles].
 *
 * Two distinct concerns:
 *   - **Receipt outbox**: only peer-authored, unread, non-deleted, non-pending records
 *     get a read receipt enqueued.
 *   - **Local lastReadTime**: advances to cover anything the user has actually viewed —
 *     including self-authored and already-read messages — so the unread count reflects
 *     reality even in Note-to-Self conversations. Only deleted/pending-send records are
 *     excluded from the date computation (deleted aren't shown; pending-send userDates
 *     are unreliable).
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
    fun markAsReadByFiles_pickMaxUserDate_excludesDeletedAndPendingButIncludesViewedSelfAndRead() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val peer = "alice.test"
            val self = fixture.testDomain

            // Peer-authored, unread — eligible for receipt.
            val m1 = fixture.seedMessage(conversationId = convoId, senderDomain = peer, userDateMs = 100L)
            val m2 = fixture.seedMessage(conversationId = convoId, senderDomain = peer, userDateMs = 200L)

            // Self-authored, userDate 500 — viewed (advances local lastReadTime), not receipted.
            val mSelf = fixture.seedMessage(conversationId = convoId, senderDomain = self, userDateMs = 500L)

            // Peer-authored but already-read, userDate 400 — viewed, not receipted.
            val mRead = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = peer,
                userDateMs = 400L,
                alreadyRead = true,
            )

            // Peer-authored but deleted, userDate 700 — must NOT advance lastReadTime.
            val mDeleted = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = peer,
                userDateMs = 700L,
                isDeleted = true,
            )

            // Peer-authored but pending-send, userDate 800 — must NOT advance lastReadTime.
            val mPending = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = peer,
                userDateMs = 800L,
                isPendingSend = true,
            )

            service.markAsReadByFiles(convoId, listOf(m1, m2, mSelf, mRead, mDeleted, mPending))

            assertEquals(
                500L,
                fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                "newReadTime must be the max over viewed records (excluding deleted/pending-send only) — " +
                        "self-authored & already-read still mark 'I've read up to here'",
            )

            // The outbox payload must only contain the receipt-eligible (m1, m2) fileIds.
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
    fun markAsReadByFiles_enrichesTargetConversation() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val id = fixture.seedMessage(
                conversationId = convoId, senderDomain = "alice.test", userDateMs = 100L,
            )

            service.markAsReadByFiles(convoId, listOf(id))

            assertEquals(listOf(convoId), fixture.unreadCountEnricher.calls.map { it.conversationId })
            assertEquals(listOf(convoId), fixture.localLastReadUpdater.calls.map { it.conversationId })
        }
    }

    // ----- deleteMessage propagation contract -----
    //
    // Pins what `deleteMessage` puts on the outbox so that "Delete for everyone"
    // carries the other participants as recipients (which is what tells the
    // sync engine to fan the deletion out to peer drives), and "Delete for me"
    // does NOT carry recipients. A regression on either side has been observed
    // in the wild as "I deleted the message and the other person still sees it",
    // so this is the cheap layer that catches it before runtime.

    @Test
    fun deleteMessage_deleteForEveryone_enqueuesDeleteRequestWithOtherParticipantsAsRecipients() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val peer = "frodo.test"
            val convoId = fixture.seedOneOnOneConversation(other = peer)
            val fileId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                senderDomain = fixture.testDomain,
                fileId = fileId,
            )

            service.deleteMessage(messageId, deleteForEveryone = true)

            val row = fixture.drainOutbox()
                .single { it.uploadType == DriveOutboxUploader.DeleteFile }
            val payload = OdinSystemSerializer.deserialize<DeleteLocalFilesByFileIdRequest>(
                row.json.decodeToString()
            )

            val recipients = assertNotNull(
                payload.recipients,
                "recipients must not be null for 'Delete for everyone' — null means the sync engine won't fan the deletion out to peer drives",
            )
            assertEquals(
                listOf(OdinId(peer)),
                recipients,
                "recipients must be the conversation participants minus self",
            )
            assertEquals(listOf(fileId), payload.fileIds)
            assertEquals(false, payload.hardDelete)
        }
    }

    @Test
    fun deleteMessage_deleteForMe_enqueuesDeleteRequestWithoutRecipients() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val peer = "frodo.test"
            val convoId = fixture.seedOneOnOneConversation(other = peer)
            val fileId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                senderDomain = fixture.testDomain,
                fileId = fileId,
            )

            service.deleteMessage(messageId, deleteForEveryone = false)

            val row = fixture.drainOutbox()
                .single { it.uploadType == DriveOutboxUploader.DeleteFile }
            val payload = OdinSystemSerializer.deserialize<DeleteLocalFilesByFileIdRequest>(
                row.json.decodeToString()
            )

            // "Delete for me" must NOT carry recipients — propagating it would
            // unilaterally erase the message from the other participant's drive.
            assertNull(payload.recipients, "recipients must be null for 'Delete for me'")
            assertEquals(listOf(fileId), payload.fileIds)
            assertEquals(false, payload.hardDelete)
        }
    }

    // ----- deleteMessage WIRE-LEVEL contract -----
    //
    // The outbox-row tests above pin what we WRITE INTO the outbox. These tests
    // pin what the DriveOutboxUploader actually puts ON THE WIRE when the row
    // drains. The live propagation bug ("I delete-for-everyone, peer still sees
    // it") could in principle live in any of: (a) outbox row JSON wrong, (b)
    // dispatcher routes to the wrong uploader method, (c) wire DTO drops/renames
    // recipients between DeleteLocalFilesByFileIdRequest and DeleteFilesBatchRequest,
    // (d) endpoint URL changes. (a) is covered above; these tests cover (b)–(d)
    // by capturing the actual HTTP request and decrypting its body.
    //
    // If both layers stay green and the live bug persists, the failure is
    // server-side fan-out, not anything the client controls.

    @Test
    fun deleteMessage_deleteForEveryone_drainsOutboxToHttpRequestWithRecipientsOnTheWire() = runTest {
        ChatMessageActionServiceTestFixture(captureHttp = true).use { fixture ->
            val service = fixture.build(scope = this, outboxScope = backgroundScope)
            val peer = "frodo.test"
            val convoId = fixture.seedOneOnOneConversation(other = peer)
            val fileId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                senderDomain = fixture.testDomain,
                fileId = fileId,
            )

            service.deleteMessage(messageId, deleteForEveryone = true)
            fixture.drainOutboxAndAwaitHttp(this)

            val request = fixture.capturedRequests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(
                request.url.encodedPath.endsWith("/files/delete-batch/by-file-id"),
                "delete must POST to /files/delete-batch/by-file-id but went to ${request.url.encodedPath}",
            )

            // The body is encrypted with the shared secret — decrypt and assert
            // the plaintext wire shape. A regression that drops `recipients`
            // between the outbox DTO and the wire DTO produces null here.
            val ciphertextJson = (request.body as TextContent).text
            val payload: DeleteFilesBatchRequest =
                CryptoHelper.decryptContent(ciphertextJson, fixture.sharedSecretBytes)

            val req = payload.requests.single()
            assertEquals(fileId, req.fileId)
            val recipients = assertNotNull(
                req.recipients,
                "recipients must survive serialization onto the wire — null is the live-bug shape",
            )
            assertEquals(listOf(OdinId(peer)), recipients)
        }
    }

    @Test
    fun deleteMessage_deleteForMe_drainsOutboxToHttpRequestWithoutRecipientsOnTheWire() = runTest {
        ChatMessageActionServiceTestFixture(captureHttp = true).use { fixture ->
            val service = fixture.build(scope = this, outboxScope = backgroundScope)
            val peer = "frodo.test"
            val convoId = fixture.seedOneOnOneConversation(other = peer)
            val fileId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                senderDomain = fixture.testDomain,
                fileId = fileId,
            )

            service.deleteMessage(messageId, deleteForEveryone = false)
            fixture.drainOutboxAndAwaitHttp(this)

            val request = fixture.capturedRequests.single()
            val ciphertextJson = (request.body as TextContent).text
            val payload: DeleteFilesBatchRequest =
                CryptoHelper.decryptContent(ciphertextJson, fixture.sharedSecretBytes)

            val req = payload.requests.single()
            assertEquals(fileId, req.fileId)
            // "Delete for me" must NOT carry recipients on the wire either —
            // a future change that flips this would unilaterally fan a
            // local-only delete out to the peer's drive.
            assertNull(req.recipients, "recipients must be null on the wire for 'Delete for me'")
        }
    }

    @Test
    fun markAsReadByFiles_onlySelfAuthored_skipsOutboxButAdvancesLocalReadState() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()

            // Only self-authored — nothing to receipt to a peer.
            // Realistic: Note-to-Self conversations, where every visible message is
            // self-authored. The local lastReadTime must still advance, otherwise the
            // unread count sticks at whatever stale value the DB has.
            val id = fixture.seedMessage(
                conversationId = convoId,
                senderDomain = fixture.testDomain,
                userDateMs = 100L,
            )

            service.markAsReadByFiles(convoId, listOf(id))

            // No peer to receipt to — outbox stays empty.
            assertTrue(fixture.drainOutbox().isEmpty())

            // But we did view the message, so local read state must advance.
            assertEquals(100L, fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId))
            assertEquals(listOf(convoId), fixture.unreadCountEnricher.calls.map { it.conversationId })
            assertEquals(listOf(convoId), fixture.localLastReadUpdater.calls.map { it.conversationId })
        }
    }
}
