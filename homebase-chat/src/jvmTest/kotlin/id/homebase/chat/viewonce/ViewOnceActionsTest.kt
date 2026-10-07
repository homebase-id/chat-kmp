package id.homebase.chat.viewonce

import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.SetReactionsOutboxRequest
import id.homebase.api.serialization.OutboxSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.Outbox
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.mapToMessageData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The real action service, optimistic writer and outbox over an in-memory database. */
class ViewOnceActionsTest {

    private val now = Clock.System.now().toEpochMilliseconds()

    private class Scenario(
        val fixture: ChatMessageActionServiceTestFixture,
        val service: ChatMessageActionService,
        val actions: ViewOnceActions,
        val conversationId: Uuid,
        val messageId: Uuid,
        val fileId: Uuid,
    )

    private suspend fun scenario(
        scope: TestScope,
        fixture: ChatMessageActionServiceTestFixture,
        createdMs: Long = now - DAY_MS,
        author: String = VO_SENDER,
    ): Scenario {
        val service = fixture.build(scope = scope)
        val conversationId = fixture.seedOneOnOneConversation(other = VO_SENDER)
        val messageId = Uuid.random()
        val fileId = Uuid.random()
        val header = viewOnceHeader(
            fileId = fileId,
            uniqueId = messageId,
            conversationId = conversationId,
            author = author,
            createdMs = createdMs,
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(
            fixture.dbm,
            MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)
                .convertFileHeaderToDriveMainIndexRecord(fixture.testIdentityId, fixture.chatDriveId, header),
        )
        fixture.seedMessage(
            conversationId = conversationId,
            senderDomain = author,
            userDateMs = createdMs,
            id = messageId,
            fileId = fileId,
        )
        return Scenario(
            fixture, service, ViewOnceActions(service, fixture.credentialsManager), conversationId, messageId, fileId,
        )
    }

    private suspend fun Scenario.stored(): MessageUiModel {
        val file = assertNotNull(
            fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId),
        )
        return assertNotNull(mapToMessageData(file, fixture.credentialsManager))
    }

    private suspend fun Scenario.state() =
        ViewOnceRules.stateOf(stored(), now, fixture.credentialsManager.requireActiveDomain())

    private val reactionRowKey = { messageId: Uuid ->
        ChatMessageActionService.reactionSetRowKey(messageId, ViewOnceSignal.OPENED_SCOPE)
    }

    private fun List<Outbox>.deletes() = filter { it.uploadType == DriveOutboxUploader.DeleteFile }

    @Test
    fun closingTheViewerQueuesTheOpenedSignalFirstThenALocalSoftDelete() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)

            assertTrue(!s.actions.isConsumed(s.messageId))
            s.actions.onViewerClosed(s.stored())
            assertTrue(s.actions.isConsumed(s.messageId))

            val reactionRow = assertNotNull(
                fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactionRowKey(s.messageId)),
            )
            assertEquals(DriveOutboxUploader.SetReactions, reactionRow.uploadType)
            val signal = OutboxSerializer.decode<SetReactionsOutboxRequest>(reactionRow)
            assertEquals(1, signal.add.size)
            assertTrue(signal.add.single().contains(ViewOnceSignal.OPENED_CODE))
            assertEquals(2L, fixture.dbm.outbox.count(), "one reaction row and one delete row")

            val first = fixture.drainOutbox()
            assertEquals(
                listOf(DriveOutboxUploader.SetReactions), first.map { it.uploadType },
                "the delete must wait for the opened signal to leave the outbox",
            )
            fixture.dbm.outbox.deleteByRowId(first.single().rowId)
            val deleteRow = fixture.drainOutbox().deletes().single()
            assertEquals(reactionRowKey(s.messageId), deleteRow.dependencyUniqueId)
            val delete = OutboxSerializer.decode<DeleteLocalFilesByFileIdRequest>(deleteRow)
            assertEquals(null, delete.recipients)
            assertEquals(false, delete.hardDelete)
            assertEquals(listOf(s.fileId), delete.fileIds)
        }
    }

    @Test
    fun theLocalRowReadsOpenedAtOnceAndAfterTheTombstoneLands() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            assertEquals(ViewOnceState.Unopened, s.state())

            s.service.setReactions(s.conversationId, s.messageId, ViewOnceSignal.openedChange())
            assertEquals(ViewOnceState.Opened, s.state(), "own _vo flips the bubble before any delete")

            s.actions.onViewerClosed(s.conversationId, s.messageId)
            val tombstone = s.stored()
            assertTrue(tombstone.isDeleted)
            assertNull(tombstone.payloads)
            assertEquals(ViewOnceState.Opened, s.state())
        }
    }

    @Test
    fun aCrashBetweenTheTwoEnqueuesIsRepairedWithExactlyOneDelete() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            s.service.setReactions(s.conversationId, s.messageId, ViewOnceSignal.openedChange())
            val stale = s.stored()
            assertTrue(!stale.isDeleted)

            val restarted = ViewOnceActions(s.service, fixture.credentialsManager)
            restarted.sweep(listOf(stale), now)
            restarted.sweep(listOf(stale), now)
            ViewOnceActions(s.service, fixture.credentialsManager).sweep(listOf(stale), now)

            fixture.dbm.outbox.deleteBy(fixture.chatDriveId, reactionRowKey(s.messageId))
            assertEquals(1, fixture.drainOutbox().deletes().size)
        }
    }

    @Test
    fun anUnopenedCopyExpiresAtThirtyDaysAndNotOneMillisecondBefore() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val created = now - ViewOnceRules.MAX_LIFESPAN_MS
            val s = scenario(this, fixture, createdMs = created)
            val model = s.stored()

            s.actions.sweep(listOf(model), created + ViewOnceRules.MAX_LIFESPAN_MS - 1)
            assertTrue(fixture.drainOutbox().isEmpty(), "29d 23h 59m: nothing yet")

            s.actions.sweep(listOf(model), created + ViewOnceRules.MAX_LIFESPAN_MS)
            val rows = fixture.drainOutbox()
            assertEquals(1, rows.deletes().size)
            assertTrue(rows.none { it.uploadType == DriveOutboxUploader.SetReactions }, "an expiry never signals opened")
            assertTrue(s.stored().isDeleted)
            assertEquals(ViewOnceState.Expired, s.state())
        }
    }

    @Test
    fun theSweepNeverTouchesTheSendersOwnMessages() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(
                this, fixture,
                createdMs = now - ViewOnceRules.MAX_LIFESPAN_MS - DAY_MS,
                author = VO_OWNER,
            )
            val own = s.stored()

            s.actions.sweep(listOf(own), now)

            assertTrue(fixture.drainOutbox().isEmpty())
            assertTrue(!s.stored().isDeleted)
        }
    }

    @Test
    fun aFreshUnopenedCopyIsLeftAlone() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            s.actions.sweep(listOf(s.stored()), now)
            assertTrue(fixture.drainOutbox().isEmpty())
        }
    }
}
