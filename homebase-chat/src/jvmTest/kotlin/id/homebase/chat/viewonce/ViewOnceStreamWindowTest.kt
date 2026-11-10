package id.homebase.chat.viewonce

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatMessagesData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.testChatMessageStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The recipient's open conversation window, through the real stream, across the strip and its server echo. */
class ViewOnceStreamWindowTest {

    private val now = Clock.System.now().toEpochMilliseconds()
    private val createdMs = now - DAY_MS

    private class Scenario(
        val fixture: ChatMessageActionServiceTestFixture,
        val actions: ViewOnceActions,
        val stream: ChatMessageStream,
        val conversationId: Uuid,
        val messageId: Uuid,
        val fileId: Uuid,
    )

    private suspend fun TestScope.withScenario(block: suspend Scenario.() -> Unit) =
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedOneOnOneConversation(other = VO_SENDER)
            val messageId = Uuid.random()
            val fileId = Uuid.random()
            val header = viewOnceHeader(fileId = fileId, uniqueId = messageId, conversationId = conversationId, createdMs = createdMs)
            MainIndexMetaHelpers.upsertDriveMainIndex(
                fixture.dbm,
                MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)
                    .convertFileHeaderToDriveMainIndexRecord(fixture.testIdentityId, fixture.chatDriveId, header),
            )
            fixture.seedMessage(conversationId, VO_SENDER, createdMs, id = messageId, fileId = fileId)
            // Its own job, joined before the database closes: the stream's pinned-bar refresh reads after each batch.
            val streamJob = Job()
            val stream = testChatMessageStream(
                fixture.dbm,
                fixture.credentialsManager,
                fixture.eventBus,
                OptimisticWriter(fixture.credentialsManager, fixture.dbm, fixture.eventBus, fixture.outboxSync),
                CoroutineScope(Dispatchers.Default + streamJob),
            )
            try {
                stream.loadConversation(conversationId)
                Scenario(fixture, ViewOnceActions(service, fixture.credentialsManager), stream, conversationId, messageId, fileId)
                    .block()
            } finally {
                streamJob.cancelAndJoin()
            }
        }

    /** The window once [settled] holds; the message is null when the window lost it. */
    private suspend fun Scenario.windowOnce(settled: (MessageUiModel?) -> Boolean): MessageUiModel? =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                val data = stream.observeMessages(conversationId).first { data ->
                    data is ChatMessagesData.Messages && settled(data.window.messages.firstOrNull { it.id == messageId })
                }
                (data as ChatMessagesData.Messages).window.messages.firstOrNull { it.id == messageId }
            }
        }

    private suspend fun Scenario.assertSpentOpened(message: MessageUiModel?) {
        val spent = assertNotNull(message, "the recipient's view-once bubble left the conversation window")
        assertTrue(spent.payloads.isNullOrEmpty(), "the media is stripped")
        assertNotNull((spent.messageContent as? MessageContent.ViewOnce)?.descriptor, "the chip still knows its kind")
        assertEquals(ViewOnceState.Opened, ViewOnceRules.stateOf(spent, now, fixture.credentialsManager.requireActiveDomain()))
        assertEquals(Instant.fromEpochMilliseconds(createdMs), spent.userDate, "the bubble keeps its place in the thread")
    }

    private val stripped = { m: MessageUiModel? -> m == null || m.payloads.isNullOrEmpty() }

    @Test
    fun closingTheViewerKeepsTheSpentBubbleInTheOpenWindow() = runTest {
        withScenario {
            assertNotNull(windowOnce { it != null })

            actions.onViewerClosed(conversationId, messageId)

            assertSpentOpened(windowOnce(stripped))
            assertSpentOpened(stream.fetchMessages(conversationId).records.firstOrNull { it.id == messageId })
        }
    }

    @Test
    fun reactingInTheViewerThenClosingKeepsTheSpentBubble() = runTest {
        withScenario {
            actions.onReact(conversationId, messageId, "👍")
            actions.onViewerClosed(conversationId, messageId)

            val spent = windowOnce(stripped)
            assertSpentOpened(spent)
            assertTrue("👍" in spent!!.ownReactions)
        }
    }

    @Test
    fun theServerEchoOfTheStripWithNoTransitCreatedKeepsTheSpentBubbleInPlace() = runTest {
        withScenario {
            actions.onViewerClosed(conversationId, messageId)
            windowOnce(stripped)

            val echo = viewOnceHeader(
                fileId = fileId,
                uniqueId = messageId,
                conversationId = conversationId,
                createdMs = createdMs,
                updatedMs = now + 60_000,
                // odin-core's metadata-only update (OverwriteMetadataInternal) does not carry transitCreated over.
                transitCreatedMs = 0,
                content = """{\"kind\":\"image\",\"schemaVersion\":1}""",
                payloadsJson = "[]",
                localReactions = listOf("""{"emoji":"_vo"}"""),
                reactionPreviewJson = """{"reactions":{"k":{"key":"k","count":1,"reactionContent":"{\"emoji\":\"_vo\"}"}},"comments":[],"totalCommentCount":0}""",
            )
            val written = MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)
                .baseUpsertEntryZapZap(fixture.testIdentityId, fixture.chatDriveId, listOf(echo), cursor = null)
            assertEquals(1, written.size, "the echo is newer than the optimistic row")
            fixture.eventBus.emit(BackendEvent.DataEvent.BatchReceived(fixture.chatDriveId, written))

            assertSpentOpened(windowOnce { it != null && !it.isPendingSend })
            assertSpentOpened(stream.fetchMessages(conversationId).records.firstOrNull { it.id == messageId })
        }
    }
}
