package id.homebase.chat.viewonce

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.SetReactionsOutboxRequest
import id.homebase.api.client.drives.upload.PayloadOperationType
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.serialization.OutboxSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.Outbox
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.mapToMessageData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun List<Outbox>.strips() = filter { it.uploadType == DriveOutboxUploader.UpdateFile }

    private fun Scenario.assertStripsOnlyTheMedia(row: Outbox) {
        assertEquals(ChatMessageActionService.viewOnceStripRowKey(messageId), row.uniqueId)
        val request = OutboxSerializer.decode<UpdateFileByUniqueIdRequest>(row)
        assertEquals(messageId, request.uniqueId)
        assertEquals(UpdateLocale.Local, request.instructions.locale)
        assertTrue(request.instructions.recipients.isEmpty(), "the strip never reaches the sender's copy")
        assertFalse(request.metadata.allowDistribution)
        assertEquals(
            listOf(PayloadOperationType.DeletePayload to VIEW_ONCE_PAYLOAD_KEY),
            request.instructions.manifest.payloadDescriptors.orEmpty().map { it.operationType to it.payloadKey },
        )
        assertTrue(request.payloads.isNullOrEmpty())
    }

    @Test
    fun closingTheViewerQueuesTheOpenedSignalFirstThenALocalMediaStrip() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)

            assertTrue(!s.actions.isConsumed(s.messageId))
            s.actions.onViewerClosed(s.conversationId, s.messageId)
            assertTrue(s.actions.isConsumed(s.messageId))

            val reactionRow = assertNotNull(
                fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactionRowKey(s.messageId)),
            )
            assertEquals(DriveOutboxUploader.SetReactions, reactionRow.uploadType)
            val signal = OutboxSerializer.decode<SetReactionsOutboxRequest>(reactionRow)
            assertEquals(1, signal.add.size)
            assertTrue(signal.add.single().contains(ViewOnceSignal.OPENED_CODE))
            assertEquals(2L, fixture.dbm.outbox.count(), "one reaction row and one strip row")

            val first = fixture.drainOutbox()
            assertEquals(
                listOf(DriveOutboxUploader.SetReactions), first.map { it.uploadType },
                "the strip must wait for the opened signal to leave the outbox",
            )
            fixture.dbm.outbox.deleteByRowId(first.single().rowId)
            val stripRow = fixture.drainOutbox().strips().single()
            assertEquals(reactionRowKey(s.messageId), stripRow.dependencyUniqueId)
            s.assertStripsOnlyTheMedia(stripRow)
        }
    }

    @Test
    fun theOpenedCopyStaysAnUndeletedMessageWithNoMediaOrCaption() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            assertEquals(ViewOnceState.Unopened, s.state())

            s.service.setReactions(s.conversationId, s.messageId, ViewOnceSignal.openedChange())
            assertEquals(ViewOnceState.Opened, s.state(), "own _vo flips the bubble before the strip")

            s.actions.onViewerClosed(s.conversationId, s.messageId)
            val opened = s.stored()
            assertFalse(opened.isDeleted)
            assertTrue(opened.payloads.isNullOrEmpty())
            val descriptor = assertNotNull((opened.messageContent as MessageContent.ViewOnce).descriptor)
            assertEquals(ViewOnceDescriptor.KIND_IMAGE, descriptor.kind)
            assertNull(descriptor.caption)
            assertTrue(ViewOnceSignal.OPENED_CODE in opened.ownReactions)
            assertEquals(ViewOnceState.Opened, s.state())
            assertNull(viewOnceViewerFor(opened, now, fixture.credentialsManager.requireActiveDomain(), mobile = true))
        }
    }

    @Test
    fun aCrashBetweenTheTwoEnqueuesIsRepairedWithExactlyOneStrip() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            s.service.setReactions(s.conversationId, s.messageId, ViewOnceSignal.openedChange())
            val stale = s.stored()
            assertFalse(stale.payloads.isNullOrEmpty())

            val restarted = ViewOnceActions(s.service, fixture.credentialsManager)
            restarted.sweep(listOf(stale), now)
            restarted.sweep(listOf(stale), now)
            ViewOnceActions(s.service, fixture.credentialsManager).sweep(listOf(stale), now)

            fixture.dbm.outbox.deleteBy(fixture.chatDriveId, reactionRowKey(s.messageId))
            val stripRow = fixture.drainOutbox().strips().single()
            assertEquals(reactionRowKey(s.messageId), stripRow.dependencyUniqueId, "the re-enqueued strip waits for the _vo row")
            s.assertStripsOnlyTheMedia(stripRow)
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
            s.assertStripsOnlyTheMedia(rows.strips().single())
            assertNull(rows.strips().single().dependencyUniqueId)
            assertTrue(rows.none { it.uploadType == DriveOutboxUploader.SetReactions }, "an expiry never signals opened")
            assertFalse(s.stored().isDeleted)
            assertTrue(s.stored().payloads.isNullOrEmpty())
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
            assertFalse(s.stored().payloads.isNullOrEmpty())
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

    private fun Scenario.shotKey() =
        ChatMessageActionService.reactionSetRowKey(messageId, ViewOnceSignal.SCREENSHOT_SCOPE)

    @Test
    fun aSecondScreenshotInTheSessionEnqueuesNothingEvenOnceTheFirstRowHasDrained() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            val message = s.stored()

            s.actions.onScreenshot(message.conversationId, message.id)
            val shotRow = assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, s.shotKey()))
            assertEquals(DriveOutboxUploader.SetReactions, shotRow.uploadType)
            assertTrue(
                OutboxSerializer.decode<SetReactionsOutboxRequest>(shotRow).add.single()
                    .contains(ViewOnceSignal.SCREENSHOT_CODE),
            )

            fixture.dbm.outbox.deleteByRowId(shotRow.rowId)
            assertEquals(0L, fixture.dbm.outbox.count())

            s.actions.onScreenshot(message.conversationId, message.id)
            assertEquals(0L, fixture.dbm.outbox.count(), "the guard, not outbox replacement, stops the repeat")
        }
    }

    @Test
    fun aFailedScreenshotEnqueueFreesALaterScreenshotToRetry() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)

            s.actions.onScreenshot(Uuid.random(), s.messageId)
            assertEquals(0L, fixture.dbm.outbox.count(), "the enqueue failed, nothing queued")

            s.actions.onScreenshot(s.conversationId, s.messageId)
            assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, s.shotKey()))
            assertEquals(1L, fixture.dbm.outbox.count())
        }
    }

    @Test
    fun theCloseQueuesOpenedWithoutClobberingAPendingScreenshotSignal() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            val message = s.stored()

            s.actions.onScreenshot(message.conversationId, message.id)
            s.actions.onViewerClosed(message.conversationId, message.id)

            val openedRow = assertNotNull(
                fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactionRowKey(s.messageId)),
            )
            assertTrue(OutboxSerializer.decode<SetReactionsOutboxRequest>(openedRow).add.single().contains(ViewOnceSignal.OPENED_CODE))
            assertNotNull(
                fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, s.shotKey()),
                "the opened signal must not clobber the pending screenshot signal",
            )
            assertEquals(3L, fixture.dbm.outbox.count(), "screenshot, opened and strip rows")
        }
    }

    private fun reactRowKey(messageId: Uuid) =
        ChatMessageActionService.reactionSetRowKey(messageId, ViewOnceSignal.REACTION_SCOPE)

    @Test
    fun reactThenCloseChainsEmojiThenOpenedThenStrip_soTheStripCannotOvertakeTheEmoji() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)

            s.actions.onReact(s.conversationId, s.messageId, "\uD83D\uDC4D")
            s.actions.onViewerClosed(s.conversationId, s.messageId)

            val emojiRow = assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactRowKey(s.messageId)))
            val request = OutboxSerializer.decode<SetReactionsOutboxRequest>(emojiRow)
            assertEquals(1, request.add.size)
            assertTrue(request.add.single().contains("\uD83D\uDC4D"))
            assertTrue(request.remove.isEmpty(), "add-only: the viewer never removes a reaction")

            val openedRow = assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactionRowKey(s.messageId)))
            assertEquals(reactRowKey(s.messageId), openedRow.dependencyUniqueId, "_vo waits for the emoji row")
            assertEquals(3L, fixture.dbm.outbox.count())

            val first = fixture.drainOutbox()
            assertEquals(listOf(emojiRow.rowId), first.map { it.rowId }, "only the emoji row is runnable first")
            fixture.dbm.outbox.deleteByRowId(emojiRow.rowId)
            val second = fixture.drainOutbox()
            assertEquals(listOf(openedRow.rowId), second.map { it.rowId })
            fixture.dbm.outbox.deleteByRowId(openedRow.rowId)
            val stripRow = fixture.drainOutbox().strips().single()
            assertEquals(reactionRowKey(s.messageId), stripRow.dependencyUniqueId, "the strip waits for _vo, which waited for the emoji")
        }
    }

    @Test
    fun screenshotThenCloseChainsTheShotBeforeOpenedAndTheStrip_soTheStripCannotDrainFirst() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)

            s.actions.onScreenshot(s.conversationId, s.messageId)
            s.actions.onViewerClosed(s.conversationId, s.messageId)

            val shotRow = assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, s.shotKey()))
            val openedRow = assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactionRowKey(s.messageId)))
            assertEquals(s.shotKey(), openedRow.dependencyUniqueId, "_vo waits for the _vs row")

            assertEquals(listOf(shotRow.rowId), fixture.drainOutbox().map { it.rowId }, "only _vs is runnable first")
            fixture.dbm.outbox.deleteByRowId(shotRow.rowId)
            assertEquals(listOf(openedRow.rowId), fixture.drainOutbox().map { it.rowId })
            fixture.dbm.outbox.deleteByRowId(openedRow.rowId)
            assertEquals(1, fixture.drainOutbox().strips().size)
        }
    }

    @Test
    fun screenshotReactAndCloseDrainInTheOrderTheyHappened() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)

            s.actions.onReact(s.conversationId, s.messageId, "\uD83D\uDC4D")
            s.actions.onScreenshot(s.conversationId, s.messageId)
            s.actions.onViewerClosed(s.conversationId, s.messageId)
            assertEquals(4L, fixture.dbm.outbox.count())

            val order = mutableListOf<Long>()
            while (true) {
                val runnable = fixture.drainOutbox()
                if (runnable.isEmpty()) break
                assertEquals(1, runnable.size, "every row waits for the one before it")
                order += runnable.single().uploadType
                if (runnable.single().uploadType == DriveOutboxUploader.SetReactions) {
                    val add = OutboxSerializer.decode<SetReactionsOutboxRequest>(runnable.single()).add.single()
                    order[order.lastIndex] = when {
                        add.contains(ViewOnceSignal.SCREENSHOT_CODE) -> SHOT
                        add.contains(ViewOnceSignal.OPENED_CODE) -> OPENED
                        else -> EMOJI
                    }
                }
                fixture.dbm.outbox.deleteByRowId(runnable.single().rowId)
            }
            assertEquals(listOf(EMOJI, SHOT, OPENED, DriveOutboxUploader.UpdateFile), order)
        }
    }

    @Test
    fun aSecondEmojiInTheSameViewingIsIgnoredSoThePendingRowIsNeverReplaced() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val s = scenario(this, fixture)
            s.actions.onReact(s.conversationId, s.messageId, "\uD83D\uDC4D")
            s.actions.onReact(s.conversationId, s.messageId, "\u2764\uFE0F")

            val row = assertNotNull(fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, reactRowKey(s.messageId)))
            assertTrue(OutboxSerializer.decode<SetReactionsOutboxRequest>(row).add.single().contains("\uD83D\uDC4D"))
            assertEquals(1L, fixture.dbm.outbox.count())
        }
    }

    private companion object {
        const val EMOJI = -1L
        const val SHOT = -2L
        const val OPENED = -3L
    }
}
